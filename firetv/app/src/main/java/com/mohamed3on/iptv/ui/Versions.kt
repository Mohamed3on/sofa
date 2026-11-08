package com.mohamed3on.iptv.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.focusProperties
import com.mohamed3on.iptv.ui.theme.material
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.mohamed3on.iptv.data.DebridCheck
import com.mohamed3on.iptv.system.Check
import com.mohamed3on.iptv.data.DebridState
import com.mohamed3on.iptv.graph
import com.mohamed3on.iptv.player.Capacity.Source
import com.mohamed3on.iptv.player.Copy
import com.mohamed3on.iptv.player.EGYPTIAN
import com.mohamed3on.iptv.player.TIER_NAMES
import com.mohamed3on.iptv.player.audioName
import com.mohamed3on.iptv.player.pictureTier
import com.mohamed3on.iptv.player.Correction
import com.mohamed3on.iptv.player.Hdr
import com.mohamed3on.iptv.player.Passthrough
import com.mohamed3on.iptv.player.Ranked
import com.mohamed3on.iptv.player.Ranking
import com.mohamed3on.iptv.player.Support
import com.mohamed3on.iptv.player.TitleAnswer
import com.mohamed3on.iptv.player.DAY
import com.mohamed3on.iptv.player.Prepared
import java.time.Instant
import com.mohamed3on.iptv.ui.theme.ActivityLine
import com.mohamed3on.iptv.ui.theme.Palette
import com.mohamed3on.iptv.ui.theme.Space
import com.mohamed3on.iptv.ui.theme.FitText
import com.mohamed3on.iptv.ui.theme.Type
import com.mohamed3on.iptv.ui.theme.PanelRow
import com.mohamed3on.iptv.ui.theme.pressable
import com.mohamed3on.iptv.ui.theme.scrollbar
import kotlin.math.roundToInt

/** What Versions lists: a title's ranking, the debrid's answers, the box's answer (sizes, names) and the copy playing, if any. */
/** A title's [Prepared] copy plan for Versions, and the copy [playing] in the player. */
class VersionsData(val prepared: Prepared, val playing: String? = null) {
    val ranking get() = prepared.ranking
    val debrid get() = prepared.debrid
    val answer get() = prepared.answer
    val failed get() = prepared.failed
}

/** How a copy stands here, in the order Versions groups them (R11): its heading, and what that means when the name alone doesn't say. */
private enum class Standing(val heading: String, private val note: String?) {
    Plays("Ready to play", null),
    Untested("Might play", "Not tried on this TV yet"),
    NotReady("Can’t start now", "%s ready to stream"),
    Wont("Won’t play on this TV", null);
    /** [service] is "Debrid" while TorBox and Real-Debrid both serve: then both are named. */
    fun note(service: String) = note?.format(if (service == "Debrid") "TorBox and Real-Debrid don’t have it" else "$service doesn’t have it")
}

/** A cached debrid copy not checked yet stands with the copies that play: it likely does. */
private fun standing(r: Ranked, data: VersionsData) = when {
    r.support == Support.Wont -> Standing.Wont
    !data.prepared.playable(r.copy) -> Standing.NotReady
    r.support == Support.Unsure -> Standing.Untested
    else -> Standing.Plays
}

/** What tells copies apart at a glance: resolution, HDR kind, sound (Atmos, surround, stereo) and whether it speaks the title's language. */
private fun kind(r: Ranked) = r.copy.let { c -> listOf(c.resolution, c.hdr.firstOrNull(), if (c.atmos) 2 else if (c.channels >= 6) 1 else 0, r.original) }

/** Copies a group shows before "Show all". */
private const val SPREAD = 4

/**
 * Every copy of [title], grouped by how it stands on this setup, each with its labels, picture tier,
 * source, size and why it ranks where it does (R11, R12). OK plays a copy and pins it ([onChoose]); Menu on
 * a pinned or marked one clears that (R13), on any other marks it as not playing here. Below: what the setup reported and the typical bandwidth the
 * pick used, the corrections (R14) and the sources' states. [onChanged] re-ranks after any change.
 */
