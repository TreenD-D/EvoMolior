package com.achulkov.evomolior.substrate.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.achulkov.evomolior.substrate.db.entity.KnowledgeBaseEntryEntity
import kotlinx.coroutines.flow.Flow

/** Room DAO for [KnowledgeBaseEntryEntity]. */
@Dao
interface KnowledgeBaseDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: KnowledgeBaseEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<KnowledgeBaseEntryEntity>)

    @Update
    suspend fun update(entry: KnowledgeBaseEntryEntity)

    @Query("SELECT * FROM knowledge_base_entries WHERE id = :id")
    suspend fun getById(id: String): KnowledgeBaseEntryEntity?

    /** Returns all active (non-archived) entries for a group, including Dead Ends. */
    @Query("""
        SELECT * FROM knowledge_base_entries 
        WHERE runId = :runId AND groupId = :groupId AND (status = 'active' OR isDeadEnd = 1)
        ORDER BY citationCount DESC, generation DESC, trustScore DESC, createdAtMs DESC
    """)
    fun observeActive(runId: String, groupId: String): Flow<List<KnowledgeBaseEntryEntity>>

    /** Lists active entries in the default agent-facing order: recently used, novel, then trusted. */
    @Query("""
        SELECT * FROM knowledge_base_entries
        WHERE runId = :runId AND groupId = :groupId AND (status = 'active' OR isDeadEnd = 1)
        ORDER BY citationCount DESC, generation DESC, trustScore DESC, createdAtMs DESC
        LIMIT :limit
    """)
    suspend fun listActiveOrganized(runId: String, groupId: String, limit: Int): List<KnowledgeBaseEntryEntity>

    /** Returns active entries by domain. Dead Ends always included. */
    @Query("""
        SELECT * FROM knowledge_base_entries 
        WHERE runId = :runId AND groupId = :groupId AND domain = :domain 
          AND (status = 'active' OR isDeadEnd = 1)
        ORDER BY citationCount DESC, generation DESC, trustScore DESC, createdAtMs DESC
        LIMIT :limit
    """)
    suspend fun queryByDomain(runId: String, groupId: String, domain: String, limit: Int): List<KnowledgeBaseEntryEntity>

    /** Simple keyword search over content. Dead Ends always included. */
    @Query("""
        SELECT * FROM knowledge_base_entries 
        WHERE runId = :runId AND groupId = :groupId 
          AND (status = 'active' OR isDeadEnd = 1)
          AND content LIKE '%' || :query || '%'
        ORDER BY citationCount DESC, generation DESC, trustScore DESC, createdAtMs DESC
        LIMIT :limit
    """)
    suspend fun search(runId: String, groupId: String, query: String, limit: Int): List<KnowledgeBaseEntryEntity>

    /** Count of active entries by domain (for Evolutionary Selector density signals). */
    @Query("""
        SELECT COUNT(*) FROM knowledge_base_entries 
        WHERE runId = :runId AND groupId = :groupId AND domain = :domain AND status = 'active'
    """)
    suspend fun countByDomain(runId: String, groupId: String, domain: String): Int

    /** Total count of active KB entries (for growth tracking). */
    @Query("""
        SELECT COUNT(*) FROM knowledge_base_entries 
        WHERE runId = :runId AND groupId = :groupId AND status = 'active'
    """)
    suspend fun countActive(runId: String, groupId: String): Int

    /** Increment citation count for an entry. */
    @Query("UPDATE knowledge_base_entries SET citationCount = citationCount + 1 WHERE id = :id")
    suspend fun incrementCitationCount(id: String)

    /**
     * Sums reuse citations across canonical, non-Death-Note KB entries authored by an agent.
     *
     * Archived versions remain canonical audit records, so their citations continue to count.
     */
    @Query("""
        SELECT COALESCE(SUM(citationCount), 0) FROM knowledge_base_entries
        WHERE runId = :runId AND groupId = :groupId AND sourceAgentId = :agentId
          AND isDeadEnd = 0
    """)
    suspend fun getCitationCountForAgent(runId: String, groupId: String, agentId: String): Long

    /** Archive entries below trust threshold (trust decay). Dead Ends excluded. */
    @Query("""
        UPDATE knowledge_base_entries 
        SET status = 'archived' 
        WHERE runId = :runId AND groupId = :groupId 
          AND status = 'active' AND isDeadEnd = 0
          AND trustScore < :threshold
    """)
    suspend fun archiveBelowTrust(runId: String, groupId: String, threshold: Double): Int

    /**
     * Archives old active entries that have not been reused and are not trusted enough to remain
     * in the default working set. History is preserved; archived rows remain addressable by ID.
     */
    @Query("""
        UPDATE knowledge_base_entries
        SET status = 'archived'
        WHERE runId = :runId AND groupId = :groupId
          AND status = 'active' AND isDeadEnd = 0
          AND generation <= (:currentGeneration - :staleGenerationWindow)
          AND citationCount < :minCitationCountToKeep
          AND trustScore < :minTrustScoreToKeep
    """)
    suspend fun archiveStaleForGeneration(
        runId: String,
        groupId: String,
        currentGeneration: Int,
        staleGenerationWindow: Int,
        minCitationCountToKeep: Int,
        minTrustScoreToKeep: Double
    ): Int

    /** Update trust score for an entry. */
    @Query("UPDATE knowledge_base_entries SET trustScore = :newScore WHERE id = :id")
    suspend fun updateTrustScore(id: String, newScore: Double)

    @Query("SELECT * FROM knowledge_base_entries WHERE runId = :runId AND groupId = :groupId AND isDeadEnd = 1")
    suspend fun getDeadEnds(runId: String, groupId: String): List<KnowledgeBaseEntryEntity>

    @Query("SELECT * FROM knowledge_base_entries WHERE runId = :runId AND groupId = :groupId AND isDeadEnd = 1")
    fun observeDeadEnds(runId: String, groupId: String): Flow<List<KnowledgeBaseEntryEntity>>
}
