package com.seeksky.thebook

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.seeksky.thebook.databinding.ActivityUnlockBinding
import com.seeksky.thebook.security.AppLockManager
import com.seeksky.thebook.security.FingerprintAuthenticator

class UnlockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUnlockBinding
    private lateinit var biometricPrompt: BiometricPrompt
    private var flowCompleted = false
    private var mode = MODE_UNLOCK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUnlockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mode = intent.getIntExtra(EXTRA_MODE, MODE_UNLOCK)
        if (mode == MODE_UNLOCK && !AppLockManager.shouldAuthenticate()) {
            finishSuccessfully()
            return
        }

        AppLockManager.beginAuthentication()
        biometricPrompt = createBiometricPrompt()

        val availability = FingerprintAuthenticator.availability(this)
        if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
            val reason = FingerprintAuthenticator.availabilityMessage(this, availability)
            Toast.makeText(
                this,
                getString(R.string.fingerprint_unlock_unavailable, reason),
                Toast.LENGTH_LONG
            ).show()
            cancelFlow()
            return
        }

        if (savedInstanceState == null) {
            val promptInfo = if (mode == MODE_ENABLE) {
                FingerprintAuthenticator.createEnablePromptInfo(this)
            } else {
                FingerprintAuthenticator.createUnlockPromptInfo(this)
            }
            biometricPrompt.authenticate(promptInfo)
        }
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            if (flowCompleted) {
                AppLockManager.endAuthentication()
            } else {
                AppLockManager.cancelAuthentication()
            }
        }
        super.onDestroy()
    }

    private fun createBiometricPrompt(): BiometricPrompt {
        return BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    super.onAuthenticationSucceeded(result)
                    AppLockManager.completeAuthentication(enableLock = mode == MODE_ENABLE)
                    finishSuccessfully()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    cancelFlow()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Toast.makeText(
                        this@UnlockActivity,
                        R.string.fingerprint_not_recognized,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    private fun finishSuccessfully() {
        flowCompleted = true
        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun cancelFlow() {
        if (flowCompleted) return
        flowCompleted = true
        AppLockManager.cancelAuthentication()
        setResult(Activity.RESULT_CANCELED)
        if (mode == MODE_UNLOCK) {
            finishAndRemoveTask()
        } else {
            finish()
        }
    }

    companion object {
        private const val EXTRA_MODE = "unlock_mode"
        private const val MODE_UNLOCK = 0
        private const val MODE_ENABLE = 1

        fun createUnlockIntent(context: Context): Intent {
            return Intent(context, UnlockActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_UNLOCK)
        }

        fun createEnableIntent(context: Context): Intent {
            return Intent(context, UnlockActivity::class.java)
                .putExtra(EXTRA_MODE, MODE_ENABLE)
        }
    }
}
