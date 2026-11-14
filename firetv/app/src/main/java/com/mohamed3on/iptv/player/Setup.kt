@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.mohamed3on.iptv.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioCapabilitiesReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** HDR formats the TV takes, as [Display.HdrCapabilities] reports them. */
enum class Hdr(val type: Int) {
    DolbyVision(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION),
    Hdr10(Display.HdrCapabilities.HDR_TYPE_HDR10),
    Hlg(Display.HdrCapabilities.HDR_TYPE_HLG),
    Hdr10Plus(Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS),
}

/** Audio formats the stick can send undecoded to the TV or soundbar. [EAc3Joc] is Atmos over Dolby Digital Plus. */
enum class Passthrough(val encoding: Int) {
    Ac3(C.ENCODING_AC3),
    EAc3(C.ENCODING_E_AC3),
    EAc3Joc(C.ENCODING_E_AC3_JOC),
    Ac4(C.ENCODING_AC4),
    Dts(C.ENCODING_DTS),
    DtsHd(C.ENCODING_DTS_HD),
    DtsX(C.ENCODING_DTS_UHD_P2),
    TrueHd(C.ENCODING_DOLBY_TRUEHD),
}

/**
 * What the current TV, stick and soundbar can show and play (KTD6). [dolbyVisionProfiles] are the
 * profile numbers (5, 8, ...) the stick decodes; [videoCodecs] are the MIME types it has a decoder for.
 */
data class SetupProfile(
    val displayName: String,
    val hdr: Set<Hdr>,
    val dolbyVisionProfiles: Set<Int>,
    val videoCodecs: Set<String>,
    val passthrough: Set<Passthrough>,
    val maxChannels: Int,
) {
    /** Marks and corrections are kept per fingerprint: a new TV or soundbar starts without them. */
    val fingerprint: String
        get() = "$displayName|${hdr.sorted().joinToString(",")}|${passthrough.sorted().joinToString(",")}"

    /** The TV is off or on another input: the reading says nothing about the setup. */
    val isBlank: Boolean get() = hdr.isEmpty() && passthrough.isEmpty()
}

/**
 * [audio] without DTS-HD and DTS:X while plain DTS passes, so Media3 sends such a track's DTS core instead. A TV can take
 * DTS-HD over eARC and hand it to a soundbar that plays only DTS, which stays silent (a TCL Q7C with a Sonos Beam); every
 * DTS-HD and DTS:X stream carries a DTS core, so the cost is only a receiver's lossless decode.
 */
fun dtsAsCore(audio: AudioCapabilities): AudioCapabilities {
    if (!audio.supportsEncoding(C.ENCODING_DTS)) return audio
    val kept = Passthrough.entries.map { it.encoding }
        .filter { audio.supportsEncoding(it) && it != C.ENCODING_DTS_HD && it != C.ENCODING_DTS_UHD_P2 }
    @Suppress("DEPRECATION") // the encoding-list constructor is the only public one
    return AudioCapabilities((listOf(C.ENCODING_PCM_16BIT) + kept).toIntArray(), audio.maxChannelCount)
}

/** The profile from raw readings: [hdrTypes] from the display, [audio] from Media3. */
fun setupProfile(
    displayName: String,
    hdrTypes: IntArray,
    dolbyVisionProfiles: Set<Int>,
    videoCodecs: Set<String>,
    audio: AudioCapabilities,
) = SetupProfile(
    displayName,
    Hdr.entries.filterTo(mutableSetOf()) { it.type in hdrTypes },
    dolbyVisionProfiles,
    videoCodecs,
    Passthrough.entries.filterTo(mutableSetOf()) { audio.supportsEncoding(it.encoding) },
    audio.maxChannelCount,
)

/** A blank reading keeps the last stable profile; the first reading is always taken. */
fun nextProfile(last: SetupProfile?, reading: SetupProfile) = if (last != null && reading.isBlank) last else reading

/**
 * The live setup profile (U5), re-read when the display or the audio route changes. The decoders
 * are the stick's own, so they are read once.
 */
class Setup(context: Context) {
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filterNot { it.isEncoder }
    private val videoCodecs = decoders.flatMap { it.supportedTypes.filter(MimeTypes::isVideo) }.toSet()
    /** CodecProfileLevel's Dolby Vision constants are bit flags: profile n is 1 shl n. */
    private val dolbyVisionProfiles = decoders
        .filter { MimeTypes.VIDEO_DOLBY_VISION in it.supportedTypes }
        .flatMap { codec -> codec.getCapabilitiesForType(MimeTypes.VIDEO_DOLBY_VISION).profileLevels.map { Integer.numberOfTrailingZeros(it.profile) } }
        .toSet()
    private val audioReceiver: AudioCapabilitiesReceiver = AudioCapabilitiesReceiver(context) { audio = it; update() }
    private var audio: AudioCapabilities = audioReceiver.register()
    private val _profile = MutableStateFlow(read().also(::log))
    /** The current profile; never blank once a real reading has arrived. */
    val profile: StateFlow<SetupProfile> = _profile

    init {
        displays.registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) update()
            }
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
        }, Handler(Looper.getMainLooper()))
    }

    private fun read(): SetupProfile {
        val display = displays.getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION") // Display.Mode.getSupportedHdrTypes is API 34; the stick is API 30.
        val hdrTypes = display?.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
        return setupProfile(display?.name.orEmpty(), hdrTypes, dolbyVisionProfiles, videoCodecs, dtsAsCore(audio))
    }

    private fun update() {
        val next = nextProfile(_profile.value, read())
        if (next != _profile.value) log(next)
        _profile.value = next
    }

    private fun log(p: SetupProfile) = Log.i(TAG, "$p fingerprint=${p.fingerprint}")

    private companion object {
        const val TAG = "IptvSetup"
    }
}