@Composable
internal fun Versions(title: String, data: VersionsData, first: FocusRequester, onChoose: (Copy) -> Unit, onChanged: () -> Unit, modifier: Modifier = Modifier) {
    val graph = LocalContext.current.graph
    val choices = graph.copyChoices
    val setup by graph.setup.profile.collectAsState()
    val debridStatus by graph.debrid.status.collectAsState()
    var all by remember { mutableStateOf(false) }
    val more = remember { FocusRequester() }
    var corrections by remember(setup) { mutableStateOf(choices.corrections(setup)) }
    var learnt by remember(setup, data.ranking) { mutableStateOf(choices.learnt(setup)) }
    val ranking = data.ranking
    // With a language chosen, the copies without it go last, in a group of their own (null): still there for the original.
    val rows = remember(ranking, data.debrid) {
        // A copy its debrid doesn't have ready can't start: not listed.
        ranking?.copies.orEmpty().filter { standing(it, data) != Standing.NotReady }
            .map { it to standing(it, data).takeIf { _ -> ranking?.language == null || it.original } }
            .sortedBy { it.second?.ordinal ?: Int.MAX_VALUE }
    }
    // A spread first: each group's best copy of each kind (so 1080p and SDR show beside the 4K remuxes), SPREAD at most,
    // and the copy pinned or playing; "Show all" has the rest.
    val spread = remember(rows) {
        rows.distinctBy { (r, s) -> s to kind(r) }.groupBy { it.second }.values.flatMap { it.take(SPREAD) }.mapTo(HashSet()) { it.first.copy.id } +
            rows.filter { (r, _) -> r.pinned || r.copy.id == data.playing }.map { it.first.copy.id }
    }
    // Reasons are worded against the copy playing, else the one Play opens, as the Plays line names it (#22).
    val plays = data.playing ?: data.prepared.opens(late = true)?.copy?.id
    val state = rememberLazyListState()
    val shown = if (all) rows else rows.filter { it.first.copy.id in spread }
    val firstMore = if (all) shown.firstOrNull { it.first.copy.id !in spread }?.first?.copy?.id else null
    // The list's index of shown copy [i]: one heading before each group's first copy.
    fun itemOf(i: Int) = i + (0..i).count { it == 0 || shown[it - 1].second != shown[it].second }
    // "Show all" leaves the list as it's pressed: focus moves on to the first copy it brought in, scrolled to first
    // (it can sit in an earlier group, off screen, where it isn't composed); else to the top copy, never nowhere.
    LaunchedEffect(all) {
        if (!all) return@LaunchedEffect
        val i = shown.indexOfFirst { it.first.copy.id == firstMore }
        if (i >= 0) state.scrollToItem(itemOf(i))
        withFrameNanos {}
        if (runCatching { more.requestFocus() }.isFailure) runCatching { first.requestFocus() }
    }
    // Menu clears a pin or mark and the row moves as the list re-ranks: focus follows it.
    var cleared by remember { mutableStateOf<String?>(null) }
    val clearedFocus = remember { FocusRequester() }
    LaunchedEffect(rows) {
        val id = cleared ?: return@LaunchedEffect
        val i = shown.indexOfFirst { it.first.copy.id == id }
        if (i < 0) runCatching { first.requestFocus() }
        else {
            if (state.layoutInfo.visibleItemsInfo.none { it.key == id }) state.scrollToItem(itemOf(i))
            withFrameNanos {}
            runCatching { clearedFocus.requestFocus() }
        }
        cleared = null
    }
    LazyColumn(modifier.scrollbar(state), state = state, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        if (ranking == null) {
            item(key = "loading") {
                Column(Modifier.padding(Space.l).focusRequester(first).focusable(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                    if (data.failed) Text("The box didn’t list copies. Play uses the library’s copy.", style = Type.body)
                    else {
                        Text("Finding copies…", style = Type.body)
                        ActivityLine()
                    }
                }
            }
        }
        shown.forEachIndexed { i, (r, s) ->
            if (i == 0 || shown[i - 1].second != s) item(key = "h${s?.name ?: "other"}") {
                val language = ranking?.language?.let(::audioName)
                if (s != null) Heading(s.heading, s.note(debridStatus.name), first = i == 0)
                else Heading("Without $language audio", "In other languages, for the original", first = i == 0)
            }
            item(key = r.copy.id) {
                // Under "Without Arabic audio" a row needn't say so again.
                val reason = ranking!!.reasonFor(r, plays).let { if (s != null) it else it.split(" · ").filterNot { p -> p.startsWith("no ") && p.endsWith(" audio") }.joinToString(" · ") }
                VersionRow(r, reason, data, focus = if (i == 0) first else if (r.copy.id == firstMore) more else null, onClick = { onChoose(r.copy) }, undo = r.pinned || r.mark != null, onMenu = {
                    cleared = r.copy.id
                    when {
                        r.pinned -> choices.pin(title, null)
                        r.mark != null -> choices.clearMark(setup, r.copy.id)
                        else -> choices.mark(setup, r.copy, works = false)
                    }
                    onChanged()
                }, modifier = if (r.copy.id == cleared) Modifier.focusRequester(clearedFocus) else Modifier)
            }
        }
        if (rows.size > shown.size) item(key = "all") { PanelRow("Show all", "${rows.size} copies") { all = true } }
        item(key = "setup") {
            Column(Modifier.padding(start = Space.l, end = Space.l, top = Space.l), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text("Why this pick", style = Type.headline)
                Text(setupLine(setup.hdr, setup.passthrough, setup.maxChannels), style = Type.caption)
                Text(bandwidthLine(graph.capacity::typicalMbps, debridStatus.name), style = Type.caption)
            }
        }
        item(key = "corrections") { Heading("Corrections") }
        items(Correction.entries, key = { it.name }) { c ->
            // What the TV and soundbar already report needs no correction: the row says so instead of "Off". Audio
            // they don't report is tried; a failure is learnt ("No, learnt 5 Nov"), and OK or Menu forgets it.
            val reported = when (c) {
                Correction.PassesTrueHd -> Passthrough.TrueHd in setup.passthrough
                Correction.PassesDts -> Passthrough.Dts in setup.passthrough
                Correction.IgnoreBandwidth -> false
            }
            val failed = learnt[c]?.takeIf { !reported && c !in corrections }
            val forget = {
                choices.learn(setup, c, failed = false)
                learnt = choices.learnt(setup)
                onChanged()
            }
            PanelRow(
                CORRECTIONS.getValue(c),
                when {
                    reported -> "Yes, reported"
                    c in corrections -> "Yes, set by you"
                    failed != null -> "No, learnt ${DAY.format(Instant.ofEpochMilli(failed))} · ☰ to undo"
                    c == Correction.IgnoreBandwidth -> "No"
                    else -> "Not reported, tried"
                },
                modifier = if (failed == null) Modifier else Modifier.onKeyEvent {
                    if (remoteKey(it.nativeKeyEvent.keyCode) != RemoteKey.Menu) return@onKeyEvent false
                    if (it.type == KeyEventType.KeyDown) forget()
                    true
                },
            ) {
                if (reported) return@PanelRow
                if (failed != null) return@PanelRow forget()
                choices.correct(setup, c, c !in corrections)
                corrections = choices.corrections(setup)
                onChanged()
            }
        }
        item(key = "sources") { Heading("Sources") }
        item(key = "rd") { PanelRow(debridStatus.name, stateText(debridStatus.state, data.answer)) {} }
        item(key = "iptv") { PanelRow("IPTV", if (data.answer?.iptvUnavailable == true) "Unavailable" else "Ready") {} }
    }
}

/**
 * Versions over a title page, from the right, on the title's prepared copies (opened from cached state, so
 * at once). OK pins the copy and plays it ([onPlay]); Back closes it ([onClose]).
 */
@Composable
internal fun VersionsPanel(title: String, onPlay: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier, open: Boolean = true) {
    // Focus stays in while [open]; a closing panel, still sliding out, lets it go back to the page at once.
    val stay by rememberUpdatedState(open)
    val graph = LocalContext.current.graph
    val p by remember(title) { graph.films.state(title) }.collectAsState()
    val first = remember { FocusRequester() }
    LaunchedEffect(p.ranking == null) {
        withFrameNanos {}
        runCatching { first.requestFocus() }
        Check.log("versions_shown", "title" to title, "copies" to p.ranking?.copies?.size)
    }
    Column(
        modifier
            .fillMaxHeight()
            .width(PANEL_WIDTH)
            .material()
            .onKeyEvent {
                if (remoteKey(it.nativeKeyEvent.keyCode) != RemoteKey.Back) return@onKeyEvent false
                if (it.type == KeyEventType.KeyDown) onClose()
                true
            }
            .focusProperties { onExit = { if (stay) cancelFocusChange() } }
            .focusGroup()
            .padding(Space.xl),
    ) {
        Text("Versions", style = Type.title, modifier = Modifier.padding(start = Space.l, bottom = Space.l))
        Versions(
            title, VersionsData(p), first,
            onChoose = {
                graph.copyChoices.pin(title, it.id)
                onClose()
                onPlay()
            },
            onChanged = { graph.films.rerank(title) },
            modifier = Modifier.weight(1f),
        )
    }
}

private val PANEL_WIDTH = 420.dp

@Composable
private fun Heading(text: String, note: String? = null, first: Boolean = false) {
    // More room above a group than inside it, so the groups read apart; the note says what the group means.
    Column(Modifier.padding(start = Space.l, end = Space.l, top = if (first) Space.s else Space.xl, bottom = Space.xs), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text, style = Type.headline)
        note?.let { Text(it, style = Type.caption.copy(color = Palette.textSecondary)) }
    }
}

