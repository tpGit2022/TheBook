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
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

const val userDefineAesFileEncryptKey = "Qwer1234!@#$"
val initialVector = "aes_256_initial_vector".substring(0, 16).toByteArray(Charset.forName("UTF-8"))
const val fileBlockSize = 1024 * 256

interface OnProcessListener {
    fun encryptDataSize(processSize: Long) {}
    fun decryptDataSize(processSize: Long) {}
}

fun getAesEncryptKey(userInputKey: String = ""): ByteArray {
    var encryptKey = userInputKey
    if (TextUtils.isEmpty(encryptKey)) {
        encryptKey = userDefineAesFileEncryptKey
    }
    val base64EncodeKey = Base64.encodeToString(encryptKey.toByteArray(), Base64.NO_WRAP)
    return MessageDigest.getInstance("MD5").digest(base64EncodeKey.toByteArray(Charset.forName("UTF-8")))
}

fun getFileMD5(inputFilePath: String): String {
    val md5 = MessageDigest.getInstance("MD5")
    val file = File(inputFilePath)
    file.inputStream().use { inputStream ->
        val buffer = ByteArray(8192)
        var bytesRead = inputStream.read(buffer)
        while (bytesRead != -1) {
            md5.update(buffer, 0, bytesRead)
            bytesRead = inputStream.read(buffer)
        }
    }
    return md5.digest().joinToString("") { "%02x".format(it) }
}


fun writeUserDefineFileHead(input_file: String, output_file: String, file_head_version: Int = 0): FileConvertInfoEntry {
    val fileMd5Str = getFileMD5(input_file)
    val fileMagicNumberBytes = ByteArray(Constants.FILE_MAGIC_NUMBER) { 0x00 }
    val inputFileTotalSize = File(input_file).length()
    val fileSizeBytesCount = ((inputFileTotalSize.toString(16).length + 1) / 2)
    val tpInputFileName = File(input_file).name
    val fileNameBytesCount = tpInputFileName.toByteArray(Charsets.UTF_8).size
    val userDefineFileHeadList = mutableListOf<Byte>()
    RandomAccessFile(input_file, "r").use { f_input ->
        val readFileMagicNumber = ByteArray(Constants.FILE_MAGIC_NUMBER)
        f_input.read(readFileMagicNumber)
        val fileMagicList = fileMagicNumberBytes.toMutableList()
        fileMagicList.subList(0, readFileMagicNumber.size).clear()
        fileMagicList.addAll(readFileMagicNumber.toList())
        userDefineFileHeadList.addAll(fileMagicList.toByteArray().toList())
    }
    userDefineFileHeadList.add(0x23.toByte()) // add # as version begin
    userDefineFileHeadList.add(file_head_version.toByte())
    // 存储自定义文件头的总字节数 先占位
    userDefineFileHeadList.add(0x00.toByte())
    userDefineFileHeadList.add(0x00.toByte())
    userDefineFileHeadList.addAll(fileMd5Str.toByteArray().toList())

    userDefineFileHeadList.add(fileSizeBytesCount.toByte())

    var remainingSize = inputFileTotalSize
    if (remainingSize == 0L) {
        userDefineFileHeadList.add(0x00.toByte())
    } else {
        while (remainingSize > 0) {
            userDefineFileHeadList.add((remainingSize and 0xff).toByte())
            remainingSize = remainingSize shr 8
        }
    }

    userDefineFileHeadList.addAll(ByteBuffer.allocate(2).putShort(fileNameBytesCount.toShort()).array().toList().reversed())

    userDefineFileHeadList.addAll(tpInputFileName.toByteArray().toList())
    val userDefineFileHeadSize = userDefineFileHeadList.size
    // Byte是有符号数类型
    userDefineFileHeadList[30] = (userDefineFileHeadSize and 0xff).toByte()
    userDefineFileHeadList[31] = ((userDefineFileHeadSize shr 8) and 0xff).toByte()

    File(output_file).outputStream().use { f_output ->
        f_output.write(userDefineFileHeadList.toByteArray())
    }
    val fileInfo = FileConvertInfoEntry()
    fileInfo.fileName = tpInputFileName
    fileInfo.fileMd5 = fileMd5Str
    fileInfo.fileHeadVersion = file_head_version
    fileInfo.fileSizeBytesCount = fileSizeBytesCount
    fileInfo.fileSize = inputFileTotalSize
    fileInfo.fileNameBytesCount = fileNameBytesCount
    fileInfo.fileHeadBytesCount = userDefineFileHeadSize
    return fileInfo
}


