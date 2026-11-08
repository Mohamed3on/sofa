package com.mohamed3on.iptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.mohamed3on.iptv.data.Channel
import com.mohamed3on.iptv.data.Crests
import com.mohamed3on.iptv.data.displayName
import com.mohamed3on.iptv.ui.theme.Palette
import com.mohamed3on.iptv.ui.theme.Radius
import com.mohamed3on.iptv.ui.theme.Space
import com.mohamed3on.iptv.ui.theme.Type

/** Tiles at most this tall get the smaller inset, so a row's logo keeps its size. */
private val SMALL_TILE = 56.dp

/**
 * A channel's logo on a fixed-size tile, so nothing shifts while it loads (from the cache, a frame or
 * two). When there is none or it fails, the tile shows the channel's monogram instead of an empty box.
 * The request is sized to the tile.
 */
@Composable
fun LogoTile(channel: Channel, size: DpSize, modifier: Modifier = Modifier) {
    val context = LocalPlatformContext.current
    val density = LocalDensity.current
    var failed by remember(channel.logo) { mutableStateOf(false) }
    val request = remember(channel.logo, size) {
        channel.logo.takeIf { it.isNotEmpty() }?.let {
            with(density) { ImageRequest.Builder(context).data(it).size(size.width.roundToPx(), size.height.roundToPx()).build() }
        }
    }
    Box(
        modifier.size(size).clip(RoundedCornerShape(Radius.row)).background(Palette.tile),
        contentAlignment = Alignment.Center,
    ) {
        if (request == null || failed) Text(remember(channel.name) { monogram(channel.name) }, style = Type.headline.copy(color = Palette.textSecondary), maxLines = 1)
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize().padding(if (size.height <= SMALL_TILE) Space.xs else Space.s),
            )
        }
    }
}

/** The first letters of the channel's first two words ("Sky Sports Main Event" → "SS"). */
private fun monogram(name: String): String =
    displayName(name).split(' ')
        .mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() } }
        .take(2).joinToString("") { it.uppercase() }

/** The crests the guide gives each 🏆 match title (GuideDb.crests); empty outside the live screen. */
val LocalCrests = staticCompositionLocalOf<Map<String, Crests>> { emptyMap() }

/**
 * A match's two crests, home then away, before its fixture: each on a faint chip so a dark crest still reads on the
 * scrim, sized to the line. Nothing at all when the guide has none for [title], so a row without them keeps its text
 * where it was; a crest that fails to load leaves its chip empty rather than moving the text.
 */
@Composable
fun MatchCrests(title: String?) {
    val crests = title?.let { LocalCrests.current[it] } ?: return
    Row(horizontalArrangement = Arrangement.spacedBy(CREST_GAP), verticalAlignment = Alignment.CenterVertically) {
        Crest(crests.home)
        Crest(crests.away)
    }
}

/** One crest, [size] on a faint chip [Space.xs] larger, so a dark crest still reads on the scrim. */
@Composable
internal fun Crest(url: String?, size: Dp = CREST) {
    val context = LocalPlatformContext.current
    val density = LocalDensity.current
    val request = remember(url) { url?.let { with(density) { ImageRequest.Builder(context).data(it).size(size.roundToPx()).build() } } }
    Box(Modifier.size(size + Space.xs).background(Palette.tile, RoundedCornerShape(Radius.chip)), contentAlignment = Alignment.Center) {
        if (request != null) AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(size))
    }
}

private val CREST = 18.dp
private val CREST_GAP = 2.dp
