package com.devoid.keysync.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Centralised application language handling.
 *
 * The selected language is persisted in SharedPreferences so it can be read very early,
 * from [android.app.Application.attachBaseContext] and
 * [android.app.Activity.attachBaseContext]. That is the only place where a wrapped,
 * correctly localised [Context] can be installed for the whole activity window.
 */
object LocaleManager {

    const val LANGUAGE_ENGLISH = "en"
    const val LANGUAGE_CHINESE = "zh"
    const val DEFAULT_LANGUAGE = LANGUAGE_ENGLISH

    /** Languages the UI is translated into. */
    val supportedLanguages: List<String> = listOf(LANGUAGE_ENGLISH, LANGUAGE_CHINESE)

    private const val PREFS_NAME = "app_config"
    private const val KEY_LANGUAGE = "locale"

    /** Returns a language code that is guaranteed to be supported. */
    fun normalize(language: String?): String =
        if (language != null && supportedLanguages.contains(language)) {
            language
        } else {
            DEFAULT_LANGUAGE
        }

    /** Language that is currently persisted. Never null, always a supported code. */
    fun getSavedLanguage(context: Context): String = normalize(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, DEFAULT_LANGUAGE)
    )

    /**
     * Persists [language]. Only the preference is written here, the UI is refreshed by
     * recreating the activity (which re-runs `attachBaseContext`).
     */
    fun saveLanguage(context: Context, language: String) {
        val normalized = normalize(language)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, normalized)
            .apply()
        Locale.setDefault(localeOf(normalized))
    }

    /**
     * Returns a [Context] whose resources resolve strings for [language].
     * [Context.createConfigurationContext] does **not** mutate the receiver, so the
     * returned context must be installed by the caller (see [applySavedLanguage]).
     */
    fun wrap(context: Context, language: String): Context {
        val locale = localeOf(normalize(language))
        Locale.setDefault(locale)

        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return context.createConfigurationContext(configuration)
    }

    /**
     * Wraps the given base context with the persisted language.
     *
     * Call it from `attachBaseContext`:
     * `super.attachBaseContext(LocaleManager.applySavedLanguage(newBase))`
     */
    fun applySavedLanguage(base: Context): Context = wrap(base, getSavedLanguage(base))

    private fun localeOf(language: String): Locale =
        if (language == LANGUAGE_CHINESE) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH
}
