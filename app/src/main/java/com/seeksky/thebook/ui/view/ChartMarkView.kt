package com.seeksky.thebook.ui.view

import android.content.Context
import android.widget.TextView
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.utils.MPPointF
import com.github.mikephil.charting.utils.Utils
import com.seeksky.thebook.R


abstract class ChartMarkView<T>(context: Context?) :
    MarkerView(context, R.layout.tv_content) {

    var xData: List<T>? = null

    private val tvContent: TextView = findViewById(R.id.tv_content)

    override fun getOffsetForDrawingAtPoint(posX: Float, posY: Float): MPPointF {
        val offset = offset
        val chart = chartView
        //Marker 自身宽度
        val width = width
        //Marker 自身高度
        val height = height

        //避免盖住X轴文字

        val xAxisLabelHeight = Utils.convertPixelsToDp(20f)

        // posY \posX 指的是markerView左上角点在图表上面的位置
        //处理Y方向
        if (posY > chart.height - height - xAxisLabelHeight) {
            //不处理会超出下边界
            if (posY < height) {
                //直接-height会超出上边界
                //让Marker整体正好在图表View的竖直方向中心，这样可以尽可能避免Marker超出View范围被切割。
                offset.y = 0.5f * chart.height - 0.5f * height - posY
            } else {
                offset.y = -height.toFloat()
            }
        } else {
            offset.y = 0f
        }

        //处理X方向，分为2种情况，1、超出右边 2、正常情况
        if (posX > chart.width - width) {
            //如果超过右边界，则向左偏移markerView的宽度
            offset.x = -width.toFloat()
        } else {
            //默认情况，不用偏移
            offset.x = 0f
        }
        return offset;
    }

    fun getTvContent(): TextView {
        return tvContent
    }
}