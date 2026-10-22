package com.achulkov.evomolior.substrate.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.achulkov.evomolior.substrate.db.entity.CostLedgerEntryEntity

/** SQL aggregate used to project finalized provider/tool cost without loading every event. */
data class CostLedgerAggregateRow(
    val finalizedEntryCount: Long,
    val unknownMonetaryEntryCount: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val knownProviderCostUsd: Double,
    val knownToolCostUsd: Double,
    val toolComputeMillis: Long,
    val wallTimeMillis: Long
)

/** Atomic append-only persistence and cumulative-cap enforcement for provider/tool cost. */
@Dao
interface CostLedgerDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: CostLedgerEntryEntity)

    @Query("SELECT * FROM cost_ledger_entries WHERE entryId = :entryId LIMIT 1")
    suspend fun byId(entryId: String): CostLedgerEntryEntity?

    @Query(
        """
        SELECT * FROM cost_ledger_entries
        WHERE runId = :runId
        ORDER BY occurredAtMs ASC, entryId ASC
        """
    )
    suspend fun entriesForRun(runId: String): List<CostLedgerEntryEntity>

    @Query(
        """
        SELECT * FROM cost_ledger_entries
        WHERE reservationId = :reservationId AND kind = 'RESERVED'
        LIMIT 1
        """
    )
    suspend fun reservation(reservationId: String): CostLedgerEntryEntity?

    @Query(
        """
        SELECT * FROM cost_ledger_entries
        WHERE reservationId = :reservationId AND kind IN ('FINALIZED', 'RELEASED')
        LIMIT 1
        """
    )
    suspend fun terminal(reservationId: String): CostLedgerEntryEntity?

    @Query(
        """
        SELECT * FROM cost_ledger_entries
        WHERE runId = :runId AND kind = 'PUBLICATION_CLOSED'
        LIMIT 1
        """
    )
    suspend fun publicationClosure(runId: String): CostLedgerEntryEntity?

    @Query(
        """
        SELECT COALESCE(SUM(knownTotalUsd), 0.0) FROM cost_ledger_entries
        WHERE runId = :runId AND kind = 'FINALIZED'
        """
    )
    suspend fun finalizedKnownUsd(runId: String): Double

    @Query(
        """
        SELECT COUNT(*) FROM cost_ledger_entries
        WHERE runId = :runId AND kind = 'FINALIZED' AND knownTotalUsd IS NULL
        """
    )
    suspend fun unknownFinalizedCount(runId: String): Long

    @Query(
        """
        SELECT COALESCE(SUM(r.reservedMaximumUsd), 0.0)
        FROM cost_ledger_entries r
        WHERE r.runId = :runId AND r.kind = 'RESERVED'
          AND (:excludedReservationId IS NULL OR r.reservationId != :excludedReservationId)
          AND NOT EXISTS (
              SELECT 1 FROM cost_ledger_entries terminal
              WHERE terminal.reservationId = r.reservationId
                AND terminal.kind IN ('FINALIZED', 'RELEASED')
          )
        """
    )
    suspend fun activeReservedUsd(
        runId: String,
        excludedReservationId: String?
    ): Double

    @Query(
        """
        SELECT COUNT(*)
        FROM cost_ledger_entries r
        WHERE r.runId = :runId AND r.kind = 'RESERVED'
          AND NOT EXISTS (
              SELECT 1 FROM cost_ledger_entries terminal
              WHERE terminal.reservationId = r.reservationId
                AND terminal.kind IN ('FINALIZED', 'RELEASED')
          )
        """
    )
    suspend fun activeReservationCount(runId: String): Long

    @Query(
        """
        SELECT
            COUNT(*) AS finalizedEntryCount,
            COALESCE(SUM(CASE WHEN knownTotalUsd IS NULL THEN 1 ELSE 0 END), 0)
                AS unknownMonetaryEntryCount,
            COALESCE(SUM(inputTokens), 0) AS inputTokens,
            COALESCE(SUM(outputTokens), 0) AS outputTokens,
            COALESCE(SUM(providerCostUsd), 0.0) AS knownProviderCostUsd,
            COALESCE(SUM(toolCostUsd), 0.0) AS knownToolCostUsd,
            COALESCE(SUM(toolComputeMillis), 0) AS toolComputeMillis,
            COALESCE(SUM(wallTimeMillis), 0) AS wallTimeMillis
        FROM cost_ledger_entries
        WHERE runId = :runId AND kind = 'FINALIZED'
        """
    )
    suspend fun aggregate(runId: String): CostLedgerAggregateRow

    /** Atomically reserves cumulative capacity without altering action-credit policy. */
    @Transaction
    suspend fun reserve(
        entry: CostLedgerEntryEntity,
        cumulativeCapUsd: Double?
    ): Boolean {
        if (publicationClosure(entry.runId) != null) return false
        if (reservation(entry.reservationId) != null || terminal(entry.reservationId) != null) {
            return false
        }
        if (cumulativeCapUsd != null) {
            if (unknownFinalizedCount(entry.runId) > 0) return false
            val reservedMaximumUsd = entry.reservedMaximumUsd ?: return false
            val nextTotal = finalizedKnownUsd(entry.runId) +
                activeReservedUsd(entry.runId, null) +
                reservedMaximumUsd
            if (nextTotal > cumulativeCapUsd) return false
        }
        insert(entry)
        return true
    }

    /** Atomically closes publication only after all reservations settle, blocking future reserve. */
    @Transaction
    suspend fun closeForPublication(entry: CostLedgerEntryEntity): Boolean {
        if (publicationClosure(entry.runId) != null) return true
        if (activeReservationCount(entry.runId) != 0L) return false
        insert(entry)
        return true
    }

    /** Atomically finalizes exact usage and rechecks the optional cumulative cap. */
    @Transaction
    suspend fun finalize(
        entry: CostLedgerEntryEntity,
        cumulativeCapUsd: Double?
    ): Boolean {
        val reservation = reservation(entry.reservationId) ?: return false
        if (terminal(entry.reservationId) != null) return false
        if (
            reservation.runId != entry.runId ||
            reservation.subjectType != entry.subjectType ||
            reservation.subjectId != entry.subjectId
        ) {
            return false
        }
        if (cumulativeCapUsd != null) {
            val finalCost = entry.knownTotalUsd ?: return false
            if (unknownFinalizedCount(entry.runId) > 0) return false
            val nextTotal = finalizedKnownUsd(entry.runId) +
                activeReservedUsd(entry.runId, entry.reservationId) +
                finalCost
            if (nextTotal > cumulativeCapUsd) return false
        }
        insert(entry)
        return true
    }

    /** Atomically releases an unused reservation. */
    @Transaction
    suspend fun release(entry: CostLedgerEntryEntity): Boolean {
        val reservation = reservation(entry.reservationId) ?: return false
        if (terminal(entry.reservationId) != null) return false
        if (
            reservation.runId != entry.runId ||
            reservation.subjectType != entry.subjectType ||
            reservation.subjectId != entry.subjectId
        ) {
            return false
        }
        insert(entry)
        return true
    }
}
