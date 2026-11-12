@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.mohamed3on.iptv.player

import android.util.Log
import android.view.Display
import android.view.Window
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** A display mode, as [Display.Mode] reports it. */
data class Mode(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

/**
 * The mode to switch to for a stream at [fps], or null to stay: the current mode already shows
 * every frame evenly (its rate is a whole multiple of [fps]), or no mode at the current
 * resolution does. Of the modes that fit, the fastest wins, so 25fps plays at 50Hz, not 25Hz.
 */
fun matchingMode(current: Mode, supported: List<Mode>, fps: Float): Int? {
    fun fits(mode: Mode): Boolean {
        val k = (mode.refreshRate / fps).roundToInt()
        return k >= 1 && abs(mode.refreshRate / (k * fps) - 1) < RATE_TOLERANCE
    }
    if (fits(current)) return null
    return supported.filter { it.width == current.width && it.height == current.height && fits(it) }
        .maxByOrNull { it.refreshRate }?.id
}

/**
 * The frame rate of rendered frames' presentation times, snapped to a broadcast rate. Uses the
 * typical gap between frames, so dropped frames (double gaps) and stream jumps don't skew it.
 */
fun measuredFps(ptsUs: List<Long>): Float? {
    val gaps = ptsUs.zipWithNext { a, b -> b - a }.filter { it in 1..MAX_GAP_US }
    if (gaps.size < MIN_GAPS) return null
    val median = gaps.sorted()[gaps.size / 2]
    val typical = gaps.filter { abs(it - median) <= median / 10 }.average()
    return snapFps((1_000_000 / typical).toFloat())
}

private fun snapFps(fps: Float): Float? =
    STANDARD_FPS.minBy { abs(it - fps) }.takeIf { abs(it - fps) / it < SNAP_TOLERANCE }

/**
 * Frame-rate matching (R23, KTD13): once a feed has played for [SETTLE_MS] (so never during a
 * zap burst), the window asks for the display mode matching the stream's frame rate, e.g. 50Hz
 * for European sports. The TS streams don't declare a frame rate, so it's measured from the
 * frames as they render. Media3 already passes its own estimate to `Surface.setFrameRate`, the
 * secondary hint. The rate is learnt once per stream ([Showing.stream]): a reconnect, reopen or
 * seek of the same one doesn't learn it again, as a second reading 0.1% off the first (23.976 vs
 * 24) switched the TV again and blanked it. While the picture is [Playback.Waiting] the mode
 * stays; the preference is dropped when playback is [Playback.Stopped] and on [detach].
 */
class FrameRate(
    private val window: Window,
    private val exo: ExoPlayer,
    showing: Flow<Showing>,
    scope: CoroutineScope,
) {
    /** Presentation times of the current stream's frames; written on the playback thread. */
    private val samples = ArrayList<Long>()
    private val fps = MutableStateFlow<Float?>(null)
    /** The current stream's frame rate as measured from its frames; null until measured. */
    val measured: StateFlow<Float?> get() = fps

    private val frames = VideoFrameMetadataListener { presentationTimeUs, _, format, _ ->
        if (fps.value != null) return@VideoFrameMetadataListener
        val declared = format.frameRate.takeIf { it > 0 }?.let(::snapFps)
        if (declared != null) {
            fps.value = declared
            Log.i(TAG, "learnt ${declared}fps, declared ${format.frameRate} (${format.sampleMimeType})")
            return@VideoFrameMetadataListener
        }
        synchronized(samples) {
            samples += presentationTimeUs
            if (samples.size >= SAMPLES) {
                fps.value = measuredFps(samples)
                Log.i(TAG, "learnt ${fps.value}fps, measured over ${samples.size} frames (${format.sampleMimeType})")
                samples.clear()
            }
        }
    }

    private val job = scope.launch {
        var stream: Any? = null
        showing.collectLatest { now ->
            if (now.stream != stream) {
                stream = now.stream
                reset()
            }
            when (now.playback) {
                Playback.Stopped -> prefer(0)
                Playback.Waiting -> {}
                Playback.Playing -> {
                    delay(SETTLE_MS)
                    match(fps.filterNotNull().first())
                }
            }
        }
    }

    init {
        exo.setVideoFrameMetadataListener(frames)
    }

    fun detach() {
        job.cancel()
        exo.clearVideoFrameMetadataListener(frames)
        prefer(0)
    }

    private fun reset() {
        synchronized(samples) {
            samples.clear()
            fps.value = null
        }
    }

    private fun match(fps: Float) {
        val display = window.decorView.display ?: return
        val id = matchingMode(display.mode.toMode(), display.supportedModes.map { it.toMode() }, fps) ?: return
        Log.i(TAG, "stream at ${fps}fps: asking for display mode $id")
        prefer(id)
    }

    /** 0 hands the choice back to the system. */
    private fun prefer(modeId: Int) {
        val attributes = window.attributes
        if (attributes.preferredDisplayModeId == modeId) return
        attributes.preferredDisplayModeId = modeId
        window.attributes = attributes
    }

    private fun Display.Mode.toMode() = Mode(modeId, physicalWidth, physicalHeight, refreshRate)

    private companion object {
        const val TAG = "FrameRate"
        const val SETTLE_MS = 1_500L
        /** About a second of 50fps video. */
        const val SAMPLES = 60
    }
}

private val STANDARD_FPS = floatArrayOf(23.976f, 24f, 25f, 29.97f, 30f, 50f, 59.94f, 60f)
/** 59.94 and 60 differ by 0.1%: tighter than that, a 59.94Hz mode reported as 59.94006 still fits. */
private const val RATE_TOLERANCE = 0.0005f
private const val SNAP_TOLERANCE = 0.01f
private const val MIN_GAPS = 10
/** Longer gaps are a stream jump, not a frame. */
private const val MAX_GAP_US = 200_000L
