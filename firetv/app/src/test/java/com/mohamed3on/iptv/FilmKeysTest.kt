package com.mohamed3on.iptv

import android.view.KeyEvent
import com.mohamed3on.iptv.ui.FilmAction
import com.mohamed3on.iptv.ui.FilmKeys
import com.mohamed3on.iptv.ui.filmKey
import org.junit.Assert.assertEquals
import org.junit.Test

class FilmKeysTest {
    private fun press(code: Int, s: FilmKeys, repeat: Int = 0, held: Long = 0, sinceStep: Long = 1_000) = filmKey(code, true, repeat, held, sinceStep, s)

    @Test fun `Back peels one layer a press, the film last`() {
        val back = KeyEvent.KEYCODE_BACK
        val all = FilmKeys(panel = true, seeking = true, sessionBack = true, controls = true)
        assertEquals(FilmAction.ClosePanel, press(back, all))
        assertEquals(FilmAction.DropSeek, press(back, all.copy(panel = false)))
        assertEquals(FilmAction.SessionBack, press(back, all.copy(panel = false, seeking = false)))
        assertEquals(FilmAction.HideControls, press(back, all.copy(panel = false, seeking = false, sessionBack = false)))
        assertEquals(FilmAction.Leave, press(back, FilmKeys()))
        assertEquals("the Unavailable card leaves at once", FilmAction.Leave, press(back, all.copy(error = true)))
        assertEquals(FilmAction.Swallow, filmKey(back, false, 0, 0, 0, FilmKeys()))
    }

    @Test fun `OK seeks a pending step, else pauses and plays`() {
        val ok = KeyEvent.KEYCODE_DPAD_CENTER
        assertEquals(FilmAction.CommitSeek, press(ok, FilmKeys(seeking = true)))
        assertEquals(FilmAction.Pause, press(ok, FilmKeys(playing = true)))
        assertEquals(FilmAction.Play, press(ok, FilmKeys(playing = false)))
        assertEquals("a held OK does nothing more", FilmAction.Swallow, press(ok, FilmKeys(), repeat = 3))
    }

    @Test fun `OK presses a Skip button or the next episode's card, never pausing under them, while Play-Pause still pauses`() {
        val ok = KeyEvent.KEYCODE_DPAD_CENTER
        assertEquals(FilmAction.Accept, press(ok, FilmKeys(skip = true)))
        assertEquals(FilmAction.NextEpisode, press(ok, FilmKeys(card = true)))
        assertEquals(FilmAction.Pause, press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, FilmKeys(skip = true)))
        assertEquals("a pending seek goes first", FilmAction.CommitSeek, press(ok, FilmKeys(card = true, seeking = true)))
        assertEquals("Back on the card keeps the credits", FilmAction.DismissCard, press(KeyEvent.KEYCODE_BACK, FilmKeys(card = true, controls = true)))
    }

    @Test fun `on the pause screen OK and Play-Pause play, any other key only wakes the controls`() {
        val asleep = FilmKeys(asleep = true, playing = false)
        for (code in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) assertEquals(FilmAction.Play, press(code, asleep))
        for (code in listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU)) assertEquals(FilmAction.Wake, press(code, asleep))
        assertEquals(FilmAction.Swallow, filmKey(KeyEvent.KEYCODE_DPAD_CENTER, false, 0, 0, 0, asleep))
    }
}
