package com.achulkov.evokammer.cli

import com.achulkov.evokammer.agents.factory.OverseerAgentRunner
import com.achulkov.evokammer.api.service.OverseerKernelService
import com.achulkov.evokammer.cli.selfimprovement.ProposalProvider
import com.achulkov.evokammer.cli.selfimprovement.SelfImprovementLoopRunner
import com.achulkov.evokammer.core.contract.OverseerExpectedEffect
import com.achulkov.evokammer.core.contract.OverseerHypothesis
import com.achulkov.evokammer.core.contract.OverseerMutabilityLevel
import com.achulkov.evokammer.core.contract.OverseerOrigin
import com.achulkov.evokammer.core.contract.OverseerOriginType
import com.achulkov.evokammer.core.contract.OverseerTargetScope
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Files

class SelfImprovementLoopRunnerTest {

    @Test
    fun `runCycle creates a missing workspace root and persists overseer state`() = runTest {
        val sourceRepoRoot = createTempDirectory("self-improve-source-").toFile().toPath()
        val workspaceRoot = createTempDirectory("self-improve-cycle-")
            .toFile()
            .toPath()
            .resolveSibling("self-improve-cycle-${System.nanoTime()}")

        val proposal = OverseerAgentRunner.OverseerProposal(
            proposedHypotheses = listOf(
                OverseerHypothesis(
                    id = "HYP-self-improve-001",
                    title = "Harden the self-improvement launcher",
                    origin = OverseerOrigin(
                        type = OverseerOriginType.INTERNAL_LOGS,
                        sources = listOf("documentation/EvoKammer_Self_Improvement_Operator_Guide.md")
                    ),
                    targetScope = OverseerTargetScope(
                        paths = listOf("documentation/EvoKammer_Self_Improvement_Operator_Guide.md"),
                        mutabilityLevel = OverseerMutabilityLevel.FULLY_MUTABLE
                    ),
                    rationale = "Exercise the overseer cycle without touching immutable paths.",
                    expectedEffect = OverseerExpectedEffect(
                        metricsUp = listOf("launcher_reliability"),
                        metricsDown = listOf("operator_friction")
                    )
                )
            ),
            rationale = "Test executor proposal"
        )

        val runner = SelfImprovementLoopRunner(
            sourceRepoRoot = sourceRepoRoot,
            workspaceRoot = workspaceRoot,
            openRouterApiKey = "sk-test",
            proposalProvider = ProposalProvider { _, _ -> proposal }
        )

        assertFalse(workspaceRoot.exists())

        val reports = runner.runCycle(cycleLimit = 1)

        assertTrue(workspaceRoot.exists())
        assertTrue(workspaceRoot.resolve(".evokammer-data").exists())
        assertEquals(1, reports.size)
        assertEquals(1, reports.single().proposalsCount)
        assertEquals(1, reports.single().formalizedCount)
        assertEquals(0, reports.single().experimentResults)
        assertTrue(reports.single().errors.isEmpty())

        val persistedState = OverseerKernelService(
            substrateFactory = SubstrateDatabaseFactory(workspaceRoot.resolve(".evokammer-data").toFile())
        ).currentState()
        assertTrue(persistedState.hypotheses.containsKey("HYP-self-improve-001"))
    }

    @Test
    fun `runCycle seeds a bootstrap hypothesis when the agent returns nothing for an empty kernel`() = runTest {
        val sourceRepoRoot = createTempDirectory("self-improve-source-").toFile().toPath()
        Files.createDirectories(sourceRepoRoot.resolve("documentation"))
        sourceRepoRoot.resolve("documentation/EvoKammer_Current_Session_Handoff.md").writeText(
            "Handoff marker: bootstrap-context-test"
        )
        sourceRepoRoot.resolve("documentation/EvoKammer_Self_Improvement_Operator_Guide.md").writeText(
            "Guide marker: bootstrap-context-test"
        )
        val workspaceRoot = createTempDirectory("self-improve-bootstrap-")
            .toFile()
            .toPath()
            .resolveSibling("self-improve-bootstrap-${System.nanoTime()}")

        val runner = SelfImprovementLoopRunner(
            sourceRepoRoot = sourceRepoRoot,
            workspaceRoot = workspaceRoot,
            openRouterApiKey = "sk-test",
            proposalProvider = ProposalProvider { _, _ ->
                OverseerAgentRunner.OverseerProposal()
            }
        )

        val reports = runner.runCycle(cycleLimit = 1)

        assertTrue(workspaceRoot.exists())
        assertEquals(1, reports.size)
        assertEquals(1, reports.single().proposalsCount)
        assertEquals(1, reports.single().formalizedCount)
        assertTrue(reports.single().errors.isEmpty())

        val persistedState = OverseerKernelService(
            substrateFactory = SubstrateDatabaseFactory(workspaceRoot.resolve(".evokammer-data").toFile())
        ).currentState()
        val bootstrapHypothesis = persistedState.hypotheses.getValue("HYP-self-improve-bootstrap-001")
        assertTrue(bootstrapHypothesis.notes.contains("Bootstrap-triggered"))
        assertTrue(bootstrapHypothesis.rationale.contains("bootstrap-context-test"))
    }
}
