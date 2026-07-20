package com.achulkov.evomolior.substrate.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.achulkov.evomolior.substrate.db.entity.CriticDecisionEntity
import com.achulkov.evomolior.substrate.db.entity.ContributionEdgeEntity
import com.achulkov.evomolior.substrate.db.entity.DownstreamUseRecordEntity
import com.achulkov.evomolior.substrate.db.entity.IntegrationNodeRecordEntity
import com.achulkov.evomolior.substrate.db.entity.FinalDeliverableReferenceRecordEntity
import com.achulkov.evomolior.substrate.db.entity.ReceiverScoreContributionRecordEntity
import com.achulkov.evomolior.substrate.db.entity.BenchmarkScoreContributionRecordEntity
import com.achulkov.evomolior.substrate.db.entity.ControlInterventionRecordEntity
import com.achulkov.evomolior.substrate.db.entity.SummaryValidationRecordEntity

/** Append-only persistence boundary for typed causal contribution edges. */
@Dao
interface ContributionGraphDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(edge: ContributionEdgeEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(edges: List<ContributionEdgeEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIdempotent(edges: List<ContributionEdgeEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCriticDecision(decision: CriticDecisionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDownstreamUse(record: DownstreamUseRecordEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertIntegrationNode(record: IntegrationNodeRecordEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFinalDeliverableReference(record: FinalDeliverableReferenceRecordEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReceiverScoreContribution(record: ReceiverScoreContributionRecordEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBenchmarkScoreContribution(record: BenchmarkScoreContributionRecordEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertControlIntervention(record: ControlInterventionRecordEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSummaryValidation(record: SummaryValidationRecordEntity)

    /** Atomically persists one downstream-use authority and its canonical lineage edge. */
    @Transaction
    suspend fun insertDownstreamUseWithEdge(
        record: DownstreamUseRecordEntity,
        edge: ContributionEdgeEntity
    ) {
        insertDownstreamUse(record)
        insert(edge)
    }

    /** Atomically persists one integration authority and every accepted input edge. */
    @Transaction
    suspend fun insertIntegrationNodeWithEdges(
        record: IntegrationNodeRecordEntity,
        edges: List<ContributionEdgeEntity>
    ) {
        insertIntegrationNode(record)
        insertAll(edges)
    }

    /** Atomically persists one final-output reference and its admitted-source edge. */
    @Transaction
    suspend fun insertFinalDeliverableReferenceWithEdge(
        record: FinalDeliverableReferenceRecordEntity,
        edge: ContributionEdgeEntity
    ) {
        insertFinalDeliverableReference(record)
        insert(edge)
    }

    /** Atomically persists one receiver-score component and its output-reference edge. */
    @Transaction
    suspend fun insertReceiverScoreContributionWithEdge(
        record: ReceiverScoreContributionRecordEntity,
        edge: ContributionEdgeEntity
    ) {
        insertReceiverScoreContribution(record)
        insert(edge)
    }

    /** Atomically persists one benchmark-score component and its output-reference edge. */
    @Transaction
    suspend fun insertBenchmarkScoreContributionWithEdge(
        record: BenchmarkScoreContributionRecordEntity,
        edge: ContributionEdgeEntity
    ) {
        insertBenchmarkScoreContribution(record)
        insert(edge)
    }

    /** Atomically persists one final Critic authority and all edges it authorizes. */
    @Transaction
    suspend fun insertCriticDecisionWithEdges(
        decision: CriticDecisionEntity,
        edges: List<ContributionEdgeEntity>
    ) {
        insertCriticDecision(decision)
        insertAll(edges)
    }

    @Query("SELECT * FROM critic_decisions WHERE decisionId = :decisionId LIMIT 1")
    suspend fun criticDecisionById(decisionId: String): CriticDecisionEntity?

    @Query(
        """
        SELECT * FROM critic_decisions
        WHERE runId = :runId AND groupId = :groupId AND submissionId = :submissionId
        LIMIT 1
        """
    )
    suspend fun criticDecisionForSubmission(
        runId: String,
        groupId: String,
        submissionId: String
    ): CriticDecisionEntity?

    @Query(
        """
        SELECT * FROM critic_decisions
        WHERE runId = :runId AND groupId = :groupId AND resultAuthorityId = :authorityId
        ORDER BY decidedAtMs ASC
        LIMIT 1
        """
    )
    suspend fun criticDecisionForResult(
        runId: String,
        groupId: String,
        authorityId: String
    ): CriticDecisionEntity?

    @Query(
        """
        SELECT * FROM critic_decisions
        WHERE runId = :runId AND groupId = :groupId AND round = :round
        ORDER BY decidedAtMs ASC, decisionId ASC
        """
    )
    suspend fun criticDecisionsForRound(
        runId: String,
        groupId: String,
        round: Int
    ): List<CriticDecisionEntity>

    @Query(
        """
        SELECT * FROM critic_decisions
        WHERE runId = :runId AND groupId = :groupId AND resultAuthorityId IS NOT NULL
        ORDER BY round ASC, decidedAtMs ASC, decisionId ASC
        """
    )
    suspend fun promotedCriticDecisionsForRun(
        runId: String,
        groupId: String
    ): List<CriticDecisionEntity>

    @Query("SELECT * FROM downstream_use_records WHERE useId = :useId LIMIT 1")
    suspend fun downstreamUseById(useId: String): DownstreamUseRecordEntity?

    @Query("SELECT * FROM integration_node_records WHERE integrationId = :integrationId LIMIT 1")
    suspend fun integrationNodeById(integrationId: String): IntegrationNodeRecordEntity?

    @Query(
        """
        SELECT * FROM integration_node_records
        WHERE runId = :runId AND groupId = :groupId
        ORDER BY round ASC, integratedAtMs ASC, integrationId ASC
        """
    )
    suspend fun integrationNodesForRun(
        runId: String,
        groupId: String
    ): List<IntegrationNodeRecordEntity>

    @Query(
        "SELECT * FROM final_deliverable_reference_records WHERE referenceId = :referenceId LIMIT 1"
    )
    suspend fun finalDeliverableReferenceById(
        referenceId: String
    ): FinalDeliverableReferenceRecordEntity?

    @Query(
        """
        SELECT * FROM final_deliverable_reference_records
        WHERE runId = :runId AND groupId = :groupId
        ORDER BY relativePath ASC, referenceId ASC
        """
    )
    suspend fun finalDeliverableReferencesForRun(
        runId: String,
        groupId: String
    ): List<FinalDeliverableReferenceRecordEntity>

    @Query(
        "SELECT * FROM receiver_score_contribution_records WHERE contributionId = :contributionId LIMIT 1"
    )
    suspend fun receiverScoreContributionById(
        contributionId: String
    ): ReceiverScoreContributionRecordEntity?

    @Query(
        "SELECT * FROM benchmark_score_contribution_records WHERE contributionId = :contributionId LIMIT 1"
    )
    suspend fun benchmarkScoreContributionById(
        contributionId: String
    ): BenchmarkScoreContributionRecordEntity?

    @Query(
        "SELECT * FROM control_intervention_records WHERE interventionId = :interventionId LIMIT 1"
    )
    suspend fun controlInterventionById(
        interventionId: String
    ): ControlInterventionRecordEntity?

    @Query(
        """
        SELECT * FROM control_intervention_records
        WHERE runId = :runId AND groupId = :groupId AND round <= :throughRound
        ORDER BY round ASC, occurredAtMs ASC, interventionId ASC
        """
    )
    suspend fun controlInterventionsThroughRound(
        runId: String,
        groupId: String,
        throughRound: Int
    ): List<ControlInterventionRecordEntity>

    @Query(
        "SELECT * FROM summary_validation_records WHERE validationId = :validationId LIMIT 1"
    )
    suspend fun summaryValidationById(validationId: String): SummaryValidationRecordEntity?

    @Query(
        """
        SELECT * FROM summary_validation_records
        WHERE runId = :runId AND groupId = :groupId AND round <= :throughRound
        ORDER BY round ASC, validatedAtMs ASC, validationId ASC
        """
    )
    suspend fun summaryValidationsThroughRound(
        runId: String,
        groupId: String,
        throughRound: Int
    ): List<SummaryValidationRecordEntity>

    @Query("SELECT * FROM contribution_edges WHERE edgeId = :edgeId LIMIT 1")
    suspend fun byId(edgeId: String): ContributionEdgeEntity?

    @Query(
        """
        SELECT * FROM contribution_edges
        WHERE runId = :runId AND groupId = :groupId
        ORDER BY round ASC, occurredAtMs ASC, edgeId ASC
        """
    )
    suspend fun forRun(runId: String, groupId: String): List<ContributionEdgeEntity>

    @Query(
        """
        SELECT * FROM contribution_edges
        WHERE runId = :runId AND groupId = :groupId
          AND sourceKind = :kind AND sourceAuthorityId = :authorityId
        ORDER BY round ASC, occurredAtMs ASC, edgeId ASC
        """
    )
    suspend fun fromAuthority(
        runId: String,
        groupId: String,
        kind: String,
        authorityId: String
    ): List<ContributionEdgeEntity>

    @Query(
        """
        SELECT * FROM contribution_edges
        WHERE runId = :runId AND groupId = :groupId
          AND targetKind = :kind AND targetAuthorityId = :authorityId
        ORDER BY round ASC, occurredAtMs ASC, edgeId ASC
        """
    )
    suspend fun toAuthority(
        runId: String,
        groupId: String,
        kind: String,
        authorityId: String
    ): List<ContributionEdgeEntity>
}
