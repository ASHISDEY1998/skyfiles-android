package me.zhanghai.android.files.app

import android.app.Application
import me.zhanghai.android.files.util.SkyFilesLogger

class SkyFilesApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        SkyFilesLogger.i("SkyFilesApplication", "APPLICATION STARTED")
        
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Check if crash originates from video player packages or classes
            if (shouldLogToVideoCrash(throwable)) {
                val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
                val exceptionType = throwable.javaClass.name
                val message = throwable.message.orEmpty()
                val cause = throwable.cause?.let { "${it.javaClass.name}: ${it.message}" }.orEmpty()
                val stacktrace = android.util.Log.getStackTraceString(throwable)
                
                val report = """
                    ===== VIDEO CRASH =====
                    Timestamp: $timestamp
                    Exception: $exceptionType
                    Message: $message
                    Cause: $cause
                    Stacktrace:
                    $stacktrace
                """.trimIndent()
                
                // Write report directly using VideoCrashLogger
                me.zhanghai.android.files.video.VideoCrashLogger.log(report)
            }
            
            SkyFilesLogger.e("FATAL CRASH", "Uncaught exception in thread ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
        
        SkyFilesLogger.i("SkyFilesApplication", "Global crash handler installed successfully.")
    }

    private fun shouldLogToVideoCrash(throwable: Throwable): Boolean {
        var curr: Throwable? = throwable
        while (curr != null) {
            val typeName = curr.javaClass.name
            if (typeName.contains("VideoPlayerActivity") ||
                typeName.contains("VideoPlayerViewModel") ||
                typeName.contains("ExoPlayer") ||
                typeName.contains("Media3") ||
                typeName.contains("DataSource")) {
                return true
            }
            val message = curr.message
            if (message != null && (
                message.contains("VideoPlayerActivity") ||
                message.contains("VideoPlayerViewModel") ||
                message.contains("ExoPlayer") ||
                message.contains("Media3") ||
                message.contains("DataSource"))) {
                return true
            }
            for (element in curr.stackTrace) {
                val elementString = element.toString()
                if (elementString.contains("VideoPlayerActivity") ||
                    elementString.contains("VideoPlayerViewModel") ||
                    elementString.contains("ExoPlayer") ||
                    elementString.contains("Media3") ||
                    elementString.contains("DataSource")) {
                    return true
                }
            }
            curr = curr.cause
        }
        return false
    }
}
