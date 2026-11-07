package com.mohamed3on.iptv.player

import android.os.SystemClock
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import com.mohamed3on.iptv.data.Channel
import com.mohamed3on.iptv.data.Route
import com.mohamed3on.iptv.data.RouteKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.math.roundToInt

/** What the playing stream really is, measured from it: the banner's badges come from this. */
data class LiveQuality(val width: Int, val height: Int, val fps: Float?, val codec: String, val atmos: Boolean, val surround: Boolean) {
    /** Same rules as the server's probed badges (api/_app-feed.ts). */
    val badges: List<String>
        get() = buildList {
            if (height >= 2160) add("4K") else if (height >= 1080) add("FHD")
            fps?.takeIf { it >= 49f }?.let { add("${it.roundToInt()}fps") }
            if (atmos) add("Atmos") else if (surround) add("5.1")
        }
}

/** The playing stream's quality from the player; null until it has a video format. */
fun liveQuality(exo: ExoPlayer, fps: Float?): LiveQuality? {
    val v = exo.videoFormat?.takeIf { it.width > 0 && it.height > 0 } ?: return null
    // Named as ffprobe names them, so app readings and probe readings compare in the cache.
    val codec = probeName(v.sampleMimeType) ?: v.sampleMimeType?.substringAfter('/') ?: "unknown"
    val audio = exo.audioFormat
    return LiveQuality(v.width, v.height, fps, codec, audio?.sampleMimeType == MimeTypes.AUDIO_E_AC3_JOC, (audio?.channelCount ?: 0) >= 6)
}

/**
 * Reports what happens while watching to the app's own server: tunes with their time to
 * picture, stalls, feed switches and problems (/api/app/events), and each stream's measured
 * quality (/api/app/quality, merged into the quality cache there). Best effort: a failed send
 * is dropped, never retried, and nothing here can affect playback. No URLs are logged.
 */
class Telemetry(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val token: String,
    private val scope: CoroutineScope,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private val queue = ArrayList<JsonObject>()
    private val qualityReported = HashSet<Int>()
    private val bitrateReported = HashSet<Int>()

    init {
        scope.launch {
            while (true) {
                delay(FLUSH_MS)
                flush()
            }
        }
    }

    /** Derives events from a session's state changes; no hooks inside the engine. */
    fun observe(session: StreamSession, route: Route, tile: Int = 0): Job = scope.launch {
        var prev = SessionState()
        var tunedAt = now()
        var shown = false // the current tune has reached a picture
        session.state.collect { s ->
            val before = prev
            val channel = s.requested
            if (channel?.identity != before.requested?.identity) {
                tunedAt = now()
                shown = false
            }
            val feed = s.feed
            val routeName = route.effective.value.name.lowercase()
            when {
                s.phase == Phase.Playing && before.phase != Phase.Playing && !shown && channel != null && feed != null -> {
                    shown = true
                    record("tune") {
                        put("id", feed.id); put("channel", channel.id); put("group", channel.group)
                        put("rank", feed.rank); put("route", routeName); put("ms", now() - tunedAt); put("tile", tile)
                    }
                }
                channel != null && channel.identity == before.requested?.identity && feed != null && before.feed != null && feed.id != before.feed.id && feed.family == before.feed.family ->
                    record("failover") { put("from", before.feed.id); put("to", feed.id); put("playing", before.phase == Phase.Playing) }
                before.phase == Phase.Playing && s.phase == Phase.Connecting && feed != null && feed.id == before.feed?.id ->
                    record("stall") { put("id", feed.id) }
                s.phase != before.phase && s.phase.problem() != null ->
                    record("problem") {
                        put("kind", s.phase.problem()); put("id", (feed ?: channel)?.id ?: 0); put("route", routeName)
                    }
            }
            prev = s
        }
    }

    /**
     * A rebuffer while [feed] played, with the line's speed and the stream's bitrate in Mbps when
     * measured, and the cushion it waits for before playing again.
     */
    fun rebuffer(feed: Channel, network: Float?, stream: Float?, route: RouteKind, cushionMs: Long) = record("rebuffer") {
        put("id", feed.id); put("route", route.name.lowercase()); put("cushionMs", cushionMs)
        network?.let { put("netMbps", it) }
        stream?.let { put("streamMbps", it) }
    }

    /** A film session's copy change or answer ([FilmSession]'s report). */
    fun film(e: FilmEvent) = when (e) {
        is FilmEvent.Failover -> record("failover") {
            put("copy", e.copy.id); put("failure", e.failure.toString()); put("permanent", e.permanent); put("playing", e.playing)
        }
        is FilmEvent.StepDown -> record("stepdown") { put("from", e.from.id); put("to", e.to.id) }
        is FilmEvent.Offer -> record("offer") { put("kind", e.kind.name.lowercase()); put("copy", e.copy.id); put("accepted", e.accepted) }
        is FilmEvent.Trial -> record("trial") { put("copy", e.copy.id); put("works", e.works) }
    }

    /**
     * Sends [feed]'s measured quality once per app run, once its frame rate is known; [onStored] runs
     * when the server kept it, since the feed order may have changed.
     */
    fun reportQuality(feed: Channel, q: LiveQuality, onStored: () -> Unit) {
        val fps = q.fps ?: return
        if (!qualityReported.add(feed.id)) return
        val body = buildJsonObject {
            put("id", feed.id); put("name", feed.name); put("w", q.width); put("h", q.height)
            put("fps", fps); put("codec", q.codec); put("atmos", q.atmos); put("surround", q.surround)
        }
        post("quality", body.toString()) { if ("\"stored\":true" in it) onStored() }
    }

    /** Sends [feed]'s measured bitrate (Mbps) once per app run; the server keeps a recent median per feed. */
    fun reportBitrate(feed: Channel, mbps: Float) {
        if (!bitrateReported.add(feed.id)) return
        post("bitrate", buildJsonObject { put("id", feed.id); put("mbps", mbps) }.toString())
    }

    private fun record(type: String, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        val event = buildJsonObject { put("type", type); fields() }
        val full = synchronized(queue) {
            if (queue.size < MAX_QUEUE) queue += event
            queue.size >= BATCH
        }
        if (full) scope.launch { flush() }
    }

    private fun flush() {
        val batch = synchronized(queue) { queue.toList().also { queue.clear() } }
        if (batch.isNotEmpty()) post("events", JsonArray(batch).toString())
    }

    private fun post(route: String, json: String, onReply: (String) -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                http.newCall(
                    Request.Builder().url("$baseUrl/api/app/$route?t=$token").post(json.toRequestBody(JSON)).build(),
                ).execute().use { if (it.isSuccessful) onReply(it.body.string()) }
            }
        }
    }

    private fun Phase.problem(): String? = when (this) {
        Phase.Busy -> "busy"
        Phase.GaveUp -> "gaveUp"
        is Phase.WrongRoute -> "wrongRoute"
        Phase.Offline -> "offline"
        else -> null
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val FLUSH_MS = 60_000L
        const val BATCH = 20
        const val MAX_QUEUE = 200
    }
}
