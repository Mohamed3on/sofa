package com.mohamed3on.iptv.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Movies & Shows searches that led somewhere, newest first: offered under an empty search box. */
class RecentSearches(private val store: KeyValueStore) {
    private val _all = MutableStateFlow(read())
    val all: StateFlow<List<String>> = _all

    /** Keeps [query] first (once, whatever its case), the oldest dropping off past [CAP]. */
    fun add(query: String) {
        val q = query.trim().takeIf { it.isNotEmpty() } ?: return
        _all.value = (listOf(q) + _all.value.filterNot { it.equals(q, ignoreCase = true) }).take(CAP)
        store.put(KEY, _all.value.joinToString("\n"))
    }

    /** Reads them again: another device's came in ([StateSync]). */
    fun reload() {
        _all.value = read()
    }

    private fun read() = store.get(KEY)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    private companion object {
        const val KEY = "movies.search.recent"
        const val CAP = 8
    }
}
