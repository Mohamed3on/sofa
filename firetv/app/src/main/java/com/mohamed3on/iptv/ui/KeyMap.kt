package com.mohamed3on.iptv.ui

import android.view.KeyEvent
import com.mohamed3on.iptv.data.Channel
import com.mohamed3on.iptv.data.Feed
import com.mohamed3on.iptv.data.LiveNow
import com.mohamed3on.iptv.data.SameMatch
import com.mohamed3on.iptv.data.Watched
import com.mohamed3on.iptv.data.resolve

/** The remote keys the full-screen player answers to (R10). */
enum class RemoteKey { Up, Down, Left, Right, Ok, Menu, Back, FastForward, Rewind, PlayPause }

fun remoteKey(keyCode: Int): RemoteKey? = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_UP -> RemoteKey.Up
    KeyEvent.KEYCODE_DPAD_DOWN -> RemoteKey.Down
    KeyEvent.KEYCODE_DPAD_LEFT -> RemoteKey.Left
    KeyEvent.KEYCODE_DPAD_RIGHT -> RemoteKey.Right
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> RemoteKey.Ok
    KeyEvent.KEYCODE_MENU -> RemoteKey.Menu
    KeyEvent.KEYCODE_BACK -> RemoteKey.Back
    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> RemoteKey.FastForward
    KeyEvent.KEYCODE_MEDIA_REWIND -> RemoteKey.Rewind
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> RemoteKey.PlayPause
    else -> null
}

/** What sits over the video. Back closes it before anything else. */
enum class Layer { None, ChannelList, Recent, QuickPanel }

/**
 * What a key press needs to know: the requested channel, what is open, and the
 * zap context when the channel was tuned from a list other than its group (Live now); null = its group;
 * and whether multi-view's tiles are on screen.
 */
data class PlayerKeys(
    val current: Channel?,
    val layer: Layer = Layer.None,
    val context: List<Channel>? = null,
    val multiView: Boolean = false,
    /** The info view (banner with the stream's numbers) is up. */
    val info: Boolean = false,
)

sealed interface KeyAction {
    data class Zap(val to: Channel, val down: Boolean) : KeyAction
    /** Back over the bare picture: leaves the app. */
    data object Exit : KeyAction
    /** The channel list (U9), focused on [at]'s row, or on its group in the groups column with [groups]. */
    data class OpenOverlay(val at: Channel, val groups: Boolean = false) : KeyAction
    data object OpenPanel : KeyAction
    /** The channels watched last, in a sheet from the right. */
    data object OpenRecent : KeyAction
    /** The banner with the stream's numbers and health, until Back (or OK, which goes on to the list). */
    data object ShowInfo : KeyAction
    data object HideInfo : KeyAction
    /** The next (or previous) match in Live now, which becomes the zap context. */
    data class LiveZap(val down: Boolean) : KeyAction
    /** Up/Down in a Live now zap context, read against Live now as it is at the press ([zapStep]); [orElse] in the channel's group when Live now has nowhere to go. */
    data class LiveStep(val down: Boolean, val orElse: Channel?) : KeyAction
    /** Multi-view: moves the selection, and the sound, toward [key]. */
    data class MoveTile(val key: RemoteKey) : KeyAction
    /** Multi-view: the selected tile goes full screen; the others stop (R18, AE7). */
    data object Promote : KeyAction
    /** Closes the open layer. */
    data object Close : KeyAction
    /** Swallowed with no effect. */
    data object Ignore : KeyAction
    /** Not the player's key: the open layer moves its own focus with it. */
    data object Pass : KeyAction
}

