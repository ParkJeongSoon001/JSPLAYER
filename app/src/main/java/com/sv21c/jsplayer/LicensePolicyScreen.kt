package com.sv21c.jsplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensePolicyScreen(
    isTvMode: Boolean,
    onBackClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0C1122), Color(0xFF05070F))))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("오픈소스 라이선스 정책", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.4f)
                )
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                item {
                    AppLicenseCard(isTvMode)
                }
                
                item {
                    Text(
                        text = "사용된 오픈소스 라이브러리",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                    )
                }

                val libraries = listOf(
                    LibraryLicense(
                        name = "jellyfin-media3-ffmpeg-decoder",
                        description = "FFmpeg 비디오 디코딩 어댑터",
                        license = "GPL-3.0 (GNU General Public License v3.0)",
                        url = "https://github.com/jellyfin/jellyfin-media3",
                        accentColor = Color(0xFFFF6B6B)
                    ),
                    LibraryLicense(
                        name = "jCIFS-NG",
                        description = "SMB 네트워크 프로토콜 클라이언트",
                        license = "LGPL-2.1 (GNU Lesser General Public License v2.1)",
                        url = "https://github.com/Agno3/jcifs-ng",
                        accentColor = Color(0xFFFFB347)
                    ),
                    LibraryLicense(
                        name = "jUPnP",
                        description = "DLNA/UPnP 미디어 서버 디스커버리",
                        license = "CDDL-1.0 (Common Development and Distribution License)",
                        url = "https://github.com/jupnp/jupnp",
                        accentColor = Color(0xFF63B3ED)
                    ),
                    LibraryLicense(
                        name = "Sardine-Android",
                        description = "WebDAV 네트워크 프로토콜 클라이언트",
                        license = "Apache License 2.0",
                        url = "https://github.com/thegrizzlylabs/sardine-android",
                        accentColor = Color(0xFF34D399)
                    ),
                    LibraryLicense(
                        name = "AndroidX (Core, Compose, Media3)",
                        description = "안드로이드 기본 UI 및 미디어 프레임워크",
                        license = "Apache License 2.0",
                        url = "https://developer.android.com/jetpack",
                        accentColor = Color(0xFF34D399)
                    ),
                    LibraryLicense(
                        name = "OkHttp, SLF4J, Jetty, Jakarta XML",
                        description = "네트워크, 서버, 로깅 유틸리티",
                        license = "Apache 2.0, MIT, EPL 1.0/2.0",
                        url = "-",
                        accentColor = Color(0xFF34D399)
                    )
                )

                items(libraries.size) { index ->
                    LicenseItemCard(libraries[index], isTvMode)
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
                    text = "JS PLAYER 라이선스 고지문 (GPL-3.0)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "본 애플리케이션은 FFmpeg 디코더(GPL-3.0)를 포함하고 있으므로, 앱의 전체 소스코드는 본연의 라이선스 정책에 따라 [GNU General Public License v3.0] 하에 공개(오픈소스)됩니다.\n\n앱 사용자는 자유롭게 앱을 실행, 복제, 배포할 수 있으며 소스코드를 열람 및 수정할 권리가 보장됩니다. 상업적 이용 및 개작/파생 앱 개발 시 동일한 GPL-3.0 라이선스가 적용되어야 합니다.",
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

data class LibraryLicense(
    val name: String,
    val description: String,
    val license: String,
    val url: String,
    val accentColor: Color
)

@Composable
fun LicenseItemCard(lib: LibraryLicense, isTvMode: Boolean) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isTvMode && isFocused) 2.dp else 1.dp,
                color = if (isTvMode && isFocused) Color.White else lib.accentColor.copy(alpha = 0.3f),
                shape = RoundedCornerShape(8.dp)
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable(),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = lib.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = lib.description,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(lib.accentColor.copy(alpha = 0.2f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = lib.license,
                        style = MaterialTheme.typography.labelSmall,
                        color = lib.accentColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
