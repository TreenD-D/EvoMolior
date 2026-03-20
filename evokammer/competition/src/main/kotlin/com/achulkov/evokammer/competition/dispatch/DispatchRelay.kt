package com.achulkov.evokammer.competition.dispatch

import com.achulkov.evokammer.competition.leaks.LeakOracle
import com.achulkov.evokammer.core.contract.LeakPacket
import io.github.aakira.napier.Napier
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Routes inter-group messages through the ring topology.
 *
 * The EvoKammer spec defines a ring where each group has exactly two neighbours.
 * Messages may only be sent to adjacent groups (one hop left or right).
 * Leak packets are forwarded through [LeakOracle] before delivery.
 *
 * @param groupIds Ordered list of group IDs defining the ring topology.
 * @param leakOracle Oracle that decides whether/how to relay each [LeakPacket].
 */
class DispatchRelay(
    private val groupIds: List<String>,
    private val leakOracle: LeakOracle
) {

    init {
        require(groupIds.size >= 2) { "Ring topology requires at least 2 groups, got ${groupIds.size}" }
    }

    // Per-group inbound channel
    private val inboundChannels: Map<String, Channel<DispatchMessage>> =
        groupIds.associateWith { Channel(capacity = Channel.UNLIMITED) }

    /** Emits messages delivered to [groupId]. */
    fun inboundFor(groupId: String): Flow<DispatchMessage> =
        inboundChannels[groupId]?.receiveAsFlow()
            ?: error("Unknown groupId: $groupId")

    /**
     * Sends a [DispatchMessage] from [sourceGroupId] to its ring neighbours.
     *
     * @param sourceGroupId Originating group.
     * @param message The message to relay.
     */
    suspend fun dispatch(sourceGroupId: String, message: DispatchMessage) {
        val neighbours = neighboursOf(sourceGroupId)
        for (targetId in neighbours) {
            val finalMessage = if (message is DispatchMessage.Leak) {
                val relayedPacket = leakOracle.process(sourceGroupId, targetId, message.packet) ?: continue
                message.copy(packet = relayedPacket)
            } else {
                message
            }
            Napier.d("DispatchRelay: $sourceGroupId -> $targetId type=${finalMessage::class.simpleName}")
            inboundChannels[targetId]?.send(finalMessage)
        }
    }

    /** Returns the two ring-adjacent group IDs for [groupId]. */
    fun neighboursOf(groupId: String): List<String> {
        val idx = groupIds.indexOf(groupId)
        require(idx >= 0) { "Unknown groupId: $groupId" }
        val left = groupIds[(idx - 1 + groupIds.size) % groupIds.size]
        val right = groupIds[(idx + 1) % groupIds.size]
        return if (left == right) listOf(left) else listOf(left, right)
    }
}

/** A message dispatched across the ring topology. */
sealed class DispatchMessage {
    /** A structured leak packet (KB entry, tool spec, plan summary). */
    data class Leak(val packet: LeakPacket) : DispatchMessage()

    /** A plain inter-group text message (e.g. trade offer, provocation). */
    data class Text(val content: String, val metadata: Map<String, String> = emptyMap()) : DispatchMessage()
}
