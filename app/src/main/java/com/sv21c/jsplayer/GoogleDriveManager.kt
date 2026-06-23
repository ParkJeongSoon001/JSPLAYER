package com.sv21c.jsplayer

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

data class GoogleDriveItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long?,
    val modifiedTime: Long?
) {
    val isDirectory: Boolean get() = mimeType == "application/vnd.google-apps.folder"
}

object GoogleDriveManager {

    fun isVideoFile(mimeType: String, name: String): Boolean {
        if (mimeType.startsWith("video/")) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return listOf("mp4", "mkv", "avi", "mov", "ts", "wmv", "flv").contains(ext)
    }

    fun isAudioFile(mimeType: String, name: String): Boolean {
        if (mimeType.startsWith("audio/")) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return listOf("mp3", "flac", "ape", "m4a", "wav", "ogg", "aac").contains(ext)
    }

    private fun getDriveService(context: Context, account: GoogleSignInAccount): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(
            context, listOf(DriveScopes.DRIVE_READONLY)
        )
        credential.selectedAccount = account.account

        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("SVC PLAYER")
            .build()
    }

    /**
     * 특정 폴더의 파일/폴더 리스트 조회 (비디오, 자막(smi, srt, ass, vtt), 폴더만 필터링)
     * @param folderId 조회할 폴더 ID (최상단은 "root")
     */
    suspend fun listFiles(context: Context, account: GoogleSignInAccount, folderId: String = "root"): Result<List<GoogleDriveItem>> = withContext(Dispatchers.IO) {
        try {
            val driveService = getDriveService(context, account)
            val query = "'$folderId' in parents and trashed = false and " +
                    "(mimeType = 'application/vnd.google-apps.folder' or " +
                    "mimeType contains 'video/' or " +
                    "mimeType contains 'audio/' or " +
                    "name contains '.smi' or name contains '.srt' or name contains '.ass' or name contains '.vtt' or " +
                    "name contains '.mp3' or name contains '.flac' or name contains '.ape' or name contains '.m4a' or name contains '.wav')"
            
            val result = driveService.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setFields("files(id, name, mimeType, size, modifiedTime)")
                .setOrderBy("folder, name")
                .setPageSize(1000)
                .execute()
                
            val items = result.files?.map { file ->
                GoogleDriveItem(
                    id = file.id,
                    name = file.name,
                    mimeType = file.mimeType,
                    size = file.getSize(),
                    modifiedTime = file.modifiedTime?.value
                )
            } ?: emptyList()
            Result.success(items)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 파일 다운로드 URL (재생용) 반환 (ExoPlayer DataSource가 헤더를 포함하여 요청해야 함)
     */
    fun getStreamUrl(fileId: String): String {
        return "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
    }
    
    /**
     * 현재 인증된 계정의 Access Token을 가져옵니다. 
     * ExoPlayer에서 접근 시 Authorization 헤더에 주입하기 위함입니다.
     */
    @Suppress("DEPRECATION")
    suspend fun getAccessToken(context: Context, account: GoogleSignInAccount): String? = withContext(Dispatchers.IO) {
        try {
            val credential = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE_READONLY))
            credential.selectedAccount = account.account
            credential.token
        } catch (e: Exception) {
            null
        }
    }
}
