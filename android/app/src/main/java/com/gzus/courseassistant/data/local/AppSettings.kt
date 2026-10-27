package com.gzus.courseassistant.data.local

import android.content.Context

class AppSettings(context: Context) {
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var reminderMinutes: Int
        get() = preferences.getInt(KEY_REMINDER, 15)
        set(value) {
            preferences.edit().putInt(KEY_REMINDER, value).apply()
        }

    var remindedKeys: Set<String>
        get() = preferences.getStringSet(KEY_REMINDED_KEYS, emptySet())
            ?.toSet()
            .orEmpty()
        set(value) {
            preferences.edit().putStringSet(KEY_REMINDED_KEYS, value.toSet()).apply()
        }

    var lastNotifiedUpdateVersion: String
        get() = preferences.getString(KEY_LAST_NOTIFIED_UPDATE, "").orEmpty()
        set(value) {
            preferences.edit().putString(KEY_LAST_NOTIFIED_UPDATE, value).apply()
        }

    fun clearAll() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val KEY_REMINDER = "reminder_minutes"
        const val KEY_REMINDED_KEYS = "reminded_keys"
        const val KEY_LAST_NOTIFIED_UPDATE = "last_notified_update"
    }
}
