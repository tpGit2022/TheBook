package com.seeksky.thebook.pomodoro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner

class PomodoroAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == PomodoroService.ACTION_COMPLETE) {
            completeExpiredTimer(context.applicationContext)
        }
    }

    private fun completeExpiredTimer(context: Context) {
        val current = PomodoroStateRepository.current(context)
        if (current.phase != PomodoroPhase.RUNNING) return

        val now = PomodoroClock.now(context)
        val remaining = current.remainingMillis(now)
        if (remaining > COMPLETION_TOLERANCE_MILLIS) {
            PomodoroAlarmScheduler.schedule(context, now.elapsedRealtime + remaining)
            return
        }

        PomodoroAlarmScheduler.cancel(context)
        PomodoroStateRepository.update(
            context,
            PomodoroState(
                phase = PomodoroPhase.FINISHED,
                durationMillis = current.durationMillis,
                pausedRemainingMillis = 0L,
                completionDialogPending = true
            )
        )

        context.stopService(Intent(context, PomodoroService::class.java))
        PomodoroNotification.cancelRunning(context)

        val appIsForeground = ProcessLifecycleOwner.get().lifecycle.currentState
            .isAtLeast(Lifecycle.State.STARTED)
        PomodoroNotification.notifyComplete(
            context,
            useFullScreenIntent = !appIsForeground
        )
    }

    private companion object {
        const val COMPLETION_TOLERANCE_MILLIS = 500L
    }
}
