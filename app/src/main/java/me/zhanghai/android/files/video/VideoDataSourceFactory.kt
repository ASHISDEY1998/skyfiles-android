package me.zhanghai.android.files.video

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java8.nio.channels.SeekableByteChannel
import java8.nio.file.Files
import java8.nio.file.Path
import java8.nio.file.Paths
import java8.nio.file.StandardOpenOption
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.provider.common.newByteChannel
import me.zhanghai.android.files.provider.common.size
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

fun getPathSafe(pathString: String): Path {
    return try {
        if (pathString.contains(":/")) {
            Paths.get(java.net.URI.create(pathString))
        } else {
            Paths.get(pathString)
        }
    } catch (e: Exception) {
        Paths.get(pathString)
    }
}

class NioPathDataSource(private val path: Path) : BaseDataSource(/* isNetwork = */ false) {
    private var channel: SeekableByteChannel? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        
        val mode = StandardOpenOption.READ
        val ch = path.newByteChannel(setOf(mode))
        channel = ch
        
        val size = ch.size()
        if (dataSpec.position > 0) {
            ch.position(dataSpec.position)
        }
        
        bytesRemaining = if (dataSpec.length != -1L.toLong()) {
            dataSpec.length
        } else {
            size - dataSpec.position
        }
        
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) {
            return 0
        } else if (bytesRemaining == 0L) {
            return C.RESULT_END_OF_INPUT
        }
        
        val ch = channel ?: throw IOException("Channel not open")
        val bytesToRead = Math.min(bytesRemaining, length.toLong()).toInt()
        val byteBuffer = ByteBuffer.wrap(buffer, offset, bytesToRead)
        
        val bytesRead = ch.read(byteBuffer)
        if (bytesRead == -1) {
            return C.RESULT_END_OF_INPUT
        }
        
        bytesRemaining -= bytesRead
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
        try {
            channel?.close()
        } finally {
            channel = null
        }
    }
}

class StreamingPathDataSource(private val path: Path) : BaseDataSource(/* isNetwork = */ true) {
    private var inputStream: InputStream? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        
        VideoCrashLogger.log("VIDEO_FILE_OPEN_START")
        val stream = try {
            val isLocal = path.isLinuxPath
            val pathType = if (isLocal) "LOCAL" else "REMOTE"
            val providerType = path.fileSystem.provider().javaClass.name
            
            val fileUri = path.fileProviderUri
            val resolvedStream = me.zhanghai.android.files.app.application.contentResolver.openInputStream(fileUri)
                ?: throw IOException("ContentResolver returned null stream")
            
            VideoCrashLogger.log("""
                VIDEO_FILE_OPEN_SUCCESS
                PathType=$pathType
                Provider=$providerType
            """.trimIndent())
            resolvedStream
        } catch (e: Exception) {
            val isLocal = path.isLinuxPath
            val pathType = if (isLocal) "LOCAL" else "REMOTE"
            val providerType = try { path.fileSystem.provider().javaClass.name } catch (ex: Exception) { "UNKNOWN" }
            VideoCrashLogger.log("""
                VIDEO_FILE_OPEN_FAILED
                PathType=$pathType
                Provider=$providerType
                Exception=${e.javaClass.name}: ${e.message}
            """.trimIndent())
            throw e
        }
        inputStream = stream
        
        if (dataSpec.position > 0) {
            var skipped = 0L
            while (skipped < dataSpec.position) {
                val skip = stream.skip(dataSpec.position - skipped)
                if (skip <= 0) {
                    break
                }
                skipped += skip
            }
        }
        
        val size = try {
            java8.nio.file.Files.size(path)
        } catch (e: Exception) {
            -1L.toLong()
        }
        
        bytesRemaining = if (dataSpec.length != -1L.toLong()) {
            dataSpec.length
        } else if (size != -1L.toLong()) {
            size - dataSpec.position
        } else {
            -1L.toLong()
        }
        
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) {
            return 0
        } else if (bytesRemaining == 0L) {
            return C.RESULT_END_OF_INPUT
        }
        
        val stream = inputStream ?: throw IOException("Stream not open")
        val bytesToRead = if (bytesRemaining == -1L.toLong()) {
            length
        } else {
            Math.min(bytesRemaining, length.toLong()).toInt()
        }
        
        val bytesRead = stream.read(buffer, offset, bytesToRead)
        if (bytesRead == -1) {
            return C.RESULT_END_OF_INPUT
        }
        
        if (bytesRemaining != -1L.toLong()) {
            bytesRemaining -= bytesRead
        }
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
        try {
            inputStream?.close()
        } finally {
            inputStream = null
        }
    }
}

