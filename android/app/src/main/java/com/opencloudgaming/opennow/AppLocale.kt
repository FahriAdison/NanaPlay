package com.opencloudgaming.opennow

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

/**
 * Per-app language support without AppCompat.
 *
 * NanaPlay does not use AppCompat, so AppCompatDelegate.setApplicationLocales()
 * is unavailable. Instead every Context that loads UI resources is wrapped with
 * [applyAppLocale], which returns a [Context] whose [Configuration] carries the
 * user's chosen locale. Android then resolves string resources from
 * `values` (English) or `values-in` (Bahasa Indonesia) automatically.
 *
 * The single source of truth is [AppSettings.appLanguage]: "en" for English
 * (default), "in" for Bahasa Indonesia.
 */
object AppLocale {
    const val LANGUAGE_ENGLISH = "en"
    const val LANGUAGE_INDONESIAN = "in"

    /** Validates a stored language value; anything unknown falls back to English. */
    fun getAppLanguage(settings: AppSettings): String =
        settings.appLanguage.takeIf { it == LANGUAGE_ENGLISH || it == LANGUAGE_INDONESIAN }
            ?: LANGUAGE_ENGLISH

    fun localeFor(language: String): Locale =
        if (language == LANGUAGE_INDONESIAN) Locale(LANGUAGE_INDONESIAN) else Locale.ENGLISH

    /**
     * Wraps [base] in a configuration context carrying the requested language.
     * Works on API 23-36 via [Context.createConfigurationContext].
     *
     * NOTE: callers must pass the not-yet-attached [base] Context (never `this`
     * inside attachBaseContext before super.attachBaseContext runs), because the
     * base context is the only fully functional Context at that point.
     */
    fun applyAppLocale(base: Context, language: String): Context {
        val locale = localeFor(language)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        return base.createConfigurationContext(config)
    }

    /** Convenience overload that reads the language straight from settings. */
    fun applyAppLocale(base: Context, settings: AppSettings): Context =
        applyAppLocale(base, getAppLanguage(settings))

    /**
     * Safe for use in attachBaseContext: reads the stored language directly from
     * SharedPreferences without initializing SettingsStore (which touches
     * PackageManager/resources that aren't ready during attachBaseContext).
     * Any failure falls back to English.
     */
    fun applyAppLocaleSafe(base: Context): Context =
        applyAppLocale(base, readStoredLanguage(base))

    private fun readStoredLanguage(base: Context): String {
        return try {
            val prefs = base.getSharedPreferences("opennow_native", Context.MODE_PRIVATE)
            val json = prefs.getString("settings", null) ?: return LANGUAGE_ENGLISH
            // Minimal parse: find "appLanguage":"xx" without full JSON deserialization
            val match = Regex("\"appLanguage\"\\s*:\\s*\"([^\"]+)\"").find(json)
            val lang = match?.groupValues?.get(1)
            if (lang == LANGUAGE_INDONESIAN) LANGUAGE_INDONESIAN else LANGUAGE_ENGLISH
        } catch (_: Exception) {
            LANGUAGE_ENGLISH
        }
    }
}
