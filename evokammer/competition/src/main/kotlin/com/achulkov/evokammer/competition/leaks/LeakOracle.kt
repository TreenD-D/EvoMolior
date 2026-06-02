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
    @Suppress("unused")
    val leakEvents: SharedFlow<LeakEvent> = _leakEvents.asSharedFlow()

    // Round-scoped quota tracking: sourceGroupId -> leakCount
    private val roundQuotas = mutableMapOf<String, Int>()

    // Round-scoped pair tracking: (sourceGroupId, targetGroupId) -> leakCount
    private val pairQuotas = mutableMapOf<Pair<String, String>, Int>()

    /**
     * B-6: Per-group dynamic ceilings overriding `config.maxLeaksPerGroupPerRound`.
     *
     * Set by `GroupCoordinator.updateMigrationCeiling()` before each round using
     * 15% of the source group's active KB entry count. Falls back to the static
     * `config.maxLeaksPerGroupPerRound` if no ceiling has been registered for a group.
     */
    private val groupCeilings = mutableMapOf<String, Int>()

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
        // B-6: Use the dynamic ceiling if set; fall back to static config default.
        val effectiveCeiling = groupCeilings.getOrDefault(sourceGroupId, config.maxLeaksPerGroupPerRound)
        if (usedQuota >= effectiveCeiling) {
            Napier.w("LeakOracle: quota exceeded for $sourceGroupId (round max=$effectiveCeiling usedQuota=$usedQuota)")
            _leakEvents.emit(LeakEvent.Blocked(sourceGroupId, targetGroupId, packet.leakId, "quota_exceeded"))
            return null
        }

        // Check domain allowlist
        if (!config.allowedDomains.contains(packet.domain)) {
            Napier.w("LeakOracle: domain '${packet.domain}' not in allowlist for $sourceGroupId")
            _leakEvents.emit(LeakEvent.Blocked(sourceGroupId, targetGroupId, packet.leakId, "domain_not_allowed"))
            return null
        }

        val pairKey = sourceGroupId to targetGroupId
        val pairUsed = pairQuotas.getOrDefault(pairKey, 0)
        if (pairUsed >= config.maxMigrationPerPairPerRound) {
            Napier.w(
                "LeakOracle: migration ceiling exceeded for $sourceGroupId -> $targetGroupId " +
                    "(pair max=${config.maxMigrationPerPairPerRound} used=$pairUsed)"
            )
            _leakEvents.emit(
                LeakEvent.Blocked(sourceGroupId, targetGroupId, packet.leakId, "migration_ceiling_exceeded")
            )
            return null
        }

        roundQuotas[sourceGroupId] = usedQuota + 1
        pairQuotas[pairKey] = pairUsed + 1

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
        pairQuotas.clear()
        // B-6: Ceilings are NOT cleared on round start; they persist until updated by
        // GroupCoordinator.updateMigrationCeiling() at the next round's spawn phase.
        Napier.d("LeakOracle: quotas reset for new round")
    }

    /**
     * B-6: Sets a dynamic per-group leak ceiling for the current round.
     *
     * Computed by the orchestration layer as `ceil(kbEntryCount * 0.15)` (minimum: 1).
     * Overrides `config.maxLeaksPerGroupPerRound` for [groupId] until the next call.
     *
     * @param groupId The source group ID whose ceiling is being updated.
     * @param ceiling The maximum number of leak events allowed from this group per round.
     */
    fun patchGroupCeiling(groupId: String, ceiling: Int) {
        val effectiveCeiling = maxOf(1, ceiling)  // always allow at least 1 leak
        groupCeilings[groupId] = effectiveCeiling
        Napier.d("LeakOracle: migration ceiling for $groupId set to $effectiveCeiling")
    }

    /** Returns the active ceiling for [groupId], or the static default if none has been patched. */
    fun ceilingFor(groupId: String): Int = groupCeilings.getOrDefault(groupId, config.maxLeaksPerGroupPerRound)

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
    val allowedDomains: Set<String> = setOf("general", "tools", "planning", "research"),
    /** Maximum number of leaks allowed from one source-target pair per round. */
    val maxMigrationPerPairPerRound: Int = 3
)

/** Events emitted by the [LeakOracle]. */
sealed class LeakEvent {
    data class Sent(val from: String, val to: String, val leakId: String) : LeakEvent()
    data class Blocked(val from: String, val to: String, val leakId: String, val reason: String) : LeakEvent()
    data class Corrupted(val from: String, val to: String, val leakId: String) : LeakEvent()
}
