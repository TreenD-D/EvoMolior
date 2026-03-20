package com.achulkov.evokammer.competition.leaks

import com.achulkov.evokammer.core.contract.LeakPacket
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Manages the controlled inter-group knowledge leakage mechanism.
 *
 * The Leak Oracle intercepts [LeakPacket]s submitted via the Disclosure tool and
 * decides whether to relay, redact, or block them based on:
 * - Constitutional allowlist of leak-eligible document types (domains)
 * - Current leak quota per group per round
 * - Corruption probability (adds random noise to leaked content)
 *
 * The oracle is stateless across runs but tracks quotas within a round.
 */
class LeakOracle(
    private val config: LeakOracleConfig
) {

    private val _leakEvents = MutableSharedFlow<LeakEvent>(extraBufferCapacity = 256)

    /** Observable stream of all leak events (sent, blocked, corrupted). */
    val leakEvents: SharedFlow<LeakEvent> = _leakEvents.asSharedFlow()

    // Round-scoped quota tracking: sourceGroupId -> leakCount
    private val roundQuotas = mutableMapOf<String, Int>()

    /**
     * Processes a [LeakPacket] from [sourceGroupId] destined for [targetGroupId].
     *
     * Returns the (possibly corrupted) packet to relay, or null if blocked.
     */
    suspend fun process(
        sourceGroupId: String,
        targetGroupId: String,
        packet: LeakPacket
    ): LeakPacket? {
        val usedQuota = roundQuotas.getOrDefault(sourceGroupId, 0)
        if (usedQuota >= config.maxLeaksPerGroupPerRound) {
            Napier.w("LeakOracle: quota exceeded for $sourceGroupId (round max=${config.maxLeaksPerGroupPerRound})")
            _leakEvents.emit(LeakEvent.Blocked(sourceGroupId, targetGroupId, packet.leakId, "quota_exceeded"))
            return null
        }

        // Check domain allowlist
        if (!config.allowedDomains.contains(packet.domain)) {
            Napier.w("LeakOracle: domain '${packet.domain}' not in allowlist for $sourceGroupId")
            _leakEvents.emit(LeakEvent.Blocked(sourceGroupId, targetGroupId, packet.leakId, "domain_not_allowed"))
            return null
        }

        roundQuotas[sourceGroupId] = usedQuota + 1

        // Apply corruption
        return if (Math.random() < config.corruptionProbability) {
            Napier.d("LeakOracle: corrupting leak from $sourceGroupId -> $targetGroupId leakId=${packet.leakId}")
            val corrupted = corruptContent(packet.content)
            _leakEvents.emit(LeakEvent.Corrupted(sourceGroupId, targetGroupId, packet.leakId))
            packet.copy(content = corrupted, isCorrupted = true)
        } else {
            _leakEvents.emit(LeakEvent.Sent(sourceGroupId, targetGroupId, packet.leakId))
            packet
        }
    }

    /** Resets quotas at the start of each round. */
    fun onRoundStart() {
        roundQuotas.clear()
        Napier.d("LeakOracle: quotas reset for new round")
    }

    private fun corruptContent(original: String): String {
        if (original.length < 20) return original.reversed()
        val cutPoint = (original.length * 0.3).toInt()
        return original.take(cutPoint) + "[REDACTED-NOISE]" + original.drop(cutPoint + original.length / 3)
    }
}

/** Configuration for the [LeakOracle]. */
data class LeakOracleConfig(
    /** Maximum number of leaks a single group can send per round. */
    val maxLeaksPerGroupPerRound: Int = 3,
    /** Probability (0.0–1.0) that a leak packet's content is corrupted before delivery. */
    val corruptionProbability: Double = 0.15,
    /** Knowledge domains eligible for leaking (e.g. "general", "tools", "planning"). */
    val allowedDomains: Set<String> = setOf("general", "tools", "planning", "research")
)

/** Events emitted by the [LeakOracle]. */
sealed class LeakEvent {
    data class Sent(val from: String, val to: String, val leakId: String) : LeakEvent()
    data class Blocked(val from: String, val to: String, val leakId: String, val reason: String) : LeakEvent()
    data class Corrupted(val from: String, val to: String, val leakId: String) : LeakEvent()
}
