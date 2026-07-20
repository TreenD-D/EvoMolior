package com.achulkov.evomolior.core.overseer

import com.achulkov.evomolior.core.contract.OverseerAcceptanceCriteria
import com.achulkov.evomolior.core.contract.OverseerDeadEnd
import com.achulkov.evomolior.core.contract.OverseerExperimentMetrics
import com.achulkov.evomolior.core.contract.OverseerExperimentOutcome
import com.achulkov.evomolior.core.contract.OverseerExperimentPlan
import com.achulkov.evomolior.core.contract.OverseerExperimentPlanHistoryActionType
import com.achulkov.evomolior.core.contract.OverseerExperimentPlanEligibility
import com.achulkov.evomolior.core.contract.OverseerExperimentResult
import com.achulkov.evomolior.core.contract.OverseerExperimentSchedule
import com.achulkov.evomolior.core.contract.OverseerExpectedEffect
import com.achulkov.evomolior.core.contract.OverseerHypothesis
import com.achulkov.evomolior.core.contract.OverseerHypothesisStatus
import com.achulkov.evomolior.core.contract.OverseerKernelEligibility
import com.achulkov.evomolior.core.contract.OverseerKernelState
import com.achulkov.evomolior.core.contract.OverseerMutabilityLevel
import com.achulkov.evomolior.core.contract.OverseerOrigin
import com.achulkov.evomolior.core.contract.OverseerOriginType
import com.achulkov.evomolior.core.contract.OverseerTargetScope
import com.achulkov.evomolior.core.contract.OverseerVariationSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OverseerKernelTest {
    private val kernel = OverseerKernel()

    @Test
    fun `formalize hypothesis accepts fully mutable documentation and test targets`() {
        val hypothesis = hypothesis(
            id = "HYP-001",
            paths = listOf(
                "documentation/EvoMolior_Current_Session_Handoff.md",
                "evomolior/core/src/test/kotlin/com/achulkov/evomolior/core/overseer/OverseerKernelTest.kt"
            ),
            mutabilityLevel = OverseerMutabilityLevel.FULLY_MUTABLE
        )

        val updated = kernel.formalizeHypothesis(OverseerKernelState(), hypothesis)

        assertEquals(1, updated.hypotheses.size)
        assertEquals(OverseerHypothesisStatus.FORMALIZED, updated.hypotheses.getValue("HYP-001").status)
        assertEquals(0, updated.deadEnds.size)
    }

    @Test
    fun `formalize hypothesis rejects immutable constitution targets`() {
        val hypothesis = hypothesis(
            id = "HYP-002",
            paths = listOf("constitution/p1.md"),
            mutabilityLevel = OverseerMutabilityLevel.FULLY_MUTABLE
        )

        assertFailsWith<IllegalArgumentException> {
            kernel.formalizeHypothesis(OverseerKernelState(), hypothesis)
        }
    }

    @Test
    fun `semi mutable hypothesis must target structured configuration files`() {
        val hypothesis = hypothesis(
            id = "HYP-003",
            paths = listOf("evomolior/agents/src/main/kotlin/com/achulkov/evomolior/agents/factory/EvoMoliorAgentFactory.kt"),
            mutabilityLevel = OverseerMutabilityLevel.SEMI_MUTABLE
        )

        assertFailsWith<IllegalArgumentException> {
            kernel.formalizeHypothesis(OverseerKernelState(), hypothesis)
        }
    }

    @Test
    fun `semi mutable hypothesis accepts config file targets`() {
        val hypothesis = hypothesis(
            id = "HYP-003B",
            paths = listOf("evomolior/core/src/main/resources/overseer/hypothesis-policy.json"),
            mutabilityLevel = OverseerMutabilityLevel.SEMI_MUTABLE
        )

        val updated = kernel.formalizeHypothesis(OverseerKernelState(), hypothesis)

        assertEquals(OverseerHypothesisStatus.FORMALIZED, updated.hypotheses.getValue("HYP-003B").status)
    }

    @Test
    fun `validate hypothesis eligibility explains safe harness targets before formalization`() {
        val hypothesis = hypothesis(
            id = "HYP-003C",
            paths = listOf(
                "documentation/EvoMolior_Recursive_Self_Improvement_Plan_v4.md",
                "evomolior/core/src/test/kotlin/com/achulkov/evomolior/core/overseer/OverseerKernelTest.kt"
            ),
            mutabilityLevel = OverseerMutabilityLevel.FULLY_MUTABLE
        )

        val eligibility = kernel.validateHypothesisEligibility(hypothesis)

        assertEquals(OverseerKernelEligibility(allowed = true), eligibility)
    }

    @Test
    fun `validate hypothesis eligibility rejects mixed immutable and semi mutable targets with reasons`() {
        val hypothesis = hypothesis(
            id = "HYP-003D",
            paths = listOf(
                "constitution/p1.md",
                "evomolior/agents/src/main/kotlin/com/achulkov/evomolior/agents/factory/EvoMoliorAgentFactory.kt"
            ),
            mutabilityLevel = OverseerMutabilityLevel.SEMI_MUTABLE
        )

        val eligibility = kernel.validateHypothesisEligibility(hypothesis)

        assertEquals(false, eligibility.allowed)
        assertEquals(
            listOf(
                "Hypothesis targets immutable path 'constitution/p1.md'.",
                "Semi-mutable hypotheses must target structured configuration files."
            ),
            eligibility.reasons
        )
    }

    @Test
    fun `record dead end marks hypothesis terminal and stores evidence`() {
        val hypothesis = hypothesis(
            id = "HYP-004",
            paths = listOf("documentation/EvoMolior_Recursive_Self_Improvement_Plan_v4.md"),
            mutabilityLevel = OverseerMutabilityLevel.FULLY_MUTABLE
        )
        val formalized = kernel.formalizeHypothesis(OverseerKernelState(), hypothesis)

        val withDeadEnd = kernel.recordDeadEnd(
            formalized,
            OverseerDeadEnd(
                hypothesisId = "HYP-004",
                reason = "Repeated provider failures made the configuration invalid.",
                counterexamplePaths = listOf("documentation/logs/run_logs.txt")
            )
        )

        assertEquals(OverseerHypothesisStatus.DEAD_END, withDeadEnd.hypotheses.getValue("HYP-004").status)
        assertEquals("Repeated provider failures made the configuration invalid.", withDeadEnd.deadEnds.getValue("HYP-004").reason)
    }

    @Test
    fun `register experiment plan requires a known hypothesis`() {
        val plan = OverseerExperimentPlan(
            id = "EXP-001",
            hypothesisId = "HYP-unknown",
            runCount = 2,
            generationsPerRun = 3,
            variationSpec = OverseerVariationSpec(configChanges = listOf("toggle fast mode")),
            metrics = OverseerExperimentMetrics(primary = listOf("pass_rate")),
            acceptanceCriteria = OverseerAcceptanceCriteria(predicates = listOf("pass_rate > 0.5")),
            maxCost = 100.0
        )

        assertFailsWith<IllegalArgumentException> {
            kernel.registerExperimentPlan(OverseerKernelState(), plan)
        }
    }

    @Test
    fun `register experiment plan marks the hypothesis under experiment`() {
        val hypothesis = hypothesis(
            id = "HYP-005",
            paths = listOf("documentation/EvoMolior_Recursive_Self_Improvement_Plan_v4.md"),
            mutabilityLevel = OverseerMutabilityLevel.FULLY_MUTABLE
        )
        val formalized = kernel.formalizeHypothesis(OverseerKernelState(), hypothesis)
        val plan = OverseerExperimentPlan(
            id = "EXP-005",
            hypothesisId = "HYP-005",
            runCount = 1,
            generationsPerRun = 1,
            variationSpec = OverseerVariationSpec(toolOrPromptChanges = listOf("refine builder prompts")),
            metrics = OverseerExperimentMetrics(primary = listOf("pass_rate")),
            acceptanceCriteria = OverseerAcceptanceCriteria(predicates = listOf("pass_rate > 0.5")),
            maxCost = 10.0
        )

        val updated = kernel.registerExperimentPlan(formalized, plan)

        assertEquals(OverseerHypothesisStatus.UNDER_EXPERIMENT, updated.hypotheses.getValue("HYP-005").status)
        assertEquals("EXP-005", updated.hypotheses.getValue("HYP-005").experimentPlanId)
        assertEquals(
            OverseerExperimentPlanHistoryActionType.REGISTERED,
            updated.experimentPlanHistory.getValue("EXP-005").first().actionType
        )
    }

    @Test
    fun `record accepted experiment result promotes hypothesis acceptance`() {
        val hypothesis = hypothesis(
            id = "HYP-006",
            paths = listOf("documentation/EvoMolior_Recursive_Self_Improvement_Plan_v4.md"),
            mutabilityLevel = OverseerMutabilityLevel.FULLY_MUTABLE
        )
        val formalized = kernel.formalizeHypothesis(OverseerKernelState(), hypothesis)
        val withPlan = kernel.registerExperimentPlan(
            formalized,
            OverseerExperimentPlan(
                id = "EXP-006",
                hypothesisId = "HYP-006",
                runCount = 1,
                generationsPerRun = 1,
                variationSpec = OverseerVariationSpec(toolOrPromptChanges = listOf("refine planner")),
                metrics = OverseerExperimentMetrics(primary = listOf("pass_rate")),
                acceptanceCriteria = OverseerAcceptanceCriteria(predicates = listOf("pass_rate > 0.7")),
                maxCost = 1.0
            )
        )

        val updated = kernel.recordExperimentResult(
            withPlan,
            OverseerExperimentResult(
                experimentPlanId = "EXP-006",
                hypothesisId = "HYP-006",
                outcome = OverseerExperimentOutcome.ACCEPTED,
                metricValues = mapOf("pass_rate" to 0.82)
            )
        )

        assertEquals(OverseerHypothesisStatus.ACCEPTED, updated.hypotheses.getValue("HYP-006").status)
        assertEquals(1, updated.experimentResults.size)
    }

    @Test
    fun `schedule experiment plans creates deterministic launch intents`() {
        val hypothesis = hypothesis(
            id = "HYP-007S",
            paths = listOf("documentation/EvoMolior_Recursive_Self_Improvement_Plan_v4.md"),
            mutabilityLevel = OverseerMutabilityLevel.FULLY_MUTABLE
        )
        val formalized = kernel.formalizeHypothesis(OverseerKernelState(), hypothesis)
        val withPlan = kernel.registerExperimentPlan(
            formalized,
            OverseerExperimentPlan(
                id = "EXP-007S",
                hypothesisId = "HYP-007S",
                runCount = 2,
                generationsPerRun = 3,
                variationSpec = OverseerVariationSpec(toolOrPromptChanges = listOf("refine prompts")),
                metrics = OverseerExperimentMetrics(primary = listOf("pass_rate")),
                acceptanceCriteria = OverseerAcceptanceCriteria(predicates = listOf("pass_rate > 0.7")),
                maxCost = 1.0
            )
        )

        val scheduled = kernel.scheduleExperimentPlans(withPlan)

        assertEquals(1, scheduled.experimentSchedules.size)
        assertEquals(
            OverseerExperimentSchedule(
                experimentPlanId = "EXP-007S",
                hypothesisId = "HYP-007S",
                runCount = 2,
                generationsPerRun = 3,
                scheduledRunIds = listOf("EXP-007S-run-01", "EXP-007S-run-02")
            ),
            scheduled.experimentSchedules.getValue("EXP-007S")
        )
        assertEquals(
            OverseerExperimentPlanHistoryActionType.SCHEDULED,
            scheduled.experimentPlanHistory.getValue("EXP-007S").last().actionType
        )
        assertEquals(scheduled, kernel.scheduleExperimentPlans(scheduled))
    }

    @Test
    fun `record rejected experiment result with counterexamples becomes a dead end`() {
        val hypothesis = hypothesis(
            id = "HYP-007",
            paths = listOf("documentation/EvoMolior_Recursive_Self_Improvement_Plan_v4.md"),
            mutabilityLevel = OverseerMutabilityLevel.FULLY_MUTABLE
        )
        val formalized = kernel.formalizeHypothesis(OverseerKernelState(), hypothesis)
        val withPlan = kernel.registerExperimentPlan(
            formalized,
            OverseerExperimentPlan(
                id = "EXP-007",
                hypothesisId = "HYP-007",
                runCount = 1,
                generationsPerRun = 1,
                variationSpec = OverseerVariationSpec(toolOrPromptChanges = listOf("refine prompts")),
                metrics = OverseerExperimentMetrics(primary = listOf("pass_rate")),
                acceptanceCriteria = OverseerAcceptanceCriteria(predicates = listOf("pass_rate > 0.7")),
                maxCost = 1.0
            )
        )

        val updated = kernel.recordExperimentResult(
            withPlan,
            OverseerExperimentResult(
                experimentPlanId = "EXP-007",
                hypothesisId = "HYP-007",
                outcome = OverseerExperimentOutcome.REJECTED,
                metricValues = mapOf("pass_rate" to 0.12),
                note = "Counterexample found",
                counterexamplePaths = listOf("evomolior/core/src/test/kotlin/com/achulkov/evomolior/core/overseer/OverseerKernelTest.kt")
            )
        )

        assertEquals(OverseerHypothesisStatus.DEAD_END, updated.hypotheses.getValue("HYP-007").status)
        assertEquals(1, updated.deadEnds.size)
        assertEquals("Counterexample found", updated.deadEnds.getValue("HYP-007").reason)
        assertEquals(
            OverseerExperimentPlanHistoryActionType.RESULT_RECORDED,
            updated.experimentPlanHistory.getValue("EXP-007").last().actionType
        )
    }

    @Test
    fun `validate experiment plan eligibility preserves stable reason order for mixed failures`() {
        val plan = OverseerExperimentPlan(
            id = "",
            hypothesisId = "",
            runCount = 0,
            generationsPerRun = 0,
            variationSpec = OverseerVariationSpec(),
            metrics = OverseerExperimentMetrics(),
            acceptanceCriteria = OverseerAcceptanceCriteria(),
            maxCost = -1.0
        )

        val eligibility = kernel.validateExperimentPlanEligibility(OverseerKernelState(), plan)

        assertEquals(
            OverseerExperimentPlanEligibility(
                allowed = false,
                reasons = listOf(
                    "Experiment plan id must not be blank.",
                    "Experiment plan hypothesis id must not be blank.",
                    "Experiment plans must run at least once.",
                    "Experiment plans must span at least one generation.",
                    "Experiment plans cannot have negative cost.",
                    "Experiment plans must declare at least one primary metric.",
                    "Experiment plans must declare at least one acceptance predicate.",
                    "Experiment plans must declare at least one variation.",
                    "Unknown hypothesis ''."
                )
            ),
            eligibility
        )
    }

    private fun hypothesis(
        id: String,
        paths: List<String>,
        mutabilityLevel: OverseerMutabilityLevel
    ): OverseerHypothesis = OverseerHypothesis(
        id = id,
        title = "Title for $id",
        origin = OverseerOrigin(
            type = OverseerOriginType.INTERNAL_METRICS,
            sources = listOf("documentation/EvoMolior_Current_Status.md")
        ),
        targetScope = OverseerTargetScope(
            paths = paths,
            mutabilityLevel = mutabilityLevel
        ),
        rationale = "A deterministic hypothesis for $id.",
        expectedEffect = OverseerExpectedEffect(
            metricsUp = listOf("pass_rate"),
            metricsDown = listOf("cost_per_success"),
            invariantsHold = listOf("constitutional_core_unchanged")
        )
    )
}
