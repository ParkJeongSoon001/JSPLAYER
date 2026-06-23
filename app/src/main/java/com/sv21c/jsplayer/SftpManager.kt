package com.sv21c.jsplayer

import android.util.Log
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.util.EnumSet

data class SftpItem(
    val name: String,
    val path: String,       // 서버 내 전체 경로 (/home/user/video.mp4)
    val isDirectory: Boolean,
    val size: Long = 0L,
    val lastModified: Long = 0L
)

/**
 * SFTP(SSH File Transfer Protocol) 서버 파일 목록 조회 매니저.
 * - SSHJ 라이브러리 기반
 * - Host Key 검증은 PromiscuousVerifier 사용 (편의성 우선)
 * - X25519 등 최신 암호 알고리즘 지원을 위해 Bouncy Castle Provider 등록
 */
object SftpManager {
    private const val TAG = "SftpManager"

    init {
        // Android 기본 BC 제거 후 전체 Bouncy Castle 등록
        // → X25519, Ed25519 등 최신 알고리즘 사용 가능
        try {
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
            Log.d(TAG, "✅ BouncyCastle Provider 등록 완료")
        } catch (e: Exception) {
            Log.e(TAG, "BouncyCastle Provider 등록 실패: ${e.message}")
        }
    }

    /**
     * SFTP 경로의 파일/폴더 목록 반환.
     * @param host SFTP 서버 IP 또는 호스트명
     * @param port SFTP 포트 (기본 22)
     * @param username 사용자명
     * @param password 비밀번호
     * @param remotePath 원격 디렉토리 경로 (예: "/")
     */
    fun listFiles(
        host: String,
        port: Int = 22,
        username: String,
        password: String,
        remotePath: String
    ): Result<List<SftpItem>> {
        var ssh: SSHClient? = null
        var sftp: SFTPClient? = null
        Log.d(TAG, "▶ SFTP 접속 시도: host=$host, port=$port, user=$username, path=$remotePath")
        return try {
            ssh = SSHClient(net.schmizz.sshj.AndroidConfig())
            ssh.addHostKeyVerifier(PromiscuousVerifier())  // Trust all host keys
            ssh.connectTimeout = 10000
            ssh.timeout = 15000
            Log.d(TAG, "  → ssh.connect($host, $port)")
            ssh.connect(host, port)
            Log.d(TAG, "  → ssh.authPassword($username, ***)")
            ssh.authPassword(username, password)
            Log.d(TAG, "  ✅ 인증 성공")

            sftp = ssh.newSFTPClient()
            val path = if (remotePath.isBlank()) "/" else remotePath
            val files: List<RemoteResourceInfo> = sftp.ls(path)

            val items = files.mapNotNull { file ->
                if (file.name == "." || file.name == "..") return@mapNotNull null
                try {
                    SftpItem(
                        name = file.name,
                        path = if (path.endsWith("/")) "$path${file.name}" else "$path/${file.name}",
                        isDirectory = file.isDirectory,
                        size = file.attributes.size,
                        lastModified = file.attributes.mtime * 1000L
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping file ${file.name}: ${e.message}")
                    null
                }
            }.sortedWith(compareByDescending<SftpItem> { it.isDirectory }.thenBy { it.name })

            sftp.close()
            ssh.disconnect()

            Log.d(TAG, "  ✅ 파일 ${items.size}개 로드 완료")
            Result.success(items)
        } catch (e: Exception) {
            Log.e(TAG, "❌ listFiles 실패: ${e.message}", e)
            try { sftp?.close() } catch (_: Exception) {}
            try { ssh?.disconnect() } catch (_: Exception) {}
            Result.failure(e)
        }
    }

    /** SFTP 경로의 파일을 바이트 배열로 읽어오기 (자막 등) */
    fun getFileBytes(
        host: String,
        port: Int = 22,
        username: String,
        password: String,
        remotePath: String
    ): Result<ByteArray> {
        var ssh: net.schmizz.sshj.SSHClient? = null
        var sftp: net.schmizz.sshj.sftp.SFTPClient? = null
        return try {
            ssh = net.schmizz.sshj.SSHClient(net.schmizz.sshj.AndroidConfig())
            ssh.addHostKeyVerifier(net.schmizz.sshj.transport.verification.PromiscuousVerifier())
            ssh.connect(host, port)
            ssh.authPassword(username, password)
            sftp = ssh.newSFTPClient()
            
            var file: net.schmizz.sshj.sftp.RemoteFile? = null
            try {
                // 1차 시도: 원시 경로 그대로 오픈
                Log.d(TAG, "getFileBytes: 1차 시도 - 원시 경로 open: $remotePath")
                file = sftp.open(remotePath, EnumSet.of(net.schmizz.sshj.sftp.OpenMode.READ))
            } catch (e: Exception) {
                Log.w(TAG, "getFileBytes: 1차 시도 실패 (${e.message}). 디렉토리 리스팅 매칭(NFC/NFD/대소문자 무시) 폴백 시도...")
                val lastSlash = remotePath.lastIndexOf('/')
                val parentPath = if (lastSlash >= 0) remotePath.substring(0, lastSlash).ifEmpty { "/" } else "/"
                val fileName = if (lastSlash >= 0) remotePath.substring(lastSlash + 1) else remotePath
                
                try {
                    val files = sftp.ls(parentPath)
                    val targetNfc = java.text.Normalizer.normalize(fileName, java.text.Normalizer.Form.NFC)
                    val targetNfd = java.text.Normalizer.normalize(fileName, java.text.Normalizer.Form.NFD)
                    
                    val matchedFile = files.find { info ->
                        val name = info.name ?: ""
                        val fileNfc = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC)
                        val fileNfd = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
                        
                        fileNfc.equals(targetNfc, ignoreCase = true) ||
                        fileNfc.equals(targetNfd, ignoreCase = true) ||
                        fileNfd.equals(targetNfc, ignoreCase = true) ||
                        fileNfd.equals(targetNfd, ignoreCase = true)
                    }
                    
                    if (matchedFile != null) {
                        val actualPath = if (parentPath.endsWith("/")) "$parentPath${matchedFile.name}" else "$parentPath/${matchedFile.name}"
                        Log.d(TAG, "getFileBytes: 매칭된 실제 서버 경로로 2차 open 시도: $actualPath")
                        file = sftp.open(actualPath, EnumSet.of(net.schmizz.sshj.sftp.OpenMode.READ))
                    } else {
                        throw e
                    }
                } catch (le: Exception) {
                    Log.e(TAG, "getFileBytes: 폴백 리스팅 매칭 도중 실패: ${le.message}")
                    throw e
                }
            }
            
            val rawStream = file.RemoteFileInputStream()
            val isStr = java.io.BufferedInputStream(rawStream, 65536)
            val bytes = isStr.readBytesWithLimit()
            isStr.close()
            file.close()
            
            sftp.close()
            ssh.disconnect()
            Result.success(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "getFileBytes 실패: ${e.message}", e)
            try { sftp?.close() } catch (_: Exception) {}
            try { ssh?.disconnect() } catch (_: Exception) {}
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

    /** 오디오 확장자 여부 확인 */
    fun isAudioFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".mp3") || lower.endsWith(".flac") ||
               lower.endsWith(".ape") || lower.endsWith(".m4a") ||
               lower.endsWith(".wav") || lower.endsWith(".ogg") ||
               lower.endsWith(".aac")
    }
}
