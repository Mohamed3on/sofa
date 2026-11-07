package com.mohamed3on.iptv.player

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import com.mohamed3on.iptv.data.TitlesClient
import com.mohamed3on.iptv.data.FoundSubtitle
import com.mohamed3on.iptv.data.TitleId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A line of an SRT, shown from [start] to [end] (ms into the film). */
data class SrtCue(val start: Long, val end: Long, val text: String)

private val TIMES = Regex("""(\d+):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d+):(\d{2}):(\d{2})[,.](\d{3})""")
// Android's ICU regex rejects a bare "}" (the JVM's doesn't): escaped, or the first fetched file crashes the app.
private val TAGS = Regex("""<[^>]+>|\{\\[^}]*\}""")

/** An SRT's cues in start order, their formatting tags dropped. */
fun parseSrt(srt: String): List<SrtCue> = srt.removePrefix("﻿").replace("\r", "").split(Regex("""\n\s*\n""")).mapNotNull { block ->
    val lines = block.trim().lines()
    val i = lines.indexOfFirst(TIMES::containsMatchIn).takeIf { it >= 0 } ?: return@mapNotNull null
    val t = TIMES.find(lines[i])!!.groupValues.drop(1).map(String::toLong)
    fun ms(at: Int) = ((t[at] * 60 + t[at + 1]) * 60 + t[at + 2]) * 1000 + t[at + 3]
    val text = lines.drop(i + 1).joinToString("\n").replace(TAGS, "").trim()
    if (text.isEmpty()) null else SrtCue(ms(0), ms(4), text)
}.sortedBy { it.start }

/** The text showing at [position] with every cue moved [offset] ms later (earlier when negative); null between lines. */
fun List<SrtCue>.at(position: Long, offset: Long = 0): String? {
    val t = position - offset
    return filter { t >= it.start && t < it.end }.joinToString("\n") { it.text }.ifEmpty { null }
}

/** The OpenSubtitles hash: the file's size plus its first and last 64 KB as little-endian 64-bit words, in 16 hex digits. */
fun osHash(head: ByteArray, tail: ByteArray, size: Long): String {
    var h = size
    for (part in listOf(head, tail)) {
        val words = ByteBuffer.wrap(part).order(ByteOrder.LITTLE_ENDIAN)
        while (words.remaining() >= 8) h += words.long
    }
    return "%016x".format(h)
}

/**
 * Subtitles from the box for a copy with no English ones (R30, KTD18). A copy's own English track shows as soon as its
 * tracks are known, with no lookup; else it is looked up then: its OpenSubtitles hash from two 64 KB range reads in
 * parallel, then the box's list, and the best English one shows at once: subtitles are always on by default ([onPicked] hears it). The app draws the lines
 * from the file's cues at the player's position ([at]), so loading a file or moving its timing never touches
 * playback. Main thread, like the player.
 */
