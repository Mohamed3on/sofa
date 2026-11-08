package com.mohamed3on.iptv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import com.mohamed3on.iptv.ui.theme.Radius
import com.mohamed3on.iptv.data.FAVOURITES
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.mohamed3on.iptv.graph
import com.mohamed3on.iptv.ui.theme.material
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.mohamed3on.iptv.data.Channel
import com.mohamed3on.iptv.data.Feed
import com.mohamed3on.iptv.data.GuideDb
import com.mohamed3on.iptv.data.NowNext
import com.mohamed3on.iptv.data.Programme
import com.mohamed3on.iptv.data.displayName
import com.mohamed3on.iptv.ui.theme.Badges
import com.mohamed3on.iptv.ui.theme.Edge
import com.mohamed3on.iptv.ui.theme.LiveMark
import com.mohamed3on.iptv.ui.theme.LocalReducedMotion
import com.mohamed3on.iptv.ui.theme.Motion
import com.mohamed3on.iptv.ui.theme.Palette
import com.mohamed3on.iptv.ui.theme.ProgressLine
import com.mohamed3on.iptv.ui.theme.Space
import com.mohamed3on.iptv.ui.theme.FitText
import com.mohamed3on.iptv.ui.theme.ScrollText
import com.mohamed3on.iptv.ui.theme.LocalFocused
import com.mohamed3on.iptv.ui.theme.Type
import com.mohamed3on.iptv.ui.theme.reveal
import com.mohamed3on.iptv.ui.theme.slideFade
import com.mohamed3on.iptv.ui.theme.staggered
import com.mohamed3on.iptv.ui.theme.PanelRow
import com.mohamed3on.iptv.ui.theme.pressable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent as AndroidKeyEvent
import com.mohamed3on.iptv.data.LiveEntry
import com.mohamed3on.iptv.data.LiveNow
import com.mohamed3on.iptv.data.SearchHit
import com.mohamed3on.iptv.data.SearchResults
import com.mohamed3on.iptv.data.TROPHY
import com.mohamed3on.iptv.data.shownTitle
import com.mohamed3on.iptv.data.groupName
import com.mohamed3on.iptv.data.liveNow
import com.mohamed3on.iptv.data.prepareSearch
import com.mohamed3on.iptv.data.search
import com.mohamed3on.iptv.data.searchNorm
import com.mohamed3on.iptv.data.searchWords
import kotlin.math.roundToInt

/** Which section of the groups column [group] is in: Favourites, the 🏆 competitions, or the rest. */
private fun groupSection(group: String) = when {
    group == FAVOURITES -> 0
    group.startsWith(TROPHY) -> 1
    else -> 2
}

/** What a channel row shows under its name. */
data class RowLines(val name: String, val now: Programme?, val next: Programme?, val noGuide: Boolean)

/**
 * A row's lines from its now/next. Synthetic filler shows neither (an event slot's name in the feed
 * is newer than the guide's copy of it); a guide that has run out says so.
 */
fun rowLines(channel: Channel, guide: NowNext): RowLines {
    val name = displayName(channel.name)
    val (now, next) = guide
    if (now == null && next == null) return RowLines(name, null, null, noGuide = true)
    if (now?.synthetic == true) return RowLines(name, null, null, noGuide = false)
    return RowLines(name, now, next?.takeUnless { it.synthetic }, noGuide = false)
}

private val GROUPS_WIDTH = 260.dp
private val CHANNELS_WIDTH = 540.dp
private val GROUP_ROW_HEIGHT = 44.dp
private val ROW_HEIGHT = 72.dp
/** A feed under a known match's header: one line beside the logo. */
private val FEED_ROW_HEIGHT = 64.dp
// 2:1, so a wide logo (most real-shape ones are 2.5–3.5:1) reads; a square one keeps the full height.
private val TILE = DpSize(112.dp, 56.dp)
private val MARKER = DpSize(3.dp, 24.dp)
private val groupIdle = Type.headline.copy(color = Palette.textSecondary)
private val DOT = 6.dp
private val INLINE_PROGRESS = 72.dp

/** What the channel column shows. */
private sealed interface Source {
    val key: String
}
private data object SearchSource : Source {
    override val key = "search"
}
private data object LiveSource : Source {
    override val key = "live"
}
private data object MoviesSource : Source {
    override val key = "movies"
}
private data class GroupSource(val name: String) : Source {
    override val key get() = "g|$name"
}

/** A channel-column entry; [key] is stable across refreshes so focus stays on the same row. */
private sealed interface Item {
    val key: String
}
/**
 * [under]: the key of the Live now match it is listed under, so the row is that match's own. A [compact] row is a feed
 * under a known match's header (logo, name, flag, badges); a [zap] one tunes with Live now as its zap context.
 */
private class ChannelItem(val channel: Channel, under: String = "", val compact: Boolean = false, val zap: Boolean = false) : Item {
    override val key = "$under${channel.id}|${channel.group}"
}
/** A Live now match the scoreboards know, as the header of the channels showing it; never focused, pinned while they are. */
private class MatchItem(val entry: LiveEntry) : Item {
    override val key = "mh|${entry.programme!!.start}|${entry.title}"
}
private class LiveItem(val entry: LiveEntry) : Item {
    override val key = entry.programme?.let { "m|${it.start}|${entry.title}" } ?: "s|${entry.via.name}"
}
private data object SlotsHeading : Item {
    override val key = "slots"
}
private data object NoMatches : Item {
    override val key = "no-matches"
}
/** The query and on-screen keyboard, first in Search. */
private data object SearchBoxItem : Item {
    override val key = "search-box"
}
private class HitItem(val hit: SearchHit) : Item {
    override val key = "h|${hit.tune.family}|${hit.programme.start}|${hit.programme.title}"
}
private data object ChannelsHeading : Item {
    override val key = "channels"
}
private data object LiveHeading : Item {
    override val key = "live"
}
private data object ProgrammesHeading : Item {
    override val key = "programmes"
}
private data class Note(val title: String, val hint: String? = null) : Item {
    override val key get() = "note"
}

private val Item.height get() = if (this is ChannelItem && compact) FEED_ROW_HEIGHT else ROW_HEIGHT
private val Item.focusable get() = this is ChannelItem || this is LiveItem || this is HitItem
private val Item.target get() = when (this) {
    is ChannelItem -> channel
    is LiveItem -> entry.tune
    is HitItem -> hit.tune
    else -> null
}

/**
 * A Live now match, then every other channel showing it: pick by language or feed. A match the scoreboards know ([known]
 * names its programme title) is a header over all of its channels, the one it tunes first.
 */
private fun MutableList<Item>.addMatch(match: LiveEntry, known: Set<String>) {
    if (match.programme?.title in known) {
        val head = MatchItem(match)
        add(head)
        add(ChannelItem(match.tune, under = "${head.key}>", compact = true, zap = true))
        match.alsoOn.mapTo(this) { c -> ChannelItem(c, under = "${head.key}>", compact = true) }
        return
    }
    val row = LiveItem(match)
    add(row)
    match.alsoOn.mapTo(this) { c -> ChannelItem(c, under = "${row.key}>") }
}

