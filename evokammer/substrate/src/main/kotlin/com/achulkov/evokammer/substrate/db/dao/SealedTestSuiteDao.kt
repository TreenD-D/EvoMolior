package com.achulkov.evokammer.substrate.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.achulkov.evokammer.substrate.db.entity.SealedTestSuiteEntity

/** Room DAO for [SealedTestSuiteEntity]. */
@Dao
interface SealedTestSuiteDao {

    /**
     * Inserts the sealed test suite if it does not already exist.
     *
     * The unique `(runId, groupId)` constraint keeps the suite write-once per run.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: SealedTestSuiteEntity)

    /** Returns the sealed suite for a run/group pair, if present. */
    @Query("SELECT * FROM sealed_test_suites WHERE runId = :runId AND groupId = :groupId LIMIT 1")
    suspend fun get(runId: String, groupId: String): SealedTestSuiteEntity?
}
