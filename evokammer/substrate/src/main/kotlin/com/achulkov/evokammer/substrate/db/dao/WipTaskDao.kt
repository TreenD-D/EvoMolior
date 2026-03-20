package com.achulkov.evokammer.substrate.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.achulkov.evokammer.substrate.db.entity.WipTaskEntity

/** Room DAO for [WipTaskEntity]. */
@Dao
interface WipTaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tasks: List<WipTaskEntity>)

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
        WHERE nodeId = :nodeId AND runId = :runId AND groupId = :groupId AND claimedByAgentId = :agentId
    """)
    suspend fun completeTask(nodeId: String, runId: String, groupId: String, agentId: String, generation: Int)

    @Query("""
        UPDATE wip_tasks 
        SET status = 'unclaimed', claimedByAgentId = NULL, 
            lastChangedGeneration = :generation, abandonCount = abandonCount + 1
        WHERE nodeId = :nodeId AND runId = :runId AND groupId = :groupId AND claimedByAgentId = :agentId
    """)
    suspend fun abandonTask(nodeId: String, runId: String, groupId: String, agentId: String, generation: Int)

    /** Tasks abandoned 3+ times — for Inspector anomaly detection. */
    @Query("""
        SELECT * FROM wip_tasks 
        WHERE runId = :runId AND groupId = :groupId AND abandonCount >= :minAbandonCount
    """)
    suspend fun getFrequentlyAbandoned(runId: String, groupId: String, minAbandonCount: Int = 3): List<WipTaskEntity>
}
