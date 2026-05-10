package com.sv21c.jsplayer

import android.content.Context
import android.util.Log
import org.jupnp.android.AndroidUpnpService
import org.jupnp.controlpoint.ActionCallback
import org.jupnp.model.action.ActionInvocation
import org.jupnp.model.message.UpnpResponse
import org.jupnp.model.meta.Device
import org.jupnp.model.meta.RemoteDevice
import org.jupnp.model.meta.Service
import org.jupnp.model.types.UDAServiceType
import org.jupnp.model.types.UnsignedIntegerFourBytes
import org.jupnp.registry.DefaultRegistryListener
import org.jupnp.registry.Registry
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.wifi.WifiManager
import android.os.IBinder
import java.util.Locale

/**
 * DLNA Digital Media Renderer(DMR)를 검색하고 제어하는 매니저.
 * AVTransport 서비스를 가진 디바이스를 검색하여 영상 재생 명령을 전송합니다.
 */
class DLNARendererManager(
    private val context: Context,
    private val onRendererAdded: (Device<*, *, *>) -> Unit,
    private val onRendererRemoved: (Device<*, *, *>) -> Unit
) {
    companion object {
        private const val TAG = "DLNARendererManager"
    }

    private var upnpService: AndroidUpnpService? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var isServiceBound = false
    private var pendingSearch = false

    private val registryListener = object : DefaultRegistryListener() {
        override fun remoteDeviceAdded(registry: Registry, device: RemoteDevice) {
            val avTransport = device.findService(UDAServiceType("AVTransport"))
            if (avTransport != null) {
                Log.d(TAG, ">>> DMR 발견: ${device.details?.friendlyName ?: device.displayString} (${device.identity.udn})")
                onRendererAdded(device)
            }
        }

        override fun remoteDeviceRemoved(registry: Registry, device: RemoteDevice) {
            val avTransport = device.findService(UDAServiceType("AVTransport"))
            if (avTransport != null) {
                Log.d(TAG, "<<< DMR 제거됨: ${device.displayString}")
                onRendererRemoved(device)
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            Log.d(TAG, "UPnP Service 연결됨 (렌더러 검색용)")
            val binder = service as AndroidUpnpService
            upnpService = binder
            isServiceBound = true

            binder.registry.addListener(registryListener)

            // 이미 발견된 디바이스 중 DMR 필터링
            binder.registry?.let { registry ->
                for (device in registry.devices) {
                    val avTransport = device.findService(UDAServiceType("AVTransport"))
                    if (avTransport != null) {
                        onRendererAdded(device)
                    }
                }
            }

            if (pendingSearch) {
                pendingSearch = false
                search()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.d(TAG, "UPnP Service 연결 해제됨")
            upnpService = null
            isServiceBound = false
        }
    }

    fun start() {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("RendererMulticastLock").apply {
            setReferenceCounted(true)
            acquire()
        }

        context.bindService(
            Intent(context, MyUpnpService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    fun stop() {
        try {
            upnpService?.registry?.removeListener(registryListener)
        } catch (e: Exception) {
            Log.e(TAG, "리스너 제거 오류", e)
        }
        try {
            if (isServiceBound) {
                context.unbindService(serviceConnection)
                isServiceBound = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "서비스 언바인드 오류", e)
        }
        multicastLock?.let {
            if (it.isHeld) it.release()
        }
    }

    fun search() {
        Log.d(TAG, "DMR 검색 시작")
        val service = upnpService
        if (service == null || !isServiceBound) {
            Log.w(TAG, "서비스 미연결, 대기 중...")
            pendingSearch = true
            return
        }
        service.controlPoint?.search()
        try {
            service.controlPoint?.search(org.jupnp.model.message.header.STAllHeader())
            val type = org.jupnp.model.types.UDADeviceType("MediaRenderer", 1)
            service.controlPoint?.search(org.jupnp.model.message.header.UDADeviceTypeHeader(type))
        } catch (e: Exception) {
            Log.e(TAG, "DMR 검색 오류", e)
        }
    }

    /**
     * TV에 영상 URL 전송 (SetAVTransportURI → Play)
     */
    fun castToDevice(
        device: Device<*, *, *>,
        mediaUrl: String,
        title: String,
        subtitleUrl: String? = null,
        startPositionMs: Long = 0L,
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {}
    ) {
        val service = upnpService
        if (service == null || !isServiceBound) {
            onFailure("UPnP 서비스가 연결되지 않았습니다.")
            return
        }

        val avTransport = device.findService(UDAServiceType("AVTransport"))
        if (avTransport == null) {
            onFailure("AVTransport 서비스를 찾을 수 없습니다.")
            return
        }

        val metadata = buildDIDLMetadata(mediaUrl, title, subtitleUrl)
        Log.d(TAG, "SetAVTransportURI 전송: $mediaUrl (자막: $subtitleUrl)")

        // SetAVTransportURI 실행
        val setUriAction = org.jupnp.model.action.ActionInvocation(avTransport.getAction("SetAVTransportURI"))
        setUriAction.setInput("InstanceID", UnsignedIntegerFourBytes(0))
        setUriAction.setInput("CurrentURI", mediaUrl)
        setUriAction.setInput("CurrentURIMetaData", metadata)

        service.controlPoint.execute(object : ActionCallback(setUriAction) {
            override fun success(invocation: ActionInvocation<out Service<*, *>>) {
                Log.d(TAG, "SetAVTransportURI 성공!")
                // 많은 스마트TV가 SetAVTransportURI 직후 STOPPED 상태에서 직접적인 Seek를 거부할 수 있으므로,
                // 우선 Play를 실행한 뒤, 성공할 때까지 주기적으로 Seek 명령을 보냅니다.
                executePlay(device, onSuccess = {
                    if (startPositionMs > 0L) {
                        Thread {
                            var retries = 0
                            var seekSuccess = false
                            while (retries < 15 && !seekSuccess) {
                                try { Thread.sleep(1000) } catch (e: Exception) {}
                                
                                val formattedTime = formatTimeForDLNA(startPositionMs)
                                Log.d(TAG, "이전 재생 위치로 Seek 시도 ($retries/15): $formattedTime")
                                
                                var callDone = false
                                seek(device, startPositionMs) { success, msg ->
                                    if (success) {
                                        seekSuccess = true
                                        Log.d(TAG, "Seek 정상 수행 완료!")
                                    } else {
                                        Log.d(TAG, "Seek 준비 안 됨, 재시도 대기... msg=$msg")
                                    }
                                    callDone = true
                                }
                                
                                // UPnP 비동기 응답 대기 (최대 3초)
                                var waitCount = 0
                                while (!callDone && waitCount < 30) {
                                    try { Thread.sleep(100) } catch (e: Exception) {}
                                    waitCount++
                                }
                                retries++
                            }
                            
                            // Seek 성공 직후, 버퍼링이나 중단 상태에 빠지는 것을 막기 위해 다시 Play 전송
                            if (seekSuccess) {
                                executePlay(device, {}, {})
                            }
                        }.start()
                    }
                    onSuccess()
                }, onFailure = onFailure)
            }

            override fun failure(
                invocation: ActionInvocation<out Service<*, *>>?,
                operation: UpnpResponse?,
                defaultMsg: String?
            ) {
                Log.e(TAG, "SetAVTransportURI 실패: $defaultMsg")
                onFailure("영상 전송 실패: ${defaultMsg ?: "알 수 없는 오류"}")
            }
        })
    }

    /**
     * Play 명령 실행
     */
    private fun executePlay(
        device: Device<*, *, *>,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val service = upnpService ?: return
        val avTransport = device.findService(UDAServiceType("AVTransport")) ?: return

        val playAction = org.jupnp.model.action.ActionInvocation(avTransport.getAction("Play"))
        playAction.setInput("InstanceID", UnsignedIntegerFourBytes(0))
        playAction.setInput("Speed", "1")

        service.controlPoint.execute(object : ActionCallback(playAction) {
            override fun success(invocation: ActionInvocation<out Service<*, *>>) {
                Log.d(TAG, "Play 성공!")
                onSuccess()
            }

            override fun failure(
                invocation: ActionInvocation<out Service<*, *>>?,
                operation: UpnpResponse?,
                defaultMsg: String?
            ) {
                Log.e(TAG, "Play 실패: $defaultMsg")
                onFailure("재생 명령 실패: ${defaultMsg ?: "알 수 없는 오류"}")
            }
        })
    }

    /**
     * Pause 명령
     */
    fun pause(device: Device<*, *, *>, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        executeSimpleAction(device, "Pause", onResult)
    }

    /**
     * 재생 재개 (일시정지 후)
     */
    fun resume(device: Device<*, *, *>, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        val service = upnpService ?: return
        val avTransport = device.findService(UDAServiceType("AVTransport")) ?: return

        val playAction = org.jupnp.model.action.ActionInvocation(avTransport.getAction("Play"))
        playAction.setInput("InstanceID", UnsignedIntegerFourBytes(0))
        playAction.setInput("Speed", "1")

        service.controlPoint.execute(object : ActionCallback(playAction) {
            override fun success(invocation: ActionInvocation<out Service<*, *>>) {
                onResult(true, null)
            }
            override fun failure(invocation: ActionInvocation<out Service<*, *>>?, operation: UpnpResponse?, defaultMsg: String?) {
                onResult(false, defaultMsg)
            }
        })
    }

    /**
     * Stop 명령
     */
    fun stop(device: Device<*, *, *>, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        executeSimpleAction(device, "Stop", onResult)
    }

    /**
     * Seek 명령
     */
    fun seek(device: Device<*, *, *>, positionMs: Long, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        val service = upnpService ?: return
        val avTransport = device.findService(UDAServiceType("AVTransport")) ?: return

        val seekAction = org.jupnp.model.action.ActionInvocation(avTransport.getAction("Seek"))
        seekAction.setInput("InstanceID", UnsignedIntegerFourBytes(0))
        seekAction.setInput("Unit", "REL_TIME")
        seekAction.setInput("Target", formatTimeForDLNA(positionMs))

        service.controlPoint.execute(object : ActionCallback(seekAction) {
            override fun success(invocation: ActionInvocation<out Service<*, *>>) {
                onResult(true, null)
            }
            override fun failure(invocation: ActionInvocation<out Service<*, *>>?, operation: UpnpResponse?, defaultMsg: String?) {
                Log.e(TAG, "Seek 실패: $defaultMsg")
                onResult(false, defaultMsg)
            }
        })
    }

    /**
     * 재생 위치 정보 조회 (GetPositionInfo)
     */
    fun getPositionInfo(
        device: Device<*, *, *>,
        onResult: (positionMs: Long, durationMs: Long) -> Unit,
        onFailure: () -> Unit = {}
    ) {
        val service = upnpService ?: return
        val avTransport = device.findService(UDAServiceType("AVTransport")) ?: return

        val posAction = org.jupnp.model.action.ActionInvocation(avTransport.getAction("GetPositionInfo"))
        posAction.setInput("InstanceID", UnsignedIntegerFourBytes(0))

        service.controlPoint.execute(object : ActionCallback(posAction) {
            override fun success(invocation: ActionInvocation<out Service<*, *>>) {
                try {
                    val relTime = invocation.getOutput("RelTime")?.value?.toString() ?: "0:00:00"
                    val trackDuration = invocation.getOutput("TrackDuration")?.value?.toString() ?: "0:00:00"
                    val posMs = parseDLNATime(relTime)
                    val durMs = parseDLNATime(trackDuration)
                    onResult(posMs, durMs)
                } catch (e: Exception) {
                    Log.e(TAG, "GetPositionInfo 파싱 오류: ${e.message}")
                    onFailure()
                }
            }
            override fun failure(invocation: ActionInvocation<out Service<*, *>>?, operation: UpnpResponse?, defaultMsg: String?) {
                Log.e(TAG, "GetPositionInfo 실패: $defaultMsg")
                onFailure()
            }
        })
    }

    /**
     * GetTransportInfo - 현재 재생 상태 조회
     */
    fun getTransportInfo(
        device: Device<*, *, *>,
        onResult: (state: String) -> Unit,
        onFailure: () -> Unit = {}
    ) {
        val service = upnpService ?: return
        val avTransport = device.findService(UDAServiceType("AVTransport")) ?: return

        val infoAction = org.jupnp.model.action.ActionInvocation(avTransport.getAction("GetTransportInfo"))
        infoAction.setInput("InstanceID", UnsignedIntegerFourBytes(0))

        service.controlPoint.execute(object : ActionCallback(infoAction) {
            override fun success(invocation: ActionInvocation<out Service<*, *>>) {
                val state = invocation.getOutput("CurrentTransportState")?.value?.toString() ?: "UNKNOWN"
                onResult(state)
            }
            override fun failure(invocation: ActionInvocation<out Service<*, *>>?, operation: UpnpResponse?, defaultMsg: String?) {
                onFailure()
            }
        })
    }

    private fun executeSimpleAction(
        device: Device<*, *, *>,
        actionName: String,
        onResult: (Boolean, String?) -> Unit
    ) {
        val service = upnpService ?: return
        val avTransport = device.findService(UDAServiceType("AVTransport")) ?: return

        val action = org.jupnp.model.action.ActionInvocation(avTransport.getAction(actionName))
        action.setInput("InstanceID", UnsignedIntegerFourBytes(0))

        service.controlPoint.execute(object : ActionCallback(action) {
            override fun success(invocation: ActionInvocation<out Service<*, *>>) {
                Log.d(TAG, "$actionName 성공")
                onResult(true, null)
            }
            override fun failure(invocation: ActionInvocation<out Service<*, *>>?, operation: UpnpResponse?, defaultMsg: String?) {
                Log.e(TAG, "$actionName 실패: $defaultMsg")
                onResult(false, defaultMsg)
            }
        })
    }

    /**
     * DIDL-Lite 메타데이터 생성 (자막 URL 포함)
     * 다양한 TV 브랜드 호환:
     * - Samsung: sec:CaptionInfoEx
     * - LG/Sony/Platinum: res 엘리먼트 + smi:caption
     * - Panasonic/Philips: pv:subtitleFileUri
     */
    private fun buildDIDLMetadata(mediaUrl: String, title: String, subtitleUrl: String?): String {
        val escapedTitle = title
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
        val escapedUrl = mediaUrl
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        // MIME 타입 추정
        val mimeType = guessMimeType(mediaUrl)

        val subtitleElement = if (subtitleUrl != null) {
            val escapedSubUrl = subtitleUrl
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
            val subMime = guessSubtitleMimeType(subtitleUrl)
            // URL에서 실제 자막 포맷 추출
            val subType = subtitleUrl.substringAfterLast('.').lowercase().let { ext ->
                when (ext) {
                    "srt" -> "srt"
                    "ass", "ssa" -> "ass"
                    "vtt" -> "vtt"
                    "smi" -> "smi"
                    else -> "srt"
                }
            }
            Log.d(TAG, "자막 DIDL 생성: type=$subType, mime=$subMime, url=$escapedSubUrl")
            // 다양한 TV 호환을 위해 복수의 자막 전달 방식 사용
            buildString {
                // 1. Samsung TV용: sec:CaptionInfoEx
                append("""<sec:CaptionInfoEx sec:type="$subType" protocolInfo="http-get:*:$subMime:*">$escapedSubUrl</sec:CaptionInfoEx>""")
                append("\n            ")
                // 2. Samsung TV용: sec:CaptionInfo (구형 Samsung)
                append("""<sec:CaptionInfo sec:type="$subType">$escapedSubUrl</sec:CaptionInfo>""")
                append("\n            ")
                // 3. 표준 DLNA res 엘리먼트 (LG/Sony/Platinum 등)
                append("""<res protocolInfo="http-get:*:$subMime:DLNA.ORG_OP=01;DLNA.ORG_CI=0">$escapedSubUrl</res>""")
                append("\n            ")
                // 4. Panasonic/Philips용: pv:subtitleFileUri
                append("""<pv:subtitleFileUri>$escapedSubUrl</pv:subtitleFileUri>""")
            }
        } else {
            Log.d(TAG, "자막 URL이 null입니다 - 외부 자막 없이 전송")
            ""
        }

        return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:sec="http://www.sec.co.kr/" xmlns:pv="http://www.pv.com/pvns/" xmlns:smi="urn:schemas-smi-com:smi">
            <item id="0" parentID="-1" restricted="1">
                <dc:title>$escapedTitle</dc:title>
                <upnp:class>object.item.videoItem</upnp:class>
                <res protocolInfo="http-get:*:$mimeType:DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000">$escapedUrl</res>
                $subtitleElement
            </item>
        </DIDL-Lite>"""
    }

    private fun guessMimeType(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.endsWith(".mp4") || lower.endsWith(".m4v") -> "video/mp4"
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".avi") -> "video/x-msvideo"
            lower.endsWith(".mov") -> "video/quicktime"
            lower.endsWith(".wmv") -> "video/x-ms-wmv"
            lower.endsWith(".flv") -> "video/x-flv"
            lower.endsWith(".ts") -> "video/mp2t"
            lower.endsWith(".webm") -> "video/webm"
            else -> "video/mp4"
        }
    }

    private fun guessSubtitleMimeType(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.endsWith(".srt") -> "text/srt"
            lower.endsWith(".ass") || lower.endsWith(".ssa") -> "text/x-ssa"
            lower.endsWith(".vtt") -> "text/vtt"
            lower.endsWith(".smi") -> "application/x-sami"
            else -> "text/plain"
        }
    }

    /**
     * 밀리초 → HH:MM:SS 포맷 변환 (DLNA 형식)
     */
    private fun formatTimeForDLNA(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    /**
     * HH:MM:SS → 밀리초 파싱
     */
    private fun parseDLNATime(timeStr: String): Long {
        return try {
            val parts = timeStr.split(":")
            if (parts.size >= 3) {
                val hours = parts[0].toLong()
                val minutes = parts[1].toLong()
                // 초에 소수점이 있을 수 있음 (예: "1:23:45.678")
                val secondsParts = parts[2].split(".")
                val seconds = secondsParts[0].toLong()
                val millis = if (secondsParts.size > 1) {
                    secondsParts[1].padEnd(3, '0').take(3).toLong()
                } else 0L
                (hours * 3600 + minutes * 60 + seconds) * 1000 + millis
            } else 0L
        } catch (e: Exception) {
            Log.e(TAG, "시간 파싱 오류: $timeStr", e)
            0L
        }
    }
}
