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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible
import kotlinx.coroutines.test.runTest

/**
 * Regression coverage for disclosure check-in telemetry.
 */
class EvoMoliorRunServiceCheckinTelemetryTest {

    @Test
    fun `round archive retains failure counts without private sealed test identities`() = runTest {
        val runId = "run-redacted-round-summary"
        val groupId = "group-0"
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = SubstrateDatabaseFactory(
                createTempDirectory("svc-round-summary-redaction-").toFile()
            ),
            metaPlannerRunner = MetaPlannerRunner()
        )
        @Suppress("UNCHECKED_CAST")
        val assessments = service.javaClass.getDeclaredField("latestGroupRoundAssessments")
            .apply { isAccessible = true }
            .get(service) as MutableMap<String, DeterministicRoundAssessment>
        assessments[groupId] = DeterministicRoundAssessment(
            dimensionScores = listOf(DimensionScore("quality", 0.5, "sealed-suite")),
            automatedTestsPassed = 1,
            automatedTestsFailed = 1,
            overallScore = 0.25,
            gradingSheetApplied = "sealed-suite-private",
            failedTestIds = listOf("private-sealed-test-id"),
            mandatoryFailure = true
        )
        val blindResult = GradingResult(
            roundIndex = 0,
            scores = listOf(
                RoundScore(
                    groupId = groupId,
                    roundIndex = 0,
                    dimensionScores = listOf(DimensionScore("quality", 50.0, "blind")),
                    weightedTotal = 50.0,
                    rank = 1
                )
            ),
            winnerId = groupId
        )

        service::class.declaredFunctions
            .first { it.name == "archiveRoundSummaries" }
            .apply { isAccessible = true }
            .callSuspend(
                service,
                runId,
                "Redact private test identities.",
                listOf(groupId),
                0,
                emptyMap<String, Any>(),
                emptyMap<String, Any>(),
                blindResult,
                emptyMap<String, String>(),
                emptyMap<String, String?>()
            )

        val contents = service.getMetaArchiveEntries(
            runId,
            RunArchiveService.ENTRY_ROUND_SUMMARY
        ).map { it.contentJson }
        assertTrue(contents.any { it.contains("\"automatedTestsFailed\":1") })
        assertTrue(contents.all { it.contains("\"failedTestIds\":[]") })
        assertTrue(contents.none { it.contains("private-sealed-test-id") })
    }

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
        assertEquals(false, checkin.stageAdvanced)
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

    @Test
    fun `buildDisclosureCheckinResult exports only a committed round advance`() {
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in this check-in telemetry test")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = SubstrateDatabaseFactory(createTempDirectory("svc-stage-advance-telemetry-test-").toFile()),
            metaPlannerRunner = MetaPlannerRunner()
        )
        @Suppress("UNCHECKED_CAST")
        val advances = service.javaClass.getDeclaredField("disclosureStageAdvancedByGroupRound")
            .apply { isAccessible = true }
            .get(service) as MutableMap<Pair<String, Int>, Boolean>
        advances["group-0" to 1] = true

        assertEquals(false, service.buildDisclosureCheckinResult("group-0", 0).stageAdvanced)
        assertEquals(true, service.buildDisclosureCheckinResult("group-0", 1).stageAdvanced)
        assertEquals(false, service.buildDisclosureCheckinResult("group-1", 1).stageAdvanced)
    }
}
