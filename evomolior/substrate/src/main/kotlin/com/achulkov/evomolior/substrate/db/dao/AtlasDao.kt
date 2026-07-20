package com.achulkov.evomolior.substrate.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.achulkov.evomolior.substrate.db.entity.AtlasAmendmentEntity
import com.achulkov.evomolior.substrate.db.entity.AtlasCommitEntity
import com.achulkov.evomolior.substrate.db.entity.AtlasExecutionStateEventEntity
import com.achulkov.evomolior.substrate.db.entity.AtlasProjectionEntity
import com.achulkov.evomolior.substrate.db.entity.AtlasVersionEntity
import com.achulkov.evomolior.substrate.db.entity.GraderAtlasVersionEntity
import com.achulkov.evomolior.substrate.db.entity.NodeCaptureDecisionEntity
import com.achulkov.evomolior.substrate.db.entity.NodeYieldApplicationEntity
import com.achulkov.evomolior.substrate.db.entity.OutlierFitnessRecordEntity
import com.achulkov.evomolior.substrate.db.entity.ShadowNodeLifecycleEventEntity
import com.achulkov.evomolior.substrate.db.entity.WipTaskEntity

/**
 * Internal append-only persistence boundary used exclusively by the Atlas repository.
 *
 * Mutation methods intentionally remain unavailable outside the substrate module. Legacy
 * consumers can read projections through repository APIs but cannot write Atlas authority rows.
 */
