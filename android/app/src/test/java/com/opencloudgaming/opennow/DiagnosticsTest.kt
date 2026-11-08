package com.opencloudgaming.opennow

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsTest {

    @Test
    fun stripUrlUserinfoRemovesUserinfoFromTurnUrl() {
        assertEquals("turn:host:3478", stripUrlUserinfo("turn:user:pass@host:3478"))
    }

    @Test
    fun stripUrlUserinfoKeepsUrlsWithoutUserinfoUnchanged() {
        assertEquals("stun:s1.stun.gamestream.nvidia.com:19308", stripUrlUserinfo("stun:s1.stun.gamestream.nvidia.com:19308"))
        assertEquals("turn:host:3478", stripUrlUserinfo("turn:host:3478"))
    }

    @Test
    fun stripUrlUserinfoHandlesAtSignInsidePassword() {
        // Authority ends at the LAST '@': the whole userinfo (including the '@' in the
        // password) must go, not just the part before the first '@'.
        assertEquals("turn:host:3478", stripUrlUserinfo("turn:u:p@ss@host:3478"))
    }

    @Test
    fun stripUrlUserinfoLeavesQueryStringIntact() {
        // The log call site strips the query before calling; the function itself must only
        // remove userinfo and leave everything else (query included) untouched.
        assertEquals("turn:host:3478?transport=udp", stripUrlUserinfo("turn:u:p@host:3478?transport=udp"))
    }

    @Test
    fun stripUrlUserinfoLeavesUrlsWithoutAuthorityAlone() {
        assertEquals("no-colon", stripUrlUserinfo("no-colon"))
        // '@' in the path is not userinfo: must not be stripped.
        assertEquals("https://host/~user", stripUrlUserinfo("https://host/~user"))
    }
}
