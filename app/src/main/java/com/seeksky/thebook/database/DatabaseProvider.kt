package com.seeksky.thebook.database

import android.content.Context
import androidx.room.Room
import com.seeksky.thebook.Constants
import java.io.File
import java.io.IOException
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

data class DatabaseSnapshot(
    val directory: File,
    val databaseVersion: Int
)

/**
 * Owns the Room instance and prevents it from being used while a physical
 * database snapshot is being created.
 *
 * Every DAO operation must stay inside [withDatabase]. Returning a DAO or the
 * database instance from the callback would allow it to outlive the shared
 * lifecycle lock and would invalidate the backup guarantee.
 */
object DatabaseProvider {
    private val lifecycleLock = ReentrantReadWriteLock(true)
    private val instanceLock = Any()

    @Volatile
    private var instance: AppDatabase? = null

    fun <T> withDatabase(context: Context, block: (AppDatabase) -> T): T {
        return lifecycleLock.read {
            block(getOrCreateDatabase(context.applicationContext))
        }
    }

    /**
     * Creates a stable copy of the complete databases directory.
     *
     * The exclusive lifecycle lock waits for current DAO calls, blocks new
     * calls, checkpoints WAL, closes Room, and only then copies the files.
     * Room is opened lazily again by the next [withDatabase] call.
     */
    fun createSnapshot(context: Context, targetDirectory: File): DatabaseSnapshot {
        val applicationContext = context.applicationContext
        return lifecycleLock.write {
            prepareEmptyDirectory(targetDirectory)

            val database = getOrCreateDatabase(applicationContext)
            val sqliteDatabase = database.openHelper.writableDatabase
            val databaseVersion = readDatabaseVersion(sqliteDatabase)
            checkpointWal(sqliteDatabase)

            try {
                database.close()
            } finally {
                synchronized(instanceLock) {
                    if (instance === database) instance = null
                }
            }

            val sourceDirectory = applicationContext
                .getDatabasePath(Constants.APP_DATABASE_NAME)
                .parentFile
                ?: throw IOException("无法定位数据库目录")
            val sourceMainDatabase = File(sourceDirectory, Constants.APP_DATABASE_NAME)
            if (!sourceMainDatabase.isFile) {
                throw IOException("数据库文件不存在：${sourceMainDatabase.name}")
            }

            copyDirectoryContents(sourceDirectory, targetDirectory)
            val snapshotMainDatabase = File(targetDirectory, Constants.APP_DATABASE_NAME)
            if (!snapshotMainDatabase.isFile || snapshotMainDatabase.length() <= 0L) {
                throw IOException("数据库快照不完整")
            }

            DatabaseSnapshot(targetDirectory, databaseVersion)
        }
    }

    private fun getOrCreateDatabase(context: Context): AppDatabase {
        instance?.let { return it }
        return synchronized(instanceLock) {
            instance ?: Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                Constants.APP_DATABASE_NAME
            ).apply {
                addMigrations(MIGRATION_1_2)
                addMigrations(MIGRATION_2_3)
                addMigrations(MIGRATION_3_4)
            }.build().also { instance = it }
        }
    }

    private fun readDatabaseVersion(database: androidx.sqlite.db.SupportSQLiteDatabase): Int {
        return database.query("PRAGMA user_version").use { cursor ->
            if (!cursor.moveToFirst()) throw IOException("无法读取数据库版本")
            cursor.getInt(0)
        }
    }

    private fun checkpointWal(database: androidx.sqlite.db.SupportSQLiteDatabase) {
        database.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            if (!cursor.moveToFirst()) throw IOException("无法读取 WAL checkpoint 结果")
            val busy = cursor.getInt(0)
            if (busy != 0) throw IOException("数据库正忙，WAL checkpoint 未完成")
        }
    }

    private fun prepareEmptyDirectory(directory: File) {
        if (directory.exists()) {
            throw IOException("数据库快照目录已存在")
        }
        if (!directory.mkdirs()) {
            throw IOException("无法创建数据库快照目录")
        }
    }

    private fun copyDirectoryContents(source: File, target: File) {
        val children = source.listFiles()
            ?: throw IOException("无法读取数据库目录")
        children.forEach { child ->
            val targetChild = File(target, child.name)
            when {
                child.isDirectory -> {
                    if (!targetChild.mkdir()) {
                        throw IOException("无法创建快照子目录：${child.name}")
                    }
                    copyDirectoryContents(child, targetChild)
                }
                child.isFile -> child.copyTo(targetChild, overwrite = false)
            }
        }
    }
}
