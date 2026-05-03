package com.sv21c.jsplayer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class LibraryLicense(
    val name: String,
    val version: String,
    val description: String,
    val license: String,
    val licenseType: LicenseType,
    val url: String,
    val category: String
)

enum class LicenseType(val label: String, val color: Color, val bgColor: Color) {
    GPL3("GPL-3.0", Color(0xFFFF6B6B), Color(0x33FF6B6B)),
    LGPL21("LGPL-2.1", Color(0xFFFFB347), Color(0x33FFB347)),
    APACHE2("Apache 2.0", Color(0xFF34D399), Color(0x3334D399)),
    MIT("MIT", Color(0xFF63B3ED), Color(0x3363B3ED)),
    EPL("EPL", Color(0xFF9F7AEA), Color(0x339F7AEA)),
    CDDL("CDDL-1.0", Color(0xFF38BDF8), Color(0x3338BDF8)),
    BSD("BSD", Color(0xFFF6E05E), Color(0x33F6E05E)),
    MULTI("복합", Color(0xFFA78BFA), Color(0x33A78BFA))
}

private fun getAllLibraries(): List<LibraryLicense> = listOf(
    // ── 미디어 / 코덱 ──
    LibraryLicense("jellyfin-media3-ffmpeg-decoder", "1.5.0+1",
        "FFmpeg 기반 비디오/오디오 소프트웨어 디코더", "GNU General Public License v3.0",
        LicenseType.GPL3, "https://github.com/jellyfin/jellyfin-media3", "미디어 / 코덱"),
    LibraryLicense("AndroidX Media3 ExoPlayer", "1.5.0",
        "Google 공식 미디어 재생 프레임워크 (ExoPlayer)", "Apache License 2.0",
        LicenseType.APACHE2, "https://github.com/androidx/media", "미디어 / 코덱"),
    LibraryLicense("AndroidX Media3 UI", "1.5.0",
        "미디어 재생 UI 컴포넌트", "Apache License 2.0",
        LicenseType.APACHE2, "https://github.com/androidx/media", "미디어 / 코덱"),
    LibraryLicense("AndroidX Media3 Session", "1.5.0",
        "미디어 세션 및 백그라운드 재생 지원", "Apache License 2.0",
        LicenseType.APACHE2, "https://github.com/androidx/media", "미디어 / 코덱"),
    LibraryLicense("AndroidX Media3 DataSource OkHttp", "1.5.0",
        "OkHttp 기반 HTTP 데이터소스 어댑터", "Apache License 2.0",
        LicenseType.APACHE2, "https://github.com/androidx/media", "미디어 / 코덱"),

    // ── 네트워크 프로토콜 ──
    LibraryLicense("jCIFS-NG", "2.1.10",
        "SMB/CIFS 네트워크 파일 공유 프로토콜 클라이언트", "GNU Lesser General Public License v2.1",
        LicenseType.LGPL21, "https://github.com/Agno3/jcifs-ng", "네트워크 프로토콜"),
    LibraryLicense("Sardine-Android", "0.9",
        "WebDAV 네트워크 프로토콜 클라이언트", "Apache License 2.0",
        LicenseType.APACHE2, "https://github.com/thegrizzlylabs/sardine-android", "네트워크 프로토콜"),
    LibraryLicense("Apache Commons Net", "3.11.1",
        "FTP/FTPS 네트워크 프로토콜 클라이언트", "Apache License 2.0",
        LicenseType.APACHE2, "https://commons.apache.org/proper/commons-net/", "네트워크 프로토콜"),
    LibraryLicense("SSHJ", "0.39.0",
        "SSH/SFTP 보안 파일 전송 프로토콜 클라이언트", "Apache License 2.0",
        LicenseType.APACHE2, "https://github.com/hierynomus/sshj", "네트워크 프로토콜"),
    LibraryLicense("OkHttp", "4.12.0",
        "고성능 HTTP 클라이언트 (Sardine/WebDAV 의존)", "Apache License 2.0",
        LicenseType.APACHE2, "https://square.github.io/okhttp/", "네트워크 프로토콜"),

    // ── DLNA / UPnP ──
    LibraryLicense("jUPnP", "3.0.4",
        "UPnP/DLNA 미디어 서버 디스커버리 및 제어", "CDDL-1.0 (Common Development and Distribution License)",
        LicenseType.CDDL, "https://github.com/jupnp/jupnp", "DLNA / UPnP"),
    LibraryLicense("NanoHTTPD", "2.3.1",
        "경량 HTTP 서버 (DLNA 캐스팅용 로컬 서버)", "BSD 3-Clause License",
        LicenseType.BSD, "https://github.com/NanoHttpd/nanohttpd", "DLNA / UPnP"),
    LibraryLicense("Eclipse Jetty", "9.4.53",
        "서블릿 컨테이너 (jUPnP Android 내부 의존)", "Eclipse Public License 1.0 / Apache 2.0",
        LicenseType.EPL, "https://www.eclipse.org/jetty/", "DLNA / UPnP"),

    // ── 클라우드 스토리지 ──
    LibraryLicense("Google Play Services Auth", "21.1.1",
        "Google 계정 인증 (Google Drive 로그인)", "Android Software Development Kit License",
        LicenseType.APACHE2, "https://developers.google.com/android/guides/overview", "클라우드 스토리지"),
    LibraryLicense("Google API Client for Android", "2.2.0",
        "Google API 호출 클라이언트", "Apache License 2.0",
        LicenseType.APACHE2, "https://github.com/googleapis/google-api-java-client", "클라우드 스토리지"),
    LibraryLicense("Google Drive API v3", "rev20240123",
        "Google Drive 파일 탐색 및 스트리밍 API", "Apache License 2.0",
        LicenseType.APACHE2, "https://developers.google.com/drive", "클라우드 스토리지"),
    LibraryLicense("Microsoft MSAL", "5.x",
        "Microsoft 계정 인증 (OneDrive 로그인)", "MIT License",
        LicenseType.MIT, "https://github.com/AzureAD/microsoft-authentication-library-for-android", "클라우드 스토리지"),

    // ── 보안 / 암호화 ──
    LibraryLicense("Bouncy Castle Provider", "1.78.1",
        "JCA/JCE 암호화 프로바이더 (X25519 등 최신 알고리즘)", "MIT License",
        LicenseType.MIT, "https://www.bouncycastle.org/", "보안 / 암호화"),
    LibraryLicense("Bouncy Castle PKIX", "1.78.1",
        "PKI 인증서 및 CMS 지원", "MIT License",
        LicenseType.MIT, "https://www.bouncycastle.org/", "보안 / 암호화"),
    LibraryLicense("AndroidX Security Crypto", "1.1.0-alpha06",
        "EncryptedSharedPreferences (자격증명 암호화 저장)", "Apache License 2.0",
        LicenseType.APACHE2, "https://developer.android.com/jetpack/androidx/releases/security", "보안 / 암호화"),

    // ── UI / 프레임워크 ──
    LibraryLicense("AndroidX Compose (BOM)", "2024.09.00",
        "Jetpack Compose UI 프레임워크 (UI, Graphics, Material3)", "Apache License 2.0",
        LicenseType.APACHE2, "https://developer.android.com/jetpack/compose", "UI / 프레임워크"),
    LibraryLicense("AndroidX Core KTX", "1.17.0",
        "Android 코어 Kotlin 확장", "Apache License 2.0",
        LicenseType.APACHE2, "https://developer.android.com/jetpack/androidx/releases/core", "UI / 프레임워크"),
    LibraryLicense("AndroidX Activity Compose", "1.12.4",
        "Activity와 Compose 통합", "Apache License 2.0",
        LicenseType.APACHE2, "https://developer.android.com/jetpack/androidx/releases/activity", "UI / 프레임워크"),
    LibraryLicense("AndroidX Lifecycle Runtime KTX", "2.10.0",
        "생명주기 관리 Kotlin 확장", "Apache License 2.0",
        LicenseType.APACHE2, "https://developer.android.com/jetpack/androidx/releases/lifecycle", "UI / 프레임워크"),
    LibraryLicense("Material Icons Extended", "-",
        "확장 Material Design 아이콘 세트", "Apache License 2.0",
        LicenseType.APACHE2, "https://fonts.google.com/icons", "UI / 프레임워크"),

    // ── 유틸리티 ──
    LibraryLicense("SLF4J", "2.0.7",
        "로깅 퍼사드 (Android Logcat 출력)", "MIT License",
        LicenseType.MIT, "https://www.slf4j.org/", "유틸리티"),
    LibraryLicense("Jakarta XML WS API", "4.0.3",
        "XML 웹서비스 API (jUPnP 의존)", "Eclipse Public License 2.0",
        LicenseType.EPL, "https://jakarta.ee/", "유틸리티"),
    LibraryLicense("Jakarta XML Bind API", "4.0.5",
        "XML 바인딩 API (jUPnP 의존)", "Eclipse Public License 2.0",
        LicenseType.EPL, "https://jakarta.ee/", "유틸리티"),
    LibraryLicense("Kotlin Coroutines Play Services", "1.7.3",
        "Google Play Services 코루틴 어댑터", "Apache License 2.0",
        LicenseType.APACHE2, "https://github.com/Kotlin/kotlinx.coroutines", "유틸리티"),
    LibraryLicense("Desugar JDK Libs", "2.0.4",
        "Java 8+ API 역호환 지원 (java.time 등)", "Apache License 2.0",
        LicenseType.APACHE2, "https://github.com/nickallendev/desugar_jdk_libs", "유틸리티")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensePolicyScreen(
    isTvMode: Boolean,
    onBackClick: () -> Unit
) {
    val libraries = remember { getAllLibraries() }
    val grouped = remember { libraries.groupBy { it.category } }
    val licenseStats = remember {
        libraries.groupBy { it.licenseType }.mapValues { it.value.size }
            .toList().sortedByDescending { it.second }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0C1122), Color(0xFF05070F))))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Text("오픈소스 라이선스", color = Color.White, fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
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
                                shape = RoundedCornerShape(8.dp)
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = if (isBackFocused) Color.White else Color.White.copy(alpha = 0.7f)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.4f)
                )
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 40.dp)
            ) {
                // ── 앱 라이선스 고지문 ──
                item { AppLicenseCard(isTvMode) }

                // ── 라이선스 통계 ──
                item { LicenseStatsRow(licenseStats, libraries.size) }

                // ── 카테고리별 라이브러리 목록 ──
                val categoryOrder = listOf(
                    "미디어 / 코덱", "네트워크 프로토콜", "DLNA / UPnP",
                    "클라우드 스토리지", "보안 / 암호화", "UI / 프레임워크", "유틸리티"
                )
                categoryOrder.forEach { category ->
                    val libs = grouped[category] ?: return@forEach
                    item {
                        CategorySection(category, libs, isTvMode)
                    }
                }
            }
        }
    }
}

