package net.stewart.armeria.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OriginCheckTest {

    @Test
    fun `absent origin passes`() {
        assertTrue(OriginCheck.originPermitted(null, "example.com:8443"))
    }

    @Test
    fun `matching host passes regardless of port`() {
        assertTrue(OriginCheck.originPermitted("https://example.com:8443", "example.com"))
        assertTrue(OriginCheck.originPermitted("https://example.com", "example.com:9090"))
        assertTrue(OriginCheck.originPermitted("https://EXAMPLE.com", "example.com"))
    }

    @Test
    fun `different host fails`() {
        assertFalse(OriginCheck.originPermitted("https://evil.example.net", "example.com"))
    }

    @Test
    fun `unknown authority fails closed`() {
        assertFalse(OriginCheck.originPermitted("https://example.com", null))
        assertFalse(OriginCheck.originPermitted("https://example.com", ""))
    }

    @Test
    fun `unparseable origin fails closed`() {
        assertFalse(OriginCheck.originPermitted("not a url", "example.com"))
    }

    @Test
    fun `ipv6 authority strips bracketed port`() {
        assertTrue(OriginCheck.originPermitted("https://[::1]:4200", "[::1]:8443"))
    }

    @Test
    fun `cookie parsing finds the named cookie`() {
        assertEquals("abc", OriginCheck.parseCookie("a=1; session=abc; b=2", "session"))
        assertNull(OriginCheck.parseCookie("a=1; b=2", "session"))
        assertNull(OriginCheck.parseCookie("session", "session"))
    }
}