/** The remote key map from the plan's High-Level Technical Design, as a pure function. */
fun keyAction(key: RemoteKey, screen: PlayerKeys, feed: Feed): KeyAction {
    if (screen.layer != Layer.None) return when {
        key == RemoteKey.Back -> KeyAction.Close
        key == RemoteKey.Menu && screen.layer == Layer.QuickPanel -> KeyAction.Close
        // Recent came in with Right; Left sends it back the way it came.
        key == RemoteKey.Left && screen.layer == Layer.Recent -> KeyAction.Close
        else -> KeyAction.Pass
    }
    if (key == RemoteKey.Menu) return KeyAction.OpenPanel
    if (screen.multiView) return when (key) {
        RemoteKey.Ok, RemoteKey.Back -> KeyAction.Promote
        RemoteKey.FastForward, RemoteKey.Rewind, RemoteKey.PlayPause -> KeyAction.Ignore
        else -> KeyAction.MoveTile(key)
    }
    val current = screen.current ?: return KeyAction.Ignore
    return when (key) {
        RemoteKey.Left -> KeyAction.OpenOverlay(current, groups = true)
        RemoteKey.Right -> KeyAction.OpenRecent
        RemoteKey.FastForward, RemoteKey.Rewind -> KeyAction.LiveZap(down = key == RemoteKey.FastForward)
        RemoteKey.Up, RemoteKey.Down -> {
            val down = key == RemoteKey.Down
            val inGroup = feed.zapNeighbour(current, down)
            if (screen.context != null) return KeyAction.LiveStep(down, inGroup)
            inGroup?.let { KeyAction.Zap(it, down) } ?: KeyAction.Ignore
        }
        // OK shows more each press: the info view, then the channel list.
        RemoteKey.Ok -> if (screen.info) KeyAction.OpenOverlay(current) else KeyAction.ShowInfo
        // Live can't pause: Play/Pause shows what is on (the info view), and hides it again.
        RemoteKey.PlayPause -> if (screen.info) KeyAction.HideInfo else KeyAction.ShowInfo
        RemoteKey.Menu -> KeyAction.OpenPanel // handled above, with or without a channel
        RemoteKey.Back -> if (screen.info) KeyAction.HideInfo else KeyAction.Exit
    }
}

/**
 * The next or previous channel in [from]'s own group, wrapping at the ends: zapping inside a 🏆
 * group follows that group (KTD5). If a refresh dropped the group, zapping continues where the
 * channel resolves now (its home group).
 */
private fun Feed.zapNeighbour(from: Channel, down: Boolean): Channel? {
    val here = find(from.identity) ?: resolve(Watched.of(from)) ?: return null
    return neighbour(channelsIn(here.group), here, down)
}

/**
 * The channel after or before [from] in [list], wrapping; null when [from] is not in it or alone.
 * A spare feed (from Recent, say) stands at its family's row.
 */
private fun neighbour(list: List<Channel>, from: Channel, down: Boolean): Channel? {
    val i = list.indexOfFirst { it.identity == from.identity }.takeIf { it >= 0 } ?: list.indexOfFirst { it.family == from.family }
    if (i < 0) return null
    return list[(i + if (down) 1 else -1).mod(list.size)].takeIf { it.family != from.family }
}

/**
 * Up/Down from [from] in a Live now zap context, against this Live now, so a match that has ended drops out:
 * through [from]'s match's channels while it still shows on several ([sameMatch]: the context was one match's),
 * else through the matches (from an ended match's channel, to the first or last). The channel to tune and the
 * context it carries; null when nothing is on or [from] is alone in it.
 */
internal fun LiveNow.zapStep(sameMatch: Boolean, from: Channel, down: Boolean): Pair<Channel, List<Channel>>? {
    val list = contextFor(from).takeIf { sameMatch && it is SameMatch } ?: context
    return stepThrough(list, from, down)?.let { it to list }
}

/** The step [down] (or up) through [list] from [from]; from outside it, its first (or last) channel. */
internal fun stepThrough(list: List<Channel>, from: Channel?, down: Boolean): Channel? =
    if (from != null && list.any { it.family == from.family }) neighbour(list, from, down)
    else if (down) list.firstOrNull() else list.lastOrNull()

/** What a film key press needs to know: what is open over the film, and what Back would peel first. */
data class FilmKeys(
    /** No copy would play: the Unavailable card is up. */
    val error: Boolean = false,
    /** Options (or Versions) is open. */
    val panel: Boolean = false,
    /** A run of steps waits to seek. */
    val seeking: Boolean = false,
    /** The session takes Back: a trial's first 30 s, or a notice showing. */
    val sessionBack: Boolean = false,
    val controls: Boolean = false,
    /** The film plays (or would once buffered), so OK pauses it. */
    val playing: Boolean = true,
    /** A Skip intro / Skip recap button shows: OK presses it. */
    val skip: Boolean = false,
    /** The next episode's card shows over the credits: OK plays it, Back keeps the credits. */
    val card: Boolean = false,
    /** The pause screen shows: any key only wakes the controls. */
    val asleep: Boolean = false,
)

