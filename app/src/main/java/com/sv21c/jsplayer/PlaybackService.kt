package com.sv21c.jsplayer

import android.content.Intent
import android.app.PendingIntent
import android.os.Bundle
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class PlaybackService : MediaSessionService() {

    override fun onCreate() {
        super.onCreate()
        
        // MediaController 바인딩 없이 직접 서비스를 시작했으므로, 
        // 서비스 생성 시점에 강제로(직접) MediaSession을 만들고 addSession() 해줘야 알림창이 생성됩니다.
        if (PlayerSingleton.mediaSession == null) {
            val player = PlayerSingleton.player
            if (player != null) {
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pendingIntent = PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )

                // 커스텀 종료(X) 버튼 액션 정의
                val actionClose = "action_close_app"
                val closeCommand = SessionCommand(actionClose, Bundle.EMPTY)
                val closeButton = CommandButton.Builder()
                    .setDisplayName("종료")
                    .setSessionCommand(closeCommand)
                    .setIconResId(android.R.drawable.ic_menu_close_clear_cancel) // 시스템 기본 X 모양 아이콘
                    .build()

                PlayerSingleton.mediaSession = MediaSession.Builder(this, player)
                    .setSessionActivity(pendingIntent)
                    .setCallback(object : MediaSession.Callback {
                        override fun onConnect(
                            session: MediaSession,
                            controller: MediaSession.ControllerInfo
                        ): MediaSession.ConnectionResult {
                            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                                .add(closeCommand)
                                .build()
                            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                                .setAvailableSessionCommands(sessionCommands)
                                .build()
                        }

                        override fun onCustomCommand(
                            session: MediaSession,
                            controller: MediaSession.ControllerInfo,
                            customCommand: SessionCommand,
                            args: Bundle
                        ): ListenableFuture<SessionResult> {
                            if (customCommand.customAction == actionClose) {
                                // X 버튼 터치 시 플레이 종료 및 알림창 강제 삭제
                                session.player.stop()
                                session.player.clearMediaItems()
                                stopSelf()
                            }
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                        }
                    })
                    .build()
                
                // 알림창 패널에 커스텀 버튼 추가 (보통 재생컨트롤러 맨 끝에 붙음)
                PlayerSingleton.mediaSession?.setCustomLayout(listOf(closeButton))
            }
        }
        
        PlayerSingleton.mediaSession?.let {
            addSession(it)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return PlayerSingleton.mediaSession
    }

    override fun onDestroy() {
        PlayerSingleton.mediaSession?.let {
            removeSession(it)
            it.release()
        }
        PlayerSingleton.mediaSession = null
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = PlayerSingleton.mediaSession?.player
        if (player != null) {
            if (!player.playWhenReady || player.mediaItemCount == 0) {
                stopSelf()
            }
        }
    }
}
