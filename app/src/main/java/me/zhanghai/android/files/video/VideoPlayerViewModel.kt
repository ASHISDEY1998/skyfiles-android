package me.zhanghai.android.files.video

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import java8.nio.file.Paths
import java8.nio.file.Files
import java8.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.media.MediaPlaylistRepository
import me.zhanghai.android.files.media.MediaResumeRepository
import me.zhanghai.android.files.media.MediaSettingsRepository
import me.zhanghai.android.files.media.ResumeEntry
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.guessFromPath
import java.io.IOException

@OptIn(UnstableApi::class)
class VideoPlayerViewModel(application: Application) : AndroidViewModel(application), AudioManager.OnAudioFocusChangeListener {

    private val context = application.applicationContext
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    
    var player: ExoPlayer? = null
        private set

    private val _videoSize = MutableLiveData<VideoSize>()
    val videoSize: LiveData<VideoSize> = _videoSize

    private val _playbackState = MutableLiveData<Int>()
    val playbackState: LiveData<Int> = _playbackState

    private val _currentPath = MutableLiveData<String?>()
    val currentPath: LiveData<String?> = _currentPath

    private val _resumePrompt = MutableLiveData<Long?>()
    val resumePrompt: LiveData<Long?> = _resumePrompt

    private var audioFocusRequest: AudioFocusRequest? = null
    
    private var sleepTimerJob: Job? = null
    private val _sleepTimeRemaining = MutableLiveData<Long>()
    val sleepTimeRemaining: LiveData<Long> = _sleepTimeRemaining

    private val handler = Handler(Looper.getMainLooper())
    private var activeDecoderName: String? = null
    private var progressLoggerJob: Job? = null

    fun initializePlayer(pathString: String) {
        if (player != null) return

        val mime = me.zhanghai.android.files.file.MimeType.guessFromPath(pathString).value
        val sourceType = when {
            pathString.startsWith("smb:/", ignoreCase = true) -> "SMB"
            pathString.startsWith("ftp:/", ignoreCase = true) -> "FTP"
            pathString.startsWith("sftp:/", ignoreCase = true) -> "SFTP"
            pathString.startsWith("dav:/", ignoreCase = true) || pathString.startsWith("davs:/", ignoreCase = true) || pathString.startsWith("webdav:/", ignoreCase = true) -> "WEBDAV"
            else -> "LOCAL"
        }
        VideoCrashLogger.log("""
            PLAYER_INIT_START
            Path=$pathString
            Mime=$mime
            Source=$sourceType
        """.trimIndent())

        try {
            PlayerDiagnostics.log("Initializing player for file: $pathString")
            _currentPath.value = pathString

            // Set up renderers factory with software decoder fallback allowed
            val renderersFactory = DefaultRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

            player = ExoPlayer.Builder(context, renderersFactory).build().apply {
                playWhenReady = true
                
                // Audio Attributes
                val media3Attrs = Media3AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build()
                setAudioAttributes(media3Attrs, /* handleAudioFocus = */ true)

                // Setup listeners
                addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        _videoSize.value = videoSize
                        PlayerDiagnostics.log("Resolution changed: ${videoSize.width}x${videoSize.height}")
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        _playbackState.value = state
                        when (state) {
                            Player.STATE_READY -> {
                                PlayerDiagnostics.log("Playback state: READY. Duration = ${duration}ms")
                                // Restores persistent speed
                                setPlaybackSpeed(MediaSettingsRepository.instance.playbackSpeed)

                                val width = videoSize.width
                                val height = videoSize.height
                                val codec = videoFormat?.sampleMimeType ?: "UNKNOWN"
                                val decoder = activeDecoderName ?: "UNKNOWN"
                                val isHW = !decoder.lowercase().contains("google") && !decoder.lowercase().contains("android") && !decoder.lowercase().contains("sw")
                                val modeStr = if (isHW) "Hardware" else "Software"

                                VideoCrashLogger.log("""
                                    VIDEO_PLAYBACK_STARTED
                                    Codec=$codec
                                    Resolution=${width}x${height}
                                    Decoder=$decoder
                                    Mode=$modeStr
                                """.trimIndent())
                            }
                            Player.STATE_ENDED -> {
                                PlayerDiagnostics.log("Playback state: ENDED")
                                if (MediaSettingsRepository.instance.autoPlayNext) {
                                    playNext()
                                }
                            }
                            Player.STATE_BUFFERING -> PlayerDiagnostics.log("Playback state: BUFFERING")
                            Player.STATE_IDLE -> PlayerDiagnostics.log("Playback state: IDLE")
                        }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        PlayerDiagnostics.log("Playback Error: ${error.errorCodeName} - ${error.message}")
                    }
                })

