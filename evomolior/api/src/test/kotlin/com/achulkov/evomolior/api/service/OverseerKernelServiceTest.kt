package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.contract.OverseerAcceptanceCriteria
import com.achulkov.evomolior.core.contract.OverseerDeadEnd
import com.achulkov.evomolior.core.contract.OverseerExperimentMetrics
import com.achulkov.evomolior.core.contract.OverseerExperimentPlan
import com.achulkov.evomolior.core.contract.OverseerExperimentPlanHistoryActionType
import com.achulkov.evomolior.core.contract.OverseerExperimentPlanEligibility
import com.achulkov.evomolior.core.contract.OverseerExperimentSchedule
import com.achulkov.evomolior.core.contract.OverseerExpectedEffect
import com.achulkov.evomolior.core.contract.OverseerHypothesis
import com.achulkov.evomolior.core.contract.OverseerKernelEligibility
import com.achulkov.evomolior.core.contract.OverseerMutabilityLevel
import com.achulkov.evomolior.core.contract.OverseerOrigin
import com.achulkov.evomolior.core.contract.OverseerOriginType
import com.achulkov.evomolior.core.contract.OverseerTargetScope
import com.achulkov.evomolior.core.contract.OverseerVariationSpec
import com.achulkov.evomolior.substrate.OverseerKernelStateStore
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class OverseerKernelServiceTest {
    @Test
    fun `service persists hypothesis experiment plan and dead end`() {
        val factory = SubstrateDatabaseFactory(createTempDirectory("overseer-service-test-").toFile())
        val service = OverseerKernelService(factory)

        val hypothesis = hypothesis("HYP-10")
        val eligibility = service.preflightHypothesis(hypothesis)
        assertEquals(OverseerKernelEligibility(allowed = true), eligibility)
        val formalized = service.formalizeHypothesis(hypothesis)
        val experiment = OverseerExperimentPlan(
            id = "EXP-10",
            hypothesisId = "HYP-10",
            runCount = 1,
            generationsPerRun = 1,
            variationSpec = OverseerVariationSpec(toolOrPromptChanges = listOf("tighten prompts")),
            metrics = OverseerExperimentMetrics(primary = listOf("pass_rate")),
            acceptanceCriteria = OverseerAcceptanceCriteria(predicates = listOf("pass_rate > 0.5")),
            maxCost = 5.0
        )
        val withExperiment = service.registerExperimentPlan(experiment)
        val withDeadEnd = service.recordDeadEnd(
            OverseerDeadEnd(
                hypothesisId = "HYP-10",
                reason = "Immutable paths are off-limits."
            )
        )

        assertEquals("HYP-10", formalized.hypotheses.getValue("HYP-10").id)
        assertEquals(1, withExperiment.experimentPlans.size)
        assertEquals(1, withDeadEnd.deadEnds.size)
        assertEquals("Immutable paths are off-limits.", service.currentState().deadEnds.getValue("HYP-10").reason)
        assertEquals(
            OverseerExperimentPlanHistoryActionType.REGISTERED,
            service.getExperimentPlanHistory("EXP-10").first().actionType
        )
        assertEquals(1, service.getDeadEndHistory("HYP-10").size)
    }

    @Test
    fun `service schedules eligible experiment plans and persists launch intents`() {
        val factory = SubstrateDatabaseFactory(createTempDirectory("overseer-service-schedule-test-").toFile())
        val service = OverseerKernelService(factory)
        val hypothesis = hypothesis("HYP-12")
        service.formalizeHypothesis(hypothesis)
        val plan = OverseerExperimentPlan(
            id = "EXP-12",
            hypothesisId = "HYP-12",
            runCount = 2,
            generationsPerRun = 3,
            variationSpec = OverseerVariationSpec(configChanges = listOf("raise selector budget")),
            metrics = OverseerExperimentMetrics(primary = listOf("pass_rate")),
            acceptanceCriteria = OverseerAcceptanceCriteria(predicates = listOf("pass_rate > 0.5")),
            maxCost = 3.0
        )
        service.registerExperimentPlan(plan)

        val scheduled = service.scheduleEligibleExperimentPlans()

        assertEquals(1, scheduled.experimentSchedules.size)
        assertEquals(
            OverseerExperimentSchedule(
                experimentPlanId = "EXP-12",
                hypothesisId = "HYP-12",
                runCount = 2,
                generationsPerRun = 3,
                scheduledRunIds = listOf("EXP-12-run-01", "EXP-12-run-02")
            ),
            scheduled.experimentSchedules.getValue("EXP-12")
        )
    }

    @Test
    fun `service scheduling is idempotent for already scheduled plans`() {
        val factory = SubstrateDatabaseFactory(createTempDirectory("overseer-service-schedule-idempotent-test-").toFile())
        val service = OverseerKernelService(factory)
        val hypothesis = hypothesis("HYP-13")
        service.formalizeHypothesis(hypothesis)
        val plan = OverseerExperimentPlan(
            id = "EXP-13",
            hypothesisId = "HYP-13",
            runCount = 2,
            generationsPerRun = 3,
            variationSpec = OverseerVariationSpec(configChanges = listOf("raise selector budget")),
            metrics = OverseerExperimentMetrics(primary = listOf("pass_rate")),
            acceptanceCriteria = OverseerAcceptanceCriteria(predicates = listOf("pass_rate > 0.5")),
            maxCost = 3.0
        )
        service.registerExperimentPlan(plan)

        val first = service.scheduleEligibleExperimentPlans()
        val second = service.scheduleEligibleExperimentPlans()

        assertEquals(first.experimentSchedules, second.experimentSchedules)
        assertEquals(1, second.experimentSchedules.size)
    }

    @Test
    fun `service preflights experiment plans against the current kernel snapshot`() {
        val factory = SubstrateDatabaseFactory(createTempDirectory("overseer-service-plan-test-").toFile())
        val service = OverseerKernelService(factory)
        val hypothesis = hypothesis("HYP-11")
        service.formalizeHypothesis(hypothesis)

        val plan = OverseerExperimentPlan(
            id = "EXP-11",
            hypothesisId = "HYP-11",
            runCount = 1,
            generationsPerRun = 1,
            variationSpec = OverseerVariationSpec(configChanges = listOf("turn on deterministic logging")),
            metrics = OverseerExperimentMetrics(primary = listOf("pass_rate")),
            acceptanceCriteria = OverseerAcceptanceCriteria(predicates = listOf("pass_rate > 0.7")),
            maxCost = 1.0
        )

        val eligibility = service.preflightExperimentPlan(plan)

        assertEquals(OverseerExperimentPlanEligibility(allowed = true), eligibility)
    }

    private fun hypothesis(id: String): OverseerHypothesis = OverseerHypothesis(
        id = id,
        title = "Tune prompt for $id",
        origin = OverseerOrigin(
            type = OverseerOriginType.INTERNAL_LOGS,
            sources = listOf("documentation/logs/run_logs.txt")
        ),
        targetScope = OverseerTargetScope(
            paths = listOf("evomolior/core/src/main/resources/overseer/policy.json"),
            mutabilityLevel = OverseerMutabilityLevel.SEMI_MUTABLE
        ),
        rationale = "Exercise the deterministic service facade.",
        expectedEffect = OverseerExpectedEffect(
            metricsUp = listOf("pass_rate"),
            metricsDown = listOf("cost_per_success"),
            invariantsHold = listOf("immutable_core_unchanged")
        )
    )
}
