package com.sv21c.jsplayer

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.io.IOException
import java.util.EnumSet

/**
 * ExoPlayer용 SFTP 커스텀 DataSource.
 * - SSHJ 라이브러리 기반
 * - RemoteFile.read()로 Seek 지원
 * - Host Key 검증 skip (PromiscuousVerifier)
 *
 * URI 형식: sftp://user:pass@host:port/path/to/file.mp4
 */
class SftpDataSource : BaseDataSource(/* isNetwork = */ true) {

    companion object {
        private const val TAG = "SftpDataSource"
        private const val BUFFER_SIZE = 256 * 1024  // 256KB (메모리 절약)

        init {
            // X25519 등 최신 암호 알고리즘 지원 보장
            try {
                java.security.Security.removeProvider("BC")
                java.security.Security.insertProviderAt(
                    org.bouncycastle.jce.provider.BouncyCastleProvider(), 1
                )
            } catch (_: Exception) {}
        }
    }

    private var sshClient: SSHClient? = null
    private var sftpClient: SFTPClient? = null
    private var remoteFile: RemoteFile? = null
    private var inputStream: java.io.InputStream? = null
    private var uri: Uri? = null
    private var bytesToRead: Long = 0
    private var currentOffset: Long = 0
    private var opened: Boolean = false

    override fun open(dataSpec: DataSpec): Long {
        Log.d(TAG, "open() called: uri=${dataSpec.uri}, position=${dataSpec.position}, length=${dataSpec.length}")
        try {
            uri = dataSpec.uri
            transferInitializing(dataSpec)

            val parsedUri = dataSpec.uri
            val host = parsedUri.host ?: throw IOException("SFTP host is null")
            val port = if (parsedUri.port > 0) parsedUri.port else 22
            val userInfo = parsedUri.userInfo
            val username = userInfo?.substringBefore(":") ?: throw IOException("SFTP username is required")
            val password = userInfo.substringAfter(":", "") ?: ""
            val remotePath = android.net.Uri.decode(parsedUri.path ?: throw IOException("SFTP path is null"))

            val ssh = SSHClient(net.schmizz.sshj.AndroidConfig())
            ssh.addHostKeyVerifier(PromiscuousVerifier())
            ssh.connectTimeout = 15000
            ssh.timeout = 15000
            ssh.connect(host, port)
            ssh.authPassword(username, password)

            val sftp = ssh.newSFTPClient()
            val file = sftp.open(remotePath, EnumSet.of(OpenMode.READ))

            val fileSize = file.length()
            currentOffset = dataSpec.position

            sshClient = ssh
            sftpClient = sftp
            remoteFile = file
            val rawStream = file.RemoteFileInputStream(currentOffset)
            inputStream = java.io.BufferedInputStream(rawStream, BUFFER_SIZE)

            bytesToRead = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                fileSize - dataSpec.position
            } else {
                dataSpec.length
            }
            if (bytesToRead < 0) throw IOException("EOF: bytesToRead=$bytesToRead")

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
            val toRead = minOf(readLength.toLong(), bytesToRead).toInt()
            val bytesRead = inputStream?.read(buffer, offset, toRead) ?: -1
            if (bytesRead == -1) {
                return C.RESULT_END_OF_INPUT
            }
            currentOffset += bytesRead
            bytesToRead -= bytesRead
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
            inputStream?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close() inputStream close failed: ${e.message}")
        }
        try {
            remoteFile?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close() remoteFile close failed: ${e.message}")
        }
        try {
            sftpClient?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close() sftpClient close failed: ${e.message}")
        }
        try {
            sshClient?.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "close() sshClient disconnect failed: ${e.message}")
        } finally {
            inputStream = null
            remoteFile = null
            sftpClient = null
            sshClient = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
