package com.opencloudgaming.opennow

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * NanaPlay 1.0.59 — pauses music during phone calls.
 *
 * Audio focus is intentionally OFF (music must mix with game audio instead of
 * ducking it), so the system will NOT pause us on calls by itself. We watch
 * the call state explicitly and pause/resume around it.
 *
 * Permission: needs READ_PHONE_STATE (dangerous). Registration is fully
 * defensive — if the permission isn't granted (or gets revoked), we silently
 * skip: music keeps working, it just won't auto-pause on calls. No crash, no
 * prompt from here.
 *
 * NOT in this file (owned by other agents):
 * - Manifest: <uses-permission android:name="android.permission.READ_PHONE_STATE" />
 *   plus a runtime permission request flow (e.g. when the user first opens the
 *   music screen) if Papah wants the auto-pause to actually work.
 *
 * Threading: callbacks are marshalled to the main thread before touching
 * NanaMusicPlayer (ExoPlayer must be touched on its creation thread).
 */
object NanaCallStateWatcher {

    @Volatile
    private var telephonyManager: TelephonyManager? = null
    private var legacyListener: android.telephony.PhoneStateListener? = null
    private var modernCallback: android.telephony.TelephonyCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun register(context: Context) {
        if (telephonyManager != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            val tm = context.applicationContext
                .getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : android.telephony.TelephonyCallback(),
                    android.telephony.TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        onCallStateMain(state)
                    }
                }
                tm.registerTelephonyCallback(context.mainExecutor, cb)
                modernCallback = cb
            } else {
                @Suppress("DEPRECATION")
                val listener = object : android.telephony.PhoneStateListener() {
                    @Deprecated("Deprecated in API 31, required below it")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        onCallStateMain(state)
                    }
                }
                @Suppress("DEPRECATION")
                tm.listen(listener, android.telephony.PhoneStateListener.LISTEN_CALL_STATE)
                legacyListener = listener
            }
            telephonyManager = tm
        } catch (t: Throwable) {
            // SecurityException (revoked mid-flight) or anything else — clean
            // up and carry on without call auto-pause.
            unregister(context)
        }
    }

    fun unregister(context: Context) {
        val tm = telephonyManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (modernCallback as? android.telephony.TelephonyCallback)?.let {
                    tm.unregisterTelephonyCallback(it)
                }
            } else {
                @Suppress("DEPRECATION")
                legacyListener?.let {
                    tm.listen(it, android.telephony.PhoneStateListener.LISTEN_NONE)
                }
            }
        } catch (t: Throwable) {
            // Best effort — never crash on teardown.
        } finally {
            telephonyManager = null
            modernCallback = null
            legacyListener = null
        }
    }

    private fun onCallStateMain(state: Int) {
        // PhoneStateListener already calls back on the registering thread
        // (main, in our case); TelephonyCallback uses mainExecutor. Post
        // defensively anyway — touching ExoPlayer off-main is a crash vector.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            handleCallState(state)
        } else {
            mainHandler.post { handleCallState(state) }
        }
    }

    private fun handleCallState(state: Int) {
        if (state == TelephonyManager.CALL_STATE_IDLE) {
            NanaMusicPlayer.resumeAfterCall()
        } else {
            // RINGING or OFFHOOK.
            NanaMusicPlayer.pauseForCall()
        }
    }
}
