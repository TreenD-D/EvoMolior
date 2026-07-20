package com.achulkov.evomolior.core.contract

import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.model.Role
import kotlinx.serialization.Serializable
import kotlin.math.abs

/** Mutually isolated evolutionary pools used by causal fitness and Legacy selection. */
@Serializable
enum class FitnessPlane {
    WORKER,
    REVIEW,
    CONTROL,
    GRADER,
    SUMMARY
}

/** Exact positive and negative causal terms admitted into plane fitness. */
@Serializable
enum class FitnessComponentKind {
    ACCEPTED_NODE_VALUE,
    DOWNSTREAM_REUSE,
    VERIFIER_PASS,
    INTEGRATION_CONTRIBUTION,
    EVIDENCE_LINEAGE_QUALITY,
    ADOPTED_NOVELTY,
    COST_EFFICIENCY,
    STRUCTURED_NEGATIVE_VALUE,
    UNPLANNED_REDUNDANCY,
    REJECTED_OR_MALFORMED_WRITE,
    UNSUPPORTED_CLAIM,
    WASTED_TOOL_RUN,
    CORRECT_PROMOTION,
    CORRECT_REJECTION,
    LATER_REVIEW_ERROR,
    REVIEW_COST,
    TRUE_POSITIVE_INTERVENTION,
    AVOIDED_LOSS,
    FALSE_POSITIVE_INTERVENTION,
    CONTROL_COST,
    REQUIREMENT_QUALITY,
    CALIBRATION,
    PREDICTIVE_VALIDITY,
    USEFUL_FEEDBACK,
    SUMMARY_FIDELITY,
    SUMMARY_COMPRESSION,
    SUMMARY_RETRIEVAL_VALUE,
    SUMMARY_PRIMARY_CONTENT_PENALTY,
    TOOL_FITNESS,
    OUTLIER_FITNESS
}

/**
 * One numeric fitness term backed by exact graph edges or immutable authority records.
 *
 * At least one authority reference is mandatory. Dashboard values and aggregate group scores
 * cannot be supplied as authority.
 */
@Serializable
data class CausalFitnessComponent(
    val kind: FitnessComponentKind,
    val value: Double,
    val contributionEdgeIds: Set<String> = emptySet(),
    val authorityRefs: Set<ContributionAuthorityRef> = emptySet()
) {
    init {
        require(value.isFinite()) { "Causal fitness component must be finite" }
        require(contributionEdgeIds.all(String::isNotBlank)) {
            "Causal fitness edge IDs must not be blank"
        }
        require(contributionEdgeIds.isNotEmpty() || authorityRefs.isNotEmpty()) {
            "Every causal fitness component requires immutable authority"
        }
    }
}

/**
 * Immutable fitness observation confined to one role plane.
 *
 * [total] must equal the exact component sum. Outliers can receive delayed novelty fitness but
 * are never Legacy-eligible. Genesis Council roles are deterministic run configuration and cannot
 * receive any fitness record.
 */
@Serializable
data class CausalFitnessRecord(
    val recordId: String,
    val runId: String,
    val groupId: AtlasGroupId,
    val round: Int,
    val agentId: String,
    val role: Role,
    val plane: FitnessPlane,
    val components: List<CausalFitnessComponent>,
    val total: Double,
    val legacyEligible: Boolean,
    val recordHash: String = ""
) {
    init {
        require(recordId.isNotBlank() && runId.isNotBlank() && agentId.isNotBlank()) {
            "Causal fitness identity fields must not be blank"
        }
        require(groupId.value.isNotBlank()) { "Causal fitness group ID must not be blank" }
        require(round >= 0) { "Causal fitness round must not be negative" }
        require(!role.isCouncilRole) { "Deterministic Council roles cannot receive fitness" }
        require(role.fitnessPlane() == plane) {
            "Role ${role.name} is not eligible for the ${plane.name} fitness plane"
        }
        require(components.isNotEmpty()) { "Causal fitness requires at least one exact component" }
        require(
            abs(total - components.sumOf(CausalFitnessComponent::value)) <= FITNESS_TOLERANCE
        ) {
            "Causal fitness total must equal its exact component sum"
        }
        require(role != Role.Outlier || !legacyEligible) {
            "Outliers never receive Legacy eligibility"
        }
    }

    /** Returns this record with its deterministic self-hash attached. */
    fun withComputedHash(): CausalFitnessRecord =
        copy(recordHash = AtlasHashing.hashCausalFitnessRecord(this))

    /** Verifies the exact plane, component, and authority commitment. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(
            recordHash,
            AtlasHashing.hashCausalFitnessRecord(this)
        )

    companion object {
        private const val FITNESS_TOLERANCE = 1e-9
    }
}

/** Returns the only causal fitness plane available to this role. */
fun Role.fitnessPlane(): FitnessPlane = when (this) {
    Role.Researcher,
    Role.Builder,
    Role.Planner,
    Role.Educator,
    Role.Connector,
    Role.Outlier -> FitnessPlane.WORKER

    Role.Critic -> FitnessPlane.REVIEW

    Role.Inspector,
    Role.Auditor,
    Role.PerspectiveTaker,
    Role.MetaPlanner -> FitnessPlane.CONTROL

    Role.GraderResearcher,
    Role.GraderBuilder,
    Role.GraderCritic -> FitnessPlane.GRADER

    Role.Archivist -> FitnessPlane.SUMMARY

    else -> error("Deterministic Council role ${name} has no fitness plane")
}
