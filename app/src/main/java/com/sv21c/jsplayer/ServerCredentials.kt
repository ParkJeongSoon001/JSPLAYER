package com.sv21c.jsplayer

import org.json.JSONArray
import org.json.JSONObject

data class ServerCredentials(
    val type: String,        // "SMB" or "WEBDAV"
    val host: String,        // IP or URL
    val username: String,
    val password: String,
    val displayName: String  // 사용자가 지정하는 표시용 이름
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        put("host", host)
        put("username", username)
        put("password", password)
        put("displayName", displayName)
    }

    companion object {
        fun fromJson(json: JSONObject) = ServerCredentials(
            type        = json.optString("type", "SMB"),
            host        = json.optString("host", ""),
            username    = json.optString("username", ""),
            password    = json.optString("password", ""),
            displayName = json.optString("displayName", "")
        )

        fun listFromJson(jsonArray: JSONArray): List<ServerCredentials> =
            (0 until jsonArray.length()).map { fromJson(jsonArray.getJSONObject(it)) }

        fun listToJson(list: List<ServerCredentials>): JSONArray =
            JSONArray().also { arr -> list.forEach { arr.put(it.toJson()) } }
    }
}
