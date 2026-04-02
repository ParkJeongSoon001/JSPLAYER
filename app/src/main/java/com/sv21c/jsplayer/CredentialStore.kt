package com.sv21c.jsplayer

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray

/**
 * EncryptedSharedPreferences 기반 자격증명 암호화 저장소
 * - MasterKey AES256-GCM 사용
 * - JSON 배열 형태로 저장
 */
object CredentialStore {
    private const val PREF_NAME = "secure_server_prefs"
    private const val KEY_SERVERS = "saved_servers"
    private const val TAG = "CredentialStore"

    private fun getPrefs(context: Context) = try {
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
        Log.e(TAG, "Failed to create EncryptedSharedPreferences: ${e.message}")
        null
    }

    /** 저장된 모든 서버 목록 반환 */
    fun loadAll(context: Context): List<ServerCredentials> {
        return try {
            val prefs = getPrefs(context) ?: return emptyList()
            val json = prefs.getString(KEY_SERVERS, "[]") ?: "[]"
            ServerCredentials.listFromJson(JSONArray(json))
        } catch (e: Exception) {
            Log.e(TAG, "loadAll failed: ${e.message}")
            emptyList()
        }
    }

    /** 서버 추가 또는 업데이트 (host + type 기준으로 중복 체크) */
    fun save(context: Context, credentials: ServerCredentials) {
        try {
            val prefs = getPrefs(context) ?: return
            val current = loadAll(context).toMutableList()
            val idx = current.indexOfFirst { it.host == credentials.host && it.type == credentials.type }
            if (idx >= 0) current[idx] = credentials else current.add(0, credentials)
            prefs.edit().putString(KEY_SERVERS,
                ServerCredentials.listToJson(current).toString()).apply()
            Log.d(TAG, "Saved credentials for ${credentials.type}:${credentials.host}")
        } catch (e: Exception) {
            Log.e(TAG, "save failed: ${e.message}")
        }
    }

    /** 서버 삭제 */
    fun remove(context: Context, credentials: ServerCredentials) {
        try {
            val prefs = getPrefs(context) ?: return
            val updated = loadAll(context).filter {
                !(it.host == credentials.host && it.type == credentials.type)
            }
            prefs.edit().putString(KEY_SERVERS,
                ServerCredentials.listToJson(updated).toString()).apply()
            Log.d(TAG, "Removed credentials for ${credentials.type}:${credentials.host}")
        } catch (e: Exception) {
            Log.e(TAG, "remove failed: ${e.message}")
        }
    }

    /** type("SMB" or "WEBDAV")으로 필터링 */
    fun loadByType(context: Context, type: String): List<ServerCredentials> =
        loadAll(context).filter { it.type == type }
}
