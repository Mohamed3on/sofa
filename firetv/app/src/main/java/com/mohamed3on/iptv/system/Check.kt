package com.mohamed3on.iptv.system

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Hooks for firetv/check.sh. `am start … --es open <target>` asks for a screen: `movies`, `live` (Movies
 * & Shows closed), `title:<titleId>`, `versions:<titleId>`, `play:<titleId>` (a film, or a show's episode up next;
 * `play:<titleId>@<copyId>` pins that copy first), `search:<query>`. A `~<seconds>` suffix on `play:` seeks the play
 * that many seconds before its end once it starts (`play:tv:1396~45`), to reach the next episode's card quickly.
 * Each event is one `IptvCheck` logcat line, `<event> ms=<since the last open> key=value …`.
 */
object Check {
    /** Prefs key: the screen to reopen on the next launch (`movies`, `title:<titleId>`), absent for live TV. */
    const val LAST_SCREEN = "screen.last"

    /** The open target not yet acted on; whoever shows it sets it back to null. */
    val open = MutableStateFlow<String?>(null)
    /** The last `play:`'s `~<seconds>` (ms): the next play to start seeks that long before its end, once. */
    @Volatile var fromEnd: Long? = null
    @Volatile private var since = SystemClock.elapsedRealtime()

    fun request(target: String?) {
        target ?: return
        since = SystemClock.elapsedRealtime()
        log("open", "target" to target)
        fromEnd = target.substringAfterLast('~', "").toLongOrNull()?.times(1_000)
        open.value = target.substringBeforeLast('~')
    }

    fun log(event: String, vararg values: Pair<String, Any?>) {
        Log.i("IptvCheck", "$event ms=${SystemClock.elapsedRealtime() - since}" + values.joinToString("") { " ${it.first}=${it.second}" })
    }
}
