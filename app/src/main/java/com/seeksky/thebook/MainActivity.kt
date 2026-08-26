package com.seeksky.thebook

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.afollestad.materialdialogs.MaterialDialog
import com.blankj.utilcode.util.ToastUtils
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.permissionx.guolindev.PermissionX
import com.seeksky.thebook.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val manageAllFilesPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // 用户从设置页返回后，检查是否已获得权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                ToastUtils.showShort("已获得所有文件访问权限")
            } else {
                MaterialDialog(this).show {
                    message(R.string.exit_app_without_permission)
                    positiveButton(R.string.btn_ok) {
                        finish()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navView: BottomNavigationView = binding.navView

        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        val appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.navigation_record,
                R.id.navigation_chart,
                R.id.navigation_mine
            )
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        checkPermission()
    }

    private fun checkPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            //高版本需要额外跳转授权
            if (!Environment.isExternalStorageManager()) {
                PermissionX.init(this).permissions(
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                )
                    .setDialogTintColor(
                        ContextCompat.getColor(this, R.color.brand_primary),
                        ContextCompat.getColor(this, R.color.text_secondary)
                    )
                    .onExplainRequestReason { scope, deniedList ->
                        val message = "TheBook需要以下权限:"
                        scope.showRequestReasonDialog(deniedList, message, "确认", "取消")
                    }
                    .onForwardToSettings { scope, deniedList ->
                        scope.showForwardToSettingsDialog(deniedList, "您需要去应用设置中手动开启权限", "我已明白", "取消")
                    }
                    .request { allGranted, grantedList, deniedList ->
                        if (!allGranted) {
                            MaterialDialog(this).show {
                                message(R.string.exit_app_without_permission)
                                positiveButton(R.string.btn_ok) {
                                    finish()
                                }
                            }
                        }

                        if (allGranted) {
                            MaterialDialog(this).show {
                                message(R.string.tip_permission_request)
                                negativeButton(R.string.btn_no) { finish() }
                                positiveButton(R.string.btn_yes) {
                                    try {
                                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                        intent.data = Uri.parse("package:" + context.packageName)
                                        manageAllFilesPermissionLauncher.launch(intent)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                        manageAllFilesPermissionLauncher.launch(intent)
                                    }
                                }
                            }
                        }
                    }
            }
        } else {
            //低版本直接授权
            PermissionX.init(this).permissions(
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            )
                .setDialogTintColor(
                    ContextCompat.getColor(this, R.color.brand_primary),
                    ContextCompat.getColor(this, R.color.text_secondary)
                )
                .onExplainRequestReason { scope, deniedList ->
                    val message = "TheBook需要以下权限:"
                    scope.showRequestReasonDialog(deniedList, message, "确认", "取消")
                }
                .onForwardToSettings { scope, deniedList ->
                    scope.showForwardToSettingsDialog(deniedList, "您需要去应用设置中手动开启权限", "我已明白", "取消")
                }
                .request { allGranted, grantedList, deniedList ->
                    if (!allGranted) {
                        MaterialDialog(this).show {
                            message(R.string.exit_app_without_permission)
                            positiveButton(R.string.btn_ok) {
                                finish()
                            }
                        }
                    }
                }
        }

    }
}