class SubtitleFetch(
    private val player: Player,
    private val http: OkHttpClient,
    private val sync: TitlesClient,
    private val scope: CoroutineScope,
    /** The film's original language (ISO 639-1) when its audio track doesn't say. */
    private val original: () -> String?,
    private val onPicked: (FoundSubtitle) -> Unit,
) {
    enum class Status { Idle, Searching, Found, None, NoneToday }

    /** [offset] ms moves the [selected] file's [cues] later (earlier when negative). */
    data class State(
        val status: Status = Status.Idle,
        val subs: List<FoundSubtitle> = emptyList(),
        val selected: FoundSubtitle? = null,
        val cues: List<SrtCue> = emptyList(),
        val offset: Long = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private var copy: String? = null
    private var decided = false
    /** A fetched file showed on the copy before: an English track in the next one shows instead. */
    private var wanted = false
    private var finding: Job? = null
    /** The open copy's lookup, run once its tracks are known and none is English. */
    private var lookup: (() -> Unit)? = null
    private var loading: Job? = null

    private val listener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) = decide()
    }

    init {
        player.addListener(listener)
    }

    /** The copy [copyId] opened at [url] (release [name]): looks its subtitles up, unless it is the same copy on a fresh link. */
    fun find(title: TitleId, copyId: String, url: String, name: String) {
        if (copyId == copy) return
        copy = copyId
        decided = false
        wanted = _state.value.selected != null
        finding?.cancel()
        loading?.cancel()
        _state.value = State(Status.Searching)
        lookup = {
            finding = scope.launch {
                val (hash, size) = hashOf(url) ?: ("" to 0L)
                val answer = sync.subtitles(title, hash, size, name).getOrNull()
                val status = when {
                    answer?.status == "quota" -> Status.NoneToday
                    answer == null || answer.subs.isEmpty() -> Status.None
                    else -> Status.Found
                }
                _state.value = State(status, answer?.subs.orEmpty())
                decide()
            }
        }
    }

    /** Shows [sub] (the file's own tracks off meanwhile), or none for null. */
    fun select(sub: FoundSubtitle?) {
        decided = true
        loading?.cancel()
        if (sub == null) {
            _state.update { it.copy(selected = null, cues = emptyList(), offset = 0) }
            return
        }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        loading = scope.launch {
            sync.subtitle(sub.id).onSuccess { srt ->
                if (srt == null) _state.update { it.copy(status = Status.NoneToday) }
                else {
                    val cues = withContext(Dispatchers.Default) { parseSrt(srt) }
                    // The lines on screen stay until the new file's take over; its timing starts from its own.
                    _state.update { it.copy(selected = sub, cues = cues, offset = 0) }
                }
            }
        }
    }

    /** Moves the lines [by] ms later (earlier when negative). */
    fun shift(by: Long) = _state.update { it.copy(offset = it.offset + by) }

    fun release() {
        player.removeListener(listener)
        finding?.cancel()
        loading?.cancel()
    }

    private fun decide() {
        val groups = player.currentTracks.groups
        if (decided || groups.isEmpty()) return
        // Subtitles are on by default (owner's choice): the file's own English track at once, with no lookup.
        if (groups.any { g -> g.type == C.TRACK_TYPE_TEXT && (0 until g.length).any { g.getTrackFormat(it).language?.startsWith("en") == true } }) {
            decided = true
            lookup = null
            _state.value = State()
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage("en").build()
            return
        }
        lookup?.let { lookup = null; return it() }
        val status = _state.value.status
        if (status == Status.Idle || status == Status.Searching) return
        decided = true
        val found = _state.value.subs.firstOrNull()
        // Only English switches on by itself; Arabic is there to pick in Options.
        if (found == null || !found.lang.startsWith("en")) return
        select(found)
        if (!wanted) onPicked(found)
    }

    /**
     * The file's OpenSubtitles hash and size, from its first 64 KB and then its last (asked by offset: the
     * IPTV provider answers a suffix range with a 500); null when the server won't send ranges.
     */
    private suspend fun hashOf(url: String): Pair<String, Long>? = withContext(Dispatchers.IO) {
        val (h, size) = range(url, "bytes=0-${CHUNK - 1}") ?: return@withContext null
        if (size < CHUNK) return@withContext null
        val (t, _) = range(url, "bytes=${size - CHUNK}-${size - 1}") ?: return@withContext null
        osHash(h, t, size) to size
    }

    private fun range(url: String, range: String): Pair<ByteArray, Long>? = runCatching {
        http.newCall(Request.Builder().url(url).header("Range", range).build()).execute().use { res ->
            val size = res.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
            // A whole-file answer would download the film: only a 64 KB part will do.
            if (res.code != 206 || size == null || (res.body.contentLength() !in 1..CHUNK.toLong())) null else res.body.bytes() to size
        }
    }.getOrNull()

    private companion object {
        const val CHUNK = 65_536
    }
}
