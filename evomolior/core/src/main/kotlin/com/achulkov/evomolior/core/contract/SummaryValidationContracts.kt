@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.core.contract

import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.model.Role
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Exact assessment of whether a summary displaced primary contribution work. */
@Serializable
enum class SummaryPrimaryContentAssessment {
    NOT_ASSESSED,
    ABSENT,
    DETECTED
}

/**
 * Immutable validation and retrieval authority for one final summary.
 *
 * Compression remains unavailable unless exact source and output byte counts are both present.
 * Fitness derivation must not synthesize missing values.
 */
@Serializable
data class SummaryValidationRecord(
    val validationId: String,
    val runId: String,
    val groupId: AtlasGroupId,
    val round: Int,
    val summarizerAgentId: String,
    val summarizerRole: Role,
    val finalReferences: List<ContributionAuthorityRef>,
    val validatorContractHash: String,
    val fidelityChecksPassed: Int,
    val fidelityChecksTotal: Int,
    val retrievalReferencesValidated: Int,
    val retrievalReferencesTotal: Int,
    val sourceContentBytes: Long? = null,
    val outputContentBytes: Long? = null,
    val primaryContentAssessment: SummaryPrimaryContentAssessment =
        SummaryPrimaryContentAssessment.NOT_ASSESSED,
    val validatedAt: Instant,
    val recordHash: String = ""
) {
    init {
        require(
            validationId.isNotBlank() && runId.isNotBlank() &&
                groupId.value.isNotBlank() && summarizerAgentId.isNotBlank()
        ) { "Summary-validation identity fields must not be blank" }
        require(round >= 0) { "Summary-validation round must not be negative" }
        require(summarizerRole == Role.Archivist) {
            "Summary validation is restricted to the Archivist/final-summarizer plane"
        }
        require(finalReferences.isNotEmpty() && finalReferences.distinct() == finalReferences) {
            "Summary validation requires unique final-deliverable references"
        }
        require(finalReferences.all {
            it.kind == ContributionAuthorityKind.FINAL_DELIVERABLE_REFERENCE &&
                it.authorityHash != null
        }) { "Summary validation requires exact final-deliverable authority" }
        require(validatorContractHash.isNotBlank()) {
            "Summary validator contract hash must not be blank"
        }
        require(
            fidelityChecksTotal > 0 &&
                fidelityChecksPassed in 0..fidelityChecksTotal
        ) { "Summary fidelity counts are invalid" }
        require(
            retrievalReferencesTotal > 0 &&
                retrievalReferencesValidated in 0..retrievalReferencesTotal
        ) { "Summary retrieval counts are invalid" }
        require((sourceContentBytes == null) == (outputContentBytes == null)) {
            "Summary compression requires both source and output byte counts"
        }
        require(
            sourceContentBytes == null ||
                (sourceContentBytes > 0L && requireNotNull(outputContentBytes) > 0L)
        ) { "Summary compression byte counts must be positive" }
    }

    /** Returns this validation authority with its deterministic self-hash. */
    fun withComputedHash(): SummaryValidationRecord =
        copy(recordHash = AtlasHashing.hashSummaryValidation(this))

    /** Verifies the exact immutable summary-validation commitment. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(recordHash, AtlasHashing.hashSummaryValidation(this))
}
