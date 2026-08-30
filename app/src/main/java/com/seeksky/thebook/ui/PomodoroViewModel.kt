package com.seeksky.thebook.ui

import android.app.Application
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import com.seeksky.thebook.pomodoro.PomodoroClock
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

    fun refresh() {
        PomodoroStateRepository.refresh(getApplication())
    }

    fun remainingMillis(state: PomodoroState): Long {
        return state.remainingMillis(PomodoroClock.now(getApplication()))
    }

    fun notificationsEnabled(): Boolean {
        return NotificationManagerCompat.from(getApplication()).areNotificationsEnabled()
    }
}
