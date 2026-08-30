package com.seeksky.thebook

import android.util.Log
import org.junit.Test

import org.junit.Assert.*
import java.text.SimpleDateFormat

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
//        assertEquals(4, 2 + 2)
        val sdf = SimpleDateFormat("yyyy_MM_dd")
        val date_begin = sdf.parse("2016_12_05")
        val date_end = sdf.parse("2022_06_22")
        val gap = (date_end.time - date_begin.time) / (3600 * 24)
        print(String.format("%s\r\n%d\r\n", "ExampleUnitTest", gap))
//        Log.d("ExampleUnitTest", \rString.format("addition_isCorrect: %d", gap))
    }
}