package me.zhanghai.android.files.video

import android.os.Environment
import android.util.Log
import me.zhanghai.android.files.app.application
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object PlayerDiagnostics {
    private const val TAG = "PlayerDiagnostics"
    private val executor = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val logFile: File?
        get() {
            return try {
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val skyFilesDir = File(downloadDir, "SkyFiles")
                if (!skyFilesDir.exists()) {
                    skyFilesDir.mkdirs()
                }
                File(skyFilesDir, "player-debug.log")
            } catch (e: Exception) {
                Log.e(TAG, "Error getting public log file", e)
                try {
                    val fallbackDir = File(application.getExternalFilesDir(null), "SkyFiles")
                    if (!fallbackDir.exists()) {
                        fallbackDir.mkdirs()
                    }
                    File(fallbackDir, "player-debug.log")
                } catch (ex: Exception) {
                    null
                }
            }
        }

    fun log(message: String) {
        val timestamp = dateFormat.format(Date())
        val logLine = "$timestamp [ExoPlayer] $message\n"
        
        // Output to Logcat as well
        Log.d(TAG, message)

        executor.execute {
            val file = logFile ?: return@execute
            try {
                FileWriter(file, true).use { writer ->
                    writer.write(logLine)
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error writing to player-debug.log", e)
            }
        }
    }
}
