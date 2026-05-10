package com.sv21c.jsplayer

import android.util.Log
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPSClient
import org.apache.commons.net.ftp.FTPReply

data class FtpItem(
    val name: String,
    val path: String,       // 서버 내 전체 경로 (/share/movie/test.mp4)
    val isDirectory: Boolean,
    val size: Long = 0L,
    val lastModified: Long = 0L
)


/**
 * FTP 서버 파일 목록 조회 매니저.
 * - Passive 모드 사용 (NAT 환경 호환)
 * - Binary 전송 모드
 * - 인코딩 자동 감지 (UTF-8 / EUC-KR)
 * - FTPS: PROT C(Clear data) 우선 시도, 실패 시 PROT P(Private) 폴백 (ASUS 라우터 호환)
 */
object FtpManager {
    private const val TAG = "FtpManager"

    /**
     * FTP 경로의 파일/폴더 목록 반환.
     * encoding이 "AUTO"이면 자동 감지, 그렇지 않으면 지정된 인코딩 사용.
     */
    fun listFiles(
        host: String,
        port: Int = 21,
        username: String,
        password: String,
        remotePath: String,
        encoding: String = "AUTO",
        isFtps: Boolean = false
    ): Result<List<FtpItem>> {
        return try {
            if (encoding == "AUTO") {
                listFilesAutoDetect(host, port, username, password, remotePath, isFtps)
            } else {
                listFilesWithEncoding(host, port, username, password, remotePath, encoding, isFtps)
            }
        } catch (e: Exception) {
            Log.e(TAG, "listFiles failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * 자동 인코딩 감지로 파일 목록 조회.
     * 1단계: setAutodetectUTF8(true) → FEAT 응답으로 UTF-8 자동 감지
     * 2단계: 파일명에 깨진 문자가 있으면 EUC-KR로 재접속
     */
    private val acceptAllTrustManager = object : javax.net.ssl.X509TrustManager {
        override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
    }

    /**
     * TLS 세션 재사용을 지원하는 커스텀 FTPSClient.
     * ASUS 라우터 등 "session reuse required" 오류를 일으키는 서버 대응.
     *
     * 핵심 전략:
     * 1. execPROT 후 소켓 팩토리를 plain 으로 리셋
     *    → FTPClient가 데이터 연결 시 plain TCP 소켓 생성
     * 2. _openDataConnection_ 에서 plain 소켓을 받아
     *    컨트롤 연결의 host:port 로 SSL 래핑
     *    → Conscrypt 세션 캐시에서 자동으로 세션 재사용
     *
     * 리플렉션 사용 안 함 (StackOverflow 방지)
     */
    private class SessionReuseFTPSClient(
        tmgr: javax.net.ssl.X509TrustManager,
        private val sslCtx: javax.net.ssl.SSLContext
    ) : FTPSClient(false, sslCtx) {

        private val plainFactory: javax.net.SocketFactory = javax.net.SocketFactory.getDefault()

        init {
            trustManager = tmgr
            isEndpointCheckingEnabled = false
        }

        /**
         * PROT P 실행 후, FTPSClient가 _socketFactory_ 를 SSLSocketFactory로 설정하는 것을
         * plain SocketFactory 로 되돌림.
         * → 이후 FTPClient._openDataConnection_ 이 plain TCP 소켓을 생성하게 됨.
         */
        override fun execPROT(prot: String) {
            super.execPROT(prot)
            // PROT P 실행 후 socketFactory를 plain으로 리셋
            // → 데이터 연결 시 plain TCP 소켓 생성
            setSocketFactory(plainFactory)
            Log.d(TAG, "execPROT: socketFactory reset to plain for manual SSL wrapping")
        }

        override fun _prepareDataSocket_(socket: java.net.Socket?) {
            // no-op: SSL 래핑은 _openDataConnection_ 에서 직접 처리
        }

        @Throws(java.io.IOException::class)
        override fun _openDataConnection_(command: String?, arg: String?): java.net.Socket? {
            // super 호출 체인:
            //   SessionReuseFTPSClient._openDataConnection_ → super (FTPSClient)
            //   FTPSClient._openDataConnection_ → super (FTPClient)
            //   FTPClient: plainFactory.createSocket() → plain TCP 소켓 반환
            //   FTPSClient: instanceof SSLSocket? → false → 핸드셰이크 스킵
            // 결과: plain TCP 소켓이 우리에게 반환됨
            val socket = super._openDataConnection_(command, arg) ?: return null

            // 이미 SSL인 경우 그대로 반환 (방어 코드)
            if (socket is javax.net.ssl.SSLSocket) return socket

            val controlSocket = _socket_ as? javax.net.ssl.SSLSocket
            if (controlSocket == null) return socket

            // 컨트롤 연결의 세션 캐시 키(peerHost, peerPort)를 정확히 가져옴
            val peerHost = controlSocket.session.peerHost ?: controlSocket.inetAddress.hostAddress
            val peerPort = controlSocket.session.peerPort.takeIf { it > 0 } ?: controlSocket.port

            Log.d(TAG, "Data SSL: attempting reuse for $peerHost:$peerPort (control_id=${controlSocket.session.id.take(4).joinToString("")})")
            
            // 캐시된 세션 확인
            try {
                val cache = sslCtx.clientSessionContext
                val ids = cache.ids
                val cachedIds = mutableListOf<String>()
                while(ids.hasMoreElements()) {
                    cachedIds.add(ids.nextElement().take(4).joinToString(""))
                }
                Log.d(TAG, "Data SSL: cached session IDs: $cachedIds")
            } catch (e: Exception) {
                Log.d(TAG, "Data SSL: failed to read session cache")
            }

            // 컨트롤 연결의 host:port 로 SSL 래핑
            // → 동일 SSLContext 세션 캐시 키 매칭 → 세션 재사용
            return try {
                val sslSocket = sslCtx.socketFactory.createSocket(
                    socket,
                    peerHost,
                    peerPort,
                    true
                ) as javax.net.ssl.SSLSocket

                sslSocket.useClientMode = true
                sslSocket.enabledProtocols = controlSocket.enabledProtocols

                // Android Q 이상인 경우 SSLSockets.setUseSessionTickets 시도
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        if (android.net.ssl.SSLSockets.isSupportedSocket(sslSocket)) {
                            android.net.ssl.SSLSockets.setUseSessionTickets(sslSocket, true)
                        }
                    }
                } catch (e: Exception) {
                    // Ignore
                }

                sslSocket.startHandshake()
                
                val reused = sslSocket.session.id.contentEquals(controlSocket.session.id)
                val cId = controlSocket.session.id.take(4).joinToString("")
                val dId = sslSocket.session.id.take(4).joinToString("")
                Log.d(TAG, "Data SSL: session reuse=${reused} (control=$cId, data=$dId)")
                
                sslSocket
            } catch (e: Exception) {
                Log.w(TAG, "Data SSL wrapping failed: ${e.message}")
                socket.close()
                null
            }
        }
    }

    private fun createClient(isFtps: Boolean): FTPClient {
        return if (isFtps) {
            val sslCtx = javax.net.ssl.SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(acceptAllTrustManager), java.security.SecureRandom())
            }
            SessionReuseFTPSClient(acceptAllTrustManager, sslCtx).apply {
                isRemoteVerificationEnabled = false
                enabledProtocols = arrayOf("TLSv1.2")
            }
        } else {
            FTPClient().apply {
                isRemoteVerificationEnabled = false
            }
        }
    }

    private fun listFilesAutoDetect(
        host: String,
        port: Int,
        username: String,
        password: String,
        remotePath: String,
        isFtps: Boolean
    ): Result<List<FtpItem>> {
        val client: FTPClient = createClient(isFtps)
        try {
            // ① connect 전에 UTF-8 기본 적용 (setAutodetectUTF8 사용 시 일부 ASUS 라우터에서 login 전 SYST 전송으로 연결이 끊어지는 문제 방지)
            client.isStrictReplyParsing = false // 비표준 응답(공백 누락 등) 허용 (ASUS 호환성)
            client.controlEncoding = "UTF-8"
            client.connectTimeout = 15000
            client.defaultTimeout = 15000
            Log.d(TAG, "Connecting to $host:$port (FTPS=$isFtps)")
            client.connect(host, port)
            client.soTimeout = 15000 // 소켓 읽기 타임아웃

            val reply = client.replyCode
            Log.d(TAG, "Connect reply: $reply")
            if (!FTPReply.isPositiveCompletion(reply)) {
                client.disconnect()
                return Result.failure(Exception("FTP 연결 거부: 코드 $reply"))
            }

            val loginUser = username.ifBlank { "anonymous" }
            val loginPass = if (username.isBlank()) "anonymous@" else password
            Log.d(TAG, "Logging in as: $loginUser")
            if (!client.login(loginUser, loginPass)) {
                client.logout()
                client.disconnect()
                return Result.failure(Exception("FTP 로그인 실패: 사용자명/비밀번호를 확인하세요."))
            }
            Log.d(TAG, "Login successful")

            if (isFtps && client is FTPSClient) {
                Log.d(TAG, "Executing PBSZ/PROT for FTPS")
                client.execPBSZ(0)
                // PROT P = 데이터 채널 암호화 (서버가 요구할 수 있음)
                // ASUS 라우터: PROT C를 200으로 수락하지만 실제 데이터 전송 시
                // "522 Data connections must be encrypted"로 거부하므로 PROT P 우선
                client.execPROT("P")
                val protReply = client.replyCode
                Log.d(TAG, "PROT P reply: $protReply - ${client.replyString.trim()}")
                if (!FTPReply.isPositiveCompletion(protReply)) {
                    Log.d(TAG, "PROT P rejected, trying PROT C")
                    client.execPROT("C")
                    Log.d(TAG, "PROT C reply: ${client.replyCode} - ${client.replyString.trim()}")
                }
            }

            Log.d(TAG, "Entering passive mode")
            client.enterLocalPassiveMode()
            Log.d(TAG, "Passive mode set, setting binary type")
            client.setFileType(FTP.BINARY_FILE_TYPE)

            // ② 파일 목록 1차 조회
            val path = if (remotePath.isBlank()) "/" else remotePath
            Log.d(TAG, "Changing directory to: $path")
            if (!client.changeWorkingDirectory(path)) {
                Log.w(TAG, "CWD failed, reply: ${client.replyCode} - ${client.replyString}")
                if (path != "/" && path.any { it.code > 127 }) {
                    client.logout()
                    client.disconnect()
                    Log.i(TAG, "UTF-8 폴더 접근 실패 → EUC-KR로 재접속 시도")
                    return listFilesWithEncoding(host, port, username, password, remotePath, "EUC-KR", isFtps)
                }
                client.logout()
                client.disconnect()
                return Result.failure(Exception("폴더를 찾을 수 없습니다: $path"))
            }
            Log.d(TAG, "CWD successful, listing files...")
            var files = fetchFileList(client)

            // FTPS 데이터 연결 실패 시 (TLS 세션 재사용 문제 등) → 평문 FTP로 폴백
            if ((files == null || files.isEmpty()) && isFtps) {
                val lastReply = try { client.replyString?.trim() ?: "" } catch (_: Exception) { "" }
                Log.w(TAG, "FTPS data channel produced no results (reply=$lastReply) → retrying with plain FTP")
                try { client.logout() } catch (_: Exception) {}
                try { client.disconnect() } catch (_: Exception) {}
                return listFilesAutoDetect(host, port, username, password, remotePath, false)
            }

            val items = files?.mapNotNull { file ->
                if (file.name == "." || file.name == "..") return@mapNotNull null
                try {
                    FtpItem(
                        name = file.name,
                        path = if (path.endsWith("/")) "$path${file.name}" else "$path/${file.name}",
                        isDirectory = file.isDirectory,
                        size = file.size,
                        lastModified = file.timestamp?.timeInMillis ?: 0L
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping file ${file.name}: ${e.message}")
                    null
                }
            }?.sortedWith(compareByDescending<FtpItem> { it.isDirectory }.thenBy { it.name })
                ?: emptyList()

            Log.d(TAG, "Parsed ${items.size} items (after filtering . and ..)")
            client.logout()
            client.disconnect()

            // ③ 파일명 인코딩 검증 - 깨진 한글이 있으면 EUC-KR로 재시도
            val hasGarbled = items.any { hasGarbledNonAscii(it.name) }
            if (hasGarbled) {
                Log.i(TAG, "파일명 깨짐 감지 → EUC-KR로 재접속 시도")
                return listFilesWithEncoding(host, port, username, password, remotePath, "EUC-KR", isFtps)
            }

            return Result.success(items)
        } catch (e: Exception) {
            Log.e(TAG, "listFilesAutoDetect failed: ${e.message}")
            try { client.disconnect() } catch (_: Exception) {}
            // FTPS 연결에서 예외 발생 시 평문 FTP로 폴백 시도
            if (isFtps) {
                Log.w(TAG, "FTPS connection exception → retrying with plain FTP")
                return try {
                    listFilesAutoDetect(host, port, username, password, remotePath, false)
                } catch (e2: Exception) {
                    Log.e(TAG, "Plain FTP fallback also failed: ${e2.message}")
                    Result.failure(e)  // 원래 FTPS 에러를 반환
                }
            }
            return Result.failure(e)
        }
    }

    /**
     * MLSD → LIST → listNames 순으로 파일 목록 조회 시도.
     * 서버 호환성에 따라 가장 먼저 성공한 방법의 결과를 반환.
     */
    private fun fetchFileList(client: FTPClient): Array<org.apache.commons.net.ftp.FTPFile>? {
        // 1. MLSD (기계 파싱 형식) 시도
        try {
            val mlsdFiles = client.mlistDir()
            Log.d(TAG, "MLSD returned ${mlsdFiles?.size ?: "null"} entries, reply: ${client.replyCode}")
            if (mlsdFiles != null && mlsdFiles.isNotEmpty()) return mlsdFiles
        } catch (e: Exception) {
            Log.d(TAG, "MLSD not supported: ${e.message}")
        }
        // 2. LIST (표준 디렉토리 리스팅) 시도
        try {
            val listFiles = client.listFiles()
            Log.d(TAG, "LIST returned ${listFiles?.size ?: "null"} entries, reply: ${client.replyCode} - ${client.replyString?.trim()}")
            if (listFiles != null && listFiles.isNotEmpty()) return listFiles
        } catch (e: Exception) {
            Log.d(TAG, "LIST failed: ${e.message}")
        }
        // 3. NLST (파일명만) 폴백 → FTPFile 배열로 변환
        try {
            val names = client.listNames()
            Log.d(TAG, "NLST returned ${names?.size ?: "null"} entries")
            if (names != null && names.isNotEmpty()) {
                return names.map { name ->
                    org.apache.commons.net.ftp.FTPFile().apply {
                        this.name = name
                        this.type = if (!name.contains(".") || name.startsWith("."))
                            org.apache.commons.net.ftp.FTPFile.DIRECTORY_TYPE
                        else
                            org.apache.commons.net.ftp.FTPFile.FILE_TYPE
                    }
                }.toTypedArray()
            }
        } catch (e: Exception) {
            Log.d(TAG, "NLST failed: ${e.message}")
        }
        return null
    }

    /**
     * 지정된 인코딩으로 파일 목록 조회.
     */
    private fun listFilesWithEncoding(
        host: String,
        port: Int,
        username: String,
        password: String,
        remotePath: String,
        encoding: String,
        isFtps: Boolean
    ): Result<List<FtpItem>> {
        val client: FTPClient = createClient(isFtps)
        return try {
            // controlEncoding은 connect() 전에 설정해야 올바르게 적용됨
            client.isStrictReplyParsing = false // 비표준 응답 허용 (ASUS 호환성)
            client.controlEncoding = encoding
            client.connectTimeout = 15000
            client.defaultTimeout = 15000
            client.connect(host, port)
            client.soTimeout = 15000

            val reply = client.replyCode
            if (!FTPReply.isPositiveCompletion(reply)) {
                client.disconnect()
                return Result.failure(Exception("FTP 연결 거부: 코드 $reply"))
            }

            val loginUser = username.ifBlank { "anonymous" }
            val loginPass = if (username.isBlank()) "anonymous@" else password
            if (!client.login(loginUser, loginPass)) {
                client.logout()
                client.disconnect()
                return Result.failure(Exception("FTP 로그인 실패: 사용자명/비밀번호를 확인하세요."))
            }

            if (isFtps && client is FTPSClient) {
                client.execPBSZ(0)
                client.execPROT("P")
                val protReply = client.replyCode
                if (!FTPReply.isPositiveCompletion(protReply)) {
                    client.execPROT("C")
                }
            }

            client.enterLocalPassiveMode()
            client.setFileType(FTP.BINARY_FILE_TYPE)

            val path = if (remotePath.isBlank()) "/" else remotePath
            if (!client.changeWorkingDirectory(path)) {
                client.logout()
                client.disconnect()
                return Result.failure(Exception("폴더를 찾을 수 없습니다: $path"))
            }
            val files = fetchFileList(client)

            val items = files?.mapNotNull { file ->
                if (file.name == "." || file.name == "..") return@mapNotNull null
                try {
                    FtpItem(
                        name = file.name,
                        path = if (path.endsWith("/")) "$path${file.name}" else "$path/${file.name}",
                        isDirectory = file.isDirectory,
                        size = file.size,
                        lastModified = file.timestamp?.timeInMillis ?: 0L
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping file ${file.name}: ${e.message}")
                    null
                }
            }?.sortedWith(compareByDescending<FtpItem> { it.isDirectory }.thenBy { it.name })
                ?: emptyList()

            client.logout()
            client.disconnect()
            Result.success(items)
        } catch (e: Exception) {
            Log.e(TAG, "listFilesWithEncoding failed: ${e.message}")
            try { client.disconnect() } catch (_: Exception) {}
            Result.failure(e)
        }
    }

    /** FTP 경로의 파일을 바이트 배열로 읽어오기 (자막 등) */
    fun getFileBytes(
        host: String,
        port: Int = 21,
        username: String,
        password: String,
        remotePath: String,
        encoding: String = "AUTO",
        isFtps: Boolean = false
    ): Result<ByteArray> {
        val client: FTPClient = createClient(isFtps)
        return try {
            val actualEncoding = if (encoding == "AUTO") "UTF-8" else encoding
            client.isStrictReplyParsing = false // 비표준 응답 허용 (ASUS 호환성)
            client.controlEncoding = actualEncoding
            client.connectTimeout = 15000
            client.defaultTimeout = 15000
            client.connect(host, port)
            client.soTimeout = 15000
            
            val loginUser = username.ifBlank { "anonymous" }
            val loginPass = if (username.isBlank()) "anonymous@" else password
            if (!client.login(loginUser, loginPass)) {
                client.logout()
                client.disconnect()
                return Result.failure(Exception("FTP 로그인 실패"))
            }

            if (isFtps && client is FTPSClient) {
                client.execPBSZ(0)
                client.execPROT("P")
                val protReply = client.replyCode
                if (!FTPReply.isPositiveCompletion(protReply)) {
                    client.execPROT("C")
                }
            }

            client.enterLocalPassiveMode()
            client.setFileType(FTP.BINARY_FILE_TYPE)
            
            val inputStream = client.retrieveFileStream(remotePath)
            
            if (inputStream == null && encoding == "AUTO" && remotePath.any { it.code > 127 }) {
                client.logout()
                client.disconnect()
                Log.i(TAG, "UTF-8 파일 접근 실패 → EUC-KR로 재접속 시도")
                return getFileBytes(host, port, username, password, remotePath, "EUC-KR", isFtps)
            }
            
            if (inputStream == null) {
                client.logout()
                client.disconnect()
                return Result.failure(Exception("파일을 열 수 없습니다: $remotePath"))
            }
            
            val bytes = inputStream.readBytesWithLimit()
            inputStream.close()
            client.completePendingCommand()
            
            client.logout()
            client.disconnect()
            Result.success(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "getFileBytes failed: ${e.message}")
            try { client.disconnect() } catch (_: Exception) {}
            Result.failure(e)
        }
    }

    /** 비디오 확장자 여부 확인 */
    fun isVideoFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") ||
               lower.endsWith(".avi") || lower.endsWith(".mov") ||
               lower.endsWith(".wmv") || lower.endsWith(".flv") ||
               lower.endsWith(".m4v") || lower.endsWith(".ts") ||
               lower.endsWith(".m2ts") || lower.endsWith(".webm")
    }

    // ─── 인코딩 자동 감지 헬퍼 함수들 ───────────────────────────────────

    /**
     * 파일명에 비-ASCII 문자가 깨져 있는지 판별.
     * - 대체 문자(U+FFFD, '�')가 포함되어 있으면 깨진 것
     * - ISO-8859-1 바이트로 복원했을 때 EUC-KR 패턴이면 깨진 것 (UTF-8로 잘못 해석된 EUC-KR)
     */
    private fun hasGarbledNonAscii(name: String): Boolean {
        // ASCII만 있으면 검사 불필요
        if (name.all { it.code < 0x80 }) return false

        // 대체 문자(U+FFFD)가 있으면 깨진 것
        if (name.contains('\uFFFD')) return true

        // 높은 바이트(0x80~0xFF)로 된 특수한 패턴 확인
        // ISO-8859-1로 원본 바이트를 복원하여 EUC-KR 패턴인지 확인
        try {
            val rawBytes = name.toByteArray(Charsets.ISO_8859_1)
            if (looksLikeEucKrRaw(rawBytes)) return true
        } catch (_: Exception) {}

        return false
    }

    /**
     * 바이트 배열이 EUC-KR 인코딩 패턴을 보이는지 확인.
     * EUC-KR 한글: 첫 바이트 0xB0~0xC8 (가~힣), 둘째 바이트 0xA1~0xFE
     * CP949 확장: 첫 바이트 0x81~0xFE, 둘째 바이트 0x41~0xFE
     */
    private fun looksLikeEucKrRaw(bytes: ByteArray): Boolean {
        var i = 0
        var eucKrPairs = 0
        var invalidUtf8 = false
        
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            if (b < 0x80) {
                i++
                continue
            }
            
            // UTF-8 유효성 검사
            val expectedLen = when {
                b in 0xC0..0xDF -> 2
                b in 0xE0..0xEF -> 3
                b in 0xF0..0xF7 -> 4
                else -> 0 // 유효하지 않은 UTF-8 시작 바이트
            }
            
            if (expectedLen == 0) {
                invalidUtf8 = true
            } else {
                // UTF-8 연속 바이트 확인 (0x80~0xBF)
                var valid = true
                for (j in 1 until expectedLen) {
                    if (i + j >= bytes.size || (bytes[i + j].toInt() and 0xC0) != 0x80) {
                        valid = false
                        break
                    }
                }
                if (!valid) invalidUtf8 = true
            }

            // EUC-KR 2바이트 쌍 확인
            if (i + 1 < bytes.size) {
                val b2 = bytes[i + 1].toInt() and 0xFF
                // 표준 EUC-KR 한글 범위
                if (b in 0xA1..0xFE && b2 in 0xA1..0xFE) {
                    eucKrPairs++
                }
                // CP949 확장 한글 범위
                else if (b in 0x81..0xFE && (b2 in 0x41..0x5A || b2 in 0x61..0x7A || b2 in 0x81..0xFE)) {
                    eucKrPairs++
                }
            }
            
            i++
        }
        
        // EUC-KR 패턴이 발견되고 UTF-8로는 유효하지 않으면 EUC-KR로 판단
        return eucKrPairs > 0 && invalidUtf8
    }
}
