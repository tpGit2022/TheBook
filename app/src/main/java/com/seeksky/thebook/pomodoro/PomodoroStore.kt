package com.seeksky.thebook.pomodoro

import android.content.Context

class PomodoroStore(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun read(): PomodoroState {
        val phase = runCatching {
            PomodoroPhase.valueOf(
                preferences.getString(KEY_PHASE, PomodoroPhase.IDLE.name)
                    ?: PomodoroPhase.IDLE.name
            )
        }.getOrDefault(PomodoroPhase.IDLE)

        val duration = preferences.getLong(
            KEY_DURATION,
            PomodoroState.DEFAULT_DURATION_MILLIS
        ).coerceAtLeast(1_000L)

        return PomodoroState(
            phase = phase,
            durationMillis = duration,
            pausedRemainingMillis = preferences.getLong(KEY_PAUSED_REMAINING, duration)
                .coerceIn(0L, duration),
            deadlineElapsedRealtime = preferences.getLong(KEY_DEADLINE_ELAPSED, 0L),
            deadlineEpochMillis = preferences.getLong(KEY_DEADLINE_EPOCH, 0L),
            bootCount = preferences.getInt(
                KEY_BOOT_COUNT,
                PomodoroState.UNKNOWN_BOOT_COUNT
            )
        )
    }

    @Synchronized
    fun write(state: PomodoroState) {
        preferences.edit()
            .putString(KEY_PHASE, state.phase.name)
            .putLong(KEY_DURATION, state.durationMillis)
            .putLong(KEY_PAUSED_REMAINING, state.pausedRemainingMillis)
            .putLong(KEY_DEADLINE_ELAPSED, state.deadlineElapsedRealtime)
            .putLong(KEY_DEADLINE_EPOCH, state.deadlineEpochMillis)
            .putInt(KEY_BOOT_COUNT, state.bootCount)
            .commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "pomodoro_state"
        const val KEY_PHASE = "phase"
        const val KEY_DURATION = "duration"
        const val KEY_PAUSED_REMAINING = "paused_remaining"
        const val KEY_DEADLINE_ELAPSED = "deadline_elapsed"
        const val KEY_DEADLINE_EPOCH = "deadline_epoch"
        const val KEY_BOOT_COUNT = "boot_count"
    }
}
