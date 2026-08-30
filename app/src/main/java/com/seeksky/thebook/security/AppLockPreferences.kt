package com.seeksky.thebook.security

import android.content.Context

class AppLockPreferences(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun isFingerprintLockEnabled(): Boolean {
        return preferences.getBoolean(KEY_FINGERPRINT_LOCK_ENABLED, false)
    }

    fun setFingerprintLockEnabled(enabled: Boolean) {
        preferences.edit()
            .putBoolean(KEY_FINGERPRINT_LOCK_ENABLED, enabled)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "app_lock"
        const val KEY_FINGERPRINT_LOCK_ENABLED = "fingerprint_lock_enabled"
    }
}
