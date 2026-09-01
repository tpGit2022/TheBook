package com.seeksky.thebook.pomodoro

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.seeksky.thebook.databinding.ActivityPomodoroCompletionBinding

class PomodoroCompletionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPomodoroCompletionBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        configureWindowForAlarm()
        super.onCreate(savedInstanceState)

        binding = ActivityPomodoroCompletionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.buttonPomodoroCompleteDismiss.setOnClickListener {
            finishAndRemoveTask()
        }

        acknowledgeCompletion()
    }

    private fun configureWindowForAlarm() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    private fun acknowledgeCompletion() {
        val current = PomodoroStateRepository.current(this)
        if (
            current.phase == PomodoroPhase.FINISHED &&
            current.completionDialogPending
        ) {
            PomodoroStateRepository.update(
                this,
                current.copy(completionDialogPending = false)
            )
        }
        PomodoroNotification.cancelComplete(this)
    }
}
