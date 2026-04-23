package com.sv21c.jsplayer

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SMB 또는 WebDAV 서버 목록 화면.
 * - 진입 즉시 로컬 서브넷을 스캔하여 DLNA처럼 발견된 서버를 표시
 * - 저장된 서버(암호화 저장)는 최상단에 표시
 * - 발견된 서버 클릭 → 자격증명 입력 → 접속
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkServerListScreen(
    serverType: String,             // "SMB" or "WEBDAV"
    isTvMode: Boolean,
    onBackClick: () -> Unit,
    onConnect: (ServerCredentials) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var savedServers by remember { mutableStateOf<List<ServerCredentials>>(emptyList()) }
    val discoveredServers = remember { mutableStateListOf<DiscoveredServer>() }
    var isScanning by remember { mutableStateOf(false) }
    var scanProgress by remember { mutableIntStateOf(0) }
    var showDialog by remember { mutableStateOf(false) }
    var prefillHost by remember { mutableStateOf("") }
    var editingCreds by remember { mutableStateOf<ServerCredentials?>(null) }
    var deletingCreds by remember { mutableStateOf<ServerCredentials?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val typeLabel = when (serverType) {
        "SMB" -> "SMB"
        "FTP_SFTP" -> "FTP / SFTP"
        else -> "WebDAV"
    }
    val accentColor = when (serverType) {
        "SMB" -> Color(0xFFFF9946)
        "FTP_SFTP" -> Color(0xFF48BB78)
        else -> Color(0xFF63B3ED)
    }
    val bgGradient = when (serverType) {
        "SMB" -> Brush.verticalGradient(listOf(Color(0xFF1A0B00), Color(0xFF0C0500)))
        "FTP_SFTP" -> Brush.verticalGradient(listOf(Color(0xFF0D1F15), Color(0xFF050F08)))
        else -> Brush.verticalGradient(listOf(Color(0xFF00101A), Color(0xFF000508)))
    }

    fun loadSaved() {
        savedServers = if (serverType == "FTP_SFTP") {
            CredentialStore.loadByType(context, "FTP") + 
            CredentialStore.loadByType(context, "SFTP")
        } else {
            CredentialStore.loadByType(context, serverType)
        }
    }

    fun startScan() {
        discoveredServers.clear()
        isScanning = true
        scanProgress = 0
        errorMsg = null
        coroutineScope.launch(Dispatchers.IO) {
            try {
                when (serverType) {
                    "SMB" -> {
                        NetworkScanner.scanForSmb(
                            context = context,
                            onProgress = { p -> coroutineScope.launch(Dispatchers.Main) { scanProgress = p } },
                            onFound = { server ->
                                coroutineScope.launch(Dispatchers.Main) {
                                    val alreadySaved = savedServers.any { it.host.contains(server.ip, true) }
                                    val alreadyFound = discoveredServers.any { it.ip == server.ip }
                                    if (!alreadySaved && !alreadyFound) discoveredServers.add(server)
                                }
                            },
                            onComplete = { coroutineScope.launch(Dispatchers.Main) { isScanning = false } }
                        )
                    }
                    "FTP_SFTP" -> {
                        var scan1Done = false
                        var scan2Done = false
                        val checkDone = { if (scan1Done && scan2Done) coroutineScope.launch(Dispatchers.Main) { isScanning = false } }
                        
                        coroutineScope.launch(Dispatchers.IO) {
                            NetworkScanner.scanForFtp(
                                context = context,
                                onProgress = { p -> coroutineScope.launch(Dispatchers.Main) { scanProgress = p / 2 } },
                                onFound = { server ->
                                    coroutineScope.launch(Dispatchers.Main) {
                                        val alreadySaved = savedServers.any { it.host.contains(server.ip, true) }
                                        val alreadyFound = discoveredServers.any { it.ip == server.ip && it.type == server.type }
                                        if (!alreadySaved && !alreadyFound) discoveredServers.add(server)
                                    }
                                },
                                onComplete = { scan1Done = true; checkDone() }
                            )
                        }
                        coroutineScope.launch(Dispatchers.IO) {
                            NetworkScanner.scanForSftp(
                                context = context,
                                onProgress = { p -> coroutineScope.launch(Dispatchers.Main) { scanProgress = 50 + p / 2 } },
                                onFound = { server ->
                                    coroutineScope.launch(Dispatchers.Main) {
                                        val alreadySaved = savedServers.any { it.host.contains(server.ip, true) }
                                        val alreadyFound = discoveredServers.any { it.ip == server.ip && it.type == server.type }
                                        if (!alreadySaved && !alreadyFound) discoveredServers.add(server)
                                    }
                                },
                                onComplete = { scan2Done = true; checkDone() }
                            )
                        }
                    }
                    else -> {
                        NetworkScanner.scanForWebDav(
                            context = context,
                            onProgress = { p -> coroutineScope.launch(Dispatchers.Main) { scanProgress = p } },
                            onFound = { server ->
                                coroutineScope.launch(Dispatchers.Main) {
                                    val alreadySaved = savedServers.any { it.host.contains(server.ip, true) }
                                    val alreadyFound = discoveredServers.any { it.ip == server.ip }
                                    if (!alreadySaved && !alreadyFound) discoveredServers.add(server)
                                }
                            },
                            onComplete = { coroutineScope.launch(Dispatchers.Main) { isScanning = false } }
                        )
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorMsg = "스캔 오류: ${e.message}"
                    isScanning = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        loadSaved()
        startScan()
    }

    // 자격증명 입력 다이얼로그
    if (showDialog) {
        CredentialInputDialog(
            serverType = serverType,
            prefillHost = prefillHost,
            editingCreds = editingCreds,
            onDismiss = { showDialog = false; prefillHost = ""; editingCreds = null },
            onConfirm = { credentials, save ->
                val old = editingCreds
                showDialog = false; prefillHost = ""; editingCreds = null
                if (old != null) {
                    // 수정: 기존 삭제 후 새로 저장
                    CredentialStore.remove(context, old)
                    CredentialStore.save(context, credentials)
                    loadSaved()
                } else {
                    if (save) { CredentialStore.save(context, credentials); loadSaved() }
                    onConnect(credentials)
                }
            }
        )
    }

    // 삭제 확인 다이얼로그
    if (deletingCreds != null) {
        val creds = deletingCreds!!
        AlertDialog(
            onDismissRequest = { deletingCreds = null },
            containerColor = Color(0xFF1C1C2E),
            titleContentColor = Color.White,
            textContentColor = Color.White,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Delete, null, tint = Color(0xFFFF6B6B), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("[삭제]", fontWeight = FontWeight.Bold)
                }
            },
            text = { Text("해당 리스트를 삭제 하시겠습니까?") },
            confirmButton = {
                TextButton(onClick = {
                    CredentialStore.remove(context, creds)
                    loadSaved()
                    deletingCreds = null
                }) {
                    Text("예", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingCreds = null }) {
                    Text("아니오", color = Color.White.copy(0.6f))
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(bgGradient)) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Column {
                        Text("$typeLabel 서버", color = Color.White, fontWeight = FontWeight.Bold)
                        if (isScanning) {
                            Text("네트워크 검색 중... $scanProgress%", color = accentColor,
                                style = MaterialTheme.typography.labelSmall)
                        } else {
                            Text("발견: ${discoveredServers.size}개  |  저장: ${savedServers.size}개",
                                color = Color.White.copy(0.5f),
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, null, tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { prefillHost = ""; showDialog = true }) {
                        Icon(Icons.Default.Add, null, tint = accentColor)
                    }
                    IconButton(onClick = { if (!isScanning) startScan() }) {
                        Icon(Icons.Default.Refresh, null,
                            tint = if (isScanning) Color.Gray else Color.White)
                    }
                    Spacer(Modifier.width(8.dp))
                    ExitAppButton()
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(0.4f))
            )

            if (isScanning) {
                LinearProgressIndicator(
                    progress = { scanProgress / 100f },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = accentColor,
                    trackColor = Color.White.copy(0.1f)
                )
            }

            if (savedServers.isEmpty() && discoveredServers.isEmpty() && !isScanning) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            if (serverType == "SMB") Icons.Default.Storage else Icons.Default.Cloud,
                            null,
                            tint = accentColor.copy(0.4f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("발견된 $typeLabel 서버가 없습니다.",
                            color = Color.White.copy(0.5f),
                            style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(8.dp))
                        Text("+ 버튼으로 주소를 직접 입력하세요.",
                            color = Color.White.copy(0.35f),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (savedServers.isNotEmpty()) {
                        item { SectionHeader("저장된 서버", Icons.Default.Bookmark, Color(0xFFFFD700)) }
                        itemsIndexed(savedServers) { _, creds ->
                            SavedServerCard(
                                creds = creds, accentColor = accentColor, isTvMode = isTvMode,
                                onConnect = { onConnect(creds) },
                                onEdit = { editingCreds = creds; prefillHost = creds.host; showDialog = true },
                                onDelete = { deletingCreds = creds }
                            )
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                    }

                    if (discoveredServers.isNotEmpty()) {
                        item { SectionHeader("발견된 서버", Icons.Default.WifiFind, accentColor) }
                        itemsIndexed(discoveredServers.toList()) { _, server ->
                            DiscoveredServerCard(
                                server = server, accentColor = accentColor, isTvMode = isTvMode,
                                onClick = { prefillHost = server.path; showDialog = true }
                            )
                        }
                    }

                    if (isScanning) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(12.dp),
                                contentAlignment = Alignment.Center) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        color = accentColor,
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("계속 검색 중...",
                                        color = Color.White.copy(0.4f),
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }

        errorMsg?.let { msg ->
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                action = { TextButton(onClick = { errorMsg = null }) { Text("닫기", color = accentColor) } }
            ) { Text(msg, color = Color.White) }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(title, color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        HorizontalDivider(color = color.copy(0.3f), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SavedServerCard(
    creds: ServerCredentials,
    accentColor: Color,
    isTvMode: Boolean,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    var isEditFocused by remember { mutableStateOf(false) }
    var isDeleteFocused by remember { mutableStateOf(false) }
    val editFocusRequester = remember { FocusRequester() }
    val deleteFocusRequester = remember { FocusRequester() }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isTvMode && isFocused) 2.dp else 1.dp,
                brush = Brush.linearGradient(
                    if (isTvMode && isFocused)
                        listOf(Color.White, Color.White)
                    else listOf(accentColor.copy(0.6f), accentColor.copy(0.2f))
                ),
                shape = RoundedCornerShape(12.dp)
            )
            .onFocusChanged { isFocused = it.hasFocus }
            .focusable()
            .onKeyEvent { e ->
                if (e.key == Key.DirectionCenter || e.key == Key.Enter) {
                    if (e.type == KeyEventType.KeyDown) onConnect()
                    true
                } else if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) {
                    try { editFocusRequester.requestFocus() } catch (_: Exception) {}
                    true
                } else false
            }
            .clickable { onConnect() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = accentColor.copy(0.12f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(40.dp).background(accentColor.copy(0.2f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Bookmark, null, tint = accentColor, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(creds.host,
                    color = Color.White, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge)
                Text("사용자: ${creds.username.ifBlank { "(없음)" }}",
                    color = Color.White.copy(0.4f), style = MaterialTheme.typography.labelSmall)
            }
            // 수정 버튼
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        if (isEditFocused) accentColor.copy(0.3f) else Color.Transparent,
                        RoundedCornerShape(8.dp)
                    )
                    .border(
                        width = if (isEditFocused) 2.dp else 0.dp,
                        color = if (isEditFocused) Color.White else Color.Transparent,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .focusRequester(editFocusRequester)
                    .onFocusChanged { isEditFocused = it.isFocused }
                    .focusable()
                    .onKeyEvent { e ->
                        if (e.key == Key.DirectionCenter || e.key == Key.Enter) {
                            if (e.type == KeyEventType.KeyDown) onEdit()
                            true
                        } else if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) {
                            try { deleteFocusRequester.requestFocus() } catch (_: Exception) {}
                            true
                        } else {
                            false
                        }
                    }
                    .clickable { onEdit() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Edit, null,
                    tint = if (isEditFocused) Color.White else accentColor.copy(0.7f),
                    modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(4.dp))
            // 삭제 버튼
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        if (isDeleteFocused) Color.Red.copy(0.3f) else Color.Transparent,
                        RoundedCornerShape(8.dp)
                    )
                    .border(
                        width = if (isDeleteFocused) 2.dp else 0.dp,
                        color = if (isDeleteFocused) Color.White else Color.Transparent,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .focusRequester(deleteFocusRequester)
                    .onFocusChanged { isDeleteFocused = it.isFocused }
                    .focusable()
                    .onKeyEvent { e ->
                        if (e.key == Key.DirectionCenter || e.key == Key.Enter) {
                            if (e.type == KeyEventType.KeyDown) onDelete()
                            true
                        } else if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) {
                            try { editFocusRequester.requestFocus() } catch (_: Exception) {}
                            true
                        } else {
                            false
                        }
                    }
                    .clickable { onDelete() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Delete, null,
                    tint = if (isDeleteFocused) Color(0xFFFF6B6B) else Color.White.copy(0.4f),
                    modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun DiscoveredServerCard(
    server: DiscoveredServer,
    accentColor: Color,
    isTvMode: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val alpha by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.5f, targetValue = 1f, label = "a",
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse)
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isTvMode && isFocused) 2.dp else 1.dp,
                brush = Brush.linearGradient(
                    if (isTvMode && isFocused) listOf(Color.White, Color.White)
                    else listOf(accentColor.copy(0.3f), accentColor.copy(0.1f))
                ),
                shape = RoundedCornerShape(12.dp)
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionCenter) { onClick(); true } else false
            }
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(0.05f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(40.dp).background(accentColor.copy(0.15f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                val iconRes = when (server.type) {
                    "SMB" -> Icons.Default.Storage
                    "FTP" -> Icons.Default.Storage
                    "SFTP" -> Icons.Default.AccountBox
                    else -> Icons.Default.Cloud
                }
                Icon(
                    iconRes,
                    null, tint = accentColor.copy(alpha), modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (server.hostname == server.ip) server.ip else "${server.hostname} (${server.ip})",
                    color = Color.White, fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(server.path, color = accentColor.copy(0.7f), style = MaterialTheme.typography.bodySmall)
            }
            Text("접속 >", color = accentColor,
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

// ─── 자격증명 입력 다이얼로그 ──────────────────────────────────────────────
@Composable
fun CredentialInputDialog(
    serverType: String,
    prefillHost: String = "",
    editingCreds: ServerCredentials? = null,
    onDismiss: () -> Unit,
    onConfirm: (ServerCredentials, Boolean) -> Unit
) {
    val isEditMode = editingCreds != null
    // FTP_SFTP mode
    val isFtpSftp = serverType == "FTP_SFTP"
    var selectedProtocol by remember { mutableStateOf(if (isEditMode) editingCreds?.type ?: "FTP" else "FTP") }
    
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }

    LaunchedEffect(prefillHost, editingCreds) {
        val uriStr = editingCreds?.host ?: prefillHost
        if (uriStr.isNotBlank()) {
            if (isFtpSftp) {
                if (uriStr.startsWith("sftp://")) selectedProtocol = "SFTP"
                else if (uriStr.startsWith("ftp://")) selectedProtocol = "FTP"
            }
            try {
                val cleanUriStr = if (uriStr.contains("://")) uriStr else "dummy://$uriStr"
                val uri = java.net.URI(cleanUriStr)
                
                val parsedHost = uri.host ?: ""
                val prefix = if (uriStr.contains("://")) "${uri.scheme}://" else ""
                host = if (parsedHost.isNotBlank()) "$prefix$parsedHost" else uriStr
                
                val p = uri.port
                if (p != -1) port = p.toString()
                
                val pth = uri.path
                if (pth != null && pth.length > 1) {
                    path = pth.removePrefix("/")
                }
            } catch (e: Exception) {
                host = uriStr
            }
        }
    }

    var username by remember { mutableStateOf(editingCreds?.username ?: "") }
    var password by remember { mutableStateOf(editingCreds?.password ?: "") }
    var saveCredentials by remember { mutableStateOf(true) }
    var showPassword by remember { mutableStateOf(false) }
    var selectedEncoding by remember { mutableStateOf(editingCreds?.encoding ?: "AUTO") }

    val actualType = if (isFtpSftp) selectedProtocol else serverType
    val typeLabel = when(actualType) {
        "SMB" -> "SMB"
        "FTP" -> "FTP"
        "SFTP" -> "SFTP"
        "FTPS" -> "FTPS"
        else -> "WebDAV"
    }
    val accentColor = when(actualType) {
        "SMB" -> Color(0xFFFF9946)
        "FTP" -> Color(0xFF48BB78)
        "SFTP" -> Color(0xFFF6AD55)
        "FTPS" -> Color(0xFF9F7AEA)
        else -> Color(0xFF63B3ED)
    }
    val dialIcon = when(actualType) {
        "SMB" -> Icons.Default.Storage
        "FTP" -> Icons.Default.Storage
        "SFTP" -> Icons.Default.AccountBox
        "FTPS" -> Icons.Default.Security
        else -> Icons.Default.Cloud
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1C1C2E),
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(dialIcon, null, tint = accentColor, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (isEditMode) "$typeLabel 서버 수정" else "$typeLabel 서버 접속",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val fieldColors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accentColor,
                    unfocusedBorderColor = Color.White.copy(0.3f),
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedLabelColor = accentColor, unfocusedLabelColor = Color.White.copy(0.5f),
                    cursorColor = accentColor,
                    disabledTextColor = Color.White.copy(0.5f),
                    disabledBorderColor = Color.White.copy(0.15f),
                    disabledLabelColor = Color.White.copy(0.3f)
                )

                if (isFtpSftp && !isEditMode) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // FTP 버튼
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, if (selectedProtocol == "FTP") Color(0xFF48BB78) else Color.White.copy(0.3f), RoundedCornerShape(8.dp))
                                .background(if (selectedProtocol == "FTP") Color(0xFF48BB78).copy(0.1f) else Color.Transparent)
                                .clickable { selectedProtocol = "FTP"; port = "21" }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("FTP", color = if (selectedProtocol == "FTP") Color(0xFF48BB78) else Color.White.copy(0.6f), fontWeight = FontWeight.Bold)
                        }
                        // SFTP 버튼
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, if (selectedProtocol == "SFTP") Color(0xFFF6AD55) else Color.White.copy(0.3f), RoundedCornerShape(8.dp))
                                .background(if (selectedProtocol == "SFTP") Color(0xFFF6AD55).copy(0.1f) else Color.Transparent)
                                .clickable { selectedProtocol = "SFTP"; port = "22" }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("SFTP", color = if (selectedProtocol == "SFTP") Color(0xFFF6AD55) else Color.White.copy(0.6f), fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("서버 주소 / IP") },
                        placeholder = {
                            val phText = when(actualType) {
                                "SMB" -> "smb://192.168.0.1"
                                "FTP", "SFTP" -> "192.168.0.1"
                                else -> "http://192.168.0.1"
                            }
                            Text(phText, style = MaterialTheme.typography.bodySmall)
                        },
                        singleLine = true,
                        modifier = Modifier.weight(0.7f),
                        colors = fieldColors
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("포트(선택)") },
                        placeholder = { Text(
                            when(actualType) {
                                "FTP" -> "21"; "SFTP" -> "22"; "SMB" -> "445"; else -> "80"
                            }, 
                            style = MaterialTheme.typography.bodySmall
                        ) },
                        singleLine = true,
                        modifier = Modifier.weight(0.3f),
                        colors = fieldColors
                    )
                }

                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("원격 경로 (선택)") },
                    placeholder = { Text("예: /share/movie", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors
                )




                OutlinedTextField(value = username, onValueChange = { username = it },
                    label = { Text("사용자 이름") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), colors = fieldColors)
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("비밀번호") }, singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                null, tint = Color.White.copy(0.5f))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(), colors = fieldColors
                )
                if (!isEditMode) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { saveCredentials = !saveCredentials }
                            .padding(4.dp)
                    ) {
                        Checkbox(
                            checked = saveCredentials,
                            onCheckedChange = { saveCredentials = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = accentColor,
                                uncheckedColor = Color.White.copy(0.5f)
                            )
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("ID/PW 저장 (다음 접속 시 자동 사용)",
                            color = Color.White.copy(0.8f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            val isEnabled = host.isNotBlank()
            TextButton(
                onClick = {
                    if (isEnabled) {
                        val trimmedHost = host.trim().trimEnd('/')
                        val hasScheme = trimmedHost.contains("://")
                        val scheme = when(actualType) {
                            "SMB" -> "smb"
                            "FTP" -> "ftp"
                            "SFTP" -> "sftp"
                            "FTPS" -> "ftps"
                            "WEBDAV" -> "http"
                            else -> "http"
                        }
                        
                        val baseHost = if (isFtpSftp) {
                            val noScheme = if (hasScheme) trimmedHost.substringAfter("://") else trimmedHost
                            "${actualType.lowercase()}://$noScheme"
                        } else {
                            if (hasScheme) trimmedHost else "$scheme://$trimmedHost"
                        }

                        // Check if baseHost already has a port, e.g. smb://192.168.1.1:5005
                        val hasPortAlready = baseHost.matches(Regex(".*:[0-9]+$"))
                        val portStr = if (port.isNotBlank() && !hasPortAlready) ":${port.trim()}" else ""
                        
                        val pathPart = path.trim().removePrefix("/").removeSuffix("/")
                        
                        val finalHost = if (pathPart.isNotBlank()) "$baseHost$portStr/$pathPart/" else "$baseHost$portStr/"

                        onConfirm(
                            ServerCredentials(actualType, finalHost, username.trim(), password, "", selectedEncoding),
                            saveCredentials
                        )
                    }
                },
                enabled = isEnabled
            ) {
                Text(
                    if (isEditMode) "저  장" else "접  속",
                    color = accentColor, fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소", color = Color.White.copy(0.6f)) }
        }
    )
}