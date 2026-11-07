package com.knowledgeatlas.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtlasUrlPolicyTest {
    @Test
    fun acceptsAbsoluteHttpsUrlsWithoutCredentials() {
        assertTrue(AtlasUrlPolicy.isHttpsUrl("https://example.org"))
        assertTrue(AtlasUrlPolicy.isHttpsUrl("https://search.example.org/v1/search?q=soil"))
        assertTrue(AtlasUrlPolicy.isHttpsUrl("https://example.org:8443/path"))
    }

    @Test
    fun rejectsPlainHttpAndRelativeUrls() {
        assertFalse(AtlasUrlPolicy.isHttpsUrl("http://example.org"))
        assertFalse(AtlasUrlPolicy.isHttpsUrl("/search"))
        assertFalse(AtlasUrlPolicy.isHttpsUrl("example.org"))
    }

    @Test
    fun rejectsCredentialsMalformedHostsAndWhitespace() {
        assertFalse(AtlasUrlPolicy.isHttpsUrl("https://user:password@example.org"))
        assertFalse(AtlasUrlPolicy.isHttpsUrl("https:///missing-host"))
        assertFalse(AtlasUrlPolicy.isHttpsUrl(" https://example.org"))
        assertFalse(AtlasUrlPolicy.isHttpsUrl("https://"))
    }
}
