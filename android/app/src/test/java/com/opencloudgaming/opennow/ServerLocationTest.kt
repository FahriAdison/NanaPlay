package com.opencloudgaming.opennow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerLocationTest {
    @Test
    fun mapsKnownGfnZonePrefixesToIndonesianLabels() {
        assertEquals("Tokyo, Jepang 🇯🇵", serverLocationLabel("NP-TYO-01"))
        assertEquals("Amsterdam, Belanda 🇳🇱", serverLocationLabel("NP-AMS-06"))
        assertEquals("Mumbai, India 🇮🇳", serverLocationLabel("NP-BOM-01"))
        assertEquals("Paris, Prancis 🇫🇷", serverLocationLabel("NP-PAR-07"))
        assertEquals("London, Inggris 🇬🇧", serverLocationLabel("NP-LON-05"))
        assertEquals("Dallas, Amerika Serikat 🇺🇸", serverLocationLabel("NP-DAL-04"))
    }

    @Test
    fun handlesAllianceAndFourCharacterServerIds() {
        assertEquals("Kuala Lumpur, Malaysia 🇲🇾", serverLocationLabel("NPA-YES-KUL-01"))
        assertEquals("Taipei, Taiwan 🇹🇼", serverLocationLabel("NPA-TWM-TPE-02"))
        assertEquals("San Jose, Amerika Serikat 🇺🇸", serverLocationLabel("NP-SJC6-04"))
    }

    @Test
    fun unknownOrBlankZonesStayUnknown() {
        assertNull(serverLocationLabel(null))
        assertNull(serverLocationLabel(""))
        assertNull(serverLocationLabel("NP-XXX-01"))
    }
}
