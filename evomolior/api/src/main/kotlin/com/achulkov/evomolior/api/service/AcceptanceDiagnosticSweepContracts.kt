package com.achulkov.evomolior.api.service

import kotlinx.serialization.Serializable

/** Closed late-publication gate catalog evaluated by the non-accepting diagnostic sweep. */
@Serializable
enum class AcceptanceDiagnosticGate {
    FINAL_ROUND_AUTHORITY,
    EXACT_GRADER_QUORUM,
    SOURCE_GROUP_RESOLUTION,
    FINAL_CANDIDATE_AUTHORITY,
    RUN_LEVEL_SEALED_TESTS,
    DIAGNOSTIC_WRITER,
    OUTPUT_CONTRACT,
    COMPLETE_SEALED_TESTS,
    SOURCE_LINEAGE,
    COST_RESERVATIONS,
    FINAL_WRITER_DEATH_NOTE,
    NON_DAEMON_QUIESCENCE,
    RUN_METRICS_CATALOG,
    APPENDIX_B_METRIC_POLICY,
    ARCHIVE_BOUNDARY,
    NORMAL_ARCHIVE_PUBLICATION,
    TERMINAL_ACCEPTANCE
}

/** Result class for one independently evaluated diagnostic gate. */
@Serializable
enum class AcceptanceDiagnosticGateStatus {
    PASS,
    FAIL,
    BLOCKED_BY
}

/**
 * Public, bounded outcome for one diagnostic gate.
 *
 * @param gate Evaluated gate.
 * @param status Pass, failure, or explicit prerequisite block.
 * @param summary Public diagnostic without private fixtures or grader narrative.
 * @param blockedBy Exact prerequisite gates when [status] is [AcceptanceDiagnosticGateStatus.BLOCKED_BY].
 * @param evidenceRefs Public authority references supporting the result.
 */
@Serializable
data class AcceptanceDiagnosticGateResult(
    val gate: AcceptanceDiagnosticGate,
    val status: AcceptanceDiagnosticGateStatus,
    val summary: String,
    val blockedBy: List<AcceptanceDiagnosticGate> = emptyList(),
    val evidenceRefs: List<String> = emptyList()
) {
    init {
        require(summary.isNotBlank() && summary.length <= MAX_SUMMARY_CHARS) {
            "Acceptance diagnostic summary must be non-blank and bounded"
        }
        require(blockedBy == blockedBy.distinct().sortedBy(AcceptanceDiagnosticGate::ordinal)) {
            "Acceptance diagnostic prerequisites must be sorted and unique"
        }
        require(
            (status == AcceptanceDiagnosticGateStatus.BLOCKED_BY) == blockedBy.isNotEmpty()
        ) {
            "Only blocked diagnostic gates may carry prerequisite gates"
        }
        require(
            evidenceRefs == evidenceRefs.distinct().sorted() &&
                evidenceRefs.size <= MAX_EVIDENCE_REFS &&
                evidenceRefs.none { it.isBlank() || it.length > MAX_EVIDENCE_REF_CHARS }
        ) {
            "Acceptance diagnostic evidence references must be bounded, sorted, and unique"
        }
    }

    companion object {
        private const val MAX_SUMMARY_CHARS = 512
        private const val MAX_EVIDENCE_REFS = 32
        private const val MAX_EVIDENCE_REF_CHARS = 256
    }
}

/**
 * Complete aggregate result of a late-publication diagnostic sweep.
 *
 * This record is non-accepting by construction and is valid only inside a recovery bundle.
 *
 * @param runId Owning run.
 * @param acceptanceProfile Exact acceptance profile.
 * @param completedRoundCount Number of completed rounds.
 * @param sourceGroupId Observed source group, never an accepted winner.
 * @param results Exactly one result for every [AcceptanceDiagnosticGate] in canonical order.
 * @param createdAtMs Diagnostic completion time.
 */
@Serializable
data class AcceptanceDiagnosticSweepReport(
    val schemaVersion: String = SCHEMA_VERSION,
    val runId: String,
    val accepted: Boolean = false,
    val acceptedWinnerId: String? = null,
    val acceptanceProfile: String,
    val completedRoundCount: Int,
    val sourceGroupId: String?,
    val results: List<AcceptanceDiagnosticGateResult>,
    val createdAtMs: Long
) {
    init {
        require(schemaVersion == SCHEMA_VERSION && runId.isNotBlank()) {
            "Acceptance diagnostic identity is invalid"
        }
        require(!accepted && acceptedWinnerId == null) {
            "Acceptance diagnostic authority cannot claim an accepted winner"
        }
        require(acceptanceProfile.isNotBlank() && completedRoundCount > 0) {
            "Acceptance diagnostic scope is incomplete"
        }
        require(
            results.map(AcceptanceDiagnosticGateResult::gate) ==
                AcceptanceDiagnosticGate.entries
        ) {
            "Acceptance diagnostic report must contain every gate exactly once in canonical order"
        }
    }

    companion object {
        /** Stable diagnostic-report schema identifier. */
        const val SCHEMA_VERSION = "acceptance-diagnostic-sweep-v1"

        /** Canonical recovery-bundle path. */
        const val BUNDLE_PATH = "recovery/acceptance-diagnostic-sweep-v1.json"
    }
}
