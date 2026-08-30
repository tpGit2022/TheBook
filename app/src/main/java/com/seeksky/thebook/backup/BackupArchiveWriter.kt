package com.seeksky.thebook.backup

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object BackupArchiveWriter {
    private const val BUFFER_SIZE = 32 * 1024

    fun write(
        output: OutputStream,
        databasesDirectory: File,
        manifest: String,
        files: List<BackupFileInfo>
    ) {
        ZipOutputStream(BufferedOutputStream(output, BUFFER_SIZE)).use { zip ->
            zip.putNextEntry(ZipEntry("backup_manifest.json"))
            zip.write(manifest.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            val canonicalRoot = databasesDirectory.canonicalFile
            files.forEach { fileInfo ->
                val relativePath = fileInfo.path.removePrefix("databases/")
                val source = File(canonicalRoot, relativePath).canonicalFile
                ensureInsideDirectory(canonicalRoot, source)
                if (!source.isFile) {
                    throw IOException("快照文件不存在：${fileInfo.path}")
                }

                zip.putNextEntry(ZipEntry(fileInfo.path))
                BufferedInputStream(FileInputStream(source), BUFFER_SIZE).use { input ->
                    input.copyTo(zip, BUFFER_SIZE)
                }
                zip.closeEntry()
            }
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file), BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private fun ensureInsideDirectory(root: File, file: File) {
        val rootPath = root.path + File.separator
        if (file != root && !file.path.startsWith(rootPath)) {
            throw IOException("备份目录中包含非法文件路径")
        }
    }
}
