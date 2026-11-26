package com.opencloudgaming.opennow

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattedReleaseNotesTest {

    @Test
    fun parseMarkdownInline_stripsMarkersAndPreservesText() {
        val input = "Pembaruan **besar** pada `Streaming.kt` dan fitur **audio**."
        val result = parseMarkdownInline(input)
        assertEquals("Pembaruan besar pada Streaming.kt dan fitur audio.", result.text)
    }

    @Test
    fun parseMarkdownInline_handlesPlainWithoutMarkers() {
        val input = "Perbaikan koneksi antrean cloud."
        val result = parseMarkdownInline(input)
        assertEquals("Perbaikan koneksi antrean cloud.", result.text)
    }

    @Test
    fun parseMarkdownInline_handlesUnpairedMarkersGracefully() {
        val input = "Single * asterisk and single ` backtick."
        val result = parseMarkdownInline(input)
        assertEquals("Single * asterisk and single ` backtick.", result.text)
    }

    @Test
    fun selectLocalizedReleaseNotes_picksIndonesianOrEnglishSection() {
        val bilingual = """
            ## Bahasa Indonesia
            ### Perbaikan Stream
            - Koneksi lebih stabil.

            ## English
            ### Stream Fixes
            - More stable connection.
        """.trimIndent()

        assertEquals(
            "### Perbaikan Stream\n- Koneksi lebih stabil.",
            selectLocalizedReleaseNotes(bilingual, "in"),
        )
        assertEquals(
            "### Perbaikan Stream\n- Koneksi lebih stabil.",
            selectLocalizedReleaseNotes(bilingual, "id"),
        )
        assertEquals(
            "### Stream Fixes\n- More stable connection.",
            selectLocalizedReleaseNotes(bilingual, "en"),
        )
    }

    @Test
    fun selectLocalizedReleaseNotes_returnsOriginalWhenSingleLanguage() {
        val englishOnly = "## What's New\n- Faster startup."
        assertEquals(englishOnly, selectLocalizedReleaseNotes(englishOnly, "in"))
    }
}
