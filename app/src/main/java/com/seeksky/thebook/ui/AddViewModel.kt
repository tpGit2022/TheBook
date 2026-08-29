package com.seeksky.thebook.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.blankj.utilcode.util.ToastUtils
import com.seeksky.thebook.database.DatabaseProvider
import com.seeksky.thebook.database.entry.Daily
import com.seeksky.thebook.database.entry.Stat
import com.seeksky.thebook.tool.applySchedulers
import io.reactivex.Observable
import io.reactivex.Observer
import io.reactivex.disposables.Disposable
import java.util.*

class AddViewModel(application: Application) : AndroidViewModel(application) {

    private val _text = MutableLiveData<String>().apply {
        value = "This is add record Fragment"
    }
    val text: LiveData<String> = _text
    val list = ArrayList<Stat>()


    fun addDailyData(type: String) {
        val daily = Daily(
            title = type,
            year = Calendar.getInstance().get(Calendar.YEAR),
            month = Calendar.getInstance().get(Calendar.MONTH) + 1,
            day = Calendar.getInstance().get(Calendar.DAY_OF_MONTH),
            hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
            time = Calendar.getInstance().timeInMillis
        )
        addDaily(daily, getApplication())
    }

    private fun addDaily(daily: Daily, context: Context) {
        Observable.create<Daily> {
            DatabaseProvider.withDatabase(context) { database ->
                database.runInTransaction {
                    val dailyDao = database.getDailyDAO()
                    val statDAO = database.getStatDAO()
                    dailyDao.addDaily(daily)

                    val st = Stat(
                        daily.year,
                        daily.month,
                        1,
                        String.format("%04d%02d", daily.year, daily.month)
                    )
                    val statList = statDAO.getStatDataSortByAsc(99999)
                    if (statList.isEmpty()) {
                        statDAO.addStat(st)
                    } else {
                        val lastStat: Stat = statList[statList.size - 1]
                        if (lastStat.year == st.year && lastStat.month == st.month) {
                            lastStat.times = lastStat.times + 1
                            statDAO.addStat(lastStat)
                        } else {
                            statDAO.addStat(st)
                        }
                    }
                }
            }
            it.onNext(daily)
            it.onComplete()
        }.compose(applySchedulers()).subscribe(object : Observer<Daily> {
            override fun onComplete() {
                ToastUtils.showShort("新增数据成功")
            }

            override fun onSubscribe(d: Disposable) {

            }

            override fun onNext(t: Daily) {

            }

            override fun onError(e: Throwable) {
                e.printStackTrace()
                ToastUtils.showShort("新增数据失败${e.message}")
            }
        })
    }


}
