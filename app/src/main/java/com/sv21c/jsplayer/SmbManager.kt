package com.sv21c.jsplayer

import android.util.Log
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.util.Properties

data class SmbItem(
    val name: String,
    val path: String,   // smb://host/share/path/
    val isDirectory: Boolean,
    val lastModified: Long = 0L
)

object SmbManager {
    private const val TAG = "SmbManager"
    private val baseContext: CIFSContext by lazy {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.responseTimeout", "10000")
            setProperty("jcifs.smb.client.connTimeout", "8000")
            setProperty("jcifs.smb.client.rcv_buf_size", "1048576")
            setProperty("jcifs.smb.client.snd_buf_size", "1048576")
            setProperty("jcifs.smb.client.disableSMB1", "true")
            setProperty("jcifs.smb.client.enableSMB2", "true")
        }
        BaseContext(PropertyConfiguration(props))
    }

    fun buildContext(username: String, password: String): CIFSContext {
        return if (username.isNotBlank()) {
            baseContext.withCredentials(NtlmPasswordAuthenticator("", username, password))
        } else {
            baseContext.withAnonymousCredentials()
        }
    }
    /**
     * SMB 경로의 파일/폴더 목록 반환
     * @param smbUrl - smb://host/share/ 형태
     */
    fun listFiles(smbUrl: String, username: String, password: String): Result<List<SmbItem>> {
        return try {
            val ctx = buildContext(username, password)
            val dir = SmbFile(smbUrl, ctx)
            val items = dir.listFiles()?.mapNotNull { file ->
                try {
                    SmbItem(
                        name = file.name.trimEnd('/'),
                        path = file.url.toString(),
                        isDirectory = file.isDirectory,
                        lastModified = file.lastModified()
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping file ${file.name}: ${e.message}")
                    null
                }
            }?.sortedWith(compareByDescending<SmbItem> { it.isDirectory }.thenBy { it.name }) ?: emptyList()
            Result.success(items)
        } catch (e: Exception) {
            Log.e(TAG, "listFiles failed for $smbUrl: ${e.message}")
            Result.failure(e)
        }
    }

    /** 비디오 확장자 여부 확인 */
    fun isVideoFile(name: String): Boolean {
        val lower = name.lowercase().trimEnd('/')
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") ||
               lower.endsWith(".avi") || lower.endsWith(".mov") ||
               lower.endsWith(".wmv") || lower.endsWith(".flv") ||
               lower.endsWith(".m4v") || lower.endsWith(".ts") ||
               lower.endsWith(".m2ts") || lower.endsWith(".webm")
    }
}
