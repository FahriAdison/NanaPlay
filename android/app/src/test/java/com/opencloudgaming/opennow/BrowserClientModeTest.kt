package com.opencloudgaming.opennow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.0.102: Penegasan identitas murni berbasis fungsi hardware/input.
 *
 * Toggle website client mode telah dihapus:
 * - Touch-friendly launch mode (3) SELALU menggunakan identitas browser (WEBRTC, BROWSER).
 * - Gamepad-friendly launch mode (2) dan Default (1) SELALU menggunakan identitas desktop/native.
 * - Suffix ";client=browser" dihapus dari streamSettingsSessionSignature.
 * - Serializer JSON mengabaikan field lama "browserClientMode" secara transparan (backward-compatible).
 */
class BrowserClientModeTest {

    @Test
    fun browserIdentityIsTiedExclusivelyToTouchFriendlyLaunchMode() {
        assertTrue(shouldUseBrowserIdentity(GfnAppLaunchMode.TOUCH_FRIENDLY))
        assertFalse(shouldUseBrowserIdentity(GfnAppLaunchMode.GAMEPAD_FRIENDLY))
        assertFalse(shouldUseBrowserIdentity(GfnAppLaunchMode.DEFAULT))
    }

    @Test
    fun streamSignatureHasNoBrowserClientMarker() {
        val settings = launchProfileSettings()
        val signature = streamSettingsSessionSignature(settings)
        assertEquals(
            "opennow-android-stream-v1;res=1920x1080;fps=60;bitrate=75;codec=H264;color=TenBit420;" +
                "hdr=0;l4s=0;gsync=0;keyboard=en-US;language=en_US",
            signature,
        )
        assertFalse(signature.contains(";client=browser"))
    }

    @Test
    fun legacyBrowserClientModeInJsonIsSafelyIgnored() {
        val legacyJson = """
            {
                "stream": {
                    "resolution": "1920x1080",
                    "fps": 60,
                    "maxBitrateMbps": 75,
                    "browserClientMode": true
                }
            }
        """.trimIndent()

        val decoded = OpenNowJson.decodeFromString<AppSettings>(legacyJson)
        assertEquals("1920x1080", decoded.stream.resolution)
        assertEquals(60, decoded.stream.fps)
        assertEquals(75, decoded.stream.maxBitrateMbps)
    }

    @Test
    fun touchControlsRemainIndependentOfStreamSettings() {
        val settings = AppSettings()
        assertEquals(NativeTouchMode.Auto, settings.androidTouch.nativeTouchMode)
    }

    private fun launchProfileSettings(): StreamSettings = StreamSettings(
        resolution = "1920x1080",
        fps = 60,
        maxBitrateMbps = 75,
        codec = VideoCodec.H264,
        colorQuality = ColorQuality.TenBit420,
        keyboardLayout = "en-US",
        gameLanguage = "en_US",
    )
}
