package com.seeksky.thebook.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.github.mikephil.charting.charts.BarLineChartBase
import com.github.mikephil.charting.components.AxisBase
import com.github.mikephil.charting.components.Description
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IAxisValueFormatter
import com.github.mikephil.charting.formatter.IValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.seeksky.thebook.R
import com.seeksky.thebook.data.DailyGap
import com.seeksky.thebook.database.DatabaseProvider
import com.seeksky.thebook.database.entry.Daily
import com.seeksky.thebook.database.entry.Stat
import com.seeksky.thebook.databinding.FragmentStatisticBinding
import com.seeksky.thebook.tool.applySchedulers
import com.seeksky.thebook.ui.view.ChartMarkView
import io.reactivex.Observable
import io.reactivex.Observer
import io.reactivex.disposables.Disposable
import java.util.*
import java.util.concurrent.TimeUnit

class StatisticsFragment : Fragment() {

    private var _binding: FragmentStatisticBinding? = null
    private var mData = mutableListOf<Daily>()
    private val lineData = ArrayList<Entry>()
    private val list = ArrayList<Stat>()
    private lateinit var markView: ChartMarkView<Stat>
    private lateinit var markViewMonthStat: ChartMarkView<Stat>
    private lateinit var markViewDailyGap: ChartMarkView<DailyGap>

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        val dashboardViewModel =
            ViewModelProvider(this).get(StatisticsViewModel::class.java)

        _binding = FragmentStatisticBinding.inflate(inflater, container, false)
        val root: View = binding.root

        applyChartTheme(binding.lineChart)
        applyChartTheme(binding.barchartMonth)
        applyChartTheme(binding.barchartGap)

//        val textView: TextView = binding.textHome
//        dashboardViewModel.text.observe(viewLifecycleOwner) {
//            textView.text = it
//        }

        markView = object : ChartMarkView<Stat>(activity) {
            override fun refreshContent(e: Entry?, highlight: Highlight?) {
                val xIndex = e?.x?.toInt()
                getTvContent().text = String.format(
                    "(%s_%s,%.0f)",
                    xData!![xIndex!!].year,
                    xData!![xIndex].month,
                    e.y
                )
                super.refreshContent(e, highlight)
            }
        }
        markViewMonthStat = object : ChartMarkView<Stat>(activity) {
            override fun refreshContent(e: Entry?, highlight: Highlight?) {
                val xIndex = e?.x?.toInt()
                getTvContent().text = String.format(
                    "(%s_%s,%.0f)",
                    xData!![xIndex!!].year,
                    xData!![xIndex].month,
                    e.y
                )
                super.refreshContent(e, highlight)
            }
        }
        markViewDailyGap = object : ChartMarkView<DailyGap>(activity) {
            override fun refreshContent(e: Entry?, highlight: Highlight?) {
                val xIndex = e?.x?.toInt()
                getTvContent().text = String.format(
                    "(%s,%.0f)",
                    xData!![xIndex!!].gap_text,
                    e.y
                )
                super.refreshContent(e, highlight)
            }
        }
        loadDataForChart()
        loadGapData()
        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun themeColor(@ColorRes colorRes: Int): Int {
        return ContextCompat.getColor(requireContext(), colorRes)
    }

    private fun applyChartTheme(chart: BarLineChartBase<*>) {
        val axisColor = themeColor(R.color.chart_axis)
        val gridColor = themeColor(R.color.chart_grid)
        val secondaryTextColor = themeColor(R.color.text_secondary)

        chart.setBackgroundColor(themeColor(R.color.app_surface))
        chart.setNoDataTextColor(secondaryTextColor)
        chart.legend.textColor = secondaryTextColor
        chart.description.textColor = secondaryTextColor

        chart.xAxis.textColor = axisColor
        chart.xAxis.axisLineColor = axisColor
        chart.xAxis.gridColor = gridColor

        chart.axisLeft.textColor = axisColor
        chart.axisLeft.axisLineColor = axisColor
        chart.axisLeft.gridColor = gridColor

        chart.axisRight.textColor = axisColor
        chart.axisRight.axisLineColor = axisColor
        chart.axisRight.gridColor = gridColor
    }


