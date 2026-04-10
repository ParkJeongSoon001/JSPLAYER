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
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkBrowseScreen(
    credentials: ServerCredentials,
    currentPath: String,
    currentPathName: String,
    isTvMode: Boolean = false,
    sortOrder: SortOrder,
    onSortOrderChange: (SortOrder) -> Unit,
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
    val context = LocalContext.current
    var showSortMenu by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val accentColor = when (credentials.type) {
        "SMB" -> Color(0xFFFF9946)
        "WEBDAV" -> Color(0xFF63B3ED)
        "FTP" -> Color(0xFF48BB78)   // 초록
        "SFTP" -> Color(0xFFF6AD55)  // 주황
        else -> Color(0xFF63B3ED)
    }

    fun loadPath(path: String) {
        isLoading = true
        errorMessage = null
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val result = when (credentials.type) {
                    "SMB" -> SmbManager.listFiles(path, credentials.username, credentials.password)
                        .map { list -> list as List<Any> }
                    "WEBDAV" -> WebDavManager.listFiles(path, credentials.username, credentials.password)
                        .map { list -> list as List<Any> }
                    "FTP" -> {
                        val uri = try { java.net.URI(credentials.host) } catch (_: Exception) { null }
                        val ftpHost = uri?.host ?: credentials.host.removePrefix("ftp://").split("/")[0].split(":")[0]
                        val ftpPort = uri?.port?.takeIf { it > 0 } ?: 21
                        FtpManager.listFiles(ftpHost, ftpPort, credentials.username, credentials.password, path)
                            .map { list -> list as List<Any> }
                    }
                    "SFTP" -> {
                        val uri = try { java.net.URI(credentials.host) } catch (_: Exception) { null }
                        val sftpHost = uri?.host ?: credentials.host.removePrefix("sftp://").split("/")[0].split(":")[0]
                        val sftpPort = uri?.port?.takeIf { it > 0 } ?: 22
                        SftpManager.listFiles(sftpHost, sftpPort, credentials.username, credentials.password, path)
                            .map { list -> list as List<Any> }
                    }
                    else -> Result.failure(Exception("지원하지 않는 프로토콜: ${credentials.type}"))
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
        searchQuery = ""
        isSearchActive = false
        loadPath(currentPath)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
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
                            text = currentPathName,
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
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
                    Icon(Icons.Default.ArrowBack, contentDescription = "뒤로",
                        tint = MaterialTheme.colorScheme.onBackground)
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
                Box {
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(Icons.Default.Sort, contentDescription = "정렬", tint = MaterialTheme.colorScheme.onBackground)
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false }
                    ) {
                        SortOrder.values().forEach { order ->
                            DropdownMenuItem(
                                text = { Text(order.displayName) },
                                onClick = {
                                    onSortOrderChange(order)
                                    SettingsStore.saveSortOrder(context, order)
                                    showSortMenu = false
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
                }
            }
            errorMessage != null -> {
                Box(
                    Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.Warning, null, tint = accentColor, modifier = Modifier.size(48.dp))
                        Text(
                            text = errorMessage ?: "",
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(
                            onClick = { loadPath(customPath) },
                            colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                        ) {
                            Text("다시 시도", color = Color.Black)
                        }
                    }
                }
            }
            items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("이 폴더는 비어 있습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                val sortedItems = remember(items, sortOrder, searchQuery) {
                    val filtered = if (searchQuery.isNotEmpty()) {
                        items.filter { 
                            val name = when(it) { is SmbItem -> it.name; is WebDavItem -> it.name; is FtpItem -> it.name; is SftpItem -> it.name; else -> "" }
                            name.contains(searchQuery, ignoreCase = true)
                        }
                    } else {
                        items
                    }
                    filtered.sortedWith { o1, o2 ->
                        val isDir1 = when(o1) { is SmbItem -> o1.isDirectory; is WebDavItem -> o1.isDirectory; is FtpItem -> o1.isDirectory; is SftpItem -> o1.isDirectory; else -> true }
                        val isDir2 = when(o2) { is SmbItem -> o2.isDirectory; is WebDavItem -> o2.isDirectory; is FtpItem -> o2.isDirectory; is SftpItem -> o2.isDirectory; else -> true }

                        if (isDir1 && !isDir2) -1
                        else if (!isDir1 && isDir2) 1
                        else {
                            val name1 = when(o1) { is SmbItem -> o1.name; is WebDavItem -> o1.name; is FtpItem -> o1.name; is SftpItem -> o1.name; else -> "" }
                            val name2 = when(o2) { is SmbItem -> o2.name; is WebDavItem -> o2.name; is FtpItem -> o2.name; is SftpItem -> o2.name; else -> "" }
                            val time1 = when(o1) { is SmbItem -> o1.lastModified; is WebDavItem -> o1.lastModified; is FtpItem -> o1.lastModified; is SftpItem -> o1.lastModified; else -> 0L }
                            val time2 = when(o2) { is SmbItem -> o2.lastModified; is WebDavItem -> o2.lastModified; is FtpItem -> o2.lastModified; is SftpItem -> o2.lastModified; else -> 0L }

                            when (sortOrder) {
                                SortOrder.NAME_ASC -> name1.compareTo(name2, ignoreCase = true)
                                SortOrder.NAME_DESC -> name2.compareTo(name1, ignoreCase = true)
                                SortOrder.DATE_DESC -> time2.compareTo(time1)
                                SortOrder.DATE_ASC -> time1.compareTo(time2)
                            }
                        }
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(sortedItems) { _, item ->
                        val (name, path, isDir, isVideo) = when (item) {
                            is SmbItem -> Quadruple(item.name, item.path, item.isDirectory,
                                SmbManager.isVideoFile(item.name) && !item.isDirectory)
                            is WebDavItem -> Quadruple(item.name, item.href, item.isDirectory,
                                WebDavManager.isVideoFile(item.name) && !item.isDirectory)
                            is FtpItem -> Quadruple(item.name, item.path, item.isDirectory,
                                FtpManager.isVideoFile(item.name) && !item.isDirectory)
                            is SftpItem -> Quadruple(item.name, item.path, item.isDirectory,
                                SftpManager.isVideoFile(item.name) && !item.isDirectory)
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
                                            handleVideoClick(
                                                item, credentials, sortedItems, playUrl = "", name, onVideoClick
                                            )
                                        }
                                        true
                                    } else false
                                }
                                .clickable {
                                    if (isDir) onFolderClick(path, name)
                                    else if (isVideo) {
                                            handleVideoClick(
                                                item, credentials, sortedItems, playUrl = "", name, onVideoClick
                                            )
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
                                    val playUrlForSave = when (credentials.type) {
                                        "WEBDAV" -> WebDavManager.buildAuthUrl(path, credentials.username, credentials.password)
                                        "SMB" -> if (credentials.username.isNotBlank()) {
                                            val authStr = "${android.net.Uri.encode(credentials.username)}:${android.net.Uri.encode(credentials.password)}@"
                                            path.replaceFirst("smb://", "smb://$authStr")
                                        } else path
                                        else -> path
                                    }
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
}

private fun handleVideoClick(
    item: Any,
    credentials: ServerCredentials,
    sortedItems: List<Any>,
    playUrl: String,
    name: String,
    onVideoClick: (url: String, title: String, subUrl: String?, subExt: String?, playlist: List<PlaylistItem>, currentIndex: Int) -> Unit
) {
    val path = when (item) {
        is SmbItem -> item.path
        is WebDavItem -> item.href
        is FtpItem -> item.path
        is SftpItem -> item.path
        else -> ""
    }
    val targetPlayUrl = playUrl.ifBlank {
        when (credentials.type) {
            "WEBDAV" -> WebDavManager.buildAuthUrl(path, credentials.username, credentials.password)
            "SMB" -> if (credentials.username.isNotBlank()) {
                val authStr = "${android.net.Uri.encode(credentials.username)}:${android.net.Uri.encode(credentials.password)}@"
                path.replaceFirst("smb://", "smb://$authStr")
            } else path
            "FTP" -> {
                val uri = try { java.net.URI(credentials.host) } catch (_: Exception) { null }
                val ftpHost = uri?.host ?: credentials.host.removePrefix("ftp://").split("/")[0].split(":")[0]
                val ftpPort = uri?.port?.takeIf { it > 0 } ?: 21
                val portStr = if (ftpPort != 21) ":$ftpPort" else ""
                val normPath = if (path.startsWith("./")) path.substring(1) else (if (path.startsWith("/")) path else "/$path")
                val encodedPath = normPath.split("/").joinToString("/") { android.net.Uri.encode(it) }
                "ftp://${android.net.Uri.encode(credentials.username)}:${android.net.Uri.encode(credentials.password)}@$ftpHost$portStr$encodedPath"
            }
            "SFTP" -> {
                val uri = try { java.net.URI(credentials.host) } catch (_: Exception) { null }
                val sftpHost = uri?.host ?: credentials.host.removePrefix("sftp://").split("/")[0].split(":")[0]
                val sftpPort = uri?.port?.takeIf { it > 0 } ?: 22
                val portStr = if (sftpPort != 22) ":$sftpPort" else ""
                val normPath = if (path.startsWith("./")) path.substring(1) else (if (path.startsWith("/")) path else "/$path")
                val encodedPath = normPath.split("/").joinToString("/") { android.net.Uri.encode(it) }
                "sftp://${android.net.Uri.encode(credentials.username)}:${android.net.Uri.encode(credentials.password)}@$sftpHost$portStr$encodedPath"
            }
            else -> path
        }
    }
    
    val nameWithoutExt = name.substringBeforeLast(".")
    val subItem = sortedItems.find { itItem ->
        val (itName, _, itIsDir) = when (itItem) {
            is SmbItem -> Triple(itItem.name, itItem.path, itItem.isDirectory)
            is WebDavItem -> Triple(itItem.name, itItem.href, itItem.isDirectory)
            is FtpItem -> Triple(itItem.name, itItem.path, itItem.isDirectory)
            is SftpItem -> Triple(itItem.name, itItem.path, itItem.isDirectory)
            else -> Triple("", "", true)
        }
        if (itIsDir || itItem == item) false
        else {
            val ext = itName.substringAfterLast(".", "").lowercase()
            val itBase = itName.substringBeforeLast(".")
            (ext == "smi" || ext == "srt" || ext == "ass" || ext == "vtt" || ext == "ssa" || ext == "sub" || ext == "txt") &&
            (itBase.equals(nameWithoutExt, ignoreCase = true) || (itBase.startsWith(nameWithoutExt, ignoreCase = true) && itBase.getOrNull(nameWithoutExt.length) == '.'))
        }
    }

    val (subUrl, subExt) = if (subItem != null) {
        val (sName, sPath, _) = when (subItem) {
            is SmbItem -> Triple(subItem.name, subItem.path, subItem.isDirectory)
            is WebDavItem -> Triple(subItem.name, subItem.href, subItem.isDirectory)
            is FtpItem -> Triple(subItem.name, subItem.path, subItem.isDirectory)
            is SftpItem -> Triple(subItem.name, subItem.path, subItem.isDirectory)
            else -> Triple("", "", true)
        }
        val authSubUrl = when (credentials.type) {
            "WEBDAV" -> WebDavManager.buildAuthUrl(sPath, credentials.username, credentials.password)
            "SMB" -> if (credentials.username.isNotBlank()) {
                val authStr = "${android.net.Uri.encode(credentials.username)}:${android.net.Uri.encode(credentials.password)}@"
                sPath.replaceFirst("smb://", "smb://$authStr")
            } else sPath
            "FTP" -> {
                val uri = try { java.net.URI(credentials.host) } catch (_: Exception) { null }
                val ftpHost = uri?.host ?: credentials.host.removePrefix("ftp://").split("/")[0].split(":")[0]
                val ftpPort = uri?.port?.takeIf { it > 0 } ?: 21
                val portStr = if (ftpPort != 21) ":$ftpPort" else ""
                val normSPath = if (sPath.startsWith("./")) sPath.substring(1) else (if (sPath.startsWith("/")) sPath else "/$sPath")
                val encodedSPath = normSPath.split("/").joinToString("/") { android.net.Uri.encode(it) }
                "ftp://${android.net.Uri.encode(credentials.username)}:${android.net.Uri.encode(credentials.password)}@$ftpHost$portStr$encodedSPath"
            }
            "SFTP" -> {
                val uri = try { java.net.URI(credentials.host) } catch (_: Exception) { null }
                val sftpHost = uri?.host ?: credentials.host.removePrefix("sftp://").split("/")[0].split(":")[0]
                val sftpPort = uri?.port?.takeIf { it > 0 } ?: 22
                val portStr = if (sftpPort != 22) ":$sftpPort" else ""
                val normSPath = if (sPath.startsWith("./")) sPath.substring(1) else (if (sPath.startsWith("/")) sPath else "/$sPath")
                val encodedSPath = normSPath.split("/").joinToString("/") { android.net.Uri.encode(it) }
                "sftp://${android.net.Uri.encode(credentials.username)}:${android.net.Uri.encode(credentials.password)}@$sftpHost$portStr$encodedSPath"
            }
            else -> sPath
        }
        authSubUrl to sName.substringAfterLast(".").lowercase()
    } else null to null

    // 플레이리스트 구성
    val videoItemsList = sortedItems.filter { itm ->
        val (_, _, _, itIsVideo) = when (itm) {
            is SmbItem -> Quadruple(itm.name, itm.path, itm.isDirectory, SmbManager.isVideoFile(itm.name) && !itm.isDirectory)
            is WebDavItem -> Quadruple(itm.name, itm.href, itm.isDirectory, WebDavManager.isVideoFile(itm.name) && !itm.isDirectory)
            is FtpItem -> Quadruple(itm.name, itm.path, itm.isDirectory, FtpManager.isVideoFile(itm.name) && !itm.isDirectory)
            is SftpItem -> Quadruple(itm.name, itm.path, itm.isDirectory, SftpManager.isVideoFile(itm.name) && !itm.isDirectory)
            else -> Quadruple("", "", true, false)
        }
        itIsVideo
    }
    val playlist = videoItemsList.map { itm ->
        val (itName, itPath, _, _) = when (itm) {
            is SmbItem -> Quadruple(itm.name, itm.path, itm.isDirectory, false)
            is WebDavItem -> Quadruple(itm.name, itm.href, itm.isDirectory, false)
            is FtpItem -> Quadruple(itm.name, itm.path, itm.isDirectory, false)
            is SftpItem -> Quadruple(itm.name, itm.path, itm.isDirectory, false)
            else -> Quadruple("", "", true, false)
        }
        val itPlayUrl = when (credentials.type) {
            "WEBDAV" -> WebDavManager.buildAuthUrl(itPath, credentials.username, credentials.password)
            "SMB" -> if (credentials.username.isNotBlank()) {
                val auth = "${android.net.Uri.encode(credentials.username)}:${android.net.Uri.encode(credentials.password)}@"
                itPath.replaceFirst("smb://", "smb://$auth")
            } else itPath
            "FTP" -> {
                val uri = try { java.net.URI(credentials.host) } catch (_: Exception) { null }
                val ftpHost = uri?.host ?: credentials.host.removePrefix("ftp://").split("/")[0].split(":")[0]
                val ftpPort = uri?.port?.takeIf { it > 0 } ?: 21
                val portStr = if (ftpPort != 21) ":$ftpPort" else ""
                val normItPath = if (itPath.startsWith("./")) itPath.substring(1) else (if (itPath.startsWith("/")) itPath else "/$itPath")
                val encodedItPath = normItPath.split("/").joinToString("/") { android.net.Uri.encode(it) }
                "ftp://${android.net.Uri.encode(credentials.username)}:${android.net.Uri.encode(credentials.password)}@$ftpHost$portStr$encodedItPath"
            }
            "SFTP" -> {
                val uri = try { java.net.URI(credentials.host) } catch (_: Exception) { null }
                val sftpHost = uri?.host ?: credentials.host.removePrefix("sftp://").split("/")[0].split(":")[0]
                val sftpPort = uri?.port?.takeIf { it > 0 } ?: 22
                val portStr = if (sftpPort != 22) ":$sftpPort" else ""
                val normItPath = if (itPath.startsWith("./")) itPath.substring(1) else (if (itPath.startsWith("/")) itPath else "/$itPath")
                val encodedItPath = normItPath.split("/").joinToString("/") { android.net.Uri.encode(it) }
                "sftp://${android.net.Uri.encode(credentials.username)}:${android.net.Uri.encode(credentials.password)}@$sftpHost$portStr$encodedItPath"
            }
            else -> itPath
        }
        
        val iNameWithoutExt = itName.substringBeforeLast(".")
        val iSubItem = sortedItems.find { candItem ->
            val candName = when(candItem) {
                is SmbItem -> candItem.name
                is WebDavItem -> candItem.name
                is FtpItem -> candItem.name
                is SftpItem -> candItem.name
                else -> ""
            }
            val candIsDir = when(candItem) {
                is SmbItem -> candItem.isDirectory
                is WebDavItem -> candItem.isDirectory
                is FtpItem -> candItem.isDirectory
                is SftpItem -> candItem.isDirectory
                else -> true
            }
            if (candIsDir || candItem == itm) false else {
                val ext = candName.substringAfterLast(".", "").lowercase()
                val candBase = candName.substringBeforeLast(".")
                (ext == "smi" || ext == "srt" || ext == "ass" || ext == "vtt" || ext == "ssa" || ext == "sub" || ext == "txt") &&
                (candBase.equals(iNameWithoutExt, ignoreCase = true) || (candBase.startsWith(iNameWithoutExt, ignoreCase = true) && candBase.getOrNull(iNameWithoutExt.length) == '.'))
            }
        }
        val (iSubUrl, iSubExt) = if (iSubItem != null) {
                val sPath = when(iSubItem) {
                    is SmbItem -> iSubItem.path
                    is WebDavItem -> iSubItem.href
                    is FtpItem -> iSubItem.path
                    is SftpItem -> iSubItem.path
                    else -> ""
                }
                val sName = when(iSubItem) {
                    is SmbItem -> iSubItem.name
                    is WebDavItem -> iSubItem.name
                    is FtpItem -> iSubItem.name
                    is SftpItem -> iSubItem.name
                    else -> ""
                }
                val authSubUrl = when (credentials.type) {
                    "WEBDAV" -> WebDavManager.buildAuthUrl(sPath, credentials.username, credentials.password)
                    "SMB" -> if (credentials.username.isNotBlank()) sPath.replaceFirst("smb://", "smb://${android.net.Uri.encode(credentials.username)}:${android.net.Uri.encode(credentials.password)}@") else sPath
                    "FTP" -> {
                        val uri = try { java.net.URI(credentials.host) } catch (_: Exception) { null }
                        val ftpHost = uri?.host ?: credentials.host.removePrefix("ftp://").split("/")[0].split(":")[0]
                        val ftpPort = uri?.port?.takeIf { it > 0 } ?: 21
                        val portStr = if (ftpPort != 21) ":$ftpPort" else ""
                        val normSPath = if (sPath.startsWith("./")) sPath.substring(1) else (if (sPath.startsWith("/")) sPath else "/$sPath")
                        val encodedSPath = normSPath.split("/").joinToString("/") { android.net.Uri.encode(it) }
                        "ftp://${android.net.Uri.encode(credentials.username)}:${android.net.Uri.encode(credentials.password)}@$ftpHost$portStr$encodedSPath"
                    }
                    "SFTP" -> {
                        val uri = try { java.net.URI(credentials.host) } catch (_: Exception) { null }
                        val sftpHost = uri?.host ?: credentials.host.removePrefix("sftp://").split("/")[0].split(":")[0]
                        val sftpPort = uri?.port?.takeIf { it > 0 } ?: 22
                        val portStr = if (sftpPort != 22) ":$sftpPort" else ""
                        val normSPath = if (sPath.startsWith("./")) sPath.substring(1) else (if (sPath.startsWith("/")) sPath else "/$sPath")
                        val encodedSPath = normSPath.split("/").joinToString("/") { android.net.Uri.encode(it) }
                        "sftp://${android.net.Uri.encode(credentials.username)}:${android.net.Uri.encode(credentials.password)}@$sftpHost$portStr$encodedSPath"
                    }
                    else -> sPath
                }
            authSubUrl to sName.substringAfterLast(".", "").lowercase()
        } else null to null
        
        PlaylistItem(videoUrl = itPlayUrl, title = itName, subtitleUrl = iSubUrl, subtitleExtension = iSubExt)
    }
    val currentIdx = playlist.indexOfFirst { it.videoUrl == targetPlayUrl }

    onVideoClick(targetPlayUrl, name, subUrl, subExt, playlist, currentIdx)
}

// 4개 요소를 가진 destructuring 헬퍼
private data class Quadruple<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
