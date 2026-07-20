package com.achulkov.evomolior.substrate.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.achulkov.evomolior.substrate.db.entity.NoveltyGraderAssessmentEntity
import com.achulkov.evomolior.substrate.db.entity.NoveltyScreeningResultEntity
import com.achulkov.evomolior.substrate.db.entity.NoveltyVectorProposalEntity
import com.achulkov.evomolior.substrate.db.entity.OutlierDownstreamLineageEntity
import com.achulkov.evomolior.substrate.db.entity.OutlierFitnessRecordEntity
import com.achulkov.evomolior.substrate.db.entity.ShadowNodeGraderVerdictEntity
import com.achulkov.evomolior.substrate.db.entity.ShadowNodeLifecycleEventEntity

@Dao
interface OutlierNoveltyDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProposal(entity: NoveltyVectorProposalEntity)

    @Query("SELECT * FROM novelty_vector_proposals WHERE proposalId = :proposalId")
    suspend fun proposalById(proposalId: String): NoveltyVectorProposalEntity?

    @Query("SELECT * FROM novelty_vector_proposals WHERE proposalHash = :proposalHash")
    suspend fun proposalByHash(proposalHash: String): NoveltyVectorProposalEntity?

    @Transaction
    suspend fun insertInitialProposal(
        proposal: NoveltyVectorProposalEntity,
        lifecycleEvent: ShadowNodeLifecycleEventEntity
    ) {
        insertProposal(proposal)
        insertLifecycleEvent(lifecycleEvent)
    }

    @Query(
        """
        SELECT * FROM novelty_vector_proposals
        WHERE runId = :runId AND round = :round
        ORDER BY proposalId ASC
        """
    )
    suspend fun proposalsForRound(runId: String, round: Int): List<NoveltyVectorProposalEntity>

    @Query(
        """
        SELECT * FROM novelty_vector_proposals
        WHERE runId = :runId AND round < :round
        ORDER BY round ASC, proposalId ASC
        """
    )
    suspend fun proposalsBeforeRound(
        runId: String,
        round: Int
    ): List<NoveltyVectorProposalEntity>

    @Query(
        """
        SELECT * FROM novelty_vector_proposals
        WHERE runId = :runId
        ORDER BY round ASC, proposalId ASC
        """
    )
    suspend fun proposalsForRun(runId: String): List<NoveltyVectorProposalEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertScreening(entity: NoveltyScreeningResultEntity)

    @Query("SELECT * FROM novelty_screening_results WHERE proposalHash = :proposalHash")
    suspend fun screeningByProposalHash(proposalHash: String): NoveltyScreeningResultEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAssessment(entity: NoveltyGraderAssessmentEntity)

    @Query(
        """
        SELECT * FROM novelty_grader_assessments
        WHERE proposalHash = :proposalHash
        ORDER BY graderGroupId ASC
        """
    )
    suspend fun assessmentsForProposal(
        proposalHash: String
    ): List<NoveltyGraderAssessmentEntity>

    @Query(
        """
        SELECT * FROM novelty_grader_assessments
        WHERE proposalHash = :proposalHash AND graderGroupId = :graderGroupId
        """
    )
    suspend fun assessment(
        proposalHash: String,
        graderGroupId: String
    ): NoveltyGraderAssessmentEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVerdict(entity: ShadowNodeGraderVerdictEntity)

    @Query("SELECT * FROM shadow_node_grader_verdicts WHERE proposalHash = :proposalHash")
    suspend fun verdictByProposalHash(proposalHash: String): ShadowNodeGraderVerdictEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLifecycleEvent(entity: ShadowNodeLifecycleEventEntity)

    @Query(
        """
        SELECT * FROM shadow_node_lifecycle_events
        WHERE proposalId = :proposalId
        ORDER BY sequence ASC
        """
    )
    suspend fun lifecycleForProposal(proposalId: String): List<ShadowNodeLifecycleEventEntity>

    @Query("SELECT * FROM shadow_node_lifecycle_events WHERE eventId = :eventId")
    suspend fun lifecycleEventById(eventId: String): ShadowNodeLifecycleEventEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDownstreamLineage(entity: OutlierDownstreamLineageEntity)

    @Query("SELECT * FROM outlier_downstream_lineage WHERE lineageId = :lineageId")
    suspend fun downstreamLineageById(lineageId: String): OutlierDownstreamLineageEntity?

    @Query(
        """
        SELECT * FROM outlier_downstream_lineage
        WHERE proposalId = :proposalId
        ORDER BY observationRound ASC, lineageId ASC
        """
    )
    suspend fun downstreamLineage(proposalId: String): List<OutlierDownstreamLineageEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFitness(entity: OutlierFitnessRecordEntity)

    /**
     * Appends one tested terminal outcome and delayed fitness under one lifecycle guard.
     */
    @Transaction
    suspend fun insertTestOutcome(
        expectedPreviousEventHash: String,
        lifecycleEvents: List<ShadowNodeLifecycleEventEntity>,
        fitness: OutlierFitnessRecordEntity
    ) {
        require(lifecycleEvents.isNotEmpty()) { "Test outcome requires lifecycle events" }
        val current = lifecycleForProposal(lifecycleEvents.first().proposalId).lastOrNull()
        require(current?.eventHash == expectedPreviousEventHash) {
            "Outlier lifecycle changed before test outcome commit"
        }
        lifecycleEvents.forEach { insertLifecycleEvent(it) }
        insertFitness(fitness)
    }

    @Query("SELECT * FROM outlier_fitness_records WHERE recordId = :recordId")
    suspend fun fitnessById(recordId: String): OutlierFitnessRecordEntity?

    @Query(
        """
        SELECT * FROM outlier_fitness_records
        WHERE proposalId = :proposalId
        ORDER BY observationRound ASC
        """
    )
    suspend fun fitnessForProposal(proposalId: String): List<OutlierFitnessRecordEntity>

    @Query(
        """
        SELECT f.* FROM outlier_fitness_records f
        INNER JOIN novelty_vector_proposals p ON p.proposalId = f.proposalId
        WHERE p.runId = :runId AND f.observationRound <= :throughRound
        ORDER BY f.observationRound ASC, f.recordId ASC
        """
    )
    suspend fun fitnessThroughRound(
        runId: String,
        throughRound: Int
    ): List<OutlierFitnessRecordEntity>
}