class PathDataSourceFactory(private val path: Path) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        val pathString = path.toString()
        val scheme = path.toUri().scheme?.lowercase()
        val type = when {
            pathString.startsWith("smb:/", ignoreCase = true) -> "SMB"
            pathString.startsWith("ftp:/", ignoreCase = true) -> "FTP"
            pathString.startsWith("sftp:/", ignoreCase = true) -> "SFTP"
            pathString.startsWith("dav:/", ignoreCase = true) || pathString.startsWith("davs:/", ignoreCase = true) || pathString.startsWith("webdav:/", ignoreCase = true) -> "WEBDAV"
            else -> "LOCAL"
        }
        VideoCrashLogger.log("""
            DATASOURCE_CREATE
            Type=$type
            Path=$pathString
        """.trimIndent())

        return if (scheme == "ftp" || scheme == "webdav" || scheme == "http" || scheme == "https") {
            PlayerDiagnostics.log("Using StreamingPathDataSource (Mode B) for $path")
            StreamingPathDataSource(path)
        } else {
            try {
                // Verify seekable support
                val testChannel = path.newByteChannel(setOf(StandardOpenOption.READ))
                testChannel.close()
                PlayerDiagnostics.log("Using NioPathDataSource (Mode A) for $path")
                NioPathDataSource(path)
            } catch (e: Exception) {
                PlayerDiagnostics.log("Fallback to StreamingPathDataSource for $path: ${e.message}")
                StreamingPathDataSource(path)
            }
        }
    }
}

object VideoDataSourceFactory {
    private const val TAG = "VideoDataSourceFactory"

    fun createMediaItem(pathString: String): MediaItem {
        val path = getPathSafe(pathString)
        val uri = Uri.parse(path.toUri().toString())
        val name = path.fileName?.toString() ?: "Video"
        
        val subtitleConfigurations = discoverSubtitles(path)
        
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(pathString)
            .setSubtitleConfigurations(subtitleConfigurations)
            .build()
    }

    private fun discoverSubtitles(videoPath: Path): List<MediaItem.SubtitleConfiguration> {
        val parent = videoPath.parent ?: return emptyList()
        val videoName = videoPath.fileName?.toString() ?: return emptyList()
        val dotIndex = videoName.lastIndexOf('.')
        if (dotIndex == -1) return emptyList()
        val baseName = videoName.substring(0, dotIndex)
        
        val list = mutableListOf<MediaItem.SubtitleConfiguration>()
        val supportedSubtitleExtensions = listOf("srt", "ass", "ssa", "vtt")
        
        try {
            java8.nio.file.Files.newDirectoryStream(parent).use { stream ->
                for (sibling in stream) {
                    if (java8.nio.file.Files.isRegularFile(sibling)) {
                        val siblingName = sibling.fileName.toString()
                        if (siblingName.startsWith(baseName) && siblingName != videoName) {
                            val extIndex = siblingName.lastIndexOf('.')
                            if (extIndex != -1) {
                                val ext = siblingName.substring(extIndex + 1).lowercase()
                                if (ext in supportedSubtitleExtensions) {
                                    val mimeType = when (ext) {
                                        "srt" -> MimeTypes.APPLICATION_SUBRIP
                                        "ass", "ssa" -> MimeTypes.TEXT_SSA
                                        "vtt" -> MimeTypes.TEXT_VTT
                                        else -> MimeTypes.APPLICATION_SUBRIP
                                    }
                                    val config = MediaItem.SubtitleConfiguration.Builder(Uri.parse(sibling.toUri().toString()))
                                        .setMimeType(mimeType)
                                        .setLanguage("en")
                                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                        .build()
                                    list.add(config)
                                    PlayerDiagnostics.log("Discovered auto-subtitle file: $siblingName")
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning directory for subtitles", e)
        }
        return list
    }
}