/** A copy: tier and labels, then why it ranks where it does, then source, size and release name. */
@Composable
private fun VersionRow(r: Ranked, reason: String, data: VersionsData, focus: FocusRequester?, onClick: () -> Unit, undo: Boolean, onMenu: () -> Unit, modifier: Modifier = Modifier) {
    val c = r.copy
    val service = debridName()
    var focused by remember { mutableStateOf(false) }
    // ☰ undoes a pin or mark; on another copy, while focused, it says the copy doesn’t play here (a black screen, say).
    val status = listOfNotNull(
        reason.ifEmpty { null },
        debridState(c, data.debrid, service),
        if (undo) "☰ to undo" else if (focused) "☰ if it doesn’t play" else null,
    ).joinToString(" · ")
    Column(
        modifier
            .fillMaxWidth()
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent {
                if (remoteKey(it.nativeKeyEvent.keyCode) != RemoteKey.Menu) return@onKeyEvent false
                if (it.type == KeyEventType.KeyDown) onMenu()
                true
            }
            .pressable(onClick)
            .padding(horizontal = Space.l, vertical = Space.s),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            copyTier(c)?.let { TierChip(it) }
            FitText(copyLabels(c).joinToString(" · ").ifEmpty { "Quality unknown" }, style = Type.headline, active = focused, modifier = Modifier.weight(1f))
            when {
                c.id == data.playing -> Text("Playing", style = Type.caption.copy(color = Palette.accent), maxLines = 1)
            }
        }
        if (status.isNotEmpty()) FitText(status, style = Type.caption.copy(color = Palette.textSecondary), active = focused)
        FitText(
            listOfNotNull(sourceName(c.source, service), sizeLabel(data.answer?.size(c.id) ?: 0), data.answer?.name(c.id)?.ifEmpty { null }).joinToString(" · "),
            style = Type.caption,
            active = focused,
        )
    }
}

