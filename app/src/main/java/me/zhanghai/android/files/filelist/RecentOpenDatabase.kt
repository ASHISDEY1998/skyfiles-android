/*
 * Copyright (c) 2026 SkyFiles
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import me.zhanghai.android.files.app.application

data class RecentOpenEntity(
    val id: Long = 0,
    val path: String,
    val displayName: String,
    val lastOpenedTimestamp: Long,
    val openCount: Int
)

class RecentOpenDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE recents (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                path TEXT UNIQUE,
                display_name TEXT,
                last_opened_timestamp INTEGER,
                open_count INTEGER
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS recents")
        onCreate(db)
    }

    companion object {
        private const val DATABASE_NAME = "recent_opens.db"
        private const val DATABASE_VERSION = 1

        val instance by lazy { RecentOpenDatabase(application) }
    }
}
