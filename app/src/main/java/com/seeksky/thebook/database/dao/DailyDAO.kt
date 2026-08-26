package com.seeksky.thebook.database.dao

import androidx.room.*
import com.seeksky.thebook.database.entry.Daily


@Dao
interface DailyDAO {
    @Query("SELECT * FROM daily ORDER BY time ASC LIMIT :limit")
    fun getDailyDataSortByASC(limit: Int = 99999999): List<Daily>

    @Query("SELECT * FROM daily WHERE daily.title IN ('zero','sleep','azero') ORDER BY time DESC LIMIT :limit")
    fun getRecent(limit: Int = 120): List<Daily>

    @Query("SELECT * FROM daily WHERE daily.title NOT IN ('zero', 'sleep', 'azero', 'Azero') LIMIT :limit")
    fun getRecentAction(limit: Int = 20): List<Daily>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun addDaily(daily: Daily)

    @Query("SELECT * FROM daily ORDER BY time DESC LIMIT :limit")
    fun getDailyDataSortByDESC(limit: Int = 9999999): List<Daily>

    @Delete
    fun delete(daily: Daily)
}