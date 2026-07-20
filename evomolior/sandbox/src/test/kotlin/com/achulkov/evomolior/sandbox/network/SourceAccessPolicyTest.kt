package com.achulkov.evomolior.sandbox.network

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SourceAccessPolicyTest {
    @Test
    fun `initialAllowedHosts combines prompt links artifact links and qwen subject inference`() {
        val hosts = SourceAccessPolicy.initialAllowedHosts(
            objective = "Use Qwen DashScope docs and https://qwencloud-hackathon.devpost.com/resources",
            artifactHosts = setOf("example.org")
        )

        assertTrue("example.org" in hosts)
        assertTrue("qwencloud-hackathon.devpost.com" in hosts)
        assertTrue("dashscope.aliyuncs.com" in hosts)
        assertTrue("help.aliyun.com" in hosts)
    }

    @Test
    fun `evaluateRequest allows subject relevant hosts and blocks unsafe hosts`() {
        val allowed = SourceAccessPolicy.evaluateRequest(
            rawUrl = "https://help.aliyun.com/document_detail/important",
            purpose = "Check DashScope API details for the Qwen backend integration.",
            runObjective = "Improve Qwen DashScope support.",
            currentlyAllowedHosts = emptySet()
        )
        assertIs<SourceAccessDecision.Allowed>(allowed)

        val blocked = SourceAccessPolicy.evaluateRequest(
            rawUrl = "http://127.0.0.1:8080/secrets",
            purpose = "Try local metadata endpoint.",
            runObjective = "Improve Qwen DashScope support.",
            currentlyAllowedHosts = emptySet()
        )
        assertIs<SourceAccessDecision.Blocked>(blocked)
        assertFalse(blocked.reason.isBlank())
    }

    @Test
    fun `evaluateApprovedFetch does not infer new hosts`() {
        val blocked = SourceAccessPolicy.evaluateApprovedFetch(
            rawUrl = "https://help.aliyun.com/document_detail/important",
            currentlyAllowedHosts = emptySet()
        )
        assertIs<SourceAccessDecision.Blocked>(blocked)
        assertTrue(blocked.reason.contains("request_source_access"))

        val allowed = SourceAccessPolicy.evaluateApprovedFetch(
            rawUrl = "https://help.aliyun.com/document_detail/important",
            currentlyAllowedHosts = setOf("help.aliyun.com")
        )
        assertIs<SourceAccessDecision.Allowed>(allowed)
    }

    @Test
    fun `prediction market objectives infer public venue hosts`() {
        val initialHosts = SourceAccessPolicy.initialAllowedHosts(
            objective = "Find prediction-market edges across Kalshi, Polymarket, PredictIt, and Manifold."
        )

        assertTrue("kalshi.com" in initialHosts)
        assertTrue("polymarket.com" in initialHosts)
        assertTrue("predictit.org" in initialHosts)
        assertTrue("manifold.markets" in initialHosts)

        val allowed = SourceAccessPolicy.evaluateRequest(
            rawUrl = "https://clob.polymarket.com/markets",
            purpose = "Inspect public prediction market event metadata for mispricing research.",
            runObjective = "Find prediction-market edges without pre-seeded market data.",
            currentlyAllowedHosts = emptySet()
        )

        assertIs<SourceAccessDecision.Allowed>(allowed)
    }

    @Test
    fun `micro saas product discovery objectives infer public complaint and store hosts`() {
        val initialHosts = SourceAccessPolicy.initialAllowedHosts(
            objective = "Find fast micro-SaaS and app product hypotheses for Play Store and App Store validation."
        )

        assertTrue("reddit.com" in initialHosts)
        assertTrue("producthunt.com" in initialHosts)
        assertTrue("play.google.com" in initialHosts)
        assertTrue("apps.apple.com" in initialHosts)

        val allowed = SourceAccessPolicy.evaluateRequest(
            rawUrl = "https://www.reddit.com/r/androidapps/",
            purpose = "Inspect public complaint signals for app product discovery and validation.",
            runObjective = "Find fast micro-SaaS and app product hypotheses.",
            currentlyAllowedHosts = emptySet()
        )

        assertIs<SourceAccessDecision.Allowed>(allowed)
    }
}
