package com.seeksky.toolbox.tool

/**
 * 文件加密工具类 采用AES256-CBC加密方式，采用PKCS5Padding/PKCS7Padding
 * 经过验证跟 https://www.mklab.cn/utils/aes 中CryptoJS组件加密内容一致
 * Android的cipher类需注意一点几点
 * 1. 针对大型文件的加密可以借助update方法，但是最终一定要调用doFinal并接收其返回值写入文件
 * 2. 创建cipher时Cipher.getInstance("AES/CBC/PKCS5Padding")这时数据并不需要自己手动pad/unpad
 * 3. 注意读取超大型文件的BlockSize应该是16的倍数
 * 4. 注意读取文件的有效值最后一次有可能小于你定义的文件读取的BlockSize,需传递有效长度给update
 * 5. 用户提供的密钥会先对其Base64编码然后取其MD5值作为文件加密的真实密钥，因此用户提供的密钥可以随意长度
 * 6. 调用update方法，如果传递的length小于16(一般是文件结尾) 返回数组将会为空此时调用doFinal来获取最后被处理后的数据
 *
 *
加密文件资产脚本，采用AES256的CBC模式进行加密
密钥使用32Bytes即AES256加密，初始向量IV需16Bytes
用户提供的密钥会先进行base64编码然后取MD5值32位的
BLOCK_SIZE固定为16
FILE_BLOCK_SIZE只要是16的整数倍就行，值太小会加长加解密时间
加密后的文件用MD5值作为文件名
解密的文件从文件头获取文件名
版本迭代
版本迭代主要涉及自定义文件头部分
#0版本:索引前后都包含
[0:27]:存储源文件的文件魔数。
[28:60]:源文件的MD5值

#1版本:索引前后都包含
[0:27]:存储源文件的文件魔数。
[28:29]:表示版本#1,#2...#255(用两个字节时为了兼容#0版本,[28]=#(0x23)在#0版本不可能出现)
[30:31]表示自定义文件头总字节数
[32:63]表示源文件MD5值
[64]:文件的大小(KB,MB,GB,Bytes)有多少字节才能表示,例如文件大小0字节[64]=1;文件大小3字节[64]=1;文件大小257字节[64]=2
[65:X]:文件大小(KB,MB,GB,Bytes)转成Bytes的值,例如文件文件大小0字节[65]=0x00;文件大小3字节[65]=0x03;
文件大小6670字节[65]=0x0E,[66]=0x1A。X的值等于65+[64]
[X+1:X+2]:文件名长度占据的字节数
[X+3:Y]:文件名
 */

import android.text.TextUtils
import android.util.Base64
import com.seeksky.toolbox.base.Constants
import com.seeksky.toolbox.base.Constants.BASE_INDEX_START
import com.seeksky.thebook.database.entry.FileConvertInfoEntry
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

const val userDefineAesFileEncryptKey = "Qwer1234!@#$"
val initialVector = "aes_256_initial_vector".substring(0, 16).toByteArray(Charset.forName("UTF-8"))
const val fileBlockSize = 1024 * 256
private const val LEGACY_HEADER_SIZE = 60
private const val MIN_VERSIONED_HEADER_SIZE = 68
private const val MAX_HEADER_SIZE = 0xffff

interface OnProcessListener {
    fun encryptDataSize(processSize: Long) {}
    fun decryptDataSize(processSize: Long) {}
}

data class FileDigest(val md5: String, val size: Long)

fun getAesEncryptKey(userInputKey: String = ""): ByteArray {
    var encryptKey = userInputKey
    if (TextUtils.isEmpty(encryptKey)) {
        encryptKey = userDefineAesFileEncryptKey
    }
    val base64EncodeKey = Base64.encodeToString(encryptKey.toByteArray(), Base64.NO_WRAP)
    return MessageDigest.getInstance("MD5").digest(base64EncodeKey.toByteArray(Charset.forName("UTF-8")))
}

fun getFileMD5(inputFilePath: String): String {
    return File(inputFilePath).inputStream().use(::getFileMD5)
}

fun getFileMD5(inputStream: InputStream): String {
    return getFileDigest(inputStream).md5
}

