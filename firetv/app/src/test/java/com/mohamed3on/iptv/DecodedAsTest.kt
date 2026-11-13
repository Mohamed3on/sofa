package com.mohamed3on.iptv

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.mohamed3on.iptv.player.decodedAs
import org.junit.Assert.assertEquals
import org.junit.Test

class DecodedAsTest {
    private fun dv(codecs: String) = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).setCodecs(codecs).build()

    @Test fun profile8PlaysItsBaseLayerOnATvWithoutDolbyVision() {
        assertEquals(MimeTypes.VIDEO_H265, decodedAs(dv("dvhe.08.06"), tvDolbyVision = false).sampleMimeType)
        assertEquals(MimeTypes.VIDEO_DOLBY_VISION, decodedAs(dv("dvhe.08.06"), tvDolbyVision = true).sampleMimeType)
    }

    @Test fun profile7AlwaysPlaysItsBaseLayerAndProfile5NeverDoes() {
        assertEquals(MimeTypes.VIDEO_H265, decodedAs(dv("dvhe.07.06"), tvDolbyVision = true).sampleMimeType)
        assertEquals(MimeTypes.VIDEO_DOLBY_VISION, decodedAs(dv("dvhe.05.06"), tvDolbyVision = false).sampleMimeType)
    }
}