/**
 * Null while Live now is still loading. Search shows a hint until 2 characters are [typed], then
 * what was [found] (nothing more while the first search runs): channels by name, then programmes,
 * each under its title only when it has rows.
 */
private fun itemsOf(source: Source, feed: Feed, live: LiveNow?, typed: Boolean, found: SearchResults?, known: Set<String>): List<Item>? = when (source) {
    SearchSource -> buildList {
        add(SearchBoxItem)
        when {
            !typed -> add(Note("Type a channel, team, player or event"))
            found == null -> {}
            found.count == 0 -> add(Note("No matches for “${found.query.trim()}”", "Try part of a channel, team or player name."))
            else -> {
                // Matches on now that the search names come first, laid out as in Live now (every channel
                // showing each); their airings then leave Programmes.
                val words = searchWords(found.query)
                val onNow = live?.matches.orEmpty().filter { m ->
                    listOf(m.title, m.programme!!.title).any { t -> searchNorm(t).let { n -> words.all(n::contains) } }
                }
                if (onNow.isNotEmpty()) {
                    add(LiveHeading)
                    onNow.forEach { addMatch(it, known) }
                }
                val airing = onNow.flatMap { m -> (listOf(m.tune) + m.alsoOn).map { it.family to m.programme!! } }.toMap()
                val hits = found.hits.filterNot { h -> airing[h.tune.family]?.let { p -> h.programme.start < p.stop && p.start < h.programme.stop } == true }
                if (found.channels.isNotEmpty()) {
                    add(ChannelsHeading)
                    found.channels.mapTo(this, ::ChannelItem)
                }
                if (hits.isNotEmpty()) {
                    add(ProgrammesHeading)
                    hits.mapTo(this, ::HitItem)
                }
            }
        }
    }
    is GroupSource -> feed.channelsIn(source.name).map(::ChannelItem)
    MoviesSource -> listOf(Note("Films and shows, best rated first, from IPTV and Debrid", "Press OK to browse"))
    LiveSource -> live?.let {
        buildList {
            for (match in it.matches) addMatch(match, known)
            if (it.slots.isNotEmpty()) {
                if (it.matches.isEmpty()) add(NoMatches)
                add(SlotsHeading)
                it.slots.mapTo(this, ::LiveItem)
            }
        }
    }
}

/** What a finished search ran with, so reopening the list never runs it again. */
internal class Searched(private val query: String, private val feed: Feed, private val guide: GuideDb?) {
    fun matches(query: String, feed: Feed, guide: GuideDb?) = query == this.query && feed === this.feed && guide === this.guide
}

/**
 * What the channel list keeps between openings, for the app session: the search (query, results,
 * the result last focused) and whether Search was on screen, so reopening goes back to it.
 */
class ListMemory {
    internal var query by mutableStateOf("")
    internal var found by mutableStateOf<SearchResults?>(null)
    internal var searching by mutableStateOf(false)
    internal var searched: Searched? = null
    /** The search result last focused; null when the keyboard was. */
    internal var searchFocus: String? = null
    internal var inSearch = false
}

private const val SEARCH_TAG = "IptvSearch"
/** About half a second of frames for a row to be composed and placed before focus gives up on it. */
private const val FOCUS_FRAMES = 30

