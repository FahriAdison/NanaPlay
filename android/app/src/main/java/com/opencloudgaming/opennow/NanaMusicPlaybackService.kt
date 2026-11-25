package com.opencloudgaming.opennow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.papahchan.nanaplay.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * NanaPlay 1.0.59 — foreground service hosting the music MediaSession.
 * 1.0.71 — wraps ExoPlayer in a ForwardingPlayer so MediaSession exposes
 * working Next and Previous actions to notifications, lockscreen, and Bluetooth.
 */
class NanaMusicPlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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

    @OptIn(UnstableApi::class)
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
        val initialButtons = buildMediaButtonPreferences(
            shuffleEnabled = NanaMusicPlayer.shuffleEnabled.value,
            repeatMode = NanaMusicPlayer.repeatMode.value,
        )
        session = MediaSession.Builder(this, forwardingPlayer)
            .setId("nana-music")
            .setSessionActivity(buildContentIntent())
            .setCallback(SessionCallback())
            .setMediaButtonPreferences(initialButtons)
            .build()
        serviceScope.launch {
            combine(
                NanaMusicPlayer.shuffleEnabled,
                NanaMusicPlayer.repeatMode,
            ) { shuffle, repeat ->
                shuffle to repeat
            }.collect { (shuffle, repeat) ->
                session?.setMediaButtonPreferences(
                    buildMediaButtonPreferences(shuffle, repeat),
                )
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun buildMediaButtonPreferences(
        shuffleEnabled: Boolean,
        repeatMode: NanaRepeatMode,
    ): List<CommandButton> {
        val shuffleIcon = if (shuffleEnabled) {
            CommandButton.ICON_SHUFFLE_ON
        } else {
            CommandButton.ICON_SHUFFLE_OFF
        }
        val shuffleLabel = getString(
            if (shuffleEnabled) R.string.music_notif_shuffle_on else R.string.music_notif_shuffle_off,
        )
        val shuffleButton = CommandButton.Builder(shuffleIcon)
            .setDisplayName(shuffleLabel)
            .setSessionCommand(COMMAND_TOGGLE_SHUFFLE)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

        val (repeatIcon, repeatLabelRes) = when (repeatMode) {
            NanaRepeatMode.OFF -> CommandButton.ICON_REPEAT_OFF to R.string.music_notif_repeat_off
            NanaRepeatMode.ALL -> CommandButton.ICON_REPEAT_ALL to R.string.music_notif_repeat_all
            NanaRepeatMode.ONE -> CommandButton.ICON_REPEAT_ONE to R.string.music_notif_repeat_one
        }
        val repeatButton = CommandButton.Builder(repeatIcon)
            .setDisplayName(getString(repeatLabelRes))
            .setSessionCommand(COMMAND_CYCLE_REPEAT)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

        return listOf(shuffleButton, repeatButton)
    }

    @OptIn(UnstableApi::class)
    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val availableCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                .buildUpon()
                .add(COMMAND_TOGGLE_SHUFFLE)
                .add(COMMAND_CYCLE_REPEAT)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(availableCommands)
                .build()
        }

        override fun onPostConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ) {
            session.setMediaButtonPreferences(
                controller,
                buildMediaButtonPreferences(
                    shuffleEnabled = NanaMusicPlayer.shuffleEnabled.value,
                    repeatMode = NanaMusicPlayer.repeatMode.value,
                ),
            )
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            return when (customCommand.customAction) {
                ACTION_TOGGLE_SHUFFLE -> {
                    NanaMusicPlayer.toggleShuffle()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_CYCLE_REPEAT -> {
                    NanaMusicPlayer.toggleRepeatMode()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                else -> super.onCustomCommand(session, controller, customCommand, args)
            }
        }
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
                .add(Player.COMMAND_SET_SHUFFLE_MODE)
                .add(Player.COMMAND_SET_REPEAT_MODE)
                .build()
        }

        override fun isCommandAvailable(command: Int): Boolean {
            if (command == Player.COMMAND_SEEK_TO_NEXT ||
                command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                command == Player.COMMAND_SEEK_TO_PREVIOUS ||
                command == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ||
                command == Player.COMMAND_SET_SHUFFLE_MODE ||
                command == Player.COMMAND_SET_REPEAT_MODE
            ) {
                return true
            }
            return super.isCommandAvailable(command)
        }

        override fun getShuffleModeEnabled(): Boolean = NanaMusicPlayer.shuffleEnabled.value

        override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
            NanaMusicPlayer.setShuffleEnabled(shuffleModeEnabled)
        }

        override fun getRepeatMode(): Int = when (NanaMusicPlayer.repeatMode.value) {
            NanaRepeatMode.OFF -> Player.REPEAT_MODE_OFF
            NanaRepeatMode.ALL -> Player.REPEAT_MODE_ALL
            NanaRepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }

        override fun setRepeatMode(repeatMode: Int) {
            val mapped = when (repeatMode) {
                Player.REPEAT_MODE_ALL -> NanaRepeatMode.ALL
                Player.REPEAT_MODE_ONE -> NanaRepeatMode.ONE
                else -> NanaRepeatMode.OFF
            }
            NanaMusicPlayer.setRepeatMode(mapped)
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
        serviceScope.cancel()
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

    private fun buildContentIntent(): PendingIntent {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildInitialNotification(): Notification {
        val contentIntent = buildContentIntent()
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
        private const val ACTION_TOGGLE_SHUFFLE = "com.papahchan.nanaplay.music.TOGGLE_SHUFFLE"
        private const val ACTION_CYCLE_REPEAT = "com.papahchan.nanaplay.music.CYCLE_REPEAT"
        private val COMMAND_TOGGLE_SHUFFLE = SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY)
        private val COMMAND_CYCLE_REPEAT = SessionCommand(ACTION_CYCLE_REPEAT, Bundle.EMPTY)

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
