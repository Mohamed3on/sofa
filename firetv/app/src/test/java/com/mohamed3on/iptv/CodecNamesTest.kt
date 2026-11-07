package com.mohamed3on.iptv

import androidx.media3.common.ColorInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.mohamed3on.iptv.player.probeName
import com.mohamed3on.iptv.player.qualityLabels
import org.junit.Assert.assertEquals
import org.junit.Test

/** The one codec table names what plays twice: on the format chips, and as ffprobe does for the server's quality cache. */
class CodecNamesTest {
    private fun video(mime: String, width: Int, height: Int, fps: Float, codecs: String? = null, color: ColorInfo? = null) =
        Format.Builder().setSampleMimeType(mime).setWidth(width).setHeight(height).setFrameRate(fps).setCodecs(codecs).setColorInfo(color).build()

    private fun audio(mime: String, channels: Int) = Format.Builder().setSampleMimeType(mime).setChannelCount(channels).build()

    @Test fun chipsNameThePictureAndSound() {
        assertEquals(listOf("1080p", "25 fps", "H.264", "AAC stereo"), qualityLabels(video(MimeTypes.VIDEO_H264, 1920, 1080, 25f), audio(MimeTypes.AUDIO_AAC, 2)))
        assertEquals(listOf("4K", "23.98 fps", "HEVC", "TrueHD 7.1 Atmos"), qualityLabels(video(MimeTypes.VIDEO_H265, 3840, 2160, 23.976f), audio(MimeTypes.AUDIO_TRUEHD, 8)))
        assertEquals(
            listOf("4K", "24 fps", "Dolby Vision 8", "E-AC3 5.1 Atmos"),
            qualityLabels(video(MimeTypes.VIDEO_DOLBY_VISION, 3840, 2160, 24f, "dvhe.08.06"), audio(MimeTypes.AUDIO_E_AC3_JOC, 6)),
        )
        val hdr10 = ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_ST2084).build()
        assertEquals(listOf("4K", "24 fps", "HDR10", "DTS 5.1"), qualityLabels(video(MimeTypes.VIDEO_H265, 3840, 2160, 24f, color = hdr10), audio(MimeTypes.AUDIO_DTS_HD, 6)))
    }

    @Test fun readingsUseFfprobeNames() {
        assertEquals("hevc", probeName(MimeTypes.VIDEO_H265))
        assertEquals("hevc", probeName(MimeTypes.VIDEO_DOLBY_VISION))
        assertEquals("h264", probeName(MimeTypes.VIDEO_H264))
        assertEquals("mpeg2video", probeName(MimeTypes.VIDEO_MPEG2))
        assertEquals("eac3", probeName(MimeTypes.AUDIO_E_AC3_JOC))
        assertEquals("truehd", probeName(MimeTypes.AUDIO_TRUEHD))
        // Every DTS kind is "dts", the family the ranker learns a failure to play under.
        assertEquals("dts", probeName(MimeTypes.AUDIO_DTS_HD))
        assertEquals("dts", probeName(MimeTypes.AUDIO_DTS_X))
    }
}
