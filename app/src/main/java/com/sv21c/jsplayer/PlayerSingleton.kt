package com.sv21c.jsplayer

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession

object PlayerSingleton {
    var player: ExoPlayer? = null
    var mediaSession: MediaSession? = null
    var vlcPlayer: VlcPlayerWrapper? = null
    var isVlcActive: Boolean = false
    @Volatile var isFloatingPlayerActive: Boolean = false

    // Activity renderer references are used to hand the video output to/from the app-owned float window.
    var activityPlayerView: androidx.media3.ui.PlayerView? = null
    var activityVlcSurface: android.view.SurfaceView? = null
    var activityVlcSurfaceWidth: Int = 0
    var activityVlcSurfaceHeight: Int = 0
}
