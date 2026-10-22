package com.opencloudgaming.opennow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidDeviceTierTest {
    @Test
    fun detectsMediaTekEntryChipsets() {
        assertTrue(isEntryLevelChipset("MediaTek Helio G35"))
        assertTrue(isEntryLevelChipset("helio g36"))
        assertTrue(isEntryLevelChipset("MT6765V/CB", "mt6765"))
        assertTrue(isEntryLevelChipset("MT6762"))
    }

    @Test
    fun detectsUnisocEntryChipsets() {
        assertTrue(isEntryLevelChipset("Unisoc T606"))
        assertTrue(isEntryLevelChipset("T610"))
        assertTrue(isEntryLevelChipset("SC9863A"))
        assertTrue(isEntryLevelChipset("ums512_1h10"))
    }

    @Test
    fun detectsSnapdragon400Series() {
        assertTrue(isEntryLevelChipset("Snapdragon 439"))
        assertTrue(isEntryLevelChipset("Qualcomm Snapdragon 4 Gen 2"))
        assertTrue(isEntryLevelChipset("SM4250"))
        assertTrue(isEntryLevelChipset("SDM450"))
    }

    @Test
    fun leavesMidRangeAndFlagshipChipsetsAlone() {
        assertFalse(isEntryLevelChipset("Snapdragon 8 Gen 3"))
        assertFalse(isEntryLevelChipset("MediaTek Dimensity 8300"))
        assertFalse(isEntryLevelChipset("Helio G99"))
        // Helio G85 (MT6768) is budget but still a 1080p-class part.
        assertFalse(isEntryLevelChipset("MT6768"))
        assertFalse(isEntryLevelChipset("Exynos 2400"))
        assertFalse(isEntryLevelChipset(null, ""))
    }
}
