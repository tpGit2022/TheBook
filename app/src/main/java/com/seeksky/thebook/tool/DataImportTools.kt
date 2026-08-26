package com.seeksky.thebook.tool

import com.seeksky.thebook.database.entry.Daily
import com.seeksky.thebook.database.entry.Stat
import jxl.Workbook
import java.io.InputStream
import java.util.GregorianCalendar
import java.util.Locale

class DailyBackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class DailyRecordKey(
    val title: String,
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val time: Long
)

fun Daily.toRecordKey() = DailyRecordKey(title, year, month, day, hour, time)

/**
 * Reads backups produced by the Android JXL exporter. Historical backups may
 * use an .xlsx filename, but their actual content is the legacy XLS format.
 */
fun parseDailyBackup(input: InputStream): List<Daily> {
    var workbook: Workbook? = null
    try {
        workbook = Workbook.getWorkbook(input)
        val sheets = workbook.sheets
        if (sheets.isEmpty()) {
            throw DailyBackupException("备份文件中没有工作表")
        }

        val sheet = sheets.firstOrNull { it.name.equals("daily", ignoreCase = true) } ?: sheets[0]
        val records = mutableListOf<Daily>()
        val ids = mutableSetOf<Int>()
        var firstContentRow = true

        for (rowIndex in 0 until sheet.rows) {
            val values = (0..6).map { column -> sheet.getCell(column, rowIndex).contents }
            if (values.all { it.isBlank() }) continue

            if (firstContentRow && values[0].trim().equals("id", ignoreCase = true)) {
                firstContentRow = false
                continue
            }
            firstContentRow = false

            val rowNumber = rowIndex + 1
            val id = values[0].asInt("id", rowNumber)
            val title = values[1]
            val year = values[2].asInt("year", rowNumber)
            val month = values[3].asInt("month", rowNumber)
            val day = values[4].asInt("day", rowNumber)
            val hour = values[5].asInt("hour", rowNumber)
            val time = values[6].asLong("time", rowNumber)

            if (id <= 0) throw DailyBackupException("第${rowNumber}行：id 必须大于 0")
            if (!ids.add(id)) throw DailyBackupException("第${rowNumber}行：id $id 重复")
            if (title.isBlank()) throw DailyBackupException("第${rowNumber}行：title 不能为空")
            if (year !in 1..9999) throw DailyBackupException("第${rowNumber}行：year 超出范围")
            if (month !in 1..12) throw DailyBackupException("第${rowNumber}行：month 超出范围")
            if (hour !in 0..23) throw DailyBackupException("第${rowNumber}行：hour 超出范围")
            if (time <= 0L) throw DailyBackupException("第${rowNumber}行：time 必须大于 0")
            validateDate(year, month, day, hour, rowNumber)

            records += Daily(title, year, month, day, hour, time).also { it.id = id }
        }

        if (records.isEmpty()) {
            throw DailyBackupException("备份文件中没有可导入的数据")
        }
        return records
    } catch (e: DailyBackupException) {
        throw e
    } catch (e: Exception) {
        throw DailyBackupException("无法读取备份文件，请选择由 TheBook 导出的 XLS 文件", e)
    } finally {
        workbook?.close()
    }
}

fun createMonthStats(records: List<Daily>): List<Stat> {
    return records
        .groupingBy { Pair(it.year, it.month) }
        .eachCount()
        .toSortedMap(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
        .map { (date, count) ->
            Stat(
                year = date.first,
                month = date.second,
                times = count,
                tag = String.format(Locale.US, "%04d%02d", date.first, date.second)
            )
        }
}

private fun String.asInt(field: String, rowNumber: Int): Int {
    return trim().toIntOrNull()
        ?: throw DailyBackupException("第${rowNumber}行：$field 不是有效整数")
}

private fun String.asLong(field: String, rowNumber: Int): Long {
    return trim().toLongOrNull()
        ?: throw DailyBackupException("第${rowNumber}行：$field 不是有效整数")
}

private fun validateDate(year: Int, month: Int, day: Int, hour: Int, rowNumber: Int) {
    try {
        GregorianCalendar().apply {
            isLenient = false
            clear()
            set(year, month - 1, day, hour, 0, 0)
            timeInMillis
        }
    } catch (e: IllegalArgumentException) {
        throw DailyBackupException("第${rowNumber}行：日期或小时无效", e)
    }
}
