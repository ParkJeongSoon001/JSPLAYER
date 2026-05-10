package com.sv21c.jsplayer

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.tasks.await

object GoogleDriveAuthManager {
    // google_drv_key.txt 에서 추출한 Client ID (선택적으로 사용)
    private const val CLIENT_ID = "46180907367-pdjd7n9g76k7bkfanqdqdam72molbukf.apps.googleusercontent.com"

    fun getSignInClient(context: Context): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_READONLY))
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    suspend fun getSignedInAccount(context: Context): GoogleSignInAccount? {
        return try {
            val task = getSignInClient(context).silentSignIn()
            task.await() // kotlinx.coroutines.tasks.await
        } catch (e: Exception) {
            null
        }
    }

    fun handleSignInResult(data: Intent?): GoogleSignInAccount? {
        return try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            task.getResult(ApiException::class.java)
        } catch (e: ApiException) {
            android.util.Log.e("GoogleDriveAuth", "signInResult:failed code=" + e.statusCode)
            null
        }
    }
    
    fun signOut(context: Context) {
        getSignInClient(context).signOut()
    }
}
