package com.mohamed3on.iptv.player

import com.mohamed3on.iptv.data.KeyValueStore
import java.time.Instant
import java.time.ZoneId

/**
 * What a film session knows about the line (KTD8): the film session's input, faked in its tests.
 * Mbps, null when there is nothing to go on (the pick is then optimistic and step-down corrects it).
 */
interface Bandwidth {
    /** Typical capacity from [source] in the hour band of wall time [at]. */
    fun typicalMbps(source: Capacity.Source, at: Long): Float?
    /** This session's capacity: the median of its last few loading bursts. */
    val sessionMbps: Float?
}

/**
 * Typical capacity per source, hour band and route (VPN on or off, whose speed differs a lot), learnt from film
 * sessions: each session leaves one sample per (source, band), the median of its loading bursts there; the last
 * [KEEP] per bucket are kept, and the typical capacity is their median, so one bad film can't decide the next pick
 * without the pick turning timid (owner: "don't be too conservative").
 */
class Capacity(
    private val store: KeyValueStore,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** Whether the stick's VPN is on now: VPN sessions are learnt and read apart from the home line's. */
    private val vpn: () -> Boolean = { false },
) {
    /** [key] names its saved samples: the debrid's keep the name they were learnt under. */
    enum class Source(val key: String) { Iptv("Iptv"), Debrid("RealDebrid") }

    /** Local hours: night 01–09, day 09–18, evening peak 18–01. */
    enum class Band { Night, Day, Evening }

    fun band(at: Long): Band = when (Instant.ofEpochMilli(at).atZone(zone).hour) {
        in 1..8 -> Band.Night
        in 9..17 -> Band.Day
        else -> Band.Evening
    }

    fun typicalMbps(source: Source, at: Long): Float? = samples(source, band(at)).sorted().let { it.getOrNull((it.size - 1) / 2) }

    /** Ends a session: one sample per bucket its [bursts] fell in. Returns what it stored, for the log. */
    fun record(bursts: List<Burst>): Map<Pair<Source, Band>, Float> =
        bursts.groupBy({ it.source to band(it.at) }, Burst::mbps).mapValues { (bucket, rates) ->
            median(rates).also { put(bucket.first, bucket.second, (samples(bucket.first, bucket.second) + it).takeLast(KEEP)) }
        }

    private fun key(source: Source, band: Band) = "capacity.${source.key}.${band.name}" + if (vpn()) ".vpn" else ""
    private fun samples(source: Source, band: Band) = store.get(key(source, band))?.split(',')?.mapNotNull(String::toFloatOrNull).orEmpty()
    private fun put(source: Source, band: Band, mbps: List<Float>) = store.put(key(source, band), mbps.joinToString(","))

    private companion object {
        const val KEEP = 20
    }
}

/** One loading burst: when it started, from which source, and how fast it came in. */
data class Burst(val at: Long, val source: Capacity.Source, val mbps: Float)

/**
 * Finds a film session's loading bursts in once-a-second readings (wall-clock ms, the player's bytes so
 * far, and whether the loader wanted data then, see [FilmLoad.filling]). A second counts when the loader
 * wanted data at both ends: it was filling as fast as the line allows, not topping up a full buffer. A
 * run of such seconds is a burst; one shorter than [MIN_MS] is ignored, and one longer than [MAX_MS] is
 * split, so a recovering line shows in [sessionMbps] within a minute or so.
 */
class BandwidthMeter(private val capacity: Capacity) : Bandwidth {
    private val _bursts = mutableListOf<Burst>()
    val bursts: List<Burst> get() = _bursts
    private var last: Reading? = null
    private var start: Reading? = null

    private class Reading(val at: Long, val bytes: Long, val filling: Boolean, val source: Capacity.Source)

    override fun typicalMbps(source: Capacity.Source, at: Long) = capacity.typicalMbps(source, at)

    override val sessionMbps get() = _bursts.takeLast(RECENT).takeIf { it.isNotEmpty() }?.let { median(it.map(Burst::mbps)) }

    fun next(at: Long, bytes: Long, filling: Boolean, source: Capacity.Source) {
        val now = Reading(at, bytes, filling, source)
        val prev = last
        last = now
        // A new copy (or player) starts the readings afresh.
        if (prev == null || source != prev.source || bytes < prev.bytes || !(filling && prev.filling)) return close(prev)
        if (start == null) start = prev
        if (at - start!!.at >= MAX_MS) {
            close(now)
            start = now
        }
    }

    /** Ends the session: stores its samples and returns them. */
    fun finish(): Map<Pair<Capacity.Source, Capacity.Band>, Float> {
        close(last)
        return capacity.record(_bursts)
    }

    private fun close(end: Reading?) {
        val s = start ?: return
        start = null
        if (end == null || end.at - s.at < MIN_MS) return
        _bursts += Burst(s.at, s.source, (end.bytes - s.bytes) * 8f / (end.at - s.at) / 1_000f)
    }

    private companion object {
        const val MIN_MS = 2_000L
        const val MAX_MS = 30_000L
        const val RECENT = 3
    }
}

private fun median(values: List<Float>): Float = values.sorted().let { s ->
    val mid = s.size / 2
    if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
}
