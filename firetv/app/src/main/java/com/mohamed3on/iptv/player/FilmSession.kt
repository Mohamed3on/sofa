package com.mohamed3on.iptv.player

import com.mohamed3on.iptv.data.Debrid
import com.mohamed3on.iptv.data.DebridCandidate
import com.mohamed3on.iptv.data.DebridCheck
import com.mohamed3on.iptv.data.DebridError
import com.mohamed3on.iptv.data.DebridState
import com.mohamed3on.iptv.player.Capacity.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Where a film is: carried across every copy switch with the audio and subtitle languages chosen; [orAudio] when the file has no [audio] track. */
data class Place(val position: Long, val audio: String? = null, val subtitles: String? = null, val orAudio: String? = null)

/** The slice of a film player a [FilmSession] drives; ExoPlayer in the app, a fake in tests. Main thread only. */
interface FilmPlayer {
    /** Stops whatever is open, then opens [url] at [place] (position and languages) and plays. */
    fun open(url: String, place: Place)
    fun stop()
    fun pause()
    fun play()
    fun seek(position: Long)
    /** Where playback is now, with the languages chosen. */
    val place: Place
    /** The film's length in ms; 0 until known. */
    val duration: Long
    fun setListener(listener: Listener?)

    interface Listener {
        fun onPlaying()
        /** Playback stopped to buffer mid-film; not for the buffering after an open or a seek. */
        fun onRebuffer()
        fun onEnded()
        /** [StreamErrors.failureOf]; also [StreamFailure.NoAudio] for no playable audio track, [StreamFailure.Unsupported] for none in the language asked or a detected Dolby Vision profile 7. */
        fun onError(failure: StreamFailure)
    }
}

/**
 * A title's copies as discovery returns them; [debrid] is each debrid copy's torrent, by copy id; [originalLanguage] when known;
 * an episode's [intro] and [recap] (ms) and where its end [credits] start (ms), when known.
 */
class TitleCopies(
    val copies: List<Copy>, val debrid: Map<String, DebridCandidate> = emptyMap(), val originalLanguage: String? = null,
    val intro: LongRange? = null, val recap: LongRange? = null, val credits: Long? = null,
)

/** What a Skip button skips. */
enum class SkipPart { Intro, Recap }

enum class FilmPhase { Stopped, Loading, Starting, Playing, Ended, Unavailable }

/** R10's better copy once bandwidth recovers, R28's untested copy, KTD12's lighter copy for a pinned one that stalls. */
enum class OfferKind { Better, Untested, Lighter }

/** What the corner shows, one at a time: an offer for [FilmSession.OFFER_MS] (▲ accepts), the rest [FilmSession.INFO_MS]; a Skip while its part plays. */
sealed interface Notice {
    data class Offer(val kind: OfferKind, val copy: Copy) : Notice
    /** An automatic step-down; Versions can reverse it (R13). */
    data class SteppedDown(val copy: Copy) : Notice
    /** A pinned copy failed for good, so its pin was cleared (KTD12). */
    data class PinCleared(val copy: Copy) : Notice
    /** A copy chosen in Versions (or offered) couldn't start (its debrid doesn't hold it): the one playing stays, the choice unpinned. */
    data class CantStart(val copy: Copy) : Notice
    data class TrialResult(val copy: Copy, val works: Boolean) : Notice
    /** Subtitles in [language] ("en", "ar") came from OpenSubtitles and show (R30). */
    data class Subtitles(val language: String) : Notice
    /** The [service]'s premium ended: its copies are skipped (shown once an app session). */
    data class PremiumEnded(val service: String) : Notice
    /** The intro or recap plays; OK on the Skip button (or ▲) jumps to its [end] (R33). Goes once playback leaves it. */
    data class Skip(val part: SkipPart, val end: Long) : Notice
}

/** For telemetry (/api/app/events). */
sealed interface FilmEvent {
    /** [copy] failed, at start or mid-film ([playing]); [permanent] faults mark it (KTD7). */
    data class Failover(val copy: Copy, val failure: StreamFailure, val permanent: Boolean, val playing: Boolean) : FilmEvent
    data class StepDown(val from: Copy, val to: Copy) : FilmEvent
    /** An offer was answered: ▲ ([accepted]), or Back or fading. */
    data class Offer(val kind: OfferKind, val copy: Copy, val accepted: Boolean) : FilmEvent
    data class Trial(val copy: Copy, val works: Boolean) : FilmEvent
}

