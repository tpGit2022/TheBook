package com.seeksky.thebook.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.blankj.utilcode.util.SDCardUtils
import com.blankj.utilcode.util.ToastUtils
import com.seeksky.thebook.App
import com.seeksky.thebook.database.AppDatabase
import com.seeksky.thebook.database.entry.Daily
import com.seeksky.thebook.tool.applySchedulers
import com.seeksky.thebook.tool.createMonthStats
import com.seeksky.thebook.tool.parseDailyBackup
import com.seeksky.thebook.tool.toRecordKey
import com.seeksky.toolbox.tool.OnProcessListener
import com.seeksky.toolbox.tool.decryptBigFileWithAES256
import com.seeksky.toolbox.tool.encryptBigFileWithAES256
import com.seeksky.toolbox.tool.getAesEncryptKey
import com.seeksky.toolbox.tool.getFileMD5
import com.seeksky.toolbox.tool.getPathDFSHelper
import com.seeksky.toolbox.tool.initialVector
import com.seeksky.toolbox.tool.parseUserDefineFileHead
import com.seeksky.toolbox.tool.writeUserDefineFileHead
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Observer
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import jxl.Workbook
import jxl.write.Label
import java.io.File
import java.lang.Exception
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

private data class ImportInspection(
    val records: List<Daily>,
    val duplicateCount: Int
)

class MineViewModel(application: Application) : AndroidViewModel(application) {
    private val getContentResolver by lazy {
        getApplication<App>().contentResolver
    }

    private val _exportText = MutableLiveData<String>().apply {
        value = "数据导出"
    }
    val exportText: LiveData<String> = _exportText

    private val _importState = MutableLiveData<DataImportState>(DataImportState.Idle)
    val importState: LiveData<DataImportState> = _importState
    private var pendingImportRecords: List<Daily> = emptyList()

    private val _mTextDirInfo: MutableLiveData<String> = MutableLiveData()
    val dirText: LiveData<String> get() = _mTextDirInfo

    private val _mProcessText: MutableLiveData<String> = MutableLiveData()
    val processText: LiveData<String> get() = _mProcessText

    private val _mTextLog: MutableLiveData<String> = MutableLiveData()
    val textLog: LiveData<String> get() = _mTextLog

    var mTextInputKey: MutableLiveData<String> = MutableLiveData()

    private val logSubject = BehaviorSubject.createDefault("")
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private val sdcardPath: String = SDCardUtils.getSDCardPathByEnvironment()
    private val targetDirPath = sdcardPath + File.separator + "00SERVER"
    private val originMediaBaseDir = File(targetDirPath, "/origin_media_data")
    private val encryptBaseDir = File(targetDirPath, "/f_input/encrypt_data")
    private val decryptBaseDir = File(targetDirPath, "/f_output/decrypt_data")

