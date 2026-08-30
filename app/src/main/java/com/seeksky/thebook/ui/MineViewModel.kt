package com.seeksky.thebook.ui

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.documentfile.provider.DocumentFile
import com.blankj.utilcode.util.ToastUtils
import com.seeksky.thebook.App
import com.seeksky.thebook.backup.DatabaseBackupExporter
import com.seeksky.thebook.backup.DatabaseBackupProgress
import com.seeksky.thebook.database.DatabaseProvider
import com.seeksky.thebook.database.entry.Daily
import com.seeksky.thebook.storage.SafWorkspace
import com.seeksky.thebook.tool.createMonthStats
import com.seeksky.thebook.tool.parseDailyBackup
import com.seeksky.thebook.tool.toRecordKey
import com.seeksky.toolbox.tool.OnProcessListener
import com.seeksky.toolbox.tool.FileDigest
import com.seeksky.toolbox.tool.decryptBigFileWithAES256
import com.seeksky.toolbox.tool.encryptBigFileWithAES256
import com.seeksky.toolbox.tool.getAesEncryptKey
import com.seeksky.toolbox.tool.getFileDigest
import com.seeksky.toolbox.tool.getFileMD5
import com.seeksky.toolbox.tool.initialVector
import com.seeksky.toolbox.tool.parseUserDefineFileHead
import com.seeksky.toolbox.tool.writeUserDefineFileHead
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import jxl.Workbook
import jxl.write.Label
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

enum class DataImportMode {
    MERGE,
    REPLACE
}

sealed class DataImportState {
    object Idle : DataImportState()
    object Reading : DataImportState()
    object Importing : DataImportState()
    data class Preview(val recordCount: Int, val duplicateCount: Int) : DataImportState()
    data class Success(
        val mode: DataImportMode,
        val importedCount: Int,
        val skippedCount: Int
    ) : DataImportState()
    data class Error(val message: String) : DataImportState()
}

sealed class DatabaseBackupState {
    object Idle : DatabaseBackupState()
    object PreparingSnapshot : DatabaseBackupState()
    object WritingArchive : DatabaseBackupState()
    data class Success(val fileCount: Int, val totalBytes: Long) : DatabaseBackupState()
    data class Error(val message: String) : DatabaseBackupState()
}

private data class ImportInspection(
    val records: List<Daily>,
    val duplicateCount: Int
)

private data class WorkspaceSourceFile(
    val document: DocumentFile,
    val name: String,
    val digest: FileDigest
)

class MineViewModel(application: Application) : AndroidViewModel(application) {
    private val getContentResolver by lazy {
        getApplication<App>().contentResolver
    }

    private val _exportText = MutableLiveData<String>().apply {
        value = "记录导出（XLS）"
    }
    val exportText: LiveData<String> = _exportText

    private val _importState = MutableLiveData<DataImportState>(DataImportState.Idle)
    val importState: LiveData<DataImportState> = _importState
    private var pendingImportRecords: List<Daily> = emptyList()

    private val _databaseBackupState = MutableLiveData<DatabaseBackupState>(DatabaseBackupState.Idle)
    val databaseBackupState: LiveData<DatabaseBackupState> = _databaseBackupState

    private val _mTextDirInfo: MutableLiveData<String> = MutableLiveData()
    val dirText: LiveData<String> get() = _mTextDirInfo

    private val _mProcessText: MutableLiveData<String> = MutableLiveData()
    val processText: LiveData<String> get() = _mProcessText

    private val _mTextLog: MutableLiveData<String> = MutableLiveData()
    val textLog: LiveData<String> get() = _mTextLog

    var mTextInputKey: MutableLiveData<String> = MutableLiveData()

    private val logSubject = BehaviorSubject.createDefault("")
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private val safWorkspace = SafWorkspace(application)
    @Volatile
    private var fileOperationRunning = false