fun getFileDigest(inputStream: InputStream): FileDigest {
    val md5 = MessageDigest.getInstance("MD5")
    val buffer = ByteArray(8192)
    var totalBytes = 0L
    var bytesRead = inputStream.read(buffer)
    while (bytesRead != -1) {
        md5.update(buffer, 0, bytesRead)
        totalBytes += bytesRead
        bytesRead = inputStream.read(buffer)
    }
    return FileDigest(
        md5 = md5.digest().joinToString("") { "%02x".format(it) },
        size = totalBytes
    )
}


fun writeUserDefineFileHead(
    input_file: String,
    output_file: String,
    file_head_version: Int = 0
): FileConvertInfoEntry {
    val inputFile = File(input_file)
    val fileMd5 = getFileMD5(input_file)
    return FileInputStream(inputFile).use { input ->
        FileOutputStream(output_file, false).use { output ->
            writeUserDefineFileHead(
                inputFileName = inputFile.name,
                inputFileSize = inputFile.length(),
                fileMd5 = fileMd5,
                inputStream = input,
                outputStream = output,
                fileHeadVersion = file_head_version
            )
        }
    }
}

fun writeUserDefineFileHead(
    inputFileName: String,
    inputFileSize: Long,
    fileMd5: String,
    inputStream: InputStream,
    outputStream: OutputStream,
    fileHeadVersion: Int = 1
): FileConvertInfoEntry {
    require(fileMd5.length == 32) { "MD5 must contain 32 hexadecimal characters" }
    val fileMagicNumberBytes = ByteArray(Constants.FILE_MAGIC_NUMBER)
    readUpTo(inputStream, fileMagicNumberBytes.size).copyInto(fileMagicNumberBytes)

    val fileSizeBytesCount = if (inputFileSize == 0L) {
        1
    } else {
        (inputFileSize.toString(16).length + 1) / 2
    }
    val fileNameBytes = inputFileName.toByteArray(Charsets.UTF_8)
    val fileNameBytesCount = fileNameBytes.size
    require(fileNameBytesCount <= 0xffff) { "File name is too long" }
    val userDefineFileHeadList = mutableListOf<Byte>()
    userDefineFileHeadList.addAll(fileMagicNumberBytes.toList())
    userDefineFileHeadList.add(0x23.toByte()) // add # as version begin
    userDefineFileHeadList.add(fileHeadVersion.toByte())
    // 存储自定义文件头的总字节数 先占位
    userDefineFileHeadList.add(0x00.toByte())
    userDefineFileHeadList.add(0x00.toByte())
    userDefineFileHeadList.addAll(fileMd5.toByteArray(Charsets.US_ASCII).toList())

    userDefineFileHeadList.add(fileSizeBytesCount.toByte())

    var remainingSize = inputFileSize
    if (remainingSize == 0L) {
        userDefineFileHeadList.add(0x00.toByte())
    } else {
        while (remainingSize > 0) {
            userDefineFileHeadList.add((remainingSize and 0xff).toByte())
            remainingSize = remainingSize shr 8
        }
    }

    userDefineFileHeadList.addAll(
        ByteBuffer.allocate(2)
            .putShort(fileNameBytesCount.toShort())
            .array()
            .toList()
            .reversed()
    )

    userDefineFileHeadList.addAll(fileNameBytes.toList())
    val userDefineFileHeadSize = userDefineFileHeadList.size
    require(userDefineFileHeadSize <= 0xffff) { "File header is too large" }
    // Byte是有符号数类型
    userDefineFileHeadList[30] = (userDefineFileHeadSize and 0xff).toByte()
    userDefineFileHeadList[31] = ((userDefineFileHeadSize shr 8) and 0xff).toByte()

    outputStream.write(userDefineFileHeadList.toByteArray())
    val fileInfo = FileConvertInfoEntry()
    fileInfo.fileName = inputFileName
    fileInfo.fileNameInHead = inputFileName
    fileInfo.fileMd5 = fileMd5
    fileInfo.fileHeadVersion = fileHeadVersion
    fileInfo.fileSizeBytesCount = fileSizeBytesCount
    fileInfo.fileSize = inputFileSize
    fileInfo.fileNameBytesCount = fileNameBytesCount
    fileInfo.fileHeadBytesCount = userDefineFileHeadSize
    return fileInfo
}


fun parseUserDefineFileHead(inputFilePath: String): FileConvertInfoEntry {
    val inputFile = File(inputFilePath)
    return BufferedInputStream(FileInputStream(inputFile)).use { input ->
        parseUserDefineFileHead(inputFile.name, input)
    }
}