    private val disposables = CompositeDisposable() // 管理所有订阅
    init {
        var tip = String.format("原始数据目录:\n%s\n加密数据目录:\n%s\n解密数据目录:\n%s", originMediaBaseDir, encryptBaseDir, decryptBaseDir)
        tip = tip.plus(String.format("\n\nAES Key:%s", getAesEncryptKey().joinToString("") { "%02x".format(it) }))
        tip = tip.plus(String.format("\n\nAES IV :%s", initialVector.joinToString("") { "%02x".format(it) }))
        _mTextDirInfo.value = tip
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

    @SuppressLint("CheckResult")
    fun encryptData(): Boolean {
        logSubject.onNext("开始加密操作...")
        if (!originMediaBaseDir.exists()) originMediaBaseDir.mkdirs()
        if (!encryptBaseDir.exists()) encryptBaseDir.mkdirs()
        if (!decryptBaseDir.exists()) decryptBaseDir.mkdirs()
        val originMediaFileList = mutableListOf<String>()
        getPathDFSHelper(originMediaFileList, originMediaBaseDir.absolutePath, 0)
        logSubject.onNext("待处理文件个数${originMediaFileList.size}")
        var processFileSize = 0L
        val progressSubject = BehaviorSubject.createDefault(0.0)
        progressSubject.subscribe{ processRate ->
            _mProcessText.postValue(String.format("加密进度%.2f",  processRate * 100))
        }
        Observable.fromIterable(originMediaFileList).subscribeOn(Schedulers.io())
            .flatMap { filePath -> Observable.fromCallable { File(filePath).length() } }
            .reduce { totalSize, fileSize -> totalSize + fileSize }
            .flatMapCompletable { totalSize ->
                Observable.fromIterable(originMediaFileList)
                    .flatMapCompletable { filePath ->
                        Completable.fromCallable {
                            val fileName = filePath.substring(filePath.lastIndexOf(File.separator) + 1)
                            val newFileName = String.format("%s_%s.%s", fileName.substring(0, fileName.lastIndexOf('.')), getFileMD5(filePath), File(filePath).extension)
                            val outputFilePath = File(encryptBaseDir, newFileName).absolutePath
                            val fileInfo = writeUserDefineFileHead(filePath, outputFilePath, file_head_version = 1)
                            logSubject.onNext("加密文件${fileName}-->${newFileName}")
                            Log.d("seeksky", fileInfo.toString())
                            val result = encryptBigFileWithAES256(filePath, outputFilePath, callback = object :
                                OnProcessListener {
                                override fun encryptDataSize(processSize: Long) {
                                    val processRate = ((processFileSize + processSize) * 1.0 / totalSize)
                                    progressSubject.onNext(processRate)
                                }
                            })
                            processFileSize += File(filePath).length()
                            @Suppress("UNUSED_EXPRESSION")
                            result
                        }.subscribeOn(Schedulers.io())
                    }
            }.observeOn(AndroidSchedulers.mainThread()).subscribe(
                {
                    // 加密完成
                    progressSubject.onComplete()
                },
                {
                    // 处理错误
                }
            )
        return false
    }

    fun decryptData(): Boolean {
        logSubject.onNext("开始解密操作...")
        if (!encryptBaseDir.exists()) encryptBaseDir.mkdirs()
        if (!decryptBaseDir.exists()) decryptBaseDir.mkdirs()
        val encryptFileList = mutableListOf<String>()
        getPathDFSHelper(encryptFileList, encryptBaseDir.absolutePath, 0)
        val decryptFileList = mutableListOf<String>()
        getPathDFSHelper(decryptFileList, decryptBaseDir.absolutePath, 0)
        var processFileSize = 0L
        val progressSubject = BehaviorSubject.createDefault(0.0)
        disposables.add(progressSubject.subscribe{ processRate ->
            _mProcessText.postValue(String.format("解密文件进度%.2f",  processRate * 100))
        })

        disposables.add(Observable.fromIterable(encryptFileList).subscribeOn(Schedulers.io())
            .flatMap { filePath -> Observable.fromCallable { File(filePath).length() } }
            .reduce { totalSize, fileSize -> totalSize + fileSize }
            .flatMapCompletable { totalSize ->
                Observable.fromIterable(encryptFileList)
                    .flatMapCompletable { f ->
                        Completable.fromCallable {
                            val fileInfo = parseUserDefineFileHead(f)
                            Log.d("seeksky", fileInfo.toString())
                            val fileName = f.substring(f.lastIndexOf(File.separator) + 1)
                            logSubject.onNext("解密文件${fileName}-->${fileInfo.fileNameInHead}")
                            val outputFilePath = File(decryptBaseDir, fileInfo.fileNameInHead).absolutePath
                            val result = decryptBigFileWithAES256(f, outputFilePath, fileInfo.fileHeadBytesCount, callback = object : OnProcessListener {
                                override fun decryptDataSize(processSize: Long) {
                                    val processRate = ((processFileSize + processSize) * 1.0 / totalSize)
                                    progressSubject.onNext(processRate)
                                }
                            })
                            val fileMD5 = getFileMD5(outputFilePath)
                            if (fileInfo.fileMd5 != fileMD5) {
                                val tip = String.format("\n解密后文件%s的MD5与源文件不符\n原始文件MD5:%s\n解密文件MD5:%s", fileName, fileInfo.fileMd5, fileMD5)
                                _mTextLog.postValue(_mProcessText.value.plus(tip))
                            }
                            processFileSize += File(f).length()
                            @Suppress("UNUSED_EXPRESSION")
                            result
                        }.subscribeOn(Schedulers.io())
                    }
            }.observeOn(AndroidSchedulers.mainThread()).subscribe(
                {
                    // 加密完成
                },
                {
                    // 处理错误
                }
            ))
        return false
    }
    fun exportDataToXls(uri: Uri) {
        Observable.create<List<Daily>>{
            val dao = AppDatabase.getInstance(getApplication()).getDailyDAO()
            val list = dao.getDailyDataSortByDESC()
            it.onNext(list)
            it.onComplete()
        }.compose(applySchedulers()).subscribe(object:
            Observer<List<Daily>> {
            override fun onComplete() {
            }

            override fun onSubscribe(d: Disposable) {
                disposables.add(d)
            }

            override fun onNext(t: List<Daily>) {
                if (saveXlsDataWithContentResolver(getContentResolver, uri, t)) {
                    ToastUtils.showLong("导出数据成功")
                } else {
                    ToastUtils.showLong("导出数据失败")
                }
            }

            override fun onError(e: Throwable) {
                e.printStackTrace()
                ToastUtils.showLong("导出数据失败：${e.message.orEmpty()}")
            }
        })
    }

    fun prepareDataImport(uri: Uri) {
        if (_importState.value == DataImportState.Reading ||
            _importState.value == DataImportState.Importing
        ) return

        pendingImportRecords = emptyList()
        _importState.value = DataImportState.Reading
        val disposable = Observable.fromCallable {
            val records = getContentResolver.openInputStream(uri)?.use { input ->
                parseDailyBackup(input)
            } ?: throw IllegalStateException("无法打开所选文件")

            val existingKeys = AppDatabase.getInstance(getApplication())
                .getDailyDAO()
                .getAll()
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
            _importState.value == DataImportState.Importing
        ) return

        val sourceRecords = pendingImportRecords
        if (sourceRecords.isEmpty()) {
            _importState.value = DataImportState.Error("没有待导入的数据，请重新选择备份文件")
            return
        }

        _importState.value = DataImportState.Importing
        val disposable = Observable.fromCallable {
            val database = AppDatabase.getInstance(getApplication())
            var importedCount = 0
            var skippedCount = 0

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
            val outputStream = contentResolver.openOutputStream(uri)
            val workbook = Workbook.createWorkbook(outputStream)
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
            outputStream?.close()
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
