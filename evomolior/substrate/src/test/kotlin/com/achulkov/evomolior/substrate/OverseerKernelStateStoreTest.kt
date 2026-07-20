package com.achulkov.evomolior.substrate

import com.achulkov.evomolior.core.contract.OverseerAcceptanceCriteria
import com.achulkov.evomolior.core.contract.OverseerDeadEnd
import com.achulkov.evomolior.core.contract.OverseerDeadEndHistoryEntry
import com.achulkov.evomolior.core.contract.OverseerDeadEndHistorySource
import com.achulkov.evomolior.core.contract.OverseerExperimentMetrics
import com.achulkov.evomolior.core.contract.OverseerExperimentPlan
import com.achulkov.evomolior.core.contract.OverseerExperimentPlanHistoryActionType
import com.achulkov.evomolior.core.contract.OverseerExperimentPlanHistoryEntry
import com.achulkov.evomolior.core.contract.OverseerExperimentSchedule
import com.achulkov.evomolior.core.contract.OverseerExpectedEffect
import com.achulkov.evomolior.core.contract.OverseerHypothesis
import com.achulkov.evomolior.core.contract.OverseerHypothesisStatus
import com.achulkov.evomolior.core.contract.OverseerKernelState
import com.achulkov.evomolior.core.contract.OverseerMutabilityLevel
import com.achulkov.evomolior.core.contract.OverseerOrigin
import com.achulkov.evomolior.core.contract.OverseerOriginType
import com.achulkov.evomolior.core.contract.OverseerTargetScope
import com.achulkov.evomolior.core.contract.OverseerVariationSpec
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OverseerKernelStateStoreTest {
    @Test
    fun `store writes and reads the overseer kernel snapshot`() {
        val factory = SubstrateDatabaseFactory(createTempDirectory("overseer-store-test-").toFile())
        val store = OverseerKernelStateStore(factory)

        val state = OverseerKernelState(
            hypotheses = mapOf(
                "HYP-1" to OverseerHypothesis(
                    id = "HYP-1",
                    title = "Tune planner prompts",
                    origin = OverseerOrigin(
                        type = OverseerOriginType.INTERNAL_METRICS,
                        sources = listOf("documentation/EvoMolior_Current_Status.md")
                    ),
                    targetScope = OverseerTargetScope(
                        paths = listOf("documentation/EvoMolior_Recursive_Self_Improvement_Plan_v4.md"),
                        mutabilityLevel = OverseerMutabilityLevel.FULLY_MUTABLE
                    ),
                    rationale = "Persist the first formal hypothesis.",
                    expectedEffect = OverseerExpectedEffect(metricsUp = listOf("pass_rate"))
                ).copy(status = OverseerHypothesisStatus.FORMALIZED)
            ),
            experimentPlans = mapOf(
                "EXP-1" to OverseerExperimentPlan(
                    id = "EXP-1",
                    hypothesisId = "HYP-1",
                    runCount = 2,
                    generationsPerRun = 3,
                    variationSpec = OverseerVariationSpec(configChanges = listOf("raise selector budget")),
                    metrics = OverseerExperimentMetrics(primary = listOf("pass_rate")),
                    acceptanceCriteria = OverseerAcceptanceCriteria(predicates = listOf("pass_rate > 0.5")),
                    maxCost = 250.0
                )
            ),
            experimentSchedules = mapOf(
                "EXP-1" to OverseerExperimentSchedule(
                    experimentPlanId = "EXP-1",
                    hypothesisId = "HYP-1",
                    runCount = 2,
                    generationsPerRun = 3,
                    scheduledRunIds = listOf("EXP-1-run-01", "EXP-1-run-02")
                )
            ),
            experimentPlanHistory = mapOf(
                "EXP-1" to listOf(
                    OverseerExperimentPlanHistoryEntry(
                        experimentPlanId = "EXP-1",
                        hypothesisId = "HYP-1",
                        actionType = OverseerExperimentPlanHistoryActionType.REGISTERED,
                        note = "registered"
                    ),
                    OverseerExperimentPlanHistoryEntry(
                        experimentPlanId = "EXP-1",
                        hypothesisId = "HYP-1",
                        actionType = OverseerExperimentPlanHistoryActionType.SCHEDULED,
                        note = "scheduled"
                    )
                )
            ),
            deadEnds = mapOf(
                "HYP-2" to OverseerDeadEnd(
                    hypothesisId = "HYP-2",
                    reason = "Repeatedly touched immutable model selection paths."
                )
            ),
            deadEndHistory = mapOf(
                "HYP-2" to listOf(
                    OverseerDeadEndHistoryEntry(
                        hypothesisId = "HYP-2",
                        reason = "Repeatedly touched immutable model selection paths.",
                        source = OverseerDeadEndHistorySource.DIRECT_RECORD
                    )
                )
            )
        )

        store.write(state)
        val restored = assertNotNull(store.read())

        assertEquals(1, restored.hypotheses.size)
        assertEquals(1, restored.experimentPlans.size)
        assertEquals(1, restored.experimentSchedules.size)
        assertEquals(1, restored.deadEnds.size)
        assertEquals(1, restored.experimentPlanHistory.size)
        assertEquals(1, restored.deadEndHistory.size)
        assertEquals("Tune planner prompts", restored.hypotheses.getValue("HYP-1").title)
        assertEquals("Repeatedly touched immutable model selection paths.", restored.deadEnds.getValue("HYP-2").reason)

        val freshStore = OverseerKernelStateStore(factory)
        val reread = assertNotNull(freshStore.read())
        assertEquals(1, reread.hypotheses.size)
        assertEquals(1, reread.experimentPlans.size)
        assertEquals(1, reread.experimentSchedules.size)
        assertEquals(1, reread.deadEnds.size)
        assertEquals(1, reread.experimentPlanHistory.size)
        assertEquals(1, reread.deadEndHistory.size)
    }

    @Test
    fun `update creates state when none exists`() {
        val factory = SubstrateDatabaseFactory(createTempDirectory("overseer-update-test-").toFile())
        val store = OverseerKernelStateStore(factory)

        val updated = store.update { current ->
            current.copy(
                deadEnds = current.deadEnds + (
                    "HYP-9" to OverseerDeadEnd(
                        hypothesisId = "HYP-9",
                        reason = "Scope test"
                    )
                )
            )
        }

        assertEquals(1, updated.deadEnds.size)
        assertEquals("Scope test", updated.deadEnds.getValue("HYP-9").reason)
    }
}
