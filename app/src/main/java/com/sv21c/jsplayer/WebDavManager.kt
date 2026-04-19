package com.sv21c.jsplayer

import android.util.Log
import com.thegrizzlylabs.sardineandroid.DavResource
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import okhttp3.OkHttpClient
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class WebDavItem(
    val name: String,
    val href: String,       // Full URL
    val isDirectory: Boolean,
    val contentLength: Long = 0L,
    val lastModified: Long = 0L
)

object WebDavManager {
    private const val TAG = "WebDavManager"

    /**
     * 일반적인 WebDAV 시도 경로 목록.
     * 서버 루트(/)가 PROPFIND 405를 반환하는 경우 순서대로 시도함.
     * - Synology NAS: /webdav
     * - Nextcloud/ownCloud: /remote.php/webdav
     * - 공유기 등 일반 NAS: /dav, /webdav
     */
    private val WEBDAV_PATH_CANDIDATES = listOf(
        "",           // 원래 경로 그대로
        "/webdav",
        "/dav",
        "/remote.php/webdav",
        "/remote.php/dav/files",
        "/owncloud/remote.php/webdav",
        "/nextcloud/remote.php/webdav"
    )

    private fun buildSardine(username: String, password: String): OkHttpSardine {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        val sardine = OkHttpSardine(client)
        if (username.isNotBlank()) {
            sardine.setCredentials(username, password)
        }
        return sardine
    }

    /** URL의 기본 origin(protocol + host + port) 추출 */
    private fun originOf(url: String): String {
        return try {
            val parsed = java.net.URL(url)
            val port = parsed.port
            "${parsed.protocol}://${parsed.host}${if (port != -1) ":$port" else ""}"
        } catch (e: Exception) {
            url.trimEnd('/')
        }
    }

    /**
     * WebDAV URL의 파일/폴더 목록 반환.
     * 루트에서 405가 나올 경우 자동으로 다른 경로를 시도함.
     */
    fun listFiles(url: String, username: String, password: String): Result<List<WebDavItem>> {
        val sardine = buildSardine(username, password)

        // url이 이미 하위 경로를 포함하고 있으면 그대로 시도
        val cleanUrl = if (url.endsWith("/")) url else "$url/"
        val origin = originOf(cleanUrl)

        // 1) 전달된 URL 그대로 먼저 시도
        val directResult = tryList(sardine, cleanUrl)
        if (directResult.isSuccess) return directResult

        // 405 / 403 / 방법 없음 오류면 대체 경로 시도
        val directErr = directResult.exceptionOrNull()?.message ?: ""
        val isMethodError = directErr.contains("405") || directErr.contains("403")
            || directErr.contains("Not Allowed") || directErr.contains("Forbidden")
            || directErr.contains("Not a valid DAV response")
            || directErr.contains("Unexpected response")
            || directErr.contains("404")
        if (!isMethodError) return directResult  // 네트워크 오류 등은 바로 반환

        Log.w(TAG, "Root path failed ($directErr), trying fallback WebDAV paths...")

        // 2) 루트 origin에서 대체 경로 순차 시도
        for (candidate in WEBDAV_PATH_CANDIDATES) {
            if (candidate.isEmpty()) continue  // 빈 경로는 이미 시도함
            val candidateUrl = "$origin$candidate/"
            Log.d(TAG, "Trying WebDAV path: $candidateUrl")
            val res = tryList(sardine, candidateUrl)
            if (res.isSuccess) {
                Log.d(TAG, "WebDAV found at: $candidateUrl")
                return res
            }
        }

        // 모든 경로 실패
        return Result.failure(
            Exception("서버를 찾을 수 없습니다.\n시도한 경로: /, /webdav, /dav, /remote.php/webdav\n\n서버 주소 입력 시 WebDAV 경로를 직접 포함해 주세요.\n예) http://192.168.1.1:5005/webdav")
        )
    }

    private fun tryList(sardine: OkHttpSardine, url: String): Result<List<WebDavItem>> {
        return try {
            val resources: List<DavResource> = sardine.list(url)
            val items = resources.drop(1).map { res ->
                val resHref = res.href?.toString() ?: ""
                val fullUrl = if (resHref.startsWith("http")) resHref
                              else "${url.trimEnd('/')}/${res.name}"
                WebDavItem(
                    name = res.name ?: resHref.trimEnd('/').substringAfterLast('/').ifBlank { "unknown" },
                    href = fullUrl,
                    isDirectory = res.isDirectory,
                    contentLength = res.contentLength ?: 0L,
                    lastModified = res.modified?.time ?: 0L
                )
            }.sortedWith(compareByDescending<WebDavItem> { it.isDirectory }.thenBy { it.name })
            Result.success(items)
        } catch (e: Exception) {
            Log.d(TAG, "tryList failed for $url: ${e.message}")
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

    /** WebDAV HTTP URL에 Basic Auth 정보를 포함한 ExoPlayer 재생 가능 URL 반환 */
    fun buildAuthUrl(url: String, username: String, password: String): String {
        if (username.isBlank()) return url
        return try {
            val parsed = java.net.URL(url)
            val encodedPass = URLEncoder.encode(password, "UTF-8")
            "${parsed.protocol}://$username:$encodedPass@${parsed.host}${if (parsed.port != -1) ":${parsed.port}" else ""}${parsed.path}${if (parsed.query != null) "?${parsed.query}" else ""}"
        } catch (e: Exception) {
            Log.w(TAG, "buildAuthUrl failed, using raw url: ${e.message}")
            url
        }
    }
}