    private val disposables = CompositeDisposable() // 管理所有订阅
    init {
        refreshWorkspaceInfo()
        _mTextLog.value = ""
        val disposable = logSubject
            .filter { it.isNotBlank() } // 过滤掉空白日志
            .observeOn(AndroidSchedulers.mainThread()) // 确保在主线程更新 LiveData
            .subscribe(
                { log ->
                    val timestamp = sdf.format(Calendar.getInstance().time)
                    val newLog = "$timestamp -> $log\n${_mTextLog.value.orEmpty()}"
                    _mTextLog.postValue(newLog)
                },
                { throwable ->
                    // 错误处理，可以记录到文件或输出日志
                    Log.e("LogManager", "Error updating log", throwable)
                }
            )
        disposables.add(disposable)
    }

    fun hasWorkspaceAccess(): Boolean = safWorkspace.hasPersistedAccess()

    fun selectWorkspace(uri: Uri): Boolean {
        return try {
            safWorkspace.persist(uri)
            safWorkspace.openDirectories()
            refreshWorkspaceInfo()
            logSubject.onNext("工作目录授权成功")
            true
        } catch (error: Exception) {
            safWorkspace.clearPersistedAccess()
            refreshWorkspaceInfo()
            logSubject.onNext("工作目录授权失败：${error.message ?: "未知错误"}")
            false
        }
    }

    fun refreshWorkspaceInfo() {
        val selectedName = safWorkspace.selectedDirectoryName()
        var tip = if (selectedName == null) {
            "尚未选择加解密工作目录\n请选择 00SERVER 文件夹"
        } else {
            "当前工作目录：$selectedName\n" +
                "原始数据目录：origin_media_data/\n" +
                "加密数据目录：f_input/encrypt_data/\n" +
                "解密数据目录：f_output/decrypt_data/"
        }
        tip += String.format(
            "\n\nAES Key:%s",
            getAesEncryptKey().joinToString("") { "%02x".format(it) }
        )
        tip += String.format(
            "\n\nAES IV :%s",
            initialVector.joinToString("") { "%02x".format(it) }
        )
        _mTextDirInfo.value = tip
    }

    fun encryptData(): Boolean {
        if (!hasWorkspaceAccess()) {
            logSubject.onNext("请先选择 00SERVER 工作目录")
            return false
        }
        if (fileOperationRunning) {
            logSubject.onNext("已有文件任务正在执行，请稍候")
            return false
        }
        fileOperationRunning = true
        logSubject.onNext("开始加密操作...")
        val disposable = Observable.fromCallable {
            val directories = safWorkspace.openDirectories()
            val documents = safWorkspace.listFilesRecursively(directories.originalFiles)
            logSubject.onNext("待加密文件个数：${documents.size}")
            if (documents.isEmpty()) {
                _mProcessText.postValue("没有待加密文件")
                return@fromCallable 0
            }

            val files = documents.map { document ->
                val name = document.name ?: "未命名文件"
                val digest = getContentResolver.openInputStream(document.uri)
                    ?.use(::getFileDigest)
                    ?: throw IOException("无法读取文件：$name")
                WorkspaceSourceFile(document, name, digest)
            }
            val totalSize = files.fold(0L) { total, file -> total + file.digest.size }
            var completedSize = 0L
            files.forEach { source ->
                val inputFile = source.document
                val inputName = source.name
                val digest = source.digest
                val fileMd5 = digest.md5
                val outputName = encryptedOutputName(inputName, fileMd5)
                val outputFile = safWorkspace.replaceFile(directories.encryptedFiles, outputName)
                try {
                    getContentResolver.openOutputStream(outputFile.uri, "w")?.use { output ->
                        getContentResolver.openInputStream(inputFile.uri)?.use { input ->
                            writeUserDefineFileHead(
                                inputFileName = inputName,
                                inputFileSize = digest.size,
                                fileMd5 = fileMd5,
                                inputStream = input,
                                outputStream = output,
                                fileHeadVersion = 1
                            )
                        } ?: throw IOException("无法读取文件：$inputName")

                        getContentResolver.openInputStream(inputFile.uri)?.use { input ->
                            encryptBigFileWithAES256(input, output, object : OnProcessListener {
                                override fun encryptDataSize(processSize: Long) {
                                    postProgress("加密", completedSize, processSize, totalSize)
                                }
                            })
                        } ?: throw IOException("无法读取文件：$inputName")
                    } ?: throw IOException("无法写入文件：$outputName")
                } catch (error: Exception) {
                    outputFile.delete()
                    throw error
                }
                completedSize += digest.size
                logSubject.onNext("加密文件 $inputName → $outputName")
            }
            files.size
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { count ->
                    fileOperationRunning = false
                    if (count > 0) {
                        _mProcessText.value = "加密完成"
                        logSubject.onNext("加密完成，共处理 $count 个文件")
                    }
                },
                { error ->
                    fileOperationRunning = false
                    _mProcessText.value = "加密失败"
                    logSubject.onNext("加密失败：${error.message ?: "未知错误"}")
                }
            )
        disposables.add(disposable)
        return true
    }

