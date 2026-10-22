package com.opencloudgaming.opennow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerLocationTest {
    @Test
    fun mapsKnownGfnZonePrefixesToCountryCodes() {
        assertEquals("Tokyo", serverLocationForZoneId("NP-TYO-01")?.city)
        assertEquals("JP", serverLocationForZoneId("NP-TYO-01")?.countryCode)
        assertEquals("NL", serverLocationForZoneId("NP-AMS-06")?.countryCode)
        assertEquals("IN", serverLocationForZoneId("NP-BOM-01")?.countryCode)
        assertEquals("FR", serverLocationForZoneId("NP-PAR-07")?.countryCode)
        assertEquals("GB", serverLocationForZoneId("NP-LON-05")?.countryCode)
        assertEquals("US", serverLocationForZoneId("NP-DAL-04")?.countryCode)
    }

    @Test
    fun rendersIndonesianCountryNames() {
        assertEquals("Tokyo, Jepang 🇯🇵", serverLocationLabel("NP-TYO-01", "in"))
        assertEquals("Amsterdam, Belanda 🇳🇱", serverLocationLabel("NP-AMS-06", "in"))
        assertEquals("Mumbai, India 🇮🇳", serverLocationLabel("NP-BOM-01", "in"))
        assertEquals("Paris, Prancis 🇫🇷", serverLocationLabel("NP-PAR-07", "in"))
        assertEquals("London, Inggris 🇬🇧", serverLocationLabel("NP-LON-05", "in"))
        assertEquals("Dallas, Amerika Serikat 🇺🇸", serverLocationLabel("NP-DAL-04", "in"))
    }

    @Test
    fun rendersEnglishCountryNames() {
        assertEquals("Tokyo, Japan 🇯🇵", serverLocationLabel("NP-TYO-01", "en"))
        assertEquals("Amsterdam, Netherlands 🇳🇱", serverLocationLabel("NP-AMS-06", "en"))
        assertEquals("Mumbai, India 🇮🇳", serverLocationLabel("NP-BOM-01", "en"))
        assertEquals("Paris, France 🇫🇷", serverLocationLabel("NP-PAR-07", "en"))
        assertEquals("London, United Kingdom 🇬🇧", serverLocationLabel("NP-LON-05", "en"))
        assertEquals("Dallas, United States 🇺🇸", serverLocationLabel("NP-DAL-04", "en"))
    }

    @Test
    fun handlesAllianceAndFourCharacterServerIds() {
        assertEquals("Kuala Lumpur, Malaysia 🇲🇾", serverLocationLabel("NPA-YES-KUL-01", "en"))
        assertEquals("Taipei, Taiwan 🇹🇼", serverLocationLabel("NPA-TWM-TPE-02", "in"))
        assertEquals("San Jose, United States 🇺🇸", serverLocationLabel("NP-SJC6-04", "en"))
    }

    @Test
    fun nonIndonesianLanguageFallsBackToEnglish() {
        assertEquals("Tokyo, Japan 🇯🇵", serverLocationLabel("NP-TYO-01", "fr"))
        assertEquals("Tokyo, Japan 🇯🇵", serverLocationLabel("NP-TYO-01", null))
    }

    @Test
    fun unknownOrBlankZonesStayUnknown() {
        assertNull(serverLocationLabel(null, "en"))
        assertNull(serverLocationLabel("", "in"))
        assertNull(serverLocationLabel("NP-XXX-01", "en"))
    }
}
