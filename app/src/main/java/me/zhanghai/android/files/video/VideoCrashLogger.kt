package me.zhanghai.android.files.video

import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.util.Log
import me.zhanghai.android.files.app.application
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object VideoCrashLogger {
    private const val TAG = "VideoCrashLogger"
    private val executor = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val logFile: File?
        get() {
            return try {
                val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
                if (hasPermission) {
                    val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val skyFilesDir = File(downloadDir, "SkyFiles")
                    if (!skyFilesDir.exists()) {
                        skyFilesDir.mkdirs()
                    }
                    File(skyFilesDir, "player-debug.log")
                } else {
                    val fallbackDir = File(application.getExternalFilesDir(null), "SkyFiles")
                    if (!fallbackDir.exists()) {
                        fallbackDir.mkdirs()
                    }
                    File(fallbackDir, "player-debug.log")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get log file path", e)
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
        Log.d(TAG, message)
        writeLog(message)
    }

    fun logError(tag: String, message: String, throwable: Throwable?) {
        val fullMessage = if (throwable != null) {
            "$message\n${Log.getStackTraceString(throwable)}"
        } else {
            message
        }
        Log.e(tag, fullMessage)
        writeLog("$tag -> $fullMessage")
    }

    private fun writeLog(message: String) {
        val timestamp = dateFormat.format(Date())
        val logLine = "$timestamp: $message\n"
        executor.execute {
            val file = logFile ?: return@execute
            try {
                val parent = file.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                }
                FileWriter(file, true).use { writer ->
                    writer.write(logLine)
                }
                MediaScannerConnection.scanFile(
                    application,
                    arrayOf(file.absolutePath),
                    null,
                    null
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error writing to player-debug.log", e)
            }
        }
    }
}
