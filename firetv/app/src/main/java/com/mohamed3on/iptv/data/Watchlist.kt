package com.mohamed3on.iptv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * The Watchlist as the stick shows it: the box's history (Trakt's, then Letterboxd's), with the owner's own adds and
 * removals applied at once (optimistic) until the box's next history has them. [toggle] writes through the box to Trakt.
 */
class Watchlist(private val sync: TitlesClient, private val scope: CoroutineScope) {
    /** Changes not yet in the box's history: title id → on the watchlist or not. */
    private val pending = MutableStateFlow<Map<TitleId, Boolean>>(emptyMap())

    val ids: StateFlow<Set<TitleId>> = combine(sync.history.items, pending) { h, p ->
        (h?.watchlist?.map { it.titleId }.orEmpty().toSet() - p.filterValues { !it }.keys) + p.filterValues { it }.keys
    }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** On the list only through Letterboxd, which has no write API: [toggle] can't take them off. */
    val letterboxd: StateFlow<Set<TitleId>> = sync.history.items.map { it?.letterboxd.orEmpty() }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** Films and shows only (an episode or a provider-only title can't go on it). */
    fun canHold(title: TitleId) = Regex("^(movie|tv):\\d+$").matches(title)

    /** False when [title] is on the list only through Letterboxd (nothing changes). */
    fun toggle(title: TitleId): Boolean {
        if (!canHold(title)) return true
        val on = title !in ids.value
        if (!on && title in letterboxd.value) return false
        pending.update { it + (title to on) }
        scope.launch {
            val ok = sync.setWatchlist(title, on)
            if (ok) sync.history.sync(Duration.ZERO)
            pending.update { it - title } // the box's history has it now, or the change failed and is undone
        }
        return true
    }
}
