package com.seeksky.thebook.tool

import android.content.Context
import com.blankj.utilcode.util.SPUtils
import com.blankj.utilcode.util.ToastUtils
import com.seeksky.thebook.Constants
import com.seeksky.thebook.database.DatabaseProvider
import com.seeksky.thebook.database.entry.Daily
import com.seeksky.thebook.database.entry.Stat
import io.reactivex.Observable
import io.reactivex.Observer
import io.reactivex.disposables.Disposable
import jxl.Workbook
import jxl.write.Label
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.TimeUnit

fun startMigrateData(context: Context, input: InputStream, writeDB: Boolean, needUnique: Boolean) {
    Observable.create<MutableList<Daily>> { e ->
        val sortList = loadXlsData(input)
        e.onNext(sortList)
        e.onComplete()
    }.delay(200, TimeUnit.MILLISECONDS).map { if (needUnique) makeDataUnique(it) else it }
        .map { dailyList ->
            val stats = covertData(dailyList)
            if (writeDB) writeMigratedData(context, dailyList, stats)
            stats
        }.compose(
        applySchedulers()
    )
        .subscribe(object : Observer<MutableList<Stat>> {
            override fun onSubscribe(d: Disposable) {}
            override fun onNext(t: MutableList<Stat>) {
                if (writeDB) {
                    SPUtils.getInstance(Constants.XML_FILE_NAME)
                        .put(Constants.KEY_DATA_MIGRATE, true)
                }
            }

            override fun onError(e: Throwable) {
                e.printStackTrace()
            }

            override fun onComplete() {
                println("onComplete")
            }
        })
}

fun startMigrateData(context: Context, input: InputStream) {
    startMigrateData(context, input, writeDB = true, needUnique = false)
}

// ensure time is unique
fun makeDataUnique(list: MutableList<Daily>): MutableList<Daily> {
    val unique = ArrayList<Daily>(list.size)
    unique.addAll(list)

    val map = HashMap<Long, Int>()
    for (i in unique) {
        map[i.time] = map[i.time]?.plus(1)?:1
    }

    for (i in 0 until unique.size - 1) {
        map[unique[i].time]?.let {
            if (it > 1) {
                unique[i].time = unique[i].time + i
            }
        }
    }
    return unique
}

fun loadXlsData(path: String): MutableList<Daily> {
    val file = File(path)
    if (!file.exists()) throw FileNotFoundException("file not found, ensure $path is valid")
    val input = FileInputStream(file)
    return loadXlsData(input)
}

fun loadXlsData(input: InputStream): MutableList<Daily> {
   val list: MutableList<Daily> = ArrayList()
    try {
        val book = Workbook.getWorkbook(input)
        for (sheet in book.sheets) {
            val rows = sheet.rows
            for (i in 0 until rows) {
                val bean = Daily(
                    title = sheet.getCell(1, i).contents,
                    year = sheet.getCell(2, i).contents.toInt(),
                    month = sheet.getCell(3, i).contents.toInt(),
                    day = sheet.getCell(4, i).contents.toInt(),
                    hour = sheet.getCell(5, i).contents.toInt(),
                    time = sheet.getCell(6, i).contents.toLong()
                )
                list.add(bean)
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    } finally {
        try {
            input.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    return list.let {
        it.sortWith(Comparator { o1: Daily, o2: Daily -> (o1.time.compareTo(o2.time)) })
        it
    }
}

fun covertData(dailyList: MutableList<Daily>): MutableList<Stat> {
    val stats = ArrayList<Stat>()
    val map = HashMap<String, Int>()
    for (i in dailyList) {
        val key = String.format("%04d%02d", i.year, i.month)
        map[key] = map[key]?.plus(1) ?: 1
    }
    for (entry in map.entries) {
        val stat = Stat(
            year = entry.key.substring(0, 4).toInt(),
            month = entry.key.substring(4, entry.key.length).toInt(),
            times = entry.value,
            tag = entry.key
        )
        stats.add(stat)
    }

    return stats.let {
        it.sortWith(Comparator { o1: Stat, o2: Stat ->
            if (o1.year.compareTo(o2.year) == 0) o1.month.compareTo(o2.month)
            else o1.year.compareTo(o2.year)
        })
        it
    }
}

private fun writeMigratedData(
    context: Context,
    dailyList: MutableList<Daily>,
    stats: MutableList<Stat>
) {
    DatabaseProvider.withDatabase(context.applicationContext) { database ->
        database.runInTransaction {
            val dailyDao = database.getDailyDAO()
            val statDao = database.getStatDAO()
            dailyList.forEach(dailyDao::addDaily)
            stats.forEach(statDao::addStat)
        }
    }
}

fun exportXls(context: Context, path: String, fileName: String) {
    Observable.create<List<Daily>>{
        val list = DatabaseProvider.withDatabase(context) { database ->
            database.getDailyDAO().getDailyDataSortByDESC()
        }
        it.onNext(list)
        it.onComplete()
    }.map { writeXls(path, fileName, it) }.compose(applySchedulers()).subscribe(object: Observer<List<Daily>>{
        override fun onComplete() {
            ToastUtils.showLong("数据导出成功!")
        }

        override fun onSubscribe(d: Disposable) {

        }

        override fun onNext(t: List<Daily>) {

        }

        override fun onError(e: Throwable) {
            e.printStackTrace()
        }
    })
}

fun writeXls(path: String, fileName: String, list: List<Daily>): List<Daily> {
    try {
        val file = File(path, fileName)
        System.out.println(String.format("seeksky file_abs:%s", file.absolutePath))
//        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
//          Android 10 创建文件夹即便是公共目录 也需要通过 SAF或者MediaStore
//        }
        if (!file.parentFile!!.exists()) file.parentFile!!.mkdirs()
        if (!file.exists())
            file.createNewFile()
        else {
            file.delete()
            file.createNewFile()
        }

        val workbook = Workbook.createWorkbook(file)
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
    } catch (e: java.lang.Exception) {
        e.printStackTrace()
    }
    return list
}
