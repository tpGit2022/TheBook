package com.seeksky.thebook

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.seeksky.thebook.backup.DatabaseBackupExporter
import com.seeksky.thebook.database.DatabaseProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class DatabaseBackupInstrumentedTest {

    @Test
    fun exportCreatesReadableArchiveWithValidDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        DatabaseProvider.withDatabase(context) { database ->
            database.openHelper.writableDatabase
        }

        val testDirectory = File(
            context.cacheDir,
            "database_backup_test_${UUID.randomUUID()}"
        )
        assertTrue(testDirectory.mkdirs())
        val archive = File(testDirectory, "backup.zip")
        val extractedDatabase = File(testDirectory, Constants.APP_DATABASE_NAME)

        try {
            val result = DatabaseBackupExporter.export(context, Uri.fromFile(archive))
            assertTrue(result.fileCount >= 1)
            assertTrue(result.totalBytes > 0L)
            assertTrue(archive.isFile)

            ZipFile(archive).use { zip ->
                val manifestEntry = zip.getEntry("backup_manifest.json")
                assertNotNull(manifestEntry)
                val manifest = zip.getInputStream(manifestEntry).bufferedReader().use {
                    JSONObject(it.readText())
                }
                assertEquals(1, manifest.getInt("backupFormatVersion"))
                assertEquals(context.packageName, manifest.getString("packageName"))

                val databaseEntry = zip.getEntry("databases/${Constants.APP_DATABASE_NAME}")
                assertNotNull(databaseEntry)
                zip.getInputStream(databaseEntry).use { input ->
                    extractedDatabase.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }

            val database = SQLiteDatabase.openDatabase(
                extractedDatabase.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            try {
                database.rawQuery("PRAGMA quick_check", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("ok", cursor.getString(0))
                }
            } finally {
                database.close()
            }
        } finally {
            testDirectory.deleteRecursively()
        }
    }

    @Test
    fun snapshotWaitsForActiveDatabaseOperation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val operationStarted = CountDownLatch(1)
        val releaseOperation = CountDownLatch(1)
        val snapshotFinished = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val snapshotDirectory = File(
            context.cacheDir,
            "database_snapshot_test_${UUID.randomUUID()}"
        )

        try {
            executor.execute {
                DatabaseProvider.withDatabase(context) {
                    operationStarted.countDown()
                    releaseOperation.await(10, TimeUnit.SECONDS)
                }
            }
            assertTrue(operationStarted.await(5, TimeUnit.SECONDS))

            executor.execute {
                try {
                    DatabaseProvider.createSnapshot(context, snapshotDirectory)
                } finally {
                    snapshotFinished.countDown()
                }
            }

            assertFalse(snapshotFinished.await(300, TimeUnit.MILLISECONDS))
            releaseOperation.countDown()
            assertTrue(snapshotFinished.await(10, TimeUnit.SECONDS))
            assertTrue(File(snapshotDirectory, Constants.APP_DATABASE_NAME).isFile)
        } finally {
            releaseOperation.countDown()
            executor.shutdownNow()
            snapshotDirectory.deleteRecursively()
        }
    }
}