@Dao
internal interface AtlasDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVersion(version: AtlasVersionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAmendment(amendment: AtlasAmendmentEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCommit(commit: AtlasCommitEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertStateEvents(events: List<AtlasExecutionStateEventEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProjections(projections: List<AtlasProjectionEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCaptureDecision(decision: NodeCaptureDecisionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertYieldApplication(application: NodeYieldApplicationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceWipProjections(tasks: List<WipTaskEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGraderAtlasVersion(version: GraderAtlasVersionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertShadowNodeLifecycleEvents(events: List<ShadowNodeLifecycleEventEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOutlierFitness(record: OutlierFitnessRecordEntity)

    /**
     * Appends authority records and refreshes legacy WIP rows as one SQLite transaction.
     */
    @Transaction
    suspend fun appendTransaction(
        commit: AtlasCommitEntity,
        version: AtlasVersionEntity?,
        amendment: AtlasAmendmentEntity?,
        captureDecision: NodeCaptureDecisionEntity?,
        yieldApplication: NodeYieldApplicationEntity?,
        stateEvents: List<AtlasExecutionStateEventEntity>,
        projections: List<AtlasProjectionEntity>,
        wipTasks: List<WipTaskEntity>
    ) {
        insertCommit(commit)
        version?.let { insertVersion(it) }
        amendment?.let { insertAmendment(it) }
        captureDecision?.let { insertCaptureDecision(it) }
        yieldApplication?.let { insertYieldApplication(it) }
        if (stateEvents.isNotEmpty()) insertStateEvents(stateEvents)
        if (projections.isNotEmpty()) insertProjections(projections)
        if (wipTasks.isNotEmpty()) replaceWipProjections(wipTasks)
    }

    /**
     * Appends matched worker/grader Atlas versions and their accepted Outlier lifecycle atomically.
     */
    @Transaction
    suspend fun appendAmendmentAuthorityTransition(
        expectedWorkerAtlasHash: String,
        expectedWorkerAtlasVersion: Int,
        expectedCommitSequence: Long,
        expectedGraderAtlasHash: String,
        expectedGraderAtlasVersion: Int,
        expectedLifecycleEventHash: String,
        commit: AtlasCommitEntity,
        version: AtlasVersionEntity,
        amendment: AtlasAmendmentEntity,
        stateEvents: List<AtlasExecutionStateEventEntity>,
        projections: List<AtlasProjectionEntity>,
        wipTasks: List<WipTaskEntity>,
        graderAtlasVersion: GraderAtlasVersionEntity,
        lifecycleEvents: List<ShadowNodeLifecycleEventEntity>,
        fitnessRecord: OutlierFitnessRecordEntity
    ) {
        val currentWorker = requireNotNull(latestVersion(version.atlasId)) {
            "Worker Atlas authority disappeared before amendment commit"
        }
        require(
            currentWorker.atlasHash == expectedWorkerAtlasHash &&
                currentWorker.version == expectedWorkerAtlasVersion
        ) {
            "Worker Atlas authority changed before amendment commit"
        }
        require(latestCommit(version.atlasId)?.commitSequence == expectedCommitSequence) {
            "Worker Atlas commit sequence changed before amendment commit"
        }
        val currentGrader = requireNotNull(latestGraderAtlasForRun(version.runId)) {
            "Grader Atlas authority disappeared before amendment commit"
        }
        require(
            currentGrader.atlasHash == expectedGraderAtlasHash &&
                currentGrader.version == expectedGraderAtlasVersion
        ) {
            "Grader Atlas authority changed before amendment commit"
        }
        val currentLifecycle = requireNotNull(
            latestShadowNodeLifecycleEvent(lifecycleEvents.first().proposalId)
        ) {
            "Outlier lifecycle authority disappeared before amendment commit"
        }
        require(currentLifecycle.eventHash == expectedLifecycleEventHash) {
            "Outlier lifecycle changed before amendment commit"
        }

        insertCommit(commit)
        insertVersion(version)
        insertAmendment(amendment)
        if (stateEvents.isNotEmpty()) insertStateEvents(stateEvents)
        if (projections.isNotEmpty()) insertProjections(projections)
        if (wipTasks.isNotEmpty()) replaceWipProjections(wipTasks)
        insertGraderAtlasVersion(graderAtlasVersion)
        insertShadowNodeLifecycleEvents(lifecycleEvents)
        insertOutlierFitness(fitnessRecord)
    }

    @Query(
        """
        SELECT * FROM atlas_versions
        WHERE atlasId = :atlasId
        ORDER BY version DESC
        LIMIT 1
        """
    )
    suspend fun latestVersion(atlasId: String): AtlasVersionEntity?

    @Query(
        """
        SELECT * FROM atlas_versions
        WHERE runId = :runId
        ORDER BY createdAtMs DESC, version DESC
        LIMIT 1
        """
    )
    suspend fun latestVersionForRun(runId: String): AtlasVersionEntity?

    @Query(
        """
        SELECT * FROM grader_atlas_versions
        WHERE runId = :runId
        ORDER BY version DESC, createdAtMs DESC
        LIMIT 1
        """
    )
    suspend fun latestGraderAtlasForRun(runId: String): GraderAtlasVersionEntity?

    @Query(
        """
        SELECT * FROM shadow_node_lifecycle_events
        WHERE proposalId = :proposalId
        ORDER BY sequence DESC
        LIMIT 1
        """
    )
    suspend fun latestShadowNodeLifecycleEvent(
        proposalId: String
    ): ShadowNodeLifecycleEventEntity?

    @Query(
        """
        SELECT * FROM atlas_versions
        WHERE atlasId = :atlasId
        ORDER BY version ASC
        """
    )
    suspend fun versionHistory(atlasId: String): List<AtlasVersionEntity>

    @Query("SELECT * FROM atlas_versions WHERE atlasHash = :atlasHash LIMIT 1")
    suspend fun versionByHash(atlasHash: String): AtlasVersionEntity?

    @Query(
        """
        SELECT * FROM atlas_amendments
        WHERE atlasId = :atlasId
        ORDER BY toVersion ASC
        """
    )
    suspend fun amendmentHistory(atlasId: String): List<AtlasAmendmentEntity>

    @Query(
        """
        SELECT * FROM atlas_commits
        WHERE atlasId = :atlasId
        ORDER BY commitSequence DESC
        LIMIT 1
        """
    )
    suspend fun latestCommit(atlasId: String): AtlasCommitEntity?

    @Query(
        """
        SELECT event.* FROM atlas_execution_state_events AS event
        INNER JOIN (
            SELECT nodeId, MAX(stateRevision) AS latestRevision
            FROM atlas_execution_state_events
            WHERE atlasId = :atlasId
            GROUP BY nodeId
        ) AS latest
        ON event.nodeId = latest.nodeId AND event.stateRevision = latest.latestRevision
        WHERE event.atlasId = :atlasId
        ORDER BY event.nodeId ASC
        """
    )
    suspend fun currentStates(atlasId: String): List<AtlasExecutionStateEventEntity>

    @Query(
        """
        SELECT * FROM atlas_execution_state_events
        WHERE atlasId = :atlasId AND nodeId = :nodeId
        ORDER BY stateRevision ASC
        """
    )
    suspend fun stateHistory(atlasId: String, nodeId: String): List<AtlasExecutionStateEventEntity>

    @Query(
        """
        SELECT * FROM atlas_projections
        WHERE atlasId = :atlasId
          AND projectionType = :projectionType
          AND scopeId = :scopeId
        ORDER BY commitSequence DESC
        LIMIT 1
        """
    )
    suspend fun latestProjection(
        atlasId: String,
        projectionType: String,
        scopeId: String
    ): AtlasProjectionEntity?

    @Query(
        """
        SELECT * FROM atlas_projections
        WHERE atlasId = :atlasId AND commitSequence = :commitSequence
        ORDER BY projectionType ASC, scopeId ASC
        """
    )
    suspend fun projectionsForCommit(
        atlasId: String,
        commitSequence: Long
    ): List<AtlasProjectionEntity>
}
