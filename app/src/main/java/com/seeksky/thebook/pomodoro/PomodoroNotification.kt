package com.seeksky.thebook.pomodoro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.seeksky.thebook.MainActivity
import com.seeksky.thebook.R
import java.util.Locale
import kotlin.math.ceil

object PomodoroNotification {

    const val RUNNING_NOTIFICATION_ID = 7300
    const val COMPLETE_NOTIFICATION_ID = 7301

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val runningChannel = NotificationChannel(
            CHANNEL_RUNNING,
            context.getString(R.string.pomodoro_channel_running),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.pomodoro_channel_running_description)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        val alertChannel = NotificationChannel(
            CHANNEL_ALERTS,
            context.getString(R.string.pomodoro_channel_alerts),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.pomodoro_channel_alerts_description)
            enableVibration(true)
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .build()
            setSound(
                android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
                audioAttributes
            )
        }
        notificationManager.createNotificationChannel(runningChannel)
        notificationManager.createNotificationChannel(alertChannel)
    }

    fun buildRunning(
        context: Context,
        state: PomodoroState,
        remainingMillis: Long
    ): Notification {
        val isRunning = state.phase == PomodoroPhase.RUNNING
        val title = if (isRunning) {
            context.getString(R.string.pomodoro_notification_running)
        } else {
            context.getString(R.string.pomodoro_notification_paused)
        }
        val contentView = buildTimerContentView(context, title, isRunning, remainingMillis)

        val builder = NotificationCompat.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_pomodoro_timer_24)
            .setContentTitle(title)
            .setContentText(
                context.getString(
                    R.string.pomodoro_notification_remaining,
                    formatRemaining(remainingMillis)
                )
            )
            .setCustomContentView(contentView)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setContentIntent(contentPendingIntent(context))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSound(null)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (isRunning) {
            builder.addAction(
                R.drawable.ic_pomodoro_pause_24,
                context.getString(R.string.pomodoro_pause),
                servicePendingIntent(context, PomodoroService.ACTION_PAUSE, REQUEST_PAUSE)
            )
        } else {
            builder.addAction(
                R.drawable.ic_pomodoro_play_24,
                context.getString(R.string.pomodoro_resume),
                servicePendingIntent(context, PomodoroService.ACTION_RESUME, REQUEST_RESUME)
            )
        }

        builder.addAction(
            R.drawable.ic_pomodoro_stop_24,
            context.getString(R.string.pomodoro_end),
            servicePendingIntent(context, PomodoroService.ACTION_CANCEL, REQUEST_CANCEL)
        )
        return builder.build()
    }

    private fun buildTimerContentView(
        context: Context,
        title: String,
        isRunning: Boolean,
        remainingMillis: Long
    ): RemoteViews {
        return RemoteViews(context.packageName, R.layout.notification_pomodoro).apply {
            setTextViewText(R.id.text_notification_pomodoro_title, title)
            if (isRunning && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                setChronometer(
                    R.id.chronometer_notification_pomodoro,
                    SystemClock.elapsedRealtime() + remainingMillis,
                    context.getString(R.string.pomodoro_notification_chronometer_format),
                    true
                )
                setChronometerCountDown(R.id.chronometer_notification_pomodoro, true)
            } else {
                setTextViewText(
                    R.id.chronometer_notification_pomodoro,
                    context.getString(
                        R.string.pomodoro_notification_remaining,
                        formatRemaining(remainingMillis)
                    )
                )
            }
        }
    }

    fun notifyComplete(context: Context) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_pomodoro_timer_24)
            .setContentTitle(context.getString(R.string.pomodoro_complete_title))
            .setContentText(context.getString(R.string.pomodoro_complete_message))
            .setContentIntent(contentPendingIntent(context))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
        notificationManager(context).notify(COMPLETE_NOTIFICATION_ID, notification)
    }

    fun cancelComplete(context: Context) {
        notificationManager(context).cancel(COMPLETE_NOTIFICATION_ID)
    }

    private fun contentPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_CONTENT,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )
    }

    private fun servicePendingIntent(
        context: Context,
        action: String,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(context, PomodoroService::class.java).apply {
            this.action = action
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context, requestCode, intent, flags)
        } else {
            PendingIntent.getService(context, requestCode, intent, flags)
        }
    }

    private fun notificationManager(context: Context): NotificationManager {
        return context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private fun immutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
    }

    private fun formatRemaining(remainingMillis: Long): String {
        val totalSeconds = ceil(remainingMillis / 1_000.0).toLong().coerceAtLeast(0L)
        return String.format(
            Locale.getDefault(),
            "%02d:%02d",
            totalSeconds / 60L,
            totalSeconds % 60L
        )
    }

    private const val CHANNEL_RUNNING = "pomodoro_running"
    private const val CHANNEL_ALERTS = "pomodoro_alerts"
    private const val REQUEST_CONTENT = 7300
    private const val REQUEST_PAUSE = 7302
    private const val REQUEST_RESUME = 7303
    private const val REQUEST_CANCEL = 7304
}
