package com.seeksky.thebook.database.entry

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily")
data class Daily(val title: String, val year: Int, val month: Int, val day: Int, val hour: Int, var time: Long) {
    @PrimaryKey(autoGenerate = true) var id: Int = 0
}