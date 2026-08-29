package com.seeksky.thebook.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.afollestad.materialdialogs.MaterialDialog
import com.blankj.utilcode.util.ToastUtils
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.viewholder.BaseViewHolder
import com.seeksky.thebook.R
import com.seeksky.thebook.adapter.RecentRecordAdapter
import com.seeksky.thebook.database.DatabaseProvider
import com.seeksky.thebook.database.entry.Daily
import com.seeksky.thebook.databinding.FragmentAddBinding
import com.seeksky.thebook.tool.applySchedulers
import io.reactivex.Observable
import io.reactivex.Observer
import io.reactivex.disposables.Disposable
import java.lang.StringBuilder
import java.util.*
import java.util.concurrent.TimeUnit

class AddFragment : Fragment() {

    private var _binding: FragmentAddBinding? = null
    private lateinit var  adapter: BaseQuickAdapter<Daily, BaseViewHolder>

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!
    val mData = mutableListOf<Daily>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        val addViewModel = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(requireActivity().application)
        )[AddViewModel::class.java]
        _binding = FragmentAddBinding.inflate(inflater, container, false)

        val root: View = binding.root

        val textZero: TextView = binding.textInsertZero
        val textSleep: TextView = binding.textInsertSleep
        val textAZero: TextView = binding.textInsertAzero
        addViewModel.text.observe(viewLifecycleOwner) {
//            addViewModel.addDailyData("zero")
        }

        adapter = RecentRecordAdapter(R.layout.item_recent_record, mData)
        binding.rv.adapter = adapter
        binding.rv.layoutManager = LinearLayoutManager(context)
        binding.rv.addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
        queryDailyData(mData)
        adapter.setOnItemChildLongClickListener { _, view: View, position ->
            val applicationContext = view.context.applicationContext
            MaterialDialog(view.context).show {
                message(text = "你确定要删除该条数据吗?")
                positiveButton {
                    dismiss()
                    if (position >= 0 && position < mData.size) {
                        Observable.create<Daily> {
                            val daily = mData[position]
                            DatabaseProvider.withDatabase(applicationContext) { database ->
                                database.getDailyDAO().delete(daily)
                            }
                            it.onNext(daily)
                            it.onComplete()
                        }.compose(applySchedulers()).subscribe(object :
                            Observer<Daily> {
                            override fun onComplete() {
                                ToastUtils.showShort("删除数据成功")
                            }

                            override fun onSubscribe(d: Disposable) {

                            }

                            override fun onNext(t: Daily) {
                                mData.remove(t)
                                adapter.notifyItemRemoved(position)
                            }

                            override fun onError(e: Throwable) {
                                e.printStackTrace()
                                ToastUtils.showShort("删除数据失败${e.message}")
                            }
                        })
                    }
                }
                negativeButton { dismiss() }
            }
            true
        }
        textZero.setOnClickListener {
            MaterialDialog(it.context).show {
                message(text = "确定新增zero类型吗?")
                positiveButton {
                    addViewModel.addDailyData("zero")
                }
                negativeButton {
                    dismiss()
                }
            }
        }

        textSleep.setOnClickListener {
            MaterialDialog(it.context).show {
                message(text = "确定新增sleep类型吗?")
                positiveButton {
                    addViewModel.addDailyData("sleep")
                }
                negativeButton {
                    dismiss()
                }
            }
        }

        textAZero.setOnClickListener {
            MaterialDialog(it.context).show {
                message(text = "确定新增azero类型吗?")
                positiveButton {
                    addViewModel.addDailyData("azero")
                }
                negativeButton {
                    dismiss()
                }
            }
        }
        updateTopDay()
        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }


    private fun queryDailyData(mData: MutableList<Daily>) {
//        val delay = if (SPUtils.getInstance(Constants.XML_FILE_NAME).getBoolean(Constants.KEY_DATA_MIGRATE)) 0 else 1000
        val delay = 0
        Observable.create<List<Daily>> {
            val list = mutableListOf<Daily>()
            context?.let { currentContext ->
                DatabaseProvider.withDatabase(currentContext) { database ->
                    val dao = database.getDailyDAO()
                    list.addAll(dao.getRecent(90))
                    list.addAll(dao.getRecentAction())
                }
            }
            it.onNext(list.toList())
            it.onComplete()
        }.delay(delay.toLong(), TimeUnit.MILLISECONDS).compose(applySchedulers())
            .subscribe(object : Observer<List<Daily>> {
                override fun onComplete() {

                }

                override fun onSubscribe(d: Disposable) {

                }

                @SuppressLint("NotifyDataSetChanged")
                override fun onNext(t: List<Daily>) {
                    mData.clear()
                    mData.addAll(t.toMutableList())
                    adapter.notifyDataSetChanged()
                }

                override fun onError(e: Throwable) {
                    e.printStackTrace()
                }
            })
    }

    private fun updateTopDay() {
//        val delay = if (SPUtils.getInstance(Constants.XML_FILE_NAME).getBoolean(Constants.KEY_DATA_MIGRATE)) 0 else 1000
        val delay = 0
        Observable.create<String> { it ->
            val list = mutableListOf<Daily>()
            val dailyMap = mutableMapOf<Long, String>()
            context?.let { currentContext ->
                DatabaseProvider.withDatabase(currentContext) { database ->
                    list.addAll(database.getDailyDAO().getRecent(999999))
                }
            }
            val size = list.size
            var max = 0L
            for (i in size - 1 downTo 1) {
                val gap = list[i - 1].time - list[i].time
                if (gap > max) max = gap
                dailyMap[gap] = String.format("%04d_%02d_%02d__%04d_%02d_%02d", list[i].year, list[i].month, list[i].day, list[i-1].year, list[i-1].month, list[i-1].day)
            }
//            it.onNext(list.toList())
            max /= (1000 * 60 * 60 * 24)
            var currentTime = Calendar.getInstance().time.time - list[0].time
            currentTime /= (1000 * 60 * 60 * 24)

            val sortHistoryDetailList = dailyMap.toList().sortedByDescending { it.first }
            val sb = StringBuilder()
            for (i in 0 until 6) {
                val gapDay = sortHistoryDetailList[i].first / (1000 * 60 * 60 * 24)
                sb.append(String.format("%02d", gapDay)).append("天").append(" ----> ").append(sortHistoryDetailList[i].second).append("\n")
            }
            it.onNext( String.format("%d:%d:%s", max, currentTime, sb.toString()))
            it.onComplete()
        }.delay(delay.toLong(), TimeUnit.MILLISECONDS).compose(applySchedulers())
            .subscribe(object : Observer<String> {
                override fun onComplete() {

                }

                override fun onSubscribe(d: Disposable) {

                }

                override fun onNext(t: String) {
                    val strArray = t.split(":")
                    binding.tvLastTop.text = strArray[0]
                    binding.tvCurrentTop.text = strArray[1]
                    binding.tvHistoryDetailContent.text = strArray[2]
                }

                override fun onError(e: Throwable) {
                    e.printStackTrace()
                }
            })
    }
}