/**
 * The channel list over the still-playing video (R5, R6, R21, R22, F2): groups on the left, the shown
 * group's channels with now/next, and a channel's schedule on Right. Focus opens on [current]'s row —
 * in Live now when it was tuned from there ([context]) — or on its group in the groups column with
 * [startInGroups]. When Search was on screen at the last close, it opens there instead, on the result
 * last focused ([memory] keeps the search for the app session). Moving through the groups shows each one.
 *
 * Choosing a row hands [onPreview] the channel and the zap context it carries (Live now's tune list,
 * else null: its group): the player tunes it behind the still-open list, which keeps its place.
 * Choosing the row playing, or any row when there is no [onPreview] (U11's pick mode), goes to
 * [onPick], which closes the list. [open] is false while the overlay leaves and while it is hidden,
 * so focus may go back to the player.
 *
 * The overlay stays composed between openings, so reopening it builds nothing: [opens] counts them,
 * and each opening starts afresh on the rows above, as described, while the built rows are reused. [updateRow] (U12) is a slot at the top of the groups column, above Search; it renders
 * nothing until filled (use [GroupRow]).
 *
 * Search (R7, R8, R26, F3) shows a keyboard and its results in the channel column; a result tunes
 * with no context, so Up/Down then follow that channel's group.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelOverlay(
    feed: Feed,
    guide: GuideDb?,
    clock: State<Long>,
    current: Channel?,
    context: List<Channel>?,
    open: Boolean,
    opens: Int,
    memory: ListMemory,
    startInGroups: Boolean,
    onPick: (channel: Channel, context: List<Channel>?) -> Unit,
    onPreview: ((channel: Channel, context: List<Channel>?) -> Unit)?,
    modifier: Modifier = Modifier,
    updateRow: LazyListScope.() -> Unit = {},
    entrance: () -> Float = { 1f },
    onLibrary: (() -> Unit)? = null,
    /** In pick mode (no [onPreview]), what OK does with the focused row's channel, said in its last line. */
    pickHint: ((Channel) -> String?)? = null,
) {
    val reduced = LocalReducedMotion.current
    val stay by rememberUpdatedState(open)
    val playing by rememberUpdatedState(current)
    val scope = rememberCoroutineScope()

    val live by produceState<LiveNow?>(null, feed, guide) {
        snapshotFlow { clock.value }.collectLatest { now ->
            // The guide can be swapped (and the old file closed) mid-read after a download: keep what is shown.
            value = withContext(Dispatchers.IO) {
                val airing = if (guide == null) emptyList() else runCatching { guide.airingMatches(now) }.getOrNull()
                airing?.let(feed::liveNow)
            } ?: return@collectLatest
        }
    }

    var shown by remember(opens) {
        mutableStateOf(
            when {
                memory.inSearch && !startInGroups -> SearchSource
                current != null && context?.any { it.identity == current.identity } == true -> LiveSource
                else -> GroupSource(current?.group ?: feed.groups.first())
            },
        )
    }
    // The channel column follows the groups column: a later group's rows rise from below, an earlier
    // one's come down from above. The old rows go at once; only the new ones move.
    val arrival = remember { Animatable(1f) }
    val built = remember { BuiltRows(perFrame = 2, onScreen = 8) }
    var travel by remember { mutableStateOf(1f) }
    fun show(next: Source) {
        if (next == shown) return
        val order = { s: Source ->
            when (s) {
                is GroupSource -> feed.groups.indexOf(s.name)
                MoviesSource -> -1
                LiveSource -> -3
                SearchSource -> -4
            }
        }
        travel = if (order(next) > order(shown)) 1f else -1f
        shown = next
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            arrival.snapTo(0f)
            arrival.animateTo(1f, if (reduced) Motion.fade() else Motion.spring(0.28f))
        }
    }
    // Holding Down through the groups column builds only the group focus rests on (~120ms), like
    // TiviMate: passing groups never pay for building their rows. Right/OK applies it at once.
    val pending = remember { PendingShow() }
    var enterShown by remember(opens) { mutableStateOf(false) }
    // A group waiting to show when the list closes never shows behind it.
    LaunchedEffect(open) {
        if (!open) {
            pending.job?.cancel()
            pending.target = null
        }
    }
    fun showSoon(next: Source) {
        pending.job?.cancel()
        pending.target = next
        pending.job = scope.launch {
            delay(GROUP_REST_MS)
            pending.target = null
            show(next)
        }
    }
    // A refresh can drop the 🏆 group on screen; fall back to the first group.
    val source = shown.takeUnless { it is GroupSource && it.name !in feed.groups } ?: GroupSource(feed.groups.first())
    SideEffect { memory.inSearch = source == SearchSource }
    val inSearch = source == SearchSource
    LaunchedEffect(feed, inSearch) { if (inSearch) withContext(Dispatchers.IO) { feed.prepareSearch() } }
    val query = memory.query
    val typed = searchNorm(query).trim().length >= 2
    // Each keystroke restarts the wait, so a search runs once typing pauses; the last results stay
    // meanwhile, and the busy line shows only for a search slow enough to notice.
    LaunchedEffect(query, feed, guide) {
        if (!typed) {
            memory.found = null
            memory.searched = null
            memory.searching = false
            return@LaunchedEffect
        }
        if (memory.searched?.matches(query, feed, guide) == true) return@LaunchedEffect
        delay(150)
        val busy = launch {
            delay(120)
            memory.searching = true
        }
        try {
            val started = SystemClock.elapsedRealtime()
            val results = withContext(Dispatchers.IO) { runCatching { feed.search(guide, query, clock.value) }.getOrNull() } ?: return@LaunchedEffect
            Log.d(SEARCH_TAG, "q=${query.trim().length} chars, ${SystemClock.elapsedRealtime() - started} ms, ${results.count} hits")
            memory.found = results
            memory.searched = Searched(query, feed, guide)
        } finally {
            busy.cancel()
            memory.searching = false
        }
    }
    val found = memory.found
    // Live now's matches the scoreboards know (by programme title): each heads its channels. Changes only when that set does.
    val crests = LocalCrests.current
    val scores = LocalScores.current
    val known by remember(live, crests) {
        derivedStateOf(structuralEqualityPolicy()) {
            live?.matches.orEmpty().mapNotNullTo(HashSet()) { m -> m.programme?.title?.takeIf { crests[it].stateIn(scores.value) != null } }
        }
    }
    val items = remember(source, feed, live.takeIf { source == LiveSource || source == SearchSource }, typed, found.takeIf { source == SearchSource }, known) {
        itemsOf(source, feed, live, typed, found, known)
    }

    // Now/next for the whole shown group in one query, off the main thread, again each minute; then
    // for every other channel too, so moving through the groups never builds a group's rows twice
    // (once empty, again when its guide lands).
    val tvgIds = remember(items) { items?.mapNotNull { (it as? ChannelItem)?.channel?.tvgId?.takeIf(String::isNotEmpty) }?.distinct().orEmpty() }
    val allTvgIds = remember(feed) { feed.channels.mapNotNull { it.tvgId.takeIf(String::isNotEmpty) }.distinct() }
    val nowNextState = remember { mutableStateOf(emptyMap<String, NowNext>()) }
    val nowNext by nowNextState
    suspend fun loadNowNext(ids: List<String>, now: Long) {
        val fresh = withContext(Dispatchers.IO) {
            if (guide == null) ids.associateWith { NowNext(null, null) } else runCatching { guide.nowNextFor(ids, now) }.getOrNull()
        } ?: return
        nowNextState.value = nowNextState.value + fresh
    }
    LaunchedEffect(tvgIds, guide) { snapshotFlow { clock.value }.collectLatest { loadNowNext(tvgIds, it) } }
    LaunchedEffect(allTvgIds, guide) {
        delay(400) // the shown group's own query first
        snapshotFlow { clock.value }.collectLatest { loadNowNext(allTvgIds, it) }
    }

    val lastFocused = remember(opens) { HashMap<String, String>() }
    var focusedKey by remember(opens) { mutableStateOf<String?>(null) }
    var focusedIndex by remember(opens) { mutableStateOf(0) }
    var wantChannels by remember(opens) { mutableStateOf(true) }
    var inChannels by remember { mutableStateOf(false) }
    var inGroups by remember { mutableStateOf(false) }
    var onKeyboard by remember(opens) { mutableStateOf(false) }
    var groupKey by remember(opens) { mutableStateOf<String?>(null) }
    // A list's end is felt, not crossed: Up on the first row or Down on the last nudges the column
    // toward the key and springs it back, where focus search would otherwise jump to the other column.
    val groupsNudge = remember { Animatable(0f) }
    val channelsNudge = remember { Animatable(0f) }
    fun bump(column: Animatable<Float, *>, down: Boolean) {
        if (reduced) return
        scope.launch {
            column.animateTo(if (down) 1f else -1f, Motion.spring(0.1f))
            column.animateTo(0f, Motion.spring(0.3f))
        }
    }
    var entryKey by remember(opens) { mutableStateOf<String?>(null) }
    var details by remember(opens) { mutableStateOf<Channel?>(null) }
    // The pane keeps its channel while it slides away.
    var detailsShown by remember(opens) { mutableStateOf<Channel?>(null) }
    // ☰ on a channel row: its actions beside the list (Favourites, Move). Move then carries that row
    // with Up/Down until any other key puts it down; each step is saved at once.
    val lineup = LocalContext.current.graph.lineup
    var actions by remember(opens) { mutableStateOf<ChannelItem?>(null) }
    var actionsShown by remember(opens) { mutableStateOf<ChannelItem?>(null) }
    var moving by remember(opens) { mutableStateOf<String?>(null) }
    // A row just (un)favourited: focus follows it to its new place (favourites lead their group).
    var follow by remember(opens) { mutableStateOf<String?>(null) }
    val rowFocus = remember { FocusRequester() }
    val groupFocus = remember { FocusRequester() }
    val keyFocus = remember { FocusRequester() }
    // One focus move at a time: a newer one cancels any still waiting for its target.
    val focusing = remember { arrayOfNulls<Job>(1) }
    fun moveFocus(block: suspend CoroutineScope.() -> Unit) {
        focusing[0]?.cancel()
        if (!stay) return // hidden: focus stays on the player
        focusing[0] = scope.launch(block = block)
    }

    /** The row last focused here, else the playing channel's (the same row, else the same channel), else the first. */
    fun entryIndex(list: List<Item>): Int {
        lastFocused[source.key]?.let { k -> list.indexOfFirst { it.key == k }.takeIf { it >= 0 }?.let { return it } }
        current?.let { c ->
            list.indexOfFirst { it.target?.identity == c.identity }.takeIf { it >= 0 }?.let { return it }
            list.indexOfFirst { it.target?.family == c.family }.takeIf { it >= 0 }?.let { return it }
        }
        return list.indexOfFirst { it.focusable }
    }

    /** The search result focused last, if it is still in [list]. */
    fun resumeIndex(list: List<Item>): Int? = memory.searchFocus?.let { k -> list.indexOfFirst { it.key == k }.takeIf { it >= 0 } }

    fun groupsStart() = ((source as? GroupSource)?.let { feed.groups.indexOf(it.name) + 1 } ?: 0).minus(2).coerceAtLeast(0)
    val groupsState = remember { LazyListState(groupsStart()) }
    fun startIndex() = items?.let { ((if (source == SearchSource) resumeIndex(it) ?: 0 else entryIndex(it)) - 1).coerceAtLeast(0) } ?: 0
    // One list state for every source, moved in the frame its rows change: the column keeps its
    // composed rows and reuses them for the next group's, rather than building each group afresh.
    val listState = remember { LazyListState(startIndex()) }
    // Each opening (and each source shown) puts the columns in place. Rows are built a few per frame
    // from the top while the column slides or fades in (the rest hold their place), so no frame is long
    // enough to drop; a source whose rows are already built (reopening on the same group) builds nothing.
    val positionedFor = remember(opens) { arrayOfNulls<String>(1) }
    SideEffect {
        if (positionedFor[0] == source.key) return@SideEffect
        if (positionedFor[0] == null) groupsState.requestScrollToItem(groupsStart())
        positionedFor[0] = source.key
        built.from = startIndex()
        built.buildFor(source.key, scope)
        listState.requestScrollToItem(built.from)
    }

    /**
     * Entering the channel column lands on [index], else the row last or now playing; in Search, on the
     * keyboard, or with [resume] on the result focused last. The target takes focus once it is composed
     * and placed, asked again each frame for a moment, so a list still settling never drops it.
     */
    fun focusChannels(index: Int? = null, resume: Boolean = false) {
        val list = items ?: return
        val i = index ?: if (source == SearchSource) (if (resume) resumeIndex(list) else null) else entryIndex(list)
        if (i == null) {
            moveFocus {
                listState.scrollToItem(0)
                repeat(FOCUS_FRAMES) {
                    withFrameNanos {}
                    runCatching { keyFocus.requestFocus() }
                    if (inChannels && onKeyboard) return@moveFocus
                }
            }
            return
        }
        if (i !in list.indices || !list[i].focusable) return
        val key = list[i].key
        entryKey = key
        moveFocus {
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == i }) listState.scrollToItem((i - 1).coerceAtLeast(0))
            repeat(FOCUS_FRAMES) {
                withFrameNanos {}
                runCatching { rowFocus.requestFocus() }
                if (inChannels && focusedKey == key) return@moveFocus
            }
        }
    }

    // A spoken query: once its results are in, the first takes focus, so OK plays it. No results leave focus on the keys.
    var spoken by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(spoken, memory.searched, items) {
        val asked = spoken ?: return@LaunchedEffect
        if (memory.searched?.matches(asked, feed, guide) != true) return@LaunchedEffect
        spoken = null
        items?.indexOfFirst { it.focusable }?.takeIf { it >= 0 }?.let { focusChannels(it) }
    }

    /** Right/OK from the groups column: a group still waiting to show is shown now, then entered; Movies & Shows opens. */
    fun enterChannels() {
        if (source == MoviesSource) return onLibrary?.invoke() ?: Unit
        val target = pending.target
        pending.job?.cancel()
        pending.target = null
        if (target == null) return focusChannels()
        show(target)
        enterShown = true
    }
    // Entered from the composition that has the newly shown group's rows; this one still has the old's.
    LaunchedEffect(source.key, enterShown) {
        if (enterShown) {
            enterShown = false
            focusChannels()
        }
    }

    /** The groups column, on the shown source's row, once the column has laid out and scrolled to it. */
    fun focusGroups() {
        moveFocus {
            repeat(FOCUS_FRAMES) {
                withFrameNanos {}
                val info = groupsState.layoutInfo
                if (info.totalItemsCount == 0) return@repeat
                if (info.visibleItemsInfo.none { it.key == source.key }) {
                    // The update row (U12) and Search come first, then Live now, Movies & Shows, then the groups.
                    val libraries = if (onLibrary != null) 1 else 0
                    val first = info.totalItemsCount - feed.groups.size - 1 - libraries
                    val at = when (source) {
                        SearchSource -> first - 1
                        LiveSource -> first
                        MoviesSource -> first + 1
                        is GroupSource -> first + 1 + libraries + feed.groups.indexOf(source.name)
                    }
                    groupsState.scrollToItem((at - 2).coerceAtLeast(0))
                } else {
                    runCatching { groupFocus.requestFocus() }
                    if (inGroups) return@moveFocus
                }
            }
        }
    }

    /** The groups column's top (Search) or its last group, shown at once and focused: a wrap round, or Left's shortcut. */
    fun jumpGroups(top: Boolean) {
        pending.job?.cancel()
        pending.target = null
        val to = if (top) SearchSource else GroupSource(feed.groups.last())
        show(to)
        moveFocus {
            groupsState.scrollToItem(if (top) 0 else groupsState.layoutInfo.totalItemsCount - 1)
            repeat(FOCUS_FRAMES) {
                withFrameNanos {}
                runCatching { groupFocus.requestFocus() }
                if (inGroups && groupKey == to.key) return@moveFocus
            }
        }
    }

    fun closeDetails() {
        details = null
        focusChannels()
    }

    fun closeActions() {
        actions = null
        focusChannels()
    }

    fun moveRow(down: Boolean) {
        val group = (source as? GroupSource)?.name ?: return
        val row = items?.firstOrNull { it.key == moving } as? ChannelItem ?: return
        lineup.move(group, feed.channelsIn(group).map { it.family }, row.channel.family, down)
    }

    /** A row chosen: tuned behind the list to preview it, or, when it is the one playing, watched full screen. */
    fun choose(channel: Channel, context: List<Channel>?) {
        val preview = onPreview
        if (preview != null && channel.family != playing?.family) preview(channel, context) else onPick(channel, context)
    }
    val watchHint = onPreview != null

    // Focus opens on the current channel once its list exists, and never lands nowhere: when a refresh
    // drops the focused row, the nearest row takes it; an emptied list hands focus to its group.
    var opened by remember(opens) { mutableStateOf(false) }
    LaunchedEffect(items, opens) {
        val list = items ?: return@LaunchedEffect
        if (!stay) return@LaunchedEffect
        if (!opened) {
            opened = true
            if (!startInGroups && (source == SearchSource || list.any { it.focusable })) focusChannels(resume = true) else focusGroups()
        } else if (focusedKey != null && wantChannels && details == null && list.none { it.key == focusedKey }) {
            val near = list.indices.filter { list[it].focusable }.minByOrNull { kotlin.math.abs(it - focusedIndex) }
            if (near != null) focusChannels(near) else focusGroups()
        }
    }

    // Back from Movies: focus returns to its row, with the list as it was left. Only then: a fresh
    // opening places focus itself, and moving it here would cancel that (Search's keyboard, say).
    val shownFor = remember { intArrayOf(-1) }
    LaunchedEffect(open) {
        if (open && opened && shownFor[0] == opens && !inGroups && !inChannels) focusGroups()
        if (open) shownFor[0] = opens
    }

    // Keep the focused row clear of the edge fades, with the next row in view.
    val density = LocalDensity.current
    val keepClear = remember(density) {
        val margin = with(density) { (ROW_HEIGHT * 0.75f + Space.safeY).toPx() }
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val before = offset - margin
                val after = offset + size + margin - containerSize
                return when {
                    before < 0 -> before
                    after > 0 -> after
                    else -> 0f
                }
            }
        }
    }

    // The row being moved stays in view: the list scrolls a row at a time as it nears an edge.
    val rowStep = with(density) { (ROW_HEIGHT + Space.xs).toPx() }
    LaunchedEffect(items) {
        follow?.let { key ->
            follow = null
            items?.indexOfFirst { it.key == key }?.takeIf { it >= 0 }?.let { focusChannels(it) }
        }
        val i = items?.indexOfFirst { it.key == moving }?.takeIf { it >= 0 } ?: return@LaunchedEffect
        val was = focusedIndex
        focusedIndex = i
        val seen = listState.layoutInfo.visibleItemsInfo.takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
        if (i > was && i >= seen.last().index - 1) listState.animateScrollBy(rowStep)
        if (i < was && i <= seen.first().index + 1) listState.animateScrollBy(-rowStep)
    }

    Row(
        modifier
            .fillMaxHeight()
            .padding(horizontal = Space.safeX)
            .onKeyEvent {
                val key = remoteKey(it.nativeKeyEvent.keyCode)
                if (actions != null && (key == RemoteKey.Back || key == RemoteKey.Left || key == RemoteKey.Menu)) {
                    if (it.type == KeyEventType.KeyDown) closeActions()
                    return@onKeyEvent true
                }
                if (details == null || key != RemoteKey.Back) return@onKeyEvent false
                if (it.type == KeyEventType.KeyDown) closeDetails()
                true
            }
            // Focus stays in the list while it is open: no key lands on the video underneath.
            .focusProperties {
                onExit = { if (stay) cancelFocusChange() }
                onEnter = { if (!stay) cancelFocusChange() }
            }
            .focusGroup(),
    ) {
        // In with the list, first; and out of the way of the schedule (Right on a channel), folding to
        // nothing with the gap after it. It stays built meanwhile, so closing the schedule builds nothing.
        val aside = animateFloatAsState(if (details == null && actions == null) 1f else 0f, Motion.panel(reduced), label = "aside")
        Box(
            Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val width = (placeable.width * aside.value).roundToInt()
                layout(width, placeable.height) { placeable.placeRelative(width - placeable.width, 0) }
            },
        ) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides keepClear) {
                LazyColumn(
                    state = groupsState,
                    modifier = Modifier
                        .padding(end = Space.xl)
                        .width(GROUPS_WIDTH)
                        .fillMaxHeight()
                        .slideFade({ entrance() * aside.value }, Edge.Left, reduced)
                        .graphicsLayer { translationY = groupsNudge.value * Space.s.toPx() }
                        .onFocusChanged { inGroups = it.hasFocus }
                        .onKeyEvent {
                            when (val code = it.nativeKeyEvent.keyCode) {
                                AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    if (it.type == KeyEventType.KeyDown) enterChannels()
                                    true
                                }
                                // Left: back to the top, a shortcut from deep in the groups.
                                AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                                    if (it.type == KeyEventType.KeyDown && groupKey != SearchSource.key) jumpGroups(top = true)
                                    true
                                }
                                AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                    val down = code == AndroidKeyEvent.KEYCODE_DPAD_DOWN
                                    val info = groupsState.layoutInfo
                                    val at = info.visibleItemsInfo.firstOrNull { v -> v.key == groupKey }?.index
                                    val edge = at != null && at == (if (down) info.totalItemsCount - 1 else 0)
                                    // A fresh press at either end wraps round; a held key stops there.
                                    if (edge && it.type == KeyEventType.KeyDown) {
                                        if (it.nativeKeyEvent.repeatCount == 0) jumpGroups(top = down) else bump(groupsNudge, down)
                                    }
                                    edge
                                }
                                else -> false
                            }
                        },
                    contentPadding = PaddingValues(vertical = Space.safeY + Space.xl),
                    verticalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    updateRow()
                    item(key = SearchSource.key) {
                        GroupRow(
                            "Search",
                            marked = source == SearchSource && inChannels,
                            focus = if (source == SearchSource) groupFocus else null,
                            onFocused = { show(SearchSource); groupKey = SearchSource.key; wantChannels = false },
                            onClick = { enterChannels() },
                            modifier = groupMotion(),
                        )
                    }
                    item(key = LiveSource.key) {
                        GroupRow(
                            "Live now",
                            marked = source == LiveSource && inChannels,
                            focus = if (source == LiveSource) groupFocus else null,
                            onFocused = { show(LiveSource); groupKey = LiveSource.key; wantChannels = false },
                            onClick = { enterChannels() },
                            modifier = groupMotion(),
                        )
                    }
                    if (onLibrary != null) item(key = MoviesSource.key) {
                        GroupRow(
                            LibraryKind.Movies.title,
                            marked = false,
                            focus = if (source == MoviesSource) groupFocus else null,
                            onFocused = { show(MoviesSource); groupKey = MoviesSource.key; wantChannels = false },
                            onClick = { onLibrary() },
                            modifier = groupMotion(),
                        )
                    }
                    itemsIndexed(feed.groups, key = { _, it -> "g|$it" }) { i, group ->
                        val here = source is GroupSource && source.name == group
                        // A gap where a section starts: after Search to Movies & Shows, then Favourites, competitions, the rest.
                        val starts = i == 0 || groupSection(feed.groups[i - 1]) != groupSection(group)
                        GroupRow(
                            group,
                            marked = here && inChannels,
                            focus = if (here) groupFocus else null,
                            onFocused = { showSoon(GroupSource(group)); groupKey = "g|$group"; wantChannels = false },
                            onClick = { enterChannels() },
                            modifier = groupMotion().then(if (starts) Modifier.padding(top = Space.l) else Modifier),
                        )
                    }
                }
            }
        }

        Box(
            Modifier
                .width(CHANNELS_WIDTH)
                .fillMaxHeight()
                // In just behind the groups (and out just ahead of them), and in from the travel's side on a group change.
                .graphicsLayer {
                    val p = staggered(entrance())
                    val a = arrival.value
                    alpha = p * a
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                    if (!reduced) {
                        translationX = -(1f - p) * size.width / 3f
                        translationY = (1f - a) * travel * Space.xl.toPx() + channelsNudge.value * Space.s.toPx()
                    }
                }
                .onFocusChanged { inChannels = it.hasFocus }
                .onPreviewKeyEvent {
                    val code = it.nativeKeyEvent.keyCode
                    if (moving == null || remoteKey(code) == null) return@onPreviewKeyEvent false
                    val step = code == AndroidKeyEvent.KEYCODE_DPAD_UP || code == AndroidKeyEvent.KEYCODE_DPAD_DOWN
                    if (step && it.type == KeyEventType.KeyDown) moveRow(down = code == AndroidKeyEvent.KEYCODE_DPAD_DOWN)
                    // Any other key puts the row down, on its release, so OK never also tunes it.
                    if (!step && it.type == KeyEventType.KeyUp) moving = null
                    true
                }
                .onKeyEvent {
                    val code = it.nativeKeyEvent.keyCode
                    // ⏪ to the first row, ⏩ to the last: one press from hundreds of channels deep.
                    if (code == AndroidKeyEvent.KEYCODE_MEDIA_REWIND || code == AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                        val list = items
                        if (list == null || onKeyboard) return@onKeyEvent false
                        if (it.type == KeyEventType.KeyDown) {
                            focusChannels(if (code == AndroidKeyEvent.KEYCODE_MEDIA_REWIND) list.indexOfFirst { item -> item.focusable } else list.indexOfLast { item -> item.focusable })
                        }
                        return@onKeyEvent true
                    }
                    if (code == AndroidKeyEvent.KEYCODE_MENU) {
                        val row = items?.firstOrNull { item -> item.key == focusedKey } as? ChannelItem
                        if (row == null || onKeyboard) return@onKeyEvent false
                        if (it.type == KeyEventType.KeyDown) {
                            actions = row
                            actionsShown = row
                        }
                        return@onKeyEvent true
                    }
                    if (code == AndroidKeyEvent.KEYCODE_DPAD_UP || code == AndroidKeyEvent.KEYCODE_DPAD_DOWN) {
                        val list = items
                        if (list == null || onKeyboard) return@onKeyEvent false
                        val down = code == AndroidKeyEvent.KEYCODE_DPAD_DOWN
                        // Up from Search's first result goes back to the keyboard above it.
                        val first = list.indexOfFirst { it.focusable }
                        val last = list.indexOfLast { it.focusable }
                        val edge = focusedIndex == (if (down) last else first) && (down || source != SearchSource)
                        // A fresh press at either end wraps round; a held key stops there, so a fast scroll never laps.
                        if (edge && it.type == KeyEventType.KeyDown) {
                            if (it.nativeKeyEvent.repeatCount == 0 && first != last) focusChannels(if (down) first else last) else bump(channelsNudge, down)
                        }
                        return@onKeyEvent edge
                    }
                    if (code != AndroidKeyEvent.KEYCODE_DPAD_LEFT && code != AndroidKeyEvent.KEYCODE_DPAD_RIGHT) return@onKeyEvent false
                    if (it.type == KeyEventType.KeyDown) {
                        if (code == AndroidKeyEvent.KEYCODE_DPAD_LEFT) focusGroups()
                        else items?.firstOrNull { item -> item.key == focusedKey }?.target?.let { c ->
                            details = c
                            detailsShown = c
                        }
                    }
                    true
                },
        ) {
            val list = items
            when {
                list == null -> {}
                list.isEmpty() -> Box(Modifier.padding(top = Space.safeY)) {
                    if (source == LiveSource) Empty("No matches on right now", "Matches appear here when they kick off.") else Empty("Nothing in this group right now")
                }
                else -> CompositionLocalProvider(LocalBringIntoViewSpec provides keepClear) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                        contentPadding = PaddingValues(vertical = Space.safeY + Space.xl),
                        verticalArrangement = Arrangement.spacedBy(Space.xs),
                    ) {
                        rowsOf(list, header = { index, item -> MatchHeader(item.entry, listState, index) }) { index, item ->
                            val ready by remember(index) { derivedStateOf { built.ready(index) } }
                            if (item.focusable && !ready) return@rowsOf Box(Modifier.fillMaxWidth().height(item.height))
                            val focus = if (item.key == entryKey) rowFocus else null
                            val onFocused = {
                                focusedKey = item.key
                                focusedIndex = index
                                lastFocused[source.key] = item.key
                                if (source == SearchSource) memory.searchFocus = item.key
                                wantChannels = true
                                onKeyboard = false
                            }
                            // The row a pane beside the list is about (its schedule, ☰) stays marked while focus is in the pane.
                            val focusedHere = item.key == focusedKey
                            CompositionLocalProvider(
                                LocalHeld provides ((actions != null || details != null) && focusedHere),
                                LocalRowHint provides (if (focusedHere) item.target?.let { pickHint?.invoke(it) } else null),
                            ) {
                            when (item) {
                                is ChannelItem -> if (item.compact) {
                                    val on = item.channel.family == current?.family
                                    FeedRow(
                                        item.channel, playing = on, watchHint = watchHint && on, focus = focus, onFocused = onFocused,
                                        onClick = { choose(item.channel, if (item.zap) live?.context else null) },
                                    )
                                } else {
                                    val on = item.channel.family == current?.family
                                    ChannelRow(
                                        item.channel, nowNext[item.channel.tvgId], clock,
                                        playing = on, watchHint = watchHint && on, focus = focus, onFocused = onFocused,
                                        onClick = { choose(item.channel, null) },
                                        moving = item.key == moving,
                                    )
                                }
                                is LiveItem -> {
                                    val on = item.entry.tune.family == current?.family
                                    LiveRow(
                                        item.entry, clock, playing = on, watchHint = watchHint && on, focus = focus, onFocused = onFocused,
                                        onClick = { choose(item.entry.tune, live?.context) },
                                    )
                                }
                                NoMatches -> Text("No matches on right now", style = Type.body, modifier = Modifier.padding(horizontal = Space.m, vertical = Space.m))
                                is MatchItem -> {}
                                SlotsHeading -> SectionTitle("Event channels · not confirmed")
                                LiveHeading -> SectionTitle("Live now")
                                ChannelsHeading -> SectionTitle("Channels")
                                ProgrammesHeading -> SectionTitle("Programmes")
                                // Typing is not a row: a refresh while on the keyboard leaves focus there.
                                SearchBoxItem -> SearchBox(
                                    query, busy = memory.searching, onQuery = { memory.query = it }, entry = keyFocus,
                                    onFocused = {
                                        wantChannels = false
                                        onKeyboard = true
                                        memory.searchFocus = null
                                    },
                                    onDown = { focusChannels(list.indexOfFirst { it.focusable }) },
                                    onSpoken = { spoken = it },
                                )
                                is HitItem -> {
                                    val on = item.hit.tune.family == current?.family
                                    HitRow(
                                        item.hit, clock, playing = on, watchHint = watchHint && on, focus = focus, onFocused = onFocused,
                                        onPick = { choose(item.hit.tune, null) },
                                    )
                                }
                                is Note -> Empty(item.title, item.hint)
                            }
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = details != null,
            enter = EnterTransition.None,
            exit = ExitTransition.None,
        ) {
            val shownNow = reveal()
            detailsShown?.let {
                Details(
                    it, guide, clock, stay = { details != null }, onClose = ::closeDetails,
                    onWatch = {
                        closeDetails()
                        choose(it, null)
                    },
                    modifier = Modifier.padding(start = Space.xl, top = Space.safeY, bottom = Space.safeY).slideFade({ shownNow.value * staggered(entrance()) }, Edge.Right, reduced),
                )
            }
        }

        AnimatedVisibility(
            visible = actions != null,
            enter = EnterTransition.None,
            exit = ExitTransition.None,
        ) {
            val shownNow = reveal()
            actionsShown?.let { row ->
                RowActions(
                    title = displayName(row.channel.name),
                    favourite = lineup.isFavourite(row.channel.family),
                    canMove = (source as? GroupSource)?.name == row.channel.group,
                    stay = { actions != null },
                    onFavourite = {
                        follow = row.key
                        actions = null
                        lineup.toggleFavourite(row.channel.family)
                    },
                    onMove = {
                        actions = null
                        moving = row.key
                        focusChannels()
                    },
                    modifier = Modifier.padding(start = Space.xl, top = Space.safeY).slideFade({ shownNow.value * staggered(entrance()) }, Edge.Right, reduced),
                )
            }
        }
    }
}

/** ☰ on a channel row: add it to or take it off Favourites, and, in its own group, move it. Left, Back or ☰ close it. */
@Composable
private fun RowActions(title: String, favourite: Boolean, canMove: Boolean, stay: () -> Boolean, onFavourite: () -> Unit, onMove: () -> Unit, modifier: Modifier = Modifier) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        runCatching { first.requestFocus() }
    }
    Column(
        modifier
            .width(DETAILS_WIDTH)
            .material()
            .focusProperties { onExit = { if (stay()) cancelFocusChange() } }
            .focusGroup()
            .padding(Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        ScrollText(title, style = Type.title, lines = 2, modifier = Modifier.padding(start = Space.l, end = Space.l, top = Space.s, bottom = Space.s))
        PanelRow(if (favourite) "Remove from Favourites" else "Add to Favourites", focus = first, onClick = onFavourite)
        if (canMove) PanelRow("Move", onClick = onMove)
    }
}

