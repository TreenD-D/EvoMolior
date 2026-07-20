package com.achulkov.evomolior.substrate.atlas

import com.achulkov.evomolior.core.atlas.AtlasAmendment
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.NodeCaptureDecision
import com.achulkov.evomolior.core.atlas.NodeExecutionState
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeYieldApplication
import com.achulkov.evomolior.core.atlas.OutlierFitnessRecord
import com.achulkov.evomolior.core.atlas.ShadowNodeLifecycleEvent
import com.achulkov.evomolior.core.grader.GraderAtlas

/** Type of append-only Atlas transaction. */
enum class AtlasMutationType {
    INITIALIZE,
    AMENDMENT,
    LEASE,
    EVIDENCE,
    GRADE,
    OWNERSHIP,
    DISCLOSURE,
    YIELD,
    CAPTURE,
    MIGRATION
}

/**
 * Optimistically guarded execution-state mutation.
 */
data class AtlasExecutionCommitRequest(
    val runId: String,
    val atlasId: String,
    val expectedAtlasHash: String,
    val expectedStateHashes: Map<NodeId, String>,
    val mutationType: String,
    val changedStates: List<NodeExecutionState>,
    val projections: AtlasProjectionSet
)

/**
 * Capture-specific Atlas mutation that atomically appends its audit decision and state snapshot.
 */
data class AtlasCaptureCommitRequest(
    val execution: AtlasExecutionCommitRequest,
    val decision: NodeCaptureDecision
)

/**
 * Yield-specific Atlas mutation that atomically appends its application and execution snapshot.
 */
data class AtlasYieldCommitRequest(
    val execution: AtlasExecutionCommitRequest,
    val application: NodeYieldApplication
)

/**
 * One atomic round-boundary transition across worker, grader, and Outlier authority.
 *
 * @param runId Owning run.
 * @param amendment Accepted worker Atlas amendment.
 * @param nextAtlas Sealed child worker Atlas.
 * @param projections Complete child-version projections.
 * @param nextGraderAtlas Separately sealed child Grader Atlas governing [nextAtlas].
 * @param lifecycleEvents Ordered `TESTED` and `ACCEPTED_AMENDMENT` lifecycle events.
 * @param fitnessRecord Delayed Outlier fitness bound to the same downstream lineage.
 */
data class AtlasAmendmentAuthorityTransitionRequest(
    val runId: String,
    val amendment: AtlasAmendment,
    val nextAtlas: NodeAtlas,
    val projections: AtlasProjectionSet,
    val nextGraderAtlas: GraderAtlas,
    val lifecycleEvents: List<ShadowNodeLifecycleEvent>,
    val fitnessRecord: OutlierFitnessRecord
)

/** Result of an atomic Atlas transaction. */
data class AtlasCommitResult(
    val commitId: String,
    val commitSequence: Long,
    val atlas: NodeAtlas,
    val stateHashes: Map<NodeId, String>,
    val projectionCount: Int
)

/** Typed transaction failure categories. */
enum class AtlasTransactionErrorCode {
    ALREADY_INITIALIZED,
    NOT_INITIALIZED,
    STALE_ATLAS,
    STALE_EXECUTION_STATE,
    MISSING_EXPECTED_STATE,
    UNKNOWN_NODE,
    INVALID_STATE_COVERAGE,
    INVALID_PROJECTION,
    AMENDMENT_NOT_ACCEPTED,
    INVALID_AMENDMENT_LINEAGE,
    RECEIVER_CONTRACT_CHANGED,
    GRADER_ATLAS_MISMATCH,
    OUTLIER_LIFECYCLE_MISMATCH
}

/**
 * Recoverable Atlas transaction rejection.
 *
 * @param code Stable rejection category.
 * @param message Diagnostic detail.
 */
class AtlasTransactionException(
    val code: AtlasTransactionErrorCode,
    message: String
) : IllegalStateException(message)
