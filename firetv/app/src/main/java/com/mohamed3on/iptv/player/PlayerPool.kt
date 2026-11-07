@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.mohamed3on.iptv.player

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicLong

/** Tune-to-first-frame timings, one line per tune (no URLs). */
private const val ZAP_TAG = "IptvZap"

/**
 * Long-lived ExoPlayers reused across zaps (KTD7): one for single view; multi-view takes one per
 * tile with [acquire]. Players are built in onStart and all released in onStop ([releaseAll]).
 * Live streams go through one shared HTTP client (OkHttp follows the provider's cross-host,
 * https-to-http redirects; the network security config allows the cleartext).
 */
class PlayerPool(private val context: Context, private val http: OkHttpClient) {
    private val players = mutableListOf<ExoStreamPlayer>()

    /** A multi-view [tile] buffers less: up to three streams share the line and the stick's memory. */
    fun acquire(tile: Boolean = false): ExoStreamPlayer {
        val cushion = Cushion()
        val exo = ExoPlayer.Builder(context)
            .setLoadControl(
                CushionLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(
                            if (tile) TILE_MIN_BUFFER_MS else MIN_BUFFER_MS,
                            if (tile) TILE_MAX_BUFFER_MS else MAX_BUFFER_MS,
                            START_BUFFER_MS,
                            Cushion.BASE_MS.toInt(),
                        )
                        .build(),
                    cushion,
                )
            )
            .build()
        // Each player counts its own bytes, so a stream's download rate is its own (stream health).
        val bytes = ByteCounter()
        val liveSources = ProgressiveMediaSource.Factory(OkHttpDataSource.Factory(http).setTransferListener(bytes), ExtractorsFactory { arrayOf(liveTs()) })
            // The session owns every retry (KTD8); ExoPlayer's own retries would hide a 458 for seconds.
            .setLoadErrorHandlingPolicy(NoRetry)
        return ExoStreamPlayer(exo, liveSources, bytes, cushion).also { players += it }
    }

    fun release(player: ExoStreamPlayer) {
        players -= player
        player.exo.release()
    }

    fun releaseAll() = players.toList().forEach(::release)

    /**
     * After a stall, waits for the [cushion] to fill before playing again; everything else is [base]'s.
     * Kotlin's `by` skips Java default methods, so each one DefaultLoadControl overrides is forwarded here.
     */
    private class CushionLoadControl(private val base: DefaultLoadControl, private val cushion: Cushion) : LoadControl by base {
        override fun shouldStartPlayback(parameters: LoadControl.Parameters) =
            base.shouldStartPlayback(parameters) &&
                (!parameters.rebuffering || parameters.bufferedDurationUs >= cushion.resumeMs(SystemClock.elapsedRealtime()) * 1000)

        override fun onPrepared(playerId: PlayerId) = base.onPrepared(playerId)
        override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray, trackSelections: Array<ExoTrackSelection>) =
            base.onTracksSelected(parameters, trackGroups, trackSelections)
        override fun onStopped(playerId: PlayerId) = base.onStopped(playerId)
        override fun onReleased(playerId: PlayerId) = base.onReleased(playerId)
        override fun getBackBufferDurationUs(playerId: PlayerId) = base.getBackBufferDurationUs(playerId)
        override fun retainBackBufferFromKeyframe(playerId: PlayerId) = base.retainBackBufferFromKeyframe(playerId)
        override fun shouldContinueLoading(parameters: LoadControl.Parameters) = base.shouldContinueLoading(parameters)
        override fun shouldContinuePreloading(playerId: PlayerId, timeline: Timeline, mediaPeriodId: MediaSource.MediaPeriodId, bufferedDurationUs: Long) =
            base.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)
    }

    private object NoRetry : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo) = C.TIME_UNSET
    }

    companion object {
        private const val MIN_BUFFER_MS = 10_000
        const val MAX_BUFFER_MS = 30_000
        private const val START_BUFFER_MS = 500
        private const val TILE_MIN_BUFFER_MS = 4_000
        private const val TILE_MAX_BUFFER_MS = 8_000

        /** The TS extractor as Media3's default factory builds it, minus the sniffing of every other container. */
        fun liveTs() = TsExtractor(
            TsExtractor.MODE_SINGLE_PMT, 0, DefaultSubtitleParserFactory(), TimestampAdjuster(0),
            DefaultTsPayloadReaderFactory(0, emptyList()), TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES,
        )
    }
}

/** A player multi-view can mute: only the selected tile is heard (R18). */
interface TilePlayer : StreamPlayer {
    fun setVolume(volume: Float)
}

/**
 * A pooled ExoPlayer as a [StreamPlayer]. [exo] is exposed for the surface, audio tracks (U14)
 * and, later, VOD: live specifics live in the per-item media source, not in the player (R11).
 */
class ExoStreamPlayer internal constructor(
    val exo: ExoPlayer,
    private val liveSources: MediaSource.Factory,
    private val bytes: ByteCounter,
    /** Grows with each rebuffer it is told of; nothing tells a multi-view tile's, so tiles keep the usual one. */
    val cushion: Cushion,
) : TilePlayer {
    /** Bytes of stream received since the player was built; read from any thread. */
    override val bytesLoaded get() = bytes.total.get()

    private var listener: StreamPlayer.Listener? = null
    /** When the last [open] ran, for the zap timing log; 0 once its first frame is logged. */
    private var openedAt = 0L
    private var playingAt = 0L
    private var openUrl: String? = null

    init {
        exo.playWhenReady = true
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && playingAt != 0L) {
                    Log.i(ZAP_TAG, "playing ${SystemClock.elapsedRealtime() - playingAt} ms")
                    playingAt = 0L
                }
                if (isPlaying) listener?.onPlaying()
            }

            override fun onRenderedFirstFrame() {
                if (openedAt == 0L) return
                Log.i(ZAP_TAG, "first frame ${SystemClock.elapsedRealtime() - openedAt} ms")
                openedAt = 0L
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) listener?.onEnded()
            }

            override fun onPlayerError(error: PlaybackException) {
                listener?.onError(StreamErrors.failureOf(error))
            }
        })
    }

    override fun open(url: String) {
        openedAt = SystemClock.elapsedRealtime()
        playingAt = openedAt
        // Reopening the same feed after a stall keeps its cushion; another feed starts afresh.
        if (url != openUrl) cushion.onTune()
        openUrl = url
        exo.stop()
        val item = MediaItem.Builder().setUri(url).setMimeType(MimeTypes.VIDEO_MP2T).build()
        exo.setMediaSource(liveSources.createMediaSource(item))
        exo.prepare()
    }

    override fun stop() = exo.stop()

    override val position get() = exo.currentPosition

    override fun setVolume(volume: Float) {
        exo.volume = volume
    }

    override fun setListener(listener: StreamPlayer.Listener?) {
        this.listener = listener
    }
}

/** Counts the bytes a player's streams deliver into [total] (on the loader thread): a live player's and a film's. */
internal open class ByteCounter(val total: AtomicLong = AtomicLong()) : TransferListener {
    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
        total.addAndGet(bytesTransferred.toLong())
    }
    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
}
