package com.seeksky.thebook.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
import com.seeksky.thebook.pomodoro.PomodoroPhase
import com.seeksky.thebook.pomodoro.PomodoroState
import com.seeksky.thebook.tool.applySchedulers
import io.reactivex.Observable
import io.reactivex.Observer
import io.reactivex.disposables.Disposable
import java.lang.StringBuilder
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

class AddFragment : Fragment() {

    private var _binding: FragmentAddBinding? = null
    private lateinit var  adapter: BaseQuickAdapter<Daily, BaseViewHolder>
    private lateinit var pomodoroViewModel: PomodoroViewModel
    private val pomodoroHandler = Handler(Looper.getMainLooper())
    private var selectedPomodoroMinutes = 25
    private val pomodoroTicker = object : Runnable {
        override fun run() {
            val state = pomodoroViewModel.state.value ?: return
            renderPomodoro(state)
        }
    }

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
        pomodoroViewModel = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(requireActivity().application)
        )[PomodoroViewModel::class.java]
        _binding = FragmentAddBinding.inflate(inflater, container, false)

        val root: View = binding.root

        val textZero: TextView = binding.textInsertZero
        val textSleep: TextView = binding.textInsertSleep
        val textAZero: TextView = binding.textInsertAzero
        addViewModel.text.observe(viewLifecycleOwner) {
//            addViewModel.addDailyData("zero")
        }
        setupPomodoro()

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
        pomodoroHandler.removeCallbacks(pomodoroTicker)
        super.onDestroyView()
        _binding = null
    }

    override fun onResume() {
        super.onResume()
        if (::pomodoroViewModel.isInitialized) {
            pomodoroViewModel.refresh()
        }
    }

    private fun setupPomodoro() {
        binding.togglePomodoroDuration.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            selectedPomodoroMinutes = when (checkedId) {
                R.id.button_duration_15 -> 15
                R.id.button_duration_45 -> 45
                else -> 25
            }
        }

        binding.buttonPomodoroPrimary.setOnClickListener {
            when (pomodoroViewModel.state.value?.phase ?: PomodoroPhase.IDLE) {
                PomodoroPhase.IDLE,
                PomodoroPhase.FINISHED -> {
                    if (pomodoroViewModel.notificationsEnabled()) {
                        pomodoroViewModel.start(selectedPomodoroMinutes)
                    } else {
                        showNotificationSettingsDialog()
                    }
                }

                PomodoroPhase.RUNNING -> pomodoroViewModel.pause()
                PomodoroPhase.PAUSED -> pomodoroViewModel.resume()
            }
        }

        binding.buttonPomodoroEnd.setOnClickListener {
            MaterialDialog(requireContext()).show {
                title(R.string.pomodoro_end_confirm_title)
                message(R.string.pomodoro_end_confirm_message)
                negativeButton(R.string.btn_no)
                positiveButton(R.string.btn_yes) {
                    pomodoroViewModel.cancel()
                }
            }
        }

        pomodoroViewModel.state.observe(viewLifecycleOwner) { state ->
            renderPomodoro(state)
        }
    }

    private fun renderPomodoro(state: PomodoroState) {
        if (_binding == null) return
        pomodoroHandler.removeCallbacks(pomodoroTicker)

        val remaining = pomodoroViewModel.remainingMillis(state)
        binding.textPomodoroTime.text = formatPomodoroTime(remaining)
        binding.textPomodoroStatus.setText(
            when (state.phase) {
                PomodoroPhase.IDLE -> R.string.pomodoro_status_idle
                PomodoroPhase.RUNNING -> R.string.pomodoro_status_running
                PomodoroPhase.PAUSED -> R.string.pomodoro_status_paused
                PomodoroPhase.FINISHED -> R.string.pomodoro_status_finished
            }
        )
        binding.buttonPomodoroPrimary.setText(
            when (state.phase) {
                PomodoroPhase.IDLE -> R.string.pomodoro_start
                PomodoroPhase.RUNNING -> R.string.pomodoro_pause
                PomodoroPhase.PAUSED -> R.string.pomodoro_resume
                PomodoroPhase.FINISHED -> R.string.pomodoro_restart
            }
        )

        val isActive = state.isActive
        binding.buttonPomodoroEnd.visibility = if (isActive) View.VISIBLE else View.GONE
        setDurationButtonsEnabled(!isActive)

        if (!isActive) {
            selectedPomodoroMinutes = (state.durationMillis / 60_000L).toInt()
            val checkedButton = when (selectedPomodoroMinutes) {
                15 -> R.id.button_duration_15
                45 -> R.id.button_duration_45
                else -> R.id.button_duration_25
            }
            if (binding.togglePomodoroDuration.checkedButtonId != checkedButton) {
                binding.togglePomodoroDuration.check(checkedButton)
            }
        }

        if (state.phase == PomodoroPhase.RUNNING) {
            if (remaining <= 0L) {
                pomodoroViewModel.completeIfExpired()
            } else {
                val remainder = remaining % 1_000L
                val delay = if (remainder == 0L) 1_000L else remainder
                pomodoroHandler.postDelayed(pomodoroTicker, delay.coerceAtLeast(100L))
            }
        }
    }

    private fun setDurationButtonsEnabled(enabled: Boolean) {
        binding.buttonDuration15.isEnabled = enabled
        binding.buttonDuration25.isEnabled = enabled
        binding.buttonDuration45.isEnabled = enabled
    }

    private fun formatPomodoroTime(remainingMillis: Long): String {
        val totalSeconds = ceil(remainingMillis / 1_000.0).toLong().coerceAtLeast(0L)
        return String.format(
            Locale.getDefault(),
            "%02d:%02d",
            totalSeconds / 60L,
            totalSeconds % 60L
        )
    }

    private fun showNotificationSettingsDialog() {
        MaterialDialog(requireContext()).show {
            title(R.string.pomodoro_notification_required_title)
            message(R.string.pomodoro_notification_required_message)
            negativeButton(R.string.btn_no)
            positiveButton(R.string.pomodoro_open_settings) {
                val settingsIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
                    }
                } else {
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${requireContext().packageName}")
                    )
                }
                startActivity(settingsIntent)
            }
        }
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
