package com.seeksky.thebook.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.seeksky.thebook.database.entry.Stat

@Dao
interface StatDAO {
    @Query("SELECT * FROM stat_month ORDER BY tag ASC LIMIT :limit")
    fun getStatDataSortByAsc(limit: Int): List<Stat>

    @Query("SELECT * FROM stat_month ORDER BY tag DESC LIMIT :limit")
    fun getStatDataSortByDesc(limit: Int): List<Stat>


    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun addStat(stat: Stat)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun addStatList(stats: List<Stat>)

    @Query("DELETE FROM stat_month")
    fun deleteAll()
}