    // load data from raw files, it will be load for the first time and it will be cache in databases in the future, but this time we don't have time to do this job
    private fun loadDataForChart() {
//        val delay = if (SPUtils.getInstance().getBoolean(Constants.KEY_DATA_MIGRATE)) 0 else 1000
        val delay = 0
        Observable.create<MutableList<Stat>> {
            val list = DatabaseProvider.withDatabase(requireActivity().applicationContext) { database ->
                database.getStatDAO().getStatDataSortByAsc(999999)
            }
            it.onNext(list.toMutableList())
            it.onComplete()
        }.delay(delay.toLong(), TimeUnit.MILLISECONDS).compose(applySchedulers())
            .subscribe(object : Observer<MutableList<Stat>> {
                override fun onComplete() {
                }

                override fun onNext(t: MutableList<Stat>) {
                    list.clear()
                    list.addAll(t)
                    fillLineChartData()
                    fillMonthBarChartData()
                }

                override fun onError(e: Throwable) {
                    e.printStackTrace()
                }

                override fun onSubscribe(d: Disposable) {

                }
            })
    }

    // fill the line chart with the ordered stat data list
    private fun fillLineChartData() {
        for (i in list.indices) {
            val entry = Entry(i.toFloat(), list[i].times.toFloat())
            lineData.add(entry)
        }
        val set = LineDataSet(lineData, "日期/月次")
        val data = LineData(set)
        binding.lineChart.apply {

        }
        // init xAxis bottom tips
        xAxisPropertiesInit(list)

        // init yAxis left or right Axis
        yAxisPropertiesInit(list)

        limitAxisPropertiesInit(list)

        linePropertiesInit(set)


        binding.lineChart.isDragEnabled = false
        binding.lineChart.isScaleXEnabled = false
        binding.lineChart.data = data
        binding.lineChart.postInvalidate()
    }

    private fun xAxisPropertiesInit(list: MutableList<Stat>) {
        val xAxis: XAxis = binding.lineChart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        val xAxisBottomData = arrayOfNulls<String>(list.size)
        var start_year = 2017
        var list_index = 0

        while (list_index < list.size) {
            xAxisBottomData[list_index] = ""
            if (list[list_index].month == 1) {
                xAxisBottomData[list_index] = String.format("%04d", start_year)
                start_year++
                list_index++
                continue
            } else if (list[list_index].month == 6) {
                xAxisBottomData[list_index] = String.format("%02d", list[list_index].month)
            } else if (list_index == 0) {
//                xAxisBottomData[list_index] = String.format("%02d", list[list_index].month)
            } else if (list_index == list.size - 1) {
                if (list[list_index].month != 6 || list[list_index].month == 10) {
                    xAxisBottomData[list_index] = String.format("%02d", list[list_index].month)
                }
            }
            list_index++
        }

        xAxis.valueFormatter = object : IAxisValueFormatter {
            override fun getFormattedValue(p0: Float, p1: AxisBase?): String {
                return xAxisBottomData[p0.toInt()]!!
            }
        }

        xAxis.setAxisMaxLabels(list.size)
        xAxis.setLabelCount(list.size, true)

//        xAxis.labelRotationAngle = 45F
        xAxis.textSize = 8F

        xAxis.setAvoidFirstLastClipping(true)
        xAxis.setDrawGridLines(false)
        xAxis.setDrawAxisLine(true)
        xAxis.setDrawLabels(true)
    }

    private fun yAxisPropertiesInit(list: MutableList<Stat>) {
        val yAxisLeft = binding.lineChart.axisLeft
        val yAxisRight = binding.lineChart.axisRight
        yAxisLeft.setDrawGridLines(false)
        var max = 0
        for (i in list) {
            max = max.coerceAtLeast(i.times)
        }
        max = max.coerceAtLeast(60)
        yAxisLeft.axisMaximum = (max + 12).toFloat()
//        yAxisLeft.spaceTop = 20f
        yAxisRight.isEnabled = false
    }

