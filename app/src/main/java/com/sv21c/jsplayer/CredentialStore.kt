package com.sv21c.jsplayer

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * 자격증명 저장소
 * - 1차: EncryptedSharedPreferences (암호화 저장)
 * - 2차: 일반 SharedPreferences (폴백, 암호화 불가 기기 대응)
 * - commit() 사용으로 동기적 디스크 쓰기 보장
 */
object CredentialStore {
    private const val PREF_NAME = "secure_server_prefs"
    private const val FALLBACK_PREF_NAME = "server_prefs_fallback"
    private const val KEY_SERVERS = "saved_servers"
    private const val TAG = "CredentialStore"

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    private fun getPrefs(context: Context): SharedPreferences? {
        cachedPrefs?.let { return it }
        return synchronized(this) {
            cachedPrefs?.let { return it }
            val prefs = try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    PREF_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                Log.w(TAG, "EncryptedSharedPreferences 생성 실패, 일반 SharedPreferences로 폴백: ${e.message}")
                try {
                    context.getSharedPreferences(FALLBACK_PREF_NAME, Context.MODE_PRIVATE)
                } catch (e2: Exception) {
                    Log.e(TAG, "SharedPreferences 폴백도 실패: ${e2.message}")
                    null
                }
            }
            cachedPrefs = prefs
            prefs
        }
    }

    /** 저장된 모든 서버 목록 반환 */
    fun loadAll(context: Context): List<ServerCredentials> {
        return try {
            val prefs = getPrefs(context) ?: return emptyList()
            val json = prefs.getString(KEY_SERVERS, "[]") ?: "[]"
            val result = ServerCredentials.listFromJson(JSONArray(json))
            Log.d(TAG, "loadAll: ${result.size}개 서버 로드됨")
            result
        } catch (e: Exception) {
            Log.e(TAG, "loadAll failed: ${e.message}")
            emptyList()
        }
    }

    /** 서버 추가 또는 업데이트 (host + type 기준으로 중복 체크) */
    fun save(context: Context, credentials: ServerCredentials) {
        try {
            val prefs = getPrefs(context)
            if (prefs == null) {
                Log.e(TAG, "save 실패: SharedPreferences가 null입니다")
                return
            }
            val current = loadAll(context).toMutableList()
            val idx = current.indexOfFirst { it.host == credentials.host && it.type == credentials.type }
            if (idx >= 0) current[idx] = credentials else current.add(0, credentials)
            val jsonStr = ServerCredentials.listToJson(current).toString()
            // commit()으로 동기 저장 보장
            val success = prefs.edit().putString(KEY_SERVERS, jsonStr).commit()
            if (success) {
                Log.d(TAG, "✅ 저장 성공: ${credentials.type}:${credentials.host} (총 ${current.size}개)")
            } else {
                Log.e(TAG, "❌ 저장 실패 (commit 반환 false): ${credentials.type}:${credentials.host}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "save failed: ${e.message}", e)
        }
    }

    /** 서버 삭제 */
    fun remove(context: Context, credentials: ServerCredentials) {
        try {
            val prefs = getPrefs(context)
            if (prefs == null) {
                Log.e(TAG, "remove 실패: SharedPreferences가 null입니다")
                return
            }
            val updated = loadAll(context).filter {
                !(it.host == credentials.host && it.type == credentials.type)
            }
            // commit()으로 동기 저장 보장
            val success = prefs.edit().putString(KEY_SERVERS,
                ServerCredentials.listToJson(updated).toString()).commit()
            if (success) {
                Log.d(TAG, "✅ 삭제 성공: ${credentials.type}:${credentials.host}")
            } else {
                Log.e(TAG, "❌ 삭제 실패 (commit 반환 false): ${credentials.type}:${credentials.host}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "remove failed: ${e.message}", e)
        }
    }

    /** type으로 필터링 ("SMB", "WEBDAV", "FTP", "SFTP") */
    fun loadByType(context: Context, type: String): List<ServerCredentials> {
        val all = loadAll(context)
        val filtered = all.filter { it.type == type }
        Log.d(TAG, "loadByType($type): 전체 ${all.size}개 중 ${filtered.size}개 매칭")
        return filtered
    }
}
