package com.sv21c.jsplayer

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * ZIP 코덱 팩 설치 유틸리티.
 *
 * MX Player의 Custom Codec과 유사한 방식으로,
 * 사용자가 선택한 ZIP 파일에서 현재 기기 ABI에 맞는
 * libffmpegJNI.so를 추출하여 getExternalFilesDir()에 설치합니다.
 *
 * 지원하는 ZIP 내부 구조:
 * 1) {abi}/libffmpegJNI.so           (AIO 폴더 구조)
 * 2) libffmpegJNI_{abi}.so           (파일명에 ABI 포함)
 * 3) libffmpegJNI.so                 (루트에 단일 파일)
 * 4) codec-info.json 존재 시 참조     (메타데이터 우선)
 */
object CodecZipInstaller {

    private const val TAG = "CodecZipInstaller"
    private const val SO_FILENAME = "libffmpegJNI.so"
    private const val INFO_FILENAME = "codec-info.json"
    private const val PREFS_NAME = "codec_prefs"
    private const val KEY_VERSION = "installed_codec_version"

    /** ZIP 분석 결과 */
    data class ZipAnalysisResult(
        val isValid: Boolean,
        val matchedAbi: String? = null,
        val matchedEntryPath: String? = null,
        val codecVersion: String? = null,
        val estimatedSize: Long = 0L,
        val errorMessage: String? = null
    )

    /** 설치 결과 */
    data class InstallResult(
        val success: Boolean,
        val message: String,
        val version: String? = null
    )

    /**
     * 현재 기기의 대상 ABI를 반환합니다.
     */
    fun getTargetAbi(): String {
        return when {
            Build.SUPPORTED_ABIS.contains("arm64-v8a") -> "arm64-v8a"
            Build.SUPPORTED_ABIS.contains("armeabi-v7a") -> "armeabi-v7a"
            Build.SUPPORTED_ABIS.contains("x86_64") -> "x86_64"
            Build.SUPPORTED_ABIS.contains("x86") -> "x86"
            else -> Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        }
    }

