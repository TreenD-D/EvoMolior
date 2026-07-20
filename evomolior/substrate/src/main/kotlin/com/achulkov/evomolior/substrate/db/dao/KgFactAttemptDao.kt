package com.achulkov.evomolior.substrate.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.achulkov.evomolior.substrate.db.entity.KgFactAttemptEntity

/** Append-only DAO for exact KG fact-attempt authority records. */
@Dao
interface KgFactAttemptDao {
    /**
     * Inserts one exact attempt, ignoring an already persisted immutable identity.
     *
     * @param record Attempt persistence row.
     * @return Inserted row ID, or `-1` when a uniqueness constraint already owns the identity.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: KgFactAttemptEntity): Long

    /**
     * Finds the single attempt authority for a staging submission.
     *
     * @param runId Owning run.
     * @param groupId Owning group.
     * @param submissionId Staging submission identity.
     * @return Persisted attempt, or null when absent.
     */
    @Query(
        """
        SELECT * FROM kg_fact_attempts
        WHERE runId = :runId AND groupId = :groupId AND submissionId = :submissionId
        LIMIT 1
        """
    )
    suspend fun bySubmission(
        runId: String,
        groupId: String,
        submissionId: String
    ): KgFactAttemptEntity?

    /**
     * Returns one group's attempt denominator in deterministic generation/time/identity order.
     *
     * @param runId Owning run.
     * @param groupId Owning group.
     * @return Ordered persisted attempts.
     */
    @Query(
        """
        SELECT * FROM kg_fact_attempts
        WHERE runId = :runId AND groupId = :groupId
        ORDER BY generation ASC, recordedAtMs ASC, submissionId ASC
        """
    )
    suspend fun forGroup(runId: String, groupId: String): List<KgFactAttemptEntity>
}
