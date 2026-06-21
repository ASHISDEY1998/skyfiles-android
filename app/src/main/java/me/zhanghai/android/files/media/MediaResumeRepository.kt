package me.zhanghai.android.files.media

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import me.zhanghai.android.files.app.application
import java.io.File
import java.io.IOException
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.attribute.BasicFileAttributes
import java8.nio.file.Files

data class ResumeEntry(
    val path: String,
    val position: Long,
    val duration: Long,
    val timestamp: Long,
    val fileSize: Long,
    val lastModified: Long
)

class MediaResumeRepository private constructor(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS resume_entries (
                path TEXT PRIMARY KEY,
                position INTEGER,
                duration INTEGER,
                timestamp INTEGER,
                file_size INTEGER,
                last_modified INTEGER
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS resume_entries")
        onCreate(db)
    }

    fun getResumeEntry(pathString: String): ResumeEntry? {
        val db = try {
            readableDatabase
        } catch (e: Exception) {
            Log.e(TAG, "Error opening database for reading", e)
            return null
        }
        return try {
            db.query(
                "resume_entries",
                null,
                "path = ?",
                arrayOf(pathString),
                null,
                null,
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    ResumeEntry(
                        path = cursor.getString(cursor.getColumnIndexOrThrow("path")),
                        position = cursor.getLong(cursor.getColumnIndexOrThrow("position")),
                        duration = cursor.getLong(cursor.getColumnIndexOrThrow("duration")),
                        timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
                        fileSize = cursor.getLong(cursor.getColumnIndexOrThrow("file_size")),
                        lastModified = cursor.getLong(cursor.getColumnIndexOrThrow("last_modified"))
                    )
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying resume entry for $pathString", e)
            null
        }
    }

    fun saveResumeEntry(entry: ResumeEntry) {
        val db = try {
            writableDatabase
        } catch (e: Exception) {
            Log.e(TAG, "Error opening database for writing", e)
            return
        }
        try {
            val values = ContentValues().apply {
                put("path", entry.path)
                put("position", entry.position)
                put("duration", entry.duration)
                put("timestamp", entry.timestamp)
                put("file_size", entry.fileSize)
                put("last_modified", entry.lastModified)
            }
            db.insertWithOnConflict(
                "resume_entries",
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error saving resume entry for ${entry.path}", e)
        }
    }

    fun deleteResumeEntry(pathString: String) {
        val db = try {
            writableDatabase
        } catch (e: Exception) {
            Log.e(TAG, "Error opening database for deletion", e)
            return
        }
        try {
            db.delete("resume_entries", "path = ?", arrayOf(pathString))
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting resume entry for $pathString", e)
        }
    }

    fun getActiveResumeEntries(): List<ResumeEntry> {
        val db = try {
            readableDatabase
        } catch (e: Exception) {
            Log.e(TAG, "Error opening database for reading", e)
            return emptyList()
        }
        val list = mutableListOf<ResumeEntry>()
        try {
            db.query(
                "resume_entries",
                null,
                null,
                null,
                null,
                null,
                "timestamp DESC"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val pos = cursor.getLong(cursor.getColumnIndexOrThrow("position"))
                    val dur = cursor.getLong(cursor.getColumnIndexOrThrow("duration"))
                    // Only show partially watched videos: watched > 60s and progress < 95%
                    if (pos >= 60000 && dur > 0 && (pos * 100 / dur) < 95) {
                        list.add(
                            ResumeEntry(
                                path = cursor.getString(cursor.getColumnIndexOrThrow("path")),
                                position = pos,
                                duration = dur,
                                timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
                                fileSize = cursor.getLong(cursor.getColumnIndexOrThrow("file_size")),
                                lastModified = cursor.getLong(cursor.getColumnIndexOrThrow("last_modified"))
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying active resume entries", e)
        }
        return list
    }

    companion object {
        private const val DATABASE_NAME = "video_resume.db"
        private const val DATABASE_VERSION = 1
        private const val TAG = "MediaResumeRepository"

        val instance by lazy { MediaResumeRepository(application) }
    }
}
