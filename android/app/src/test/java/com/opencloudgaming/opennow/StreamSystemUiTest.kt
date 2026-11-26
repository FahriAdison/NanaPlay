package com.opencloudgaming.opennow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSystemUiTest {
    @Test
    fun leavesTransientThreeButtonNavigationVisibleLongEnoughToUse() {
        assertFalse(
            shouldPeriodicallyEnforceStreamSystemUi(
                streamActive = true,
                navigationBarsVisible = true,
            ),
        )
    }

    @Test
    fun keepsFullscreenEnforcementForHiddenNavigationBars() {
        assertTrue(
            shouldPeriodicallyEnforceStreamSystemUi(
                streamActive = true,
                navigationBarsVisible = false,
            ),
        )
        assertFalse(
            shouldPeriodicallyEnforceStreamSystemUi(
                streamActive = false,
                navigationBarsVisible = false,
            ),
        )
    }

    @Test
    fun rehidesVisibleStatusBarWhileStreamIsActive() {
        assertTrue(
            shouldRehideStreamStatusBars(
                streamActive = true,
                statusBarsVisible = true,
            ),
        )
        assertFalse(
            shouldRehideStreamStatusBars(
                streamActive = true,
                statusBarsVisible = false,
            ),
        )
        assertFalse(
            shouldRehideStreamStatusBars(
                streamActive = false,
                statusBarsVisible = true,
            ),
        )
    }
}
