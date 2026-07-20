package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.contract.OverseerAcceptanceCriteria
import com.achulkov.evomolior.core.contract.OverseerDeadEnd
import com.achulkov.evomolior.core.contract.OverseerExpectedEffect
import com.achulkov.evomolior.core.contract.OverseerExperimentMetrics
import com.achulkov.evomolior.core.contract.OverseerHypothesis
import com.achulkov.evomolior.core.contract.OverseerMutabilityLevel
import com.achulkov.evomolior.core.contract.OverseerOrigin
import com.achulkov.evomolior.core.contract.OverseerOriginType
import com.achulkov.evomolior.core.contract.OverseerTargetScope
import com.achulkov.evomolior.core.contract.OverseerVariationSpec
import com.achulkov.evomolior.core.contract.OverseerExperimentPlan
import com.achulkov.evomolior.core.contract.OverseerKernelAuditActionType
import com.achulkov.evomolior.core.contract.OverseerKernelRetentionPolicy
import com.achulkov.evomolior.core.contract.OverseerExperimentPlanHistoryActionType
import com.achulkov.evomolior.core.contract.OverseerHypothesisStatus
import com.achulkov.evomolior.core.contract.OverseerExperimentOutcome
import com.achulkov.evomolior.core.contract.OverseerExperimentResult
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverseerKernelServiceForgetGateTest {

    @Test
    fun `compactTerminalState prunes the stalest terminal hypotheses first when over capacity`() {
        val dataRoot = createTempDirectory("overseer-forget-gate-").toFile()
        val service = OverseerKernelService(SubstrateDatabaseFactory(dataRoot))

        val olderHypothesis = OverseerHypothesis(
            id = "HYP-forget-001",
            title = "Older terminal hypothesis",
            origin = OverseerOrigin(OverseerOriginType.INTERNAL_LOGS, listOf("documentation/EvoMolior_Current_Session_Handoff.md")),
            targetScope = OverseerTargetScope(
                paths = listOf("documentation/EvoMolior_Current_Session_Handoff.md"),
                mutabilityLevel = OverseerMutabilityLevel.FULLY_MUTABLE
            ),
            rationale = "Seed a terminal entry that should be forgotten by the recurrent loop.",
            expectedEffect = OverseerExpectedEffect(metricsDown = listOf("stale_state"))
        )
        val newerHypothesis = OverseerHypothesis(
            id = "HYP-forget-002",
            title = "Newer terminal hypothesis",
            origin = OverseerOrigin(OverseerOriginType.INTERNAL_LOGS, listOf("documentation/EvoMolior_Current_Session_Handoff.md")),
            targetScope = OverseerTargetScope(
                paths = listOf("documentation/EvoMolior_Current_Session_Handoff.md"),
                mutabilityLevel = OverseerMutabilityLevel.FULLY_MUTABLE
            ),
            rationale = "Keep the newer terminal entry as the retained one.",
            expectedEffect = OverseerExpectedEffect(metricsDown = listOf("stale_state"))
        )

        service.formalizeHypothesis(olderHypothesis)
        service.formalizeHypothesis(newerHypothesis)
        service.recordDeadEnd(
            OverseerDeadEnd(
                hypothesisId = olderHypothesis.id,
                reason = "Older terminal hypothesis should be pruned first.",
                recordedAtMs = 0L
            )
        )
        service.recordDeadEnd(
            OverseerDeadEnd(
                hypothesisId = newerHypothesis.id,
                reason = "Newer terminal hypothesis should survive overflow pruning."
            )
        )

        val compacted = service.compactTerminalState(
            OverseerKernelRetentionPolicy(
                terminalRetentionMs = Long.MAX_VALUE,
                maxRetainedTerminalHypotheses = 1
            )
        )

        assertTrue(compacted != null)
        assertFalse(service.currentState().hypotheses.containsKey(olderHypothesis.id))
        assertTrue(service.currentState().hypotheses.containsKey(newerHypothesis.id))
        assertFalse(service.currentState().deadEnds.containsKey(olderHypothesis.id))
        assertTrue(service.currentState().deadEnds.containsKey(newerHypothesis.id))
        assertEquals(1, service.getDeadEndHistory(olderHypothesis.id).size)
        assertTrue(
            service.getAuditEntries().any { it.actionType == OverseerKernelAuditActionType.COMPACT_KERNEL_STATE }
        )
    }

    @Test
    fun `compactTerminalState prunes terminal hypotheses with their plans schedules and results`() {
        val dataRoot = createTempDirectory("overseer-forget-gate-plans-").toFile()
        val service = OverseerKernelService(SubstrateDatabaseFactory(dataRoot))

        val hypothesis = OverseerHypothesis(
            id = "HYP-forget-plan-001",
            title = "Terminal hypothesis with plan",
            origin = OverseerOrigin(
                OverseerOriginType.INTERNAL_LOGS,
                listOf("documentation/EvoMolior_Current_Session_Handoff.md")
            ),
            targetScope = OverseerTargetScope(
                paths = listOf("documentation/EvoMolior_Current_Session_Handoff.md"),
                mutabilityLevel = OverseerMutabilityLevel.FULLY_MUTABLE
            ),
            rationale = "Seed a terminal entry with an attached plan and outcome.",
            expectedEffect = OverseerExpectedEffect(metricsDown = listOf("stale_state"))
        )
        val plan = OverseerExperimentPlan(
            id = "PLAN-forget-plan-001",
            hypothesisId = hypothesis.id,
            runCount = 1,
            generationsPerRun = 1,
            variationSpec = OverseerVariationSpec(configChanges = listOf("touch stale terminal state")),
            metrics = OverseerExperimentMetrics(primary = listOf("stale_state")),
            acceptanceCriteria = OverseerAcceptanceCriteria(predicates = listOf("stale_state < 0.5")),
            maxCost = 1.0
        )

        service.formalizeHypothesis(hypothesis)
        service.registerExperimentPlan(plan)
        service.scheduleEligibleExperimentPlans()
        service.launchExperimentRun(plan.id, "${plan.id}-run-01")
        service.recordExperimentResult(
            OverseerExperimentResult(
                experimentPlanId = plan.id,
                hypothesisId = hypothesis.id,
                outcome = OverseerExperimentOutcome.REJECTED,
                counterexamplePaths = listOf("documentation/EvoMolior_Current_Session_Handoff.md"),
                recordedAtMs = 0L
            )
        )

        val compacted = service.compactTerminalState(
            OverseerKernelRetentionPolicy(
                terminalRetentionMs = Long.MAX_VALUE,
                maxRetainedTerminalHypotheses = 0
            )
        )

        assertTrue(compacted != null)
        assertFalse(service.currentState().hypotheses.containsKey(hypothesis.id))
        assertFalse(service.currentState().experimentPlans.containsKey(plan.id))
        assertFalse(service.currentState().experimentSchedules.containsKey(plan.id))
        assertFalse(service.currentState().experimentResults.containsKey(plan.id))
        assertTrue(service.getDeadEndHistory(hypothesis.id).isNotEmpty())
    }
}
