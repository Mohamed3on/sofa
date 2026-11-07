package com.mohamed3on.iptv.player

import com.mohamed3on.iptv.data.Channel
import java.util.Locale

/** A feed change [Downshift] asks for: a lighter feed of the family, or back to the one it left. */
sealed interface Shift {
    val to: Channel

    /** The line carried [network] Mbps of the [stream] Mbps the feed needed. */
    data class Down(override val to: Channel, val network: Float, val stream: Float) : Shift {
        /** What the health reason says while the lighter feed plays. */
        val reason get() = String.format(Locale.US, "Lighter feed · network %.1f of %.1f Mbps", network, stream)
    }
    data class Up(override val to: Channel) : Shift
}

/**
 * Steps a live channel down to a lighter feed of its family when the line can't carry the one playing,
 * before it stalls, and back up once the lighter one has held (#4). Down: the line behind the stream for
 * [BEHIND_MS], or a rebuffer with the line slower than the stream, moves to the best-ranked feed whose
 * measured bitrate fits [FIT] of the line (the stream's rate learnt over viewings, else the one measured now, which reads low on a line that never kept up), or, never measured, has a lower resolution. Up: a live stream
 * arrives at its own bitrate, so the line's spare speed can't be read while a lighter feed plays; after
 * [UP_AFTER_MS] without falling behind it tries the original again, and an up that falls behind within
 * [UP_HELD_MS] doubles that wait. At most one step down per [DOWN_GAP_MS], except straight after an up.
 */
class Downshift(private val now: () -> Long) {
    private var family: String? = null
    /** The feed playing before the first step down, while on a lighter one. */
    private var original: Channel? = null
    private var playing: Int? = null
    private var behindSince: Long? = null
    private var steadySince: Long? = null
    private var lastDown: Long? = null
    private var lastUp: Long? = null
    private var upAfter = UP_AFTER_MS

    /** A health reading of [current] (a member of [members], best first); the move to make, if any. */
    fun next(current: Channel, members: List<Channel>, health: Health, usable: (Channel) -> Boolean): Shift? {
        follow(current)
        val t = now()
        behindSince = if (health.behind) behindSince ?: t else null
        steadySince = if (health.behind || health.level == Health.Level.Bad) null else steadySince ?: t
        val since = behindSince
        if (since != null && t - since >= BEHIND_MS) return down(current, members, health.network, current.mbps ?: health.stream, usable)
        val back = original
        val held = steadySince?.let { t - it >= upAfter } == true
        if (back != null && back.id != current.id && held && usable(back)) {
            lastUp = t
            return Shift.Up(back).also { forget() }
        }
        return null
    }

    /** A rebuffer of [current]: a step down at once when the line was slower than the stream. */
    fun rebuffered(current: Channel, members: List<Channel>, network: Float?, measured: Float?, usable: (Channel) -> Boolean): Shift? {
        follow(current)
        val stream = current.mbps ?: measured
        return if (network != null && stream != null && network < stream) down(current, members, network, stream, usable) else null
    }

    /** Wraps a reading of a new family (a new tune) or a feed the session moved to by itself. */
    private fun follow(current: Channel) {
        if (current.family != family) {
            family = current.family
            original = null
            lastDown = null
            lastUp = null
            upAfter = UP_AFTER_MS
        }
        if (current.id != playing) {
            playing = current.id
            behindSince = null
            steadySince = null
        }
    }

    private fun down(current: Channel, members: List<Channel>, network: Float?, stream: Float?, usable: (Channel) -> Boolean): Shift? {
        if (network == null || stream == null) return null
        val t = now()
        val afterUp = lastUp?.let { t - it < UP_HELD_MS } == true
        if (!afterUp && lastDown?.let { t - it < DOWN_GAP_MS } == true) return null
        val to = members.firstOrNull { it.id != current.id && usable(it) && lighter(it, current, network, stream) } ?: return null
        if (afterUp) upAfter = (upAfter * 2).coerceAtMost(UP_MAX_MS)
        if (original == null) original = current
        lastDown = t
        behindSince = null
        return Shift.Down(to, network, stream)
    }

    private fun lighter(m: Channel, current: Channel, network: Float, stream: Float): Boolean {
        val mbps = m.mbps
        if (mbps != null) return mbps <= FIT * network && mbps < stream
        val h = m.h
        val ch = current.h
        return h != null && ch != null && h < ch
    }

    private fun forget() {
        original = null
        behindSince = null
        steadySince = null
    }

    private companion object {
        const val BEHIND_MS = 10_000L
        const val FIT = 0.8f
        const val DOWN_GAP_MS = 5 * 60_000L
        const val UP_AFTER_MS = 15 * 60_000L
        const val UP_MAX_MS = 2 * 60 * 60_000L
        const val UP_HELD_MS = 5 * 60_000L
    }
}
