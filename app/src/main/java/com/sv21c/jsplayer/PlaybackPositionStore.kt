package com.sv21c.jsplayer

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences를 이용해 영상 재생 위치를 저장/복원하는 유틸리티
 * - Key: 영상 URL (또는 URI 문자열)
 * - Value: "position|duration" (ms) 형태로 저장
 *
 * 이전 버전에서 putLong()으로 저장된 데이터와의 호환성을 위해
 * getString() 실패 시 getLong()으로 폴백 처리
 */
object PlaybackPositionStore {
    private const val PREFS_NAME = "jsplayer_playback_positions"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 재생 위치와 전체 시간 저장 (새 형식: "position|duration" 문자열) */
    fun savePosition(context: Context, videoUrl: String, positionMs: Long, durationMs: Long = 0L) {
        prefs(context).edit().putString(videoUrl, "$positionMs|$durationMs").apply()
    }

    /** 저장된 재생 위치 가져오기 (없으면 0L) */
    fun getPosition(context: Context, videoUrl: String): Long {
        val p = prefs(context)
        // 이전 버전 호환: putLong()으로 저장된 값이면 ClassCastException 발생
        return try {
            val value = p.getString(videoUrl, null) ?: return 0L
            value.split("|")[0].toLongOrNull() ?: 0L
        } catch (e: ClassCastException) {
            // 이전 형태 (Long 직접 저장) 호환
            try {
                val oldVal = p.getLong(videoUrl, 0L)
                // 새 형식으로 마이그레이션
                if (oldVal > 0L) {
                    p.edit().remove(videoUrl).apply()
                    p.edit().putString(videoUrl, "$oldVal|0").apply()
                }
                oldVal
            } catch (_: Exception) { 0L }
        }
    }

    /** 저장된 전체 재생 시간 가져오기 (없으면 0L) */
    fun getDuration(context: Context, videoUrl: String): Long {
        val p = prefs(context)
        return try {
            val value = p.getString(videoUrl, null) ?: return 0L
            val parts = value.split("|")
            if (parts.size >= 2) parts[1].toLongOrNull() ?: 0L else 0L
        } catch (e: ClassCastException) {
            // 이전 형태 Long — duration 정보 없음
            0L
        }
    }

    /** 저장된 재생 위치 삭제 (영상 재생 완료 시) */
    fun removePosition(context: Context, videoUrl: String) {
        prefs(context).edit().remove(videoUrl).apply()
    }

    /** 재생 위치를 HH:MM:SS 포맷 문자열로 변환 */
    fun formatPosition(positionMs: Long): String {
        if (positionMs <= 0L) return ""
        val totalSec = positionMs / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(java.util.Locale.US, "%d:%02d", m, s)
    }
}
