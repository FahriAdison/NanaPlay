package com.opencloudgaming.opennow

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
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

    /** F-04: in-memory language set before recreate(), avoiding a race with the
     * ExternalPrefs background disk write. */
    @Volatile
    var pendingLanguage: String? = null

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
        // F-04: prefer the in-memory value; the disk write may not have flushed yet.
        pendingLanguage?.let { return if (it == LANGUAGE_INDONESIAN) LANGUAGE_INDONESIAN else LANGUAGE_ENGLISH }
        return try {
            val json = readSettingsJson(base) ?: return LANGUAGE_ENGLISH
            // Minimal parse: find "appLanguage":"xx" without full JSON deserialization
            val match = Regex("\"appLanguage\"\\s*:\\s*\"([^\"]+)\"").find(json)
            val lang = match?.groupValues?.get(1)
            if (lang == LANGUAGE_INDONESIAN) LANGUAGE_INDONESIAN else LANGUAGE_ENGLISH
        } catch (_: Exception) {
            LANGUAGE_ENGLISH
        }
    }

    /**
     * Reads the raw settings JSON exactly where ExternalPrefs stores it, without
     * initializing SettingsStore (unsafe in attachBaseContext: it touches
     * PackageManager/resources that aren't ready yet).
     *
     * Mirrors ExternalPrefs lookup order: external primary file, then the
     * internal filesDir fallback (newest wins), then the internal
     * SharedPreferences (only non-empty before ExternalPrefs migration runs).
     */
    private fun readSettingsJson(base: Context): String? {
        val extDir = runCatching { base.getExternalFilesDir(null) }.getOrNull()
        val filesDir = runCatching { base.filesDir }.getOrNull()
        val candidates = listOfNotNull(
            extDir?.let { File(it, EXTERNAL_PREFS_FILE) },
            filesDir?.let { File(it, EXTERNAL_PREFS_FILE) },
        ).filter { it.isFile }
        candidates.maxByOrNull { it.lastModified() }
            ?.let { readSettingsFromXml(it) }
            ?.let { return it }
        // Pre-migration fallback: internal SharedPreferences, cleared by migration.
        return runCatching {
            base.getSharedPreferences("opennow_native", Context.MODE_PRIVATE)
                .getString("settings", null)
        }.getOrNull()
    }

    private fun readSettingsFromXml(file: File): String? {
        return try {
            FileInputStream(file).use { fis ->
                val parser = Xml.newPullParser()
                parser.setInput(fis, "UTF-8")
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG &&
                        parser.name == "string" &&
                        parser.getAttributeValue(null, "name") == "settings"
                    ) {
                        // nextText() returns the XML-unescaped text content.
                        return parser.nextText().takeIf { it.isNotBlank() }
                    }
                    event = parser.next()
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** File name used by ExternalPrefs (see Persistence.kt). */
    private const val EXTERNAL_PREFS_FILE = "opennow_native.xml"
}
