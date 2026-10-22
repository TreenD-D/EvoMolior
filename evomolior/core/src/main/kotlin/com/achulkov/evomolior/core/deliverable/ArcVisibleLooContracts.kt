package com.achulkov.evomolior.core.deliverable

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Schema identifier for the runtime-owned ARC visible leave-one-out contract. */
const val ARC_VISIBLE_LOO_SCHEMA_V1: String = "arc-visible-loo-v1"

/** Stable benchmark identifier for the exact 358-row visible LOO contract. */
const val ARC_VISIBLE_LOO_BENCHMARK_ID: String = "arc-visible-training-pair-loo-358"

/** Exact number of eligible visible leave-one-out rows in the benchmark contract. */
const val ARC_VISIBLE_LOO_ROW_COUNT: Int = 358

private val SHA256_PATTERN = Regex("[0-9a-f]{64}")

/**
 * Exact benchmark definition commitment consumed by the runtime evaluator.
 *
 * @param benchmarkId Stable benchmark identity.
 * @param challengeDefinitionHash SHA-256 of the exact challenge/benchmark definition bytes.
 * @param expectedRowCount Required eligible-row cardinality.
 * @param evaluatorVersion Provider-free evaluator implementation contract version.
 */
@Serializable
data class ArcVisibleLooBenchmarkDefinition(
    val benchmarkId: String = ARC_VISIBLE_LOO_BENCHMARK_ID,
    val challengeDefinitionHash: String,
    val expectedRowCount: Int = ARC_VISIBLE_LOO_ROW_COUNT,
    val evaluatorVersion: String = "runtime-owned-exact-v1",
) {
    init {
        require(benchmarkId.isNotBlank()) { "ARC visible LOO benchmark ID must not be blank" }
        require(challengeDefinitionHash.isArcSha256()) {
            "ARC visible LOO challenge definition hash must be lowercase SHA-256"
        }
        require(expectedRowCount == ARC_VISIBLE_LOO_ROW_COUNT) {
            "ARC visible LOO is bounded to exactly $ARC_VISIBLE_LOO_ROW_COUNT rows"
        }
        require(evaluatorVersion.isNotBlank()) {
            "ARC visible LOO evaluator version must not be blank"
        }
    }

    /** Returns the built-in definition for [challengeDefinitionHash]. */
    companion object {
        /**
         * Creates the exact 358-row definition from challenge-definition bytes.
         *
         * @param challengeDefinitionBytes Exact challenge/benchmark definition bytes.
         * @return Hash-bound visible LOO definition.
         */
        fun fromChallengeDefinition(
            challengeDefinitionBytes: ByteArray,
        ): ArcVisibleLooBenchmarkDefinition = ArcVisibleLooBenchmarkDefinition(
            challengeDefinitionHash = ArcVisibleLooHashing.hashBytes(challengeDefinitionBytes),
        )

        /**
         * Creates the exact 358-row definition from a textual challenge definition.
         *
         * @param challengeDefinition Exact challenge/benchmark definition text.
         * @return Hash-bound visible LOO definition.
         */
        fun fromChallengeDefinition(
            challengeDefinition: String,
        ): ArcVisibleLooBenchmarkDefinition =
            fromChallengeDefinition(challengeDefinition.encodeToByteArray())

        /**
         * Returns a deterministic fixture definition for tests and local integration smoke runs.
         *
         * @return Hash-bound fixture definition.
         */
        fun fixture(): ArcVisibleLooBenchmarkDefinition =
            fromChallengeDefinition("arc-visible-loo-358-fixture-definition-v1")
    }
}

/**
 * Runtime-visible identity of the candidate artifact or source being evaluated.
 *
 * Raw source is deliberately not carried into the report. The hash is the authority that binds
 * the report to the exact artifact supplied to the runtime.
 *
 * @param candidateId Stable candidate identity.
 * @param sourceArtifactHash SHA-256 of the exact candidate artifact/source bytes.
 * @param sourceLabel Non-semantic operator label for the source.
 */