/** A section's title in the channel column, in sentence case. */
@Composable
private fun SectionTitle(text: String) {
    Text(text, style = Type.caption, modifier = Modifier.padding(start = Space.m, top = Space.l, bottom = Space.xs))
}

/**
 * A groups-column row arriving or leaving while the list is open (Update ready, a new 🏆 group) fades,
 * and the rows below glide to their places instead of jumping. Keys are stable, so moving through the
 * column never animates.
 */
@Composable
fun LazyItemScope.groupMotion(): Modifier {
    val reduced = LocalReducedMotion.current
    return Modifier.animateItem(fadeInSpec = Motion.fade(), placementSpec = if (reduced) null else Motion.panel(), fadeOutSpec = Motion.quickFade())
}

/** A groups-column entry; [marked] shows a quiet accent bar while the channel column has focus. Idle groups recede. */
@Composable
fun GroupRow(label: String, marked: Boolean, focus: FocusRequester?, onFocused: () -> Unit, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val name = remember(label) { groupName(label) }
    Box(
        modifier
            .fillMaxWidth()
            .height(GROUP_ROW_HEIGHT)
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .pressable(onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (marked) Box(Modifier.size(MARKER).clip(RoundedCornerShape(MARKER.width)).background(Palette.accent))
        CompName(label, if (focused || marked) Type.headline else groupIdle, Modifier.padding(horizontal = Space.l), fallback = name, active = focused)
    }
}

/** True on the row whose schedule or ☰ pane is open beside the list. */
internal val LocalHeld = compositionLocalOf { false }

@Composable
internal fun RowFrame(
    tile: Channel,
    focus: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    height: Dp = ROW_HEIGHT,
    lines: @Composable ColumnScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(height)
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .then(if (LocalHeld.current) Modifier.drawBehind { drawRoundRect(Palette.raised, cornerRadius = CornerRadius(Radius.row.toPx())) } else Modifier)
            .pressable(onClick)
            .padding(horizontal = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        LogoTile(tile, TILE)
        Column(Modifier.weight(1f)) { CompositionLocalProvider(LocalFocused provides focused) { lines() } }
    }
}

/**
 * Name and badges, then now (with progress) and next. Until the guide lookup returns, the lines are
 * held empty at full height, so rows never jump as data fills in. [watchHint] puts [WATCH_HINT] in
 * place of next. Narrow sheets leave out the [badges], so the name keeps its room.
 */
@Composable
internal fun ChannelRow(
    channel: Channel,
    guide: NowNext?,
    clock: State<Long>,
    playing: Boolean,
    watchHint: Boolean,
    focus: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    badges: Boolean = true,
    moving: Boolean = false,
) {
    val lines = remember(channel, guide) { guide?.let { rowLines(channel, it) } }
    val name = remember(channel.name) { displayName(channel.name) }
    RowFrame(channel, focus, onFocused, onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            if (playing) PlayingDot()
            FitText(name, style = Type.headline, modifier = Modifier.weight(1f, fill = false))
            if (badges) Badges(channel.badges)
        }
        val now = lines?.now
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            when {
                lines?.noGuide == true -> Text("No guide", style = Type.body, maxLines = 1)
                now != null -> {
                    val match = matchOf(now.title)
                    if (match != null) MatchLine(match, Type.body)
                    else {
                        if (now.match) LiveMark()
                        MatchCrests(now.title)
                        FitText(remember(now) { shownTitle(now) }, style = Type.body, modifier = Modifier.weight(1f))
                        Progress(now, clock)
                    }
                }
                else -> Text("", style = Type.body)
            }
        }
        val next = lines?.next
        if (moving) Hint(MOVE_HINT) else LastLine(watchHint) {
            FitText(remember(next) { next?.let { "Next · ${clockTime(it.start)} ${shownTitle(it)}" } ?: "" }, style = Type.caption)
        }
    }
}