data class FilmState(
    val phase: FilmPhase = FilmPhase.Stopped,
    /** The copy open or opening. */
    val copy: Copy? = null,
    /** Every copy best first, for Versions; null until copies arrive. */
    val ranking: Ranking? = null,
    /** Debrid answers by copy id; a debrid copy without one is unchecked. */
    val debrid: Map<String, DebridCheck> = emptyMap(),
    /** The copy plays as a trial: Back in its first 30 s returns to the previous one (R28). */
    val trial: Boolean = false,
    val notice: Notice? = null,
)

/**
 * One film play from first copy to end (U9, KTD11): failover at start, step-down after stalls, the
 * offers, trials and fresh debrid links. What plays and what comes next on failure are the title's [plan]'s
 * answers (#14), the same the Plays line and Versions show: its copies within [WAIT_MS], else [play]'s
 * fallback IPTV copy starts; copies arriving later feed failover, Versions and offers (KTD13). The plan checks
 * the debrid copies and fetches links ahead, so failover rarely waits. One session per play; call from the main
 * thread, with [scope] there too. Only permanent faults, and trials (a failure in one too), write marks (KTD7, R28).
 */
class FilmSession(
    private val titleId: String,
    private val plan: CopyPlan,
    private val player: FilmPlayer,
    private val choices: CopyChoices,
    private val setup: () -> SetupProfile,
    private val bandwidth: Bandwidth,
    private val iptvUrl: (Copy) -> String,
    scope: CoroutineScope,
    private val now: () -> Long,
    private val report: (FilmEvent) -> Unit = {},
) {
    private val _state = MutableStateFlow(FilmState())
    val state: StateFlow<FilmState> = _state

    /** Everything this session runs; [stop] cancels it. */
    private val jobs = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    /** Opening a copy: resolving its URL, then the open. Cancelled by any other switch. */
    private var work: Job? = null

    private var phase = FilmPhase.Stopped
    /** Copies already marked for a permanent debrid refusal. */
    private val faulted = mutableSetOf<String>()

    private var current: Copy? = null
    /** Whether the open copy's player events still count. */
    private var attempt = false
    private var openPlace = Place(0)
    private var openedAt = 0L
    private var playingAt: Long? = null
    private val tried = mutableSetOf<String>()
    private val retried = mutableSetOf<String>()
    private var iptvBusy = false
    private var busyRetries = 0
    /** Where a switch that fails returns: the copy before an accepted offer, a trial or a pick in Versions. */
    private var back: Copy? = null
    private var trial = false
    private var trialFrom = 0L
    private val rebuffers = ArrayDeque<Long>()
    /** R10: the copy bandwidth held back (left by a step-down, or ranked out at start). */
    private var better: Copy? = null
    private var fitsSince: Long? = null
    private val spent = mutableSetOf<OfferKind>()
    private val notices = ArrayDeque<Notice>()
    private var noticeAt = 0L
    private var pausedAt: Long? = null
    private var stale = false
    private var panel = false
    /** The episode's skippable parts, by what they are; each offered once a play. */
    private var parts: Map<SkipPart, LongRange> = emptyMap()
    private val raised = mutableSetOf<SkipPart>()
    /** Play starts by then: Real-Debrid's check gets only what is left of [WAIT_MS] while IPTV could play (KTD13). */
    private var startBy = 0L

    init {
        player.setListener(object : FilmPlayer.Listener {
            override fun onPlaying() {
                if (attempt && phase == FilmPhase.Starting) playing()
            }
            override fun onRebuffer() {
                if (attempt && phase == FilmPhase.Playing) rebuffered()
            }
            override fun onEnded() {
                if (!attempt) return
                attempt = false
                phase = FilmPhase.Ended
                publish()
            }
            override fun onError(failure: StreamFailure) {
                if (attempt) failed(failure)
            }
        })
    }

    /**
     * Plays the title from [from]: the best copy the plan ranks, or [fallback] (its IPTV copy from the cached library) if
     * copies take longer than [WAIT_MS]; with no fallback it waits for them. Copies arriving later join as they come.
     */
    fun play(from: Place, fallback: Copy? = null) {
        phase = FilmPhase.Loading
        startBy = now() + WAIT_MS
        publish()
        jobs.launch {
            while (true) {
                delay(TICK_MS)
                tick()
            }
        }
        // Versions follows the plan; a play's next link is fetched ahead as its answers change.
        jobs.launch {
            plan.state.collect {
                it.answer?.copies?.let { c -> parts = listOfNotNull(c.recap?.let { SkipPart.Recap to it }, c.intro?.let { SkipPart.Intro to it }).toMap() }
                faults(it)
                publish()
                prepare()
            }
        }
        plan.fallback(fallback)
        work = jobs.launch {
            val loaded = suspend { plan.state.first { it.failed || it.asked || it.answer?.copies?.copies?.isNotEmpty() == true } }
            if (fallback == null) loaded() else withTimeoutOrNull(WAIT_MS) { loaded() }
            next(from)
        }
    }

    /** ▲ on an offer: switches to its copy at the same place (a trial for an untested one); on a Skip, jumps past its part. False when neither shows. */
    fun accept(): Boolean {
        (notices.firstOrNull() as? Notice.Skip)?.let {
            dismiss(accepted = true)
            player.seek(it.end)
            return true
        }
        val n = notices.firstOrNull() as? Notice.Offer ?: return false
        dismiss(accepted = true)
        if (n.kind == OfferKind.Better) better = null
        switch(n.copy, back = current, trial = n.kind == OfferKind.Untested)
        return true
    }

    /**
     * Back: in a trial's first 30 s it returns to the previous copy and marks the trial copy as not working
     * here; else it dismisses the notice showing (spending an offer). False when it did neither: leave the player.
     */
    fun back(): Boolean {
        val copy = current
        val to = back
        if (copy != null && to != null && undoable()) {
            trialOver(copy, works = false)
            reopen(to, here())
            return true
        }
        return dismiss(accepted = false) != null
    }

    /** Whether [back] would act now: a trial's first 30 s, or a notice showing. */
    val takesBack: Boolean get() = undoable() || notices.isNotEmpty()

    private fun undoable() = trial && current != null && back != null && here().position - trialFrom < TRIAL_MS

    /** Versions: plays [copyId] at the same place and pins it for the title (KTD12). */
    fun choose(copyId: String) {
        choices.pin(titleId, copyId)
        rerank()
        val to = plan.state.value.ranking?.copies?.firstOrNull { it.copy.id == copyId }?.copy ?: return
        if (to.id != current?.id || phase != FilmPhase.Playing) switch(to, back = current.takeIf { phase == FilmPhase.Playing }, trial = false)
    }

    fun pause() {
        if (pausedAt == null) pausedAt = now()
        player.pause()
    }

    /** After a pause over [PAUSE_MS], or an IP change meanwhile, a Real-Debrid copy gets a fresh link first (KTD2). */
    fun resume() {
        val at = pausedAt ?: return player.play()
        pausedAt = null
        val copy = current
        if (copy?.source == Source.Debrid && phase == FilmPhase.Playing && (stale || now() - at > PAUSE_MS)) reopen(copy, player.place) else player.play()
        stale = false
    }

    /** The network or VPN changed: a Real-Debrid link is tied to the old IP, so it is fetched afresh (KTD2). */
    fun ipChanged() {
        val copy = current ?: return
        if (copy.source != Source.Debrid || phase != FilmPhase.Playing) return
        if (pausedAt != null) stale = true else reopen(copy, player.place)
    }

    /** A word from outside the session (fetched subtitles), queued with its own notices. */
    fun inform(n: Notice) = notify(n)

    /** Options or Versions is open: no offer is raised meanwhile. */
    fun panel(open: Boolean) {
        panel = open
    }

    /** Re-ranks after a mark, pin or trial: the plan's ranking, so the title page follows. */
    private fun rerank() = plan.rerank()

    fun stop() {
        jobs.cancel()
        attempt = false
        player.stop()
        phase = FilmPhase.Stopped
        publish()
    }

    /**
     * Opens the best copy not yet tried at [place]; Unavailable when none is left. When only IPTV copies are left and
     * the provider's one connection was taken (often the live stream just closed), they are tried again 5 s on, 3 times.
     */
    private suspend fun next(place: Place) {
        while (true) {
            val r = candidate()
            if (r == null) {
                if (!iptvBusy || busyRetries >= BUSY_RETRIES) return unavailable()
                busyRetries++
                delay(BUSY_RETRY_MS)
                iptvBusy = false
                tried.removeAll { it.startsWith("iptv:") }
                continue
            }
            tried += r.copy.id
            val url = url(r.copy) ?: continue
            // R28: every original is untested, so the best of them plays as a trial with the working dub to go back to.
            val ranking = plan.state.value.ranking
            val dub = ranking?.fallback?.copy
            if (current == null && r.copy.id == ranking?.pick?.copy?.id && r.support == Support.Unsure && dub != null) {
                trial = true
                back = dub
                trialFrom = place.position
            }
            return open(r.copy, url, place)
        }
    }

    /** The plan's next copy: the best not yet tried that can play (no IPTV while the provider's connection is taken). */
    private fun candidate(): Ranked? = plan.state.value.pick(tried, iptvBusy)

    /** [copy]'s URL: IPTV's at once; a debrid copy's once ready, from a link fetched ahead or a fresh one. Null when it can't play. */
    private suspend fun url(copy: Copy, chosen: Boolean = false): String? {
        if (copy.source == Source.Iptv) return iptvUrl(copy)
        ready(copy, chosen) ?: return null
        return try {
            plan.link(copy)
        } catch (e: DebridError) {
            null.also { fault(copy) }
        }
    }

    private suspend fun ready(copy: Copy, chosen: Boolean = false): DebridCheck.Ready? {
        if (copy.id !in plan.state.value.debrid) {
            // While an IPTV copy could play instead, the debrid gets no longer than copies do, and at the start only
            // what is left of Play's wait (KTD13); a copy chosen (Versions, an offer) waits, the one playing kept meanwhile.
            val other = !chosen && !iptvBusy && plan.state.value.ranking?.copies.orEmpty().any { it.copy.source == Source.Iptv && it.copy.id !in tried }
            if (other) withTimeoutOrNull(if (current == null) startBy - now() else WAIT_MS) { plan.check(copy) } else plan.check(copy)
        }
        faults(plan.state.value)
        return plan.state.value.debrid[copy.id] as? DebridCheck.Ready
    }

    /** Copies the debrid refused for good, the title page's check included: marked once (KTD7), a pin on one cleared. */
    private fun faults(p: Prepared) {
        for (r in p.ranking?.copies.orEmpty()) {
            if ((p.debrid[r.copy.id] as? DebridCheck.Failed)?.error?.permanent == true && faulted.add(r.copy.id)) fault(r.copy)
        }
    }

    /** Fetches links ahead for the copies a failover or a step-down would open next. */
    private fun prepare() {
        val copy = current?.takeIf { phase == FilmPhase.Playing } ?: return
        plan.prefetch(listOfNotNull(candidate()?.copy, usable().lighter(copy)?.copy))
    }

    private fun open(copy: Copy, url: String, at: Place) {
        // No track chosen yet: the title's audio language (chosen on its page, or "Arabic dub first"; a file may have it
        // unlisted), then the film's own (R2), not the dub a multi-language file flags as default.
        val originalLanguage = plan.state.value.answer?.copies?.originalLanguage
        val want = plan.state.value.ranking?.language?.let(::audioTrack)
        val place = when {
            at.audio != null && at.audio != "und" -> at // "und": a track without a language, none to keep
            want != null -> at.copy(audio = want, orAudio = originalLanguage)
            else -> at.copy(audio = originalLanguage)
        }
        current = copy
        openPlace = place
        attempt = true
        openedAt = now()
        playingAt = null
        rebuffers.clear()
        phase = FilmPhase.Starting
        publish()
        player.open(url, place)
    }

    private fun playing() {
        val copy = current!!
        playingAt = now()
        phase = FilmPhase.Playing
        tried.clear()
        tried += copy.id
        retried.clear()
        iptvBusy = false
        busyRetries = 0
        if (!trial) back = null
        if (better == null) usable().takeIf { it.pick?.copy?.id == copy.id }?.heavier?.let { better = it.copy }
        publish()
        prepare()
    }

    private fun failed(failure: StreamFailure) {
        val copy = current!!
        if (failure == StreamFailure.NoLanguage) {
            // Only the title's chosen language (or "Arabic dub first") moves on: the copy is remembered as lacking it.
            // A copy that never claimed it plays on, in the film's own language.
            val want = plan.state.value.ranking?.language?.takeIf { speaks(copy, it, plan.state.value.answer?.copies?.originalLanguage) } ?: return
            // A track carried over (the copy before's, in Versions or a resume) isn't the chosen language: the copy is asked for that instead.
            if (openPlace.audio != audioTrack(want)) return reopen(copy, openPlace.copy(audio = null))
            choices.lacks(copy, audioTrack(want))
            rerank()
        }
        val playing = phase == FilmPhase.Playing
        // The next copy asks for the title's language again, not the track this file fell back to.
        val place = here().let { if (failure == StreamFailure.NoLanguage) it.copy(audio = null) else it }
        attempt = false
        work?.cancel()
        player.stop()
        val permanent = StreamErrors.permanent(failure)
        report(FilmEvent.Failover(copy, failure, permanent, playing))
        // No track played: this setup doesn't play that audio (DTS, TrueHD); copies listing it rank lower here (this one is marked).
        if (failure is StreamFailure.NoAudio) for (f in failure.audio.mapNotNull(::audioFamily)) choices.learn(setup(), f, failed = true)
        if (permanent) fault(copy)
        val to = back
        when {
            to != null -> {
                if (trial) trialOver(copy, works = false) // R28: any error in a trial marks it, transient too
                reopen(to, place)
            }
            // The provider's one connection is taken: every IPTV copy would get the same answer.
            copy.source == Source.Iptv && StreamErrors.recovery(failure) == Recovery.Busy -> {
                iptvBusy = true
                restart { next(place) }
            }
            !permanent && failure != StreamFailure.NoLanguage && retried.add(copy.id) -> reopen(copy, place)
            else -> restart { next(place) }
        }
    }

    /** Reopens [copy] at [place] with a fresh URL, else the next copy. */
    private fun reopen(copy: Copy, place: Place) {
        back = null
        trial = false
        plan.forget(copy) // a retry gets a fresh link
        restart { url(copy)?.let { open(copy, it, place) } ?: next(place) }
    }

    private fun restart(block: suspend () -> Unit) {
        attempt = false
        work?.cancel()
        phase = FilmPhase.Starting
        openedAt = now()
        publish()
        work = jobs.launch { block() }
    }

    /** Switches to [to] once its URL is ready; what plays keeps playing until then, and stays if it can't be had. */
    private fun switch(to: Copy, back: Copy?, trial: Boolean) {
        work?.cancel()
        work = jobs.launch {
            val url = url(to, chosen = true) ?: return@launch cantStart(to)
            val place = here()
            this@FilmSession.back = back
            this@FilmSession.trial = trial
            trialFrom = place.position
            tried += to.id
            open(to, url, place)
        }
    }

    /** [copy] was chosen and can't start: said, rather than nothing happening, and its pin cleared so Play doesn't keep trying it. */
    private fun cantStart(copy: Copy) {
        if (choices.pin(titleId) == copy.id) {
            choices.pin(titleId, null)
            rerank()
        }
        notify(Notice.CantStart(copy))
    }

    /** A permanent fault: the copy is marked for this setup, and a pin on it cleared (KTD7, KTD12). */
    private fun fault(copy: Copy) {
        choices.mark(setup(), copy, works = false)
        if (choices.pin(titleId) == copy.id) {
            choices.pin(titleId, null)
            notify(Notice.PinCleared(copy))
        }
        rerank()
    }

    private fun unavailable() {
        attempt = false
        player.stop()
        phase = FilmPhase.Unavailable
        publish()
    }

    /** R9: three rebuffers within ten minutes step down to the next lighter working copy, unless this one is pinned. */
    private fun rebuffered() {
        val t = now()
        rebuffers.addLast(t)
        while (t - rebuffers.first() > STALL_WINDOW_MS) rebuffers.removeFirst()
        if (rebuffers.size < STALLS || trial) return
        rebuffers.clear()
        val from = current!!
        val to = usable().lighter(from)?.copy ?: return
        if (choices.pin(titleId) == from.id) return offer(OfferKind.Lighter, to)
        report(FilmEvent.StepDown(from, to))
        notify(Notice.SteppedDown(to))
        better = from
        fitsSince = null
        switch(to, back = null, trial = false)
    }

    private fun tick() {
        val t = now()
        // A Skip stays while its part plays ([skips] takes it away).
        notices.firstOrNull()?.let { if (it !is Notice.Skip && t - noticeAt >= if (it is Notice.Offer) OFFER_MS else INFO_MS) dismiss(accepted = false) }
        if (attempt && phase == FilmPhase.Starting && t - openedAt >= START_MS) return failed(StreamFailure.Timeout)
        if (phase != FilmPhase.Playing) return
        val copy = current ?: return
        if (trial && player.place.position - trialFrom >= TRIAL_MS) {
            back = null
            trialOver(copy, works = true)
        }
        skips()
        offers(t, copy)
    }

    /** R33: while the intro or recap plays (not in its last seconds), its Skip shows, once a play; leaving the part takes it away. */
    private fun skips() {
        val at = player.place.position
        val head = notices.firstOrNull()
        if (head is Notice.Skip && parts[head.part]?.let { at in it.first until it.last - TAIL_MS } != true) dismiss(accepted = false)
        val showing = notices.firstOrNull()
        notices.removeAll { it is Notice.Skip && it !== showing && parts[it.part]?.contains(at) != true }
        for ((part, w) in parts) {
            if (part in raised || pausedAt != null || at < w.first || at >= w.last - TAIL_MS) continue
            raised += part
            notify(Notice.Skip(part, w.last))
        }
    }

    /** KTD11: an offer waits for 5 min of bandwidth 1.3× the better copy's, or 10 s of play for an untested one; never late in the film. */
    private fun offers(t: Long, copy: Copy) {
        val dur = player.duration
        val quiet = attempt && pausedAt == null && !panel && !trial && back == null && (dur <= 0 || dur - player.place.position > LATE_MS)
        val b = better
        val mbps = bandwidth.sessionMbps
        fitsSince = if (quiet && b != null && b.mbps > 0f && mbps != null && mbps >= MARGIN * b.mbps) fitsSince ?: t else null
        // A waiting offer whose window passed goes unspent.
        val head = notices.firstOrNull()
        if (!quiet) notices.removeAll { it is Notice.Offer && it !== head }
        if (!quiet || notices.isNotEmpty()) return
        if (b != null && OfferKind.Better !in spent && fitsSince?.let { t - it >= FIT_MS } == true) return offer(OfferKind.Better, b)
        if (OfferKind.Untested in spent || t - (playingAt ?: t) < SETTLE_MS || choices.pin(titleId) == copy.id) return
        usable().offer?.copy?.takeIf { it.id != copy.id }?.let { offer(OfferKind.Untested, it) }
    }

    private fun offer(kind: OfferKind, copy: Copy) {
        if (kind !in spent && notices.none { it is Notice.Offer }) notify(Notice.Offer(kind, copy))
    }

    /** One notice at a time, by priority: a Skip, trial result, offers, then information; the one showing stays. */
    private fun notify(n: Notice) {
        fun rank(x: Notice) = when (x) {
            is Notice.Skip -> 0
            is Notice.TrialResult -> 1
            is Notice.Offer -> 2
            else -> 3
        }
        notices.add((1 until notices.size).firstOrNull { rank(notices[it]) > rank(n) } ?: notices.size, n)
        if (notices.size == 1) noticeAt = now()
        publish()
    }

    private fun dismiss(accepted: Boolean): Notice? {
        val n = notices.removeFirstOrNull() ?: return null
        if (n is Notice.Offer) {
            spent += n.kind
            report(FilmEvent.Offer(n.kind, n.copy, accepted))
        }
        noticeAt = now()
        publish()
        return n
    }

    /** R28: a trial's result is marked for this setup. */
    private fun trialOver(copy: Copy, works: Boolean) {
        choices.mark(setup(), copy, works)
        rerank()
        trial = false
        report(FilmEvent.Trial(copy, works))
        notify(Notice.TrialResult(copy, works))
    }

    private fun here() = if (phase == FilmPhase.Playing) player.place else openPlace

    /** The copies that can play now: IPTV's, and the debrid's that are ready (R1). */
    private fun usable() = plan.usable()

    private fun publish() {
        val p = plan.state.value
        _state.value = FilmState(phase, current, p.ranking, p.debrid, trial, notices.firstOrNull())
    }

    companion object {
        /** Play waits this long for copies, and for Real-Debrid while IPTV could play instead (KTD13). */
        const val WAIT_MS = 4_000L
        const val OFFER_MS = 12_000L
        const val INFO_MS = 6_000L
        /** A Skip leaves this long before its part ends: by then the jump saves nothing. */
        private const val TAIL_MS = 3_000L
        private const val TICK_MS = 1_000L
        /** First frame of a film, over a connect and the 1.5 s start buffer. */
        private const val START_MS = 15_000L
        private const val BUSY_RETRY_MS = 5_000L
        private const val BUSY_RETRIES = 3
        private const val STALLS = 3
        private const val STALL_WINDOW_MS = 10 * 60_000L
        private const val MARGIN = 1.3f
        private const val FIT_MS = 5 * 60_000L
        private const val LATE_MS = 15 * 60_000L
        private const val SETTLE_MS = 10_000L
        private const val TRIAL_MS = 30_000L
        private const val PAUSE_MS = 10 * 60_000L
    }
}
