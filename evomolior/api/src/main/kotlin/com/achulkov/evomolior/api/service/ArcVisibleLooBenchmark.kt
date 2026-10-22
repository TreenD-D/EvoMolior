package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.deliverable.ARC_VISIBLE_LOO_ROW_COUNT
import com.achulkov.evomolior.core.deliverable.ArcVisibleLooBenchmarkDefinition
import com.achulkov.evomolior.core.deliverable.ArcVisibleLooCandidateSource
import com.achulkov.evomolior.core.deliverable.ArcVisibleLooFamilyProvenance
import com.achulkov.evomolior.core.deliverable.ArcVisibleLooHashing
import com.achulkov.evomolior.core.deliverable.ArcVisibleLooReport
import com.achulkov.evomolior.core.deliverable.ArcVisibleLooRepeatabilityMetadata
import com.achulkov.evomolior.core.deliverable.ArcVisibleLooRow
import com.achulkov.evomolior.core.deliverable.ArcVisibleLooRowInput
import kotlinx.serialization.json.Json

/**
 * Exact bytes and hashes emitted by [ArcVisibleLooBenchmark].
 *
 * @param report Hash-bound visible LOO report.
 * @param reportBytes Exact canonical UTF-8 bytes of [report].
 * @param reportHash Report self-hash excluding the self-hash field.
 * @param reportBytesHash SHA-256 of the complete canonical report bytes.
 * @param repeatedReportBytes Exact canonical bytes from the independent repeat pass.
 */
data class ArcVisibleLooBenchmarkResult(
    val report: ArcVisibleLooReport,
    val reportBytes: ByteArray,
    val reportHash: String,
    val reportBytesHash: String,
    val repeatedReportBytes: ByteArray,
) {
    init {
        require(report.hasValidHash()) { "ARC visible LOO result report is not hash-valid" }
        require(reportBytes.contentEquals(report.canonicalBytes())) {
            "ARC visible LOO result bytes do not equal report canonical bytes"
        }
        require(reportHash == report.reportHash) {
            "ARC visible LOO result report hash does not match report"
        }
        require(reportBytesHash == ArcVisibleLooHashing.hashBytes(reportBytes)) {
            "ARC visible LOO result bytes hash is invalid"
        }
        require(reportBytes.contentEquals(repeatedReportBytes)) {
            "ARC visible LOO repeat bytes differ from the primary report"
        }
    }
}

/**
 * Runtime-owned, provider-free evaluator for the exact ARC visible 358-row LOO contract.
 *
 * The service accepts already-produced row/candidate values and performs only canonical value
 * comparison, aggregation, hashing, and exact repeat verification. It never imports or executes
 * candidate source, calls a model, reads a provider, or infers missing rows.
 *
 * @param definition Exact challenge/benchmark definition commitment.
 */