@Serializable
data class ArcVisibleLooCandidateSource(
    val candidateId: String,
    val sourceArtifactHash: String,
    val sourceLabel: String = "",
) {
    init {
        require(candidateId.isNotBlank()) { "ARC visible LOO candidate ID must not be blank" }
        require(sourceArtifactHash.isArcSha256()) {
            "ARC visible LOO source artifact hash must be lowercase SHA-256"
        }
    }

    companion object {
        /**
         * Hashes exact textual candidate source without exposing it in the report.
         *
         * @param candidateId Stable candidate identity.
         * @param source Exact candidate source text.
         * @param sourceLabel Non-semantic operator label.
         * @return Hash-bound candidate source.
         */
        fun fromSource(
            candidateId: String,
            source: String,
            sourceLabel: String = "",
        ): ArcVisibleLooCandidateSource = ArcVisibleLooCandidateSource(
            candidateId = candidateId,
            sourceArtifactHash = ArcVisibleLooHashing.hashText(source),
            sourceLabel = sourceLabel,
        )

        /**
         * Binds a candidate to an already-computed artifact hash.
         *
         * @param candidateId Stable candidate identity.
         * @param sourceArtifactHash Exact source artifact hash.
         * @param sourceLabel Non-semantic operator label.
         * @return Hash-bound candidate source.
         */
        fun fromArtifactHash(
            candidateId: String,
            sourceArtifactHash: String,
            sourceLabel: String = "",
        ): ArcVisibleLooCandidateSource = ArcVisibleLooCandidateSource(
            candidateId = candidateId,
            sourceArtifactHash = sourceArtifactHash,
            sourceLabel = sourceLabel,
        )
    }
}

/**
 * One provider-supplied selected output for a visible LOO row.
 *
 * Candidate order is explicit and is part of the hash-bound input. Rank one is the top-1 output;
 * rank two is the alternate used by the any-two metric.
 *
 * @param selectionRank One-based selected-output rank, exactly 1 or 2.
 * @param familyId Stable candidate-family provenance identifier.
 * @param output Exact candidate output in canonical textual form.
 * @param candidateHash Hash of this exact candidate selection record.
 */
@Serializable
data class ArcVisibleLooCandidate(
    val selectionRank: Int,
    val familyId: String,
    val output: String,
    val candidateHash: String = "",
) {
    init {
        require(selectionRank in 1..2) {
            "ARC visible LOO candidate selection rank must be 1 or 2"
        }
        require(familyId.isNotBlank()) { "ARC visible LOO candidate family must not be blank" }
        require(output.isNotBlank()) { "ARC visible LOO candidate output must not be blank" }
        require(candidateHash.isBlank() || candidateHash.isArcSha256()) {
            "ARC visible LOO candidate hash must be lowercase SHA-256 when present"
        }
    }

    /** Returns this candidate with a deterministic hash of its exact family, rank, and output. */
    fun withComputedHash(): ArcVisibleLooCandidate =
        copy(candidateHash = ArcVisibleLooHashing.hashCandidate(this))

    /** Returns whether the candidate hash commits its complete input. */
    fun hasValidHash(): Boolean =
        candidateHash.isArcSha256() &&
            constantTimeEquals(candidateHash, ArcVisibleLooHashing.hashCandidate(this))
}

/**
 * One exact visible training-pair holdout and the two selected candidate outputs.
 *
 * @param rowId Stable globally unique row identity.
 * @param taskId Stable ARC task identity.
 * @param holdoutIndex Zero-based visible training-pair index removed for this row.
 * @param expectedOutput Exact held-out output.
 * @param candidates Ordered top-1 and alternate candidate outputs.
 * @param rowHash Hash of this complete supplied row.
 */
@Serializable
data class ArcVisibleLooRowInput(
    val rowId: String,
    val taskId: String,
    val holdoutIndex: Int,
    val expectedOutput: String,
    val candidates: List<ArcVisibleLooCandidate>,
    val rowHash: String = "",
) {
    init {
        require(rowId.isNotBlank() && taskId.isNotBlank()) {
            "ARC visible LOO row and task IDs must not be blank"
        }
        require(holdoutIndex >= 0) {
            "ARC visible LOO holdout index must not be negative"
        }
        require(expectedOutput.isNotBlank()) {
            "ARC visible LOO expected output must not be blank"
        }
        require(candidates.size == 2) {
            "ARC visible LOO requires exactly two selected candidates per row"
        }
        require(candidates.map { it.selectionRank } == listOf(1, 2)) {
            "ARC visible LOO candidates must be ordered as ranks 1 and 2"
        }
        require(candidates.all {
            it.candidateHash.isBlank() || it.hasValidHash()
        }) {
            "ARC visible LOO row contains an invalid candidate hash"
        }
        require(rowHash.isBlank() || rowHash.isArcSha256()) {
            "ARC visible LOO row hash must be lowercase SHA-256 when present"
        }
    }

    /** Returns this row with deterministic candidate and row hashes attached. */
    fun withComputedHash(): ArcVisibleLooRowInput {
        val hashedCandidates = candidates.map { it.withComputedHash() }
        return copy(
            candidates = hashedCandidates,
            rowHash = ArcVisibleLooHashing.hashRowInput(copy(candidates = hashedCandidates)),
        )
    }

    /** Returns whether the row hash commits expected output and candidate-family provenance. */
    fun hasValidHash(): Boolean =
        rowHash.isArcSha256() &&
            candidates.all(ArcVisibleLooCandidate::hasValidHash) &&
            constantTimeEquals(rowHash, ArcVisibleLooHashing.hashRowInput(this))
}

