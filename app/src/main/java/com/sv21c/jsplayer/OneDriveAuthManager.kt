package com.sv21c.jsplayer

import android.app.Activity
import android.content.Context
import android.util.Log
import com.microsoft.identity.client.*
import com.microsoft.identity.client.exception.MsalException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * OneDrive(Microsoft Graph) 인증을 담당하는 매니저.
 * GoogleDriveAuthManager와 동일한 역할을 합니다.
 *
 * MSAL PublicClientApplication을 사용하여 OAuth 2.0 로그인/로그아웃을 처리하고
 * Access Token을 관리합니다.
 */
object OneDriveAuthManager {
    private const val TAG = "OneDriveAuth"
    
    // Microsoft Graph에서 파일 읽기 전용 스코프
    private val SCOPES = arrayOf("Files.Read")
    
    private var msalApp: ISingleAccountPublicClientApplication? = null

    /**
     * MSAL 앱 인스턴스를 초기화합니다. 이미 초기화된 경우 기존 인스턴스를 반환합니다.
     */
    suspend fun initialize(context: Context): ISingleAccountPublicClientApplication? {
        if (msalApp != null) return msalApp
        
        return withContext(Dispatchers.IO) {
            try {
                suspendCancellableCoroutine { continuation ->
                    PublicClientApplication.createSingleAccountPublicClientApplication(
                        context.applicationContext,
                        R.raw.msal_config,
                        object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                            override fun onCreated(application: ISingleAccountPublicClientApplication) {
                                msalApp = application
                                Log.d(TAG, "MSAL 앱 초기화 성공")
                                continuation.resume(application)
                            }

                            override fun onError(exception: MsalException) {
                                Log.e(TAG, "MSAL 앱 초기화 실패: ${exception.message}", exception)
                                continuation.resume(null)
                            }
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "MSAL 초기화 예외: ${e.message}", e)
                null
            }
        }
    }
    
    /**
     * 로그인을 수행합니다.
     * 이미 로그인된 계정이 있으면 Silent 토큰 갱신으로 기존 계정을 반환하고,
     * 없을 때만 인터랙티브 로그인 화면을 표시합니다.
     * @return 로그인된 계정의 IAccount 또는 null
     */
    suspend fun signIn(activity: Activity): IAccount? {
        val app = msalApp ?: initialize(activity)
        if (app == null) {
            Log.e(TAG, "MSAL 앱이 초기화되지 않았습니다.")
            return null
        }

        // 1) IO 스레드에서 기존 계정 확인
        val existingAccount = withContext(Dispatchers.IO) {
            try {
                app.currentAccount?.currentAccount
            } catch (e: Exception) {
                Log.e(TAG, "기존 계정 확인 실패: ${e.message}", e)
                null
            }
        }

        if (existingAccount != null) {
            Log.d(TAG, "기존 로그인 계정 발견: ${existingAccount.username}, Silent 토큰 갱신 시도")
            val token = getAccessToken()
            if (token != null) {
                Log.d(TAG, "Silent 토큰 갱신 성공, 기존 계정 재사용")
                return existingAccount
            }
            // Silent 실패 → 로그아웃 후 재로그인
            Log.d(TAG, "Silent 토큰 갱신 실패, 로그아웃 후 재로그인 시도")
            signOut()
        }
        
        // 2) 인터랙티브 로그인 (Main 스레드 필요)
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                app.signIn(
                    SignInParameters.builder()
                        .withActivity(activity)
                        .withScopes(SCOPES.toList())
                        .withCallback(object : AuthenticationCallback {
                            override fun onSuccess(authenticationResult: IAuthenticationResult) {
                                Log.d(TAG, "로그인 성공: ${authenticationResult.account.username}")
                                continuation.resume(authenticationResult.account)
                            }

                            override fun onError(exception: MsalException) {
                                Log.e(TAG, "로그인 실패: ${exception.message}", exception)
                                continuation.resume(null)
                            }

                            override fun onCancel() {
                                Log.d(TAG, "로그인 취소됨")
                                continuation.resume(null)
                            }
                        })
                        .build()
                )
            }
        }
    }

    /**
     * 현재 로그인된 계정을 가져옵니다.
     */
    suspend fun getCurrentAccount(): IAccount? {
        val app = msalApp ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val accountResult = app.currentAccount
                accountResult?.currentAccount
            } catch (e: Exception) {
                Log.e(TAG, "현재 계정 조회 실패: ${e.message}", e)
                null
            }
        }
    }
    
    /**
     * Silent 방식으로 Access Token을 갱신/획득합니다.
     * @return 유효한 Access Token 또는 null
     */
    suspend fun getAccessToken(): String? {
        val app = msalApp ?: return null
        
        return withContext(Dispatchers.IO) {
            try {
                val account = app.currentAccount?.currentAccount ?: return@withContext null
                
                suspendCancellableCoroutine { continuation ->
                    app.acquireTokenSilentAsync(
                        AcquireTokenSilentParameters.Builder()
                            .forAccount(account)
                            .fromAuthority(account.authority)
                            .withScopes(SCOPES.toList())
                            .withCallback(object : SilentAuthenticationCallback {
                                override fun onSuccess(authenticationResult: IAuthenticationResult) {
                                    continuation.resume(authenticationResult.accessToken)
                                }

                                override fun onError(exception: MsalException) {
                                    Log.e(TAG, "Silent 토큰 갱신 실패: ${exception.message}", exception)
                                    continuation.resume(null)
                                }
                            })
                            .build()
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "토큰 획득 예외: ${e.message}", e)
                null
            }
        }
    }
    
    /**
     * 로그아웃 처리
     */
    suspend fun signOut() {
        val app = msalApp ?: return
        withContext(Dispatchers.IO) {
            try {
                suspendCancellableCoroutine<Unit> { continuation ->
                    app.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
                        override fun onSignOut() {
                            Log.d(TAG, "로그아웃 성공")
                            continuation.resume(Unit)
                        }

                        override fun onError(exception: MsalException) {
                            Log.e(TAG, "로그아웃 실패: ${exception.message}", exception)
                            continuation.resume(Unit)
                        }
                    })
                }
            } catch (e: Exception) {
                Log.e(TAG, "로그아웃 예외: ${e.message}", e)
            }
        }
    }
}
