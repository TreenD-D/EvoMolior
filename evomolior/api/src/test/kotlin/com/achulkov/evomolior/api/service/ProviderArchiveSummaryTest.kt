@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.contract.CostLedgerEntry
import com.achulkov.evomolior.core.contract.CostLedgerEntryKind
import com.achulkov.evomolior.core.contract.CostSubjectType
import com.achulkov.evomolior.core.contract.MonetaryAmount
import com.achulkov.evomolior.core.contract.MonetaryAmountStatus
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class ProviderArchiveSummaryTest {
    @Test
    fun `only finalized calls count and unknown usage is never priced`() {
        val finalized = CostLedgerEntry(
            entryId = "finalized", reservationId = "reservation", runId = "run",
            groupId = AtlasGroupId("group"), agentId = "agent",
            subjectType = CostSubjectType.LLM_PROVIDER_CALL, subjectId = "call",
            kind = CostLedgerEntryKind.FINALIZED,
            providerCost = MonetaryAmount(MonetaryAmountStatus.UNKNOWN),
            wallTimeMillis = 125,
            occurredAt = Instant.fromEpochMilliseconds(0),
        )
        val reserved = finalized.copy(
            entryId = "reserved", kind = CostLedgerEntryKind.RESERVED,
            providerCost = MonetaryAmount(MonetaryAmountStatus.NOT_APPLICABLE),
            wallTimeMillis = null,
        )
        val lines = ProviderArchiveSummary.render(
            "run", listOf(reserved, finalized, finalized.copy(runId = "other")),
            mapOf(("group" to "agent") to "writer"),
        )
        assertEquals(2, lines.size)
        assertTrue(lines.first().contains("finalizedCalls=1"))
        assertTrue(lines.last().contains("role=writer calls=1"))
        assertTrue(lines.last().contains("wallTimeMillis=125"))
        assertTrue(lines.last().contains("unknownInputUsageCalls=1"))
        assertTrue(lines.last().contains("unknownProviderCostCalls=1"))
        assertFalse(lines.any { it.contains("USD") || it.contains("usd=") })
        assertTrue(ProviderArchiveSummary.render("run", listOf(finalized)).last().contains("role=UNKNOWN"))
    }

    @Test
    fun `unregistered runtime agent ids are labelled as inferred roles`() {
        val expected = mapOf(
            "council:CouncilTaskAnatomist" to "CouncilTaskAnatomist",
            "critic-grader-group_567d20-r3" to "Critic/grader-criteria",
            "critic-worker-group_c83da2-r1" to "Critic",
            "critic-review-9f2" to "Critic/second-review",
            "criteria-structured-graderbuilder-1a2b3c" to "GraderBuilder/structured",
            "criteria-graderbuilder-67c91402" to "GraderBuilder",
            "criteria-graderresearcher-0a1b2c3d" to "GraderResearcher",
            "round-judge-grader-group_1-r0" to "RoundJudge",
            "differential-privacy-transform-r2" to "LeakTransform",
            "agent-builder-0555c45c" to "Builder",
        )
        expected.forEach { (agentId, role) ->
            assertEquals(role, ProviderArchiveSummary.inferredRole(agentId), agentId)
        }
        listOf(null, "", "agent", "writer-7").forEach { agentId ->
            assertEquals(null, ProviderArchiveSummary.inferredRole(agentId), agentId.toString())
        }

        val call = CostLedgerEntry(
            entryId = "judge", reservationId = "reservation", runId = "run",
            groupId = AtlasGroupId("grader-group_1"), agentId = "round-judge-grader-group_1-r0",
            subjectType = CostSubjectType.LLM_PROVIDER_CALL, subjectId = "call",
            kind = CostLedgerEntryKind.FINALIZED,
            providerCost = MonetaryAmount(MonetaryAmountStatus.UNKNOWN),
            wallTimeMillis = 75_000,
            occurredAt = Instant.fromEpochMilliseconds(0),
        )
        assertTrue(ProviderArchiveSummary.render("run", listOf(call)).last().contains("role=inferred:RoundJudge calls=1"))
    }
}
