package com.achulkov.evokammer.cli

import com.achulkov.evokammer.agents.factory.OverseerAgentRunner
import com.achulkov.evokammer.api.service.OverseerKernelService
import com.achulkov.evokammer.cli.selfimprovement.ProposalProvider
import com.achulkov.evokammer.cli.selfimprovement.SelfImprovementLoopRunner
import com.achulkov.evokammer.core.contract.OverseerAcceptanceCriteria
import com.achulkov.evokammer.core.contract.OverseerExpectedEffect
import com.achulkov.evokammer.core.contract.OverseerExperimentMetrics
import com.achulkov.evokammer.core.contract.OverseerExperimentPlan
import com.achulkov.evokammer.core.contract.OverseerHypothesis
import com.achulkov.evokammer.core.contract.OverseerMutabilityLevel
import com.achulkov.evokammer.core.contract.OverseerOrigin
import com.achulkov.evokammer.core.contract.OverseerOriginType
import com.achulkov.evokammer.core.contract.OverseerTargetScope
import com.achulkov.evokammer.core.contract.OverseerVariationSpec
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
        assertTrue(workspaceRoot.resolve(".evokammer-self-improve/repo").exists())
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

    @Test
    fun `runCycle can execute scheduled experiments when enabled`() = runTest {
        val sourceRepoRoot = createTempDirectory("self-improve-source-").toFile().toPath()
        val workspaceRoot = createTempDirectory("self-improve-execute-")
            .toFile()
            .toPath()
            .resolveSibling("self-improve-execute-${System.nanoTime()}")

        val hypothesis = OverseerHypothesis(
            id = "HYP-self-improve-execute-001",
            title = "Execute scheduled overseer plans",
            origin = OverseerOrigin(
                type = OverseerOriginType.INTERNAL_LOGS,
                sources = listOf("documentation/EvoKammer_Current_Session_Handoff.md")
            ),
            targetScope = OverseerTargetScope(
                paths = listOf("evokammer/cli/src/main/kotlin/com/achulkov/evokammer/cli/SelfImprovementCli.kt"),
                mutabilityLevel = OverseerMutabilityLevel.FULLY_MUTABLE
            ),
            rationale = "Exercise the overseer execution bridge.",
            expectedEffect = OverseerExpectedEffect(
                metricsUp = listOf("execution_coverage"),
                invariantsHold = listOf("kernel_state_persists")
            )
        )
        val plan = OverseerExperimentPlan(
            id = "PLAN-self-improve-execute-001",
            hypothesisId = hypothesis.id,
            runCount = 1,
            generationsPerRun = 1,
            variationSpec = OverseerVariationSpec(
                configChanges = listOf("enable overseer execution bridge")
            ),
            metrics = OverseerExperimentMetrics(primary = listOf("run_completed")),
            acceptanceCriteria = OverseerAcceptanceCriteria(predicates = listOf("run_completed > 0.5")),
            maxCost = 1.0
        )

        val runner = SelfImprovementLoopRunner(
            sourceRepoRoot = sourceRepoRoot,
            workspaceRoot = workspaceRoot,
            openRouterApiKey = "sk-test",
            proposalProvider = ProposalProvider { _, _ ->
                OverseerAgentRunner.OverseerProposal(
                    proposedHypotheses = listOf(hypothesis),
                    proposedExperimentPlans = listOf(plan),
                    rationale = "proposal with scheduled execution"
                )
            },
            experimentCycleRunner = { 2 },
            executeScheduledExperiments = true
        )

        val reports = runner.runCycle(cycleLimit = 1)

        assertEquals(1, reports.size)
        assertEquals(1, reports.single().formalizedCount)
        assertEquals(2, reports.single().proposalsCount)
        assertEquals(2, reports.single().experimentResults)
        assertTrue(reports.single().errors.isEmpty())

        val persistedState = OverseerKernelService(
            substrateFactory = SubstrateDatabaseFactory(workspaceRoot.resolve(".evokammer-data").toFile())
        ).currentState()
        assertTrue(persistedState.hypotheses.containsKey(hypothesis.id))
        assertTrue(persistedState.experimentPlans.containsKey(plan.id))
    }
}