@Composable
fun LicenseStatsRow(stats: List<Pair<LicenseType, Int>>, total: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "총 ${total}개 오픈소스 라이브러리 사용",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(Modifier.height(12.dp))
            // license bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
            ) {
                stats.forEach { (type, count) ->
                    Box(
                        modifier = Modifier
                            .weight(count.toFloat())
                            .fillMaxHeight()
                            .background(type.color)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            // legend — chunked rows로 자동 줄바꿈
            val chunked = stats.chunked(4)
            chunked.forEachIndexed { index, row ->
                if (index > 0) Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    row.forEach { (type, count) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(type.bgColor)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(type.color)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "${type.label} ($count)",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CategorySection(category: String, libs: List<LibraryLicense>, isTvMode: Boolean) {
    var expanded by remember { mutableStateOf(true) }
    val rotation by animateFloatAsState(if (expanded) 0f else -90f, label = "arrow")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(300))
    ) {
        // Category header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "$category (${libs.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.rotate(rotation)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(300)),
            exit = shrinkVertically(animationSpec = tween(200))
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                libs.forEach { lib ->
                    LicenseItemCard(lib, isTvMode)
                }
            }
        }
    }
}

@Composable
fun AppLicenseCard(isTvMode: Boolean) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isTvMode && isFocused) 2.dp else 1.dp,
                color = if (isTvMode && isFocused) Color.White else Color(0xFFD280FF).copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp)
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF5B3F80).copy(alpha = 0.2f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFFD280FF))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "SVC PLAYER 라이선스 고지문 (GPL-3.0)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "본 애플리케이션은 FFmpeg 디코더(GPL-3.0)를 선택적으로 사용할 수 있도록 설계되어 있습니다.\n\nFFmpeg 코덱 바이너리(.so)는 앱에 기본 포함되어 있지 않으며, 사용자가 별도의 코덱 팩(ZIP)을 선택하여 직접 설치할 수 있습니다. 코덱을 설치하여 사용하는 경우 해당 코덱 바이너리에는 GPL-3.0 라이선스가 적용됩니다.\n\n앱의 전체 소스코드는 [GNU General Public License v3.0] 하에 공개(오픈소스)됩니다. 앱 사용자는 자유롭게 앱을 실행, 복제, 배포할 수 있으며 소스코드를 열람 및 수정할 권리가 보장됩니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.8f),
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.3f
            )
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "소스코드 저장소: https://github.com/ParkJeongSoon001/JSPLAYER",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF94A3B8),
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun LicenseItemCard(lib: LibraryLicense, isTvMode: Boolean) {
    var isFocused by remember { mutableStateOf(false) }
    val accentColor = lib.licenseType.color

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isTvMode && isFocused) 2.dp else 0.dp,
                color = if (isTvMode && isFocused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable(),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.04f)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Accent bar
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(44.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accentColor)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = lib.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (lib.version != "-") {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = lib.version,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.4f)
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = lib.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(lib.licenseType.bgColor)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = lib.license,
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}