/** A key press in the film player, mapped by [filmKey]. */
sealed interface FilmAction {
    /** Back out to the titles (the place is kept). */
    data object Leave : FilmAction
    data object ClosePanel : FilmAction
    data object OpenPanel : FilmAction
    /** Drops the pending seek. */
    data object DropSeek : FilmAction
    /** Back is the session's: undo a trial or dismiss the notice. */
    data object SessionBack : FilmAction
    data object HideControls : FilmAction
    /** Seeks now to the pending target. */
    data object CommitSeek : FilmAction
    data object Play : FilmAction
    data object Pause : FilmAction
    /** Steps the pending target by [by] ms. */
    data class Step(val by: Long) : FilmAction
    /** ▲, or OK on the Skip button: takes the offer or Skip showing. */
    data object Accept : FilmAction
    /** OK on the next episode's card: plays it now. */
    data object NextEpisode : FilmAction
    /** Back on the next episode's card: keeps watching the credits. */
    data object DismissCard : FilmAction
    /** A key on the pause screen: back to the controls, the key spent. */
    data object Wake : FilmAction
    /** The player's key, nothing to do (a key's release, a held repeat). */
    data object Swallow : FilmAction
    /** Not the player's key: the panel moves its own focus with it. */
    data object Pass : FilmAction
}

/** Left/Right step 10 s, 30 s once held for [FILM_HOLD_MS]; ⏪/⏩ 30 s; repeats closer than [FILM_REPEAT_MS] are skipped. */
private const val FILM_STEP_MS = 10_000L
private const val FILM_HELD_STEP_MS = 30_000L
private const val FILM_JUMP_MS = 30_000L
private const val FILM_HOLD_MS = 1_000L
private const val FILM_REPEAT_MS = 100L

/**
 * The film player's keys as a pure function: [held] is how long the key has been down, [sinceStep] the time since the
 * last step. Back peels one layer a press: the Unavailable card, a panel, a pending seek, the next episode's card, a
 * trial or notice, the controls, then the film. OK and Play/Pause seek a pending step, else pause and play; OK presses
 * the next episode's card or a Skip button while one shows. On the pause screen any key only wakes the controls.
 */
fun filmKey(code: Int, down: Boolean, repeat: Int, held: Long, sinceStep: Long, s: FilmKeys): FilmAction {
    val back = code == KeyEvent.KEYCODE_BACK
    if (back && s.error) return if (down) FilmAction.Leave else FilmAction.Swallow
    if (s.panel) return if (!back && code != KeyEvent.KEYCODE_MENU) FilmAction.Pass else if (down) FilmAction.ClosePanel else FilmAction.Swallow
    if (s.error) return FilmAction.Pass
    if (s.asleep) return if (down && repeat == 0) FilmAction.Wake else FilmAction.Swallow
    val ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
    val action = when (code) {
        KeyEvent.KEYCODE_BACK -> when {
            s.seeking -> FilmAction.DropSeek
            s.card -> FilmAction.DismissCard
            s.sessionBack -> FilmAction.SessionBack
            s.controls -> FilmAction.HideControls
            else -> FilmAction.Leave
        }
        KeyEvent.KEYCODE_MENU -> FilmAction.OpenPanel
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> when {
            repeat > 0 -> FilmAction.Swallow
            s.seeking -> FilmAction.CommitSeek
            ok && s.card -> FilmAction.NextEpisode
            ok && s.skip -> FilmAction.Accept
            code == KeyEvent.KEYCODE_MEDIA_PLAY -> FilmAction.Play
            code == KeyEvent.KEYCODE_MEDIA_PAUSE || s.playing -> FilmAction.Pause
            else -> FilmAction.Play
        }
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
            if (repeat > 0 && sinceStep < FILM_REPEAT_MS) FilmAction.Swallow
            else FilmAction.Step((if (held >= FILM_HOLD_MS) FILM_HELD_STEP_MS else FILM_STEP_MS) * if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
        KeyEvent.KEYCODE_MEDIA_REWIND -> FilmAction.Step(-FILM_JUMP_MS)
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> FilmAction.Step(FILM_JUMP_MS)
        KeyEvent.KEYCODE_DPAD_UP -> if (repeat == 0) FilmAction.Accept else FilmAction.Swallow
        KeyEvent.KEYCODE_DPAD_DOWN -> FilmAction.Swallow
        else -> return FilmAction.Pass
    }
    return if (down) action else FilmAction.Swallow
}
