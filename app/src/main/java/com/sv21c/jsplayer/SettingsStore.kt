package com.sv21c.jsplayer

import android.content.Context
import android.content.SharedPreferences

object SettingsStore {
    private const val PREFS_NAME = "jsplayer_settings"
    private const val KEY_SORT_ORDER = "sort_order"
    private const val KEY_AUDIO_PASSTHROUGH = "audio_passthrough"
    private const val KEY_LOCAL_VIEW_MODE = "local_view_mode"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getSortOrder(context: Context): SortOrder {
        val sortOrderName = getPrefs(context).getString(KEY_SORT_ORDER, SortOrder.NAME_ASC.name)
        return try {
            SortOrder.valueOf(sortOrderName ?: SortOrder.NAME_ASC.name)
        } catch (e: Exception) {
            SortOrder.NAME_ASC
        }
    }

    fun saveSortOrder(context: Context, sortOrder: SortOrder) {
        getPrefs(context).edit().putString(KEY_SORT_ORDER, sortOrder.name).apply()
    }

    fun getAudioPassthroughEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUDIO_PASSTHROUGH, false)
    }

    fun saveAudioPassthroughEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUDIO_PASSTHROUGH, enabled).apply()
    }

    fun getLocalViewMode(context: Context): LocalViewMode {
        val modeName = getPrefs(context).getString(KEY_LOCAL_VIEW_MODE, LocalViewMode.ALL_VIDEOS.name)
        return try {
            LocalViewMode.valueOf(modeName ?: LocalViewMode.ALL_VIDEOS.name)
        } catch (e: Exception) {
            LocalViewMode.ALL_VIDEOS
        }
    }

    fun saveLocalViewMode(context: Context, mode: LocalViewMode) {
        getPrefs(context).edit().putString(KEY_LOCAL_VIEW_MODE, mode.name).apply()
    }

    fun getShowProgressBar(context: Context): Boolean {
        return getPrefs(context).getBoolean("show_progress_bar", true)
    }

    fun saveShowProgressBar(context: Context, show: Boolean) {
        getPrefs(context).edit().putBoolean("show_progress_bar", show).apply()
    }

    fun getShowPlayTime(context: Context): Boolean {
        return getPrefs(context).getBoolean("show_play_time", true)
    }

    fun saveShowPlayTime(context: Context, show: Boolean) {
        getPrefs(context).edit().putBoolean("show_play_time", show).apply()
    }

    fun getSeekTime(context: Context): Int {
        return getPrefs(context).getInt("seek_time", 10)
    }

    fun saveSeekTime(context: Context, seconds: Int) {
        getPrefs(context).edit().putInt("seek_time", seconds).apply()
    }

    fun getDoubleClickSeekTime(context: Context): Int {
        return getPrefs(context).getInt("double_click_seek_time", 10)
    }

    fun saveDoubleClickSeekTime(context: Context, seconds: Int) {
        getPrefs(context).edit().putInt("double_click_seek_time", seconds).apply()
    }

    fun getRemoteSeekTime(context: Context): Int {
        return getPrefs(context).getInt("remote_seek_time", 10)
    }

    fun saveRemoteSeekTime(context: Context, seconds: Int) {
        getPrefs(context).edit().putInt("remote_seek_time", seconds).apply()
    }

    fun getBluetoothSeekTime(context: Context): Int {
        return getPrefs(context).getInt("bluetooth_seek_time", 10)
    }

    fun saveBluetoothSeekTime(context: Context, seconds: Int) {
        getPrefs(context).edit().putInt("bluetooth_seek_time", seconds).apply()
    }

    fun getAutoHideTime(context: Context): Int {
        return getPrefs(context).getInt("auto_hide_time", 3)
    }

    fun saveAutoHideTime(context: Context, seconds: Int) {
        getPrefs(context).edit().putInt("auto_hide_time", seconds).apply()
    }

    fun getShowListPlayHistory(context: Context): Boolean {
        return getPrefs(context).getBoolean("show_list_play_history", true)
    }

    fun saveShowListPlayHistory(context: Context, show: Boolean) {
        getPrefs(context).edit().putBoolean("show_list_play_history", show).apply()
    }

    fun getShowListFileInfo(context: Context): Boolean {
        return getPrefs(context).getBoolean("show_list_file_info", true)
    }

    fun saveShowListFileInfo(context: Context, show: Boolean) {
        getPrefs(context).edit().putBoolean("show_list_file_info", show).apply()
    }

    fun getForceHwDecoder(context: Context): Boolean {
        return getPrefs(context).getBoolean("force_hw_decoder", false)
    }

    fun saveForceHwDecoder(context: Context, force: Boolean) {
        getPrefs(context).edit().putBoolean("force_hw_decoder", force).apply()
    }

    fun getUseTextureView(context: Context): Boolean {
        return getPrefs(context).getBoolean("use_texture_view", false)
    }

    fun saveUseTextureView(context: Context, use: Boolean) {
        getPrefs(context).edit().putBoolean("use_texture_view", use).apply()
    }
}
