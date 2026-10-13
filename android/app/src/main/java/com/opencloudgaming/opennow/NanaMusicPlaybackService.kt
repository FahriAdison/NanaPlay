package com.opencloudgaming.opennow

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.papahchan.nanaplay.R

/**
 * NanaPlay 1.0.59 — foreground service hosting the music MediaSession.
 *
 * Problem it fixes: the music ExoPlayer used to live in the bare app process
 * with no service, so once the user left the app (outside a stream) the OS
 * could kill the process at any time and the music just stopped — contradicting
 * the "keeps playing in the background" promise. Now the OS treats music as
 * real media playback: the process survives backgrounding, and the user gets
 * a system media notification + lock-screen / Bluetooth controls for free.
 *
 * Design notes:
 * - The ExoPlayer itself is still owned by the NanaMusicPlayer singleton
 *   (queue, restore, JioSaavn resolve all live there). This service only wraps
 *   that player in a MediaSession. obtainServicePlayer() hops to the main
 *   thread internally if needed, so calling it from onCreate() is safe.
 * - MediaSessionService automatically promotes itself to foreground with a
 *   media notification (DefaultMediaNotificationProvider) while the player is
 *   playing, and drops foreground when it pauses. We only start this service
 *   at moments playback is definitely active (see NanaMusicPlayer), so the
 *   foreground-service timeout can never bite.
 * - onDestroy() releases the session but NOT the player — the singleton owns
 *   it and may outlive the service (e.g. UI-driven playback while the app is
 *   in the foreground).
 *
 * NOT in this file (owned by other agents):
 * - Manifest: <service android:name="...NanaMusicPlaybackService"
 *     android:exported="false" android:foregroundServiceType="mediaPlayback">
 *     <intent-filter>
 *       <action android:name="androidx.media3.session.MediaSessionService" />
 *     </intent-filter>
 *   </service>
 * - Build: implementation("androidx.media3:media3-session:1.10.1")
 *   (media3-session is NOT a dependency yet — the service won't compile
 *   without it).
 */
class NanaMusicPlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Custom provider so the notification uses our channel/id instead of
        // the Media3 defaults.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setNotificationId(NOTIFICATION_ID)
                .build(),
        )
        val player = NanaMusicPlayer.obtainServicePlayer(this)
        session = MediaSession.Builder(this, player)
            .setId("nana-music")
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away must NOT stop the music — background playback
        // is the entire point of this service. Only stop when idle.
        val player = session?.player
        if (player == null || !player.playWhenReady || player.playbackState == Player.STATE_IDLE) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        // createNotificationChannel is a no-op when the channel exists, so
        // this is safe to call on every service start.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.music_notif_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        private const val CHANNEL_ID = "nana_music_playback"
        private const val NOTIFICATION_ID = 1001

        /** Start the service. Call only when playback is actually active. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NanaMusicPlaybackService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NanaMusicPlaybackService::class.java))
        }
    }
}
