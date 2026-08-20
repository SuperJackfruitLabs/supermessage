package dev.supermessage

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule iOS paid for. From RootView.swift:
 *
 *   `sizeClass == .regular` was the first answer and it is wrong on the device
 *   that exposed it. In portrait at 834 points the inspector was laid out at
 *   x=850.5: present in the accessibility tree, off the side of the screen.
 *
 * WindowWidthSizeClass.Expanded begins at 840dp, so the default pane directive
 * would call an 834-point tablet portrait "expanded" and lay out three panes
 * where two fit. That is the case this file exists for.
 *
 * It is not tablet-only: the phone AVD used to prove this scaffold measures
 * 914dp in landscape — inside the 840-1000dp band this file guards against.
 */
class PaneLayoutTest {

    @Test
    fun aPhoneInPortraitGetsOnePane() {
        assertEquals(1, paneCountFor(411.dp))
    }

    @Test
    fun aTabletInPortraitGetsTwoPanesNotThree() {
        // The regression. If this returns 3, the info pane is off-screen.
        assertEquals(2, paneCountFor(840.dp))
    }

    @Test
    fun theBoundaryBelowThreePanesIsExclusive() {
        assertEquals(2, paneCountFor(999.dp))
        assertEquals(3, paneCountFor(1000.dp))
    }

    @Test
    fun aTabletInLandscapeGetsThreePanes() {
        assertEquals(3, paneCountFor(1200.dp))
    }

    @Test
    fun theBoundaryBelowTwoPanesIsExclusive() {
        assertEquals(1, paneCountFor(599.dp))
        assertEquals(2, paneCountFor(600.dp))
    }
}
