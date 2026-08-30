package com.seeksky.thebook.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

class BackupArchiveWriterTest {

    @Test
    fun sha256MatchesKnownValue() {
        val directory = Files.createTempDirectory("backup_hash_test").toFile()
        val file = File(directory, "value.txt")
        try {
            file.writeText("abc")
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                BackupArchiveWriter.sha256(file)
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun archiveContainsManifestAndDatabaseFiles() {
        val directory = Files.createTempDirectory("backup_archive_test").toFile()
        val databaseFile = File(directory, "daily.db")
        try {
            databaseFile.writeText("database-content")
            val files = listOf(
                BackupFileInfo(
                    path = "databases/daily.db",
                    size = databaseFile.length(),
                    sha256 = BackupArchiveWriter.sha256(databaseFile)
                )
            )
            val output = ByteArrayOutputStream()

            BackupArchiveWriter.write(
                output = output,
                databasesDirectory = directory,
                manifest = "{\"backupFormatVersion\":1}",
                files = files
            )

            val entries = mutableMapOf<String, String>()
            ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                    zip.closeEntry()
                }
            }

            assertNotNull(entries["backup_manifest.json"])
            assertEquals("database-content", entries["databases/daily.db"])
        } finally {
            directory.deleteRecursively()
        }
    }
}
