package com.achulkov.evomolior.sandbox.network

import kotlin.test.Test
import kotlin.test.assertEquals

class ContextUrlAllowlistExtractorTest {
    @Test
    fun `extractHosts finds direct urls and bare www links`() {
        val hosts = ContextUrlAllowlistExtractor.extractHosts(
            "See https://docs.example.com/guide.",
            "Also check www.example.org/reference and https://sub.domain.example.net/path?x=1."
        )

        assertEquals(
            setOf("docs.example.com", "www.example.org", "sub.domain.example.net"),
            hosts
        )
    }
}
