package com.achulkov.evomolior.substrate.db

import androidx.room.Database
import androidx.room.AutoMigration
import androidx.room.RoomDatabase
import com.achulkov.evomolior.substrate.db.dao.AtlasDao
import com.achulkov.evomolior.substrate.db.dao.BoundedCompoundingLedgerDao
import com.achulkov.evomolior.substrate.db.dao.DeadEndsDao
import com.achulkov.evomolior.substrate.db.dao.ContributionGraphDao
import com.achulkov.evomolior.substrate.db.dao.CausalFitnessDao
import com.achulkov.evomolior.substrate.db.dao.CompletionPersistenceDao
import com.achulkov.evomolior.substrate.db.dao.CostLedgerDao
import com.achulkov.evomolior.substrate.db.dao.EventLogDao
import com.achulkov.evomolior.substrate.db.dao.GraderAuthorityDao
import com.achulkov.evomolior.substrate.db.dao.KGSchemaDao
import com.achulkov.evomolior.substrate.db.dao.KgFactAttemptDao
import com.achulkov.evomolior.substrate.db.dao.KgSchemaConstraintDao
import com.achulkov.evomolior.substrate.db.dao.KnowledgeBaseDao
import com.achulkov.evomolior.substrate.db.dao.KnowledgeGraphFactDao
import com.achulkov.evomolior.substrate.db.dao.LegacyProtocolDao
import com.achulkov.evomolior.substrate.db.dao.MetaArchiveDao
import com.achulkov.evomolior.substrate.db.dao.NodeCaptureDao
import com.achulkov.evomolior.substrate.db.dao.OutlierNoveltyDao
import com.achulkov.evomolior.substrate.db.dao.PlanRegistryDao
import com.achulkov.evomolior.substrate.db.dao.PublicationStateDao
import com.achulkov.evomolior.substrate.db.dao.RunConfigDao
import com.achulkov.evomolior.substrate.db.dao.SealedTestSuiteDao
import com.achulkov.evomolior.substrate.db.dao.StagingDao
import com.achulkov.evomolior.substrate.db.dao.ToolRegistryDao
import com.achulkov.evomolior.substrate.db.dao.ToolInfrastructureDao
import com.achulkov.evomolior.substrate.db.dao.TrustScoreDao
import com.achulkov.evomolior.substrate.db.dao.WipTaskDao
import com.achulkov.evomolior.substrate.db.dao.WorkerExecutionDao
import com.achulkov.evomolior.substrate.db.entity.DeadEndsEntity
import com.achulkov.evomolior.substrate.db.entity.AtlasAmendmentEntity
import com.achulkov.evomolior.substrate.db.entity.AtlasCommitEntity
import com.achulkov.evomolior.substrate.db.entity.AtlasExecutionStateEventEntity
import com.achulkov.evomolior.substrate.db.entity.AtlasProjectionEntity
import com.achulkov.evomolior.substrate.db.entity.AtlasVersionEntity
import com.achulkov.evomolior.substrate.db.entity.CompoundingOutlierExperimentEntity
import com.achulkov.evomolior.substrate.db.entity.CompoundingRepairWorkItemEntity
import com.achulkov.evomolior.substrate.db.entity.CompoundingRewardArtifactDeltaEntity
import com.achulkov.evomolior.substrate.db.entity.CompoundingRewardSpendEntity
import com.achulkov.evomolior.substrate.db.entity.EventLogEntryEntity
import com.achulkov.evomolior.substrate.db.entity.EvaluationIndependenceProfileEntity
import com.achulkov.evomolior.substrate.db.entity.GraderAtlasVersionEntity
import com.achulkov.evomolior.substrate.db.entity.GraderCriteriaFreezeEntity
import com.achulkov.evomolior.substrate.db.entity.GraderFitnessRecordEntity
import com.achulkov.evomolior.substrate.db.entity.GraderNodeLeaseEntity
import com.achulkov.evomolior.substrate.db.entity.GraderNodeStateEventEntity
import com.achulkov.evomolior.substrate.db.entity.GraderScoreBundleEntity
import com.achulkov.evomolior.substrate.db.entity.GradingRequirementEntity
import com.achulkov.evomolior.substrate.db.entity.HiddenTestDefinitionEntity
import com.achulkov.evomolior.substrate.db.entity.KGSchemaEntity
import com.achulkov.evomolior.substrate.db.entity.KgFactAttemptEntity
import com.achulkov.evomolior.substrate.db.entity.KgSchemaConstraintEntity
import com.achulkov.evomolior.substrate.db.entity.KnowledgeBaseEntryEntity
import com.achulkov.evomolior.substrate.db.entity.KnowledgeGraphFactEntity
import com.achulkov.evomolior.substrate.db.entity.LegacyProtocolEntity
import com.achulkov.evomolior.substrate.db.entity.MetaArchiveEntryEntity
import com.achulkov.evomolior.substrate.db.entity.NodeCaptureDecisionEntity
import com.achulkov.evomolior.substrate.db.entity.NodeEvidencePacketEntity
import com.achulkov.evomolior.substrate.db.entity.NodeGradeBundleEntity
import com.achulkov.evomolior.substrate.db.entity.NodeYieldApplicationEntity
import com.achulkov.evomolior.substrate.db.entity.NodeYieldScheduleEntity
import com.achulkov.evomolior.substrate.db.entity.RewardUseLineageEventEntity
import com.achulkov.evomolior.substrate.db.entity.RunConfigEntity
import com.achulkov.evomolior.substrate.db.entity.SealedTestSuiteEntity
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.db.entity.ToolRegistryEntryEntity
import com.achulkov.evomolior.substrate.db.entity.TrustScoreEntity
import com.achulkov.evomolior.substrate.db.entity.WipTaskEntity
import com.achulkov.evomolior.substrate.db.entity.AgentExitRecordEntity
import com.achulkov.evomolior.substrate.db.entity.AgentLifecycleRegistrationEntity
import com.achulkov.evomolior.substrate.db.entity.AgentRuntimeStateEventEntity
import com.achulkov.evomolior.substrate.db.entity.ContributionCommitEntity
import com.achulkov.evomolior.substrate.db.entity.ContributionEdgeEntity
import com.achulkov.evomolior.substrate.db.entity.CriticDecisionEntity
import com.achulkov.evomolior.substrate.db.entity.CausalFitnessRecordEntity
import com.achulkov.evomolior.substrate.db.entity.CostLedgerEntryEntity
import com.achulkov.evomolior.substrate.db.entity.DecisionPackageEntity
import com.achulkov.evomolior.substrate.db.entity.DownstreamUseRecordEntity
import com.achulkov.evomolior.substrate.db.entity.DeliverableBlueprintEntity
import com.achulkov.evomolior.substrate.db.entity.IntegrationNodeRecordEntity
import com.achulkov.evomolior.substrate.db.entity.InterimDeliverableSnapshotEntity
import com.achulkov.evomolior.substrate.db.entity.FinalDeliverableReferenceRecordEntity
import com.achulkov.evomolior.substrate.db.entity.ReceiverScoreContributionRecordEntity
import com.achulkov.evomolior.substrate.db.entity.BenchmarkScoreContributionRecordEntity
import com.achulkov.evomolior.substrate.db.entity.ControlInterventionRecordEntity
import com.achulkov.evomolior.substrate.db.entity.SummaryValidationRecordEntity
import com.achulkov.evomolior.substrate.db.entity.DegradedExecutionRecordEntity
import com.achulkov.evomolior.substrate.db.entity.TtlCheckpointEventEntity
import com.achulkov.evomolior.substrate.db.entity.NoveltyVectorProposalEntity
import com.achulkov.evomolior.substrate.db.entity.NoveltyScreeningResultEntity
import com.achulkov.evomolior.substrate.db.entity.NoveltyGraderAssessmentEntity
import com.achulkov.evomolior.substrate.db.entity.ShadowNodeGraderVerdictEntity
import com.achulkov.evomolior.substrate.db.entity.ShadowNodeLifecycleEventEntity
import com.achulkov.evomolior.substrate.db.entity.OutlierDownstreamLineageEntity
import com.achulkov.evomolior.substrate.db.entity.OutlierFitnessRecordEntity
import com.achulkov.evomolior.substrate.db.entity.PublicationStateEventEntity
import com.achulkov.evomolior.substrate.db.entity.PublicationOutcomeEventEntity
import com.achulkov.evomolior.substrate.db.entity.RoundPhaseEventEntity
import com.achulkov.evomolior.substrate.db.entity.FinalCandidateRecordEntity
import com.achulkov.evomolior.substrate.db.entity.SimulationRunRecordEntity
import com.achulkov.evomolior.substrate.db.entity.ToolExecutionRecordEntity
import com.achulkov.evomolior.substrate.db.entity.ToolFitnessRecordEntity
import com.achulkov.evomolior.substrate.db.entity.ToolLifecycleEventEntity
import com.achulkov.evomolior.substrate.db.entity.ToolOpportunityCommitmentEntity
import com.achulkov.evomolior.substrate.db.entity.ToolRepairAttemptEntity
import com.achulkov.evomolior.substrate.db.entity.ToolRepairDiagnosticEntity
import com.achulkov.evomolior.substrate.db.entity.ToolReuseLineageEntity
import com.achulkov.evomolior.substrate.db.entity.NodePortfolioEntity

