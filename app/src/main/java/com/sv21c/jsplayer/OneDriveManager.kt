package com.sv21c.jsplayer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OneDrive 파일을 탐색하고 스트리밍 URL을 제공하는 매니저.
 * GoogleDriveManager와 동일한 역할입니다.
 *
 * Microsoft Graph REST API를 OkHttp로 직접 호출합니다.
 */
data class OneDriveItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long?,
    val lastModified: Long?,
    val isDirectory: Boolean,
    val downloadUrl: String? = null
)

object OneDriveManager {

    private const val TAG = "OneDriveManager"
    private const val GRAPH_BASE = "https://graph.microsoft.com/v1.0"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // 지원하는 동영상 확장자
    private val VIDEO_EXTENSIONS = listOf("mp4", "mkv", "avi", "mov", "ts", "wmv", "flv", "webm", "m4v")
    // 지원하는 오디오 확장자
    private val AUDIO_EXTENSIONS = listOf("mp3", "flac", "ape", "m4a", "wav", "ogg", "aac")
    // 지원하는 자막 확장자
    private val SUBTITLE_EXTENSIONS = listOf("smi", "srt", "ass", "vtt", "ssa", "sub", "txt")

    fun isVideoFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return VIDEO_EXTENSIONS.contains(ext)
    }

    fun isAudioFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return AUDIO_EXTENSIONS.contains(ext)
    }

    fun isVideoOrSubtitleFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return VIDEO_EXTENSIONS.contains(ext) || SUBTITLE_EXTENSIONS.contains(ext)
    }

    fun isVideoOrAudioOrSubtitleFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return VIDEO_EXTENSIONS.contains(ext) || AUDIO_EXTENSIONS.contains(ext) || SUBTITLE_EXTENSIONS.contains(ext)
    }

    /**
     * 특정 폴더의 파일/폴더 리스트 조회 (비디오, 자막, 폴더만 필터링)
     * @param folderId 조회할 폴더 ID (최상단은 "root")
     */
    suspend fun listFiles(folderId: String = "root"): Result<List<OneDriveItem>> = withContext(Dispatchers.IO) {
        try {
            val accessToken = OneDriveAuthManager.getAccessToken()
                ?: return@withContext Result.failure(Exception("OneDrive 인증 토큰을 가져올 수 없습니다. 다시 로그인해 주세요."))

            val url = if (folderId == "root") {
                "$GRAPH_BASE/me/drive/root/children?\$top=1000&\$orderby=name"
            } else {
                "$GRAPH_BASE/me/drive/items/$folderId/children?\$top=1000&\$orderby=name"
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "Graph API 호출 실패: ${response.code} - $errorBody")
                return@withContext Result.failure(Exception("OneDrive API 오류 (${response.code})"))
            }

            val json = JSONObject(response.body!!.string())
            val valueArray = json.getJSONArray("value")
            val items = mutableListOf<OneDriveItem>()

            for (i in 0 until valueArray.length()) {
                val obj = valueArray.getJSONObject(i)
                val name = obj.optString("name", "")
                val id = obj.optString("id", "")
                val isFolder = obj.has("folder")
                val size = if (obj.has("size")) obj.optLong("size") else null
                val lastModified = try {
                    val dateStr = obj.optString("lastModifiedDateTime", "")
                    if (dateStr.isNotEmpty()) {
                        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                            .parse(dateStr.substringBefore(".").substringBefore("Z"))?.time ?: 0L
                    } else 0L
                } catch (e: Exception) { 0L }
                
                val downloadUrl = obj.optString("@microsoft.graph.downloadUrl", null)
                
                // mimeType 추출 (file > mimeType)
                val mimeType = if (isFolder) {
                    "folder"
                } else {
                    obj.optJSONObject("file")?.optString("mimeType", "application/octet-stream")
                        ?: "application/octet-stream"
                }

                // 폴더이거나 비디오/오디오/자막 파일만 포함
                if (isFolder || isVideoOrAudioOrSubtitleFile(name)) {
                    items.add(
                        OneDriveItem(
                            id = id,
                            name = name,
                            mimeType = mimeType,
                            size = size,
                            lastModified = lastModified,
                            isDirectory = isFolder,
                            downloadUrl = downloadUrl
                        )
                    )
                }
            }

            // 폴더 우선, 이름 순 정렬
            val sorted = items.sortedWith(compareByDescending<OneDriveItem> { it.isDirectory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            
            Result.success(sorted)
        } catch (e: Exception) {
            Log.e(TAG, "listFiles 실패: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 파일 스트리밍 URL을 반환합니다.
     * @microsoft.graph.downloadUrl이 있으면 사용하고, 없으면 content API를 호출합니다.
     */
    fun getStreamUrl(fileId: String): String {
        // Graph API content 엔드포인트 (리다이렉트로 실제 다운로드 URL 반환)
        return "$GRAPH_BASE/me/drive/items/$fileId/content"
    }

    /**
     * 파일의 직접 다운로드 URL을 가져옵니다.
     * @microsoft.graph.downloadUrl이 포함된 메타데이터를 조회합니다.
     */
    suspend fun getDirectDownloadUrl(fileId: String): String? = withContext(Dispatchers.IO) {
        try {
            val accessToken = OneDriveAuthManager.getAccessToken() ?: return@withContext null

            val request = Request.Builder()
                .url("$GRAPH_BASE/me/drive/items/$fileId")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val json = JSONObject(response.body!!.string())
            json.optString("@microsoft.graph.downloadUrl", null)
        } catch (e: Exception) {
            Log.e(TAG, "getDirectDownloadUrl 실패: ${e.message}", e)
            null
        }
    }
}
