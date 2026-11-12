package com.mohamed3on.iptv

import android.net.Uri
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.exoplayer.ExoPlaybackException
import com.mohamed3on.iptv.player.Recovery
import com.mohamed3on.iptv.player.StreamErrors
import com.mohamed3on.iptv.player.StreamFailure
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class StreamErrorsTest {
    private fun recovery(f: StreamFailure) = StreamErrors.recovery(f)
    private fun http(status: Int) = recovery(StreamFailure.Http(status))

    @Test fun http458MeansTheAccountIsBusy() = assertEquals(Recovery.Busy, http(458))

    @Test fun http511And403MeanTheRouteIsWrong() {
        assertEquals(Recovery.WrongRoute, http(511))
        assertEquals(Recovery.WrongRoute, http(403))
    }

    @Test fun notFoundTimeoutsAndUnplayableBodiesMoveToTheNextFeed() {
        assertEquals(Recovery.NextFeed, http(404))
        assertEquals(Recovery.NextFeed, recovery(StreamFailure.Timeout))
        assertEquals(Recovery.NextFeed, recovery(StreamFailure.Unplayable("")))
    }

    // An unreachable host is the network, not the channel: no other feed would do better.
    @Test fun serverErrorsAndNoNetworkBackOff() {
        assertEquals(Recovery.Backoff, http(500))
        assertEquals(Recovery.Backoff, http(503))
        assertEquals(Recovery.Backoff, recovery(StreamFailure.Unreachable))
    }

    private fun source(e: IOException, code: Int) = ExoPlaybackException.createForSource(e, code)

    @Test fun theHttpStatusIsReadFromThePlayerError() {
        val bad = InvalidResponseCodeException(458, null, null, emptyMap(), DataSpec(Uri.EMPTY), ByteArray(0))
        val error = source(bad, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        assertEquals(StreamFailure.Http(458), StreamErrors.failureOf(error))
    }

    @Test fun networkErrorsAreClassifiedByErrorCode() {
        assertEquals(StreamFailure.Timeout, StreamErrors.failureOf(source(IOException(), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)))
        assertEquals(StreamFailure.Unreachable, StreamErrors.failureOf(source(IOException(), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)))
        val garbled = ParserException.createForMalformedContainer(null, null)
        assertEquals(StreamFailure.Unplayable("ERROR_CODE_PARSING_CONTAINER_MALFORMED: ParserException"), StreamErrors.failureOf(source(garbled, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)))
    }
}
