package com.mohamed3on.iptv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A football match as ESPN's scoreboard has it (/api/app/matches): home and away score, [state] "pre", "in" or "post",
 * and the [clock] shown for it: the minute in play ("67'", "90'+6'"), "HT", "FT", or the kick-off time ("21:00").
 */
@Serializable
data class MatchState(val hs: Int, @SerialName("as") val away: Int, val state: String, val clock: String)

/** Today's scores by "homeKey|awayKey" (the guide's crests table), from the box every 30 s while [poll] runs. */
class LiveScores(private val server: SofaServer) {
    private val _scores = MutableStateFlow<Map<String, MatchState>>(emptyMap())
    val scores: StateFlow<Map<String, MatchState>> = _scores.asStateFlow()

    /** Asks the box now and every 30 s after, until cancelled; a failed ask keeps the last scores. */
    suspend fun poll(): Nothing {
        while (true) {
            withContext(Dispatchers.IO) { server.get("matches") { json.decodeFromString<Map<String, MatchState>>(it.string()) } }
                .onSuccess { _scores.value = it }
            delay(POLL_MS)
        }
    }

    private companion object {
        const val POLL_MS = 30_000L
        val json = Json { ignoreUnknownKeys = true }
    }
}