/**
 * Per-group Room database for EvoMolior substrate storage.
 *
 * One instance is created per (runId, groupId) pair. DB file path:
 * `{appDataDir}/evomolior/runs/{runId}/groups/{groupId}/substrate.db`
 *
 * The global [RunConfigDao] is also accessible here — `run_configs` is read/written by the
 * LifecycleManager and the desktop control panel.
 *
 * Created via [SubstrateDatabaseFactory].
 *
 * @see SubstrateDatabaseFactory
 */
@Database(
    entities = [
        KnowledgeBaseEntryEntity::class,
        KnowledgeGraphFactEntity::class,
        ToolRegistryEntryEntity::class,
        StagingSubmissionEntity::class,
        WipTaskEntity::class,
        EventLogEntryEntity::class,
        LegacyProtocolEntity::class,
        TrustScoreEntity::class,
        DeadEndsEntity::class,
        KGSchemaEntity::class,
        KgSchemaConstraintEntity::class,
        MetaArchiveEntryEntity::class,
        SealedTestSuiteEntity::class,
        RunConfigEntity::class,
        AtlasVersionEntity::class,
        AtlasAmendmentEntity::class,
        AtlasCommitEntity::class,
        AtlasExecutionStateEventEntity::class,
        AtlasProjectionEntity::class,
        ContributionCommitEntity::class,
        ContributionEdgeEntity::class,
        CriticDecisionEntity::class,
        CausalFitnessRecordEntity::class,
        CostLedgerEntryEntity::class,
        DownstreamUseRecordEntity::class,
        DegradedExecutionRecordEntity::class,
        TtlCheckpointEventEntity::class,
        AgentExitRecordEntity::class,
        AgentRuntimeStateEventEntity::class,
        NodeEvidencePacketEntity::class,
        NodeGradeBundleEntity::class,
        NodeCaptureDecisionEntity::class,
        NodeYieldScheduleEntity::class,
        NodeYieldApplicationEntity::class,
        RewardUseLineageEventEntity::class,
        GraderAtlasVersionEntity::class,
        GraderNodeStateEventEntity::class,
        GraderNodeLeaseEntity::class,
        GradingRequirementEntity::class,
        HiddenTestDefinitionEntity::class,
        GraderCriteriaFreezeEntity::class,
        GraderScoreBundleEntity::class,
        GraderFitnessRecordEntity::class,
        EvaluationIndependenceProfileEntity::class,
        NoveltyVectorProposalEntity::class,
        NoveltyScreeningResultEntity::class,
        NoveltyGraderAssessmentEntity::class,
        ShadowNodeGraderVerdictEntity::class,
        ShadowNodeLifecycleEventEntity::class,
        OutlierDownstreamLineageEntity::class,
        OutlierFitnessRecordEntity::class,
        ToolOpportunityCommitmentEntity::class,
        ToolLifecycleEventEntity::class,
        ToolExecutionRecordEntity::class,
        SimulationRunRecordEntity::class,
        ToolReuseLineageEntity::class,
        ToolFitnessRecordEntity::class,
        IntegrationNodeRecordEntity::class,
        AgentLifecycleRegistrationEntity::class,
        PublicationStateEventEntity::class,
        FinalCandidateRecordEntity::class,
        FinalDeliverableReferenceRecordEntity::class,
        ReceiverScoreContributionRecordEntity::class,
        BenchmarkScoreContributionRecordEntity::class,
        ControlInterventionRecordEntity::class,
        SummaryValidationRecordEntity::class,
        NodePortfolioEntity::class,
        KgFactAttemptEntity::class,
        ToolRepairDiagnosticEntity::class,
        ToolRepairAttemptEntity::class,
        DeliverableBlueprintEntity::class,
        InterimDeliverableSnapshotEntity::class,
        DecisionPackageEntity::class,
        PublicationOutcomeEventEntity::class,
        RoundPhaseEventEntity::class,
        CompoundingRepairWorkItemEntity::class,
        CompoundingOutlierExperimentEntity::class,
        CompoundingRewardSpendEntity::class,
        CompoundingRewardArtifactDeltaEntity::class
    ],
    version = 25,
    autoMigrations = [
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 11, to = 12),
        AutoMigration(from = 12, to = 13),
        AutoMigration(from = 13, to = 14),
        AutoMigration(from = 14, to = 15),
        AutoMigration(from = 15, to = 16),
        AutoMigration(from = 16, to = 17),
        AutoMigration(from = 17, to = 18),
        AutoMigration(from = 18, to = 19),
        AutoMigration(from = 19, to = 20),
        AutoMigration(from = 20, to = 21),
        AutoMigration(from = 21, to = 22),
        AutoMigration(from = 22, to = 23),
        AutoMigration(from = 23, to = 24),
        AutoMigration(from = 24, to = 25)
    ],
    exportSchema = true
)
abstract class EvoMoliorDatabase : RoomDatabase() {
    abstract fun knowledgeBaseDao(): KnowledgeBaseDao
    abstract fun knowledgeGraphFactDao(): KnowledgeGraphFactDao
    abstract fun stagingDao(): StagingDao
    abstract fun wipTaskDao(): WipTaskDao
    abstract fun eventLogDao(): EventLogDao
    abstract fun legacyProtocolDao(): LegacyProtocolDao
    abstract fun toolRegistryDao(): ToolRegistryDao
    abstract fun trustScoreDao(): TrustScoreDao
    abstract fun deadEndsDao(): DeadEndsDao
    abstract fun kgSchemaDao(): KGSchemaDao
    abstract fun kgFactAttemptDao(): KgFactAttemptDao
    abstract fun kgSchemaConstraintDao(): KgSchemaConstraintDao
    abstract fun metaArchiveDao(): MetaArchiveDao
    abstract fun planRegistryDao(): PlanRegistryDao
    abstract fun sealedTestSuiteDao(): SealedTestSuiteDao
    abstract fun runConfigDao(): RunConfigDao
    abstract fun workerExecutionDao(): WorkerExecutionDao
    abstract fun publicationStateDao(): PublicationStateDao
    abstract fun contributionGraphDao(): ContributionGraphDao
    abstract fun causalFitnessDao(): CausalFitnessDao
    abstract fun costLedgerDao(): CostLedgerDao
    abstract fun outlierNoveltyDao(): OutlierNoveltyDao
    abstract fun toolInfrastructureDao(): ToolInfrastructureDao
    abstract fun completionPersistenceDao(): CompletionPersistenceDao
    abstract fun boundedCompoundingLedgerDao(): BoundedCompoundingLedgerDao
    internal abstract fun atlasDao(): AtlasDao
    internal abstract fun nodeCaptureDao(): NodeCaptureDao
    internal abstract fun graderAuthorityDao(): GraderAuthorityDao
}
