package com.achulkov.evokammer.sandbox.network

import com.achulkov.evokammer.core.model.Role
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AllowlistProxyTest {
    @Test
    fun `default allowlist accepts general public web domains`() {
        val proxy = AllowlistProxy()

        assertIs<AllowlistDecision.Allowed>(
            proxy.evaluate("https://platform.openai.com/docs", Role.Researcher)
        )
        assertIs<AllowlistDecision.Allowed>(
            proxy.evaluate("https://raw.githubusercontent.com/owner/repo/main/README.md", Role.Builder)
        )
        assertIs<AllowlistDecision.Allowed>(
            proxy.evaluate("https://docs.python.org/3/", Role.Researcher)
        )
    }

    @Test
    fun `default allowlist still denies unknown hosts`() {
        val proxy = AllowlistProxy()

        val decision = proxy.evaluate("https://example.invalid/path", Role.Researcher)
        assertTrue(decision is AllowlistDecision.Denied)
    }
}
