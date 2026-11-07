package com.mohamed3on.iptv.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Why a stream failed, as far as recovery cares. Never carries the URL (the repo is public). */
sealed interface StreamFailure {
    data class Http(val status: Int) : StreamFailure
    /** Connect or read timed out, or the start watchdog fired. */
    data object Timeout : StreamFailure
    /** No connection to the host at all: the network is down or DNS fails. */
    data object Unreachable : StreamFailure
    /** Anything else: an empty or garbled body, a decoder failure. */
    data object Unplayable : StreamFailure
    /** This file can't play here at all: an unsupported container or format, no playable audio, Dolby Vision profile 7. Permanent (KTD7). */
    data object Unsupported : StreamFailure
    /** No audio track of this file can play here; [audio] names the tracks' formats as the ranker does ("dts", "truehd"). Permanent too. */
    data class NoAudio(val audio: Set<String>) : StreamFailure
    /** Every track of this file names its language and none is the one asked for (a release name's languages are often its subtitles'). */
    data object NoLanguage : StreamFailure
}

/** What a session does about a failure (KTD8). */
enum class Recovery { Busy, WrongRoute, NextFeed, Backoff }

object StreamErrors {
    fun recovery(failure: StreamFailure): Recovery = when (failure) {
        is StreamFailure.Http -> when (failure.status) {
            458 -> Recovery.Busy // another IP holds the account's one connection
            511, 403 -> Recovery.WrongRoute // plain front over VPN / VPN front without VPN
            in 500..599 -> Recovery.Backoff
            else -> Recovery.NextFeed
        }
        StreamFailure.Timeout, StreamFailure.Unplayable, StreamFailure.Unsupported, is StreamFailure.NoAudio, StreamFailure.NoLanguage -> Recovery.NextFeed
        // The network, not the channel: another feed would fail the same way. Retrying lets
        // playback come back by itself when the network does.
        StreamFailure.Unreachable -> Recovery.Backoff
    }

    /** Only these faults mark a film copy as failing on this setup; the rest may pass (KTD7). */
    fun permanent(failure: StreamFailure) = failure == StreamFailure.Unsupported || failure is StreamFailure.NoAudio

    /** Delay before 5xx retry number [attempt] (0-based): 1s, 2s, 4s, then 8s. */
    fun backoff(attempt: Int): Duration = (1 shl attempt.coerceAtMost(3)).seconds

    fun failureOf(error: PlaybackException): StreamFailure {
        val http = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<InvalidResponseCodeException>().firstOrNull()
        return when {
            http != null -> StreamFailure.Http(http.responseCode)
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> StreamFailure.Timeout
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> StreamFailure.Unreachable
            error.errorCode in UNSUPPORTED -> StreamFailure.Unsupported
            else -> StreamFailure.Unplayable
        }
    }

    // Not decoder init: that also fails while another player still holds the decoder.
    private val UNSUPPORTED = setOf(
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    )
}
