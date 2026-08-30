package com.seeksky.thebook.tool

import com.seeksky.thebook.database.entry.Daily
import jxl.Workbook
import jxl.write.Label
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class DataImportToolsTest {

    @Test
    fun parseDailyBackup_readsHistoricalExportFormat() {
        val bytes = createWorkbook(
            listOf(
                listOf("8", "zero", "2026", "8", "25", "20", "1787660000000"),
                listOf("9", "sleep", "2026", "8", "26", "1", "1787670000000")
            )
        )

        val records = ByteArrayInputStream(bytes).use(::parseDailyBackup)

        assertEquals(2, records.size)
        assertEquals(8, records[0].id)
        assertEquals("zero", records[0].title)
        assertEquals(1787670000000L, records[1].time)
    }

    @Test
    fun parseDailyBackup_rejectsInvalidRows() {
        val bytes = createWorkbook(
            listOf(
                listOf("1", "zero", "2026", "2", "30", "8", "1787660000000")
            )
        )

        assertThrows(DailyBackupException::class.java) {
            ByteArrayInputStream(bytes).use(::parseDailyBackup)
        }
    }

    @Test
    fun createMonthStats_groupsAllDailyRecords() {
        val records = listOf(
            Daily("zero", 2026, 7, 1, 1, 1L),
            Daily("sleep", 2026, 8, 1, 1, 2L),
            Daily("zero", 2026, 8, 2, 1, 3L)
        )

        val stats = createMonthStats(records)

        assertEquals(listOf("202607", "202608"), stats.map { it.tag })
        assertEquals(listOf(1, 2), stats.map { it.times })
    }

    private fun createWorkbook(rows: List<List<String>>): ByteArray {
        val output = ByteArrayOutputStream()
        val workbook = Workbook.createWorkbook(output)
        val sheet = workbook.createSheet("daily", 0)
        rows.forEachIndexed { rowIndex, values ->
            values.forEachIndexed { columnIndex, value ->
                sheet.addCell(Label(columnIndex, rowIndex, value))
            }
        }
        workbook.write()
        workbook.close()
        return output.toByteArray()
    }
}
