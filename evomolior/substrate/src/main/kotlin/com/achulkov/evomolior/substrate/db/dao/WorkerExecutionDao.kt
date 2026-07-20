package com.achulkov.evomolior.substrate.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.achulkov.evomolior.substrate.db.entity.AgentExitRecordEntity
import com.achulkov.evomolior.substrate.db.entity.AgentLifecycleRegistrationEntity
import com.achulkov.evomolior.substrate.db.entity.ContributionCommitEntity
import com.achulkov.evomolior.substrate.db.entity.DegradedExecutionRecordEntity
import com.achulkov.evomolior.substrate.db.entity.NodePortfolioEntity
import com.achulkov.evomolior.substrate.db.entity.TtlCheckpointEventEntity

/** Append-only persistence boundary for worker commitments, TTL, degradation, and exits. */
@Dao
interface WorkerExecutionDao {
    /**
     * Inserts one exact portfolio, ignoring an already persisted immutable identity.
     *
     * @param portfolio Portfolio persistence row.
     * @return Inserted row ID, or `-1` when a uniqueness constraint already owns the identity.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNodePortfolio(portfolio: NodePortfolioEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRegistration(record: AgentLifecycleRegistrationEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertContribution(commit: ContributionCommitEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCheckpoint(event: TtlCheckpointEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDegradedExecution(record: DegradedExecutionRecordEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertExit(record: AgentExitRecordEntity): Long

    @Query("SELECT * FROM contribution_commits WHERE commitId = :commitId LIMIT 1")
    suspend fun contributionById(commitId: String): ContributionCommitEntity?

    @Query(
        """
        SELECT * FROM agent_lifecycle_registrations
        WHERE runId = :runId AND groupId = :groupId
        ORDER BY registeredAtMs ASC, agentId ASC
        """
    )
    suspend fun registrations(
        runId: String,
        groupId: String
    ): List<AgentLifecycleRegistrationEntity>

    /**
     * Finds one exact portfolio by immutable identity.
     *
     * @param portfolioId Portfolio identity.
     * @return Persisted portfolio, or null when absent.
     */
    @Query("SELECT * FROM node_portfolios WHERE portfolioId = :portfolioId LIMIT 1")
    suspend fun nodePortfolioById(portfolioId: String): NodePortfolioEntity?

    /**
     * Returns one group's portfolios in deterministic round and identity order.
     *
     * @param runId Owning run.
     * @param groupId Owning group.
     * @return Ordered persisted portfolios.
     */
    @Query(
        """
        SELECT * FROM node_portfolios
        WHERE runId = :runId AND groupId = :groupId
        ORDER BY round ASC, portfolioId ASC
        """
    )
    suspend fun nodePortfolios(
        runId: String,
        groupId: String
    ): List<NodePortfolioEntity>

    @Query(
        """
        SELECT * FROM contribution_commits
        WHERE runId = :runId AND groupId = :groupId AND agentId = :agentId
        ORDER BY committedAtMs ASC
        """
    )
    suspend fun contributionsForAgent(
        runId: String,
        groupId: String,
        agentId: String
    ): List<ContributionCommitEntity>

    @Query(
        """
        SELECT * FROM contribution_commits
        WHERE runId = :runId AND groupId = :groupId AND dedupeHash = :dedupeHash
        LIMIT 1
        """
    )
    suspend fun contributionByDedupeHash(
        runId: String,
        groupId: String,
        dedupeHash: String
    ): ContributionCommitEntity?

    @Query(
        """
        SELECT * FROM contribution_commits
        WHERE runId = :runId AND groupId = :groupId AND nodeId = :nodeId
        ORDER BY round ASC, committedAtMs ASC, commitId ASC
        """
    )
    suspend fun contributionsForNode(
        runId: String,
        groupId: String,
        nodeId: String
    ): List<ContributionCommitEntity>

    @Query(
        """
        SELECT * FROM contribution_commits
        WHERE runId = :runId AND groupId = :groupId AND round = :round
        ORDER BY committedAtMs ASC, commitId ASC
        """
    )
    suspend fun contributionsForRound(
        runId: String,
        groupId: String,
        round: Int
    ): List<ContributionCommitEntity>

    @Query(
        """
        SELECT * FROM degraded_execution_records
        WHERE runId = :runId AND groupId = :groupId AND agentId = :agentId
        ORDER BY occurredAtMs ASC
        """
    )
    suspend fun degradedExecutionsForAgent(
        runId: String,
        groupId: String,
        agentId: String
    ): List<DegradedExecutionRecordEntity>

    @Query(
        """
        SELECT * FROM degraded_execution_records
        WHERE runId = :runId AND groupId = :groupId AND nodeId = :nodeId
        ORDER BY round ASC, occurredAtMs ASC, recordId ASC
        """
    )
    suspend fun degradedExecutionsForNode(
        runId: String,
        groupId: String,
        nodeId: String
    ): List<DegradedExecutionRecordEntity>

    @Query(
        """
        SELECT * FROM ttl_checkpoint_events
        WHERE runId = :runId AND groupId = :groupId AND agentId = :agentId
        ORDER BY elapsedFraction ASC
        """
    )
    suspend fun checkpointsForAgent(
        runId: String,
        groupId: String,
        agentId: String
    ): List<TtlCheckpointEventEntity>

    @Query(
        """
        SELECT * FROM agent_exit_records
        WHERE runId = :runId AND (:groupId IS NULL OR groupId = :groupId)
        ORDER BY exitedAtMs ASC
        """
    )
    suspend fun exits(runId: String, groupId: String?): List<AgentExitRecordEntity>
}
