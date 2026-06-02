package com.sv21c.jsplayer

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPSClient
import java.io.IOException
import java.io.InputStream

/**
 * ExoPlayer용 FTP 커스텀 DataSource.
 * - Passive 모드로 NAT 환경 호환
 * - REST 명령으로 Seek 지원
 * - 1MB 버퍼링
 * - 인코딩 자동 감지 지원 (UTF-8 / EUC-KR)
 *
 * URI 형식: ftp://user:pass@host:port/path/to/file.mp4
 */
class FtpDataSource(
    private val encoding: String = "AUTO"
) : BaseDataSource(/* isNetwork = */ true) {

    companion object {
        private const val TAG = "FtpDataSource"
    }

    private var client: FTPClient? = null
    private var inputStream: InputStream? = null
    private var uri: Uri? = null
    private var bytesToRead: Long = 0
    private var opened: Boolean = false

    override fun open(dataSpec: DataSpec): Long {
        Log.d(TAG, "open() called: uri=${dataSpec.uri}, position=${dataSpec.position}, length=${dataSpec.length}, encoding=$encoding")
        // 이전 연결이 남아있으면 정리
        close()
        try {
            uri = dataSpec.uri
            transferInitializing(dataSpec)

            val parsedUri = dataSpec.uri
            val host = parsedUri.host ?: throw IOException("FTP host is null")
            val port = if (parsedUri.port > 0) parsedUri.port else 21
            val userInfo = parsedUri.userInfo
            val username = userInfo?.substringBefore(":") ?: "anonymous"
            val password = userInfo?.substringAfter(":") ?: "anonymous@"
            val remotePath = parsedUri.path ?: throw IOException("FTP path is null")

            val isFtps = parsedUri.scheme?.lowercase() == "ftps"
            val ftpClient: FTPClient = if (isFtps) {
                FTPSClient("TLS", false).apply {
                    trustManager = object : javax.net.ssl.X509TrustManager {
                        override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                        override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                    }
                    isEndpointCheckingEnabled = false
                    isRemoteVerificationEnabled = false
                }
            } else {
                FTPClient().apply {
                    isRemoteVerificationEnabled = false
                }
            }
            ftpClient.connectTimeout = 15000
            ftpClient.defaultTimeout = 15000
            ftpClient.dataTimeout = java.time.Duration.ofMillis(30000)  // 데이터 소켓 타임아웃
            ftpClient.setBufferSize(1024 * 1024)  // 데이터 전송 버퍼 크기

            // ── 인코딩 설정 (connect() 전에 해야 함) ──
            when (encoding) {
                "AUTO" -> {
                    // 자동 감지: UTF-8 먼저 시도
                    ftpClient.setAutodetectUTF8(true)
                    ftpClient.controlEncoding = "UTF-8"
                }
                else -> {
                    ftpClient.controlEncoding = encoding
                }
            }

            ftpClient.connect(host, port)
            if (!ftpClient.login(username, password)) {
                ftpClient.disconnect()
                throw IOException("FTP login failed")
            }
            if (isFtps && ftpClient is FTPSClient) {
                ftpClient.execPBSZ(0)
                ftpClient.execPROT("P")
            }
            ftpClient.enterLocalPassiveMode()
            Log.d(TAG, "Passive mode - data connection: ${ftpClient.passiveHost}:${ftpClient.passivePort}")
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE)

            // ── 안전한 한글/공백 경로 처리 ──
            val lastSlash = remotePath.lastIndexOf('/')
            val parentPath = if (lastSlash >= 0) remotePath.substring(0, lastSlash) else ""
            val fileName = if (lastSlash >= 0) remotePath.substring(lastSlash + 1) else remotePath

            var targetFileName = fileName
            var isCdSuccessful = false

            if (parentPath.isNotEmpty()) {
                if (ftpClient.changeWorkingDirectory(parentPath)) {
                    isCdSuccessful = true
                    Log.d(TAG, "Successfully changed FTP directory to: $parentPath")
                    
                    val ftpFiles = try { ftpClient.listFiles() } catch (e: Exception) { null }
                    if (ftpFiles != null) {
                        val targetNfc = java.text.Normalizer.normalize(fileName, java.text.Normalizer.Form.NFC)
                        val targetNfd = java.text.Normalizer.normalize(fileName, java.text.Normalizer.Form.NFD)
                        val matched = ftpFiles.find { file ->
                            val name = file.name ?: ""
                            val fileNfc = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC)
                            val fileNfd = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
                            fileNfc.equals(targetNfc, ignoreCase = true) || fileNfd.equals(targetNfd, ignoreCase = true)
                        }
                        if (matched != null) {
                            targetFileName = matched.name
                            Log.d(TAG, "FTP File matched in directory: $targetFileName")
                        }
                    }
                } else {
                    Log.w(TAG, "Failed to change FTP directory to: $parentPath, falling back to full path")
                }
            }

            val queryPath = if (isCdSuccessful) targetFileName else remotePath

            // 파일 크기 조회
            val fileSize: Long? = ftpClient.queryFileSize(queryPath)
            val totalSize: Long = if (fileSize != null) {
                fileSize
            } else {
                C.LENGTH_UNSET.toLong()
            }

            // Seek: REST 명령으로 시작 위치 설정
            if (dataSpec.position > 0) {
                ftpClient.restartOffset = dataSpec.position
            }

            // 파일 스트림 열기
            var stream = ftpClient.retrieveFileStream(queryPath)

            // AUTO 모드에서 UTF-8로 실패하면 EUC-KR로 재시도
            if (stream == null && encoding == "AUTO") {
                Log.w(TAG, "UTF-8로 파일 열기 실패 (${ftpClient.replyString?.trim()}), EUC-KR로 재시도")
                try { ftpClient.disconnect() } catch (_: Exception) {}

                val retryClient: FTPClient = if (isFtps) {
                    FTPSClient("TLS", false).apply {
                        trustManager = object : javax.net.ssl.X509TrustManager {
                            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                        }
                        isEndpointCheckingEnabled = false
                        isRemoteVerificationEnabled = false
                    }
                } else {
                    FTPClient().apply {
                        isRemoteVerificationEnabled = false
                    }
                }
                retryClient.connectTimeout = 15000
                retryClient.defaultTimeout = 15000
                retryClient.dataTimeout = java.time.Duration.ofMillis(30000)
                retryClient.setBufferSize(1024 * 1024)
                retryClient.controlEncoding = "EUC-KR"
                retryClient.connect(host, port)
                if (!retryClient.login(username, password)) {
                    retryClient.disconnect()
                    throw IOException("FTP login failed (EUC-KR retry)")
                }
                if (isFtps && retryClient is FTPSClient) {
                    retryClient.execPBSZ(0)
                    retryClient.execPROT("P")
                }
                retryClient.enterLocalPassiveMode()
                Log.d(TAG, "Passive mode (EUC-KR retry) - data connection: ${retryClient.passiveHost}:${retryClient.passivePort}")
                retryClient.setFileType(FTP.BINARY_FILE_TYPE)

                // EUC-KR 환경에서도 안전한 디렉터리 이동 시도
                var isCdSuccessfulRetry = false
                var targetFileNameRetry = fileName
                if (parentPath.isNotEmpty()) {
                    if (retryClient.changeWorkingDirectory(parentPath)) {
                        isCdSuccessfulRetry = true
                        val ftpFilesRetry = try { retryClient.listFiles() } catch (e: Exception) { null }
                        if (ftpFilesRetry != null) {
                            val targetNfc = java.text.Normalizer.normalize(fileName, java.text.Normalizer.Form.NFC)
                            val targetNfd = java.text.Normalizer.normalize(fileName, java.text.Normalizer.Form.NFD)
                            val matched = ftpFilesRetry.find { file ->
                                val name = file.name ?: ""
                                val fileNfc = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC)
                                val fileNfd = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
                                fileNfc.equals(targetNfc, ignoreCase = true) || fileNfd.equals(targetNfd, ignoreCase = true)
                            }
                            if (matched != null) {
                                targetFileNameRetry = matched.name
                            }
                        }
                    }
                }
                val queryPathRetry = if (isCdSuccessfulRetry) targetFileNameRetry else remotePath

                // EUC-KR 재접속 시 파일 크기 조회
                val retrySizeVal: Long? = retryClient.queryFileSize(queryPathRetry)
                val retryTotalSize: Long = retrySizeVal ?: C.LENGTH_UNSET.toLong()

                if (dataSpec.position > 0) {
                    retryClient.restartOffset = dataSpec.position
                }

                // EUC-KR 인코딩으로 파일 스트림 열기
                stream = retryClient.retrieveFileStream(queryPathRetry)
                if (stream == null) {
                    val replyStr = retryClient.replyString
                    retryClient.disconnect()
                    throw IOException("FTP retrieveFileStream returned null (EUC-KR): $replyStr")
                }

                inputStream = java.io.BufferedInputStream(stream, 1024 * 1024)
                client = retryClient

                bytesToRead = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                    if (retryTotalSize != C.LENGTH_UNSET.toLong()) {
                        retryTotalSize - dataSpec.position
                    } else {
                        C.LENGTH_UNSET.toLong()
                    }
                } else {
                    dataSpec.length
                }
            } else if (stream == null) {
                throw IOException("FTP retrieveFileStream returned null: ${ftpClient.replyString}")
            } else {
                inputStream = java.io.BufferedInputStream(stream, 1024 * 1024) // 1MB buffer
                client = ftpClient

                bytesToRead = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                    if (totalSize != C.LENGTH_UNSET.toLong()) {
                        totalSize - dataSpec.position
                    } else {
                        C.LENGTH_UNSET.toLong()
                    }
                } else {
                    dataSpec.length
                }
            }

            opened = true
            transferStarted(dataSpec)

            return bytesToRead
        } catch (e: Exception) {
            Log.e(TAG, "open() failed: ${e.message}", e)
            throw IOException(e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (readLength == 0) return 0
        if (bytesToRead == 0L) return C.RESULT_END_OF_INPUT

        return try {
            val toRead = if (bytesToRead != C.LENGTH_UNSET.toLong()) {
                minOf(readLength.toLong(), bytesToRead).toInt()
            } else {
                readLength
            }
            val bytesRead = inputStream?.read(buffer, offset, toRead) ?: -1
            if (bytesRead == -1) {
                return C.RESULT_END_OF_INPUT
            }
            if (bytesToRead != C.LENGTH_UNSET.toLong()) {
                bytesToRead -= bytesRead
            }
            bytesTransferred(bytesRead)
            bytesRead
        } catch (e: Exception) {
            Log.e(TAG, "read() failed: ${e.message}", e)
            throw IOException(e)
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            client?.let { ftpClient ->
                if (ftpClient.isConnected) {
                    try {
                        ftpClient.abort()
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "close() FTP abort failed: ${e.message}")
        }
        try {
            inputStream?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close() inputStream close failed: ${e.message}")
        }
        try {
            client?.let { ftpClient ->
                if (ftpClient.isConnected) {
                    ftpClient.disconnect()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "close() FTP disconnect failed: ${e.message}")
        } finally {
            inputStream = null
            client = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    /**
     * 파일 크기 조회 - Apache Commons Net 내장 getSize(String): String 활용
     * SIZE 명령을 서버에 보내고 응답 문자열을 Long으로 파싱
     * 지원하지 않는 서버의 경우 null 반환
     */
    private fun FTPClient.queryFileSize(remotePath: String): Long? {
        return try {
            val sizeStr: String? = getSize(remotePath)
            if (sizeStr != null) {
                // 응답 예: "213 123456789" 또는 "123456789"
                val size = sizeStr.trim().substringAfterLast(" ").toLongOrNull()
                Log.d(TAG, "queryFileSize() via SIZE: $size (raw: $sizeStr)")
                size
            } else {
                Log.w(TAG, "queryFileSize() SIZE returned null for: $remotePath")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "queryFileSize() exception: ${e.message}")
            null
        }
    }
}
