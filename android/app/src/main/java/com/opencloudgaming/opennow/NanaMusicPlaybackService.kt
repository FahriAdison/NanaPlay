package com.opencloudgaming.opennow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
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
    private var noisyPlayerListener: Player.Listener? = null
    private var noisyReceiverRegistered = false
    private val noisyReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                session?.player?.pause()
            }
        }
    }

    private fun registerNoisyReceiver() {
        if (!noisyReceiverRegistered) {
            val filter = android.content.IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(noisyReceiver, filter)
            }
            noisyReceiverRegistered = true
        }
    }

    private fun unregisterNoisyReceiver() {
        if (noisyReceiverRegistered) {
            runCatching { unregisterReceiver(noisyReceiver) }
            noisyReceiverRegistered = false
        }
    }

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
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    registerNoisyReceiver()
                } else {
                    unregisterNoisyReceiver()
                }
            }
        }
        noisyPlayerListener = listener
        forwardingPlayer.addListener(listener)
        if (forwardingPlayer.isPlaying) {
            registerNoisyReceiver()
        }
        session = MediaSession.Builder(this, forwardingPlayer)
            .setId("nana-music")
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote immediately before super.onStartCommand: Media3's async notification
        // pipeline (artwork fetch, buffering state) can miss Android 14-16's strict
        // startForegroundService() deadline and trigger RemoteServiceException.
        createNotificationChannel()
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildInitialNotification(), foregroundType)
        return super.onStartCommand(intent, flags, startId)
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
        // is the entire point of this service. Only stop when genuinely idle/ended.
        val player = session?.player
        // Instrumentasi saja: marker lifecycle persisten untuk hunt "musik berhenti sendiri".
        NanaMusicPlayer.logLifecycleMarker(this, "music.onTaskRemoved playWhenReady=${player?.playWhenReady} state=${player?.playbackState}")
        if (!NanaMusicPlayer.isPlaybackRequested() &&
            (player == null || !player.playWhenReady || player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED)
        ) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        unregisterNoisyReceiver()
        noisyPlayerListener?.let { session?.player?.removeListener(it) }
        noisyPlayerListener = null
        val player = session?.player
        // Instrumentasi saja: marker lifecycle persisten untuk hunt "musik berhenti sendiri".
        NanaMusicPlayer.logLifecycleMarker(this, "music.onDestroy playWhenReady=${player?.playWhenReady} state=${player?.playbackState}")
        val playbackActive = NanaMusicPlayer.isPlaybackRequested() || (
            player != null &&
                (player.isPlaying || (player.playWhenReady && player.playbackState != Player.STATE_IDLE && player.playbackState != Player.STATE_ENDED))
            )
        session?.release()
        session = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (!playbackActive) {
            NanaMusicPlayer.release(stopService = false)
        }
        super.onDestroy()
    }

    private fun buildInitialNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val track = NanaMusicPlayer.currentTrack
        val title = track?.title?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name)
        val artist = track?.artist?.takeIf { it.isNotBlank() } ?: getString(R.string.music_notif_channel)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tab_stream)
            .setContentTitle(title)
            .setContentText(artist)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .build()
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
            ).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            },
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
