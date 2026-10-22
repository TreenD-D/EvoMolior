package com.achulkov.evomolior.substrate.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.achulkov.evomolior.substrate.db.entity.AgentExitRecordEntity
import com.achulkov.evomolior.substrate.db.entity.AgentLifecycleRegistrationEntity
import com.achulkov.evomolior.substrate.db.entity.AgentRuntimeStateEventEntity
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

    /**
     * Returns the immutable exit authority for one exact registered agent, when present.
     *
     * @param runId Owning run identity.
     * @param groupId Owning group identity.
     * @param agentId Registered agent identity.
     */
    @Query(
        """
        SELECT * FROM agent_exit_records
        WHERE runId = :runId AND groupId = :groupId AND agentId = :agentId
        LIMIT 1
        """
    )
    suspend fun exitForAgent(
        runId: String,
        groupId: String,
        agentId: String
    ): AgentExitRecordEntity?

    /**
     * Inserts one runtime-state transition, ignoring an already owned event or revision identity.
     *
     * @param event Immutable transition row.
     * @return Inserted row ID, or `-1` when an immutable identity already exists.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRuntimeStateEvent(event: AgentRuntimeStateEventEntity): Long

    /**
     * Returns a runtime-state transition by immutable event identity.
     *
     * @param eventId Event identity.
     * @return Persisted transition, or null when absent.
     */
    @Query("SELECT * FROM agent_runtime_state_events WHERE eventId = :eventId LIMIT 1")
    suspend fun runtimeStateEventById(eventId: String): AgentRuntimeStateEventEntity?

    /**
     * Returns the transition owning one lifecycle revision.
     *
     * @param runId Owning run identity.
     * @param groupId Owning group identity.
     * @param agentId Registered agent identity.
     * @param generation Registered lifecycle generation.
     * @param revision Monotonic state revision.
     * @return Persisted transition, or null when absent.
     */
    @Query(
        """
        SELECT * FROM agent_runtime_state_events
        WHERE runId = :runId AND groupId = :groupId AND agentId = :agentId
          AND generation = :generation AND revision = :revision
        LIMIT 1
        """
    )
    suspend fun runtimeStateEventForRevision(
        runId: String,
        groupId: String,
        agentId: String,
        generation: Int,
        revision: Long
    ): AgentRuntimeStateEventEntity?

    /**
     * Returns one lifecycle's runtime-state transitions in revision order.
     *
     * @param runId Owning run identity.
     * @param groupId Owning group identity.
     * @param agentId Registered agent identity.
     * @param generation Registered lifecycle generation.
     * @return Ordered transition rows.
     */
    @Query(
        """
        SELECT * FROM agent_runtime_state_events
        WHERE runId = :runId AND groupId = :groupId AND agentId = :agentId
          AND generation = :generation
        ORDER BY revision ASC
        """
    )
    suspend fun runtimeStateEvents(
        runId: String,
        groupId: String,
        agentId: String,
        generation: Int
    ): List<AgentRuntimeStateEventEntity>

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
     * Returns registrations carrying the artifact-owner duty for one group run.
     *
     * @param runId Owning run identity.
     * @param groupId Owning group identity.
     * @return Ordered artifact-owner registrations.
     */
    @Query(
        """
        SELECT * FROM agent_lifecycle_registrations
        WHERE runId = :runId AND groupId = :groupId AND duty = 'ARTIFACT_OWNER'
        ORDER BY registeredAtMs ASC, agentId ASC
        """
    )
    suspend fun artifactOwnerRegistrations(
        runId: String,
        groupId: String
    ): List<AgentLifecycleRegistrationEntity>

    /**
     * Returns the immutable registration owning one exact agent identity.
     *
     * @param runId Owning run identity.
     * @param groupId Owning group identity.
     * @param agentId Registered agent identity.
     * @return Persisted registration, or null when the agent was not registered.
     */
    @Query(
        """
        SELECT * FROM agent_lifecycle_registrations
        WHERE runId = :runId AND groupId = :groupId AND agentId = :agentId
        LIMIT 1
        """
    )
    suspend fun registrationForAgent(
        runId: String,
        groupId: String,
        agentId: String
    ): AgentLifecycleRegistrationEntity?

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
