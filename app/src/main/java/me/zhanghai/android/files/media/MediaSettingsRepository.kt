package me.zhanghai.android.files.media

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import me.zhanghai.android.files.app.application

class MediaSettingsRepository private constructor(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var autoPlayNext: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PLAY_NEXT, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_PLAY_NEXT, value).apply()

    var playbackSpeed: Float
        get() = prefs.getFloat(KEY_PLAYBACK_SPEED, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_PLAYBACK_SPEED, value).apply()

    var aspectMode: String
        get() = prefs.getString(KEY_ASPECT_MODE, "FIT") ?: "FIT"
        set(value) = prefs.edit().putString(KEY_ASPECT_MODE, value).apply()

    var subtitleSize: Float
        get() = prefs.getFloat(KEY_SUBTITLE_SIZE, 16f)
        set(value) = prefs.edit().putFloat(KEY_SUBTITLE_SIZE, value).apply()

    var subtitleColor: Int
        get() = prefs.getInt(KEY_SUBTITLE_COLOR, Color.WHITE)
        set(value) = prefs.edit().putInt(KEY_SUBTITLE_COLOR, value).apply()

    var subtitleBackground: Int
        get() = prefs.getInt(KEY_SUBTITLE_BACKGROUND, Color.TRANSPARENT)
        set(value) = prefs.edit().putInt(KEY_SUBTITLE_BACKGROUND, value).apply()

    var subtitleDelay: Long
        get() = prefs.getLong(KEY_SUBTITLE_DELAY, 0L)
        set(value) = prefs.edit().putLong(KEY_SUBTITLE_DELAY, value).apply()

    companion object {
        private const val PREFS_NAME = "skyfiles_media_settings"
        private const val KEY_AUTO_PLAY_NEXT = "auto_play_next"
        private const val KEY_PLAYBACK_SPEED = "playback_speed"
        private const val KEY_ASPECT_MODE = "aspect_mode"
        private const val KEY_SUBTITLE_SIZE = "subtitle_size"
        private const val KEY_SUBTITLE_COLOR = "subtitle_color"
        private const val KEY_SUBTITLE_BACKGROUND = "subtitle_background"
        private const val KEY_SUBTITLE_DELAY = "subtitle_delay"

        val instance by lazy { MediaSettingsRepository(application) }
    }
}
