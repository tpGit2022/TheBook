package com.seeksky.thebook.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // 1. 新增表
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS `totp` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `websiteName` TEXT NOT NULL,
                `accountName` TEXT NOT NULL,
                `totpSecretKey` TEXT NOT NULL,
                'addTime' INTEGER NOT NULL,
                'websiteUrl' TEXT
            )
        """)
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) = Unit
}

/** Remove the TOTP feature while preserving all record and statistics data. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("DROP TABLE IF EXISTS `totp`")
    }
}