/** The serving debrid's name, for labels: "TorBox", "Real-Debrid", or "Debrid" while both serve. */
@Composable
internal fun debridName() = LocalContext.current.graph.debrid.status.collectAsState().value.name

internal fun sourceName(source: Source, service: String) = if (source == Source.Debrid) service else "IPTV"

/** "4K", "HDR10", "HEVC", "E-AC3 5.1 Atmos", "DE", "18 Mbps": what is known of a copy. */
internal fun copyLabels(c: Copy): List<String> = listOfNotNull(
    when {
        c.resolution >= 2000 -> "4K"
        c.resolution > 0 -> "${c.resolution}p"
        else -> null
    },
    c.hdr.firstOrNull()?.let { if (it == "DV") "Dolby Vision" + (c.dvProfile?.let { p -> " $p" } ?: "") else it },
    VIDEO[c.codec],
    listOfNotNull(AUDIO_LABEL[c.audio], c.channels.takeIf { it > 0 }?.let(::channelLayout), "Atmos".takeIf { c.atmos }).joinToString(" ").ifEmpty { null },
    c.languages.filter { it != "ar" || EGYPTIAN !in c.languages }.joinToString("/") { when (it) { "multi" -> "Multi"; EGYPTIAN -> "AR-EG"; else -> it.uppercase() } }.ifEmpty { null },
    c.mbps.takeIf { it > 0f }?.let { "${it.roundToInt()} Mbps" },
)

/** The picture in a word (see [pictureTier]). */
internal fun copyTier(c: Copy): String? = pictureTier(c)?.let(TIER_NAMES::get)

/** "14.2 GB"; null when unknown. */
internal fun sizeLabel(bytes: Long): String? = when {
    bytes <= 0 -> null
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    else -> "${bytes shr 20} MB"
}

