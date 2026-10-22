package com.achulkov.evomolior.core.deliverable

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArcVisibleLooContractsTest {
    @Test
    fun `fixture covers exact 358 rows and preserves hash-bound provenance`() {
        val rows = ArcVisibleLooFixtureFactory.rows(
            top1CorrectRows = 100,
            anyTwoCorrectRows = 150,
        )

        assertEquals(ARC_VISIBLE_LOO_ROW_COUNT, rows.size)
        assertTrue(rows.all(ArcVisibleLooRowInput::hasValidHash))
        assertTrue(rows.flatMap { it.candidates }.all(ArcVisibleLooCandidate::hasValidHash))
        assertEquals(listOf(1, 2), rows.first().candidates.map { it.selectionRank })
    }

    @Test
    fun `report canonical bytes and self hash are deterministic`() {
        val definition = ArcVisibleLooBenchmarkDefinition.fixture()
        val rows = ArcVisibleLooFixtureFactory.rows(100, 150)
        val report = report(definition, rows)

        assertTrue(report.hasValidHash())
        assertEquals(report.canonicalBytes().toList(), report.canonicalBytes().toList())
        assertEquals(
            report.reportHash,
            ArcVisibleLooHashing.hashReport(report),
        )
        assertEquals(
            report.canonicalBytesHash(),
            ArcVisibleLooHashing.hashBytes(report.canonicalBytes()),
        )
    }

    @Test
    fun `tampered row and non-358 report fail closed`() {
        val rows = ArcVisibleLooFixtureFactory.rows()
        assertFailsWith<IllegalArgumentException> {
            rows.first().copy(expectedOutput = "tampered").hasValidHash().also {
                require(it) { "tampered row unexpectedly remained valid" }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            ArcVisibleLooReport(
                benchmarkId = ARC_VISIBLE_LOO_BENCHMARK_ID,
                challengeDefinitionHash = "a".repeat(64),
                sourceArtifactHash = "b".repeat(64),
                candidateId = "candidate",
                rowCount = 357,
                top1Correct = 0,
                anyTwoCorrect = 0,
                rows = emptyList(),
                candidateFamilyProvenance = emptyList(),
                repeatability = ArcVisibleLooRepeatabilityMetadata(
                    evaluatorVersion = "test",
                    repeatCount = 2,
                    canonicalBytesEqual = true,
                    canonicalHashEqual = true,
                    providerFree = true,
                ),
            )
        }
    }

    private fun report(
        definition: ArcVisibleLooBenchmarkDefinition,
        rows: List<ArcVisibleLooRowInput>,
    ): ArcVisibleLooReport {
        val evaluated = rows.map { input ->
            ArcVisibleLooRow(
                rowId = input.rowId,
                taskId = input.taskId,
                holdoutIndex = input.holdoutIndex,
                inputRowHash = input.rowHash,
                expectedOutputHash = ArcVisibleLooHashing.hashText(input.expectedOutput),
                selectedCandidateFamilies = input.candidates.map { it.familyId },
                selectedCandidateOutputHashes = input.candidates.map {
                    ArcVisibleLooHashing.hashText(it.output)
                },
                top1Correct = false,
                anyTwoCorrect = false,
            ).withComputedHash()
        }
        return ArcVisibleLooReport(
            benchmarkId = definition.benchmarkId,
            challengeDefinitionHash = definition.challengeDefinitionHash,
            sourceArtifactHash = "b".repeat(64),
            candidateId = "candidate",
            rowCount = rows.size,
            top1Correct = 0,
            anyTwoCorrect = 0,
            rows = evaluated,
            candidateFamilyProvenance = listOf(
                ArcVisibleLooFamilyProvenance("family-alternate", rows.size),
                ArcVisibleLooFamilyProvenance("family-top1", rows.size),
            ).sortedBy { it.familyId },
            repeatability = ArcVisibleLooRepeatabilityMetadata(
                evaluatorVersion = definition.evaluatorVersion,
                repeatCount = 2,
                canonicalBytesEqual = true,
                canonicalHashEqual = true,
                providerFree = true,
            ),
        ).withComputedHash()
    }
}