    fun decryptData(): Boolean {
        if (!hasWorkspaceAccess()) {
            logSubject.onNext("请先选择 00SERVER 工作目录")
            return false
        }
        if (fileOperationRunning) {
            logSubject.onNext("已有文件任务正在执行，请稍候")
            return false
        }
        fileOperationRunning = true
        logSubject.onNext("开始解密操作...")
        val disposable = Observable.fromCallable {
            val directories = safWorkspace.openDirectories()
            val files = safWorkspace.listFilesRecursively(directories.encryptedFiles)
            logSubject.onNext("待解密文件个数：${files.size}")
            if (files.isEmpty()) {
                _mProcessText.postValue("没有待解密文件")
                return@fromCallable 0
            }

            val totalSize = files.fold(0L) { total, file -> total + file.length() }
            var completedSize = 0L
            files.forEach { inputFile ->
                val inputName = inputFile.name ?: "未命名文件"
                val fileInfo = getContentResolver.openInputStream(inputFile.uri)?.use { input ->
                    parseUserDefineFileHead(inputName, input)
                } ?: throw IOException("无法读取文件：$inputName")
                Log.d("seeksky", fileInfo.toString())

                val outputFile = safWorkspace.replaceFile(
                    directories.decryptedFiles,
                    fileInfo.fileNameInHead
                )
                try {
                    getContentResolver.openInputStream(inputFile.uri)?.use { input ->
                        getContentResolver.openOutputStream(outputFile.uri, "w")?.use { output ->
                            decryptBigFileWithAES256(
                                input,
                                output,
                                fileInfo.fileHeadBytesCount,
                                object : OnProcessListener {
                                    override fun decryptDataSize(processSize: Long) {
                                        postProgress("解密", completedSize, processSize, totalSize)
                                    }
                                }
                            )
                        } ?: throw IOException("无法写入文件：${fileInfo.fileNameInHead}")
                    } ?: throw IOException("无法读取文件：$inputName")

                    val decryptedMd5 = getContentResolver.openInputStream(outputFile.uri)
                        ?.use(::getFileMD5)
                        ?: throw IOException("无法校验文件：${fileInfo.fileNameInHead}")
                    if (!fileInfo.fileMd5.equals(decryptedMd5, ignoreCase = true)) {
                        throw IOException(
                            "文件 $inputName 校验失败，原始 MD5=${fileInfo.fileMd5}，" +
                                "解密 MD5=$decryptedMd5"
                        )
                    }
                } catch (error: Exception) {
                    outputFile.delete()
                    throw error
                }
                completedSize += inputFile.length()
                logSubject.onNext("解密文件 $inputName → ${fileInfo.fileNameInHead}")
            }
            files.size
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { count ->
                    fileOperationRunning = false
                    if (count > 0) {
                        _mProcessText.value = "解密完成"
                        logSubject.onNext("解密完成，共处理 $count 个文件")
                    }
                },
                { error ->
                    fileOperationRunning = false
                    _mProcessText.value = "解密失败"
                    logSubject.onNext("解密失败：${error.message ?: "未知错误"}")
                }
            )
        disposables.add(disposable)
        return true
    }

    private fun encryptedOutputName(inputName: String, md5: String): String {
        val dotIndex = inputName.lastIndexOf('.')
        val hasExtension = dotIndex > 0 && dotIndex < inputName.lastIndex
        val baseName = if (hasExtension) inputName.substring(0, dotIndex) else inputName
        return if (hasExtension) {
            "${baseName}_$md5.${inputName.substring(dotIndex + 1)}"
        } else {
            "${baseName}_$md5"
        }
    }

