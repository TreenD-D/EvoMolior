package com.achulkov.evomolior.substrate.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
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
        WHERE runId = :runId AND groupId = :groupId AND generation = :generation AND status = 'pending'
        ORDER BY submittedAtMs ASC
    """)
    suspend fun getPendingByGeneration(runId: String, groupId: String, generation: Int): List<StagingSubmissionEntity>

    /**
     * Returns the complete promoted evidence universe for one group generation.
     *
     * Inline mortal-Critic decisions and the post-round Critic pipeline share this authority
     * boundary, so callers must not derive evidence from a pre-review pending snapshot.
     */
    @Query("""
        SELECT * FROM staging_submissions
        WHERE runId = :runId AND groupId = :groupId AND generation = :generation
          AND status = 'promoted'
        ORDER BY submittedAtMs ASC, id ASC
    """)
    suspend fun getPromotedByGeneration(
        runId: String,
        groupId: String,
        generation: Int
    ): List<StagingSubmissionEntity>

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

    /**
     * Counts substantive promotions reviewed in one generation.
     *
     * Death Notes and runtime-generated fallback records are lifecycle observability rather than
     * promoted work, so they do not represent progress for selector stasis signals.
     */
    @Query("""
        SELECT COUNT(*) FROM staging_submissions
        WHERE runId = :runId AND groupId = :groupId AND generation = :generation
          AND status = 'promoted'
          AND type != 'DEATH_NOTE'
          AND budgetSignature NOT LIKE 'SUBSTRATE_CONTRACT_FALLBACK:%'
          AND budgetSignature NOT LIKE 'NO_ACTION_FALLBACK:%'
    """)
    suspend fun countSubstantivePromotedByGeneration(
        runId: String,
        groupId: String,
        generation: Int
    ): Int

    /**
     * Counts critic-reviewable work that can still complete an agent's claimed WiP task.
     *
     * Automatic Death Notes and runtime compatibility fallbacks are observability records, not
     * task artifacts. They must not keep an exited agent's claim alive.
     */
    @Query("""
        SELECT COUNT(*) FROM staging_submissions
        WHERE runId = :runId AND groupId = :groupId AND agentId = :agentId
          AND wipTaskRef = :nodeId AND status = 'pending'
          AND type != 'DEATH_NOTE'
          AND budgetSignature NOT LIKE 'SUBSTRATE_CONTRACT_FALLBACK:%'
          AND budgetSignature NOT LIKE 'NO_ACTION_FALLBACK:%'
    """)
    suspend fun countPendingCompletionCandidates(
        runId: String,
        groupId: String,
        agentId: String,
        nodeId: String
    ): Int

    @Query("UPDATE staging_submissions SET status = :status, criticAgentId = :criticId, initialTrustScore = :trustScore, rejectionReason = :reason, reviewedAtMs = :reviewedAtMs WHERE id = :id")
    suspend fun updateReview(
        id: String,
        status: String,
        criticId: String?,
        trustScore: Double?,
        reason: String?,
        reviewedAtMs: Long
    )

    @Query(
        """
        UPDATE staging_submissions
        SET promotedArtifactRef = :artifactRef, promotedArtifactHash = :artifactHash
        WHERE id = :id AND status = 'promoted'
        """
    )
    suspend fun recordPromotionArtifact(
        id: String,
        artifactRef: String,
        artifactHash: String
    ): Int

    @Query("SELECT * FROM staging_submissions WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<StagingSubmissionEntity>

    /**
     * Acceptance rate across substantive submissions that completed Critic review.
     *
     * Death Notes bypass review, while runtime fallbacks are observability and recovery records;
     * neither represents agent-authored work for fitness purposes.
     */
    @Query("""
        SELECT CAST(SUM(CASE WHEN status = 'promoted' THEN 1 ELSE 0 END) AS REAL) / 
               CAST(COUNT(*) AS REAL)
        FROM staging_submissions 
        WHERE runId = :runId AND groupId = :groupId AND agentId = :agentId 
          AND status IN ('promoted', 'rejected')
          AND type != 'DEATH_NOTE'
          AND budgetSignature NOT LIKE 'SUBSTRATE_CONTRACT_FALLBACK:%'
          AND budgetSignature NOT LIKE 'NO_ACTION_FALLBACK:%'
    """)
    suspend fun getAcceptanceRateForAgent(runId: String, groupId: String, agentId: String): Double?

    /** Counts substantive promoted submissions for fitness and Legacy Protocol eligibility. */
    @Query("""
        SELECT COUNT(*) FROM staging_submissions 
        WHERE runId = :runId AND groupId = :groupId AND agentId = :agentId AND status = 'promoted'
          AND type != 'DEATH_NOTE'
          AND budgetSignature NOT LIKE 'SUBSTRATE_CONTRACT_FALLBACK:%'
          AND budgetSignature NOT LIKE 'NO_ACTION_FALLBACK:%'
    """)
    suspend fun getPromotedCountForAgent(runId: String, groupId: String, agentId: String): Int

    /** Count promoted KG fact submissions for an agent. */
    @Query("""
        SELECT COUNT(*) FROM staging_submissions 
        WHERE runId = :runId AND groupId = :groupId AND agentId = :agentId 
          AND status = 'promoted' AND type = 'KG_FACT'
          AND budgetSignature NOT LIKE 'SUBSTRATE_CONTRACT_FALLBACK:%'
          AND budgetSignature NOT LIKE 'NO_ACTION_FALLBACK:%'
    """)
    suspend fun getPromotedKgFactCountForAgent(runId: String, groupId: String, agentId: String): Int

    /** Total submission count for an agent (all statuses). */
    @Query("""
        SELECT COUNT(*) FROM staging_submissions 
        WHERE runId = :runId AND groupId = :groupId AND agentId = :agentId
    """)
    suspend fun getTotalCountForAgent(runId: String, groupId: String, agentId: String): Int

    /** All submissions for a run+group regardless of status (for the Substrate Browser). */
    @Query("""
        SELECT * FROM staging_submissions 
        WHERE runId = :runId AND groupId = :groupId
        ORDER BY submittedAtMs DESC
        LIMIT :limit
    """)
    suspend fun getAll(runId: String, groupId: String, limit: Int = 300): List<StagingSubmissionEntity>

    @Query("""
        SELECT MAX(generation) FROM staging_submissions
        WHERE runId = :runId AND groupId = :groupId
    """)
    suspend fun getMaxGeneration(runId: String, groupId: String): Int?

    /** Agent IDs with at least one substantive submission reviewed in a generation. */
    @Query("""
        SELECT DISTINCT agentId FROM staging_submissions 
        WHERE runId = :runId AND groupId = :groupId AND generation = :generation
          AND status IN ('promoted', 'rejected')
          AND type != 'DEATH_NOTE'
          AND budgetSignature NOT LIKE 'SUBSTRATE_CONTRACT_FALLBACK:%'
          AND budgetSignature NOT LIKE 'NO_ACTION_FALLBACK:%'
    """)
    suspend fun getDistinctAgentIds(runId: String, groupId: String, generation: Int): List<String>
}
