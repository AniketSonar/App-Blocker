package com.example.focusguard

import android.content.Context
import java.io.File
import java.util.Calendar

/**
 * Appends one line per important event to a file on the phone:
 *   filesDir/events.csv
 * Later, this file becomes the training data for the ML model.
 * It never leaves the phone.
 */
object EventLog {
    private const val FILE_NAME = "events.csv"
    private const val HEADER = "timestamp_ms,event,package,session_min,hour,day_of_week,detail\n"

    @Synchronized
    fun log(context: Context, event: String, pkg: String, sessionMin: Long = 0, detail: String = "") {
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) file.appendText(HEADER)
            val cal = Calendar.getInstance()
            file.appendText(
                "${System.currentTimeMillis()},$event,$pkg,$sessionMin," +
                    "${cal.get(Calendar.HOUR_OF_DAY)},${cal.get(Calendar.DAY_OF_WEEK)}," +
                    "${detail.replace(",", " ")}\n"
            )
        } catch (e: Exception) {
            // Logging must never crash the app.
        }
    }
}
