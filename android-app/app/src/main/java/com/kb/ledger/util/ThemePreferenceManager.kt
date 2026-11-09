package com.kb.ledger.util

import android.content.Context
import android.content.SharedPreferences

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

/**
 * Persists and retrieves the user's appearance mode (System, Light, Dark).
 * Preserves user choice across app restarts.
 */
class ThemePreferenceManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "ledger_theme_prefs"
        private const val KEY_THEME_MODE = "theme_mode"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getThemeMode(): ThemeMode {
        val saved = prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)
        return try {
            ThemeMode.valueOf(saved ?: ThemeMode.SYSTEM.name)
        } catch (e: Exception) {
            ThemeMode.SYSTEM
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }
}
