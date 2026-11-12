package com.mohamed3on.iptv.player

import android.view.Window
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What a player is doing, all its surface needs to know: live TV's session and a film's each say it in these terms. */
enum class Playback {
    Playing,
    /** Between pictures (a zap, a reconnect, a copy opening): the display keeps its mode. */
    Waiting,
    /** Nothing to show: the display gets its own mode back. */
    Stopped,
}

/**
 * A player's [playback] of [stream] (a live feed's id, a film copy's id): a reconnect, reopen or seek of the same
 * stream keeps what was learnt about it (its frame rate), a new stream learns its own.
 */
data class Showing(val playback: Playback, val stream: Any?)

/**
 * Where a player meets the screen, the same for live TV and a film: the picture's [aspect], refresh-rate
 * switching ([FrameRate], following [showing]) and the [audio] tracks. Without a [window] the display
 * keeps its mode. Main thread only; [release] before the player goes.
 */
class PlaybackSurface(private val exo: ExoPlayer, window: Window?, showing: Flow<Showing>, scope: CoroutineScope) {
    private val shape = MutableStateFlow(0f)
    /** The picture's shape (width / height, pixel aspect included); 0 until known. */
    val aspect: StateFlow<Float> = shape
    val audio = AudioTracks(exo)
    private val frameRate = window?.let { FrameRate(it, exo, showing, scope) }
    /** The frame rate measured from the frames; null until measured. */
    val fps: StateFlow<Float?> = frameRate?.measured ?: MutableStateFlow(null)

    private val sizes = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) shape.value = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
        }
    }

    init {
        exo.addListener(sizes)
    }

    fun release() {
        exo.removeListener(sizes)
        audio.release()
        frameRate?.detach()
    }
}

/** A codec's names: on the format chips ("E-AC3"), and as ffprobe gives them ("eac3"), the server's and the copy ranker's word. */
private class CodecName(val chip: String, val probe: String)

private val CODECS = mapOf(
    MimeTypes.VIDEO_H264 to CodecName("H.264", "h264"),
    MimeTypes.VIDEO_H265 to CodecName("HEVC", "hevc"),
    // HEVC underneath; its chip names the profile instead (qualityLabels).
    MimeTypes.VIDEO_DOLBY_VISION to CodecName("Dolby Vision", "hevc"),
    MimeTypes.VIDEO_AV1 to CodecName("AV1", "av1"),
    MimeTypes.VIDEO_VP9 to CodecName("VP9", "vp9"),
    MimeTypes.VIDEO_MPEG2 to CodecName("MPEG2", "mpeg2video"),
    MimeTypes.AUDIO_AAC to CodecName("AAC", "aac"),
    MimeTypes.AUDIO_AC3 to CodecName("AC3", "ac3"),
    MimeTypes.AUDIO_E_AC3 to CodecName("E-AC3", "eac3"),
    MimeTypes.AUDIO_E_AC3_JOC to CodecName("E-AC3", "eac3"),
    MimeTypes.AUDIO_MPEG to CodecName("MP3", "mp3"),
    MimeTypes.AUDIO_MPEG_L2 to CodecName("MP2", "mp2"),
    MimeTypes.AUDIO_TRUEHD to CodecName("TrueHD", "truehd"),
    // ffprobe calls every DTS kind "dts"; the ranker learns them as one family (audioFamily).
    MimeTypes.AUDIO_DTS to CodecName("DTS", "dts"),
    MimeTypes.AUDIO_DTS_HD to CodecName("DTS", "dts"),
    MimeTypes.AUDIO_DTS_EXPRESS to CodecName("DTS", "dts"),
    MimeTypes.AUDIO_DTS_X to CodecName("DTS", "dts"),
    MimeTypes.AUDIO_OPUS to CodecName("OPUS", "opus"),
)

/** The format chips' name for [mime] ("HEVC", "E-AC3"); an unknown one by its MIME subtype. */
fun chipName(mime: String?) = CODECS[mime]?.chip ?: mime?.substringAfter('/')?.uppercase() ?: "?"

/** ffprobe's name for [mime] ("hevc", "eac3"); null for one the table doesn't know. */
fun probeName(mime: String?) = CODECS[mime]?.probe
