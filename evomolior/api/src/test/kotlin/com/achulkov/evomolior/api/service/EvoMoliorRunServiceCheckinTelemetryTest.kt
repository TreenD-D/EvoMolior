package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.service.DeterministicRoundAssessment
import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.core.model.DimensionScore
import com.achulkov.evomolior.core.model.GradingResult
import com.achulkov.evomolior.core.model.RoundScore
import com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression coverage for disclosure check-in telemetry.
 */
class EvoMoliorRunServiceCheckinTelemetryTest {

    @Test
    fun `buildDisclosureCheckinResult derives a deterministic pass rate from sealed assessment telemetry`() {
        val service = EvoMoliorRunService(
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

    @Test
    fun `buildDisclosureCheckinResult uses authoritative grader scores with substrate test telemetry`() {
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in this check-in telemetry test")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = SubstrateDatabaseFactory(createTempDirectory("svc-grader-checkin-test-").toFile()),
            metaPlannerRunner = MetaPlannerRunner()
        )
        @Suppress("UNCHECKED_CAST")
        val graderResults = service.javaClass.getDeclaredField("authoritativeGraderRoundResults")
            .apply { isAccessible = true }
            .get(service) as MutableMap<Int, GradingResult>
        graderResults[0] = GradingResult(
            roundIndex = 0,
            scores = listOf(
                RoundScore(
                    groupId = "group-0",
                    roundIndex = 0,
                    dimensionScores = listOf(DimensionScore("accuracy", 8.5, "grader evidence")),
                    weightedTotal = 85.0
                )
            ),
            winnerId = "group-0"
        )

        val checkin = service.buildDisclosureCheckinResult("group-0", 0)

        assertEquals(0.85, checkin.overallScore)
        assertEquals(mapOf("accuracy" to 8.5), checkin.dimensionScores)
        assertEquals("grader-civilisation-round-0", checkin.gradingSheetApplied)
    }
}
