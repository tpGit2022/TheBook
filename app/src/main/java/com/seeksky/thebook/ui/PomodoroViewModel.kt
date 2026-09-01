package com.seeksky.thebook.ui

import android.app.Application
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import com.seeksky.thebook.pomodoro.PomodoroClock
import com.seeksky.thebook.pomodoro.PomodoroPhase
import com.seeksky.thebook.pomodoro.PomodoroNotification
import com.seeksky.thebook.pomodoro.PomodoroService
import com.seeksky.thebook.pomodoro.PomodoroState
import com.seeksky.thebook.pomodoro.PomodoroStateRepository

class PomodoroViewModel(application: Application) : AndroidViewModel(application) {

    val state: LiveData<PomodoroState> = PomodoroStateRepository.state

    init {
        PomodoroStateRepository.initialize(application)
        if (PomodoroStateRepository.current(application).isActive) {
            PomodoroService.restore(application)
        }
    }

    fun start(durationMinutes: Int) {
        PomodoroService.start(getApplication(), durationMinutes)
    }

    fun pause() {
        PomodoroService.pause(getApplication())
    }

    fun resume() {
        PomodoroService.resume(getApplication())
    }

    fun cancel() {
        PomodoroService.cancel(getApplication())
    }

    fun completeIfExpired() {
        PomodoroService.complete(getApplication())
    }

    fun acknowledgeCompletionDialog() {
        val application = getApplication<Application>()
        val current = PomodoroStateRepository.current(application)
        if (
            current.phase == PomodoroPhase.FINISHED &&
            current.completionDialogPending
        ) {
            PomodoroStateRepository.update(
                application,
                current.copy(completionDialogPending = false)
            )
        }
    }

    fun refresh() {
        PomodoroStateRepository.refresh(getApplication())
    }

    fun remainingMillis(state: PomodoroState): Long {
        return state.remainingMillis(PomodoroClock.now(getApplication()))
    }

    fun notificationsEnabled(): Boolean {
        return NotificationManagerCompat.from(getApplication()).areNotificationsEnabled()
    }

    fun fullScreenIntentsEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true

        val application = getApplication<Application>()
        val notificationManager = application.getSystemService(NotificationManager::class.java)
        return runCatching {
            NotificationManager::class.java
                .getMethod("canUseFullScreenIntent")
                .invoke(notificationManager) as Boolean
        }.getOrDefault(true)
    }

    fun completionAlertsCanInterrupt(): Boolean {
        return PomodoroNotification.completionAlertsCanInterrupt(getApplication())
    }
}
