package com.achulkov.evomolior.substrate.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.achulkov.evomolior.substrate.db.entity.EventLogEntryEntity
import kotlinx.coroutines.flow.Flow

/** Room DAO for [EventLogEntryEntity]. Append-only — no update or delete. */
@Dao
interface EventLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: EventLogEntryEntity)

    @Query("SELECT * FROM event_log_entries WHERE id = :id")
    suspend fun getById(id: String): EventLogEntryEntity?

    /** Observe the latest events for the Inspector (real-time anomaly detection). */
    @Query("""
        SELECT * FROM event_log_entries 
        WHERE runId = :runId 
        ORDER BY timestampMs DESC 
        LIMIT :limit
    """)
    fun observeLatest(runId: String, limit: Int): Flow<List<EventLogEntryEntity>>

    /** Filtered query for the desktop Event Log Viewer. */
    @Query("""
        SELECT * FROM event_log_entries 
        WHERE runId = :runId 
          AND (:groupId IS NULL OR groupId = :groupId)
          AND (:agentId IS NULL OR agentId = :agentId)
          AND (:eventType IS NULL OR eventType = :eventType)
          AND (:minGeneration IS NULL OR generation >= :minGeneration)
          AND (:maxGeneration IS NULL OR generation <= :maxGeneration)
        ORDER BY timestampMs DESC 
        LIMIT :limit OFFSET :offset
    """)
    suspend fun queryFiltered(
        runId: String,
        groupId: String?,
        agentId: String?,
        eventType: String?,
        minGeneration: Int?,
        maxGeneration: Int?,
        limit: Int,
        offset: Int
    ): List<EventLogEntryEntity>

    /** Count events by type for an agent (for fitness scoring). */
    @Query("""
        SELECT COUNT(*) FROM event_log_entries 
        WHERE runId = :runId AND groupId = :groupId AND agentId = :agentId AND eventType = :eventType
    """)
    suspend fun countByAgentAndType(runId: String, groupId: String, agentId: String, eventType: String): Int

    /** Get all events for a generation (for Inspector analysis). */
    @Query("""
        SELECT * FROM event_log_entries 
        WHERE runId = :runId AND groupId = :groupId AND generation = :generation
        ORDER BY timestampMs ASC
    """)
    suspend fun getByGeneration(runId: String, groupId: String, generation: Int): List<EventLogEntryEntity>

    /** Latest N events for a specific event type (for Watchdog trigger checks). */
    @Query("""
        SELECT * FROM event_log_entries 
        WHERE runId = :runId AND groupId = :groupId AND eventType = :eventType
        ORDER BY timestampMs DESC 
        LIMIT :limit
    """)
    suspend fun getLatestByType(runId: String, groupId: String, eventType: String, limit: Int): List<EventLogEntryEntity>

    @Query("""
        SELECT MAX(generation) FROM event_log_entries
        WHERE runId = :runId AND groupId = :groupId
    """)
    suspend fun getMaxGeneration(runId: String, groupId: String): Int?

    /** Stable complete pagination used only by immutable run-manifest export. */
    @Query(
        """
        SELECT * FROM event_log_entries
        WHERE runId = :runId
        ORDER BY timestampMs ASC, id ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun forManifest(
        runId: String,
        limit: Int,
        offset: Int
    ): List<EventLogEntryEntity>
}
