package com.mohamed3on.iptv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Watched marks and the owner's ratings as the stick shows them: its own marks with the box's history (Trakt,
 * Letterboxd, IMDb), the owner's changes applied at once (optimistic) and written through the box to Trakt, then
 * the history synced again ([resync]), like [Watchlist]. A change that fails is undone.
 */
class Marks(
    private val sync: TitlesClient,
    private val films: WatchedStore,
    private val episodes: WatchedStore,
    private val scope: CoroutineScope,
    private val resync: suspend () -> Unit,
) {
    /** Marked unwatched here, until the box's history drops them. */
    private val unwatched = MutableStateFlow<Set<TitleId>>(emptySet())
    /** Ratings changed here, until the box's history has them: 0 cleared. */
    private val rated = MutableStateFlow<Map<TitleId, Int>>(emptyMap())

    /** Titles watched: the stick's own marks and the box's. */
    val watched: StateFlow<Set<TitleId>> = combine(sync.history.items, films.ids, episodes.ids, unwatched) { h, f, e, u ->
        f + e + (h?.watched.orEmpty() - u)
    }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** The owner's rating per title, out of 10. */
    val ratings: StateFlow<Map<TitleId, Int>> = combine(sync.history.items, rated) { h, r ->
        if (r.isEmpty()) h?.ratings.orEmpty() else (h?.ratings.orEmpty() + r).filterValues { it > 0 }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** A film, show or episode Trakt can hold (not a provider-only title). */
    fun canMark(title: TitleId) = MARKABLE.matches(title)

    /**
     * Marks [title] watched or not: a film or episode, or a season ("tv:N:sS") or show ("tv:N") watched, [ticks]
     * its episodes as the page lists them.
     */
    fun setWatched(title: TitleId, on: Boolean, ticks: List<TitleId> = listOf(title)) {
        val store = if (title.startsWith("movie:")) films else episodes
        val changed = ticks.filter { (it in store.ids.value) != on }
        if (on) store.markAll(changed) else {
            store.unmarkAll(changed)
            unwatched.update { it + ticks }
        }
        scope.launch {
            val ok = sync.mark(title, watched = on)
            if (ok) resync() else if (on) store.unmarkAll(changed) else store.markAll(changed)
            unwatched.update { it - ticks.toSet() }
        }
    }

    /** Rates [title] 1–10, or clears its rating (null). */
    fun rate(title: TitleId, rating: Int?) {
        rated.update { it + (title to (rating ?: 0)) }
        scope.launch {
            if (sync.mark(title, rating = rating, rate = true)) resync()
            rated.update { it - title }
        }
    }

    private companion object {
        val MARKABLE = Regex("^(movie:\\d+|tv:\\d+(:s\\d+e\\d+)?)$")
    }
}
