package com.seeksky.thebook.pomodoro

import org.junit.Assert.assertEquals
import org.junit.Test

class PomodoroStateTest {

    @Test
    fun runningStateUsesMonotonicClockDuringSameBoot() {
        val startedAt = PomodoroNow(
            elapsedRealtime = 10_000L,
            epochMillis = 1_000_000L,
            bootCount = 4
        )
        val state = PomodoroState.running(60_000L, startedAt)

        val remaining = state.remainingMillis(
            PomodoroNow(
                elapsedRealtime = 25_000L,
                epochMillis = 9_000_000L,
                bootCount = 4
            )
        )

        assertEquals(45_000L, remaining)
    }

    @Test
    fun runningStateFallsBackToWallClockAfterReboot() {
        val state = PomodoroState(
            phase = PomodoroPhase.RUNNING,
            durationMillis = 60_000L,
            pausedRemainingMillis = 60_000L,
            deadlineElapsedRealtime = 70_000L,
            deadlineEpochMillis = 1_060_000L,
            bootCount = 4
        )

        val remaining = state.remainingMillis(
            PomodoroNow(
                elapsedRealtime = 2_000L,
                epochMillis = 1_025_000L,
                bootCount = 5
            )
        )

        assertEquals(35_000L, remaining)
    }

    @Test
    fun pausedStateDoesNotDecrease() {
        val state = PomodoroState(
            phase = PomodoroPhase.PAUSED,
            durationMillis = 60_000L,
            pausedRemainingMillis = 42_000L
        )

        assertEquals(
            42_000L,
            state.remainingMillis(
                PomodoroNow(
                    elapsedRealtime = 999_999L,
                    epochMillis = 999_999L,
                    bootCount = 10
                )
            )
        )
    }

    @Test
    fun remainingTimeNeverBecomesNegative() {
        val state = PomodoroState.running(
            durationMillis = 60_000L,
            now = PomodoroNow(10_000L, 1_000_000L, 4)
        )

        assertEquals(
            0L,
            state.remainingMillis(PomodoroNow(90_000L, 1_080_000L, 4))
        )
    }
}
