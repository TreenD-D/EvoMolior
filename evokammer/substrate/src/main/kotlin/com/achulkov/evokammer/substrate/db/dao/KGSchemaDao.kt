package com.achulkov.evokammer.substrate.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.achulkov.evokammer.substrate.db.entity.KGSchemaEntity

@Dao
interface KGSchemaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(schema: KGSchemaEntity)

    /** Get the current schema for a run+group. */
    @Query("""
        SELECT * FROM kg_schemas
        WHERE runId = :runId AND groupId = :groupId
        ORDER BY schemaVersion DESC
        LIMIT 1
    """)
    suspend fun get(runId: String, groupId: String): KGSchemaEntity?

    /** Get all schema versions for a run+group (for audit/history). */
    @Query("""
        SELECT * FROM kg_schemas
        WHERE runId = :runId AND groupId = :groupId
        ORDER BY schemaVersion DESC
    """)
    suspend fun getAll(runId: String, groupId: String): List<KGSchemaEntity>

    /** Get the entity types JSON for a run+group. */
    @Query("""
        SELECT entityTypesJson FROM kg_schemas
        WHERE runId = :runId AND groupId = :groupId
        ORDER BY schemaVersion DESC
        LIMIT 1
    """)
    suspend fun getEntityTypesJson(runId: String, groupId: String): String?

    /** Get the hard constraints JSON for a run+group. */
    @Query("""
        SELECT hardConstraintsJson FROM kg_schemas
        WHERE runId = :runId AND groupId = :groupId
        ORDER BY schemaVersion DESC
        LIMIT 1
    """)
    suspend fun getHardConstraintsJson(runId: String, groupId: String): String?

    /** Delete all schema entries for a run (cleanup). */
    @Query("DELETE FROM kg_schemas WHERE runId = :runId")
    suspend fun deleteForRun(runId: String)
}
