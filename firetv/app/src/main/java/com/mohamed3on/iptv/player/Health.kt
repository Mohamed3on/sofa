package com.mohamed3on.iptv.player

import java.util.Locale

/**
 * How the playing stream is doing: the health dot's [level] and, unless it is [Level.Good], why.
 * [corner] says whether it is worth showing unasked (a spare feed is not). The numbers feed the
 * info view: the line's speed and the stream's bitrate in Mbps (null until measured) and the
 * seconds of picture buffered ahead. [behind]: less picture arriving than time passing, the line too slow for the stream.
 */
data class Health(
    val level: Level,
    val reason: String? = null,
    val corner: Boolean = level != Level.Good,
    val network: Float? = null,
    val stream: Float? = null,
    val buffered: Float = 0f,
    val behind: Boolean = false,
) {
    enum class Level { Good, Warn, Bad }
}

/** Where the session is with the stream a reading was taken from. */
enum class StreamPhase {
    /** A fresh tune: the banner already says it is tuning. */
    Tuning,
    Playing,
    /** Reopening a stream that was playing (a stall or a failover). */
    Reconnecting,
}

/** One reading of the player, taken each second. Times and media in ms. */
data class HealthSample(
    val at: Long,
    val phase: StreamPhase,
    /** The feed playing; a new one starts the readings afresh. */
    val feedId: Int,
    /** Playing but out of picture to show. */
    val buffering: Boolean,
    val position: Long,
    val buffered: Long,
    val bytes: Long,
    val spare: Boolean,
    val plainRoute: Boolean,
    /** The picture a rebuffer waits for when stalls raised it above the usual ([Cushion]); null at the usual. */
    val cushionMs: Long? = null,
    /** Why a lighter feed plays ([Downshift]), said in place of "On a spare feed"; null when it does not. */
    val lighter: String? = null,
)

/**
 * Turns per-second readings into [Health]. Buffering now is bad; a network falling behind the stream,
 * a recent rebuffer or a spare feed is a warning. "Behind the stream" compares how much picture
 * arrived with how much time passed over the last [windowMs]: the same bytes measured in seconds of
 * picture and in seconds of clock give both the stream's bitrate and the line's speed, so neither
 * has to be known in advance. [onRebuffer] hears each rebuffer with those two numbers (Mbps).
 */
class HealthModel(
    private val windowMs: Long = 10_000,
    private val recentMs: Long = 120_000,
    private val onRebuffer: (network: Float?, stream: Float?) -> Unit = { _, _ -> },
) {
    private val window = ArrayDeque<HealthSample>()
    private var feedId: Int? = null
    private var wasBuffering = false
    private var lastRebuffer: Long? = null
    /** When this feed started playing: a stream settling in its first moments is not a rebuffer. */
    private var playingSince: Long? = null
    /** The stream's bitrate while it kept up: what it needs when it no longer does. */
    private var streamMbps: Float? = null

    fun next(s: HealthSample): Health? {
        if (s.feedId != feedId) {
            feedId = s.feedId
            window.clear()
            wasBuffering = false
            lastRebuffer = null
            playingSince = null
            streamMbps = null
        }
        when (s.phase) {
            StreamPhase.Tuning -> {
                window.clear()
                playingSince = null
                return null
            }
            StreamPhase.Reconnecting -> {
                window.clear() // a reopened stream starts its position again
                playingSince = null
                return Health(Health.Level.Bad, "Reconnecting", stream = streamMbps)
            }
            StreamPhase.Playing -> {}
        }
        val since = playingSince ?: s.at.also { playingSince = it }
        val settling = s.at - since < SETTLE_MS
        window.addLast(s)
        while (window.first().at < s.at - windowMs) window.removeFirst()

        val first = window.first()
        val wall = s.at - first.at
        val bytes = s.bytes - first.bytes
        val media = (s.position + s.buffered) - (first.position + first.buffered)
        val measured = wall >= MIN_SPAN_MS
        val network = if (measured) mbps(bytes, wall) else null
        // Keeping up: as much picture arrived as time passed (or the buffer is full, so loading pauses).
        val keepingUp = !measured || media >= wall * KEEP_UP || s.buffered >= FULL_MS
        if (measured && media > 0 && media >= wall * KEEP_UP) streamMbps = mbps(bytes, media)
        val stream = streamMbps
        val starved = measured && window.takeLast(STARVED_TICKS).let { it.size == STARVED_TICKS && it.last().bytes == it.first().bytes }

        if (s.buffering && !wasBuffering && !settling) {
            lastRebuffer = s.at
            onRebuffer(network, stream)
        }
        wasBuffering = s.buffering
        val slowLine = network != null && stream != null && network < stream
        val vpn = if (slowLine && s.plainRoute) " · VPN may help" else ""
        val numbers = if (network != null && stream != null) "network ${fmt(network)} of ${fmt(stream)} Mbps" else null

        fun health(level: Health.Level, reason: String?, corner: Boolean = level != Health.Level.Good) =
            Health(level, reason, corner, network, stream, s.buffered / 1000f, behind = !keepingUp)

        return when {
            s.buffering && settling -> health(Health.Level.Good, null)
            s.buffering -> health(
                Health.Level.Bad,
                when {
                    starved -> "Buffering · no data from the provider"
                    s.cushionMs != null -> "Buffering · building a ${s.cushionMs / 1000} s cushion$vpn"
                    slowLine -> "Buffering · $numbers$vpn"
                    else -> "Buffering"
                },
            )
            !keepingUp && numbers != null -> health(Health.Level.Warn, "Network behind the stream · $numbers$vpn")
            lastRebuffer?.let { s.at - it < recentMs } == true -> {
                val minutes = (s.at - lastRebuffer!!) / 60_000
                health(Health.Level.Warn, if (minutes < 1) "Buffered just now" else "Buffered $minutes min ago")
            }
            s.spare -> health(Health.Level.Warn, s.lighter ?: "On a spare feed", corner = false)
            else -> health(Health.Level.Good, null)
        }
    }

    private companion object {
        /** Readings this far apart before rates mean anything. */
        const val MIN_SPAN_MS = 4_000L
        /** Picture arriving at under this share of real time is falling behind. */
        const val KEEP_UP = 0.9
        /** With this much buffered, ExoPlayer may pause loading: not a slow line. */
        const val FULL_MS = 8_000L
        /** A stream's first moments of playing, when a brief rebuffer is it settling, not trouble. */
        const val SETTLE_MS = 5_000L
        /** Seconds with no bytes at all before it is the provider, not the line. */
        const val STARVED_TICKS = 3

        fun mbps(bytes: Long, ms: Long) = bytes * 8f / ms / 1000f

        fun fmt(mbps: Float) = String.format(Locale.US, "%.1f", mbps)
    }
}
