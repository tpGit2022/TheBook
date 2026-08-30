package com.seeksky.thebook

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.blankj.utilcode.util.SPUtils
import com.seeksky.thebook.databinding.ActivitySplashBinding
import com.seeksky.thebook.tool.applySchedulers

import io.reactivex.Observable
import io.reactivex.functions.Consumer
import java.util.concurrent.TimeUnit



@SuppressLint("CustomSplashScreen")
class SplashActivity: AppCompatActivity() {
    private lateinit var binding: ActivitySplashBinding
    @SuppressLint("CheckResult")
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.SplashTheme)
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)
        val delay = if (SPUtils.getInstance(Constants.XML_FILE_NAME).getBoolean(Constants.KEY_DATA_MIGRATE, false)) 1200L else 3000L
        Observable.timer(delay, TimeUnit.MILLISECONDS).compose(applySchedulers()).subscribe(object: Consumer<Long> {
            override fun accept(t: Long?) {
                startActivity(Intent(this@SplashActivity, MainActivity::class.java))
                finish()
            }
        })
    }
}