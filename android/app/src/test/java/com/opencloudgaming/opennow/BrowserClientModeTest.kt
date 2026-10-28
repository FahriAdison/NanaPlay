package com.opencloudgaming.opennow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Website client mode (port of upstream 2.1.1 "Website client mode"), with one deliberate
 * divergence from upstream: touch controls are NOT disabled while the mode is on.
 *
 * These tests pin the parts NanaPlay can express in pure functions — the stored default, the
 * settings signature, and JSON round-trip through [SettingsStore]'s serialiser. The parts that need
 * `GfnSessionRepository` (headers on the wire, `clientPlatformName`, monitor descriptor) are
 * statically checked, not asserted here.
 */
class BrowserClientModeTest {

    /** Website client mode is opt-in: nobody gets it by upgrading. */
    @Test
    fun websiteClientModeIsOffByDefault() {
        assertFalse(StreamSettings().browserClientMode)
        assertFalse(AppSettings().stream.browserClientMode)
        assertFalse(launchProfileSettings().browserClientMode)
    }

    /** The OFF path must stay byte-identical to before the port. */
    @Test
    fun offSignatureIsUnchangedFromBeforeThePort() {
        val settings = launchProfileSettings()
        assertEquals(
            "opennow-android-stream-v1;res=1920x1080;fps=60;bitrate=75;codec=H264;color=TenBit420;" +
                "hdr=0;l4s=0;gsync=0;keyboard=en-US;language=en_US",
            streamSettingsSessionSignature(settings),
        )
    }

    /** ON adds exactly the upstream marker and nothing else. */
    @Test
    fun onSignatureAppendsBrowserMarkerOnly() {
        val off = streamSettingsSessionSignature(launchProfileSettings())
        val on = streamSettingsSessionSignature(launchProfileSettings().copy(browserClientMode = true))
        assertEquals("$off;client=browser", on)
        assertTrue(on.endsWith(";client=browser"))
    }

    /**
     * A browser session and a desktop session are different sessions to NanaPlay, so a website-mode
     * session is not silently resume-matched by a normal launch (and the reverse). Upstream carries
     * the marker in the same signature for the same reason.
     */
    @Test
    fun websiteModeChangesSessionMatching() {
        val off = launchProfileSettings()
        val on = off.copy(browserClientMode = true)
        assertFalse(
            streamSettingsSessionSignature(off) == streamSettingsSessionSignature(on),
        )
    }

    /** The flag must survive the store's JSON round-trip, off and on. */
    @Test
    fun flagSurvivesSettingsSerializationRoundTrip() {
        val off = AppSettings().copy(stream = launchProfileSettings())
        val on = off.copy(stream = off.stream.copy(browserClientMode = true))

        assertEquals(
            false,
            OpenNowJson.decodeFromString<AppSettings>(OpenNowJson.encodeToString(off)).stream.browserClientMode,
        )
        assertEquals(
            true,
            OpenNowJson.decodeFromString<AppSettings>(OpenNowJson.encodeToString(on)).stream.browserClientMode,
        )
    }

    /** Per-game overrides are full snapshots, so the flag rides along instead of resetting to off. */
    @Test
    fun perGameOverrideKeepsWebsiteClientMode() {
        val settings = AppSettings().stream.copy(browserClientMode = true)
        val stored = AppSettings().copy(
            stream = AppSettings().stream,
            perGameSettings = mapOf("game-1" to settings),
        )
        assertEquals(true, stored.streamSettingsFor("game-1").browserClientMode)
        // A game without an override still falls back to the global stream settings.
        assertEquals(false, stored.streamSettingsFor("game-2").browserClientMode)
    }

    /**
     * The touch-control settings live in their own block and are never derived from
     * [StreamSettings.browserClientMode]: this is the structural half of "touch stays on".
     */
    @Test
    fun touchControlsAreIndependentOfWebsiteClientMode() {
        val off = AppSettings()
        val on = off.copy(stream = off.stream.copy(browserClientMode = true))
        assertEquals(off.androidTouch, on.androidTouch)
        assertEquals(off.androidTouch.nativeTouchMode, on.androidTouch.nativeTouchMode)
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
