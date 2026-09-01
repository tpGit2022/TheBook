package com.seeksky.thebook.pomodoro

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.seeksky.thebook.MainActivity

object PomodoroAlarmScheduler {

    fun schedule(context: Context, triggerAtElapsedRealtime: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val operation = completionPendingIntent(context)

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !alarmManager.canScheduleExactAlarms()
        ) {
            scheduleInexact(alarmManager, triggerAtElapsedRealtime, operation)
            return
        }

        val remaining = (triggerAtElapsedRealtime - SystemClock.elapsedRealtime())
            .coerceAtLeast(0L)
        val triggerAtEpochMillis = System.currentTimeMillis() + remaining
        try {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(
                    triggerAtEpochMillis,
                    showTimerPendingIntent(context)
                ),
                operation
            )
        } catch (_: SecurityException) {
            scheduleInexact(alarmManager, triggerAtElapsedRealtime, operation)
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(completionPendingIntent(context))
    }

    private fun completionPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, PomodoroAlarmReceiver::class.java).apply {
            action = PomodoroService.ACTION_COMPLETE
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_COMPLETE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )
    }

    private fun showTimerPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_SHOW_TIMER,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )
    }

    private fun scheduleInexact(
        alarmManager: AlarmManager,
        triggerAtElapsedRealtime: Long,
        operation: PendingIntent
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtElapsedRealtime,
                operation
            )
        } else {
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtElapsedRealtime,
                operation
            )
        }
    }

    private fun immutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
    }

    private const val REQUEST_COMPLETE = 7301
    private const val REQUEST_SHOW_TIMER = 7305
}