    private fun postProgress(
        operation: String,
        completedSize: Long,
        currentFileSize: Long,
        totalSize: Long
    ) {
        val rate = if (totalSize <= 0L) 1.0 else {
            ((completedSize + currentFileSize).toDouble() / totalSize).coerceIn(0.0, 1.0)
        }
        _mProcessText.postValue(
            String.format(Locale.getDefault(), "%s进度 %.2f%%", operation, rate * 100)
        )
    }
    fun exportDataToXls(uri: Uri) {
        if (isDatabaseBackupRunning()) return
        val disposable = Observable.fromCallable {
            val list = DatabaseProvider.withDatabase(getApplication()) { database ->
                database.getDailyDAO().getDailyDataSortByDESC()
            }
            saveXlsDataWithContentResolver(getContentResolver, uri, list)
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { success ->
                    if (success) {
                        ToastUtils.showLong("导出数据成功")
                    } else {
                        ToastUtils.showLong("导出数据失败")
                    }
                },
                { error ->
                    error.printStackTrace()
                    ToastUtils.showLong("导出数据失败：${error.message.orEmpty()}")
                }
            )
        disposables.add(disposable)
    }

    fun prepareDataImport(uri: Uri) {
        if (_importState.value == DataImportState.Reading ||
            _importState.value == DataImportState.Importing ||
            isDatabaseBackupRunning()
        ) return

        pendingImportRecords = emptyList()
        _importState.value = DataImportState.Reading
        val disposable = Observable.fromCallable {
            val records = getContentResolver.openInputStream(uri)?.use { input ->
                parseDailyBackup(input)
            } ?: throw IllegalStateException("无法打开所选文件")

            val existingRecords = DatabaseProvider.withDatabase(getApplication()) { database ->
                database.getDailyDAO().getAll()
            }
            val existingKeys = existingRecords
                .mapTo(mutableSetOf()) { it.toRecordKey() }
            var duplicateCount = 0
            records.forEach { record ->
                if (!existingKeys.add(record.toRecordKey())) duplicateCount++
            }
            ImportInspection(records, duplicateCount)
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { inspection ->
                    pendingImportRecords = inspection.records
                    _importState.value = DataImportState.Preview(
                        inspection.records.size,
                        inspection.duplicateCount
                    )
                },
                { error ->
                    pendingImportRecords = emptyList()
                    _importState.value = DataImportState.Error(
                        error.message ?: "读取备份文件失败"
                    )
                }
            )
        disposables.add(disposable)
    }

    fun importPendingData(mode: DataImportMode) {
        if (_importState.value == DataImportState.Reading ||
            _importState.value == DataImportState.Importing ||
            isDatabaseBackupRunning()
        ) return

        val sourceRecords = pendingImportRecords
        if (sourceRecords.isEmpty()) {
            _importState.value = DataImportState.Error("没有待导入的数据，请重新选择备份文件")
            return
        }

        _importState.value = DataImportState.Importing
        val disposable = Observable.fromCallable {
            var importedCount = 0
            var skippedCount = 0

            DatabaseProvider.withDatabase(getApplication()) { database ->
                database.runInTransaction {
                    val dailyDAO = database.getDailyDAO()
                    val statDAO = database.getStatDAO()

                    val recordsToInsert = when (mode) {
                        DataImportMode.REPLACE -> {
                            dailyDAO.deleteAll()
                            sourceRecords.map { it.copyForImport(preserveId = true) }
                        }
                        DataImportMode.MERGE -> {
                            val keys = dailyDAO.getAll()
                                .mapTo(mutableSetOf()) { it.toRecordKey() }
                            sourceRecords.mapNotNull { record ->
                                if (keys.add(record.toRecordKey())) {
                                    record.copyForImport(preserveId = false)
                                } else {
                                    skippedCount++
                                    null
                                }
                            }
                        }
                    }

                    if (recordsToInsert.isNotEmpty()) {
                        dailyDAO.addDailyList(recordsToInsert)
                    }
                    importedCount = recordsToInsert.size

                    val stats = createMonthStats(dailyDAO.getAll())
                    statDAO.deleteAll()
                    if (stats.isNotEmpty()) {
                        statDAO.addStatList(stats)
                    }
                }
            }

            DataImportState.Success(mode, importedCount, skippedCount)
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { result ->
                    pendingImportRecords = emptyList()
                    _importState.value = result
                },
                { error ->
                    pendingImportRecords = emptyList()
                    _importState.value = DataImportState.Error(
                        error.message ?: "写入数据库失败"
                    )
                }
            )
        disposables.add(disposable)
    }

