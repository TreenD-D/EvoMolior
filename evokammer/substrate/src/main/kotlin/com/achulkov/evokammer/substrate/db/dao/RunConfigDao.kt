package com.achulkov.evokammer.substrate.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.achulkov.evokammer.substrate.db.entity.RunConfigEntity
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [RunConfigEntity].
 *
 * Stored in the global (not per-group) database so the desktop control panel
 * can list all runs independently of any group substrate.
 */
@Dao
interface RunConfigDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(config: RunConfigEntity)

    @Update
    suspend fun update(config: RunConfigEntity)

    @Query("SELECT * FROM run_configs WHERE id = :id")
    suspend fun getById(id: String): RunConfigEntity?

    /** All runs ordered by creation time descending (newest first). */
    @Query("SELECT * FROM run_configs ORDER BY createdAtMs DESC")
    fun observeAll(): Flow<List<RunConfigEntity>>

    /** All active (non-terminated, non-failed) runs. */
    @Query("""
        SELECT * FROM run_configs
        WHERE status NOT IN ('Terminated', 'Failed', 'Completed')
        ORDER BY createdAtMs DESC
    """)
    suspend fun getActiveRuns(): List<RunConfigEntity>

    /** Returns the single most recent run in 'Running' state (if any). */
    @Query("""
        SELECT * FROM run_configs
        WHERE status = 'Running'
        ORDER BY startedAtMs DESC
        LIMIT 1
    """)
    suspend fun getRunningRun(): RunConfigEntity?

    /** Update run status. */
    @Query("UPDATE run_configs SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    /** Record the start time and update status to Running. */
    @Query("UPDATE run_configs SET status = 'Running', startedAtMs = :startedAtMs WHERE id = :id")
    suspend fun markStarted(id: String, startedAtMs: Long)

    /** Record completion time and update status. */
    @Query("UPDATE run_configs SET status = :status, completedAtMs = :completedAtMs WHERE id = :id")
    suspend fun markCompleted(id: String, status: String, completedAtMs: Long)

    /** Advance current generation counter. */
    @Query("UPDATE run_configs SET currentGeneration = :generation WHERE id = :id")
    suspend fun updateGeneration(id: String, generation: Int)

    /** Increment total spend. */
    @Query("UPDATE run_configs SET totalSpent = totalSpent + :amount WHERE id = :id")
    suspend fun addSpend(id: String, amount: Double)

    /** Update per-group disclosure stages JSON. */
    @Query("UPDATE run_configs SET groupStagesJson = :stagesJson WHERE id = :id")
    suspend fun updateGroupStages(id: String, stagesJson: String)

    /** Store the StartingField JSON once the Genesis Council completes. */
    @Query("UPDATE run_configs SET startingFieldJson = :json WHERE id = :id")
    suspend fun saveStartingField(id: String, json: String)

    /** Delete a run and all its metadata (does NOT delete group substrates — those are separate DB files). */
    @Query("DELETE FROM run_configs WHERE id = :id")
    suspend fun delete(id: String)
}
