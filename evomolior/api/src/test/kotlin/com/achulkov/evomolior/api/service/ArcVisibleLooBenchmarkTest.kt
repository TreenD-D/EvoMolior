package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.deliverable.ARC_VISIBLE_LOO_ROW_COUNT
import com.achulkov.evomolior.core.deliverable.ArcVisibleLooBenchmarkDefinition
import com.achulkov.evomolior.core.deliverable.ArcVisibleLooCandidateSource
import com.achulkov.evomolior.core.deliverable.ArcVisibleLooFixtureFactory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArcVisibleLooBenchmarkTest {
    @Test
    fun `positive fixture reports top1 any-two provenance and repeatable bytes`() {
        val rows = ArcVisibleLooFixtureFactory.rows(
            top1CorrectRows = 120,
            anyTwoCorrectRows = 180,
        )
        val result = ArcVisibleLooBenchmark(ArcVisibleLooBenchmarkDefinition.fixture())
            .benchmark(
                ArcVisibleLooCandidateSource.fromSource(
                    candidateId = "candidate-positive",
                    source = "provider-free-fixture-source",
                ),
                rows,
            )

        assertEquals(ARC_VISIBLE_LOO_ROW_COUNT, result.report.rowCount)
        assertEquals(120, result.report.top1Correct)
        assertEquals(180, result.report.anyTwoCorrect)
        assertEquals(2, result.report.repeatability.repeatCount)
        assertTrue(result.report.repeatability.providerFree)
        assertTrue(result.report.repeatability.canonicalBytesEqual)
        assertContentEquals(result.reportBytes, result.repeatedReportBytes)
        assertEquals(result.report.reportHash, result.reportHash)
        assertTrue(result.report.hasValidHash())
        val csv = result.report.canonicalCsvBytes().decodeToString()
        assertEquals(ARC_VISIBLE_LOO_ROW_COUNT + 1, csv.trimEnd().lineSequence().count())
        assertTrue(csv.startsWith("row_id,task_id,holdout_index,input_row_hash"))
        assertEquals(
            listOf("family-alternate", "family-top1"),
            result.report.candidateFamilyProvenance.map { it.familyId },
        )
    }

    @Test
    fun `no-improvement fixture remains a valid deterministic zero result`() {
        val rows = ArcVisibleLooFixtureFactory.rows()
        val result = ArcVisibleLooBenchmark().benchmark("candidate-source", rows)

        assertEquals(0, result.report.top1Correct)
        assertEquals(0, result.report.anyTwoCorrect)
        assertEquals(0.0, result.report.top1Rate)
        assertEquals(0.0, result.report.anyTwoRate)
        assertTrue(result.report.rows.all { !it.top1Correct && !it.anyTwoCorrect })
        assertEquals(
            result.report.challengeDefinitionHash,
            ArcVisibleLooBenchmarkDefinition.fixture().challengeDefinitionHash,
        )
    }

    @Test
    fun `service rejects missing rows tampered row hashes and invalid source hash`() {
        val rows = ArcVisibleLooFixtureFactory.rows()
        assertFailsWith<IllegalArgumentException> {
            ArcVisibleLooBenchmark().benchmark("source", rows.dropLast(1))
        }
        assertFailsWith<IllegalArgumentException> {
            ArcVisibleLooBenchmark().benchmark(
                "source",
                rows.drop(1) + rows.first().copy(rowId = "row-000").let {
                    it.copy(rowHash = "c".repeat(64))
                },
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ArcVisibleLooBenchmark().benchmark(
                ArcVisibleLooCandidateSource(
                    candidateId = "bad",
                    sourceArtifactHash = "not-a-hash",
                ),
                rows,
            )
        }
    }

    @Test
    fun `json output comparison is canonical while non-json values remain exact`() {
        val rows = ArcVisibleLooFixtureFactory.rows(
            top1CorrectRows = 1,
            anyTwoCorrectRows = 1,
        ).toMutableList()
        val first = rows.first()
        rows[0] = first.copy(
            expectedOutput = """{"grid":[[1,2],[3,4]]}""",
            candidates = listOf(
                first.candidates[0].copy(output = """{ "grid": [[1, 2], [3, 4]] }""")
                    .withComputedHash(),
                first.candidates[1],
            ),
        ).withComputedHash()

        val result = ArcVisibleLooBenchmark().benchmark("json-candidate", rows)

        assertEquals(1, result.report.top1Correct)
        assertEquals(1, result.report.anyTwoCorrect)
    }
}
