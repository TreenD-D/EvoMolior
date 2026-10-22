package com.achulkov.evomolior.api.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Exact candidate-time diagnostics, distinct from earlier round snapshots and accepted authority.
 *
 * @param runId Owning run.
 * @param groupId Source group, not accepted-winner authority.
 * @param roundIndex Last completed round.
 * @param relativePath Candidate path.
 * @param contentHash Exact candidate bytes hashed before validation.
 * @param outputContractHash Exact operator output contract hash.
 * @param sealedSuiteHash Active suite identity, or null when none applies.
 * @param publicContractFailures Deterministic output-format violations.
 * @param addressableTestsPassed Tests evaluated against this candidate and passed, null if unavailable.
 * @param addressableTestsFailed Tests evaluated against this candidate and failed, null if unavailable.
 * @param mandatoryFailure Whether candidate-addressable mandatory tests failed.
 * @param evaluatedAtMs Evaluation timestamp.
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
internal data class CandidateOutputValidationRecord(
    val runId: String,
    val groupId: String,
    val roundIndex: Int,
    val relativePath: String,
    val contentHash: String,
    val outputContractHash: String,
    val sealedSuiteHash: String?,
    val publicContractFailures: List<String>,
    val addressableTestsPassed: Int?,
    val addressableTestsFailed: Int?,
    val mandatoryFailure: Boolean?,
    val evaluatedAtMs: Long,
) {
    @EncodeDefault
    val acceptanceGranted: Boolean = false

    companion object {
        const val ENTRY_TYPE = "CANDIDATE_OUTPUT_VALIDATION_V1"
    }
}
