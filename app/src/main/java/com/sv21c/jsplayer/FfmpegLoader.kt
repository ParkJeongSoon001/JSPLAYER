package com.sv21c.jsplayer

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

import android.media.MediaCodecList

/**
 * FFmpeg 네이티브 라이브러리(.so)의 동적 로딩 관리자.
 *
 * 앱 시작 시 getExternalFilesDir()에서 libffmpegJNI.so를 탐색하여
 * System.load()로 메모리에 로드합니다.
 *
 * ZIP 코덱 팩(CodecZipInstaller)을 통해 설치된 .so 파일을 처리합니다.
 */
object FfmpegLoader {

    private const val TAG = "FfmpegLoader"
    private const val SO_FILENAME = "libffmpegJNI.so"

    var isLoaded = false
        private set

    /** 코덱 상태를 나타내는 enum */
    enum class CodecStatus {
        NOT_INSTALLED,    // 코덱 파일 없음
        LOADED,           // 정상 로드됨
        LOAD_ERROR,       // 파일은 있으나 로드 실패
        NATIVE_SUPPORT    // 기기 하드웨어 자체 지원 (코덱 불필요)
    }

    private var lastLoadError: String? = null

    /**
     * 현재 코덱 상태를 반환합니다.
     */
    fun getStatus(context: Context): CodecStatus {
        if (isLoaded) return CodecStatus.LOADED
        // 사용자 요청: 하드웨어 지원 팝업 비활성화를 위해 체크 스킵
        // if (isNativeDtsSupported()) return CodecStatus.NATIVE_SUPPORT

        val soFile = getTargetSoFile(context)
        if (!soFile.exists()) return CodecStatus.NOT_INSTALLED

        // 파일은 있으나 아직 로드되지 않았거나 에러
        return if (lastLoadError != null) CodecStatus.LOAD_ERROR
        else CodecStatus.NOT_INSTALLED
    }

    /**
     * 마지막 로드 에러 메시지를 반환합니다.
     */
    fun getLastLoadError(): String? = lastLoadError

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
     * libffmpegJNI.so 파일이 있어야 할 기대 경로 반환 (내부 저장소)
     */
    fun getTargetSoFile(context: Context): File {
        // 보안 및 실행 권한을 위해 내부 전용 폴더(Internal Storage) 사용
        // /data/user/0/com.sv21c.jsplayer/app_codecs/libffmpegJNI.so
        val codecsDir = context.getDir("codecs", Context.MODE_PRIVATE)
        return File(codecsDir, SO_FILENAME)
    }

    /**
     * 설치된 .so 파일의 크기를 반환합니다 (null이면 미설치).
     */
    fun getInstalledFileSize(context: Context): Long? {
        val soFile = getTargetSoFile(context)
        return if (soFile.exists()) soFile.length() else null
    }

    /**
     * 설치된 코덱 버전 문자열을 반환합니다.
     */
    fun getInstalledVersion(context: Context): String? {
        return CodecZipInstaller.getInstalledVersion(context)
    }

    /**
     * 앱 시작 시 호출하여 외부 파일 로드를 시도합니다.
     */
    fun initialize(context: Context): Boolean {
        if (isLoaded) {
            checkMedia3Availability()
            return true
        }

        val soFile = getTargetSoFile(context)
        Log.d(TAG, "Checking for FFmpeg library at: ${soFile.absolutePath} (ABI: ${getRequiredAbi()})")
        
        if (!soFile.exists()) {
            Log.w(TAG, "libffmpegJNI.so not found at target directory.")
            return false
        }

        return try {
            // JVM에 네이티브 라이브러리 로드
            System.load(soFile.absolutePath)
            isLoaded = true
            lastLoadError = null
            Log.d(TAG, "Successfully loaded dynamic libffmpegJNI.so from ${soFile.absolutePath}")
            
            // Media3 확장 라이브러리가 이를 인식하는지 확인
            checkMedia3Availability()
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load FFmpeg library due to ABI mismatch or missing dependencies: ${e.message}")
            lastLoadError = "ABI 불일치 또는 의존성 오류: ${e.message}"
            isLoaded = false
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unknown error while loading FFmpeg library: ${e.message}")
            lastLoadError = "알 수 없는 오류: ${e.message}"
            isLoaded = false
            false
        }
    }

    /**
     * Media3 FFmpeg 확장 코드가 이 라이브러리를 사용할 수 있는지 진단합니다.
     */
    private fun checkMedia3Availability() {
        try {
            // 리플렉션을 사용하여 Media3 확장 라이브러리의 가용 여부 확인
            val clazz = Class.forName("androidx.media3.decoder.ffmpeg.FfmpegLibrary")
            val isAvailableMethod = clazz.getMethod("isAvailable")
            val isAvailable = isAvailableMethod.invoke(null) as Boolean
            
            if (isAvailable) {
                Log.i(TAG, "✅ Media3 FFmpeg extension is READY to use the native library.")
            } else {
                Log.w(TAG, "⚠️ Media3 FFmpeg extension reports NOT AVAILABLE. (Native library loaded but not recognized by extension)")
                // 이 상황이 발생하면, extension 내부에서 System.loadLibrary("ffmpegJNI")가 실패했음을 의미함.
            }
        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "Media3 FFmpeg extension (lib-ffmpeg) NOT found on classpath.")
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Media3 FFmpeg availability: ${e.message}")
        }
    }

    /**
     * 로드 상태를 초기화합니다. (코덱 삭제 시 호출)
     */
    fun resetLoadState() {
        isLoaded = false
        lastLoadError = null
    }

    /**
     * 코덱을 삭제합니다.
     */
    fun deleteCodec(context: Context): Boolean {
        return CodecZipInstaller.deleteCodec(context)
    }
}