/**
 * [list]'s rows, each Live now match's header pinned at the top while its channels scroll under it (stickyHeader); one
 * run of rows, as before, for a list without any.
 */
private fun LazyListScope.rowsOf(list: List<Item>, header: @Composable LazyItemScope.(Int, MatchItem) -> Unit, row: @Composable LazyItemScope.(Int, Item) -> Unit) {
    var from = 0
    fun run(to: Int) {
        val start = from
        if (to > start) items(to - start, key = { list[start + it].key }, contentType = { list[start + it]::class }) { row(start + it, list[start + it]) }
    }
    list.forEachIndexed { i, item ->
        if (item !is MatchItem) return@forEachIndexed
        run(i)
        stickyHeader(key = item.key, contentType = MatchItem::class) { header(i, item) }
        from = i + 1
    }
    run(list.size)
}

/**
 * A known match over the channels showing it: its match line, then its 🏆 competition. While its channels scroll under
 * it, it sits on solid ground inside the safe area, so no row reads through it.
 */
@Composable
private fun MatchHeader(entry: LiveEntry, listState: LazyListState, index: Int) {
    val match = matchOf(entry.programme?.title)
    // Pinned: a later row has scrolled up under it. Read while scrolling; the header changes only when that does.
    val stuck by remember(index) {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            val self = visible.firstOrNull { it.index == index }
            self != null && visible.any { it.index > index && it.offset < self.offset + self.size }
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (stuck) Modifier.background(Palette.ink) else Modifier)
            .padding(top = if (stuck) Space.safeY else Space.m, bottom = Space.xs)
            .padding(horizontal = Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        if (match != null) MatchLine(match, Type.headline) else FitText(entry.title, style = Type.headline)
        LiveComp(entry)
    }
}