/**
 * Hash-bound evaluated result for one visible LOO row.
 *
 * @param rowId Stable source row identity.
 * @param taskId Stable ARC task identity.
 * @param holdoutIndex Zero-based held-out training-pair index.
 * @param inputRowHash Hash of the exact supplied row input.
 * @param expectedOutputHash Hash of the exact held-out output after canonical value encoding.
 * @param selectedCandidateFamilies Ordered family provenance for top-1 and alternate outputs.
 * @param selectedCandidateOutputHashes Ordered hashes of top-1 and alternate outputs.
 * @param top1Correct Whether the top-1 output exactly matches the holdout.
 * @param anyTwoCorrect Whether either selected output exactly matches the holdout.
 * @param rowHash Hash of this evaluated row.
 */
@Serializable
data class ArcVisibleLooRow(
    val rowId: String,
    val taskId: String,
    val holdoutIndex: Int,
    val inputRowHash: String,
    val expectedOutputHash: String,
    val selectedCandidateFamilies: List<String>,
    val selectedCandidateOutputHashes: List<String>,
    val top1Correct: Boolean,
    val anyTwoCorrect: Boolean,
    val rowHash: String = "",
) {
    init {
        require(rowId.isNotBlank() && taskId.isNotBlank()) {
            "ARC visible LOO evaluated row and task IDs must not be blank"
        }
        require(holdoutIndex >= 0) { "ARC visible LOO holdout index must not be negative" }
        require(inputRowHash.isArcSha256() && expectedOutputHash.isArcSha256()) {
            "ARC visible LOO evaluated row input hashes are invalid"
        }
        require(selectedCandidateFamilies.size == 2) {
            "ARC visible LOO evaluated row requires two family provenance entries"
        }
        require(selectedCandidateFamilies.all(String::isNotBlank)) {
            "ARC visible LOO candidate-family provenance must not be blank"
        }
        require(
            selectedCandidateOutputHashes.size == 2 &&
                selectedCandidateOutputHashes.all(String::isArcSha256)
        ) { "ARC visible LOO evaluated candidate output hashes are invalid" }
        require(!top1Correct || anyTwoCorrect) {
            "ARC visible LOO any-two cannot be false when top-1 is correct"
        }
        require(rowHash.isBlank() || rowHash.isArcSha256()) {
            "ARC visible LOO evaluated row hash must be lowercase SHA-256 when present"
        }
    }

    /** Returns this evaluated row with its deterministic self-hash attached. */
    fun withComputedHash(): ArcVisibleLooRow =
        copy(rowHash = ArcVisibleLooHashing.hashRow(this))

    /** Returns whether the row hash commits outcomes and candidate-family provenance. */
    fun hasValidHash(): Boolean =
        rowHash.isArcSha256() &&
            constantTimeEquals(rowHash, ArcVisibleLooHashing.hashRow(this))
}

/**
 * Aggregate candidate-family provenance retained by an ARC visible LOO report.
 *
 * @param familyId Stable candidate-family identifier.
 * @param selectedCount Number of selected outputs from this family across all rows.
 */
@Serializable
data class ArcVisibleLooFamilyProvenance(
    val familyId: String,
    val selectedCount: Int,
) {
    init {
        require(familyId.isNotBlank()) { "ARC visible LOO family ID must not be blank" }
        require(selectedCount > 0) { "ARC visible LOO family selection count must be positive" }
    }
}

