package com.achulkov.evomolior.substrate.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.flow.Flow

/**
 * Canonical read model for group plan registry entries.
 *
 * MetaPlanner and Planner plan updates are persisted in the staging substrate as
 * `PLAN_UPDATE` submissions. This DAO exposes that persisted stream through a
 * dedicated plan-registry view so the run loop and graders can consume it without
 * having to know about the staging table implementation detail.
 */
@Dao
interface PlanRegistryDao {

    /**
     * Returns the most recent plan updates for a group.
     *
     * @param runId Run identifier.
     * @param groupId Group identifier.
     * @param limit Maximum number of updates to return.
     */
    @Query("""
        SELECT * FROM staging_submissions
        WHERE runId = :runId
          AND groupId = :groupId
          AND type = 'PLAN_UPDATE'
          AND status IN ('promoted', 'pending')
        ORDER BY submittedAtMs DESC
        LIMIT :limit
    """)
    suspend fun getLatest(runId: String, groupId: String, limit: Int = 50): List<StagingSubmissionEntity>

    /**
     * Observes the most recent plan updates for a group.
     *
     * @param runId Run identifier.
     * @param groupId Group identifier.
     * @param limit Maximum number of updates to return.
     */
    @Query("""
        SELECT * FROM staging_submissions
        WHERE runId = :runId
          AND groupId = :groupId
          AND type = 'PLAN_UPDATE'
          AND status IN ('promoted', 'pending')
        ORDER BY submittedAtMs DESC
        LIMIT :limit
    """)
    fun observeLatest(runId: String, groupId: String, limit: Int = 50): Flow<List<StagingSubmissionEntity>>
}