/** A feed under a known match: logo, its language's flag and name, and badges; the match is the header's to say. */
@Composable
private fun FeedRow(channel: Channel, playing: Boolean, watchHint: Boolean, focus: FocusRequester?, onFocused: () -> Unit, onClick: () -> Unit) {
    val name = remember(channel) { feedFlag(channel).let { flag -> if (flag.isEmpty()) displayName(channel.name) else "$flag  ${displayName(channel.name)}" } }
    RowFrame(channel, focus, onFocused, onClick, height = FEED_ROW_HEIGHT) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            if (playing) PlayingDot()
            FitText(name, style = Type.headline, modifier = Modifier.weight(1f, fill = false))
            Badges(channel.badges)
        }
        if (watchHint || LocalRowHint.current != null) LastLine(watchHint) {}
    }
}

/**
 * A match: its title first, then the channel it tunes and the competition. Slots say they're
 * unconfirmed. [watchHint] puts [WATCH_HINT] in place of the competition.
 */
@Composable
private fun LiveRow(entry: LiveEntry, clock: State<Long>, playing: Boolean, watchHint: Boolean, focus: FocusRequester?, onFocused: () -> Unit, onClick: () -> Unit) {
    val programme = entry.programme
    val title = remember(entry) { if (programme != null) entry.title else displayName(entry.title) }
    val channel = remember(entry.tune.name) { displayName(entry.tune.name) }
    RowFrame(entry.tune, focus, onFocused, onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            if (playing) PlayingDot()
            if (programme != null) LiveMark()
            MatchCrests(programme?.title)
            FitText(title, style = Type.headline)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            FitText(if (programme == null) "Not confirmed" else if (entry.alsoOn.isNotEmpty()) "$channel · +${entry.alsoOn.size} more" else channel, style = Type.body, modifier = Modifier.weight(1f))
            if (programme != null) Progress(programme, clock)
        }
        LastLine(watchHint) { LiveComp(entry) }
    }
}

