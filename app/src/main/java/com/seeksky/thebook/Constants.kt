package com.seeksky.thebook

import java.text.SimpleDateFormat
import java.util.*

object Constants {
    const val APP_DATABASE_NAME = "daily.db"
    const val XML_FILE_NAME = "config"
    const val KEY_DATA_MIGRATE = "hasMigration"
}

fun main() {
    val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ssSSS", Locale.getDefault())
    println(sdf.format(Date(System.currentTimeMillis())))
}