    private fun limitAxisPropertiesInit(list: MutableList<Stat>) {
        var sum = 0;
        var max = Int.MIN_VALUE;
        var min = Int.MAX_VALUE;
        for (i in list.indices) {
            sum += list[i].times
            if (!list[i].tag.equals("201612")) {
                min = min.coerceAtMost(list[i].times)
                max = max.coerceAtLeast(list[i].times)
            }
        }
        val avge = sum.toFloat() / list.size

        val avge_line = LimitLine(avge, String.format("%.1f", avge))
        avge_line.lineColor = themeColor(R.color.chart_average)
        avge_line.lineWidth = 0.6f
        avge_line.textColor = themeColor(R.color.chart_average)
        avge_line.textSize = 8f
        avge_line.enableDashedLine(5f, 20f, 0f)

        val min_line = LimitLine(min.toFloat(), String.format("%d", min))
        min_line.lineColor = themeColor(R.color.chart_minimum)
        min_line.lineWidth = 0.6f
        min_line.textColor = themeColor(R.color.chart_minimum)
        min_line.textSize = 8f
        min_line.enableDashedLine(5f, 20f, 0f)

        val max_line = LimitLine(max.toFloat(), String.format("%d", max))
        max_line.lineColor = themeColor(R.color.chart_maximum)
        max_line.lineWidth = 0.6f
        max_line.textColor = themeColor(R.color.chart_maximum)
        max_line.textSize = 8f
        max_line.enableDashedLine(5f, 20f, 0f)

        val leftAxis = binding.lineChart.axisLeft
        leftAxis.addLimitLine(avge_line)
        leftAxis.addLimitLine(min_line)
        leftAxis.addLimitLine(max_line)

    }

    private fun linePropertiesInit(line: LineDataSet) {
        line.color = themeColor(R.color.chart_line)

        line.circleRadius = 1.2f
        val circleColors = ArrayList<Int>()
        circleColors.add(themeColor(R.color.chart_point))
        line.circleColors = circleColors
        line.setDrawValues(false) //
        line.setDrawCircles(true)
        line.lineWidth = 2F
        val des = Description()
        des.text = ""

        markView.xData = list
        markView.chartView = binding.lineChart
        binding.lineChart.setMarker(markView)
//        binding.lineChart.extraRightOffset = 40 .0f 设置

        binding.lineChart.description = des
        binding.lineChart.isDoubleTapToZoomEnabled = false
//        line_chart.setScaleEnabled(false)
//        line_chart.isDragEnabled = false
    }

    private fun fillMonthBarChartData() {
        binding.barchartMonth.apply {
            // property set
            extraRightOffset = 10f
            isDoubleTapToZoomEnabled = false
//            setTouchEnabled(false)
            isScaleXEnabled = false
            isScaleYEnabled = false
            // prepare XAxis
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.textSize = 8F
            val data_size = 24
            xAxis.setAvoidFirstLastClipping(true)
            xAxis.setDrawGridLines(false)
            xAxis.setDrawAxisLine(true)
            xAxis.setDrawLabels(true)
//            xAxis.setCenterAxisLabels(true)
            xAxis
            xAxis.setLabelCount(data_size, true)
            xAxis.granularity = 1f

            val xAxisBottomData = arrayOfNulls<String>(data_size)

            val last24monthStatDataList = list.takeLast(data_size)
            var index = 0
            while (index < last24monthStatDataList.size) {
                if (last24monthStatDataList[index].month == 1) {
                    xAxisBottomData[index] = String.format("%04d", last24monthStatDataList[index].year)
                }
//                else if (index == last24monthStatDataList.size - 1) {
////                    xAxisBottomData[index] = String.format("%02d", last24monthStatDataList[index].month)
////                } else if (index % 3 == 0) {
////                    xAxisBottomData[index] = String.format("%02d", last24monthStatDataList[index].month)
                 else {
                xAxisBottomData[index] = String.format("%02d", last24monthStatDataList[index].month)
                }
                index++
            }

            //TODO 横坐标的标签值不准 不知道为什么会漏值
//            xAxis.valueFormatter = IAxisValueFormatter { value, axis -> xAxisBottomData[value.toInt()]!! }
            xAxis.valueFormatter =
                IAxisValueFormatter { value, axis -> xAxisBottomData[value.toInt()].toString() }

            //prepare yAxis
            binding.barchartMonth.axisRight.isEnabled = false
            val yAxis = binding.barchartMonth.axisLeft
            yAxis.setDrawGridLines(false)
            yAxis.axisMinimum = 0f
            yAxis.spaceTop = 30f
            yAxis.mDecimals = 0


            // all data property prepare
            val monthStatBarChartDataSource = ArrayList<BarEntry>()
            var sum = 0;
            var min = Int.MAX_VALUE;
            var max = Int.MIN_VALUE;
            for (i in last24monthStatDataList.indices) {
                val month_times = last24monthStatDataList[i].times
                val entry = BarEntry(i.toFloat(), month_times.toFloat())
                monthStatBarChartDataSource.add(entry)
                sum += month_times
                min = min.coerceAtMost(month_times)
                max = max.coerceAtLeast(month_times)
            }
            val dataSet = BarDataSet(monthStatBarChartDataSource, "近24月情况")
            dataSet.color = themeColor(R.color.chart_line)
            dataSet.setDrawValues(false)  //设置是否在柱状图单柱上显示当前数据
//            设置数据显示的格式，本来是float将其转为int后再转string消除小数部分
            dataSet.valueFormatter =
                IValueFormatter { value, entry, dataSetIndex, viewPortHandler -> value.toInt().toString() }
            val des = Description()
            des.text = ""
            description = des
            data = BarData(dataSet)

//            add limit line
            val average = (sum * 1.0f) / data_size
            val avge_line = LimitLine(average, String.format("%.1f", average))
            avge_line.lineColor = themeColor(R.color.chart_average)
            avge_line.lineWidth = 0.6f
            avge_line.textColor = themeColor(R.color.chart_average)
            avge_line.textSize = 8f
            avge_line.enableDashedLine(5f, 20f, 0f)

            val min_line = LimitLine(min.toFloat(), String.format("%d", min))
            min_line.lineColor = themeColor(R.color.chart_minimum)
            min_line.lineWidth = 0.6f
            min_line.textColor = themeColor(R.color.chart_minimum)
            min_line.textSize = 8f
            min_line.enableDashedLine(5f, 20f, 0f)

            val max_line = LimitLine(max.toFloat(), String.format("%d", max))
            max_line.lineColor = themeColor(R.color.chart_maximum)
            max_line.lineWidth = 0.6f
            max_line.textColor = themeColor(R.color.chart_maximum)
            max_line.textSize = 8f
            max_line.enableDashedLine(5f, 20f, 0f)

            yAxis.addLimitLine(avge_line)
            yAxis.addLimitLine(min_line)
            yAxis.addLimitLine(max_line)

            // add marker view
            markViewMonthStat.xData = last24monthStatDataList
            markViewMonthStat.chartView = binding.barchartMonth
            setDrawMarkers(true)
            marker = markViewMonthStat
            invalidate()
        }
    }

