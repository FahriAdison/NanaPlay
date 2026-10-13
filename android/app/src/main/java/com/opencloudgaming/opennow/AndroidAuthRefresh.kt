package com.opencloudgaming.opennow

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

private const val AUTH_REFRESH_WORK_NAME = "opennow-auth-token-refresh"
private const val AUTH_REFRESH_LOG_TAG = "NanaPlayAuthRefresh"
private const val AUTH_REFRESH_INTERVAL_MINUTES = 15L
private const val AUTH_REFRESH_FLEX_MINUTES = 5L
private const val AUTH_REFRESH_BACKOFF_MINUTES = 5L
private const val AUTH_REFRESH_NEEDS_RELOGIN_KEY = "auth_refresh_needs_relogin"

internal fun AuthTokens.needsBackgroundRefresh(nowMs: Long = System.currentTimeMillis()): Boolean =
    expiresAt - nowMs < TOKEN_REFRESH_WINDOW_MS ||
        clientToken.isNullOrBlank() ||
        clientTokenExpiresAt == null ||
        clientTokenExpiresAt - nowMs < CLIENT_TOKEN_REFRESH_WINDOW_MS

internal fun authenticationRefreshClientIds(
    savedClientId: String?,
    browserClientId: String,
    deviceClientId: String,
): List<String> =
    listOfNotNull(
        savedClientId?.takeIf(String::isNotBlank),
        browserClientId,
        deviceClientId,
    ).distinct()

internal object AndroidAuthRefreshScheduler {
    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequest.Builder(
            AndroidAuthRefreshWorker::class.java,
            AUTH_REFRESH_INTERVAL_MINUTES,
            TimeUnit.MINUTES,
            AUTH_REFRESH_FLEX_MINUTES,
            TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                AUTH_REFRESH_BACKOFF_MINUTES,
                TimeUnit.MINUTES,
            )
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            AUTH_REFRESH_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}

internal class AndroidAuthRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val application = applicationContext as OpenNowApplication
        val authStore = application.authStore
        val activeSession = authStore.reload().let { state ->
            state.sessions.firstOrNull { it.user.userId == state.activeUserId }
                ?: state.sessions.firstOrNull()
        } ?: return Result.success()
        if (!activeSession.tokens.needsBackgroundRefresh()) return Result.success()

        return runCatching {
            val refreshed = application.authRepository.restore(
                throwOnRefreshFailure = true,
                removeExpiredSessionOnFailure = false,
            )
            if (refreshed?.tokens?.needsBackgroundRefresh() == true) {
                Result.retry()
            } else {
                // Refresh worked: any earlier "must log in again" flag is stale.
                clearRefreshNeedsRelogin()
                Result.success()
            }
        }.getOrElse { error ->
            if (isPermanentRefreshFailure(error)) {
                // The refresh token is dead (revoked/expired/invalid_grant): retrying every
                // 15 minutes forever will never succeed. Stop the worker and flag the app
                // so it can ask the user to log in again.
                Log.w(AUTH_REFRESH_LOG_TAG, "Background token refresh failed permanently; user must log in again", error)
                markNeedsRelogin()
                Result.success()
            } else {
                Log.w(AUTH_REFRESH_LOG_TAG, "Background token refresh failed; retrying", error)
                Result.retry()
            }
        }
    }

    /**
     * A refresh failure is permanent when the server rejected the credentials themselves
     * (HTTP 4xx / invalid_grant / "log in again") rather than a transient network/server
     * problem. Permanent failures must NOT be retried.
     */
    private fun isPermanentRefreshFailure(error: Throwable?): Boolean {
        val message = error?.message.orEmpty()
        return message.contains("invalid_grant", ignoreCase = true) ||
            message.contains("log in again", ignoreCase = true) ||
            message.contains("failed (4", ignoreCase = true)
    }

    /** Records that the stored credentials are dead and the user must log in again. */
    private fun markNeedsRelogin() {
        applicationContext.getSharedPreferences(AUTH_REFRESH_WORK_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(AUTH_REFRESH_NEEDS_RELOGIN_KEY, true)
            .apply()
    }

    /**
     * Clears the needs-relogin flag. Called when a background refresh succeeds: a
     * successful refresh proves the stored tokens are valid again (e.g. the user
     * logged back in interactively), so any earlier permanent-failure flag is stale.
     */
    fun clearRefreshNeedsRelogin() {
        applicationContext.getSharedPreferences(AUTH_REFRESH_WORK_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(AUTH_REFRESH_NEEDS_RELOGIN_KEY)
            .apply()
    }

    companion object {
        /**
         * Consumed by the app on startup: true when a background refresh previously
         * failed permanently and the user must log in again.
         */
        fun consumeBackgroundRefreshNeedsRelogin(context: Context): Boolean {
            val prefs = context.getSharedPreferences(AUTH_REFRESH_WORK_NAME, Context.MODE_PRIVATE)
            val needsRelogin = prefs.getBoolean(AUTH_REFRESH_NEEDS_RELOGIN_KEY, false)
            if (needsRelogin) prefs.edit().remove(AUTH_REFRESH_NEEDS_RELOGIN_KEY).apply()
            return needsRelogin
        }
    }
}
