package com.mohamed3on.iptv.player

import com.mohamed3on.iptv.data.CopiesAnswer
import com.mohamed3on.iptv.data.Debrid
import com.mohamed3on.iptv.data.DebridCandidate
import com.mohamed3on.iptv.data.DebridCheck
import com.mohamed3on.iptv.data.DebridError
import com.mohamed3on.iptv.data.DebridState
import com.mohamed3on.iptv.data.ServerCopy
import com.mohamed3on.iptv.player.Capacity.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** A title's copies as the box answered, mapped for the ranker and [FilmSession], with what Versions shows of each. */
class TitleAnswer(val copies: TitleCopies, val debridPending: Boolean = false, val debridUnavailable: Boolean = false, val iptvUnavailable: Boolean = false, private val server: Map<String, ServerCopy> = emptyMap()) {
    /** An IPTV copy's container ("mkv"). */
    fun ext(id: String): String? = server[id]?.ext
    /** A Real-Debrid copy's size in bytes; 0 unknown. */
    fun size(id: String): Long = server[id]?.size ?: 0
    /** The release or catalogue name. */
    fun name(id: String): String = server[id]?.name.orEmpty()
}

/**
 * A title's [CopyPlan] as it stands: its copies ranked for this setup, the debrid answers, and [plays], the copy
 * Play opens, once [debridChecked] (or there is nothing on a debrid). [failed] when the box could not answer: Play then
 * starts the library's IPTV copy. [asked] once the box is asked no more.
 */
data class Prepared(
    val answer: TitleAnswer? = null,
    val ranking: Ranking? = null,
    val debrid: Map<String, DebridCheck> = emptyMap(),
    val debridChecked: Boolean = false,
    val plays: Copy? = null,
    val failed: Boolean = false,
    val asked: Boolean = false,
    /** The debrid premium has ended: its copies don't play. */
    val debridOff: Boolean = false,
    /** The last check ran out of time: copies it didn't answer are still worth a check of their own. */
    val debridSlow: Boolean = false,
) {
    /**
     * [c] can play as far as is known: IPTV's (unless [iptvBusy]), a debrid copy that is ready, or one not answered
     * yet while the check runs (or ran out of time) or that the box flags cached (likely ready; Play checks it when it comes to it).
     */
    fun playable(c: Copy, iptvBusy: Boolean = false): Boolean {
        if (c.source == Source.Iptv) return !iptvBusy
        val torrent = answer?.copies?.debrid?.get(c.id) ?: return false
        return !debridOff && debrid[c.id].let { it is DebridCheck.Ready || it == null && (!debridChecked || debridSlow || torrent.cached) }
    }

    /** What plays now (#14): the best copy not in [skip] that can play; a pin beats "won't play here". The one answer for the Plays line, Versions and Play. */
    fun pick(skip: Set<String> = emptySet(), iptvBusy: Boolean = false): Ranked? =
        ranking?.copies?.firstOrNull { r -> r.copy.id !in skip && (r.support != Support.Wont || r.pinned) && playable(r.copy, iptvBusy) }

    /**
     * What Play opens (#22), [late] once it has waited [FilmSession.WAIT_MS]: [pick] once the debrid check is in; past the
     * wait with the check still out, the best that plays without it (KTD13); before then, not known yet. The one answer
     * for the Plays line, Versions and a play's first open.
     */
    fun opens(late: Boolean, skip: Set<String> = emptySet(), iptvBusy: Boolean = false): Ranked? = when {
        debridChecked -> pick(skip, iptvBusy)
        late -> pick(skip + ranking?.copies.orEmpty().filter { it.copy.source != Source.Iptv && debrid[it.copy.id] !is DebridCheck.Ready }.map { it.copy.id }, iptvBusy)
        else -> null
    }
}

/**
 * One title's copies (#14, KTD13): the box's answers (asked again shortly while its debrid side is pending), the
 * ranking for this setup, the debrid answers and one link cache. It answers what plays now ([Prepared.pick]) and
 * what next when a copy fails, for the title page, Versions and Play alike; a play's own choices (failover order,
 * step-down, offers, trials) stay in [FilmSession]. Checks and links run on [scope], off the main thread.
 */
