package com.seeksky.thebook.adapter

import android.annotation.SuppressLint
import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.viewholder.BaseViewHolder
import com.seeksky.thebook.R
import com.seeksky.thebook.database.entry.Daily
import java.text.SimpleDateFormat
import java.util.*

class RecentRecordAdapter(layoutResId: Int, data: MutableList<Daily>) : BaseQuickAdapter<Daily, BaseViewHolder>(layoutResId, data) {
    private val date: Date = Date()
    @SuppressLint("SimpleDateFormat")
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    init {
        addChildLongClickViewIds(R.id.ll_container)
    }

    override fun convert(holder: BaseViewHolder, item: Daily) {
        try {
            holder.setText(R.id.tvTitle, item.title)
            date.time = item.time
            holder.setText(R.id.tvTime, sdf.format(date))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}