    /**
     * ZIP 파일 내용을 분석하여 설치 가능 여부를 미리 확인합니다.
     */
    suspend fun analyzeZip(context: Context, zipUri: Uri): ZipAnalysisResult =
        withContext(Dispatchers.IO) {
            try {
                val targetAbi = getTargetAbi()
                val entryNames = mutableListOf<String>()
                var codecInfoJson: String? = null

                // 1차 스캔: 엔트리 목록 수집 + codec-info.json 읽기
                context.contentResolver.openInputStream(zipUri)?.use { inputStream ->
                    ZipInputStream(inputStream).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            entryNames.add(entry.name)
                            if (entry.name.endsWith(INFO_FILENAME) && !entry.isDirectory) {
                                codecInfoJson = zis.readBytes().decodeToString()
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                } ?: return@withContext ZipAnalysisResult(
                    isValid = false,
                    errorMessage = "ZIP 파일을 열 수 없습니다."
                )

                // codec-info.json 기반 탐색
                var codecVersion: String? = null
                if (codecInfoJson != null) {
                    try {
                        val json = JSONObject(codecInfoJson!!)
                        codecVersion = json.optString("version", null)
                        val files = json.optJSONObject("files")
                        val abiPath = files?.optString(targetAbi)
                        if (abiPath != null && entryNames.contains(abiPath)) {
                            val size = estimateEntrySize(context, zipUri, abiPath)
                            return@withContext ZipAnalysisResult(
                                isValid = true,
                                matchedAbi = targetAbi,
                                matchedEntryPath = abiPath,
                                codecVersion = codecVersion,
                                estimatedSize = size
                            )
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "codec-info.json 파싱 실패, 폴백 탐색 진행: ${e.message}")
                    }
                }

                // 폴백: 패턴 매칭 탐색
                val matchedEntry = findMatchingSoEntry(entryNames, targetAbi)
                if (matchedEntry != null) {
                    val size = estimateEntrySize(context, zipUri, matchedEntry)
                    return@withContext ZipAnalysisResult(
                        isValid = true,
                        matchedAbi = targetAbi,
                        matchedEntryPath = matchedEntry,
                        codecVersion = codecVersion,
                        estimatedSize = size
                    )
                }

                // 호환 파일 없음
                val availableAbis = entryNames
                    .filter { it.endsWith(".so") }
                    .mapNotNull { extractAbiFromPath(it) }
                    .distinct()

                ZipAnalysisResult(
                    isValid = false,
                    errorMessage = if (availableAbis.isEmpty()) {
                        "유효한 코덱 파일(.so)이 ZIP 안에 없습니다."
                    } else {
                        "이 기기($targetAbi)에 호환되는 코덱이 없습니다.\n" +
                                "ZIP에 포함된 아키텍처: ${availableAbis.joinToString(", ")}"
                    }
                )

            } catch (e: Exception) {
                Log.e(TAG, "ZIP 분석 실패: ${e.message}", e)
                ZipAnalysisResult(
                    isValid = false,
                    errorMessage = "ZIP 파일 분석 중 오류: ${e.message}"
                )
            }
        }

    /**
     * ZIP에서 현재 기기 ABI에 맞는 .so를 추출하여 설치합니다.
     */
    suspend fun installFromZip(
        context: Context,
        zipUri: Uri,
        onProgress: (Float) -> Unit = {}
    ): InstallResult = withContext(Dispatchers.IO) {
        try {
            val analysis = analyzeZip(context, zipUri)
            if (!analysis.isValid || analysis.matchedEntryPath == null) {
                return@withContext InstallResult(
                    success = false,
                    message = analysis.errorMessage ?: "호환 코덱을 찾을 수 없습니다."
                )
            }

            val targetEntryPath = analysis.matchedEntryPath
            val targetFile = FfmpegLoader.getTargetSoFile(context)
            val targetDir = targetFile.parentFile 
                ?: return@withContext InstallResult(
                    success = false,
                    message = "설치 디렉토리를 생성할 수 없습니다."
                )
            
            if (!targetDir.exists()) targetDir.mkdirs()
            val tempFile = File(targetDir, "$SO_FILENAME.tmp")

            // 구버전 파일(외부 저장소) 정리
            cleanupOldExternalFiles(context)

            Log.d(TAG, "ZIP 추출 시작: $targetEntryPath → ${targetFile.absolutePath}")
            onProgress(0.1f)

            // ZIP에서 대상 엔트리 추출
            context.contentResolver.openInputStream(zipUri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (entry.name == targetEntryPath && !entry.isDirectory) {
                            // 임시 파일에 기록
                            FileOutputStream(tempFile).use { fos ->
                                val totalSize = analysis.estimatedSize.coerceAtLeast(1L)
                                val buffer = ByteArray(8192)
                                var bytesRead: Int
                                var totalRead = 0L

                                while (zis.read(buffer).also { bytesRead = it } != -1) {
                                    fos.write(buffer, 0, bytesRead)
                                    totalRead += bytesRead
                                    val progress = 0.1f + (totalRead.toFloat() / totalSize) * 0.8f
                                    onProgress(progress.coerceAtMost(0.9f))
                                }
                            }

                            Log.d(TAG, "추출 완료: ${tempFile.length()} bytes")
                            break
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } ?: return@withContext InstallResult(
                success = false,
                message = "ZIP 파일을 열 수 없습니다."
            )

            // 유효성 검사
            if (!tempFile.exists() || tempFile.length() < 1024) {
                tempFile.delete()
                return@withContext InstallResult(
                    success = false,
                    message = "추출된 파일이 유효하지 않습니다. (${tempFile.length()} bytes)"
                )
            }

            // 기존 파일 교체 (원자적 rename)
            if (targetFile.exists()) {
                targetFile.delete()
            }
            val renamed = tempFile.renameTo(targetFile)
            if (!renamed) {
                // rename 실패 시 복사 후 임시 파일 삭제
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            onProgress(0.95f)

            // 즉시 로드 시도
            val loadSuccess = FfmpegLoader.initialize(context)
            onProgress(1.0f)

            // 버전 정보 저장
            val version = analysis.codecVersion ?: "unknown"
            saveInstalledVersion(context, version)

            if (loadSuccess) {
                InstallResult(
                    success = true,
                    message = "코덱이 성공적으로 설치 및 로드되었습니다!",
                    version = version
                )
            } else {
                val errorDetail = FfmpegLoader.getLastLoadError() ?: "사유 미상"
                InstallResult(
                    success = false,
                    message = "파일 추출은 완료되었으나 로드에 실패했습니다.\n" +
                            "오류: $errorDetail\n" +
                            "기기 ABI: ${getTargetAbi()}"
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "ZIP 설치 실패: ${e.message}", e)
            InstallResult(
                success = false,
                message = "설치 중 오류 발생: ${e.message}"
            )
        }
    }

    /**
     * ZIP 내부에서 현재 기기 ABI에 맞는 .so 엔트리 경로를 찾습니다.
     *
     * 탐색 우선순위:
     * 1) {abi}/libffmpegJNI.so            (예: arm64-v8a/libffmpegJNI.so)
     * 2) **\/{abi}/libffmpegJNI.so        (예: codec/arm64-v8a/libffmpegJNI.so)
     * 3) libffmpegJNI_{abi}.so            (예: libffmpegJNI_arm64-v8a.so)
     * 4) libffmpegJNI.so                  (루트에 단일 파일)
     */
    private fun findMatchingSoEntry(entryNames: List<String>, targetAbi: String): String? {
        // 1순위: {abi}/libffmpegJNI.so
        val directMatch = "$targetAbi/$SO_FILENAME"
        if (entryNames.contains(directMatch)) return directMatch

        // 2순위: 서브폴더 내 {abi}/libffmpegJNI.so
        val subfolderMatch = entryNames.find {
            it.endsWith("$targetAbi/$SO_FILENAME") && !it.startsWith("__MACOSX")
        }
        if (subfolderMatch != null) return subfolderMatch

        // 3순위: libffmpegJNI_{abi}.so (파일명에 ABI 포함)
        val abiSuffixPatterns = listOf(
            "libffmpegJNI_$targetAbi.so",
            "libffmpegJNI-$targetAbi.so"
        )
        for (pattern in abiSuffixPatterns) {
            val match = entryNames.find { it.endsWith(pattern) }
            if (match != null) return match
        }

        // 4순위: 루트에 libffmpegJNI.so 단일 파일
        if (entryNames.contains(SO_FILENAME)) return SO_FILENAME

        // 5순위: 어딘가의 libffmpegJNI.so (마지막 폴백)
        val anyMatch = entryNames.find {
            it.endsWith(SO_FILENAME) && !it.startsWith("__MACOSX")
        }
        return anyMatch
    }

    /**
     * 엔트리 경로에서 ABI 이름을 추출합니다.
     */
    private fun extractAbiFromPath(entryPath: String): String? {
        val knownAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        for (abi in knownAbis) {
            if (entryPath.contains(abi)) return abi
        }
        return null
    }

    /**
     * 특정 엔트리의 크기를 비압축 크기로 추정합니다.
     */
    private fun estimateEntrySize(context: Context, zipUri: Uri, entryPath: String): Long {
        return try {
            context.contentResolver.openInputStream(zipUri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (entry.name == entryPath) {
                            val size = entry.size
                            if (size > 0) return@use size
                            // size가 -1인 경우 실제 읽어서 계산
                            var total = 0L
                            val buf = ByteArray(8192)
                            var n: Int
                            while (zis.read(buf).also { n = it } != -1) {
                                total += n
                            }
                            return@use total
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                    0L
                }
            } ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "엔트리 크기 추정 실패: ${e.message}")
            0L
        }
    }

    /** 설치된 코덱 버전을 저장합니다. */
    fun saveInstalledVersion(context: Context, version: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VERSION, version)
            .apply()
    }

    /** 설치된 코덱 버전을 조회합니다. */
    fun getInstalledVersion(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_VERSION, null)
    }

    /** 설치된 코덱을 삭제합니다. */
    fun deleteCodec(context: Context): Boolean {
        val soFile = FfmpegLoader.getTargetSoFile(context)
        val deleted = if (soFile.exists()) soFile.delete() else true
        
        // 구버전(외부 저장소) 파일도 함께 삭제 시도
        cleanupOldExternalFiles(context)
        
        if (deleted) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_VERSION)
                .apply()
            FfmpegLoader.resetLoadState()
        }
        return deleted
    }

    /** 구버전 외부 저장소 파일 클린업 */
    private fun cleanupOldExternalFiles(context: Context) {
        try {
            val oldFile = File(context.getExternalFilesDir(null), SO_FILENAME)
            if (oldFile.exists()) {
                oldFile.delete()
                Log.d(TAG, "구버전 코덱 파일(외부 저장소) 삭제 완료")
            }
        } catch (e: Exception) {
            Log.w(TAG, "구버전 파일 삭제 중 오류: ${e.message}")
        }
    }

    /** 설치된 .so 파일 크기를 반환합니다. */
    fun getInstalledFileSize(context: Context): Long? {
        val soFile = FfmpegLoader.getTargetSoFile(context)
        return if (soFile.exists()) soFile.length() else null
    }
}