    fun backupDatabase(uri: Uri) {
        if (isDatabaseBackupRunning() ||
            _importState.value == DataImportState.Reading ||
            _importState.value == DataImportState.Importing
        ) return

        _databaseBackupState.value = DatabaseBackupState.PreparingSnapshot
        val disposable = Observable.fromCallable {
            DatabaseBackupExporter.export(getApplication(), uri) { progress ->
                val state = when (progress) {
                    DatabaseBackupProgress.PREPARING_SNAPSHOT ->
                        DatabaseBackupState.PreparingSnapshot
                    DatabaseBackupProgress.WRITING_ARCHIVE ->
                        DatabaseBackupState.WritingArchive
                }
                _databaseBackupState.postValue(state)
            }
        }
            .subscribeOn(Schedulers.io())
            .subscribe(
                { result ->
                    _databaseBackupState.postValue(DatabaseBackupState.Success(
                        result.fileCount,
                        result.totalBytes
                    ))
                },
                { error ->
                    _databaseBackupState.postValue(DatabaseBackupState.Error(
                        error.message ?: "未知错误"
                    ))
                }
            )
        disposables.add(disposable)
    }

    fun consumeDatabaseBackupState() {
        _databaseBackupState.value = DatabaseBackupState.Idle
    }

    private fun isDatabaseBackupRunning(): Boolean {
        return _databaseBackupState.value == DatabaseBackupState.PreparingSnapshot ||
            _databaseBackupState.value == DatabaseBackupState.WritingArchive
    }

    fun consumeImportState() {
        _importState.value = DataImportState.Idle
    }

    fun cancelPendingImport() {
        pendingImportRecords = emptyList()
        _importState.value = DataImportState.Idle
    }

    /**
     * Android 11的存储要求 为了符合规范和兼容存储数据的流程如下
     * 1. 通过Intent请求Android系统的数据存储框架(Storage Access Framework),用户自主选择存储的位置
     * 2. 通过回调获取文件存储位置的虚拟Uri
     * 3. 通过Uri借助ContentResolver写入文件的具体内容
     */
    private fun saveXlsDataWithContentResolver(contentResolver: ContentResolver, uri: Uri, list: List<Daily>): Boolean {
        try {
            val outputStream = contentResolver.openOutputStream(uri) ?: return false
            outputStream.use { output ->
                val workbook = Workbook.createWorkbook(output)
                val sheet = workbook.createSheet("daily", 0)
                for (r in list.indices) {
                    val id = Label(0, r, list[r].id.toString())
                    sheet.addCell(id)
                    val title = Label(1, r, list[r].title)
                    sheet.addCell(title)
                    val year = Label(2, r, list[r].year.toString())
                    sheet.addCell(year)
                    val month = Label(3, r, list[r].month.toString())
                    sheet.addCell(month)
                    val day = Label(4, r, list[r].day.toString())
                    sheet.addCell(day)
                    val hour = Label(5, r, list[r].hour.toString())
                    sheet.addCell(hour)
                    val time = Label(6, r, list[r].time.toString())
                    sheet.addCell(time)
                }
                workbook.write()
                workbook.close()
            }
            return true
        } catch (e: Exception) {
            return false
        }
    }

    override fun onCleared() {
        super.onCleared()
        disposables.clear()
    }

    private fun Daily.copyForImport(preserveId: Boolean): Daily {
        return Daily(title, year, month, day, hour, time).also { copy ->
            if (preserveId) copy.id = id
        }
    }
}
