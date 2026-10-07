package com.pokedaisey.app.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HuntTrackerTest {
    // TID 0x1234, SID 0x5678: the OT id word is SID in the high half.
    private val otId = 0x56781234L

    @Test fun shinyValueIsZeroWhenPidHalvesCancelTheIds() {
        val pid = ((0x1234L xor 0x5678L) shl 16) // high half = TID ^ SID, low half 0
        assertEquals(0, shinyValue(pid, otId))
    }

    @Test fun shinyValueIsTheLowHalfLeftOver() {
        val pid = ((0x1234L xor 0x5678L) shl 16) or 0x00C8L // 200
        assertEquals(200, shinyValue(pid, otId))
        // Shiny on the modded cutoff, not on stock Gen 3's.
        assertTrue(200 < SHINY_CUTOFF_MOD)
        assertTrue(200 >= SHINY_CUTOFF_STOCK)
    }
}
