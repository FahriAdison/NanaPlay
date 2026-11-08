package com.opencloudgaming.opennow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * F-a: proves the atomic read-modify-write semantics that [OpenNowViewModel.updateSettingsTransform]
 * relies on. The ViewModel method is a one-line delegate to [SettingsStore.update], which runs
 * its transform inside [AtomicSettingsCell.update] — so the cell is the exact code path under
 * test, without needing an Android Context in a pure JVM test.
 */
class SettingsStoreTest {

    @Test
    fun transformUpdateAppliesToLatestValueAndDoesNotRevertOtherFields() {
        val cell = AtomicSettingsCell(AppSettings())

        // Field A written first via update()...
        cell.update { it.copy(quickAccessFabX = 0.11f) }
        // ...then a transform-based write for field B, as updateSettingsTransform does.
        cell.update { it.copy(androidTouch = it.androidTouch.copy(enabled = false)) }

        // Field A must survive (no revert from a stale snapshot), field B must be applied.
        assertEquals(0.11f, cell.value.quickAccessFabX, 0f)
        assertFalse(cell.value.androidTouch.enabled)
    }

    @Test
    fun replaceStillOverwritesTheWholeObject() {
        val cell = AtomicSettingsCell(AppSettings())

        cell.update { it.copy(quickAccessFabX = 0.11f) }
        cell.replace(AppSettings())

        assertEquals(AppSettings().quickAccessFabX, cell.value.quickAccessFabX, 0f)
    }

    @Test
    fun replaceWrapperAppliesNormalizationAndFullOverwrite() {
        // Mirrors SettingsStore.replace: normalize the incoming object, then replace the cell.
        val cell = AtomicSettingsCell(AppSettings())
        cell.update { it.copy(quickAccessFabX = 0.11f) }

        val incoming = AppSettings().copy(androidTouch = AppSettings().androidTouch.copy(opacity = 5f))
        cell.replace(incoming.normalizedForAndroid())

        // Full overwrite: field A from the earlier update() is gone (replace reverts it)...
        assertEquals(AppSettings().quickAccessFabX, cell.value.quickAccessFabX, 0f)
        // ...and the incoming value was normalized (opacity coerced into 0.15..1).
        assertEquals(1f, cell.value.androidTouch.opacity, 0f)
    }
}
