package com.achulkov.evokammer.substrate.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.achulkov.evokammer.substrate.db.entity.KnowledgeGraphFactEntity
import kotlinx.coroutines.flow.Flow

/** Room DAO for [KnowledgeGraphFactEntity] — typed KG fact storage with pattern query support. */
@Dao
interface KnowledgeGraphFactDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(fact: KnowledgeGraphFactEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(facts: List<KnowledgeGraphFactEntity>)

    @Update
    suspend fun update(fact: KnowledgeGraphFactEntity)

    @Query("SELECT * FROM knowledge_graph_facts WHERE id = :id")
    suspend fun getById(id: String): KnowledgeGraphFactEntity?

    /** All active (non-disputed, non-rejected) facts for a group. */
    @Query("""
        SELECT * FROM knowledge_graph_facts
        WHERE runId = :runId AND groupId = :groupId AND status = 'active'
        ORDER BY trustScore DESC, generation DESC
    """)
    fun observeActive(runId: String, groupId: String): Flow<List<KnowledgeGraphFactEntity>>

    /** Pattern query: match facts by subject (or wildcard '%'). */
    @Query("""
        SELECT * FROM knowledge_graph_facts
        WHERE runId = :runId AND groupId = :groupId AND status = 'active'
          AND subject LIKE :subjectPattern
        ORDER BY trustScore DESC
        LIMIT :limit
    """)
    suspend fun queryBySubject(runId: String, groupId: String, subjectPattern: String, limit: Int): List<KnowledgeGraphFactEntity>

    /** Pattern query: match facts by relation. */
    @Query("""
        SELECT * FROM knowledge_graph_facts
        WHERE runId = :runId AND groupId = :groupId AND status = 'active'
          AND relation LIKE :relationPattern
        ORDER BY trustScore DESC
        LIMIT :limit
    """)
    suspend fun queryByRelation(runId: String, groupId: String, relationPattern: String, limit: Int): List<KnowledgeGraphFactEntity>

    /** Pattern query: match facts by object. */
    @Query("""
        SELECT * FROM knowledge_graph_facts
        WHERE runId = :runId AND groupId = :groupId AND status = 'active'
          AND `object` LIKE :objectPattern
        ORDER BY trustScore DESC
        LIMIT :limit
    """)
    suspend fun queryByObject(runId: String, groupId: String, objectPattern: String, limit: Int): List<KnowledgeGraphFactEntity>

    /**
     * Flexible triple pattern query — any of subject/relation/object can be '%' for wildcard.
     * Primary tool for `ReadKGTool`.
     */
    @Query("""
        SELECT * FROM knowledge_graph_facts
        WHERE runId = :runId AND groupId = :groupId AND status = 'active'
          AND subject LIKE :subjectPattern
          AND relation LIKE :relationPattern
          AND `object` LIKE :objectPattern
        ORDER BY trustScore DESC
        LIMIT :limit
    """)
    suspend fun queryByTriplePattern(
        runId: String,
        groupId: String,
        subjectPattern: String,
        relationPattern: String,
        objectPattern: String,
        limit: Int
    ): List<KnowledgeGraphFactEntity>

    /** Check for existing fact with same subject+relation (for conflict detection). */
    @Query("""
        SELECT * FROM knowledge_graph_facts
        WHERE runId = :runId AND groupId = :groupId AND status = 'active'
          AND subject = :subject AND relation = :relation
        LIMIT 1
    """)
    suspend fun findConflict(runId: String, groupId: String, subject: String, relation: String): KnowledgeGraphFactEntity?

    /** Count of active KG facts for dashboard display. */
    @Query("""
        SELECT COUNT(*) FROM knowledge_graph_facts
        WHERE runId = :runId AND groupId = :groupId AND status = 'active'
    """)
    suspend fun countActive(runId: String, groupId: String): Int

    /** Count of speculative (Outlier) facts pending elevation. */
    @Query("""
        SELECT COUNT(*) FROM knowledge_graph_facts
        WHERE runId = :runId AND groupId = :groupId AND speculative = 1 AND status = 'active'
    """)
    suspend fun countSpeculative(runId: String, groupId: String): Int

    /** Update trust score. */
    @Query("UPDATE knowledge_graph_facts SET trustScore = :newScore WHERE id = :id")
    suspend fun updateTrustScore(id: String, newScore: Double)

    /** Mark fact as disputed (sent to KG-Critic). */
    @Query("UPDATE knowledge_graph_facts SET status = 'disputed' WHERE id = :id")
    suspend fun markDisputed(id: String)

    /** Mark fact as rejected. */
    @Query("UPDATE knowledge_graph_facts SET status = 'rejected' WHERE id = :id")
    suspend fun markRejected(id: String)

    /** Get all disputed facts (for KG-Critic queue). */
    @Query("""
        SELECT * FROM knowledge_graph_facts
        WHERE runId = :runId AND groupId = :groupId AND status = 'disputed'
        ORDER BY createdAtMs ASC
    """)
    suspend fun getDisputed(runId: String, groupId: String): List<KnowledgeGraphFactEntity>

    /** Get all facts from a specific generation (for Legacy Protocol extraction). */
    @Query("""
        SELECT * FROM knowledge_graph_facts
        WHERE runId = :runId AND groupId = :groupId AND generation = :generation AND status = 'active'
        ORDER BY trustScore DESC
    """)
    suspend fun getByGeneration(runId: String, groupId: String, generation: Int): List<KnowledgeGraphFactEntity>

    /** Archive all facts below a trust threshold. */
    @Query("""
        UPDATE knowledge_graph_facts
        SET status = 'archived'
        WHERE runId = :runId AND groupId = :groupId AND status = 'active'
          AND trustScore < :threshold
    """)
    suspend fun archiveBelowTrust(runId: String, groupId: String, threshold: Double): Int

    /**
     * Count the number of conflicting (subject, relation) pairs — i.e., pairs where
     * multiple distinct objects are asserted as active at the same time.
     * Used by `Watchdog` for the KG inconsistency rate signal.
     */
    @Query("""
        SELECT COUNT(*) FROM (
            SELECT subject, relation
            FROM knowledge_graph_facts
            WHERE runId = :runId AND groupId = :groupId AND status = 'active'
            GROUP BY subject, relation
            HAVING COUNT(DISTINCT `object`) > 1
        )
    """)
    suspend fun countConflicts(runId: String, groupId: String): Int
}
