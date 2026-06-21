package me.zhanghai.android.files.util

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

object SkyFilesLogger {
    private const val TAG = "SkyFiles"
    private val executor = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private val logFile: File?
        get() {
            return try {
                // Try writing to public Download folder first if manager permission exists or API <= 29
                val hasPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
                if (hasPermission) {
                    val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val skyFilesDir = File(downloadDir, "SkyFiles")
                    if (!skyFilesDir.exists()) {
                        val created = skyFilesDir.mkdirs()
                        Log.d(TAG, "skyfiles public folder created: $created")
                    }
                    File(skyFilesDir, "skyfiles-debug.log")
                } else {
                    // Fallback to app-private storage where no permission is required
                    val fallbackDir = File(application.getExternalFilesDir(null), "SkyFiles")
                    if (!fallbackDir.exists()) {
                        fallbackDir.mkdirs()
                    }
                    File(fallbackDir, "skyfiles-debug.log")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get public log file, falling back to private files dir", e)
                try {
                    val fallbackDir = File(application.getExternalFilesDir(null), "SkyFiles")
                    if (!fallbackDir.exists()) {
                        fallbackDir.mkdirs()
                    }
                    File(fallbackDir, "skyfiles-debug.log")
                } catch (ex: Exception) {
                    Log.e(TAG, "All log file paths failed", ex)
                    null
                }
            }
        }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        writeLog("DEBUG", tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        writeLog("INFO", tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        writeLog("WARN", tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        val fullMessage = if (throwable != null) {
            "$message\n${Log.getStackTraceString(throwable)}"
        } else {
            message
        }
        writeLog("ERROR", tag, fullMessage)
    }

    private fun writeLog(level: String, tag: String, message: String) {
        if (tag == "SkyFiles" && level != "ERROR") {
            return
        }
        val threadName = Thread.currentThread().name
        val timestamp = dateFormat.format(Date())
        val logLine = "$timestamp [$threadName] $tag -> $message\n"

        // Mirror all SkyFilesLogger writes to Logcat
        Log.d("SkyFilesLogger", "$level: $tag -> $message")

        executor.execute {
            val file = logFile ?: return@execute
            Log.d("SkyFilesLogger", "Writing log to absolute path: ${file.absolutePath}")
            try {
                // Create Download/SkyFiles if missing
                val parent = file.parentFile
                if (parent != null && !parent.exists()) {
                    val created = parent.mkdirs()
                    Log.d("SkyFilesLogger", "Download/SkyFiles created: $created")
                }

                FileWriter(file, true).use { writer ->
                    writer.write(logLine)
                }
                // Notify the OS media scanner so the file is immediately visible in file managers / MTP
                MediaScannerConnection.scanFile(
                    application,
                    arrayOf(file.absolutePath),
                    null
                ) { path, uri ->
                    Log.v(TAG, "Scanned $path: uri=$uri")
                }
            } catch (e: java.io.IOException) {
                Log.e("SkyFilesLogger", "IOException while writing to ${file.absolutePath}", e)
            } catch (e: Exception) {
                Log.e("SkyFilesLogger", "Exception while writing to ${file.absolutePath}", e)
            }
        }
    }
}
