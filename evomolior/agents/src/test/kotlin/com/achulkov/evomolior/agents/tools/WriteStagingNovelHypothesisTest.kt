package com.achulkov.evomolior.agents.tools

import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.contract.ProvisionalNovelHypothesis
import com.achulkov.evomolior.core.budget.BudgetConfig
import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WriteStagingNovelHypothesisTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    @Test
    fun `valid hypothesis stages as explicitly speculative`() = runTest {
        val database = SubstrateDatabaseFactory(
            createTempDirectory("write-staging-hypothesis-valid-").toFile()
        ).get("run-hypothesis", "group-hypothesis")
        try {
            val tool = tool(database.stagingDao())
            val result = tool.execute(
                WriteStagingTool.Args(
                    type = "HYPOTHESIS",
                    content = json.encodeToString(hypothesis().withComputedHash()),
                    speculative = true
                )
            )

            assertTrue(result.startsWith("Submission accepted"))
            val stored = database.stagingDao()
                .getByAgent("run-hypothesis", "group-hypothesis", "agent-hypothesis")
                .single()
            assertEquals("HYPOTHESIS", stored.type)
            assertTrue(stored.speculative)
        } finally {
            database.close()
        }
    }

    @Test
    fun `hypothesis rejects identity speculative and hash violations before staging`() = runTest {
        val database = SubstrateDatabaseFactory(
            createTempDirectory("write-staging-hypothesis-invalid-").toFile()
        ).get("run-hypothesis", "group-hypothesis")
        try {
            val cases = listOf(
                WriteStagingTool.Args(
                    type = "HYPOTHESIS",
                    content = json.encodeToString(
                        hypothesis(runId = "foreign-run").withComputedHash()
                    ),
                    speculative = true
                ) to "identity",
                WriteStagingTool.Args(
                    type = "HYPOTHESIS",
                    content = json.encodeToString(hypothesis().withComputedHash()),
                    speculative = false
                ) to "speculative",
                WriteStagingTool.Args(
                    type = "HYPOTHESIS",
                    content = json.encodeToString(
                        hypothesis().withComputedHash().copy(idea = "tampered")
                    ),
                    speculative = true
                ) to "self-hash"
            )
            cases.forEach { (args, expected) ->
                val result = tool(database.stagingDao()).execute(args)
                assertTrue(result.contains("Error"), expected)
            }
            assertTrue(
                database.stagingDao()
                    .getByAgent("run-hypothesis", "group-hypothesis", "agent-hypothesis")
                    .isEmpty()
            )
        } finally {
            database.close()
        }
    }

    private fun tool(stagingDao: com.achulkov.evomolior.substrate.db.dao.StagingDao) =
        WriteStagingTool(
            stagingDao = stagingDao,
            budgetLedger = BudgetLedger(
                agentId = "agent-hypothesis",
                initialBudget = 100.0,
                floor = 1.0,
                config = BudgetConfig(
                    groupId = "group-hypothesis",
                    profile = GroupProfile.default(GroupProfileType.Library)
                )
            ),
            runId = "run-hypothesis",
            groupId = "group-hypothesis",
            agentId = "agent-hypothesis",
            role = "Researcher",
            generation = 3
        )

    private fun hypothesis(
        runId: String = "run-hypothesis"
    ) = ProvisionalNovelHypothesis(
        hypothesisId = "hypothesis-tool-1",
        runId = runId,
        groupId = AtlasGroupId("group-hypothesis"),
        round = 3,
        agentId = AtlasAgentId("agent-hypothesis"),
        title = "Pairing invariant",
        idea = "A hidden pairing invariant may explain repeated near misses.",
        inferredFrom = "Two held-out examples share a positional symmetry.",
        assumptions = listOf("The examples belong to one task family."),
        uncertainty = "The sample may be too small.",
        expectedImpact = "Reduce false candidate matches.",
        falsifiablePrediction = "A held-out counterexample breaks the pairing rule.",
        minimalTestOrArtifact = "Run the rule on three held-out examples.",
        confidence = 0.4,
        provenanceRefs = listOf("artifact:read-1")
    )
}
