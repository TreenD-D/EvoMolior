@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.agents.factory

import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.agents.features.eventHandler.feature.EventHandlerConfigCommon
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.contract.CostLedgerEntry
import com.achulkov.evomolior.core.contract.CostLedgerEntryKind
import com.achulkov.evomolior.core.contract.CostSubjectType
import com.achulkov.evomolior.core.contract.MonetaryAmount
import com.achulkov.evomolior.core.contract.MonetaryAmountStatus
import com.achulkov.evomolior.substrate.cost.CostLedgerRepository
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Settles each Koog provider attempt against the durable cost ledger.
 *
 * Reservations intentionally carry no invented monetary bound. Capped runs therefore reject a
 * provider attempt before dispatch until an authoritative model-price bound is available.
 */
class ProviderCallCostRecorder(
    private val repository: CostLedgerRepository,
    private val runId: String,
    private val groupId: String?,
    private val agentId: String?,
    private val now: () -> Instant = { Clock.System.now() }
) {
    private val active = ConcurrentHashMap<String, Reservation>()
    private val settlementMutex = Mutex()

    /** Reserves one provider attempt before network dispatch. */
    suspend fun starting(eventId: String, model: LLModel) {
        val subjectId = providerSubjectId(eventId)
        val reservationId = "provider-cost:$subjectId"
        val reservation = Reservation(
            reservationId = reservationId,
            subjectId = subjectId,
            providerId = model.provider.id,
            modelId = model.id,
            startedAt = now()
        )
        check(active.putIfAbsent(eventId, reservation) == null) {
            "Provider call $eventId already has an active cost reservation"
        }
        try {
            repository.reserve(
                entry(
                    entryId = "$reservationId:reserved",
                    reservation = reservation,
                    kind = CostLedgerEntryKind.RESERVED
                )
            )
        } catch (error: Throwable) {
            active.remove(eventId, reservation)
            throw error
        }
    }

    /** Finalizes exact structured token observations from a completed provider response. */
    suspend fun completed(eventId: String, metaInfo: ResponseMetaInfo?) {
        finalize(eventId, metaInfo)
    }

    /** Finalizes failed attempts without inventing unavailable token counts or zero cost. */
    suspend fun failed(eventId: String) {
        finalize(eventId, null)
    }

    /** Finalizes every provider attempt still active when Koog aborts the agent execution. */
    suspend fun failedAll() {
        withContext(NonCancellable) {
            active.keys.toList().forEach { eventId ->
                finalize(eventId, null)
            }
        }
    }

    private suspend fun finalize(eventId: String, metaInfo: ResponseMetaInfo?) =
        withContext(NonCancellable) {
            settlementMutex.withLock {
                val reservation = active[eventId] ?: return@withLock
                val completedAt = now()
                val wallTimeMillis =
                    (completedAt - reservation.startedAt).inWholeMilliseconds.coerceAtLeast(0)
                repository.finalize(
                    entry(
                        entryId = "${reservation.reservationId}:finalized",
                        reservation = reservation,
                        kind = CostLedgerEntryKind.FINALIZED,
                        inputTokens = metaInfo?.inputTokensCount?.toLong(),
                        outputTokens = metaInfo?.outputTokensCount?.toLong(),
                        providerCost = MonetaryAmount(MonetaryAmountStatus.UNKNOWN),
                        wallTimeMillis = wallTimeMillis,
                        occurredAt = completedAt
                    )
                )
                check(active.remove(eventId, reservation)) {
                    "Provider call $eventId cost reservation changed during finalization"
                }
            }
        }

    private fun entry(
        entryId: String,
        reservation: Reservation,
        kind: CostLedgerEntryKind,
        inputTokens: Long? = null,
        outputTokens: Long? = null,
        providerCost: MonetaryAmount = MonetaryAmount(
            MonetaryAmountStatus.NOT_APPLICABLE
        ),
        wallTimeMillis: Long? = null,
        occurredAt: Instant = reservation.startedAt
    ): CostLedgerEntry = CostLedgerEntry(
        entryId = entryId,
        reservationId = reservation.reservationId,
        runId = runId,
        groupId = groupId?.let(::AtlasGroupId),
        agentId = agentId,
        subjectType = CostSubjectType.LLM_PROVIDER_CALL,
        subjectId = reservation.subjectId,
        kind = kind,
        modelProviderId = reservation.providerId,
        modelId = reservation.modelId,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        providerCost = providerCost,
        wallTimeMillis = wallTimeMillis,
        occurredAt = occurredAt
    ).withComputedHash()

    private fun providerSubjectId(eventId: String): String =
        listOfNotNull(agentId, eventId).joinToString(":")

    private data class Reservation(
        val reservationId: String,
        val subjectId: String,
        val providerId: String,
        val modelId: String,
        val startedAt: Instant
    )
}

/** Installs fail-closed reservation and settlement handlers on one Koog agent. */
fun EventHandlerConfigCommon.installProviderCostRecorder(
    recorder: ProviderCallCostRecorder
) {
    onLLMCallStarting { context ->
        recorder.starting(context.eventId, context.model)
    }
    onLLMCallCompleted { context ->
        recorder.completed(
            context.eventId,
            context.response?.metaInfo as? ResponseMetaInfo
        )
    }
    onAgentExecutionFailed {
        recorder.failedAll()
    }
}
