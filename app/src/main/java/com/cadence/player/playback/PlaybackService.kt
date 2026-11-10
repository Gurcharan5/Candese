package com.cadence.player.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.cadence.player.CadenceApp
import com.cadence.player.MainActivity

/**
 * Keeps playback alive in the background and publishes the media notification, lock screen controls,
 * headphone / Bluetooth button handling and smartwatch controls.
 * The player itself is owned by [CadenceApp], so the service only wraps it in a session.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as CadenceApp
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val built = MediaSession.Builder(this, app.player)
            .setSessionActivity(openApp)
            .setBitmapLoader(ThumbnailBitmapLoader(this))
            .build()
        session = built
        // Our UI talks to the player directly, so no MediaController ever connects and the service would never
        // learn about the session on its own. Without this there is no notification, no foreground service,
        // and Android freezes the app (and the music) shortly after it goes to the background.
        addSession(built)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}
