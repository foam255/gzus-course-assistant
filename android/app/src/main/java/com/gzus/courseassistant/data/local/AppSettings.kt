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

    var examReminderEnabled: Boolean
        get() = preferences.getBoolean(KEY_EXAM_REMINDER_ENABLED, true)
        set(value) {
            preferences.edit().putBoolean(KEY_EXAM_REMINDER_ENABLED, value).apply()
        }

    /** 提前提醒的小时数集合，默认「提前 1 天」与「提前 3 小时」两档。 */
    var examReminderLeadHours: Set<Int>
        get() = preferences.getStringSet(KEY_EXAM_REMINDER_LEADS, emptySet())
            .orEmpty()
            .mapNotNull { it.toIntOrNull() }
            .filter { it > 0 }
            .toSet()
            .ifEmpty { DEFAULT_EXAM_LEADS }
        set(value) {
            preferences.edit()
                .putStringSet(KEY_EXAM_REMINDER_LEADS, value.map(Int::toString).toSet())
                .apply()
        }

    var examRemindedKeys: Set<String>
        get() = preferences.getStringSet(KEY_EXAM_REMINDED_KEYS, emptySet())
            ?.toSet()
            .orEmpty()
        set(value) {
            preferences.edit().putStringSet(KEY_EXAM_REMINDED_KEYS, value.toSet()).apply()
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
        const val KEY_EXAM_REMINDER_ENABLED = "exam_reminder_enabled"
        const val KEY_EXAM_REMINDER_LEADS = "exam_reminder_leads"
        const val KEY_EXAM_REMINDED_KEYS = "exam_reminded_keys"
        const val KEY_LAST_NOTIFIED_UPDATE = "last_notified_update"
        val DEFAULT_EXAM_LEADS = setOf(24, 3)
    }
}
