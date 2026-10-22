package com.achulkov.evomolior.substrate

import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.contract.ProvisionalNovelHypothesis
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.novelty.ProvisionalNovelHypothesisLedger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProvisionalNovelHypothesisLedgerTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    @Test
    fun `ledger returns only promoted speculative hypotheses`() = runTest {
        val database = SubstrateDatabaseFactory(
            createTempDirectory("hypothesis-ledger-").toFile()
        ).get("run-ledger", "group-ledger")
        try {
            val hypothesis = ProvisionalNovelHypothesis(
                hypothesisId = "hypothesis-ledger-1",
                runId = "run-ledger",
                groupId = AtlasGroupId("group-ledger"),
                round = 1,
                agentId = AtlasAgentId("agent-ledger"),
                title = "Testable idea",
                idea = "A testable idea survives propagation.",
                inferredFrom = "Observed in artifact:one.",
                assumptions = listOf("The artifact is representative."),
                uncertainty = "One example is insufficient.",
                expectedImpact = "A focused test may find a reusable rule.",
                falsifiablePrediction = "The rule fails on a counterexample.",
                minimalTestOrArtifact = "Run the rule on one held-out case.",
                confidence = 0.3,
                provenanceRefs = listOf("artifact:one")
            ).withComputedHash()
            database.stagingDao().insert(
                row(
                    id = "promoted",
                    content = json.encodeToString(hypothesis),
                    status = "promoted",
                    speculative = true
                )
            )
            database.stagingDao().insert(
                row(
                    id = "pending",
                    content = json.encodeToString(hypothesis),
                    status = "pending",
                    speculative = true
                )
            )
            database.stagingDao().insert(
                row(
                    id = "non-speculative",
                    content = json.encodeToString(hypothesis),
                    status = "promoted",
                    speculative = false
                )
            )

            val result = ProvisionalNovelHypothesisLedger(database.stagingDao())
                .getPromoted("run-ledger", "group-ledger")

            assertEquals(listOf(hypothesis), result)
            assertTrue(result.single().hasValidHash())
        } finally {
            database.close()
        }
    }

    private fun row(
        id: String,
        content: String,
        status: String,
        speculative: Boolean
    ) = StagingSubmissionEntity(
        id = id,
        agentId = "agent-ledger",
        role = "Researcher",
        type = "HYPOTHESIS",
        content = content,
        speculative = speculative,
        budgetSignature = "signature",
        status = status,
        criticAgentId = "critic",
        generation = 1,
        groupId = "group-ledger",
        runId = "run-ledger",
        submittedAtMs = id.hashCode().toLong(),
        reviewedAtMs = 2L
    )
}