                addAnalyticsListener(object : AnalyticsListener {
                    override fun onVideoDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        decoderName: String,
                        initializedMs: Long,
                        initializationDurationMs: Long
                    ) {
                        activeDecoderName = decoderName
                        PlayerDiagnostics.log("Video Decoder Initialized: $decoderName (HW/SW Mode)")
                    }

                    override fun onAudioDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        decoderName: String,
                        initializedMs: Long,
                        initializationDurationMs: Long
                    ) {
                        PlayerDiagnostics.log("Audio Decoder Initialized: $decoderName")
                    }
                })
            }

            // Request Audio Focus
            requestAudioFocus()

            // Setup MediaItem & Load
            val mediaItem = VideoDataSourceFactory.createMediaItem(pathString)
            
            // Mode selector
            val factory = PathDataSourceFactory(getPathSafe(pathString))
            val mediaSource = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(factory)
                .createMediaSource(mediaItem)

            player?.setMediaSource(mediaSource)
            player?.prepare()
            VideoCrashLogger.log("PLAYER_INIT_SUCCESS")
        } catch (e: Exception) {
            VideoCrashLogger.log("PLAYER_INIT_FAILED")
            throw e
        }

        // Check Resume validation
        viewModelScope.launch(Dispatchers.IO) {
            val file = getPathSafe(pathString)
            val exists = Files.exists(file)
            val size = if (exists) Files.size(file) else 0L
            val lastMod = if (exists) Files.getLastModifiedTime(file).toMillis() else 0L

            val entry = MediaResumeRepository.instance.getResumeEntry(pathString)
            if (entry != null) {
                if (entry.fileSize == size && entry.lastModified == lastMod) {
                    // Valid resume entry, trigger UI prompt on main thread
                    _resumePrompt.postValue(entry.position)
                } else {
                    PlayerDiagnostics.log("Resume validation failed (size or timestamp mismatch). Deleting stale entry for $pathString")
                    MediaResumeRepository.instance.deleteResumeEntry(pathString)
                    _resumePrompt.postValue(null)
                }
            } else {
                _resumePrompt.postValue(null)
            }
        }

        startProgressLogger()
    }

    fun resumePlayback(position: Long) {
        player?.seekTo(position)
        player?.play()
    }

    private fun startProgressLogger() {
        progressLoggerJob?.cancel()
        progressLoggerJob = viewModelScope.launch(Dispatchers.Main.immediate) {
            while (true) {
                delay(5000)
                val p = player
                val path = _currentPath.value
                if (p != null && path != null && p.playbackState == Player.STATE_READY) {
                    val pos = p.currentPosition
                    val dur = p.duration
                    if (pos > 60000 && dur > 0) { // save only if > 60s
                        withContext(Dispatchers.IO) {
                            val file = getPathSafe(path)
                            val exists = Files.exists(file)
                            val size = if (exists) Files.size(file) else 0L
                            val lastMod = if (exists) Files.getLastModifiedTime(file).toMillis() else 0L
                            
                            MediaResumeRepository.instance.saveResumeEntry(
                                ResumeEntry(
                                    path = path,
                                    position = pos,
                                    duration = dur,
                                    timestamp = System.currentTimeMillis(),
                                    fileSize = size,
                                    lastModified = lastMod
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    fun playNext() {
        val next = MediaPlaylistRepository.instance.getNext()
        if (next != null) {
            releasePlayer()
            initializePlayer(next)
        } else {
            PlayerDiagnostics.log("No next video in playlist.")
        }
    }

    fun playPrevious() {
        val prev = MediaPlaylistRepository.instance.getPrevious()
        if (prev != null) {
            releasePlayer()
            initializePlayer(prev)
        } else {
            PlayerDiagnostics.log("No previous video in playlist.")
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        player?.setPlaybackSpeed(speed)
        MediaSettingsRepository.instance.playbackSpeed = speed
    }

    fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) return
        
        val totalMs = minutes * 60 * 1000L
        sleepTimerJob = viewModelScope.launch {
            var remaining = totalMs
            while (remaining > 0) {
                _sleepTimeRemaining.postValue(remaining)
                delay(1000)
                remaining -= 1000
            }
            _sleepTimeRemaining.postValue(0)
            player?.pause()
            PlayerDiagnostics.log("Sleep timer expired. Playback paused.")
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        _sleepTimeRemaining.postValue(0)
    }

    private fun requestAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusAttrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(focusAttrs)
                    .setOnAudioFocusChangeListener(this)
                    .build()
                audioManager.requestAudioFocus(audioFocusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    this,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }
        } catch (e: Exception) {
            Log.e("VideoPlayerViewModel", "Failed to request audio focus", e)
        }
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                player?.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                player?.volume = 0.2f
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                player?.volume = 1.0f
            }
        }
    }

    fun releasePlayer() {
        progressLoggerJob?.cancel()
        sleepTimerJob?.cancel()
        
        val p = player
        val path = _currentPath.value
        if (p != null && path != null) {
            val pos = p.currentPosition
            val dur = p.duration
            if (pos > 60000 && dur > 0) {
                viewModelScope.launch(Dispatchers.IO) {
                    val file = getPathSafe(path)
                    val exists = Files.exists(file)
                    val size = if (exists) Files.size(file) else 0L
                    val lastMod = if (exists) Files.getLastModifiedTime(file).toMillis() else 0L

                    MediaResumeRepository.instance.saveResumeEntry(
                        ResumeEntry(
                            path = path,
                            position = pos,
                            duration = dur,
                            timestamp = System.currentTimeMillis(),
                            fileSize = size,
                            lastModified = lastMod
                        )
                    )
                }
            }
        }

        player?.release()
        player = null
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(this)
            }
        } catch (e: Exception) {
            Log.e("VideoPlayerViewModel", "Failed to abandon audio focus", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        releasePlayer()
    }
}
