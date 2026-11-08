package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.Channel
import com.mohamed3on.iptv.ui.feedFlag
import org.junit.Assert.assertEquals
import org.junit.Test

class FeedFlagTest {
    private val bein = "🇸🇦 beIN & Arabic Sports"
    private fun flag(name: String, home: String? = bein) = feedFlag(Channel(id = 1, name = name, group = "🏆 La Liga", home = home, family = "$home|$name", rank = 0))

    @Test fun aLanguageTheNameSaysBeatsItsGroup() {
        assertEquals("🇬🇧", flag("◉: beIN Sp⚽rts 1 ENGLISH 4K"))
        assertEquals("🇬🇧", flag("ᵁᴴᴰ: beIN Sp⚽rts 1 EN ʰᵉᵛᶜ"))
        assertEquals("🇫🇷", flag("8K: beIN SP⚽RTS 1 FRANCE ᴿᴬᵂ"))
        assertEquals("🇫🇷", flag("ᵁᴴᴰ: beIN Sp⚽rts 1 FR ᴿᴬᵂ"))
        assertEquals("🇪🇬", flag("SS: beIN SP⚽RTS 1 4K"))
    }
}
