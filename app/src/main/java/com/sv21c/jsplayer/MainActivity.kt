package com.sv21c.jsplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Sort
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sv21c.jsplayer.R
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.ui.PlayerView
import com.sv21c.jsplayer.ui.theme.JSPLAYERTheme
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.delay
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.jupnp.model.meta.Device
import android.util.Log
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jupnp.support.model.DIDLObject
import org.jupnp.support.model.container.Container
import org.jupnp.support.model.item.Item
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.focus.onFocusChanged
import android.content.pm.ActivityInfo
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import android.provider.Settings
import android.net.Uri
import android.content.Intent
import android.app.AppOpsManager
import android.content.Context

sealed class ScreenState {
    object Home : ScreenState()
    object ServerList : ScreenState()
    object LicensePolicy : ScreenState()
    object Settings : ScreenState()
    data class Browsing(
        val device: Device<*, *, *>,
        val currentContainerId: String,
        val containerName: String
    ) : ScreenState()
    object LocalBrowsing : ScreenState()
    // ── SMB / WebDAV ──────────────────────────────────────────────
    data class SmbServerList(val dummy: Unit = Unit) : ScreenState()
    data class WebDavServerList(val dummy: Unit = Unit) : ScreenState()
    data class FtpSftpServerList(val dummy: Unit = Unit) : ScreenState()
    data class NetworkBrowsing(
        val credentials: ServerCredentials,
        val currentPath: String,
        val currentPathName: String
    ) : ScreenState()
    // ─────────────────────────────────────────────────────────────
    data class Playing(
        val videoUrl: String,
        val title: String,
        val subtitleUrl: String? = null,
        val subtitleExtension: String? = null,
        val playlist: List<PlaylistItem> = emptyList(),
        val currentIndex: Int = -1,
        val ftpEncoding: String = "AUTO",
        val httpHeaders: Map<String, String> = emptyMap()
    ) : ScreenState()
}

enum class LocalViewMode {
    ALL_VIDEOS,
    FOLDERS
}

data class NavHistoryItem(val containerId: String, val containerName: String)

data class LocalVideoItem(
    val id: Long,
    val uri: android.net.Uri,
    val title: String,
    val duration: Long,
    val size: Long,
    val path: String?
)

data class PlaylistItem(
    val videoUrl: String,
    val title: String,
    val subtitleUrl: String? = null,
    val subtitleExtension: String? = null
)

class MainActivity : ComponentActivity() {
    private val devices = mutableStateListOf<Device<*, *, *>>()
    private lateinit var dlnaManager: DLNAManager
    // onBackPressAction: 빈 람다로 초기화 — null 없이 항상 안전 호출 가능
    private var onBackPressAction: () -> Unit = {}

    // ── DLNA 캐스팅 (DMR) ────────────────────────────────────────
    internal lateinit var rendererManager: DLNARendererManager
    val rendererDevices = mutableStateListOf<Device<*, *, *>>()
    val isCasting = mutableStateOf(false)
    val currentRenderer = mutableStateOf<Device<*, *, *>?>(null)
    private var localHttpServer: LocalHttpServer? = null

    // ── PIP (Picture-in-Picture) ─────────────────────────────────
    val isInPipMode = mutableStateOf(false)
    private var pipExoPlayer: ExoPlayer? = null
    private var isVideoScreenActive = false

