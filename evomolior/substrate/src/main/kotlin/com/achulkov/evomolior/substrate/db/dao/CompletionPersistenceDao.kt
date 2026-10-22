package com.achulkov.evomolior.substrate.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.achulkov.evomolior.substrate.db.entity.DecisionPackageEntity
import com.achulkov.evomolior.substrate.db.entity.DeliverableBlueprintEntity
import com.achulkov.evomolior.substrate.db.entity.InterimDeliverableSnapshotEntity
import com.achulkov.evomolior.substrate.db.entity.PublicationOutcomeEventEntity
import com.achulkov.evomolior.substrate.db.entity.RoundPhaseEventEntity
import com.achulkov.evomolior.substrate.db.entity.ToolRepairAttemptEntity
import com.achulkov.evomolior.substrate.db.entity.ToolRepairDiagnosticEntity

/** Append-only DAO for the bounded completion persistence slice. */
@Dao
interface CompletionPersistenceDao {
    /** Inserts one immutable round phase event, ignoring only an exact primary-key replay. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRoundPhaseEvent(value: RoundPhaseEventEntity): Long

    /** Returns one round phase event by its immutable event hash. */
    @Query("SELECT * FROM round_phase_events WHERE eventHash = :eventHash")
    suspend fun roundPhaseEvent(eventHash: String): RoundPhaseEventEntity?

    /** Returns the exact ordered phase history for one run round. */
    @Query(
        """
        SELECT * FROM round_phase_events
        WHERE runId = :runId AND round = :round
        ORDER BY sequence ASC
        """
    )
    suspend fun roundPhaseHistory(runId: String, round: Int): List<RoundPhaseEventEntity>

    /** Returns the current immutable phase head for one run round. */
    @Query(
        """
        SELECT * FROM round_phase_events
        WHERE runId = :runId AND round = :round
        ORDER BY sequence DESC
        LIMIT 1
        """
    )
    suspend fun latestRoundPhaseEvent(runId: String, round: Int): RoundPhaseEventEntity?

    /**
     * Atomically appends a phase event after the repository has checked its expected head.
     *
     * @param value Exact immutable round phase event.
     * @param expectedPreviousHash Hash that must still identify the round head.
     * @return The exact persisted immutable event.
     */
    @Transaction
    suspend fun appendRoundPhaseEvent(
        value: RoundPhaseEventEntity,
        expectedPreviousHash: String?
    ): RoundPhaseEventEntity {
        roundPhaseEvent(value.eventHash)?.let { existing ->
            require(existing == value) { "Round phase event hash is immutable" }
            return existing
        }
        check(latestRoundPhaseEvent(value.runId, value.round)?.eventHash == expectedPreviousHash) {
            "Round phase history changed before append"
        }
        insertRoundPhaseEvent(value)
        return requireNotNull(roundPhaseEvent(value.eventHash)) {
            "Round phase append conflicted with another immutable authority"
        }.also { persisted ->
            require(persisted == value) { "Round phase event authority is immutable" }
        }
    }

    /** Inserts one immutable tool-repair diagnostic, ignoring only a conflicting identity. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDiagnostic(value: ToolRepairDiagnosticEntity): Long

    /** Returns one diagnostic by its stable identity. */
    @Query("SELECT * FROM tool_repair_diagnostics WHERE diagnosticId = :diagnosticId")
    suspend fun diagnostic(diagnosticId: String): ToolRepairDiagnosticEntity?

    /** Returns every persisted diagnostic in one run/group scope in deterministic identity order. */
    @Query(
        """
        SELECT * FROM tool_repair_diagnostics
        WHERE runId = :runId AND groupId = :groupId
        ORDER BY createdRound ASC, diagnosticId ASC
        """
    )
    suspend fun diagnosticsForScope(
        runId: String,
        groupId: String
    ): List<ToolRepairDiagnosticEntity>

    /** Returns diagnostics visible to one opportunity by [round], in deterministic order. */
    @Query(
        """
        SELECT * FROM tool_repair_diagnostics
        WHERE runId = :runId
          AND groupId = :groupId
          AND opportunityId = :opportunityId
          AND visibleFromRound <= :round
        ORDER BY visibleFromRound ASC, createdRound ASC, diagnosticId ASC
        """
    )
    suspend fun visibleDiagnostics(
        runId: String,
        groupId: String,
        opportunityId: String,
        round: Int
    ): List<ToolRepairDiagnosticEntity>

    /** Inserts one immutable bounded repair attempt, ignoring only a conflicting identity. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAttempt(value: ToolRepairAttemptEntity): Long

    /** Returns one repair attempt by its stable identity. */
    @Query("SELECT * FROM tool_repair_attempts WHERE attemptId = :attemptId")
    suspend fun attempt(attemptId: String): ToolRepairAttemptEntity?

    /** Returns the durable attempt count for one opportunity lineage. */
    @Query("SELECT COUNT(*) FROM tool_repair_attempts WHERE opportunityId = :opportunityId")
    suspend fun attemptCount(opportunityId: String): Int

    /** Returns one opportunity's attempt lineage in deterministic order. */
    @Query(
        """
        SELECT * FROM tool_repair_attempts
        WHERE opportunityId = :opportunityId
        ORDER BY round ASC, attemptId ASC
        """
    )
    suspend fun attempts(opportunityId: String): List<ToolRepairAttemptEntity>

