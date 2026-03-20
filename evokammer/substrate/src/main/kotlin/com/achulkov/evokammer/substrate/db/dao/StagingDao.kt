package com.achulkov.evokammer.substrate.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.achulkov.evokammer.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.flow.Flow

/** Room DAO for [StagingSubmissionEntity]. */
@Dao
interface StagingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(submission: StagingSubmissionEntity)

    @Update
    suspend fun update(submission: StagingSubmissionEntity)

    @Query("SELECT * FROM staging_submissions WHERE id = :id")
    suspend fun getById(id: String): StagingSubmissionEntity?

    @Query("""
        SELECT * FROM staging_submissions 
        WHERE runId = :runId AND groupId = :groupId AND status = 'pending'
        ORDER BY submittedAtMs ASC
    """)
    suspend fun getPending(runId: String, groupId: String): List<StagingSubmissionEntity>

    @Query("""
        SELECT * FROM staging_submissions 
        WHERE runId = :runId AND groupId = :groupId AND status = 'pending'
        ORDER BY submittedAtMs ASC
    """)
    fun observePending(runId: String, groupId: String): Flow<List<StagingSubmissionEntity>>

    @Query("""
        SELECT * FROM staging_submissions 
        WHERE runId = :runId AND groupId = :groupId AND agentId = :agentId
        ORDER BY submittedAtMs DESC
    """)
    suspend fun getByAgent(runId: String, groupId: String, agentId: String): List<StagingSubmissionEntity>

    @Query("""
        SELECT COUNT(*) FROM staging_submissions 
        WHERE runId = :runId AND groupId = :groupId AND status = 'pending' AND type = :type
    """)
    suspend fun countPendingByType(runId: String, groupId: String, type: String): Int

    /** Total pending across all types (for Evolutionary Selector backlog signal). */
    @Query("""
        SELECT COUNT(*) FROM staging_submissions 
        WHERE runId = :runId AND groupId = :groupId AND status = 'pending'
    """)
    suspend fun countPending(runId: String, groupId: String): Int

    @Query("UPDATE staging_submissions SET status = :status, criticAgentId = :criticId, initialTrustScore = :trustScore, rejectionReason = :reason, reviewedAtMs = :reviewedAtMs WHERE id = :id")
    suspend fun updateReview(
        id: String,
        status: String,
        criticId: String?,
        trustScore: Double?,
        reason: String?,
        reviewedAtMs: Long
    )

    /** Staging acceptance rate for an agent (for fitness scoring). */
    @Query("""
        SELECT CAST(SUM(CASE WHEN status = 'promoted' THEN 1 ELSE 0 END) AS REAL) / 
               CAST(COUNT(*) AS REAL)
        FROM staging_submissions 
        WHERE runId = :runId AND groupId = :groupId AND agentId = :agentId 
          AND status IN ('promoted', 'rejected')
    """)
    suspend fun getAcceptanceRateForAgent(runId: String, groupId: String, agentId: String): Double?

    /** Count promoted submissions for an agent (for fitness scoring). */
    @Query("""
        SELECT COUNT(*) FROM staging_submissions 
        WHERE runId = :runId AND groupId = :groupId AND agentId = :agentId AND status = 'promoted'
    """)
    suspend fun getPromotedCountForAgent(runId: String, groupId: String, agentId: String): Int

    /** Count promoted KG fact submissions for an agent. */
    @Query("""
        SELECT COUNT(*) FROM staging_submissions 
        WHERE runId = :runId AND groupId = :groupId AND agentId = :agentId 
          AND status = 'promoted' AND type = 'KG_FACT'
    """)
    suspend fun getPromotedKgFactCountForAgent(runId: String, groupId: String, agentId: String): Int

    /** Total submission count for an agent (all statuses). */
    @Query("""
        SELECT COUNT(*) FROM staging_submissions 
        WHERE runId = :runId AND groupId = :groupId AND agentId = :agentId
    """)
    suspend fun getTotalCountForAgent(runId: String, groupId: String, agentId: String): Int

    /** All distinct agent IDs with at least one submission in a generation. */
    @Query("""
        SELECT DISTINCT agentId FROM staging_submissions 
        WHERE runId = :runId AND groupId = :groupId AND generation = :generation
    """)
    suspend fun getDistinctAgentIds(runId: String, groupId: String, generation: Int): List<String>
}
