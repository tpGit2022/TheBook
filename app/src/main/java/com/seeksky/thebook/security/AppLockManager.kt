package com.seeksky.thebook.security

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

object AppLockManager : DefaultLifecycleObserver {

    private lateinit var preferences: AppLockPreferences

    @Volatile
    private var initialized = false

    @Volatile
    private var sessionUnlocked = false

    @Volatile
    private var authenticationFlowActive = false

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            preferences = AppLockPreferences(context.applicationContext)
            sessionUnlocked = !preferences.isFingerprintLockEnabled()
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
            initialized = true
        }
    }

    fun isEnabled(): Boolean {
        check(initialized) { "AppLockManager has not been initialized" }
        return preferences.isFingerprintLockEnabled()
    }

    fun shouldAuthenticate(): Boolean = isEnabled() && !sessionUnlocked

    fun beginAuthentication() {
        authenticationFlowActive = true
    }

    fun completeAuthentication(enableLock: Boolean) {
        if (enableLock) {
            preferences.setFingerprintLockEnabled(true)
        }
        sessionUnlocked = true
    }

    fun endAuthentication() {
        authenticationFlowActive = false
    }

    fun cancelAuthentication() {
        authenticationFlowActive = false
        if (isEnabled()) sessionUnlocked = false
    }

    fun disable() {
        preferences.setFingerprintLockEnabled(false)
        authenticationFlowActive = false
        sessionUnlocked = true
    }

    fun onAppUiHidden() {
        lockSessionIfNeeded()
    }

    override fun onStop(owner: LifecycleOwner) {
        lockSessionIfNeeded()
    }

    private fun lockSessionIfNeeded() {
        if (isEnabled() && !authenticationFlowActive) {
            sessionUnlocked = false
        }
    }
}
