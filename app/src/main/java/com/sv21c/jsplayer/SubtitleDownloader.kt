package com.sv21c.jsplayer

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import android.net.Uri

object SubtitleDownloader {
    suspend fun downloadAndConvertSmi(
        uriStr: String,
        context: android.content.Context
    ): Pair<String?, String?> {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val uri = Uri.parse(uriStr)
                val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(8000)
                    .setReadTimeoutMs(10000)
                var finalUriStr = uriStr
                if (uri.scheme?.lowercase()?.startsWith("http") == true) {
                    val userInfo = uri.userInfo
                    val encodedUserInfo = uri.encodedUserInfo
                    if (!userInfo.isNullOrBlank() && !encodedUserInfo.isNullOrBlank()) {
                        val authHeader = "Basic " + android.util.Base64.encodeToString(userInfo.toByteArray(kotlin.text.Charsets.UTF_8), android.util.Base64.NO_WRAP)
                        httpDataSourceFactory.setDefaultRequestProperties(mapOf("Authorization" to authHeader))
                        finalUriStr = uriStr.replaceFirst("://$encodedUserInfo@", "://")
                    }
                }
                
                val defaultDataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, httpDataSourceFactory)
                val dataSource = defaultDataSourceFactory.createDataSource()
                
                val finalUri = Uri.parse(finalUriStr)
                val dataSpec = DataSpec(finalUri)
                
                val activeDataSource = if (finalUri.scheme?.lowercase() == "smb") {
                    SmbDataSource { u ->
                        val ui = u.userInfo
                        val un = ui?.substringBefore(":") ?: ""
                        val pw = ui?.substringAfter(":") ?: ""
                        SmbManager.buildContext(un, pw)
                    }
                } else {
                    dataSource
                }

                activeDataSource.open(dataSpec)
                
                val buffer = java.io.ByteArrayOutputStream()
                val tempBuffer = ByteArray(4096)
                while (true) {
                    val bytesRead = activeDataSource.read(tempBuffer, 0, tempBuffer.size)
                    if (bytesRead == -1 || bytesRead == 0) break
                    buffer.write(tempBuffer, 0, bytesRead)
                }
                activeDataSource.close()
                
                val bytes = buffer.toByteArray()
                if (bytes.isEmpty()) return@withContext null to null
                
                var smiContent = ""
                val b0 = if (bytes.isNotEmpty()) bytes[0].toInt() and 0xFF else 0
                val b1 = if (bytes.size > 1) bytes[1].toInt() and 0xFF else 0
                
                if (b0 == 0xFF && b1 == 0xFE) {
                    smiContent = String(bytes, kotlin.text.Charsets.UTF_16LE)
                } else if (b0 == 0xFE && b1 == 0xFF) {
                    smiContent = String(bytes, kotlin.text.Charsets.UTF_16BE)
                } else if (b0 == 0x3C && b1 == 0x00) {
                    smiContent = String(bytes, kotlin.text.Charsets.UTF_16LE)
                } else if (b0 == 0x00 && b1 == 0x3C) {
                    smiContent = String(bytes, kotlin.text.Charsets.UTF_16BE)
                } else {
                    smiContent = String(bytes, kotlin.text.Charsets.UTF_8)
                    if (smiContent.contains("\uFFFD") || !smiContent.contains("<sami", true)) {
                        smiContent = String(bytes, java.nio.charset.Charset.forName("EUC-KR"))
                    }
                }
                
                smiContent = smiContent.replace(Regex("charset=euc-kr", RegexOption.IGNORE_CASE), "charset=utf-8")
                smiContent = smiContent.replace(Regex("charset=\"euc-kr\"", RegexOption.IGNORE_CASE), "charset=\"utf-8\"")
                smiContent = smiContent.replace(Regex("charset=cp949", RegexOption.IGNORE_CASE), "charset=utf-8")
                
                val srtContent = convertSmiToSrt(smiContent)
                
                val exoSubsDir = java.io.File(context.cacheDir, ".exo_subs")
                if (!exoSubsDir.exists()) exoSubsDir.mkdirs()
                val outFile = java.io.File(exoSubsDir, "converted_${System.currentTimeMillis()}.srt")
                outFile.writeText(srtContent, Charsets.UTF_8)
                
                Pair("file://" + outFile.absolutePath, "srt")
            } catch (e: Exception) {
                e.printStackTrace()
                null to null
            }
        }
    }
}
