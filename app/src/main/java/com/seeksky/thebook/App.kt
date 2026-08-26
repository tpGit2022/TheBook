package com.seeksky.thebook

import android.app.Application
import android.os.StrictMode
import androidx.appcompat.app.AppCompatDelegate
import com.blankj.utilcode.util.SPUtils
import com.seeksky.thebook.tool.startMigrateData

class App: Application() {

    init {
        // enable svg library function
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
    }

    override fun onCreate() {
        initDebug(false)
        super.onCreate()
        migrateData()
    }

    private fun initDebug(enable: Boolean) {
        //TODO temp close strict mode avoid unknown problem
        if (!enable || !BuildConfig.DEBUG) return
        val build = StrictMode.ThreadPolicy.Builder()
        build.detectDiskReads().detectDiskWrites().detectNetwork()
        build.penaltyLog()
        StrictMode.setThreadPolicy(build.build())

        val vpBuild = StrictMode.VmPolicy.Builder()
        vpBuild.detectLeakedSqlLiteObjects().detectLeakedClosableObjects()
        vpBuild.penaltyLog().penaltyDeath()
        StrictMode.setVmPolicy(vpBuild.build())
    }

    private fun migrateData() {
        val isMigrate = SPUtils.getInstance(Constants.XML_FILE_NAME).getBoolean(Constants.KEY_DATA_MIGRATE, false)
        if (!isMigrate) {
            startMigrateData(applicationContext, resources)
        }
    }
}
