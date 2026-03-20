package com.achulkov.evokammer.substrate.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.achulkov.evokammer.substrate.db.entity.TrustScoreEntity

@Dao
interface TrustScoreDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: TrustScoreEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<TrustScoreEntity>)

    /** Get the trust score for a specific source. */
    @Query("""
        SELECT * FROM trust_scores
        WHERE runId = :runId AND groupId = :groupId AND sourceId = :sourceId AND sourceType = :sourceType
        LIMIT 1
    """)
    suspend fun get(runId: String, groupId: String, sourceId: String, sourceType: String): TrustScoreEntity?

    /** Get the numeric score for a specific source (or null if no record). */
    @Query("""
        SELECT score FROM trust_scores
        WHERE runId = :runId AND groupId = :groupId AND sourceId = :sourceId AND sourceType = :sourceType
        LIMIT 1
    """)
    suspend fun getScore(runId: String, groupId: String, sourceId: String, sourceType: String): Double?

    /** Get all trust entries for a run+group above a minimum threshold. */
    @Query("""
        SELECT * FROM trust_scores
        WHERE runId = :runId AND groupId = :groupId AND score >= :minScore
        ORDER BY score DESC
    """)
    suspend fun getTrustedSources(runId: String, groupId: String, minScore: Double = 0.5): List<TrustScoreEntity>

    /** Get all dispatch trust scores for a group (for leak oracle trust gating). */
    @Query("""
        SELECT * FROM trust_scores
        WHERE runId = :runId AND groupId = :groupId AND sourceType = 'dispatch'
        ORDER BY score DESC
    """)
    suspend fun getDispatchTrustScores(runId: String, groupId: String): List<TrustScoreEntity>

    /** Get the average trust score for dispatches (for Leak Oracle frequency adjustment). */
    @Query("""
        SELECT AVG(score) FROM trust_scores
        WHERE runId = :runId AND groupId = :groupId AND sourceType = 'dispatch'
    """)
    suspend fun getAverageDispatchTrust(runId: String, groupId: String): Double?

    /** Apply decay: reduce all active trust scores by the given factor. */
    @Query("""
        UPDATE trust_scores
        SET score = score * :decayFactor, decayCount = decayCount + 1, lastUpdatedMs = :nowMs
        WHERE runId = :runId AND groupId = :groupId AND sourceType != 'dead_end'
    """)
    suspend fun applyDecay(runId: String, groupId: String, decayFactor: Double, nowMs: Long)

    /** Record a promotion boost for a source. */
    @Query("""
        UPDATE trust_scores
        SET score = MIN(1.0, score + :boost), promotionCount = promotionCount + 1, lastUpdatedMs = :nowMs
        WHERE runId = :runId AND groupId = :groupId AND sourceId = :sourceId
    """)
    suspend fun recordPromotion(runId: String, groupId: String, sourceId: String, boost: Double = 0.05, nowMs: Long)

    /** Record a rejection penalty for a source. */
    @Query("""
        UPDATE trust_scores
        SET score = MAX(0.0, score - :penalty), rejectionCount = rejectionCount + 1, lastUpdatedMs = :nowMs
        WHERE runId = :runId AND groupId = :groupId AND sourceId = :sourceId
    """)
    suspend fun recordRejection(runId: String, groupId: String, sourceId: String, penalty: Double = 0.1, nowMs: Long)

    /** Delete all trust scores for a run (cleanup). */
    @Query("DELETE FROM trust_scores WHERE runId = :runId")
    suspend fun deleteForRun(runId: String)
}
