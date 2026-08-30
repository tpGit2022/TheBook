package com.seeksky.thebook.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.seeksky.thebook.database.dao.DailyDAO
import com.seeksky.thebook.database.dao.StatDAO
import com.seeksky.thebook.database.entry.Daily
import com.seeksky.thebook.database.entry.Stat


@Database(entities = [Daily::class, Stat::class], exportSchema = false, version = 4)
abstract class AppDatabase: RoomDatabase() {
    abstract fun getDailyDAO(): DailyDAO
    abstract fun getStatDAO(): StatDAO
}
