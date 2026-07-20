package com.achulkov.evomolior.substrate.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.achulkov.evomolior.substrate.db.entity.PublicationStateEventEntity
import com.achulkov.evomolior.substrate.db.entity.FinalCandidateRecordEntity

/** Append-only persistence boundary for strict publication transitions. */
@Dao
interface PublicationStateDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFinalCandidates(records: List<FinalCandidateRecordEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: PublicationStateEventEntity)

    @Query(
        """
        SELECT * FROM publication_state_events
        WHERE runId = :runId
        ORDER BY sequence DESC
        LIMIT 1
        """
    )
    suspend fun latest(runId: String): PublicationStateEventEntity?

    @Query(
        """
        SELECT * FROM publication_state_events
        WHERE runId = :runId
        ORDER BY sequence ASC
        """
    )
    suspend fun history(runId: String): List<PublicationStateEventEntity>

    @Query(
        """
        SELECT * FROM final_candidate_records
        WHERE runId = :runId
        ORDER BY groupId ASC
        """
    )
    suspend fun finalCandidates(runId: String): List<FinalCandidateRecordEntity>

    /** Inserts [event] only if [expectedPreviousHash] still names the latest transition. */
    @Transaction
    suspend fun append(
        event: PublicationStateEventEntity,
        expectedPreviousHash: String?
    ) {
        check(latest(event.runId)?.eventHash == expectedPreviousHash) {
            "Publication state changed before transition commit"
        }
        insert(event)
    }
}
