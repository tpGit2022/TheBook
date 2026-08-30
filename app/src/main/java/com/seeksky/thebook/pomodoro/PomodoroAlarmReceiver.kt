package com.seeksky.thebook.pomodoro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PomodoroAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == PomodoroService.ACTION_COMPLETE) {
            PomodoroService.complete(context)
        }
    }
}