    private fun loadGapData() {
        val delay = 0
        Observable.create<MutableList<DailyGap>> {
            val list = DatabaseProvider.withDatabase(requireActivity().applicationContext) { database ->
                database.getDailyDAO().getRecent(40)
            }
            val t = list.sortedBy { it.time }.toMutableList()
            var gapDataSource = mutableListOf<DailyGap>()
            val calendar = Calendar.getInstance()
            val tpDaily = Daily(
                "tp",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH),
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.timeInMillis
            )
            t.add(tpDaily)
            val size = t.size
            val one_day = (1000 * 60 * 60 * 24)
            for (i in 0 until size - 1) {
                val gap = t[i + 1].time - t[i].time
                val gap_day = gap / one_day
                if (gap_day < 1) {
                    continue
                }
                val dailyGapBean = DailyGap()
                dailyGapBean.start_year = t[i].year
                dailyGapBean.start_month = t[i].month
                dailyGapBean.start_day = t[i].day
                dailyGapBean.start_date = String.format(
                    "%04d_%02d_%02d",
                    dailyGapBean.start_year,
                    dailyGapBean.start_month,
                    dailyGapBean.start_day
                )
                dailyGapBean.start_time_stamp = t[i].time
                dailyGapBean.end_year = t[i + 1].year
                dailyGapBean.end_month = t[i + 1].month
                dailyGapBean.end_day = t[i + 1].day
                dailyGapBean.end_time_stamp = t[i + 1].time
                dailyGapBean.end_date = String.format(
                    "%04d_%02d_%02d",
                    dailyGapBean.end_year,
                    dailyGapBean.end_month,
                    dailyGapBean.end_day
                )
                dailyGapBean.gap = gap_day.toInt()
                dailyGapBean.gap_text =
                    String.format("%s->%s", dailyGapBean.start_date, dailyGapBean.end_date)
                gapDataSource.add(dailyGapBean)
            }
            gapDataSource = gapDataSource.filter { it.gap != 0 }.toMutableList()

            it.onNext(gapDataSource)
            it.onComplete()
        }.delay(delay.toLong(), TimeUnit.MILLISECONDS).compose(applySchedulers())
            .subscribe(object : Observer<MutableList<DailyGap>> {
                override fun onComplete() {}

                override fun onNext(t: MutableList<DailyGap>) {

                    val xAxisBottomData = mutableListOf<String>()
                    val entries = mutableListOf<BarEntry>()
                    var sum = 0;
                    var min = Int.MAX_VALUE;
                    var max = Int.MIN_VALUE;
                    for (i in t.indices) {
                        val gap = t[i].gap
                        val entry = BarEntry(i.toFloat(), gap.toFloat())
                        entries.add(entry)
                        sum += gap
                        min = min.coerceAtMost(gap)
                        max = max.coerceAtLeast(gap)
                        xAxisBottomData.add(t[i].end_day.toString())
                    }
                    val barChart = binding.barchartGap

                    barChart.apply {
                        val barDataSet = BarDataSet(entries, "间隔")
                        barDataSet.color = themeColor(R.color.chart_line)
                        val barData = BarData(barDataSet)
                        barChart.data = barData
                        barChart.data.setDrawValues(false) //不绘制柱状图顶部的当前具体数值
                        description.isEnabled = false
                        setDrawGridBackground(false)

                        xAxis.isEnabled = true

                        xAxis.position = XAxis.XAxisPosition.BOTTOM
                        xAxis.setAvoidFirstLastClipping(true)

                    xAxis.valueFormatter =
                        IAxisValueFormatter { value, axis -> xAxisBottomData[value.toInt()] }
//                        xAxis.setAxisMaxLabels(30)
//                        xAxis.setLabelCount(30 , true)
//                        xAxis.setDrawLabels(false)
                        xAxis.setDrawGridLines(false)
                        xAxis.gridColor = themeColor(R.color.chart_grid)
                        xAxis.axisLineWidth = 2f
                        xAxis.axisLineColor = themeColor(R.color.chart_axis)
//                        xAxis.gridLineWidth = 0.25f
//                        xAxis.axisMinimum = -4.0f
                        xAxis.textSize = 8.0f



                        axisRight.isEnabled = false
                        val yAxis = axisLeft
                        yAxis.isEnabled = true
                        yAxis.setDrawGridLines(false)
                        yAxis.axisLineWidth = 2f
                        yAxis.axisLineColor = themeColor(R.color.chart_axis)
                        yAxis.axisMinimum = 0f //解决数据Y值为0时不跟X轴重合问题
                        yAxis.spaceTop = 30f


                        isDragEnabled = false
                        isScaleXEnabled = false
                        isScaleYEnabled = false

//                        add limit line
                        val average = (sum * 1.0f) / t.size
                        val avge_line = LimitLine(average, String.format("%.1f", average))
                        avge_line.lineColor = themeColor(R.color.chart_average)
                        avge_line.lineWidth = 0.6f
                        avge_line.textColor = themeColor(R.color.chart_average)
                        avge_line.textSize = 8f
                        avge_line.enableDashedLine(5f, 20f, 0f)

                        val min_line = LimitLine(min.toFloat(), String.format("%d", min))
                        min_line.lineColor = themeColor(R.color.chart_minimum)
                        min_line.lineWidth = 0.6f
                        min_line.textColor = themeColor(R.color.chart_minimum)
                        min_line.textSize = 8f
                        min_line.enableDashedLine(5f, 20f, 0f)

                        val max_line = LimitLine(max.toFloat(), String.format("%d", max))
                        max_line.lineColor = themeColor(R.color.chart_maximum)
                        max_line.lineWidth = 0.6f
                        max_line.textColor = themeColor(R.color.chart_maximum)
                        max_line.textSize = 8f
                        max_line.enableDashedLine(5f, 20f, 0f)

                        yAxis.addLimitLine(avge_line)
                        yAxis.addLimitLine(min_line)
                        yAxis.addLimitLine(max_line)

                        markViewDailyGap.xData = t
                        markViewDailyGap.chartView = binding.barchartGap
                        setDrawMarkers(true)
                        marker = markViewDailyGap

                        invalidate()
                    }
                }

                override fun onError(e: Throwable) {
                    e.printStackTrace()
                }

                override fun onSubscribe(d: Disposable) {

                }
            })
    }
}
