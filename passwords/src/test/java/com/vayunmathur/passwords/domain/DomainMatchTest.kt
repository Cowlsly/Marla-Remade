package com.vayunmathur.passwords.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DomainMatchTest {

    @Test fun normalizeStripsSchemePathPortAndWww() {
        assertEquals("example.com", DomainMatch.normalizeSite("https://www.example.com/login?next=1"))
        assertEquals("example.com", DomainMatch.normalizeSite("  HTTP://Example.COM:8443/  "))
        assertEquals("example.com", DomainMatch.normalizeSite("example.com."))
        assertEquals("example.com", DomainMatch.normalizeSite("https://user:pw@example.com"))
        assertEquals("mail.example.com", DomainMatch.normalizeSite("mail.example.com"))
    }

    @Test fun subdomainMatchesItsParent() {
        assertTrue(DomainMatch.isSameSiteOrSubdomain("example.com", "example.com"))
        assertTrue(DomainMatch.isSameSiteOrSubdomain("mail.example.com", "example.com"))
        assertTrue(DomainMatch.isSameSiteOrSubdomain("a.b.example.com", "example.com"))
    }

    /** The bug the old bidirectional endsWith had. */
    @Test fun siblingAndSuffixLookalikesDoNotMatch() {
        assertFalse(DomainMatch.isSameSiteOrSubdomain("notexample.com", "example.com"))
        assertFalse(DomainMatch.isSameSiteOrSubdomain("example.com", "mail.example.com"))
        assertFalse(DomainMatch.isSameSiteOrSubdomain("mail.example.com", "bank.example.com"))
        assertFalse(DomainMatch.isSameSiteOrSubdomain("", "example.com"))
        assertFalse(DomainMatch.isSameSiteOrSubdomain("example.com", ""))
    }

    @Test fun androidTargetsAreNotHosts() {
        assertTrue(DomainMatch.isAndroidPackageSite("android-app://com.example.app"))
        assertTrue(DomainMatch.isAndroidPackageSite("com.example.app"))
        assertFalse(DomainMatch.isAndroidPackageSite("example.com"))
        assertFalse(DomainMatch.isAndroidPackageSite("https://mail.example.com"))
    }
}
