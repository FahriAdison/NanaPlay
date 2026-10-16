package com.opencloudgaming.opennow

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.papahchan.nanaplay.R

/**
 * NanaPlay 1.0.59 — foreground service hosting the music MediaSession.
 * 1.0.71 — wraps ExoPlayer in a ForwardingPlayer so MediaSession exposes
 * working Next and Previous actions to notifications, lockscreen, and Bluetooth.
 */
class NanaMusicPlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setNotificationId(NOTIFICATION_ID)
                .build(),
        )
        val basePlayer = NanaMusicPlayer.obtainServicePlayer(this)
        val forwardingPlayer = NanaForwardingPlayer(basePlayer, this)
        session = MediaSession.Builder(this, forwardingPlayer)
            .setId("nana-music")
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        session

    private class NanaForwardingPlayer(
        player: Player,
        private val context: Context,
    ) : ForwardingPlayer(player) {

        override fun getAvailableCommands(): Player.Commands {
            val base = super.getAvailableCommands()
            return base.buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()
        }

        override fun isCommandAvailable(command: Int): Boolean {
            if (command == Player.COMMAND_SEEK_TO_NEXT ||
                command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                command == Player.COMMAND_SEEK_TO_PREVIOUS ||
                command == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
            ) {
                return true
            }
            return super.isCommandAvailable(command)
        }

        override fun seekToNext() {
            NanaMusicPlayer.next(context)
        }

        override fun seekToNextMediaItem() {
            NanaMusicPlayer.next(context)
        }

        override fun seekToPrevious() {
            NanaMusicPlayer.previous(context)
        }

        override fun seekToPreviousMediaItem() {
            NanaMusicPlayer.previous(context)
        }
    }

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
