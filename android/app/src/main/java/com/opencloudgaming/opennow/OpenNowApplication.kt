package com.opencloudgaming.opennow

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OpenNowApplication : Application() {
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal val httpClient by lazy(::defaultHttpClient)
    internal val authStore by lazy { AuthStore(this) }
    internal val authRepository by lazy { GfnAuthRepository(this, authStore, httpClient) }
    internal val localTvConnector by lazy { LocalTvConnector() }

    override fun attachBaseContext(base: Context) {
        // Apply the user's chosen app language before any resource is loaded.
        // Uses applyAppLocaleSafe: SettingsStore is not safe here (it touches
        // PackageManager during attachBaseContext which crashes on launch).
        val localized = AppLocale.applyAppLocaleSafe(base)
        super.attachBaseContext(localized)
    }

    override fun onCreate() {
        super.onCreate()
        // Installed before anything else: a force close during launch must land on disk, because the
        // in-app diagnostics buffer is memory-only and dies with the process.
        CrashReporter.install(this)
        // Native (signal-based) capture for force closes that never reach the Java handler
        // (SIGSEGV/SIGABRT/...). Best-effort: if the native library cannot load, this is a no-op.
        NativeCrashReporter.install(this)

        startupScope.launch {
            // SettingsStore construction does disk I/O (XML parse) plus a
            // PackageManager IPC (isAndroidTvProfile) — keep it off the main thread.
            val settings = SettingsStore(this@OpenNowApplication).settings.value
            if (isTelevisionDevice()) {
                delay(TV_BACKGROUND_SERVICE_START_DELAY_MS)
            }
            withContext(Dispatchers.Main) {
                initializeBackgroundServices(settings)
            }
        }
    }

    override fun onTerminate() {
        localTvConnector.close()
        super.onTerminate()
    }

    private fun initializeBackgroundServices(settings: AppSettings) {
        OpenNowAnalytics.setup(this, settings)
        AndroidAuthRefreshScheduler.schedule(this)
    }

    private fun isTelevisionDevice(): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION

    private companion object {
        const val TV_BACKGROUND_SERVICE_START_DELAY_MS = 2_500L
    }
}