/** A Live now match's 🏆 competition, by its logo where one is known; else the group of the channel it came from. */
@Composable
private fun LiveComp(entry: LiveEntry) {
    if (entry.comp != null) CompName(entry.comp, Type.caption) else FitText(groupName(entry.via.group), style = Type.caption)
}

/** Repaints from the shared minute clock in the draw phase; the row never recomposes for it. */
@Composable
internal fun Progress(programme: Programme, clock: State<Long>) {
    ProgressLine(
        fraction = { (clock.value - programme.start).toFloat() / (programme.stop - programme.start).coerceAtLeast(1) },
        modifier = Modifier.width(INLINE_PROGRESS),
    )
}

/** On a row being moved (☰, Move). */
internal const val MOVE_HINT = "Moving · Up/Down, then OK"

/** On the playing row while the list previews: OK on it again goes full screen. */
internal const val WATCH_HINT = "Playing · OK to watch full screen"

/** The focused row's note in pick mode (multi-view): what OK does with it. */
internal val LocalRowHint = compositionLocalOf<String?> { null }

/** A row's last line; [WATCH_HINT] (or the pick mode's [LocalRowHint]) fades in in its place. */
@Composable
internal fun LastLine(watchHint: Boolean, line: @Composable () -> Unit) {
    val hint = LocalRowHint.current ?: WATCH_HINT.takeIf { watchHint } ?: return line()
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, Motion.spring(0.3f)) }
    Box(Modifier.graphicsLayer { alpha = shown.value }) { Hint(hint) }
}