    /**
     * Atomically appends one attempt while enforcing the opportunity lineage bound.
     *
     * @param value Exact immutable attempt.
     * @param maximumAttempts Maximum durable attempts permitted for the opportunity.
     * @return Exact persisted attempt.
     */
    @Transaction
    suspend fun appendAttemptBounded(
        value: ToolRepairAttemptEntity,
        maximumAttempts: Int
    ): ToolRepairAttemptEntity {
        attempt(value.attemptId)?.let { existing ->
            require(existing == value) { "Tool-repair attempt identity is immutable" }
            return existing
        }
        require(attemptCount(value.opportunityId) < maximumAttempts) {
            "Tool-repair opportunity is bounded to $maximumAttempts attempts"
        }
        insertAttempt(value)
        return requireNotNull(attempt(value.attemptId)) {
            "Tool-repair attempt append conflicted with another immutable authority"
        }.also { persisted ->
            require(persisted == value) { "Tool-repair attempt identity is immutable" }
        }
    }

    /** Inserts one immutable deliverable blueprint, ignoring only a conflicting identity. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBlueprint(value: DeliverableBlueprintEntity): Long

    /** Returns one blueprint by stable identity. */
    @Query("SELECT * FROM deliverable_blueprints WHERE blueprintId = :blueprintId")
    suspend fun blueprint(blueprintId: String): DeliverableBlueprintEntity?

    /** Inserts one immutable interim snapshot, ignoring only a conflicting identity. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSnapshot(value: InterimDeliverableSnapshotEntity): Long

    /** Returns the exact snapshot for one run/group/round authority. */
    @Query(
        """
        SELECT * FROM interim_deliverable_snapshots
        WHERE runId = :runId AND groupId = :groupId AND round = :round
        LIMIT 1
        """
    )
    suspend fun snapshot(
        runId: String,
        groupId: String,
        round: Int
    ): InterimDeliverableSnapshotEntity?

    /** Returns all sealed snapshots for one group in round order. */
    @Query(
        """
        SELECT * FROM interim_deliverable_snapshots
        WHERE runId = :runId AND groupId = :groupId
        ORDER BY round ASC
        """
    )
    suspend fun snapshots(runId: String, groupId: String): List<InterimDeliverableSnapshotEntity>

    /** Inserts one immutable receiver package, ignoring only a conflicting identity. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDecisionPackage(value: DecisionPackageEntity): Long

    /** Returns one receiver package by stable identity. */
    @Query("SELECT * FROM decision_packages WHERE packageId = :packageId")
    suspend fun decisionPackage(packageId: String): DecisionPackageEntity?

    /** Returns all receiver packages for one run in deterministic creation order. */
    @Query(
        """
        SELECT * FROM decision_packages
        WHERE runId = :runId
        ORDER BY generatedAtMs ASC, packageId ASC
        """
    )
    suspend fun decisionPackages(runId: String): List<DecisionPackageEntity>

    /** Inserts one immutable publication outcome event, ignoring only a conflicting identity. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPublicationOutcome(value: PublicationOutcomeEventEntity): Long

    /** Returns one publication outcome event by stable identity. */
    @Query("SELECT * FROM publication_outcome_events WHERE eventId = :eventId")
    suspend fun publicationOutcome(eventId: String): PublicationOutcomeEventEntity?

    /** Returns one outcome state machine's exact ordered history. */
    @Query(
        """
        SELECT * FROM publication_outcome_events
        WHERE runId = :runId AND outcomeClass = :outcomeClass
        ORDER BY sequence ASC
        """
    )
    suspend fun publicationOutcomes(
        runId: String,
        outcomeClass: String
    ): List<PublicationOutcomeEventEntity>

    /** Returns the latest event in one outcome state machine. */
    @Query(
        """
        SELECT * FROM publication_outcome_events
        WHERE runId = :runId AND outcomeClass = :outcomeClass
        ORDER BY sequence DESC
        LIMIT 1
        """
    )
    suspend fun latestPublicationOutcome(
        runId: String,
        outcomeClass: String
    ): PublicationOutcomeEventEntity?

    /**
     * Atomically performs strict idempotence and expected-head checking for an outcome event.
     *
     * @param value Exact event row.
     * @param expectedPreviousHash Hash that must still identify the state-machine head.
     * @return The exact persisted row.
     */
    @Transaction
    suspend fun appendPublicationOutcome(
        value: PublicationOutcomeEventEntity,
        expectedPreviousHash: String?
    ): PublicationOutcomeEventEntity {
        publicationOutcome(value.eventId)?.let { existing ->
            require(existing == value) { "Publication outcome event identity is immutable" }
            return existing
        }
        check(
            latestPublicationOutcome(value.runId, value.outcomeClass)?.eventHash ==
                expectedPreviousHash
        ) { "Publication outcome history changed before append" }
        insertPublicationOutcome(value)
        return requireNotNull(publicationOutcome(value.eventId)) {
            "Publication outcome append conflicted with another immutable authority"
        }.also { persisted ->
            require(persisted == value) { "Publication outcome event identity is immutable" }
        }
    }
}
