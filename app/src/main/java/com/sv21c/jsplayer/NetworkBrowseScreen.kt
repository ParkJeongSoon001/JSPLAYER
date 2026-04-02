package com.sv21c.jsplayer

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SMB / WebDAV 파일 탐색 화면
 * - 폴더: 클릭 시 하위 진입
 * - 동영상: 클릭 시 재생 화면으로 이동
 * - 오류 시: 경로 직접 변경 입력란 제공
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkBrowseScreen(
    credentials: ServerCredentials,
    currentPath: String,
    currentPathName: String,
    isTvMode: Boolean = false,
    onBackClick: () -> Unit,
    onFolderClick: (path: String, name: String) -> Unit,
    onVideoClick: (url: String, title: String, subUrl: String?, subExt: String?, playlist: List<PlaylistItem>, currentIndex: Int) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var items by remember(currentPath) { mutableStateOf<List<Any>>(emptyList()) }
    var isLoading by remember(currentPath) { mutableStateOf(true) }
    var errorMessage by remember(currentPath) { mutableStateOf<String?>(null) }
    var customPath by remember { mutableStateOf(currentPath) }
    var showPathEdit by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val accentColor = if (credentials.type == "SMB") Color(0xFFFF9946) else Color(0xFF63B3ED)

    fun loadPath(path: String) {
        isLoading = true
        errorMessage = null
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val result = if (credentials.type == "SMB") {
                    SmbManager.listFiles(path, credentials.username, credentials.password)
                        .map { list -> list as List<Any> }
                } else {
                    WebDavManager.listFiles(path, credentials.username, credentials.password)
                        .map { list -> list as List<Any> }
                }
                withContext(Dispatchers.Main) {
                    result.fold(
                        onSuccess = { list ->
                            items = list
                            isLoading = false
                        },
                        onFailure = { e ->
                            errorMessage = e.message ?: "목록을 불러오지 못했습니다."
                            isLoading = false
                            Log.e("NetworkBrowseScreen", "Load failed: ${e.message}")
                        }
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorMessage = e.message ?: "알 수 없는 오류"
                    isLoading = false
                }
            }
        }
    }

    LaunchedEffect(currentPath) {
        customPath = currentPath
        loadPath(currentPath)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = {
                Text(
                    text = currentPathName,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "뒤로",
                        tint = MaterialTheme.colorScheme.onBackground)
                }
            },
            actions = {
                IconButton(onClick = { loadPath(customPath) }) {
                    Icon(Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.onBackground)
                }
                androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                ExitAppButton()
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )

        when {
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = primaryColor)
                    Spacer(Modifier.height(12.dp))
                    Text("불러오는 중...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (credentials.type == "WEBDAV") "여러 WebDAV 경로를 순서대로 확인 중..." else customPath,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.5f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                }
            }
            errorMessage != null -> {
                // 오류 화면 + 경로 직접 입력
                Box(
                    Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            null,
                            tint = accentColor,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            "서버 접속 실패",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = errorMessage ?: "",
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall
                        )

                        Spacer(Modifier.height(8.dp))

                        // WebDAV 경로 직접 입력
                        if (credentials.type == "WEBDAV") {
                            Text(
                                "WebDAV 경로를 직접 입력하세요:",
                                color = Color.White.copy(0.7f),
                                style = MaterialTheme.typography.bodySmall
                            )
                            OutlinedTextField(
                                value = customPath,
                                onValueChange = { customPath = it },
                                placeholder = { Text("http://192.168.1.1:5005/webdav") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = accentColor,
                                    unfocusedBorderColor = Color.White.copy(0.3f),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    cursorColor = accentColor
                                )
                            )
                            // 일반적인 Synology 포트 힌트
                            Text(
                                "Synology NAS WebDAV 기본 포트:\nHTTP: 5005  |  HTTPS: 5006",
                                color = Color.White.copy(0.4f),
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center
                            )
                        }

                        Button(
                            onClick = { loadPath(customPath) },
                            colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("다시 시도", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        if (credentials.type == "SMB") Icons.Default.Storage else Icons.Default.Cloud,
                        null,
                        tint = accentColor.copy(0.4f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("이 폴더는 비어 있습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(items) { _, item ->
                    val (name, path, isDir, isVideo) = when (item) {
                        is SmbItem -> Quadruple(item.name, item.path, item.isDirectory,
                            SmbManager.isVideoFile(item.name) && !item.isDirectory)
                        is WebDavItem -> Quadruple(item.name, item.href, item.isDirectory,
                            WebDavManager.isVideoFile(item.name) && !item.isDirectory)
                        else -> return@itemsIndexed
                    }

                    val iconVector = if (isDir) Icons.Default.Folder else Icons.Default.PlayArrow
                    val iconTint = if (isDir) secondaryColor else primaryColor

                    val focusRequester = remember { FocusRequester() }
                    var isFocused by remember { mutableStateOf(false) }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged { isFocused = it.isFocused }
                            .focusable()
                            .onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionCenter) {
                                    if (isDir) onFolderClick(path, name)
                                    else if (isVideo) {
                                        val playUrl = if (credentials.type == "WEBDAV")
                                            WebDavManager.buildAuthUrl(path, credentials.username, credentials.password)
                                        else if (credentials.type == "SMB" && credentials.username.isNotBlank()) {
                                            val authStr = "${android.net.Uri.encode(credentials.username)}:${android.net.Uri.encode(credentials.password)}@"
                                            path.replaceFirst("smb://", "smb://$authStr")
                                        } else path
                                        
                                        val nameWithoutExt = name.substringBeforeLast(".")
                                        val subItem = items.find { itItem ->
                                            val (itName, _, itIsDir) = when (itItem) {
                                                is SmbItem -> Triple(itItem.name, itItem.path, itItem.isDirectory)
                                                is WebDavItem -> Triple(itItem.name, itItem.href, itItem.isDirectory)
                                                else -> Triple("", "", true)
                                            }
                                            if (itIsDir || itItem == item) false
                                            else {
                                                val ext = itName.substringAfterLast(".", "").lowercase()
                                                (ext == "smi" || ext == "srt" || ext == "ass" || ext == "vtt") &&
                                                itName.substringBeforeLast(".") == nameWithoutExt
                                            }
                                        }

                                        val (subUrl, subExt) = if (subItem != null) {
                                            val (sName, sPath, _) = when (subItem) {
                                                is SmbItem -> Triple(subItem.name, subItem.path, subItem.isDirectory)
                                                is WebDavItem -> Triple(subItem.name, subItem.href, subItem.isDirectory)
                                                else -> Triple("", "", true)
                                            }
                                            val authSubUrl = if (credentials.type == "WEBDAV")
                                                WebDavManager.buildAuthUrl(sPath, credentials.username, credentials.password)
                                            else if (credentials.type == "SMB" && credentials.username.isNotBlank()) {
                                                val authStr = "${android.net.Uri.encode(credentials.username)}:${android.net.Uri.encode(credentials.password)}@"
                                                sPath.replaceFirst("smb://", "smb://$authStr")
                                            } else sPath
                                            authSubUrl to sName.substringAfterLast(".").lowercase()
                                        } else null to null

                                        // 플레이리스트 구성
                                        val videoItemsList = items.filter { itm ->
                                            val (itName, _, itIsDir, itIsVideo) = when (itm) {
                                                is SmbItem -> Quadruple(itm.name, itm.path, itm.isDirectory, SmbManager.isVideoFile(itm.name) && !itm.isDirectory)
                                                is WebDavItem -> Quadruple(itm.name, itm.href, itm.isDirectory, WebDavManager.isVideoFile(itm.name) && !itm.isDirectory)
                                                else -> Quadruple("", "", true, false)
                                            }
                                            itIsVideo
                                        }
                                        val playlist = videoItemsList.map { itm ->
                                            val (itName, itPath, _, _) = when (itm) {
                                                is SmbItem -> Quadruple(itm.name, itm.path, itm.isDirectory, false)
                                                is WebDavItem -> Quadruple(itm.name, itm.href, itm.isDirectory, false)
                                                else -> Quadruple("", "", true, false)
                                            }
                                            val itPlayUrl = if (credentials.type == "WEBDAV")
                                                WebDavManager.buildAuthUrl(itPath, credentials.username, credentials.password)
                                            else if (credentials.type == "SMB" && credentials.username.isNotBlank()) {
                                                val auth = "${android.net.Uri.encode(credentials.username)}:${android.net.Uri.encode(credentials.password)}@"
                                                itPath.replaceFirst("smb://", "smb://$auth")
                                            } else itPath

                                            // 각 플레이리스트 아이템에 대해 자막 파일 검색
                                            val iNameWithoutExt = itName.substringBeforeLast(".")
                                            val iSubItem = items.find { candItem ->
                                                val candName = if(candItem is SmbItem) candItem.name else if(candItem is WebDavItem) candItem.name else ""
                                                val candIsDir = if(candItem is SmbItem) candItem.isDirectory else if(candItem is WebDavItem) candItem.isDirectory else true
                                                if (candIsDir || candItem == itm) false else {
                                                    val ext = candName.substringAfterLast(".", "").lowercase()
                                                    val candBase = candName.substringBeforeLast(".")
                                                    (ext == "smi" || ext == "srt" || ext == "ass" || ext == "vtt") &&
                                                    (candBase == iNameWithoutExt || (candBase.startsWith(iNameWithoutExt) && candBase.getOrNull(iNameWithoutExt.length) == '.'))
                                                }
                                            }
                                            val (iSubUrl, iSubExt) = if (iSubItem != null) {
                                                val sPath = if(iSubItem is SmbItem) iSubItem.path else if (iSubItem is WebDavItem) iSubItem.href else ""
                                                val sName = if(iSubItem is SmbItem) iSubItem.name else if (iSubItem is WebDavItem) iSubItem.name else ""
                                                val authSubUrl = if (credentials.type == "WEBDAV") WebDavManager.buildAuthUrl(sPath, credentials.username, credentials.password)
                                                else if (credentials.type == "SMB" && credentials.username.isNotBlank()) sPath.replaceFirst("smb://", "smb://${android.net.Uri.encode(credentials.username)}:${android.net.Uri.encode(credentials.password)}@")
                                                else sPath
                                                authSubUrl to sName.substringAfterLast(".", "").lowercase()
                                            } else null to null

                                            PlaylistItem(videoUrl = itPlayUrl, title = itName, subtitleUrl = iSubUrl, subtitleExtension = iSubExt)
                                        }
                                        val currentIdx = playlist.indexOfFirst { it.videoUrl == playUrl }

                                        onVideoClick(playUrl, name, subUrl, subExt, playlist, currentIdx)
                                    }
                                    true
                                } else false
                            }
                            .clickable {
                                if (isDir) onFolderClick(path, name)
                                else if (isVideo) {
                                    val playUrl = if (credentials.type == "WEBDAV")
                                        WebDavManager.buildAuthUrl(path, credentials.username, credentials.password)
                                    else if (credentials.type == "SMB" && credentials.username.isNotBlank()) {
                                        val authStr = "${android.net.Uri.encode(credentials.username)}:${android.net.Uri.encode(credentials.password)}@"
                                        path.replaceFirst("smb://", "smb://$authStr")
                                    } else path
                                    
                                    val nameWithoutExt = name.substringBeforeLast(".")
                                    val subItem = items.find { itItem ->
                                        val (itName, _, itIsDir) = when (itItem) {
                                            is SmbItem -> Triple(itItem.name, itItem.path, itItem.isDirectory)
                                            is WebDavItem -> Triple(itItem.name, itItem.href, itItem.isDirectory)
                                            else -> Triple("", "", true)
                                        }
                                        if (itIsDir || itItem == item) false
                                        else {
                                            val ext = itName.substringAfterLast(".", "").lowercase()
                                            val itBase = itName.substringBeforeLast(".")
                                            (ext == "smi" || ext == "srt" || ext == "ass" || ext == "vtt") &&
                                            (itBase == nameWithoutExt || (itBase.startsWith(nameWithoutExt) && itBase.getOrNull(nameWithoutExt.length) == '.'))
                                        }
                                    }

                                    val (subUrl, subExt) = if (subItem != null) {
                                        val (sName, sPath, _) = when (subItem) {
                                            is SmbItem -> Triple(subItem.name, subItem.path, subItem.isDirectory)
                                            is WebDavItem -> Triple(subItem.name, subItem.href, subItem.isDirectory)
                                            else -> Triple("", "", true)
                                        }
                                        val authSubUrl = if (credentials.type == "WEBDAV")
                                            WebDavManager.buildAuthUrl(sPath, credentials.username, credentials.password)
                                        else if (credentials.type == "SMB" && credentials.username.isNotBlank()) {
                                            val authStr = "${android.net.Uri.encode(credentials.username)}:${android.net.Uri.encode(credentials.password)}@"
                                            sPath.replaceFirst("smb://", "smb://$authStr")
                                        } else sPath
                                        authSubUrl to sName.substringAfterLast(".").lowercase()
                                    } else null to null

                                    // 플레이리스트 구성
                                    val videoItemsList2 = items.filter { itm ->
                                        val (_, _, _, itIsVideo) = when (itm) {
                                            is SmbItem -> Quadruple(itm.name, itm.path, itm.isDirectory, SmbManager.isVideoFile(itm.name) && !itm.isDirectory)
                                            is WebDavItem -> Quadruple(itm.name, itm.href, itm.isDirectory, WebDavManager.isVideoFile(itm.name) && !itm.isDirectory)
                                            else -> Quadruple("", "", true, false)
                                        }
                                        itIsVideo
                                    }
                                    val playlist2 = videoItemsList2.map { itm ->
                                        val (itName, itPath, _, _) = when (itm) {
                                            is SmbItem -> Quadruple(itm.name, itm.path, itm.isDirectory, false)
                                            is WebDavItem -> Quadruple(itm.name, itm.href, itm.isDirectory, false)
                                            else -> Quadruple("", "", true, false)
                                        }
                                        val itPlayUrl = if (credentials.type == "WEBDAV")
                                            WebDavManager.buildAuthUrl(itPath, credentials.username, credentials.password)
                                        else if (credentials.type == "SMB" && credentials.username.isNotBlank()) {
                                            val auth = "${android.net.Uri.encode(credentials.username)}:${android.net.Uri.encode(credentials.password)}@"
                                            itPath.replaceFirst("smb://", "smb://$auth")
                                        } else itPath
                                        
                                        val iNameWithoutExt = itName.substringBeforeLast(".")
                                        val iSubItem = items.find { candItem ->
                                            val candName = if(candItem is SmbItem) candItem.name else if(candItem is WebDavItem) candItem.name else ""
                                            val candIsDir = if(candItem is SmbItem) candItem.isDirectory else if(candItem is WebDavItem) candItem.isDirectory else true
                                            if (candIsDir || candItem == itm) false else {
                                                val ext = candName.substringAfterLast(".", "").lowercase()
                                                val candBase = candName.substringBeforeLast(".")
                                                (ext == "smi" || ext == "srt" || ext == "ass" || ext == "vtt") &&
                                                (candBase == iNameWithoutExt || (candBase.startsWith(iNameWithoutExt) && candBase.getOrNull(iNameWithoutExt.length) == '.'))
                                            }
                                        }
                                        val (iSubUrl, iSubExt) = if (iSubItem != null) {
                                            val sPath = if(iSubItem is SmbItem) iSubItem.path else if (iSubItem is WebDavItem) iSubItem.href else ""
                                            val sName = if(iSubItem is SmbItem) iSubItem.name else if (iSubItem is WebDavItem) iSubItem.name else ""
                                            val authSubUrl = if (credentials.type == "WEBDAV") WebDavManager.buildAuthUrl(sPath, credentials.username, credentials.password)
                                            else if (credentials.type == "SMB" && credentials.username.isNotBlank()) sPath.replaceFirst("smb://", "smb://${android.net.Uri.encode(credentials.username)}:${android.net.Uri.encode(credentials.password)}@")
                                            else sPath
                                            authSubUrl to sName.substringAfterLast(".", "").lowercase()
                                        } else null to null
                                        
                                        PlaylistItem(videoUrl = itPlayUrl, title = itName, subtitleUrl = iSubUrl, subtitleExtension = iSubExt)
                                    }
                                    val currentIdx2 = playlist2.indexOfFirst { it.videoUrl == playUrl }

                                    onVideoClick(playUrl, name, subUrl, subExt, playlist2, currentIdx2)
                                }
                            }
                            .border(
                                width = if (isTvMode && isFocused) 3.dp else 0.dp,
                                color = if (isTvMode && isFocused) primaryColor else Color.Transparent,
                                shape = RoundedCornerShape(12.dp)
                            ),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(iconTint.copy(alpha = 0.15f), shape = CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(iconVector, null, tint = iconTint, modifier = Modifier.size(22.dp))
                            }
                            Spacer(Modifier.width(14.dp))
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (!isDir && !isVideo)
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            // 저장된 재생 위치 표시 (시간 + 프로그레스 바)
                            if (isVideo) {
                                val context = androidx.compose.ui.platform.LocalContext.current
                                val playUrlForSave = if (credentials.type == "WEBDAV")
                                    WebDavManager.buildAuthUrl(path, credentials.username, credentials.password)
                                else if (credentials.type == "SMB" && credentials.username.isNotBlank()) {
                                    val authStr = "${android.net.Uri.encode(credentials.username)}:${android.net.Uri.encode(credentials.password)}@"
                                    path.replaceFirst("smb://", "smb://$authStr")
                                } else path
                                val savedPos = PlaybackPositionStore.getPosition(context, playUrlForSave)
                                if (savedPos > 0L) {
                                    val savedDur = PlaybackPositionStore.getDuration(context, playUrlForSave)
                                    Column(
                                        horizontalAlignment = androidx.compose.ui.Alignment.End,
                                        modifier = Modifier.padding(start = 12.dp).width(72.dp)
                                    ) {
                                        Text(
                                            text = "▶ " + PlaybackPositionStore.formatPosition(savedPos),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                        if (savedDur > 0L) {
                                            Spacer(Modifier.height(4.dp))
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(6.dp)
                                                    .background(
                                                        Color.White.copy(alpha = 0.2f),
                                                        shape = RoundedCornerShape(3.dp)
                                                    )
                                            ) {
                                                val progress = (savedPos.toFloat() / savedDur.toFloat()).coerceIn(0f, 1f)
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth(progress)
                                                        .fillMaxHeight()
                                                        .background(
                                                            MaterialTheme.colorScheme.primary,
                                                            shape = RoundedCornerShape(3.dp)
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

// 4개 요소를 가진 destructuring 헬퍼
private data class Quadruple<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
