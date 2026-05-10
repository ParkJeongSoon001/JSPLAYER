package com.sv21c.jsplayer

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Solid Explorer 등 외부 파일 탐색기의 로컬 프록시(127.0.0.1) 스트림을
 * 임시 파일에 버퍼링한 후 ExoPlayer에 안정적으로 제공하는 DataSource.
 *
 * 프록시 서버가 SFTP/FTP 서버에서 데이터를 가져오는 과정에서
 * 스트림이 불안정해지는 문제를 해결합니다.
 *
 * 동작 방식:
 * 1. open() 호출 시 백그라운드 스레드에서 프록시 URL을 다운로드하여 임시 파일에 저장
 * 2. read() 호출 시 임시 파일에서 데이터를 읽음 (다운로드 진행 중이면 대기)
 * 3. ExoPlayer는 항상 디스크의 완전한 데이터를 읽으므로 스트림 깨짐 방지
 */
class ProxyBufferingDataSource(
    private val context: Context
) : DataSource {

    companion object {
        private const val TAG = "ProxyBuffering"
        private const val INITIAL_BUFFER_SIZE = 256L * 1024  // 256KB 초기 버퍼 (빠른 시작)
        private const val READ_WAIT_MS = 20L  // 데이터 대기 간격
        private const val DOWNLOAD_BUFFER_SIZE = 256 * 1024  // 256KB 다운로드 청크
    }

    private var tempFile: File? = null
    private var reader: RandomAccessFile? = null
    private var downloadThread: Thread? = null

    @Volatile private var bytesDownloaded: Long = 0
    @Volatile private var isDownloadComplete = false
    @Volatile private var downloadError: IOException? = null

    private var readPosition: Long = 0
    private var uri: Uri? = null
    private var isClosed = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val url = dataSpec.uri.toString()
        isClosed = false
        bytesDownloaded = 0
        isDownloadComplete = false
        downloadError = null
        readPosition = dataSpec.position

        Log.d(TAG, "🚀 프록시 버퍼링 시작: $url (position=${dataSpec.position})")

        // 임시 파일 생성
        val cacheDir = File(context.cacheDir, "proxy_buffer")
        cacheDir.mkdirs()
        tempFile = File(cacheDir, "buffer_${System.currentTimeMillis()}.tmp")

        // 백그라운드에서 다운로드 시작
        downloadThread = Thread({
            var connection: java.net.HttpURLConnection? = null
            var inputStream: java.io.InputStream? = null
            var outputStream: FileOutputStream? = null

            try {
                val urlObj = java.net.URL(url)
                connection = urlObj.openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 60_000
                connection.readTimeout = 300_000  // 5분 (대용량 파일 대비)
                connection.setRequestProperty("User-Agent", "SVC-Player/1.0")
                // Range 요청 하지 않음 - 처음부터 순차 다운로드
                connection.doInput = true
                connection.connect()

                val responseCode = connection.responseCode
                val contentType = connection.contentType
                val contentLength = connection.contentLengthLong

                Log.d(TAG, "📡 HTTP $responseCode | Content-Type: $contentType | Content-Length: $contentLength")

                if (responseCode !in 200..299) {
                    throw IOException("HTTP error $responseCode from proxy")
                }

                inputStream = connection.inputStream
                outputStream = FileOutputStream(tempFile!!)

                val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                var totalRead = 0L

                while (!isClosed) {
                    val read = inputStream.read(buffer)
                    if (read == -1) break

                    outputStream.write(buffer, 0, read)
                    outputStream.flush()
                    totalRead += read
                    bytesDownloaded = totalRead

                    // 1MB마다 진행률 로그
                    if (totalRead % (1024 * 1024) < DOWNLOAD_BUFFER_SIZE) {
                        val mb = totalRead / (1024 * 1024)
                        Log.d(TAG, "📥 다운로드 진행: ${mb}MB")
                    }
                }

                isDownloadComplete = true
                Log.d(TAG, "✅ 다운로드 완료: ${totalRead / (1024 * 1024)}MB ($totalRead bytes)")

            } catch (e: Exception) {
                if (!isClosed) {  // 정상 종료가 아닌 경우만 에러 처리
                    Log.e(TAG, "❌ 다운로드 에러", e)
                    downloadError = IOException("Proxy download failed: ${e.message}", e)
                }
            } finally {
                try { inputStream?.close() } catch (_: Exception) {}
                try { outputStream?.close() } catch (_: Exception) {}
                try { connection?.disconnect() } catch (_: Exception) {}
            }
        }, "ProxyBufferDownload")
        downloadThread!!.isDaemon = true
        downloadThread!!.start()

        // 초기 버퍼가 채워질 때까지 대기 (또는 다운로드 완료/에러)
        Log.d(TAG, "⏳ 초기 버퍼링 대기 (${INITIAL_BUFFER_SIZE / 1024}KB)...")
        while (bytesDownloaded < INITIAL_BUFFER_SIZE && !isDownloadComplete && downloadError == null) {
            Thread.sleep(READ_WAIT_MS)
        }

        if (downloadError != null) {
            throw downloadError!!
        }

        Log.d(TAG, "✅ 초기 버퍼 준비 완료: ${bytesDownloaded / 1024}KB")

        // 읽기용 RandomAccessFile 열기
        reader = RandomAccessFile(tempFile!!, "r")

        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (isClosed) return C.RESULT_END_OF_INPUT

        // 요청한 위치에 데이터가 올 때까지 대기
        while (readPosition >= bytesDownloaded && !isDownloadComplete) {
            if (downloadError != null) {
                throw downloadError!!
            }
            try {
                Thread.sleep(READ_WAIT_MS)
            } catch (_: InterruptedException) {
                return C.RESULT_END_OF_INPUT
            }
        }

        // 다운로드 완료 + 모든 데이터 읽음 → EOF
        if (readPosition >= bytesDownloaded && isDownloadComplete) {
            return C.RESULT_END_OF_INPUT
        }

        // 현재 읽을 수 있는 양 계산
        val available = (bytesDownloaded - readPosition).coerceAtMost(length.toLong()).toInt()
        if (available <= 0) return C.RESULT_END_OF_INPUT

        return try {
            reader!!.seek(readPosition)
            val read = reader!!.read(buffer, offset, available)
            if (read > 0) {
                readPosition += read
            }
            read
        } catch (e: IOException) {
            Log.e(TAG, "파일 읽기 에러 (position=$readPosition)", e)
            throw e
        }
    }

    override fun close() {
        isClosed = true
        try { reader?.close() } catch (_: Exception) {}
        reader = null

        // 다운로드 스레드 종료 대기 (최대 1초)
        downloadThread?.let { thread ->
            try {
                thread.interrupt()
                thread.join(1000)
            } catch (_: Exception) {}
        }
        downloadThread = null

        // 임시 파일 삭제
        try {
            tempFile?.delete()
            Log.d(TAG, "🗑️ 임시 파일 삭제 완료")
        } catch (_: Exception) {}
        tempFile = null

        bytesDownloaded = 0
        isDownloadComplete = false
        downloadError = null
        readPosition = 0
    }

    override fun getUri(): Uri? = uri

    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

    override fun addTransferListener(listener: TransferListener) {
        // Not needed for proxy buffering
    }

    /**
     * Factory for ProxyBufferingDataSource
     */
    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return ProxyBufferingDataSource(context)
        }
    }
}
