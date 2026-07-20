package com.achulkov.evomolior.sandbox.network

import com.achulkov.evomolior.core.model.Role
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AllowlistProxyTest {
    @Test
    fun `default allowlist accepts general public web domains`() {
        val proxy = AllowlistProxy()

        assertIs<AllowlistDecision.Allowed>(
            proxy.evaluate(
                "https://platform.openai.com/docs",
                Role.Researcher,
                setOf("platform.openai.com")
            )
        )
        assertIs<AllowlistDecision.Allowed>(
            proxy.evaluate(
                "https://raw.githubusercontent.com/owner/repo/main/README.md",
                Role.Builder,
                setOf("raw.githubusercontent.com")
            )
        )
        assertIs<AllowlistDecision.Allowed>(
            proxy.evaluate("https://docs.python.org/3/", Role.Researcher, setOf("docs.python.org"))
        )
    }

    @Test
    fun `default allowlist still denies unknown hosts`() {
        val proxy = AllowlistProxy()

        val decision = proxy.evaluate(
            "https://example.invalid/path",
            Role.Researcher,
            setOf("platform.openai.com")
        )
        assertTrue(decision is AllowlistDecision.Denied)
    }

    @Test
    fun `role restriction narrows the approved run hosts`() {
        val proxy = AllowlistProxy(
            roleAllowedHosts = mapOf(Role.Researcher to setOf("research.example"))
        )

        val decision = proxy.evaluate(
            "https://build.example/tool",
            Role.Researcher,
            setOf("build.example")
        )

        assertIs<AllowlistDecision.Denied>(decision)
    }
}