fun parseUserDefineFileHead(inputFilePath: String): FileConvertInfoEntry {
    val rsFileInfo = FileConvertInfoEntry()
    val tpInputFileName = File(inputFilePath).name
    val bis = BufferedInputStream(FileInputStream(inputFilePath))
    val readBuffer = ByteArray(500)
    val realReadBytesCount = bis.read(readBuffer)
    bis.close()
    val saturatedReadBytes = readBuffer.sliceArray(0 until realReadBytesCount)
    var useDefineFileHeadVersion = 0
    if (saturatedReadBytes[28] == 0x23.toByte()) {
        useDefineFileHeadVersion = saturatedReadBytes[29].toInt()
    } else {
        val md5Bytes = saturatedReadBytes.sliceArray(28 until 60)
        rsFileInfo.fileHeadVersion = useDefineFileHeadVersion
        rsFileInfo.fileMd5 = md5Bytes.decodeToString()
        rsFileInfo.fileName = tpInputFileName
        rsFileInfo.fileNameInHead = tpInputFileName
        rsFileInfo.fileHeadBytesCount = 60
        return rsFileInfo
    }

    // 前面存储的Bytes是有符号数直接toInt会导致超过127的变成负数
//    val fileHeadBytesCount = (saturatedReadBytes[31].toInt() shl 8) + saturatedReadBytes[30].toInt()
    val fileHeadBytesCount = (saturatedReadBytes[31].toUByte().toInt() shl 8) + saturatedReadBytes[30].toUByte().toInt()
    val md5Bytes = saturatedReadBytes.sliceArray(32 until 64)

    val fileLengthBytesCount = saturatedReadBytes[BASE_INDEX_START].toInt()
    var startIndex = BASE_INDEX_START + 1
    val end = BASE_INDEX_START + 1 + fileLengthBytesCount
    val fileLength = saturatedReadBytes.slice(startIndex until end)
        .foldRight(0L) { byte, acc ->
            (acc shl 8) or byte.toUByte().toLong()
        }
    startIndex = BASE_INDEX_START + fileLengthBytesCount + 1
    val fileNameLengthBytesCount = (saturatedReadBytes[startIndex + 1].toInt() shl 8) + saturatedReadBytes[startIndex].toInt()
    val fileNameBytes = saturatedReadBytes.sliceArray(startIndex + 2 until startIndex + 2 + fileNameLengthBytesCount)
    val fileNameStr = fileNameBytes.decodeToString()

    rsFileInfo.fileHeadVersion = useDefineFileHeadVersion
    rsFileInfo.fileMd5 = md5Bytes.decodeToString()
    rsFileInfo.fileName = tpInputFileName
    rsFileInfo.fileSizeBytesCount = fileLengthBytesCount
    rsFileInfo.fileSize = fileLength
    rsFileInfo.fileNameBytesCount = 2
    rsFileInfo.fileNameInHead = fileNameStr
    rsFileInfo.fileHeadBytesCount = fileHeadBytesCount
    return rsFileInfo
}


fun encryptBigFileWithAES256(inputFilePath: String, outputFilePath: String, append: Boolean = true, callback: OnProcessListener? = null) {
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    val secretKey = SecretKeySpec(getAesEncryptKey(), "AES")
    val ivSpec = IvParameterSpec(initialVector)
    cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)

    val input = File(inputFilePath)
    val output = File(outputFilePath)
    val inputStream = FileInputStream(input)
    val outputStream = FileOutputStream(output, append)

    val blockSize = fileBlockSize
    val inputBuffer = ByteArray(blockSize)
    var bytesRead: Int
    var totalReadBytesSize = 0L
    while (inputStream.read(inputBuffer).also { bytesRead = it } != -1) {
        totalReadBytesSize += bytesRead
        if (bytesRead == blockSize) {
            val encryptedBytes = cipher.update(inputBuffer, 0, blockSize)
            outputStream.write(encryptedBytes)
        } else {
            // need set buffer length to real read size
            // 设置数组长度为真实读取长度值，若有效读取长度小于16 返回的encryptedBytes为空数组
            val encryptedBytes = cipher.update(inputBuffer, 0, bytesRead)
            outputStream.write(encryptedBytes)
        }
        //after send data to encrypt buffer, should reset buffer,avoid last read data
        inputBuffer.fill(0)
        callback?.encryptDataSize(totalReadBytesSize)
    }
    // if you invoke cipher.update to encrypt file with stream mode, you should invoke doFinal()
    val encryptedBytes = cipher.doFinal()
    outputStream.write(encryptedBytes)
    inputStream.close()
    outputStream.flush()
    outputStream.close()
}


fun decryptBigFileWithAES256(inputFilePath: String, outputFilePath: String, skip_bytes: Int = 0, callback: OnProcessListener? = null) {
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    val secretKey = SecretKeySpec(getAesEncryptKey(), "AES")
    val ivSpec = IvParameterSpec(initialVector)
    cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

    val input = File(inputFilePath)
    val output = File(outputFilePath)
    val inputStream = FileInputStream(input)
    val outputStream = FileOutputStream(output)

    val blockSize = fileBlockSize
    val inputBuffer = ByteArray(blockSize)

    var bytesRead: Int
    var totalReadBytesSize = skip_bytes.toLong()
    // we define a file head to store file magic number and md5 which size is 60, we should skip it
    inputStream.skip(skip_bytes.toLong())

    while (inputStream.read(inputBuffer).also { bytesRead = it } != -1) {
        totalReadBytesSize += bytesRead
        if (bytesRead == blockSize) {
            val decryptedBytes = cipher.update(inputBuffer, 0, blockSize)
            outputStream.write(decryptedBytes)
        } else {
            // 设置数组长度为真实读取长度值，若有效读取长度小于16 返回的decryptedBytes为空数组
            val decryptedBytes = cipher.update(inputBuffer, 0, bytesRead)
            outputStream.write(decryptedBytes)
        }
        //after send data to encrypt buffer, should reset buffer,avoid last read data
        inputBuffer.fill(0)
        callback?.decryptDataSize(totalReadBytesSize)
    }
    // update方法可能返回空数组，调用doFinal获取最后一组处理后的数据
    val decryptedBytes = cipher.doFinal()
    outputStream.write(decryptedBytes)
    inputStream.close()
    outputStream.flush()
    outputStream.close()
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