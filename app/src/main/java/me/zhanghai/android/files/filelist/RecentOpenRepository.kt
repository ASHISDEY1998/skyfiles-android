/*
 * Copyright (c) 2026 SkyFiles
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.ContentValues
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat

object RecentOpenRepository {
    private val dbHelper = RecentOpenDatabase.instance

    suspend fun addRecentFile(path: String, displayName: String) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            // Check if exists
            val cursor = db.query(
                "recents",
                arrayOf("id", "open_count"),
                "path = ?",
                arrayOf(path),
                null, null, null
            )
            val exists = cursor.moveToFirst()
            if (exists) {
                val id = cursor.getLong(0)
                val count = cursor.getInt(1)
                cursor.close()

                val values = ContentValues().apply {
                    put("last_opened_timestamp", System.currentTimeMillis())
                    put("open_count", count + 1)
                }
                db.update("recents", values, "id = ?", arrayOf(id.toString()))
            } else {
                cursor.close()
                val values = ContentValues().apply {
                    put("path", path)
                    put("display_name", displayName)
                    put("last_opened_timestamp", System.currentTimeMillis())
                    put("open_count", 1)
                }
                db.insert("recents", null, values)
            }

            // Enforce max 100 entries limit
            db.execSQL("""
                DELETE FROM recents 
                WHERE id NOT IN (
                    SELECT id FROM recents 
                    ORDER BY last_opened_timestamp DESC 
                    LIMIT 100
                )
            """)
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun getRecentFiles(limit: Int): List<RecentOpenEntity> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val list = mutableListOf<RecentOpenEntity>()
        val cursor = db.query(
            "recents",
            null,
            null, null, null, null,
            "last_opened_timestamp DESC",
            limit.toString()
        )
        if (cursor.moveToFirst()) {
            do {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow("id"))
                val path = cursor.getString(cursor.getColumnIndexOrThrow("path"))
                val displayName = cursor.getString(cursor.getColumnIndexOrThrow("display_name"))
                val timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("last_opened_timestamp"))
                val openCount = cursor.getInt(cursor.getColumnIndexOrThrow("open_count"))
                list.add(RecentOpenEntity(id, path, displayName, timestamp, openCount))
            } while (cursor.moveToNext())
        }
        cursor.close()
        list
    }

    fun recordNetworkConnection(path: java8.nio.file.Path) {
        val storages = Settings.STORAGES.valueCompat
        val matchingStorage = storages.firstOrNull { it.path != null && path.startsWith(it.path) }
        if (matchingStorage != null && (matchingStorage is me.zhanghai.android.files.storage.SmbServer ||
            matchingStorage is me.zhanghai.android.files.storage.FtpServer ||
            matchingStorage is me.zhanghai.android.files.storage.SftpServer ||
            matchingStorage is me.zhanghai.android.files.storage.WebDavServer)) {
            val context = application
            val prefs = context.getSharedPreferences("skyfiles_network", Context.MODE_PRIVATE)
            val name = matchingStorage.getName(context)
            prefs.edit()
                .putString("last_connected", name)
                .putStringSet("active_connections", (prefs.getStringSet("active_connections", emptySet()) ?: emptySet()).toMutableSet().apply { add(name) })
                .apply()
        }
    }
}