class CopyPlan(
    val title: String,
    private val debrid: Debrid,
    private val choices: CopyChoices,
    private val setup: () -> SetupProfile,
    private val typical: (Source) -> Float?,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    /** The stick's public IP (VPN on or off): a link made on another is never used (KTD2). */
    private val ip: () -> Any? = { null },
    /** A link fetched ahead, for [FilmHeads] to read the file's head. */
    private val linked: suspend (String) -> Unit = {},
) {
    private class Link(val at: Long, val ip: Any?, val url: Deferred<String?>)

    /** When it was made, or its last play ended: [FilmCopies] keeps it 30 min from then, and all through a play. */
    @Volatile internal var kept = now()
    /** Plays holding it ([playing]). */
    @Volatile internal var plays = 0
    private val _state = MutableStateFlow(Prepared(debridOff = debrid.status.value.state == DebridState.NotPremium))
    val state: StateFlow<Prepared> = _state
    @Volatile private var checking: Job? = null
    @Volatile private var fallback: Copy? = null
    private val links = ConcurrentHashMap<String, Link>()
    /** [usable] for the state it was ranked from: ranked once per change, not on every tick of a play. */
    @Volatile private var usableFor: Pair<Prepared, Ranking>? = null

    /**
     * A play holds its plan ([on]) until it ends: nothing replaces it meanwhile, so its links, IPTV containers and what
     * it learned stay the play's, and the title page shows them after (#22).
     */
    @Synchronized
    fun playing(on: Boolean) {
        plays += if (on) 1 else -1
        if (!on) kept = now()
    }

    /** The box's answer: ranked, then the unchecked debrid copies are checked. */
    @Synchronized
    fun answered(a: TitleAnswer) {
        _state.update { it.copy(answer = a, debridChecked = it.debridChecked || a.copies.debrid.isEmpty()) }
        rerank()
    }

    /** The box couldn't answer; [asked] once it is asked no more. */
    internal fun ended(failed: Boolean) = _state.update { it.copy(failed = it.failed || failed && it.answer == null, asked = true) }

    /** The library's IPTV copy, ranked with the rest when the box doesn't list it. */
    @Synchronized
    fun fallback(copy: Copy?) {
        if (copy == null || fallback != null) return
        fallback = copy
        rerank()
    }

    /** Ranks again (a pin, mark, correction or trial changed), then checks what became worth checking. */
    @Synchronized
    fun rerank() {
        val p = _state.value
        val t = p.answer?.copies
        val copies = t?.copies.orEmpty() + listOfNotNull(fallback?.takeIf { f -> t?.copies.orEmpty().none { it.id == f.id } })
        val ranking = choices.rank(title, copies, t?.originalLanguage, setup(), typical)
        _state.update { it.copy(ranking = ranking).settled() }
        startCheck()
        prefetch()
    }

    @Synchronized
    internal fun debridOff(off: Boolean) = _state.update { it.copy(debridOff = off).settled() }

    /** The copies that can play now, ranked: IPTV's and the ready debrid ones (R1), for step-downs and offers. */
    fun usable(): Ranking {
        val p = _state.value
        usableFor?.takeIf { it.first === p }?.let { return it.second }
        val copies = p.ranking?.copies.orEmpty().map { it.copy }.filter { it.source == Source.Iptv || p.debrid[it.id] is DebridCheck.Ready }
        return choices.rank(title, copies, p.answer?.copies?.originalLanguage, setup(), typical).also { usableFor = p to it }
    }

    /**
     * [copy]'s debrid answer for Play, checking first when it has none, with no time limit (Play bounds its own wait);
     * still unanswered, the first copy of a batch: the account refuses.
     */
    suspend fun check(copy: Copy): DebridCheck {
        if (copy.id !in _state.value.debrid) {
            checking?.join()
            if (copy.id !in _state.value.debrid) startCheck(copy.id, capped = false)?.join()
            _state.update { if (copy.id in it.debrid) it else it.copy(debrid = it.debrid + (copy.id to DebridCheck.NotCached)).settled() }
        }
        return _state.value.debrid.getValue(copy.id)
    }

    /**
     * Asks the debrid about the unchecked copies in rank order ([also] among them), [capped] at [CHECK_MS] ahead of Play:
     * a check that hangs must not hold the Plays line. One at a time; null when nothing needs asking.
     */
    @Synchronized
    private fun startCheck(also: String? = null, capped: Boolean = true): Job? {
        checking?.takeIf { it.isActive }?.let { return it }
        val p = _state.value
        val torrents = p.answer?.copies?.debrid ?: return null
        val ids = p.ranking?.copies.orEmpty()
            .filter { it.copy.id in torrents && it.copy.id !in p.debrid && (it.support != Support.Wont || it.pinned || it.copy.id == also) }.map { it.copy.id }
        if (ids.isEmpty()) {
            if (!p.debridChecked) _state.update { it.copy(debridChecked = true).settled() }
            return null
        }
        return scope.launch {
            val ask = suspend { runCatching { debrid.check(ids.map(torrents::getValue)) }.getOrNull() ?: emptyMap() }
            val answers = if (capped) withTimeoutOrNull(CHECK_MS) { ask() } else ask()
            val byId = ids.mapNotNull { id -> answers?.get(torrents.getValue(id))?.let { id to it } }.toMap()
            _state.update { it.copy(debrid = it.debrid + byId, debridChecked = true, debridSlow = answers == null).settled() }
            prefetch()
        }.also { checking = it }
    }

    /** Drops [copy]'s link fetched ahead: a retry of it gets a fresh one. */
    fun forget(copy: Copy) {
        links.remove(copy.id)
    }

    /**
     * [copy]'s link: one fetched ahead, if under [LINK_MS] old and made on this IP (used once: a retry gets a fresh
     * one), else a fresh one. Null when it isn't ready or can't be had; throws [DebridError] when the debrid refuses for good.
     */
    suspend fun link(copy: Copy): String? {
        val ready = _state.value.debrid[copy.id] as? DebridCheck.Ready ?: return null
        links.remove(copy.id)?.takeIf { it.fresh() }?.url?.await()?.let { return it }
        repeat(2) {
            try {
                return debrid.link(ready)
            } catch (e: DebridError) {
                if (e.permanent) throw e
            }
        }
        return null
    }

    /** Fetches ahead the links of [copies] (by default what plays now), unless a fresh one is kept. */
    @Synchronized
    fun prefetch(copies: List<Copy> = listOfNotNull(_state.value.plays)) {
        for (copy in copies) {
            val ready = _state.value.debrid[copy.id] as? DebridCheck.Ready ?: continue
            if (links[copy.id]?.fresh() == true) continue
            links[copy.id] = Link(now(), ip(), scope.async { runCatching { debrid.link(ready) }.getOrNull()?.also { scope.launch { linked(it) } } })
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun Link.fresh() = now() - at < LINK_MS && ip == ip() && !(url.isCompleted && url.getCompleted() == null)

    /** Settles [Prepared.plays] once the check is in; kept as it was while one runs. */
    private fun Prepared.settled(): Prepared = if (ranking == null || !debridChecked) this else copy(plays = pick()?.copy)

    companion object {
        const val CHECK_MS = 4_000L
        /** A link fetched ahead is used within this long, on the IP it was made on. */
        const val LINK_MS = 10 * 60_000L
    }
}

/**
 * Every title's [CopyPlan], kept in memory a while (KTD13). Opening a title page, resting on an Up next card or
 * starting an episode (for the next one) [warm]s a title: copies from the box, the ranking, the debrid check and,
 * when Play would open a debrid copy, its link, all off the main thread, so Play opens a copy at once.
 */
class FilmCopies(
    private val fetch: suspend (String) -> Result<CopiesAnswer>,
    private val debrid: Debrid,
    private val choices: CopyChoices,
    private val capacity: Capacity,
    private val setup: () -> SetupProfile,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val ip: () -> Any? = { null },
    private val linked: suspend (String) -> Unit = {},
) {
    private val plans = ConcurrentHashMap<String, CopyPlan>()
    /** Every plan still held, a play's past [KEEP_MS] too: each hears when the premium ends. */
    private val held = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(java.util.WeakHashMap<CopyPlan, Boolean>()))

    init {
        scope.launch { debrid.status.collect { s -> synchronized(held) { held.toList() }.forEach { it.debridOff(s.state == DebridState.NotPremium) } } }
    }

    /** [title]'s plan: a recent one kept, else a new one, asking the box now. */
    fun plan(title: String): CopyPlan = synchronized(plans) {
        plans[title]?.takeIf { it.plays > 0 || now() - it.kept < KEEP_MS && !it.state.value.failed }
            ?: CopyPlan(title, debrid, choices, setup, { capacity.typicalMbps(it, now()) }, scope, now, ip, linked).also {
                plans[title] = it
                held += it
                scope.launch { gather(it) }
            }
    }.also { it.prefetch() } // again once the last link is old

    /** [title] as it is prepared; preparing it now unless a recent answer is kept. */
    fun state(title: String): StateFlow<Prepared> = plan(title).state

    /** Prepares [title] ahead of Play: copies, ranking and the debrid check. */
    fun warm(title: String) {
        plan(title)
    }

    /** Asks the box for [title]'s copies again (a play measured one), keeping its plan and debrid answers. */
    fun refresh(title: String) {
        plans[title]?.let { scope.launch { gather(it) } }
    }

    /** Ranks [title] again after a pin, mark or correction outside a play. */
    fun rerank(title: String) {
        plans[title]?.let { scope.launch { it.rerank() } }
    }

    private suspend fun gather(plan: CopyPlan) {
        var failed = false
        try {
            repeat(ASKS) { i ->
                val a = fetch(plan.title).getOrNull()?.let(::answerOf)
                if (a == null) {
                    failed = true
                    return
                }
                plan.answered(a)
                if (!a.debridPending || i == ASKS - 1) return
                delay(REASK_MS)
            }
        } finally {
            plan.ended(failed)
        }
    }

    private companion object {
        const val KEEP_MS = 30 * 60_000L
        /** A pending debrid side is asked again this much later, at most [ASKS] asks in all. */
        const val REASK_MS = 2_500L
        const val ASKS = 3
    }
}

/** The box's answer as copies: ids stable across plays ("iptv:<stream>", "rd:<hash>:<file>"), names the ranker's. */
fun answerOf(a: CopiesAnswer): TitleAnswer {
    val server = a.copies.associateBy(::copyId)
    val copies = server.map { (id, s) -> copyOf(id, s) }
    val rd = server.filterValues { it.src == "rd" }.mapValues { (_, s) -> DebridCandidate(s.hash, s.file, s.filename, s.cached) }
    return TitleAnswer(
        TitleCopies(copies, rd, a.originalLanguage.ifEmpty { null }, span(a.intro), span(a.recap), a.credits.firstOrNull()?.takeIf { it > 0 }),
        debridPending = a.sources.rd == "pending", debridUnavailable = a.sources.rd == "unavailable", iptvUnavailable = a.sources.iptv == "unavailable",
        server = server,
    )
}

/** A [start, end] ms pair from the box, null when absent. */
private fun span(w: List<Long>) = w.takeIf { it.size == 2 && it[1] > it[0] }?.let { it[0]..it[1] }

private fun copyId(s: ServerCopy) = if (s.src == "rd") "rd:${s.hash}" + (s.file?.let { ":$it" } ?: "") else "iptv:${s.id}"

private fun copyOf(id: String, s: ServerCopy) = Copy(
    id = id,
    source = if (s.src == "rd") Source.Debrid else Source.Iptv,
    resolution = s.res,
    hdr = s.hdr.map { HDR[it] ?: it.uppercase() },
    dvProfile = s.dvProfile,
    codec = CODECS[s.codec] ?: s.codec,
    audio = AUDIO[s.audio] ?: s.audio,
    channels = s.ch,
    atmos = s.atmos,
    languages = s.langs,
    englishSubs = "en" in s.subs,
    mbps = (s.kbps / 1000).toFloat(),
    seeds = s.seeds,
)

private val HDR = mapOf("dv" to "DV", "hdr10+" to "HDR10+", "hdr10" to "HDR10", "hlg" to "HLG")
private val CODECS = mapOf("mpeg2" to "mpeg2video")
private val AUDIO = mapOf("dts-hd" to "dtshd")
