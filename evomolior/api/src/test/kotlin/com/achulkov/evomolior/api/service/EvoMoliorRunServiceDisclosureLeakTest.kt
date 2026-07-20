package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.disclosure.DisclosureStage
import com.achulkov.evomolior.competition.disclosure.ProgressiveDisclosureEngine
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.core.contract.GroupCheckinResult
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
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
class EvoMoliorRunServiceDisclosureLeakTest {

    @Test
    fun `below-threshold disclosure check-in raises neighbour leak ceilings`() = runTest {
        val service = EvoMoliorRunService(
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
            ),
            stageAuthority = com.achulkov.evomolior.competition.disclosure.LocalProjectionDisclosureStageAuthority
        ).also { engine ->
            groupIds.forEach { engine.registerGroup(it) }
        }
        disclosureField.set(service, disclosureEngine)

        val assessmentsField = service.javaClass.getDeclaredField("latestGroupRoundAssessments").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val assessments = assessmentsField.get(service) as MutableMap<String, Any>
        assessments["group-1"] = com.achulkov.evomolior.api.service.DeterministicRoundAssessment(
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