/**
 * Repeatability evidence attached to one runtime-owned evaluation.
 *
 * @param evaluatorVersion Exact provider-free evaluator version.
 * @param repeatCount Number of complete evaluator passes compared.
 * @param canonicalBytesEqual Whether compared report bytes were exactly equal.
 * @param canonicalHashEqual Whether compared report hashes were exactly equal.
 * @param providerFree Whether no model/provider execution participated.
 */
@Serializable
data class ArcVisibleLooRepeatabilityMetadata(
    val evaluatorVersion: String,
    val repeatCount: Int,
    val canonicalBytesEqual: Boolean,
    val canonicalHashEqual: Boolean,
    val providerFree: Boolean,
) {
    init {
        require(evaluatorVersion.isNotBlank()) {
            "ARC visible LOO evaluator version must not be blank"
        }
        require(repeatCount >= 2) { "ARC visible LOO repeat comparison requires two passes" }
        require(providerFree) { "ARC visible LOO evaluator must be provider-free" }
    }
}

/**
 * Complete hash-bound runtime-owned ARC visible LOO report.
 *
 * @param schemaVersion Contract schema identifier.
 * @param benchmarkId Stable benchmark identity.
 * @param challengeDefinitionHash Exact challenge/benchmark definition hash.
 * @param sourceArtifactHash Exact candidate source artifact hash.
 * @param candidateId Candidate identity.
 * @param rowCount Exact evaluated row count; this contract requires 358.
 * @param top1Correct Number of rows whose rank-one output matched.
 * @param anyTwoCorrect Number of rows where either selected output matched.
 * @param rows Exact evaluated row results.
 * @param candidateFamilyProvenance Aggregate selected-family provenance.
 * @param repeatability Repeat comparison metadata.
 * @param reportHash Self-hash over all fields except this hash.
 */
@Serializable
data class ArcVisibleLooReport(
    val schemaVersion: String = ARC_VISIBLE_LOO_SCHEMA_V1,
    val benchmarkId: String,
    val challengeDefinitionHash: String,
    val sourceArtifactHash: String,
    val candidateId: String,
    val rowCount: Int,
    val top1Correct: Int,
    val anyTwoCorrect: Int,
    val rows: List<ArcVisibleLooRow>,
    val candidateFamilyProvenance: List<ArcVisibleLooFamilyProvenance>,
    val repeatability: ArcVisibleLooRepeatabilityMetadata,
    val reportHash: String = "",
) {
    init {
        require(schemaVersion == ARC_VISIBLE_LOO_SCHEMA_V1) {
            "Unsupported ARC visible LOO schema $schemaVersion"
        }
        require(benchmarkId.isNotBlank() && candidateId.isNotBlank()) {
            "ARC visible LOO report identity must not be blank"
        }
        require(challengeDefinitionHash.isArcSha256() && sourceArtifactHash.isArcSha256()) {
            "ARC visible LOO report source commitments are invalid"
        }
        require(rowCount == ARC_VISIBLE_LOO_ROW_COUNT && rows.size == rowCount) {
            "ARC visible LOO report must cover exactly $ARC_VISIBLE_LOO_ROW_COUNT rows"
        }
        require(rows.map { it.rowId }.distinct().size == rows.size) {
            "ARC visible LOO report row IDs must be unique"
        }
        require(rows.all(ArcVisibleLooRow::hasValidHash)) {
            "ARC visible LOO report contains an invalid row"
        }
        require(top1Correct in 0..rowCount && anyTwoCorrect in 0..rowCount) {
            "ARC visible LOO report counts are outside the row cardinality"
        }
        require(top1Correct <= anyTwoCorrect) {
            "ARC visible LOO any-two count cannot be below top-1 count"
        }
        require(
            top1Correct == rows.count(ArcVisibleLooRow::top1Correct) &&
                anyTwoCorrect == rows.count(ArcVisibleLooRow::anyTwoCorrect)
        ) { "ARC visible LOO aggregate counts do not match row outcomes" }
        require(
            candidateFamilyProvenance.map { it.familyId }.distinct().size ==
                candidateFamilyProvenance.size &&
                candidateFamilyProvenance == candidateFamilyProvenance.sortedBy {
                    it.familyId
                }
        ) { "ARC visible LOO family provenance must be unique and sorted" }
        val observedFamilyCounts = rows
            .flatMap(ArcVisibleLooRow::selectedCandidateFamilies)
            .groupingBy { it }
            .eachCount()
        require(
            candidateFamilyProvenance.associate { it.familyId to it.selectedCount } ==
                observedFamilyCounts
        ) { "ARC visible LOO family provenance counts do not match row provenance" }
        require(reportHash.isBlank() || reportHash.isArcSha256()) {
            "ARC visible LOO report hash must be lowercase SHA-256 when present"
        }
    }

    /** Top-1 accuracy as a fraction of the exact 358-row benchmark. */
    val top1Rate: Double
        get() = top1Correct.toDouble() / rowCount

    /** Any-two accuracy as a fraction of the exact 358-row benchmark. */
    val anyTwoRate: Double
        get() = anyTwoCorrect.toDouble() / rowCount

    /** Top-1 percentage, retained as a convenience projection for benchmark consumers. */
    val top1RatePercent: Double
        get() = top1Rate * 100.0

    /** Any-two percentage, retained as a convenience projection for benchmark consumers. */
    val anyTwoRatePercent: Double
        get() = anyTwoRate * 100.0

    /** Returns this report with its deterministic self-hash attached. */
    fun withComputedHash(): ArcVisibleLooReport =
        copy(reportHash = ArcVisibleLooHashing.hashReport(this))

    /** Returns whether the report, every row, and every aggregate are hash-valid. */
    fun hasValidHash(): Boolean =
        reportHash.isArcSha256() &&
            constantTimeEquals(reportHash, ArcVisibleLooHashing.hashReport(this)) &&
            rows.all(ArcVisibleLooRow::hasValidHash)

    /** Returns exact canonical UTF-8 bytes for the complete report including its self-hash. */
    fun canonicalBytes(): ByteArray = ArcVisibleLooHashing.canonicalReportBytes(this)

    /** Returns SHA-256 of [canonicalBytes]. */
    fun canonicalBytesHash(): String = ArcVisibleLooHashing.hashBytes(canonicalBytes())

    /** Returns exact canonical UTF-8 CSV bytes for the row-level benchmark report. */
    fun canonicalCsvBytes(): ByteArray = ArcVisibleLooHashing.canonicalCsvBytes(this)
}

