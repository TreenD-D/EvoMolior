package com.achulkov.evokammer.substrate.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.achulkov.evokammer.substrate.db.entity.KgSchemaConstraintEntity

/** DAO for first-class KG schema constraint artifacts. */
@Dao
interface KgSchemaConstraintDao {

    /** Inserts or replaces a promoted KG constraint artifact. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(constraint: KgSchemaConstraintEntity)

    /** Returns all constraints for a run+group in insertion order. */
    @Query("""
        SELECT * FROM kg_schema_constraints
        WHERE runId = :runId AND groupId = :groupId
        ORDER BY createdAtMs ASC
    """)
    suspend fun getAll(runId: String, groupId: String): List<KgSchemaConstraintEntity>

    /** Deletes all constraints for a run during cleanup. */
    @Query("DELETE FROM kg_schema_constraints WHERE runId = :runId")
    suspend fun deleteForRun(runId: String)
}
