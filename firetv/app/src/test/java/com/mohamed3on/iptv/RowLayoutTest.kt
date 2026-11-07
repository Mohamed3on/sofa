package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.RowLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class RowLayoutTest {
    @Test fun movedRowsKeepTheirOrderHiddenGoAndANewRowKeepsTheBoxsPlace() {
        val store = MapStore()
        val layout = RowLayout(store)
        val box = listOf("ts", "trending", "new", "ar")
        layout.move(layout.arrange(box) { it }, "ar", down = false)
        layout.move(layout.arrange(box) { it }, "ar", down = false)
        assertEquals(listOf("ts", "ar", "trending", "new"), layout.arrange(box) { it })
        layout.hide("trending")
        // A fresh start (same store) on a library with a new row, "dub", which the box puts before "ar".
        assertEquals(listOf("ts", "ar", "dub", "new"), RowLayout(store).arrange(listOf("ts", "trending", "new", "dub", "ar")) { it })
        layout.showHidden()
        assertEquals(listOf("ts", "ar", "trending", "dub", "new"), layout.arrange(listOf("ts", "trending", "new", "dub", "ar")) { it })
    }
}
