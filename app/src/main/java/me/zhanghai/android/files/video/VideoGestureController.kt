package me.zhanghai.android.files.video

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

class VideoGestureController(
    context: Context,
    private val view: View,
    private val callback: GestureCallback
) : View.OnTouchListener {

    interface GestureCallback {
        fun onSingleTap()
        fun onDoubleTapLeft()
        fun onDoubleTapRight()
        fun onDoubleTapCenter()
        fun onVolumeSwipe(deltaPercent: Float)
        fun onBrightnessSwipe(deltaPercent: Float)
        fun onSeekScrubStart()
        fun onSeekScrub(deltaMs: Long)
        fun onSeekScrubEnd()
        fun onScale(scaleFactor: Float)
        fun onPan(dx: Float, dy: Float)
    }

    private val gestureDetector: GestureDetector
    private val scaleGestureDetector: ScaleGestureDetector
    
    private var isScrubbing = false
    private var isVerticalSwipe = false
    private var isScaling = false
    private var swipeType = SwipeType.NONE
    
    private var scaleFactor = 1.0f
    
    enum class SwipeType {
        NONE, VOLUME, BRIGHTNESS, SEEK
    }

    init {
        gestureDetector = GestureDetector(context, GestureListener())
        scaleGestureDetector = ScaleGestureDetector(context, ScaleListener())
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        // First feed to scale gesture detector
        scaleGestureDetector.onTouchEvent(event)
        
        if (scaleGestureDetector.isInProgress) {
            isScaling = true
            return true
        }

        if (event.action == MotionEvent.ACTION_UP) {
            if (isScrubbing) {
                isScrubbing = false
                callback.onSeekScrubEnd()
            }
            isVerticalSwipe = false
            swipeType = SwipeType.NONE
            isScaling = false
        }

        return gestureDetector.onTouchEvent(event) || isScrubbing || isVerticalSwipe || isScaling
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor *= detector.scaleFactor
            scaleFactor = scaleFactor.coerceIn(1.0f, 5.0f)
            callback.onScale(scaleFactor)
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            callback.onSingleTap()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val width = view.width
            val x = e.x
            when {
                x < width / 3 -> callback.onDoubleTapLeft()
                x > width * 2 / 3 -> callback.onDoubleTapRight()
                else -> callback.onDoubleTapCenter()
            }
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (e1 == null) return false
            
            // If scaleFactor > 1x, we handle panning
            if (scaleFactor > 1.0f) {
                callback.onPan(-distanceX, -distanceY)
                return true
            }

            val width = view.width
            val height = view.height
            val deltaX = e2.x - e1.x
            val deltaY = e2.y - e1.y

            if (swipeType == SwipeType.NONE) {
                if (Math.abs(deltaX) > Math.abs(deltaY)) {
                    swipeType = SwipeType.SEEK
                    isScrubbing = true
                    callback.onSeekScrubStart()
                } else {
                    swipeType = if (e1.x < width / 2) {
                        SwipeType.BRIGHTNESS
                    } else {
                        SwipeType.VOLUME
                    }
                    isVerticalSwipe = true
                }
            }

            when (swipeType) {
                SwipeType.VOLUME -> {
                    // Upward swipe has negative distanceY, so we negate it to increase volume
                    val deltaPercent = (-distanceY / height) * 100f
                    callback.onVolumeSwipe(deltaPercent)
                }
                SwipeType.BRIGHTNESS -> {
                    val deltaPercent = (-distanceY / height) * 100f
                    callback.onBrightnessSwipe(deltaPercent)
                }
                SwipeType.SEEK -> {
                    // Scrub: convert horizontal swipe delta to time delta (e.g. 1px = 100ms)
                    val deltaMs = (deltaX * 100).toLong()
                    callback.onSeekScrub(deltaMs)
                }
                else -> {}
            }
            return true
        }
    }
}