/** Deterministic hashing and canonical serialization for ARC visible LOO authority. */
object ArcVisibleLooHashing {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = false
    }

    /** Hashes exact UTF-8 [value]. */
    fun hashText(value: String): String = hashBytes(value.encodeToByteArray())

    /** Hashes exact [bytes]. */
    fun hashBytes(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    /** Returns canonical bytes for [report], including its report hash. */
    fun canonicalReportBytes(report: ArcVisibleLooReport): ByteArray =
        canonicalJson(json.encodeToJsonElement(ArcVisibleLooReport.serializer(), report))
            .encodeToByteArray()

    /** Hashes [report] after excluding its self-referential report hash. */
    fun hashReport(report: ArcVisibleLooReport): String =
        hashCanonicalWithoutField(
            ArcVisibleLooReport.serializer(),
            report,
            "reportHash",
        )

    /** Hashes [row] after excluding its self-referential row hash. */
    fun hashRow(row: ArcVisibleLooRow): String =
        hashCanonicalWithoutField(ArcVisibleLooRow.serializer(), row, "rowHash")

    /** Hashes [row] after excluding its self-referential row hash. */
    fun hashRowInput(row: ArcVisibleLooRowInput): String =
        hashCanonicalWithoutField(ArcVisibleLooRowInput.serializer(), row, "rowHash")

    /** Hashes [candidate] after excluding its self-referential candidate hash. */
    fun hashCandidate(candidate: ArcVisibleLooCandidate): String =
        hashCanonicalWithoutField(ArcVisibleLooCandidate.serializer(), candidate, "candidateHash")

    /**
     * Returns deterministic row-level CSV bytes for the exact benchmark report.
     *
     * The CSV is a package projection of the same hash-bound rows; it is not an independent score
     * authority. Every field needed to replay row outcomes and family provenance is retained.
     *
     * @param report Hash-valid visible LOO report.
     * @return Canonical UTF-8 CSV bytes.
     */
    fun canonicalCsvBytes(report: ArcVisibleLooReport): ByteArray {
        require(report.hasValidHash()) {
            "ARC visible LOO CSV projection requires a hash-valid report"
        }
        return buildString {
            appendLine(
                listOf(
                    "row_id",
                    "task_id",
                    "holdout_index",
                    "input_row_hash",
                    "expected_output_hash",
                    "top1_family",
                    "alternate_family",
                    "top1_output_hash",
                    "alternate_output_hash",
                    "top1_correct",
                    "any_two_correct",
                    "row_hash",
                ).joinToString(","),
            )
            report.rows.forEach { row ->
                appendLine(
                    listOf(
                        row.rowId,
                        row.taskId,
                        row.holdoutIndex.toString(),
                        row.inputRowHash,
                        row.expectedOutputHash,
                        row.selectedCandidateFamilies[0],
                        row.selectedCandidateFamilies[1],
                        row.selectedCandidateOutputHashes[0],
                        row.selectedCandidateOutputHashes[1],
                        row.top1Correct.toString(),
                        row.anyTwoCorrect.toString(),
                        row.rowHash,
                    ).joinToString(",") { value -> escapeCsv(value) },
                )
            }
        }.encodeToByteArray()
    }

    /** Returns canonical JSON with lexicographically sorted object keys. */
    fun canonicalJson(element: JsonElement): String = DeliverableHashing.canonicalJson(element)

    private fun <T> hashCanonicalWithoutField(
        serializer: kotlinx.serialization.KSerializer<T>,
        value: T,
        field: String,
    ): String {
        val encoded = json.encodeToJsonElement(serializer, value)
        return hashText(canonicalJson(JsonObject(encoded.jsonObject.filterKeys { it != field })))
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun escapeCsv(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
}

/**
 * Deterministic fixture factory for the exact 358-row visible LOO contract.
 *
 * The factory creates no provider calls and uses opaque textual outputs so tests can exercise
 * scoring, provenance, hashing, repeatability, and no-improvement behavior locally.
 */
object ArcVisibleLooFixtureFactory {
    /**
     * Creates hash-valid rows with independently configurable top-1 and any-two success counts.
     *
     * @param top1CorrectRows Number of rows where rank one is correct.
     * @param anyTwoCorrectRows Number of rows where either rank is correct.
     * @param rowCount Required row count; must remain 358.
     * @param top1Family Family ID for rank-one candidates.
     * @param alternateFamily Family ID for rank-two candidates.
     * @return Hash-valid visible LOO row inputs in deterministic row-ID order.
     */
    fun rows(
        top1CorrectRows: Int = 0,
        anyTwoCorrectRows: Int = 0,
        rowCount: Int = ARC_VISIBLE_LOO_ROW_COUNT,
        top1Family: String = "family-top1",
        alternateFamily: String = "family-alternate",
    ): List<ArcVisibleLooRowInput> {
        require(rowCount == ARC_VISIBLE_LOO_ROW_COUNT) {
            "ARC visible LOO fixtures must contain exactly $ARC_VISIBLE_LOO_ROW_COUNT rows"
        }
        require(top1CorrectRows in 0..rowCount && anyTwoCorrectRows in 0..rowCount) {
            "ARC visible LOO fixture scores must fit the row count"
        }
        require(top1CorrectRows <= anyTwoCorrectRows) {
            "ARC visible LOO fixture top-1 count cannot exceed any-two count"
        }
        require(top1Family.isNotBlank() && alternateFamily.isNotBlank()) {
            "ARC visible LOO fixture family IDs must not be blank"
        }
        return (0 until rowCount).map { index ->
            val expected = "expected-output-$index"
            val rankOneCorrect = index < top1CorrectRows
            val anyTwoCorrect = index < anyTwoCorrectRows
            val rankOneOutput = if (rankOneCorrect) expected else "wrong-top1-$index"
            val rankTwoOutput = if (!rankOneCorrect && anyTwoCorrect) {
                expected
            } else {
                "wrong-alternate-$index"
            }
            ArcVisibleLooRowInput(
                rowId = "row-${index.toString().padStart(3, '0')}",
                taskId = "fixture-task-${index / 10}",
                holdoutIndex = index % 10,
                expectedOutput = expected,
                candidates = listOf(
                    ArcVisibleLooCandidate(1, top1Family, rankOneOutput).withComputedHash(),
                    ArcVisibleLooCandidate(2, alternateFamily, rankTwoOutput).withComputedHash(),
                ),
            ).withComputedHash()
        }
    }
}

private fun String.isArcSha256(): Boolean = matches(SHA256_PATTERN)

private fun constantTimeEquals(expected: String, actual: String): Boolean =
    MessageDigest.isEqual(expected.encodeToByteArray(), actual.encodeToByteArray())
