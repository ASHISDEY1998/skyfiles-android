package me.zhanghai.android.files.video

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.ui.AspectRatioFrameLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Paths
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.databinding.ActivityVideoPlayerBinding
import me.zhanghai.android.files.media.MediaPlaylistRepository
import me.zhanghai.android.files.media.MediaSettingsRepository
import me.zhanghai.android.files.media.PlaylistSource
import java.util.Formatter
import java.util.Locale

class VideoPlayerActivity : AppActivity(), VideoGestureController.GestureCallback {

    private lateinit var binding: ActivityVideoPlayerBinding
    private val viewModel: VideoPlayerViewModel by viewModels()
    private lateinit var gestureController: VideoGestureController
    private lateinit var audioManager: AudioManager
    
    private val handler = Handler(Looper.getMainLooper())
    private var controlsVisible = true
    private var isLocked = false
    private var isPipActive = false

    private val hideControlsRunnable = Runnable { hideControls() }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val causeTrace = error.cause?.let { android.util.Log.getStackTraceString(it) }.orEmpty()
            VideoCrashLogger.log("""
                PLAYER_ERROR
                Code=${error.errorCode}
                Name=${error.errorCodeName}
                Message=${error.message}
                Cause=${error.cause?.message}
                Stacktrace:
                $causeTrace
            """.trimIndent())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        VideoCrashLogger.log("VIDEO_ACTIVITY_CREATED")
        super.onCreate(savedInstanceState)
        VideoCrashLogger.log("VIDEO_ACTIVITY_THEME_INITIALIZED")
        
        // Fullscreen setup
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        try {
            binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        } catch (e: Exception) {
            VideoCrashLogger.logError("VideoPlayerActivity", "LAYOUT_INFLATION_FAILED", e)
            VideoCrashLogger.log("VIEW_BINDING_FAILED")
            throw e
        }

        try {
            setContentView(binding.root)
        } catch (e: Exception) {
            VideoCrashLogger.logError("VideoPlayerActivity", "LAYOUT_INFLATION_FAILED", e)
            throw e
        }

        try {
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            gestureController = VideoGestureController(this, binding.gestureOverlay, this)
            binding.gestureOverlay.setOnTouchListener(gestureController)
        } catch (e: Exception) {
            VideoCrashLogger.logError("VideoPlayerActivity", "VIEW_BINDING_FAILED", e)
            throw e
        }

        // Setup Viewmodel observers
        setupObservers()

        // Handle path
        val path = try {
            intent.getStringExtra(EXTRA_PATH)
        } catch (e: Exception) {
            VideoCrashLogger.logError("VideoPlayerActivity", "INTENT_PARSE_FAILED", e)
            throw e
        }
        val playlist = try {
            intent.getStringArrayListExtra(EXTRA_PLAYLIST) ?: arrayListOf()
        } catch (e: Exception) {
            VideoCrashLogger.logError("VideoPlayerActivity", "INTENT_PARSE_FAILED", e)
            throw e
        }
        val playlistSource = try {
            intent.getStringExtra(EXTRA_PLAYLIST_SOURCE)?.let {
                try { PlaylistSource.valueOf(it) } catch (e: Exception) { PlaylistSource.SOURCE_FOLDER }
            } ?: PlaylistSource.SOURCE_FOLDER
        } catch (e: Exception) {
            VideoCrashLogger.logError("VideoPlayerActivity", "INTENT_PARSE_FAILED", e)
            throw e
        }

        if (path != null) {
            MediaPlaylistRepository.instance.setPlaylist(playlist, path, playlistSource)
            VideoCrashLogger.log("VIDEO_PLAYER_INIT_START")
            viewModel.initializePlayer(path)
        } else {
            VideoCrashLogger.log("NULL_VIDEO_PATH")
            Toast.makeText(this, "No video source provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Setup Buttons & UI
        setupControls()
        startSeekBarUpdate()
        
        // Hide System Bars
        hideSystemBars()
        
        // Apply default aspect ratio
        applyAspectMode(MediaSettingsRepository.instance.aspectMode)
    }

    private fun setupObservers() {
        viewModel.player?.let {
            binding.playerView.player = it
            it.removeListener(playerListener)
            it.addListener(playerListener)
        }

        viewModel.currentPath.observe(this) { path ->
            if (path != null) {
                binding.txtFilename.text = Paths.get(path).fileName.toString()
                binding.playerView.player = viewModel.player
                viewModel.player?.removeListener(playerListener)
                viewModel.player?.addListener(playerListener)
            }
        }

        viewModel.resumePrompt.observe(this) { position ->
            if (position != null && position > 0) {
                showResumeDialog(position)
            }
        }

        viewModel.sleepTimeRemaining.observe(this) { timeMs ->
            if (timeMs > 0) {
                binding.btnSleepTimer.setColorFilter(Color.RED)
            } else {
                binding.btnSleepTimer.setColorFilter(Color.WHITE)
            }
        }
    }

    private fun showResumeDialog(positionMs: Long) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Resume playback?")
            .setMessage("Last watched at ${stringForTime(positionMs)}")
            .setPositiveButton("Resume") { _, _ ->
                viewModel.resumePlayback(positionMs)
            }
            .setNegativeButton("Start Over") { _, _ ->
                viewModel.resumePlayback(0)
            }
            .setCancelable(false)
            .show()
    }

