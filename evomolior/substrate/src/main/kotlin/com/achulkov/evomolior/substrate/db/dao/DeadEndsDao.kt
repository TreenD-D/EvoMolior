package com.achulkov.evomolior.substrate.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.achulkov.evomolior.substrate.db.entity.DeadEndsEntity

@Dao
interface DeadEndsDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: DeadEndsEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entries: List<DeadEndsEntity>)

    /** Get all dead ends for a run+group. */
    @Query("""
        SELECT * FROM dead_ends
        WHERE runId = :runId AND groupId = :groupId
        ORDER BY createdAtMs DESC
    """)
    suspend fun getAll(runId: String, groupId: String): List<DeadEndsEntity>

    /** Get dead ends for a specific domain. */
    @Query("""
        SELECT * FROM dead_ends
        WHERE runId = :runId AND groupId = :groupId AND domain = :domain
        ORDER BY createdAtMs DESC
    """)
    suspend fun getByDomain(runId: String, groupId: String, domain: String): List<DeadEndsEntity>

    /** Search dead ends by keyword match in approach or reason. */
    @Query("""
        SELECT * FROM dead_ends
        WHERE runId = :runId AND groupId = :groupId
          AND (approach LIKE '%' || :query || '%' OR reason LIKE '%' || :query || '%')
        ORDER BY referenceCount DESC
        LIMIT :limit
    """)
    suspend fun search(runId: String, groupId: String, query: String, limit: Int): List<DeadEndsEntity>

    /** Get the most-referenced dead ends (for prominent display in boot context). */
    @Query("""
        SELECT * FROM dead_ends
        WHERE runId = :runId AND groupId = :groupId
        ORDER BY referenceCount DESC
        LIMIT :limit
    """)
    suspend fun getTopReferenced(runId: String, groupId: String, limit: Int): List<DeadEndsEntity>

    /** Get dead ends from a specific generation. */
    @Query("""
        SELECT * FROM dead_ends
        WHERE runId = :runId AND groupId = :groupId AND generation >= :fromGeneration
        ORDER BY createdAtMs DESC
    """)
    suspend fun getFromGeneration(runId: String, groupId: String, fromGeneration: Int): List<DeadEndsEntity>

    /** Count dead ends for a run+group. */
    @Query("SELECT COUNT(*) FROM dead_ends WHERE runId = :runId AND groupId = :groupId")
    suspend fun count(runId: String, groupId: String): Int

    /** Increment the reference count for a dead end (called when an agent cites it). */
    @Query("""
        UPDATE dead_ends SET referenceCount = referenceCount + 1
        WHERE id = :id
    """)
    suspend fun incrementReferenceCount(id: String)

    /** Delete all dead ends for a run (cleanup). */
    @Query("DELETE FROM dead_ends WHERE runId = :runId")
    suspend fun deleteForRun(runId: String)
}
