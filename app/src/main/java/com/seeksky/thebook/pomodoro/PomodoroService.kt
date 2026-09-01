package com.seeksky.thebook.pomodoro

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner

class PomodoroService : Service() {

    private val legacyNotificationHandler = Handler(Looper.getMainLooper())
    private val legacyNotificationTicker = object : Runnable {
        override fun run() {
            val current = PomodoroStateRepository.current(this@PomodoroService)
            if (current.phase != PomodoroPhase.RUNNING) return

            val remaining = current.remainingMillis(PomodoroClock.now(this@PomodoroService))
            if (remaining <= 0L) {
                finishTimer(current)
            } else {
                showForeground(current, remaining)
                legacyNotificationHandler.postDelayed(this, 1_000L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        PomodoroNotification.createChannels(this)
        PomodoroStateRepository.initialize(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_RESTORE) {
            ACTION_START -> handleStart(intent?.getLongExtra(EXTRA_DURATION_MILLIS, 0L) ?: 0L)
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
            ACTION_CANCEL -> handleCancel()
            ACTION_COMPLETE -> handleComplete()
            ACTION_RESTORE -> handleRestore()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        legacyNotificationHandler.removeCallbacks(legacyNotificationTicker)
        super.onDestroy()
    }

    private fun handleStart(requestedDurationMillis: Long) {
        val duration = requestedDurationMillis.coerceIn(
            MIN_DURATION_MILLIS,
            MAX_DURATION_MILLIS
        )
        val state = PomodoroState.running(duration, PomodoroClock.now(this))
        PomodoroNotification.cancelComplete(this)
        saveAndPublish(state)
        startActiveTimer(state)
    }

    private fun handlePause() {
        val current = PomodoroStateRepository.current(this)
        if (current.phase != PomodoroPhase.RUNNING) {
            handleRestore()
            return
        }

        val remaining = current.remainingMillis(PomodoroClock.now(this))
        if (remaining <= 0L) {
            finishTimer(current)
            return
        }

        val paused = current.copy(
            phase = PomodoroPhase.PAUSED,
            pausedRemainingMillis = remaining,
            deadlineElapsedRealtime = 0L,
            deadlineEpochMillis = 0L,
            bootCount = PomodoroState.UNKNOWN_BOOT_COUNT
        )
        PomodoroAlarmScheduler.cancel(this)
        stopLegacyNotificationTicker()
        saveAndPublish(paused)
        showForeground(paused, remaining)
    }

    private fun handleResume() {
        val current = PomodoroStateRepository.current(this)
        if (current.phase != PomodoroPhase.PAUSED) {
            handleRestore()
            return
        }
        if (current.pausedRemainingMillis <= 0L) {
            finishTimer(current)
            return
        }

        val running = PomodoroState.runningFromRemaining(
            durationMillis = current.durationMillis,
            remainingMillis = current.pausedRemainingMillis,
            now = PomodoroClock.now(this)
        )
        saveAndPublish(running)
        startActiveTimer(running)
    }

    private fun handleCancel() {
        val current = PomodoroStateRepository.current(this)
        if (current.isActive) {
            showForeground(current, current.remainingMillis(PomodoroClock.now(this)))
        }
        PomodoroAlarmScheduler.cancel(this)
        stopLegacyNotificationTicker()
        val idle = PomodoroState(
            phase = PomodoroPhase.IDLE,
            durationMillis = current.durationMillis,
            pausedRemainingMillis = current.durationMillis
        )
        saveAndPublish(idle)
        stopForeground(true)
        stopSelf()
    }

    private fun handleComplete() {
        val current = PomodoroStateRepository.current(this)
        if (current.phase != PomodoroPhase.RUNNING) {
            handleRestore()
            return
        }

        val remaining = current.remainingMillis(PomodoroClock.now(this))
        if (remaining > COMPLETION_TOLERANCE_MILLIS) {
            startActiveTimer(current)
            return
        }
        finishTimer(current)
    }

    private fun handleRestore() {
        val current = PomodoroStateRepository.current(this)
        when (current.phase) {
            PomodoroPhase.RUNNING -> {
                val remaining = current.remainingMillis(PomodoroClock.now(this))
                if (remaining <= 0L) {
                    finishTimer(current)
                } else {
                    startActiveTimer(current)
                }
            }

            PomodoroPhase.PAUSED -> {
                PomodoroAlarmScheduler.cancel(this)
                stopLegacyNotificationTicker()
                showForeground(current, current.pausedRemainingMillis)
            }

            PomodoroPhase.IDLE,
            PomodoroPhase.FINISHED -> {
                PomodoroAlarmScheduler.cancel(this)
                stopLegacyNotificationTicker()
                stopForeground(true)
                stopSelf()
            }
        }
    }

    private fun startActiveTimer(state: PomodoroState) {
        val now = PomodoroClock.now(this)
        val remaining = state.remainingMillis(now)
        if (remaining <= 0L) {
            finishTimer(state)
            return
        }
        showForeground(state, remaining)
        PomodoroAlarmScheduler.schedule(this, now.elapsedRealtime + remaining)
        startLegacyNotificationTicker()
    }

    private fun finishTimer(previous: PomodoroState) {
        if (previous.isActive) {
            showForeground(previous, 0L)
        }
        PomodoroAlarmScheduler.cancel(this)
        stopLegacyNotificationTicker()
        val finished = PomodoroState(
            phase = PomodoroPhase.FINISHED,
            durationMillis = previous.durationMillis,
            pausedRemainingMillis = 0L,
            completionDialogPending = true
        )
        saveAndPublish(finished)
        stopForeground(true)
        val appIsForeground = ProcessLifecycleOwner.get().lifecycle.currentState
            .isAtLeast(Lifecycle.State.STARTED)
        PomodoroNotification.notifyComplete(
            this,
            useFullScreenIntent = !appIsForeground
        )
        stopSelf()
    }

    private fun showForeground(state: PomodoroState, remainingMillis: Long) {
        startForeground(
            PomodoroNotification.RUNNING_NOTIFICATION_ID,
            PomodoroNotification.buildRunning(this, state, remainingMillis)
        )
    }

    private fun saveAndPublish(state: PomodoroState) {
        PomodoroStateRepository.update(this, state)
    }

    private fun startLegacyNotificationTicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) return
        legacyNotificationHandler.removeCallbacks(legacyNotificationTicker)
        legacyNotificationHandler.postDelayed(legacyNotificationTicker, 1_000L)
    }

    private fun stopLegacyNotificationTicker() {
        legacyNotificationHandler.removeCallbacks(legacyNotificationTicker)
    }

    companion object {
        const val ACTION_START = "com.seeksky.thebook.pomodoro.START"
        const val ACTION_PAUSE = "com.seeksky.thebook.pomodoro.PAUSE"
        const val ACTION_RESUME = "com.seeksky.thebook.pomodoro.RESUME"
        const val ACTION_CANCEL = "com.seeksky.thebook.pomodoro.CANCEL"
        const val ACTION_COMPLETE = "com.seeksky.thebook.pomodoro.COMPLETE"
        const val ACTION_RESTORE = "com.seeksky.thebook.pomodoro.RESTORE"

        private const val EXTRA_DURATION_MILLIS = "duration_millis"
        private const val MIN_DURATION_MILLIS = 60_000L
        private const val MAX_DURATION_MILLIS = 180L * 60_000L
        private const val COMPLETION_TOLERANCE_MILLIS = 500L

        fun start(context: Context, durationMinutes: Int) {
            sendAction(
                context,
                Intent(context, PomodoroService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_DURATION_MILLIS, durationMinutes * 60_000L)
                }
            )
        }

        fun pause(context: Context) = sendSimpleAction(context, ACTION_PAUSE)

        fun resume(context: Context) = sendSimpleAction(context, ACTION_RESUME)

        fun cancel(context: Context) = sendSimpleAction(context, ACTION_CANCEL)

        fun complete(context: Context) = sendSimpleAction(context, ACTION_COMPLETE)

        fun restore(context: Context) = sendSimpleAction(context, ACTION_RESTORE)

        private fun sendSimpleAction(context: Context, action: String) {
            sendAction(
                context,
                Intent(context, PomodoroService::class.java).apply {
                    this.action = action
                }
            )
        }

        private fun sendAction(context: Context, intent: Intent) {
            ContextCompat.startForegroundService(context.applicationContext, intent)
        }
    }
}
