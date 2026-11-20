package com.motocompanion.util

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.motocompanion.App
import com.motocompanion.R
import java.util.Locale

object LocaleThemeManager {

    private const val PREFS_NAME = "hsync_settings_prefs"
    private const val KEY_THEME = "app_theme_mode"
    private const val KEY_LANGUAGE = "app_language_code"

    const val THEME_SYSTEM = "system"
    const val THEME_DARK = "dark"
    const val THEME_LIGHT = "light"

    const val LANG_SYSTEM = "system"
    const val LANG_IN = "in"
    const val LANG_EN = "en"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getTheme(context: Context): String {
        return getPrefs(context).getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM
    }

    fun setTheme(context: Context, themeMode: String) {
        getPrefs(context).edit().putString(KEY_THEME, themeMode).apply()
        applyTheme(themeMode)
    }

    fun applySavedTheme(context: Context) {
        val themeMode = getTheme(context)
        applyTheme(themeMode)
    }

    private fun applyTheme(themeMode: String) {
        val nightMode = when (themeMode) {
            THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    fun getLanguage(context: Context): String {
        return getPrefs(context).getString(KEY_LANGUAGE, LANG_SYSTEM) ?: LANG_SYSTEM
    }

    fun setLanguage(context: Context, langCode: String) {
        getPrefs(context).edit().putString(KEY_LANGUAGE, langCode).apply()
        applyLanguage(langCode)
    }

    fun applySavedLanguage(context: Context) {
        val langCode = getLanguage(context)
        applyLanguage(langCode)
    }

    private fun applyLanguage(langCode: String) {
        val localeList = when (langCode) {
            LANG_IN -> LocaleListCompat.forLanguageTags("in")
            LANG_EN -> LocaleListCompat.forLanguageTags("en")
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        AppCompatDelegate.setApplicationLocales(localeList)

        // Sesuaikan bahasa TTS jika instance App sudah aktif
        try {
            val ttsLocale = when (langCode) {
                LANG_EN -> Locale.ENGLISH
                LANG_IN -> Locale("id", "ID")
                else -> {
                    val def = Locale.getDefault()
                    if (def.language == "en") Locale.ENGLISH else Locale("id", "ID")
                }
            }
            App.instance.ttsManager.setLanguage(ttsLocale)
        } catch (_: Exception) {}
    }

    fun getThemeDisplayName(context: Context, themeMode: String): String {
        return when (themeMode) {
            THEME_DARK -> context.getString(R.string.theme_dark)
            THEME_LIGHT -> context.getString(R.string.theme_light)
            else -> context.getString(R.string.theme_system)
        }
    }

    fun getLanguageDisplayName(context: Context, langCode: String): String {
        return when (langCode) {
            LANG_IN -> context.getString(R.string.lang_indonesian)
            LANG_EN -> context.getString(R.string.lang_english)
            else -> context.getString(R.string.lang_system)
        }
    }
}
