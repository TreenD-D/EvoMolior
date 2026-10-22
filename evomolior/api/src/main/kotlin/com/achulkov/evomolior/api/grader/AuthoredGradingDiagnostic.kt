package com.achulkov.evomolior.api.grader

import com.achulkov.evomolior.core.model.DimensionScore
import kotlinx.serialization.Serializable

/** Independent of structural score-bundle validity and downstream aggregation. */
@Serializable
internal enum class AuthoredGateStatus {
    NO_AUTHORED_TESTS,
    PASS,
    OPTIONAL_FAILURE,
    MANDATORY_FAILURE
}

/** Whether the evaluated snapshot is exactly the target committed by the criteria freeze. */
@Serializable
internal enum class AuthoredTargetAuthorityStatus { UNSEALED, MATCHED, MISMATCH }

/** Regex execution errors are distinct from successfully executed failing predicates. */
@Serializable
internal enum class AuthoredTestExecutionStatus { PASS, FAIL, ERROR }

/** Exact predicate execution, including the immutable frozen test identity when available. */
@Serializable
internal data class AuthoredTestDiagnostic(
    val outcome: AuthoredTestOutcome,
    val frozenTestId: String?,
    val status: AuthoredTestExecutionStatus = outcome.status
)

/**
 * Persisted pre-aggregation audit: raw model judgments are never overwritten by gate results.
 * A round canon snapshot is a bounded substrate digest, not final-output validation authority.
 */
@Serializable
internal data class AuthoredGradingDiagnostic(
    val runId: String,
    val roundIndex: Int,
    val graderGroupId: String,
    val targetGroupId: String,
    val targetSnapshotHash: String,
    val criteriaFreezeHash: String?,
    val targetAuthorityStatus: AuthoredTargetAuthorityStatus,
    val tests: List<AuthoredTestDiagnostic>,
    val rawScores: List<DimensionScore>,
    val gatedScores: List<DimensionScore>,
    val schemaVersion: Int = 1,
    val targetKind: String = "ROUND_CANON_SNAPSHOT",
    val finalCandidateValidationStatus: String = "NOT_EVALUATED",
    val aggregationStatus: String = "PRE_AGGREGATION",
    val gateStatus: AuthoredGateStatus = when {
        tests.isEmpty() -> AuthoredGateStatus.NO_AUTHORED_TESTS
        tests.any { it.status != AuthoredTestExecutionStatus.PASS && it.outcome.mandatory } ->
            AuthoredGateStatus.MANDATORY_FAILURE
        tests.any { it.status != AuthoredTestExecutionStatus.PASS } -> AuthoredGateStatus.OPTIONAL_FAILURE
        else -> AuthoredGateStatus.PASS
    }
)
