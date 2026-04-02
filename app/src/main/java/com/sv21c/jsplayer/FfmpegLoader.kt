package com.sv21c.jsplayer

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

import android.media.MediaCodecList

object FfmpegLoader {

    private const val TAG = "FfmpegLoader"
    var isLoaded = false
        private set

    /**
     * 기기가 하드웨어적으로 DTS 디코딩을 자체 지원하는지 확인합니다.
     */
    fun isNativeDtsSupported(): Boolean {
        try {
            val mediaCodecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            return mediaCodecList.codecInfos.any { codecInfo ->
                !codecInfo.isEncoder && codecInfo.supportedTypes.any { type ->
                    type.equals("audio/vnd.dts", ignoreCase = true) ||
                    type.equals("audio/vnd.dts.hd", ignoreCase = true) ||
                    type.equals("audio/ac3", ignoreCase = true) ||
                    type.equals("audio/eac3", ignoreCase = true)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking native DTS support: ${e.message}")
            return false
        }
    }

    /**
     * 지원되는 시스템 아키텍처(ABI) 반환
     */
    fun getRequiredAbi(): String {
        return when {
            Build.SUPPORTED_ABIS.contains("arm64-v8a") -> "arm64-v8a"
            Build.SUPPORTED_ABIS.contains("armeabi-v7a") -> "armeabi-v7a"
            Build.SUPPORTED_ABIS.contains("x86_64") -> "x86_64"
            Build.SUPPORTED_ABIS.contains("x86") -> "x86"
            else -> "unknown (${Build.SUPPORTED_ABIS.firstOrNull() ?: ""})"
        }
    }

    /**
     * libffmpegJNI.so 파일이 있어야 할 기대 경로 반환
     */
    fun getTargetSoFile(context: Context): File {
        return File(context.getExternalFilesDir(null), "libffmpegJNI.so")
    }

    /**
     * 앱 시작 시 호출하여 외부 파일 로드를 시도합니다.
     */
    fun initialize(context: Context): Boolean {
        if (isLoaded) return true

        val soFile = getTargetSoFile(context)
        Log.d(TAG, "Checking for FFmpeg library at: ${soFile.absolutePath}")
        
        if (!soFile.exists()) {
            Log.w(TAG, "libffmpegJNI.so not found at target directory.")
            return false
        }

        return try {
            System.load(soFile.absolutePath)
            isLoaded = true
            Log.d(TAG, "Successfully loaded dynamic libffmpegJNI.so from ${soFile.absolutePath}")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load FFmpeg library due to ABI mismatch or missing dependencies: ${e.message}")
            isLoaded = false
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unknown error while loading FFmpeg library: ${e.message}")
            isLoaded = false
            false
        }
    }
}