    private val pipBroadcastReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == PIP_ACTION_PLAY_PAUSE) {
                pipExoPlayer?.let { player ->
                    if (player.isPlaying) player.pause() else player.play()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        updatePipActions(player.isPlaying)
                    }
                }
            }
        }
    }

    // 외부 앱에서 동영상 파일 열기(ACTION_VIEW)로 전달된 URI
    private val pendingVideoUri = mutableStateOf<android.net.Uri?>(null)

    private val codecInstallResultMessage = mutableStateOf<String?>(null)
    private val codecZipPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: android.net.Uri? ->
        uri?.let {
            lifecycleScope.launch {
                val result = CodecZipInstaller.installFromZip(this@MainActivity, it)
                if (result.success) {
                    // 설치 성공 직후 다시 로딩 시도 (현재 세션에서 사용 가능하도록)
                    val loaded = FfmpegLoader.initialize(this@MainActivity)
                    codecInstallResultMessage.value = "설치 성공: 고덱이 성공적으로 설치 및 로드되었습니다." + 
                        if (loaded) "\n(즉시 적용됨)" else "\n(적용을 위해 앱 재시작을 권장합니다)"
                } else {
                    codecInstallResultMessage.value = "설치 실패: ${result.message}"
                }
            }
        }
    }

    /**
     * ACTION_VIEW intent에서 동영상 URI를 추출하여 pendingVideoUri에 저장.
     * Compose에서 LaunchedEffect로 관찰하여 자동 재생.
     */
    private fun handleIncomingIntent(intent: android.content.Intent?) {
        if (intent?.action == android.content.Intent.ACTION_VIEW) {
            val uri = intent.data
            if (uri != null) {
                android.util.Log.d("MainActivity", "[EXTERNAL] 외부 동영상 URI 수신: $uri")
                pendingVideoUri.value = uri
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    // TV OS는 finish() / finishAffinity() / finishAndRemoveTask() 중 어느 것이든
    // 사용할 수 있으므로 모두 차단법.
    private var allowFinish = false
    override fun finish() {
        if (allowFinish) { super.finish() }
        else {
            android.util.Log.d("MainActivity", "[BACK] finish() 차단")
        }
    }
    override fun finishAffinity() {
        if (allowFinish) { super.finishAffinity() }
        else {
            android.util.Log.d("MainActivity", "[BACK] finishAffinity() 차단")
        }
    }
    override fun finishAndRemoveTask() {
        if (allowFinish) { super.finishAndRemoveTask() }
        else {
            android.util.Log.d("MainActivity", "[BACK] finishAndRemoveTask() 차단")
        }
    }

    // ─── 뒤로가기 핵심 처리 ───────────────────────────────────────────
    private var lastBackHandledAt = 0L
    private fun handleBackPress() {
        val now = System.currentTimeMillis()
        if (now - lastBackHandledAt < 400) return
        lastBackHandledAt = now
        android.util.Log.d("MainActivity", "[BACK] handleBackPress 호출")
        onBackPressAction()
    }

    // [경로 A] dispatchKeyEvent: TV 하드웨어 BACK/ESCAPE 키를 뷰 계층 전에 캡처
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK ||
            event.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == android.view.KeyEvent.ACTION_UP) {
                handleBackPress()
            }
            return true // DOWN/UP 모두 소비 → 시스템이 finish() 호출 불가
        }
        return super.dispatchKeyEvent(event)
    }

    // [경로 B] onBackPressed: 구형 TV OS가 dispatchKeyEvent 우회 후 직접 호출하는 경우
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBackPress() // super 절대 호출 안 함 → finish() 차단
    }

    companion object {
        const val PIP_ACTION_PLAY_PAUSE = "com.sv21c.jsplayer.PIP_PLAY_PAUSE"
        init {
            System.setProperty("java.net.preferIPv4Stack", "true")
            System.setProperty("org.slf4j.simpleLogger.logFile", "System.out")
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "trace")
            System.setProperty("org.slf4j.simpleLogger.showDateTime", "true")
            System.setProperty("org.slf4j.simpleLogger.showThreadName", "true")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 추가 코덱 동적 로딩 초기화 (Media3 구성 요소 로드 전 최우선 실행)
        FfmpegLoader.initialize(this)
        
        super.onCreate(savedInstanceState)

        // [경로 C] OnBackPressedCallback: 스마트폰 제스처 뒤로가기 (API 33+ 필수!)
        // Android 13+에서 제스처 뒤로가기는 KeyEvent를 보내지 않고
        // OnBackPressedDispatcher를 직접 호출함 → 이 콜백이 없으면 finish() 가 불림!
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPress()
            }
        })



        // 외부 앱에서 동영상 열기 intent 처리
        handleIncomingIntent(intent)
        
        // (원래 위치인 여기에도 남겨두거나 위로 완전히 옮겼으므로 삭제 가능하지만, 안전을 위해 중복 호출도 무방함)
        // FfmpegLoader.initialize(this) 

        // PIP 브로드캐스트 리시버 등록
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pipFilter = IntentFilter(PIP_ACTION_PLAY_PAUSE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(pipBroadcastReceiver, pipFilter, RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(pipBroadcastReceiver, pipFilter)
            }
        }

        
        dlnaManager = DLNAManager(
            context = this,
            onDeviceAdded = { device ->
                runOnUiThread {
                    val typeStr = device.type?.type ?: "Unknown"
                    val hasContentDirectory = device.findService(org.jupnp.model.types.UDAServiceType("ContentDirectory")) != null
                    val isMediaServer = typeStr.contains("MediaServer", ignoreCase = true) || hasContentDirectory
                    
                    if (isMediaServer) {
                        if (devices.none { it.identity.udn == device.identity.udn }) {
                            devices.add(device)
                        }
                    }
                }
            },
            onDeviceRemoved = { device ->
                runOnUiThread {
                    devices.removeIf { it.identity.udn == device.identity.udn }
                }
            }
        )

        // ── DLNA 렌더러 매니저 (캐스팅용) 초기화 ──────────────────
        rendererManager = DLNARendererManager(
            context = this,
            onRendererAdded = { device ->
                runOnUiThread {
                    if (rendererDevices.none { it.identity.udn == device.identity.udn }) {
                        rendererDevices.add(device)
                    }
                }
            },
            onRendererRemoved = { device ->
                runOnUiThread {
                    rendererDevices.removeIf { it.identity.udn == device.identity.udn }
                    // 현재 캐스팅 대상이 제거된 경우 캐스팅 중지
                    if (currentRenderer.value?.identity?.udn == device.identity.udn) {
                        isCasting.value = false
                        currentRenderer.value = null
                    }
                }
            }
        )

        // ── 로컬 HTTP 서버 시작 (캐스팅용) ────────────────────────
        try {
            localHttpServer = LocalHttpServer.getInstance(this)
            localHttpServer?.start()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "LocalHttpServer 시작 실패", e)
        }

        // ── OneDrive MSAL 초기화 (비동기) ────────────────────────
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            OneDriveAuthManager.initialize(this@MainActivity)
        }

        enableEdgeToEdge()
        setContent {
            JSPLAYERTheme {
                var showForegroundServicePermissionDialog by remember { mutableStateOf(false) }
                var showPipPermissionDialog by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    if (!checkForegroundServicePermissions()) {
                        showForegroundServicePermissionDialog = true
                    } else if (!isPipPermissionGranted()) {
                        showPipPermissionDialog = true
                    }
                }
                var screenState by remember { mutableStateOf<ScreenState>(ScreenState.Home) }
                val navStack = remember { mutableStateListOf<NavHistoryItem>() }
                var browseItems by remember { mutableStateOf<List<DIDLObject>>(emptyList()) }
                var isLoading by remember { mutableStateOf(false) }
                var errorMessage by remember { mutableStateOf<String?>(null) }
                val coroutineScope = rememberCoroutineScope()
                var showExitDialog by remember { mutableStateOf(false) }
                var browsingDeviceOverride: Device<*, *, *>? by remember { mutableStateOf(null) }
                var localBrowsingActiveMode: Boolean by remember { mutableStateOf(false) }
                var localSortOrder by remember { mutableStateOf(SortOrder.NAME_ASC) }
                var networkSortOrder by remember { mutableStateOf(SettingsStore.getSortOrder(this@MainActivity)) }
                var localViewMode by remember { mutableStateOf(LocalViewMode.ALL_VIDEOS) }
                var localSelectedFolder by remember { mutableStateOf<String?>(null) }
                var isSearchingServers by remember { mutableStateOf(false) }
                val lastFocusedIndexMap = remember { mutableStateMapOf<String, Int>() }
                // 외부 앱에서 동영상을 열었는지 추적 (뒤로가기 시 Home으로 복귀)
                var externalVideoMode by remember { mutableStateOf(false) }
                // 영상 플레이어 컨트롤 표시 여부 (Activity 레벨 호이스팅 — triggerBack에서 참조)
                var isVideoControlVisible by remember { mutableStateOf(true) }
                // ── SMB/WebDAV 탐색 스택 & 자격증명 ─────────────────────────
                val networkNavStack = remember { mutableStateListOf<Pair<String, String>>() } // (path, name)
                var networkBrowsingCredentials: ServerCredentials? by remember { mutableStateOf(null) }

                // ── 외부 앱에서 동영상 열기 처리 (ACTION_VIEW) ───────────────
                val externalUri = pendingVideoUri.value
                LaunchedEffect(externalUri) {
                    if (externalUri != null) {
                        // URI에서 파일명 추출 시도
                        val title = externalUri.lastPathSegment
                            ?.substringAfterLast("/")
                            ?: "외부 동영상"
                        android.util.Log.d("MainActivity", "[EXTERNAL] 재생 시작: $title ($externalUri)")
                        localBrowsingActiveMode = false
                        browsingDeviceOverride = null
                        networkBrowsingCredentials = null
                        externalVideoMode = true

                        // IO 스레드에서 자막 검색
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            var localSubUrl: String? = null
                            var localSubExt: String? = null

                            // content:// URI에서 실제 파일 경로 추출
                            var filePath: String? = null
                            try {
                                // 1) content:// URI → DATA 컬럼에서 경로 추출
                                if (externalUri.scheme == "content") {
                                    val projection = arrayOf(android.provider.MediaStore.MediaColumns.DATA)
                                    this@MainActivity.contentResolver.query(externalUri, projection, null, null, null)?.use { cursor ->
                                        if (cursor.moveToFirst()) {
                                            val idx = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
                                            if (idx >= 0) filePath = cursor.getString(idx)
                                        }
                                    }
                                }
                                // 2) file:// URI → 직접 경로
                                if (filePath == null && externalUri.scheme == "file") {
                                    filePath = externalUri.path
                                }
                            } catch (e: Exception) {
                                android.util.Log.d("SubtitleSearch", "[EXTERNAL] 경로 추출 실패: ${e.message}")
                            }

                            android.util.Log.d("SubtitleSearch", "========================================")
                            android.util.Log.d("SubtitleSearch", "[EXTERNAL] Video: $title, Path: $filePath")

                            if (filePath != null) {
                                try {
                                    val file = java.io.File(filePath)
                                    val parent = file.parentFile
                                    val baseName = file.nameWithoutExtension
                                    android.util.Log.d("SubtitleSearch", "[EXTERNAL] BaseName: $baseName, Dir: ${parent?.absolutePath}")

                                    if (parent != null && parent.exists() && parent.isDirectory) {
                                        val subs = parent.listFiles { _, name ->
                                            val lower = name.lowercase()
                                            val isSubExt = lower.endsWith(".smi") || lower.endsWith(".srt") || lower.endsWith(".vtt") || lower.endsWith(".ass")
                                            val itBase = name.substringBeforeLast(".")
                                            isSubExt && (itBase == baseName || (itBase.startsWith(baseName) && itBase.getOrNull(baseName.length) == '.'))
                                        }
                                        if (subs != null && subs.isNotEmpty()) {
                                            val subFile = subs.find { it.extension.equals("ass", true) }
                                                ?: subs.find { it.extension.equals("srt", true) }
                                                ?: subs.find { it.extension.equals("smi", true) }
                                                ?: subs[0]
                                            localSubUrl = "file://" + subFile.absolutePath
                                            localSubExt = subFile.extension.lowercase()
                                            android.util.Log.d("SubtitleSearch", "[EXTERNAL] Found subtitle: $localSubUrl (Ext: $localSubExt)")

                                            // 인코딩 감지 & SMI→SRT 변환
                                            try {
                                                if (subFile.length() > 10 * 1024 * 1024) throw Exception("Subtitle too large")
                                                val bytes = subFile.readBytes()
                                                if (bytes.isNotEmpty()) {
                                                    val sniffEnd = Math.min(bytes.size, 4096)
                                                    val sniffText = String(bytes.copyOfRange(0, sniffEnd), kotlin.text.Charsets.UTF_8).lowercase()
                                                    val isLikelySubtitle = sniffText.contains("<sami") || sniffText.contains("-->") || sniffText.contains("[script info]") || sniffText.contains("dialogue:") || sniffText.contains("webvtt")
                                                    if (!isLikelySubtitle && bytes.size > 1024 * 1024) throw Exception("Doesn't look like a subtitle")
                                                    
                                                    var text = ""
                                                    val b0 = bytes[0].toInt() and 0xFF
                                                    val b1 = if (bytes.size > 1) bytes[1].toInt() and 0xFF else 0

                                                    if (b0 == 0xFF && b1 == 0xFE) {
                                                        text = String(bytes, kotlin.text.Charsets.UTF_16LE)
                                                    } else if (b0 == 0xFE && b1 == 0xFF) {
                                                        text = String(bytes, kotlin.text.Charsets.UTF_16BE)
                                                    } else if (b0 == 0x3C && b1 == 0x00) {
                                                        text = String(bytes, kotlin.text.Charsets.UTF_16LE)
                                                    } else if (b0 == 0x00 && b1 == 0x3C) {
                                                        text = String(bytes, kotlin.text.Charsets.UTF_16BE)
                                                    } else {
                                                        text = String(bytes, kotlin.text.Charsets.UTF_8)
                                                        if (text.contains("\uFFFD") || (!text.contains("<sami", true) && localSubExt == "smi")) {
                                                            text = String(bytes, java.nio.charset.Charset.forName("EUC-KR"))
                                                        }
                                                    }

                                                    text = text.replace(Regex("charset=euc-kr", RegexOption.IGNORE_CASE), "charset=utf-8")
                                                    text = text.replace(Regex("charset=\"euc-kr\"", RegexOption.IGNORE_CASE), "charset=\"utf-8\"")
                                                    text = text.replace(Regex("charset=cp949", RegexOption.IGNORE_CASE), "charset=utf-8")

                                                    val lowerText = text.lowercase()
                                                    val isSmi = lowerText.contains("<sami")
                                                    val isSrt = lowerText.contains("-->")
                                                    val isAss = lowerText.contains("[script info]") || lowerText.contains("dialogue:") || localSubExt == "ass"

                                                    if ((localSubExt == "smi" && isSmi) || isSmi) {
                                                        val srtText = convertSmiToSrt(text)
                                                        val cacheFile = java.io.File(this@MainActivity.cacheDir, "cached_ext_sub.srt")
                                                        cacheFile.writeText(srtText, kotlin.text.Charsets.UTF_8)
                                                        localSubUrl = "file://" + cacheFile.absolutePath
                                                        localSubExt = "srt"
                                                        android.util.Log.d("SubtitleSearch", "[EXTERNAL] Converted SMI to SRT: $localSubUrl")
                                                    } else if ((localSubExt == "srt" && isSrt) || isSrt) {
                                                        val cacheFile = java.io.File(this@MainActivity.cacheDir, "cached_ext_sub.srt")
                                                        cacheFile.writeText(text, kotlin.text.Charsets.UTF_8)
                                                        localSubUrl = "file://" + cacheFile.absolutePath
                                                        localSubExt = "srt"
                                                        android.util.Log.d("SubtitleSearch", "[EXTERNAL] Saved SRT: $localSubUrl")
                                                    } else if (isAss) {
                                                        val cacheFile = java.io.File(this@MainActivity.cacheDir, "cached_ext_sub.ass")
                                                        cacheFile.writeText(text, kotlin.text.Charsets.UTF_8)
                                                        localSubUrl = "file://" + cacheFile.absolutePath
                                                        localSubExt = "ass"
                                                        android.util.Log.d("SubtitleSearch", "[EXTERNAL] Saved ASS: $localSubUrl")
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                android.util.Log.d("SubtitleSearch", "[EXTERNAL] Error parsing subtitle: ${e.message}")
                                            }
                                        } else {
                                            android.util.Log.d("SubtitleSearch", "[EXTERNAL] No matching subtitle found")
                                        }
                                    } else {
                                        android.util.Log.d("SubtitleSearch", "[EXTERNAL] Parent directory not accessible")
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.d("SubtitleSearch", "[EXTERNAL] Error searching subtitle: ${e.message}")
                                }
                            } else {
                                android.util.Log.d("SubtitleSearch", "[EXTERNAL] File path is null, cannot search subtitles")
                            }

                            android.util.Log.d("SubtitleSearch", "========================================")

                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                screenState = ScreenState.Playing(
                                    videoUrl = externalUri.toString(),
                                    title = title,
                                    subtitleUrl = localSubUrl,
                                    subtitleExtension = localSubExt,
                                    playlist = emptyList(),
                                    currentIndex = -1
                                )
                                pendingVideoUri.value = null  // 소비 완료
                            }
                        }
                    }
                }


                fun loadContainer(device: Device<*, *, *>, containerId: String) {
                    try {
                        isLoading = true
                        errorMessage = null
                        dlnaManager.browse(device, containerId) { didl, error ->
                            coroutineScope.launch(Dispatchers.Main) {
                                isLoading = false
                                if (error != null) {
                                    errorMessage = error
                                } else if (didl != null) {
                                    browseItems = didl.containers + didl.items
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "loadContainer 예외: ${e.message}", e)
                        isLoading = false
                        errorMessage = "로드 실패: ${e.message}"
                    }
                }

                fun goBack() {
                    when (screenState) {
                        is ScreenState.Home -> {
                            // Do nothing or exit
                        }
                        is ScreenState.LocalBrowsing -> {
                            screenState = ScreenState.Home
                        }
                        is ScreenState.Playing -> {
                        }
                        is ScreenState.Browsing -> {
                            if (navStack.isNotEmpty()) {
                                navStack.removeAt(navStack.lastIndex)
                                if (navStack.isNotEmpty()) {
                                    val previous = navStack.last()
                                    val currentState = screenState as? ScreenState.Browsing ?: return
                                    screenState = ScreenState.Browsing(currentState.device, previous.containerId, previous.containerName)
                                    loadContainer(currentState.device, previous.containerId)
                                } else {
                                    screenState = ScreenState.ServerList
                                }
                            } else {
                                screenState = ScreenState.ServerList
                            }
                        }
                        is ScreenState.Settings -> {
                            screenState = ScreenState.Home
                        }
                        is ScreenState.LicensePolicy -> {
                            screenState = ScreenState.Home
                        }
                        is ScreenState.ServerList -> {
                            screenState = ScreenState.Home
                        }
                        is ScreenState.SmbServerList -> {
                            screenState = ScreenState.Home
                        }
                        is ScreenState.WebDavServerList -> {
                            screenState = ScreenState.Home
                        }
                        is ScreenState.FtpSftpServerList -> {
                            screenState = ScreenState.Home
                        }
                        is ScreenState.NetworkBrowsing -> {
                            val cur = screenState as ScreenState.NetworkBrowsing
                            if (networkNavStack.isNotEmpty()) {
                                networkNavStack.removeAt(networkNavStack.lastIndex)
                                if (networkNavStack.isNotEmpty()) {
                                    val prev = networkNavStack.last()
                                    screenState = ScreenState.NetworkBrowsing(cur.credentials, prev.first, prev.second)
                                } else {
                                    screenState = when (cur.credentials.type) {
                                        "SMB" -> ScreenState.SmbServerList()
                                        "FTP", "SFTP" -> ScreenState.FtpSftpServerList()
                                        "GOOGLE_DRIVE" -> ScreenState.Home
                                        "ONEDRIVE" -> ScreenState.Home
                                        else -> ScreenState.WebDavServerList()
                                    }
                                }
                            } else {
                                screenState = when (cur.credentials.type) {
                                    "SMB" -> ScreenState.SmbServerList()
                                    "FTP", "SFTP" -> ScreenState.FtpSftpServerList()
                                    "GOOGLE_DRIVE" -> ScreenState.Home
                                    "ONEDRIVE" -> ScreenState.Home
                                    else -> ScreenState.WebDavServerList()
                                }
                            }
                        }
                    }
                }

                fun triggerBack() {
                    if (showExitDialog) {
                        showExitDialog = false
                        return
                    }
                    when (screenState) {
                        is ScreenState.Home -> {
                            showExitDialog = true
                        }
                        is ScreenState.Settings -> {
                            screenState = ScreenState.Home
                        }
                        is ScreenState.LicensePolicy -> {
                            screenState = ScreenState.Home
                        }
                        is ScreenState.ServerList -> {
                            screenState = ScreenState.Home
                        }
                        is ScreenState.SmbServerList -> {
                            screenState = ScreenState.Home
                        }
                        is ScreenState.WebDavServerList -> {
                            screenState = ScreenState.Home
                        }
                        is ScreenState.FtpSftpServerList -> {
                            screenState = ScreenState.Home
                        }
                        is ScreenState.NetworkBrowsing -> {
                            goBack()
                        }
                        is ScreenState.Playing -> {
                            // 0단계: 캐스팅 중이면 먼저 캐스팅 종료
                            if (isCasting.value) {
                                currentRenderer.value?.let { device ->
                                    rendererManager.stop(device) { _, _ -> }
                                }
                                isCasting.value = false
                                currentRenderer.value = null
                                localHttpServer?.clearSources()
                                return
                            }
                            // 1단계: 컨트롤이 보이면 먼저 숨김
                            if (isVideoControlVisible) {
                                isVideoControlVisible = false
                                return
                            }
                            // 2단계: 컨트롤 없는 상태에서 뒤로가기 → 이전 화면으로
                            isVideoControlVisible = true
                            val cur = screenState
                            if (cur is ScreenState.Playing) {
                                // networkBrowsingCredentials가 있으면 NetworkBrowsing으로 복귀
                                val creds = networkBrowsingCredentials
                                if (creds != null && networkNavStack.isNotEmpty()) {
                                    val prev = networkNavStack.last()
                                    screenState = ScreenState.NetworkBrowsing(creds, prev.first, prev.second)
                                } else if (browsingDeviceOverride != null && navStack.isNotEmpty()) {
                                    val prev = navStack.last()
                                    screenState = ScreenState.Browsing(browsingDeviceOverride!!, prev.containerId, prev.containerName)
                                } else if (localBrowsingActiveMode) {
                                    screenState = ScreenState.LocalBrowsing
                                } else if (externalVideoMode) {
                                    externalVideoMode = false
                                    screenState = ScreenState.Home
                                } else {
                                    screenState = ScreenState.ServerList
                                }
                            }
                        }
                        is ScreenState.Browsing -> {
                            goBack()
                        }
                        is ScreenState.LocalBrowsing -> {
                            if (localSelectedFolder != null) {
                                localSelectedFolder = null
                            } else {
                                screenState = ScreenState.Home
                            }
                        }
                    }
                }

                // ✅ SideEffect: Recomposition마다 최신 상태 반영 (Stale Closure 방지)
                SideEffect {
                    onBackPressAction = {
                        try {
                            android.util.Log.d("MainActivity", "[BACK] triggerBack 호출, 상태: $screenState")
                            triggerBack()
                        } catch (e: Throwable) {
                            android.util.Log.e("MainActivity", "[BACK] triggerBack 예외!", e)
                        }
                    }
                }

                val isTvMode = remember {
                    packageManager.hasSystemFeature("android.software.leanback_launcher") ||
                    packageManager.hasSystemFeature("android.hardware.type.television")
                }
                val context = androidx.compose.ui.platform.LocalContext.current

                val localPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions(),
                    onResult = { results ->
                        val allGranted = results.values.all { it }
                        if (allGranted || (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && results[Manifest.permission.READ_MEDIA_VIDEO] == true) || (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU && results[Manifest.permission.READ_EXTERNAL_STORAGE] == true)) {
                            localBrowsingActiveMode = true
                            screenState = ScreenState.LocalBrowsing
                        } else {
                            android.widget.Toast.makeText(context, "권한이 필요합니다.", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                )

                val dlnaPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions(),
                    onResult = { results ->
                        val allGranted = results.values.all { it }
                        if (allGranted || (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && results[Manifest.permission.NEARBY_WIFI_DEVICES] == true) || (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU && results[Manifest.permission.ACCESS_FINE_LOCATION] == true)) {
                            localBrowsingActiveMode = false
                            isSearchingServers = true
                            dlnaManager.search()
                            screenState = ScreenState.ServerList
                        } else {
                            android.widget.Toast.makeText(context, "근처 기기 검색 권한이 필요합니다.", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                )

                fun checkAndRequestPermission(permission: String, launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>, onGranted: () -> Unit) {
                    if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                        onGranted()
                    } else {
                        launcher.launch(arrayOf(permission))
                    }
                }



                // Removed LocalOnBackPressedDispatcherOwner logic as it's replaced by BackHandler(enabled=true) above

                if (showExitDialog) {
                    AlertDialog(
                        onDismissRequest = { showExitDialog = false },
                        title = { Text("앱 종료") },
                        text = { Text("앱을 종료 하시겠습니까?") },
                        confirmButton = {
                            TextButton(onClick = {
                                showExitDialog = false
                                allowFinish = true       // finish() 오버라이드 허용
                                this@MainActivity.finish()
                            }) {
                                Text("예")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showExitDialog = false }) {
                                Text("아니오")
                            }
                        }
                    )
                }

                if (showForegroundServicePermissionDialog) {
                    AlertDialog(
                        onDismissRequest = { showForegroundServicePermissionDialog = false },
                        title = { Text("백그라운드 재생 권한 안내") },
                        text = { Text("영상을 백그라운드에서 끊김 없이 재생하려면 포그라운드 서비스 권한이 필요합니다. 설정 화면에서 권한을 확인해 주세요.") },
                        confirmButton = {
                            TextButton(onClick = {
                                showForegroundServicePermissionDialog = false
                                try {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", packageName, null)
                                    }
                                    startActivity(intent)
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(context, "설정 화면을 열 수 없습니다.", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Text("설정으로 이동")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showForegroundServicePermissionDialog = false }) {
                                Text("나중에")
                            }
                        }
                    )
                }

                if (showPipPermissionDialog) {
                    AlertDialog(
                        onDismissRequest = { showPipPermissionDialog = false },
                        title = { Text("PIP(사진 속 사진) 권한 안내") },
                        text = { Text("다른 앱을 사용하는 중에도 영상을 작게 띄워 계속 시청할 수 있습니다. 이 기능을 사용하려면 '사진 속 사진' 권한 허용이 필요합니다.") },
                        confirmButton = {
                            TextButton(onClick = {
                                showPipPermissionDialog = false
                                try {
                                    val intent = Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS", Uri.parse("package:$packageName"))
                                    startActivity(intent)
                                } catch (e: Exception) {
                                    try {
                                        startActivity(Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS"))
                                    } catch (e2: Exception) {
                                        android.widget.Toast.makeText(context, "PIP 설정 화면을 열 수 없습니다.", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }) {
                                Text("설정으로 이동")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showPipPermissionDialog = false }) {
                                Text("나중에")
                            }
                        }
                    )
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    when (val state = screenState) {
                        is ScreenState.Home -> {
                            HomeScreen(
                                padding = innerPadding, 
                                onLocalClick = {
                                    checkAndRequestPermission(
                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE,
                                        localPermissionLauncher
                                    ) {
                                        localBrowsingActiveMode = true
                                        screenState = ScreenState.LocalBrowsing
                                    }
                                }, 
                                onDlnaClick = {
                                    val dlnaPerm = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) 
                                        "android.permission.NEARBY_WIFI_DEVICES" 
                                    else 
                                        Manifest.permission.ACCESS_FINE_LOCATION
                                    
                                    checkAndRequestPermission(dlnaPerm, dlnaPermissionLauncher) {
                                        localBrowsingActiveMode = false
                                        isSearchingServers = true
                                        dlnaManager.search()
                                        screenState = ScreenState.ServerList
                                    }
                                },
                                onSmbClick = {
                                    networkNavStack.clear()
                                    networkBrowsingCredentials = null
                                    screenState = ScreenState.SmbServerList()
                                },
                                onWebDavClick = {
                                    networkNavStack.clear()
                                    networkBrowsingCredentials = null
                                    screenState = ScreenState.WebDavServerList()
                                },
                                onFtpSftpClick = {
                                    networkNavStack.clear()
                                    networkBrowsingCredentials = null
                                    screenState = ScreenState.FtpSftpServerList()
                                },
                                onGoogleDriveSuccess = { account ->
                                    val navCreds = ServerCredentials(
                                        type = "GOOGLE_DRIVE",
                                        host = "drive.google.com",
                                        username = account.email ?: "Unknown",
                                        password = "",
                                        displayName = "Google Drive"
                                    )
                                    android.util.Log.d("GoogleDrive", "Authentication Success: ${account.email}")
                                    networkNavStack.clear()
                                    networkBrowsingCredentials = navCreds
                                    screenState = ScreenState.NetworkBrowsing(navCreds, "root", "내 드라이브")
                                },
                                onOneDriveClick = {
                                    coroutineScope.launch {
                                        val account = OneDriveAuthManager.signIn(this@MainActivity)
                                        if (account != null) {
                                            val navCreds = ServerCredentials(
                                                type = "ONEDRIVE",
                                                host = "onedrive.live.com",
                                                username = account.username ?: "Unknown",
                                                password = "",
                                                displayName = "OneDrive"
                                            )
                                            android.util.Log.d("OneDrive", "Authentication Success: ${account.username}")
                                            networkNavStack.clear()
                                            networkBrowsingCredentials = navCreds
                                            screenState = ScreenState.NetworkBrowsing(navCreds, "root", "내 OneDrive")
                                        } else {
                                            android.widget.Toast.makeText(this@MainActivity, "OneDrive 로그인이 실패하거나 취소되었습니다.", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onSettingsClick = { screenState = ScreenState.Settings },
                                onLicenseClick = { screenState = ScreenState.LicensePolicy },
                                isTvMode = isTvMode,
                                onZipClick = { codecZipPickerLauncher.launch("application/zip") },
                                codecInstallResultMessage = codecInstallResultMessage.value
                            )
                        }
                        is ScreenState.Settings -> {
                            PassthroughSettingsScreen(
                                isTvMode = isTvMode,
                                onBackClick = { triggerBack() }
                            )
                        }
                        is ScreenState.LicensePolicy -> {
                            LicensePolicyScreen(
                                isTvMode = isTvMode,
                                onBackClick = { triggerBack() }
                            )
                        }
                        is ScreenState.ServerList -> {
                            if (isSearchingServers) {
                                LaunchedEffect(Unit) {
                                    kotlinx.coroutines.delay(5000)
                                    isSearchingServers = false
                                }
                            }
                            ServerListScreen(
                                padding = innerPadding,
                                devices = devices,
                                isSearching = isSearchingServers,
                                isTvMode = isTvMode,
                                initialSelectedIndex = lastFocusedIndexMap["ServerList"] ?: 0,
                                onRefresh = {
                                    isSearchingServers = true
                                    dlnaManager.search()
                                },
                                onDeviceClick = { device ->
                                    isSearchingServers = false
                                    browsingDeviceOverride = device
                                    // Save index
                                    val idx = devices.indexOfFirst { it.identity.udn == device.identity.udn }
                                    if (idx != -1) lastFocusedIndexMap["ServerList"] = idx
                                    
                                    screenState = ScreenState.Browsing(device, "0", device.details?.friendlyName ?: "Root")
                                    navStack.clear()
                                    navStack.add(NavHistoryItem("0", "Root"))
                                    loadContainer(device, "0")
                                },
                                onBackClick = { triggerBack() }
                            )
                        }
                        is ScreenState.Browsing -> {
                            browsingDeviceOverride = state.device
                            localBrowsingActiveMode = false
                            BrowseScreen(
                                padding = innerPadding,
                                state = state,
                                items = browseItems,
                                isLoading = isLoading,
                                errorMessage = errorMessage,
                                isTvMode = isTvMode,
                                sortOrder = networkSortOrder,
                                onSortOrderChange = { networkSortOrder = it },
                                initialSelectedIndex = lastFocusedIndexMap[state.currentContainerId] ?: 0,
                                onBackClick = { triggerBack() },
                                onItemClick = { item, index ->
                                    // Save current index before navigating
                                    lastFocusedIndexMap[state.currentContainerId] = index

                                    if (item is Container) {
                                        navStack.add(NavHistoryItem(item.id, item.title))
                                        screenState = ScreenState.Browsing(state.device, item.id, item.title)
                                        loadContainer(state.device, item.id)
                                    } else if (item is Item) {
                                        val resources = item.resources
                                        val videoRes = resources.find { it.protocolInfo.contentFormat.startsWith("video/") || it.protocolInfo.contentFormat.startsWith("audio/") } ?: item.firstResource
                                        
                                        var subResUrl: String? = null
                                        var subResExt: String? = null
                                        
                                        // --- VERBOSE LOGGING START ---
                                        android.util.Log.d("SubtitleSearch", "========================================")
                                        android.util.Log.d("SubtitleSearch", "Inspecting Video Item: ${item.title}")
                                        android.util.Log.d("SubtitleSearch", "Video URL: ${videoRes?.value}")
                                        android.util.Log.d("SubtitleSearch", "DLNA Resources (${resources.size}):")
                                        resources.forEachIndexed { index, r ->
                                            android.util.Log.d("SubtitleSearch", "  Res[$index]: format=${r.protocolInfo?.contentFormat}")
                                            android.util.Log.d("SubtitleSearch", "  Res[$index]: addInfo=${r.protocolInfo?.additionalInfo}")
                                            android.util.Log.d("SubtitleSearch", "  Res[$index]: value=${r.value}")
                                        }
                                        android.util.Log.d("SubtitleSearch", "DLNA Properties (${item.properties.size}):")
                                        item.properties.forEach { prop ->
                                            android.util.Log.d("SubtitleSearch", "  Prop: ${prop.descriptorName} = ${prop.value}")
                                        }
                                        android.util.Log.d("SubtitleSearch", "========================================")
                                        // --- VERBOSE LOGGING END ---

                                        // 1. Look for subtitle embedded in the item's resources
                                        val embeddedSub = resources.find { 
                                            val fmt = it.protocolInfo.contentFormat.lowercase()
                                            fmt.contains("srt") || fmt.contains("smi") || fmt.contains("sub") || it.value.lowercase().endsWith(".srt") || it.value.lowercase().endsWith(".smi")
                                        }
                                        if (embeddedSub != null) {
                                            subResUrl = embeddedSub.value
                                            val fmt = embeddedSub.protocolInfo.contentFormat.lowercase()
                                            subResExt = if (embeddedSub.value.lowercase().endsWith(".smi") || fmt.contains("smi") || fmt.contains("sami")) "smi" else "srt"
                                            android.util.Log.d("SubtitleSearch", "Found embedded subtitle: $subResUrl (ext: $subResExt)")
                                        }
                                        
                                        // 2. Custom check for Samsung sec:CaptionInfo (common on Synology/NAS)
                                        if (subResUrl == null) {
                                            val captionInfo = item.properties.find { it.descriptorName.contains("CaptionInfo") || it.descriptorName.contains("captionInfo") }
                                            if (captionInfo != null && captionInfo.value != null) {
                                                subResUrl = captionInfo.value.toString()
                                                val lurl = subResUrl.lowercase()
                                                subResExt = if (lurl.endsWith(".smi") || lurl.contains("smi")) "smi" else "srt"
                                                android.util.Log.d("SubtitleSearch", "Found sec:CaptionInfo subtitle: $subResUrl (ext: $subResExt)")
                                            }
                                        }
                                        
                                        // 3. If not found, look for a sibling item with the same base name
                                        if (subResUrl == null && item.title != null) {
                                            val videoTitle = item.title!!
                                            val lastDotIndex = videoTitle.lastIndexOf('.')
                                            val baseName = if (lastDotIndex > 0) videoTitle.substring(0, lastDotIndex) else videoTitle
                                            
                                            val siblingSubItem = browseItems.filterIsInstance<Item>().find { sibling ->
                                                val siblingName = sibling.title ?: ""
                                                val sLastDotIndex = siblingName.lastIndexOf('.')
                                                val siblingBase = if (sLastDotIndex > 0) siblingName.substring(0, sLastDotIndex) else siblingName
                                                
                                                val valUrl = sibling.firstResource?.value?.lowercase() ?: ""
                                                val ext = when {
                                                    valUrl.substringAfterLast("?").substringBeforeLast("#").endsWith(".smi") -> "smi"
                                                    valUrl.substringAfterLast("?").substringBeforeLast("#").endsWith(".srt") -> "srt"
                                                    valUrl.substringAfterLast("?").substringBeforeLast("#").endsWith(".vtt") -> "vtt"
                                                    valUrl.substringAfterLast("?").substringBeforeLast("#").endsWith(".sub") -> "sub"
                                                    valUrl.substringAfterLast("?").substringBeforeLast("#").endsWith(".ass") -> "ass"
                                                    sLastDotIndex > 0 -> siblingName.substring(sLastDotIndex + 1).lowercase()
                                                    else -> ""
                                                }
                                                val isSubtitleExt = ext == "smi" || ext == "srt" || ext == "vtt" || ext == "sub" || ext == "ass"
                                                val isMatch = siblingBase == baseName || siblingBase.startsWith(baseName) || baseName.startsWith(siblingBase)
                                                if (isMatch && isSubtitleExt) {
                                                    subResExt = ext
                                                    true
                                                } else false
                                            }
                                            if (siblingSubItem != null) {
                                                subResUrl = siblingSubItem.firstResource?.value
                                                android.util.Log.d("SubtitleSearch", "Found sibling Subtitle URL: $subResUrl with Ext: $subResExt")
                                            }
                                        }

                                        val url = videoRes?.value
                                        if (url != null) {
                                            // Always use the async downloader to handle SMI encoding issues (EUC-KR to UTF-8) and caching.
                                            isLoading = true
                                            coroutineScope.launch(Dispatchers.IO) {
                                                var verifiedLocalSubPath: String? = null
                                                var verifiedExt: String? = null
                                                
                                                fun tryDownloadSub(targetUrl: String, ext: String): Boolean {
                                                    try {
                                                        android.util.Log.d("SubtitleSearch", "Fetching subtitle: $targetUrl")
                                                        val conn = java.net.URL(targetUrl).openConnection() as java.net.HttpURLConnection
                                                        conn.requestMethod = "GET"
                                                        conn.connectTimeout = 3000
                                                        conn.readTimeout = 5000
                                                        if (conn.responseCode !in 200..299) {
                                                            android.util.Log.d("SubtitleSearch", "Fetch failed: HTTP ${conn.responseCode}")
                                                            return false
                                                        }
                                                        
                                                        val contentType = conn.contentType?.lowercase() ?: ""
                                                        if (contentType.contains("video/")) {
                                                            android.util.Log.d("SubtitleSearch", "Fetch failed: Content-Type is video")
                                                            return false
                                                        }
                                                        val contentLength = conn.contentLengthLong
                                                        if (contentLength > 20_000_000) {
                                                            android.util.Log.d("SubtitleSearch", "Fetch failed: File too large")
                                                            return false // > 20MB is suspicious
                                                        }
                                                        
                                                        val bytes = conn.inputStream.readBytesWithLimit(10 * 1024 * 1024)
                                                        if (bytes.isEmpty()) {
                                                            android.util.Log.d("SubtitleSearch", "Fetch failed: Empty body")
                                                            return false
                                                        }
                                                        
                                                        val sniffEnd = Math.min(bytes.size, 4096)
                                                        val sniffText = String(bytes.copyOfRange(0, sniffEnd), kotlin.text.Charsets.UTF_8).lowercase()
                                                        val isLikelySubtitle = sniffText.contains("<sami") || sniffText.contains("-->") || sniffText.contains("[script info]") || sniffText.contains("dialogue:") || sniffText.contains("webvtt")
                                                        if (!isLikelySubtitle && bytes.size > 1024 * 1024) {
                                                            android.util.Log.d("SubtitleSearch", "Fetch failed: Doesn't look like a subtitle")
                                                            return false
                                                        }
                                                        
                                                        // Detect UTF-16 BOM or typical starting bytes
                                                        var text = ""
                                                        val b0 = if (bytes.isNotEmpty()) bytes[0].toInt() and 0xFF else 0
                                                        val b1 = if (bytes.size > 1) bytes[1].toInt() and 0xFF else 0
                                                        
                                                        if (b0 == 0xFF && b1 == 0xFE) {
                                                            text = String(bytes, kotlin.text.Charsets.UTF_16LE)
                                                        } else if (b0 == 0xFE && b1 == 0xFF) {
                                                            text = String(bytes, kotlin.text.Charsets.UTF_16BE)
                                                        } else if (b0 == 0x3C && b1 == 0x00) {
                                                            // '<' in UTF-16LE without BOM
                                                            text = String(bytes, kotlin.text.Charsets.UTF_16LE)
                                                        } else if (b0 == 0x00 && b1 == 0x3C) {
                                                            // '<' in UTF-16BE without BOM
                                                            text = String(bytes, kotlin.text.Charsets.UTF_16BE)
                                                        } else {
                                                            text = String(bytes, kotlin.text.Charsets.UTF_8)
                                                            // If it looks broken or is SMI without <sami, try EUC-KR
                                                            if (text.contains("\uFFFD") || (!text.contains("<sami", true) && ext == "smi")) {
                                                                text = String(bytes, java.nio.charset.Charset.forName("EUC-KR"))
                                                            }
                                                        }
                                                        
                                                        // Force ExoPlayer SamiDecoder to parse as UTF-8 since we just saved it as UTF-8
                                                        text = text.replace(Regex("charset=euc-kr", RegexOption.IGNORE_CASE), "charset=utf-8")
                                                        text = text.replace(Regex("charset=\"euc-kr\"", RegexOption.IGNORE_CASE), "charset=\"utf-8\"")
                                                        text = text.replace(Regex("charset=cp949", RegexOption.IGNORE_CASE), "charset=utf-8")
                                                        
                                                        val lowerText = text.lowercase()
                                                        val isSmi = lowerText.contains("<sami")
                                                        val isSrt = lowerText.contains("-->")
                                                        val isAss = lowerText.contains("[script info]") || lowerText.contains("dialogue:") || ext == "ass"
                                                        
                                                        if ((ext == "smi" && isSmi) || isSmi) {
                                                            val srtText = convertSmiToSrt(text)
                                                            val cacheFile = java.io.File(this@MainActivity.cacheDir, "cached_sub_${System.currentTimeMillis()}.srt")
                                                            cacheFile.writeText(srtText, kotlin.text.Charsets.UTF_8)
                                                            verifiedLocalSubPath = "file://" + cacheFile.absolutePath
                                                            verifiedExt = "srt"
                                                            android.util.Log.d("SubtitleSearch", "Successfully downloaded and converted SMI to SRT at $verifiedLocalSubPath")
                                                            return true
                                                        } else if ((ext == "srt" && isSrt) || isSrt) {
                                                            val cacheFile = java.io.File(this@MainActivity.cacheDir, "cached_sub_${System.currentTimeMillis()}.srt")
                                                            cacheFile.writeText(text, kotlin.text.Charsets.UTF_8)
                                                            verifiedLocalSubPath = "file://" + cacheFile.absolutePath
                                                            verifiedExt = "srt"
                                                            android.util.Log.d("SubtitleSearch", "Successfully downloaded SRT to $verifiedLocalSubPath")
                                                            return true
                                                        } else if (isAss) {
                                                            val cacheFile = java.io.File(this@MainActivity.cacheDir, "cached_sub_${System.currentTimeMillis()}.ass")
                                                            cacheFile.writeText(text, kotlin.text.Charsets.UTF_8)
                                                            verifiedLocalSubPath = "file://" + cacheFile.absolutePath
                                                            verifiedExt = "ass"
                                                            android.util.Log.d("SubtitleSearch", "Successfully downloaded ASS to $verifiedLocalSubPath")
                                                            return true
                                                        }
                                                        val snippet = text.take(200).replace('\n', ' ')
                                                        android.util.Log.d("SubtitleSearch", "Fetch failed: Format not recognized. Snippet: $snippet")
                                                        return false
                                                    } catch (e: Exception) {
                                                        android.util.Log.d("SubtitleSearch", "Fetch failed: ${e.message}")
                                                        return false
                                                    }
                                                }

                                                var found = false
                                                // 1. Try downloading previously discovered subtitle URL (embedded, CaptionInfo, or sibling)
                                                if (subResUrl != null) {
                                                    android.util.Log.d("SubtitleSearch", "Trying provided sub URL: $subResUrl")
                                                    found = tryDownloadSub(subResUrl!!, subResExt ?: "srt")
                                                }
                                                
                                                // 2. If not found or failed, probe the hidden URLs with the same base name
                                                if (!found) {
                                                    android.util.Log.d("SubtitleSearch", "Probing hidden subtitle URLs for base name...")
                                                    val lastDotIndex = url.lastIndexOf('.')
                                                    if (lastDotIndex > 0) {
                                                        val baseUrl = url.substring(0, lastDotIndex)
                                                        if (tryDownloadSub("$baseUrl.ass", "ass")) found = true
                                                        else if (tryDownloadSub("$baseUrl.srt", "srt")) found = true
                                                        else if (tryDownloadSub("$baseUrl.smi", "smi")) found = true
                                                    }
                                                }
                                                
                                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                                    isLoading = false
                                                    // Only use the subResUrl directly if tryDownloadSub failed completely, but usually we just pass the verified offline file
                                                    val finalUrlToPass = if (found) verifiedLocalSubPath else subResUrl
                                                    val finalExtToPass = if (found) verifiedExt else subResExt
                                                    
                                                    // 플레이리스트 구성 (같은 폴더의 동영상 아이템 - 자막 파일 제외)
                                                    val subtitleExts = setOf("srt", "smi", "ass", "vtt", "sub", "ssa", "idx")
                                                    val videoItems = browseItems.filterIsInstance<Item>().filter { bi ->
                                                        val titleLower = bi.title?.lowercase() ?: ""
                                                        val titleExt = titleLower.substringAfterLast(".", "")
                                                        // 자막 확장자를 가진 아이템은 제외
                                                        if (subtitleExts.contains(titleExt)) return@filter false
                                                        val fmt = bi.resources.find { r -> r.protocolInfo.contentFormat.startsWith("video/") || r.protocolInfo.contentFormat.startsWith("audio/") }
                                                        fmt != null || bi.firstResource != null
                                                    }
                                                    val playlist = videoItems.map { vi ->
                                                        val vRes = vi.resources.find { r -> r.protocolInfo.contentFormat.startsWith("video/") || r.protocolInfo.contentFormat.startsWith("audio/") } ?: vi.firstResource
                                                        
                                                        // FIND SUBTITLE FOR THIS `vi`
                                                        var viSubResUrl: String? = null
                                                        var viSubResExt: String? = null
                                                        
                                                        val viEmbeddedSub = vi.resources.find { 
                                                            val fmt = it.protocolInfo.contentFormat.lowercase()
                                                            fmt.contains("srt") || fmt.contains("smi") || fmt.contains("sub") || it.value.lowercase().endsWith(".srt") || it.value.lowercase().endsWith(".smi")
                                                        }
                                                        if (viEmbeddedSub != null) {
                                                            viSubResUrl = viEmbeddedSub.value
                                                            val fmt = viEmbeddedSub.protocolInfo.contentFormat.lowercase()
                                                            viSubResExt = if (viEmbeddedSub.value.lowercase().endsWith(".smi") || fmt.contains("smi") || fmt.contains("sami")) "smi" else "srt"
                                                        }
                                                        
                                                        if (viSubResUrl == null) {
                                                            val captionInfo = vi.properties.find { it.descriptorName.contains("CaptionInfo") || it.descriptorName.contains("captionInfo") }
                                                            if (captionInfo != null && captionInfo.value != null) {
                                                                viSubResUrl = captionInfo.value.toString()
                                                                val lurl = viSubResUrl.lowercase()
                                                                viSubResExt = if (lurl.endsWith(".smi") || lurl.contains("smi")) "smi" else "srt"
                                                            }
                                                        }
                                                        
                                                        if (viSubResUrl == null && vi.title != null) {
                                                            val videoTitle = vi.title!!
                                                            val lastDotIndex = videoTitle.lastIndexOf('.')
                                                            val baseName = if (lastDotIndex > 0) videoTitle.substring(0, lastDotIndex) else videoTitle
                                                            
                                                            val siblingSubItem = browseItems.filterIsInstance<Item>().find { sibling ->
                                                                if (sibling == vi) return@find false
                                                                val siblingName = sibling.title ?: ""
                                                                val sLastDotIndex = siblingName.lastIndexOf('.')
                                                                val siblingBase = if (sLastDotIndex > 0) siblingName.substring(0, sLastDotIndex) else siblingName
                                                                
                                                                val valUrl = sibling.firstResource?.value?.lowercase() ?: ""
                                                                val ext = when {
                                                                    valUrl.substringAfterLast("?").substringBeforeLast("#").endsWith(".smi") -> "smi"
                                                                    valUrl.substringAfterLast("?").substringBeforeLast("#").endsWith(".srt") -> "srt"
                                                                    valUrl.substringAfterLast("?").substringBeforeLast("#").endsWith(".vtt") -> "vtt"
                                                                    valUrl.substringAfterLast("?").substringBeforeLast("#").endsWith(".sub") -> "sub"
                                                                    valUrl.substringAfterLast("?").substringBeforeLast("#").endsWith(".ass") -> "ass"
                                                                    sLastDotIndex > 0 -> siblingName.substring(sLastDotIndex + 1).lowercase()
                                                                    else -> ""
                                                                }
                                                                val isSubtitleExt = ext == "smi" || ext == "srt" || ext == "vtt" || ext == "sub" || ext == "ass"
                                                                val isMatch = siblingBase == baseName || siblingBase.startsWith(baseName) || baseName.startsWith(siblingBase)
                                                                isSubtitleExt && isMatch
                                                            }
                                                            if (siblingSubItem != null) {
                                                                viSubResUrl = siblingSubItem.firstResource?.value
                                                                val siblingName = siblingSubItem.title ?: ""
                                                                viSubResExt = siblingName.substringAfterLast(".", "").lowercase().takeIf { it.isNotEmpty() } ?: "srt"
                                                            }
                                                        }

                                                        PlaylistItem(
                                                            videoUrl = vRes?.value ?: "",
                                                            title = vi.title ?: "Unknown",
                                                            subtitleUrl = viSubResUrl,
                                                            subtitleExtension = viSubResExt
                                                        )
                                                    }
                                                    val playlistIndex = playlist.indexOfFirst { it.videoUrl == url }
                                                    
                                                    screenState = ScreenState.Playing(
                                                        videoUrl = url,
                                                        title = item.title ?: "Playing",
                                                        subtitleUrl = finalUrlToPass,
                                                        subtitleExtension = finalExtToPass,
                                                        playlist = playlist,
                                                        currentIndex = playlistIndex
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            )
                        }
                        is ScreenState.LocalBrowsing -> {
                            browsingDeviceOverride = null // Reset DLNA device override
                            localBrowsingActiveMode = true
                            LocalBrowserScreen(
                                padding = innerPadding,
                                isTvMode = isTvMode,
                                initialSelectedIndex = lastFocusedIndexMap["LocalBrowsing"] ?: 0,
                                sortOrder = localSortOrder,
                                onSortOrderChange = { localSortOrder = it },
                                viewMode = localViewMode,
                                onViewModeChange = { localViewMode = it },
                                selectedFolder = localSelectedFolder,
                                onSelectedFolderChange = { localSelectedFolder = it },
                                onBackClick = { triggerBack() },
                                onItemClick = { videoItem, index, allVideos ->
                                    // Save index
                                    lastFocusedIndexMap["LocalBrowsing"] = index
                                    
                                    // 플레이리스트는 자막 검색 없이 즉시 구성 (빠른 재생 시작)
                                    val playlist = allVideos.map { v ->
                                        PlaylistItem(
                                            videoUrl = v.uri.toString(),
                                            title = v.title,
                                            subtitleUrl = null,
                                            subtitleExtension = null
                                        )
                                    }
                                    
                                    // IO 스레드에서 현재 동영상의 자막만 검색 (ANR 방지)
                                    coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        // 현재 선택된 동영상의 자막 검색 & 변환
                                        var localSubUrl: String? = null
                                        var localSubExt: String? = null
                                        
                                        android.util.Log.d("SubtitleSearch", "========================================")
                                        android.util.Log.d("SubtitleSearch", "Inspecting Local Video: ${videoItem.title}")
                                        
                                        if (videoItem.path != null) {
                                            try {
                                                val file = java.io.File(videoItem.path)
                                                val parent = file.parentFile
                                                val baseName = file.nameWithoutExtension
                                                android.util.Log.d("SubtitleSearch", "Local path: ${file.absolutePath}, BaseName: $baseName")
                                                
                                                if (parent != null && parent.exists() && parent.isDirectory) {
                                                    val subs = parent.listFiles { _, name -> 
                                                        val lower = name.lowercase()
                                                        val isSubExt = lower.endsWith(".smi") || lower.endsWith(".srt") || lower.endsWith(".vtt") || lower.endsWith(".ass")
                                                        val itBase = name.substringBeforeLast(".")
                                                        isSubExt && (itBase == baseName || (itBase.startsWith(baseName) && itBase.getOrNull(baseName.length) == '.'))
                                                    }
                                                    if (subs != null && subs.isNotEmpty()) {
                                                        val subFile = subs.find { it.extension.equals("ass", true) }
                                                            ?: subs.find { it.extension.equals("srt", true) }
                                                            ?: subs.find { it.extension.equals("smi", true) }
                                                            ?: subs[0]
                                                        localSubUrl = "file://" + subFile.absolutePath
                                                        localSubExt = subFile.extension.lowercase()
                                                        android.util.Log.d("SubtitleSearch", "Found local subtitle: $localSubUrl (Ext: $localSubExt)")
                                                        
                                                        // Convert SMI/Parse encoding
                                                        try {
                                                            if (subFile.length() > 10 * 1024 * 1024) throw Exception("Subtitle too large")
                                                            val bytes = subFile.readBytes()
                                                            if (bytes.isNotEmpty()) {
                                                                val sniffEnd = Math.min(bytes.size, 4096)
                                                                val sniffText = String(bytes.copyOfRange(0, sniffEnd), kotlin.text.Charsets.UTF_8).lowercase()
                                                                val isLikelySubtitle = sniffText.contains("<sami") || sniffText.contains("-->") || sniffText.contains("[script info]") || sniffText.contains("dialogue:") || sniffText.contains("webvtt")
                                                                if (!isLikelySubtitle && bytes.size > 1024 * 1024) throw Exception("Doesn't look like a subtitle")
                                                                
                                                                var text = ""
                                                                val b0 = bytes[0].toInt() and 0xFF
                                                                val b1 = if (bytes.size > 1) bytes[1].toInt() and 0xFF else 0
                                                                
                                                                if (b0 == 0xFF && b1 == 0xFE) {
                                                                    text = String(bytes, kotlin.text.Charsets.UTF_16LE)
                                                                } else if (b0 == 0xFE && b1 == 0xFF) {
                                                                    text = String(bytes, kotlin.text.Charsets.UTF_16BE)
                                                                } else if (b0 == 0x3C && b1 == 0x00) {
                                                                    text = String(bytes, kotlin.text.Charsets.UTF_16LE)
                                                                } else if (b0 == 0x00 && b1 == 0x3C) {
                                                                    text = String(bytes, kotlin.text.Charsets.UTF_16BE)
                                                                } else {
                                                                    text = String(bytes, kotlin.text.Charsets.UTF_8)
                                                                    if (text.contains("\uFFFD") || (!text.contains("<sami", true) && localSubExt == "smi")) {
                                                                        text = String(bytes, java.nio.charset.Charset.forName("EUC-KR"))
                                                                    }
                                                                }
                                                                
                                                                text = text.replace(Regex("charset=euc-kr", RegexOption.IGNORE_CASE), "charset=utf-8")
                                                                text = text.replace(Regex("charset=\"euc-kr\"", RegexOption.IGNORE_CASE), "charset=\"utf-8\"")
                                                                text = text.replace(Regex("charset=cp949", RegexOption.IGNORE_CASE), "charset=utf-8")
                                                                
                                                                val lowerText = text.lowercase()
                                                                val isSmi = lowerText.contains("<sami")
                                                                val isSrt = lowerText.contains("-->")
                                                                val isAss = lowerText.contains("[script info]") || lowerText.contains("dialogue:") || localSubExt == "ass"
                                                                
                                                                if ((localSubExt == "smi" && isSmi) || isSmi) {
                                                                    val srtText = convertSmiToSrt(text)
                                                                    val cacheFile = java.io.File(this@MainActivity.cacheDir, "cached_local_sub.srt")
                                                                    cacheFile.writeText(srtText, kotlin.text.Charsets.UTF_8)
                                                                    localSubUrl = "file://" + cacheFile.absolutePath
                                                                    localSubExt = "srt"
                                                                    android.util.Log.d("SubtitleSearch", "Successfully converted Local SMI to SRT at $localSubUrl")
                                                                } else if ((localSubExt == "srt" && isSrt) || isSrt) {
                                                                    val cacheFile = java.io.File(this@MainActivity.cacheDir, "cached_local_sub.srt")
                                                                    cacheFile.writeText(text, kotlin.text.Charsets.UTF_8)
                                                                    localSubUrl = "file://" + cacheFile.absolutePath
                                                                    localSubExt = "srt"
                                                                    android.util.Log.d("SubtitleSearch", "Successfully saved Local SRT to cache $localSubUrl")
                                                                } else if (isAss) {
                                                                    val cacheFile = java.io.File(this@MainActivity.cacheDir, "cached_local_sub.ass")
                                                                    cacheFile.writeText(text, kotlin.text.Charsets.UTF_8)
                                                                    localSubUrl = "file://" + cacheFile.absolutePath
                                                                    localSubExt = "ass"
                                                                    android.util.Log.d("SubtitleSearch", "Successfully saved Local ASS to cache $localSubUrl")
                                                                }
                                                            }
                                                        } catch (e: Throwable) {
                                                            android.util.Log.d("SubtitleSearch", "Error parsing local subtitle: ${e.message}")
                                                        }
                                                    } else {
                                                        android.util.Log.d("SubtitleSearch", "No matching subtitle found in directory.")
                                                    }
                                                } else {
                                                    android.util.Log.d("SubtitleSearch", "Parent directory not accessible.")
                                                }
                                            } catch (e: Throwable) {
                                                android.util.Log.d("SubtitleSearch", "Error searching local subtitle: ${e.message}")
                                            }
                                        } else {
                                            android.util.Log.d("SubtitleSearch", "Video path is null, cannot search local subtitles.")
                                        }
                                        
                                        android.util.Log.d("SubtitleSearch", "========================================")
                                        
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            screenState = ScreenState.Playing(
                                                videoUrl = videoItem.uri.toString(),
                                                title = videoItem.title,
                                                subtitleUrl = localSubUrl,
                                                subtitleExtension = localSubExt,
                                                playlist = playlist,
                                                currentIndex = index
                                            )
                                        }
                                    }
                                }
                            )
                        }
                        is ScreenState.SmbServerList -> {
                            networkBrowsingCredentials = null
                            localBrowsingActiveMode = false
                            browsingDeviceOverride = null
                            NetworkServerListScreen(
                                serverType = "SMB",
                                isTvMode = isTvMode,
                                onBackClick = { triggerBack() },
                                onConnect = { creds ->
                                    networkBrowsingCredentials = creds
                                    val rootPath = creds.host.trimEnd('/') + "/"
                                    networkNavStack.clear()
                                    networkNavStack.add(Pair(rootPath, creds.displayName.ifBlank { creds.host }))
                                    screenState = ScreenState.NetworkBrowsing(creds, rootPath, creds.displayName.ifBlank { creds.host })
                                }
                            )
                        }
                        is ScreenState.WebDavServerList -> {
                            networkBrowsingCredentials = null
                            localBrowsingActiveMode = false
                            browsingDeviceOverride = null
                            NetworkServerListScreen(
                                serverType = "WEBDAV",
                                isTvMode = isTvMode,
                                onBackClick = { triggerBack() },
                                onConnect = { creds ->
                                    networkBrowsingCredentials = creds
                                    val rootPath = creds.host.trimEnd('/')
                                    networkNavStack.clear()
                                    networkNavStack.add(Pair(rootPath, creds.displayName.ifBlank { creds.host }))
                                    screenState = ScreenState.NetworkBrowsing(creds, rootPath, creds.displayName.ifBlank { creds.host })
                                }
                            )
                        }
                        is ScreenState.FtpSftpServerList -> {
                            networkBrowsingCredentials = null
                            localBrowsingActiveMode = false
                            browsingDeviceOverride = null
                            NetworkServerListScreen(
                                serverType = "FTP_SFTP",
                                isTvMode = isTvMode,
                                onBackClick = { triggerBack() },
                                onConnect = { creds ->
                                    networkBrowsingCredentials = creds
                                    val rootPath = if (creds.type == "SFTP") "." else "/"
                                    networkNavStack.clear()
                                    networkNavStack.add(Pair(rootPath, creds.displayName.ifBlank { creds.host }))
                                    screenState = ScreenState.NetworkBrowsing(creds, rootPath, creds.displayName.ifBlank { creds.host })
                                }
                            )
                        }
                        is ScreenState.NetworkBrowsing -> {
                            browsingDeviceOverride = null
                            localBrowsingActiveMode = false
                            NetworkBrowseScreen(
                                credentials = state.credentials,
                                currentPath = state.currentPath,
                                currentPathName = state.currentPathName,
                                isTvMode = isTvMode,
                                sortOrder = networkSortOrder,
                                onSortOrderChange = { networkSortOrder = it },
                                onBackClick = { triggerBack() },
                                onFolderClick = { path, name ->
                                    networkNavStack.add(Pair(path, name))
                                    screenState = ScreenState.NetworkBrowsing(state.credentials, path, name)
                                },
                                onVideoClick = { url, title, subUrl, subExt, playlist, currentIndex, httpHeaders ->
                                    networkBrowsingCredentials = state.credentials
                                    screenState = ScreenState.Playing(
                                        videoUrl = url,
                                        title = title,
                                        subtitleUrl = subUrl,
                                        subtitleExtension = subExt,
                                        playlist = playlist,
                                        currentIndex = currentIndex,
                                        ftpEncoding = if (state.credentials.type == "FTP") state.credentials.encoding else "AUTO",
                                        httpHeaders = httpHeaders
                                    )
                                }
                            )
                        }
                        is ScreenState.Playing -> {
                            key(state.videoUrl) {
                                VideoPlayerScreen(
                                    padding = innerPadding,
                                    videoUrl = state.videoUrl,
                                    subtitleUrl = state.subtitleUrl,
                                    subtitleExtension = state.subtitleExtension,
                                    title = state.title,
                                    isControlVisible = isVideoControlVisible,
                                    onControlVisibilityChange = { isVideoControlVisible = it },
                                    httpHeaders = state.httpHeaders,
                                    onClose = {
                                        isVideoControlVisible = true // 다음 재생 시 초기화
                                        val creds = networkBrowsingCredentials
                                        if (creds != null && networkNavStack.isNotEmpty()) {
                                            val prev = networkNavStack.last()
                                            screenState = ScreenState.NetworkBrowsing(creds, prev.first, prev.second)
                                        } else if (browsingDeviceOverride != null && navStack.isNotEmpty()) {
                                            val prev = navStack.last()
                                            screenState = ScreenState.Browsing(browsingDeviceOverride!!, prev.containerId, prev.containerName)
                                        } else if (localBrowsingActiveMode) {
                                            screenState = ScreenState.LocalBrowsing
                                        } else if (externalVideoMode) {
                                            externalVideoMode = false
                                            screenState = ScreenState.Home
                                        } else {
                                            screenState = ScreenState.ServerList
                                        }
                                    },
                                    playlist = state.playlist,
                                    currentIndex = state.currentIndex,
                                    ftpEncoding = state.ftpEncoding,
                                    onPlayNext = { nextItem ->
                                        val nextIndex = state.currentIndex + 1
                                        android.util.Log.d("AutoPlay", "Playing next: ${nextItem.title} (index=$nextIndex)")
                                        android.util.Log.d("AutoPlay", "  subtitleUrl=${nextItem.subtitleUrl}")
                                        android.util.Log.d("AutoPlay", "  subtitleExt=${nextItem.subtitleExtension}")
                                        screenState = ScreenState.Playing(
                                            videoUrl = nextItem.videoUrl,
                                            title = nextItem.title,
                                            subtitleUrl = nextItem.subtitleUrl,
                                            subtitleExtension = nextItem.subtitleExtension,
                                            playlist = state.playlist,
                                            currentIndex = nextIndex,
                                            ftpEncoding = state.ftpEncoding
                                        )
                                    },
                                    onPlayPrevious = { prevItem ->
                                        val prevIndex = state.currentIndex - 1
                                        android.util.Log.d("AutoPlay", "Playing previous: ${prevItem.title} (index=$prevIndex)")
                                        screenState = ScreenState.Playing(
                                            videoUrl = prevItem.videoUrl,
                                            title = prevItem.title,
                                            subtitleUrl = prevItem.subtitleUrl,
                                            subtitleExtension = prevItem.subtitleExtension,
                                            playlist = state.playlist,
                                            currentIndex = prevIndex,
                                            ftpEncoding = state.ftpEncoding
                                        )
                                    },
                                    // ── 캐스팅 콜백 ──
                                    rendererDevices = rendererDevices.toList(),
                                    isCasting = isCasting.value,
                                    currentRenderer = currentRenderer.value,
                                    onCastToDevice = { device, actualSubtitleUrl, positionMs ->
                                        val httpServer = localHttpServer ?: return@VideoPlayerScreen
                                        val credentials = networkBrowsingCredentials
                                        val videoUrl = state.videoUrl
                                        val videoTitle = state.title
                                        val effectiveSubUrl = actualSubtitleUrl ?: state.subtitleUrl
                                        // 네트워크 파일 크기 조회가 I/O를 수행하므로 백그라운드에서 실행
                                        Thread {
                                            android.util.Log.d("Casting", "━━━ 캐스팅 시작 (백그라운드) ━━━")
                                            android.util.Log.d("Casting", "videoUrl: $videoUrl")
                                            android.util.Log.d("Casting", "effectiveSubUrl: $effectiveSubUrl")
                                            android.util.Log.d("Casting", "credentials: ${if (credentials != null) "있음(user=${credentials.username})" else "없음"}")
                                            try {
                                                val (streamUrl, streamSubUrl) = httpServer.getStreamableUrl(
                                                    videoUrl,
                                                    effectiveSubUrl,
                                                    credentials
                                                )
                                                if (streamUrl.isBlank()) {
                                                    android.util.Log.e("Casting", "스트리밍 URL을 생성할 수 없습니다")
                                                    return@Thread
                                                }
                                                android.util.Log.d("Casting", "영상 스트림 URL: $streamUrl")
                                                android.util.Log.d("Casting", "자막 스트림 URL: $streamSubUrl")
                                                rendererManager.castToDevice(
                                                    device = device,
                                                    mediaUrl = streamUrl,
                                                    title = videoTitle,
                                                    subtitleUrl = streamSubUrl,
                                                    startPositionMs = positionMs,
                                                    onSuccess = {
                                                        runOnUiThread {
                                                            isCasting.value = true
                                                            currentRenderer.value = device
                                                        }
                                                    },
                                                    onFailure = { msg ->
                                                        runOnUiThread {
                                                            android.util.Log.e("Casting", "캐스팅 실패: $msg")
                                                        }
                                                    }
                                                )
                                            } catch (e: Exception) {
                                                android.util.Log.e("Casting", "캐스팅 처리 오류: ${e.message}", e)
                                            }
                                        }.start()
                                    },
                                    onStopCasting = {
                                        currentRenderer.value?.let { device ->
                                            rendererManager.stop(device) { _, _ -> }
                                        }
                                        isCasting.value = false
                                        currentRenderer.value = null
                                        localHttpServer?.clearSources()
                                    },
                                    onCastPause = {
                                        currentRenderer.value?.let { device ->
                                            rendererManager.pause(device)
                                        }
                                    },
                                    onCastResume = {
                                        currentRenderer.value?.let { device ->
                                            rendererManager.resume(device)
                                        }
                                    },
                                    onCastSeek = { posMs ->
                                        currentRenderer.value?.let { device ->
                                            rendererManager.seek(device, posMs)
                                        }
                                    },
                                    onSearchRenderers = {
                                        rendererManager.search()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dlnaManager.start()
        rendererManager.start()
    }

    override fun onStop() {
        super.onStop()
        dlnaManager.stop()
        rendererManager.stop()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try { unregisterReceiver(pipBroadcastReceiver) } catch (_: Exception) {}
        }
        // 캐스팅 정리
        try {
            if (isCasting.value && currentRenderer.value != null) {
                rendererManager.stop(currentRenderer.value!!) { _, _ -> }
            }
        } catch (_: Exception) {}
        try { localHttpServer?.stop() } catch (_: Exception) {}
        localHttpServer?.clearSources()
        super.onDestroy()
    }

    private fun checkForegroundServicePermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { // Android 9+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }

    private fun isPipPermissionGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, android.os.Process.myUid(), packageName)
        }
        return status == AppOpsManager.MODE_ALLOWED
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode.value = isInPictureInPictureMode
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isVideoScreenActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            enterPipMode()
        }
    }

    fun setPipPlayer(player: ExoPlayer?) {
        pipExoPlayer = player
        if (player != null) {
            isVideoScreenActive = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                updatePipActions(player.isPlaying)
            }
        } else {
            isVideoScreenActive = false
            clearPipParams()
        }
    }

    fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val isPlaying = pipExoPlayer?.isPlaying ?: true
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .setActions(buildPipRemoteActions(isPlaying))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    fun updatePipActions(isPlaying: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val builder = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .setActions(buildPipRemoteActions(isPlaying))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setAutoEnterEnabled(true)
            }
            setPictureInPictureParams(builder.build())
        }
    }

    fun clearPipParams() {
        pipExoPlayer = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAutoEnterEnabled(false)
                    .build()
                setPictureInPictureParams(params)
            } catch (_: Exception) {}
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipRemoteActions(isPlaying: Boolean): List<RemoteAction> {
        val icon = Icon.createWithResource(this,
            if (isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
        val title = if (isPlaying) "일시정지" else "재생"
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0,
            android.content.Intent(PIP_ACTION_PLAY_PAUSE).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return listOf(RemoteAction(icon, title, title, pendingIntent))
    }

    private fun requestPermissions() {
        // Obsolete, removed logic. Permissions are now requested via Composable launchers in HomeScreen.
    }
}

@Composable
fun ExitAppButton() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var activeContext = context
    while (activeContext is android.content.ContextWrapper && activeContext !is android.app.Activity) {
        activeContext = activeContext.baseContext
    }
    val activity = activeContext as? android.app.Activity
    var showExitDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    IconButton(onClick = { showExitDialog = true }) {
        Icon(androidx.compose.material.icons.Icons.Default.Close, contentDescription = "앱 종료", tint = androidx.compose.material3.MaterialTheme.colorScheme.onBackground)
    }

    if (showExitDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { androidx.compose.material3.Text("앱 종료", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
            text = { androidx.compose.material3.Text("앱 종료 하겠습니까?") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showExitDialog = false
                    activity?.finishAffinity()
                    android.os.Process.killProcess(android.os.Process.myPid())
                }) {
                    androidx.compose.material3.Text("예", color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showExitDialog = false }) {
                    androidx.compose.material3.Text("아니오", color = androidx.compose.material3.MaterialTheme.colorScheme.primary)
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    padding: PaddingValues,
    devices: List<Device<*, *, *>>,
    isSearching: Boolean,
    isTvMode: Boolean = false,
    initialSelectedIndex: Int = 0,
    onDeviceClick: (Device<*, *, *>) -> Unit,
    onBackClick: () -> Unit,
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(padding)
    ) {
        TopAppBar(
            title = {
                Text(
                    text = "서버 목록 (${devices.size})",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                )
            },
            navigationIcon = {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
                }
            },
            actions = {
                Button(
                    onClick = onRefresh,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("서버 재검색", color = MaterialTheme.colorScheme.onPrimary)
                }
                androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                ExitAppButton()
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
        
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()

        LaunchedEffect(devices, isSearching) {
            if (!isSearching && devices.isNotEmpty()) {
                if (initialSelectedIndex in devices.indices) {
                    listState.scrollToItem(initialSelectedIndex)
                }
            }
        }

        if (isSearching) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "서버 검색중...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        } else if (devices.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text(
                    text = "발견된 서버가 없습니다.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(devices) { index, device ->
                    val focusRequester = remember { FocusRequester() }
                    DeviceItem(
                        device = device, 
                        onDeviceClick = { onDeviceClick(device) },
                        focusRequester = focusRequester,
                        isTvMode = isTvMode
                    )
                    
                    if (!isSearching && index == initialSelectedIndex) {
                        LaunchedEffect(Unit) {
                            try { focusRequester.requestFocus() } catch(e:Exception){}
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceItem(
    device: Device<*, *, *>, 
    onDeviceClick: (Device<*, *, *>) -> Unit,
    focusRequester: FocusRequester = remember { FocusRequester() },
    isTvMode: Boolean = false
) {
    var isFocused by remember { mutableStateOf(false) }
    val PrimaryColor = MaterialTheme.colorScheme.primary

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionCenter) {
                    onDeviceClick(device)
                    true
                } else false
            }
            .clickable { onDeviceClick(device) }
            .border(
                width = if (isTvMode && isFocused) 3.dp else 0.dp,
                color = if (isTvMode && isFocused) PrimaryColor else Color.Transparent,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
            ),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f), shape = androidx.compose.foundation.shape.CircleShape),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AccountBox, // Will import
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                val friendlyName = device.details?.friendlyName ?: "Unknown Server"
                Text(
                    text = friendlyName, 
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = device.identity.toString().take(20) + "...", 
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    padding: PaddingValues,
    state: ScreenState.Browsing,
    items: List<DIDLObject>,
    isLoading: Boolean,
    errorMessage: String?,
    isTvMode: Boolean = false,
    sortOrder: SortOrder,
    onSortOrderChange: (SortOrder) -> Unit,
    initialSelectedIndex: Int = 0,
    onBackClick: () -> Unit,
    onItemClick: (DIDLObject, Int) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    val sortedItems = remember(items, sortOrder, searchQuery) {
        val filtered = if (searchQuery.isNotEmpty()) {
            items.filter { (it.title ?: "").contains(searchQuery, ignoreCase = true) }
        } else {
            items
        }
        filtered.sortedWith { o1, o2 ->
            val isDir1 = o1 is Container
            val isDir2 = o2 is Container

            if (isDir1 && !isDir2) -1
            else if (!isDir1 && isDir2) 1
            else {
                val name1 = o1.title ?: ""
                val name2 = o2.title ?: ""
                
                // jUPnP DIDLObject date extraction
                fun getDate(obj: DIDLObject): String {
                    return try {
                        obj.properties.find { it.descriptorName == "dc:date" }?.value?.toString() ?: ""
                    } catch (e: Exception) { "" }
                }
                
                val date1 = getDate(o1)
                val date2 = getDate(o2)

                when (sortOrder) {
                    SortOrder.NAME_ASC -> name1.compareTo(name2, ignoreCase = true)
                    SortOrder.NAME_DESC -> name2.compareTo(name1, ignoreCase = true)
                    SortOrder.DATE_DESC -> date2.compareTo(date1) // String comparison for ISO dates usually works
                    SortOrder.DATE_ASC -> date1.compareTo(date2)
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(padding)
    ) {
        TopAppBar(
            title = { 
                if (isSearchActive) {
                    androidx.compose.material3.TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("이름 검색...", style = MaterialTheme.typography.bodyMedium) },
                        textStyle = MaterialTheme.typography.bodyMedium,
                        singleLine = true,
                        colors = androidx.compose.material3.TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                } else {
                    Column {
                        Text(
                            text = state.containerName,
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Text(
                            text = sortOrder.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
                }
            },
            actions = {
                if (isSearchActive) {
                    IconButton(onClick = { 
                        searchQuery = ""
                        isSearchActive = false 
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "닫기", tint = MaterialTheme.colorScheme.onBackground)
                    }
                } else {
                    IconButton(onClick = { isSearchActive = true }) {
                        Icon(Icons.Default.Search, contentDescription = "검색", tint = MaterialTheme.colorScheme.onBackground)
                    }
                }

                // 정렬 메뉴 추가
                Box {
                    IconButton(onClick = { sortMenuExpanded = true }) {
                        Icon(Icons.Default.Sort, contentDescription = "Sort", tint = MaterialTheme.colorScheme.onBackground)
                    }
                    DropdownMenu(
                        expanded = sortMenuExpanded,
                        onDismissRequest = { sortMenuExpanded = false }
                    ) {
                        SortOrder.values().forEach { order ->
                            DropdownMenuItem(
                                text = { Text(order.displayName) },
                                onClick = {
                                    onSortOrderChange(order)
                                    SettingsStore.saveSortOrder(context, order)
                                    sortMenuExpanded = false
                                },
                                leadingIcon = {
                                    if (sortOrder == order) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            )
                        }
                    }
                }
                ExitAppButton()
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
        
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()

        LaunchedEffect(items, isLoading) {
            if (!isLoading && items.isNotEmpty()) {
                if (initialSelectedIndex in items.indices) {
                    listState.scrollToItem(initialSelectedIndex)
                }
            }
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else if (errorMessage != null) {
            Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("에러: $errorMessage", color = MaterialTheme.colorScheme.error, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        } else if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("이 폴더는 비어 있습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(sortedItems) { index, item ->
                    val isFolder = item is Container
                    val iconVector = if (isFolder) Icons.Default.List else Icons.Default.PlayArrow
                    val iconTint = if (isFolder) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                    
                    var isFocused by remember { mutableStateOf(false) }
                    val itemFocusRequester = remember { FocusRequester() }
                    val PrimaryColor = MaterialTheme.colorScheme.primary

                    if (!isLoading && index == initialSelectedIndex) {
                        LaunchedEffect(Unit) {
                            try { itemFocusRequester.requestFocus() } catch(e:Exception){}
                        }
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(itemFocusRequester)
                            .onFocusChanged { isFocused = it.isFocused }
                            .focusable()
                            .onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionCenter) {
                                    onItemClick(item, index)
                                    true
                                } else false
                            }
                            .clickable { onItemClick(item, index) }
                            .border(
                                width = if (isTvMode && isFocused) 3.dp else 0.dp,
                                color = if (isTvMode && isFocused) PrimaryColor else Color.Transparent,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                            ),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(iconTint.copy(alpha = 0.15f), shape = androidx.compose.foundation.shape.CircleShape),
                                contentAlignment = androidx.compose.ui.Alignment.Center
                            ) {
                                Icon(
                                    imageVector = iconVector,
                                    contentDescription = null,
                                    tint = iconTint,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title ?: "Unknown", 
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                )
                                if (!isFolder && item.resources.isNotEmpty()) {
                                    val res = item.firstResource
                                    // 재생 시간 파싱 (HH:MM:SS or H:MM:SS.xxx 형식)
                                    val durationStr = res?.duration
                                    val durationDisplay = durationStr?.substringBefore(".")?.trim() ?: ""
                                    val resInfo = res?.resolution ?: ""
                                    val info = listOfNotNull(
                                        if (durationDisplay.isNotEmpty()) durationDisplay else null,
                                        if (resInfo.isNotEmpty()) resInfo else null
                                    ).joinToString(" | ")
                                    if (info.isNotEmpty()) {
                                        Text(
                                            text = info, 
                                            style = MaterialTheme.typography.bodySmall, 
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            // 저장된 재생 위치 표시 (시간 + 프로그레스 바)
                            if (!isFolder && item is Item) {
                                val videoRes = item.resources.find { it.protocolInfo.contentFormat.startsWith("video/") || it.protocolInfo.contentFormat.startsWith("audio/") } ?: item.firstResource
                                val videoUrlForSave = videoRes?.value ?: ""
                                if (videoUrlForSave.isNotEmpty()) {
                                    val dlnaContext = androidx.compose.ui.platform.LocalContext.current
                                    val savedPos = PlaybackPositionStore.getPosition(dlnaContext, videoUrlForSave)
                                    if (savedPos > 0L) {
                                        val savedDur = PlaybackPositionStore.getDuration(dlnaContext, videoUrlForSave)
                                        Column(
                                            horizontalAlignment = androidx.compose.ui.Alignment.End,
                                            modifier = Modifier.padding(start = 12.dp).width(72.dp)
                                        ) {
                                            Text(
                                                text = "▶ " + PlaybackPositionStore.formatPosition(savedPos),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                            )
                                            if (savedDur > 0L) {
                                                Spacer(Modifier.height(4.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(6.dp)
                                                        .background(
                                                            Color.White.copy(alpha = 0.2f),
                                                            shape = androidx.compose.foundation.shape.RoundedCornerShape(3.dp)
                                                        )
                                                ) {
                                                    val progress = (savedPos.toFloat() / savedDur.toFloat()).coerceIn(0f, 1f)
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth(progress)
                                                            .fillMaxHeight()
                                                            .background(
                                                                MaterialTheme.colorScheme.primary,
                                                                shape = androidx.compose.foundation.shape.RoundedCornerShape(3.dp)
                                                            )
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayerScreen(
    padding: PaddingValues,
    videoUrl: String,
    subtitleUrl: String? = null,
    subtitleExtension: String? = null,
    title: String,
    isControlVisible: Boolean,
    onControlVisibilityChange: (Boolean) -> Unit,
    onClose: () -> Unit,
    playlist: List<PlaylistItem> = emptyList(),
    currentIndex: Int = -1,
    ftpEncoding: String = "AUTO",
    httpHeaders: Map<String, String> = emptyMap(),
    onPlayNext: ((PlaylistItem) -> Unit)? = null,
    onPlayPrevious: ((PlaylistItem) -> Unit)? = null,
    // ── 캐스팅 관련 파라미터 ──
    rendererDevices: List<Device<*, *, *>> = emptyList(),
    isCasting: Boolean = false,
    currentRenderer: Device<*, *, *>? = null,
    onCastToDevice: (Device<*, *, *>, String?, Long) -> Unit = { _, _, _ -> },
    onStopCasting: () -> Unit = {},
    onCastPause: () -> Unit = {},
    onCastResume: () -> Unit = {},
    onCastSeek: (Long) -> Unit = {},
    onSearchRenderers: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? android.app.Activity
    val mainActivity = context as? com.sv21c.jsplayer.MainActivity
    val PrimaryColor = MaterialTheme.colorScheme.primary
    val totalButtons = 10
    val hasPrevious = playlist.isNotEmpty() && currentIndex > 0
    val hasNext = playlist.isNotEmpty() && currentIndex >= 0 && currentIndex < playlist.size - 1

    // ── 캐스팅 UI 상태 ──
    var showRendererDialog by remember { mutableStateOf(false) }
    var castPosition by remember { mutableLongStateOf(0L) }
    var castDuration by remember { mutableLongStateOf(1L) }
    var castIsPlaying by remember { mutableStateOf(true) }
    var castErrorMessage by remember { mutableStateOf<String?>(null) }
    
    // 재생이 실제로 시작되었는지 추적 (한번 플레이된 후 멈췄을 때만 자동 종료)
    var hasStartedPlaying by remember { mutableStateOf(false) }

    // 캐스팅 중 위치 동기화 (1초마다 폴링)
    LaunchedEffect(isCasting, currentRenderer) {
        if (isCasting && currentRenderer != null) {
            hasStartedPlaying = false // 새 장치/캐스팅 시작 시 초기화
            while (true) {
                mainActivity?.let { act ->
                    act.rendererManager.let { manager ->
                        manager.getPositionInfo(
                            currentRenderer,
                            onResult = { pos, dur ->
                                act.runOnUiThread {
                                    castPosition = pos
                                    castDuration = dur.coerceAtLeast(1L)
                                }
                            }
                        )
                        manager.getTransportInfo(
                            currentRenderer,
                            onResult = { state ->
                                act.runOnUiThread {
                                    val isPlaying = state == "PLAYING"
                                    castIsPlaying = isPlaying
                                    
                                    if (isPlaying) {
                                        hasStartedPlaying = true
                                    } else if (hasStartedPlaying && state == "STOPPED") {
                                        // 한 번이라도 재생 후 정지되었다면 영상이 끝났거나 강제 종료된 것이므로 팝업을 닫음
                                        Log.d("CastingOverlay", "영상 재생 완료 또는 강제 정지 감지. 캐스팅 종료.")
                                        onStopCasting()
                                    }
                                }
                            }
                        )
                    }
                }
                delay(1000L)
            }
        }
    }

    // --- Fullscreen & Landscape ---
    DisposableEffect(Unit) {
        val originalOrientation = activity?.requestedOrientation ?: android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (activity != null) {
            val window = activity.window
            val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (activity != null) {
                val window = activity.window
                val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                insetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                
                // Restore Orientation
                activity.requestedOrientation = originalOrientation
            }
        }
    }

    // --- UI State (isControlVisible는 외부에서 관리됨 — triggerBack에서 컨트롤) ---
    // Button index: 0=이전, 1=-10s, 2=Play/Pause, 3=+10s, 4=다음, 5=Sub-, 6=Sub+, 7=Speed, 8=Close, 9=SeekBar
    var focusedButtonIndex by remember { mutableIntStateOf(2) }
    var subtitleScale by remember { mutableFloatStateOf(1.0f) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var totalDuration by remember { mutableLongStateOf(1L) }
    var isPlaying by remember { mutableStateOf(true) }
    var currentSpeed by remember { mutableFloatStateOf(1.0f) }
    var hideTimerKey by remember { mutableIntStateOf(0) }
    var isOrientationLocked by remember { mutableStateOf(true) }
    var videoSizeState by remember { mutableStateOf<androidx.media3.common.VideoSize?>(null) }
    
    // orientation 로직을 별도로 분리하여 상태 변화 시 즉각 적용되도록 함
    LaunchedEffect(isOrientationLocked, videoSizeState) {
        val activity = context as? android.app.Activity ?: return@LaunchedEffect
        val videoSize = videoSizeState
        if (!isOrientationLocked) {
            // 해제 모드: 센서에 따라 자유롭게 회전 (상하좌우 모두)
            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        } else if (videoSize != null) {
            // 잠금 모드: 영상 비율에 따라 자동 고정
            val isRotated = videoSize.unappliedRotationDegrees % 180 != 0
            val actualWidth = if (isRotated) videoSize.height else videoSize.width
            val actualHeight = if (isRotated) videoSize.width else videoSize.height
            
            if (actualWidth > 0 && actualHeight > 0) {
                val isVertical = actualHeight > actualWidth
                if (isVertical) {
                    // 세로 동영상: 세로로 고정 또는 센서 기반 세로 회전
                    activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
                } else {
                    // 가로 동영상: 항상 가로 고정
                    activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            }
        }
    }
    
    // For Slider Interaction
    var isDragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableLongStateOf(0L) }
    
    // For Screen Gestures (Double tap, Drag)
    var isDraggingScreen by remember { mutableStateOf(false) }
    var screenDragPosition by remember { mutableLongStateOf(-1L) }
    var seekIndicatorText by remember { mutableStateOf("") }
    var showSeekIndicator by remember { mutableStateOf(false) }

    LaunchedEffect(showSeekIndicator, isDraggingScreen) {
        if (showSeekIndicator && !isDraggingScreen) {
            delay(800L)
            showSeekIndicator = false
        }
    }

    var brightnessText by remember { mutableStateOf("") }
    var showBrightnessIndicator by remember { mutableStateOf(false) }
    var isDraggingBrightness by remember { mutableStateOf(false) }

    LaunchedEffect(showBrightnessIndicator, isDraggingBrightness) {
        if (showBrightnessIndicator && !isDraggingBrightness) {
            delay(800L)
            showBrightnessIndicator = false
        }
    }

    var volumeText by remember { mutableStateOf("") }
    var showVolumeIndicator by remember { mutableStateOf(false) }
    var isDraggingVolume by remember { mutableStateOf(false) }
    var currentVolumeFloat by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(showVolumeIndicator, isDraggingVolume) {
        if (showVolumeIndicator && !isDraggingVolume) {
            delay(800L)
            showVolumeIndicator = false
        }
    }
    
    val currentIsControlVisible by androidx.compose.runtime.rememberUpdatedState(isControlVisible)

    val speeds = remember { listOf(0.5f, 1.0f, 1.5f, 2.0f) }

    var finalSubtitleUrl by remember { mutableStateOf<String?>(null) }
    var finalSubtitleExt by remember { mutableStateOf<String?>(null) }
    var isSubReady by remember { mutableStateOf(false) }
    
    LaunchedEffect(subtitleUrl, videoUrl) {
        if (subtitleUrl != null) {
            android.util.Log.d("AutoPlay", "VideoPlayerScreen: Processing subtitle: $subtitleUrl (ext=$subtitleExtension)")
            if (subtitleUrl.startsWith("file://") && subtitleUrl.contains("cached_sub")) {
                finalSubtitleUrl = subtitleUrl
                finalSubtitleExt = subtitleExtension
                android.util.Log.d("AutoPlay", "VideoPlayerScreen: Using cached subtitle: $finalSubtitleUrl")
                isSubReady = true
            } else {
                val (url, ext) = downloadAndProcessSubtitle(context, subtitleUrl, httpHeaders = httpHeaders, providedExtension = subtitleExtension)
                if (url != null) {
                    finalSubtitleUrl = url
                    finalSubtitleExt = ext
                    android.util.Log.d("AutoPlay", "VideoPlayerScreen: Downloaded and processed subtitle: $finalSubtitleUrl (ext=$finalSubtitleExt)")
                } else {
                    finalSubtitleUrl = subtitleUrl
                    finalSubtitleExt = subtitleExtension
                    android.util.Log.d("AutoPlay", "VideoPlayerScreen: Download failed, using raw URL: $finalSubtitleUrl")
                }
                isSubReady = true
            }
        } else {
            // 폴백: subtitleUrl이 null인 경우 비디오 URL 기반으로 자막 프로빙
            android.util.Log.d("AutoPlay", "VideoPlayerScreen: No subtitle URL provided, probing from video URL: $videoUrl")
            val parsedUri = android.net.Uri.parse(videoUrl)
            val isLocalContent = parsedUri.scheme == "content" || parsedUri.scheme == null
            if (isLocalContent) {
                // content:// URI → MediaStore에서 파일 경로를 가져와 로컬 자막 검색
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val projection = arrayOf(android.provider.MediaStore.Video.Media.DATA)
                        context.contentResolver.query(parsedUri, projection, null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val dataIdx = cursor.getColumnIndex(android.provider.MediaStore.Video.Media.DATA)
                                if (dataIdx >= 0) {
                                    val filePath = cursor.getString(dataIdx)
                                    if (filePath != null) {
                                        val file = java.io.File(filePath)
                                        val parent = file.parentFile
                                        val baseName = file.nameWithoutExtension
                                        android.util.Log.d("AutoPlay", "VideoPlayerScreen: Local path resolved: $filePath, BaseName: $baseName")
                                        if (parent != null && parent.exists() && parent.isDirectory) {
                                            val subs = parent.listFiles { _, name ->
                                                val lower = name.lowercase()
                                                val isSubExt = lower.endsWith(".smi") || lower.endsWith(".srt") || lower.endsWith(".vtt") || lower.endsWith(".ass") || lower.endsWith(".ssa") || lower.endsWith(".sub") || lower.endsWith(".txt")
                                                val itBase = name.substringBeforeLast(".")
                                                isSubExt && (itBase.equals(baseName, ignoreCase = true) || (itBase.startsWith(baseName, ignoreCase = true) && itBase.getOrNull(baseName.length) == '.'))
                                            }
                                            if (subs != null && subs.isNotEmpty()) {
                                                val subFile = subs.find { it.extension.equals("ass", true) }
                                                    ?: subs.find { it.extension.equals("srt", true) }
                                                    ?: subs.find { it.extension.equals("smi", true) }
                                                    ?: subs[0]
                                                val (url, ext) = downloadAndProcessSubtitle(context, "file://" + subFile.absolutePath, ftpEncoding, httpHeaders, subFile.extension)
                                                if (url != null) {
                                                    finalSubtitleUrl = url
                                                    finalSubtitleExt = ext
                                                    android.util.Log.d("AutoPlay", "VideoPlayerScreen: Local subtitle found: $url (ext=$ext)")
                                                }
                                            } else {
                                                android.util.Log.d("AutoPlay", "VideoPlayerScreen: No local subtitle found in directory")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        android.util.Log.d("AutoPlay", "VideoPlayerScreen: Error probing local subtitle: ${e.message}")
                    }
                }
            } else {
                // 네트워크 URL → 기존 확장자 기반 프로빙
                val lastSlashIndex = videoUrl.lastIndexOf('/')
                val lastDotIndex = videoUrl.lastIndexOf('.')
                if (lastDotIndex > lastSlashIndex && lastDotIndex > 0 && videoUrl.length - lastDotIndex <= 5) {
                    val baseUrl = videoUrl.substring(0, lastDotIndex)
                    var probeFound = false
                    for (probeExt in listOf("ass", "ssa", "srt", "smi", "vtt", "sub", "txt")) {
                        val probeUrl = "$baseUrl.$probeExt"
                        android.util.Log.d("AutoPlay", "VideoPlayerScreen: Probing subtitle: $probeUrl")
                        try {
                            val (url, ext) = downloadAndProcessSubtitle(context, probeUrl, ftpEncoding, httpHeaders, probeExt)
                            if (url != null) {
                                finalSubtitleUrl = url
                                finalSubtitleExt = ext
                                android.util.Log.d("AutoPlay", "VideoPlayerScreen: Probe found subtitle: $finalSubtitleUrl (ext=$finalSubtitleExt)")
                                probeFound = true
                                break
                            }
                        } catch (e: Throwable) {
                            android.util.Log.d("AutoPlay", "VideoPlayerScreen: Probe failed for $probeUrl: ${e.message}")
                        }
                    }
                    if (!probeFound) {
                        android.util.Log.d("AutoPlay", "VideoPlayerScreen: No subtitle found from probing")
                    }
                }
            }
            isSubReady = true
        }
    }

    if (!isSubReady) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }    // --- Track Selector ---
    val trackSelector = remember {
        val selector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context)
        selector.setParameters(
            selector.buildUponParameters()
                .setPreferredTextLanguage("ko")
                .setSelectUndeterminedTextLanguage(true)
        )
        selector
    }

    // --- ExoPlayer ---
    val exoPlayer = remember {
        val passthroughEnabled = SettingsStore.getAudioPassthroughEnabled(context)
        val extensionMode = if (passthroughEnabled) {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        } else {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
        }
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(extensionMode)
            
        var finalVideoUrl = videoUrl
        val finalHeaders = httpHeaders.toMutableMap()
        val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            
        try {
            val uri = android.net.Uri.parse(videoUrl)
            if (uri.scheme?.lowercase()?.startsWith("http") == true) {
                val userInfo = uri.userInfo
                val encodedUserInfo = uri.encodedUserInfo
                if (!userInfo.isNullOrBlank() && !encodedUserInfo.isNullOrBlank()) {
                    val authHeader = "Basic " + android.util.Base64.encodeToString(userInfo.toByteArray(kotlin.text.Charsets.UTF_8), android.util.Base64.NO_WRAP)
                    finalHeaders["Authorization"] = authHeader
                    
                    finalVideoUrl = videoUrl.replaceFirst("://$encodedUserInfo@", "://")
                    android.util.Log.d("VideoPlayerScreen", "Extracted userInfo from HTTP URL and added Authorization header.")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("VideoPlayerScreen", "Failed to parse UserInfo from URL", e)
        }
        
        httpDataSourceFactory.setDefaultRequestProperties(finalHeaders)

        val defaultDataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, httpDataSourceFactory)
        
        val customDataSourceFactory = androidx.media3.datasource.DataSource.Factory {
            val defaultDataSource = defaultDataSourceFactory.createDataSource()
            object : androidx.media3.datasource.DataSource {
                private var activeDataSource: androidx.media3.datasource.DataSource? = null
                private val smbDataSource by lazy {
                    SmbDataSource { uri ->
                        val userInfo = uri.userInfo
                        val userName = userInfo?.substringBefore(":") ?: ""
                        val password = userInfo?.substringAfter(":") ?: ""
                        SmbManager.buildContext(userName, password)
                    }
                }
                private val ftpDataSource by lazy { FtpDataSource(ftpEncoding) }
                private val sftpDataSource by lazy { SftpDataSource() }

                override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
                    defaultDataSource.addTransferListener(transferListener)
                    smbDataSource.addTransferListener(transferListener)
                    ftpDataSource.addTransferListener(transferListener)
                    sftpDataSource.addTransferListener(transferListener)
                }

                override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
                    val scheme = dataSpec.uri.scheme?.lowercase()
                    activeDataSource = when (scheme) {
                        "smb" -> smbDataSource
                        "ftp" -> ftpDataSource
                        "sftp" -> sftpDataSource
                        else -> defaultDataSource
                    }
                    return activeDataSource!!.open(dataSpec)
                }

                override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
                    return activeDataSource?.read(buffer, offset, readLength) ?: -1
                }

                override fun getUri(): android.net.Uri? = activeDataSource?.uri

                override fun close() {
                    activeDataSource?.close()
                    activeDataSource = null
                }
                
                override fun getResponseHeaders(): Map<String, List<String>> {
                    return activeDataSource?.responseHeaders ?: emptyMap()
                }
            }
        }

        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
            .setDataSourceFactory(customDataSourceFactory)

        ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(trackSelector)
            .build().apply {
                val mediaMetadata = androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(title)
                    .build()
                val mediaItemBuilder = MediaItem.Builder()
                    .setUri(finalVideoUrl)
                    .setMediaMetadata(mediaMetadata)
                if (finalSubtitleUrl != null) {
                    val isSmi = finalSubtitleExt?.equals("smi", ignoreCase = true) == true || finalSubtitleUrl!!.lowercase().endsWith(".smi")
                    val isVtt = finalSubtitleExt?.equals("vtt", ignoreCase = true) == true || finalSubtitleUrl!!.lowercase().endsWith(".vtt")
                    val isAss = finalSubtitleExt?.equals("ass", ignoreCase = true) == true || finalSubtitleUrl!!.lowercase().endsWith(".ass")
                    val mimeType = when {
                        isSmi -> "application/x-sami"
                        isVtt -> androidx.media3.common.MimeTypes.TEXT_VTT
                        isAss -> androidx.media3.common.MimeTypes.TEXT_SSA
                        else -> androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
                    }
                    android.util.Log.d("VideoPlayerScreen", "Loading subtitle: $finalSubtitleUrl MimeType: $mimeType")
                    val uri = if (finalSubtitleUrl!!.startsWith("http") || 
                        finalSubtitleUrl!!.startsWith("file://") || 
                        finalSubtitleUrl!!.startsWith("sftp://") || 
                        finalSubtitleUrl!!.startsWith("ftp://") || 
                        finalSubtitleUrl!!.startsWith("smb://")) {
                        android.net.Uri.parse(finalSubtitleUrl)
                    } else {
                        android.net.Uri.fromFile(java.io.File(finalSubtitleUrl!!))
                    }
                    val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(uri)
                        .setMimeType(mimeType)
                        .setLanguage("ko")
                        .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT or androidx.media3.common.C.SELECTION_FLAG_FORCED)
                        .setRoleFlags(androidx.media3.common.C.ROLE_FLAG_SUBTITLE)
                        .setId("jsplayer_external_sub")
                        .build()
                    mediaItemBuilder.setSubtitleConfigurations(listOf(subtitleConfig))
                }
                setMediaItem(mediaItemBuilder.build())
                prepare()
                
                // --- 디버깅 로그 추가 ---
                addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                    override fun onAudioDecoderInitialized(
                        eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                        decoderName: String,
                        initializedTimestampMs: Long,
                        initializationDurationMs: Long
                    ) {
                        android.util.Log.i("ExoPlayer_Debug", "✅ Audio Decoder Initialized: $decoderName")
                    }
                })

                addListener(object : androidx.media3.common.Player.Listener {
                    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                        for (group in tracks.groups) {
                            if (group.type == androidx.media3.common.C.TRACK_TYPE_AUDIO) {
                                for (i in 0 until group.length) {
                                    val format = group.getTrackFormat(i)
                                    val isSelected = group.isTrackSelected(i)
                                    // 해당 트랙이 렌더러에 의해 지원되는지 확인
                                    val support = group.getTrackSupport(i)
                                    val supportStr = when(support) {
                                        androidx.media3.common.C.FORMAT_HANDLED -> "HANDLED"
                                        androidx.media3.common.C.FORMAT_EXCEEDS_CAPABILITIES -> "EXCEEDS_CAPS"
                                        androidx.media3.common.C.FORMAT_UNSUPPORTED_DRM -> "UNSUPPORTED_DRM"
                                        androidx.media3.common.C.FORMAT_UNSUPPORTED_SUBTYPE -> "UNSUPPORTED_SUBTYPE"
                                        androidx.media3.common.C.FORMAT_UNSUPPORTED_TYPE -> "UNSUPPORTED_TYPE"
                                        else -> "UNKNOWN"
                                    }
                                    android.util.Log.i("ExoPlayer_Debug", "🔊 Audio Track [$i]: ${format.sampleMimeType}, Support: $supportStr, Selected: $isSelected")
                                }
                            }
                        }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        android.util.Log.e("ExoPlayer_Debug", "❌ Player Error: ${error.message}", error)
                    }
                })

                // 코덱 상태 확인 로그 (토스트 제거)
                val codecStatus = if (FfmpegLoader.isLoaded) "FFmpeg 로드됨" else "기본 코덱 사용"
                try {
                    val clazz = Class.forName("androidx.media3.decoder.ffmpeg.FfmpegLibrary")
                    val isAvailable = clazz.getMethod("isAvailable").invoke(null) as Boolean
                    val detail = if (isAvailable) " (가용성: 확인됨)" else " (가용성: 실패)"
                    android.util.Log.i("ExoPlayer_Debug", "📦 Codec Status: $codecStatus$detail")
                } catch(e: Exception) {
                    android.util.Log.i("ExoPlayer_Debug", "📦 Codec Status: $codecStatus")
                }

                playWhenReady = true
                
                // 저장된 재생 위치로 이동
                val savedPosition = PlaybackPositionStore.getPosition(context, videoUrl)
                if (savedPosition > 0L) {
                    seekTo(savedPosition)
                    android.util.Log.d("VideoPlayerScreen", "Restored playback position: ${savedPosition}ms")
                }
            }
    }

    // ── 캐스팅 시 ExoPlayer 일시정지 / 캐스팅 종료 시 재개 ──
    LaunchedEffect(isCasting) {
        if (isCasting) {
            exoPlayer.playWhenReady = false
            android.util.Log.d("Casting", "캐스팅 시작 → ExoPlayer 일시정지")
        } else {
            exoPlayer.playWhenReady = true
            android.util.Log.d("Casting", "캐스팅 종료 → ExoPlayer 재생 재개")
        }
    }

    // Props version is used directly to ensure sync on recomposition
    // key(videoUrl) in MainActivity ensures fresh state on video change

    // 썸네일 비동기 추출 및 알림창 배경 업데이트
    LaunchedEffect(videoUrl) {
        val thumbnailUri = ThumbnailExtractor.extractThumbnailUri(context, videoUrl)
        if (thumbnailUri != null) {
            val currentIndex = exoPlayer.currentMediaItemIndex
            if (currentIndex >= 0 && currentIndex < exoPlayer.mediaItemCount) {
                val currentItem = exoPlayer.getMediaItemAt(currentIndex)
                val updatedMetadata = currentItem.mediaMetadata.buildUpon()
                    .setArtworkUri(thumbnailUri)
                    .build()
                val updatedMediaItem = currentItem.buildUpon()
                    .setMediaMetadata(updatedMetadata)
                    .build()
                
                exoPlayer.replaceMediaItem(currentIndex, updatedMediaItem)
                android.util.Log.d("VideoPlayerScreen", "Artwork thumbnail injected into MediaSession Notification!")
            }
        }
    }

    DisposableEffect(exoPlayer) {
        mainActivity?.setPipPlayer(exoPlayer)
        PlayerSingleton.player = exoPlayer
            
        val serviceIntent = android.content.Intent(context, PlaybackService::class.java)
        try {
            androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            android.util.Log.e("VideoPlayerScreen", "Failed to start MediaSessionService", e)
        }
        
        onDispose {
            mainActivity?.clearPipParams()
            try {
                context.stopService(serviceIntent)
            } catch (e: Exception) {}
            PlayerSingleton.mediaSession?.release()
            PlayerSingleton.mediaSession = null
            PlayerSingleton.player = null
        }
    }

    // 자동 다음 재생 리스너
    DisposableEffect(exoPlayer) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == androidx.media3.common.Player.STATE_ENDED) {
                    // 재생 완료 → 저장 위치 삭제
                    PlaybackPositionStore.removePosition(context, videoUrl)
                    android.util.Log.d("VideoPlayerScreen", "Playback ended for: $videoUrl")
                    
                    // 다음 파일 자동 재생 
                    if (playlist.isNotEmpty() && currentIndex >= 0 && currentIndex < playlist.size - 1) {
                        val nextIndex = currentIndex + 1
                        val nextItem = playlist[nextIndex]
                        android.util.Log.d("VideoPlayerScreen", "Auto-playing next: ${nextItem.title} (index=$nextIndex)")
                        onPlayNext?.invoke(nextItem)
                    }
                }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                super.onTracksChanged(tracks)
                android.util.Log.d("VideoPlayerScreen", "onTracksChanged: ${tracks.groups.size} groups")
                
                var bestGroup: androidx.media3.common.Tracks.Group? = null
                var bestScore = -1
                
                for (group in tracks.groups) {
                    if (group.type != androidx.media3.common.C.TRACK_TYPE_TEXT || !group.isSupported) continue
                    
                    val format = group.mediaTrackGroup.getFormat(0)
                    val lang = format.language
                    val mimeType = format.sampleMimeType ?: ""
                    val id = format.id ?: ""
                    
                    var score = 0
                    if (id == "jsplayer_external_sub") {
                        score = 1000 // 외부 자막 최우선
                    } else if (lang == "ko" || lang == "kor") {
                        score = 500
                    } else if (lang == null || lang == "und" || lang == "") {
                        if (mimeType.contains("x-sami") || mimeType.contains("subrip") || mimeType.contains("vtt") || mimeType.contains("ssa")) {
                            score = 100
                        } else {
                            score = 10
                        }
                    } else if (lang == "en" || lang == "eng") {
                        score = 1 // 영어는 최하위 (다른 대안이 있을 경우)
                    } else {
                        score = 5
                    }
                    
                    if (score > bestScore) {
                        bestScore = score
                        bestGroup = group
                    }
                }
                
                if (bestGroup != null && !bestGroup.isSelected) {
                    trackSelector.setParameters(
                        trackSelector.buildUponParameters()
                            .setOverrideForType(
                                androidx.media3.common.TrackSelectionOverride(
                                    bestGroup.mediaTrackGroup,
                                    0
                                )
                            )
                    )
                    android.util.Log.d("VideoPlayerScreen", "Selected best subtitle track: score=$bestScore, lang=${bestGroup.mediaTrackGroup.getFormat(0).language}")
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    DisposableEffect(Unit) { onDispose {
        // 재생 위치 저장 (90% 이상 진행한 경우 삭제)
        val pos = exoPlayer.currentPosition
        val dur = exoPlayer.duration
        if (dur > 0 && pos > 0) {
            if (pos.toFloat() / dur.toFloat() > 0.9f) {
                PlaybackPositionStore.removePosition(context, videoUrl)
            } else {
                PlaybackPositionStore.savePosition(context, videoUrl, pos, dur)
                android.util.Log.d("VideoPlayerScreen", "Saved playback position: ${pos}ms / ${dur}ms for $videoUrl")
            }
        }
        exoPlayer.release()
    } }

    // BackHandler는 직접 사용하지 않음.
    // onKeyDown/onKeyUp (Activity 레벨)에서 triggerBack()을 호출하므로
    // 컨트롤 토글은 triggerBack() 내부에서 isVideoControlVisible로 처리됨

    // --- Auto-hide timer (3s) ---
    LaunchedEffect(isControlVisible, hideTimerKey) {
        if (isControlVisible) {
            delay(3000L)
            onControlVisibilityChange(false)
        }
    }

    // --- Position update (500ms) ---
    LaunchedEffect(exoPlayer) {
        while (true) {
            currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
            totalDuration = exoPlayer.duration.coerceAtLeast(1L)
            isPlaying = exoPlayer.isPlaying
            delay(500L)
        }
    }

    fun resetHideTimer() { hideTimerKey++; onControlVisibilityChange(true) }

    fun executeButton(index: Int) {
        when (index) {
            0 -> { // 이전 동영상
                if (hasPrevious) {
                    val prevItem = playlist[currentIndex - 1]
                    android.util.Log.d("AutoPlay", "Manual previous: ${prevItem.title} (index=${currentIndex - 1})")
                    onPlayPrevious?.invoke(prevItem)
                    return
                }
            }
            1 -> exoPlayer.seekTo((exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L))
            2 -> if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
            3 -> exoPlayer.seekTo((exoPlayer.currentPosition + 10_000L).coerceAtMost(exoPlayer.duration.coerceAtLeast(0L)))
            4 -> { // 다음 동영상
                if (hasNext) {
                    val nextItem = playlist[currentIndex + 1]
                    android.util.Log.d("AutoPlay", "Manual next: ${nextItem.title} (index=${currentIndex + 1})")
                    onPlayNext?.invoke(nextItem)
                    return
                }
            }
            5 -> subtitleScale = maxOf(0.5f, subtitleScale - 0.15f)
            6 -> subtitleScale = minOf(3.0f, subtitleScale + 0.15f)
            7 -> { // 속도 +0.1
                currentSpeed = (kotlin.math.round((currentSpeed + 0.1f) * 10f) / 10f).coerceIn(0.1f, 2.0f)
                exoPlayer.setPlaybackSpeed(currentSpeed)
            }
            8 -> { // 속도 초기화 (1.0x)
                currentSpeed = 1.0f
                exoPlayer.setPlaybackSpeed(currentSpeed)
            }
            9 -> { // 속도 -0.1
                currentSpeed = (kotlin.math.round((currentSpeed - 0.1f) * 10f) / 10f).coerceIn(0.1f, 2.0f)
                exoPlayer.setPlaybackSpeed(currentSpeed)
            }
            10 -> isOrientationLocked = !isOrientationLocked // 회전 잠금/해제 토글
            11 -> { mainActivity?.enterPipMode(); return }
            12 -> { onClose(); return }
            13 -> { showRendererDialog = true } // 캐스트 버튼
        }
        resetHideTimer()
    }

    // --- Focus for key events ---
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { focusRequester.requestFocus() } catch (e: Exception) { /* ignore */ }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        onControlVisibilityChange(!currentIsControlVisible)
                        if (!currentIsControlVisible) hideTimerKey++
                    },
                    onDoubleTap = { offset ->
                        val screenWidth = size.width
                        if (offset.x < screenWidth / 2f) {
                            exoPlayer.seekTo((exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L))
                            seekIndicatorText = "-10초"
                            showSeekIndicator = true
                        } else {
                            val duration = exoPlayer.duration.coerceAtLeast(0L)
                            exoPlayer.seekTo((exoPlayer.currentPosition + 10_000L).coerceAtMost(duration))
                            seekIndicatorText = "+10초"
                            showSeekIndicator = true
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { _ ->
                        isDraggingScreen = true
                        screenDragPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
                        showSeekIndicator = true
                    },
                    onDragEnd = {
                        isDraggingScreen = false
                        exoPlayer.seekTo(screenDragPosition)
                    },
                    onDragCancel = {
                        isDraggingScreen = false
                        showSeekIndicator = false
                    },
                    onHorizontalDrag = { change: androidx.compose.ui.input.pointer.PointerInputChange, dragAmount: Float ->
                        val duration = exoPlayer.duration.coerceAtLeast(1L)
                        val deltaMs = (dragAmount * 150).toLong()
                        screenDragPosition = (screenDragPosition + deltaMs).coerceIn(0L, duration)
                        seekIndicatorText = "${formatSrtTime(screenDragPosition).substringBefore(",")} / ${formatSrtTime(duration).substringBefore(",")}"
                    }
                )
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        val screenWidth = size.width
                        if (offset.x > screenWidth * 0.6f) { // 오른쪽 40% 영역: 밝기
                            isDraggingBrightness = true
                            showBrightnessIndicator = true
                            if (activity != null) {
                                val lp = activity.window.attributes
                                var current = lp.screenBrightness
                                if (current < 0f) {
                                    try {
                                        current = android.provider.Settings.System.getInt(
                                            context.contentResolver, 
                                            android.provider.Settings.System.SCREEN_BRIGHTNESS
                                        ) / 255f
                                    } catch (e: Exception) { current = 0.5f }
                                    lp.screenBrightness = current
                                    activity.window.attributes = lp
                                }
                                brightnessText = "☀ ${(current * 100).toInt()}%"
                            }
                        } else if (offset.x < screenWidth * 0.4f) { // 왼쪽 40% 영역: 볼륨
                            isDraggingVolume = true
                            showVolumeIndicator = true
                            val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                            val currentVol = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                            val maxVol = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                            currentVolumeFloat = currentVol.toFloat()
                            volumeText = "🔊 ${(currentVol.toFloat() / maxVol * 100).toInt()}%"
                        }
                    },
                    onDragEnd = { 
                        isDraggingBrightness = false 
                        isDraggingVolume = false
                    },
                    onDragCancel = { 
                        isDraggingBrightness = false 
                        isDraggingVolume = false
                    },
                    onVerticalDrag = { _: androidx.compose.ui.input.pointer.PointerInputChange, dragAmount: Float ->
                        val screenHeight = size.height
                        if (isDraggingBrightness && activity != null) {
                            // 위로 스크롤하면 dragAmount가 음수이므로 밝기 증가
                            val deltaBrightness = -(dragAmount / screenHeight) * 1.5f
                            val lp = activity.window.attributes
                            var current = lp.screenBrightness
                            if (current < 0f) current = 0.5f
                            val newBrightness = (current + deltaBrightness).coerceIn(0.01f, 1f)
                            lp.screenBrightness = newBrightness
                            activity.window.attributes = lp
                            brightnessText = "☀ ${(newBrightness * 100).toInt()}%"
                        } else if (isDraggingVolume) {
                            val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                            val maxVol = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                            
                            val deltaVol = -(dragAmount / screenHeight) * maxVol * 1.5f
                            currentVolumeFloat = (currentVolumeFloat + deltaVol).coerceIn(0f, maxVol.toFloat())
                            
                            val newVolInt = currentVolumeFloat.toInt()
                            audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, newVolInt, 0)
                            
                            volumeText = "🔊 ${(newVolInt.toFloat() / maxVol * 100).toInt()}%"
                        }
                    }
                )
            }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionCenter -> {
                            if (!isControlVisible) { onControlVisibilityChange(true); hideTimerKey++ }
                            else if (focusedButtonIndex != 10) executeButton(focusedButtonIndex)
                            true
                        }
                        Key.DirectionLeft -> {
                            if (isControlVisible) {
                                if (focusedButtonIndex == 10) {
                                    // Slider seek -5s
                                    exoPlayer.seekTo((exoPlayer.currentPosition - 5_000L).coerceAtLeast(0L))
                                } else {
                                    focusedButtonIndex = maxOf(0, focusedButtonIndex - 1)
                                }
                                resetHideTimer()
                            } else {
                                exoPlayer.seekTo((exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L))
                            }
                            true
                        }
                        Key.DirectionRight -> {
                            if (isControlVisible) {
                                if (focusedButtonIndex == 100) { // Slider index changed to 100 to avoid conflict
                                    // Slider seek +5s
                                    exoPlayer.seekTo((exoPlayer.currentPosition + 5_000L).coerceAtMost(exoPlayer.duration.coerceAtLeast(0L)))
                                } else {
                                    focusedButtonIndex = minOf(12, focusedButtonIndex + 1)
                                }
                                resetHideTimer()
                            } else {
                                exoPlayer.seekTo((exoPlayer.currentPosition + 10_000L).coerceAtMost(exoPlayer.duration.coerceAtLeast(0L)))
                            }
                            true
                        }
                        Key.DirectionUp -> {
                            if (isControlVisible && focusedButtonIndex < 100) {
                                focusedButtonIndex = 100 // Move to Slider
                            } else {
                                subtitleScale = minOf(3.0f, subtitleScale + 0.15f)
                            }
                            resetHideTimer()
                            true
                        }
                        Key.DirectionDown -> {
                            if (isControlVisible && focusedButtonIndex == 100) {
                                focusedButtonIndex = 2 // Move to Play/Pause button
                            } else {
                                subtitleScale = maxOf(0.5f, subtitleScale - 0.15f)
                            }
                            resetHideTimer()
                            true
                        }
                        Key.Back -> { 
                            // onKeyDown/onKeyUp에서 triggerBack()이 컨트롤하므로 여기서는 처리 안 함
                            false 
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        // --- Video surface ---
        AndroidView(
            factory = { ctx ->
                androidx.media3.ui.PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false // Use custom TV controller
                    keepScreenOn = true // 재생 중 화면 꺼짐 방지
                    subtitleView?.setStyle(
                        androidx.media3.ui.CaptionStyleCompat(
                            android.graphics.Color.WHITE,
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT,
                            androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                            android.graphics.Color.BLACK,
                            null
                        )
                    )
                    exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            android.util.Log.e("VideoPlayerScreen", "ExoPlayer Error: ${error.errorCode} ${error.message}", error)
                        }
                        
                        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                            videoSizeState = videoSize
                        }
                    })
                }
            },
            update = { playerView ->
                playerView.subtitleView?.setFractionalTextSize(
                    androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * subtitleScale
                )
            },
            modifier = Modifier.fillMaxSize()
        )

        // --- TV Control Overlay (bottom) ---
        val showOverlay = isControlVisible && (mainActivity?.isInPipMode?.value != true)
        androidx.compose.animation.AnimatedVisibility(
            visible = showOverlay,
            enter = androidx.compose.animation.fadeIn(tween(250)),
            exit = androidx.compose.animation.fadeOut(tween(250)),
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
        ) {
            val progress = (currentPosition.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6180F23), Color(0xFF180F23)))
                    )
                    .padding(horizontal = 36.dp, vertical = 20.dp)
            ) {
                // Title
                Text(
                    text = title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 10.dp)
                )

                // Progress Slider (Interactive)
                Slider(
                    value = if (isDragging) dragPosition.toFloat() else currentPosition.toFloat(),
                    onValueChange = { 
                        isDragging = true
                        dragPosition = it.toLong()
                        resetHideTimer()
                    },
                    onValueChangeFinished = {
                        exoPlayer.seekTo(dragPosition)
                        isDragging = false
                    },
                    valueRange = 0f..totalDuration.toFloat().coerceAtLeast(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = PrimaryColor,
                        activeTrackColor = PrimaryColor,
                        inactiveTrackColor = Color.White.copy(alpha = 0.24f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 0.dp)
                        .border(
                            width = if (focusedButtonIndex == 100) 3.dp else 0.dp,
                            color = if (focusedButtonIndex == 100) PrimaryColor else Color.Transparent,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                        )
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatSrtTime(currentPosition).substringBefore(","), color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
                    Text(formatSrtTime(totalDuration).substringBefore(","), color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
                }

                // Control buttons row
                val configuration = androidx.compose.ui.platform.LocalConfiguration.current
                val isPortrait = configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

                if (isPortrait) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            TvControlButton(label = "⏮\n이전", isFocused = focusedButtonIndex == 0, highlightColor = PrimaryColor, onClick = { executeButton(0) }, enabled = hasPrevious)
                            Spacer(Modifier.width(8.dp))
                            TvControlButton(label = "◀◀\n-10초", isFocused = focusedButtonIndex == 1, highlightColor = PrimaryColor, onClick = { executeButton(1) })
                            Spacer(Modifier.width(8.dp))
                            TvControlButton(label = if (isPlaying) "⏸" else "▶", isFocused = focusedButtonIndex == 2, highlightColor = PrimaryColor, isLarge = true, onClick = { executeButton(2) })
                            Spacer(Modifier.width(8.dp))
                            TvControlButton(label = "+10초\n▶▶", isFocused = focusedButtonIndex == 3, highlightColor = PrimaryColor, onClick = { executeButton(3) })
                            Spacer(Modifier.width(8.dp))
                            TvControlButton(label = "⏭\n다음", isFocused = focusedButtonIndex == 4, highlightColor = PrimaryColor, onClick = { executeButton(4) }, enabled = hasNext)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            TvControlButton(label = "자막\n－", isFocused = focusedButtonIndex == 5, highlightColor = PrimaryColor, onClick = { executeButton(5) })
                            Spacer(Modifier.width(8.dp))
                            TvControlButton(label = "자막\n＋", isFocused = focusedButtonIndex == 6, highlightColor = PrimaryColor, onClick = { executeButton(6) })
                            Spacer(Modifier.width(8.dp))
                            TvControlButton(label = "속도\n＋", isFocused = focusedButtonIndex == 7, highlightColor = PrimaryColor, onClick = { executeButton(7) })
                            Spacer(Modifier.width(8.dp))
                            TvControlButton(label = "${currentSpeed}x\n초기화", isFocused = focusedButtonIndex == 8, highlightColor = PrimaryColor, onClick = { executeButton(8) })
                            Spacer(Modifier.width(8.dp))
                            TvControlButton(label = "속도\n－", isFocused = focusedButtonIndex == 9, highlightColor = PrimaryColor, onClick = { executeButton(9) })
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            TvControlButton(label = if (isOrientationLocked) "회전\n잠금" else "회전\n해제", isFocused = focusedButtonIndex == 10, highlightColor = PrimaryColor, onClick = { executeButton(10) })
                            Spacer(Modifier.width(16.dp))
                            TvControlButton(label = "PIP\n화면", isFocused = focusedButtonIndex == 11, highlightColor = PrimaryColor, onClick = { executeButton(11) })
                            Spacer(Modifier.width(16.dp))
                            TvControlButton(label = if (isCasting) "📺\n연결\u2713" else "📺\n캐스트", isFocused = focusedButtonIndex == 13, highlightColor = if (isCasting) Color(0xFF4CAF50) else PrimaryColor, onClick = { executeButton(13) })
                            Spacer(Modifier.width(16.dp))
                            TvControlButton(label = "✕\n닫기", isFocused = focusedButtonIndex == 12, highlightColor = PrimaryColor, onClick = { executeButton(12) })
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        TvControlButton(label = "⏮\n이전", isFocused = focusedButtonIndex == 0, highlightColor = PrimaryColor, onClick = { executeButton(0) }, enabled = hasPrevious)
                        Spacer(Modifier.width(8.dp))
                        TvControlButton(label = "◀◀\n-10초", isFocused = focusedButtonIndex == 1, highlightColor = PrimaryColor, onClick = { executeButton(1) })
                        Spacer(Modifier.width(8.dp))
                        TvControlButton(label = if (isPlaying) "⏸" else "▶", isFocused = focusedButtonIndex == 2, highlightColor = PrimaryColor, isLarge = true, onClick = { executeButton(2) })
                        Spacer(Modifier.width(8.dp))
                        TvControlButton(label = "+10초\n▶▶", isFocused = focusedButtonIndex == 3, highlightColor = PrimaryColor, onClick = { executeButton(3) })
                        Spacer(Modifier.width(8.dp))
                        TvControlButton(label = "⏭\n다음", isFocused = focusedButtonIndex == 4, highlightColor = PrimaryColor, onClick = { executeButton(4) }, enabled = hasNext)
                        Spacer(Modifier.width(16.dp))
                        TvControlButton(label = "자막\n－", isFocused = focusedButtonIndex == 5, highlightColor = PrimaryColor, onClick = { executeButton(5) })
                        Spacer(Modifier.width(8.dp))
                        TvControlButton(label = "자막\n＋", isFocused = focusedButtonIndex == 6, highlightColor = PrimaryColor, onClick = { executeButton(6) })
                        Spacer(Modifier.width(16.dp))
                        TvControlButton(label = "속도\n＋", isFocused = focusedButtonIndex == 7, highlightColor = PrimaryColor, onClick = { executeButton(7) })
                        Spacer(Modifier.width(8.dp))
                        TvControlButton(label = "${currentSpeed}x\n초기화", isFocused = focusedButtonIndex == 8, highlightColor = PrimaryColor, onClick = { executeButton(8) })
                        Spacer(Modifier.width(8.dp))
                        TvControlButton(label = "속도\n－", isFocused = focusedButtonIndex == 9, highlightColor = PrimaryColor, onClick = { executeButton(9) })
                        Spacer(Modifier.width(16.dp))
                        TvControlButton(label = if (isOrientationLocked) "회전\n잠금" else "회전\n해제", isFocused = focusedButtonIndex == 10, highlightColor = PrimaryColor, onClick = { executeButton(10) })
                        Spacer(Modifier.width(16.dp))
                        TvControlButton(label = "PIP\n화면", isFocused = focusedButtonIndex == 11, highlightColor = PrimaryColor, onClick = { executeButton(11) })
                        Spacer(Modifier.width(16.dp))
                        TvControlButton(label = if (isCasting) "📺\n연결\u2713" else "📺\n캐스트", isFocused = focusedButtonIndex == 13, highlightColor = if (isCasting) Color(0xFF4CAF50) else PrimaryColor, onClick = { executeButton(13) })
                        Spacer(Modifier.width(16.dp))
                        TvControlButton(label = "✕\n닫기", isFocused = focusedButtonIndex == 12, highlightColor = PrimaryColor, onClick = { executeButton(12) })
                    }
                }

                // Shortcut hints (Updated for Slider navigation)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "↑↓ 시크바/버튼 이동  |  ◀ ▶ 포커스 이동/가속  |  확인 실행  |  BACK 닫기",
                    color = Color.White.copy(alpha = 0.45f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally)
                )
            }
        }

        // --- Seek Indicator Overlay ---
        androidx.compose.animation.AnimatedVisibility(
            visible = showSeekIndicator,
            enter = androidx.compose.animation.fadeIn(tween(150)),
            exit = androidx.compose.animation.fadeOut(tween(300)),
            modifier = Modifier.align(androidx.compose.ui.Alignment.Center)
        ) {
            Box(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Text(
                    text = seekIndicatorText,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // --- Brightness Indicator Overlay ---
        androidx.compose.animation.AnimatedVisibility(
            visible = showBrightnessIndicator,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(150)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(300)),
            modifier = Modifier.align(androidx.compose.ui.Alignment.CenterEnd).padding(end = 48.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Text(
                    text = brightnessText,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // --- Volume Indicator Overlay ---
        androidx.compose.animation.AnimatedVisibility(
            visible = showVolumeIndicator,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(150)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(300)),
            modifier = Modifier.align(androidx.compose.ui.Alignment.CenterStart).padding(start = 48.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Text(
                    text = volumeText,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // ── 캐스팅 오버레이 ──────────────────────────────────────────
        if (isCasting && currentRenderer != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f)),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                // X 종료 버튼 (우측 상단)
                Box(
                    modifier = Modifier
                        .align(androidx.compose.ui.Alignment.TopEnd)
                        .padding(16.dp)
                        .size(44.dp)
                        .background(Color.White.copy(alpha = 0.15f), androidx.compose.foundation.shape.CircleShape)
                        .clickable { onStopCasting() },
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Text("✕", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Column(
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Text("📺", style = MaterialTheme.typography.displayLarge)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "\"${currentRenderer.details?.friendlyName ?: "스마트 TV"}\"에서 재생 중",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = title,
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))

                    // Slider 드래그 상태 관리
                    var isCastDragging by remember { mutableStateOf(false) }
                    var castDragValue by remember { mutableFloatStateOf(0f) }
                    val castProgress = if (isCastDragging) castDragValue else (castPosition.toFloat() / castDuration.toFloat()).coerceIn(0f, 1f)
                    Slider(
                        value = castProgress,
                        onValueChange = { newVal ->
                            isCastDragging = true
                            castDragValue = newVal
                        },
                        onValueChangeFinished = {
                            val seekMs = (castDragValue * castDuration).toLong()
                            onCastSeek(seekMs)
                            castPosition = seekMs
                            isCastDragging = false
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF4CAF50),
                            activeTrackColor = Color(0xFF4CAF50),
                            inactiveTrackColor = Color.White.copy(alpha = 0.24f)
                        ),
                        modifier = Modifier.fillMaxWidth(0.8f)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(0.8f),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatSrtTime(castPosition).substringBefore(","), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                        Text(formatSrtTime(castDuration).substringBefore(","), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                    }

                    Spacer(Modifier.height(20.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color.White.copy(alpha = 0.15f), androidx.compose.foundation.shape.CircleShape)
                                .clickable { onCastSeek((castPosition - 10000L).coerceAtLeast(0L)) },
                            contentAlignment = androidx.compose.ui.Alignment.Center
                        ) {
                            Text("◀◀", color = Color.White, style = MaterialTheme.typography.bodySmall)
                        }

                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .background(Color(0xFF4CAF50), androidx.compose.foundation.shape.CircleShape)
                                .clickable { if (castIsPlaying) onCastPause() else onCastResume() },
                            contentAlignment = androidx.compose.ui.Alignment.Center
                        ) {
                            Text(
                                text = if (castIsPlaying) "⏸" else "▶",
                                color = Color.White,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color.White.copy(alpha = 0.15f), androidx.compose.foundation.shape.CircleShape)
                                .clickable { onCastSeek((castPosition + 10000L).coerceAtMost(castDuration)) },
                            contentAlignment = androidx.compose.ui.Alignment.Center
                        ) {
                            Text("▶▶", color = Color.White, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    Box(
                        modifier = Modifier
                            .background(Color(0x44FF5252), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                            .clickable { onStopCasting() }
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        Text("연결 해제", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // ── 캐스트 에러 메시지 ──
        castErrorMessage?.let { msg ->
            LaunchedEffect(msg) {
                delay(3000L)
                castErrorMessage = null
            }
            Box(
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.TopCenter)
                    .padding(top = 60.dp)
                    .background(Color(0xCC333333), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(msg, color = Color(0xFFFF5252), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    // ── 렌더러 선택 다이얼로그 ──────────────────────────────────────
    if (showRendererDialog) {
        LaunchedEffect(Unit) { onSearchRenderers() }

        AlertDialog(
            onDismissRequest = { showRendererDialog = false },
            title = {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("📺 ", style = MaterialTheme.typography.headlineSmall)
                    Text("스마트 TV로 전송", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    if (isCasting && currentRenderer != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF4CAF50).copy(alpha = 0.15f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                .padding(12.dp)
                        ) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text("✅ ", style = MaterialTheme.typography.bodyLarge)
                                Column {
                                    Text(
                                        text = currentRenderer.details?.friendlyName ?: "TV",
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF4CAF50)
                                    )
                                    Text("연결됨", style = MaterialTheme.typography.bodySmall, color = Color(0xFF4CAF50).copy(alpha = 0.7f))
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Divider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(8.dp))
                    }

                    if (rendererDevices.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = androidx.compose.ui.Alignment.Center
                        ) {
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(32.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(12.dp))
                                Text("같은 네트워크의 TV를 검색 중...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                            }
                        }
                    } else {
                        Text("발견된 기기:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        Spacer(Modifier.height(8.dp))
                        rendererDevices.forEach { device ->
                            val isCurrentDevice = currentRenderer?.identity?.udn == device.identity.udn
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .background(
                                        if (isCurrentDevice) Color(0xFF4CAF50).copy(alpha = 0.1f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        showRendererDialog = false
                                        val currentPos = exoPlayer.currentPosition.coerceAtLeast(0L)
                                        onCastToDevice(device, finalSubtitleUrl, currentPos)
                                    }
                                    .padding(12.dp)
                            ) {
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Text("📺 ", style = MaterialTheme.typography.bodyLarge)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = device.details?.friendlyName ?: device.displayString,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                        val model = device.details?.modelDetails?.modelName ?: ""
                                        if (model.isNotBlank()) {
                                            Text(model, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                        }
                                    }
                                    if (isCurrentDevice) {
                                        Text("✓", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isCasting) {
                        TextButton(onClick = {
                            showRendererDialog = false
                            onStopCasting()
                        }) {
                            Text("연결 해제", color = Color(0xFFFF5252))
                        }
                    }
                    TextButton(onClick = { onSearchRenderers() }) {
                        Text("새로고침")
                    }
                    TextButton(onClick = { showRendererDialog = false }) {
                        Text("닫기")
                    }
                }
            }
        )
    }
}

@Composable
fun TvControlButton(
    label: String,
    isFocused: Boolean,
    highlightColor: Color,
    isLarge: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit = {}
) {
    val size = if (isLarge) 52.dp else 40.dp
    val alpha = if (enabled) 1f else 0.35f
    // Match Stitch design: Large play button is fully colored, others are slightly translucent
    val isPrimaryFilled = isLarge
    val bgColor = if (isPrimaryFilled) highlightColor else (if (isFocused && enabled) Color.White.copy(alpha = 0.22f) else Color(0x661E293B)) // slate-800/40 equivalent
    val textColor = if (isPrimaryFilled) Color(0xFF180F23) else (if (isFocused && enabled) highlightColor else Color.White)
    val borderColor = if (isFocused && !isPrimaryFilled && enabled) highlightColor else Color.White.copy(alpha = 0.1f)
    val borderWidth = if (isFocused && !isPrimaryFilled && enabled) 2.dp else 1.dp
    val fontSize = if (isLarge) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.labelSmall
    val shape = androidx.compose.foundation.shape.CircleShape // Stitch design uses rounded-full for buttons

    Box(
        modifier = Modifier
            .size(size)
            .alpha(alpha)
            .border(borderWidth, borderColor, shape)
            .background(bgColor, shape)
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            ) { if (enabled) onClick() },
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            style = fontSize,
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}




suspend fun downloadAndProcessSubtitle(context: android.content.Context, subtitleUrl: String, ftpEncoding: String = "AUTO", httpHeaders: Map<String, String>? = null, providedExtension: String? = null): Pair<String?, String?> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    try {
        // Clean up old cached_sub files (older than 24 hours) to prevent storage bloat
        context.cacheDir.listFiles()?.forEach {
            if (it.isFile && it.name.startsWith("cached_sub_") && System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000) {
                it.delete()
            }
        }

        val bytes = if (subtitleUrl.startsWith("smb://")) {
            val uri = android.net.Uri.parse(subtitleUrl)
            val userInfo = uri.userInfo
            val userName = userInfo?.substringBefore(":") ?: ""
            val password = userInfo?.substringAfter(":") ?: ""
            
            val ctx = SmbManager.buildContext(userName, password)
            val cleanUrl = subtitleUrl.replaceFirst(Regex("(?<=smb://).*?@"), "")
            val file = jcifs.smb.SmbFile(cleanUrl, ctx)
            val stream = file.inputStream
            val b = stream.readBytesWithLimit()
            stream.close()
            b
        } else if (subtitleUrl.startsWith("http")) {
            val uri = android.net.Uri.parse(subtitleUrl)
            val userInfo = uri.userInfo
            val encodedUserInfo = uri.encodedUserInfo
            val authHeader = if (!userInfo.isNullOrBlank() && !encodedUserInfo.isNullOrBlank()) {
                "Basic " + android.util.Base64.encodeToString(userInfo.toByteArray(kotlin.text.Charsets.UTF_8), android.util.Base64.NO_WRAP)
            } else null
            
            val urlStr = if (!encodedUserInfo.isNullOrBlank()) subtitleUrl.replaceFirst("://$encodedUserInfo@", "://") else subtitleUrl
            val urlObj = java.net.URL(urlStr)
            val conn = urlObj.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            
            httpHeaders?.forEach { (k, v) ->
                conn.setRequestProperty(k, v)
            }
            
            if (authHeader != null && conn.getRequestProperty("Authorization") == null) {
                conn.setRequestProperty("Authorization", authHeader)
            }
            if (conn.responseCode !in 200..299) return@withContext null to null
            conn.inputStream.readBytesWithLimit()
        } else if (subtitleUrl.startsWith("sftp://")) {
            val uri = android.net.Uri.parse(subtitleUrl)
            val userInfo = uri.userInfo
            val username = userInfo?.substringBefore(":") ?: ""
            val password = userInfo?.substringAfter(":", "") ?: ""
            val host = uri.host ?: ""
            val port = if (uri.port > 0) uri.port else 22
            val remotePath = uri.path ?: ""
            
            SftpManager.getFileBytes(host, port, username, password, remotePath).getOrNull()
        } else if (subtitleUrl.startsWith("ftp://")) {
            val uri = android.net.Uri.parse(subtitleUrl)
            val userInfo = uri.userInfo
            val username = userInfo?.substringBefore(":") ?: ""
            val password = userInfo?.substringAfter(":", "") ?: ""
            val host = uri.host ?: ""
            val port = if (uri.port > 0) uri.port else 21
            val remotePath = uri.path ?: ""
            
            FtpManager.getFileBytes(host, port, username, password, remotePath, ftpEncoding).getOrNull()
        } else {
            val path = subtitleUrl.replace("file://", "")
            val file = java.io.File(path)
            if (file.exists()) {
                if (file.length() > 10 * 1024 * 1024) throw Exception("Local file exceeds subtitle size limit (10MB).")
                file.readBytes()
            } else null
        }
        
        if (bytes == null || bytes.isEmpty()) return@withContext null to null
        
        val sniffEnd = Math.min(bytes.size, 4096)
        val sniffText = String(bytes.copyOfRange(0, sniffEnd), kotlin.text.Charsets.UTF_8).lowercase()
        val isLikelySubtitle = sniffText.contains("<sami") || sniffText.contains("-->") || sniffText.contains("[script info]") || sniffText.contains("dialogue:") || sniffText.contains("webvtt")
        if (!isLikelySubtitle && bytes.size > 1024 * 1024) return@withContext null to null
        
        var text = ""
        val b0 = if (bytes.isNotEmpty()) bytes[0].toInt() and 0xFF else 0
        val b1 = if (bytes.size > 1) bytes[1].toInt() and 0xFF else 0
        val ext = (providedExtension ?: subtitleUrl.substringAfterLast('.')).lowercase()
        
        if (b0 == 0xFF && b1 == 0xFE) {
            text = String(bytes, kotlin.text.Charsets.UTF_16LE)
        } else if (b0 == 0xFE && b1 == 0xFF) {
            text = String(bytes, kotlin.text.Charsets.UTF_16BE)
        } else if (b0 == 0x3C && b1 == 0x00) {
            text = String(bytes, kotlin.text.Charsets.UTF_16LE)
        } else if (b0 == 0x00 && b1 == 0x3C) {
            text = String(bytes, kotlin.text.Charsets.UTF_16BE)
        } else {
            text = String(bytes, kotlin.text.Charsets.UTF_8)
            if (text.contains("\uFFFD") || (!text.contains("<sami", true) && ext == "smi")) {
                text = String(bytes, java.nio.charset.Charset.forName("EUC-KR"))
            }
        }
        
        text = text.replace(Regex("charset=euc-kr", RegexOption.IGNORE_CASE), "charset=utf-8")
        text = text.replace(Regex("charset=\"euc-kr\"", RegexOption.IGNORE_CASE), "charset=\"utf-8\"")
        text = text.replace(Regex("charset=cp949", RegexOption.IGNORE_CASE), "charset=utf-8")
        
        val lowerText = text.lowercase()
        val isSmi = lowerText.contains("<sami")
        val isSrt = lowerText.contains("-->")
        val isAss = lowerText.contains("[script info]") || lowerText.contains("dialogue:") || ext == "ass"
        
        val uniqueSuffix = System.currentTimeMillis()
        if ((ext == "smi" && isSmi) || isSmi) {
            val srtText = convertSmiToSrt(text)
            val cacheFile = java.io.File(context.cacheDir, "cached_sub_${uniqueSuffix}.srt")
            cacheFile.writeText(srtText, kotlin.text.Charsets.UTF_8)
            return@withContext "file://" + cacheFile.absolutePath to "srt"
        } else if ((ext == "srt" && isSrt) || isSrt) {
            val cacheFile = java.io.File(context.cacheDir, "cached_sub_${uniqueSuffix}.srt")
            cacheFile.writeText(text, kotlin.text.Charsets.UTF_8)
            return@withContext "file://" + cacheFile.absolutePath to "srt"
        } else if (isAss) {
            val cacheFile = java.io.File(context.cacheDir, "cached_sub_${uniqueSuffix}.ass")
            cacheFile.writeText(text, kotlin.text.Charsets.UTF_8)
            return@withContext "file://" + cacheFile.absolutePath to "ass"
        } else {
            val cacheFile = java.io.File(context.cacheDir, "cached_sub_${uniqueSuffix}." + ext)
            cacheFile.writeBytes(bytes)
            return@withContext "file://" + cacheFile.absolutePath to ext
        }
    } catch (e: Exception) {
        android.util.Log.e("SubtitleSearch", "Process failed", e)
        null to null
    }
}

fun convertSmiToSrt(smiText: String): String {
    val srt = StringBuilder()
    var counter = 1
    
    val parts = smiText.split(Regex("(?i)<SYNC"))
    var currentStart = -1L
    var currentText = ""
    
    for (i in 1 until parts.size) {
        val part = parts[i]
        val startMatch = Regex("(?i)\\s*Start\\s*=\\s*([0-9]+)").find(part)
        
        if (startMatch != null) {
            val timeMs = startMatch.groupValues[1].toLong()
            
            val textStart = part.indexOf('>')
            val rawText = if (textStart != -1) part.substring(textStart + 1) else ""
            
            var cleanText = rawText.replace(Regex("(?i)<br\\s*/?>"), "\n")
            cleanText = cleanText.replace(Regex("<[^>]+>"), "")
            cleanText = cleanText.replace("&nbsp;", " ")
            cleanText = cleanText.trim()
            
            if (currentStart != -1L && currentText.isNotBlank()) {
                srt.append(counter++).append("\n")
                srt.append(formatSrtTime(currentStart)).append(" --> ").append(formatSrtTime(timeMs)).append("\n")
                srt.append(currentText).append("\n\n")
            }
            
            currentStart = timeMs
            currentText = cleanText
        }
    }
    
    if (currentStart != -1L && currentText.isNotBlank()) {
        srt.append(counter++).append("\n")
        srt.append(formatSrtTime(currentStart)).append(" --> ").append(formatSrtTime(currentStart + 5000)).append("\n")
        srt.append(currentText).append("\n\n")
    }
    
    return srt.toString()
}

fun formatSrtTime(ms: Long): String {
    val h = ms / 3600000
    val m = (ms % 3600000) / 60000
    val s = (ms % 60000) / 1000
    val msRem = ms % 1000
    return String.format(java.util.Locale.US, "%02d:%02d:%02d,%03d", h, m, s, msRem)
}

fun java.io.InputStream.readBytesWithLimit(limit: Int = 10 * 1024 * 1024): ByteArray {
    val out = java.io.ByteArrayOutputStream(Math.max(8192, this.available().coerceAtMost(limit)))
    val buffer = ByteArray(8192)
    var bytesRead: Int
    var totalBytes = 0
    while (this.read(buffer).also { bytesRead = it } != -1) {
        totalBytes += bytesRead
        if (totalBytes > limit) {
            throw java.io.IOException("File exceeds subtitle size limit (${limit / (1024 * 1024)}MB).")
        }
        out.write(buffer, 0, bytesRead)
    }
    return out.toByteArray()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalBrowserScreen(
    padding: PaddingValues,
    isTvMode: Boolean = false,
    initialSelectedIndex: Int = 0,
    sortOrder: SortOrder,
    onSortOrderChange: (SortOrder) -> Unit,
    viewMode: LocalViewMode,
    onViewModeChange: (LocalViewMode) -> Unit,
    selectedFolder: String?,
    onSelectedFolderChange: (String?) -> Unit,
    onBackClick: () -> Unit,
    onItemClick: (LocalVideoItem, Int, List<LocalVideoItem>) -> Unit
) {
    var videos by remember { mutableStateOf<List<LocalVideoItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    
    val context = androidx.compose.ui.platform.LocalContext.current
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    // 폴더 목록 추출 (videos가 바뀔 때마다 계산)
    val folders = remember(videos, searchQuery) {
        val baseFolders = videos.mapNotNull { it.path?.substringBeforeLast("/") }
            .distinct()
            .map { fullPath -> 
                val name = fullPath.substringAfterLast("/")
                name to fullPath
            }
        
        if (searchQuery.isNotEmpty()) {
            baseFolders.filter { it.first.contains(searchQuery, ignoreCase = true) }
                .sortedBy { it.first.lowercase() }
        } else {
            baseFolders.sortedBy { it.first.lowercase() }
        }
    }

    // 현재 뷰모드와 선택된 폴더에 따라 보여줄 목록 필터링
    val displayVideos = remember(videos, viewMode, selectedFolder, searchQuery) {
        val base = if (viewMode == LocalViewMode.FOLDERS && selectedFolder != null) {
            videos.filter { it.path?.startsWith(selectedFolder) == true }
        } else {
            videos
        }
        if (searchQuery.isNotEmpty()) {
            base.filter { it.title.contains(searchQuery, ignoreCase = true) }
        } else {
            base
        }
    }

    LaunchedEffect(viewMode, selectedFolder) {
        searchQuery = ""
        isSearchActive = false
    }

    LaunchedEffect(displayVideos, isLoading) {
        if (!isLoading && displayVideos.isNotEmpty()) {
            if (initialSelectedIndex in displayVideos.indices) {
                listState.scrollToItem(initialSelectedIndex)
            }
        }
    }

    LaunchedEffect(sortOrder) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                isLoading = true
                val videoList = mutableListOf<LocalVideoItem>()
                
                val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL)
                } else {
                    android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                }

                val projection = arrayOf(
                    android.provider.MediaStore.Video.Media._ID,
                    android.provider.MediaStore.Video.Media.DISPLAY_NAME,
                    android.provider.MediaStore.Video.Media.DURATION,
                    android.provider.MediaStore.Video.Media.SIZE,
                    android.provider.MediaStore.Video.Media.DATA,
                    android.provider.MediaStore.Video.Media.DATE_ADDED
                )

                val mediaStoreSortOrder = when (sortOrder) {
                    SortOrder.NAME_ASC -> "${android.provider.MediaStore.Video.Media.DISPLAY_NAME} ASC"
                    SortOrder.NAME_DESC -> "${android.provider.MediaStore.Video.Media.DISPLAY_NAME} DESC"
                    SortOrder.DATE_DESC -> "${android.provider.MediaStore.Video.Media.DATE_ADDED} DESC"
                    SortOrder.DATE_ASC -> "${android.provider.MediaStore.Video.Media.DATE_ADDED} ASC"
                }

                context.contentResolver.query(
                    collection,
                    projection,
                    null,
                    null,
                    mediaStoreSortOrder
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media._ID)
                    val nameColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DISPLAY_NAME)
                    val durationColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DURATION)
                    val sizeColumn = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.SIZE)
                    val dataColumn = cursor.getColumnIndex(android.provider.MediaStore.Video.Media.DATA)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val name = cursor.getString(nameColumn) ?: "Unknown"
                        val duration = cursor.getLong(durationColumn)
                        val size = cursor.getLong(sizeColumn)
                        val path = if (dataColumn != -1) cursor.getString(dataColumn) else null
                        
                        val contentUri = android.content.ContentUris.withAppendedId(collection, id)
                        
                        videoList.add(LocalVideoItem(id, contentUri, name, duration, size, path))
                    }
                }
                
                videos = videoList
            } catch (e: Exception) {
                errorMessage = e.message
            } finally {
                isLoading = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(padding)
    ) {
        TopAppBar(
            title = { 
                if (isSearchActive) {
                    androidx.compose.material3.TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("이름 검색...", style = MaterialTheme.typography.bodyMedium) },
                        textStyle = MaterialTheme.typography.bodyMedium,
                        singleLine = true,
                        colors = androidx.compose.material3.TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                } else {
                    Column {
                        Text(
                            text = if (viewMode == LocalViewMode.ALL_VIDEOS) "로컬 동영상 (전체)" 
                                   else if (selectedFolder == null) "로컬 동영상 (폴더별)"
                                   else "폴더: ${selectedFolder?.substringAfterLast("/")}",
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Text(
                            text = sortOrder.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
                }
            },
            actions = {
                if (isSearchActive) {
                    IconButton(onClick = { 
                        searchQuery = ""
                        isSearchActive = false 
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "닫기", tint = MaterialTheme.colorScheme.onBackground)
                    }
                } else {
                    IconButton(onClick = { isSearchActive = true }) {
                        Icon(Icons.Default.Search, contentDescription = "검색", tint = MaterialTheme.colorScheme.onBackground)
                    }
                }

                // 뷰 모드 전환 버튼
                IconButton(onClick = {
                    onViewModeChange(
                        if (viewMode == LocalViewMode.ALL_VIDEOS) LocalViewMode.FOLDERS 
                        else LocalViewMode.ALL_VIDEOS
                    )
                    onSelectedFolderChange(null)
                }) {
                    Icon(
                        imageVector = if (viewMode == LocalViewMode.ALL_VIDEOS) Icons.Default.Folder else Icons.Default.List,
                        contentDescription = "Toggle View Mode",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }

                // 정렬 메뉴
                Box {
                    IconButton(onClick = { sortMenuExpanded = true }) {
                        Icon(Icons.Default.Sort, contentDescription = "Sort", tint = MaterialTheme.colorScheme.onBackground)
                    }
                    DropdownMenu(
                        expanded = sortMenuExpanded,
                        onDismissRequest = { sortMenuExpanded = false }
                    ) {
                        SortOrder.values().forEach { order ->
                            DropdownMenuItem(
                                text = { Text(order.displayName) },
                                onClick = {
                                    onSortOrderChange(order)
                                    sortMenuExpanded = false
                                },
                                leadingIcon = {
                                    if (sortOrder == order) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            )
                        }
                    }
                }
                
                ExitAppButton()
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
        
        val firstItemFocusRequester = remember { FocusRequester() }

        // Removed initial requestFocus as it's handled in LazyColumn items

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else if (errorMessage != null) {
            Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    Text("에러: $errorMessage", color = MaterialTheme.colorScheme.error, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        val uri = android.net.Uri.fromParts("package", context.packageName, null)
                        intent.data = uri
                        context.startActivity(intent)
                    }) {
                        Text("앱 설정으로 이동하여 미디어 권한을 확인하세요")
                    }
                }
            }
        } else if (videos.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("기기에 동영상이 없거나 접근 권한이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (viewMode == LocalViewMode.FOLDERS && selectedFolder == null) {
                    items(folders) { (name, fullPath) ->
                        var isFocused by remember { mutableStateOf(false) }
                        val itemFocusRequester = remember { FocusRequester() }
                        val PrimaryColor = MaterialTheme.colorScheme.primary

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(itemFocusRequester)
                                .onFocusChanged { isFocused = it.isFocused }
                                .focusable()
                                .onKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionCenter) {
                                        onSelectedFolderChange(fullPath)
                                        true
                                    } else false
                                }
                                .clickable { onSelectedFolderChange(fullPath) }
                                .border(
                                    width = if (isTvMode && isFocused) 3.dp else 0.dp,
                                    color = if (isTvMode && isFocused) PrimaryColor else Color.Transparent,
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                                ),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f), shape = androidx.compose.foundation.shape.CircleShape),
                                    contentAlignment = androidx.compose.ui.Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = name, 
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    val count = videos.count { it.path?.startsWith(fullPath) == true }
                                    Text(
                                        text = "동영상 ${count}개", 
                                        style = MaterialTheme.typography.bodySmall, 
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                } else {
                    itemsIndexed(displayVideos, key = { _, video -> video.id }) { index, video ->
                        var isFocused by remember { mutableStateOf(false) }
                        val itemFocusRequester = remember { FocusRequester() }
                        val PrimaryColor = MaterialTheme.colorScheme.primary

                        if (!isLoading && index == initialSelectedIndex) {
                            LaunchedEffect(Unit) {
                                try { itemFocusRequester.requestFocus() } catch(e:Exception){}
                            }
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(itemFocusRequester)
                                .onFocusChanged { isFocused = it.isFocused }
                                .focusable()
                                .onKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionCenter) {
                                        onItemClick(video, index, displayVideos)
                                        true
                                    } else false
                                }
                                .clickable { onItemClick(video, index, displayVideos) }
                                .border(
                                    width = if (isTvMode && isFocused) 3.dp else 0.dp,
                                    color = if (isTvMode && isFocused) PrimaryColor else Color.Transparent,
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                                ),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), shape = androidx.compose.foundation.shape.CircleShape),
                                    contentAlignment = androidx.compose.ui.Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = video.title, 
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                        maxLines = 2,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    val sizeMb = video.size / (1024.0 * 1024.0)
                                    val durationText = formatSrtTime(video.duration).substringBefore(",") // Basic formatting
                                    Text(
                                        text = String.format(java.util.Locale.US, "%.1f MB • %s", sizeMb, durationText), 
                                        style = MaterialTheme.typography.bodySmall, 
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                // 저장된 재생 위치 표시 (시간 + 프로그레스 바)
                                val savedPos = PlaybackPositionStore.getPosition(context, video.uri.toString())
                                if (savedPos > 0L) {
                                    val savedDur = PlaybackPositionStore.getDuration(context, video.uri.toString())
                                    val effectiveDur = if (savedDur > 0L) savedDur else if (video.duration > 0) video.duration else 0L
                                    Column(
                                        horizontalAlignment = androidx.compose.ui.Alignment.End,
                                        modifier = Modifier.padding(start = 12.dp).width(72.dp)
                                    ) {
                                        Text(
                                            text = "▶ " + PlaybackPositionStore.formatPosition(savedPos),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                        )
                                        if (effectiveDur > 0L) {
                                            Spacer(Modifier.height(3.dp))
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(4.dp)
                                                    .background(
                                                        MaterialTheme.colorScheme.surfaceVariant,
                                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp)
                                                    )
                                            ) {
                                                val progress = (savedPos.toFloat() / effectiveDur.toFloat()).coerceIn(0f, 1f)
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth(progress)
                                                        .fillMaxHeight()
                                                        .background(
                                                            MaterialTheme.colorScheme.primary,
                                                            shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp)
                                                        )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HomeScreen(
    padding: PaddingValues,
    onLocalClick: () -> Unit,
    onDlnaClick: () -> Unit,
    onSmbClick: () -> Unit = {},
    onWebDavClick: () -> Unit = {},
    onFtpSftpClick: () -> Unit = {},
    onGoogleDriveSuccess: (com.google.android.gms.auth.api.signin.GoogleSignInAccount) -> Unit = {},
    onOneDriveClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onLicenseClick: () -> Unit = {},
    isTvMode: Boolean = false,
    onZipClick: () -> Unit = {},
    codecInstallResultMessage: String? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    
    val googleSignInLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val account = GoogleDriveAuthManager.handleSignInResult(result.data)
        if (account != null) {
            onGoogleDriveSuccess(account)
        } else {
            android.widget.Toast.makeText(context, "Google 로그인이 실패하거나 취소되었습니다.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    var isLocalFocused by remember { mutableStateOf(false) }
    var isDlnaFocused by remember { mutableStateOf(false) }
    var isSmbFocused by remember { mutableStateOf(false) }
    var isWebDavFocused by remember { mutableStateOf(false) }
    var isFtpSftpFocused by remember { mutableStateOf(false) }
    var isGoogleDriveFocused by remember { mutableStateOf(false) }
    var isOneDriveFocused by remember { mutableStateOf(false) }

    val card1BorderStart = Color(0xFFD280FF)
    val card1BorderEnd   = Color(0xFF7C3AED)
    val card1Bg          = Color(0xFF5B3F80)

    val card2BorderStart = Color(0xFF34D399)
    val card2BorderEnd   = Color(0xFF0EA5E9)
    val card2Bg          = Color(0xFF1A5F5F)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0C1122), Color(0xFF05070F))))
    ) {
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 28.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
        ) {
            item {
                Spacer(modifier = Modifier.height(if (isTvMode) 96.dp else 48.dp))

                // Play Button Logo: Canvas-drawn ring + triangle
                androidx.compose.foundation.Canvas(modifier = Modifier.size(60.dp)) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val outerR = size.width / 2f - 3.dp.toPx()
                    val innerR = outerR - 4.dp.toPx()

                    drawCircle(
                        brush = Brush.linearGradient(
                            listOf(Color(0xFFCBD5E1), Color(0xFF64748B)),
                            start = androidx.compose.ui.geometry.Offset(cx - outerR, cy - outerR),
                            end   = androidx.compose.ui.geometry.Offset(cx + outerR, cy + outerR)
                        ),
                        radius = outerR,
                        center = androidx.compose.ui.geometry.Offset(cx, cy),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.dp.toPx())
                    )
                    drawCircle(
                        color = Color(0xFF94A3B8).copy(alpha = 0.3f),
                        radius = innerR,
                        center = androidx.compose.ui.geometry.Offset(cx, cy),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx())
                    )
                    val triSize = innerR * 0.55f
                    val triX = cx + triSize * 0.15f
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(triX - triSize * 0.5f, cy - triSize)
                        lineTo(triX + triSize * 0.9f, cy)
                        lineTo(triX - triSize * 0.5f, cy + triSize)
                        close()
                    }
                    drawPath(
                        path = path,
                        brush = Brush.linearGradient(
                            listOf(Color.White, Color(0xFFCBD5E1)),
                            start = androidx.compose.ui.geometry.Offset(triX - triSize * 0.5f, cy - triSize),
                            end   = androidx.compose.ui.geometry.Offset(triX + triSize * 0.9f, cy + triSize)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                val ctx = androidx.compose.ui.platform.LocalContext.current
                val versionName = remember {
                    try {
                        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "unknown"
                    } catch (e: Exception) {
                        "unknown"
                    }
                }

                Column(
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    modifier = Modifier.padding(bottom = 24.dp)
                ) {
                    Text(
                        text = "SVC PLAYER",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "(ver $versionName)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        color = Color(0xFF94A3B8)
                    )
                }

                // ── 주의사항 섹션 ─────────────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                        .padding(bottom = 16.dp)
                ) {
                    Text(
                        text = "[주의사항]",
                        fontSize = 10.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = Color(0xFFE2E8F0)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "- 본 앱은 사용자분들의 편의를 돕기 위해 제작되었습니다. 앱 사용 중 기기 환경에 따라 예기치 못한 오류가 발생할 수 있으며, 이로 인한 문제에 대해서는 개발자가 책임을 지기 어려운 점 양해 부탁드립니다.",
                        fontSize = 8.sp,
                        color = Color.White.copy(alpha = 0.5f),
                        lineHeight = 12.sp
                    )
                }

                // 로컬 디렉토리 Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(90.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(card1Bg, Color(0xFF3B2060)),
                                start = androidx.compose.ui.geometry.Offset(0f, 0f),
                                end   = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                            )
                        )
                        .border(
                            width = if (isTvMode && isLocalFocused) 2.5.dp else 1.dp,
                            brush = Brush.linearGradient(listOf(
                                if (isTvMode && isLocalFocused) Color.White else card1BorderStart,
                                card1BorderEnd
                            )),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                        )
                        .onFocusChanged { isLocalFocused = it.isFocused }
                        .focusable()
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionCenter) { onLocalClick(); true } else false
                        }
                        .clickable { onLocalClick() }
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(Color.White.copy(alpha = 0.15f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                                contentAlignment = androidx.compose.ui.Alignment.Center
                            ) {
                                Icon(Icons.Default.List, null, tint = Color(0xFFE2C6FF), modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Text("로컬 디렉토리", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text(">", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("내부 및 외부 저장소의 동영상을 찾아보세요.", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                    }
                }

                Spacer(Modifier.height(12.dp))

                // DLNA 검색 Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(90.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(card2Bg, Color(0xFF0C3B3B)),
                                start = androidx.compose.ui.geometry.Offset(0f, 0f),
                                end   = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                            )
                        )
                        .border(
                            width = if (isTvMode && isDlnaFocused) 2.5.dp else 1.dp,
                            brush = Brush.linearGradient(listOf(
                                if (isTvMode && isDlnaFocused) Color.White else card2BorderStart,
                                card2BorderEnd
                            )),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                        )
                        .onFocusChanged { isDlnaFocused = it.isFocused }
                        .focusable()
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionCenter) { onDlnaClick(); true } else false
                        }
                        .clickable { onDlnaClick() }
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(Color.White.copy(alpha = 0.15f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                                contentAlignment = androidx.compose.ui.Alignment.Center
                            ) {
                                Icon(Icons.Default.AccountBox, null, tint = Color(0xFF6EE7B7), modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Text("DLNA 검색", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text(">", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("홈 네트워크의 미디어 서버를 스캔합니다.", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                    }
                }

                Spacer(Modifier.height(12.dp))

                // SMB 검색 Card
                val card3BorderStart = Color(0xFFFF9946)
                val card3BorderEnd   = Color(0xFFE05A00)
                val card3Bg          = Color(0xFF5C3010)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(90.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(card3Bg, Color(0xFF3A1D08)),
                                start = androidx.compose.ui.geometry.Offset(0f, 0f),
                                end   = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                            )
                        )
                        .border(
                            width = if (isTvMode && isSmbFocused) 2.5.dp else 1.dp,
                            brush = Brush.linearGradient(listOf(
                                if (isTvMode && isSmbFocused) Color.White else card3BorderStart,
                                card3BorderEnd
                            )),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                        )
                        .onFocusChanged { isSmbFocused = it.isFocused }
                        .focusable()
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionCenter) { onSmbClick(); true } else false
                        }
                        .clickable { onSmbClick() }
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(Color.White.copy(alpha = 0.15f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                                contentAlignment = androidx.compose.ui.Alignment.Center
                            ) {
                                Icon(Icons.Default.Storage, null, tint = Color(0xFFFFCA8A), modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Text("SMB 접속", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text(">", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("네트워크 공유 폴더(삼바)에 접속합니다.", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                    }
                }

                Spacer(Modifier.height(12.dp))

                // WebDAV 접속 Card
                val card4BorderStart = Color(0xFF63B3ED)
                val card4BorderEnd   = Color(0xFF2B6CB0)
                val card4Bg          = Color(0xFF1A3B5C)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(90.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(card4Bg, Color(0xFF0E2340)),
                                start = androidx.compose.ui.geometry.Offset(0f, 0f),
                                end   = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                            )
                        )
                        .border(
                            width = if (isTvMode && isWebDavFocused) 2.5.dp else 1.dp,
                            brush = Brush.linearGradient(listOf(
                                if (isTvMode && isWebDavFocused) Color.White else card4BorderStart,
                                card4BorderEnd
                            )),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                        )
                        .onFocusChanged { isWebDavFocused = it.isFocused }
                        .focusable()
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionCenter) { onWebDavClick(); true } else false
                        }
                        .clickable { onWebDavClick() }
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(Color.White.copy(alpha = 0.15f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                                contentAlignment = androidx.compose.ui.Alignment.Center
                            ) {
                                Icon(Icons.Default.Cloud, null, tint = Color(0xFFBEE3F8), modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Text("WebDAV 접속", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text(">", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("WebDAV 서버(NAS, 클라우드)에 접속합니다.", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                    }
                }

                Spacer(Modifier.height(12.dp))

                // FTP / SFTP 접속 Card
                val card5BorderStart = Color(0xFF48BB78)
                val card5BorderEnd   = Color(0xFF2F855A)
                val card5Bg          = Color(0xFF1C4532)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(90.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(card5Bg, Color(0xFF0F2F21)),
                                start = androidx.compose.ui.geometry.Offset(0f, 0f),
                                end   = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                            )
                        )
                        .border(
                            width = if (isTvMode && isFtpSftpFocused) 2.5.dp else 1.dp,
                            brush = Brush.linearGradient(listOf(
                                if (isTvMode && isFtpSftpFocused) Color.White else card5BorderStart,
                                card5BorderEnd
                            )),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                        )
                        .onFocusChanged { isFtpSftpFocused = it.isFocused }
                        .focusable()
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionCenter) { onFtpSftpClick(); true } else false
                        }
                        .clickable { onFtpSftpClick() }
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(Color.White.copy(alpha = 0.15f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                                contentAlignment = androidx.compose.ui.Alignment.Center
                            ) {
                                Icon(Icons.Default.Upload, null, tint = Color(0xFFB8E994), modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Text("FTP / SFTP 접속", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text(">", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("FTP 또는 SFTP 서버에 접속하여 영상을 스트리밍합니다.", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                    }
                }
                
                Spacer(Modifier.height(12.dp))

                // 구글 드라이브 접속 Card
                val card6BorderStart = Color(0xFF4285F4)
                val card6BorderEnd   = Color(0xFF34A853)
                val card6Bg          = Color(0xFF0F224A)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(90.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(card6Bg, Color(0xFF08122B)),
                                start = androidx.compose.ui.geometry.Offset(0f, 0f),
                                end   = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                            )
                        )
                        .border(
                            width = if (isTvMode && isGoogleDriveFocused) 2.5.dp else 1.dp,
                            brush = Brush.linearGradient(listOf(
                                if (isTvMode && isGoogleDriveFocused) Color.White else card6BorderStart,
                                card6BorderEnd
                            )),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                        )
                        .onFocusChanged { isGoogleDriveFocused = it.isFocused }
                        .focusable()
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionCenter) { 
                                googleSignInLauncher.launch(GoogleDriveAuthManager.getSignInClient(context).signInIntent)
                                true 
                            } else false
                        }
                        .clickable { 
                            googleSignInLauncher.launch(GoogleDriveAuthManager.getSignInClient(context).signInIntent)
                        }
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(Color.White.copy(alpha = 0.15f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                                contentAlignment = androidx.compose.ui.Alignment.Center
                            ) {
                                Icon(Icons.Default.Cloud, null, tint = Color(0xFF90CAF9), modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Text("구글 드라이브 (Google Drive)", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text(">", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("구글 드라이브 계정에 로그인하여 영상을 스트리밍합니다.", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                    }
                }


                Spacer(Modifier.height(12.dp))

                // 원드라이브 접속 Card
                val card7BorderStart = Color(0xFF0078D4)
                val card7BorderEnd   = Color(0xFF00BCF2)
                val card7Bg          = Color(0xFF0A1E3D)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(90.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(card7Bg, Color(0xFF061428)),
                                start = androidx.compose.ui.geometry.Offset(0f, 0f),
                                end   = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                            )
                        )
                        .border(
                            width = if (isTvMode && isOneDriveFocused) 2.5.dp else 1.dp,
                            brush = Brush.linearGradient(listOf(
                                if (isTvMode && isOneDriveFocused) Color.White else card7BorderStart,
                                card7BorderEnd
                            )),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                        )
                        .onFocusChanged { isOneDriveFocused = it.isFocused }
                        .focusable()
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionCenter) { 
                                onOneDriveClick()
                                true 
                            } else false
                        }
                        .clickable { 
                            onOneDriveClick()
                        }
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(Color.White.copy(alpha = 0.15f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                                contentAlignment = androidx.compose.ui.Alignment.Center
                            ) {
                                Icon(Icons.Default.Cloud, null, tint = Color(0xFF00BCF2), modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Text("원드라이브 (OneDrive)", style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text(">", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("마이크로소프트 원드라이브 계정에 로그인하여 영상을 스트리밍합니다.", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                    }
                }

                Spacer(Modifier.height(32.dp))

                // ── 앱 접근 권한 안내 섹션 ──────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.12f),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                        )
                        .padding(horizontal = 20.dp, vertical = 20.dp)
                ) {
                    Column {
                        Text(
                            text = "【앱 사용 권한 안내】",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = Color(0xFFE2E8F0)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "사용자의 원활한 앱 이용을 위해 다음과 같은 권한을 요청합니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                        Spacer(Modifier.height(14.dp))
                        Divider(color = Color.White.copy(alpha = 0.1f))
                        Spacer(Modifier.height(14.dp))

                        // 권한 항목 1
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = androidx.compose.ui.Alignment.Top
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .size(6.dp)
                                    .background(Color(0xFFD280FF), androidx.compose.foundation.shape.CircleShape)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "저장소(사진 및 동영상) 권한 (선택)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                    color = Color(0xFFE2C6FF)
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "기기 내부에 저장된 로컬 영상 파일을 불러오고 재생하기 위해 필요합니다.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.65f)
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // 권한 항목 2
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = androidx.compose.ui.Alignment.Top
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .size(6.dp)
                                    .background(Color(0xFF34D399), androidx.compose.foundation.shape.CircleShape)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "네트워크 및 Wi-Fi 연결 (필수)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                    color = Color(0xFF6EE7B7)
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "DLNA, SMB, WebDAV 서버를 검색하고 원격으로 영상을 스트리밍하기 위해 필요합니다.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.65f)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                // 안내 및 라이선스 정책 버튼
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    var showCodecDialog by remember { mutableStateOf(false) }
                    
                    if (showCodecDialog) {
                        CodecInstallDialog(
                            onDismiss = {
                                showCodecDialog = false
                                // 안내창 닫힌 후 수동 복사했을 수도 있으니 다시 로드 시도
                                FfmpegLoader.initialize(context)
                            },
                            onZipClick = onZipClick,
                            resultMessage = codecInstallResultMessage
                        )
                    }

                    var isCodecFocused by remember { mutableStateOf(false) }
                    TextButton(
                        onClick = { showCodecDialog = true },
                        modifier = Modifier
                            .onFocusChanged { isCodecFocused = it.isFocused }
                            .focusable()
                            .onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionCenter) {
                                    showCodecDialog = true
                                    true
                                } else false
                            }
                            .background(
                                color = if (isCodecFocused) Color.White.copy(alpha = 0.2f) else Color.Transparent,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                            ),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = "[추가 코덱 설치 안내]",
                            color = if (isCodecFocused) Color.White else Color(0xFF94A3B8),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.width(2.dp))

                    var isSettingsFocused by remember { mutableStateOf(false) }
                    TextButton(
                        onClick = onSettingsClick,
                        modifier = Modifier
                            .onFocusChanged { isSettingsFocused = it.isFocused }
                            .focusable()
                            .onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionCenter) {
                                    onSettingsClick()
                                    true
                                } else false
                            }
                            .background(
                                color = if (isSettingsFocused) Color.White.copy(alpha = 0.2f) else Color.Transparent,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                            ),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = "[오디오 설정]",
                            color = if (isSettingsFocused) Color.White else Color(0xFF94A3B8),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.width(2.dp))

                    var isLicenseFocused by remember { mutableStateOf(false) }
                    TextButton(
                        onClick = onLicenseClick,
                        modifier = Modifier
                            .onFocusChanged { isLicenseFocused = it.isFocused }
                            .focusable()
                            .onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionCenter) {
                                    onLicenseClick()
                                    true
                                } else false
                            }
                            .background(
                                color = if (isLicenseFocused) Color.White.copy(alpha = 0.2f) else Color.Transparent,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                            ),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = "[오픈소스 라이선스 정책]",
                            color = if (isLicenseFocused) Color.White else Color(0xFF94A3B8),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun CodecInstallDialog(
    onDismiss: () -> Unit,
    onZipClick: () -> Unit = {},
    resultMessage: String? = null
) {
    val abi = FfmpegLoader.getRequiredAbi()
    val isLoaded = FfmpegLoader.isLoaded

    var isFocused by remember { mutableStateOf(false) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text(
                    text = "추가 코덱 설치 안내",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(20.dp))

                if (isLoaded) {
                    Text(
                        text = "추가 코덱이 설치가 되어 있습니다.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color(0xFF34D399),
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Text(
                        text = "추가 코덱이 설치가 되어 있지 않습니다. 아래 버튼 눌러서 코덱이 있는 Zip 파일을 선택 완료 하시면 설치 됩니다.\n(Zip 파일 설치후 앱 종료 후 다시 실행하세요)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.9f),
                        lineHeight = 22.sp
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // 기기 정보 안내 (설치에 도움을 주므로 유지)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.3f), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Column {
                            Text(text = "▶ 설치 권장 파일: libffmpegJNI.so", color = Color(0xFFD280FF), style = MaterialTheme.typography.bodySmall)
                            Text(text = "▶ 현재 기기 아키텍처: $abi", color = Color(0xFFD280FF), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                val resultMsg = resultMessage
                if (resultMsg != null) {
                    Text(
                        text = resultMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFACC15),
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = androidx.compose.ui.Alignment.End),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    var isZipFocused by remember { mutableStateOf(false) }
                    Button(
                        onClick = onZipClick,
                        modifier = Modifier
                            .onFocusChanged { isZipFocused = it.isFocused }
                            .focusable()
                            .onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionCenter) {
                                    onZipClick()
                                    true
                                } else false
                            }
                            .border(
                                width = if (isZipFocused) 2.dp else 0.dp,
                                color = if (isZipFocused) Color.White else Color.Transparent,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                            ),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF8B5CF6),
                            contentColor = Color.White
                        ),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Zip 파일로 코덱 설치",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .onFocusChanged { isFocused = it.isFocused }
                            .focusable()
                            .onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionCenter) {
                                    onDismiss()
                                    true
                                } else false
                            }
                            .border(
                                width = if (isFocused) 2.dp else 0.dp,
                                color = if (isFocused) Color.White else Color.Transparent,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                            ),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF475569)), // Grayish Blue
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text("닫기", color = Color.White, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun PassthroughSettingsScreen(
    isTvMode: Boolean,
    onBackClick: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var isPassthroughEnabled by remember { mutableStateOf(SettingsStore.getAudioPassthroughEnabled(context)) }
    
    // UI elements
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0C1122), Color(0xFF05070F))))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(if (isTvMode) 32.dp else 16.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                var isBackFocused by remember { mutableStateOf(false) }
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier
                        .onFocusChanged { isBackFocused = it.isFocused }
                        .focusable()
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionCenter) {
                                onBackClick()
                                true
                            } else false
                        }
                        .background(
                            color = if (isBackFocused) Color.White.copy(alpha = 0.2f) else Color.Transparent,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "뒤로가기",
                        tint = if (isBackFocused) Color.White else Color(0xFF94A3B8)
                    )
                }
                Text(
                    text = "오디오 패스스루 설정",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp)
                )
            }

            // Setting Item
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (isTvMode) 32.dp else 16.dp)
                    .padding(top = 16.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.1f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    )
            ) {
                var isRowFocused by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { isRowFocused = it.isFocused }
                        .focusable()
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionCenter) {
                                val newVal = !isPassthroughEnabled
                                isPassthroughEnabled = newVal
                                SettingsStore.saveAudioPassthroughEnabled(context, newVal)
                                true
                            } else false
                        }
                        .clickable {
                            val newVal = !isPassthroughEnabled
                            isPassthroughEnabled = newVal
                            SettingsStore.saveAudioPassthroughEnabled(context, newVal)
                        }
                        .background(if (isRowFocused) Color.White.copy(alpha = 0.15f) else Color.Transparent)
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "하드웨어 오디오 패스스루 사용",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "기본값은 OFF입니다. 설정 시 비디오 오디오(DTS/AC3 등)를 디코딩하지 않고 외부 하드웨어(사운드바, AV 리시버 등)로 원본 형태로 전송(패스스루)합니다. 호환되지 않는 기기인 경우 오디오가 재생되지 않을 수 있습니다.",
                            color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = isPassthroughEnabled,
                        onCheckedChange = { newVal ->
                            isPassthroughEnabled = newVal
                            SettingsStore.saveAudioPassthroughEnabled(context, newVal)
                        },
                        modifier = Modifier.padding(start = 16.dp),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF8B5CF6),
                            uncheckedThumbColor = Color(0xFF94A3B8),
                            uncheckedTrackColor = Color(0xFF334155)
                        )
                    )
                }
            }
        }
    }
}
