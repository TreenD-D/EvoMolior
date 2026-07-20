@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.core.contract

import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Runtime work classes accounted independently from product action credits. */
@Serializable
enum class CostSubjectType {
    LLM_PROVIDER_CALL,
    TOOL_EXECUTION,
    SIMULATION
}

/** Append-only lifecycle events for one atomic provider/tool cost reservation. */
@Serializable
enum class CostLedgerEntryKind {
    RESERVED,
    FINALIZED,
    RELEASED
}

/** Whether a monetary amount is known, unavailable, or irrelevant to the subject. */
@Serializable
enum class MonetaryAmountStatus {
    KNOWN,
    UNKNOWN,
    NOT_APPLICABLE
}

/**
 * Typed monetary amount which never equates unavailable provider pricing with zero.
 */
@Serializable
data class MonetaryAmount(
    val status: MonetaryAmountStatus,
    val usd: Double? = null
) {
    init {
        when (status) {
            MonetaryAmountStatus.KNOWN ->
                require(usd != null && usd.isFinite() && usd >= 0.0) {
                    "Known monetary amount must be finite and non-negative"
                }

            MonetaryAmountStatus.UNKNOWN,
            MonetaryAmountStatus.NOT_APPLICABLE ->
                require(usd == null) {
                    "$status monetary amount cannot carry a numeric value"
                }
        }
    }
}

/**
 * One immutable provider/tool cost lifecycle event.
 *
 * Reservations carry [reservedMaximumUsd] when a defensible monetary bound is available.
 * Finalizations carry exact token, monetary, compute, and wall-time observations. Releases carry
 * no invented cost. [entryHash] excludes itself.
 */
@Serializable
data class CostLedgerEntry(
    val entryId: String,
    val reservationId: String,
    val runId: String,
    val groupId: AtlasGroupId?,
    val agentId: String?,
    val subjectType: CostSubjectType,
    val subjectId: String,
    val kind: CostLedgerEntryKind,
    val modelProviderId: String? = null,
    val modelId: String? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val providerCost: MonetaryAmount = MonetaryAmount(
        MonetaryAmountStatus.NOT_APPLICABLE
    ),
    val toolCost: MonetaryAmount = MonetaryAmount(
        MonetaryAmountStatus.NOT_APPLICABLE
    ),
    val toolComputeMillis: Long? = null,
    val wallTimeMillis: Long? = null,
    val reservedMaximumUsd: Double? = null,
    val occurredAt: Instant,
    val entryHash: String = ""
) {
    init {
        require(
            entryId.isNotBlank() && reservationId.isNotBlank() &&
                runId.isNotBlank() && subjectId.isNotBlank()
        ) {
            "Cost ledger identity fields must not be blank"
        }
        require(groupId == null || groupId.value.isNotBlank()) {
            "Cost ledger group ID must be null or non-blank"
        }
        require(agentId == null || agentId.isNotBlank()) {
            "Cost ledger agent ID must be null or non-blank"
        }
        require(inputTokens == null || inputTokens >= 0) {
            "Input token count must not be negative"
        }
        require(outputTokens == null || outputTokens >= 0) {
            "Output token count must not be negative"
        }
        require(toolComputeMillis == null || toolComputeMillis >= 0) {
            "Tool compute duration must not be negative"
        }
        require(wallTimeMillis == null || wallTimeMillis >= 0) {
            "Wall time must not be negative"
        }
        require(
            reservedMaximumUsd == null ||
                reservedMaximumUsd.isFinite() && reservedMaximumUsd >= 0.0
        ) {
            "Reserved maximum cost must be finite and non-negative"
        }
        when (kind) {
            CostLedgerEntryKind.RESERVED -> {
                require(
                    inputTokens == null && outputTokens == null &&
                        toolComputeMillis == null && wallTimeMillis == null
                ) {
                    "Cost reservation cannot claim completed usage"
                }
            }

            CostLedgerEntryKind.FINALIZED -> {
                require(reservedMaximumUsd == null) {
                    "Cost finalization cannot rewrite its reserved maximum"
                }
                require(wallTimeMillis != null) {
                    "Cost finalization requires exact wall time"
                }
                if (subjectType == CostSubjectType.LLM_PROVIDER_CALL) {
                    require(toolCost.status == MonetaryAmountStatus.NOT_APPLICABLE) {
                        "LLM finalization cannot claim tool cost"
                    }
                } else {
                    require(toolComputeMillis != null) {
                        "Tool and simulation finalization require compute duration"
                    }
                    require(providerCost.status == MonetaryAmountStatus.NOT_APPLICABLE) {
                        "Tool finalization cannot claim provider cost"
                    }
                }
            }

            CostLedgerEntryKind.RELEASED -> {
                require(
                    reservedMaximumUsd == null &&
                        inputTokens == null && outputTokens == null &&
                        toolComputeMillis == null && wallTimeMillis == null
                ) {
                    "Released reservations cannot invent usage or cost"
                }
            }
        }
    }

    /** Exact known monetary total, or null when any applicable component is unknown. */
    fun knownTotalUsd(): Double? {
        val applicable = listOf(providerCost, toolCost)
            .filter { it.status != MonetaryAmountStatus.NOT_APPLICABLE }
        return if (applicable.all { it.status == MonetaryAmountStatus.KNOWN }) {
            applicable.sumOf { requireNotNull(it.usd) }
        } else {
            null
        }
    }

    /** Returns this entry with its deterministic self-hash attached. */
    fun withComputedHash(): CostLedgerEntry =
        copy(entryHash = AtlasHashing.hashCostLedgerEntry(this))

    /** Verifies the exact reservation/finalization/release commitment. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(
            entryHash,
            AtlasHashing.hashCostLedgerEntry(this)
        )
}

/** Optional operator/experiment safety cap, independent from action-credit policy. */
@Serializable
data class CostLedgerPolicy(
    val cumulativeCapUsd: Double? = null
) {
    init {
        require(
            cumulativeCapUsd == null ||
                cumulativeCapUsd.isFinite() && cumulativeCapUsd >= 0.0
        ) {
            "Cumulative cost cap must be finite and non-negative"
        }
    }
}

/** Exact read-only aggregate over finalized and active provider/tool cost authority. */
@Serializable
data class CostLedgerSummary(
    val runId: String,
    val finalizedEntryCount: Long,
    val unknownMonetaryEntryCount: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val knownProviderCostUsd: Double,
    val knownToolCostUsd: Double,
    val toolComputeMillis: Long,
    val wallTimeMillis: Long,
    val activeReservedMaximumUsd: Double,
    val activeReservationCount: Long
)
