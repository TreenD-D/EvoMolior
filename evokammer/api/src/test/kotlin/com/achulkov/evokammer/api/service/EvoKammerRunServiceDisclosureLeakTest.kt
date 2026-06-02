package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.api.model.RunConfig
import com.achulkov.evokammer.competition.coordinator.GroupCoordinator
import com.achulkov.evokammer.competition.disclosure.DisclosureStage
import com.achulkov.evokammer.competition.disclosure.ProgressiveDisclosureEngine
import com.achulkov.evokammer.competition.grader.GraderGroupManager
import com.achulkov.evokammer.core.contract.GroupCheckinResult
import com.achulkov.evokammer.core.model.ScoringDimension
import com.achulkov.evokammer.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import kotlin.io.path.createTempDirectory
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression coverage for disclosure failure pressure increasing leak ceilings on neighbours.
 */
class EvoKammerRunServiceDisclosureLeakTest {

    @Test
    fun `below-threshold disclosure check-in raises neighbour leak ceilings`() = runTest {
        val service = EvoKammerRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in this disclosure leak test")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = SubstrateDatabaseFactory(createTempDirectory("svc-disclosure-leak-test-").toFile()),
            metaPlannerRunner = MetaPlannerRunner()
        )

        val groupIds = listOf("group-0", "group-1", "group-2")
        val coordinatorField = service.javaClass.getDeclaredField("activeCoordinator").apply { isAccessible = true }
        val coordinator = GroupCoordinator(
            groupIds = groupIds,
            dimensions = listOf(
                ScoringDimension(
                    id = "accuracy",
                    name = "Accuracy",
                    description = "Score accuracy",
                    weight = 1.0
                )
            )
        )
        coordinatorField.set(service, coordinator)

        val disclosureField = service.javaClass.getDeclaredField("activeDisclosureEngine").apply { isAccessible = true }
        val disclosureEngine = ProgressiveDisclosureEngine(
            stages = listOf(
                DisclosureStage(
                    stageIndex = 0,
                    title = "Stage 0",
                    constraintFramedContent = "constraints",
                    goalFramedContent = "goal"
                )
            )
        ).also { engine ->
            groupIds.forEach { engine.registerGroup(it) }
        }
        disclosureField.set(service, disclosureEngine)

        val assessmentsField = service.javaClass.getDeclaredField("latestGroupRoundAssessments").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val assessments = assessmentsField.get(service) as MutableMap<String, Any>
        assessments["group-1"] = com.achulkov.evokammer.api.service.DeterministicRoundAssessment(
            dimensionScores = emptyList(),
            automatedTestsPassed = 0,
            automatedTestsFailed = 0,
            overallScore = 0.2,
            gradingSheetApplied = "sheet",
            failedTestIds = emptyList(),
            mandatoryFailure = false
        )

        val processFn = service::class.declaredFunctions.first { it.name == "processDisclosureCheckIns" }.apply {
            isAccessible = true
        }
        processFn.callSuspend(service, "run-disclosure-test", groupIds, 0)

        assertTrue(
            coordinator.leakOracle.ceilingFor("group-0") > 3,
            "Neighbours of a below-threshold group should get a higher outbound leak ceiling"
        )
    }
}
