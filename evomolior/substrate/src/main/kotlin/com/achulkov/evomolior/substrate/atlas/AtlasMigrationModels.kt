package com.achulkov.evomolior.substrate.atlas

import com.achulkov.evomolior.core.atlas.ReceiverContract
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.NodeExecutionState
import com.achulkov.evomolior.core.contract.DeliverableSkeletonTree
import kotlinx.serialization.Serializable

/**
 * Complete legacy state supplied to the one-time Atlas migration.
 */
data class LegacyAtlasSnapshot(
    val atlasId: String,
    val runId: String,
    val receiverContract: ReceiverContract,
    val deliverableTrees: List<DeliverableSkeletonTree>,
    val wip: List<WipProjection>,
    val resources: ResourceProjection,
    val disclosure: DisclosureProjection
)

/**
 * Validated authority state ready for one atomic initial commit.
 */
data class PreparedAtlasMigration(
    val receiverContract: ReceiverContract,
    val atlas: NodeAtlas,
    val executionStates: List<NodeExecutionState>,
    val projections: AtlasProjectionSet
)

/** Stable categories for fail-closed legacy migration diagnostics. */
@Serializable
enum class AtlasMigrationErrorCode {
    RUN_ID_MISMATCH,
    DUPLICATE_NODE_DEFINITION,
    AMBIGUOUS_NODE_MAPPING,
    MISSING_NODE_MAPPING,
    INVALID_DEPENDENCY,
    INVALID_WIP_STATUS,
    INVALID_DISCLOSURE_STAGE,
    INVALID_ATLAS
}

/**
 * One typed failure that prevents lossless Atlas migration.
 */
@Serializable
data class AtlasMigrationError(
    val code: AtlasMigrationErrorCode,
    val source: LegacyNodeSource?,
    val sourceNodeId: String?,
    val scopeId: String?,
    val message: String
)

/**
 * Fail-closed result of compiling legacy node systems into one Atlas.
 */
sealed interface AtlasMigrationResult {
    data class Success(val migration: PreparedAtlasMigration) : AtlasMigrationResult
    data class Failure(val errors: List<AtlasMigrationError>) : AtlasMigrationResult
}
