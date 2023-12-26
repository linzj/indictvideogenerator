package com.example.indcitvideo

import java.text.ParseException
import java.text.SimpleDateFormat

class TimeFormatValidator {
    private val timeFormat = SimpleDateFormat("HH:mm:ss").apply {
        isLenient = false
    }

    fun testTimeFormat(timeString: String?): Boolean {
        if (timeString == null) {
            return true
        }

        return try {
            timeFormat.parse(timeString)
            true
        } catch (e: ParseException) {
            false
        }
    }
}
