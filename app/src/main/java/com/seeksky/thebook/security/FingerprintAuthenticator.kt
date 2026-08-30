package com.seeksky.thebook.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.hardware.fingerprint.FingerprintManagerCompat
import com.seeksky.thebook.R

object FingerprintAuthenticator {

    const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK

    @Suppress("DEPRECATION")
    fun availability(context: Context): Int {
        val fingerprintManager = FingerprintManagerCompat.from(context)
        if (!fingerprintManager.isHardwareDetected) {
            return BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
        }
        if (!fingerprintManager.hasEnrolledFingerprints()) {
            return BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
        }
        return BiometricManager.from(context).canAuthenticate(AUTHENTICATORS)
    }

    fun createUnlockPromptInfo(context: Context): BiometricPrompt.PromptInfo {
        return createPromptInfo(
            context,
            R.string.fingerprint_unlock_prompt_subtitle,
            R.string.fingerprint_prompt_exit
        )
    }

    fun createEnablePromptInfo(context: Context): BiometricPrompt.PromptInfo {
        return createPromptInfo(
            context,
            R.string.fingerprint_enable_prompt_subtitle,
            R.string.fingerprint_prompt_cancel
        )
    }

    private fun createPromptInfo(
        context: Context,
        subtitleRes: Int,
        negativeButtonRes: Int
    ): BiometricPrompt.PromptInfo {
        return BiometricPrompt.PromptInfo.Builder()
            .setTitle(context.getString(R.string.fingerprint_prompt_title))
            .setSubtitle(context.getString(subtitleRes))
            .setAllowedAuthenticators(AUTHENTICATORS)
            .setNegativeButtonText(context.getString(negativeButtonRes))
            .build()
    }

    fun availabilityMessage(context: Context, result: Int): String {
        return when (result) {
            BiometricManager.BIOMETRIC_SUCCESS ->
                context.getString(R.string.fingerprint_status_available)
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                context.getString(R.string.fingerprint_status_no_hardware)
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                context.getString(R.string.fingerprint_status_none_enrolled)
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                context.getString(R.string.fingerprint_status_unavailable)
            else -> context.getString(R.string.fingerprint_status_unavailable)
        }
    }
}
