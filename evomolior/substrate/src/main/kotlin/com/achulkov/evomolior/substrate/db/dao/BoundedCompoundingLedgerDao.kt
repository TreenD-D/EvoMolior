package com.achulkov.evomolior.substrate.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.achulkov.evomolior.substrate.db.entity.CompoundingOutlierExperimentEntity
import com.achulkov.evomolior.substrate.db.entity.CompoundingRepairWorkItemEntity
import com.achulkov.evomolior.substrate.db.entity.CompoundingRewardArtifactDeltaEntity
import com.achulkov.evomolior.substrate.db.entity.CompoundingRewardSpendEntity

/** Append-only DAO for the bounded compounding ledger slice. */
@Dao
interface BoundedCompoundingLedgerDao {
    /** Inserts a repair work item, ignoring an exact immutable replay. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRepairWorkItem(value: CompoundingRepairWorkItemEntity): Long

    /** Returns one repair work item by stable identity. */
    @Query("SELECT * FROM compounding_repair_work_items WHERE workItemId = :workItemId LIMIT 1")
    suspend fun repairWorkItem(workItemId: String): CompoundingRepairWorkItemEntity?

    /** Returns one repair work item by its source diagnostic commitment. */
    @Query("SELECT * FROM compounding_repair_work_items WHERE diagnosticHash = :diagnosticHash LIMIT 1")
    suspend fun repairWorkItemByDiagnosticHash(
        diagnosticHash: String
    ): CompoundingRepairWorkItemEntity?

    /** Returns repair work items in deterministic next-round order. */
    @Query(
        """
        SELECT * FROM compounding_repair_work_items
        WHERE runId = :runId AND groupId = :groupId
        ORDER BY nextRound ASC, workItemId ASC
        """
    )
    suspend fun repairWorkItems(runId: String, groupId: String): List<CompoundingRepairWorkItemEntity>

    /** Inserts a bounded Outlier experiment, ignoring an exact immutable replay. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOutlierExperiment(value: CompoundingOutlierExperimentEntity): Long

    /** Returns one experiment by stable identity. */
    @Query(
        "SELECT * FROM compounding_outlier_experiments WHERE experimentId = :experimentId LIMIT 1"
    )
    suspend fun outlierExperiment(experimentId: String): CompoundingOutlierExperimentEntity?

    /** Returns one experiment by its accepted proposal commitment. */
    @Query(
        "SELECT * FROM compounding_outlier_experiments WHERE proposalHash = :proposalHash LIMIT 1"
    )
    suspend fun outlierExperimentByProposalHash(
        proposalHash: String
    ): CompoundingOutlierExperimentEntity?

    /** Returns bounded Outlier experiments in deterministic start-round order. */
    @Query(
        """
        SELECT * FROM compounding_outlier_experiments
        WHERE runId = :runId AND groupId = :groupId
        ORDER BY startRound ASC, experimentId ASC
        """
    )
    suspend fun outlierExperiments(
        runId: String,
        groupId: String
    ): List<CompoundingOutlierExperimentEntity>

    /** Inserts one explicit reward spend, ignoring an exact immutable replay. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRewardSpend(value: CompoundingRewardSpendEntity): Long

    /** Returns one spend by stable identity. */
    @Query("SELECT * FROM compounding_reward_spends WHERE spendId = :spendId LIMIT 1")
    suspend fun rewardSpend(spendId: String): CompoundingRewardSpendEntity?

    /** Returns reward spends in deterministic round order. */
    @Query(
        """
        SELECT * FROM compounding_reward_spends
        WHERE runId = :runId AND groupId = :groupId
        ORDER BY spentRound ASC, spendId ASC
        """
    )
    suspend fun rewardSpends(runId: String, groupId: String): List<CompoundingRewardSpendEntity>

    /** Inserts a later artifact delta, ignoring an exact immutable replay. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRewardArtifactDelta(value: CompoundingRewardArtifactDeltaEntity): Long

    /** Returns one artifact delta by stable identity. */
    @Query(
        "SELECT * FROM compounding_reward_artifact_deltas WHERE deltaId = :deltaId LIMIT 1"
    )
    suspend fun rewardArtifactDelta(deltaId: String): CompoundingRewardArtifactDeltaEntity?

    /** Returns the later artifact delta linked to one spend. */
    @Query("SELECT * FROM compounding_reward_artifact_deltas WHERE spendId = :spendId LIMIT 1")
    suspend fun rewardArtifactDeltaForSpend(
        spendId: String
    ): CompoundingRewardArtifactDeltaEntity?

    /** Returns reward artifact deltas in deterministic observation order. */
    @Query(
        """
        SELECT * FROM compounding_reward_artifact_deltas
        WHERE runId = :runId AND groupId = :groupId
        ORDER BY observedRound ASC, deltaId ASC
        """
    )
    suspend fun rewardArtifactDeltas(
        runId: String,
        groupId: String
    ): List<CompoundingRewardArtifactDeltaEntity>
}
