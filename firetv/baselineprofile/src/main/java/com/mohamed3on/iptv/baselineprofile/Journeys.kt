package com.mohamed3on.iptv.baselineprofile

import android.view.KeyEvent
import androidx.benchmark.macro.MacrobenchmarkScope

internal const val PACKAGE = "com.mohamed3on.iptv"

/** Presses [code] [times] times, [pauseMs] apart, as a remote would. */
internal fun MacrobenchmarkScope.press(code: Int, times: Int = 1, pauseMs: Long = 250) {
    repeat(times) {
        device.pressKeyCode(code)
        Thread.sleep(pauseMs)
    }
}

/**
 * Launched, with the channels loaded and the hidden channel list built (LIST_BUILD_DELAY_MS, 6 s after launch). A wait,
 * not a text check: without a screen reader the app shows no semantics tree (MainActivity), so UiAutomator sees no text.
 */
internal fun MacrobenchmarkScope.launchAndSettle() {
    pressHome()
    startActivityAndWait()
    Thread.sleep(10_000)
}

/** OK in the player: the info view (banner, stream numbers, health) for a moment, then OK again: the list. */
private fun MacrobenchmarkScope.openListViaInfo() {
    press(KeyEvent.KEYCODE_DPAD_CENTER, pauseMs = 1_500)
    press(KeyEvent.KEYCODE_DPAD_CENTER, pauseMs = 1_000)
}

/**
 * The channel list: open on the playing row, scroll, step through the groups, a schedule, close; a
 * preview of another row and back out; opened on the groups column (Left), and closed.
 */
internal fun MacrobenchmarkScope.browseChannels() {
    openListViaInfo()
    press(KeyEvent.KEYCODE_DPAD_DOWN, times = 8, pauseMs = 150)
    press(KeyEvent.KEYCODE_DPAD_UP, times = 8, pauseMs = 150)
    press(KeyEvent.KEYCODE_DPAD_LEFT, pauseMs = 600)
    // Single presses that rest on each group (each builds its rows), then a quick run past several.
    press(KeyEvent.KEYCODE_DPAD_DOWN, times = 6, pauseMs = 500)
    press(KeyEvent.KEYCODE_DPAD_DOWN, times = 6, pauseMs = 80)
    press(KeyEvent.KEYCODE_DPAD_UP, times = 12, pauseMs = 300) // back to the group it started on
    press(KeyEvent.KEYCODE_DPAD_RIGHT, pauseMs = 800)
    press(KeyEvent.KEYCODE_DPAD_DOWN, times = 3, pauseMs = 200)
    press(KeyEvent.KEYCODE_DPAD_RIGHT, pauseMs = 1_000) // the schedule
    press(KeyEvent.KEYCODE_DPAD_DOWN, times = 3, pauseMs = 200)
    press(KeyEvent.KEYCODE_BACK, pauseMs = 800)
    press(KeyEvent.KEYCODE_BACK, pauseMs = 1_000)
    // A row previewed behind the list (OK on a row that is not playing), then out of the list.
    openListViaInfo()
    press(KeyEvent.KEYCODE_DPAD_DOWN, times = 2, pauseMs = 300)
    press(KeyEvent.KEYCODE_DPAD_CENTER, pauseMs = 2_500)
    press(KeyEvent.KEYCODE_BACK, pauseMs = 1_000)
    // Opened on the groups column (Left from the player), and closed again.
    press(KeyEvent.KEYCODE_DPAD_LEFT, pauseMs = 1_000)
    press(KeyEvent.KEYCODE_BACK, pauseMs = 1_000)
}

/** The player's other layers and zapping: Recent, the quick panel, Up/Down with the info view up, which Back closes. */
internal fun MacrobenchmarkScope.playerLayers() {
    press(KeyEvent.KEYCODE_DPAD_RIGHT, pauseMs = 1_000) // recent channels
    press(KeyEvent.KEYCODE_DPAD_DOWN, times = 2, pauseMs = 200)
    press(KeyEvent.KEYCODE_BACK, pauseMs = 1_000)
    press(KeyEvent.KEYCODE_MENU, pauseMs = 1_000) // quick panel
    press(KeyEvent.KEYCODE_DPAD_DOWN, times = 2, pauseMs = 200)
    press(KeyEvent.KEYCODE_BACK, pauseMs = 1_000)
    press(KeyEvent.KEYCODE_DPAD_CENTER, pauseMs = 1_200) // the info view
    press(KeyEvent.KEYCODE_DPAD_UP, pauseMs = 2_000) // zap
    press(KeyEvent.KEYCODE_DPAD_DOWN, pauseMs = 2_000)
    press(KeyEvent.KEYCODE_BACK, pauseMs = 1_000)
}
