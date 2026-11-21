package com.lia.app.data.profile

import android.content.Context
import com.lia.app.data.model.ThemeMode

interface AppPreferences {
    fun getThemeMode(): ThemeMode
    fun saveThemeMode(mode: ThemeMode)
    fun areNotificationsEnabled(): Boolean
    fun saveNotificationsEnabled(enabled: Boolean)
}

class SharedPrefsAppPreferences(context: Context) : AppPreferences {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    override fun getThemeMode(): ThemeMode {
        val stored = prefs.getString(KEY_THEME_MODE, ThemeMode.LIGHT.name)
        return runCatching { ThemeMode.valueOf(stored ?: ThemeMode.LIGHT.name) }
            .getOrDefault(ThemeMode.LIGHT)
    }

    override fun saveThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    override fun areNotificationsEnabled(): Boolean =
        prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, true)

    override fun saveNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "lia_app_preferences"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
    }
}

class InMemoryAppPreferences(
    private var themeMode: ThemeMode = ThemeMode.LIGHT,
    private var notificationsEnabled: Boolean = true
) : AppPreferences {
    override fun getThemeMode(): ThemeMode = themeMode
    override fun saveThemeMode(mode: ThemeMode) {
        themeMode = mode
    }

    override fun areNotificationsEnabled(): Boolean = notificationsEnabled
    override fun saveNotificationsEnabled(enabled: Boolean) {
        notificationsEnabled = enabled
    }
}
