package com.achulkov.evomolior.substrate.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.achulkov.evomolior.substrate.db.entity.WipTaskEntity

/** Room DAO for [WipTaskEntity]. */
@Dao
interface WipTaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tasks: List<WipTaskEntity>)

    /**
     * Inserts a derived task without replacing existing lifecycle history.
     *
     * @return Row ID, or `-1` when the deterministic task identity already exists.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(task: WipTaskEntity): Long

    @Update
    suspend fun update(task: WipTaskEntity)

    @Query("SELECT * FROM wip_tasks WHERE nodeId = :nodeId AND runId = :runId AND groupId = :groupId")
    suspend fun getByNodeId(nodeId: String, runId: String, groupId: String): WipTaskEntity?

    @Query("""
        SELECT * FROM wip_tasks 
        WHERE runId = :runId AND groupId = :groupId AND status = 'unclaimed'
        ORDER BY lastChangedGeneration ASC
        LIMIT 1
    """)
    suspend fun getNextUnclaimed(runId: String, groupId: String): WipTaskEntity?

    @Query("""
        SELECT COUNT(*) FROM wip_tasks 
        WHERE runId = :runId AND groupId = :groupId AND status = 'unclaimed'
    """)
    suspend fun countUnclaimed(runId: String, groupId: String): Int

    /** Total WiP task count for the group. */
    @Query("""
        SELECT COUNT(*) FROM wip_tasks
        WHERE runId = :runId AND groupId = :groupId
    """)
    suspend fun countAll(runId: String, groupId: String): Int

    /** Count completed WiP tasks for the group. */
    @Query("""
        SELECT COUNT(*) FROM wip_tasks
        WHERE runId = :runId AND groupId = :groupId AND status = 'completed'
    """)
    suspend fun countCompleted(runId: String, groupId: String): Int

    /** Returns every legacy WIP row for Atlas migration and projection verification. */
    @Query("""
        SELECT * FROM wip_tasks
        WHERE runId = :runId AND groupId = :groupId
        ORDER BY nodeId ASC
    """)
    suspend fun getAll(runId: String, groupId: String): List<WipTaskEntity>

    /**
     * Returns the exact WIP rows for the supplied node IDs within one run/group scope.
     *
     * @param runId The owning run identifier.
     * @param groupId The owning group identifier.
     * @param nodeIds Exact node IDs to fetch.
     */
    @Query("""
        SELECT * FROM wip_tasks
        WHERE runId = :runId AND groupId = :groupId AND nodeId IN (:nodeIds)
        ORDER BY nodeId ASC
    """)
    suspend fun getByNodeIds(runId: String, groupId: String, nodeIds: List<String>): List<WipTaskEntity>

    /** Oldest still-open tasks, used for planning and hierarchical decomposition. */
    @Query("""
        SELECT * FROM wip_tasks
        WHERE runId = :runId AND groupId = :groupId AND status IN ('unclaimed', 'claimed')
        ORDER BY abandonCount DESC, lastChangedGeneration ASC
        LIMIT :limit
    """)
    suspend fun getOpenTasks(runId: String, groupId: String, limit: Int): List<WipTaskEntity>

    /** Atomically claim a task. Returns 1 if claim succeeded, 0 if already claimed. */
    @Query("""
        UPDATE wip_tasks 
        SET status = 'claimed', claimedByAgentId = :agentId, lastChangedGeneration = :generation
        WHERE nodeId = :nodeId AND runId = :runId AND groupId = :groupId AND status = 'unclaimed'
    """)
    suspend fun claimTask(nodeId: String, runId: String, groupId: String, agentId: String, generation: Int): Int

    @Query("""
        UPDATE wip_tasks 
        SET status = 'completed', lastChangedGeneration = :generation
        WHERE nodeId = :nodeId AND runId = :runId AND groupId = :groupId
          AND claimedByAgentId = :agentId AND status = 'claimed'
    """)
    suspend fun completeTask(nodeId: String, runId: String, groupId: String, agentId: String, generation: Int): Int

    @Query("""
        UPDATE wip_tasks 
        SET status = 'unclaimed', claimedByAgentId = NULL, 
            lastChangedGeneration = :generation, abandonCount = abandonCount + 1
        WHERE nodeId = :nodeId AND runId = :runId AND groupId = :groupId AND claimedByAgentId = :agentId AND status = 'claimed'
    """)
    suspend fun abandonTask(nodeId: String, runId: String, groupId: String, agentId: String, generation: Int): Int

    /**
     * Reactivates exact completed WIP rows for a projection repair.
     *
     * Only rows in the supplied run/group, with matching node IDs, `status = 'completed'`, and
     * `lastChangedGeneration < generation` are reopened.
     *
     * @param runId The owning run identifier.
     * @param groupId The owning group identifier.
     * @param nodeIds Exact node IDs to reopen.
     * @param generation The current generation used as the reopen cutoff.
     * @return Number of rows updated.
     */
    @Query("""
        UPDATE wip_tasks
        SET status = 'unclaimed', claimedByAgentId = NULL,
            lastChangedGeneration = :generation
        WHERE runId = :runId AND groupId = :groupId
          AND nodeId IN (:nodeIds)
          AND status = 'completed'
          AND lastChangedGeneration < :generation
    """)
    suspend fun reactivateCompletedTasksForProjection(
        runId: String,
        groupId: String,
        nodeIds: List<String>,
        generation: Int
    ): Int

    @Query("""
        UPDATE wip_tasks
        SET status = 'unclaimed', claimedByAgentId = NULL,
            lastChangedGeneration = :generation, abandonCount = abandonCount + 1
        WHERE runId = :runId AND groupId = :groupId AND status = 'claimed' AND lastChangedGeneration >= :generation
    """)
    suspend fun releaseClaimsFromGeneration(runId: String, groupId: String, generation: Int): Int

    /** Tasks abandoned 3+ times — for Inspector anomaly detection. */
    @Query("""
        SELECT * FROM wip_tasks 
        WHERE runId = :runId AND groupId = :groupId AND abandonCount >= :minAbandonCount
    """)
    suspend fun getFrequentlyAbandoned(runId: String, groupId: String, minAbandonCount: Int = 3): List<WipTaskEntity>

    /** All tasks linked to a specific DST node, ordered by last changed generation. */
    @Query("""
        SELECT * FROM wip_tasks
        WHERE runId = :runId AND groupId = :groupId AND dstNodeRef = :dstNodeRef
        ORDER BY lastChangedGeneration ASC
    """)
    suspend fun getByDstNodeRef(runId: String, groupId: String, dstNodeRef: String): List<WipTaskEntity>
}
