package com.sv21c.jsplayer

import android.content.Context
import android.content.SharedPreferences

object PlayHistoryStore {
    private const val PREFS_NAME = "jsplayer_play_history"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun markPlayed(context: Context, videoUrl: String) {
        prefs(context).edit().putLong(videoUrl, System.currentTimeMillis()).apply()
    }

    fun getLastPlayed(context: Context, videoUrl: String): Long {
        return prefs(context).getLong(videoUrl, 0L)
    }
}
