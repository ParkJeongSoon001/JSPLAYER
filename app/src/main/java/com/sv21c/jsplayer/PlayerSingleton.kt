package com.sv21c.jsplayer

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession

object PlayerSingleton {
    var player: ExoPlayer? = null
    var mediaSession: MediaSession? = null
}
