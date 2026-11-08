package com.mohamed3on.iptv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.mohamed3on.iptv.data.Channel
import com.mohamed3on.iptv.data.Crests
import com.mohamed3on.iptv.data.MatchState
import com.mohamed3on.iptv.ui.theme.FitText
import com.mohamed3on.iptv.ui.theme.Palette
import com.mohamed3on.iptv.ui.theme.Space
import com.mohamed3on.iptv.ui.theme.StatusPill
import com.mohamed3on.iptv.ui.theme.Type
import com.mohamed3on.iptv.ui.theme.tabular
import java.util.concurrent.ConcurrentHashMap

/** Today's scores ([com.mohamed3on.iptv.data.LiveScores]) while live TV is on screen; only a row whose title has crests reads them. */
val LocalScores = staticCompositionLocalOf<State<Map<String, MatchState>>> { mutableStateOf(emptyMap()) }

/** A match the scoreboards know: crests and short names from the guide, score and clock from the box. */
@Immutable
class Match(val crests: Crests, val state: MatchState)

/** The state of the match these crests are for: null in a guide without keys, or when the scoreboards don't have it. */
internal fun Crests?.stateIn(scores: Map<String, MatchState>): MatchState? = this?.key?.let(scores::get)

/**
 * The match [title] names when the scoreboards know it, else null: any other programme (or a match ESPN doesn't carry)
 * keeps its LIVE mark, title and time bar. Reads the scores only for a title the guide has crests for.
 */
@Composable
fun matchOf(title: String?): Match? {
    val crests = title?.let { LocalCrests.current[it] } ?: return null
    val state = crests.stateIn(LocalScores.current.value) ?: return null
    return remember(crests, state) { Match(crests, state) }
}

/** The minute in live red, half time in amber, full time quiet, kick-off neutral. */
@Composable
fun MatchPill(state: MatchState) = StatusPill(
    state.clock,
    when {
        state.state == "in" && state.clock == "HT" -> Palette.warning
        state.state == "in" -> Palette.live
        state.state == "post" -> Palette.textTertiary
        else -> Palette.textSecondary
    },
)

private val scoreSpan = SpanStyle(color = Palette.textPrimary, fontFeatureSettings = "tnum")
/** The matchup's score: the heading's largest figures, bigger than the names it sits between. */
private val scoreStyle = Type.display.tabular

private fun MatchState.score() = "$hs–$away"

/** "Man Utd 4–4 Aston Villa", home first; only the score in tabular figures, and "v" before kick-off. */
private fun fixture(m: Match) = buildAnnotatedString {
    append(m.crests.homeName.orEmpty())
    append(' ')
    if (m.state.state == "pre") append('v') else withStyle(scoreSpan) { append(m.state.score()) }
    append(' ')
    append(m.crests.awayName.orEmpty())
}

/** A known match on one line, the same everywhere: [67'] (crest) Man Utd 4–4 Aston Villa (crest). */
@Composable
fun MatchLine(match: Match, style: TextStyle, modifier: Modifier = Modifier) {
    val text = remember(match) { fixture(match) }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
        MatchPill(match.state)
        Crest(match.crests.home)
        FitText(text, style, Modifier.weight(1f, fill = false))
        Crest(match.crests.away)
    }
}

private val MATCHUP_CREST = 32.dp

/**
 * A known match as the banner's heading, as a scorebug reads: crests flanking the short names, the score between them at
 * display size, centred on the names' line, with the clock's pill hanging under it; then its 🏆 competition.
 */
@Composable
fun Matchup(match: Match, comp: String?) {
    // Everything in the row centres on the names' line; the score's column on its score alone, so the pill hangs below.
    val scoreHalf = with(LocalDensity.current) { (scoreStyle.lineHeight.toPx() / 2).roundToInt() }
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            val centre = Modifier.alignBy { it.measuredHeight / 2 }
            Crest(match.crests.home, MATCHUP_CREST, centre)
            FitText(match.crests.homeName.orEmpty(), Type.title, centre)
            val scored = match.state.state != "pre"
            Column(
                if (scored) Modifier.alignBy { scoreHalf } else centre,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                if (scored) Text(match.state.score(), style = scoreStyle)
                MatchPill(match.state)
            }
            FitText(match.crests.awayName.orEmpty(), Type.title, centre)
            Crest(match.crests.away, MATCHUP_CREST, centre)
        }
        if (comp != null) FitText(comp, Type.caption)
    }
}

private val flags = ConcurrentHashMap<String, String>()
private val EGYPT = "🇪🇬"
private val NAME_PREFIX = Regex("""^([A-Za-z]{2})[:|]""")
/** Country prefixes a feed's name can carry ("UK: TNT Sports 1"); "AR" is Arabic, never Argentina. */
private val COUNTRIES = setOf("US", "CA", "IE", "FR", "ES", "DE", "IT", "PT", "NL", "BE", "CH", "AT", "TR", "PL", "SG", "HK", "NZ", "AU")

/**
 * The flag for a feed's language: its home group's ("🇪🇸 Spanish Sports"; Sky & UK as 🇬🇧, beIN & Arabic as 🇪🇬), else
 * its name's country prefix ("UK: …"); "" when neither says. Arabic is always Egypt's flag.
 */
internal fun feedFlag(c: Channel): String = flags.getOrPut(c.family + "\n" + c.name) {
    val home = c.home.orEmpty()
    val indicator = { i: Int -> i < home.length && Character.codePointAt(home, i) in 0x1F1E6..0x1F1FF }
    when {
        home.startsWith("🇸🇦") -> EGYPT
        home.startsWith("🏴") -> "🇬🇧"
        indicator(0) && indicator(2) -> home.substring(0, 4)
        else -> when (val code = NAME_PREFIX.find(c.name)?.groupValues?.get(1)?.uppercase()) {
            "UK", "GB", "EN" -> "🇬🇧"
            "AR" -> EGYPT
            in COUNTRIES -> code!!.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
            else -> ""
        }
    }
}
