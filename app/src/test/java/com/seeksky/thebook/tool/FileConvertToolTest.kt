package com.seeksky.toolbox.tool

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class FileConvertToolTest {

    @Test
    fun streamHeaderRoundTripPreservesMetadata() {
        val content = "SAF stream content".toByteArray()
        val fileName = "测试文件.txt"
        val digest = getFileDigest(ByteArrayInputStream(content))
        val md5 = digest.md5
        val encoded = ByteArrayOutputStream()

        val written = writeUserDefineFileHead(
            inputFileName = fileName,
            inputFileSize = content.size.toLong(),
            fileMd5 = md5,
            inputStream = ByteArrayInputStream(content),
            outputStream = encoded,
            fileHeadVersion = 1
        )
        val parsed = parseUserDefineFileHead(
            "encrypted.bin",
            ByteArrayInputStream(encoded.toByteArray())
        )

        assertEquals(fileName, parsed.fileNameInHead)
        assertEquals(content.size.toLong(), digest.size)
        assertEquals(content.size.toLong(), parsed.fileSize)
        assertEquals(md5, parsed.fileMd5)
        assertEquals(1, parsed.fileHeadVersion)
        assertEquals(written.fileHeadBytesCount, parsed.fileHeadBytesCount)
    }
}
