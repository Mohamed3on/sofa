package com.mohamed3on.iptv

import com.mohamed3on.iptv.player.Mode
import com.mohamed3on.iptv.player.matchingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRateTest {
    private val hz60 = Mode(1, 3840, 2160, 60f)
    private val modes = listOf(hz60, Mode(2, 3840, 2160, 50f), Mode(3, 3840, 2160, 25f), Mode(4, 3840, 2160, 23.976f))

    @Test fun aFilmWithEachFrameTwicePlaysAtTheFilmsRate() = assertEquals(4, matchingMode(hz60, modes, 47.952f))

    @Test fun fiftyFpsSportPlaysAt50HzNot25() = assertEquals(2, matchingMode(hz60, modes, 50f))

    @Test fun aModeThatFitsIsHeldNotSwitched() = assertEquals(2, matchingMode(Mode(2, 3840, 2160, 50f), modes + Mode(5, 3840, 2160, 100f), 25f))

    @Test fun aTvWithOneModeStays() = assertNull(matchingMode(hz60, listOf(hz60), 23.976f))
}
