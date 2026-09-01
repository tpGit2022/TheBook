package com.seeksky.thebook.pomodoro

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings

enum class PomodoroPhase {
    IDLE,
    RUNNING,
    PAUSED,
    FINISHED
}

data class PomodoroNow(
    val elapsedRealtime: Long,
    val epochMillis: Long,
    val bootCount: Int
)

data class PomodoroState(
    val phase: PomodoroPhase = PomodoroPhase.IDLE,
    val durationMillis: Long = DEFAULT_DURATION_MILLIS,
    val pausedRemainingMillis: Long = DEFAULT_DURATION_MILLIS,
    val deadlineElapsedRealtime: Long = 0L,
    val deadlineEpochMillis: Long = 0L,
    val bootCount: Int = UNKNOWN_BOOT_COUNT,
    val completionDialogPending: Boolean = false
) {
    val isActive: Boolean
        get() = phase == PomodoroPhase.RUNNING || phase == PomodoroPhase.PAUSED

    fun remainingMillis(now: PomodoroNow): Long = when (phase) {
        PomodoroPhase.RUNNING -> {
            val remaining = if (
                bootCount != UNKNOWN_BOOT_COUNT &&
                now.bootCount != UNKNOWN_BOOT_COUNT &&
                bootCount == now.bootCount
            ) {
                deadlineElapsedRealtime - now.elapsedRealtime
            } else {
                deadlineEpochMillis - now.epochMillis
            }
            remaining.coerceIn(0L, durationMillis)
        }

        PomodoroPhase.PAUSED -> pausedRemainingMillis.coerceIn(0L, durationMillis)
        PomodoroPhase.IDLE -> durationMillis
        PomodoroPhase.FINISHED -> 0L
    }

    companion object {
        const val UNKNOWN_BOOT_COUNT = -1
        const val DEFAULT_DURATION_MINUTES = 5
        const val DEFAULT_DURATION_MILLIS = DEFAULT_DURATION_MINUTES * 60_000L

        fun running(durationMillis: Long, now: PomodoroNow): PomodoroState {
            return runningFromRemaining(durationMillis, durationMillis, now)
        }

        fun runningFromRemaining(
            durationMillis: Long,
            remainingMillis: Long,
            now: PomodoroNow
        ): PomodoroState {
            val safeDuration = durationMillis.coerceAtLeast(1_000L)
            val safeRemaining = remainingMillis.coerceIn(1_000L, safeDuration)
            return PomodoroState(
                phase = PomodoroPhase.RUNNING,
                durationMillis = safeDuration,
                pausedRemainingMillis = safeRemaining,
                deadlineElapsedRealtime = now.elapsedRealtime + safeRemaining,
                deadlineEpochMillis = now.epochMillis + safeRemaining,
                bootCount = now.bootCount
            )
        }
    }
}

object PomodoroClock {
    fun now(context: Context): PomodoroNow {
        return PomodoroNow(
            elapsedRealtime = SystemClock.elapsedRealtime(),
            epochMillis = System.currentTimeMillis(),
            bootCount = readBootCount(context)
        )
    }

    private fun readBootCount(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return PomodoroState.UNKNOWN_BOOT_COUNT
        }
        return try {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.BOOT_COUNT,
                PomodoroState.UNKNOWN_BOOT_COUNT
            )
        } catch (_: SecurityException) {
            PomodoroState.UNKNOWN_BOOT_COUNT
        }
    }
}
