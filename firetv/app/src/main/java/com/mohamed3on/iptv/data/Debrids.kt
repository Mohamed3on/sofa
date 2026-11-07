package com.mohamed3on.iptv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Two debrids, whichever has the copy: a check asks every one that can serve (not without a key, refused, denied
 * or lapsed) at once and takes a ready copy from either, so the best-ranked copy plays whoever holds it and one
 * going down leaves the other playing. The best copy ready on [first] needs no other answer; a slow service gets
 * [ASK_MS]. A copy is not ready only once every service says so. A ready copy is linked by the service that found
 * it. [status] is [first]'s while it serves, else [second]'s, named "Debrid" while both do; with neither, a lapsed
 * one's, so "premium ended" only shows once both are down and names the service whose premium ended.
 */
class Debrids(private val first: Debrid, private val second: Debrid, scope: CoroutineScope) : Debrid {
    private val owners = ConcurrentHashMap<DebridCheck.Ready, Debrid>()
    private val both = listOf(first, second)
    private val serving get() = both.filter(::usable).ifEmpty { listOf(both.firstOrNull { it.status.value.state == DebridState.NotPremium } ?: first) }

    override val status: StateFlow<DebridStatus> = combine(first.status, second.status) { _, _ -> current() }.stateIn(scope, SharingStarted.Eagerly, current())

    private fun current() = serving.let { s -> s.first().status.value.let { if (s.size > 1) it.copy(name = "Debrid") else it } }

    override suspend fun check(candidates: List<DebridCandidate>): Map<DebridCandidate, DebridCheck> = coroutineScope {
        val asked = serving
        val asks = asked.map { d -> d to async { withTimeoutOrNull(ASK_MS) { runCatching { d.check(candidates) }.getOrNull() } } }
        val answers = mutableListOf<Pair<Debrid, Map<DebridCandidate, DebridCheck>>>()
        for ((d, ask) in asks) {
            val a = ask.await() ?: continue
            answers += d to a
            if (candidates.firstOrNull()?.let { a[it] } is DebridCheck.Ready) break
        }
        asks.forEach { it.second.cancel() }
        candidates.mapNotNull { c ->
            answers.firstNotNullOfOrNull { (d, a) -> (a[c] as? DebridCheck.Ready)?.also { owners[it] = d } }?.let { return@mapNotNull c to it }
            val said = answers.mapNotNull { it.second[c] }
            if (said.size == asked.size) c to said.first() else null
        }.toMap()
    }

    override suspend fun link(ready: DebridCheck.Ready) = (owners[ready] ?: serving.first()).link(ready)

    private companion object {
        /** Under the film session's own 4 s check budget, so a slow service never costs the other's answer. */
        const val ASK_MS = 3_500L
        val DOWN = setOf(DebridState.NoToken, DebridState.TokenInvalid, DebridState.Denied, DebridState.NotPremium)
        fun usable(d: Debrid) = d.status.value.state !in DOWN
    }
}
