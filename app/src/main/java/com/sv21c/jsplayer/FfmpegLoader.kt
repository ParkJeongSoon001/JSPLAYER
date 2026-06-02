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
     * 앱 시작 시 호출하여 FFmpeg 네이티브 라이브러리를 로드합니다.
     *
     * 로드 우선순위:
     * 1) APK 번들 libffmpegJNI.so (System.loadLibrary) — 비디오+오디오 디코더 포함
     * 2) 사용자 수동 설치 .so (System.load) — 코덱 팩 ZIP으로 설치된 경우
     */
    fun initialize(context: Context): Boolean {
        if (isLoaded) {
            checkMedia3Availability()
            return true
        }

        Log.d(TAG, "FFmpeg 초기화 시작 (ABI: ${getRequiredAbi()})")

        // ── 1순위: APK 번들 .so (System.loadLibrary) ──────────────
        // build.gradle.kts에서 excludes 제거로 APK에 번들됨
        // Jellyfin AAR의 libffmpegJNI.so: mpeg4, wmv3, vp6, h263 등 비디오 디코더 포함
        try {
            System.loadLibrary("ffmpegJNI")
            isLoaded = true
            lastLoadError = null
            Log.i(TAG, "✅ APK 번들 libffmpegJNI.so 로드 성공 (비디오+오디오 디코더 포함)")
            forceMedia3LibraryLoaded()
            checkMedia3Availability()
            return true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "APK 번들 .so 로드 실패: ${e.message}")
        }

        // ── 2순위: 사용자 수동 설치 .so (System.load) ─────────────
        val soFile = getTargetSoFile(context)
        Log.d(TAG, "폴백: 수동 설치 .so 확인 → ${soFile.absolutePath}")

        if (!soFile.exists()) {
            Log.w(TAG, "수동 설치 .so도 없음. FFmpeg 라이브러리 사용 불가.")
            return false
        }

        return try {
            System.load(soFile.absolutePath)
            isLoaded = true
            lastLoadError = null
            Log.d(TAG, "✅ 수동 설치 .so 로드 성공: ${soFile.absolutePath} (${soFile.length()} bytes)")
            forceMedia3LibraryLoaded()
            checkMedia3Availability()
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "수동 설치 .so 로드 실패 (ABI 불일치): ${e.message}")
            lastLoadError = "ABI 불일치 또는 의존성 오류: ${e.message}"
            isLoaded = false
            false
        } catch (e: Exception) {
            Log.e(TAG, "수동 설치 .so 로드 중 알 수 없는 오류: ${e.message}")
            lastLoadError = "알 수 없는 오류: ${e.message}"
            isLoaded = false
            false
        }
    }

    /**
     * AAR에 번들된 libffmpegJNI.so를 앱 내부 저장소로 추출합니다. (deprecated)
     * build.gradle.kts에서 .so 제외가 제거되어 System.loadLibrary()로 직접 로드 가능.
     */
    @Suppress("unused")
    private fun extractBundledSoFromAar(context: Context, targetFile: File): Boolean {
        Log.w(TAG, "extractBundledSoFromAar: 더 이상 필요하지 않음. APK 번들 .so 사용.")
        return false
    }

    /**
     * Media3 LibraryLoader의 내부 isLoaded 플래그를 강제로 true로 설정합니다.
     * 외부 .so 파일을 System.load()로 직접 로드했기 때문에, Media3 내부의 
     * System.loadLibrary("ffmpegJNI") 호출 실패로 인한 availability 판단 오류를 방어합니다.
     */
    private fun forceMedia3LibraryLoaded() {
        try {
            // 1. FfmpegLibrary 클래스 로드
            val ffmpegLibraryClass = Class.forName("androidx.media3.decoder.ffmpeg.FfmpegLibrary")
            
            // 2. LOADER static 필드 룩업
            val loaderField = ffmpegLibraryClass.getDeclaredField("LOADER")
            loaderField.isAccessible = true
            val loader = loaderField.get(null)
            
            if (loader != null) {
                // 3. LibraryLoader 클래스의 필드 룩업 및 설정
                val loaderClass = loader.javaClass
                
                // isLoaded 필드 강제 주입
                val isLoadedField = loaderClass.getDeclaredField("isLoaded")
                isLoadedField.isAccessible = true
                isLoadedField.setBoolean(loader, true)
                
                // failed 필드 강제 리셋
                try {
                    val failedField = loaderClass.getDeclaredField("failed")
                    failedField.isAccessible = true
                    failedField.setBoolean(loader, false)
                } catch (_: Exception) {}
                
                Log.i(TAG, "✅ Successfully forced Media3 LibraryLoader.isLoaded = true")
            }
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ Failed to force Media3 LibraryLoader state: ${e.message}")
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
                
                // FFmpeg이 지원하는 코덱 진단
                try {
                    val supportsFormatMethod = clazz.getMethod("supportsFormat", String::class.java)
                    val testMimeTypes = listOf(
                        // 비디오 코덱
                        "video/mp4v-es" to "MPEG-4 Part 2 (DivX/Xvid)",
                        "video/x-ms-wmv" to "WMV",
                        "video/x-vnd.on2.vp6" to "VP6",
                        "video/3gpp" to "H.263",
                        "video/mpeg" to "MPEG-1",
                        "video/mpeg2" to "MPEG-2",
                        // 오디오 코덱  
                        "audio/ac3" to "AC3 (Dolby Digital)",
                        "audio/eac3" to "E-AC3",
                        "audio/vnd.dts" to "DTS",
                        "audio/x-ms-wma" to "WMA"
                    )
                    
                    val videoSupported = mutableListOf<String>()
                    val videoUnsupported = mutableListOf<String>()
                    
                    for ((mime, name) in testMimeTypes) {
                        val supported = supportsFormatMethod.invoke(null, mime) as Boolean
                        val emoji = if (supported) "✅" else "❌"
                        Log.i(TAG, "$emoji FFmpeg codec: $name ($mime) = ${if (supported) "SUPPORTED" else "NOT SUPPORTED"}")
                        
                        if (mime.startsWith("video/")) {
                            if (supported) videoSupported.add(name) else videoUnsupported.add(name)
                        }
                    }
                    
                    if (videoSupported.isEmpty()) {
                        Log.w(TAG, "⚠️ FFmpeg에 비디오 디코더가 없습니다! DivX/Xvid 재생 불가.")
                        Log.w(TAG, "⚠️ 비디오 디코더 포함된 코덱 팩을 설치해야 합니다.")
                    } else {
                        Log.i(TAG, "🎬 FFmpeg 비디오 디코더 지원: ${videoSupported.joinToString(", ")}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "FFmpeg 코덱 진단 실패: ${e.message}")
                }
                
                // ExperimentalFfmpegVideoRenderer 존재 확인
                try {
                    Class.forName("androidx.media3.decoder.ffmpeg.ExperimentalFfmpegVideoRenderer")
                    Log.i(TAG, "✅ ExperimentalFfmpegVideoRenderer 클래스 사용 가능")
                } catch (e: ClassNotFoundException) {
                    Log.w(TAG, "❌ ExperimentalFfmpegVideoRenderer 클래스 없음 — FFmpeg 비디오 디코딩 불가")
                }
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
