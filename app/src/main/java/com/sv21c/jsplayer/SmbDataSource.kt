package com.sv21c.jsplayer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import jcifs.CIFSContext
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import java.io.IOException

class SmbDataSource(
    private val contextProvider: (Uri) -> CIFSContext
) : BaseDataSource(/* isNetwork = */ true) {

    private var file: SmbRandomAccessFile? = null
    private var inputStream: java.io.InputStream? = null
    private var uri: Uri? = null
    private var bytesToRead: Long = 0
    private var opened: Boolean = false

    override fun open(dataSpec: DataSpec): Long {
        android.util.Log.d("SmbDataSource", "open() called: uri=${dataSpec.uri}, position=${dataSpec.position}, length=${dataSpec.length}")
        try {
            uri = dataSpec.uri
            transferInitializing(dataSpec)
            
            val ctx = contextProvider(uri!!)
            val cleanUriString = uri.toString().replaceFirst(Regex("(?<=smb://).*?@"), "")
            
            // Pass clean URL to SmbFile to avoid credential conflicts
            val smbFile = SmbFile(cleanUriString, ctx)
            
            file = SmbRandomAccessFile(smbFile, "r")
            
            file?.seek(dataSpec.position)
            
            inputStream = java.io.BufferedInputStream(object : java.io.InputStream() {
                override fun read(): Int {
                    return file?.read() ?: -1
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    return file?.read(b, off, len) ?: -1
                }

                override fun close() {
                    file?.close()
                }
            }, 1024 * 1024) // 1MB buffer
            
            val fileLength = file?.length() ?: 0L
            val bytesRemaining = fileLength - dataSpec.position
            
            bytesToRead = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                bytesRemaining
            } else {
                dataSpec.length
            }
            if (bytesToRead < 0) throw IOException("EOF")

            opened = true
            transferStarted(dataSpec)
            
            return bytesToRead
        } catch (e: Exception) {
            android.util.Log.e("SmbDataSource", "open() failed: ${e.message}", e)
            throw IOException(e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (readLength == 0) return 0
        if (bytesToRead == 0L) return C.RESULT_END_OF_INPUT

        return try {
            val bytesToReadNow = minOf(readLength.toLong(), bytesToRead).toInt()
            val bytesRead = inputStream?.read(buffer, offset, bytesToReadNow) ?: -1
            if (bytesRead > 0) {
                bytesToRead -= bytesRead
                bytesTransferred(bytesRead)
            } else if (bytesToRead > 0) {
                return C.RESULT_END_OF_INPUT
            }
            bytesRead
        } catch (e: Exception) {
            android.util.Log.e("SmbDataSource", "read() failed: ${e.message}", e)
            throw IOException(e)
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            inputStream?.close() ?: file?.close()
        } catch (e: Exception) {
            android.util.Log.w("SmbDataSource", "close() file close failed: ${e.message}")
        } finally {
            inputStream = null
            file = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
