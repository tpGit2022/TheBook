package com.seeksky.thebook.database.entry

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey

@Entity(tableName = "FileConvertInfo")
class FileConvertInfoEntry {
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0

    @ColumnInfo(name = "file_name")
    var fileName: String = ""

    @ColumnInfo(name = "file_head_version")
    var fileHeadVersion: Int = 0

    @ColumnInfo(name = "file_md5")
    var fileMd5: String = ""

    @ColumnInfo(name = "file_size")
    var fileSize: Long = 0L

    @Ignore
    var fileSizeBytesCount: Int = 0

    @ColumnInfo(name = "file_name_in_head")
    var fileNameInHead: String = ""

    @Ignore
    var fileNameBytesCount: Int = 0

    @ColumnInfo(name = "file_head_bytes_count")
    var fileHeadBytesCount: Int = 0

    override fun toString(): String {
        return "文件名: $fileName, 加密版本 V$fileHeadVersion, " +
                "MD5: $fileMd5, 文件大小: $fileSize, " +
                "文件大小字节数: $fileSizeBytesCount, " +
                "文件名: $fileNameInHead, 文件名字节数: $fileNameBytesCount, " +
                "文件头字节数: $fileHeadBytesCount"
    }
}