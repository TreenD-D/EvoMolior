package com.achulkov.evomolior.competition.dispatch

import com.achulkov.evomolior.competition.leaks.LeakOracle
import com.achulkov.evomolior.core.contract.GroupTopologyKind
import com.achulkov.evomolior.core.contract.LeakPacket
import io.github.aakira.napier.Napier
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Routes inter-group messages through the configured topology.
 *
 * The EvoMolior spec defines a ring where each group has exactly two neighbours, but
 * the relay can also model star and small-world variants for experiments.
 * Leak packets require [leakTransformer] and are transformed before [LeakOracle] processing.
 * Missing transformation capability fails closed: raw leak content is never delivered.
 *
 * @param groupIds Ordered list of group IDs defining the topology.
 * @param leakOracle Oracle that decides whether/how to relay each [LeakPacket].
 * @param topologyAdaptationConfig Optional trust-aware rewiring policy. When present, the
 *   relay prunes low-trust edges and refreshes a cached topology snapshot on demand.
 * @param trustScorer In-memory trust tracker for per-recipient dispatch trust.
 * @param onDelivered Optional callback invoked after a message is delivered to a target inbox.
 */
class DispatchRelay(
    private val groupIds: List<String>,
    private val leakOracle: LeakOracle,
    private val topologyKind: GroupTopologyKind = GroupTopologyKind.RING,
    private val topologyAdaptationConfig: DispatchTopologyAdaptationConfig? = null,
    private val leakTransformer: (suspend (LeakPacket) -> LeakPacket)? = null,
    private val trustScorer: DispatchTrustScoring = DispatchTrustScoring(),
    private val onDelivered: (suspend (sourceGroupId: String, targetGroupId: String, message: DispatchMessage, trustScore: Double) -> Unit)? = null
) {

    init {
        require(groupIds.isNotEmpty()) { "Dispatch topology requires at least 1 group" }
    }

    // Per-group inbound channel.
    private val inboundChannels: Map<String, Channel<DispatchMessage>> =
        groupIds.associateWith { Channel(capacity = Channel.UNLIMITED) }
    private val activeNeighboursByGroup = groupIds.associateWith { computeBaseNeighboursFor(it) }.toMutableMap()

    /** Emits messages delivered to [groupId]. */
    fun inboundFor(groupId: String): Flow<DispatchMessage> =
        inboundChannels[groupId]?.receiveAsFlow()
            ?: error("Unknown groupId: $groupId")

    /** Returns the current trust score for [sourceGroupId] in the default scope. */
    fun trustScoreOf(sourceGroupId: String): Double = trustScorer.scoreOf(sourceGroupId)

    /** Returns the current trust score for [sourceGroupId] as seen by [targetGroupId]. */
    fun trustScoreOf(sourceGroupId: String, targetGroupId: String): Double =
        trustScorer.scoreOf(sourceGroupId, targetGroupId)

    /** Applies trust feedback in the default scope. */
    fun recordFeedback(
        sourceGroupId: String,
        accepted: Boolean,
        magnitude: Double = 1.0
    ) {
        trustScorer.applyFeedback(sourceGroupId, accepted, magnitude)
    }

    /** Applies trust feedback for a specific target/source pair. */
    fun recordFeedback(
        sourceGroupId: String,
        targetGroupId: String,
        accepted: Boolean,
        magnitude: Double = 1.0
    ) {
        trustScorer.applyFeedback(sourceGroupId, accepted, magnitude, targetGroupId)
    }

    /**
     * Refreshes the cached topology snapshot when trust-aware adaptation is enabled.
     *
     * The relay keeps this separate from trust updates so the coordinator can refresh
     * edges at explicit generation boundaries.
     */
    fun refreshTopology() {
        if (topologyAdaptationConfig == null) return
        groupIds.forEach { groupId ->
            activeNeighboursByGroup[groupId] = computeAdaptedNeighbours(groupId)
        }
    }

    /**
     * Sends a [DispatchMessage] from [sourceGroupId] to its topology neighbours.
     *
     * @param sourceGroupId Originating group.
     * @param message The message to relay.
     */
    suspend fun dispatch(sourceGroupId: String, message: DispatchMessage) {
        val neighbours = neighboursOf(sourceGroupId)
        for (targetId in neighbours) {
            dispatchTo(sourceGroupId, targetId, message)
        }
    }

    /**
     * Sends one message over a single active topology edge.
     *
     * Watchdog leak delivery uses this bounded path so one target request cannot fan out to every
     * neighbour of the selected source.
     */
    suspend fun dispatchTo(sourceGroupId: String, targetGroupId: String, message: DispatchMessage): Boolean {
        require(targetGroupId in neighboursOf(sourceGroupId)) {
            "$targetGroupId is not an active neighbour of $sourceGroupId"
        }
        val sourceTrust = trustScoreOf(sourceGroupId, targetGroupId)
        val finalMessage = if (message is DispatchMessage.Leak) {
            val transformer = leakTransformer
            if (transformer == null) {
                Napier.w(
                    "DispatchRelay: blocked untransformed leak ${message.packet.leakId} " +
                        "$sourceGroupId -> $targetGroupId"
                )
                return false
            }
            val preparedPacket = transformer(message.packet)
            val relayedPacket = leakOracle.process(sourceGroupId, targetGroupId, preparedPacket) ?: return false
            message.copy(packet = relayedPacket)
        } else {
            message
        }
        Napier.d(
            "DispatchRelay: $sourceGroupId -> $targetGroupId type=${finalMessage::class.simpleName} " +
                "trust=${String.format(java.util.Locale.ROOT, "%.2f", sourceTrust)}"
        )
        inboundChannels[targetGroupId]?.send(finalMessage)
        onDelivered?.invoke(sourceGroupId, targetGroupId, finalMessage, sourceTrust)
        return true
    }

    /** Returns the topology-adjacent group IDs for [groupId]. */
    fun neighboursOf(groupId: String): List<String> {
        val idx = groupIds.indexOf(groupId)
        require(idx >= 0) { "Unknown groupId: $groupId" }
        return activeNeighboursByGroup[groupId] ?: computeBaseNeighboursFor(groupId).also {
            activeNeighboursByGroup[groupId] = it
        }
    }

    private fun computeBaseNeighboursFor(groupId: String): List<String> {
        val idx = groupIds.indexOf(groupId)
        require(idx >= 0) { "Unknown groupId: $groupId" }
        return when (topologyKind) {
            GroupTopologyKind.RING -> ringNeighboursOf(idx)
            GroupTopologyKind.STAR -> starNeighboursOf(idx)
            GroupTopologyKind.SMALL_WORLD -> smallWorldNeighboursOf(idx)
        }
    }

    private fun computeAdaptedNeighbours(groupId: String): List<String> {
        val baseNeighbours = computeBaseNeighboursFor(groupId)
        return topologyAdaptationConfig?.let { adaptNeighbours(groupId, baseNeighbours, it) } ?: baseNeighbours
    }

    private fun adaptNeighbours(
        sourceGroupId: String,
        baseNeighbours: List<String>,
        config: DispatchTopologyAdaptationConfig
    ): List<String> {
        val desiredCount = baseNeighbours.size
        if (desiredCount == 0) return emptyList()

        val retained = baseNeighbours.filter { targetGroupId ->
            trustScoreOf(sourceGroupId, targetGroupId) >= config.minimumTrustToRetain
        }
        val nonBaseCandidates = groupIds
            .filterNot { it == sourceGroupId || it in baseNeighbours || it in retained }
            .sortedWith(
                compareByDescending<String> { trustScoreOf(sourceGroupId, it) }
                    .thenBy { groupIds.indexOf(it) }
            )
        val rewired = nonBaseCandidates.take(maxOf(0, desiredCount - retained.size).coerceAtMost(config.maxRewiredNeighbours))
        val fallback = baseNeighbours
            .filterNot { it in retained }
            .sortedWith(
                compareByDescending<String> { trustScoreOf(sourceGroupId, it) }
                    .thenBy { groupIds.indexOf(it) }
            )
        return (retained + rewired + fallback)
            .distinct()
            .take(desiredCount)
    }

    private fun ringNeighboursOf(idx: Int): List<String> {
        if (groupIds.size == 1) return emptyList()
        val left = groupIds[(idx - 1 + groupIds.size) % groupIds.size]
        val right = groupIds[(idx + 1) % groupIds.size]
        return if (left == right) listOf(left) else listOf(left, right)
    }

    private fun starNeighboursOf(idx: Int): List<String> {
        val hubIndex = 0
        return if (idx == hubIndex) {
            groupIds.drop(1)
        } else {
            listOf(groupIds[hubIndex])
        }
    }

    private fun smallWorldNeighboursOf(idx: Int): List<String> {
        val ring = ringNeighboursOf(idx)
        if (groupIds.size < 4) return ring
        val chord = groupIds[(idx + 2) % groupIds.size]
        return (ring + chord).distinct()
    }
}

/** Competition-layer alias for the core dispatch topology adaptation spec. */
typealias DispatchTopologyAdaptationConfig = com.achulkov.evomolior.core.contract.DispatchTopologyAdaptationSpec

/** A message dispatched across the topology. */
sealed class DispatchMessage {
    /** A structured leak packet (KB entry, tool spec, plan summary). */
    data class Leak(val packet: LeakPacket) : DispatchMessage()

    /** A plain inter-group text message (e.g. trade offer, provocation). */
    data class Text(val content: String, val metadata: Map<String, String> = emptyMap()) : DispatchMessage()
}