/** A quiet note in place of a row's last line. */
@Composable
internal fun Hint(text: String) {
    FitText(text, style = Type.caption.copy(color = Palette.textSecondary))
}

/** The channel playing now. */
@Composable
internal fun PlayingDot() {
    Box(Modifier.size(DOT).clip(CircleShape).background(Palette.accent))
}

/** An empty list, said plainly at the height of a row. */
@Composable
private fun Empty(title: String, hint: String? = null) {
    Column(
        Modifier.fillMaxWidth().padding(top = Space.xl).height(ROW_HEIGHT).padding(horizontal = Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.xs, Alignment.CenterVertically),
    ) {
        Text(title, style = Type.body)
        if (hint != null) Text(hint, style = Type.caption)
    }
}

/** A group the groups column is about to show, once focus rests on it. */
private class PendingShow {
    var job: kotlinx.coroutines.Job? = null
    var target: Source? = null
}

private const val GROUP_REST_MS = 120L

/**
 * A column's rows built a few per frame: [perFrame] more each frame from [from] (the first on screen)
 * until about a screenful ([onScreen]) is, then all. A row not yet [ready] holds its place empty.
 */
internal class BuiltRows(private val perFrame: Int, private val onScreen: Int) {
    var from = 0
    private var key: String? = null
    private val count = mutableIntStateOf(Int.MAX_VALUE)
    private var job: Job? = null

    fun ready(index: Int) = index - from < count.intValue

    /** Starts building [key]'s rows, unless they are the ones built. */
    fun buildFor(key: String, scope: CoroutineScope) {
        if (key == this.key) return
        this.key = key
        job?.cancel()
        count.intValue = perFrame
        job = scope.launch {
            while (count.intValue < onScreen) {
                withFrameNanos {}
                count.intValue += perFrame
            }
            count.intValue = Int.MAX_VALUE
        }
    }
}