class ArcVisibleLooBenchmark(
    private val definition: ArcVisibleLooBenchmarkDefinition =
        ArcVisibleLooBenchmarkDefinition.fixture(),
) {
    init {
        require(definition.expectedRowCount == ARC_VISIBLE_LOO_ROW_COUNT) {
            "ARC visible LOO runtime is bounded to exactly $ARC_VISIBLE_LOO_ROW_COUNT rows"
        }
    }

    /**
     * Evaluates supplied rows against one exact candidate source identity.
     *
     * @param candidate Candidate artifact/source commitment.
     * @param rows Exact hash-valid visible LOO rows.
     * @return Hash-bound report, canonical bytes, and exact repeat comparison.
     */
    fun benchmark(
        candidate: ArcVisibleLooCandidateSource,
        rows: List<ArcVisibleLooRowInput>,
    ): ArcVisibleLooBenchmarkResult {
        require(rows.size == definition.expectedRowCount) {
            "ARC visible LOO requires exactly ${definition.expectedRowCount} rows"
        }
        require(rows.map { it.rowId }.distinct().size == rows.size) {
            "ARC visible LOO row IDs must be unique"
        }
        require(rows.all(ArcVisibleLooRowInput::hasValidHash)) {
            "ARC visible LOO refuses unhashed or tampered row inputs"
        }
        val orderedRows = rows.sortedBy(ArcVisibleLooRowInput::rowId)
        val repeatability = ArcVisibleLooRepeatabilityMetadata(
            evaluatorVersion = definition.evaluatorVersion,
            repeatCount = 2,
            canonicalBytesEqual = true,
            canonicalHashEqual = true,
            providerFree = true,
        )
        val first = evaluate(candidate, orderedRows, repeatability)
        val second = evaluate(candidate, orderedRows, repeatability)
        val firstBytes = first.canonicalBytes()
        val secondBytes = second.canonicalBytes()
        require(firstBytes.contentEquals(secondBytes)) {
            "ARC visible LOO repeat comparison produced different canonical bytes"
        }
        require(first.canonicalBytesHash() == second.canonicalBytesHash()) {
            "ARC visible LOO repeat comparison produced different canonical hashes"
        }
        require(first.repeatability.canonicalBytesEqual && first.repeatability.canonicalHashEqual) {
            "ARC visible LOO repeatability metadata did not pass"
        }
        return ArcVisibleLooBenchmarkResult(
            report = first,
            reportBytes = firstBytes,
            reportHash = first.reportHash,
            reportBytesHash = first.canonicalBytesHash(),
            repeatedReportBytes = secondBytes,
        )
    }

    /**
     * Benchmarks exact textual candidate source, hashing it before report construction.
     *
     * A 64-character lowercase hexadecimal string is treated as an already-computed artifact
     * hash; every other string is treated as source bytes.
     *
     * @param candidateArtifactOrSource Candidate artifact hash or exact source text.
     * @param rows Exact hash-valid visible LOO rows.
     * @return Hash-bound benchmark result.
     */
    fun benchmark(
        candidateArtifactOrSource: String,
        rows: List<ArcVisibleLooRowInput>,
    ): ArcVisibleLooBenchmarkResult {
        val source = if (candidateArtifactOrSource.matches(Regex("[0-9a-f]{64}"))) {
            ArcVisibleLooCandidateSource.fromArtifactHash("candidate", candidateArtifactOrSource)
        } else {
            ArcVisibleLooCandidateSource.fromSource("candidate", candidateArtifactOrSource)
        }
        return benchmark(source, rows)
    }

    /**
     * Benchmarks exact candidate artifact bytes without executing them.
     *
     * @param candidateArtifact Exact candidate artifact bytes.
     * @param rows Exact hash-valid visible LOO rows.
     * @return Hash-bound benchmark result.
     */
    fun benchmark(
        candidateArtifact: ByteArray,
        rows: List<ArcVisibleLooRowInput>,
    ): ArcVisibleLooBenchmarkResult = benchmark(
        ArcVisibleLooCandidateSource(
            candidateId = "candidate",
            sourceArtifactHash = ArcVisibleLooHashing.hashBytes(candidateArtifact),
        ),
        rows,
    )

    private fun evaluate(
        candidate: ArcVisibleLooCandidateSource,
        rows: List<ArcVisibleLooRowInput>,
        repeatability: ArcVisibleLooRepeatabilityMetadata,
    ): ArcVisibleLooReport {
        val evaluatedRows = rows.map { input ->
            val expected = canonicalValue(input.expectedOutput)
            val outputs = input.candidates.map { candidateOutput ->
                canonicalValue(candidateOutput.output)
            }
            val expectedHash = ArcVisibleLooHashing.hashText(expected)
            val outputHashes = outputs.map(ArcVisibleLooHashing::hashText)
            val top1Correct = outputs[0] == expected
            val anyTwoCorrect = outputs.any { it == expected }
            ArcVisibleLooRow(
                rowId = input.rowId,
                taskId = input.taskId,
                holdoutIndex = input.holdoutIndex,
                inputRowHash = input.rowHash,
                expectedOutputHash = expectedHash,
                selectedCandidateFamilies = input.candidates.map { it.familyId },
                selectedCandidateOutputHashes = outputHashes,
                top1Correct = top1Correct,
                anyTwoCorrect = anyTwoCorrect,
            ).withComputedHash()
        }
        val familyProvenance = evaluatedRows
            .flatMap(ArcVisibleLooRow::selectedCandidateFamilies)
            .groupingBy { it }
            .eachCount()
            .toSortedMap()
            .map { (familyId, selectedCount) ->
                ArcVisibleLooFamilyProvenance(familyId, selectedCount)
            }
        return ArcVisibleLooReport(
            benchmarkId = definition.benchmarkId,
            challengeDefinitionHash = definition.challengeDefinitionHash,
            sourceArtifactHash = candidate.sourceArtifactHash,
            candidateId = candidate.candidateId,
            rowCount = rows.size,
            top1Correct = evaluatedRows.count(ArcVisibleLooRow::top1Correct),
            anyTwoCorrect = evaluatedRows.count(ArcVisibleLooRow::anyTwoCorrect),
            rows = evaluatedRows,
            candidateFamilyProvenance = familyProvenance,
            repeatability = repeatability,
        ).withComputedHash()
    }

    private fun canonicalValue(value: String): String =
        runCatching {
            ArcVisibleLooHashing.canonicalJson(Json.parseToJsonElement(value))
        }.getOrElse { value }
}
