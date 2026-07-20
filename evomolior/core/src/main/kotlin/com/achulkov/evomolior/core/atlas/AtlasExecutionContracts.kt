@file:Suppress("DEPRECATION")

package com.achulkov.evomolior.core.atlas

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Derived mutable execution state for one node and round.
 *
 * @param nodeId Node being executed.
 * @param round Round represented by this snapshot.
 * @param revealedToGroups Groups allowed to read the hidden payload.
 * @param leases Active and historical leases.
 * @param evidencePacketIds Submitted evidence packet IDs.
 * @param grades Authoritative grade records.
 * @param ownerGroupId Current owner.
 * @param captureState Current capture state.
 * @param yieldHistory Applied yields.
 * @param disclosureLineage Group-local reveal authorizations touching this node.
 */
@Serializable
data class NodeExecutionState(
    val nodeId: NodeId,
    val round: Int,
    val revealedToGroups: Set<AtlasGroupId> = emptySet(),
    val leases: List<NodeLease> = emptyList(),
    val evidencePacketIds: List<String> = emptyList(),
    val grades: List<NodeGrade> = emptyList(),
    val ownerGroupId: AtlasGroupId? = null,
    val captureState: CaptureState = CaptureState.UNOWNED,
    val yieldHistory: List<NodeYield> = emptyList(),
    val disclosureLineage: List<DisclosureLineageEntry> = emptyList()
)

/** Competitive capture state for a node. */
@Serializable
enum class CaptureState { UNOWNED, CONTESTED, CAPTURED, SHARED, FROZEN }

/**
 * Lease of one node to a group or agent.
 *
 * @param leaseId Stable lease ID.
 * @param nodeId Leased node.
 * @param groupId Leasing group.
 * @param agentId Assigned agent, if spawned.
 * @param round Lease round.
 * @param expiresAt Lease expiry.
 * @param replicationSlot Independent replication slot.
 * @param state Lease lifecycle state.
 */
@Serializable
@OptIn(kotlin.time.ExperimentalTime::class)
@Suppress("DEPRECATION")
data class NodeLease(
    val leaseId: String,
    val nodeId: NodeId,
    val groupId: AtlasGroupId,
    val agentId: AtlasAgentId? = null,
    val round: Int,
    val expiresAt: Instant,
    val replicationSlot: Int = 0,
    val state: LeaseState = LeaseState.ACTIVE
)

/** Lifecycle state of a node lease. */
@Serializable
enum class LeaseState { ACTIVE, COMMITTED, EXPIRED, ABANDONED }

/**
 * Authoritative grade attached to node evidence.
 *
 * @param gradeId Stable grade ID.
 * @param groupId Graded group.
 * @param evidencePacketId Graded packet.
 * @param evidencePacketHash Exact graded packet commitment.
 * @param scoreBundleHash Exact aggregated grader bundle commitment.
 * @param scoreVector Complete node-quality vector retained before scalar comparison.
 * @param criteriaHash Frozen node-specific criteria commitment.
 * @param capturePolicyHash Frozen node capture-policy commitment.
 * @param normalizedScore Normalized score.
 * @param verdict Grade validity verdict.
 * @param graderIds Independent graders contributing to the result.
 * @param round Grade round.
 */
@Serializable
data class NodeGrade(
    val gradeId: String,
    val groupId: AtlasGroupId,
    val evidencePacketId: String,
    val evidencePacketHash: String = "",
    val scoreBundleHash: String = "",
    val scoreVector: NodeScoreVector? = null,
    val criteriaHash: String = "",
    val capturePolicyHash: String = "",
    val normalizedScore: Double,
    val verdict: GradeVerdict,
    val graderIds: Set<String>,
    val round: Int
)

/** Validity verdict for an aggregated node grade. */
@Serializable
enum class GradeVerdict { VALID_PASS, VALID_FAIL, INVALID, ABSTAINED }

/**
 * Applied resource yield from node ownership.
 *
 * @param yieldId Stable yield event ID.
 * @param groupId Receiving group.
 * @param rewardClass Applied reward class.
 * @param credits Applied credits.
 * @param capabilityYields Applied capability tokens.
 * @param round Application round.
 * @param scheduleId Delayed schedule identity.
 * @param scheduleHash Exact delayed schedule commitment.
 * @param captureDecisionHash Exact capture decision commitment.
 * @param evidencePacketHash Exact evidence commitment causing the reward.
 * @param scoreBundleHash Exact grade commitment causing the reward.
 */
@Serializable
data class NodeYield(
    val yieldId: String,
    val groupId: AtlasGroupId,
    val rewardClass: RewardClass,
    val credits: Double,
    val capabilityYields: Set<String>,
    val round: Int,
    val scheduleId: String = "",
    val scheduleHash: String = "",
    val captureDecisionHash: String = "",
    val evidencePacketHash: String = "",
    val scoreBundleHash: String = ""
)
