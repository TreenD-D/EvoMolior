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
                        sources = listOf("documentation/EvoMolior_Self_Improvement_Operator_Guide.md")
                    ),
                    targetScope = OverseerTargetScope(
                        paths = listOf("documentation/EvoMolior_Self_Improvement_Operator_Guide.md"),
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
        assertTrue(workspaceRoot.resolve(".evomolior-data").exists())
        assertTrue(workspaceRoot.resolve(".evomolior-self-improve/repo").exists())
        assertEquals(1, reports.size)
        assertEquals(1, reports.single().proposalsCount)
        assertEquals(1, reports.single().formalizedCount)
        assertEquals(0, reports.single().experimentResults)
        assertTrue(reports.single().errors.isEmpty())

        val persistedState = OverseerKernelService(
            substrateFactory = SubstrateDatabaseFactory(workspaceRoot.resolve(".evomolior-data").toFile())
        ).currentState()
        assertTrue(persistedState.hypotheses.containsKey("HYP-self-improve-001"))
    }

    @Test
    fun `runCycle seeds successive bootstrap hypotheses from the architecture plan across empty cycles`() = runTest {
        val sourceRepoRoot = createTempDirectory("self-improve-source-").toFile().toPath()
        Files.createDirectories(sourceRepoRoot.resolve("documentation"))
        sourceRepoRoot.resolve("documentation/EvoMolior_Architecture_Improvements_Plan.md").writeText(
            """
            ## 1. Feedforward Layers of Evo Runs (MLP-equivalent)
            - Seed layer-wise runs for discovery, synthesis, and final assembly.

            ## 2. Recurrent Self-Improvement Loop (RNN/LSTM-equivalent)
            - Feed prior results into later cycles instead of resetting to zero.

            ## 3. Attention-Style Routing Between Runs (Transformer-equivalent)
            - Select bootstrap evidence by relevance and trust.

            ## 4. MoE / MoA with LLM Router (Mixture-of-Experts-equivalent)
            - Route tasks to specialist bootstrap expert runs.

            ## 5. Graph of Evo Runs (GNN-equivalent)
            - Connect runs with explicit trust-weighted edges.

            ## 6. Adversarial Generative / Discriminative Runs (GAN-equivalent)
            - Pair generator proposals with critic-style failure checks.

            ## 7. Human Collective Intelligence Patterns as Constraints
            - Keep bootstrap roles balanced and communication distributed.

            ## 8. Recommended Experimental Roadmap
            - Follow the staged roadmap and verify each phase.
            """.trimIndent()
        )
        sourceRepoRoot.resolve("documentation/EvoMolior_Current_Session_Handoff.md").writeText(
            "Handoff marker: bootstrap-context-test"
        )
        sourceRepoRoot.resolve("documentation/EvoMolior_Self_Improvement_Operator_Guide.md").writeText(
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

        val reports = runner.runCycle(cycleLimit = 4)

        assertTrue(workspaceRoot.exists())
        assertEquals(4, reports.size)
        assertTrue(reports.all { it.proposalsCount == 1 })
        assertTrue(reports.all { it.formalizedCount == 1 })
        assertTrue(reports.all { it.errors.isEmpty() })

        val persistedState = OverseerKernelService(
            substrateFactory = SubstrateDatabaseFactory(workspaceRoot.resolve(".evomolior-data").toFile())
        ).currentState()
        val feedforwardHypothesis = persistedState.hypotheses.getValue("HYP-self-improve-bootstrap-001")
        val recurrentHypothesis = persistedState.hypotheses.getValue("HYP-self-improve-bootstrap-002")
        val attentionHypothesis = persistedState.hypotheses.getValue("HYP-self-improve-bootstrap-003")
        val adversarialHypothesis = persistedState.hypotheses.getValue("HYP-self-improve-bootstrap-004")

        assertTrue(feedforwardHypothesis.rationale.contains("Feedforward Layers of Evo Runs"))
        assertTrue(recurrentHypothesis.rationale.contains("Recurrent Self-Improvement Loop"))
        assertTrue(attentionHypothesis.rationale.contains("Attention-Style Routing Between Runs"))
        assertTrue(adversarialHypothesis.rationale.contains("Adversarial Generative / Discriminative Runs"))
        assertTrue(feedforwardHypothesis.rationale.contains("bootstrap-context-test"))
        assertTrue(feedforwardHypothesis.origin.sources.contains("documentation/EvoMolior_Architecture_Improvements_Plan.md"))
    }

    @Test
    fun `runCycle seeds bootstrap experiment plans when execution is enabled on an empty kernel`() = runTest {
        val sourceRepoRoot = createTempDirectory("self-improve-source-").toFile().toPath()
        Files.createDirectories(sourceRepoRoot.resolve("documentation"))
        sourceRepoRoot.resolve("documentation/EvoMolior_Architecture_Improvements_Plan.md").writeText(
            """
            ## 1. Feedforward Layers of Evo Runs (MLP-equivalent)
            - Seed layer-wise runs for discovery, synthesis, and final assembly.

            ## 2. Recurrent Self-Improvement Loop (RNN/LSTM-equivalent)
            - Feed prior results into later cycles instead of resetting to zero.

            ## 3. Attention-Style Routing Between Runs (Transformer-equivalent)
            - Select bootstrap evidence by relevance and trust.

            ## 4. MoE / MoA with LLM Router (Mixture-of-Experts-equivalent)
            - Route tasks to specialist bootstrap expert runs.

            ## 5. Graph of Evo Runs (GNN-equivalent)
            - Connect runs with explicit trust-weighted edges.

            ## 6. Adversarial Generative / Discriminative Runs (GAN-equivalent)
            - Pair generator proposals with critic-style failure checks.

            ## 7. Human Collective Intelligence Patterns as Constraints
            - Keep bootstrap roles balanced and communication distributed.

            ## 8. Recommended Experimental Roadmap
            - Follow the staged roadmap and verify each phase.
            """.trimIndent()
        )
        sourceRepoRoot.resolve("documentation/EvoMolior_Current_Session_Handoff.md").writeText(
            "Handoff marker: bootstrap-execution-test"
        )
        sourceRepoRoot.resolve("documentation/EvoMolior_Self_Improvement_Operator_Guide.md").writeText(
            "Guide marker: bootstrap-execution-test"
        )
        val workspaceRoot = createTempDirectory("self-improve-bootstrap-exec-")
            .toFile()
            .toPath()
            .resolveSibling("self-improve-bootstrap-exec-${System.nanoTime()}")

        val runner = SelfImprovementLoopRunner(
            sourceRepoRoot = sourceRepoRoot,
            workspaceRoot = workspaceRoot,
            openRouterApiKey = "sk-test",
            proposalProvider = ProposalProvider { _, _ ->
                OverseerAgentRunner.OverseerProposal()
            },
            experimentCycleRunner = { 3 },
            executeScheduledExperiments = true
        )

        val reports = runner.runCycle(cycleLimit = 1)

        assertEquals(1, reports.size)
        assertEquals(2, reports.single().proposalsCount)
        assertEquals(1, reports.single().formalizedCount)
        assertEquals(3, reports.single().experimentResults)
        assertTrue(reports.single().errors.isEmpty())

        val persistedState = OverseerKernelService(
            substrateFactory = SubstrateDatabaseFactory(workspaceRoot.resolve(".evomolior-data").toFile())
        ).currentState()
        assertTrue(persistedState.experimentPlans.containsKey("PLAN-self-improve-bootstrap-001"))
    }

    @Test
    fun `runCycle reports a bootstrap error when the architecture plan heading is missing`() = runTest {
        val sourceRepoRoot = createTempDirectory("self-improve-source-").toFile().toPath()
        Files.createDirectories(sourceRepoRoot.resolve("documentation"))
        sourceRepoRoot.resolve("documentation/EvoMolior_Architecture_Improvements_Plan.md").writeText(
            """
            ## 1. Feedforward Layers of Evo Runs (MLP-equivalent)
            - Seed layer-wise runs for discovery, synthesis, and final assembly.
            """.trimIndent()
        )
        sourceRepoRoot.resolve("documentation/EvoMolior_Current_Session_Handoff.md").writeText(
            "Handoff marker: missing-heading-test"
        )
        sourceRepoRoot.resolve("documentation/EvoMolior_Self_Improvement_Operator_Guide.md").writeText(
            "Guide marker: missing-heading-test"
        )
        val workspaceRoot = createTempDirectory("self-improve-bootstrap-missing-")
            .toFile()
            .toPath()
            .resolveSibling("self-improve-bootstrap-missing-${System.nanoTime()}")

        val runner = SelfImprovementLoopRunner(
            sourceRepoRoot = sourceRepoRoot,
            workspaceRoot = workspaceRoot,
            openRouterApiKey = "sk-test",
            proposalProvider = ProposalProvider { _, _ ->
                OverseerAgentRunner.OverseerProposal()
            }
        )

        val reports = runner.runCycle(cycleLimit = 1)

        assertEquals(1, reports.size)
        assertEquals(0, reports.single().proposalsCount)
        assertEquals(0, reports.single().formalizedCount)
        assertTrue(reports.single().errors.any { it.contains("Missing architecture plan section") })
    }

    @Test
    fun `runCycle prunes stale terminal hypotheses through the recurrent forget gate`() = runTest {
        val sourceRepoRoot = createTempDirectory("self-improve-source-").toFile().toPath()
        Files.createDirectories(sourceRepoRoot.resolve("documentation"))
        sourceRepoRoot.resolve("documentation/EvoMolior_Architecture_Improvements_Plan.md").writeText(
            """
            ## 1. Feedforward Layers of Evo Runs (MLP-equivalent)
            - Seed layer-wise runs for discovery, synthesis, and final assembly.

            ## 2. Recurrent Self-Improvement Loop (RNN/LSTM-equivalent)
            - Feed prior results into later cycles instead of resetting to zero.

            ## 3. Attention-Style Routing Between Runs (Transformer-equivalent)
            - Select bootstrap evidence by relevance and trust.

            ## 4. MoE / MoA with LLM Router (Mixture-of-Experts-equivalent)
            - Route tasks to specialist bootstrap expert runs.

            ## 5. Graph of Evo Runs (GNN-equivalent)
            - Connect runs with explicit trust-weighted edges.

            ## 6. Adversarial Generative / Discriminative Runs (GAN-equivalent)
            - Pair generator proposals with critic-style failure checks.

            ## 7. Human Collective Intelligence Patterns as Constraints
            - Keep bootstrap roles balanced and communication distributed.

            ## 8. Recommended Experimental Roadmap
            - Follow the staged roadmap and verify each phase.
            """.trimIndent()
        )
        sourceRepoRoot.resolve("documentation/EvoMolior_Current_Session_Handoff.md").writeText(
            "Handoff marker: forget-gate-test"
        )
        sourceRepoRoot.resolve("documentation/EvoMolior_Self_Improvement_Operator_Guide.md").writeText(
            "Guide marker: forget-gate-test"
        )

        val workspaceRoot = createTempDirectory("self-improve-forget-gate-")
            .toFile()
            .toPath()
            .resolveSibling("self-improve-forget-gate-${System.nanoTime()}")

        val staleService = OverseerKernelService(
            substrateFactory = SubstrateDatabaseFactory(workspaceRoot.resolve(".evomolior-data").toFile())
        )
        staleService.formalizeHypothesis(
            OverseerHypothesis(
                id = "HYP-forget-gate-stale",
                title = "Stale terminal hypothesis",
                origin = OverseerOrigin(
                    type = OverseerOriginType.INTERNAL_LOGS,
                    sources = listOf("documentation/EvoMolior_Current_Session_Handoff.md")
                ),
                targetScope = OverseerTargetScope(
                    paths = listOf("documentation/EvoMolior_Current_Session_Handoff.md"),
                    mutabilityLevel = OverseerMutabilityLevel.FULLY_MUTABLE
                ),
                rationale = "Seed a stale terminal entry for the recurrent forget gate.",
                expectedEffect = OverseerExpectedEffect(metricsDown = listOf("stale_state"))
            )
        )
        staleService.recordDeadEnd(
            com.achulkov.evomolior.core.contract.OverseerDeadEnd(
                hypothesisId = "HYP-forget-gate-stale",
                reason = "Terminal entry should be compacted after the loop advances.",
                recordedAtMs = 0L
            )
        )

        val runner = SelfImprovementLoopRunner(
            sourceRepoRoot = sourceRepoRoot,
            workspaceRoot = workspaceRoot,
            openRouterApiKey = "sk-test",
            proposalProvider = ProposalProvider { _, _ -> OverseerAgentRunner.OverseerProposal() }
        )

        val reports = runner.runCycle(cycleLimit = 1)

        assertEquals(1, reports.size)
        assertFalse(
            OverseerKernelService(
                substrateFactory = SubstrateDatabaseFactory(workspaceRoot.resolve(".evomolior-data").toFile())
            ).currentState().hypotheses.containsKey("HYP-forget-gate-stale")
        )
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
                sources = listOf("documentation/EvoMolior_Current_Session_Handoff.md")
            ),
            targetScope = OverseerTargetScope(
                paths = listOf("evomolior/cli/src/main/kotlin/com/achulkov/evomolior/cli/SelfImprovementCli.kt"),
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
            substrateFactory = SubstrateDatabaseFactory(workspaceRoot.resolve(".evomolior-data").toFile())
        ).currentState()
        assertTrue(persistedState.hypotheses.containsKey(hypothesis.id))
        assertTrue(persistedState.experimentPlans.containsKey(plan.id))
    }
}
