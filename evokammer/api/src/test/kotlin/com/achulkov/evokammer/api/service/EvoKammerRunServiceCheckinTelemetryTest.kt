package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.api.service.DeterministicRoundAssessment
import com.achulkov.evokammer.competition.coordinator.GroupCoordinator
import com.achulkov.evokammer.competition.grader.GraderGroupManager
import com.achulkov.evokammer.core.model.DimensionScore
import com.achulkov.evokammer.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression coverage for disclosure check-in telemetry.
 */
class EvoKammerRunServiceCheckinTelemetryTest {

    @Test
    fun `buildDisclosureCheckinResult derives a deterministic pass rate from sealed assessment telemetry`() {
        val service = EvoKammerRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in this check-in telemetry test")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = SubstrateDatabaseFactory(createTempDirectory("svc-checkin-telemetry-test-").toFile()),
            metaPlannerRunner = MetaPlannerRunner()
        )

        val assessmentsField = service.javaClass.getDeclaredField("latestGroupRoundAssessments").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val assessments = assessmentsField.get(service) as MutableMap<String, Any>
        assessments["group-0"] = DeterministicRoundAssessment(
            dimensionScores = listOf(
                DimensionScore(
                    dimensionId = "accuracy",
                    rawScore = 0.75,
                    justification = "sealed-suite"
                )
            ),
            automatedTestsPassed = 3,
            automatedTestsFailed = 1,
            overallScore = 0.75,
            gradingSheetApplied = "sheet-0",
            failedTestIds = listOf("t4"),
            mandatoryFailure = false
        )

        val checkin = service.buildDisclosureCheckinResult("group-0", 0)
        assertEquals(0.75, checkin.overallScore)
        assertEquals(0.75, checkin.testPassRate)
        assertEquals(1, checkin.failedTestCount)
        assertEquals("sheet-0", checkin.gradingSheetApplied)
    }
}