fun parseUserDefineFileHead(
    inputFileName: String,
    inputStream: InputStream
): FileConvertInfoEntry {
    val prefix = readUpTo(inputStream, BASE_INDEX_START + 1)
    if (prefix.size < LEGACY_HEADER_SIZE) {
        throw IOException("Encrypted file header is incomplete")
    }

    if (prefix[28] != 0x23.toByte()) {
        return FileConvertInfoEntry().apply {
            fileHeadVersion = 0
            fileMd5 = String(prefix, 28, 32, Charsets.US_ASCII)
            fileName = inputFileName
            fileNameInHead = inputFileName
            fileHeadBytesCount = LEGACY_HEADER_SIZE
        }
    }
    if (prefix.size < BASE_INDEX_START + 1) {
        throw IOException("Encrypted file header is incomplete")
    }

    val headerSize = (prefix[31].toUByte().toInt() shl 8) or
        prefix[30].toUByte().toInt()
    if (headerSize < MIN_VERSIONED_HEADER_SIZE || headerSize > MAX_HEADER_SIZE) {
        throw IOException("Encrypted file header size is invalid")
    }
    val remainingHeader = readExactly(inputStream, headerSize - prefix.size)
    val header = prefix + remainingHeader

    val fileSizeByteCount = header[BASE_INDEX_START].toUByte().toInt()
    if (fileSizeByteCount !in 1..Long.SIZE_BYTES) {
        throw IOException("Encrypted file size metadata is invalid")
    }
    val fileSizeStart = BASE_INDEX_START + 1
    val fileNameLengthIndex = fileSizeStart + fileSizeByteCount
    if (fileNameLengthIndex + 2 > header.size) {
        throw IOException("Encrypted file name metadata is incomplete")
    }

    var fileSize = 0L
    repeat(fileSizeByteCount) { index ->
        fileSize = fileSize or
            (header[fileSizeStart + index].toUByte().toLong() shl (index * 8))
    }

    val fileNameByteCount = header[fileNameLengthIndex].toUByte().toInt() or
        (header[fileNameLengthIndex + 1].toUByte().toInt() shl 8)
    val fileNameStart = fileNameLengthIndex + 2
    val fileNameEnd = fileNameStart + fileNameByteCount
    if (fileNameEnd > header.size) {
        throw IOException("Encrypted file name metadata is incomplete")
    }

    return FileConvertInfoEntry().apply {
        fileHeadVersion = header[29].toUByte().toInt()
        fileMd5 = String(header, 32, 32, Charsets.US_ASCII)
        fileName = inputFileName
        fileSizeBytesCount = fileSizeByteCount
        this.fileSize = fileSize
        fileNameBytesCount = fileNameByteCount
        fileNameInHead = String(header, fileNameStart, fileNameByteCount, Charsets.UTF_8)
        fileHeadBytesCount = headerSize
    }
}


fun encryptBigFileWithAES256(inputFilePath: String, outputFilePath: String, append: Boolean = true, callback: OnProcessListener? = null) {
    FileInputStream(inputFilePath).use { inputStream ->
        FileOutputStream(outputFilePath, append).use { outputStream ->
            encryptBigFileWithAES256(inputStream, outputStream, callback)
        }
    }
}

fun encryptBigFileWithAES256(
    inputStream: InputStream,
    outputStream: OutputStream,
    callback: OnProcessListener? = null
) {
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    val secretKey = SecretKeySpec(getAesEncryptKey(), "AES")
    val ivSpec = IvParameterSpec(initialVector)
    cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)

    val blockSize = fileBlockSize
    val inputBuffer = ByteArray(blockSize)
    var bytesRead: Int
    var totalReadBytesSize = 0L
    while (inputStream.read(inputBuffer).also { bytesRead = it } != -1) {
        totalReadBytesSize += bytesRead
        val encryptedBytes = cipher.update(inputBuffer, 0, bytesRead)
        if (encryptedBytes != null && encryptedBytes.isNotEmpty()) {
            outputStream.write(encryptedBytes)
        }
        //after send data to encrypt buffer, should reset buffer,avoid last read data
        inputBuffer.fill(0)
        callback?.encryptDataSize(totalReadBytesSize)
    }
    // if you invoke cipher.update to encrypt file with stream mode, you should invoke doFinal()
    val encryptedBytes = cipher.doFinal()
    outputStream.write(encryptedBytes)
    outputStream.flush()
}


