package com.achulkov.evomolior.cli

import com.achulkov.evomolior.agents.factory.OverseerAgentRunner
import com.achulkov.evomolior.api.service.OverseerKernelService
import com.achulkov.evomolior.cli.selfimprovement.ProposalProvider
import com.achulkov.evomolior.cli.selfimprovement.SelfImprovementLoopRunner
import com.achulkov.evomolior.core.contract.OverseerAcceptanceCriteria
import com.achulkov.evomolior.core.contract.OverseerExpectedEffect
import com.achulkov.evomolior.core.contract.OverseerExperimentMetrics
import com.achulkov.evomolior.core.contract.OverseerExperimentPlan
import com.achulkov.evomolior.core.contract.OverseerHypothesis
import com.achulkov.evomolior.core.contract.OverseerMutabilityLevel
import com.achulkov.evomolior.core.contract.OverseerOrigin
import com.achulkov.evomolior.core.contract.OverseerOriginType
import com.achulkov.evomolior.core.contract.OverseerTargetScope
import com.achulkov.evomolior.core.contract.OverseerVariationSpec
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.nio.file.Path

class SelfImprovementLoopRunnerLiveExecutionTest {

    @Test
    fun `real hypothesis and experiment plan record an accepted overseer result without bootstrap fallback`() = runBlocking {
        assumeTrue(
            System.getenv("EVOMOLIOR_LIVE_SELF_IMPROVEMENT_SMOKE").equals("true", ignoreCase = true),
            "Set EVOMOLIOR_LIVE_SELF_IMPROVEMENT_SMOKE=true to authorize provider-backed execution",
        )
        val apiKey = readOpenRouterApiKey()
        assumeTrue(apiKey.isNotBlank(), "OPENROUTER_API_KEY must be present in local.properties to run this live regression")

        val sourceRepoRoot = Path.of(".").toAbsolutePath().normalize()
        val workspaceRoot = createTempDirectory("self-improve-live-execution-")
            .toFile()
            .toPath()
            .resolveSibling("self-improve-live-execution-${System.nanoTime()}")

        val hypothesis = OverseerHypothesis(
            id = "HYP-live-execution-001",
            title = "Live overseer execution regression",
            origin = OverseerOrigin(
                type = OverseerOriginType.INTERNAL_LOGS,
                sources = listOf("documentation/EvoMolior_Current_Session_Handoff.md")
            ),
            targetScope = OverseerTargetScope(
                paths = listOf("documentation/EvoMolior_Self_Improvement_Operator_Guide.md"),
                mutabilityLevel = OverseerMutabilityLevel.FULLY_MUTABLE
            ),
            rationale = "Prove the overseer cycle executes a real plan without bootstrap fallback.",
            expectedEffect = OverseerExpectedEffect(
                metricsUp = listOf("accepted_results"),
                invariantsHold = listOf("workspace_isolation")
            )
        )
        val plan = OverseerExperimentPlan(
            id = "EXP-live-execution-001",
            hypothesisId = hypothesis.id,
            runCount = 1,
            generationsPerRun = 1,
            variationSpec = OverseerVariationSpec(
                toolOrPromptChanges = listOf("exercise the live overseer execution bridge")
            ),
            metrics = OverseerExperimentMetrics(primary = listOf("run_completed")),
            acceptanceCriteria = OverseerAcceptanceCriteria(predicates = listOf("run_completed > 0.5")),
            maxCost = 1.0
        )

        val runner = SelfImprovementLoopRunner(
            sourceRepoRoot = sourceRepoRoot,
            workspaceRoot = workspaceRoot,
            openRouterApiKey = apiKey,
            proposalProvider = ProposalProvider { _, _ ->
                OverseerAgentRunner.OverseerProposal(
                    proposedHypotheses = listOf(hypothesis),
                    proposedExperimentPlans = listOf(plan),
                    rationale = "Live overseer execution regression"
                )
            },
            executeScheduledExperiments = true,
            stopOnNoProgress = false
        )

        val reports = runner.runCycle(cycleLimit = 1)

        assertEquals(1, reports.size)
        assertTrue(reports.single().experimentResults > 0)
        assertTrue(reports.single().errors.isEmpty())

        val kernelState = OverseerKernelService(
            substrateFactory = SubstrateDatabaseFactory(workspaceRoot.resolve(".evomolior-data").toFile())
        ).currentState()

        assertTrue(kernelState.hypotheses.containsKey(hypothesis.id))
        assertTrue(kernelState.experimentPlans.containsKey(plan.id))
        assertEquals("ACCEPTED", kernelState.experimentResults[plan.id]?.outcome?.name)
        assertNotNull(kernelState.experimentResults[plan.id])
        assertTrue(workspaceRoot.exists())
    }

    private fun readOpenRouterApiKey(): String {
        val localProperties = Path.of("local.properties")
        if (!localProperties.toFile().exists()) return ""
        return localProperties.toFile().readText()
            .lineSequence()
            .firstOrNull { it.startsWith("OPENROUTER_API_KEY=") }
            ?.substringAfter('=')
            .orEmpty()
            .trim()
    }
}
