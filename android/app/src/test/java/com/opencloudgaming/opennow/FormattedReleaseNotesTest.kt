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
}
