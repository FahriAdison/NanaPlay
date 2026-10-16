package com.opencloudgaming.opennow

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor

/**
 * NanaPlay 1.0.59 — pauses music during phone calls.
 *
 * Audio focus is intentionally OFF (music must mix with game audio instead of
 * ducking it), so the system will NOT pause us on calls by itself. We watch
 * the audio mode explicitly and pause/resume around calls.
 *
 * 1.0.60 (F-03 fix): uses AudioManager.addOnModeChangedListener (API 31+)
 * instead of TelephonyCallback, so no READ_PHONE_STATE permission is needed
 * at all. On API < 31 the listener API doesn't exist, so auto-pause is
 * skipped there (music keeps working, just no auto-pause on calls).
 *
 * Threading: callbacks are marshalled to the main thread before touching
 * NanaMusicPlayer (ExoPlayer must be touched on its creation thread).
 */
object NanaCallStateWatcher {

    @Volatile
    private var audioManager: AudioManager? = null
    private var modeListener: AudioManager.OnModeChangedListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { mainHandler.post(it) }

    fun register(context: Context) {
        if (audioManager != null) return
        // F-03: addOnModeChangedListener requires API 31+.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            val am = context.applicationContext
                .getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return
            val listener = AudioManager.OnModeChangedListener { mode ->
                onModeChangedMain(mode)
            }
            am.addOnModeChangedListener(mainExecutor, listener)
            modeListener = listener
            audioManager = am
            // Sync initial state in case a call is already active.
            onModeChangedMain(am.mode)
        } catch (t: Throwable) {
            // Anything unexpected — clean up and carry on without call auto-pause.
            unregister(context)
        }
    }

    fun unregister(context: Context) {
        val am = audioManager ?: return
        try {
            modeListener?.let { am.removeOnModeChangedListener(it) }
        } catch (t: Throwable) {
            // Best effort — never crash on teardown.
        } finally {
            audioManager = null
            modeListener = null
        }
    }

    private fun onModeChangedMain(mode: Int) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            handleAudioMode(mode)
        } else {
            mainHandler.post { handleAudioMode(mode) }
        }
    }

    private fun handleAudioMode(mode: Int) {
        // M-04: MODE_RINGTONE (1) pauses music when the phone is ringing with an incoming call;
        // MODE_IN_CALL (2) pauses during active calls; mode 4 (MODE_CALL_SCREENING) during screening;
        // F-26: MODE_IN_COMMUNICATION (3) pauses during VoIP calls outside an active game stream.
        val isCallActiveOrRinging = mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_RINGTONE ||
            mode == 4 ||
            (mode == AudioManager.MODE_IN_COMMUNICATION && NativeStreamInputRouter.getClient() == null)

        if (isCallActiveOrRinging) {
            NanaMusicPlayer.pauseForCall()
        } else {
            NanaMusicPlayer.resumeAfterCall()
        }
    }
}
