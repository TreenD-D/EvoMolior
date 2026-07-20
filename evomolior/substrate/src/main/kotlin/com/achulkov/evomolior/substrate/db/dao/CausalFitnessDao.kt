package com.achulkov.evomolior.substrate.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.achulkov.evomolior.substrate.db.entity.CausalFitnessRecordEntity

/** Append-only persistence boundary for plane-separated causal fitness. */
@Dao
interface CausalFitnessDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: CausalFitnessRecordEntity): Long

    @Query("SELECT * FROM causal_fitness_records WHERE recordId = :recordId LIMIT 1")
    suspend fun byId(recordId: String): CausalFitnessRecordEntity?

    @Query(
        """
        SELECT * FROM causal_fitness_records
        WHERE runId = :runId AND groupId = :groupId AND round = :round AND plane = :plane
        ORDER BY total DESC, agentId ASC
        """
    )
    suspend fun forPlane(
        runId: String,
        groupId: String,
        round: Int,
        plane: String
    ): List<CausalFitnessRecordEntity>

    @Query(
        """
        SELECT * FROM causal_fitness_records
        WHERE runId = :runId AND groupId = :groupId AND agentId = :agentId
        ORDER BY round ASC
        """
    )
    suspend fun forAgent(
        runId: String,
        groupId: String,
        agentId: String
    ): List<CausalFitnessRecordEntity>
}
