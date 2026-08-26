package com.seeksky.thebook.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.seeksky.thebook.Constants
import com.seeksky.thebook.database.dao.DailyDAO
import com.seeksky.thebook.database.dao.StatDAO
import com.seeksky.thebook.database.entry.Daily
import com.seeksky.thebook.database.entry.Stat


@Database(entities = [Daily::class, Stat::class], exportSchema = false, version = 4)
abstract class AppDatabase: RoomDatabase() {
    abstract fun getDailyDAO(): DailyDAO
    abstract fun getStatDAO(): StatDAO

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: buildDatabase(context.applicationContext).also { instance = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(context, AppDatabase::class.java, Constants.APP_DATABASE_NAME).apply {
                addMigrations(MIGRATION_1_2)
                addMigrations(MIGRATION_2_3)
                addMigrations(MIGRATION_3_4)
            }.build()
        }
    }
}
