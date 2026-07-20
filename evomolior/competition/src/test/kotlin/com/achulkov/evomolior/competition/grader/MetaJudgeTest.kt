package com.achulkov.evomolior.competition.grader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression coverage for the meta-judge's consensus and arbitration path.
 */
class MetaJudgeTest {

    @Test
    fun `verdict flags contested dimensions and skews arbitration toward higher quality sheets`() {
        val metaJudge = MetaJudge(minGraderGroups = 1)

        val lowQualitySheet = GradingSheet(
            sheetId = "sheet-low",
            runId = "run-metajudge",
            roundIndex = 0,
            graderGroupId = "grader-low",
            rubricDimensions = listOf(
                RubricDimension("accuracy", "Accuracy", 0.8, "Favor accurate scoring"),
                RubricDimension("novelty", "Novelty", 0.2, "Favor novel evidence")
            ),
            automatedTests = emptyList(),
            failureModes = emptyList(),
            comparativePairs = emptyList(),
            sealedAt = 1L
        )

        val highQualitySheet = GradingSheet(
            sheetId = "sheet-high",
            runId = "run-metajudge",
            roundIndex = 0,
            graderGroupId = "grader-high",
            rubricDimensions = listOf(
                RubricDimension("accuracy", "Accuracy", 0.2, "Favor accurate scoring"),
                RubricDimension("novelty", "Novelty", 0.8, "Favor novel evidence")
            ),
            automatedTests = (1..10).map { index ->
                AutomatedTest(
                    testId = "test-$index",
                    description = "Test $index",
                    expectedOutputPattern = ".*",
                    penaltyOnFailure = 0.5,
                    isMandatory = index == 1
                )
            },
            failureModes = (1..5).map { index ->
                FailureMode(
                    modeId = "mode-$index",
                    description = "Failure mode $index",
                    penaltyWeight = 0.5
                )
            },
            comparativePairs = emptyList(),
            sealedAt = 1L
        )

        metaJudge.registerSheet(lowQualitySheet)
        metaJudge.registerSheet(highQualitySheet)

        val verdict = metaJudge.verdict(0)

        assertEquals(listOf("accuracy", "novelty"), verdict.contestedDimensionIds)
        assertTrue(
            verdict.averageSheetQuality > 0.5,
            "High-completeness sheets should raise the average sheet quality"
        )
        assertTrue(
            verdict.dimensionWeights.getValue("accuracy") < 0.5,
            "Weighted arbitration should lean toward the higher-quality sheet's accuracy weight"
        )
        assertTrue(
            verdict.dimensionWeights.getValue("novelty") > 0.5,
            "Weighted arbitration should lean toward the higher-quality sheet's novelty weight"
        )
        assertEquals(2, verdict.graderSheetIds.size)
    }
}
