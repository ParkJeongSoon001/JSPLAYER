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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
            val mrType = org.jupnp.model.types.UDADeviceType("MediaRenderer", 1)
            service.controlPoint?.search(org.jupnp.model.message.header.UDADeviceTypeHeader(mrType))
            val avType = org.jupnp.model.types.UDAServiceType("AVTransport", 1)
            service.controlPoint?.search(org.jupnp.model.message.header.UDAServiceTypeHeader(avType))
        } catch (e: Exception) {
            Log.e(TAG, "DMR 검색 오류", e)
        }

        // Direct Raw UDP SSDP M-SEARCH 패킷 발송 (TV 탐색 호환성 극대화)
        Thread {
            try {
                val socket = java.net.MulticastSocket()
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                var wifiInterface: java.net.NetworkInterface? = null
                while (interfaces.hasMoreElements()) {
                    val intf = interfaces.nextElement()
                    if (intf.isUp && intf.supportsMulticast() && intf.name.startsWith("wlan")) {
                        val hasIpv4 = intf.inetAddresses.asSequence().any { !it.isLoopbackAddress && it.hostAddress.contains(".") }
                        if (hasIpv4) {
                            wifiInterface = intf
                            break
                        }
                        wifiInterface = intf
                    }
                }
                if (wifiInterface != null) {
                    socket.networkInterface = wifiInterface
                }
                socket.timeToLive = 4
                val targets = listOf(
                    "urn:schemas-upnp-org:device:MediaRenderer:1",
                    "urn:schemas-upnp-org:service:AVTransport:1",
                    "ssdp:all"
                )
                val address = java.net.InetAddress.getByName("239.255.255.250")
                for (st in targets) {
                    val ssdpMessage = "M-SEARCH * HTTP/1.1\r\n" +
                            "HOST: 239.255.255.250:1900\r\n" +
                            "MAN: \"ssdp:discover\"\r\n" +
                            "MX: 3\r\n" +
                            "ST: $st\r\n\r\n"
                    val data = ssdpMessage.toByteArray()
                    val packet = java.net.DatagramPacket(data, data.size, address, 1900)
                    socket.send(packet)
                }
                socket.close()
                Log.d(TAG, "Raw SSDP M-SEARCH 패킷 발송 완료")
            } catch (e: Exception) {
                Log.e(TAG, "Raw SSDP 패킷 발송 오류", e)
            }
        }.start()
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
        Log.d(TAG, "━━━ SetAVTransportURI 전송 ━━━")
        Log.d(TAG, "  mediaUrl: $mediaUrl")
        Log.d(TAG, "  subtitleUrl: ${subtitleUrl ?: "(없음 → TV 내장 자막 사용)"}")
        if (subtitleUrl != null) {
            Log.d(TAG, "  ✅ 외부 자막 메타데이터: sec:CaptionInfoEx + res@pv:subtitleFileUri + upnp:resExt Caption")
        }

        // SetAVTransportURI 실행
        val setUriAction = org.jupnp.model.action.ActionInvocation(avTransport.getAction("SetAVTransportURI"))
        setUriAction.setInput("InstanceID", UnsignedIntegerFourBytes(0))
        setUriAction.setInput("CurrentURI", mediaUrl)
        setUriAction.setInput("CurrentURIMetaData", metadata)

        service.controlPoint.execute(object : ActionCallback(setUriAction) {
            override fun success(invocation: ActionInvocation<out Service<*, *>>) {
                Log.d(TAG, "SetAVTransportURI 성공!")
                // 많은 스마트TV가 SetAVTransportURI 직후 STOPPED 상태에서 직접적인 Seek를 거부하므로,
                // 먼저 Play를 시작하고 실제 TV의 위치가 목표 위치로 이동했는지 확인합니다.
                executePlay(device, onSuccess = {
                    if (startPositionMs > 0L) {
                        Thread {
                            var retries = 0
                            var seekSuccess = false
                            val formattedTime = formatTimeForDLNA(startPositionMs)
                            while (retries < 8 && !seekSuccess) {
                                try { Thread.sleep(750) } catch (e: InterruptedException) {
                                    Thread.currentThread().interrupt()
                                    break
                                }
                                
                                Log.d(TAG, "이전 재생 위치로 Seek 및 TV 위치 확인 시도 (${retries + 1}/8): $formattedTime")
                                seekSuccess = seekAndConfirmPosition(device, startPositionMs)
                                if (!seekSuccess) Log.d(TAG, "TV가 목표 위치로 이동하지 않음, 잠시 후 재시도")
                                retries++
                            }
                            
                            // Seek 후 TV가 재생을 멈추는 모델을 위해 확인이 끝난 뒤 Play를 재전송합니다.
                            if (seekSuccess) {
                                executePlay(device, {}, {})
                            } else {
                                Log.e(TAG, "재생 위치 복원 실패: TV가 $formattedTime 위치 이동을 확인해주지 않았습니다.")
                            }
                            // Seek 완료 후 자막 강제 활성화 시도
                            if (subtitleUrl != null) {
                                try { Thread.sleep(1500) } catch (_: Exception) {}
                                trySendSubtitleToTV(device, subtitleUrl)
                            }
                        }.start()
                    } else {
                        // startPositionMs == 0 이고 자막이 있는 경우에도 강제 활성화
                        if (subtitleUrl != null) {
                            Thread {
                                try { Thread.sleep(2000) } catch (_: Exception) {}
                                trySendSubtitleToTV(device, subtitleUrl)
                            }.start()
                        }
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
        val service = upnpService
        if (service == null) {
            onResult(false, "UPnP 서비스가 연결되지 않았습니다.")
            return
        }
        val avTransport = device.findService(UDAServiceType("AVTransport"))
        if (avTransport == null) {
            onResult(false, "AVTransport 서비스를 찾을 수 없습니다.")
            return
        }

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

    /** A successful UPnP Seek response does not guarantee that the TV actually moved. */
    private fun seekAndConfirmPosition(device: Device<*, *, *>, targetPositionMs: Long): Boolean {
        val seekFinished = CountDownLatch(1)
        var seekAccepted = false
        seek(device, targetPositionMs) { success, message ->
            seekAccepted = success
            if (!success) Log.d(TAG, "TV Seek 거부: $message")
            seekFinished.countDown()
        }
        if (!seekFinished.await(3, TimeUnit.SECONDS) || !seekAccepted) return false

        try {
            Thread.sleep(600)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }

        val positionFinished = CountDownLatch(1)
        var actualPositionMs = -1L
        getPositionInfo(
            device = device,
            onResult = { positionMs, _ ->
                actualPositionMs = positionMs
                positionFinished.countDown()
            },
            onFailure = { positionFinished.countDown() }
        )
        if (!positionFinished.await(3, TimeUnit.SECONDS) || actualPositionMs < 0L) return false

        val differenceMs = kotlin.math.abs(actualPositionMs - targetPositionMs)
        val confirmed = differenceMs <= 3_000L
        Log.d(
            TAG,
            "TV 위치 확인: 목표=${formatTimeForDLNA(targetPositionMs)}, 실제=${formatTimeForDLNA(actualPositionMs)}, 확인=$confirmed"
        )
        return confirmed
    }

    /**
     * 재생 위치 정보 조회 (GetPositionInfo)
     */
    fun getPositionInfo(
        device: Device<*, *, *>,
        onResult: (positionMs: Long, durationMs: Long) -> Unit,
        onFailure: () -> Unit = {}
    ) {
        val service = upnpService
        if (service == null) {
            onFailure()
            return
        }
        val avTransport = device.findService(UDAServiceType("AVTransport"))
        if (avTransport == null) {
            onFailure()
            return
        }

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
     * 외부 자막은 첫 SetAVTransportURI의 DIDL 메타데이터에 포함합니다.
     * 이후 지원되는 벤더 액션/RenderingControl 변환만 시도하고 URI는 재설정하지 않습니다.
     */
    private fun trySendSubtitleToTV(device: Device<*, *, *>, subtitleUrl: String) {
        val service = upnpService ?: return
        val avTransport = device.findService(UDAServiceType("AVTransport")) ?: return
        val deviceName = device.details?.friendlyName ?: device.displayString
        Log.d(TAG, "━━━ 외부 자막 강제 활성화 시도 ━━━")
        Log.d(TAG, "  TV: $deviceName")
        Log.d(TAG, "  자막 URL: $subtitleUrl")

        // 첫 SetAVTransportURI에 sec:CaptionInfoEx / pv:subtitleFileUri가 포함되어 있으므로
        // 이를 다시 보내 재생 위치를 초기화하지 않고, 지원되는 캡션 액션만 호출합니다.
        // Samsung TV 전용 - X_SetStealSubtitleURL (일부 모델 지원)
        try {
            val stealAction = avTransport.getAction("X_SetStealSubtitleURL")
            if (stealAction != null) {
                val action = org.jupnp.model.action.ActionInvocation(stealAction)
                action.setInput("InstanceID", UnsignedIntegerFourBytes(0))
                action.setInput("SubtitleURL", subtitleUrl)
                action.setInput("SubtitleType", "srt")
                service.controlPoint.execute(object : ActionCallback(action) {
                    override fun success(invocation: ActionInvocation<out Service<*, *>>) {
                        Log.d(TAG, "✅ [Samsung] X_SetStealSubtitleURL 성공")
                    }
                    override fun failure(invocation: ActionInvocation<out Service<*, *>>?, operation: UpnpResponse?, defaultMsg: String?) {
                        Log.d(TAG, "ℹ [Samsung] X_SetStealSubtitleURL 미지원: $defaultMsg")
                    }
                })
            }
        } catch (e: Exception) {
            Log.d(TAG, "ℹ [Samsung] X_SetStealSubtitleURL 시도 실패: ${e.message}")
        }

        // X_SetCaptionInfo (일부 Samsung/LG 모델)
        try {
            val captionAction = avTransport.getAction("X_SetCaptionInfo")
            if (captionAction != null) {
                val action = org.jupnp.model.action.ActionInvocation(captionAction)
                action.setInput("InstanceID", UnsignedIntegerFourBytes(0))
                action.setInput("CaptionInfoURL", subtitleUrl)
                service.controlPoint.execute(object : ActionCallback(action) {
                    override fun success(invocation: ActionInvocation<out Service<*, *>>) {
                        Log.d(TAG, "✅ [Samsung/LG] X_SetCaptionInfo 성공")
                    }
                    override fun failure(invocation: ActionInvocation<out Service<*, *>>?, operation: UpnpResponse?, defaultMsg: String?) {
                        Log.d(TAG, "ℹ [Samsung/LG] X_SetCaptionInfo 미지원: $defaultMsg")
                    }
                })
            }
        } catch (e: Exception) {
            Log.d(TAG, "ℹ [Samsung/LG] X_SetCaptionInfo 시도 실패: ${e.message}")
        }

        // Generic DLNA renderers may ignore Samsung/Panasonic caption extensions. If the
        // renderer exposes RenderingControl:3 transforms, explicitly request Korean subtitles.
        trySetSubtitleTransform(device, subtitleUrl)

        Log.d(TAG, "━━━ 외부 자막 메타데이터 전달 완료 (TV의 실제 표시 여부는 확인 불가) ━━━")
    }

    /** Select the external caption language on renderers that implement RenderingControl transforms. */
    private fun trySetSubtitleTransform(device: Device<*, *, *>, subtitleUrl: String) {
        val renderingControl = device.findService(UDAServiceType("RenderingControl"))
        if (renderingControl == null) {
            Log.d(TAG, "ℹ [UPnP] RenderingControl 서비스 없음 — 표준 자막 선택 변환 미지원")
            return
        }

        val getAllowedAction = renderingControl.getAction("GetAllowedTransforms")
        val setTransformsAction = renderingControl.getAction("SetTransforms")
        if (getAllowedAction == null || setTransformsAction == null) {
            Log.d(TAG, "ℹ [UPnP] RenderingControl 자막 변환 액션 미지원 (GetAllowedTransforms/SetTransforms)")
            return
        }

        val service = upnpService ?: return
        val getAllowed = org.jupnp.model.action.ActionInvocation(getAllowedAction)
        getAllowed.setInput("InstanceID", UnsignedIntegerFourBytes(0))
        service.controlPoint.execute(object : ActionCallback(getAllowed) {
            override fun success(invocation: ActionInvocation<out Service<*, *>>) {
                val allowedXml = invocation.getOutput("CurrentAllowedTransformSettings")
                    ?.value?.toString().orEmpty()
                val allowedValues = readSubtitleTransformValues(allowedXml)
                val selectedLanguage = allowedValues.firstOrNull { it.equals("ko", ignoreCase = true) }
                    ?: allowedValues.firstOrNull { it.startsWith("ko-", ignoreCase = true) }
                if (selectedLanguage == null) {
                    Log.d(TAG, "ℹ [UPnP] TV에서 한국어 자막 변환을 허용하지 않음: $allowedValues")
                    return
                }

                val transformXml = """<?xml version="1.0" encoding="UTF-8"?>
                    |<TransformSettings xmlns="urn:schemas-upnp-org:av:TransformSettings">
                    |  <transform name="Subtitle"><value>$selectedLanguage</value></transform>
                    |</TransformSettings>""".trimMargin()
                val setTransforms = org.jupnp.model.action.ActionInvocation(setTransformsAction)
                setTransforms.setInput("InstanceID", UnsignedIntegerFourBytes(0))
                setTransforms.setInput("DesiredTransformValues", transformXml)
                Log.d(TAG, "[UPnP] TV 한국어 자막 선택 요청: lang=$selectedLanguage, url=$subtitleUrl")
                service.controlPoint.execute(object : ActionCallback(setTransforms) {
                    override fun success(invocation: ActionInvocation<out Service<*, *>>) {
                        Log.d(TAG, "✅ [UPnP] RenderingControl Subtitle=$selectedLanguage 적용 응답")
                    }

                    override fun failure(
                        invocation: ActionInvocation<out Service<*, *>>?,
                        operation: UpnpResponse?,
                        defaultMsg: String?
                    ) {
                        Log.w(TAG, "⚠ [UPnP] RenderingControl 자막 선택 실패: $defaultMsg")
                    }
                })
            }

            override fun failure(
                invocation: ActionInvocation<out Service<*, *>>?,
                operation: UpnpResponse?,
                defaultMsg: String?
            ) {
                Log.w(TAG, "⚠ [UPnP] TV 자막 변환 목록 조회 실패: $defaultMsg")
            }
        })
    }

    private fun readSubtitleTransformValues(transformXml: String): List<String> {
        if (transformXml.isBlank()) return emptyList()
        return try {
            val parser = android.util.Xml.newPullParser()
            parser.setInput(java.io.StringReader(transformXml))
            var inSubtitleTransform = false
            val values = mutableListOf<String>()
            while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    org.xmlpull.v1.XmlPullParser.START_TAG -> when (parser.name) {
                        "transform" -> inSubtitleTransform =
                            parser.getAttributeValue(null, "name").equals("Subtitle", ignoreCase = true)
                        "allowedValue" -> if (inSubtitleTransform) {
                            parser.nextText().trim().takeIf { it.isNotEmpty() }?.let(values::add)
                        }
                    }
                    org.xmlpull.v1.XmlPullParser.END_TAG -> if (parser.name == "transform") {
                        inSubtitleTransform = false
                    }
                }
            }
            values
        } catch (e: Exception) {
            Log.w(TAG, "[UPnP] TV 자막 변환 목록 XML 파싱 실패: ${e.message}")
            emptyList()
        }
    }

    /**
     * DIDL-Lite 메타데이터 생성 (자막 URL 포함)
     * 다양한 TV 브랜드 호환:
     * - Samsung: sec:CaptionInfoEx, sec:CaptionInfo
     * - LG/Sony/Platinum: res 엘리먼트 (application/x-subrip, text/srt) + res_ext:caption
     * - Panasonic/Philips/TCL: pv:subtitleFileUri, pv:subtitleFileType
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

        val subtitleResourceAttributes = if (subtitleUrl != null) {
            val escapedSubUrl = subtitleUrl
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
            // pv subtitle metadata belongs to the video <res> element. A standalone
            // pv:subtitleFileUri child of <item> is ignored by common renderers.
            " pv:subtitleFileUri=\"$escapedSubUrl\" pv:subtitleFileType=\"SRT\""
        } else ""

        val subtitleElements = if (subtitleUrl != null) {
            val escapedSubUrl = subtitleUrl
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
            val subMime = "application/x-subrip"
            val subType = "srt"
            Log.d(TAG, "자막 DIDL 생성: external=$escapedSubUrl, pv:subtitleFileType=SRT")
            buildString {
                // 1. Samsung TV용: sec:CaptionInfoEx & sec:CaptionInfo
                append("""<sec:CaptionInfoEx sec:type="$subType" protocolInfo="http-get:*:$subMime:*" sec:enabled="true">$escapedSubUrl</sec:CaptionInfoEx>""")
                append("\n                ")
                append("""<sec:CaptionInfo sec:type="$subType">$escapedSubUrl</sec:CaptionInfo>""")
            }
        } else {
            Log.d(TAG, "자막 URL이 null입니다 - 외부 자막 없이 전송")
            ""
        }

        // Some Android TV renderers ignore the vendor-specific CaptionInfo and resExt
        // fields, but discover sidecar tracks only when they are advertised as a sibling
        // DIDL resource. Keep the video <res> first so it remains the primary resource.
        val subtitleResourceElement = if (subtitleUrl != null) {
            val escapedSubUrl = subtitleUrl
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
            """<res protocolInfo="http-get:*:text/srt:*">$escapedSubUrl</res>"""
        } else ""

        return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:sec="http://www.sec.co.kr/" xmlns:pv="http://www.pv.com/pvns/" xmlns:smi="urn:schemas-smi-com:smi" xmlns:res_ext="urn:schemas-dlna-org:metadata-1-0/LD/">
            <item id="0" parentID="-1" restricted="1">
                <dc:title>$escapedTitle</dc:title>
                <dc:language>kor</dc:language>
                <upnp:class>object.item.videoItem</upnp:class>
                <pv:subtitleLanguage>kor</pv:subtitleLanguage>
                <res id="video0" protocolInfo="http-get:*:$mimeType:DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000"$subtitleResourceAttributes>$escapedUrl</res>
                $subtitleResourceElement
                $subtitleElements
                ${buildSubtitleComponentInfo(subtitleUrl, mimeType)}
            </item>
        </DIDL-Lite>"""
    }

    private fun buildSubtitleComponentInfo(subtitleUrl: String?, videoMimeType: String): String {
        if (subtitleUrl == null) return ""
        val escapedSubUrl = subtitleUrl
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        val subtitleMimeType = "application/x-subrip"
        return """<upnp:resExt id="video0">
            |  <upnp:componentInfo>
            |    <upnp:componentGroup groupID="video" required="1">
            |      <upnp:component componentID="video0">
            |        <upnp:componentClass>Video</upnp:componentClass>
            |        <upnp:contentType MIMEType="$videoMimeType" extendedType="*"/>
            |      </upnp:component>
            |    </upnp:componentGroup>
            |    <upnp:componentGroup groupID="external-captions" required="0">
            |      <upnp:component componentID="external-caption-ko">
            |        <upnp:componentClass>Caption</upnp:componentClass>
            |        <upnp:purpose>Alternative</upnp:purpose>
            |        <upnp:language>ko</upnp:language>
            |        <upnp:contentType MIMEType="$subtitleMimeType" extendedType="*"/>
            |        <upnp:compRes>
            |          <upnp:res protocolInfo="http-get:*:$subtitleMimeType:*">$escapedSubUrl</upnp:res>
            |        </upnp:compRes>
            |      </upnp:component>
            |    </upnp:componentGroup>
            |  </upnp:componentInfo>
            |</upnp:resExt>""".trimMargin()
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
                lower.endsWith(".srt") -> "application/x-subrip"
                lower.endsWith(".ass") || lower.endsWith(".ssa") -> "application/x-ssa"
                lower.endsWith(".vtt") -> "text/vtt"
                lower.endsWith(".smi") -> "application/x-sami"
                else -> "application/octet-stream"
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