    private fun setupControls() {
        binding.btnPlay.setOnClickListener {
            val p = viewModel.player ?: return@setOnClickListener
            if (p.isPlaying) {
                p.pause()
                binding.btnPlay.setImageResource(android.R.drawable.ic_media_play)
            } else {
                p.play()
                binding.btnPlay.setImageResource(android.R.drawable.ic_media_pause)
            }
            resetControlsTimer()
        }

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnNext.setOnClickListener {
            viewModel.playNext()
            setupObservers()
            resetControlsTimer()
        }

        binding.btnPrev.setOnClickListener {
            viewModel.playPrevious()
            setupObservers()
            resetControlsTimer()
        }

        binding.btnRewind.setOnClickListener {
            val p = viewModel.player ?: return@setOnClickListener
            p.seekTo((p.currentPosition - 30000).coerceAtLeast(0))
            resetControlsTimer()
        }

        binding.btnForward.setOnClickListener {
            val p = viewModel.player ?: return@setOnClickListener
            p.seekTo((p.currentPosition + 30000).coerceAtMost(p.duration))
            resetControlsTimer()
        }

        binding.btnLock.setOnClickListener {
            isLocked = !isLocked
            if (isLocked) {
                binding.btnLock.setImageResource(android.R.drawable.ic_lock_lock)
                binding.controllerLayout.visibility = View.INVISIBLE
                binding.btnLock.visibility = View.VISIBLE
            } else {
                binding.btnLock.setImageResource(android.R.drawable.ic_lock_idle_lock)
                binding.controllerLayout.visibility = View.VISIBLE
                resetControlsTimer()
            }
        }

        binding.btnAspectRatio.setOnClickListener {
            showAspectDialog()
            resetControlsTimer()
        }

        binding.btnSpeed.setOnClickListener {
            showSpeedDialog()
            resetControlsTimer()
        }

        binding.btnSubtitles.setOnClickListener {
            showSubtitlesDialog()
            resetControlsTimer()
        }

        binding.btnSleepTimer.setOnClickListener {
            showSleepTimerDialog()
            resetControlsTimer()
        }

        binding.seekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val p = viewModel.player ?: return
                    val duration = p.duration
                    val target = (duration * progress) / 1000
                    binding.txtTime.text = stringForTime(target)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                handler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val p = viewModel.player ?: return
                val progress = seekBar?.progress ?: 0
                val target = (p.duration * progress) / 1000
                p.seekTo(target)
                resetControlsTimer()
            }
        })
    }

    private fun showAspectDialog() {
        val modes = arrayOf("FIT", "FILL", "CROP", "STRETCH", "ORIGINAL")
        val currentMode = MediaSettingsRepository.instance.aspectMode
        val checkedItem = modes.indexOf(currentMode).coerceAtLeast(0)
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Aspect Ratio")
            .setSingleChoiceItems(modes, checkedItem) { dialog, which ->
                val selected = modes[which]
                MediaSettingsRepository.instance.aspectMode = selected
                applyAspectMode(selected)
                dialog.dismiss()
            }
            .show()
    }

    private fun applyAspectMode(mode: String) {
        when (mode) {
            "FIT" -> binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            "FILL" -> binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            "CROP" -> binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            "STRETCH" -> binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            "ORIGINAL" -> binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    private fun showSpeedDialog() {
        val speeds = arrayOf("0.25x", "0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "1.75x", "2.0x", "3.0x")
        val values = arrayOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 3.0f)
        val currentSpeed = MediaSettingsRepository.instance.playbackSpeed
        val checkedItem = values.indexOf(currentSpeed).coerceAtLeast(3)

        MaterialAlertDialogBuilder(this)
            .setTitle("Playback Speed")
            .setSingleChoiceItems(speeds, checkedItem) { dialog, which ->
                val speed = values[which]
                viewModel.setPlaybackSpeed(speed)
                binding.btnSpeed.text = speeds[which]
                dialog.dismiss()
            }
            .show()
    }

    private fun showSubtitlesDialog() {
        val p = viewModel.player ?: return
        val tracks = p.currentTracks
        val subtitleTracks = mutableListOf<Tracks.Group>()
        val trackNames = mutableListOf<String>()

        trackNames.add("Disable Subtitles")

        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_TEXT) {
                subtitleTracks.add(group)
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val label = format.label ?: format.language ?: "Track ${subtitleTracks.size}"
                    trackNames.add(label)
                }
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Subtitle Tracks")
            .setItems(trackNames.toTypedArray()) { dialog, which ->
                if (which == 0) {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                } else {
                    var count = 1
                    for (group in subtitleTracks) {
                        for (i in 0 until group.length) {
                            if (count == which) {
                                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                                    .build()
                                dialog.dismiss()
                                return@setItems
                            }
                            count++
                        }
                    }
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun showSleepTimerDialog() {
        val options = arrayOf("15 Min", "30 Min", "45 Min", "60 Min", "Cancel Timer")
        val values = intArrayOf(15, 30, 45, 60, 0)
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Sleep Timer")
            .setItems(options) { _, which ->
                val minutes = values[which]
                if (minutes > 0) {
                    viewModel.startSleepTimer(minutes)
                    Toast.makeText(this, "Sleep timer set for $minutes minutes", Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.cancelSleepTimer()
                    Toast.makeText(this, "Sleep timer cancelled", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun startSeekBarUpdate() {
        handler.post(object : Runnable {
            override fun run() {
                val p = viewModel.player
                if (p != null && p.playbackState == Player.STATE_READY) {
                    val current = p.currentPosition
                    val duration = p.duration
                    binding.txtTime.text = stringForTime(current)
                    binding.txtDuration.text = stringForTime(duration)
                    if (duration > 0) {
                        binding.seekbar.progress = ((current * 1000) / duration).toInt()
                    }
                    if (p.isPlaying) {
                        binding.btnPlay.setImageResource(android.R.drawable.ic_media_pause)
                    } else {
                        binding.btnPlay.setImageResource(android.R.drawable.ic_media_play)
                    }
                }
                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun resetControlsTimer() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, 3000)
    }

    private fun showControls() {
        if (isLocked) {
            binding.btnLock.visibility = View.VISIBLE
            return
        }
        binding.controllerLayout.visibility = View.VISIBLE
        binding.btnLock.visibility = View.VISIBLE
        controlsVisible = true
        resetControlsTimer()
    }

    private fun hideControls() {
        binding.controllerLayout.visibility = View.INVISIBLE
        if (isLocked) {
            binding.btnLock.visibility = View.INVISIBLE
        }
        controlsVisible = false
    }

    private fun hideSystemBars() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun stringForTime(timeMs: Long): String {
        val totalSeconds = (timeMs + 500) / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        val formatBuilder = StringBuilder()
        val formatter = Formatter(formatBuilder, Locale.getDefault())
        return if (hours > 0) {
            formatter.format("%d:%02d:%02d", hours, minutes, seconds).toString()
        } else {
            formatter.format("%02d:%02d", minutes, seconds).toString()
        }
    }

    // Gesture Controller Callbacks
    override fun onSingleTap() {
        if (controlsVisible) {
            hideControls()
        } else {
            showControls()
        }
    }

    override fun onDoubleTapLeft() {
        if (isLocked) return
        val p = viewModel.player ?: return
        val target = (p.currentPosition - 10000).coerceAtLeast(0)
        p.seekTo(target)
        showDoubleTapFeedback("<< 10s")
    }

    override fun onDoubleTapRight() {
        if (isLocked) return
        val p = viewModel.player ?: return
        val target = (p.currentPosition + 10000).coerceAtMost(p.duration)
        p.seekTo(target)
        showDoubleTapFeedback("10s >>")
    }

    override fun onDoubleTapCenter() {
        if (isLocked) return
        binding.btnPlay.performClick()
    }

    private fun showDoubleTapFeedback(text: String) {
        binding.doubleTapOverlay.text = text
        binding.doubleTapOverlay.visibility = View.VISIBLE
        handler.removeCallbacksAndMessages(binding.doubleTapOverlay)
        handler.postDelayed({
            binding.doubleTapOverlay.visibility = View.GONE
        }, 1000)
    }

    override fun onVolumeSwipe(deltaPercent: Float) {
        if (isLocked) return
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val delta = (deltaPercent / 100f) * maxVolume
        val target = (currentVolume + delta).toInt().coerceIn(0, maxVolume)
        
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        
        binding.volumeOverlay.visibility = View.VISIBLE
        val pct = (target * 100) / maxVolume
        binding.volumeText.text = "$pct%"
        handler.removeCallbacksAndMessages(binding.volumeOverlay)
        handler.postDelayed({
            binding.volumeOverlay.visibility = View.GONE
        }, 1000)
    }

    override fun onBrightnessSwipe(deltaPercent: Float) {
        if (isLocked) return
        val attrs = window.attributes
        var currentBrightness = attrs.screenBrightness
        if (currentBrightness < 0) {
            currentBrightness = 0.5f // default
        }
        val delta = deltaPercent / 100f
        val target = (currentBrightness + delta).coerceIn(0.01f, 1.0f)
        
        attrs.screenBrightness = target
        window.attributes = attrs
        
        binding.brightnessOverlay.visibility = View.VISIBLE
        val pct = (target * 100).toInt()
        binding.brightnessText.text = "$pct%"
        handler.removeCallbacksAndMessages(binding.brightnessOverlay)
        handler.postDelayed({
            binding.brightnessOverlay.visibility = View.GONE
        }, 1000)
    }

    override fun onSeekScrubStart() {
        if (isLocked) return
        binding.seekScrubOverlay.visibility = View.VISIBLE
    }

    override fun onSeekScrub(deltaMs: Long) {
        if (isLocked) return
        val p = viewModel.player ?: return
        val current = p.currentPosition
        val duration = p.duration
        val target = (current + deltaMs).coerceIn(0, duration)
        
        binding.scrubDirection.text = if (deltaMs > 0) "Forward" else "Rewind"
        binding.scrubTime.text = stringForTime(target)
    }

    override fun onSeekScrubEnd() {
        if (isLocked) return
        binding.seekScrubOverlay.visibility = View.GONE
        val p = viewModel.player ?: return
        val progress = binding.seekbar.progress
        val target = (p.duration * progress) / 1000
        p.seekTo(target)
    }

    override fun onScale(scaleFactor: Float) {
        if (isLocked) return
        binding.playerView.scaleX = scaleFactor
        binding.playerView.scaleY = scaleFactor
        
        binding.zoomOverlayText.visibility = View.VISIBLE
        binding.zoomOverlayText.text = String.format(Locale.US, "Zoom: %.1fx", scaleFactor)
        handler.removeCallbacksAndMessages(binding.zoomOverlayText)
        handler.postDelayed({
            binding.zoomOverlayText.visibility = View.GONE
        }, 1000)
    }

    override fun onPan(dx: Float, dy: Float) {
        if (isLocked) return
        binding.playerView.translationX += dx
        binding.playerView.translationY += dy
    }



    override fun onStart() {
        VideoCrashLogger.log("VIDEO_ACTIVITY_STARTED")
        super.onStart()
    }

    override fun onResume() {
        VideoCrashLogger.log("VIDEO_ACTIVITY_RESUMED")
        super.onResume()
    }

    override fun onPause() {
        VideoCrashLogger.log("VIDEO_ACTIVITY_PAUSED")
        super.onPause()
    }

    override fun onStop() {
        VideoCrashLogger.log("VIDEO_ACTIVITY_STOPPED")
        super.onStop()
    }

    override fun onDestroy() {
        VideoCrashLogger.log("VIDEO_ACTIVITY_DESTROYED")
        viewModel.player?.removeListener(playerListener)
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    companion object {
        private const val EXTRA_PATH = "path"
        private const val EXTRA_PLAYLIST = "playlist"
        private const val EXTRA_PLAYLIST_SOURCE = "playlist_source"

        fun start(context: Context, path: String, playlist: List<String> = emptyList(), source: PlaylistSource = PlaylistSource.SOURCE_FOLDER) {
            val intent = Intent(context, VideoPlayerActivity::class.java).apply {
                putExtra(EXTRA_PATH, path)
                putStringArrayListExtra(EXTRA_PLAYLIST, ArrayList(playlist))
                putExtra(EXTRA_PLAYLIST_SOURCE, source.name)
            }
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