fun decryptBigFileWithAES256(inputFilePath: String, outputFilePath: String, skip_bytes: Int = 0, callback: OnProcessListener? = null) {
    FileInputStream(inputFilePath).use { inputStream ->
        FileOutputStream(outputFilePath).use { outputStream ->
            decryptBigFileWithAES256(inputStream, outputStream, skip_bytes, callback)
        }
    }
}

fun decryptBigFileWithAES256(
    inputStream: InputStream,
    outputStream: OutputStream,
    skipBytes: Int = 0,
    callback: OnProcessListener? = null
) {
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    val secretKey = SecretKeySpec(getAesEncryptKey(), "AES")
    val ivSpec = IvParameterSpec(initialVector)
    cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

    val blockSize = fileBlockSize
    val inputBuffer = ByteArray(blockSize)

    var bytesRead: Int
    var totalReadBytesSize = skipBytes.toLong()
    // we define a file head to store file magic number and md5 which size is 60, we should skip it
    skipExactly(inputStream, skipBytes.toLong())

    while (inputStream.read(inputBuffer).also { bytesRead = it } != -1) {
        totalReadBytesSize += bytesRead
        val decryptedBytes = cipher.update(inputBuffer, 0, bytesRead)
        if (decryptedBytes != null && decryptedBytes.isNotEmpty()) {
            outputStream.write(decryptedBytes)
        }
        //after send data to encrypt buffer, should reset buffer,avoid last read data
        inputBuffer.fill(0)
        callback?.decryptDataSize(totalReadBytesSize)
    }
    // update方法可能返回空数组，调用doFinal获取最后一组处理后的数据
    val decryptedBytes = cipher.doFinal()
    outputStream.write(decryptedBytes)
    outputStream.flush()
}

private fun readUpTo(inputStream: InputStream, byteCount: Int): ByteArray {
    require(byteCount >= 0) { "byteCount must not be negative" }
    val buffer = ByteArray(byteCount)
    var offset = 0
    while (offset < byteCount) {
        val count = inputStream.read(buffer, offset, byteCount - offset)
        if (count < 0) break
        if (count == 0) {
            val value = inputStream.read()
            if (value < 0) break
            buffer[offset++] = value.toByte()
        } else {
            offset += count
        }
    }
    return if (offset == buffer.size) buffer else buffer.copyOf(offset)
}

private fun readExactly(inputStream: InputStream, byteCount: Int): ByteArray {
    val bytes = readUpTo(inputStream, byteCount)
    if (bytes.size != byteCount) {
        throw IOException("Encrypted file header is incomplete")
    }
    return bytes
}

private fun skipExactly(inputStream: InputStream, byteCount: Long) {
    require(byteCount >= 0) { "byteCount must not be negative" }
    var remaining = byteCount
    while (remaining > 0) {
        val skipped = inputStream.skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
        } else if (inputStream.read() >= 0) {
            remaining--
        } else {
            throw IOException("Encrypted file is shorter than its header")
        }
    }
}

fun compareFiles(fileOne: String, fileTwo: String): Boolean {
    val fileOneBytes = File(fileOne).readBytes()
    val fileTwoBytes = File(fileTwo).readBytes()
    return fileOneBytes.contentEquals(fileTwoBytes)
}


fun getPathDFSHelper(pathCollectList: MutableList<String>, inputPath: String, deep: Int) {
    val file = File(inputPath)
    if (!file.exists()) {
        println("目录不存在: $inputPath")
        return
    }

    if (deep > 10) {
        return
    }

    if (file.isFile) {
        pathCollectList.add(inputPath)
        return
    }

    val files = file.listFiles()
    files?.forEach { f ->
        val fAbs = f.absolutePath
        getPathDFSHelper(pathCollectList, fAbs, deep + 1)
    }
}
//
//fun saveVideoThumbnailAsJpg(videoPath: String, outputPath: String) {
//    val retriever = MediaMetadataRetriever()
//    retriever.setDataSource(videoPath)
//
//    val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
//    val durationInMillis = durationStr?.toLongOrNull() ?: 0 //获取视频时长 单位ms(毫秒)
//
//    // 获取指定位置的帧 单位是us(微秒)
//    val frame = retriever.getFrameAtTime(600 * 1000 * 1000)
//    // 保存为 JPG 文件
//    frame?.let {
//        val file = File(outputPath)
//        val outputStream = FileOutputStream(file)
//        it.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
//        outputStream.flush()
//        outputStream.close()
//    }
//}
