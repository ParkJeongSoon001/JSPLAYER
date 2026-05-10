package com.sv21c.jsplayer

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 즐겨찾기 항목 데이터 모델
 * 다양한 소스(로컬, SMB, WebDAV, FTP, SFTP, DLNA, Google Drive, OneDrive)에서
 * 재생에 필요한 모든 정보를 자체 포함합니다.
 */
data class FavoriteItem(
    val id: String,              // UUID (고유 식별자)
    val title: String,           // 표시 이름 (파일명 or 폴더명)
    val videoUrl: String,        // 재생 URL (폴더일 경우 빈 문자열)
    val isDirectory: Boolean,    // 폴더인지 여부
    val sourceType: String,      // "LOCAL", "SMB", "WEBDAV", "FTP", "SFTP", "FTPS", "DLNA", "GOOGLE_DRIVE", "ONEDRIVE"
    val sourcePath: String,      // 원본 경로 (폴더 탐색용)
    val addedAt: Long,           // 추가 시각 (밀리초)
    val subtitleUrl: String? = null,
    val subtitleExtension: String? = null,
    val ftpEncoding: String = "AUTO",
    val credentialsJson: String? = null,  // 네트워크 접속 시 ServerCredentials JSON
    val httpHeaders: String? = null       // JSON 형태의 HTTP 헤더
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("videoUrl", videoUrl)
        put("isDirectory", isDirectory)
        put("sourceType", sourceType)
        put("sourcePath", sourcePath)
        put("addedAt", addedAt)
        put("subtitleUrl", subtitleUrl ?: JSONObject.NULL)
        put("subtitleExtension", subtitleExtension ?: JSONObject.NULL)
        put("ftpEncoding", ftpEncoding)
        put("credentialsJson", credentialsJson ?: JSONObject.NULL)
        put("httpHeaders", httpHeaders ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject): FavoriteItem = FavoriteItem(
            id = json.optString("id", java.util.UUID.randomUUID().toString()),
            title = json.optString("title", ""),
            videoUrl = json.optString("videoUrl", ""),
            isDirectory = json.optBoolean("isDirectory", false),
            sourceType = json.optString("sourceType", "LOCAL"),
            sourcePath = json.optString("sourcePath", ""),
            addedAt = json.optLong("addedAt", 0L),
            subtitleUrl = json.optString("subtitleUrl", "").takeIf { it.isNotEmpty() && it != "null" },
            subtitleExtension = json.optString("subtitleExtension", "").takeIf { it.isNotEmpty() && it != "null" },
            ftpEncoding = json.optString("ftpEncoding", "AUTO"),
            credentialsJson = json.optString("credentialsJson", "").takeIf { it.isNotEmpty() && it != "null" },
            httpHeaders = json.optString("httpHeaders", "").takeIf { it.isNotEmpty() && it != "null" }
        )
    }
}

/**
 * SharedPreferences 기반 즐겨찾기 저장소
 * - 추가/삭제/토글/조회 기능 제공
 * - videoUrl 또는 sourcePath 기준으로 중복 체크
 */
object FavoriteStore {
    private const val PREFS_NAME = "jsplayer_favorites"
    private const val KEY_FAVORITES = "favorites_list"
    private const val TAG = "FavoriteStore"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 전체 즐겨찾기 목록 반환 (추가 시간 역순) */
    fun getAll(context: Context): List<FavoriteItem> {
        return try {
            val json = prefs(context).getString(KEY_FAVORITES, "[]") ?: "[]"
            val arr = JSONArray(json)
            val list = mutableListOf<FavoriteItem>()
            for (i in 0 until arr.length()) {
                try {
                    list.add(FavoriteItem.fromJson(arr.getJSONObject(i)))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse favorite item at index $i: ${e.message}")
                }
            }
            list.sortedByDescending { it.addedAt }
        } catch (e: Exception) {
            Log.e(TAG, "getAll failed: ${e.message}")
            emptyList()
        }
    }

    /** 즐겨찾기 추가 (중복 체크: videoUrl 또는 sourcePath 기준) */
    fun add(context: Context, item: FavoriteItem) {
        try {
            val current = getAll(context).toMutableList()
            // 중복 체크
            val exists = current.any { existing ->
                if (item.videoUrl.isNotEmpty() && existing.videoUrl.isNotEmpty()) {
                    existing.videoUrl == item.videoUrl
                } else {
                    existing.sourcePath == item.sourcePath && existing.sourceType == item.sourceType
                }
            }
            if (exists) {
                Log.d(TAG, "Already exists: ${item.title}")
                return
            }
            current.add(0, item)
            saveList(context, current)
            Log.d(TAG, "✅ 추가: ${item.title} (${item.sourceType})")
        } catch (e: Exception) {
            Log.e(TAG, "add failed: ${e.message}", e)
        }
    }

    /** ID로 즐겨찾기 삭제 */
    fun remove(context: Context, id: String) {
        try {
            val current = getAll(context).toMutableList()
            val removed = current.removeAll { it.id == id }
            if (removed) {
                saveList(context, current)
                Log.d(TAG, "✅ 삭제: id=$id")
            }
        } catch (e: Exception) {
            Log.e(TAG, "remove failed: ${e.message}", e)
        }
    }

    /** videoUrl 또는 sourcePath 기준으로 삭제 */
    fun removeByKey(context: Context, videoUrl: String, sourcePath: String) {
        try {
            val current = getAll(context).toMutableList()
            current.removeAll { existing ->
                if (videoUrl.isNotEmpty() && existing.videoUrl.isNotEmpty()) {
                    existing.videoUrl == videoUrl
                } else {
                    existing.sourcePath == sourcePath
                }
            }
            saveList(context, current)
        } catch (e: Exception) {
            Log.e(TAG, "removeByKey failed: ${e.message}", e)
        }
    }

    /** 해당 videoUrl이 즐겨찾기인지 확인 */
    fun isFavorite(context: Context, videoUrl: String): Boolean {
        if (videoUrl.isEmpty()) return false
        return getAll(context).any { it.videoUrl == videoUrl }
    }

    /** 해당 sourcePath가 즐겨찾기인지 확인 */
    fun isFavoritePath(context: Context, sourcePath: String): Boolean {
        if (sourcePath.isEmpty()) return false
        return getAll(context).any { it.sourcePath == sourcePath }
    }

    /** videoUrl 또는 sourcePath 기준으로 즐겨찾기 여부 확인 */
    fun isFavoriteByKey(context: Context, videoUrl: String, sourcePath: String): Boolean {
        return if (videoUrl.isNotEmpty()) isFavorite(context, videoUrl)
        else isFavoritePath(context, sourcePath)
    }

    /** 즐겨찾기 토글 (있으면 삭제, 없으면 추가) → 토글 후 상태(true=추가됨) 반환 */
    fun toggle(context: Context, item: FavoriteItem): Boolean {
        val key = if (item.videoUrl.isNotEmpty()) item.videoUrl else item.sourcePath
        return if (isFavoriteByKey(context, item.videoUrl, item.sourcePath)) {
            removeByKey(context, item.videoUrl, item.sourcePath)
            Log.d(TAG, "토글 → 삭제: ${item.title}")
            false
        } else {
            add(context, item)
            Log.d(TAG, "토글 → 추가: ${item.title}")
            true
        }
    }

    /** 즐겨찾기 총 개수 */
    fun count(context: Context): Int = getAll(context).size

    private fun saveList(context: Context, list: List<FavoriteItem>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY_FAVORITES, arr.toString()).apply()
    }
}
