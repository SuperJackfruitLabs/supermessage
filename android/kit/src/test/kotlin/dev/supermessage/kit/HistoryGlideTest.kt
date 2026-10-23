package dev.supermessage.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryGlideTest {
    private val ms = 1_000_000L

    @Test
    fun itStartsAtTheOldPlaceAndEndsAtTheNew() {
        val glide = HistoryGlide()
        glide.add(100f)
        assertEquals(100f, glide.offsetAt(5 * ms), 0.01f)
        assertEquals(0f, glide.offsetAt(5 * ms + 320 * ms), 0.01f)
        assertFalse(glide.isActive)
    }

    @Test
    fun itFollowsTheCurveIOSUses() {
        // Reference points of cubic-bezier(0.25, 0.1, 0.25, 1) (the CSS "ease"): 0.5 -> ~0.8.
        assertEquals(0.8024f, HistoryGlide.ease(0.5f), 0.01f)
        assertEquals(0.4093f, HistoryGlide.ease(0.25f), 0.01f)
        assertEquals(0f, HistoryGlide.ease(0f), 0f)
        assertEquals(1f, HistoryGlide.ease(1f), 0f)
    }

    @Test
    fun itTakesThirtyTwoHundredthsOfASecond() {
        val glide = HistoryGlide()
        glide.add(100f)
        glide.offsetAt(0)
        assertTrue(glide.offsetAt(319 * ms) > 0f)
        assertTrue(glide.isActive)
        glide.offsetAt(320 * ms)
        assertFalse(glide.isActive)
    }

    @Test
    fun aSecondGrowthAddsToTheOneRunningInsteadOfRestartingIt() {
        val glide = HistoryGlide()
        glide.add(100f)
        glide.offsetAt(0)
        val alone = glide.offsetAt(160 * ms)
        glide.add(50f)
        // The second run starts on this frame; the first carries on from where it was.
        assertEquals(alone + 50f, glide.offsetAt(160 * ms), 0.01f)
        // The first finishes on its own clock, the second is still going.
        val later = glide.offsetAt(330 * ms)
        assertTrue("second still running: $later", later > 0f && later < 50f)
    }
}
