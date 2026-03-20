package com.achulkov.evokammer.core.contract

import kotlinx.serialization.Serializable

/**
 * An intelligence leak packet delivered to a group's IntelligenceInbox.
 *
 * Agents read leak packets via ReadKBTool when querying the "intelligence_leaks" domain.
 * The source group is NEVER included in the packet — privacy is enforced by the
 * DifferentialPrivacyTransform before this packet is created.
 *
 * @param leakId Unique identifier.
 * @param content The privacy-transformed, summarised knowledge extract.
 * @param domain The knowledge domain this leak is from (used by agents for filtering).
 * @param recipientGroupId The group that should receive this packet.
 * @param deliveredAtGeneration The generation at which this was delivered.
 * @param runId The run this leak belongs to.
 * @param isCorrupted True if the LeakOracle corrupted the content before delivery.
 */
@Serializable
data class LeakPacket(
    val leakId: String,
    val content: String,
    val domain: String,
    val recipientGroupId: String,
    val deliveredAtGeneration: Int,
    val runId: String,
    val isCorrupted: Boolean = false
)
