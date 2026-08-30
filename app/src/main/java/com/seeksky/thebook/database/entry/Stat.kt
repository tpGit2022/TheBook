package com.seeksky.thebook.database.entry

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 每月的统计记录
 * //TODO 感觉不是很必要可以去掉
 */
@Entity(tableName = "stat_month")
data class Stat(val year: Int, val month: Int, var times: Int, val tag: String) {
    @PrimaryKey(autoGenerate = true) var id: Int = 0
}