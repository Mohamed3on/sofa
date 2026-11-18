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
    // 458, 511/403, timeouts, 5xx and an unreachable host are played through in StreamSessionTest.
    @Test fun notFoundAndUnplayableBodiesMoveToTheNextFeed() {
        assertEquals(Recovery.NextFeed, StreamErrors.recovery(StreamFailure.Http(404)))
        assertEquals(Recovery.NextFeed, StreamErrors.recovery(StreamFailure.Unplayable("")))
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
