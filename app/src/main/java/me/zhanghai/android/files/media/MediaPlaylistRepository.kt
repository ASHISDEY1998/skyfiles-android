package me.zhanghai.android.files.media

import android.util.Log
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.guessFromPath
import java.io.IOException
import java.nio.file.DirectoryStream
import java.util.Collections

enum class PlaylistSource {
    SOURCE_FOLDER,
    SOURCE_SEARCH,
    SOURCE_RECENTS,
    SOURCE_FAVORITES,
    SOURCE_MEDIA_CATEGORY
}

class MediaPlaylistRepository {
    private var playlist: List<String> = emptyList()
    private var currentIndex: Int = -1
    private var source: PlaylistSource = PlaylistSource.SOURCE_FOLDER

    fun setPlaylist(paths: List<String>, currentPath: String, playlistSource: PlaylistSource) {
        playlist = paths
        source = playlistSource
        currentIndex = playlist.indexOf(currentPath)
        Log.d(TAG, "Playlist set with ${playlist.size} items. Current index: $currentIndex, Source: $source")
    }

    suspend fun loadFolderPlaylistIfNeeded(currentPathString: String) = withContext(Dispatchers.IO) {
        if (playlist.isNotEmpty() && currentIndex != -1) {
            return@withContext
        }
        try {
            val currentPath = Paths.get(currentPathString)
            val parent = currentPath.parent ?: return@withContext
            val videoPaths = mutableListOf<String>()
            
            java8.nio.file.Files.newDirectoryStream(parent).use { stream ->
                for (path in stream) {
                    if (java8.nio.file.Files.isRegularFile(path)) {
                        val mimeType = MimeType.guessFromPath(path.toString()).value
                        if (mimeType.startsWith("video/")) {
                            videoPaths.add(path.toString())
                        }
                    }
                }
            }
            
            // Sort by file name
            videoPaths.sort()
            playlist = videoPaths
            currentIndex = playlist.indexOf(currentPathString)
            source = PlaylistSource.SOURCE_FOLDER
            Log.d(TAG, "Automatically loaded folder playlist with ${playlist.size} items. Current index: $currentIndex")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load folder playlist", e)
            playlist = listOf(currentPathString)
            currentIndex = 0
        }
    }

    fun getCurrentPath(): String? {
        return if (currentIndex in playlist.indices) playlist[currentIndex] else null
    }

    fun hasNext(): Boolean {
        return currentIndex != -1 && currentIndex < playlist.size - 1
    }

    fun hasPrevious(): Boolean {
        return currentIndex > 0
    }

    fun getNext(): String? {
        if (hasNext()) {
            currentIndex++
            return playlist[currentIndex]
        }
        return null
    }

    fun getPrevious(): String? {
        if (hasPrevious()) {
            currentIndex--
            return playlist[currentIndex]
        }
        return null
    }

    fun getPlaylist(): List<String> = playlist

    companion object {
        private const val TAG = "MediaPlaylistRepository"
        val instance by lazy { MediaPlaylistRepository() }
    }
}
