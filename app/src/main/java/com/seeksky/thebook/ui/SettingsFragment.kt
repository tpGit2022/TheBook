package com.seeksky.thebook.ui

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.fragment.app.Fragment
import com.seeksky.thebook.R
import com.seeksky.thebook.UnlockActivity
import com.seeksky.thebook.databinding.FragmentSettingsBinding
import com.seeksky.thebook.security.AppLockManager
import com.seeksky.thebook.security.FingerprintAuthenticator

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = requireNotNull(_binding)
    private var updatingSwitch = false

    private val enableLockLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val enabled = AppLockManager.isEnabled()
        setSwitchChecked(enabled)
        refreshStatus()
        if (result.resultCode == Activity.RESULT_OK && enabled) {
            showMessage(R.string.fingerprint_enabled)
        } else {
            showMessage(R.string.fingerprint_enable_cancelled)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setSwitchChecked(AppLockManager.isEnabled())
        refreshStatus()

        binding.switchFingerprintLock.setOnCheckedChangeListener { _, isChecked ->
            if (updatingSwitch) return@setOnCheckedChangeListener
            if (isChecked) {
                enableFingerprintLock()
            } else {
                AppLockManager.disable()
                refreshStatus()
                showMessage(R.string.fingerprint_disabled)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            setSwitchChecked(AppLockManager.isEnabled())
            refreshStatus()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun enableFingerprintLock() {
        val availability = FingerprintAuthenticator.availability(requireContext())
        if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
            setSwitchChecked(false)
            refreshStatus()
            Toast.makeText(
                requireContext(),
                FingerprintAuthenticator.availabilityMessage(requireContext(), availability),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        enableLockLauncher.launch(UnlockActivity.createEnableIntent(requireContext()))
    }

    private fun setSwitchChecked(checked: Boolean) {
        if (_binding == null) return
        updatingSwitch = true
        binding.switchFingerprintLock.isChecked = checked
        updatingSwitch = false
    }

    private fun refreshStatus() {
        if (_binding == null) return
        val enabled = AppLockManager.isEnabled()
        val availability = FingerprintAuthenticator.availability(requireContext())
        binding.textFingerprintStatus.text = when {
            enabled -> getString(R.string.fingerprint_status_enabled)
            availability == BiometricManager.BIOMETRIC_SUCCESS ->
                getString(R.string.fingerprint_status_disabled)
            else -> FingerprintAuthenticator.availabilityMessage(requireContext(), availability)
        }
    }

    private fun showMessage(messageRes: Int) {
        if (!isAdded) return
        Toast.makeText(requireContext(), messageRes, Toast.LENGTH_SHORT).show()
    }
}