/** The copy as the title page and Options name it: "Real-Debrid · 4K · HDR10 · E-AC3 5.1 · 14.2 GB". */
internal fun copyLine(c: Copy, answer: TitleAnswer?, service: String): String =
    (listOf(sourceName(c.source, service)) + (copyLabels(c) - setOfNotNull(VIDEO[c.codec])) + listOfNotNull(sizeLabel(answer?.size(c.id) ?: 0))).joinToString(" · ")

/** What a glance at the copy needs, as chips: picture, HDR, sound, and languages other than plain English ("4K", "Dolby Vision 8", "DTS-HD 5.1"). */
internal fun formatLabels(c: Copy): List<String> = copyLabels(c).filter { it != VIDEO[c.codec] && it != "EN" && !it.endsWith(" Mbps") }

/** Only a debrid copy that failed its check says so; a ready or not-yet-checked one reads as the plain service name. */
private fun debridState(c: Copy, checks: Map<String, DebridCheck>, service: String): String? {
    if (c.source != Source.Debrid) return null
    return when (val r = checks[c.id]) {
        is DebridCheck.Ready, null -> null
        DebridCheck.NotCached -> "$service doesn’t have it"
        is DebridCheck.Failed -> if (r.error.permanent) "refused by $service" else "couldn’t ask $service"
    }
}

private fun stateText(state: DebridState, answer: TitleAnswer?) = when (state) {
    DebridState.NoToken -> "No key on the box"
    DebridState.TokenInvalid -> "Key refused"
    DebridState.RateLimited -> "Busy, adds paused"
    DebridState.TooManyDownloads -> "Too many downloads"
    DebridState.Denied -> "Refused (account or IP)"
    DebridState.NotPremium -> "Premium ended"
    DebridState.Ok -> when {
        answer?.debridPending == true -> "Searching…"
        answer?.debridUnavailable == true -> "Search unavailable"
        else -> "Ready"
    }
}

/** "TV shows HDR10, HLG · passes E-AC3, Atmos · up to 8 channels". */
private fun setupLine(hdr: Set<Hdr>, passthrough: Set<Passthrough>, channels: Int): String = listOfNotNull(
    "TV shows " + hdr.joinToString(", ") { HDR_NAMES.getValue(it) }.ifEmpty { "SDR only" },
    "passes " + passthrough.joinToString(", ") { PASS_NAMES.getValue(it) }.ifEmpty { "stereo only" },
    channels.takeIf { it > 0 }?.let { "up to $it channels" },
).joinToString(" · ")

/** "Typical IPTV 40 Mbps · Real-Debrid not measured yet": the bandwidth the ranking used, this hour. */
private fun bandwidthLine(typical: (Source, Long) -> Float?, service: String): String {
    val now = System.currentTimeMillis()
    return "Typical " + Source.entries.joinToString(" · ") { s -> "${sourceName(s, service)} " + (typical(s, now)?.let { "${it.roundToInt()} Mbps" } ?: "not measured yet") }
}

private val VIDEO = mapOf("hevc" to "HEVC", "h264" to "H.264", "av1" to "AV1", "vp9" to "VP9", "mpeg2video" to "MPEG-2", "vc1" to "VC-1")
private val AUDIO_LABEL = mapOf(
    "truehd" to "TrueHD", "dtshd" to "DTS-HD", "dtsx" to "DTS:X", "dts" to "DTS", "eac3" to "E-AC3", "ac3" to "AC3", "aac" to "AAC", "opus" to "Opus", "flac" to "FLAC",
)
private val HDR_NAMES = mapOf(Hdr.DolbyVision to "Dolby Vision", Hdr.Hdr10 to "HDR10", Hdr.Hlg to "HLG", Hdr.Hdr10Plus to "HDR10+")
private val PASS_NAMES = mapOf(
    Passthrough.Ac3 to "AC3", Passthrough.EAc3 to "E-AC3", Passthrough.EAc3Joc to "Atmos", Passthrough.Ac4 to "AC-4",
    Passthrough.Dts to "DTS", Passthrough.DtsHd to "DTS-HD", Passthrough.DtsX to "DTS:X", Passthrough.TrueHd to "TrueHD",
)
private val CORRECTIONS = mapOf(
    Correction.PassesTrueHd to "Sound passes TrueHD", Correction.PassesDts to "Sound passes DTS", Correction.IgnoreBandwidth to "Ignore the bandwidth limit",
)
