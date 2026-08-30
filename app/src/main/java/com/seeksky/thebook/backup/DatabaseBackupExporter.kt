package com.seeksky.thebook.backup

import android.content.ContentResolver
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import com.seeksky.thebook.Constants
import com.seeksky.thebook.database.DatabaseProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

object DatabaseBackupExporter {
    private const val BACKUP_FORMAT_VERSION = 1
    private const val BACKUP_CACHE_DIRECTORY = "database_backups"

    fun export(
        context: Context,
        destination: Uri,
        onProgress: (DatabaseBackupProgress) -> Unit = {}
    ): DatabaseBackupSuccess {
        val applicationContext = context.applicationContext
        val cacheRoot = File(applicationContext.cacheDir, BACKUP_CACHE_DIRECTORY)
        val stagingRoot = File(cacheRoot, UUID.randomUUID().toString())

        try {
            if (!stagingRoot.mkdirs()) {
                throw IOException("无法创建备份缓存目录")
            }

            onProgress(DatabaseBackupProgress.PREPARING_SNAPSHOT)
            val databasesSnapshotDirectory = File(stagingRoot, "databases")
            val snapshot = DatabaseProvider.createSnapshot(
                applicationContext,
                databasesSnapshotDirectory
            )

            validateMainDatabase(databasesSnapshotDirectory)
            val files = collectFileInfo(databasesSnapshotDirectory)
            if (files.isEmpty()) throw IOException("数据库快照中没有可导出的文件")

            val manifest = createManifest(
                context = applicationContext,
                databaseVersion = snapshot.databaseVersion,
                files = files
            )

            onProgress(DatabaseBackupProgress.WRITING_ARCHIVE)
            writeArchive(
                contentResolver = applicationContext.contentResolver,
                destination = destination,
                databasesDirectory = databasesSnapshotDirectory,
                manifest = manifest,
                files = files
            )
            return DatabaseBackupSuccess(
                fileCount = files.size,
                totalBytes = files.sumOf { it.size }
            )
        } finally {
            stagingRoot.deleteRecursively()
        }
    }

    private fun validateMainDatabase(databasesDirectory: File) {
        val databaseFile = File(databasesDirectory, Constants.APP_DATABASE_NAME)
        if (!databaseFile.isFile || databaseFile.length() <= 0L) {
            throw IOException("数据库快照缺少 ${Constants.APP_DATABASE_NAME}")
        }

        val database = SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
        try {
            database.rawQuery("PRAGMA quick_check", null).use { cursor ->
                if (!cursor.moveToFirst() || cursor.getString(0) != "ok") {
                    throw IOException("数据库完整性检查失败")
                }
            }
        } finally {
            database.close()
        }
    }

    private fun collectFileInfo(databasesDirectory: File): List<BackupFileInfo> {
        val canonicalRoot = databasesDirectory.canonicalFile
        return databasesDirectory.walkTopDown()
            .filter { it.isFile }
            .map { file ->
                val canonicalFile = file.canonicalFile
                ensureInsideDirectory(canonicalRoot, canonicalFile)
                val relativePath = canonicalFile.relativeTo(canonicalRoot)
                    .invariantSeparatorsPath
                BackupFileInfo(
                    path = "databases/$relativePath",
                    size = canonicalFile.length(),
                    sha256 = BackupArchiveWriter.sha256(canonicalFile)
                )
            }
            .sortedBy { it.path }
            .toList()
    }

    private fun createManifest(
        context: Context,
        databaseVersion: Int,
        files: List<BackupFileInfo>
    ): JSONObject {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        val fileArray = JSONArray()
        files.forEach { file ->
            fileArray.put(
                JSONObject()
                    .put("path", file.path)
                    .put("size", file.size)
                    .put("sha256", file.sha256)
            )
        }

        return JSONObject()
            .put("backupFormatVersion", BACKUP_FORMAT_VERSION)
            .put("packageName", context.packageName)
            .put("appVersionName", packageInfo.versionName.orEmpty())
            .put("appVersionCode", versionCode)
            .put("databaseVersion", databaseVersion)
            .put("createdAt", System.currentTimeMillis())
            .put("files", fileArray)
    }

    private fun writeArchive(
        contentResolver: ContentResolver,
        destination: Uri,
        databasesDirectory: File,
        manifest: JSONObject,
        files: List<BackupFileInfo>
    ) {
        var outputOpened = false
        try {
            val rawOutput = contentResolver.openOutputStream(destination)
                ?: throw IOException("无法打开目标备份文件")
            outputOpened = true
            BackupArchiveWriter.write(
                output = rawOutput,
                databasesDirectory = databasesDirectory,
                manifest = manifest.toString(2),
                files = files
            )
        } catch (error: Exception) {
            if (outputOpened) {
                runCatching { contentResolver.delete(destination, null, null) }
            }
            throw error
        }
    }

    private fun ensureInsideDirectory(root: File, file: File) {
        val rootPath = root.path + File.separator
        if (file != root && !file.path.startsWith(rootPath)) {
            throw IOException("备份目录中包含非法文件路径")
        }
    }
}
