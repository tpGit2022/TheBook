package com.seeksky.thebook

import android.app.Activity
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.afollestad.materialdialogs.MaterialDialog
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.seeksky.thebook.databinding.ActivityMainBinding
import com.seeksky.thebook.pomodoro.PomodoroNotification
import com.seeksky.thebook.pomodoro.PomodoroPhase
import com.seeksky.thebook.security.AppLockManager
import com.seeksky.thebook.ui.PomodoroViewModel

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var pomodoroViewModel: PomodoroViewModel
    private var pomodoroCompletionDialog: MaterialDialog? = null
    private var unlockRequestInFlight = false

    private val unlockLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        unlockRequestInFlight = false
        if (result.resultCode == Activity.RESULT_OK || !AppLockManager.isEnabled()) {
            updateLockOverlay()
        } else {
            finishAndRemoveTask()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        updateLockOverlay()

        val navView: BottomNavigationView = binding.navView
        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        val appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.navigation_record,
                R.id.navigation_chart,
                R.id.navigation_mine,
                R.id.navigation_settings
            )
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        observePomodoroCompletion()
    }

    override fun onStart() {
        super.onStart()
        requestUnlockIfNeeded()
    }

    private fun requestUnlockIfNeeded() {
        updateLockOverlay()
        if (!AppLockManager.shouldAuthenticate() || unlockRequestInFlight) return

        unlockRequestInFlight = true
        unlockLauncher.launch(UnlockActivity.createUnlockIntent(this))
    }

    private fun updateLockOverlay() {
        binding.appLockOverlay.visibility = if (AppLockManager.shouldAuthenticate()) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun observePomodoroCompletion() {
        pomodoroViewModel = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        )[PomodoroViewModel::class.java]
        pomodoroViewModel.state.observe(this) { state ->
            if (
                state.phase == PomodoroPhase.FINISHED &&
                state.completionDialogPending &&
                !isFinishing &&
                !isDestroyed
            ) {
                showPomodoroCompletionDialog()
                pomodoroViewModel.acknowledgeCompletionDialog()
            }
        }
    }

    private fun showPomodoroCompletionDialog() {
        if (pomodoroCompletionDialog?.isShowing == true) return

        pomodoroCompletionDialog = MaterialDialog(this).show {
            title(R.string.pomodoro_complete_title)
            message(R.string.pomodoro_complete_message)
            positiveButton(R.string.btn_ok)
        }.also { dialog ->
            dialog.setOnDismissListener {
                pomodoroCompletionDialog = null
            }
        }
        binding.root.post {
            PomodoroNotification.cancelComplete(applicationContext)
        }
    }
}
