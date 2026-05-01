package com.sv21c.jsplayer

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
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
    onFolderClick: (String, String) -> Unit,
    onVideoClick: (url: String, title: String, subUrl: String?, subExt: String?, playlist: List<PlaylistItem>, currentIndex: Int, httpHeaders: Map<String, String>) -> Unit
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
    
    val showListPlayHistory = SettingsStore.getShowListPlayHistory(context)
    val showListFileInfo = SettingsStore.getShowListFileInfo(context)
    
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val accentColor = when (credentials.type) {
        "SMB" -> Color(0xFFFF9946)
        "WEBDAV" -> Color(0xFF63B3ED)
        "FTP" -> Color(0xFF48BB78)   // 초록
        "SFTP" -> Color(0xFFF6AD55)  // 주황
        "ONEDRIVE" -> Color(0xFF0078D4)  // 마이크로소프트 블루
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
                    "FTP", "FTPS" -> {
                        val uri = try { java.net.URI(credentials.host) } catch (_: Exception) { null }
                        val scheme = if (credentials.type == "FTPS") "ftps://" else "ftp://"
                        val ftpHost = uri?.host ?: credentials.host.removePrefix(scheme).split("/")[0].split(":")[0]
                        val ftpPort = uri?.port?.takeIf { it > 0 } ?: 21
                        val isFtps = credentials.type == "FTPS"
                        FtpManager.listFiles(ftpHost, ftpPort, credentials.username, credentials.password, path, credentials.encoding, isFtps)
                            .map { list -> list as List<Any> }
                    }
                    "SFTP" -> {
                        val uri = try { java.net.URI(credentials.host) } catch (_: Exception) { null }
                        val sftpHost = uri?.host ?: credentials.host.removePrefix("sftp://").split("/")[0].split(":")[0]
                        val sftpPort = uri?.port?.takeIf { it > 0 } ?: 22
                        SftpManager.listFiles(sftpHost, sftpPort, credentials.username, credentials.password, path)
                            .map { list -> list as List<Any> }
                    }
                    "GOOGLE_DRIVE" -> {
                        // username 필드에 저장된 이메일을 사용하여 계정 검색
                        val account = kotlinx.coroutines.withContext(Dispatchers.Main) { GoogleDriveAuthManager.getSignedInAccount(context) }
                        if (account != null) {
                            GoogleDriveManager.listFiles(context, account, path)
                                .map { list -> list as List<Any> }
                        } else {
                            Result.failure(Exception("Google Drive 계정 인증이 필요합니다."))
                        }
                    }
                    "ONEDRIVE" -> {
                        OneDriveManager.listFiles(path)
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
                            val name = when(it) { is SmbItem -> it.name; is WebDavItem -> it.name; is FtpItem -> it.name; is SftpItem -> it.name; is OneDriveItem -> it.name; else -> "" }
                            name.contains(searchQuery, ignoreCase = true)
                        }
                    } else {
                        items
                    }
                    filtered.sortedWith { o1, o2 ->
                        val isDir1 = when(o1) { is SmbItem -> o1.isDirectory; is WebDavItem -> o1.isDirectory; is FtpItem -> o1.isDirectory; is SftpItem -> o1.isDirectory; is OneDriveItem -> o1.isDirectory; else -> true }
                        val isDir2 = when(o2) { is SmbItem -> o2.isDirectory; is WebDavItem -> o2.isDirectory; is FtpItem -> o2.isDirectory; is SftpItem -> o2.isDirectory; is OneDriveItem -> o2.isDirectory; else -> true }

                        if (isDir1 && !isDir2) -1
                        else if (!isDir1 && isDir2) 1
                        else {
                            val name1 = when(o1) { is SmbItem -> o1.name; is WebDavItem -> o1.name; is FtpItem -> o1.name; is SftpItem -> o1.name; is OneDriveItem -> o1.name; else -> "" }
                            val name2 = when(o2) { is SmbItem -> o2.name; is WebDavItem -> o2.name; is FtpItem -> o2.name; is SftpItem -> o2.name; is OneDriveItem -> o2.name; else -> "" }
                            val time1 = when(o1) { is SmbItem -> o1.lastModified; is WebDavItem -> o1.lastModified; is FtpItem -> o1.lastModified; is SftpItem -> o1.lastModified; is OneDriveItem -> (o1.lastModified ?: 0L); else -> 0L }
                            val time2 = when(o2) { is SmbItem -> o2.lastModified; is WebDavItem -> o2.lastModified; is FtpItem -> o2.lastModified; is SftpItem -> o2.lastModified; is OneDriveItem -> (o2.lastModified ?: 0L); else -> 0L }

                            when (sortOrder) {
                                SortOrder.NAME_ASC -> name1.compareTo(name2, ignoreCase = true)
                                SortOrder.NAME_DESC -> name2.compareTo(name1, ignoreCase = true)
                                SortOrder.DATE_DESC -> time2.compareTo(time1)
                                SortOrder.DATE_ASC -> time1.compareTo(time2)
                                SortOrder.PLAYED_DESC -> {
                                    val url1 = getTargetPlayUrl(o1, credentials)
                                    val url2 = getTargetPlayUrl(o2, credentials)
                                    val played1 = PlayHistoryStore.getLastPlayed(context, url1)
                                    val played2 = PlayHistoryStore.getLastPlayed(context, url2)
                                    played2.compareTo(played1)
                                }
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
                            is GoogleDriveItem -> Quadruple(item.name, item.id, item.isDirectory,
                                GoogleDriveManager.isVideoFile(item.mimeType, item.name) && !item.isDirectory)
                            is OneDriveItem -> Quadruple(item.name, item.downloadUrl ?: item.id, item.isDirectory,
                                OneDriveManager.isVideoFile(item.name) && !item.isDirectory)
                            else -> return@itemsIndexed
                        }
                        val iconVector = if (isDir) Icons.Default.Folder else Icons.Default.PlayArrow
                        val iconTint = if (isDir) secondaryColor else primaryColor
                        val focusRequester = remember { FocusRequester() }
                        var isFocused by remember { mutableStateOf(false) }

                        // 즐겨찾기 데이터 준비
                        val showStar = isDir || isVideo
                        val favVideoUrl = if (isVideo && showStar) getTargetPlayUrl(item, credentials) else ""
                        val favSourcePath = if (showStar) when {
                            credentials.type == "ONEDRIVE" && item is OneDriveItem -> item.id
                            credentials.type == "GOOGLE_DRIVE" && item is GoogleDriveItem -> item.id
                            else -> path
                        } else ""
                        var isFav by remember(favVideoUrl, favSourcePath) {
                            mutableStateOf(if (showStar) FavoriteStore.isFavoriteByKey(context, favVideoUrl, favSourcePath) else false)
                        }

                        // 즐겨찾기 토글 실행 함수
                        val doToggleFavorite: () -> Unit = {
                            val (favSubUrl, favSubExt) = if (isVideo && !isDir) {
                                val nameWithoutExt = name.substringBeforeLast(".")
                                val subItem = sortedItems.find { candItem ->
                                    val (candName, _, candIsDir) = when (candItem) {
                                        is SmbItem -> Triple(candItem.name, candItem.path, candItem.isDirectory)
                                        is WebDavItem -> Triple(candItem.name, candItem.href, candItem.isDirectory)
                                        is FtpItem -> Triple(candItem.name, candItem.path, candItem.isDirectory)
                                        is SftpItem -> Triple(candItem.name, candItem.path, candItem.isDirectory)
                                        is GoogleDriveItem -> Triple(candItem.name, candItem.id, candItem.isDirectory)
                                        is OneDriveItem -> Triple(candItem.name, candItem.downloadUrl ?: candItem.id, candItem.isDirectory)
                                        else -> Triple("", "", true)
                                    }
                                    if (candIsDir || candItem == item) false
                                    else {
                                        val ext = candName.substringAfterLast(".", "").lowercase()
                                        val candBase = candName.substringBeforeLast(".")
                                        (ext == "smi" || ext == "srt" || ext == "ass" || ext == "vtt" || ext == "ssa" || ext == "sub" || ext == "txt") &&
                                        (candBase.equals(nameWithoutExt, ignoreCase = true) || (candBase.startsWith(nameWithoutExt, ignoreCase = true) && candBase.getOrNull(nameWithoutExt.length) == '.'))
                                    }
                                }
                                if (subItem != null) {
                                    val (sName, sPath) = when (subItem) {
                                        is GoogleDriveItem -> subItem.name to subItem.id
                                        is OneDriveItem -> subItem.name to subItem.id
                                        is SmbItem -> subItem.name to subItem.path
                                        is WebDavItem -> subItem.name to subItem.href
                                        is FtpItem -> subItem.name to subItem.path
                                        is SftpItem -> subItem.name to subItem.path
                                        else -> "" to ""
                                    }
                                    val fullSubUrl = when (credentials.type) {
                                        "WEBDAV" -> WebDavManager.buildAuthUrl(sPath, credentials.username, credentials.password)
                                        "SMB" -> if (credentials.username.isNotBlank()) {
                                            val authStr = "${android.net.Uri.encode(credentials.username)}:${android.net.Uri.encode(credentials.password)}@"
                                            sPath.replaceFirst("smb://", "smb://$authStr")
                                        } else sPath
                                        "FTP", "FTPS" -> {
                                            val uri = try { java.net.URI(credentials.host) } catch (_: Exception) { null }
                                            val scheme = if (credentials.type == "FTPS") "ftps://" else "ftp://"
                                            val ftpHost = uri?.host ?: credentials.host.removePrefix(scheme).split("/")[0].split(":")[0]
                                            val ftpPort = uri?.port?.takeIf { it > 0 } ?: 21
                                            val portStr = if (ftpPort != 21) ":$ftpPort" else ""
                                            val normSPath = if (sPath.startsWith("./")) sPath.substring(1) else (if (sPath.startsWith("/")) sPath else "/$sPath")
                                            val encodedSPath = normSPath.split("/").joinToString("/") { android.net.Uri.encode(it) }
                                            "$scheme${android.net.Uri.encode(credentials.username)}:${android.net.Uri.encode(credentials.password)}@$ftpHost$portStr$encodedSPath"
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
                                        "GOOGLE_DRIVE" -> sPath
                                        "ONEDRIVE" -> sPath
                                        else -> sPath
                                    }
                                    fullSubUrl to sName.substringAfterLast(".", "").lowercase()
                                } else null to null
                            } else null to null

                            val favItem = FavoriteItem(
                                id = java.util.UUID.randomUUID().toString(),
                                title = name,
                                videoUrl = favVideoUrl,
                                isDirectory = isDir,
                                sourceType = credentials.type,
                                sourcePath = favSourcePath,
                                addedAt = System.currentTimeMillis(),
                                subtitleUrl = favSubUrl,
                                subtitleExtension = favSubExt,
                                credentialsJson = credentials.toJson().toString()
                            )
                            isFav = FavoriteStore.toggle(context, favItem)
                        }

                        var isCardFocused by remember { mutableStateOf(false) }
                        var isStarFocused by remember { mutableStateOf(false) }
                        val itemFocusRequester = remember { FocusRequester() }

                        @OptIn(ExperimentalFoundationApi::class)
                        Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onFocusChanged { 
                                            isCardFocused = it.hasFocus 
                                            if (!it.hasFocus) isStarFocused = false
                                        }
                                        .focusRequester(itemFocusRequester)
                                .then(
                                    if (isTvMode) {
                                        Modifier
                                            .onKeyEvent { event ->
                                                if (event.key == Key.DirectionCenter || event.key == Key.Enter) {
                                                    if (event.type == KeyEventType.KeyDown) {
                                                        if (isStarFocused && showStar) {
                                                            doToggleFavorite()
                                                            val msg = if (isFav) "\"$name\" 즐겨찾기에 추가됨 ★" else "\"$name\" 즐겨찾기에서 제거됨"
                                                            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                                        } else {
                                                            if (isDir) onFolderClick(path, name)
                                                            else if (isVideo) {
                                                                val playUrl = when (item) {
                                                                    is GoogleDriveItem -> GoogleDriveManager.getStreamUrl(item.id)
                                                                    is OneDriveItem -> item.downloadUrl ?: OneDriveManager.getStreamUrl(item.id)
                                                                    else -> ""
                                                                }
                                                                handleVideoClick(
                                                                    item, credentials, sortedItems, playUrl, name, context, coroutineScope, onVideoClick
                                                                )
                                                            }
                                                        }
                                                    }
                                                    return@onKeyEvent true
                                                } else if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight && showStar) {
                                                    isStarFocused = true
                                                    return@onKeyEvent true
                                                } else if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft && isStarFocused) {
                                                    isStarFocused = false
                                                    return@onKeyEvent true
                                                }
                                                false
                                            }
                                            .focusable()
                                    } else {
                                        Modifier.clickable {
                                            if (isDir) onFolderClick(path, name)
                                            else if (isVideo) {
                                                val playUrl = when (item) {
                                                    is GoogleDriveItem -> GoogleDriveManager.getStreamUrl(item.id)
                                                    is OneDriveItem -> item.downloadUrl ?: OneDriveManager.getStreamUrl(item.id)
                                                    else -> ""
                                                }
                                                handleVideoClick(
                                                    item, credentials, sortedItems, playUrl, name, context, coroutineScope, onVideoClick
                                                )
                                            }
                                        }
                                    }
                                )
                                .border(
                                    width = if (isTvMode && isCardFocused) 3.dp else 0.dp,
                                    color = if (isTvMode && isCardFocused) primaryColor else Color.Transparent,
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
                                val playUrlForSave = if (isVideo) getTargetPlayUrl(item, credentials) else ""
                                val savedPos = if (isVideo && playUrlForSave.isNotEmpty()) PlaybackPositionStore.getPosition(context, playUrlForSave) else 0L

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (!isDir && !isVideo)
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                        else if (savedPos > 0L)
                                            Color(0xFFBB86FC)
                                        else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val sizeVal = when (item) {
                                        is SmbItem -> item.size
                                        is WebDavItem -> item.size
                                        is FtpItem -> item.size
                                        is SftpItem -> item.size
                                        is GoogleDriveItem -> item.size ?: 0L
                                        is OneDriveItem -> item.size ?: 0L
                                        else -> 0L
                                    }
                                    val sizeDisplay = if (sizeVal > 0) android.text.format.Formatter.formatShortFileSize(context, sizeVal) else ""
                                    
                                    val info = listOfNotNull(
                                        sizeDisplay.takeIf { it.isNotEmpty() }
                                    ).joinToString(" | ")
                                    
                                    if (info.isNotEmpty() && showListFileInfo) {
                                        Text(
                                            text = info, 
                                            style = MaterialTheme.typography.bodySmall, 
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                // 저장된 재생 위치 표시 (시간 + 프로그레스 바)
                                if (savedPos > 0L && showListPlayHistory) {
                                    val savedDur = PlaybackPositionStore.getDuration(context, playUrlForSave)
                                    Column(
                                        horizontalAlignment = androidx.compose.ui.Alignment.End,
                                        modifier = Modifier.padding(start = 12.dp).width(48.dp)
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
                                                    .height(2.dp)
                                                    .background(
                                                        Color.White.copy(alpha = 0.2f),
                                                        shape = RoundedCornerShape(1.dp)
                                                    )
                                            ) {
                                                val progress = (savedPos.toFloat() / savedDur.toFloat()).coerceIn(0f, 1f)
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth(progress)
                                                        .fillMaxHeight()
                                                        .background(
                                                            MaterialTheme.colorScheme.primary,
                                                            shape = RoundedCornerShape(1.dp)
                                                        )
                                                )
                                            }
                                        }
                                    }
                                }

                                if (showStar) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .background(
                                                if (isCardFocused && isStarFocused) primaryColor.copy(0.3f) else Color.Transparent,
                                                RoundedCornerShape(8.dp)
                                            )
                                            .border(
                                                width = if (isCardFocused && isStarFocused) 2.dp else 0.dp,
                                                color = if (isCardFocused && isStarFocused) Color.White else Color.Transparent,
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .clickable {
                                                doToggleFavorite()
                                                val msg = if (isFav) "\"$name\" 즐겨찾기에 추가됨 ★" else "\"$name\" 즐겨찾기에서 제거됨"
                                                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (isFav) Icons.Filled.Star else Icons.Default.StarBorder,
                                            contentDescription = "즐겨찾기 토글",
                                            tint = if (isFav) Color(0xFFFFD700) else Color.Gray.copy(alpha = 0.5f),
                                            modifier = Modifier.size(28.dp)
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

private fun handleVideoClick(
    item: Any,
    credentials: ServerCredentials,
    sortedItems: List<Any>,
    playUrl: String,
    name: String,
    context: Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    onVideoClick: (url: String, title: String, subUrl: String?, subExt: String?, playlist: List<PlaylistItem>, currentIndex: Int, httpHeaders: Map<String, String>) -> Unit
) {
    val path = when (item) {
        is SmbItem -> item.path
        is WebDavItem -> item.href
        is FtpItem -> item.path
        is SftpItem -> item.path
        is OneDriveItem -> item.downloadUrl ?: item.id
        else -> ""
    }
    val targetPlayUrl = playUrl.ifBlank {
        when (credentials.type) {
            "WEBDAV" -> WebDavManager.buildAuthUrl(path, credentials.username, credentials.password)
            "SMB" -> if (credentials.username.isNotBlank()) {
                val authStr = "${android.net.Uri.encode(credentials.username)}:${android.net.Uri.encode(credentials.password)}@"
                path.replaceFirst("smb://", "smb://$authStr")
            } else path
            "FTP", "FTPS" -> {
                val uri = try { java.net.URI(credentials.host) } catch (_: Exception) { null }
                val scheme = if (credentials.type == "FTPS") "ftps://" else "ftp://"
                val ftpHost = uri?.host ?: credentials.host.removePrefix(scheme).split("/")[0].split(":")[0]
                val ftpPort = uri?.port?.takeIf { it > 0 } ?: 21
                val portStr = if (ftpPort != 21) ":$ftpPort" else ""
                val normPath = if (path.startsWith("./")) path.substring(1) else (if (path.startsWith("/")) path else "/$path")
                val encodedPath = normPath.split("/").joinToString("/") { android.net.Uri.encode(it) }
                "$scheme${android.net.Uri.encode(credentials.username)}:${android.net.Uri.encode(credentials.password)}@$ftpHost$portStr$encodedPath"
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
            is GoogleDriveItem -> Triple(itItem.name, itItem.id, itItem.isDirectory)
            is OneDriveItem -> Triple(itItem.name, itItem.downloadUrl ?: itItem.id, itItem.isDirectory)
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
            is GoogleDriveItem -> Triple(subItem.name, subItem.id, subItem.isDirectory)
            is OneDriveItem -> Triple(subItem.name, subItem.downloadUrl ?: subItem.id, subItem.isDirectory)
            else -> Triple("", "", true)
        }
        val authSubUrl = when (credentials.type) {
            "WEBDAV" -> WebDavManager.buildAuthUrl(sPath, credentials.username, credentials.password)
            "SMB" -> if (credentials.username.isNotBlank()) {
                val authStr = "${android.net.Uri.encode(credentials.username)}:${android.net.Uri.encode(credentials.password)}@"
                sPath.replaceFirst("smb://", "smb://$authStr")
            } else sPath
            "FTP", "FTPS" -> {
                val uri = try { java.net.URI(credentials.host) } catch (_: Exception) { null }
                val scheme = if (credentials.type == "FTPS") "ftps://" else "ftp://"
                val ftpHost = uri?.host ?: credentials.host.removePrefix(scheme).split("/")[0].split(":")[0]
                val ftpPort = uri?.port?.takeIf { it > 0 } ?: 21
                val portStr = if (ftpPort != 21) ":$ftpPort" else ""
                val normSPath = if (sPath.startsWith("./")) sPath.substring(1) else (if (sPath.startsWith("/")) sPath else "/$sPath")
                val encodedSPath = normSPath.split("/").joinToString("/") { android.net.Uri.encode(it) }
                "$scheme${android.net.Uri.encode(credentials.username)}:${android.net.Uri.encode(credentials.password)}@$ftpHost$portStr$encodedSPath"
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
            "GOOGLE_DRIVE" -> {
                GoogleDriveManager.getStreamUrl(sPath)
            }
            "ONEDRIVE" -> {
                // If it's a downloadUrl (starts with http), use it directly. Otherwise use getStreamUrl
                if (sPath.startsWith("http")) sPath else OneDriveManager.getStreamUrl(sPath)
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
            is GoogleDriveItem -> Quadruple(itm.name, itm.id, itm.isDirectory, GoogleDriveManager.isVideoFile(itm.mimeType, itm.name) && !itm.isDirectory)
            is OneDriveItem -> Quadruple(itm.name, itm.downloadUrl ?: itm.id, itm.isDirectory, OneDriveManager.isVideoFile(itm.name) && !itm.isDirectory)
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
            is GoogleDriveItem -> Quadruple(itm.name, itm.id, itm.isDirectory, false)
            is OneDriveItem -> Quadruple(itm.name, itm.downloadUrl ?: itm.id, itm.isDirectory, false)
            else -> Quadruple("", "", true, false)
        }
        val itPlayUrl = when (credentials.type) {
            "WEBDAV" -> WebDavManager.buildAuthUrl(itPath, credentials.username, credentials.password)
            "SMB" -> if (credentials.username.isNotBlank()) {
                val auth = "${android.net.Uri.encode(credentials.username)}:${android.net.Uri.encode(credentials.password)}@"
                itPath.replaceFirst("smb://", "smb://$auth")
            } else itPath
            "FTP", "FTPS" -> {
                val uri = try { java.net.URI(credentials.host) } catch (_: Exception) { null }
                val scheme = if (credentials.type == "FTPS") "ftps://" else "ftp://"
                val ftpHost = uri?.host ?: credentials.host.removePrefix(scheme).split("/")[0].split(":")[0]
                val ftpPort = uri?.port?.takeIf { it > 0 } ?: 21
                val portStr = if (ftpPort != 21) ":$ftpPort" else ""
                val normItPath = if (itPath.startsWith("./")) itPath.substring(1) else (if (itPath.startsWith("/")) itPath else "/$itPath")
                val encodedItPath = normItPath.split("/").joinToString("/") { android.net.Uri.encode(it) }
                "$scheme${android.net.Uri.encode(credentials.username)}:${android.net.Uri.encode(credentials.password)}@$ftpHost$portStr$encodedItPath"
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
            "GOOGLE_DRIVE" -> {
                GoogleDriveManager.getStreamUrl(itPath)
            }
            "ONEDRIVE" -> {
                if (itPath.startsWith("http")) itPath else OneDriveManager.getStreamUrl(itPath)
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
                is GoogleDriveItem -> candItem.name
                is OneDriveItem -> candItem.name
                else -> ""
            }
            val candIsDir = when(candItem) {
                is SmbItem -> candItem.isDirectory
                is WebDavItem -> candItem.isDirectory
                is FtpItem -> candItem.isDirectory
                is SftpItem -> candItem.isDirectory
                is GoogleDriveItem -> candItem.isDirectory
                is OneDriveItem -> candItem.isDirectory
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
                    is GoogleDriveItem -> iSubItem.id
                    is OneDriveItem -> iSubItem.downloadUrl ?: iSubItem.id
                    else -> ""
                }
                val sName = when(iSubItem) {
                    is SmbItem -> iSubItem.name
                    is WebDavItem -> iSubItem.name
                    is FtpItem -> iSubItem.name
                    is SftpItem -> iSubItem.name
                    is GoogleDriveItem -> iSubItem.name
                    is OneDriveItem -> iSubItem.name
                    else -> ""
                }
                val authSubUrl = when (credentials.type) {
                    "WEBDAV" -> WebDavManager.buildAuthUrl(sPath, credentials.username, credentials.password)
                    "SMB" -> if (credentials.username.isNotBlank()) sPath.replaceFirst("smb://", "smb://${android.net.Uri.encode(credentials.username)}:${android.net.Uri.encode(credentials.password)}@") else sPath
                    "FTP", "FTPS" -> {
                        val uri = try { java.net.URI(credentials.host) } catch (_: Exception) { null }
                        val scheme = if (credentials.type == "FTPS") "ftps://" else "ftp://"
                        val ftpHost = uri?.host ?: credentials.host.removePrefix(scheme).split("/")[0].split(":")[0]
                        val ftpPort = uri?.port?.takeIf { it > 0 } ?: 21
                        val portStr = if (ftpPort != 21) ":$ftpPort" else ""
                        val normSPath = if (sPath.startsWith("./")) sPath.substring(1) else (if (sPath.startsWith("/")) sPath else "/$sPath")
                        val encodedSPath = normSPath.split("/").joinToString("/") { android.net.Uri.encode(it) }
                        "$scheme${android.net.Uri.encode(credentials.username)}:${android.net.Uri.encode(credentials.password)}@$ftpHost$portStr$encodedSPath"
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
                    "GOOGLE_DRIVE" -> {
                        GoogleDriveManager.getStreamUrl(sPath)
                    }
                    "ONEDRIVE" -> {
                        if (sPath.startsWith("http")) sPath else OneDriveManager.getStreamUrl(sPath)
                    }
                    else -> sPath
                }
            authSubUrl to sName.substringAfterLast(".", "").lowercase()
        } else null to null
        
        PlaylistItem(videoUrl = itPlayUrl, title = itName, subtitleUrl = iSubUrl, subtitleExtension = iSubExt)
    }
    val currentIdx = playlist.indexOfFirst { it.videoUrl == targetPlayUrl }

    if (credentials.type == "GOOGLE_DRIVE") {
        coroutineScope.launch(Dispatchers.IO) {
            val account = kotlinx.coroutines.withContext(Dispatchers.Main) { GoogleDriveAuthManager.getSignedInAccount(context) }
            val token = if (account != null) GoogleDriveManager.getAccessToken(context, account) else null
            val httpHeaders = if (token != null) mapOf("Authorization" to "Bearer $token") else emptyMap()
            
            withContext(Dispatchers.Main) {
                onVideoClick(targetPlayUrl, name, subUrl, subExt, playlist, currentIdx, httpHeaders)
            }
        }
    } else if (credentials.type == "ONEDRIVE") {
        coroutineScope.launch(Dispatchers.IO) {
            val token = OneDriveAuthManager.getAccessToken()
            var finalPlayUrl = targetPlayUrl
            var httpHeaders = emptyMap<String, String>()
            
            if (targetPlayUrl.startsWith("https://graph.microsoft.com")) {
                val fileId = (item as? OneDriveItem)?.id
                if (fileId != null) {
                    val directUrl = OneDriveManager.getDirectDownloadUrl(fileId)
                    if (directUrl != null) {
                        finalPlayUrl = directUrl
                    } else if (token != null) {
                        httpHeaders = mapOf("Authorization" to "Bearer $token")
                    }
                } else if (token != null) {
                    httpHeaders = mapOf("Authorization" to "Bearer $token")
                }
            }
            
            withContext(Dispatchers.Main) {
                onVideoClick(finalPlayUrl, name, subUrl, subExt, playlist, currentIdx, httpHeaders)
            }
        }
    } else {
        onVideoClick(targetPlayUrl, name, subUrl, subExt, playlist, currentIdx, emptyMap())
    }
}

// 4개 요소를 가진 destructuring 헬퍼
private data class Quadruple<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

private fun getTargetPlayUrl(item: Any, credentials: ServerCredentials): String {
    val path = when (item) {
        is SmbItem -> item.path
        is WebDavItem -> item.href
        is FtpItem -> item.path
        is SftpItem -> item.path
        is GoogleDriveItem -> item.id
        is OneDriveItem -> item.downloadUrl ?: item.id
        else -> ""
    }
    return when (credentials.type) {
        "WEBDAV" -> WebDavManager.buildAuthUrl(path, credentials.username, credentials.password)
        "SMB" -> if (credentials.username.isNotBlank()) {
            val authStr = "${android.net.Uri.encode(credentials.username)}:${android.net.Uri.encode(credentials.password)}@"
            path.replaceFirst("smb://", "smb://$authStr")
        } else path
        "FTP", "FTPS" -> {
            val uri = try { java.net.URI(credentials.host) } catch (_: Exception) { null }
            val scheme = if (credentials.type == "FTPS") "ftps://" else "ftp://"
            val ftpHost = uri?.host ?: credentials.host.removePrefix(scheme).split("/")[0].split(":")[0]
            val ftpPort = uri?.port?.takeIf { it > 0 } ?: 21
            val portStr = if (ftpPort != 21) ":$ftpPort" else ""
            val normPath = if (path.startsWith("./")) path.substring(1) else (if (path.startsWith("/")) path else "/$path")
            val encodedPath = normPath.split("/").joinToString("/") { android.net.Uri.encode(it) }
            "$scheme${android.net.Uri.encode(credentials.username)}:${android.net.Uri.encode(credentials.password)}@$ftpHost$portStr$encodedPath"
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
        "GOOGLE_DRIVE" -> GoogleDriveManager.getStreamUrl(path)
        "ONEDRIVE" -> if (path.startsWith("http")) path else OneDriveManager.getStreamUrl(path)
        else -> path
    }
}
