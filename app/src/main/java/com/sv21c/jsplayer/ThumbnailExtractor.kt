package com.sv21c.jsplayer

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object ThumbnailExtractor {

    suspend fun extractThumbnailUri(context: Context, videoUrl: String): Uri? {
        return withContext(Dispatchers.IO) {
            var retriever: MediaMetadataRetriever? = null
            try {
                val uri = Uri.parse(videoUrl)
                val scheme = uri.scheme?.lowercase() ?: ""

                // 1. 네트워크 주소인 경우 썸네일 추출 생략 (로딩 지연 및 크래시 방지)
                if (scheme == "smb" || scheme == "ftp" || scheme == "sftp" || scheme.startsWith("http")) {
                    return@withContext null
                }

                retriever = MediaMetadataRetriever()
                
                // 2. 미디어 소스 지정
                if (scheme == "content") {
                    retriever.setDataSource(context, uri)
                } else {
                    // file:// 이거나 절대 경로인 경우
                    val path = if (scheme == "file") uri.path else videoUrl
                    path?.let { retriever.setDataSource(it) } ?: return@withContext null
                }

                // 3. 약 5초 지점 (5_000_000 마이크로초)의 프레임 추출. (영상 길이에 따라 실패할 수 있는데 이 경우 가장 가까운 프레임)
                val timeUs = 5_000_000L
                val extractedBitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) 
                    ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) // 5초 지점 실패시 0초 폴백

                if (extractedBitmap != null) {
                    // 4. Notification TransactionTooLargeException 방지를 위해 강제 다운스케일 (가로/세로 최대 512)
                    var width = extractedBitmap.width
                    var height = extractedBitmap.height
                    val maxSide = 512
                    
                    val b: Bitmap = if (width > maxSide || height > maxSide) {
                        val ratio = maxOf(width.toFloat() / maxSide, height.toFloat() / maxSide)
                        width = (width / ratio).toInt()
                        height = (height / ratio).toInt()
                        Bitmap.createScaledBitmap(extractedBitmap, width, height, true)
                    } else {
                        extractedBitmap
                    }

                    // 5. 캐시 파일로 저장
                    val cacheDir = File(context.cacheDir, "thumbnails").apply { mkdirs() }
                    val thumbFile = File(cacheDir, "current_thumb.jpg")
                    FileOutputStream(thumbFile).use { out ->
                        b.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }
                    
                    // 추출한 원본/리사이즈 비트맵 메모리 해제
                    if (b != extractedBitmap) b.recycle()
                    extractedBitmap.recycle()
                    
                    return@withContext Uri.fromFile(thumbFile)
                }
            } catch (e: Exception) {
                android.util.Log.e("ThumbnailExtractor", "Failed to extract thumbnail: ${e.message}")
            } finally {
                try {
                    retriever?.release()
                } catch (e: Exception) {}
            }
            null
        }
    }
}
