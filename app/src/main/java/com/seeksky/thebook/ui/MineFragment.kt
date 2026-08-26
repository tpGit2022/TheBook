package com.seeksky.thebook.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.afollestad.materialdialogs.MaterialDialog
import com.blankj.utilcode.util.ToastUtils
import com.permissionx.guolindev.PermissionX
import com.seeksky.thebook.R
import com.seeksky.thebook.databinding.FragmentMineBinding
import java.text.SimpleDateFormat
import java.util.*

class MineFragment : Fragment() {

    private val createExportDocument = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            it.data?.data?.let(mineViewModel::exportDataToXls)
        }
    }

    private val openImportDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(mineViewModel::prepareDataImport)
    }

    private var _binding: FragmentMineBinding? = null
    private lateinit var mineViewModel: MineViewModel

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!
    @SuppressLint("SimpleDateFormat")
    private final val sdf: SimpleDateFormat = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        mineViewModel = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(requireActivity().application)).get(
            MineViewModel::class.java)
        _binding = FragmentMineBinding.inflate(inflater, container, false)
        val root: View = binding.root

        val tvExportData: TextView = binding.textMineExportData
        mineViewModel.exportText.observe(viewLifecycleOwner) {
            tvExportData.text = it
        }
        binding.textMinePackageName.text = let {
            val appPackage = activity?.application?.packageName
            val appPackageInfo = appPackage?.let { it1 -> activity?.packageManager?.getPackageInfo(it1, 0) }
            val appVersionCode = appPackageInfo?.versionCode
            val appVersionName = appPackageInfo?.versionName
            val textContent = appPackage?.let {it2 -> "包名:$it2\n" }  + appVersionCode?.let {it3 -> "版本号:$it3\n"} + appVersionName?.let { it4 -> "版本名:$it4" }
            textContent
        }
        tvExportData.setOnClickListener {
            MaterialDialog(it.context).show {
                val export_file_name = String.format("%s.xls", sdf.format(Calendar.getInstance().time))
                val tip = String.format(getString(R.string.export_data_tip), export_file_name)
                message(text = tip)
                positiveButton { openSAFForSaveFile(export_file_name) }
                negativeButton { dismiss() }
            }
        }

        val tvImportData: TextView = binding.textMineImportData
        tvImportData.setOnClickListener {
            MaterialDialog(it.context).show {
                message(R.string.import_data_tip)
                positiveButton(R.string.btn_ok) { openSAFForImportFile() }
                negativeButton { dismiss() }
            }
        }
        mineViewModel.importState.observe(viewLifecycleOwner) { state ->
            when (state) {
                DataImportState.Idle -> {
                    tvImportData.isEnabled = true
                    tvImportData.text = getString(R.string.import_data)
                }
                DataImportState.Reading -> {
                    tvImportData.isEnabled = false
                    tvImportData.text = getString(R.string.import_data_reading)
                }
                DataImportState.Importing -> {
                    tvImportData.isEnabled = false
                    tvImportData.text = getString(R.string.import_data_writing)
                }
                is DataImportState.Preview -> {
                    tvImportData.isEnabled = true
                    tvImportData.text = getString(R.string.import_data)
                    showImportPreview(state)
                }
                is DataImportState.Success -> {
                    tvImportData.isEnabled = true
                    tvImportData.text = getString(R.string.import_data)
                    val message = if (state.mode == DataImportMode.MERGE) {
                        getString(
                            R.string.import_data_merge_success,
                            state.importedCount,
                            state.skippedCount
                        )
                    } else {
                        getString(R.string.import_data_replace_success, state.importedCount)
                    }
                    ToastUtils.showLong(message)
                    mineViewModel.consumeImportState()
                }
                is DataImportState.Error -> {
                    tvImportData.isEnabled = true
                    tvImportData.text = getString(R.string.import_data)
                    ToastUtils.showLong(getString(R.string.import_data_failed, state.message))
                    mineViewModel.consumeImportState()
                }
            }
        }

        val tvProcess = binding!!.textProcess
//        binding!!.textLog.movementMethod = ScrollingMovementMethod.getInstance()
        mineViewModel.processText.observe(viewLifecycleOwner) { text: String? ->
            tvProcess.text = text
        }
        val tvDirInfo = binding!!.textFileInfo
        mineViewModel.dirText.observe(viewLifecycleOwner) { text: String? ->
            tvDirInfo.text = text
        }
        mineViewModel.textLog.observe(viewLifecycleOwner) { text: String? ->
            binding!!.textLog.text = text
        }
        binding!!.etInputKey.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                // 在文本变化前执行操作（可选）
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // 当文本发生变化时更新 editTextData 的值

            }

            override fun afterTextChanged(s: Editable?) {
                // 在文本变化后执行操作（可选）
                mineViewModel.mTextInputKey.value = s.toString()
            }
        })

        binding!!.btnEncryptData.setOnClickListener {
            checkPermission()
            mineViewModel.encryptData()
        }
        binding!!.btnDecryptData.setOnClickListener {
            checkPermission()
            mineViewModel.decryptData()
        }
        return root
    }


    private fun openSAFForSaveFile(fileName: String) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/vnd.ms-excel"
            putExtra(Intent.EXTRA_TITLE, fileName)
            // Optionally, specify a URI for the directory that should be opened in
            // the system file picker before your app creates the document.
//            putExtra(DocumentsContract.EXTRA_INITIAL_URI, pickerInitialUri)
        }

        createExportDocument.launch(intent)
    }

    private fun openSAFForImportFile() {
        openImportDocument.launch(
            arrayOf(
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/octet-stream"
            )
        )
    }

    private fun showImportPreview(preview: DataImportState.Preview) {
        MaterialDialog(requireContext()).show {
            title(R.string.import_data_preview_title)
            message(
                text = getString(
                    R.string.import_data_preview_message,
                    preview.recordCount,
                    preview.duplicateCount
                )
            )
            positiveButton(R.string.import_data_merge) {
                mineViewModel.importPendingData(DataImportMode.MERGE)
            }
            neutralButton(R.string.import_data_replace) {
                dismiss()
                showReplaceConfirmation()
            }
            negativeButton {
                mineViewModel.cancelPendingImport()
                dismiss()
            }
        }
    }

    private fun showReplaceConfirmation() {
        MaterialDialog(requireContext()).show {
            title(R.string.import_data_replace_confirm_title)
            message(R.string.import_data_replace_confirm_message)
            positiveButton(R.string.import_data_replace) {
                mineViewModel.importPendingData(DataImportMode.REPLACE)
            }
            negativeButton {
                mineViewModel.cancelPendingImport()
                dismiss()
            }
        }
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun checkPermission() {
        PermissionX.init(this).permissions(
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        )
            .setDialogTintColor(
                ContextCompat.getColor(requireContext(), R.color.brand_primary),
                ContextCompat.getColor(requireContext(), R.color.text_secondary)
            )
            .onExplainRequestReason { scope, deniedList ->
                val message = "ToolBox需要以下权限:"
                scope.showRequestReasonDialog(deniedList, message, "确认", "取消")
            }
            .onForwardToSettings { scope, deniedList ->
                scope.showForwardToSettingsDialog(deniedList, "您需要去应用设置中手动开启权限", "我已明白", "取消")
            }
            .request { allGranted, grantedList, deniedList ->
                if (!allGranted) {
                    MaterialDialog(requireContext()).show {
                        message(R.string.exit_app_without_permission)
                        positiveButton(R.string.btn_ok) {
                            requireActivity().finish()
                        }
                    }
                }
            }
    }
}
