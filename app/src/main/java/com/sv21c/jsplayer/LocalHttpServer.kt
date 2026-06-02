package com.sv21c.jsplayer

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * 로컬 HTTP 서버: 로컬 파일 및 네트워크 파일(SMB/FTP/SFTP/WebDAV)을
 * HTTP URL로 변환하여 DLNA 렌더러(스마트 TV)에 제공합니다.
 * 자막 파일도 HTTP로 제공합니다.
 */
class LocalHttpServer(
    private val context: Context,
    port: Int = 0 // 0이면 자동 할당
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "LocalHttpServer"
        private var instance: LocalHttpServer? = null

        fun getInstance(context: Context): LocalHttpServer {
            return instance ?: LocalHttpServer(context, 0).also {
                instance = it
            }
        }
    }

    // 등록된 스트리밍 소스 (token → StreamSource)
    private val streamSources = ConcurrentHashMap<String, StreamSource>()
    private var actualPort: Int = 0

    /**
     * 스트리밍 소스 정보
     * rangeStreamProvider: HTTP 기반 프로토콜(WebDAV)에서 Range 요청을 upstream 서버에 직접 전달할 때 사용
     */
    data class StreamSource(
        val token: String,
        val mimeType: String,
        val size: Long = -1L,
        val inputStreamProvider: () -> InputStream?,
        val rangeStreamProvider: ((start: Long, end: Long) -> InputStream?)? = null,
        val originalUrl: String = ""
    )

    override fun start() {
        super.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        actualPort = listeningPort
        Log.d(TAG, "로컬 HTTP 서버 시작됨: 포트 $actualPort")
    }

    fun getServerUrl(): String {
        val ip = getLocalIpAddress()
        return "http://$ip:$actualPort"
    }

    /**
     * 로컬 파일을 HTTP URL로 등록
     */
    fun registerLocalFile(filePath: String, mimeType: String? = null): String {
        val file = File(filePath)
        if (!file.exists()) {
            Log.e(TAG, "파일이 존재하지 않음: $filePath")
            return ""
        }

        val token = "local_${System.currentTimeMillis()}_${file.name.hashCode().toUInt()}"
        val detectedMime = mimeType ?: guessMimeType(filePath)

        streamSources[token] = StreamSource(
            token = token,
            mimeType = detectedMime,
            size = file.length(),
            inputStreamProvider = { FileInputStream(file) },
            originalUrl = filePath
        )

        val url = "${getServerUrl()}/stream/$token/${encodeFileName(file.name)}"
        Log.d(TAG, "로컬 파일 등록됨: $filePath → $url")
        return url
    }

    /**
     * Content URI를 HTTP URL로 등록
     */
    fun registerContentUri(contentUri: android.net.Uri, fileName: String, mimeType: String? = null): String {
        val token = "content_${System.currentTimeMillis()}_${contentUri.hashCode().toUInt()}"
        
        // 파일 크기 조회
        var fileSize = -1L
        try {
            context.contentResolver.query(contentUri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIdx >= 0) {
                        fileSize = cursor.getLong(sizeIdx)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Content URI 크기 조회 실패", e)
        }

        val detectedMime = mimeType ?: context.contentResolver.getType(contentUri) ?: "video/mp4"

        streamSources[token] = StreamSource(
            token = token,
            mimeType = detectedMime,
            size = fileSize,
            inputStreamProvider = { context.contentResolver.openInputStream(contentUri) },
            originalUrl = contentUri.toString()
        )

        val url = "${getServerUrl()}/stream/$token/${encodeFileName(fileName)}"
        Log.d(TAG, "Content URI 등록됨: $contentUri → $url")
        return url
    }

    /**
     * SMB 파일을 HTTP URL로 등록 (프록시)
     * 파일 크기를 사전 조회하여 DLNA TV의 Range 요청을 지원합니다.
     */
    fun registerSmbFile(smbUrl: String, userName: String, password: String, fileName: String): String {
        val token = "smb_${System.currentTimeMillis()}_${smbUrl.hashCode().toUInt()}"
        val detectedMime = guessMimeType(fileName)

        // SMB URL에서 인증정보를 제거한 클린 URL 생성 (smbContext에서 인증 처리)
        val cleanSmbUrl = try {
            val uri = java.net.URI(smbUrl)
            if (uri.userInfo != null) {
                java.net.URI(uri.scheme, null, uri.host, uri.port, uri.path, uri.query, uri.fragment).toString()
            } else smbUrl
        } catch (e: Exception) { smbUrl }

        // 파일 크기 사전 조회 (DLNA TV 호환성을 위해 필수)
        val fileSize = try {
            val smbContext = SmbManager.buildContext(userName, password)
            val smbFile = SmbManager.createSafeSmbFile(cleanSmbUrl, smbContext)
            val size = smbFile.length()
            Log.d(TAG, "SMB 파일 크기 조회 성공: $size bytes")
            size
        } catch (e: Exception) {
            Log.w(TAG, "SMB 파일 크기 조회 실패 (chunked 모드 사용): ${e.message}")
            -1L
        }

        streamSources[token] = StreamSource(
            token = token,
            mimeType = detectedMime,
            size = fileSize,
            inputStreamProvider = {
                try {
                    val smbContext = SmbManager.buildContext(userName, password)
                    val smbFile = SmbManager.createSafeSmbFile(cleanSmbUrl, smbContext)
                    smbFile.inputStream
                } catch (e: Exception) {
                    Log.e(TAG, "SMB 스트림 열기 실패: ${e.message}")
                    null
                }
            },
            originalUrl = smbUrl
        )

        val url = "${getServerUrl()}/stream/$token/${encodeFileName(fileName)}"
        Log.d(TAG, "SMB 파일 등록됨: $smbUrl → $url (크기: $fileSize bytes)")
        return url
    }

    /**
     * FTP 파일을 HTTP URL로 등록 (프록시)
     * 파일 크기를 사전 조회하여 DLNA TV의 Range 요청을 지원합니다.
     * 타임아웃을 설정하여 느린 서버에서의 무한 대기를 방지합니다.
     */
    fun registerFtpFile(ftpUrl: String, fileName: String): String {
        val token = "ftp_${System.currentTimeMillis()}_${ftpUrl.hashCode().toUInt()}"
        val detectedMime = guessMimeType(fileName)

        val uri = android.net.Uri.parse(ftpUrl)
        val host = uri.host ?: ""
        val port = if (uri.port > 0) uri.port else 21
        val userInfo = uri.userInfo
        val user = userInfo?.substringBefore(":") ?: "anonymous"
        val pass = userInfo?.substringAfter(":") ?: ""
        val path = uri.path ?: "/"

        // 파일 크기 사전 조회 (타임아웃 5초)
        val fileSize = try {
            val sizeClient = org.apache.commons.net.ftp.FTPClient().apply {
                connectTimeout = 5000
                defaultTimeout = 5000
            }
            sizeClient.connect(host, port)
            sizeClient.soTimeout = 5000
            sizeClient.login(user, pass)
            sizeClient.enterLocalPassiveMode()
            sizeClient.setFileType(org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE)

            // SIZE 명령으로 파일 크기 조회 (가장 빠른 방법)
            sizeClient.sendCommand("SIZE", path)
            val size = if (sizeClient.replyCode == 213) {
                sizeClient.replyString?.trim()?.substringAfter(" ")?.trim()?.toLongOrNull() ?: -1L
            } else {
                -1L  // SIZE 미지원 시 빠르게 포기 (LIST는 너무 느림)
            }

            try { sizeClient.logout() } catch (_: Exception) {}
            try { sizeClient.disconnect() } catch (_: Exception) {}
            Log.d(TAG, "FTP 파일 크기 조회 성공: $size bytes")
            size
        } catch (e: Exception) {
            Log.w(TAG, "FTP 파일 크기 조회 실패 (타임아웃/오류): ${e.message}")
            -1L
        }

        streamSources[token] = StreamSource(
            token = token,
            mimeType = detectedMime,
            size = fileSize,
            inputStreamProvider = {
                try {
                    val ftpClient = org.apache.commons.net.ftp.FTPClient().apply {
                        connectTimeout = 15000
                        defaultTimeout = 15000
                    }
                    ftpClient.connect(host, port)
                    ftpClient.soTimeout = 30000  // 스트리밍 시에는 여유있게
                    ftpClient.login(user, pass)
                    ftpClient.enterLocalPassiveMode()
                    ftpClient.setFileType(org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE)
                    ftpClient.retrieveFileStream(path)
                } catch (e: Exception) {
                    Log.e(TAG, "FTP 스트림 열기 실패: ${e.message}")
                    null
                }
            },
            originalUrl = ftpUrl
        )

        val url = "${getServerUrl()}/stream/$token/${encodeFileName(fileName)}"
        Log.d(TAG, "FTP 파일 등록됨: $ftpUrl → $url (크기: $fileSize bytes)")
        return url
    }

    /**
     * SFTP 파일을 HTTP URL로 등록 (프록시)
     * 파일 크기를 사전 조회하여 DLNA TV의 Range 요청을 지원합니다.
     * 타임아웃을 설정하여 느린 서버에서의 무한 대기를 방지합니다.
     */
    fun registerSftpFile(sftpUrl: String, fileName: String): String {
        val token = "sftp_${System.currentTimeMillis()}_${sftpUrl.hashCode().toUInt()}"
        val detectedMime = guessMimeType(fileName)

        val uri = android.net.Uri.parse(sftpUrl)
        val host = uri.host ?: ""
        val port = if (uri.port > 0) uri.port else 22
        val userInfo = uri.userInfo
        val user = userInfo?.substringBefore(":") ?: ""
        val pass = userInfo?.substringAfter(":") ?: ""
        val path = uri.path ?: "/"

        val fileSize = try {
            val ssh = net.schmizz.sshj.SSHClient(net.schmizz.sshj.AndroidConfig()).apply {
                connectTimeout = 5000
                timeout = 5000
            }
            ssh.addHostKeyVerifier(net.schmizz.sshj.transport.verification.PromiscuousVerifier())
            ssh.connect(host, port)
            ssh.authPassword(user, pass)
            val sftp = ssh.newSFTPClient()
            val attrs = sftp.stat(path)
            val size = attrs.size
            try { sftp.close() } catch (_: Exception) {}
            try { ssh.disconnect() } catch (_: Exception) {}
            Log.d(TAG, "SFTP 파일 크기 조회 성공: $size bytes")
            size
        } catch (e: Exception) {
            Log.w(TAG, "SFTP 파일 크기 조회 실패 (타임아웃/오류): ${e.message}")
            -1L
        }

        streamSources[token] = StreamSource(
            token = token,
            mimeType = detectedMime,
            size = fileSize,
            inputStreamProvider = {
                try {
                    val ssh = net.schmizz.sshj.SSHClient(net.schmizz.sshj.AndroidConfig()).apply {
                        connectTimeout = 15000
                        timeout = 30000
                    }
                    ssh.addHostKeyVerifier(net.schmizz.sshj.transport.verification.PromiscuousVerifier())
                    ssh.connect(host, port)
                    ssh.authPassword(user, pass)
                    val sftp = ssh.newSFTPClient()
                    val remoteFile = sftp.open(path)
                    remoteFile.RemoteFileInputStream()
                } catch (e: Exception) {
                    Log.e(TAG, "SFTP 스트림 열기 실패: ${e.message}")
                    null
                }
            },
            originalUrl = sftpUrl
        )

        val url = "${getServerUrl()}/stream/$token/${encodeFileName(fileName)}"
        Log.d(TAG, "SFTP 파일 등록됨: $sftpUrl → $url (크기: $fileSize bytes)")
        return url
    }

    /**
     * WebDAV 파일을 HTTP URL로 등록 (프록시)
     * OkHttp HEAD 요청으로 파일 크기를 빠르게 조회합니다.
     * 스트리밍은 Sardine 대신 OkHttp 직접 GET으로 처리하여 연결 속도를 개선합니다.
     */
    fun registerWebDavFile(webDavUrl: String, userName: String, password: String, fileName: String): String {
        val token = "webdav_${System.currentTimeMillis()}_${webDavUrl.hashCode().toUInt()}"
        val detectedMime = guessMimeType(fileName)

        // URL에서 인증정보(userInfo) 제거 → clean URL
        val cleanUrl = try {
            val parsed = java.net.URL(webDavUrl)
            if (parsed.userInfo != null) {
                val port = if (parsed.port != -1) ":${parsed.port}" else ""
                val query = if (parsed.query != null) "?${parsed.query}" else ""
                "${parsed.protocol}://${parsed.host}$port${parsed.path}$query"
            } else webDavUrl
        } catch (e: Exception) { webDavUrl }

        Log.d(TAG, "WebDAV clean URL 생성: $cleanUrl")

        // 인증 헤더 생성 (Basic Auth)
        val authHeader = if (userName.isNotBlank()) {
            okhttp3.Credentials.basic(userName, password)
        } else null

        // 파일 크기 조회: HEAD 요청 사용 (PROPFIND보다 훨씬 빠름)
        val fileSize = try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val requestBuilder = okhttp3.Request.Builder().url(cleanUrl).head()
            if (authHeader != null) requestBuilder.header("Authorization", authHeader)
            val response = client.newCall(requestBuilder.build()).execute()
            val size = response.header("Content-Length")?.toLongOrNull() ?: -1L
            response.close()
            Log.d(TAG, "WebDAV 파일 크기 조회 성공 (HEAD): $size bytes")
            size
        } catch (e: Exception) {
            Log.w(TAG, "WebDAV 파일 크기 조회 실패 (타임아웃/오류): ${e.message}")
            -1L
        }

        streamSources[token] = StreamSource(
            token = token,
            mimeType = detectedMime,
            size = fileSize,
            inputStreamProvider = {
                try {
                    Log.d(TAG, "🌐 WebDAV 스트림 연결 시작: $cleanUrl")
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)  // 스트리밍은 무제한
                        .build()
                    val requestBuilder = okhttp3.Request.Builder().url(cleanUrl).get()
                    if (authHeader != null) requestBuilder.header("Authorization", authHeader)
                    val response = client.newCall(requestBuilder.build()).execute()
                    if (response.isSuccessful) {
                        Log.d(TAG, "✅ WebDAV 스트림 연결 성공 (${response.code})")
                        response.body?.byteStream()
                    } else {
                        Log.e(TAG, "❌ WebDAV 스트림 응답 오류: ${response.code} ${response.message}")
                        response.close()
                        null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ WebDAV 스트림 열기 실패: ${e.message}")
                    null
                }
            },
            // Range 요청을 WebDAV 서버에 직접 전달 (skip 불필요)
            rangeStreamProvider = { start, end ->
                try {
                    Log.d(TAG, "🌐 WebDAV Range 스트림 연결: bytes=$start-$end")
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val requestBuilder = okhttp3.Request.Builder()
                        .url(cleanUrl)
                        .get()
                        .header("Range", "bytes=$start-$end")
                    if (authHeader != null) requestBuilder.header("Authorization", authHeader)
                    val response = client.newCall(requestBuilder.build()).execute()
                    if (response.isSuccessful || response.code == 206) {
                        Log.d(TAG, "✅ WebDAV Range 스트림 성공 (${response.code})")
                        response.body?.byteStream()
                    } else {
                        Log.e(TAG, "❌ WebDAV Range 스트림 오류: ${response.code}")
                        response.close()
                        null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ WebDAV Range 스트림 실패: ${e.message}")
                    null
                }
            },
            originalUrl = webDavUrl
        )

        val url = "${getServerUrl()}/stream/$token/${encodeFileName(fileName)}"
        Log.d(TAG, "WebDAV 파일 등록됨: $cleanUrl → $url (크기: $fileSize bytes)")
        return url
    }

    /**
     * 자막 파일을 HTTP URL로 등록
     */
    fun registerSubtitleFile(subtitlePath: String): String {
        val file = File(subtitlePath.removePrefix("file://"))
        if (!file.exists()) {
            Log.e(TAG, "자막 파일이 존재하지 않음: $subtitlePath")
            return ""
        }

        val token = "sub_${System.currentTimeMillis()}_${file.name.hashCode().toUInt()}"
        val mimeType = guessSubtitleMimeType(file.name)

        streamSources[token] = StreamSource(
            token = token,
            mimeType = mimeType,
            size = file.length(),
            inputStreamProvider = { FileInputStream(file) },
            originalUrl = subtitlePath
        )

        val url = "${getServerUrl()}/stream/$token/${encodeFileName(file.name)}"
        Log.d(TAG, "자막 파일 등록됨: $subtitlePath → $url")
        return url
    }

    /**
     * HTTP URL (이미 접근 가능한 URL)인 경우 그대로 반환
     */
    fun getStreamableUrl(videoUrl: String, subtitleUrl: String?, credentials: ServerCredentials?): Pair<String, String?> {
        val safeVideoUrl = getSafeEncodedUrl(videoUrl)
        val safeSubtitleUrl = subtitleUrl?.let { getSafeEncodedUrl(it) }

        val parsedUri = android.net.Uri.parse(safeVideoUrl)
        val scheme = parsedUri.scheme?.lowercase() ?: ""
        val fileName = parsedUri.lastPathSegment ?: "video"

        Log.d(TAG, "━━━ getStreamableUrl ━━━")
        Log.d(TAG, "videoUrl: $safeVideoUrl")
        Log.d(TAG, "scheme: $scheme, credentials: ${if (credentials != null) "있음(user=${credentials.username})" else "없음"}")

        val streamVideoUrl = when {
            // WebDAV: HTTP URL이지만 인증이 필요한 경우 → 프록시를 통해 전달
            scheme.startsWith("http") && credentials != null -> {
                Log.d(TAG, "WebDAV URL 감지 → 프록시를 통해 전달")
                registerWebDavFile(safeVideoUrl, credentials.username, credentials.password, fileName)
            }
            // 일반 HTTP URL (DLNA 등) → 그대로 사용
            scheme.startsWith("http") -> {
                Log.d(TAG, "일반 HTTP URL → 그대로 사용")
                safeVideoUrl
            }
            scheme == "content" -> registerContentUri(parsedUri, fileName)
            scheme == "file" -> {
                val path = parsedUri.path ?: ""
                registerLocalFile(path)
            }
            scheme == "smb" -> {
                val user = credentials?.username ?: ""
                val pass = credentials?.password ?: ""
                registerSmbFile(safeVideoUrl, user, pass, fileName)
            }
            scheme == "ftp" -> {
                registerFtpFile(safeVideoUrl, fileName)
            }
            scheme == "sftp" -> {
                registerSftpFile(safeVideoUrl, fileName)
            }
            else -> {
                // content:// 또는 로컬 파일 경로
                if (safeVideoUrl.startsWith("/")) {
                    registerLocalFile(safeVideoUrl)
                } else {
                    registerContentUri(android.net.Uri.parse(safeVideoUrl), fileName)
                }
            }
        }

        Log.d(TAG, "변환 결과: $streamVideoUrl")

        // 자막 URL 처리
        val streamSubtitleUrl = if (safeSubtitleUrl != null) {
            val subParsedUri = android.net.Uri.parse(safeSubtitleUrl)
            val subScheme = subParsedUri.scheme?.lowercase() ?: ""
            when {
                // WebDAV 자막도 프록시 필요
                subScheme.startsWith("http") && credentials != null -> {
                    val subFileName = subParsedUri.lastPathSegment ?: "subtitle.srt"
                    registerWebDavFile(safeSubtitleUrl, credentials.username, credentials.password, subFileName)
                }
                subScheme.startsWith("http") -> safeSubtitleUrl
                subScheme == "content" -> {
                    val subFileName = subParsedUri.lastPathSegment ?: "subtitle.srt"
                    registerContentUri(subParsedUri, subFileName)
                }
                subScheme == "file" || safeSubtitleUrl.startsWith("/") -> {
                    val path = safeSubtitleUrl.removePrefix("file://")
                    registerSubtitleFile(path)
                }
                subScheme == "smb" -> {
                    val user = credentials?.username ?: ""
                    val pass = credentials?.password ?: ""
                    val subFileName = subParsedUri.lastPathSegment ?: "subtitle.srt"
                    registerSmbFile(safeSubtitleUrl, user, pass, subFileName)
                }
                subScheme == "ftp" -> {
                    val subFileName = subParsedUri.lastPathSegment ?: "subtitle.srt"
                    registerFtpFile(safeSubtitleUrl, subFileName)
                }
                subScheme == "sftp" -> {
                    val subFileName = subParsedUri.lastPathSegment ?: "subtitle.srt"
                    registerSftpFile(safeSubtitleUrl, subFileName)
                }
                else -> safeSubtitleUrl
            }
        } else null

        return Pair(streamVideoUrl, streamSubtitleUrl)
    }

    /**
     * 등록된 스트리밍 소스 정리
     */
    fun clearSources() {
        streamSources.clear()
        Log.d(TAG, "모든 스트리밍 소스 제거됨")
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val rangeHeader = session.headers["range"]
        Log.d(TAG, "📥 HTTP 요청: ${session.method} $uri (from: ${session.headers["remote-addr"] ?: session.headers["http-client-ip"] ?: "unknown"}, Range: ${rangeHeader ?: "전체"})")

        // /stream/{token}/{filename} 패턴
        if (uri.startsWith("/stream/")) {
            val parts = uri.removePrefix("/stream/").split("/", limit = 2)
            val token = parts.firstOrNull() ?: ""
            val source = streamSources[token]

            if (source == null) {
                Log.e(TAG, "❌ 토큰을 찾을 수 없음: $token (등록된 소스: ${streamSources.keys})")
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            }

            Log.d(TAG, "✅ 스트림 소스 찾음: token=$token, mime=${source.mimeType}, size=${source.size}, original=${source.originalUrl.take(80)}")

            // 자막 파일 요청인지 확인
            if (token.startsWith("sub_")) {
                Log.d(TAG, "🎯 자막 파일 요청 수신! token=$token, mime=${source.mimeType}, size=${source.size}")
            }

            // HEAD 요청 처리 (TV가 파일 정보를 먼저 확인)
            if (session.method == Method.HEAD) {
                Log.d(TAG, "📋 HEAD 요청 처리 (파일 정보 확인)")
                val response = newFixedLengthResponse(Response.Status.OK, source.mimeType, "")
                if (source.size > 0) {
                    response.addHeader("Content-Length", source.size.toString())
                }
                response.addHeader("Accept-Ranges", "bytes")
                response.addHeader("transferMode.dlna.org", "Streaming")
                response.addHeader("contentFeatures.dlna.org", "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000")
                return response
            }

            return serveStream(session, source)
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
    }

    private fun serveStream(session: IHTTPSession, source: StreamSource): Response {
        try {
            val rangeHeader = session.headers["range"]

            // Range 요청이고 rangeStreamProvider가 있으면 직접 Range 스트림 사용 (WebDAV 최적화)
            if (rangeHeader != null && source.size > 0 && source.rangeStreamProvider != null) {
                Log.d(TAG, "🎬 Range 스트림 (upstream 직접 전달): $rangeHeader")
                return serveRangeWithProvider(source, rangeHeader)
            }

            Log.d(TAG, "🎬 스트림 시작: mime=${source.mimeType}, size=${source.size}")
            val inputStream = source.inputStreamProvider()
            if (inputStream == null) {
                Log.e(TAG, "❌ 스트림을 열 수 없습니다: ${source.originalUrl}")
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "스트림을 열 수 없습니다")
            }

            if (rangeHeader != null && source.size > 0) {
                Log.d(TAG, "📐 Range 요청 처리 (skip 방식): $rangeHeader")
                return serveRangeRequest(inputStream, source, rangeHeader)
            }

            // 전체 파일 전송
            val response = if (source.size > 0) {
                Log.d(TAG, "📦 전체 파일 전송 (Content-Length: ${source.size})")
                newFixedLengthResponse(Response.Status.OK, source.mimeType, inputStream, source.size)
            } else {
                Log.d(TAG, "📦 전체 파일 전송 (chunked 모드 - 크기 불명)")
                newChunkedResponse(Response.Status.OK, source.mimeType, inputStream)
            }
            // DLNA TV 호환 헤더 추가
            response.addHeader("Accept-Ranges", "bytes")
            response.addHeader("transferMode.dlna.org", "Streaming")
            response.addHeader("contentFeatures.dlna.org", "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000")
            return response
        } catch (e: Exception) {
            Log.e(TAG, "❌ 스트리밍 오류: ${e.message}", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    /**
     * Range Request 처리 (TV에서의 탐색 기능 지원)
     */
    private fun serveRangeRequest(inputStream: InputStream, source: StreamSource, rangeHeader: String): Response {
        try {
            val rangeSpec = rangeHeader.replace("bytes=", "").trim()
            val rangeParts = rangeSpec.split("-")
            val start = rangeParts[0].toLongOrNull() ?: 0L
            val end = if (rangeParts.size > 1 && rangeParts[1].isNotEmpty()) {
                rangeParts[1].toLongOrNull() ?: (source.size - 1)
            } else {
                source.size - 1
            }

            val contentLength = end - start + 1

            // skip을 안정적으로 수행 (네트워크 스트림에서는 한 번에 전체를 skip하지 못할 수 있음)
            if (start > 0) {
                var remaining = start
                while (remaining > 0) {
                    val skipped = inputStream.skip(remaining)
                    if (skipped <= 0) {
                        // skip 실패 시 read로 대체
                        val buf = ByteArray(minOf(remaining, 8192L).toInt())
                        val read = inputStream.read(buf)
                        if (read <= 0) break  // EOF
                        remaining -= read
                    } else {
                        remaining -= skipped
                    }
                }
            }

            Log.d(TAG, "📐 Range 응답: bytes $start-$end/${source.size} (전송: $contentLength bytes)")

            val response = newFixedLengthResponse(
                Response.Status.PARTIAL_CONTENT,
                source.mimeType,
                inputStream,
                contentLength
            )
            response.addHeader("Content-Range", "bytes $start-$end/${source.size}")
            response.addHeader("Accept-Ranges", "bytes")
            response.addHeader("Content-Length", contentLength.toString())
            response.addHeader("transferMode.dlna.org", "Streaming")
            response.addHeader("contentFeatures.dlna.org", "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000")

            return response
        } catch (e: Exception) {
            Log.e(TAG, "❌ Range 요청 처리 오류: ${e.message}", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Range error")
        }
    }

    /**
     * rangeStreamProvider를 사용한 Range 요청 처리 (WebDAV 최적화)
     * upstream 서버에 Range 헤더를 직접 전달하여 skip 없이 원하는 위치부터 스트리밍
     */
    private fun serveRangeWithProvider(source: StreamSource, rangeHeader: String): Response {
        try {
            val rangeSpec = rangeHeader.replace("bytes=", "").trim()
            val rangeParts = rangeSpec.split("-")
            val start = rangeParts[0].toLongOrNull() ?: 0L
            val end = if (rangeParts.size > 1 && rangeParts[1].isNotEmpty()) {
                rangeParts[1].toLongOrNull() ?: (source.size - 1)
            } else {
                source.size - 1
            }

            val contentLength = end - start + 1

            val inputStream = source.rangeStreamProvider?.invoke(start, end)
            if (inputStream == null) {
                Log.e(TAG, "❌ Range 스트림을 열 수 없습니다, skip 방식으로 fallback")
                // fallback: 전체 스트림 열고 skip
                val fallbackStream = source.inputStreamProvider()
                    ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "스트림을 열 수 없습니다")
                return serveRangeRequest(fallbackStream, source, rangeHeader)
            }

            Log.d(TAG, "📐 Range 응답 (upstream): bytes $start-$end/${source.size} (전송: $contentLength bytes)")

            val response = newFixedLengthResponse(
                Response.Status.PARTIAL_CONTENT,
                source.mimeType,
                inputStream,
                contentLength
            )
            response.addHeader("Content-Range", "bytes $start-$end/${source.size}")
            response.addHeader("Accept-Ranges", "bytes")
            response.addHeader("Content-Length", contentLength.toString())
            response.addHeader("transferMode.dlna.org", "Streaming")
            response.addHeader("contentFeatures.dlna.org", "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000")

            return response
        } catch (e: Exception) {
            Log.e(TAG, "❌ Range(provider) 요청 처리 오류: ${e.message}", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Range error")
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (!intf.isUp || intf.isLoopback) continue
                // Wi-Fi 인터페이스 우선
                if (intf.name.startsWith("wlan") || intf.name.startsWith("eth")) {
                    val addresses = intf.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            return addr.hostAddress ?: "127.0.0.1"
                        }
                    }
                }
            }
            // Fallback: 아무 비-루프백 IPv4 인터페이스
            val allInterfaces = NetworkInterface.getNetworkInterfaces()
            while (allInterfaces.hasMoreElements()) {
                val intf = allInterfaces.nextElement()
                if (!intf.isUp || intf.isLoopback) continue
                val addresses = intf.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "IP 주소 조회 실패", e)
        }
        return "127.0.0.1"
    }

    private fun guessMimeType(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".mp4") || lower.endsWith(".m4v") -> "video/mp4"
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".avi") -> "video/x-msvideo"
            lower.endsWith(".mov") -> "video/quicktime"
            lower.endsWith(".wmv") -> "video/x-ms-wmv"
            lower.endsWith(".flv") -> "video/x-flv"
            lower.endsWith(".ts") -> "video/mp2t"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".3gp") -> "video/3gpp"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".flac") -> "audio/flac"
            lower.endsWith(".wav") -> "audio/wav"
            else -> "video/mp4"
        }
    }

    private fun guessSubtitleMimeType(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".srt") -> "text/srt"
            lower.endsWith(".ass") || lower.endsWith(".ssa") -> "text/x-ssa"
            lower.endsWith(".vtt") -> "text/vtt"
            lower.endsWith(".smi") -> "application/x-sami"
            else -> "text/plain"
        }
    }

    private fun encodeFileName(name: String): String {
        return try {
            java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        } catch (e: Exception) {
            name
        }
    }

    private fun getSafeEncodedUrl(url: String): String {
        try {
            val schemeIndex = url.indexOf("://")
            if (schemeIndex == -1) {
                return url
            }
            val scheme = url.substring(0, schemeIndex)
            val remainder = url.substring(schemeIndex + 3)

            var endOfAuthority = remainder.length
            for (i in 0 until remainder.length) {
                val c = remainder[i]
                if (c == '/' || c == '?' || c == '#') {
                    endOfAuthority = i
                    break
                }
            }
            val authority = remainder.substring(0, endOfAuthority)
            val rest = remainder.substring(endOfAuthority)

            val atIndex = authority.lastIndexOf('@')
            val userInfoStr: String
            val hostPort: String
            if (atIndex != -1) {
                val rawUserInfo = authority.substring(0, atIndex)
                hostPort = authority.substring(atIndex + 1)

                val colonIndex = rawUserInfo.indexOf(':')
                userInfoStr = if (colonIndex != -1) {
                    val user = rawUserInfo.substring(0, colonIndex)
                    val pass = rawUserInfo.substring(colonIndex + 1)
                    val decUser = android.net.Uri.decode(user)
                    val decPass = android.net.Uri.decode(pass)
                    val encUser = android.net.Uri.encode(decUser)
                    val encPass = android.net.Uri.encode(decPass)
                    "$encUser:$encPass@"
                } else {
                    val decUser = android.net.Uri.decode(rawUserInfo)
                    val encUser = android.net.Uri.encode(decUser)
                    "$encUser@"
                }
            } else {
                userInfoStr = ""
                hostPort = authority
            }

            var path = ""
            var query = ""
            var fragment = ""

            var tempRest = rest
            val hashIndex = tempRest.indexOf('#')
            if (hashIndex != -1) {
                fragment = tempRest.substring(hashIndex)
                tempRest = tempRest.substring(0, hashIndex)
            }

            val questionIndex = tempRest.indexOf('?')
            if (questionIndex != -1) {
                query = tempRest.substring(questionIndex)
                path = tempRest.substring(0, questionIndex)
            } else {
                path = tempRest
            }

            val encodedPath = if (path.isNotEmpty()) {
                val decodedPath = android.net.Uri.decode(path)
                val segments = decodedPath.split("/")
                segments.joinToString("/") { segment ->
                    android.net.Uri.encode(segment)
                }
            } else {
                ""
            }

            val encodedQuery = if (query.length > 1) {
                val queryContent = query.substring(1)
                "?" + queryContent.split("&").joinToString("&") { pair ->
                    val eqIndex = pair.indexOf('=')
                    if (eqIndex != -1) {
                        val key = pair.substring(0, eqIndex)
                        val value = pair.substring(eqIndex + 1)
                        val decKey = android.net.Uri.decode(key)
                        val decValue = android.net.Uri.decode(value)
                        "${android.net.Uri.encode(decKey)}=${android.net.Uri.encode(decValue)}"
                    } else {
                        val decPair = android.net.Uri.decode(pair)
                        android.net.Uri.encode(decPair)
                    }
                }
            } else {
                query
            }

            val encodedFragment = if (fragment.length > 1) {
                val fragContent = fragment.substring(1)
                "#" + android.net.Uri.encode(android.net.Uri.decode(fragContent))
            } else {
                fragment
            }

            return "$scheme://$userInfoStr$hostPort$encodedPath$encodedQuery$encodedFragment"
        } catch (e: Exception) {
            return url
        }
    }
}
