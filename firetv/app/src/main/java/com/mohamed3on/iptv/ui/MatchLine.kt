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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
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

/**
 * A match's home side, middle (score, "v") and away side in a row: the middle at its full width, never cut; the sides
 * share what is left, each whole when both fit, else the wider gives way first, down to half each. The sides centre on
 * one line, and the middle sits on it at [middleCentre] of its height (a score whose pill hangs below, by the score).
 */
@Composable
private fun Sides(
    home: @Composable () -> Unit,
    middle: @Composable () -> Unit,
    away: @Composable () -> Unit,
    gap: Dp,
    modifier: Modifier = Modifier,
    middleCentre: (height: Int) -> Int = { it / 2 },
) {
    Layout(contents = listOf(home, middle, away), modifier = modifier) { (homes, mids, aways), constraints ->
        val g = gap.roundToPx()
        val m = mids.first().measure(Constraints())
        val hw = homes.first().maxIntrinsicWidth(Constraints.Infinity)
        val aw = aways.first().maxIntrinsicWidth(Constraints.Infinity)
        val room = if (constraints.hasBoundedWidth) (constraints.maxWidth - m.width - 2 * g).coerceAtLeast(0) else hw + aw
        val half = room / 2
        val homeMax = when {
            hw + aw <= room || hw <= half -> hw
            aw <= half -> room - aw
            else -> half
        }
        val h = homes.first().measure(Constraints(maxWidth = homeMax))
        val a = aways.first().measure(Constraints(maxWidth = (room - h.width).coerceIn(0, aw)))
        val mc = middleCentre(m.height)
        val line = maxOf(h.height / 2, a.height / 2, mc)
        val height = line + maxOf(h.height - h.height / 2, a.height - a.height / 2, m.height - mc)
        layout(h.width + m.width + a.width + 2 * g, height) {
            h.placeRelative(0, line - h.height / 2)
            m.placeRelative(h.width + g, line - mc)
            a.placeRelative(h.width + g + m.width + g, line - a.height / 2)
        }
    }
}

/** "Man Utd 4–4 Aston Villa", home first: the score (or "v" before kick-off) in tabular figures, never cut ([Sides]). */
@Composable
private fun Fixture(match: Match, style: TextStyle, modifier: Modifier = Modifier) {
    val middleStyle = remember(style) { style.merge(scoreSpan) }
    Sides(
        home = { FitText(match.crests.homeName.orEmpty(), style) },
        middle = { Text(if (match.state.state == "pre") "v" else match.state.score(), style = middleStyle, maxLines = 1, softWrap = false) },
        away = { FitText(match.crests.awayName.orEmpty(), style) },
        gap = Space.xs,
        modifier = modifier,
    )
}

/** A known match on one line, the same everywhere: [67'] (crest) Man Utd 4–4 Aston Villa (crest). */
@Composable
fun MatchLine(match: Match, style: TextStyle, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
        MatchPill(match.state)
        Crest(match.crests.home, name = match.crests.homeName)
        Fixture(match, style, Modifier.weight(1f, fill = false))
        Crest(match.crests.away, name = match.crests.awayName)
    }
}

private val MATCHUP_CREST = 32.dp

/**
 * A known match as the banner's heading, as a scorebug reads: crests flanking the short names, the score between them at
 * display size, centred on the names' line, with the clock's pill hanging under it; then its 🏆 competition. Long names
 * give way as in [Sides]; the crests, score and pill never do.
 */
@Composable
fun Matchup(match: Match, comp: String?) {
    val scoreHalf = with(LocalDensity.current) { (scoreStyle.lineHeight.toPx() / 2).roundToInt() }
    val scored = match.state.state != "pre"
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Sides(
            home = {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                    Crest(match.crests.home, MATCHUP_CREST, name = match.crests.homeName)
                    FitText(match.crests.homeName.orEmpty(), Type.title, Modifier.weight(1f, fill = false))
                }
            },
            middle = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    if (scored) Text(match.state.score(), style = scoreStyle)
                    MatchPill(match.state)
                }
            },
            away = {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                    FitText(match.crests.awayName.orEmpty(), Type.title, Modifier.weight(1f, fill = false))
                    Crest(match.crests.away, MATCHUP_CREST, name = match.crests.awayName)
                }
            },
            gap = Space.m,
            // the score's own line on the names' line, so the pill hangs below it
            middleCentre = { if (scored) scoreHalf else it / 2 },
        )
        if (comp != null) CompName(comp, Type.caption)
    }
}

private val flags = ConcurrentHashMap<String, String>()
private val EGYPT = "🇪🇬"
private val NAME_PREFIX = Regex("""^([A-Za-z]{2})[:|]""")
/** A language word after the name's prefix: "beIN Sp⚽rts 1 ENGLISH 4K", "beIN Sp⚽rts 1 EN ʰᵉᵛᶜ", "… 1 FR ᴿᴬᵂ". */
private val ENGLISH = Regex("""(?i:\bENGLISH\b)|\bEN\b(?!:)""")
private val FRENCH = Regex("""(?i:\bFRANCE\b|\bFRENCH\b)|\bFR\b(?!:)""")
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
        // a language the name says beats its group's: beIN's English and French feeds sit under 🇸🇦
        ENGLISH.containsMatchIn(c.name) -> "🇬🇧"
        FRENCH.containsMatchIn(c.name) -> "🇫🇷"
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
