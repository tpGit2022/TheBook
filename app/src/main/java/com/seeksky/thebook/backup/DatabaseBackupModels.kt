package com.seeksky.thebook.backup

data class DatabaseBackupSuccess(
    val fileCount: Int,
    val totalBytes: Long
)

enum class DatabaseBackupProgress {
    PREPARING_SNAPSHOT,
    WRITING_ARCHIVE
}

data class BackupFileInfo(
    val path: String,
    val size: Long,
    val sha256: String
)
