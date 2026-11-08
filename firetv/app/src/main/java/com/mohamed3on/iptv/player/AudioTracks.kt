package com.mohamed3on.iptv.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One playable audio track: [language] as an ISO 639-1 code where known, [codec] a short name ("AC3"). */
data class AudioOption(val language: String?, val codec: String, val channels: Int, val group: TrackGroup, val track: Int)

/** The quick panel offers a choice only when [options] has more than one entry (R13). */
data class AudioState(val options: List<AudioOption> = emptyList(), val selected: AudioOption? = null)

/**
 * The current feed's audio tracks and the switch between them (R13). Tracks the device can't
 * decode (e.g. MP2 on the Fire TV stick, KTD12) are left out, so they're never offered.
 * Main thread only, like the player.
 */
class AudioTracks(private val player: Player) {
    private val _state = MutableStateFlow(audioState(player.currentTracks))
    val state: StateFlow<AudioState> = _state

    private val listener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            _state.value = audioState(tracks)
        }
    }

    init {
        player.addListener(listener)
    }

    fun select(option: AudioOption) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(option.group, option.track))
            .build()
    }

    fun release() = player.removeListener(listener)
}

private fun audioState(tracks: Tracks): AudioState {
    var selected: AudioOption? = null
    val options = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { group ->
        // "Supported" counts a decoder that exists but doubts the parameters: only a missing decoder hides a track.
        (0 until group.length).filter { group.isTrackSupported(it, true) }.map { i ->
            val format = group.getTrackFormat(i)
            AudioOption(trackLanguage(format), chipName(format.sampleMimeType), format.channelCount, group.mediaTrackGroup, i)
                .also { if (group.isTrackSelected(i)) selected = it }
        }
    }
    return AudioState(options, selected)
}

/** A track name for the Egyptian dub ("Arabic (Egyption)" as Bluey's pack spells it, "مصري"). */
private val EGYPTIAN_LABEL = Regex("egypt|مصر", RegexOption.IGNORE_CASE)

/** A track's language: [EGYPTIAN] for an Arabic one named for Egypt, so it reads apart from the Fusha one. */
internal fun trackLanguage(f: Format): String? = if (f.language == "ar" && EGYPTIAN_LABEL.containsMatchIn(f.label.orEmpty())) EGYPTIAN else f.language

/** One subtitle track:[language] as an ISO 639-1 code where known, [label] the file's own name for it. */
data class SubtitleOption(val language: String?, val label: String?, val group: TrackGroup, val track: Int)

/** [selected] is null when subtitles are off. */
data class SubtitleState(val options: List<SubtitleOption> = emptyList(), val selected: SubtitleOption? = null)

/** A film's subtitle tracks and the choice between them, or none. Main thread only, like the player. */
class SubtitleTracks(private val player: Player) {
    private val _state = MutableStateFlow(subtitleState(player.currentTracks))
    val state: StateFlow<SubtitleState> = _state

    private val listener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            _state.value = subtitleState(tracks)
        }
    }

    init {
        player.addListener(listener)
    }

    /** Shows [option], or turns subtitles off for null. */
    fun select(option: SubtitleOption?) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, option == null)
            .apply { if (option != null) setOverrideForType(TrackSelectionOverride(option.group, option.track)) }
            .build()
    }

    fun release() = player.removeListener(listener)
}

private fun subtitleState(tracks: Tracks): SubtitleState {
    var selected: SubtitleOption? = null
    val options = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { group ->
        (0 until group.length).filter { group.isTrackSupported(it, true) }.map { i ->
            val format = group.getTrackFormat(i)
            SubtitleOption(format.language, format.label, group.mediaTrackGroup, i).also { if (group.isTrackSelected(i)) selected = it }
        }
    }
    return SubtitleState(options, selected)
}

/** "4K", "24 fps", "Dolby Vision", "E-AC3 5.1 Atmos": what is actually playing, from the decoders' formats. */
fun qualityLabels(video: Format?, audio: Format?): List<String> {
    val parts = mutableListOf<String>()
    if (video != null && video.height > 0) {
        parts += when {
            video.width >= 3800 || video.height >= 2100 -> "4K"
            else -> "${video.height}p"
        }
        val f = video.frameRate
        // 25 and 50 read whole; 23.976 keeps its decimals.
        if (f > 0) parts += if (kotlin.math.abs(f - Math.round(f)) < 0.01f) "${Math.round(f)} fps" else "%.2f fps".format(java.util.Locale.ROOT, f)
        parts += when {
            video.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> dolbyVisionProfile(video)?.let { "Dolby Vision $it" } ?: "Dolby Vision"
            video.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10"
            video.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
            else -> chipName(video.sampleMimeType)
        }
    }
    if (audio != null) {
        val layout = when (audio.channelCount) {
            1 -> "mono"
            2 -> "stereo"
            6 -> "5.1"
            8 -> "7.1"
            else -> null
        }
        parts += listOfNotNull(chipName(audio.sampleMimeType), layout, "Atmos".takeIf { audio.sampleMimeType == MimeTypes.AUDIO_E_AC3_JOC || audio.sampleMimeType == MimeTypes.AUDIO_TRUEHD }).joinToString(" ")
    }
    return parts.filter(String::isNotEmpty)
}

/** A Dolby Vision stream's profile from its codecs string ("dvhe.05.06" → 5, "dvhe.08.06" → 8); null when unknown. */
fun dolbyVisionProfile(video: Format): Int? = Regex("""^dv(?:he|h1|av|a1)\.(\d+)""").find(video.codecs ?: "")?.groupValues?.get(1)?.toIntOrNull()
