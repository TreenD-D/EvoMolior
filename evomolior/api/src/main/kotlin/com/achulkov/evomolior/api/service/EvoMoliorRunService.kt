@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.deliverable.SealedRoundDeliverableSnapshotCompiler
import com.achulkov.evomolior.api.deliverable.SealedRoundDeliverableSnapshotRequest
import com.achulkov.evomolior.api.deliverable.AcceptedDecisionPackageCompiler
import com.achulkov.evomolior.api.deliverable.AcceptedDecisionPackageRequest
import com.achulkov.evomolior.api.deliverable.ProvisionalDecisionPackageCompiler
import com.achulkov.evomolior.api.deliverable.ProvisionalDecisionPackageRequest
import com.achulkov.evomolior.api.deliverable.StartupDiagnosticOutcomeCompiler
import com.achulkov.evomolior.api.events.EvoMoliorEvent
import com.achulkov.evomolior.core.model.SubmissionType
import com.achulkov.evomolior.api.grader.GraderCivilizationController
import com.achulkov.evomolior.api.model.FinalOutputKind
import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.api.model.AcceptanceDiagnosticMode
import com.achulkov.evomolior.api.model.OverseerExperimentLaunchRequest
import com.achulkov.evomolior.api.model.OverseerExperimentLaunchResponse
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.api.model.RunInputArtifactUpload
import com.achulkov.evomolior.api.service.NESTED_GROUP_OPEN_TASK_LIMIT
import com.achulkov.evomolior.core.config.LlmTimeoutPolicy
import com.achulkov.evomolior.core.contract.OverseerDeadEnd
import com.achulkov.evomolior.core.contract.OverseerDeadEndHistoryEntry
import com.achulkov.evomolior.core.contract.OverseerKernelAuditActionType
import com.achulkov.evomolior.core.contract.OverseerKernelAuditEntry
import com.achulkov.evomolior.core.contract.OverseerExperimentPlan
import com.achulkov.evomolior.core.contract.OverseerExperimentPlanEligibility
import com.achulkov.evomolior.core.contract.OverseerExperimentPlanHistoryEntry
import com.achulkov.evomolior.core.contract.OverseerExperimentResult
import com.achulkov.evomolior.core.contract.OverseerHypothesis
import com.achulkov.evomolior.core.contract.OverseerKernelEligibility
import com.achulkov.evomolior.core.contract.OverseerKernelState
import com.achulkov.evomolior.core.contract.OverseerKernelArchive
import com.achulkov.evomolior.agents.factory.RolePromptBuilder
import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.atlas.AtlasDisclosureStageAuthority
import com.achulkov.evomolior.competition.atlas.AtlasNodeCaptureAuthority
import com.achulkov.evomolior.competition.atlas.AtlasNodeYieldAuthority
import com.achulkov.evomolior.competition.grader.CanonSnapshotExporter
import com.achulkov.evomolior.competition.disclosure.DisclosureStage
import com.achulkov.evomolior.competition.disclosure.ProgressiveDisclosureEngine
import com.achulkov.evomolior.competition.disclosure.DisclosureAtlasProjectionAdapter
import com.achulkov.evomolior.competition.dispatch.DispatchMessage
import com.achulkov.evomolior.competition.grader.ComparativePair
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.competition.grader.GraderNodeLeaseAuthority
import com.achulkov.evomolior.competition.grader.GradingSheet
import com.achulkov.evomolior.competition.grader.ExactGraderBundleResolver
import com.achulkov.evomolior.competition.grader.ExactGraderRoundStatus
import com.achulkov.evomolior.competition.grader.MetaJudge
import com.achulkov.evomolior.competition.grader.RubricDimension
import com.achulkov.evomolior.competition.resourcemap.NodeClaimEvent
import com.achulkov.evomolior.competition.resourcemap.NodeYieldCandidate
import com.achulkov.evomolior.competition.resourcemap.NodeYieldScheduler
import com.achulkov.evomolior.competition.resourcemap.NodeYieldSchedulingBatch
import com.achulkov.evomolior.competition.resourcemap.LegacyResourceCaptureMode
import com.achulkov.evomolior.competition.resourcemap.ResourceMapEngine
import com.achulkov.evomolior.competition.resourcemap.ResourceAtlasProjectionAdapter
import com.achulkov.evomolior.competition.resourcemap.ResourceNode
import com.achulkov.evomolior.competition.resourcemap.ResourceType
import com.achulkov.evomolior.competition.scoring.FailureTelemetry
import com.achulkov.evomolior.competition.scoring.GroupRoundResult
import com.achulkov.evomolior.competition.scoring.NodeCaptureResolutionRequest
import com.achulkov.evomolior.competition.scoring.NodeCaptureResolver
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.CodexReasoningEffort
import com.achulkov.evomolior.core.config.CouncilExecutionPurpose
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import com.achulkov.evomolior.core.config.ObjectiveExpertRouter
import com.achulkov.evomolior.core.config.RunObjectiveHeuristics
import com.achulkov.evomolior.core.compounding.RepairDiagnosticDisposition
import com.achulkov.evomolior.core.compounding.RepairDiagnosticWorkItemV1
import com.achulkov.evomolior.core.compounding.RewardArtifactDeltaV1
import com.achulkov.evomolior.core.compounding.RewardSpendPurposeKind
import com.achulkov.evomolior.core.compounding.RewardSpendPurposeV1
import com.achulkov.evomolior.core.compounding.RewardSpendRecordV1
import com.achulkov.evomolior.core.atlas.EvidenceKind as AtlasEvidenceKind
import com.achulkov.evomolior.core.atlas.EvidenceProfile as AtlasEvidenceProfile
import com.achulkov.evomolior.core.atlas.FinalOutputSpec as AtlasFinalOutputSpec
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.benchmark.ComparativeRuntimeInputs
import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.InstrumentalRewardType
import com.achulkov.evomolior.core.atlas.NodeEvidencePacket
import com.achulkov.evomolior.core.atlas.NodeGradeBundle
import com.achulkov.evomolior.core.atlas.NodeGradeBundleVerdict
import com.achulkov.evomolior.core.atlas.NodeCaptureDecisionType
import com.achulkov.evomolior.core.atlas.NodeCaptureDecision
import com.achulkov.evomolior.core.atlas.LimitationNode
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.NodeYieldSchedule
import com.achulkov.evomolior.core.atlas.ScheduledReward
import com.achulkov.evomolior.core.atlas.YieldConstraintPolicy
import com.achulkov.evomolior.core.atlas.NodeGradingAssignment
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.RewardUseLineageEvent
import com.achulkov.evomolior.core.atlas.NoveltyGraderAssessment
import com.achulkov.evomolior.core.atlas.NoveltyVectorProposal
import com.achulkov.evomolior.core.atlas.OutlierNoveltyMetrics
import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitment
import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitmentAuthority
import com.achulkov.evomolior.core.atlas.ToolReuseLineage
import com.achulkov.evomolior.core.atlas.ToolVersionId
import com.achulkov.evomolior.core.atlas.ReceiverContract as AtlasReceiverContract
import com.achulkov.evomolior.core.budget.BudgetConfig
import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNode
import com.achulkov.evomolior.core.contract.DeliverableSkeletonTree
import com.achulkov.evomolior.core.contract.DeliverableSkeletonTreeSummary
import com.achulkov.evomolior.core.contract.FeedforwardLayerSpec
import com.achulkov.evomolior.core.contract.GeneratedImageAsset
import com.achulkov.evomolior.core.contract.GroupCheckinResult
import com.achulkov.evomolior.core.contract.GraderMethodologyDraft
import com.achulkov.evomolior.core.contract.GRADER_METHODOLOGY_DRAFT_SIGNATURE_PREFIX
import com.achulkov.evomolior.core.contract.LeakPacket
import com.achulkov.evomolior.core.contract.RunGraphEdge
import com.achulkov.evomolior.core.contract.ResourceNodeSeed
import com.achulkov.evomolior.core.contract.StartingField
import com.achulkov.evomolior.core.contract.StartingFieldWireContract
import com.achulkov.evomolior.core.contract.RunInputArtifact
import com.achulkov.evomolior.core.contract.RunInputArtifactKind
import com.achulkov.evomolior.core.contract.AgentTtlPolicy
import com.achulkov.evomolior.core.contract.CommitType
import com.achulkov.evomolior.core.contract.ContributionCommit
import com.achulkov.evomolior.core.contract.ContributionAuthorityKind
import com.achulkov.evomolior.core.contract.ContributionAuthorityRef
import com.achulkov.evomolior.core.contract.ContributionRelation
import com.achulkov.evomolior.core.contract.CriticReview
import com.achulkov.evomolior.core.contract.IntegrationNodeRecord
import com.achulkov.evomolior.core.contract.FinalDeliverableReferenceRecord
import com.achulkov.evomolior.core.contract.FinalCandidateRecord
import com.achulkov.evomolior.core.contract.ControlInterventionOutcome
import com.achulkov.evomolior.core.contract.ControlInterventionRecord
import com.achulkov.evomolior.core.contract.ControlInterventionTargetKind
import com.achulkov.evomolior.core.contract.SummaryPrimaryContentAssessment
import com.achulkov.evomolior.core.contract.SummaryValidationRecord
import com.achulkov.evomolior.core.contract.ReceiverScoreContributionRecord
import com.achulkov.evomolior.core.contract.PublicationState
import com.achulkov.evomolior.core.contract.PublicationStateEvent
import com.achulkov.evomolior.core.contract.RecoveryPublicationState
import com.achulkov.evomolior.core.contract.RecoveryPublicationStateEvent
import com.achulkov.evomolior.core.contract.StartupDiagnosticState
import com.achulkov.evomolior.core.contract.StartupDiagnosticStateEvent
import com.achulkov.evomolior.core.contract.RoundPhaseLedgerEventV1
import com.achulkov.evomolior.core.contract.RoundPhaseLedgerPolicy
import com.achulkov.evomolior.core.contract.RoundPhaseV1
import com.achulkov.evomolior.core.contract.WorkerArtifactRef
import com.achulkov.evomolior.core.contract.WorkerToolRepairDiagnostic
import com.achulkov.evomolior.core.deliverable.AcceptedDeliverableAuthority
import com.achulkov.evomolior.core.deliverable.ArcNotebookProfile
import com.achulkov.evomolior.core.deliverable.ArcArtifactBranchLineage
import com.achulkov.evomolior.core.deliverable.ArcSolverFamily
import com.achulkov.evomolior.core.deliverable.ArcVisibleLooBenchmarkDefinition
import com.achulkov.evomolior.core.deliverable.ArcVisibleLooCandidateSource
import com.achulkov.evomolior.core.deliverable.ArcVisibleLooRowInput
import com.achulkov.evomolior.core.deliverable.ArtifactContractViolation
import com.achulkov.evomolior.core.deliverable.ArtifactRef
import com.achulkov.evomolior.core.deliverable.ArtifactSnapshot
import com.achulkov.evomolior.core.deliverable.ArtifactViolationCode
import com.achulkov.evomolior.core.deliverable.BenchmarkRegression
import com.achulkov.evomolior.core.deliverable.ComponentAuthorityClass
import com.achulkov.evomolior.core.deliverable.DeliverableBlueprint
import com.achulkov.evomolior.core.deliverable.DeliverableBlueprintV2
import com.achulkov.evomolior.core.deliverable.DeliverableComponentSpec
import com.achulkov.evomolior.core.deliverable.DeliverableFormat
import com.achulkov.evomolior.core.deliverable.DeliverableRecoveryPolicy
import com.achulkov.evomolior.core.deliverable.InterimDeliverableSnapshot
import com.achulkov.evomolior.core.deliverable.WriterRepairRequest
import com.achulkov.evomolior.core.deliverable.WriterSourceAuthority
import com.achulkov.evomolior.core.deliverable.WriterSourceAuthorityClass
import com.achulkov.evomolior.core.repair.ToolPromotionStatus
import com.achulkov.evomolior.core.repair.ApproachVector
import com.achulkov.evomolior.core.repair.ToolRepairAttemptRecord
import com.achulkov.evomolior.core.repair.ToolSemanticReview
import com.achulkov.evomolior.core.repair.ToolSemanticVerdict
import com.achulkov.evomolior.core.repair.ToolValidationStatus
import com.achulkov.evomolior.core.contract.WorkerLeaseAssignment
import com.achulkov.evomolior.core.contract.VisibleShadowNodeCard
import com.achulkov.evomolior.core.council.CouncilFieldSealing
import com.achulkov.evomolior.core.council.CouncilDeterministicIds
import com.achulkov.evomolior.core.council.CouncilContractHashing
import com.achulkov.evomolior.core.council.ExecutionEnvelope
import com.achulkov.evomolior.core.council.QuantitativeToolObligation
import com.achulkov.evomolior.core.contract.CostLedgerPolicy
import com.achulkov.evomolior.core.council.FieldConsistencyReport
import com.achulkov.evomolior.core.council.SealedTestSuiteV3ValidationContext
import com.achulkov.evomolior.core.council.StartingFieldCompileResult
import com.achulkov.evomolior.core.council.providerCostCapUsd
import com.achulkov.evomolior.core.grader.GraderAtlas
import com.achulkov.evomolior.core.grader.GraderCriteriaFreeze
import com.achulkov.evomolior.core.grader.GradingRequirement
import com.achulkov.evomolior.core.grader.GradingRequirementId
import com.achulkov.evomolior.core.grader.GradingRequirementOrigin
import com.achulkov.evomolior.core.grader.HiddenTestDefinition
import com.achulkov.evomolior.core.grader.HiddenTestId
import com.achulkov.evomolior.core.grader.HiddenTestStatus
import com.achulkov.evomolior.core.model.DimensionScore
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.model.RoundScore
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.orchestration.epigenetic.CausalFitnessDeriver
import com.achulkov.evomolior.orchestration.epigenetic.LegacyProtocolExtractor
import com.achulkov.evomolior.orchestration.inspector.InspectorRunner
import com.achulkov.evomolior.orchestration.inspector.InspectorObservation
import com.achulkov.evomolior.orchestration.nested.NestedGroupConfig
import com.achulkov.evomolior.orchestration.nested.NestedGroupResult
import com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evomolior.orchestration.repair.RepairPriorityPlanner
import com.achulkov.evomolior.orchestration.lifecycle.CriticPipeline
import com.achulkov.evomolior.orchestration.lifecycle.CriticLifecycleSummary
import com.achulkov.evomolior.orchestration.lifecycle.CriticSummary
import com.achulkov.evomolior.orchestration.lifecycle.LifecycleBoundCriticPipeline
import com.achulkov.evomolior.orchestration.lifecycle.LifecycleManager
import com.achulkov.evomolior.orchestration.lifecycle.ToolPromotionService
import com.achulkov.evomolior.orchestration.lifecycle.WipCompletionAuthorityDecision
import com.achulkov.evomolior.orchestration.lifecycle.WorkerAuthorityAssignment
import com.achulkov.evomolior.orchestration.worker.WorkerViewCompiler
import com.achulkov.evomolior.orchestration.worker.NodePortfolioAllocator
import com.achulkov.evomolior.orchestration.worker.ExpectedToolReuseAuthorityResolver
import com.achulkov.evomolior.substrate.grader.GraderAuthorityRepository
import com.achulkov.evomolior.orchestration.selector.EvolutionarySelector
import com.achulkov.evomolior.orchestration.watchdog.Watchdog
import com.achulkov.evomolior.orchestration.watchdog.WatchdogSignal
import com.achulkov.evomolior.orchestration.watchdog.WatchdogSeverity
import com.achulkov.evomolior.sandbox.network.ContextUrlAllowlistExtractor
import com.achulkov.evomolior.sandbox.network.SourceAccessPolicy
import com.achulkov.evomolior.sandbox.shuru.ShuruVmManager
import com.achulkov.evomolior.sandbox.shuru.ShuruCheckResult
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.DeliverableSkeletonTreeRefiner
import com.achulkov.evomolior.substrate.DeliverableSkeletonTreeStore
import com.achulkov.evomolior.substrate.novelty.ProvisionalNovelHypothesisLedger
import com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
import com.achulkov.evomolior.substrate.db.entity.EventLogEntryEntity
import com.achulkov.evomolior.substrate.db.entity.EventTypes
import com.achulkov.evomolior.substrate.db.entity.MetaArchiveEntryEntity
import com.achulkov.evomolior.substrate.db.entity.KnowledgeBaseEntryEntity
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.db.entity.WipTaskEntity
import com.achulkov.evomolior.substrate.tools.ToolInfrastructureRepository
import com.achulkov.evomolior.substrate.tools.ToolAuthoringNodeAuthorityVerifier
import com.achulkov.evomolior.substrate.atlas.AtlasMigrationResult
import com.achulkov.evomolior.substrate.atlas.AtlasTransactionRepository
import com.achulkov.evomolior.substrate.atlas.AtlasLeaseAuthority
import com.achulkov.evomolior.substrate.atlas.NodeCaptureRepository
import com.achulkov.evomolior.substrate.atlas.NodeEvidencePacketSealer
import com.achulkov.evomolior.substrate.atlas.SealNodeEvidenceRequest
import com.achulkov.evomolior.substrate.worker.WorkerExecutionRepository
import com.achulkov.evomolior.substrate.repair.ToolRepairPersistenceRepository
import com.achulkov.evomolior.substrate.compounding.BoundedCompoundingLedgerRepository
import com.achulkov.evomolior.substrate.roundledger.RoundPhaseLedgerRepository
import com.achulkov.evomolior.substrate.worker.CausalFitnessRepository
import com.achulkov.evomolior.substrate.worker.ContributionGraphRepository
import com.achulkov.evomolior.substrate.cost.CostLedgerRepository
import com.achulkov.evomolior.substrate.deliverable.DeliverablePersistenceRepository
import com.achulkov.evomolior.substrate.outlier.OutlierNoveltyRepository
import com.achulkov.evomolior.substrate.publication.PublicationStateRepository
import com.achulkov.evomolior.substrate.publication.PublicationOutcomeRepository
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.time.Duration

private const val META_ARCHIVE_GROUP_ID = "__meta_archive__"
private const val COST_LEDGER_GROUP_ID = "__cost_ledger__"
private const val PUBLICATION_QUIESCENCE_TIMEOUT_MS = 60_000L

/**
 * Default lifetime of one ordinary worker. `atomicNodeEffortBound` counts agent slots per node,
 * not minutes, so it must not be converted into seconds: doing so gave every one-worker run the
 * 300 s floor, while one high-reasoning Codex call takes ~217 s (October 2026 logs), and a
 * Builder was killed between staging its report and committing it. Workers also finished
 * ~10 minutes before grader criteria in every observed round, so this does not lengthen rounds.
 */
private const val DEFAULT_ORDINARY_AGENT_TTL_SECONDS = 600L
private const val RECOVERY_SOURCE_KB_SCAN_LIMIT = 200
private const val MAX_RUN_ARTIFACT_PREVIEW_CHARS = 512
private const val MAX_RUN_GRAPH_MESSAGE_SUMMARIES = 6
private const val ROUND_SUMMARY_MAX_ATTEMPTS = 2
private const val FINAL_DELIVERABLE_MAX_ATTEMPTS = 3
private const val RECOVERY_DELIVERABLE_MAX_ATTEMPTS = 2
private const val DEFAULT_FINAL_DELIVERABLE_ATTEMPT_TIMEOUT_MS = 300_000L
private const val FINAL_DELIVERABLE_OUTPUT_POST_EXIT_GRACE_MS = 2_000L
private const val DEFAULT_GRADER_GENESIS_COUNCIL_TIMEOUT_MS = 5 * 60_000L
private const val TEST_FINAL_DELIVERABLE_MIN_ATTEMPT_TIMEOUT_MS = 5_000L
private const val TEST_FINAL_DELIVERABLE_MAX_ATTEMPT_TIMEOUT_MS = 90_000L
private const val DEFAULT_MULTI_GRADER_GROUP_COUNT = 3
private const val CRITIC_PIPELINE_TTL_MS = 120_000L
private const val POST_CRITIC_METHODOLOGY_REPAIR_CYCLES = 2
private const val CRITERIA_FREEZE_LEASE_TTL_MS = 60 * 60_000L
private const val KB_STALE_GENERATION_WINDOW = 2
private const val KB_STALE_MIN_CITATIONS_TO_KEEP = 1
private const val KB_STALE_MIN_TRUST_SCORE_TO_KEEP = 0.85
private const val MAX_DYNAMIC_LEAK_CEILING = 32
private const val POST_MUTATION_ATLAS_AUTHORITY_PREFIX = "post-mutation-atlas:"
private const val GRADER_ATLAS_AUTHORITY_PREFIX = "grader-atlas:"
private const val NOT_CONFIGURED_AUTHORITY = "not-configured"
private val EXACT_CLAIM_DECISIONS = setOf(
    NodeCaptureDecisionType.CAPTURED,
    NodeCaptureDecisionType.RECAPTURED,
    NodeCaptureDecisionType.SHARED_VALIDATION_CREDIT
)

/**
 * Computes the budget available to spawn a single generation for one group.
 *
 * `GroupSummary.remainingBudget` is updated from live agent ledgers and therefore drops to zero
 * when all agents from a round have exited. It is a live telemetry value, not the next generation's
 * spawn budget. Deriving the next round directly from that value stops all later generations from
 * spawning, which breaks EvoMolior's compounding model. The configured profile/concurrency budget
 * is the stable per-generation ceiling; live telemetry can only raise the ceiling if a coordinator
 * has explicitly granted more budget.
 *
 * @param maximumCreditAllocation Optional share of an explicit run credit limit. This bounds
 *   both configured grants and added credits; it is not a provider-price estimate.
 */
internal fun computeGenerationSpawnBudgetCeiling(
    profile: GroupProfile,
    maxAgentsPerGroup: Int,
    observedLiveRemainingBudget: Double?,
    nodeSpawnCredits: Double,
    maximumCreditAllocation: Double? = null,
): Double {
    require(maximumCreditAllocation == null ||
        (maximumCreditAllocation.isFinite() && maximumCreditAllocation >= 0.0)) {
        "Maximum credit allocation must be finite and nonnegative"
    }
    val configuredGenerationBudget = profile.spawnBudget * maxAgentsPerGroup.coerceAtLeast(1)
    val liveBudget = observedLiveRemainingBudget
        ?.takeIf { it.isFinite() && it > 0.0 }
        ?: 0.0
    val appliedSpawnCredits = nodeSpawnCredits
        .takeIf { it.isFinite() && it > 0.0 }
        ?: 0.0
    val available = maxOf(configuredGenerationBudget, liveBudget) + appliedSpawnCredits
    return maximumCreditAllocation?.let { minOf(available, it) } ?: available
}

internal fun requireCompiledCouncilRecoveryIntent(
    candidate: CompiledCouncilCandidateManifestV3,
    expectedCouncilExecutionPurpose: CouncilExecutionPurpose,
    expectedAcceptanceProfile: AcceptanceProfile,
    expectedQuantitativeToolObligation: QuantitativeToolObligation?
) {
    require(
        candidate.executionEnvelope.councilExecutionPurpose ==
            expectedCouncilExecutionPurpose
    ) {
        "compiled Council execution purpose=" +
            "${candidate.executionEnvelope.councilExecutionPurpose.id} does not match " +
            "recovery purpose=${expectedCouncilExecutionPurpose.id}"
    }
    require(candidate.executionEnvelope.acceptanceProfile == expectedAcceptanceProfile) {
        "compiled Council acceptance profile=" +
            "${candidate.executionEnvelope.acceptanceProfile.id} does not match " +
            "recovery profile=${expectedAcceptanceProfile.id}"
    }
    require(
        candidate.executionEnvelope.quantitativeToolObligation ==
            expectedQuantitativeToolObligation
    ) {
        "compiled Council quantitative-tool obligation does not match recovery intent"
    }
}

/**
 * Selects a deterministic source-only group for non-accepted recovery.
 *
 * This helper returns no acceptance or quorum authority. It exists so recovery can give a
 * bounded writer a stable substrate context even when the scoring winner is unresolved.
 *
 * @param winnerId Optional scored group candidate.
 * @param groupIds Eligible worker groups.
 * @return The valid scored candidate, or the lexicographically first eligible group.
 */
internal fun deterministicRecoverySourceGroupId(
    winnerId: String?,
    groupIds: List<String>,
    canonicalDocumentChars: Map<String, Int> = emptyMap(),
): String? = winnerId
    ?.takeIf { it in groupIds }
    // Without a winner, prefer the group whose canon holds the most substantial document: an
    // all-equal grader tie must not route the writer to an alphabetically-first group whose
    // canon is thinner (October 2026: a 3,519-word draft was chosen over a 5,033-word one).
    ?: groupIds.sorted().maxWithOrNull(
        compareBy<String> { canonicalDocumentChars[it] ?: 0 }.thenByDescending { it }
    )

/**
 * Adds target-specific final-writer guidance without changing generic output prompts.
 *
 * @param outputSpec Declared final output target.
 * @return Existing generic requirements, or those requirements plus the exact ARC blueprint.
 */
internal fun finalDeliverableWriterValidationRequirements(
    outputSpec: FinalOutputSpec
): String? {
    val genericRequirements = FinalOutputContractValidator.promptRequirements(outputSpec)
    if (!ArcAgi2NotebookRecoveryFallback.supports(outputSpec)) {
        return genericRequirements
    }
    return buildString {
        genericRequirements
            ?.takeIf { it.isNotBlank() }
            ?.let {
                appendLine(it)
                appendLine()
            }
        appendLine("=== ARC NOTEBOOK IMPLEMENTATION CHECKLIST ===")
        appendLine(ArcAgi2NotebookQualificationAuthority.notebookWriterBlueprint)
    }
}

private data class MaterializedRunInputArtifacts(
    val artifacts: List<RunInputArtifact>,
    val allowedHosts: Set<String>
)

private data class PersistedToolCandidateAuthority(
    val candidateHash: String,
    val opportunityCommitmentHash: String,
    val consumedDiagnosticIds: List<String>,
    val repairApproachVector: ApproachVector?
)

private data class RepairApproachAssignmentKey(
    val groupId: String,
    val nodeId: NodeId,
    val round: Int
)

private data class FinalDeliverablePackageInputs(
    val blueprint: DeliverableBlueprint,
    val snapshot: InterimDeliverableSnapshot,
)

@Serializable
private data class RoundSummaryRecord(
    val runId: String,
    val roundIndex: Int,
    val roundWinnerId: String?,
    val judgeOutputsByGrader: Map<String, String>,
    val groupSummaries: List<RoundGroupSummaryRecord>,
    val generatedAtMs: Long = System.currentTimeMillis()
)

/** Exact staging identity authored by one spawned round-submission summariser. */
internal data class RoundSubmissionSummaryCandidate(
    val submissionId: String,
    val agentId: String,
    val content: String
)

/**
 * Durable completion payload stored in the run meta-archive.
 *
 * The completion marker is intentionally the single source of truth for the final answer card,
 * judge outputs, and submission summaries after a run finishes.
 */
@Serializable
data class RunCompletionRecord(
    val runId: String,
    val winnerId: String?,
    val completedRoundCount: Int = 0,
    val completionReason: RunCompletionReason = RunCompletionReason.ROUND_LIMIT_REACHED,
    val finalAnswerSummary: String = "",
    val finalDeliverableHtml: String? = null,
    val generatedOutputFiles: List<GeneratedOutputFile> = emptyList(),
    val generatedImageAssets: List<GeneratedImageAsset> = emptyList(),
    val requestedOutputSpec: FinalOutputSpec? = null,
    val judgeOutputsByGrader: Map<String, String> = emptyMap(),
    val submissionSummariesByGroup: Map<String, String?> = emptyMap(),
    val substrateHealthByGroup: List<RunSubstrateHealthSummary> = emptyList(),
    val graphMessage: RunGraphMessage? = null,
    val completedAtMs: Long = System.currentTimeMillis()
)

private enum class ArchiveBoundaryRecoveryStatus {
    NOT_REACHED,
    PASSED,
    REJECTED
}

private data class LatePublicationRecoveryContext(
    val groupIds: List<String>,
    val recoverySourceGroupId: String?,
    val recoverySourceGroupQuorumBound: Boolean = false,
    val finalRoundIndex: Int,
    val completedRoundCount: Int,
    val outputSpec: FinalOutputSpec?,
    val generatedImageAssets: List<GeneratedImageAsset>,
    val finalRoundSummary: String?,
    val candidateOutput: GeneratedOutputFile? = null,
    val candidateOutputSource: RecoveredDeliverableSource? = null,
    val publicationPhaseReached: Boolean = false,
    val finalPublicationBoundaryRejected: Boolean = false,
    val archiveBoundaryStatus: ArchiveBoundaryRecoveryStatus =
        ArchiveBoundaryRecoveryStatus.NOT_REACHED
)

private enum class FinalDeliverablePublicationMode {
    ACCEPTED,
    MAIN_PRE_ACCEPTANCE,
    RECOVERY_NON_ACCEPTED
}

private class NonAcceptingDiagnosticSweepRequestedException :
    IllegalStateException("Non-accepting acceptance diagnostic sweep requested")

/**
 * Compact architecture-health snapshot archived with [RunCompletionRecord].
 *
 * The fields intentionally mirror the failure signatures used in run audits so future reruns can
 * be compared from the archive without scraping low-level logs.
 */
@Serializable
data class RunSubstrateHealthSummary(
    val groupId: String,
    val kbCount: Int,
    val kgCount: Int,
    val activeToolCount: Int,
    val toolExecutionCount: Int,
    val kgFactSubmissionCount: Int = 0,
    val toolSubmissionCount: Int = 0,
    val kgAbstentionRationaleCount: Int = 0,
    val toolAbstentionRationaleCount: Int = 0,
    val noActionFallbackSubmissionCount: Int = 0,
    val trustDecayEventCount: Int = 0,
    val lowTrustArchivedByDecayCount: Int = 0,
    val stagedSubmissionCount: Int,
    val promotedSubmissionCount: Int,
    val pendingSubmissionCount: Int,
    val rejectedSubmissionCount: Int,
    val outlierSubmissionCount: Int,
    val roundSubmissionSummaryPresent: Boolean
)

/**
 * Canonical generated file captured from the final output writer.
 *
 * Stored in the run completion record and copied into downloadable result bundles so follow-up
 * pipeline stages and operators can retrieve a stable artifact path without scraping summaries.
 *
 * @param relativePath Relative path under the run's `outputs/` directory.
 * @param mediaType MIME type of the generated file.
 * @param content Authored file content extracted from the final output writer.
 * @param componentNodeIds Exact writer-declared component-to-accepted-node selection.
 * @param componentLineageSubmissionId Persisted same-writer `PLAN_UPDATE` authority.
 * @param componentLineageSubmittedAtMs Ordering authority proving lineage preceded the Death Note.
 */
@Serializable
data class GeneratedOutputFile(
    val relativePath: String,
    val mediaType: String,
    val content: String,
    val contentHash: String = AtlasHashing.hashText(content),
    val sourceGroupId: String? = null,
    val sourceAgentId: String? = null,
    val sourceSubmissionId: String? = null,
    val sourceRole: Role? = null,
    val componentNodeIds: Map<String, Set<String>> = emptyMap(),
    val componentLineageSubmissionId: String? = null,
    val componentLineageSubmittedAtMs: Long? = null
) {
    init {
        require(
            (componentLineageSubmissionId == null) ==
                (componentLineageSubmittedAtMs == null)
        ) { "Final component-lineage submission ID and timestamp must be present together" }
    }
}

@Serializable
private data class RoundGroupSummaryRecord(
    val runId: String,
    val roundIndex: Int,
    val groupId: String,
    val criticPromoted: Int,
    val criticRejected: Int,
    val criticArbitrated: Int,
    val fitnessScoredAgents: Int,
    val fitnessTopEligibleAgents: Int,
    val legacyProtocolsExtracted: Int,
    val legacyProtocolsExpired: Int,
    val deadEndsInserted: Int,
    val submissionSummary: String? = null,
    val deterministicScore: DeterministicAssessmentRecord,
    val disclosureCheckin: DisclosureCheckinRecord,
    val blindScore: BlindScoreRecord
)

@Serializable
private data class DeterministicAssessmentRecord(
    val overallScore: Double,
    val automatedTestsPassed: Int,
    val automatedTestsFailed: Int,
    val mandatoryFailure: Boolean,
    val gradingSheetApplied: String,
    val failedTestIds: List<String>,
    val dimensionScores: Map<String, Double>
)

@Serializable
private data class DisclosureCheckinRecord(
    val overallScore: Double,
    val passed: Boolean,
    val testPassRate: Double,
    val failedTestCount: Int,
    val gradingSheetApplied: String,
    val stageAdvanced: Boolean,
    val dimensionScores: Map<String, Double>
)

@Serializable
private data class BlindScoreRecord(
    val weightedTotal: Double,
    val rank: Int,
    val dimensionScores: Map<String, Double>
)

@Serializable
private data class SubstrateRoundAssessment(
    val dimensionScores: List<DimensionScore>,
    val overallScore: Double,
    val kbCount: Int,
    val kgCount: Int,
    val toolCount: Int,
    val toolSuccessRate: Double,
    val completedWipTasks: Int,
    val totalWipTasks: Int,
    val failureModesTriggered: List<String> = emptyList()
)

@Serializable
private data class EpigeneticRoundSummary(
    val fitnessScoredAgents: Int,
    val fitnessTopEligibleAgents: Int,
    val legacyProtocolsExtracted: Int,
    val legacyProtocolsExpired: Int,
    val deadEndsInserted: Int
)

/**
 * Real in-process implementation of [EvoMoliorEngineService].
 *
 * Manages the full run lifecycle:
 * 1. Group setup and LifecycleManager creation per group
 * 2. GroupCoordinator for inter-group mechanics (leak oracle, dispatch relay, scoring)
 * 3. Round loop: start → CriticPipeline → trust decay → check-in → evaluation → score emission
 * 4. ProgressiveDisclosureEngine stage gating per group
 * 5. RunArchiveService archival on completion
 * 6. Event emission to subscribed UI clients
 *
 * @param lifecycleManagerProvider Factory lambda for creating [LifecycleManager] instances.
 * @param groupCoordinatorProvider Factory lambda for creating [GroupCoordinator] instances.
 * @param graderGroupManagerProvider Factory lambda for creating [GraderGroupManager] instances.
 * @param substrateFactory [SubstrateDatabaseFactory] for per-group DB access.
 * @param archiveService Optional [RunArchiveService] — if provided, archives the run on completion.
 * @param acceptanceRunBoundaryVerifier Synchronous immutable-input verifier for non-normal
 * acceptance round, publication, and archive authority boundaries.
 * @param requiresShuru Whether this service configuration requires Shuru-backed lifecycle
 * execution. Normal in-process production remains the default (`false`).
 * @param shuruPreflight Explicit Shuru availability authority used only when [requiresShuru] is
 * true. The default delegates to [shuruVmManager].
 * @param criticModelOverride Optional model override for [CriticPipeline] review agents.
 * @param trustDecayFactor Per-round trust decay multiplier applied to all trust scores after each round (default: 0.97).
 * @param disclosureStages Optional list of [DisclosureStage]s for progressive task disclosure; if null, disclosure is disabled.
 */
class EvoMoliorRunService(
    private val lifecycleManagerProvider: (
        runId: String,
        groupId: String,
        openRouterApiKey: String,
        agentJarPath: String,
        groupProfile: GroupProfile
    ) -> LifecycleManager,
    private val lifecycleManagerProviderWithBackend: (
        runId: String,
        groupId: String,
        openRouterApiKey: String,
        agentJarPath: String,
        groupProfile: GroupProfile,
        llmBackend: LlmBackend
    ) -> LifecycleManager = { runId, groupId, openRouterApiKey, agentJarPath, groupProfile, _ ->
        lifecycleManagerProvider(runId, groupId, openRouterApiKey, agentJarPath, groupProfile)
    },
    private val lifecycleManagerProviderWithExecutionEnvelope: (
        runId: String,
        groupId: String,
        openRouterApiKey: String,
        agentJarPath: String,
        groupProfile: GroupProfile,
        llmBackend: LlmBackend,
        executionEnvelope: ExecutionEnvelope?
    ) -> LifecycleManager = {
            runId,
            groupId,
            openRouterApiKey,
            agentJarPath,
            groupProfile,
            llmBackend,
            _
        ->
        lifecycleManagerProviderWithBackend(
            runId,
            groupId,
            openRouterApiKey,
            agentJarPath,
            groupProfile,
            llmBackend
        )
    },
    private val groupCoordinatorProvider: (
        groupIds: List<String>,
        dimensions: List<ScoringDimension>
    ) -> GroupCoordinator,
    private val groupCoordinatorProviderWithKey: (
        groupIds: List<String>,
        dimensions: List<ScoringDimension>,
        openRouterApiKey: String
    ) -> GroupCoordinator = { groupIds, dimensions, _ ->
        groupCoordinatorProvider(groupIds, dimensions)
    },
    private val groupCoordinatorProviderWithReasoning: (
        groupIds: List<String>,
        dimensions: List<ScoringDimension>,
        openRouterApiKey: String,
        codexReasoningEffort: CodexReasoningEffort
    ) -> GroupCoordinator = { groupIds, dimensions, openRouterApiKey, _ ->
        groupCoordinatorProviderWithKey(groupIds, dimensions, openRouterApiKey)
    },
    private val graderGroupManagerProvider: (
        dimensions: List<ScoringDimension>
    ) -> GraderGroupManager,
    private val substrateFactory: SubstrateDatabaseFactory,
    /** Shuru VM manager used for pre-flight availability check and referenced by LifecycleManagers. */
    private val shuruVmManager: ShuruVmManager = ShuruVmManager(),
    private val archiveService: RunArchiveService? = null,
    private val toolTransportClosureMeasurementProvider:
        suspend (String) -> ToolTransportClosureMeasurements = { runId ->
            ToolTransportAttemptStore(substrateFactory).measurements(runId)
        },
    private val acceptanceRunBoundaryVerifier: AcceptanceRunBoundaryVerifier =
        AcceptanceRunBoundaryVerifier.NONE,
    /** Exact immutable-distribution commitment; production resolves it from the launcher environment. */
    private val distributionManifestHashProvider: (() -> String)? = null,
    private val requiresShuru: Boolean = false,
    private val shuruPreflight: suspend () -> ShuruCheckResult = shuruVmManager::ensureInstalled,
    private val comparativeRuntimeInputs: ComparativeRuntimeInputs? = null,
    private val criticModelOverride: String? = null,
    /** Deterministic worker-review seam; null preserves the configured provider-backed Critic. */
    private val workerCriticReviewRunner: (suspend (
        submission: StagingSubmissionEntity,
        prompt: String,
        disputeContext: String?,
        criticId: String
    ) -> CriticReview)? = null,
    /** Deterministic typed-tool-review seam; null preserves provider-backed semantic review. */
    private val workerToolSemanticReviewRunner: (suspend (
        submission: StagingSubmissionEntity,
        prompt: String,
        criticId: String
    ) -> ToolSemanticReview)? = null,
    /**
     * Deterministic lifecycle-race seam. Production derives TTL from the sealed execution
     * envelope; focused full-path tests may supply a shorter positive value.
     */
    private val ordinaryAgentTtlSecondsOverride: Long? = null,
    /**
     * Deterministic round-clock seam. Production uses the configured whole-minute duration;
     * focused full-path tests may supply a shorter positive duration.
     */
    private val roundDurationMillisOverride: Long? = null,
    /**
     * Deterministic final-writer attempt timeout seam. Production leaves this unset so the
     * backend-specific policy remains authoritative.
     */
    private val finalDeliverableAttemptTimeoutMsOverride: Long? = null,
    /**
     * Deterministic round-closure seam. Production is a no-op; focused tests may fail immediately
     * before interim snapshots are compiled to prove last-complete-round recovery.
     */
    private val beforeRoundSnapshotCompilation: suspend (runId: String, round: Int) -> Unit =
        { _, _ -> },
    /**
     * Runtime-owned visible-LOO input seam. Production callers may supply exact rows loaded from
     * the sealed benchmark definition; a null result records an explicit unavailable benchmark,
     * never a fabricated score.
     */
    private val arcVisibleLooRowsProvider: suspend (
        runId: String,
        groupId: String,
        round: Int,
        artifact: GeneratedOutputFile
    ) -> List<ArcVisibleLooRowInput>? = { _, _, _, _ -> null },
    private val arcVisibleLooDefinitionProvider: (runId: String) ->
        ArcVisibleLooBenchmarkDefinition = { ArcVisibleLooBenchmarkDefinition.fixture() },
    private val trustDecayFactor: Double = 0.97,
    private val disclosureStages: List<DisclosureStage>? = null,
    /** How often to emit heartbeat progress events while agents are running (default: 30 seconds). */
    private val roundHeartbeatIntervalMs: Long = 30_000L,
    /** How often the Inspector scans event logs for anomalies (default: 60 seconds). */
    private val inspectorCheckIntervalMs: Long = 60_000L,
    /**
     * Optional Genesis Council runner.
     *
     * If provided, called at the start of each production run to compile both worker and grader
     * fields plus the authoritative Atlas. Missing, invalid, failed, or timed-out production
     * Council execution aborts startup.
     *
     * The callback receives the exact execution envelope and materialized input artifacts before
     * any role designs the field.
     * - onProgress is called with human-readable progress strings as the council runs
     * - onInvocationStarted must be called as the runner's first action, before provider work
     *
     * Maximum time: 8 minutes for network backends and 45 minutes for Codex.
     */
    private val genesisCouncilRunner: (suspend (
        runId: String,
        objective: String,
        openRouterApiKey: String,
        executionEnvelope: ExecutionEnvelope,
        inputArtifacts: List<RunInputArtifact>,
        onProgress: ((String) -> Unit)?,
        onInvocationStarted: () -> Unit
    ) -> StartingFieldCompileResult)? = null,
    /**
     * Resolves the unchanged overall Genesis Council envelope.
     *
     * The production policy is eight minutes for network backends and 45 minutes for Codex.
     * Tests may inject a shorter finite positive duration.
     */
    private val genesisCouncilTimeoutResolver: (LlmBackend) -> Duration = { backend ->
        LlmTimeoutPolicy.genesisCouncilEnvelope(backend)
    },
    /**
     * Optional Grader Round runner.
     *
     * If provided, called post-round (after competing agents complete, before substrate scoring)
     * to run the Grader Group AI civilisation that evaluates each competing group.
     *
     * The callback signature: (runId, graderGroupIds, competingGroupIds, roundIndex,
     *   runObjective, scoringDimensions, graderGroupManager, onProgress?) → Map<String, String>
     *
     * If null or if the grader run fails, falls back to substrate-based scoring only.
     */
    private val graderRunner: (suspend (
        runId: String,
        openRouterApiKey: String,
        graderGroupIds: List<String>,
        competingGroupIds: List<String>,
        roundIndex: Int,
        runObjective: String,
        scoringDimensions: List<ScoringDimension>,
        graderGroupManager: GraderGroupManager,
        onProgress: ((String) -> Unit)?
    ) -> Unit)? = null,
    /**
     * Optional Grader Criteria runner — Phase A of the new grader architecture.
     *
     * If provided, called at round START (concurrent with main group agents) to develop
     * grading criteria for the round. Criteria agents run during the round and write
     * their rubric to the grader group staging DB.
     *
     * Signature: (runId, openRouterApiKey, graderGroupIds, roundIndex, runObjective,
     *   scoringDimensions, onProgress?) → Unit
     *
     * When both [graderCriteriaRunner] and [graderJudgingRunner] are provided, the two-phase
     * architecture is active: criteria agents run concurrently during the round, and the
     * RoundJudge scores post-round using those criteria.
     *
     * If null, the legacy [graderRunner] is used post-round (backward compat).
     */
    private val graderCriteriaRunner: (suspend (
        runId: String,
        openRouterApiKey: String,
        graderGroupIds: List<String>,
        roundIndex: Int,
        runObjective: String,
        scoringDimensions: List<ScoringDimension>,
        onProgress: ((String) -> Unit)?
    ) -> Unit)? = null,
    /**
     * Optional Grader Judging runner — Phase B of the new grader architecture.
     *
     * If provided, called post-round (after CriticPipeline) to run the RoundJudge, which
     * reads criteria from the grader staging DB and scores all competing groups.
     *
     * If null, the legacy [graderRunner] is used post-round (backward compat).
     */
    private val graderJudgingRunner: (suspend (
        runId: String,
        openRouterApiKey: String,
        graderGroupIds: List<String>,
        competingGroupIds: List<String>,
        roundIndex: Int,
        runObjective: String,
        scoringDimensions: List<ScoringDimension>,
        graderGroupManager: GraderGroupManager,
        nodeGradingAssignments: List<NodeGradingAssignment>,
        onNodeGradeBundles: (List<NodeGradeBundle>) -> Unit,
        onProgress: ((String) -> Unit)?
    ) -> Map<String, String>)? = null,
    /**
     * Optional isolated novelty-assessment runner.
     *
     * Each returned assessment must be proposal-bound and authored in the corresponding
     * grader-local database. Missing or malformed authority is handled fail-closed.
     */
    private val graderNoveltyAssessmentRunner: (suspend (
        runId: String,
        openRouterApiKey: String,
        graderGroupIds: Set<String>,
        roundIndex: Int,
        runObjective: String,
        proposals: List<NoveltyVectorProposal>,
        onProgress: ((String) -> Unit)?
    ) -> List<NoveltyGraderAssessment>)? = null,
    /**
     * Optional Grader Criteria reviewer — reviews criteria submissions before they are used.
     *
     * If provided, called after the criteria phase completes and before judging begins.
     * This lets tests or custom runtimes substitute a reviewer while the default path
     * still uses the built-in CriticPipeline-based review.
     */
    private val graderCriteriaReviewRunner: (suspend (
        runId: String,
        openRouterApiKey: String,
        graderGroupIds: List<String>,
        roundIndex: Int,
        onProgress: ((String) -> Unit)?
    ) -> Unit)? = null,
    /**
     * Optional MetaPlanner runner.
     *
     * When provided, round-level stasis signals are fed into the runner and it can spawn
     * a MetaPlanner agent through the relevant group's LifecycleManager once the threshold
     * is exceeded.
     */
    private val metaPlannerRunner: MetaPlannerRunner? = null
) : EvoMoliorEngineService {

    init {
        require(ordinaryAgentTtlSecondsOverride == null || ordinaryAgentTtlSecondsOverride > 0L) {
            "Ordinary-agent TTL override must be positive"
        }
        require(roundDurationMillisOverride == null || roundDurationMillisOverride > 0L) {
            "Round-duration override must be positive"
        }
        require(
            finalDeliverableAttemptTimeoutMsOverride == null ||
                finalDeliverableAttemptTimeoutMsOverride > 0L
        ) {
            "Final-deliverable attempt timeout override must be positive"
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private enum class TerminalOwner { ABORT, COMPLETION, RECOVERY }
    private val terminalOwner = AtomicReference<TerminalOwner?>(null)
    @Volatile private var activeRunLoopJob: Job? = null
    private data class FailedRunDrain(val criteriaJob: Job?, val failure: IllegalStateException)
    @Volatile private var failedRunDrain: FailedRunDrain? = null

    /** JSON instance for serialising [RunConfig] into the meta-archive. Lenient for future field additions. */
    private val runConfigJson = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val startupRejectionStore = StartupRejectionFileStore(substrateFactory::runControlDir)
    /** Lenient parser for council-authored knowledge-node and deliverable resource seeds. */
    private val resourceSeedJson = Json { ignoreUnknownKeys = true; isLenient = true }
    /** Strict parser for promoted typed grader methodology authority. */
    private val graderMethodologyJson = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    private val _events = MutableSharedFlow<EvoMoliorEvent>(replay = 0, extraBufferCapacity = 512)
    override val events: SharedFlow<EvoMoliorEvent> = _events.asSharedFlow()

    @Volatile private var activeRunId: String? = null
    @Volatile private var currentStatus: EngineStatus = EngineStatus.IDLE
    @Volatile private var currentRound: Int? = null
    @Volatile private var groupSummaries: List<GroupSummary> = emptyList()
    /** Preserved across cleanup so meta-archive can be queried after run completion. */
    @Volatile private var lastRunId: String? = null
    @Volatile private var lastRunGroupIds: List<String> = emptyList()
    @Volatile private var activeGraderGroupIds: List<String> = emptyList()
    @Volatile private var startupRunStartedEmitted: Boolean = false
    @Volatile private var startupWorkerInfrastructureActivated: Boolean = false
    @Volatile private var startupGraderInfrastructureActivated: Boolean = false
    private var startupRejectionArtifact: StartupRejectionArtifact? = null

    private val liveLifecycleManagers = mutableMapOf<String, LifecycleManager>()
    private var activeCoordinator: GroupCoordinator? = null
    private var activeGraderGroupManager: GraderGroupManager? = null
    private var activeDisclosureEngine: ProgressiveDisclosureEngine? = null
    /** Resource-map engine that turns round outcomes into node claim/recapture events. */
    private var activeResourceMapEngine: ResourceMapEngine? = null
    private var activeWorkerAtlasId: String? = null
    private var activeWorkerExecutionEnvelope: ExecutionEnvelope? = null
    private var activeYieldConstraintPolicy: YieldConstraintPolicy? = null
    /** Host-owned ARC artifact workspaces, one durable workspace per competing group. */
    private val artifactWorkspaceHandlesByGroup =
        ConcurrentHashMap<String, ArtifactWorkspaceHandle>()
    /** Latest host-compiled ARC artifact per group, including scaffold and writer output. */
    private val artifactOutputsByGroup =
        ConcurrentHashMap<String, GeneratedOutputFile>()
    /** Latest source authority persisted for each group's main-writer path. */
    private val writerSourceAuthoritiesByGroup =
        ConcurrentHashMap<String, WriterSourceAuthority>()
    private var activeArcArtifactBlueprint: DeliverableBlueprintV2? = null

    /** Returns the exact active run cost policy for production boundary composition. */
    fun costLedgerPolicy(runId: String): CostLedgerPolicy {
        require(activeRunId == runId) {
            "Cost policy requested for inactive run $runId"
        }
        return CostLedgerPolicy(
            cumulativeCapUsd = activeWorkerExecutionEnvelope?.providerCostCapUsd
        )
    }

    /** Returns the active run's immutable Codex reasoning authority. */
    fun codexReasoningEffort(runId: String): CodexReasoningEffort {
        require(activeRunId == runId) {
            "Codex reasoning effort requested for inactive run $runId"
        }
        return requireNotNull(activeWorkerExecutionEnvelope) {
            "Codex reasoning effort requires the active execution envelope"
        }.codexReasoningEffort
    }
    /**
     * Returns the persisted role-aware Codex policy for an active run.
     *
     * @param runId Active run identity.
     */
    fun codexRuntimePreset(runId: String): com.achulkov.evomolior.core.config.CodexRuntimePreset? {
        require(activeRunId == runId) { "Codex preset requested for inactive run $runId" }
        return requireNotNull(activeWorkerExecutionEnvelope) {
            "Codex preset requires the active execution envelope"
        }.codexRuntimePreset
    }

    /** Durable deliverable tree store for per-group skeleton state. */
    private val deliverableSkeletonTreeStore = DeliverableSkeletonTreeStore(substrateFactory)
    /** Run-global, append-only authority behind all node projections. */
    private val atlasTransactionRepository = AtlasTransactionRepository(substrateFactory)
    private val nodeCaptureRepository = NodeCaptureRepository(substrateFactory)
    private val graderAuthorityRepository = GraderAuthorityRepository(substrateFactory)
    private val graderNodeLeaseAuthority =
        GraderNodeLeaseAuthority(graderAuthorityRepository)
    private val sealedNodeEvidenceByRound =
        ConcurrentHashMap<Int, ConcurrentLinkedQueue<NodeEvidencePacket>>()
    private val nodeGradeBundlesByRound =
        ConcurrentHashMap<Int, List<NodeGradeBundle>>()
    private val exactGraderVerdictsByRound =
        ConcurrentHashMap<Int, com.achulkov.evomolior.competition.grader.ExactGraderRoundVerdict>()
    private val scoringWorkerExactGraderVerdictsByRound =
        ConcurrentHashMap<Int, com.achulkov.evomolior.competition.grader.ExactGraderRoundVerdict>()
    private val outlierNoveltyMetricsByRound =
        ConcurrentHashMap<Int, OutlierNoveltyMetrics>()
    private val disclosureFeedbackByGroup =
        ConcurrentHashMap<String, com.achulkov.evomolior.core.contract.GroupFeedbackPacket>()
    private val disclosureStageAdvancedByGroupRound =
        ConcurrentHashMap<Pair<String, Int>, Boolean>()
    private val repairApproachAssignments =
        ConcurrentHashMap<RepairApproachAssignmentKey, ApproachVector>()
    private val rewardSpendLineageByGroupRound =
        ConcurrentHashMap<Pair<String, Int>, ConcurrentLinkedQueue<RewardUseLineageEvent>>()
    private val rewardArtifactLineageByArtifactId =
        ConcurrentHashMap<String, ConcurrentLinkedQueue<RewardUseLineageEvent>>()
    /** Captures legacy node systems into the initial authoritative Atlas. */
    private val legacyNodeAtlasMigrationAdapter = LegacyNodeAtlasMigrationAdapter(
        substrateFactory = substrateFactory,
        deliverableStore = deliverableSkeletonTreeStore
    )
    /** Projects evolved deliverable gaps and structural changes into claimable WiP tasks. */
    private val deliverableWipReconciler = DeliverableWipReconciler(
        deliverableSkeletonTreeStore,
        substrateFactory
    )
    /** Reconciles each selected Atlas portfolio with generation-local group WIP state. */
    private val atlasWipProjectionReconciler = AtlasWipProjectionReconciler(substrateFactory)
    /** Durable Overseer kernel state store for recursive self-improvement metadata. */
    private val overseerKernelService = OverseerKernelService(substrateFactory)
    /** Mirror of Overseer audit entries in the project meta-archive for evidence queries. */
    private val overseerAuditArchiveJson = Json { ignoreUnknownKeys = true; prettyPrint = false }
    /** Dedicated scoring boundary for blind tournament gate/reveal lifecycle. */
    private var activeBlindTournamentService: BlindTournamentService? = null
    /** Deterministic evaluator that binds the sealed suite to canonical snapshots. */
    private val sealedTestSuiteEvaluator = SealedTestSuiteEvaluator()
    /** Model assignments from Genesis Council (role name → model string). Applied as fallback. */
    @Volatile private var activeModelAssignments: Map<String, String> = emptyMap()
    /** Candidate model pools from Genesis Council (role name → ordered list of model IDs). */
    @Volatile private var activeModelPools: Map<String, List<String>> = emptyMap()
    /** Active group profile order for the current run, aligned with [lastRunGroupIds]. */
    @Volatile private var activeGroupProfiles: List<GroupProfileType> = emptyList()
    /** Role prompts from Genesis Council (role name → system prompt). Applied each round spawn. */
    @Volatile private var activeRolePrompts: Map<String, String> = emptyMap()
    /** Max rounds from StartingField.generationCeiling (caps config.rounds in the run loop). */
    @Volatile private var activeGenerationCeiling: Int = 100
    /** Council-defined cadence for deterministic Watchdog leak-delivery signals. */
    @Volatile private var activeLeakIntervalGenerations: Int = 1
    /** Connector allocation cadence derived from the formal-dispatch lag contract. */
    @Volatile private var activeDispatchCadenceGenerations: Int = 1
    /** Authoritative generation allocation currently observed by each group's Watchdog telemetry. */
    private val activeGenerationBudgetCeilings = ConcurrentHashMap<String, Double>()
    /** Generation index associated with [activeGenerationBudgetCeilings]. */
    private val activeGenerationBudgetRounds = ConcurrentHashMap<String, Int>()
    /** Canonical sealed test suite JSON emitted by the Genesis Council. */
    @Volatile private var activeSealedTestSuiteJson: String = ""
    /** Canonical sealed test suite hash emitted by the Genesis Council. */
    @Volatile private var activeSealedTestSuiteHash: String = ""
    /** Exact external authority required to execute an active compiled v3 sealed suite. */
    @Volatile
    private var activeSealedTestSuiteValidationContext: SealedTestSuiteV3ValidationContext? = null
    /** Dedicated grader-civilisation seed resolved for the active run. */
    @Volatile private var activeGraderStartingField: StartingField? = null

    /**
     * Returns exact sealed grader model assignments for an active verified compiled-v3 runtime.
     *
     * @param runId The currently active run identifier.
     * @return A detached role-to-model assignment map, or null for normal/uncompiled routes.
     * @throws IllegalStateException if [runId] is not the active run.
     */
    fun activeSealedGraderModelAssignments(runId: String): Map<String, String>? {
        requireActiveRun(runId)
        if (activeSealedTestSuiteValidationContext == null) return null
        return activeGraderStartingField?.modelAssignment?.toMap()
    }
    /** Council-defined score normalization policy keyed by scoring dimension ID. */
    @Volatile private var activeScoreNormalization: Map<String, Double> = emptyMap()
    /**
     * Latest per-group dimension scores computed from live substrate signals.
     * Populated each round and consumed by [processDisclosureCheckIns] (A-3).
     */
    private val latestGroupDimensionScores = ConcurrentHashMap<String, Map<String, Double>>()
    /** Latest live substrate assessment per group. */
    private val latestGroupSubstrateAssessments = ConcurrentHashMap<String, SubstrateRoundAssessment>()
    /** Latest deterministic sealed-suite assessment per group. */
    private val latestGroupRoundAssessments = ConcurrentHashMap<String, DeterministicRoundAssessment>()
    /** Complete grader-civilisation result selected as the authoritative score source per round. */
    private val authoritativeGraderRoundResults = ConcurrentHashMap<Int, com.achulkov.evomolior.core.model.GradingResult>()
    /** Compact adversarial feedback brief carried into the next generator round. */
    private val adversarialDiscriminatorBriefs = ConcurrentHashMap<String, String>()
    /** Reward multiplier for the next discriminator cycle, keyed by run id. */
    private val adversarialDiscriminatorBudgetMultipliers = ConcurrentHashMap<String, Double>()
    /** Cache of archived discriminator briefs keyed by run/group so repeated disclosures stay cheap. */
    private val archivedAdversarialDiscriminatorBriefs = ConcurrentHashMap<String, String>()
    private val graderFeedbackBriefs = ConcurrentHashMap<String, String>()
    private val archivedGraderFeedbackBriefs = ConcurrentHashMap<String, String>()
    /** Per-group stasis flags set by Watchdog signal collection. Reset each round. */
    private val stasisFlags = ConcurrentHashMap<String, Boolean>()
    /** WiP completions observed during the current round, grouped by owning group. */
    private val pendingWipCompletions = ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>()
    /** Groups that have already spawned an Auditor during this run. */
    private val auditorSpawnedGroups = ConcurrentHashMap.newKeySet<String>()
    /** Groups that have already spawned a PerspectiveTaker during this run. */
    private val perspectiveTakerSpawnedGroups = ConcurrentHashMap.newKeySet<String>()
    /** Groups that already consumed the narrow nested-hierarchy trigger during this run. */
    private val nestedHierarchyTriggeredGroups = ConcurrentHashMap.newKeySet<String>()
    /** Testable planner for deriving the first production nested-hierarchy trigger. */
    private val nestedHierarchySpawnPlanner = NestedHierarchySpawnPlanner()
    /** Watchdog instance for the active run (null when no run is active). */
    private var activeWatchdog: Watchdog? = null
    /** Inspector runner for the active run (null when no run is active). */
    private var activeInspectorRunner: InspectorRunner? = null
    /**
     * Canonical operator-facing output spec for runs started by this service.
     *
     * Kept after completion so `getResultsBundle()` can still recover the declared output
     * contract even if the persisted state file has been removed or is stale.
     */
    private val runFinalOutputSpecs = ConcurrentHashMap<String, FinalOutputSpec>()
    /**
     * KG schema JSON from the Genesis Council's [StartingField].
     * Threaded into [CriticPipeline] each round so KG_FACT submissions can be
     * validated against the council-designed schema (B-1).
     */
    @Volatile private var activeKgSchema: String = "{}"
    /**
     * B-5: Round index for which the check-in gate is currently open, or null if closed.
     * Set by [WatchdogSignal.CheckInGateOpen] and cleared by [WatchdogSignal.CheckInGateClosed].
     * [processDisclosureCheckIns] only runs when this matches the current round.
     */
    @Volatile private var checkInGateOpenRound: Int? = null

    private data class ActivatedRunInfrastructure(
        val coordinator: GroupCoordinator,
        val graderGroupManager: GraderGroupManager,
        val sheetRegisteredRounds: MutableSet<Int>
    )

    private data class ResumeBootstrapPlan(
        val persistedState: PersistedRunState?,
        val startRoundIndex: Int,
        val replayRoundIndex: Int?
    )

    private fun persistedRunStateFile(runId: String): File =
        File(substrateFactory.runControlDir(runId), PersistedRunStateStore.STATE_FILE_NAME)

    private fun readPersistedRunState(runId: String): PersistedRunState? =
        PersistedRunStateStore.read(persistedRunStateFile(runId))

    private fun writePersistedRunState(state: PersistedRunState) {
        PersistedRunStateStore.write(persistedRunStateFile(state.runId), state)
    }

    private fun resolveGroupIds(startingField: StartingField, expectedGroupCount: Int): List<String> {
        require(startingField.groupCount == expectedGroupCount) {
            "sealed StartingField groupCount=${startingField.groupCount} does not match expected=$expectedGroupCount"
        }
        require(startingField.ringTopology.size == startingField.groupCount) {
            "sealed StartingField ring size=${startingField.ringTopology.size} does not match groupCount=${startingField.groupCount}"
        }
        require(startingField.ringTopology.distinct().size == startingField.ringTopology.size) {
            "sealed StartingField ring topology contains duplicate group IDs"
        }
        require(startingField.ringTopology.all(startingField.groupProfiles::containsKey)) {
            "sealed StartingField does not define a profile for every ring group"
        }
        return startingField.ringTopology
    }

    private fun resolveRecoveryGroupProfiles(
        config: RunConfig,
        startingField: StartingField,
        groupIds: List<String>
    ): List<GroupProfileType> = groupIds.mapIndexed { index, groupId ->
        startingField.groupProfiles[groupId]
            ?: config.groupProfiles.getOrElse(index) {
                config.groupProfiles.firstOrNull() ?: GroupProfileType.Library
            }
    }

    private suspend fun loadSealedStartingFieldSnapshot(runId: String): StartingField? = withContext(Dispatchers.IO) {
        val archiveDb = substrateFactory.get(runId, META_ARCHIVE_GROUP_ID)
        val entries = runCatching {
            archiveDb.metaArchiveDao().getByRun(runId)
        }.getOrElse { emptyList() }
            .filter { it.entryType == RunArchiveService.ENTRY_STARTING_FIELD }
        val currentEntries = entries.filter { entry ->
            StartingFieldWireContract.validationError(
                entry.contentJson,
                "sealed StartingField snapshot for run=$runId"
            ) == null
        }
        if (currentEntries.isEmpty() && entries.isNotEmpty()) {
            failStartup(
                runId,
                checkNotNull(
                    StartingFieldWireContract.validationError(
                        entries.first().contentJson,
                        "sealed StartingField snapshot for run=$runId"
                    )
                )
            )
        }
        currentEntries
            .map { entry ->
                entry to decodeCurrentStartingField(
                    runId = runId,
                    subject = "sealed StartingField snapshot",
                    content = entry.contentJson
                )
            }
            .maxWithOrNull(
                compareBy<Pair<MetaArchiveEntryEntity, StartingField>> { it.second.seal != null }
                    .thenBy { it.first.id == "starting-field-$runId" }
                    .thenBy { it.first.contentJson.length }
            )
            ?.second
    }

    private suspend fun loadActiveCompiledCouncilManifestForResume(
        runId: String,
        persistedState: PersistedRunState?,
        expectedCouncilExecutionPurpose: CouncilExecutionPurpose,
        expectedAcceptanceProfile: AcceptanceProfile,
        expectedQuantitativeToolObligation: QuantitativeToolObligation?
    ): CompiledCouncilCandidateManifestV3? {
        val archiveDb = substrateFactory.get(runId, META_ARCHIVE_GROUP_ID)
        val entries = runCatching {
            archiveDb.metaArchiveDao().getByRun(runId)
        }.getOrElse { error ->
            failStartup(
                runId,
                "could not read compiled Council manifest authority during resume: ${error.message}",
                error
            )
        }
        val candidateEntries = entries.filter {
            it.entryType == RunArchiveService.ENTRY_COMPILED_COUNCIL_CANDIDATE_MANIFEST
        }
        val activeEntries = entries.filter {
            it.entryType == RunArchiveService.ENTRY_ACTIVE_COMPILED_COUNCIL_MANIFEST
        }
        if (candidateEntries.isEmpty() && activeEntries.isEmpty()) {
            if (
                persistedState?.activeCouncilManifestHash != null ||
                expectedCouncilExecutionPurpose == CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE
            ) {
                failStartup(
                    runId,
                    "current compiled run lacks an active sealed-tests-v3 Council manifest"
                )
            }
            return null
        }
        if (candidateEntries.size != 1) {
            failStartup(
                runId,
                "compiled Council recovery requires exactly one immutable candidate manifest"
            )
        }
        if (activeEntries.size != 1) {
            failStartup(
                runId,
                "compiled Council candidate is not resumable without exactly one ACTIVE manifest"
            )
        }
        val active = runCatching {
            ActiveCompiledCouncilManifestV3Codec.decodeVerified(
                activeEntries.single().contentJson
            )
        }.getOrElse { error ->
            failStartup(
                runId,
                "active compiled Council manifest is invalid during resume: ${error.message}",
                error
            )
        }
        if (active.runId != runId) {
            failStartup(runId, "active compiled Council manifest belongs to a different run")
        }
        val candidateEntry = candidateEntries.single()
        if (candidateEntry.id != active.candidateArchiveEntryId) {
            failStartup(
                runId,
                "active compiled Council manifest does not point to the exact candidate entry"
            )
        }
        val candidate = runCatching {
            CompiledCouncilCandidateManifestV3Codec.decodeVerified(
                candidateEntry.contentJson
            )
        }.getOrElse { error ->
            failStartup(
                runId,
                "compiled Council candidate manifest is invalid during resume: ${error.message}",
                error
            )
        }
        if (
            candidate.runId != runId ||
            !CouncilContractHashing.constantTimeEquals(
                active.candidateManifestHash,
                candidate.manifestHash
            )
        ) {
            failStartup(runId, "active compiled Council candidate hash or run identity is invalid")
        }
        if (
            persistedState?.activeCouncilManifestSchemaVersion !=
                ActiveCompiledCouncilManifestV3.SCHEMA_VERSION ||
            persistedState?.activeCouncilManifestHash != active.activationHash
        ) {
            failStartup(
                runId,
                "persisted run state does not reference the exact ACTIVE Council manifest"
            )
        }
        runCatching {
            requireCompiledCouncilRecoveryIntent(
                candidate = candidate,
                expectedCouncilExecutionPurpose = expectedCouncilExecutionPurpose,
                expectedAcceptanceProfile = expectedAcceptanceProfile,
                expectedQuantitativeToolObligation = expectedQuantitativeToolObligation
            )
        }.getOrElse { error ->
            failStartup(
                runId,
                error.message ?: "compiled Council recovery intent does not match",
                error
            )
        }
        val inspection = sealedTestSuiteEvaluator.inspectCompiledSuite(
            candidate.graderStartingField.sealedTestSuiteJson,
            candidate.validationContext
        )
        if (!inspection.isComplete) {
            failStartup(
                runId,
                "active sealed-tests-v3 suite is not executable during resume: " +
                    inspection.failures.joinToString("; ")
            )
        }
        val storedWorkerField = loadSealedStartingFieldSnapshot(runId)
            ?: failStartup(runId, "active compiled Council worker StartingField is missing")
        if (storedWorkerField != candidate.workerStartingField) {
            failStartup(runId, "active compiled Council worker StartingField changed after activation")
        }
        val storedWorkerAtlas = atlasTransactionRepository.currentAtlas(
            runId,
            candidate.workerAtlas.atlasId
        ) ?: failStartup(runId, "active compiled Council worker Atlas is missing")
        val storedReceiver = atlasTransactionRepository.currentReceiverContract(
            runId,
            candidate.workerAtlas.atlasId
        ) ?: failStartup(runId, "active compiled Council receiver contract is missing")
        val storedGraderAtlas = graderAuthorityRepository.currentAtlas(runId)
            ?: failStartup(runId, "active compiled Council Grader Atlas is missing")
        if (
            storedWorkerAtlas != candidate.workerAtlas ||
            storedReceiver != candidate.receiverContract ||
            storedGraderAtlas != candidate.graderAtlas
        ) {
            failStartup(runId, "active compiled Council Atlas authority changed after activation")
        }
        verifySealedTestSuite(
            runId = runId,
            firstGroupId = candidate.workerStartingField.ringTopology.firstOrNull(),
            expectedHash = candidate.graderStartingField.sealedTestSuiteHash,
            expectedSuiteJson = candidate.graderStartingField.sealedTestSuiteJson
        )
        return candidate
    }

    private suspend fun decodeCurrentStartingField(
        runId: String,
        subject: String,
        content: String
    ): StartingField {
        StartingFieldWireContract.validationError(
            content,
            "$subject for run=$runId"
        )?.let { reason -> failStartup(runId, reason) }
        return runCatching {
            runConfigJson.decodeFromString(StartingField.serializer(), content)
        }.getOrElse { error ->
            failStartup(
                runId,
                "$subject could not be decoded for run=$runId: ${error.message}",
                error
            )
        }
    }

    /**
     * Performs the service-configured Shuru admission before any runtime provider is constructed.
     *
     * In-process production does not require Shuru and therefore does not probe it. A
     * Shuru-required deployment must fail before coordinator, grader, or lifecycle construction.
     */
    private suspend fun preflightShuruIfRequired(config: RunConfig) {
        if (!requiresShuru) return
        val result = runCatching { shuruPreflight() }.getOrElse { error ->
            emit(
                EvoMoliorEvent.ShuruStatus(
                    available = false,
                    version = null,
                    message = "Shuru check exception: ${error.message}"
                )
            )
            failStartup(
                config.runId,
                "shuru_unavailable: preflight failed: ${error.message}",
                error
            )
        }
        emit(
            EvoMoliorEvent.ShuruStatus(
                available = result.available,
                version = result.version,
                message = result.message
            )
        )
        if (!result.available) {
            failStartup(config.runId, "shuru_unavailable: ${result.message}")
        }
    }

    private suspend fun activateRunInfrastructure(
        config: RunConfig,
        startingField: StartingField,
        executionEnvelope: ExecutionEnvelope?,
        groupIds: List<String>,
        groupProfiles: List<GroupProfileType>,
        graderGroupIds: List<String>,
        allowedHosts: Set<String>,
        emitRunStartedEvent: Boolean
    ): ActivatedRunInfrastructure {
        activeModelAssignments = startingField.modelAssignment
        activeModelPools = startingField.modelPools
        activeRolePrompts = startingField.rolePrompts
        activeGenerationCeiling = startingField.generationCeiling
        activeLeakIntervalGenerations = startingField.leakIntervalGenerations.coerceAtLeast(1)
        activeDispatchCadenceGenerations = startingField.dispatchLagGenerations.coerceAtLeast(1)
        activeKgSchema = startingField.kgSchema
        activeScoreNormalization = startingField.scoreNormalization
        val gradingField = activeGraderStartingField ?: startingField
        activeSealedTestSuiteJson = gradingField.sealedTestSuiteJson
        activeSealedTestSuiteHash = gradingField.sealedTestSuiteHash
        activeGroupProfiles = groupProfiles
        activeGraderGroupIds = graderGroupIds
        lastRunId = config.runId
        lastRunGroupIds = groupIds
        groupSummaries = groupIds.mapIndexed { idx, gid ->
            GroupSummary(
                groupId = gid,
                profileType = activeGroupProfiles.getOrElse(idx) { GroupProfileType.Library }.name,
                activeAgents = 0,
                remainingBudget = GroupProfile.default(activeGroupProfiles.getOrElse(idx) { GroupProfileType.Library }).spawnBudget *
                    config.maxAgentsPerGroup,
                currentRoundScore = null
            )
        }
        Napier.i("EvoMoliorRunService: initialised ${groupSummaries.size} group summaries for run ${config.runId}")
        initializeArcArtifactBackbones(
            config = config,
            startingField = startingField,
            groupIds = groupIds,
        )

        val coordinator = groupCoordinatorProviderWithReasoning(
            groupIds,
            config.scoringDimensions,
            config.openRouterApiKey,
            config.codexRuntimePreset?.reasoningEffort(Role.Connector, config.codexReasoningEffort)
                ?: config.codexReasoningEffort
        )
        coordinator.configureTopology(
            startingField.topologyKind,
            startingField.dispatchTopologyAdaptation,
            startingField.dispatchLagGenerations
        )
        coordinator.configureDispatchTrustPersistence(
            runId = config.runId,
            trustScoreDaosByGroup = groupIds.associateWith { groupId -> substrateFactory.get(config.runId, groupId).trustScoreDao() }
        )
        coordinator.configureProviderCostLedger(
            runId = config.runId,
            repository = CostLedgerRepository(
                substrateFactory.get(config.runId, "__cost_ledger__"),
                CostLedgerPolicy(
                    cumulativeCapUsd = executionEnvelope?.providerCostCapUsd
                )
            )
        )
        coordinator.configureDeliveryConsumer { sourceGroupId, targetGroupId, message, trustScore ->
            persistCrossGroupDelivery(
                runId = config.runId,
                sourceGroupId = sourceGroupId,
                targetGroupId = targetGroupId,
                message = message,
                trustScore = trustScore
            )
        }
        activeCoordinator = coordinator
        val graderGroupManager = graderGroupManagerProvider(config.scoringDimensions)
        activeGraderGroupManager = graderGroupManager
        val atlasId = resolveNodeAtlasId(config.runId, startingField)
        activeWorkerAtlasId = atlasId
        activeWorkerExecutionEnvelope = executionEnvelope
        activeYieldConstraintPolicy = startingField.yieldConstraintPolicy?.also { policy ->
            require(policy.hasValidHash()) { "StartingField yield constraint policy hash is invalid" }
        }
        val atlasProjections = requireNotNull(
            atlasTransactionRepository.currentProjections(config.runId, atlasId)
        ) {
            "Node Atlas projections are unavailable for ${config.runId}"
        }
        val resourceNodes = ResourceAtlasProjectionAdapter.fromProjection(
            atlasProjections.resources
        )
        require(resourceNodes.all { it.floorGroupId == null || it.floorGroupId in groupIds }) {
            "Council resourceNodes contain a floorGroupId outside the active group topology"
        }
        coordinator.registerResourceNodes(resourceNodes)
        activeResourceMapEngine = ResourceMapEngine(
            resourceMap = coordinator.resourceMap,
            ownershipAuthority =
                com.achulkov.evomolior.competition.resourcemap
                    .LocalProjectionResourceOwnershipAuthority
        )
        val blindTournamentService = BlindTournamentService(
            onFailureTelemetry = { telemetry ->
                routeFailureTelemetry(config.runId, graderGroupIds, telemetry)
            },
            onDeliverableNodeUpdates = { updates ->
                for (update in updates) {
                    val targetRunId = config.runId
                    val targetGroupId = update.groupId ?: continue
                    runCatching {
                        deliverableSkeletonTreeStore.applyUpdate(targetRunId, targetGroupId, update)
                        deliverableWipReconciler.reconcile(
                            runId = targetRunId,
                            groupId = targetGroupId,
                            generation = update.owningGeneration ?: currentRound ?: 0
                        )
                    }.onFailure { e ->
                        Napier.w(e) { "EvoMoliorRunService: failed to apply deliverable skeleton update for node ${update.dstNodeRef} group $targetGroupId" }
                    }
                }
            },
            onExactGraderReplay = { round, replay ->
                val live = requireNotNull(exactGraderVerdictsByRound[round]) {
                    "Scoring child replay has no matching live exact verdict for round=$round"
                }
                scoringWorkerExactGraderVerdictsByRound[round] = replay
                ScoreReplayAuthorityV1Store(substrateFactory).append(
                    runId = config.runId,
                    observation = ScoreReplayAuthority.compare(round, live, replay),
                )
            }
        ).also { it.start(groupIds) }
        activeBlindTournamentService = blindTournamentService
        startupWorkerInfrastructureActivated = true
        startupGraderInfrastructureActivated = graderGroupIds.isNotEmpty()
        val sheetRegisteredRounds = mutableSetOf<Int>()

        groupIds.forEachIndexed { idx, groupId ->
            val profileType = activeGroupProfiles.getOrElse(idx) { activeGroupProfiles.firstOrNull() ?: GroupProfileType.Library }
            val lm = lifecycleManagerProviderWithExecutionEnvelope(
                config.runId, groupId, config.openRouterApiKey, resolveAgentJarPath(),
                GroupProfile.default(profileType),
                config.llmBackend,
                executionEnvelope
            )
            lm.configureStartingField(startingField)
            executionEnvelope?.let { envelope ->
                val workerViewCompiler = WorkerViewCompiler()
                val expectedToolReuseAuthorityResolver =
                    ExpectedToolReuseAuthorityResolver()
                val leaseAuthority = AtlasLeaseAuthority(atlasTransactionRepository)
                val ttlSeconds = ordinaryAgentTtlSecondsOverride
                    ?: envelope.operatorOverrides[ORDINARY_AGENT_TTL_OVERRIDE_KEY]
                        ?.toLongOrNull()
                        ?.takeIf { it > 0 }
                    ?: DEFAULT_ORDINARY_AGENT_TTL_SECONDS
                val knowledgeRefs = startingField.inputArtifacts.map { artifact ->
                    WorkerArtifactRef(
                        artifactId = artifact.relativePath,
                        artifactType = ArtifactType.DATASET,
                        label = artifact.sourceName,
                        contentHash = artifact.sha256
                    )
                }
                lm.configureWorkerAuthority { request ->
                    leaseAuthority.expireElapsed(config.runId, atlasId)
                    val atlas = requireNotNull(
                        atlasTransactionRepository.currentAtlas(config.runId, atlasId)
                    ) { "Node Atlas $atlasId disappeared before worker spawn" }
                    val opportunityCommitments = originToolOpportunityCommitments(atlas)
                    val states = atlasTransactionRepository.currentStates(config.runId, atlasId)
                    val assignedNodeId = request.wipTaskClaim?.nodeId?.let(::NodeId)
                    val database = substrateFactory.get(config.runId, groupId)
                    val provisionalHypotheses = ProvisionalNovelHypothesisLedger(
                        database.stagingDao()
                    ).getPromoted(
                        runId = config.runId,
                        groupId = groupId,
                        limit = 32
                    )
                    val expectedReuseAuthority =
                        expectedToolReuseAuthorityResolver.resolve(
                            database = database,
                            atlas = atlas,
                            groupId = AtlasGroupId(groupId),
                            opportunityCommitments = opportunityCommitments
                        )
                    val assignedToolRepairApproach = assignedNodeId?.let { nodeId ->
                        repairApproachAssignments[
                            RepairApproachAssignmentKey(
                                groupId = groupId,
                                nodeId = nodeId,
                                round = request.generation
                            )
                        ]
                    }
                    val assignedToolRepairDiagnostics: List<WorkerToolRepairDiagnostic> =
                        assignedNodeId?.takeIf { assignedToolRepairApproach != null }?.let { nodeId ->
                            val opportunity = atlas.nodes.getValue(nodeId).toolOpportunity
                                ?: return@let emptyList()
                            val commitment = requireNotNull(
                                opportunityCommitments[opportunity.opportunityId]
                            ) {
                                "Worker repair context lacks origin-version opportunity authority"
                            }
                            WorkerToolRepairDiagnosticAssembler.project(
                                diagnostics = ToolRepairPersistenceRepository(database)
                                    .visibleDiagnostics(
                                        runId = config.runId,
                                        groupId = groupId,
                                        opportunityId =
                                            commitment.opportunity.opportunityId,
                                        round = request.generation
                                    ),
                                runId = config.runId,
                                groupId = AtlasGroupId(groupId),
                                round = request.generation,
                                commitment = commitment
                            )
                        }.orEmpty()
                    val instant = Clock.System.now()
                    val expiresAt = instant + ttlSeconds.seconds
                    val lease = assignedNodeId?.let { nodeId ->
                        leaseAuthority.acquire(
                            runId = config.runId,
                            atlasId = atlasId,
                            groupId = AtlasGroupId(groupId),
                            agentId = AtlasAgentId(request.agentId),
                            nodeId = nodeId,
                            round = request.generation,
                            expiresAt = expiresAt
                        )
                    }
                    val workerView = workerViewCompiler.compile(
                        atlas = atlas,
                        states = if (lease == null) {
                            states
                        } else {
                            atlasTransactionRepository.currentStates(config.runId, atlasId)
                        },
                        groupId = AtlasGroupId(groupId),
                        round = request.generation,
                        assignedNodeId = assignedNodeId,
                        feedback = disclosureFeedbackByGroup[groupId],
                        allowedKnowledgeRefs = knowledgeRefs,
                        availableToolCards = assignedNodeId
                            ?.let(expectedReuseAuthority.toolCardsByNodeId::get)
                            .orEmpty(),
                        visibleShadowNodes = visibleShadowNodes(
                            runId = config.runId,
                            groupId = AtlasGroupId(groupId),
                            round = request.generation
                        ),
                        provisionalHypotheses = provisionalHypotheses,
                        outlierMandate = if (request.role == Role.Outlier) {
                            "Propose one evidence-grounded novelty or assumption challenge using only the visible Atlas frontier."
                        } else {
                            null
                        },
                        opportunityCommitments = opportunityCommitments,
                        assignedToolRepairDiagnostics = assignedToolRepairDiagnostics,
                        assignedToolRepairApproach = assignedToolRepairApproach
                    )
                    WorkerAuthorityAssignment(
                        workerView = workerView,
                        assignedLease = lease?.let {
                            WorkerLeaseAssignment(
                                leaseId = it.leaseId,
                                nodeId = it.nodeId,
                                expiresAt = it.expiresAt,
                                replicationSlot = it.replicationSlot
                            )
                        },
                        ttlPolicy = AgentTtlPolicy(
                            lifetimeSeconds = ttlSeconds,
                            deadline = expiresAt
                        ),
                        finalizeLease = { committed ->
                            lease?.let {
                                leaseAuthority.finalize(
                                    runId = config.runId,
                                    atlasId = atlasId,
                                    leaseId = it.leaseId,
                                    committed = committed
                                )
                            }
                        }
                    )
                }
            }
            lm.configureRunArtifacts(
                inputArtifacts = startingField.inputArtifacts,
                artifactsMountPath = if (startingField.inputArtifacts.isNotEmpty()) resolveRunArtifactsRoot(config.runId).absolutePath else null,
                initialContextAllowedHosts = allowedHosts
            )
            lm.onAgentSpawned = { agentId, role, modelId ->
                scope.launch {
                    emit(EvoMoliorEvent.AgentSpawned(config.runId, groupId, agentId, role))
                    Napier.i("[$groupId] Agent spawned: $agentId (${role.name}) model=$modelId")
                    emit(EvoMoliorEvent.EngineProgress(config.runId, "agent_spawned",
                        "[$groupId] ${role.name} agent $agentId started (model=$modelId)"))
                    groupSummaries = groupSummaries.map { s ->
                        if (s.groupId == groupId) s.copy(activeAgents = s.activeAgents + 1) else s
                    }
                }
            }
            lm.onAgentDied = { agentId, wasKilled ->
                scope.launch {
                    val reason = if (wasKilled) "killed" else "completed"
                    emit(EvoMoliorEvent.AgentDied(config.runId, groupId, agentId, reason))
                    val usefulExit = WorkerExecutionRepository(
                        substrateFactory.get(config.runId, groupId)
                    ).usefulExitSummary(config.runId, groupId)
                    emit(
                        EvoMoliorEvent.UsefulExitRateUpdated(
                            runId = config.runId,
                            groupId = groupId,
                            usefulExitCount = usefulExit.usefulExitCount,
                            ordinaryExitCount = usefulExit.ordinaryExitCount,
                            usefulExitRate = usefulExit.usefulExitRate
                        )
                    )
                    Napier.i("[$groupId] Agent $agentId $reason")
                    emit(EvoMoliorEvent.EngineProgress(config.runId, "agent_died",
                        "[$groupId] Agent $agentId $reason"))
                    groupSummaries = groupSummaries.map { s ->
                        if (s.groupId == groupId) s.copy(activeAgents = maxOf(0, s.activeAgents - 1)) else s
                    }
                }
            }
            val budgetTelemetryMutex = Mutex()
            var lastBudgetTelemetryRevision = -1L
            var lastBudgetTelemetryGeneration = -1
            lm.onBudgetTelemetryChanged = { observation ->
                scope.launch {
                    budgetTelemetryMutex.withLock {
                        if (observation.revision <= lastBudgetTelemetryRevision ||
                            observation.generation < lastBudgetTelemetryGeneration) return@withLock
                        lastBudgetTelemetryRevision = observation.revision
                        lastBudgetTelemetryGeneration = observation.generation
                        val accounting = com.achulkov.evomolior.api.events.GroupBudgetAccounting(
                            generation = observation.generation,
                            allocatedCredits = observation.allocatedCredits,
                            liveAllocatedCredits = observation.liveAllocatedCredits,
                            liveCredits = observation.liveCredits,
                            spentCredits = observation.spentCredits,
                            returnedCredits = observation.returnedCredits,
                            liveAgentCount = observation.liveAgentCount,
                            revision = observation.revision,
                        )
                        emit(EvoMoliorEvent.GroupBudgetChanged(
                            config.runId, groupId, observation.liveCredits, accounting))
                        groupSummaries = groupSummaries.map { summary ->
                            if (summary.groupId == groupId) summary.copy(
                                remainingBudget = observation.liveCredits) else summary
                        }
                        Napier.i("[$groupId] Credit accounting generation=${observation.generation}: " +
                            "allocated=${observation.allocatedCredits} live=${observation.liveCredits} " +
                            "spent=${observation.spentCredits} returned=${observation.returnedCredits} " +
                            "ownedAgents=${observation.liveAgentCount} (not provider USD)")
                        if (activeGenerationBudgetRounds[groupId] == observation.generation) {
                            activeWatchdog?.updateBudget(groupId, observation)
                        }
                    }
                }
            }
            lm.onSpawnFailed = { agentId, role, error ->
                scope.launch {
                    val msg = error.message ?: "unknown error"
                    emit(EvoMoliorEvent.AgentSpawnFailed(config.runId, groupId, agentId, role, msg))
                    Napier.e("[$groupId] Agent spawn failed: $agentId (${role.name}) — $msg")
                    emit(EvoMoliorEvent.EngineProgress(config.runId, "spawn_error",
                        "[$groupId] Failed to spawn ${role.name} agent: $msg"))
                }
            }
            lm.onWipTaskAbandoned = { agentId, nodeId, generation ->
                scope.launch {
                    emit(EvoMoliorEvent.EngineProgress(
                        config.runId,
                        "wip_abandoned",
                        "[$groupId] WiP task $nodeId abandoned by $agentId at generation $generation"
                    ))
                }
            }
            liveLifecycleManagers[groupId] = lm
        }

        val disclosureProjection = atlasProjections.disclosure
        val resolvedDisclosureStages = DisclosureAtlasProjectionAdapter.fromProjection(
            disclosureProjection
        )
        activeDisclosureEngine = resolvedDisclosureStages.takeIf { it.isNotEmpty() }?.let { stages ->
            ProgressiveDisclosureEngine(
                stages = stages,
                stageAuthority = AtlasDisclosureStageAuthority(
                    runId = config.runId,
                    atlasId = atlasId,
                    repository = atlasTransactionRepository
                )
            ).also { engine ->
                groupIds.forEach { groupId ->
                    engine.registerGroup(
                        groupId = groupId,
                        initialStageIndex = disclosureProjection.groupStageIndices[groupId] ?: 0,
                        initialLeakMultiplier = disclosureProjection.groupLeakMultipliers[groupId] ?: 1.0
                    )
                }
                Napier.i("EvoMoliorRunService: ProgressiveDisclosureEngine initialised with ${stages.size} stages")
            }
        }

        if (emitRunStartedEvent) {
            startupRunStartedEmitted = true
            emit(EvoMoliorEvent.RunStarted(
                runId = config.runId,
                objective = config.objective,
                scoringDimensionIds = config.scoringDimensions.map { it.id }
            ))
            Napier.i("EvoMoliorRunService: emitted RunStarted for run ${config.runId}")
        }

        val watchdog = Watchdog(
            substrateDatabaseFactory = substrateFactory,
            runId = config.runId
        )
        activeWatchdog = watchdog
        watchdog.start(scope)

        val inspector = InspectorRunner(watchdog = watchdog, checkIntervalMs = inspectorCheckIntervalMs)
        activeInspectorRunner = inspector
        stasisFlags.clear()

        groupIds.forEach { gid ->
            inspector.startFor(gid, scope) {
                try {
                    substrateFactory.get(config.runId, gid)
                        .eventLogDao()
                        .queryFiltered(config.runId, gid, null, null, null, null, 200, 0)
                        .map(InspectorObservation::from)
                } catch (_: Exception) { emptyList() }
            }
        }

        scope.launch {
            watchdog.signals.collect { signal ->
                handleWatchdogSignal(config, signal, groupIds)
            }
        }

        return ActivatedRunInfrastructure(
            coordinator = coordinator,
            graderGroupManager = graderGroupManager,
            sheetRegisteredRounds = sheetRegisteredRounds
        )
    }

    private fun updatePersistedRunState(runId: String, transform: (PersistedRunState) -> PersistedRunState) {
        val current = readPersistedRunState(runId) ?: return
        writePersistedRunState(
            transform(current).copy(lastUpdatedAtMs = System.currentTimeMillis())
        )
    }

    private fun updatePersistedRunStatus(runId: String, status: PersistedRunStatus) {
        updatePersistedRunState(runId) { current ->
            current.copy(status = status)
        }
    }

    private suspend fun buildResumeBootstrapPlan(
        config: RunConfig,
        groupIds: List<String>,
        persistedState: PersistedRunState?
    ): ResumeBootstrapPlan {
        if (persistedState != null) {
            val replayRoundIndex = persistedState.activeRoundIndex
            val startRoundIndex = replayRoundIndex ?: persistedState.nextRoundIndex
            return ResumeBootstrapPlan(
                persistedState = persistedState,
                startRoundIndex = startRoundIndex,
                replayRoundIndex = replayRoundIndex
            )
        }

        val archiveDb = substrateFactory.get(config.runId, META_ARCHIVE_GROUP_ID)
        val lastCompletedRound = runCatching {
            archiveDb.metaArchiveDao().getByRun(config.runId)
                .asSequence()
                .filter { it.entryType == RunArchiveService.ENTRY_ROUND_SUMMARY }
                .maxOfOrNull { it.generation }
        }.getOrNull()

        val maxObservedGeneration = groupIds.maxOfOrNull { groupId ->
            val db = substrateFactory.get(config.runId, groupId)
            listOfNotNull(
                runCatching { db.eventLogDao().getMaxGeneration(config.runId, groupId) }.getOrNull(),
                runCatching { db.stagingDao().getMaxGeneration(config.runId, groupId) }.getOrNull()
            ).maxOrNull() ?: -1
        } ?: -1

        val replayRoundIndex = when {
            maxObservedGeneration > (lastCompletedRound ?: -1) -> maxObservedGeneration
            else -> null
        }
        val startRoundIndex = replayRoundIndex ?: ((lastCompletedRound ?: -1) + 1)
        return ResumeBootstrapPlan(
            persistedState = null,
            startRoundIndex = startRoundIndex,
            replayRoundIndex = replayRoundIndex
        )
    }

    private suspend fun sanitizeReplayRound(
        runId: String,
        groupIds: List<String>,
        graderGroupIds: List<String>,
        replayRoundIndex: Int
    ) {
        suspend fun sanitizeGroup(groupId: String) {
            val db = substrateFactory.get(runId, groupId)
            db.stagingDao().getPendingByGeneration(runId, groupId, replayRoundIndex).forEach { pending ->
                db.stagingDao().updateReview(
                    id = pending.id,
                    status = "rejected",
                    criticId = "resume-recovery",
                    trustScore = pending.initialTrustScore,
                    reason = "recovery_replay_round_$replayRoundIndex",
                    reviewedAtMs = System.currentTimeMillis()
                )
            }
            db.wipTaskDao().releaseClaimsFromGeneration(runId, groupId, replayRoundIndex)
        }

        groupIds.forEach { sanitizeGroup(it) }
        graderGroupIds.forEach { sanitizeGroup(it) }
    }

    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override suspend fun startRun(requestedConfig: RunConfig): String = mutex.withLock {
        val config = requestedConfig.copy(scoringDimensions = RunScoringDimensionPolicy.select(requestedConfig))
        activeRunId?.let { existingRunId ->
            rejectAdmissionBeforeOwnership(
                config,
                "Run $existingRunId is already active — abort it first"
            )
        }
        activeRunId = config.runId
        terminalOwner.set(null)
        activeRunLoopJob = null
        startupRunStartedEmitted = false
        startupWorkerInfrastructureActivated = false
        startupGraderInfrastructureActivated = false
        initializeStartupRejectionArtifact(config)
        try {
            requireSourceGroundingPath(config)
        } catch (error: Exception) {
            failStartup(config.runId, error.message ?: "source grounding admission failed", error)
        }
        if (config.acceptanceProfile != AcceptanceProfile.NORMAL) {
            if (!hasConfiguredAcceptanceRunBoundaryVerifier()) {
                failStartup(
                    config.runId,
                    "Acceptance profile ${config.acceptanceProfile.id} requires a non-NONE AcceptanceRunBoundaryVerifier"
                )
            }
            if (archiveService == null) failStartup(config.runId, "Acceptance profile ${config.acceptanceProfile.id} requires a durable run archive service")
            if (genesisCouncilRunner == null) failStartup(config.runId, "Acceptance profile ${config.acceptanceProfile.id} requires production Genesis Council compilation")
            if (config.finalOutputSpec == null) failStartup(config.runId, "Acceptance profile ${config.acceptanceProfile.id} requires an explicit FinalOutputSpec")
        }
        if (config.councilExecutionPurpose == CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE) {
            if (archiveService == null) failStartup(config.runId, "Appendix B acceptance requires a durable run archive service")
            if (resolveFinalOutputSpec(config) == null) failStartup(config.runId, "Appendix B acceptance requires a receiver-facing final output contract")
        }
        comparativeRuntimeInputs?.let { inputs ->
            if (!config.isTestRun) {
                failStartup(config.runId, "Frozen comparative runtime inputs are restricted to experiment runs")
            }
            if (inputs.atlas.runId != config.runId) {
                failStartup(config.runId, "Frozen comparative Atlas belongs to a different logical run")
            }
            if (inputs.receiverContract.rawObjective != config.objective) {
                failStartup(config.runId, "Frozen comparative receiver contract changed the exact operator objective")
            }
        }

        Napier.i("EvoMoliorRunService: starting run ${config.runId} groups=${config.groupCount} rounds=${config.rounds}")
        config.finalOutputSpec?.let { runFinalOutputSpecs[config.runId] = it } ?: runFinalOutputSpecs.remove(config.runId)
        currentStatus = EngineStatus.GENESIS
        emit(EvoMoliorEvent.EngineProgress(
            config.runId,
            "scoring_dimensions",
            "Enabled scoring dimensions: ${config.scoringDimensions.joinToString(", ") { "${it.id}(weight=${it.weight})" }}"
        ))

        currentRound = null

        val continuationSourceRunId = config.continuationFromRunId?.trim()?.takeIf { it.isNotBlank() }
        val continuationSourceStartingField = continuationSourceRunId?.let { sourceRunId ->
            loadSealedStartingField(sourceRunId, config.runId)
                ?: failStartup(config.runId, "continuation source run=$sourceRunId does not have a sealed StartingField")
        }
        if (continuationSourceStartingField != null) {
            if (config.isTestRun && continuationSourceStartingField.groupCount != 1) {
                failStartup(
                    config.runId,
                    "test-run continuation requires a single-group source run, but ${continuationSourceStartingField.runId} has ${continuationSourceStartingField.groupCount} groups"
                )
            }
            emit(
                EvoMoliorEvent.EngineProgress(
                    config.runId,
                    "continuation_start",
                    "Continuing from completed run ${continuationSourceStartingField.runId}"
                )
            )
            Napier.i(
                "EvoMoliorRunService: continuing run ${config.runId} from source run ${continuationSourceStartingField.runId}"
            )
        }

        if (continuationSourceRunId != null) {
            copyContinuationArtifacts(
                sourceRunId = continuationSourceRunId,
                targetRunId = config.runId,
                sourceArtifacts = continuationSourceStartingField?.inputArtifacts.orEmpty()
            )
        }
        val uploadedArtifacts = materializeRunInputArtifacts(config.runId, config.contextArtifacts)

        // ── Genesis Council ───────────────────────────────────────────────────
        // Run the Genesis Council before setting up groups. The council designs the run:
        // model assignments, role prompts, scoring dimensions, KG schema, etc.
        // Production Council output must compile. Missing, invalid, failed, or timed-out Council
        // execution aborts startup without activating any worker or grader group.
        //
        // TEST RUN SHORT-CIRCUIT: When config.isTestRun is true, skip the Genesis Council
        // entirely and use a minimal StartingField with a fixed budget. This lets developers
        // verify the agent pipeline (tasks, KB, death notes, substrate) without spending real
        // LLM budget on council orchestration.
        var councilProducedField = false
        var compiledCouncilField: StartingFieldCompileResult.Valid? = null
        var compiledCouncilEnvelope: ExecutionEnvelope? = null
        val sourceGroupCount = continuationSourceStartingField?.groupCount ?: config.groupCount
        val routedContextSummaries = continuationSourceStartingField?.let { source ->
            routeContinuationContextSummaries(
                sourceRunId = source.runId,
                sourceStartingField = source,
                objective = config.objective
            )
        }.orEmpty()
        var startingField: StartingField = when {
            continuationSourceStartingField != null -> buildContinuationStartingField(
                source = continuationSourceStartingField,
                config = config,
                routedContextSummaries = routedContextSummaries
            )
            config.isTestRun -> {
                Napier.i("EvoMoliorRunService: TEST RUN — skipping Genesis Council, using frozen or minimal StartingField (budget=${config.budgetOverride ?: 200.0})")
                emit(EvoMoliorEvent.EngineProgress(config.runId, "genesis_skipped",
                    "Test run — Genesis Council bypassed. Using fixed defaults (budget=${config.budgetOverride ?: 200.0})."))
                val testGroupProfiles = config.groupProfiles.mapIndexed { idx, pt ->
                    "group-$idx" to pt
                }.toMap()
                comparativeRuntimeInputs?.startingFieldTemplate?.copy(
                    runId = config.runId,
                    objective = config.objective,
                    groupCount = config.groupCount,
                    groupProfiles = testGroupProfiles,
                    ringTopology = testGroupProfiles.keys.toList(),
                    graderGroupCount = config.graderGroupCountOverride ?: 0,
                    globalBudgetCeiling = config.budgetOverride ?: 200.0,
                    generationCeiling = config.rounds,
                    seal = null
                ) ?: StartingField.default(
                    objective = config.objective,
                    runId = config.runId,
                    groupCount = config.groupCount,
                    groupProfilesMap = testGroupProfiles,
                    budgetCeiling = config.budgetOverride ?: 200.0,
                    generationCeiling = config.rounds
                )
            }
            genesisCouncilRunner != null -> {
                startupRejectionArtifact = startupRejectionArtifact?.copy(
                    phase = StartupRejectionPhase.COUNCIL_COMPILE,
                    reasons = listOf(StartupRejectionReason.COUNCIL_EXECUTION_FAILED),
                    errors = listOf(StartupRejectionError(
                        kind = StartupRejectionErrorKind.COUNCIL_COMPILE,
                        message = "Genesis Council compilation has not completed"
                    )),
                    activationFlags = startupActivationFlags()
                )
                Napier.i("EvoMoliorRunService: starting Genesis Council for run ${config.runId}")
                emit(EvoMoliorEvent.EngineProgress(config.runId, "genesis", "Genesis Council starting — designing this run…"))
                val envelope = runCatching {
                    CouncilExecutionEnvelopeFactory.create(config)
                }.getOrElse { error ->
                    failStartup(
                        config.runId,
                        "could not construct the Genesis Council execution envelope: ${error.message}",
                        error
                    )
                }
                if (config.acceptanceProfile != AcceptanceProfile.NORMAL) {
                    require(envelope.requiredWorkerGroupCount.minimum >= 2) {
                        "Acceptance profile ${config.acceptanceProfile.id} requires at least two worker groups"
                    }
                    require(envelope.requiredGraderGroupCount.minimum >= 2) {
                        "Acceptance profile ${config.acceptanceProfile.id} requires at least two grader groups"
                    }
                }
                compiledCouncilEnvelope = envelope
                startupRejectionArtifact = startupRejectionArtifact?.copy(
                    declaredWorkerGroupCount = envelope.requiredWorkerGroupCount.minimum,
                    declaredGraderGroupCount = envelope.requiredGraderGroupCount.minimum
                )
                val compileResultOrNull = try {
                    val timeout = LlmTimeoutPolicy.requireFinitePositive(
                        "Genesis Council timeout",
                        genesisCouncilTimeoutResolver(config.llmBackend)
                    )
                    withTimeoutOrNull(timeout.inWholeMilliseconds.coerceAtLeast(1L)) {
                        var invocationStarted = false
                        val result = genesisCouncilRunner.invoke(
                            config.runId,
                            config.objective,
                            config.openRouterApiKey,
                            envelope,
                            uploadedArtifacts.artifacts,
                            { progressMsg ->
                                scope.launch {
                                    emit(EvoMoliorEvent.EngineProgress(config.runId, "genesis_agent", progressMsg))
                                    Napier.i("GenesisCouncil: $progressMsg")
                                }
                            },
                            {
                                check(!invocationStarted) {
                                    "Genesis Council runner declared invocation start more than once"
                                }
                                invocationStarted = true
                                recordGenesisCouncilInvocation(config, envelope)
                            }
                        )
                        check(invocationStarted) {
                            "Genesis Council runner returned without declaring invocation start"
                        }
                        result
                    }
                } catch (e: Exception) {
                    failStartup(
                        config.runId,
                        "Genesis Council execution failed: ${e.message}",
                        e
                    )
                }
                val compileResult = compileResultOrNull ?: failStartup(
                    config.runId,
                    "Genesis Council timed out before producing a compiled field"
                )
                when (compileResult) {
                    is StartingFieldCompileResult.Invalid -> {
                        val detail = compileResult.errors.joinToString("; ") {
                            "${it.path}: ${it.message}"
                        }
                        failStartup(
                            config.runId,
                            "Genesis Council field compilation failed: $detail",
                            rejection = startupRejectionArtifactForInvalidCompile(
                                config,
                                envelope,
                                compileResult
                            )
                        )
                    }
                    is StartingFieldCompileResult.Valid -> {
                        if (compileResult.workerField.objective != config.objective ||
                            compileResult.graderField.objective != config.objective
                        ) {
                            failStartup(
                                config.runId,
                                "compiled Council field changed the exact operator objective"
                            )
                        }
                        if (compileResult.workerField.runId != config.runId) {
                            failStartup(
                                config.runId,
                                "compiled Council worker field has the wrong run ID"
                            )
                        }
                        compiledCouncilField = compileResult
                        councilProducedField = true
                        compileResult.workerField
                    }
                }
            }
            else -> {
                failStartup(
                    config.runId,
                    "production run requires a configured Genesis Council compiler"
                )
            }
        }
        if (compiledCouncilField == null) {
            startingField = canonicalizeWorkerStartingField(
                startingField = startingField,
                runId = config.runId,
                requestedGroupCount = sourceGroupCount,
                fallbackProfiles = config.groupProfiles
            )
            startingField = applyModelOverrides(
                applyCodexRuntimePreset(
                    applyObjectiveExpertRouting(canonicalizeStartingField(startingField)),
                    config,
                ),
                config.modelOverrides
            )
            startingField = normalizeStartingFieldModelsForBackend(startingField, config.llmBackend)
            startingField = seedFeedforwardLayerIfMissing(startingField)
        }
        val mergedInputArtifacts = mergeInputArtifacts(
            startingField.inputArtifacts,
            uploadedArtifacts.artifacts
        )
        val initialAllowedHosts = SourceAccessPolicy.initialAllowedHosts(
            objective = config.objective + "\n" + startingField.objective + "\n" +
                mergedInputArtifacts.joinToString("\n") { artifact -> artifact.textPreview.orEmpty() },
            artifactHosts = uploadedArtifacts.allowedHosts
        )
        if (mergedInputArtifacts.isNotEmpty()) {
            if (compiledCouncilField != null && mergedInputArtifacts != startingField.inputArtifacts) {
                failStartup(
                    config.runId,
                    "compiled Council field does not contain the exact materialized input artifacts"
                )
            }
            startingField = startingField.copy(inputArtifacts = mergedInputArtifacts)
        }
        val graderStartingField = compiledCouncilField?.graderField
            ?: resolveGraderStartingField(
                config = config,
                workerStartingField = startingField
            )
        if (compiledCouncilField == null) {
            startingField = startingField.copy(
                graderGroupCount = graderStartingField?.groupCount ?: 0
            )
        } else if (startingField.graderGroupCount != graderStartingField?.groupCount) {
            failStartup(config.runId, "compiled worker and grader group counts disagree")
        }
        val gradingField = graderStartingField ?: startingField
        val councilModelSummary = when {
            startingField.modelAssignment.isNotEmpty() ->
                startingField.modelAssignment.entries.joinToString(", ") { "${it.key}→${it.value}" }
            startingField.modelPools.isNotEmpty() ->
                startingField.modelPools.entries.joinToString(", ") { (role, pool) ->
                    "$role→[${pool.joinToString(", ")}]"
                }
            else -> "defaults"
        }
        Napier.i("EvoMoliorRunService: Genesis Council complete — models=[$councilModelSummary]")
        emit(EvoMoliorEvent.EngineProgress(config.runId, "genesis_complete",
            "Genesis Council complete — ${startingField.groupCount} groups, models: $councilModelSummary"))
        // Emit rich Genesis Council summary event for the Log Viewer / Dashboard.
        emit(EvoMoliorEvent.GenesisCouncilCompleted(
            runId = config.runId,
            groupCount = startingField.groupCount,
            modelAssignment = startingField.modelAssignment,
            rolePromptSummary = startingField.rolePrompts.mapValues { (_, v) ->
                v.lines().firstOrNull { it.isNotBlank() }?.take(120) ?: "(empty)"
            },
            stageCount = startingField.stageContents.size,
            scoringDimensionIds = config.scoringDimensions.map { it.id },
            kgSchemaNodeCount = if (startingField.kgSchema == "{}") 0
                else startingField.kgSchema.split("\"type\"").size - 1,
            primordialKbCount = startingField.primordialKb.size,
            primordialFactCount = startingField.primordialKgFacts.size,
            // B-7: `usedDefault` tracks whether the council runner produced the field.
            // A council that deliberately designs empty model assignments would previously
            // set usedDefault=true incorrectly; now it is false iff the council actually ran.
            usedDefault = !councilProducedField,
            // B-3: Surface the CouncilSynthesis consistency report in the log viewer / dashboard.
            consistencyReport = startingField.consistencyReport
        ))

        compiledCouncilField?.let { compiled ->
            val envelope = requireNotNull(compiledCouncilEnvelope)
            if (startingField != compiled.workerField ||
                graderStartingField != compiled.graderField
            ) {
                failStartup(
                    config.runId,
                    "compiled Council fields were materially changed before persistence"
                )
            }
            val seal = startingField.seal ?: failStartup(
                config.runId,
                "compiled Council field is missing its shared seal"
            )
            if (!CouncilFieldSealing.verifySeal(
                    seal = seal,
                    workerField = startingField,
                    graderField = compiled.graderField,
                    atlas = compiled.atlas,
                    graderAtlas = compiled.graderAtlas,
                    receiverContract = compiled.receiverContract,
                    envelope = envelope,
                    report = compiled.report
                )
            ) {
                failStartup(config.runId, "compiled Council field seal verification failed")
            }
        }
        // Build group IDs from the council's ring order when available so topology stays canonical.
        val groupIds = resolveGroupIds(startingField, sourceGroupCount)
        val groupProfiles = resolveRecoveryGroupProfiles(config, startingField, groupIds)
        // Grader groups come from the dedicated grader-civilisation seed.
        val graderGroupIds = graderStartingField?.ringTopology
            ?: (0 until startingField.graderGroupCount).map { "grader-$it" }
        if (
            config.acceptanceProfile != AcceptanceProfile.NORMAL &&
            (groupIds.size < 2 || graderGroupIds.size < 2)
        ) {
            failStartup(
                config.runId,
                "Acceptance profile ${config.acceptanceProfile.id} requires at least two " +
                    "worker groups and two grader groups"
            )
        }

        if (
            config.councilExecutionPurpose == CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE &&
            gradingField.sealedTestSuiteJson.isBlank()
        ) {
            failStartup(
                config.runId,
                "Appendix B acceptance requires a non-empty compiled sealed test suite"
            )
        }
        preflightShuruIfRequired(config)
        startupRejectionArtifact = startupRejectionArtifact?.copy(
            phase = StartupRejectionPhase.TEST_EXECUTABILITY_PREFLIGHT,
            reasons = listOf(StartupRejectionReason.TEST_SUITE_NOT_EXECUTABLE),
            errors = listOf(StartupRejectionError(
                kind = StartupRejectionErrorKind.TEST_EXECUTABILITY,
                message = "Sealed test suite preflight has not completed"
            )),
            candidateFieldHash = sha256Hex(
                runConfigJson.encodeToString(StartingField.serializer(), startingField).toByteArray()
            ),
            testSuiteHash = gradingField.sealedTestSuiteHash.takeIf { it.isNotBlank() },
            compiledWorkerGroupCount = startingField.groupCount,
            compiledGraderGroupCount = graderStartingField?.groupCount ?: startingField.graderGroupCount,
            activationFlags = startupActivationFlags()
        )
        if (gradingField.sealedTestSuiteJson.isNotBlank()) {
            val inspection = compiledCouncilField?.sealedTests?.validationContext
                ?.let { validationContext ->
                    sealedTestSuiteEvaluator.inspectCompiledSuite(
                        gradingField.sealedTestSuiteJson,
                        validationContext
                    )
                } ?: if (
                config.councilExecutionPurpose == CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE
            ) {
                sealedTestSuiteEvaluator.inspectCompiledSuite(gradingField.sealedTestSuiteJson)
            } else {
                sealedTestSuiteEvaluator.inspectSuite(gradingField.sealedTestSuiteJson)
            }
            if (!inspection.isComplete) {
                failStartup(
                    config.runId,
                    "sealed test suite is not runtime-executable: " +
                        "${inspection.executableTestCount}/${inspection.declaredTestCount} tests parsed" +
                        inspection.failures.takeIf { it.isNotEmpty() }
                            ?.joinToString(prefix = " (", postfix = ")", separator = "; ")
                            .orEmpty(),
                    rejection = startupRejectionArtifactForPreflight(
                        config = config,
                        startingField = startingField,
                        graderStartingField = graderStartingField,
                        envelope = compiledCouncilEnvelope,
                        declaredTestCount = inspection.declaredTestCount,
                        compiledTestCount = inspection.executableTestCount,
                        failures = inspection.failures
                    )
                )
            }
            Napier.i(
                "EvoMoliorRunService: sealed test suite preflight passed for run=${config.runId}: " +
                    "${inspection.executableTestCount} executable test(s)"
            )
        }
        startupRejectionArtifact = startupRejectionArtifact?.copy(
            phase = StartupRejectionPhase.PRE_ACTIVATION,
            reasons = listOf(StartupRejectionReason.PRE_ACTIVATION_INVARIANT_FAILED),
            errors = listOf(StartupRejectionError(
                kind = StartupRejectionErrorKind.PRE_ACTIVATION,
                message = "Pre-activation persistence has not completed"
            )),
            candidateFieldHash = CouncilContractHashing.hashStartingField(startingField),
            testSuiteHash = gradingField.sealedTestSuiteHash.takeIf(String::isNotBlank),
            compiledWorkerGroupCount = startingField.groupCount,
            compiledGraderGroupCount = graderStartingField?.groupCount
                ?: startingField.graderGroupCount,
            activationFlags = startupActivationFlags()
        )
        val persistedCompiledCandidate =
            if (compiledCouncilField != null && compiledCouncilEnvelope != null) {
                persistCompiledCouncilCandidate(
                    runId = config.runId,
                    compiled = compiledCouncilField,
                    envelope = compiledCouncilEnvelope
                )
            } else {
                null
            }
        persistedCompiledCandidate?.let {
            emit(EvoMoliorEvent.EngineProgress(
                config.runId,
                "council_candidate_persisted",
                "Compiled Council candidate persisted and read back"
            ))
        }
        val activeCompiledManifest = persistedCompiledCandidate?.let { candidate ->
            activateCompiledCouncilCandidate(config.runId, candidate)
        }
        activeCompiledManifest?.let {
            emit(EvoMoliorEvent.EngineProgress(
                config.runId,
                "council_field_activated",
                "Compiled Council candidate promoted to ACTIVE"
            ))
        }
        persistSealedStartingField(config.runId, startingField)
        if (gradingField.sealedTestSuiteJson.isNotBlank()) {
            persistSealedTestSuite(config.runId, groupIds.firstOrNull(), gradingField)
            verifySealedTestSuite(
                runId = config.runId,
                firstGroupId = groupIds.firstOrNull(),
                expectedHash = gradingField.sealedTestSuiteHash,
                expectedSuiteJson = gradingField.sealedTestSuiteJson
            )
        }
        persistedCompiledCandidate?.let {
            emit(EvoMoliorEvent.EngineProgress(
                config.runId,
                "council_canonical_authority_persisted",
                "Canonical compiled Council field and sealed suite persisted"
            ))
        }
        Napier.i(
            "EvoMoliorRunService: stored ${startingField.modelAssignment.size} model assignments, " +
                "${startingField.modelPools.size} model pools, and ${startingField.rolePrompts.size} role prompts " +
                "from Genesis Council (generationCeiling=${startingField.generationCeiling})"
        )

        // ── Seed primordial substrate data for each group ─────────────────
        // KB documents, KG facts, and WIP tasks from the Genesis Council are inserted
        // before the first round so agents have starting context and tasks to claim.
        // Grader groups receive the same seed so their KB is not empty at first eval.
        seedPrimordialData(
            runId = config.runId,
            groupIds = groupIds,
            workerStartingField = startingField,
            graderGroupIds = graderGroupIds,
            graderStartingField = graderStartingField
        )
        ensureNodeAtlasAuthority(
            config = config,
            startingField = startingField,
            groupIds = groupIds,
            authoritativeAtlas = comparativeRuntimeInputs?.atlas ?: compiledCouncilField?.atlas,
            authoritativeReceiverContract = comparativeRuntimeInputs?.receiverContract
                ?: compiledCouncilField?.receiverContract
        )
        compiledCouncilField?.let { compiled ->
            graderAuthorityRepository.appendAtlas(compiled.graderAtlas)
        }
        persistedCompiledCandidate?.let {
            emit(EvoMoliorEvent.EngineProgress(
                config.runId,
                "council_substrates_seeded",
                "Canonical compiled Council worker and grader substrate projections seeded"
            ))
        }
        persistedCompiledCandidate?.let { candidate ->
            verifyActiveCompiledCouncilCanonicalProjections(
                runId = config.runId,
                candidate = candidate,
                active = requireNotNull(activeCompiledManifest)
            )
        }
        activeGraderStartingField = graderStartingField
        activeSealedTestSuiteJson = gradingField.sealedTestSuiteJson
        activeSealedTestSuiteHash = gradingField.sealedTestSuiteHash
        activeSealedTestSuiteValidationContext =
            persistedCompiledCandidate?.manifest?.validationContext
        writePersistedRunState(
            PersistedRunState.fromRunConfig(
                config = config,
                groupIds = groupIds,
                graderGroupIds = graderGroupIds,
                allowedHosts = initialAllowedHosts,
                nextRoundIndex = 0,
                activeRoundIndex = null,
                status = PersistedRunStatus.BOOTSTRAPPED,
                groupProfiles = groupProfiles
            ).copy(
                activeCouncilManifestSchemaVersion =
                    activeCompiledManifest?.schemaVersion,
                activeCouncilManifestHash =
                    activeCompiledManifest?.activationHash
            )
        )
        val activatedRun = activateRunInfrastructureOrFail(
            config = config,
            startingField = startingField,
            executionEnvelope = compiledCouncilEnvelope,
            groupIds = groupIds,
            groupProfiles = groupProfiles,
            graderGroupIds = graderGroupIds,
            allowedHosts = initialAllowedHosts,
            emitRunStartedEvent = true
        )

        if (continuationSourceStartingField != null) {
            emit(
                EvoMoliorEvent.RunGraphExpanded(
                    runId = config.runId,
                    sourceRunId = continuationSourceStartingField.runId,
                    ancestryDepth = startingField.runGraphEdges.size,
                    edgeCount = startingField.runGraphEdges.size
                )
            )
        }

        // Launch run loop
        activeRunLoopJob = scope.launch {
            runLoop(
                config = config,
                groupIds = groupIds,
                coordinator = activatedRun.coordinator,
                graderGroupIds = graderGroupIds,
                graderGroupManager = activatedRun.graderGroupManager,
                sheetRegisteredRounds = activatedRun.sheetRegisteredRounds,
                initialRoundIndex = 0
            )
        }

        config.runId
    }

    override suspend fun resumePersistedRun(config: RunConfig): String = mutex.withLock {
        check(activeRunId == null) { "Run $activeRunId is already active — abort it first" }
        terminalOwner.set(null)
        activeRunLoopJob = null
        if (config.acceptanceProfile != AcceptanceProfile.NORMAL) {
            require(hasConfiguredAcceptanceRunBoundaryVerifier()) {
                "Acceptance profile ${config.acceptanceProfile.id} requires a non-NONE AcceptanceRunBoundaryVerifier"
            }
            requireNotNull(archiveService) {
                "Acceptance profile ${config.acceptanceProfile.id} requires a durable run archive service"
            }
        }

        val persistedState = readPersistedRunState(config.runId)
        persistedState?.requireCompatibleRecoveryIntent(config)
        val recoveryArchive = archiveService ?: RunArchiveService(substrateFactory)
        val archiveDb = substrateFactory.get(config.runId, META_ARCHIVE_GROUP_ID)
        val completionEntries = runCatching {
            archiveDb.metaArchiveDao().getByType(RunArchiveService.ENTRY_RUN_COMPLETED)
        }.getOrElse { emptyList() }
        val completionRecord: RunCompletionRecord? = runCatching {
            completionEntries
                .firstOrNull { it.runId == config.runId }
                ?.contentJson
                ?.let { runConfigJson.decodeFromString(RunCompletionRecord.serializer(), it) }
        }.getOrNull()
        val publicationRepository = PublicationStateRepository(
            substrateFactory.getAtlas(config.runId)
        )
        val publicationState = publicationRepository.latest(config.runId)?.state
        val outcomeRepository = PublicationOutcomeRepository(
            substrateFactory.getAtlas(config.runId)
        )
        val provisionalRecovery =
            recoveryArchive.readProvisionalRecoveredRunRecord(config.runId)
        if (provisionalRecovery != null) {
            requireNotNull(persistedState) {
                "provisional recovered run ${config.runId} lacks persisted run state"
            }
            val persistedHistory = outcomeRepository.recoveryHistory(config.runId)
            if (
                persistedHistory == provisionalRecovery.recoveryPublicationHistory.dropLast(1)
            ) {
                outcomeRepository.appendRecovery(
                    provisionalRecovery.recoveryPublicationHistory.last()
                )
            }
            require(
                outcomeRepository.recoveryHistory(config.runId) ==
                    provisionalRecovery.recoveryPublicationHistory
            ) { "Provisional recovery archive history differs from durable publication history" }
            updatePersistedRunState(config.runId) { current ->
                current.copy(
                    status = PersistedRunStatus.RECOVERED,
                    nextRoundIndex = persistedState.nextRoundIndex,
                    activeRoundIndex = null
                )
            }
            terminalOwner.set(TerminalOwner.RECOVERY)
            currentStatus = EngineStatus.RECOVERED
            cleanup(config.runId)
            emit(
                EvoMoliorEvent.RunRecovered(
                    runId = config.runId,
                    sourceGroupId = null,
                    recoveredOutputPaths = listOf(
                        "results/recovery-results.zip",
                        "provisional-decision-package.json"
                    ),
                    reason = "Persisted provisional decision package remains non-accepted",
                    terminalOutcome = provisionalRecovery.terminalOutcome,
                )
            )
            return config.runId
        }
        val startupArchive = recoveryArchive.readStartupDiagnosticArchive(config.runId)
        if (startupArchive != null) {
            requireNotNull(persistedState) {
                "startup-diagnostic run ${config.runId} lacks persisted run state"
            }
            reconcileStartupOutcomeHistoryFromArchive(config.runId, startupArchive)
            val presentation = RunOutcomeBundleInspector.inspect(startupArchive)
            require(
                presentation.outcomeClass ==
                    com.achulkov.evomolior.core.deliverable.RunOutcomeClass
                        .STARTUP_DIAGNOSTIC_PACKAGE
            ) { "Persisted startup diagnostic has the wrong outcome class" }
            updatePersistedRunState(config.runId) { current ->
                current.copy(
                    status = PersistedRunStatus.FAILED,
                    activeRoundIndex = null,
                )
            }
            terminalOwner.set(TerminalOwner.ABORT)
            currentStatus = EngineStatus.IDLE
            cleanup(config.runId)
            emit(
                EvoMoliorEvent.RunAborted(
                    config.runId,
                    "Persisted startup diagnostic remains rejected"
                )
            )
            return config.runId
        }
        val acceptedOutcomeArchive = recoveryArchive.readAcceptedOutcomeArchive(config.runId)
        if (acceptedOutcomeArchive != null) {
            requireNotNull(persistedState) {
                "accepted run ${config.runId} lacks persisted run state"
            }
            reconcileAcceptedOutcomeHistoryFromArchive(
                config.runId,
                acceptedOutcomeArchive
            )
            updatePersistedRunState(config.runId) { current ->
                current.copy(
                    status = PersistedRunStatus.COMPLETED,
                    nextRoundIndex = completionRecord?.completedRoundCount
                        ?: persistedState.nextRoundIndex,
                    activeRoundIndex = null,
                )
            }
            terminalOwner.set(TerminalOwner.COMPLETION)
            currentStatus = EngineStatus.COMPLETED
            cleanup(config.runId)
            emit(
                EvoMoliorEvent.RunCompleted(
                    config.runId,
                    completionRecord?.winnerId
                )
            )
            return config.runId
        }
        val acceptedOutcomeHistory = outcomeRepository.acceptedHistory(config.runId)
        if (acceptedOutcomeHistory.isNotEmpty()) {
            throw IllegalStateException(
                "Outcome-aware accepted publication for ${config.runId} stopped at " +
                    "$publicationState without a verified accepted archive; it cannot be " +
                    "resumed as work or reclassified as provisional"
            )
        }
        if (outcomeRepository.recoveryHistory(config.runId).isNotEmpty()) {
            throw IllegalStateException(
                "Provisional publication for ${config.runId} is incomplete and cannot be " +
                    "resumed as work without its verified recovery archive"
            )
        }
        if (outcomeRepository.startupHistory(config.runId).isNotEmpty()) {
            throw IllegalStateException(
                "Startup-diagnostic publication for ${config.runId} is incomplete and cannot " +
                    "activate runtime work"
            )
        }
        startupRunStartedEmitted = false
        startupWorkerInfrastructureActivated = false
        startupGraderInfrastructureActivated = false
        initializeStartupRejectionArtifact(config)
        val recovered = recoveryArchive.readRecoveredRunRecord(config.runId)
        if (recovered != null) {
            requireNotNull(persistedState) {
                "recovered run ${config.runId} lacks persisted run state"
            }
            recoveryArchive.readRecoveryBundle(config.runId)
                ?: failStartup(
                    config.runId,
                    "persisted recovered run lacks recovery-results.zip"
                )
            updatePersistedRunState(config.runId) { current ->
                current.copy(
                    status = PersistedRunStatus.RECOVERED,
                    nextRoundIndex = recovered.completedRoundCount,
                    activeRoundIndex = null
                )
            }
            terminalOwner.set(TerminalOwner.RECOVERY)
            currentStatus = EngineStatus.RECOVERED
            cleanup(config.runId)
            emit(
                EvoMoliorEvent.RunRecovered(
                    runId = config.runId,
                    sourceGroupId = recovered.recoverySourceGroupId,
                    recoveredOutputPaths = buildList {
                        add("recovery/recovery-notice.md")
                        recovered.diagnosticSweepReport?.let {
                            add(AcceptanceDiagnosticSweepReport.BUNDLE_PATH)
                        }
                        recovered.recoveredDeliverables.forEach {
                            add("outputs/recovered/${it.relativePath}")
                        }
                    },
                    reason = recovered.failureSummary
                )
            )
            Napier.i(
                "EvoMoliorRunService: persisted run ${config.runId} is recovered/non-accepted"
            )
            return config.runId
        }
        if (persistedState?.status == PersistedRunStatus.RECOVERED) {
            failStartup(
                config.runId,
                "persisted recovered run lacks recovered-run authority"
            )
        }
        val compiledManifest = loadActiveCompiledCouncilManifestForResume(
            runId = config.runId,
            persistedState = persistedState,
            expectedCouncilExecutionPurpose = config.councilExecutionPurpose,
            expectedAcceptanceProfile = config.acceptanceProfile,
            expectedQuantitativeToolObligation = config.quantitativeToolObligation
        )
        val startingField = compiledManifest?.workerStartingField
            ?: loadSealedStartingFieldSnapshot(config.runId)
            ?: failStartup(
                config.runId,
                "persisted run ${config.runId} does not have an active StartingField snapshot"
            )
        if (compiledManifest == null && startingField.seal != null) {
            failStartup(
                config.runId,
                "sealed compiled Council fields without active sealed-tests-v3 context " +
                    "cannot be resumed as current authority"
            )
        }

        val completed = completionEntries.any { it.runId == config.runId }
        if (completed) {
            if (
                publicationState == PublicationState.METRICS_EXPORTED ||
                publicationState == PublicationState.MANIFEST_HASHED ||
                publicationState == PublicationState.ARCHIVED
            ) {
                if (archiveService == null) {
                    failStartup(
                        config.runId,
                        "persisted archive recovery requires a durable run archive service"
                    )
                }
                val exactCompletion = completionRecord
                    ?: failStartup(
                        config.runId,
                        "persisted completed run lacks a readable completion record"
                    )
                val recoveryGroupIds = persistedState?.groupIds
                    ?: failStartup(
                        config.runId,
                        "persisted archive recovery lacks exact worker group IDs"
                    )
                verifyAcceptanceRunBoundary(
                    config,
                    AcceptanceRunBoundary.BeforeArchivePublication
                )
                archiveCompletedRun(
                    config = config,
                    groupIds = recoveryGroupIds,
                    winnerId = exactCompletion.winnerId,
                    completionRecord = exactCompletion
                )
            }
            currentStatus = EngineStatus.COMPLETED
            cleanup(config.runId)
            emit(EvoMoliorEvent.RunCompleted(config.runId, completionRecord?.winnerId))
            Napier.i("EvoMoliorRunService: persisted run ${config.runId} is already completed; emitted completion for publication")
            return config.runId
        }

        activeGraderStartingField = compiledManifest?.graderStartingField
        val gradingField = compiledManifest?.graderStartingField ?: startingField
        activeSealedTestSuiteJson = gradingField.sealedTestSuiteJson
        activeSealedTestSuiteHash = gradingField.sealedTestSuiteHash
        activeSealedTestSuiteValidationContext = compiledManifest?.validationContext

        Napier.i("EvoMoliorRunService: resuming persisted run ${config.runId}")
        activeRunId = config.runId
        config.finalOutputSpec?.let { runFinalOutputSpecs[config.runId] = it } ?: runFinalOutputSpecs.remove(config.runId)
        currentStatus = EngineStatus.GENESIS
        currentRound = null

        val groupIds = persistedState?.groupIds
            ?.takeIf { it.isNotEmpty() }
            ?: resolveGroupIds(startingField, config.groupCount)
        val groupProfiles = resolveRecoveryGroupProfiles(config, startingField, groupIds)
        val graderGroupIds = persistedState?.graderGroupIds
            ?.takeIf { it.isNotEmpty() }
            ?: compiledManifest?.graderStartingField?.ringTopology
            ?: (0 until startingField.graderGroupCount).map { "grader-$it" }
        val resumePlan = buildResumeBootstrapPlan(config, groupIds, persistedState)
        if (resumePlan.replayRoundIndex != null) {
            sanitizeReplayRound(
                runId = config.runId,
                groupIds = groupIds,
                graderGroupIds = graderGroupIds,
                replayRoundIndex = resumePlan.replayRoundIndex
            )
        }

        val allowedHosts = resumePlan.persistedState?.allowedHosts?.toSet() ?: emptySet()
        preflightShuruIfRequired(config)
        ensureLegacyNodeProjectionSeedsForResume(config.runId, startingField, groupIds)
        ensureNodeAtlasAuthority(
            config = config,
            startingField = startingField,
            groupIds = groupIds,
            authoritativeAtlas = compiledManifest?.workerAtlas,
            authoritativeReceiverContract = compiledManifest?.receiverContract
        )
        compiledManifest?.let { manifest ->
            graderAuthorityRepository.appendAtlas(manifest.graderAtlas)
        }
        val activatedRun = activateRunInfrastructureOrFail(
            config = config,
            startingField = startingField,
            executionEnvelope = compiledManifest?.executionEnvelope,
            groupIds = groupIds,
            groupProfiles = groupProfiles,
            graderGroupIds = graderGroupIds,
            allowedHosts = allowedHosts,
            emitRunStartedEvent = false
        )

        writePersistedRunState(
            PersistedRunState.fromRunConfig(
                config = config,
                groupIds = groupIds,
                graderGroupIds = graderGroupIds,
                allowedHosts = allowedHosts,
                nextRoundIndex = resumePlan.startRoundIndex,
                activeRoundIndex = resumePlan.replayRoundIndex,
                status = PersistedRunStatus.RUNNING,
                bootstrappedAtMs = resumePlan.persistedState?.bootstrappedAtMs ?: System.currentTimeMillis(),
                groupProfiles = groupProfiles
            ).copy(
                autoCompletionCandidateWinnerId =
                    resumePlan.persistedState?.autoCompletionCandidateWinnerId,
                autoCompletionAcceptedRoundStreak =
                    resumePlan.persistedState?.autoCompletionAcceptedRoundStreak ?: 0,
                activeCouncilManifestSchemaVersion =
                    resumePlan.persistedState?.activeCouncilManifestSchemaVersion,
                activeCouncilManifestHash =
                    resumePlan.persistedState?.activeCouncilManifestHash
            )
        )

        emit(
            EvoMoliorEvent.EngineProgress(
                config.runId,
                "recovery_start",
                "Recovering persisted run ${config.runId} from round ${resumePlan.startRoundIndex}"
            )
        )

        activeRunLoopJob = scope.launch {
            runLoop(
                config = config,
                groupIds = groupIds,
                coordinator = activatedRun.coordinator,
                graderGroupIds = graderGroupIds,
                graderGroupManager = activatedRun.graderGroupManager,
                sheetRegisteredRounds = activatedRun.sheetRegisteredRounds,
                initialRoundIndex = resumePlan.startRoundIndex
            )
        }

        config.runId
    }

    private suspend fun activateRunInfrastructureOrFail(
        config: RunConfig,
        startingField: StartingField,
        executionEnvelope: ExecutionEnvelope?,
        groupIds: List<String>,
        groupProfiles: List<GroupProfileType>,
        graderGroupIds: List<String>,
        allowedHosts: Set<String>,
        emitRunStartedEvent: Boolean
    ): ActivatedRunInfrastructure = try {
        activateRunInfrastructure(
            config = config,
            startingField = startingField,
            executionEnvelope = executionEnvelope,
            groupIds = groupIds,
            groupProfiles = groupProfiles,
            graderGroupIds = graderGroupIds,
            allowedHosts = allowedHosts,
            emitRunStartedEvent = emitRunStartedEvent
        )
    } catch (cancellation: CancellationException) {
        startupRejectionArtifact = null
        runCatching { cleanup(config.runId) }
            .exceptionOrNull()
            ?.let(cancellation::addSuppressed)
        throw cancellation
    } catch (error: Exception) {
        val reason = "runtime infrastructure activation failed: " +
            (error.message ?: error::class.simpleName.orEmpty())
        if (
            !startupWorkerInfrastructureActivated &&
            !startupGraderInfrastructureActivated &&
            !startupRunStartedEmitted
        ) {
            failStartup(config.runId, reason, error)
        }
        // Runtime ownership has begun, so this is an abort—not a pre-activation rejection.
        startupRejectionArtifact = null
        val statePersistenceError = runCatching {
            updatePersistedRunStatus(config.runId, PersistedRunStatus.FAILED)
        }.exceptionOrNull()
        val cleanupError = runCatching { cleanup(config.runId) }.exceptionOrNull()
        val abortEventError = runCatching {
            emit(
                EvoMoliorEvent.RunAborted(
                    config.runId,
                    "runtime infrastructure activation failed"
                )
            )
        }.exceptionOrNull()
        throw IllegalStateException(
            reason,
            error
        ).also { failure ->
            statePersistenceError?.let(failure::addSuppressed)
            cleanupError?.let(failure::addSuppressed)
            abortEventError?.let(failure::addSuppressed)
        }
    }

    override suspend fun pauseRun(runId: String) {
        requireActiveRun(runId)
        currentStatus = EngineStatus.PAUSED
        updatePersistedRunStatus(runId, PersistedRunStatus.PAUSED)
        Napier.i("EvoMoliorRunService: paused run $runId")
    }

    override suspend fun resumeRun(runId: String) {
        requireActiveRun(runId)
        if (currentStatus == EngineStatus.PAUSED) {
            currentStatus = EngineStatus.RUNNING
            updatePersistedRunStatus(runId, PersistedRunStatus.RUNNING)
        }
        Napier.i("EvoMoliorRunService: resumed run $runId")
    }

    override suspend fun abortRun(runId: String, reason: String): Unit = mutex.withLock {
        requireActiveRun(runId)
        failedRunDrain?.let { pending ->
            activeRunLoopJob?.join()
            finishRunLoop(
                runId,
                CancellationException("Retrying incomplete cleanup for $runId"),
                null,
                pending.criteriaJob,
            )
            updatePersistedRunStatus(runId, PersistedRunStatus.ABORTED)
            emit(EvoMoliorEvent.RunAborted(runId, reason))
            return
        }
        if (!terminalOwner.compareAndSet(null, TerminalOwner.ABORT)) {
            Napier.i("EvoMoliorRunService: terminal ownership already claimed for run $runId")
            return
        }
        Napier.w("EvoMoliorRunService: aborting run $runId reason=$reason")
        val runLoopJob = activeRunLoopJob
        runLoopJob?.cancel(CancellationException("Run $runId aborted: $reason"))
        runLoopJob?.join()
        failedRunDrain?.let { throw it.failure }
        updatePersistedRunStatus(runId, PersistedRunStatus.ABORTED)
        cleanup(runId)
        emit(EvoMoliorEvent.RunAborted(runId, reason))
    }

    override suspend fun getMetaArchiveSummary(runId: String): MetaArchiveSummary? {
        return try {
            // Meta-archive records are written to the dedicated meta-archive substrate DB.
            val db = substrateFactory.get(runId, META_ARCHIVE_GROUP_ID)
            val total = db.metaArchiveDao().count()
            val allEntries = db.metaArchiveDao().getByRun(runId)
            val byType = allEntries.groupingBy { it.entryType }.eachCount()
            MetaArchiveSummary(total = total, byType = byType)
        } catch (e: Exception) {
            Napier.w("EvoMoliorRunService: failed to load meta-archive summary for $runId: ${e.message}")
            null
        }
    }

    override suspend fun getMetaArchiveEntries(runId: String, entryType: String?): List<MetaArchiveEntryRecord> {
        return try {
            val db = substrateFactory.get(runId, META_ARCHIVE_GROUP_ID)
            val entries = db.metaArchiveDao().getByRun(runId)
            entries.asSequence()
                .filter { entryType == null || it.entryType == entryType }
                .map { entry ->
                    MetaArchiveEntryRecord(
                        id = entry.id,
                        runId = entry.runId,
                        entryType = entry.entryType,
                        groupId = entry.groupId,
                        label = entry.label,
                        contentJson = entry.contentJson,
                        runObjective = entry.runObjective,
                        finalScore = entry.finalScore,
                        isFromWinner = entry.isFromWinner,
                        generation = entry.generation,
                        chunkIndex = entry.chunkIndex,
                        totalChunks = entry.totalChunks,
                        archivedAtMs = entry.archivedAtMs
                    )
                }
                .toList()
        } catch (e: Exception) {
            Napier.w("EvoMoliorRunService: failed to load meta-archive entries for $runId: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getResultsBundle(runId: String): ByteArray? {
        return try {
            val archive = archiveService ?: RunArchiveService(substrateFactory)
            archive.readRecoveryBundle(runId)?.let { return it }
            archive.readStartupDiagnosticArchive(runId)?.let { return it }
            archive.readAcceptedOutcomeArchive(runId)?.let { return it }
            val outcomeRepository = PublicationOutcomeRepository(
                substrateFactory.getAtlas(runId)
            )
            if (
                outcomeRepository.acceptedHistory(runId).isNotEmpty() ||
                outcomeRepository.recoveryHistory(runId).isNotEmpty() ||
                outcomeRepository.startupHistory(runId).isNotEmpty()
            ) {
                return null
            }
            val requestedOutputSpec = runFinalOutputSpecs[runId] ?: readPersistedRunState(runId)?.finalOutputSpec
            archive.buildResultsBundle(runId, requestedOutputSpec = requestedOutputSpec)
        } catch (e: Exception) {
            Napier.w("EvoMoliorRunService: failed to build results bundle for $runId: ${e.message}")
            null
        }
    }

    override suspend fun getOverseerKernelState(): OverseerKernelState = overseerKernelService.currentState()

    override suspend fun preflightOverseerHypothesis(hypothesis: OverseerHypothesis): OverseerKernelEligibility =
        overseerKernelService.preflightHypothesis(hypothesis)

    override suspend fun preflightOverseerExperimentPlan(experimentPlan: OverseerExperimentPlan): OverseerExperimentPlanEligibility =
        overseerKernelService.preflightExperimentPlan(experimentPlan)

    override suspend fun formalizeOverseerHypothesis(hypothesis: OverseerHypothesis): OverseerKernelState {
        val result = overseerKernelService.formalizeHypothesisWithAudit(hypothesis)
        mirrorOverseerAuditEntry(result.auditEntry)
        return result.state
    }

    override suspend fun registerOverseerExperimentPlan(experimentPlan: OverseerExperimentPlan): OverseerKernelState {
        val result = overseerKernelService.registerExperimentPlanWithAudit(experimentPlan)
        mirrorOverseerAuditEntry(result.auditEntry)
        return result.state
    }

    override suspend fun scheduleOverseerExperimentPlans(): OverseerKernelState {
        val result = overseerKernelService.scheduleEligibleExperimentPlansWithAudit()
        mirrorOverseerAuditEntry(result.auditEntry)
        return result.state
    }

    override suspend fun launchOverseerExperimentRun(
        experimentPlanId: String,
        launchRequest: OverseerExperimentLaunchRequest
    ): OverseerExperimentLaunchResponse {
        val currentState = overseerKernelService.currentState()
        val schedule = currentState.experimentSchedules[experimentPlanId]
            ?: error("No scheduled experiment plan found for '$experimentPlanId'.")
        require(launchRequest.scheduledRunId in schedule.scheduledRunIds) {
            "Scheduled run '${launchRequest.scheduledRunId}' is not registered for experiment plan '$experimentPlanId'."
        }
        require(launchRequest.runConfig.runId == launchRequest.scheduledRunId) {
            "Launch runId '${launchRequest.runConfig.runId}' must match scheduled run '${launchRequest.scheduledRunId}'."
        }

        val launchedRunId = startRun(launchRequest.runConfig)
        val launchRecord = overseerKernelService.launchExperimentRunWithAudit(experimentPlanId, launchRequest.scheduledRunId)
        mirrorOverseerAuditEntry(launchRecord.auditEntry)
        return OverseerExperimentLaunchResponse(
            experimentPlanId = experimentPlanId,
            scheduledRunId = launchRequest.scheduledRunId,
            runId = launchedRunId
        )
    }

    override suspend fun recordOverseerExperimentResult(experimentResult: OverseerExperimentResult): OverseerKernelState {
        val result = overseerKernelService.recordExperimentResultWithAudit(experimentResult)
        mirrorOverseerAuditEntry(result.auditEntry)
        return result.state
    }

    override suspend fun recordOverseerDeadEnd(deadEnd: OverseerDeadEnd): OverseerKernelState {
        val result = overseerKernelService.recordDeadEndWithAudit(deadEnd)
        mirrorOverseerAuditEntry(result.auditEntry)
        return result.state
    }

    override suspend fun getOverseerAuditEntries(): List<OverseerKernelAuditEntry> =
        overseerKernelService.getAuditEntries()

    override suspend fun getOverseerExperimentPlanHistory(experimentPlanId: String): List<OverseerExperimentPlanHistoryEntry> =
        overseerKernelService.getExperimentPlanHistory(experimentPlanId)

    override suspend fun getOverseerDeadEndHistory(hypothesisId: String): List<OverseerDeadEndHistoryEntry> =
        overseerKernelService.getDeadEndHistory(hypothesisId)

    override suspend fun getOverseerAuditArchiveEntries(): List<MetaArchiveEntryRecord> = withContext(Dispatchers.IO) {
        val archiveDb = substrateFactory.get(OverseerKernelArchive.RUN_ID, META_ARCHIVE_GROUP_ID)
        archiveDb.metaArchiveDao().getByType(OverseerKernelArchive.ENTRY_TYPE).map { entry ->
            MetaArchiveEntryRecord(
                id = entry.id,
                runId = entry.runId,
                entryType = entry.entryType,
                groupId = entry.groupId,
                label = entry.label,
                contentJson = entry.contentJson,
                runObjective = entry.runObjective,
                finalScore = entry.finalScore,
                isFromWinner = entry.isFromWinner,
                generation = entry.generation,
                chunkIndex = entry.chunkIndex,
                totalChunks = entry.totalChunks,
                archivedAtMs = entry.archivedAtMs
            )
        }
    }

    override suspend fun getState(): EngineState = EngineState(
        status = currentStatus,
        activeRunId = activeRunId,
        currentRound = currentRound,
        groupSummaries = groupSummaries
    )

    private suspend fun mirrorOverseerAuditEntry(
        auditEntry: OverseerKernelAuditEntry
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val archiveDb = substrateFactory.get(OverseerKernelArchive.RUN_ID, META_ARCHIVE_GROUP_ID)
            archiveDb.metaArchiveDao().insert(
                MetaArchiveEntryEntity(
                    id = auditEntry.entryId,
                    runId = OverseerKernelArchive.RUN_ID,
                    entryType = OverseerKernelArchive.ENTRY_TYPE,
                    groupId = null,
                    label = "Overseer audit — ${auditEntry.actionType.name}",
                    contentJson = overseerAuditArchiveJson.encodeToString(
                        OverseerKernelAuditEntry.serializer(),
                        auditEntry
                    ),
                    runObjective = "Recursive self-improvement control-plane evidence",
                    finalScore = null,
                    isFromWinner = false,
                    generation = 0,
                    archivedAtMs = auditEntry.timestampMs
                )
            )
        }.onFailure { error ->
            Napier.w("EvoMoliorRunService: failed to mirror Overseer audit entry to meta-archive", error)
        }
    }

    // ── Internal run loop ────────────────────────────────────────────────────

    /**
     * Owns one run's terminal disposition: rounds via [executeRound], then [completeRunAfterRounds],
     * with failure recovery and [finishRunLoop] cleanup.
     *
     * Phase helpers share a [RunLoopState] so recovery always sees the latest publication context.
     * Keep new round or publication logic in those helpers: this coroutine previously reached the
     * JVM 64 KB method limit (63,697 bytes) and now compiles to under 3 KB.
     */
    private suspend fun runLoop(
        config: RunConfig,
        groupIds: List<String>,
        coordinator: GroupCoordinator,
        graderGroupIds: List<String>,
        graderGroupManager: GraderGroupManager,
        sheetRegisteredRounds: MutableSet<Int>,
        initialRoundIndex: Int
    ) {
        currentStatus = EngineStatus.RUNNING
        updatePersistedRunStatus(config.runId, PersistedRunStatus.RUNNING)
        val effectiveRounds = minOf(config.rounds, activeGenerationCeiling)
        if (effectiveRounds < config.rounds) {
            Napier.w("EvoMoliorRunService: config.rounds=${config.rounds} exceeds generationCeiling=$activeGenerationCeiling — capping at $effectiveRounds")
            scope.launch {
                emit(EvoMoliorEvent.EngineProgress(config.runId, "gen_ceiling_cap",
                    "Run capped at $effectiveRounds rounds by Genesis Council generationCeiling"))
            }
        }
        Napier.i("EvoMoliorRunService: run loop started effectiveRounds=$effectiveRounds")

        val state = RunLoopState()
        var cancellationFailure: CancellationException? = null
        try {
            val recoveredAutoCompletion = readPersistedRunState(config.runId)
            state.autoCompletionCandidateWinnerId =
                recoveredAutoCompletion?.autoCompletionCandidateWinnerId
            state.autoCompletionAcceptedRoundStreak =
                recoveredAutoCompletion?.autoCompletionAcceptedRoundStreak ?: 0
            require(initialRoundIndex in 0..effectiveRounds) {
                "Resume round index $initialRoundIndex is outside 0..$effectiveRounds"
            }
            if (initialRoundIndex == effectiveRounds) {
                val restoredRound = loadCompletedRoundAuthority(
                    runId = config.runId,
                    expectedCompletedRoundCount = effectiveRounds,
                    expectedGroupIds = groupIds
                )
                state.finalRoundIndex = restoredRound.roundIndex
                state.finalRoundJudgeOutputs = restoredRound.judgeOutputsByGrader
                state.finalRoundSubmissionSummaries = restoredRound.groupSummaries
                    .associate { it.groupId to it.submissionSummary }
                state.finalRoundResult = restoredRound.toGradingResult()
                state.latePublicationRecoveryContext = completedRoundRecoveryContext(
                    config = config,
                    groupIds = groupIds,
                    roundIndex = state.finalRoundIndex,
                    roundResult = state.finalRoundResult,
                    roundSubmissionSummaries = state.finalRoundSubmissionSummaries,
                )
            }
            for (round in initialRoundIndex until effectiveRounds) {
                val outcome = executeRound(
                    config = config,
                    groupIds = groupIds,
                    coordinator = coordinator,
                    graderGroupIds = graderGroupIds,
                    graderGroupManager = graderGroupManager,
                    sheetRegisteredRounds = sheetRegisteredRounds,
                    round = round,
                    state = state,
                )
                when (outcome) {
                    RoundStepOutcome.CONTINUE -> Unit
                    RoundStepOutcome.ABORTED -> return
                    RoundStepOutcome.AUTO_COMPLETED -> break
                }
            }
            completeRunAfterRounds(config, groupIds, effectiveRounds, state)
        } catch (e: Exception) {
            if (e is CancellationException) {
                cancellationFailure = e
                throw e
            }
            try {
                recoverRunLoopFailure(config, state.latePublicationRecoveryContext, e) {
                    state.terminalEvent = it
                }
            } catch (failure: CancellationException) {
                cancellationFailure = failure
                throw failure
            }
        } finally {
            finishRunLoop(
                config.runId,
                cancellationFailure,
                state.terminalEvent,
                state.pendingCriteriaJob
            )
        }
    }

    /** Outcome of executing or restoring one round in [runLoop]. */
    private enum class RoundStepOutcome { CONTINUE, ABORTED, AUTO_COMPLETED }

    /** How a round's worker wait ended. */
    private enum class RoundWaitOutcome { COMPLETED, STALLED, ABORTED }

    /**
     * Mutable authority shared by [runLoop] and its phase helpers for one run invocation.
     *
     * Helpers bind local variables to these fields (`var x by state::x`), so every update, including
     * a partially prepared publication context, is visible to failure recovery immediately.
     */
    private class RunLoopState {
        var latePublicationRecoveryContext: LatePublicationRecoveryContext? = null
        var pendingCriteriaJob: Job? = null
        var terminalEvent: EvoMoliorEvent? = null
        var finalRoundJudgeOutputs: Map<String, String> = emptyMap()
        var finalRoundSubmissionSummaries: Map<String, String?> = emptyMap()
        var finalRoundResult: com.achulkov.evomolior.core.model.GradingResult? = null
        var finalRoundIndex: Int = -1
        var autoCompletionCandidateWinnerId: String? = null
        var autoCompletionAcceptedRoundStreak: Int = 0
        var autoCompletionReached: Boolean = false
    }

    /**
     * Executes one round: worker spawn, criteria development, workers, Critic review, grading,
     * blind scoring, mutation boundaries, archive, and auto-completion bookkeeping.
     *
     * @param config Active run configuration.
     * @param groupIds Competing worker groups.
     * @param coordinator Inter-group coordinator.
     * @param graderGroupIds Grader civilisations.
     * @param graderGroupManager Grader verdict manager.
     * @param sheetRegisteredRounds Rounds whose grading sheets are registered.
     * @param round Round to execute.
     * @param state Run-loop state updated with this round's authority.
     */
    private suspend fun executeRound(
        config: RunConfig,
        groupIds: List<String>,
        coordinator: GroupCoordinator,
        graderGroupIds: List<String>,
        graderGroupManager: GraderGroupManager,
        sheetRegisteredRounds: MutableSet<Int>,
        round: Int,
        state: RunLoopState,
    ): RoundStepOutcome {
        if (restoreArchivedRound(config, groupIds, round, state)) return RoundStepOutcome.CONTINUE
        var finalRoundJudgeOutputs by state::finalRoundJudgeOutputs
        var finalRoundSubmissionSummaries by state::finalRoundSubmissionSummaries
        var finalRoundResult by state::finalRoundResult
        var finalRoundIndex by state::finalRoundIndex
        var latePublicationRecoveryContext by state::latePublicationRecoveryContext
        var pendingCriteriaJob by state::pendingCriteriaJob
        var autoCompletionCandidateWinnerId by state::autoCompletionCandidateWinnerId
        var autoCompletionAcceptedRoundStreak by state::autoCompletionAcceptedRoundStreak
        var autoCompletionReached by state::autoCompletionReached
        // Honour pause/abort before starting round
        while (currentStatus == EngineStatus.PAUSED) { delay(500) }
        if (currentStatus == EngineStatus.IDLE) {
            Napier.i("EvoMoliorRunService: run aborted before round $round")
            return RoundStepOutcome.ABORTED
        }

        currentRound = round
        updatePersistedRunState(config.runId) { current ->
            current.copy(
                status = PersistedRunStatus.RUNNING,
                nextRoundIndex = round,
                activeRoundIndex = round
            )
        }
        val roundAtlasAuthority = snapshotRoundAtlasAuthority(config.runId)
        val roundWorkerAtlas = sealRoundAtlasPhase(
            config.runId,
            round,
            roundAtlasAuthority
        )
        coordinator.onRoundStart(round)
        coordinator.flushPendingDispatches(round)
        val dueLeakTargets = automaticLeakTargets(
            isTestRun = config.isTestRun,
            roundIndex = round,
            intervalGenerations = activeLeakIntervalGenerations,
            groupIds = groupIds,
            leakMultipliers = groupIds.associateWith { groupId ->
                activeDisclosureEngine?.leakMultiplierFor(groupId) ?: 1.0
            }
        )
        if (dueLeakTargets.isNotEmpty()) {
            activeWatchdog?.requestLeakDelivery(
                targetGroupIds = dueLeakTargets,
                roundIndex = round
            )
            yield()
        }
        emit(EvoMoliorEvent.RoundStarted(config.runId, round))
        val budgetLedRound = isBudgetLedProductionRound(
            config = config,
            roundDurationMillisOverride = roundDurationMillisOverride
        )
        val roundDurationMs = if (budgetLedRound) {
            null
        } else {
            roundDurationMillisOverride
                ?: config.roundDurationMinutes * 60_000L
        }
        Napier.i(
            "EvoMoliorRunService: round $round started " +
                if (budgetLedRound) "(mode=budget-led)" else "(duration=${roundDurationMs}ms)"
        )
        emit(EvoMoliorEvent.EngineProgress(config.runId, "round_start", "Round $round started — spawning agents for ${groupIds.size} group(s)"))

        // ── Spawn agents for this round ──────────────────────────────
        prepareAndSpawnRoundWorkers(
            config,
            groupIds,
            round,
            roundWorkerAtlas,
            roundAtlasAuthority
        )

        // ── Launch grader criteria phase concurrently (Phase A) ───────
        // Criteria agents run IN PARALLEL with the main group agents.
        // They study the run objective + dimensions and develop the scoring rubric.
        // The job is collected so we can wait for it before the judging phase.
        val criteriaJob = launchGraderCriteriaJob(config, graderGroupIds, round)
        pendingCriteriaJob = criteriaJob
        recordCriteriaSpawnedPhase(config.runId, round, graderGroupIds, criteriaJob != null)

        // ── Wait for round completion with heartbeat events ──────────
        // Fixed-duration runs retain their existing wall-clock semantics. Production
        // runs with duration <= 0 instead observe live balances and let healthy workers
        // spend their assigned budget until they finish.
        val roundWaitOutcome = awaitRoundWorkers(config, groupIds, round, budgetLedRound, roundDurationMs)
        if (roundWaitOutcome == RoundWaitOutcome.ABORTED) return RoundStepOutcome.ABORTED
        val roundEndedDueToStall = roundWaitOutcome == RoundWaitOutcome.STALLED

        // ── End of round: terminate remaining agents when required ────
        val remainingAgents = groupIds.sumOf { gid ->
            liveLifecycleManagers[gid]?.getLiveAgentCount() ?: 0
        }
        if (budgetLedRound && !roundEndedDueToStall) {
            Napier.i("EvoMoliorRunService: round $round completed by budget-led worker quiescence")
            emit(EvoMoliorEvent.EngineProgress(config.runId, "round_end",
                "Round $round budget-led work complete — running post-round evaluation"))
        } else {
            Napier.i("EvoMoliorRunService: round $round duration elapsed — terminating remaining agents")
            emit(EvoMoliorEvent.EngineProgress(config.runId, "round_end",
                "Round $round time up — terminating agents and running post-round evaluation"))
        }
        if (!budgetLedRound || roundEndedDueToStall || remainingAgents > 0) {
            groupIds.forEach { gid ->
                try {
                    liveLifecycleManagers[gid]?.terminateAll()
                } catch (e: Exception) {
                    Napier.w("EvoMoliorRunService: terminateAll failed for group $gid: ${e.message}")
                }
            }
        }
        settleRoundExecutionPhases(config.runId, groupIds, round)

        // ── Round submission summaries ───────────────────────────────
        val pendingRoundSubmissionSummaries = spawnRoundSubmissionSummaries(
            config = config,
            groupIds = groupIds,
            round = round
        )

        // ── Post-round processing ────────────────────────────────────

        currentStatus = EngineStatus.EVALUATING

        // 1. Run CriticPipeline for each group — review and promote staging submissions
        val criticSummaries = runCriticPipelinesForRound(config, groupIds, round)
        recordCriticRoundPhase(config.runId, groupIds, round, criticSummaries)
        val roundSubmissionSummaries = promotedRoundSubmissionSummaries(
            runId = config.runId,
            round = round,
            candidates = pendingRoundSubmissionSummaries
        )
        if (roundSubmissionSummaries.isNotEmpty()) {
            val summaryLine = roundSubmissionSummaries.entries
                .joinToString(" | ") { (groupId, summary) ->
                    "$groupId=${summary.orEmpty().replace(Regex("\\s+"), " ")
                        .trim().take(180)}"
                }
            emit(
                EvoMoliorEvent.EngineProgress(
                    config.runId,
                    "round_summary_result",
                    "Round $round promoted submission summaries: $summaryLine"
                )
            )
        }

        // 2. Apply per-round trust decay and generation-boundary KB organisation.
        applyTrustDecayForRound(config.runId, groupIds)
        organizeKnowledgeBasesForGeneration(config.runId, groupIds, round)

        // 3a. Wait for criteria phase to complete (if running), then run judging phase
        criteriaJob?.join()
        pendingCriteriaJob = null
        if (graderCriteriaRunner != null && graderGroupIds.isNotEmpty()) {
            reviewGraderCriteriaForRound(
                runId = config.runId,
                openRouterApiKey = config.openRouterApiKey,
                graderGroupIds = graderGroupIds,
                roundIndex = round,
                llmBackend = config.llmBackend
            ) { progressMsg ->
                scope.launch {
                    emit(
                        EvoMoliorEvent.EngineProgress(
                            config.runId,
                            "grader_criteria_review",
                            progressMsg
                        )
                    )
                }
            }
            if (graderAuthorityRepository.currentAtlas(config.runId) != null) {
                repairRejectedGraderMethodologies(
                    config = config,
                    graderGroupIds = graderGroupIds,
                    round = round
                )
            }
            freezeCompetitiveGraderCriteria(
                runId = config.runId,
                graderGroupIds = graderGroupIds,
                competingGroupIds = groupIds,
                round = round,
                roundAuthority = roundAtlasAuthority
            )
            rolloverGraderCivilizations(
                runId = config.runId,
                graderGroupIds = graderGroupIds,
                generation = round + 1
            )
        }
        recordEvidenceAndCriteriaRoundPhases(
            config.runId,
            groupIds,
            graderGroupIds,
            round,
            roundWorkerAtlas
        )

        val judgeOutputs = if (graderJudgingRunner != null && graderGroupIds.isNotEmpty()) {
            // New two-phase architecture: RoundJudge reads criteria + scores all groups
            runGraderJudging(
                config,
                graderGroupIds,
                groupIds,
                round,
                graderGroupManager,
                roundAtlasAuthority
            )
        } else {
            emptyMap()
        }
        recordEvidenceRevealRoundPhase(
            config.runId,
            graderGroupIds,
            round,
            judgeOutputs
        )

        if (judgeOutputs.isNotEmpty()) {
            val judgeSummary = judgeOutputs.entries.joinToString(" | ") { (graderGroupId, output) ->
                "[$graderGroupId] ${output.replace(Regex("\\s+"), " ").trim().take(220)}"
            }
            emit(
                EvoMoliorEvent.EngineProgress(
                    config.runId,
                    "grader_judge_result",
                    "Round $round RoundJudge output: $judgeSummary"
                )
            )
        }

        if (graderJudgingRunner == null || graderGroupIds.isEmpty()) {
            // Legacy: old post-round grader pipeline (backward compat)
            runGraderAgents(config, graderGroupIds, groupIds, round, graderGroupManager)
        }

        val graderAuthorityConfigured = graderJudgingRunner != null || graderRunner != null
        val graderRoundResult = if (graderGroupIds.isNotEmpty()) {
            registerRoundWithGraderManager(
                manager = graderGroupManager,
                runConfig = config,
                groupIds = groupIds,
                roundIndex = round,
                sheetRegisteredRounds = sheetRegisteredRounds
            )
            resolveAuthoritativeGraderRoundResult(
                manager = graderGroupManager,
                groupIds = groupIds,
                roundIndex = round,
                graderAuthorityConfigured = graderAuthorityConfigured
            )?.copy(
                nodeGradeBundles = nodeGradeBundlesByRound[round].orEmpty()
            )?.also { result ->
                updateGraderFeedbackBriefs(config.runId, round, result)
                emit(
                    EvoMoliorEvent.EngineProgress(
                        config.runId,
                        "grader_authoritative_verdict",
                        "Authoritative grader verdict completed for round $round — winner ${result.winnerId}"
                    )
                )
                Napier.i(
                    "EvoMoliorRunService: authoritative grader verdict round=$round " +
                        "winner=${result.winnerId} resolution=${result.resolution} scores=${result.scores.size}"
                )
            }
        } else null
        recordGraderOutcomeRoundPhase(config.runId, round, graderRoundResult)

        // 3b. Compute substrate telemetry for deterministic checks and scoring fallback.
        computeSubstrateScores(
            config = config,
            groupIds = groupIds,
            round = round,
            roundSubmissionSummaries = roundSubmissionSummaries
        )
        selectAuthoritativeRoundScores(round, graderRoundResult)

        // 4. Seal and reveal the round scores through the blind scoring gate.
        val blindResult = publishBlindRoundResult(config, groupIds, round)
        updateAdversarialDiscriminatorBriefs(
            runId = config.runId,
            roundIndex = round,
            blindResult = blindResult,
            roundSubmissionSummaries = roundSubmissionSummaries
        )
        if (groupIds.isNotEmpty()) {
            val adversarialSummary = groupIds.joinToString(" | ") { groupId ->
                adversarialDiscriminatorBriefs[groupId]
                    ?.lineSequence()
                    ?.firstOrNull()
                    ?.take(180)
                    ?.let { "$groupId=$it" }
                    ?: "$groupId=none"
            }
            emit(
                EvoMoliorEvent.EngineProgress(
                    config.runId,
                    "discriminator_feedback",
                    "Round $round discriminator feedback prepared: $adversarialSummary"
                )
            )
        }

        // Phase 7.5: Apply accepted DST structural refinements and rubric policies.
        applyDstRefinementsFromRound(config.runId, groupIds, blindResult, round)

        // 4b. Apply exact disclosure, fitness, capture, yield, and reward mutations.
        val epigeneticSummaries = processAuthoritativeRoundMutationBoundary(
            config = config,
            groupIds = groupIds,
            gradingResult = graderRoundResult ?: blindResult,
            round = round,
            workerAtlas = roundAtlasAuthority?.workerAtlas
        )

        val completedWipTasksByGroup = groupIds.associateWith { drainWipCompletions(it) }
            .filterValues { it.isNotEmpty() }

        // 5c. Feed explicit WiP completion into progress/stasis, not territorial ownership.
        // A round without completions increments stasis; completions reset the counter.
        processMetaPlannerSignals(config, groupIds, round, completedWipTasksByGroup)

        sealRoundMutationSnapshotsAndArchive(
            config = config,
            groupIds = groupIds,
            graderGroupIds = graderGroupIds,
            round = round,
            criticSummaries = criticSummaries,
            epigeneticSummaries = epigeneticSummaries,
            blindResult = blindResult,
            judgeOutputs = judgeOutputs,
            roundSubmissionSummaries = roundSubmissionSummaries
        )
        finalRoundJudgeOutputs = judgeOutputs
        finalRoundSubmissionSummaries = roundSubmissionSummaries
        finalRoundResult = blindResult
        finalRoundIndex = round
        latePublicationRecoveryContext = completedRoundRecoveryContext(
            config = config,
            groupIds = groupIds,
            roundIndex = round,
            roundResult = blindResult,
            roundSubmissionSummaries = roundSubmissionSummaries,
        )
        currentStatus = EngineStatus.RUNNING
        val autoCompletionEvidence = config.autoCompletionPolicy?.let {
            evaluateAutoCompletionRound(
                config = config,
                round = round,
                blindResult = blindResult,
                graderRoundResult = graderRoundResult,
                roundSubmissionSummaries = roundSubmissionSummaries
            )
        }
        val autoCompletionProgress = if (
            config.autoCompletionPolicy != null &&
            autoCompletionEvidence != null
        ) {
            RunAutoCompletionAuthority.advance(
                policy = config.autoCompletionPolicy,
                previousWinnerId = autoCompletionCandidateWinnerId,
                previousStreak = autoCompletionAcceptedRoundStreak,
                evidence = autoCompletionEvidence
            ).also { progress ->
                autoCompletionCandidateWinnerId = progress.candidateWinnerId
                autoCompletionAcceptedRoundStreak = progress.acceptedRoundStreak
                emit(
                    EvoMoliorEvent.EngineProgress(
                        config.runId,
                        "auto_completion",
                        if (autoCompletionEvidence.accepted) {
                            "Round $round accepted for auto-completion: winner=" +
                                "${progress.candidateWinnerId}, stability=" +
                                "${progress.acceptedRoundStreak}/" +
                                config.autoCompletionPolicy.consecutiveAcceptedRounds
                        } else {
                            "Round $round did not advance auto-completion: " +
                                autoCompletionEvidence.rejectionReasons.sorted().joinToString()
                        }
                    )
                )
            }
        } else {
            null
        }
        updatePersistedRunState(config.runId) { current ->
            current.copy(
                status = PersistedRunStatus.RUNNING,
                nextRoundIndex = round + 1,
                activeRoundIndex = null,
                autoCompletionCandidateWinnerId = autoCompletionCandidateWinnerId,
                autoCompletionAcceptedRoundStreak = autoCompletionAcceptedRoundStreak
            )
        }
        if (autoCompletionProgress?.complete == true) {
            autoCompletionReached = true
            Napier.i(
                "EvoMoliorRunService: auto-completion stability reached at round=$round " +
                    "winner=$autoCompletionCandidateWinnerId"
            )
            return RoundStepOutcome.AUTO_COMPLETED
        }
        return RoundStepOutcome.CONTINUE
    }

    /**
     * Restores an already archived round on restart instead of replaying its mutable phases.
     *
     * @param config Active run configuration.
     * @param groupIds Competing worker groups.
     * @param round Round about to execute.
     * @param state Run-loop state receiving the restored final-round authority.
     * @return True when [round] was archived and restored, so it must not execute again.
     */
    private suspend fun restoreArchivedRound(
        config: RunConfig,
        groupIds: List<String>,
        round: Int,
        state: RunLoopState,
    ): Boolean {
        var finalRoundJudgeOutputs by state::finalRoundJudgeOutputs
        var finalRoundSubmissionSummaries by state::finalRoundSubmissionSummaries
        var finalRoundResult by state::finalRoundResult
        var finalRoundIndex by state::finalRoundIndex
        var latePublicationRecoveryContext by state::latePublicationRecoveryContext
        val existingRoundHistory = RoundPhaseLedgerRepository(
            substrateFactory.getAtlas(config.runId)
        ).history(config.runId, round)
        if (existingRoundHistory.isNotEmpty()) {
            require(RoundPhaseLedgerPolicy.isArchived(existingRoundHistory)) {
                "Run restart found a partially sealed round $round; mutable round phases " +
                    "will not be replayed"
            }
            val restoredRound = loadCompletedRoundAuthority(
                runId = config.runId,
                expectedCompletedRoundCount = round + 1,
                expectedGroupIds = groupIds
            )
            require(restoredRound.roundIndex == round) {
                "Archived round ledger does not match completed-round authority"
            }
            finalRoundIndex = restoredRound.roundIndex
            finalRoundJudgeOutputs = restoredRound.judgeOutputsByGrader
            finalRoundSubmissionSummaries = restoredRound.groupSummaries
                .associate { it.groupId to it.submissionSummary }
            finalRoundResult = restoredRound.toGradingResult()
            updatePersistedRunState(config.runId) { current ->
                current.copy(
                    status = PersistedRunStatus.RUNNING,
                    nextRoundIndex = round + 1,
                    activeRoundIndex = null
                )
            }
            latePublicationRecoveryContext = completedRoundRecoveryContext(
                config = config,
                groupIds = groupIds,
                roundIndex = finalRoundIndex,
                roundResult = finalRoundResult,
                roundSubmissionSummaries = finalRoundSubmissionSummaries,
            )
            return true
        }
        return false
    }

    /**
     * Launches the grader criteria phase concurrently with round workers (Phase A).
     *
     * Criteria agents study the objective and dimensions and develop the scoring rubric; the
     * returned job is joined before judging.
     *
     * @param config Active run configuration.
     * @param graderGroupIds Grader civilisations.
     * @param round Current round.
     * @return The criteria job, or null when no criteria runner or grader group is configured.
     */
    private fun launchGraderCriteriaJob(
        config: RunConfig,
        graderGroupIds: List<String>,
        round: Int,
    ): Job? {
        return if (graderCriteriaRunner != null && graderGroupIds.isNotEmpty()) {
            scope.launch {
                try {
                    emit(EvoMoliorEvent.EngineProgress(config.runId, "grader_criteria_start",
                        "Grader criteria agents starting (round $round) — developing scoring rubric…"))
                    graderCriteriaRunner.invoke(
                        config.runId,
                        config.openRouterApiKey,
                        graderGroupIds,
                        round,
                        config.objective,
                        config.scoringDimensions
                    ) { progressMsg ->
                        scope.launch {
                            emit(EvoMoliorEvent.EngineProgress(config.runId, "grader_criteria", progressMsg))
                        }
                    }
                    emit(EvoMoliorEvent.EngineProgress(config.runId, "grader_criteria_done",
                        "Grader criteria phase complete (round $round)"))
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Napier.w("EvoMoliorRunService: criteria phase failed for round=$round: ${e.message}")
                }
            }
        } else null
    }

    /**
     * Waits for the round's workers, emitting heartbeats and honouring pause and abort.
     *
     * Fixed-duration rounds keep wall-clock semantics. Budget-led production rounds observe live
     * balances and let healthy workers spend their grants until they finish or stall.
     *
     * @param config Active run configuration.
     * @param groupIds Competing worker groups.
     * @param round Current round.
     * @param budgetLedRound Whether the round ends on worker quiescence rather than time.
     * @param roundDurationMs Fixed duration, or null for budget-led rounds.
     */
    private suspend fun awaitRoundWorkers(
        config: RunConfig,
        groupIds: List<String>,
        round: Int,
        budgetLedRound: Boolean,
        roundDurationMs: Long?,
    ): RoundWaitOutcome {
        var roundEndedDueToStall = false
        if (budgetLedRound) {
            var previousLiveAgentBudgets: Map<String, Double>? = null
            var unchangedSinceMs = System.currentTimeMillis()
            var heartbeatCounter = 0
            while (currentCoroutineContext().isActive) {
                // Honour pause
                var resumedFromPause = false
                while (currentStatus == EngineStatus.PAUSED) {
                    resumedFromPause = true
                    delay(500)
                }
                if (resumedFromPause) {
                    // Paused time is operator-controlled, not evidence of worker stasis.
                    previousLiveAgentBudgets = null
                    unchangedSinceMs = System.currentTimeMillis()
                }
                // Honour abort
                if (currentStatus == EngineStatus.IDLE) {
                    Napier.i("EvoMoliorRunService: run aborted mid-round at round $round")
                    return RoundWaitOutcome.ABORTED
                }

                val liveAgentBudgets = snapshotLiveAgentBudgets(groupIds)
                if (liveAgentBudgets.isEmpty()) {
                    emit(EvoMoliorEvent.EngineProgress(config.runId, "round_early_end",
                        "Round $round — all agents completed, advancing to post-round evaluation"))
                    break
                }

                delay(roundHeartbeatIntervalMs)
                heartbeatCounter++
                // Avoid evaluating a stale snapshot if pause began during the heartbeat.
                if (currentStatus == EngineStatus.PAUSED) continue
                val now = System.currentTimeMillis()
                val heartbeatSnapshot = snapshotLiveAgentBudgets(groupIds)
                if (heartbeatSnapshot != previousLiveAgentBudgets) {
                    previousLiveAgentBudgets = heartbeatSnapshot
                    unchangedSinceMs = now
                }

                val liveAgentCount = heartbeatSnapshot.size
                val unchangedForMs = now - unchangedSinceMs
                val msg = "Round $round — budget-led heartbeat $heartbeatCounter, " +
                    "$liveAgentCount agents active, " +
                    "${unchangedForMs / 1_000L}s without live-set/budget progress"
                Napier.i("EvoMoliorRunService: $msg")
                emit(EvoMoliorEvent.EngineProgress(config.runId, "round_wait", msg, null))

                if (heartbeatSnapshot.isEmpty()) {
                    Napier.i("EvoMoliorRunService: all agents completed for round $round — ending round")
                    emit(EvoMoliorEvent.EngineProgress(config.runId, "round_early_end",
                        "Round $round — all agents completed, advancing to post-round evaluation"))
                    break
                }
                if (unchangedForMs >= BUDGET_LED_ROUND_STALL_GRACE_MS) {
                    roundEndedDueToStall = true
                    Napier.w(
                        "EvoMoliorRunService: round $round stalled for " +
                            "${BUDGET_LED_ROUND_STALL_GRACE_MS / 60_000L} minutes"
                    )
                    emit(EvoMoliorEvent.EngineProgress(config.runId, "round_stall_end",
                        "Round $round — live agents and remaining budgets made no progress " +
                            "for ${BUDGET_LED_ROUND_STALL_GRACE_MS / 60_000L} minutes; " +
                            "terminating the stalled workers"))
                    break
                }
            }
        } else {
            val fixedRoundDurationMs = requireNotNull(roundDurationMs)
            // Instead of a raw delay, poll periodically so the UI sees "still alive"
            // messages and we can honour pause/abort without waiting the full duration.
            val roundEndMs = System.currentTimeMillis() + fixedRoundDurationMs
            var heartbeatCounter = 0
            while (currentCoroutineContext().isActive) {
                val now = System.currentTimeMillis()
                val remainingMs = roundEndMs - now
                if (remainingMs <= 0) break

                // Honour pause
                while (currentStatus == EngineStatus.PAUSED) { delay(500) }
                // Honour abort
                if (currentStatus == EngineStatus.IDLE) {
                    Napier.i("EvoMoliorRunService: run aborted mid-round at round $round")
                    return RoundWaitOutcome.ABORTED
                }

                delay(minOf(roundHeartbeatIntervalMs, remainingMs))
                heartbeatCounter++

                val remainingAfterDelayMs =
                    (roundEndMs - System.currentTimeMillis()).coerceAtLeast(0L)
                val elapsedMs = (fixedRoundDurationMs - remainingAfterDelayMs)
                    .coerceIn(0L, fixedRoundDurationMs)
                val fraction = elapsedMs.toDouble() / fixedRoundDurationMs.toDouble()

                val liveAgentCounts = groupIds.sumOf { gid ->
                    liveLifecycleManagers[gid]?.getLiveAgentCount() ?: 0
                }
                val msg = "Round $round — ${elapsedMs / 1_000L}/" +
                    "${fixedRoundDurationMs / 1_000L} sec elapsed, $liveAgentCounts agents active"
                Napier.i("EvoMoliorRunService: $msg")
                emit(EvoMoliorEvent.EngineProgress(config.runId, "round_wait", msg, fraction))

                // Early exit: if all agents have completed after at least one heartbeat,
                // there is no point waiting out the full round duration.
                if (liveAgentCounts == 0 && heartbeatCounter > 0) {
                    Napier.i("EvoMoliorRunService: all agents completed for round $round — ending round early")
                    emit(EvoMoliorEvent.EngineProgress(config.runId, "round_early_end",
                        "Round $round — all agents completed, advancing to post-round evaluation"))
                    break
                }
            }
        }
        return if (roundEndedDueToStall) RoundWaitOutcome.STALLED else RoundWaitOutcome.COMPLETED
    }

    /**
     * Resolves the winner and publishes the run after its last round: accepted publication when
     * authority allows, otherwise the non-accepted terminal package.
     *
     * @param config Active run configuration.
     * @param groupIds Competing worker groups.
     * @param effectiveRounds Round horizon after Council capping.
     * @param state Run-loop state with final-round authority and terminal disposition.
     */
    private suspend fun completeRunAfterRounds(
        config: RunConfig,
        groupIds: List<String>,
        effectiveRounds: Int,
        state: RunLoopState,
    ) {
        var latePublicationRecoveryContext by state::latePublicationRecoveryContext
        var terminalEvent by state::terminalEvent
        val finalRoundJudgeOutputs = state.finalRoundJudgeOutputs
        val finalRoundSubmissionSummaries = state.finalRoundSubmissionSummaries
        val finalRoundResult = state.finalRoundResult
        val finalRoundIndex = state.finalRoundIndex
        val autoCompletionReached = state.autoCompletionReached
        if (finalRoundIndex >= 0) {
            val finalAnswerSummary = buildString {
                append("Round ${finalRoundIndex + 1} scoring result: scoringCandidate=")
                append(finalRoundResult?.winnerId ?: "none")
                append(" | acceptanceAuthority=pending")
                if (finalRoundJudgeOutputs.isNotEmpty()) {
                    append(" | judge=")
                    append(
                        finalRoundJudgeOutputs.entries.joinToString(" | ") { (graderId, output) ->
                            "$graderId=${summarizeRunGraphSnippet(output, 180)}"
                        }
                    )
                }
                if (finalRoundSubmissionSummaries.isNotEmpty()) {
                    append(" | summaries=")
                    append(
                        finalRoundSubmissionSummaries.entries.joinToString(" | ") { (groupId, summary) ->
                            "$groupId=${summary.orEmpty().replace(Regex("\\s+"), " ").trim().take(120)}"
                        }
                    )
                }
            }
            emit(
                EvoMoliorEvent.EngineProgress(
                    config.runId,
                    "final_round_result",
                    finalAnswerSummary
                )
            )
        }

        // All rounds complete
        require(finalRoundIndex >= 0) {
            "Final publication requires durable completed-round authority"
        }
        val substrateHealthByGroup = collectRunSubstrateHealth(
            runId = config.runId,
            groupIds = groupIds,
            submissionSummariesByGroup = finalRoundSubmissionSummaries
        )
        val winnerId = selectFinalWinnerId(
            config = config,
            finalRoundIndex = finalRoundIndex,
            finalRoundResult = authoritativeGraderRoundResults[finalRoundIndex],
            groupSummaries = groupSummaries,
            submissionSummariesByGroup = finalRoundSubmissionSummaries,
            substrateHealthByGroup = substrateHealthByGroup
        )
        val finalAnswerSummary = if (finalRoundIndex >= 0) {
            buildString {
                append("Round ${finalRoundIndex + 1} final answer: winner=")
                append(winnerId ?: "none")
                if (finalRoundJudgeOutputs.isNotEmpty()) {
                    append(" | judge=")
                    append(
                        finalRoundJudgeOutputs.entries.joinToString(" | ") { (graderId, output) ->
                            "$graderId=${summarizeRunGraphSnippet(output, 180)}"
                        }
                    )
                }
                if (finalRoundSubmissionSummaries.isNotEmpty()) {
                    append(" | summaries=")
                    append(
                        finalRoundSubmissionSummaries.entries.joinToString(" | ") { (groupId, summary) ->
                            "$groupId=${summary.orEmpty().replace(Regex("\\s+"), " ").trim().take(120)}"
                        }
                    )
                }
            }
        } else {
            ""
        }
        val resolvedFinalOutputSpec = resolveFinalOutputSpec(config)
        val generatedImageAssets = prepareFinalPublicationContext(
            config = config,
            winnerId = winnerId,
            finalRoundIndex = finalRoundIndex,
            effectiveRounds = effectiveRounds,
            groupIds = groupIds,
            resolvedFinalOutputSpec = resolvedFinalOutputSpec,
            finalRoundSubmissionSummaries = finalRoundSubmissionSummaries,
        ) { latePublicationRecoveryContext = it }
        try {
            verifyAcceptanceRunBoundary(
                config,
                AcceptanceRunBoundary.BeforeFinalPublication
            )
        } catch (boundaryFailure: Exception) {
            latePublicationRecoveryContext = latePublicationRecoveryContext?.copy(
                finalPublicationBoundaryRejected = true
            )
            throw boundaryFailure
        }
        if (config.councilExecutionPurpose == CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE) {
            requireNotNull(winnerId) {
                "Appendix B acceptance run completed its rounds without a publishable winning group"
            }
            requireNotNull(resolvedFinalOutputSpec) {
                "Appendix B acceptance run has no receiver-facing final output contract"
            }
        }
        if (
            config.acceptanceDiagnosticMode ==
            AcceptanceDiagnosticMode.FULL_SWEEP_NON_ACCEPTING
        ) {
            throw NonAcceptingDiagnosticSweepRequestedException()
        }
        val acceptedPackageInputs = winnerId?.let { finalWinnerId ->
            loadFinalDeliverablePackageInputs(
                runId = config.runId,
                winnerGroupId = finalWinnerId,
                finalRoundIndex = finalRoundIndex
            )
        }
        val acceptedSnapshotReady = acceptedPackageInputs?.let { inputs ->
            val required = inputs.blueprint.componentSpecs
                .map(DeliverableComponentSpec::componentId)
                .sorted()
            inputs.snapshot.acceptedComponentIds == required &&
                inputs.snapshot.provisionalComponentIds.isEmpty() &&
                inputs.snapshot.withheldComponentIds.isEmpty() &&
                inputs.snapshot.failedGateIds.isEmpty() &&
                inputs.snapshot.missingAuthorityComponentIds.isEmpty()
        } == true
        val publicationEnabled =
            winnerId != null &&
                resolvedFinalOutputSpec != null &&
                acceptedSnapshotReady
        if (!publicationEnabled) {
            terminalEvent = prepareNonAcceptedPublicationTerminal(
                config = config,
                groupIds = groupIds,
                context = requireNotNull(latePublicationRecoveryContext),
                finalRoundIndex = finalRoundIndex,
                winnerId = winnerId,
                resolvedFinalOutputSpec = resolvedFinalOutputSpec,
            )
            return
        }
        val finalCandidates = sealFinalPublicationCandidates(
            config = config,
            groupIds = groupIds,
            winnerId = requireNotNull(winnerId),
            finalRoundIndex = finalRoundIndex,
            outputSpec = requireNotNull(resolvedFinalOutputSpec),
        )
        val reusableCandidateOutput = latePublicationRecoveryContext
            ?.candidateOutput
            ?.takeIf { publicationEnabled && it.sourceGroupId == winnerId }
            ?.let { candidate ->
                refreshAcceptedCandidateLineage(
                    config = config,
                    groupId = requireNotNull(winnerId),
                    outputSpec = requireNotNull(resolvedFinalOutputSpec),
                    candidate = candidate,
                )
            }
        val generatedOutputFile =
            reusableCandidateOutput ?: winnerId
                    ?.let { winningGroupId ->
                        resolvedFinalOutputSpec
                            ?.let { outputSpec ->
                                spawnFinalDeliverableFile(
                                    config = config,
                                    groupId = winningGroupId,
                                    finalRoundIndex = finalRoundIndex.takeIf { it >= 0 }
                                        ?: (effectiveRounds - 1),
                                    outputSpec = outputSpec,
                                    generatedImageAssets = generatedImageAssets,
                                    finalRoundSummary =
                                        finalRoundSubmissionSummaries[winningGroupId]
                                )
                            }
                    }
        latePublicationRecoveryContext = latePublicationRecoveryContext?.copy(
            candidateOutput = generatedOutputFile,
            candidateOutputSource = generatedOutputFile?.let {
                if (reusableCandidateOutput != null) {
                    latePublicationRecoveryContext?.candidateOutputSource
                        ?: RecoveredDeliverableSource.STAGED_WRITER_OUTPUT
                } else {
                    RecoveredDeliverableSource.STAGED_WRITER_OUTPUT
                }
            }
        )

        if (generatedOutputFile != null) {
            validateStagedFinalOutput(
                output = generatedOutputFile,
                outputSpec = requireNotNull(resolvedFinalOutputSpec),
                runObjective = config.objective,
                generatedImageAssets = generatedImageAssets,
                runId = config.runId,
                groupId = winnerId,
                roundIndex = finalRoundIndex.takeIf { it >= 0 } ?: (effectiveRounds - 1),
                scoringDimensions = normaliseScoringDimensions(config.scoringDimensions)
            )
            val candidateRepository = PublicationStateRepository(
                substrateFactory.getAtlas(config.runId)
            )
            val sealedFinalCandidates = candidateRepository.sealFinalCandidates(finalCandidates)
            require(
                sealedFinalCandidates.mapTo(linkedSetOf()) { it.groupId } == groupIds.toSet()
            ) {
                "Accepted publication lacks the complete sealed final-candidate set"
            }
            advancePublicationState(
                runId = config.runId,
                state = PublicationState.FINAL_CANDIDATES_SEALED,
                authorityReferenceIds =
                sealedFinalCandidates.mapTo(linkedSetOf()) { candidate ->
                    "final-candidate:${candidate.candidateId}:${candidate.recordHash}"
                }
            )
            advancePublicationState(
                runId = config.runId,
                state = PublicationState.WINNER_OR_FINAL_POLICY_RESOLVED,
                authorityReferenceIds = setOf("winner:$winnerId")
            )
            advancePublicationState(
                runId = config.runId,
                state = PublicationState.FINAL_WRITER_RUNNING,
                authorityReferenceIds = setOf(
                    "winner:$winnerId",
                    "writer-output:${generatedOutputFile.contentHash}",
                )
            )
            val finalReferences = recordFinalDeliverableReferences(
                runId = config.runId,
                groupId = requireNotNull(generatedOutputFile.sourceGroupId),
                round = finalRoundIndex.takeIf { it >= 0 } ?: (effectiveRounds - 1),
                output = generatedOutputFile,
                outputSpec = requireNotNull(resolvedFinalOutputSpec)
            )
            require(finalReferences.isNotEmpty()) {
                "Final deliverable ${generatedOutputFile.relativePath} has no admitted lineage authority"
            }
            advancePublicationState(
                runId = config.runId,
                state = PublicationState.OUTPUT_STAGED,
                authorityReferenceIds = finalReferences.mapTo(linkedSetOf()) {
                    "final-deliverable-reference:${it.referenceId}:${it.recordHash}"
                }
            )
            val receiverScoreContribution = recordReceiverScoreContribution(
                runId = config.runId,
                round = finalRoundIndex.takeIf { it >= 0 } ?: (effectiveRounds - 1),
                outputSpec = requireNotNull(resolvedFinalOutputSpec),
                finalReferences = finalReferences
            )
            val summaryValidation = recordSummaryValidation(
                runId = config.runId,
                round = finalRoundIndex.takeIf { it >= 0 } ?: (effectiveRounds - 1),
                output = generatedOutputFile,
                outputSpec = requireNotNull(resolvedFinalOutputSpec),
                finalReferences = finalReferences
            )
            advancePublicationState(
                runId = config.runId,
                state = PublicationState.OUTPUT_VALIDATED,
                authorityReferenceIds = finalReferences.mapTo(linkedSetOf()) {
                    "validated-final-deliverable-reference:${it.referenceId}:${it.recordHash}"
                } + listOfNotNull(summaryValidation).map {
                    "summary-validation:${it.validationId}:${it.recordHash}"
                } + listOf(receiverScoreContribution).map {
                    "receiver-score-contribution:${it.contributionId}:${it.recordHash}"
                }
            )
            finalizePublicationQuiescence(
                runId = config.runId,
                groupIds = groupIds,
                generatedOutputFile = generatedOutputFile
            )
            emit(
                EvoMoliorEvent.EngineProgress(
                    config.runId,
                    "final_deliverable_result",
                    "Final deliverable writer published ${generatedOutputFile.relativePath} for winner=$winnerId"
                )
            )
        }

        val requiredSubstrateWinnerCaveat = buildRequiredSubstrateWinnerCaveat(
            runObjective = config.objective,
            substrateHealthByGroup = substrateHealthByGroup
        )
        val finalAnswerSummaryWithCaveat = listOfNotNull(
            finalAnswerSummary.takeIf { it.isNotBlank() },
            requiredSubstrateWinnerCaveat
        ).joinToString(" | ")
        val graphMessage = buildRunGraphMessage(
            runId = config.runId,
            winnerId = winnerId,
            finalAnswerSummary = finalAnswerSummaryWithCaveat,
            finalDeliverableHtml = generatedOutputFile
                ?.takeIf { it.mediaType.startsWith("text/html", ignoreCase = true) }
                ?.content,
            generatedOutputFiles = listOfNotNull(generatedOutputFile),
            generatedImageAssets = generatedImageAssets,
            judgeOutputsByGrader = finalRoundJudgeOutputs,
            submissionSummariesByGroup = finalRoundSubmissionSummaries,
            substrateHealthByGroup = substrateHealthByGroup
        )
        val completionDecision = resolveRunCompletionDecision(
            finalRoundIndex = finalRoundIndex,
            autoCompletionReached = autoCompletionReached
        )
        val completionRecord = RunCompletionRecord(
            runId = config.runId,
            winnerId = winnerId,
            completedRoundCount = completionDecision.completedRoundCount,
            completionReason = completionDecision.reason,
            finalAnswerSummary = finalAnswerSummaryWithCaveat,
            finalDeliverableHtml = generatedOutputFile
                ?.takeIf { it.mediaType.startsWith("text/html", ignoreCase = true) }
                ?.content,
            generatedOutputFiles = listOfNotNull(generatedOutputFile),
            generatedImageAssets = generatedImageAssets,
            requestedOutputSpec = resolvedFinalOutputSpec,
            judgeOutputsByGrader = finalRoundJudgeOutputs,
            submissionSummariesByGroup = finalRoundSubmissionSummaries,
            substrateHealthByGroup = substrateHealthByGroup,
            graphMessage = graphMessage
        )
        if (!terminalOwner.compareAndSet(null, TerminalOwner.COMPLETION)) {
            Napier.i(
                "EvoMoliorRunService: abort owns terminal disposition for run ${config.runId}; " +
                    "skipping completion authority"
            )
            return
        }
        try {
            try {
                verifyAcceptanceRunBoundary(
                    config,
                    AcceptanceRunBoundary.BeforeArchivePublication
                )
                latePublicationRecoveryContext = latePublicationRecoveryContext?.copy(
                    archiveBoundaryStatus = ArchiveBoundaryRecoveryStatus.PASSED
                )
            } catch (boundaryFailure: Exception) {
                latePublicationRecoveryContext = latePublicationRecoveryContext?.copy(
                    archiveBoundaryStatus = ArchiveBoundaryRecoveryStatus.REJECTED
                )
                throw boundaryFailure
            }
            persistRunCompletionMarker(completionRecord)
            persistRunGraphMessage(completionRecord)

            // 4b. Phase 7.5: Synthesise final deliverables from the DST for each group.
            runFinalSynthesisers(config, groupIds)

            // 5. Archive run artefacts for cross-run Council learning
            publishAcceptedOutcome(
                config = config,
                groupIds = groupIds,
                winnerId = requireNotNull(winnerId),
                finalRoundIndex = finalRoundIndex,
                generatedOutput = requireNotNull(generatedOutputFile),
                completionRecord = completionRecord,
                packageInputs = requireNotNull(acceptedPackageInputs)
            )
            emit(
                EvoMoliorEvent.EngineProgress(
                    config.runId,
                    "final_answer",
                    finalAnswerSummary
                )
            )
        } catch (error: Exception) {
            val publicationState = runCatching {
                PublicationStateRepository(substrateFactory.getAtlas(config.runId))
                    .latest(config.runId)
                    ?.state
            }.onFailure(error::addSuppressed).getOrNull()
            val acceptedOutcomeArchiveExists = runCatching {
                (archiveService ?: RunArchiveService(substrateFactory))
                    .readAcceptedOutcomeArchive(config.runId) != null
            }.onFailure(error::addSuppressed).getOrDefault(false)
            if (publicationState != null || acceptedOutcomeArchiveExists) {
                Napier.w(
                    "EvoMoliorRunService: preserving completion authority for exact " +
                        "archive recovery from $publicationState"
                )
            } else {
                runCatching {
                    clearRunCompletionAuthorities(config.runId)
                }.onFailure(error::addSuppressed)
            }
            throw error
        }

        currentStatus = EngineStatus.COMPLETED
        updatePersistedRunState(config.runId) { current ->
            current.copy(
                status = PersistedRunStatus.COMPLETED,
                nextRoundIndex = completionDecision.completedRoundCount,
                activeRoundIndex = null
            )
        }
        Napier.i("EvoMoliorRunService: run ${config.runId} completed, winner=$winnerId")
        terminalEvent = EvoMoliorEvent.RunCompleted(config.runId, winnerId)
    }

    /** Resolves failed execution against durable publication authority before terminal cleanup. */
    private suspend fun recoverRunLoopFailure(
        config: RunConfig,
        context: LatePublicationRecoveryContext?,
        error: Exception,
        onTerminalEvent: (EvoMoliorEvent) -> Unit,
    ) {
        if (terminalOwner.get() == TerminalOwner.ABORT) return
        val acceptedArchive = runCatching {
            (archiveService ?: RunArchiveService(substrateFactory))
                .readAcceptedOutcomeArchive(config.runId)
        }.onFailure(error::addSuppressed).getOrNull()
        if (acceptedArchive != null) {
            runCatching {
                reconcileAcceptedOutcomeHistoryFromArchive(config.runId, acceptedArchive)
            }.onFailure(error::addSuppressed)
            terminalOwner.set(TerminalOwner.COMPLETION)
            currentStatus = EngineStatus.COMPLETED
            runCatching {
                updatePersistedRunState(config.runId) { current ->
                    current.copy(
                        status = PersistedRunStatus.COMPLETED,
                        nextRoundIndex = context?.completedRoundCount ?: current.nextRoundIndex,
                        activeRoundIndex = null,
                    )
                }
            }.onFailure(error::addSuppressed)
            onTerminalEvent(
                EvoMoliorEvent.RunCompleted(config.runId, context?.recoverySourceGroupId)
            )
            Napier.w(
                "EvoMoliorRunService: accepted archive remains terminal authority after " +
                    "post-archive reconciliation failure",
                error,
            )
            return
        }
        var recoveryPublicationFailure: Throwable? = null
        val recoveredEvent = context?.let { recoveryContext ->
            runCatching {
                recoverLatePublicationFailure(config, recoveryContext, error)
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                recoveryPublicationFailure = failure
                error.addSuppressed(failure)
            }.getOrNull()
        }
        if (recoveredEvent != null) {
            onTerminalEvent(
                persistRecoveryTerminalProjection(
                    config.runId,
                    recoveredEvent,
                    context?.completedRoundCount,
                )
            )
        } else {
            if (!claimAbortTerminal()) return
            Napier.e("EvoMoliorRunService: run loop error", error)
            onTerminalEvent(
                EvoMoliorEvent.RunAborted(
                    config.runId,
                    if (recoveryPublicationFailure != null) {
                        "Run failed after archived work; provisional publication was unavailable"
                    } else {
                        error.message ?: "internal_error"
                    },
                )
            )
            currentStatus = EngineStatus.IDLE
            updatePersistedRunState(config.runId) { current ->
                current.copy(status = PersistedRunStatus.FAILED)
            }
        }
    }

    /**
     * Cancels every owned participant before draining any of them. Failed drains retain ownership
     * and can be retried through abortRun after the remaining execution actually settles.
     *
     * @param runId Run whose work is being finalized.
     * @param cancellationFailure Original cancellation, if finalization followed cancellation.
     * @param terminalEvent Event permitted only after successful resource settlement.
     * @param pendingCriteriaJob Criteria work not yet joined by the round loop.
     * @param drainTimeoutMillis Finite per-participant allowance for cancellation and draining.
     */
    internal suspend fun finishRunLoop(
        runId: String,
        cancellationFailure: CancellationException?,
        terminalEvent: EvoMoliorEvent?,
        pendingCriteriaJob: Job?,
        drainTimeoutMillis: Long = 30_000L,
    ) {
        require(drainTimeoutMillis > 0L) { "Run drain timeout must be positive" }
        val wasCancelled = cancellationFailure != null || !currentCoroutineContext().isActive
        val criteria = pendingCriteriaJob ?: failedRunDrain?.criteriaJob
        val managers = liveLifecycleManagers.entries
            .map { it.key to it.value }
            .sortedBy { it.first }

        suspend fun attempt(label: String, block: suspend () -> Unit): Exception? = try {
            withTimeout(drainTimeoutMillis) { block() }
            null
        } catch (failure: Exception) {
            IllegalStateException("$label did not settle", failure)
        }

        withContext(NonCancellable) {
            val failures = mutableListOf<Exception>()
            criteria?.let { job ->
                attempt("Criteria cancellation") { job.cancel() }?.let(failures::add)
            }
            // A failed criteria join or one group's cancellation must never prevent the other
            // groups from receiving their stop request.
            failures += supervisorScope {
                managers.map { (groupId, manager) ->
                    async { attempt("Worker $groupId cancellation") { manager.terminateAll() } }
                }.mapNotNull { it.await() }
            }
            failures += supervisorScope {
                val criteriaDrain = criteria?.let { job ->
                    async { attempt("Criteria drain") { job.join() } }
                }
                val workerDrains = managers.map { (groupId, manager) ->
                    async {
                        attempt("Worker $groupId drain") {
                            manager.awaitQuiescence(drainTimeoutMillis)
                        }
                    }
                }
                (listOfNotNull(criteriaDrain) + workerDrains).mapNotNull { it.await() }
            }
            if (failures.isNotEmpty()) {
                val aggregate = IllegalStateException(
                    "Run $runId cleanup failed in ${failures.size} operation(s); " +
                        "unsettled execution ownership is retained",
                    cancellationFailure,
                )
                failures.forEach(aggregate::addSuppressed)
                failedRunDrain = FailedRunDrain(criteria, aggregate)
                Napier.e("EvoMoliorRunService: bounded run cleanup failed", aggregate)
                throw aggregate
            }
            failedRunDrain = null
            if (wasCancelled || terminalEvent != null) {
                cleanup(runId)
            }
        }
        if (!wasCancelled && terminalEvent != null) {
            emit(terminalEvent)
        }
    }

    /**
     * Seals contribution-bound candidates after the winner's immutable run checks pass.
     * Kept outside the coroutine run loop so publication work has its own JVM method boundary.
     */
    private suspend fun sealFinalPublicationCandidates(
        config: RunConfig,
        groupIds: List<String>,
        winnerId: String,
        finalRoundIndex: Int,
        outputSpec: FinalOutputSpec,
    ): List<FinalCandidateRecord> {
        validateFinalWriterRunAuthority(
            runId = config.runId,
            groupId = winnerId,
            roundIndex = finalRoundIndex,
            scoringDimensions = normaliseScoringDimensions(config.scoringDimensions),
        )
        val outputContractHash = AtlasHashing.hashText(
            runConfigJson.encodeToString(FinalOutputSpec.serializer(), outputSpec)
        )
        val sealedAt = Clock.System.now()
        return groupIds.map { groupId ->
            val sources = ContributionGraphRepository(
                database = substrateFactory.get(config.runId, groupId),
                atlasDatabase = substrateFactory.getAtlas(config.runId),
            ).finalDeliverableSources(config.runId, groupId)
                .map { it.authority }
                .distinct()
                .sortedWith(compareBy({ it.kind.name }, ContributionAuthorityRef::authorityId))
            require(sources.isNotEmpty()) {
                "Final candidate $groupId lacks admitted contribution authority"
            }
            FinalCandidateRecord(
                candidateId = CouncilDeterministicIds.create(
                    "final-candidate",
                    config.runId,
                    groupId,
                    finalRoundIndex.toString(),
                    outputContractHash,
                    sources.joinToString("|") {
                        "${it.kind.name}:${it.authorityId}:${it.authorityHash}"
                    },
                ),
                runId = config.runId,
                groupId = groupId,
                round = finalRoundIndex,
                outputContractHash = outputContractHash,
                sourceAuthorities = sources,
                sealedAt = sealedAt,
            ).withComputedHash()
        }
    }

    private suspend fun prepareNonAcceptedPublicationTerminal(
        config: RunConfig,
        groupIds: List<String>,
        context: LatePublicationRecoveryContext,
        finalRoundIndex: Int,
        winnerId: String?,
        resolvedFinalOutputSpec: FinalOutputSpec?,
    ): EvoMoliorEvent? {
        if (!terminalOwner.compareAndSet(null, TerminalOwner.RECOVERY)) {
            return null
        }
        val recoveredEvent = try {
            val recoveryFailureClass =
                if (
                    config.acceptanceDiagnosticMode ==
                        AcceptanceDiagnosticMode.FULL_SWEEP_NON_ACCEPTING
                ) {
                    RunRecoveryFailureClass.DIAGNOSTIC_SWEEP
                } else {
                    RunRecoveryFailureClass.RUN_AUTHORITY_REJECTED
                }
            val recoveryFailureSummary = publicRecoveryFailureSummary(recoveryFailureClass)
            val recoveredOutput = selectRecoveredOutput(
                config = config,
                context = context,
                failureClass = recoveryFailureClass,
                failureSummary = recoveryFailureSummary,
                originalFailure = IllegalStateException(recoveryFailureSummary),
            )
            val diagnosticSweepReport =
                if (config.acceptanceDiagnosticMode ==
                    AcceptanceDiagnosticMode.FULL_SWEEP_NON_ACCEPTING
                ) {
                    runLatePublicationDiagnosticSweep(
                        config = config,
                        context = context,
                        recoveredOutput = recoveredOutput,
                        failureClass = recoveryFailureClass,
                    )
                } else {
                    null
                }
            publishProvisionalOutcome(
                config = config,
                groupIds = groupIds,
                finalRoundIndex = finalRoundIndex,
                winnerId = winnerId,
                resolvedFinalOutputSpec = resolvedFinalOutputSpec,
                recoveredOutput = recoveredOutput?.first,
                recoveredOutputSource = recoveredOutput?.second,
                diagnosticSweepReport = diagnosticSweepReport,
            )
        } catch (failure: Exception) {
            terminalOwner.compareAndSet(TerminalOwner.RECOVERY, null)
            throw failure
        }
        return persistRecoveryTerminalProjection(
            runId = config.runId,
            recoveredEvent = recoveredEvent,
            nextRoundIndex = finalRoundIndex + 1,
        )
    }

    private fun EvoMoliorEvent.RunRecovered.toCompletedProvisionalEventIfReady():
        EvoMoliorEvent =
        if (
            terminalOutcome?.executionStatus ==
                com.achulkov.evomolior.core.deliverable.ExecutionCompletionStatus.COMPLETED &&
            terminalOutcome?.acceptanceStatus ==
                com.achulkov.evomolior.core.deliverable.AcceptanceStatus.PROVISIONAL
        ) {
            EvoMoliorEvent.RunCompletedProvisional(
                runId = runId,
                sourceGroupId = sourceGroupId,
                outputPaths = recoveredOutputPaths,
                reason = reason,
                terminalOutcome = requireNotNull(terminalOutcome),
            )
        } else {
            this
        }

    private suspend fun persistRecoveryTerminalProjection(
        runId: String,
        recoveredEvent: EvoMoliorEvent.RunRecovered,
        nextRoundIndex: Int?,
    ): EvoMoliorEvent {
        val completedProvisionally =
            recoveredEvent.terminalOutcome?.executionStatus ==
                com.achulkov.evomolior.core.deliverable.ExecutionCompletionStatus.COMPLETED &&
                recoveredEvent.terminalOutcome?.acceptanceStatus ==
                com.achulkov.evomolior.core.deliverable.AcceptanceStatus.PROVISIONAL
        currentStatus = if (completedProvisionally) {
            EngineStatus.COMPLETED
        } else {
            EngineStatus.RECOVERED
        }
        updatePersistedRunState(runId) { current ->
            current.copy(
                status = if (completedProvisionally) {
                    PersistedRunStatus.COMPLETED
                } else {
                    PersistedRunStatus.RECOVERED
                },
                nextRoundIndex = nextRoundIndex ?: current.nextRoundIndex,
                activeRoundIndex = null,
            )
        }
        return recoveredEvent.toCompletedProvisionalEventIfReady()
    }

    /**
     * Archives a useful non-accepted result after a completed cycle fails normal publication.
     */
    private suspend fun recoverLatePublicationFailure(
        config: RunConfig,
        context: LatePublicationRecoveryContext,
        originalFailure: Exception
    ): EvoMoliorEvent.RunRecovered? {
        if (
            (archiveService ?: RunArchiveService(substrateFactory))
                .readAcceptedOutcomeArchive(config.runId) != null
        ) {
            return null
        }
        if (
            context.finalPublicationBoundaryRejected ||
            context.archiveBoundaryStatus == ArchiveBoundaryRecoveryStatus.REJECTED ||
            context.completedRoundCount <= 0
        ) {
            return null
        }
        val publicationState = runCatching {
            PublicationStateRepository(substrateFactory.getAtlas(config.runId))
                .latest(config.runId)
                ?.state
        }.onFailure(originalFailure::addSuppressed).getOrNull()
        if (publicationState != null) {
            return null
        }
        if (!claimRecoveryTerminal()) return null

        runCatching {
            clearRunCompletionAuthorities(config.runId)
        }.onFailure(originalFailure::addSuppressed)

        return try {
            val publicReason = if (context.publicationPhaseReached) {
                "Accepted publication failed before any accepted publication state was durable"
            } else {
                val roundLabel = if (context.completedRoundCount == 1) "round" else "rounds"
                "Run stopped after ${context.completedRoundCount} fully archived $roundLabel; " +
                    "accepted publication authority was unavailable"
            }
            val recoveryFailureClass =
                if (
                    config.acceptanceDiagnosticMode ==
                    AcceptanceDiagnosticMode.FULL_SWEEP_NON_ACCEPTING
                ) {
                    RunRecoveryFailureClass.DIAGNOSTIC_SWEEP
                } else {
                    RunRecoveryFailureClass.PUBLICATION_FAILED
                }
            val recoveryFailureSummary = publicRecoveryFailureSummary(recoveryFailureClass)
            val recoveredOutput = selectRecoveredOutput(
                config = config,
                context = context,
                failureClass = recoveryFailureClass,
                failureSummary = recoveryFailureSummary,
                originalFailure = originalFailure,
            )
            val diagnosticSweepReport = if (
                config.acceptanceDiagnosticMode ==
                AcceptanceDiagnosticMode.FULL_SWEEP_NON_ACCEPTING
            ) {
                runLatePublicationDiagnosticSweep(
                    config = config,
                    context = context,
                    recoveredOutput = recoveredOutput,
                    failureClass = recoveryFailureClass,
                )
            } else {
                null
            }
            publishProvisionalOutcome(
                config = config,
                groupIds = context.groupIds,
                finalRoundIndex = context.finalRoundIndex,
                winnerId = null,
                resolvedFinalOutputSpec = context.outputSpec,
                recoveredOutput = recoveredOutput?.first,
                recoveredOutputSource = recoveredOutput?.second,
                publicReason = publicReason,
                diagnosticSweepReport = diagnosticSweepReport,
            )
        } catch (failure: Exception) {
            terminalOwner.compareAndSet(TerminalOwner.RECOVERY, null)
            throw failure
        }
    }

    private fun claimRecoveryTerminal(): Boolean {
        while (true) {
            when (terminalOwner.get()) {
                TerminalOwner.ABORT,
                TerminalOwner.RECOVERY -> return false

                null -> if (
                    terminalOwner.compareAndSet(null, TerminalOwner.RECOVERY)
                ) {
                    return true
                }

                TerminalOwner.COMPLETION -> if (
                    terminalOwner.compareAndSet(
                        TerminalOwner.COMPLETION,
                        TerminalOwner.RECOVERY
                    )
                ) {
                    return true
                }
            }
        }
    }

    private fun claimAbortTerminal(): Boolean {
        while (true) {
            when (terminalOwner.get()) {
                TerminalOwner.ABORT,
                TerminalOwner.RECOVERY -> return false

                null -> if (terminalOwner.compareAndSet(null, TerminalOwner.ABORT)) {
                    return true
                }

                TerminalOwner.COMPLETION -> if (
                    terminalOwner.compareAndSet(TerminalOwner.COMPLETION, TerminalOwner.ABORT)
                ) {
                    return true
                }
            }
        }
    }

    private fun classifyRecoveryFailure(
        publicationState: PublicationState?,
        archiveBoundaryStatus: ArchiveBoundaryRecoveryStatus
    ): RunRecoveryFailureClass {
        if (archiveBoundaryStatus == ArchiveBoundaryRecoveryStatus.PASSED) {
            return RunRecoveryFailureClass.ARCHIVE_PUBLICATION_FAILED
        }
        return when (publicationState) {
        PublicationState.WINNER_OR_FINAL_POLICY_RESOLVED ->
            RunRecoveryFailureClass.RUN_AUTHORITY_REJECTED

        PublicationState.FINAL_WRITER_RUNNING,
        PublicationState.OUTPUT_STAGED ->
            RunRecoveryFailureClass.FINAL_OUTPUT_REJECTED

        PublicationState.OUTPUT_VALIDATED,
        PublicationState.COST_FINALIZED,
        PublicationState.DEATH_NOTE_FINALIZED,
        PublicationState.ALL_NON_DAEMON_AGENTS_TERMINATED,
        PublicationState.METRICS_EXPORTED ->
            RunRecoveryFailureClass.ARCHIVE_PUBLICATION_FAILED

        PublicationState.FINAL_CANDIDATES_SEALED,
        null -> RunRecoveryFailureClass.PUBLICATION_FAILED

        PublicationState.MANIFEST_HASHED,
        PublicationState.ARCHIVED ->
            error("Accepted manifest/archive authority is not recoverable")
        }
    }

    private fun publicRecoveryFailureSummary(
        failureClass: RunRecoveryFailureClass
    ): String = when (failureClass) {
        RunRecoveryFailureClass.RUN_AUTHORITY_REJECTED ->
            "Normal publication failed immutable run-level acceptance authority."

        RunRecoveryFailureClass.DIAGNOSTIC_SWEEP ->
            "Normal publication was deliberately withheld for a non-accepting diagnostic sweep."

        RunRecoveryFailureClass.FINAL_OUTPUT_REJECTED ->
            "Normal publication failed final-output authority."

        RunRecoveryFailureClass.ARCHIVE_PUBLICATION_FAILED ->
            "Normal publication failed during quiescence, metrics, or archive publication."

        RunRecoveryFailureClass.PUBLICATION_FAILED ->
            "Normal publication failed after the work cycle completed."
    }

    private suspend fun selectRecoveredOutput(
        config: RunConfig,
        context: LatePublicationRecoveryContext,
        failureClass: RunRecoveryFailureClass,
        failureSummary: String,
        originalFailure: Exception
    ): Pair<GeneratedOutputFile, RecoveredDeliverableSource>? {
        val sourceGroupId = context.recoverySourceGroupId ?: return null
        val outputSpec = context.outputSpec ?: return null
        selectBestHistoricalArtifact(
            config = config,
            context = context,
            outputSpec = outputSpec,
        )?.let { historical ->
            emit(
                EvoMoliorEvent.EngineProgress(
                    config.runId,
                    "recovery_best_historical_artifact",
                    "[$sourceGroupId] Selected the best evidence-backed artifact snapshot " +
                        "before considering the latest regressed candidate",
                )
            )
            return historical
        }
        context.candidateOutput
            ?.let {
                validatePublicRecoveryOutput(
                    output = it,
                    outputSpec = outputSpec,
                    config = config,
                    context = context
                )
            }
            ?.let { output ->
                return output to (
                    context.candidateOutputSource
                        ?: RecoveredDeliverableSource.STAGED_WRITER_OUTPUT
                    )
            }

        // Preserve the host-owned scaffold when provider candidates regress and no historical
        // snapshot has execution or benchmark facts yet. A completed run must not lose its only
        // receiver artifact merely because diagnostic recovery is selecting among bad candidates.
        artifactOutputsByGroup[sourceGroupId]
            ?.let {
                validatePublicRecoveryOutput(
                    output = it,
                    outputSpec = outputSpec,
                    config = config,
                    context = context,
                )
            }
            ?.let { output ->
                val message =
                    "[$sourceGroupId] Selected the host-owned ARC scaffold as a " +
                        "provisional recovery artifact"
                Napier.i("EvoMoliorRunService: $message")
                emit(
                    EvoMoliorEvent.EngineProgress(
                        config.runId,
                        "recovery_host_owned_scaffold",
                        message,
                    )
                )
                return output to RecoveredDeliverableSource.DETERMINISTIC_RECOVERY_FALLBACK
            }

        if (ArcAgi2NotebookRecoveryFallback.supportsRecoveryTarget(outputSpec)) {
            val fallback = ArcAgi2NotebookRecoveryFallback.build(
                outputSpec = outputSpec,
                sourceGroupId = sourceGroupId
            )
            validatePublicRecoveryOutput(
                output = fallback,
                outputSpec = outputSpec,
                config = config,
                context = context
            )?.let { output ->
                val message =
                    "[$sourceGroupId] Selected validated deterministic non-accepted ARC " +
                        "notebook recovery fallback before launching recovery writer attempts"
                Napier.i("EvoMoliorRunService: $message")
                emit(
                    EvoMoliorEvent.EngineProgress(
                        config.runId,
                        "recovery_deterministic_fallback",
                        message
                    )
                )
                return output to RecoveredDeliverableSource.DETERMINISTIC_RECOVERY_FALLBACK
            }
        }

        if (
            failureClass == RunRecoveryFailureClass.DIAGNOSTIC_SWEEP ||
            failureClass == RunRecoveryFailureClass.RUN_AUTHORITY_REJECTED ||
            failureClass == RunRecoveryFailureClass.PUBLICATION_FAILED
        ) {
            runCatching {
                spawnFinalDeliverableFile(
                    config = config,
                    groupId = sourceGroupId,
                    finalRoundIndex = context.finalRoundIndex,
                    outputSpec = outputSpec,
                    generatedImageAssets = context.generatedImageAssets,
                    finalRoundSummary = context.finalRoundSummary,
                    publicationMode =
                        FinalDeliverablePublicationMode.RECOVERY_NON_ACCEPTED,
                    recoveryReason = failureSummary
                )
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                originalFailure.addSuppressed(failure)
            }.getOrNull()
                ?.let {
                    validatePublicRecoveryOutput(
                        output = it,
                        outputSpec = outputSpec,
                        config = config,
                        context = context
                    )
                }
                ?.let {
                    return it to RecoveredDeliverableSource.RECOVERY_WRITER_OUTPUT
                }
        }

        val summaryCandidate = context.finalRoundSummary
            ?.let { extractRoundCompletionCandidate(it, outputSpec) }
            ?.let { extractGeneratedOutputFile(it, outputSpec) }
            ?.copy(sourceGroupId = sourceGroupId)
        summaryCandidate?.let { candidate ->
            validatePublicRecoveryOutput(
                output = candidate,
                outputSpec = outputSpec,
                config = config,
                context = context
            )?.let { return it to RecoveredDeliverableSource.FINAL_ROUND_SUMMARY }
        }

        if (ArcAgi2NotebookRecoveryFallback.supportsRecoveryTarget(outputSpec)) {
            val fallback = ArcAgi2NotebookRecoveryFallback.build(
                outputSpec = outputSpec,
                sourceGroupId = sourceGroupId
            )
            validatePublicRecoveryOutput(
                output = fallback,
                outputSpec = outputSpec,
                config = config,
                context = context
            )?.let {
                Napier.w(
                    "EvoMoliorRunService: using deterministic non-accepted ARC notebook " +
                        "recovery fallback after all authored candidates failed validation"
                )
                return it to RecoveredDeliverableSource.DETERMINISTIC_RECOVERY_FALLBACK
            }
        }
        return null
    }

    private fun selectBestHistoricalArtifact(
        config: RunConfig,
        context: LatePublicationRecoveryContext,
        outputSpec: FinalOutputSpec,
    ): Pair<GeneratedOutputFile, RecoveredDeliverableSource>? {
        val sourceGroupId = context.recoverySourceGroupId ?: return null
        val handle = artifactWorkspaceHandlesByGroup[sourceGroupId] ?: return null
        val managerRoot = handle.root.parentFile
            ?.parentFile
            ?.parentFile
            ?.parentFile
            ?: return null
        val manager = ArtifactWorkspaceManager(managerRoot)
        val snapshot = manager.bestSnapshot(handle) ?: return null
        if (
            snapshot.top1 == null &&
            snapshot.anyTwo == null &&
            snapshot.executionReportHash == null &&
            snapshot.benchmarkReportHash == null
        ) {
            return null
        }
        val artifact = runCatching {
            manager.readSnapshotArtifact(handle, snapshot)
        }.getOrNull() ?: return null
        return validatePublicRecoveryOutput(
            output = artifact,
            outputSpec = outputSpec,
            config = config,
            context = context,
        )?.let { it to RecoveredDeliverableSource.STAGED_WRITER_OUTPUT }
    }

    private suspend fun runLatePublicationDiagnosticSweep(
        config: RunConfig,
        context: LatePublicationRecoveryContext,
        recoveredOutput: Pair<GeneratedOutputFile, RecoveredDeliverableSource>?,
        failureClass: RunRecoveryFailureClass
    ): AcceptanceDiagnosticSweepReport {
        val results = linkedMapOf<
            AcceptanceDiagnosticGate,
            AcceptanceDiagnosticGateResult
            >()

        fun record(
            gate: AcceptanceDiagnosticGate,
            status: AcceptanceDiagnosticGateStatus,
            summary: String,
            blockedBy: List<AcceptanceDiagnosticGate> = emptyList(),
            evidenceRefs: List<String> = emptyList()
        ) {
            check(gate !in results) { "Diagnostic gate $gate was evaluated more than once" }
            results[gate] = AcceptanceDiagnosticGateResult(
                gate = gate,
                status = status,
                summary = summary.take(512),
                blockedBy = blockedBy.distinct().sortedBy(AcceptanceDiagnosticGate::ordinal),
                evidenceRefs = evidenceRefs.distinct().sorted()
            )
        }

        suspend fun evaluate(
            gate: AcceptanceDiagnosticGate,
            passSummary: String,
            failSummary: String,
            evidenceRefs: List<String> = emptyList(),
            block: suspend () -> Unit
        ) {
            val failure = runCatching { block() }.exceptionOrNull()
            if (failure == null) {
                record(gate, AcceptanceDiagnosticGateStatus.PASS, passSummary, evidenceRefs = evidenceRefs)
            } else {
                record(
                    gate = gate,
                    status = AcceptanceDiagnosticGateStatus.FAIL,
                    summary = failSummary,
                    evidenceRefs = evidenceRefs + (
                        "opaque-failure:" + AtlasHashing.hashText(
                            failure::class.qualifiedName.orEmpty() + "|" +
                                failure.message.orEmpty()
                        )
                        )
                )
            }
        }

        fun blocked(
            gate: AcceptanceDiagnosticGate,
            summary: String,
            vararg prerequisites: AcceptanceDiagnosticGate
        ) {
            record(
                gate = gate,
                status = AcceptanceDiagnosticGateStatus.BLOCKED_BY,
                summary = summary,
                blockedBy = prerequisites.toList()
            )
        }

        if (context.completedRoundCount == config.rounds) {
            record(
                AcceptanceDiagnosticGate.FINAL_ROUND_AUTHORITY,
                AcceptanceDiagnosticGateStatus.PASS,
                "All configured rounds have durable completion authority.",
                evidenceRefs = listOf(
                    "round-count:${context.completedRoundCount}",
                    "final-round:${context.finalRoundIndex}"
                )
            )
        } else {
            record(
                AcceptanceDiagnosticGate.FINAL_ROUND_AUTHORITY,
                AcceptanceDiagnosticGateStatus.FAIL,
                "Completed-round authority does not cover the configured horizon.",
                evidenceRefs = listOf(
                    "round-count:${context.completedRoundCount}",
                    "configured-rounds:${config.rounds}"
                )
            )
        }

        val exactVerdict = exactGraderVerdictsByRound[context.finalRoundIndex]
        val expectedBundleCount = activeGraderGroupIds.size * context.groupIds.size
        if (
            exactVerdict != null &&
            activeGraderGroupIds.isNotEmpty() &&
            exactVerdict.status == ExactGraderRoundStatus.VALID &&
            exactVerdict.acceptedBundleHashes.size == expectedBundleCount &&
            exactVerdict.targetSnapshotHashes.keys ==
            context.groupIds.mapTo(linkedSetOf(), ::AtlasGroupId)
        ) {
            record(
                AcceptanceDiagnosticGate.EXACT_GRADER_QUORUM,
                AcceptanceDiagnosticGateStatus.PASS,
                "Final-round grader cardinality and target coverage are exact.",
                evidenceRefs = listOf(
                    "accepted-bundles:${exactVerdict.acceptedBundleHashes.size}",
                    "expected-bundles:$expectedBundleCount"
                )
            )
        } else {
            record(
                AcceptanceDiagnosticGate.EXACT_GRADER_QUORUM,
                AcceptanceDiagnosticGateStatus.FAIL,
                "Final-round exact grader quorum or target coverage is unresolved.",
                evidenceRefs = listOf("expected-bundles:$expectedBundleCount")
            )
        }

        val sourceGroupId = context.recoverySourceGroupId
        if (
            sourceGroupId != null &&
            sourceGroupId in context.groupIds &&
            context.recoverySourceGroupQuorumBound
        ) {
            record(
                AcceptanceDiagnosticGate.SOURCE_GROUP_RESOLUTION,
                AcceptanceDiagnosticGateStatus.PASS,
                "A quorum-bound diagnostic source group was resolved.",
                evidenceRefs = listOf("source-group:$sourceGroupId")
            )
        } else {
            record(
                AcceptanceDiagnosticGate.SOURCE_GROUP_RESOLUTION,
                AcceptanceDiagnosticGateStatus.FAIL,
                "No quorum-bound diagnostic source group was resolved."
            )
        }

        evaluate(
            gate = AcceptanceDiagnosticGate.FINAL_CANDIDATE_AUTHORITY,
            passSummary = "Every worker group has admitted final-candidate source authority.",
            failSummary = "At least one worker group lacks admitted final-candidate source authority."
        ) {
            context.groupIds.forEach { groupId ->
                val sources = ContributionGraphRepository(
                    database = substrateFactory.get(config.runId, groupId),
                    atlasDatabase = substrateFactory.getAtlas(config.runId)
                ).finalDeliverableSources(config.runId, groupId)
                require(sources.isNotEmpty())
            }
        }

        if (sourceGroupId == null || !context.recoverySourceGroupQuorumBound) {
            blocked(
                AcceptanceDiagnosticGate.RUN_LEVEL_SEALED_TESTS,
                "Run-level sealed tests require a resolved source group.",
                AcceptanceDiagnosticGate.SOURCE_GROUP_RESOLUTION
            )
        } else {
            evaluate(
                gate = AcceptanceDiagnosticGate.RUN_LEVEL_SEALED_TESTS,
                passSummary = "Run-level immutable sealed-test authority passed.",
                failSummary = "Run-level immutable sealed-test authority rejected the source group.",
                evidenceRefs = listOf(
                    "source-group:$sourceGroupId",
                    "round:${context.finalRoundIndex}"
                )
            ) {
                validateFinalWriterRunAuthority(
                    runId = config.runId,
                    groupId = sourceGroupId,
                    roundIndex = context.finalRoundIndex,
                    scoringDimensions = normaliseScoringDimensions(config.scoringDimensions)
                )
            }
        }

        val output = recoveredOutput?.first
        val outputSource = recoveredOutput?.second
        when {
            output == null -> record(
                AcceptanceDiagnosticGate.DIAGNOSTIC_WRITER,
                AcceptanceDiagnosticGateStatus.FAIL,
                "No diagnostic writer output or public fallback candidate was available."
            )
            outputSource == RecoveredDeliverableSource.FINAL_ROUND_SUMMARY -> record(
                AcceptanceDiagnosticGate.DIAGNOSTIC_WRITER,
                AcceptanceDiagnosticGateStatus.FAIL,
                "The writer produced no valid output; a final-round summary supplied the fallback.",
                evidenceRefs = listOf("output:${output.relativePath}:${output.contentHash}")
            )
            else -> record(
                AcceptanceDiagnosticGate.DIAGNOSTIC_WRITER,
                AcceptanceDiagnosticGateStatus.PASS,
                "A bounded non-accepting writer produced a candidate.",
                evidenceRefs = listOf("output:${output.relativePath}:${output.contentHash}")
            )
        }

        if (output == null || context.outputSpec == null) {
            blocked(
                AcceptanceDiagnosticGate.OUTPUT_CONTRACT,
                "Output-contract validation requires a candidate and an explicit output contract.",
                AcceptanceDiagnosticGate.DIAGNOSTIC_WRITER
            )
        } else {
            evaluate(
                gate = AcceptanceDiagnosticGate.OUTPUT_CONTRACT,
                passSummary = "The diagnostic candidate satisfies the public receiver contract.",
                failSummary = "The diagnostic candidate failed the public receiver contract.",
                evidenceRefs = listOf("output:${output.relativePath}:${output.contentHash}")
            ) {
                requireNotNull(
                    validatePublicRecoveryOutput(
                        output = output,
                        outputSpec = context.outputSpec,
                        config = config,
                        context = context
                    )
                )
            }
        }

        if (
            output == null ||
            context.outputSpec == null ||
            sourceGroupId == null ||
            !context.recoverySourceGroupQuorumBound
        ) {
            blocked(
                AcceptanceDiagnosticGate.COMPLETE_SEALED_TESTS,
                "Complete sealed tests require a source group, output contract, and candidate.",
                AcceptanceDiagnosticGate.SOURCE_GROUP_RESOLUTION,
                AcceptanceDiagnosticGate.DIAGNOSTIC_WRITER
            )
        } else {
            evaluate(
                gate = AcceptanceDiagnosticGate.COMPLETE_SEALED_TESTS,
                passSummary = "The diagnostic candidate passed the complete sealed-test suite.",
                failSummary = "The diagnostic candidate failed complete sealed-test authority.",
                evidenceRefs = listOf("output:${output.relativePath}:${output.contentHash}")
            ) {
                validateFinalOutputCandidate(
                    output = output,
                    outputSpec = context.outputSpec,
                    runObjective = config.objective,
                    generatedImageAssets = context.generatedImageAssets,
                    runId = config.runId,
                    groupId = sourceGroupId,
                    roundIndex = context.finalRoundIndex,
                    scoringDimensions = normaliseScoringDimensions(config.scoringDimensions),
                    selection = SealedTestEvaluationSelection.COMPLETE_SUITE,
                    requireEvaluatedTests = true,
                    sealedFailureMessage =
                        "Diagnostic candidate failed complete sealed-test authority"
                )
            }
        }

        if (output == null || sourceGroupId == null) {
            blocked(
                AcceptanceDiagnosticGate.SOURCE_LINEAGE,
                "Source-lineage inspection requires a source group and candidate.",
                AcceptanceDiagnosticGate.SOURCE_GROUP_RESOLUTION,
                AcceptanceDiagnosticGate.DIAGNOSTIC_WRITER
            )
        } else {
            evaluate(
                gate = AcceptanceDiagnosticGate.SOURCE_LINEAGE,
                passSummary = "The candidate retains admitted source and writer submission lineage.",
                failSummary = "The candidate lacks complete admitted source or writer lineage.",
                evidenceRefs = listOf("output:${output.relativePath}:${output.contentHash}")
            ) {
                validateDiagnosticSourceLineage(config.runId, sourceGroupId, output)
            }
        }

        evaluate(
            gate = AcceptanceDiagnosticGate.COST_RESERVATIONS,
            passSummary = "No active cost reservation remains.",
            failSummary = "Active cost reservations remain after the work cycle."
        ) {
            val summary = CostLedgerRepository(
                substrateFactory.get(config.runId, COST_LEDGER_GROUP_ID),
                costLedgerPolicy(config.runId)
            ).summary(config.runId)
            require(summary.activeReservationCount == 0L)
        }

        if (output == null || sourceGroupId == null) {
            blocked(
                AcceptanceDiagnosticGate.FINAL_WRITER_DEATH_NOTE,
                "Final-writer Death Note inspection requires a writer candidate.",
                AcceptanceDiagnosticGate.DIAGNOSTIC_WRITER
            )
        } else {
            evaluate(
                gate = AcceptanceDiagnosticGate.FINAL_WRITER_DEATH_NOTE,
                passSummary = "The diagnostic writer has durable Death Note authority.",
                failSummary = "The diagnostic writer lacks durable Death Note authority."
            ) {
                validateDiagnosticWriterDeathNote(config.runId, sourceGroupId, output)
            }
        }

        evaluate(
            gate = AcceptanceDiagnosticGate.NON_DAEMON_QUIESCENCE,
            passSummary = "All worker groups are quiescent with exact non-daemon accounting.",
            failSummary = "Worker lifecycle or non-daemon accounting is not quiescent."
        ) {
            validateDiagnosticQuiescence(context.groupIds)
        }

        var metricsExport: RunArchiveService.NonAcceptedRunMetricsExport? = null
        evaluate(
            gate = AcceptanceDiagnosticGate.RUN_METRICS_CATALOG,
            passSummary = "All 21 run metrics were collected in canonical order.",
            failSummary = "The complete 21-metric catalog could not be exported."
        ) {
            val startingField = loadSealedStartingFieldSnapshot(config.runId)
                ?: error("Diagnostic metric export requires the sealed StartingField")
            metricsExport = (archiveService ?: RunArchiveService(substrateFactory))
                .exportNonAcceptedRunMetrics(
                    runId = config.runId,
                    groupIds = context.groupIds,
                    startingFieldJson = runConfigJson.encodeToString(
                        StartingField.serializer(),
                        startingField
                    ),
                    finalGeneration = context.completedRoundCount,
                    requestedOutputSpec = context.outputSpec,
                    completionRecord = null
                )
        }

        val exportedMetrics = metricsExport
        if (config.acceptanceProfile != AcceptanceProfile.APPENDIX_B_FULL) {
            record(
                AcceptanceDiagnosticGate.APPENDIX_B_METRIC_POLICY,
                AcceptanceDiagnosticGateStatus.FAIL,
                "Appendix-B substantive metric policy requires appendix-b-full."
            )
        } else if (exportedMetrics == null) {
            blocked(
                AcceptanceDiagnosticGate.APPENDIX_B_METRIC_POLICY,
                "Appendix-B metric policy requires the complete metric catalog.",
                AcceptanceDiagnosticGate.RUN_METRICS_CATALOG
            )
        } else {
            val failures = buildList {
                if (exportedMetrics.missingMeasurementInfrastructureMetricIds.isNotEmpty()) {
                    add(
                        "missing infrastructure=" +
                            exportedMetrics.missingMeasurementInfrastructureMetricIds
                                .sorted()
                                .joinToString()
                    )
                }
                addAll(exportedMetrics.appendixBPolicyFailures)
            }
            if (failures.isEmpty()) {
                record(
                    AcceptanceDiagnosticGate.APPENDIX_B_METRIC_POLICY,
                    AcceptanceDiagnosticGateStatus.PASS,
                    "All substantive Appendix-B metric predicates pass.",
                    evidenceRefs = listOf(
                        "run-metrics-v1:${exportedMetrics.artifact.sha256}"
                    )
                )
            } else {
                record(
                    AcceptanceDiagnosticGate.APPENDIX_B_METRIC_POLICY,
                    AcceptanceDiagnosticGateStatus.FAIL,
                    failures.joinToString("; ").take(512),
                    evidenceRefs = listOf(
                        "run-metrics-v1:${exportedMetrics.artifact.sha256}"
                    )
                )
            }
        }

        if (context.archiveBoundaryStatus == ArchiveBoundaryRecoveryStatus.PASSED) {
            record(
                AcceptanceDiagnosticGate.ARCHIVE_BOUNDARY,
                AcceptanceDiagnosticGateStatus.PASS,
                "The immutable pre-archive boundary passed in the normal path."
            )
        } else {
            verifyAcceptanceRunBoundary(
                config,
                AcceptanceRunBoundary.BeforeArchivePublication
            )
            record(
                AcceptanceDiagnosticGate.ARCHIVE_BOUNDARY,
                AcceptanceDiagnosticGateStatus.PASS,
                "The immutable pre-archive boundary passed during the diagnostic sweep."
            )
        }

        if (failureClass == RunRecoveryFailureClass.DIAGNOSTIC_SWEEP) {
            record(
                AcceptanceDiagnosticGate.NORMAL_ARCHIVE_PUBLICATION,
                AcceptanceDiagnosticGateStatus.FAIL,
                "Normal archive publication is intentionally disabled in diagnostic mode."
            )
        } else if (failureClass == RunRecoveryFailureClass.ARCHIVE_PUBLICATION_FAILED) {
            record(
                AcceptanceDiagnosticGate.NORMAL_ARCHIVE_PUBLICATION,
                AcceptanceDiagnosticGateStatus.FAIL,
                "Normal archive publication failed after its prerequisites were attempted."
            )
        } else {
            val blockers = results.values
                .filter { it.status == AcceptanceDiagnosticGateStatus.FAIL }
                .map(AcceptanceDiagnosticGateResult::gate)
            if (blockers.isEmpty()) {
                record(
                    AcceptanceDiagnosticGate.NORMAL_ARCHIVE_PUBLICATION,
                    AcceptanceDiagnosticGateStatus.FAIL,
                    "Normal archive publication was not reached in the strict path."
                )
            } else {
                blocked(
                    AcceptanceDiagnosticGate.NORMAL_ARCHIVE_PUBLICATION,
                    "Normal archive publication is forbidden after a diagnostic bypass.",
                    *blockers.toTypedArray()
                )
            }
        }
        blocked(
            AcceptanceDiagnosticGate.TERMINAL_ACCEPTANCE,
            "Terminal acceptance requires verified normal archive publication.",
            AcceptanceDiagnosticGate.NORMAL_ARCHIVE_PUBLICATION
        )

        return AcceptanceDiagnosticSweepReport(
            runId = config.runId,
            acceptanceProfile = config.acceptanceProfile.id,
            completedRoundCount = context.completedRoundCount,
            sourceGroupId = sourceGroupId,
            results = AcceptanceDiagnosticGate.entries.map(results::getValue),
            createdAtMs = System.currentTimeMillis()
        )
    }

    private suspend fun validateDiagnosticSourceLineage(
        runId: String,
        groupId: String,
        output: GeneratedOutputFile
    ) {
        require(output.sourceGroupId == groupId)
        val sourceAgentId = requireNotNull(output.sourceAgentId)
        val sourceSubmissionId = requireNotNull(output.sourceSubmissionId)
        val sources = ContributionGraphRepository(
            database = substrateFactory.get(runId, groupId),
            atlasDatabase = substrateFactory.getAtlas(runId)
        ).finalDeliverableSources(runId, groupId)
        require(sources.isNotEmpty())
        val stagingDao = substrateFactory.get(runId, groupId).stagingDao()
        val writerSubmission = requireNotNull(stagingDao.getById(sourceSubmissionId))
        require(writerSubmission.agentId == sourceAgentId)
        output.componentLineageSubmissionId?.let { lineageId ->
            val lineage = requireNotNull(stagingDao.getById(lineageId))
            require(
                lineage.agentId == sourceAgentId &&
                    lineage.type ==
                    com.achulkov.evomolior.core.model.SubmissionType.PLAN_UPDATE.name
            )
        }
    }

    private suspend fun validateDiagnosticWriterDeathNote(
        runId: String,
        groupId: String,
        output: GeneratedOutputFile
    ) {
        val sourceAgentId = requireNotNull(output.sourceAgentId)
        val deathNote = substrateFactory.get(runId, groupId)
            .stagingDao()
            .getByAgent(runId, groupId, sourceAgentId)
            .firstOrNull {
                it.type ==
                    com.achulkov.evomolior.core.model.SubmissionType.DEATH_NOTE.name
            }
        requireNotNull(deathNote)
        output.componentLineageSubmittedAtMs?.let { lineageAt ->
            FinalComponentLineageOrder.requireBeforeDeathNote(
                lineageSubmittedAtMs = lineageAt,
                deathNoteSubmittedAtMs = deathNote.submittedAtMs
            )
        }
    }

    private suspend fun validateDiagnosticQuiescence(groupIds: List<String>) {
        groupIds.forEach { groupId ->
            val manager = requireNotNull(liveLifecycleManagers[groupId])
            require(manager.getLiveAgentCount() == 0)
            require(manager.getLiveNestedGroupCount() == 0)
            require(manager.publicationAccounting().isQuiescent)
        }
    }

    private fun validatePublicRecoveryOutput(
        output: GeneratedOutputFile,
        outputSpec: FinalOutputSpec,
        config: RunConfig,
        context: LatePublicationRecoveryContext
    ): GeneratedOutputFile? = runCatching {
        require(output.sourceGroupId == context.recoverySourceGroupId) {
            "Recovery output belongs to a non-authoritative source group"
        }
        val validated = validateGeneratedOutputFile(output)
        val rejection = validateFinalOutputAgainstObjective(
            output = validated,
            outputSpec = outputSpec,
            runObjective = config.objective,
            generatedImageAssets = context.generatedImageAssets
        )
        require(rejection == null) {
            "Recovery output failed the public output contract"
        }
        validated
    }.getOrNull()

    private fun buildRecoveryNotice(
        runId: String,
        completedRoundCount: Int,
        failureSummary: String,
        sourceGroupId: String?,
        recoveredOutput: GeneratedOutputFile?
    ): String = buildString {
        appendLine("# Recovered Run — Not Accepted")
        appendLine()
        appendLine("- Run: `$runId`")
        appendLine("- Completed rounds: $completedRoundCount")
        appendLine("- Acceptance authority: **false**")
        appendLine("- Accepted winner: **none**")
        appendLine("- Failure: $failureSummary")
        appendLine(
            "- Recovery source group: " +
                (sourceGroupId?.let { "`$it` (source only; not a winner)" } ?: "none")
        )
        appendLine(
            "- Recovered receiver artifact: " +
                (
                    recoveredOutput?.let {
                        "`outputs/recovered/${it.relativePath}`"
                    } ?: "none; this notice is the deliverable"
                    )
        )
        appendLine()
        appendLine(
            "This bundle preserves useful work and public diagnostics. It is not normal " +
                "completion, Appendix-B acceptance, a passing metrics packet, or permission " +
                "to treat the source group as a winner."
        )
    }

    private fun buildProvisionalTerminalOutcome(
        runId: String,
        recoverySourceGroupId: String?,
        recoveredOutput: GeneratedOutputFile?,
        failureClass: RunRecoveryFailureClass,
        decisionPackageHash: String,
    ): com.achulkov.evomolior.core.deliverable.RunTerminalOutcomeV2 {
        if (recoveredOutput == null) {
            return com.achulkov.evomolior.core.deliverable.RunTerminalOutcomeV2(
                executionStatus =
                    com.achulkov.evomolior.core.deliverable.ExecutionCompletionStatus
                        .FAILED_UNWRITABLE_OUTPUT,
                acceptanceStatus =
                    com.achulkov.evomolior.core.deliverable.AcceptanceStatus.NOT_EVALUATED,
                primaryArtifactRef = null,
                decisionPackageRef = null,
                acceptedWinnerId = null,
                writerSourceAuthority = null,
                failedGateIds = listOf("primary-artifact-unavailable"),
            ).withComputedHash()
        }
        val primaryArtifactRef = ArtifactRef(
            artifactId = "primary-artifact-$runId",
            relativePath = "outputs/recovered/${recoveredOutput.relativePath}",
            mediaType = recoveredOutput.mediaType,
            artifactHash = recoveredOutput.contentHash,
        )
        val sourceAuthority = recoverySourceGroupId
            ?.let(writerSourceAuthoritiesByGroup::get)
            ?.takeUnless {
                it.authorityClass == WriterSourceAuthorityClass.ACCEPTED_WINNER
            }
            // The snapshot authority ladder is ARC-specific. A generic authored file has
            // staging provenance, but must not be mislabeled as a host-owned scaffold.
            ?: if (
                ArcAgi2NotebookRecoveryFallback.supportsRecoveryTarget(
                    FinalOutputSpec(recoveredOutput.relativePath, recoveredOutput.mediaType)
                )
            ) {
                WriterSourceAuthority(
                    primaryGroupId = recoverySourceGroupId,
                    authorityClass = WriterSourceAuthorityClass.SCAFFOLD_ONLY,
                    sourceSnapshotIds = emptyList(),
                    donorArtifactRefs = listOf(recoveredOutput.relativePath),
                    failedGateDiagnostics = listOf(failureClass.name),
                ).withComputedHash()
            } else {
                null
            }
        return com.achulkov.evomolior.core.deliverable.RunTerminalOutcomeV2(
            executionStatus =
                com.achulkov.evomolior.core.deliverable.ExecutionCompletionStatus.COMPLETED,
            acceptanceStatus =
                com.achulkov.evomolior.core.deliverable.AcceptanceStatus.PROVISIONAL,
            primaryArtifactRef = primaryArtifactRef,
            decisionPackageRef = ArtifactRef(
                artifactId = "provisional-decision-package-$runId",
                relativePath = "provisional-decision-package.json",
                mediaType = "application/json",
                artifactHash = decisionPackageHash,
            ),
            acceptedWinnerId = null,
            writerSourceAuthority = sourceAuthority,
            failedGateIds = listOf(
                "strict-acceptance-${failureClass.name.lowercase()}",
            ),
            primaryArtifactValidationStatus =
                com.achulkov.evomolior.core.deliverable.PrimaryArtifactValidationStatus
                    .NOT_EVALUATED,
        ).withComputedHash()
    }

    private fun resolveRecoverySourceGroupId(
        config: RunConfig,
        winnerId: String?,
        finalRoundIndex: Int,
        groupIds: List<String>
    ): String? {
        val resolvedWinnerId = winnerId ?: return null
        if (resolvedWinnerId !in groupIds) return null
        if (
            config.councilExecutionPurpose !=
            CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE
        ) {
            return resolvedWinnerId
        }
        val exactVerdict = exactGraderVerdictsByRound[finalRoundIndex]
            ?: return null
        val expectedBundleCount = activeGraderGroupIds.size * groupIds.size
        return resolvedWinnerId.takeIf {
            activeGraderGroupIds.isNotEmpty() &&
                exactVerdict.status == ExactGraderRoundStatus.VALID &&
                exactVerdict.acceptedBundleHashes.size == expectedBundleCount &&
                exactVerdict.targetSnapshotHashes.keys ==
                groupIds.mapTo(linkedSetOf(), ::AtlasGroupId) &&
                AtlasGroupId(resolvedWinnerId) in exactVerdict.targetSnapshotHashes
        }
    }

    /**
     * Resolves the final writer's source group, builds the late-publication recovery context,
     * generates optional image assets, and prepares the pre-acceptance writer candidate.
     *
     * Extracted from [runLoop], which sits at the JVM method-size limit. [publishContext] is
     * invoked after every intermediate context update, exactly as the inline code assigned it, so
     * a failure part-way still leaves [runLoop]'s failure recovery with the latest context.
     *
     * @param config Active run configuration.
     * @param winnerId Final winner, or null when unresolved.
     * @param finalRoundIndex Last fully completed round index.
     * @param effectiveRounds Round horizon used when no round completed.
     * @param groupIds Competing worker groups.
     * @param resolvedFinalOutputSpec Output contract for the run.
     * @param finalRoundSubmissionSummaries Final-round summaries by group.
     * @param publishContext Receives each updated recovery context.
     * @return Generated image assets for the final deliverable.
     */
    private suspend fun prepareFinalPublicationContext(
        config: RunConfig,
        winnerId: String?,
        finalRoundIndex: Int,
        effectiveRounds: Int,
        groupIds: List<String>,
        resolvedFinalOutputSpec: FinalOutputSpec?,
        finalRoundSubmissionSummaries: Map<String, String?>,
        publishContext: (LatePublicationRecoveryContext?) -> Unit,
    ): List<GeneratedImageAsset> {
        val quorumRecoverySourceGroupId = resolveRecoverySourceGroupId(
            config = config,
            winnerId = winnerId,
            finalRoundIndex = finalRoundIndex,
            groupIds = groupIds
        )
        val provisionalRecoverySourceGroupId =
            quorumRecoverySourceGroupId
                ?: resolveProvisionalRecoverySourceGroupId(
                    runId = config.runId,
                    winnerId = winnerId,
                    groupIds = groupIds
                )
        var context: LatePublicationRecoveryContext? = LatePublicationRecoveryContext(
            groupIds = groupIds,
            recoverySourceGroupId = provisionalRecoverySourceGroupId,
            recoverySourceGroupQuorumBound = quorumRecoverySourceGroupId != null,
            finalRoundIndex = finalRoundIndex.takeIf { it >= 0 }
                ?: (effectiveRounds - 1),
            completedRoundCount = finalRoundIndex + 1,
            outputSpec = resolvedFinalOutputSpec,
            generatedImageAssets = emptyList(),
            finalRoundSummary = provisionalRecoverySourceGroupId
                ?.let(finalRoundSubmissionSummaries::get),
            publicationPhaseReached = true,
        )
        publishContext(context)
        val generatedImageAssets = if (
            provisionalRecoverySourceGroupId != null &&
            (resolvedFinalOutputSpec?.kind == FinalOutputKind.HTML_ARTICLE ||
                resolvedFinalOutputSpec?.mediaType
                    ?.startsWith("text/html", ignoreCase = true) == true)
        ) {
            generateFinalDeliverableImageAssets(config)
        } else {
            emptyList()
        }
        context = requireNotNull(context).copy(
            generatedImageAssets = generatedImageAssets,
        )
        publishContext(context)
        context = attachPreAcceptanceWriterOutput(
            context = context,
            prepared = preparePreAcceptanceWriterOutput(
                config = config,
                groupId = provisionalRecoverySourceGroupId,
                finalRoundIndex = finalRoundIndex.takeIf { it >= 0 }
                    ?: (effectiveRounds - 1),
                finalRoundSummary = provisionalRecoverySourceGroupId
                    ?.let(finalRoundSubmissionSummaries::get),
                outputSpec = resolvedFinalOutputSpec,
                generatedImageAssets = generatedImageAssets,
            ),
        )
        publishContext(context)
        return generatedImageAssets
    }

    /**
     * Selects a deterministic source-only group for provisional writer salvage.
     *
     * This selection never creates winner or quorum authority. It only gives a recovery writer a
     * stable lifecycle and substrate context when accepted winner resolution is unavailable.
     */
    private suspend fun resolveProvisionalRecoverySourceGroupId(
        runId: String,
        winnerId: String?,
        groupIds: List<String>,
    ): String? = deterministicRecoverySourceGroupId(
        winnerId,
        groupIds,
        if (winnerId in groupIds) emptyMap() else canonicalDocumentCharsByGroup(runId, groupIds),
    )

    /**
     * Length of each group's largest active canonical KB document; unreadable groups count as 0.
     * Used only to choose a recovery writer's source context, never as quality authority.
     */
    private suspend fun canonicalDocumentCharsByGroup(
        runId: String,
        groupIds: List<String>,
    ): Map<String, Int> = groupIds.associateWith { groupId ->
        try {
            substrateFactory.get(runId, groupId).knowledgeBaseDao()
                .listActiveOrganized(runId, groupId, RECOVERY_SOURCE_KB_SCAN_LIMIT)
                .filterNot { it.isDeadEnd }
                .maxOfOrNull { it.content.length }
                ?: 0
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Napier.w("EvoMoliorRunService: recovery source sizing failed for $groupId: ${error.message}")
            0
        }
    }

    private suspend fun completedRoundRecoveryContext(
        config: RunConfig,
        groupIds: List<String>,
        roundIndex: Int,
        roundResult: com.achulkov.evomolior.core.model.GradingResult?,
        roundSubmissionSummaries: Map<String, String?>,
    ): LatePublicationRecoveryContext {
        require(roundIndex >= 0) {
            "Completed-round recovery context requires a non-negative round index"
        }
        val quorumBoundSourceGroupId = resolveRecoverySourceGroupId(
            config = config,
            winnerId = roundResult?.winnerId,
            finalRoundIndex = roundIndex,
            groupIds = groupIds,
        )
        val sourceGroupId = quorumBoundSourceGroupId
            ?: resolveProvisionalRecoverySourceGroupId(
                runId = config.runId,
                winnerId = roundResult?.winnerId,
                groupIds = groupIds,
            )
        return LatePublicationRecoveryContext(
            groupIds = groupIds,
            recoverySourceGroupId = sourceGroupId,
            recoverySourceGroupQuorumBound = quorumBoundSourceGroupId != null,
            finalRoundIndex = roundIndex,
            completedRoundCount = roundIndex + 1,
            outputSpec = resolveFinalOutputSpec(config),
            generatedImageAssets = emptyList(),
            finalRoundSummary = sourceGroupId?.let(roundSubmissionSummaries::get),
        )
    }

    /**
     * Replays deterministic validation after staged lineage is durable and before validation is
     * published as authority. Attempt-level preflight remains responsible only for writer retry.
     */
    private suspend fun validateStagedFinalOutput(
        output: GeneratedOutputFile,
        outputSpec: FinalOutputSpec,
        runObjective: String,
        generatedImageAssets: List<GeneratedImageAsset>,
        runId: String,
        groupId: String,
        roundIndex: Int,
        scoringDimensions: List<ScoringDimension>
    ) {
        validateFinalOutputCandidate(
            output = output,
            outputSpec = outputSpec,
            runObjective = runObjective,
            generatedImageAssets = generatedImageAssets,
            runId = runId,
            groupId = groupId,
            roundIndex = roundIndex,
            scoringDimensions = scoringDimensions,
            selection = SealedTestEvaluationSelection.COMPLETE_SUITE,
            requireEvaluatedTests = true,
            sealedFailureMessage = "Staged final output failed active sealed-tests-v3 authority"
        )
    }

    /**
     * Validates one candidate against only authority addressable by the current publication phase.
     */
    private suspend fun validateFinalOutputCandidate(
        output: GeneratedOutputFile,
        outputSpec: FinalOutputSpec,
        runObjective: String,
        generatedImageAssets: List<GeneratedImageAsset>,
        runId: String,
        groupId: String,
        roundIndex: Int,
        scoringDimensions: List<ScoringDimension>,
        selection: SealedTestEvaluationSelection,
        requireEvaluatedTests: Boolean,
        sealedFailureMessage: String
    ) {
        val validated = validateGeneratedOutputFile(output)
        require(
            validated.relativePath == output.relativePath &&
                validated.mediaType == output.mediaType &&
                AtlasHashing.constantTimeEquals(validated.contentHash, output.contentHash)
        ) {
            "Authoritative output validation changed the staged artifact"
        }
        val rejection = validateFinalOutputAgainstObjective(
            output = validated,
            outputSpec = outputSpec,
            runObjective = runObjective,
            generatedImageAssets = generatedImageAssets
        )
        require(rejection == null) {
            "Staged final output failed authoritative validation: $rejection"
        }
        val validationContext = activeSealedTestSuiteValidationContext ?: return
        val suiteJson = activeSealedTestSuiteJson.takeIf(String::isNotBlank)
            ?: error("Active v3 sealed-test context has no private suite")
        val toolExecutions = ToolInfrastructureRepository(
            substrateFactory.get(runId, groupId)
        ).executionsForGroupThroughRound(runId, groupId, roundIndex)
        val sealedAssessment = sealedTestSuiteEvaluator.assessGroup(
            suiteJson = suiteJson,
            validationContext = validationContext,
            input = SealedTestEvaluationInputV3(
                runId = runId,
                groupId = groupId,
                roundIndex = roundIndex,
                finalOutput = sealedTestOutputArtifact(validated),
                promotionExecutions = toolExecutions
            ),
            scoringDimensions = scoringDimensions,
            selection = selection
        )
        require(
            (!requireEvaluatedTests ||
                sealedAssessment.automatedTestsPassed +
                sealedAssessment.automatedTestsFailed > 0) &&
                !sealedAssessment.mandatoryFailure
        ) {
            sealedFailureMessage
        }
    }

    /**
     * Records checks of the actual settled candidate without rewriting earlier snapshot gates.
     * Recovery can retain a useful candidate with failed private tests; this record never grants
     * acceptance, component lineage, or winner authority.
     */
    private suspend fun recordCandidateOutputValidation(
        config: RunConfig,
        groupId: String,
        roundIndex: Int,
        output: GeneratedOutputFile,
        outputSpec: FinalOutputSpec,
    ) {
        val assessment = activeSealedTestSuiteValidationContext?.let { validationContext ->
            val suite = activeSealedTestSuiteJson.takeIf(String::isNotBlank)
                ?: error("Active candidate validation lacks its sealed suite")
            sealedTestSuiteEvaluator.assessGroup(
                suiteJson = suite,
                validationContext = validationContext,
                input = SealedTestEvaluationInputV3(
                    runId = config.runId,
                    groupId = groupId,
                    roundIndex = roundIndex,
                    finalOutput = sealedTestOutputArtifact(output),
                    promotionExecutions = emptyList(),
                ),
                scoringDimensions = normaliseScoringDimensions(config.scoringDimensions),
                selection = SealedTestEvaluationSelection.FINAL_OUTPUT_ADDRESSABLE,
            )
        }
        val record = CandidateOutputValidationRecord(
            runId = config.runId,
            groupId = groupId,
            roundIndex = roundIndex,
            relativePath = output.relativePath,
            contentHash = output.contentHash,
            outputContractHash = AtlasHashing.hashText(
                runConfigJson.encodeToString(FinalOutputSpec.serializer(), outputSpec)),
            sealedSuiteHash = activeSealedTestSuiteHash.takeIf(String::isNotBlank),
            publicContractFailures = FinalOutputContractValidator.validate(output, outputSpec),
            addressableTestsPassed = assessment?.automatedTestsPassed,
            addressableTestsFailed = assessment?.automatedTestsFailed,
            mandatoryFailure = assessment?.mandatoryFailure,
            evaluatedAtMs = System.currentTimeMillis(),
        )
        substrateFactory.get(config.runId, "__meta_archive__").metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "candidate-validation-${config.runId}-$groupId-${output.contentHash}",
                runId = config.runId,
                entryType = CandidateOutputValidationRecord.ENTRY_TYPE,
                groupId = groupId,
                label = "Candidate-time validation; no acceptance authority",
                contentJson = runConfigJson.encodeToString(CandidateOutputValidationRecord.serializer(), record),
                runObjective = config.objective,
                finalScore = null,
                isFromWinner = false,
                generation = roundIndex,
                archivedAtMs = record.evaluatedAtMs,
            )
        )
        emit(EvoMoliorEvent.EngineProgress(
            config.runId, "candidate_validation",
            "[$groupId] Candidate ${output.contentHash.take(12)}: public format failures=" +
                "${record.publicContractFailures.size}; candidate-addressable tests " +
                "passed=${record.addressableTestsPassed ?: "NOT_EVALUATED"} " +
                "failed=${record.addressableTestsFailed ?: "NOT_EVALUATED"}. " +
                "Earlier round gates unchanged; acceptance not granted."))
    }

    /**
     * Rejects immutable run-level sealed-test failures before accepted publication proceeds.
     */
    private suspend fun validateFinalWriterRunAuthority(
        runId: String,
        groupId: String,
        roundIndex: Int,
        scoringDimensions: List<ScoringDimension>
    ) {
        val validationContext = activeSealedTestSuiteValidationContext ?: return
        val suiteJson = activeSealedTestSuiteJson.takeIf(String::isNotBlank)
            ?: error("Active v3 sealed-test context has no private suite")
        val toolExecutions = ToolInfrastructureRepository(
            substrateFactory.get(runId, groupId)
        ).executionsForGroupThroughRound(runId, groupId, roundIndex)
        val assessment = sealedTestSuiteEvaluator.assessGroup(
            suiteJson = suiteJson,
            validationContext = validationContext,
            input = SealedTestEvaluationInputV3(
                runId = runId,
                groupId = groupId,
                roundIndex = roundIndex,
                finalOutput = null,
                promotionExecutions = toolExecutions
            ),
            scoringDimensions = scoringDimensions,
            selection = SealedTestEvaluationSelection.RUN_AUTHORITY
        )
        if (assessment.mandatoryFailure) {
            throw IllegalStateException(
                "Final publication blocked by immutable mandatory sealed-test authority"
            )
        }
    }

    private suspend fun recordSummaryValidation(
        runId: String,
        round: Int,
        output: GeneratedOutputFile,
        outputSpec: FinalOutputSpec,
        finalReferences: List<FinalDeliverableReferenceRecord>
    ): SummaryValidationRecord? {
        if (output.sourceRole != Role.Archivist) return null
        val groupId = requireNotNull(output.sourceGroupId)
        val finalAuthorityRefs = finalReferences.map { reference ->
            ContributionAuthorityRef(
                ContributionAuthorityKind.FINAL_DELIVERABLE_REFERENCE,
                reference.referenceId,
                reference.recordHash
            )
        }
        val validatorContractHash = AtlasHashing.hashText(
            runConfigJson.encodeToString(FinalOutputSpec.serializer(), outputSpec)
        )
        val record = SummaryValidationRecord(
            validationId = CouncilDeterministicIds.create(
                "summary-validation",
                runId,
                groupId,
                requireNotNull(output.sourceAgentId),
                output.relativePath,
                output.contentHash,
                validatorContractHash
            ),
            runId = runId,
            groupId = AtlasGroupId(groupId),
            round = round,
            summarizerAgentId = requireNotNull(output.sourceAgentId),
            summarizerRole = Role.Archivist,
            finalReferences = finalAuthorityRefs,
            validatorContractHash = validatorContractHash,
            fidelityChecksPassed = 1,
            fidelityChecksTotal = 1,
            retrievalReferencesValidated = finalAuthorityRefs.size,
            retrievalReferencesTotal = finalAuthorityRefs.size,
            primaryContentAssessment = SummaryPrimaryContentAssessment.NOT_ASSESSED,
            validatedAt = Clock.System.now()
        ).withComputedHash()
        return ContributionGraphRepository(
            database = substrateFactory.get(runId, groupId),
            atlasDatabase = substrateFactory.getAtlas(runId)
        ).recordSummaryValidation(record)
    }

    private suspend fun recordReceiverScoreContribution(
        runId: String,
        round: Int,
        outputSpec: FinalOutputSpec,
        finalReferences: List<FinalDeliverableReferenceRecord>
    ): ReceiverScoreContributionRecord {
        val reference = finalReferences.sortedBy(FinalDeliverableReferenceRecord::referenceId).first()
        val contractHash = AtlasHashing.hashText(
            runConfigJson.encodeToString(FinalOutputSpec.serializer(), outputSpec)
        )
        val finalReference = ContributionAuthorityRef(
            ContributionAuthorityKind.FINAL_DELIVERABLE_REFERENCE,
            reference.referenceId,
            reference.recordHash
        )
        val record = ReceiverScoreContributionRecord(
            contributionId = CouncilDeterministicIds.create(
                "receiver-score-contribution",
                runId,
                reference.groupId.value,
                reference.contentHash,
                contractHash
            ),
            runId = runId,
            groupId = reference.groupId,
            round = round,
            finalReference = finalReference,
            scoringContractId = "final-output-contract",
            scoringContractVersion = 1,
            scoringContractHash = contractHash,
            criterionId = "deterministic-contract-validation",
            scoreContribution = 1.0,
            maximumContribution = 1.0,
            scoredAt = Clock.System.now()
        ).withComputedHash()
        return ContributionGraphRepository(
            database = substrateFactory.get(runId, reference.groupId.value),
            atlasDatabase = substrateFactory.getAtlas(runId)
        ).recordReceiverScoreContribution(record)
    }

    private suspend fun recordFinalDeliverableReferences(
        runId: String,
        groupId: String,
        round: Int,
        output: GeneratedOutputFile,
        outputSpec: FinalOutputSpec
    ): List<FinalDeliverableReferenceRecord> {
        val repository = ContributionGraphRepository(
            database = substrateFactory.get(runId, groupId),
            atlasDatabase = substrateFactory.getAtlas(runId)
        )
        val sources = repository.finalDeliverableSources(runId, groupId)
            .sortedWith(
                compareBy(
                    { it.nodeId.orEmpty() },
                    { it.authority.kind.name },
                    { it.authority.authorityId }
                )
            )
        require(sources.isNotEmpty()) {
            "Final output requires at least one admitted contribution authority"
        }
        val writerAgentId = requireNotNull(output.sourceAgentId) {
            "Final output lacks writer-agent authority"
        }
        val writerSubmissionId = requireNotNull(output.sourceSubmissionId) {
            "Final output lacks writer-submission authority"
        }
        val referencedAt = Clock.System.now()
        val requiredComponentIds = outputSpec.validation
            ?.requiredMarkdownSectionSignals
            .orEmpty()
            .toSet()
        val sourceByNode = sources.mapNotNull { source ->
            source.nodeId?.let { it to source.authority }
        }.toMap()
        val componentNodeIds = if (requiredComponentIds.isEmpty()) {
            mapOf(
                output.relativePath to sources.mapNotNullTo(sortedSetOf()) { it.nodeId }
            )
        } else {
            val lineageSubmissionId = requireNotNull(output.componentLineageSubmissionId) {
                "Required final components lack the writer's PLAN_UPDATE authority"
            }
            val database = substrateFactory.get(runId, groupId)
            val writerSubmission = requireNotNull(
                database.stagingDao().getById(writerSubmissionId)
            ) { "Final output writer DOC authority disappeared" }
            val lineageSubmission = requireNotNull(
                database.stagingDao().getById(lineageSubmissionId)
            ) { "Final output component-lineage PLAN_UPDATE authority disappeared" }
            require(
                lineageSubmission.runId == runId &&
                    lineageSubmission.groupId == groupId &&
                    lineageSubmission.agentId == writerAgentId &&
                    lineageSubmission.type ==
                    com.achulkov.evomolior.core.model.SubmissionType.PLAN_UPDATE.name &&
                    lineageSubmission.submittedAtMs == output.componentLineageSubmittedAtMs
            ) {
                "Final output component lineage is not the exact same-writer post-DOC authority"
            }
            FinalComponentLineageOrder.requireAfterDoc(
                docSubmittedAtMs = writerSubmission.submittedAtMs,
                lineageSubmittedAtMs = lineageSubmission.submittedAtMs
            )
            val parsed = FinalComponentLineageV1Codec.parse(
                content = lineageSubmission.content,
                expectedOutputRelativePath = output.relativePath,
                requiredComponentIds = requiredComponentIds,
                availableNodeIds = sourceByNode.keys
            )
            require(parsed == output.componentNodeIds) {
                "Final output component lineage differs from its persisted PLAN_UPDATE authority"
            }
            parsed
        }
        val markerSource = sources.first().authority
        return componentNodeIds.toSortedMap().flatMap { (componentId, selectedNodeIds) ->
            val marker = FinalDeliverableReferenceRecord(
                referenceId = CouncilDeterministicIds.create(
                    "final-deliverable-reference",
                    runId,
                    groupId,
                    output.relativePath,
                    output.contentHash,
                    componentId,
                    "component-marker",
                    markerSource.kind.name,
                    markerSource.authorityId
                ),
                runId = runId,
                groupId = AtlasGroupId(groupId),
                round = round,
                relativePath = output.relativePath,
                mediaType = output.mediaType,
                contentHash = output.contentHash,
                writerAgentId = writerAgentId,
                writerSubmissionId = writerSubmissionId,
                nodeId = null,
                sourceAuthority = markerSource,
                referencedAt = referencedAt,
                componentId = componentId
            ).withComputedHash()
            val nodeReferences = selectedNodeIds.sorted().map { nodeId ->
                val source = requireNotNull(sourceByNode[nodeId]) {
                    "Final component $componentId names unavailable accepted node $nodeId"
                }
                FinalDeliverableReferenceRecord(
                    referenceId = CouncilDeterministicIds.create(
                        "final-deliverable-reference",
                        runId,
                        groupId,
                        output.relativePath,
                        output.contentHash,
                        componentId,
                        nodeId,
                        source.kind.name,
                        source.authorityId
                    ),
                    runId = runId,
                    groupId = AtlasGroupId(groupId),
                    round = round,
                    relativePath = output.relativePath,
                    mediaType = output.mediaType,
                    contentHash = output.contentHash,
                    writerAgentId = writerAgentId,
                    writerSubmissionId = writerSubmissionId,
                    nodeId = nodeId,
                    sourceAuthority = source,
                    referencedAt = referencedAt,
                    componentId = componentId
                ).withComputedHash()
            }
            listOf(marker) + nodeReferences
        }.map { record ->
            repository.recordFinalDeliverableReference(record)
        }
    }

    private suspend fun advancePublicationState(
        runId: String,
        state: PublicationState,
        authorityReferenceIds: Set<String>
    ): PublicationStateEvent {
        val repository = PublicationStateRepository(substrateFactory.getAtlas(runId))
        repository.latest(runId)?.let { latest ->
            if (latest.state == state) {
                require(latest.authorityReferenceIds == authorityReferenceIds) {
                    "Publication state ${state.name} conflicts with persisted authority"
                }
                PublicationOutcomeRepository(substrateFactory.getAtlas(runId))
                    .appendAccepted(latest)
                return latest
            }
            require(latest.state.next() == state) {
                "Publication state cannot advance ${latest.state.name} -> ${state.name}"
            }
        }
        val previous = repository.latest(runId)
        val event = PublicationStateEvent(
            eventId = CouncilDeterministicIds.create(
                "publication-state",
                runId,
                state.name
            ),
            runId = runId,
            state = state,
            sequence = (previous?.sequence ?: -1) + 1,
            previousEventHash = previous?.eventHash,
            authorityReferenceIds = authorityReferenceIds,
            occurredAt = Clock.System.now()
        ).withComputedHash()
        val persisted = repository.append(event)
        PublicationOutcomeRepository(substrateFactory.getAtlas(runId))
            .appendAccepted(persisted)
        return persisted
    }

    /**
     * Publishes the useful, explicitly non-accepted terminal package when strict winner or output
     * authority is absent. This path never invokes the accepted writer, final-reference, metrics,
     * completion-marker, or accepted archive authorities.
     */
    private suspend fun publishProvisionalOutcome(
        config: RunConfig,
        groupIds: List<String>,
        finalRoundIndex: Int,
        winnerId: String?,
        resolvedFinalOutputSpec: FinalOutputSpec?,
        recoveredOutput: GeneratedOutputFile? = null,
        recoveredOutputSource: RecoveredDeliverableSource? = null,
        publicReason: String? = null,
        diagnosticSweepReport: AcceptanceDiagnosticSweepReport? = null,
    ): EvoMoliorEvent.RunRecovered {
        require(groupIds.isNotEmpty()) { "Provisional publication requires eligible groups" }
        val eligibleGroupIds = groupIds.toSortedSet()
        require(eligibleGroupIds.size == groupIds.size) {
            "Provisional publication group identities must be unique"
        }
        val deliverables = DeliverablePersistenceRepository(
            substrateFactory.getAtlas(config.runId)
        )
        val latestSnapshots = eligibleGroupIds.map { groupId ->
            requireNotNull(
                deliverables.snapshot(config.runId, groupId, finalRoundIndex)
            ) {
                "Provisional publication lacks final-round snapshot for $groupId"
            }
        }
        require(latestSnapshots.mapTo(linkedSetOf()) { it.groupId } == eligibleGroupIds) {
            "Provisional publication snapshots do not exactly cover eligible groups"
        }

        val roundHistory = RoundPhaseLedgerRepository(
            substrateFactory.getAtlas(config.runId)
        ).history(config.runId, finalRoundIndex)
        val archivedRound = requireNotNull(roundHistory.lastOrNull()) {
            "Provisional publication requires final-round ledger authority"
        }
        require(RoundPhaseLedgerPolicy.isArchived(roundHistory)) {
            "Provisional publication requires a fully archived final round"
        }
        val postMutationAtlasHash = roundHistory
            .single {
                it.phase ==
                    RoundPhaseV1.MUTATION_CAPTURE_YIELD_DISCLOSURE_AND_OUTLIER_RESOLVED
            }
            .authorityReferenceIds
            .single { it.startsWith(POST_MUTATION_ATLAS_AUTHORITY_PREFIX) }
            .removePrefix(POST_MUTATION_ATLAS_AUTHORITY_PREFIX)
        val atlasFrozenReferences = roundHistory
            .single { it.phase == RoundPhaseV1.ATLAS_MATCHED_FROZEN }
            .authorityReferenceIds
        val roundGraderAtlasHash = atlasFrozenReferences
            .single { it.startsWith(GRADER_ATLAS_AUTHORITY_PREFIX) }
            .removePrefix(GRADER_ATLAS_AUTHORITY_PREFIX)
            .takeUnless { it == NOT_CONFIGURED_AUTHORITY }
        val currentWorkerAtlas = requireNotNull(
            atlasTransactionRepository.currentAtlas(config.runId)
        ) { "Provisional publication requires current worker Atlas authority" }
        val workerAtlas = requireNotNull(
            atlasTransactionRepository
                .versionHistory(config.runId, currentWorkerAtlas.atlasId)
                .singleOrNull { it.atlasHash == postMutationAtlasHash }
        ) {
            "Provisional publication round ledger references an unknown worker Atlas"
        }
        val receiverContract = requireNotNull(
            atlasTransactionRepository.currentReceiverContract(
                config.runId,
                workerAtlas.atlasId
            )
        ) { "Provisional publication requires current receiver contract authority" }
        val expectedBlueprint =
            SealedRoundDeliverableSnapshotCompiler.compileBlueprint(receiverContract)
        val blueprint = requireNotNull(deliverables.blueprint(expectedBlueprint.blueprintId)) {
            "Provisional publication lacks its persisted deliverable blueprint"
        }
        require(blueprint == expectedBlueprint) {
            "Persisted deliverable blueprint differs from the sealed receiver contract"
        }
        require(latestSnapshots.all { it.blueprintHash == blueprint.blueprintHash }) {
            "Provisional publication snapshots disagree on blueprint authority"
        }

        val unresolvedAuthorityRefs = buildSet {
            add("round-ledger:${archivedRound.eventHash}")
            add("worker-atlas:${workerAtlas.atlasHash}")
            roundGraderAtlasHash?.let { add("grader-atlas:$it") }
            if (winnerId == null) add("accepted-winner:unresolved")
            if (resolvedFinalOutputSpec == null) add("accepted-output-contract:unresolved")
            if (winnerId != null && resolvedFinalOutputSpec != null) {
                add("accepted-component-authority:unresolved")
            }
        }
        val firstEvent = advanceRecoveryPublicationState(
            runId = config.runId,
            state = RecoveryPublicationState.WORK_CYCLE_CLOSED_WITHOUT_ACCEPTANCE,
            authorityReferenceIds = unresolvedAuthorityRefs
        )
        val compilation = ProvisionalDecisionPackageCompiler.compile(
            ProvisionalDecisionPackageRequest(
                blueprint = blueprint,
                runId = config.runId,
                eligibleGroupIds = eligibleGroupIds,
                latestSnapshots = latestSnapshots,
                generatedAt = firstEvent.occurredAt
            )
        )
        val sourceSet = compilation.recoverySourceSet
        advanceRecoveryPublicationState(
            runId = config.runId,
            state = RecoveryPublicationState.RECOVERY_SOURCE_SET_SEALED,
            authorityReferenceIds = setOf("recovery-source-set:${sourceSet.sourceSetHash}")
        )
        val decisionPackage = deliverables.appendDecisionPackage(compilation.decisionPackage)
        advanceRecoveryPublicationState(
            runId = config.runId,
            state = RecoveryPublicationState.PROVISIONAL_PACKAGE_ASSEMBLED,
            authorityReferenceIds = setOf("decision-package:${decisionPackage.packageHash}")
        )
        require(
            decisionPackage.hasValidHashes() &&
                decisionPackage.acceptedAuthority == null &&
                decisionPackage.runId == config.runId
        ) { "Provisional decision package validation failed" }
        advanceRecoveryPublicationState(
            runId = config.runId,
            state = RecoveryPublicationState.PROVISIONAL_PACKAGE_VALIDATED,
            authorityReferenceIds = setOf(
                "validated-decision-package:${decisionPackage.packageHash}",
                "deliverable-blueprint:${blueprint.blueprintHash}"
            )
        )

        groupIds.sorted().forEach { groupId ->
            requireNotNull(liveLifecycleManagers[groupId]) {
                "Recovery publication lacks lifecycle manager for $groupId"
            }.terminateAll()
        }
        val lifecycleHash = provisionalLifecycleReconciliationHash(
            runId = config.runId,
            groupIds = groupIds
        )
        val durableArchive = archiveService ?: RunArchiveService(substrateFactory)
        val admissionDistributionHash = startupDistributionManifestHash()
        val acceptanceAdmissionBytes = durableArchive.acceptanceAdmissionBytesForOutcome(
            runId = config.runId,
            acceptanceProfile = config.acceptanceProfile,
            distributionManifestSha256 = admissionDistributionHash,
        )
        val startingField = requireNotNull(loadSealedStartingFieldSnapshot(config.runId)) {
            "Provisional publication requires sealed StartingField authority"
        }
        val closureRuntimeInput = RunClosureMetricRuntimeInput(
            lifecycle = terminalLifecycleClosureMeasurements(
                runId = config.runId,
                groupIds = groupIds,
            ),
            toolTransport = toolTransportClosureMeasurementProvider(config.runId),
        )
        val metrics = durableArchive.compileProvisionalRunMetrics(
            runId = config.runId,
            groupIds = groupIds,
            startingFieldJson = runConfigJson.encodeToString(
                StartingField.serializer(),
                startingField,
            ),
            finalGeneration = finalRoundIndex + 1,
            requestedOutputSpec = resolvedFinalOutputSpec,
            closureRuntimeInput = closureRuntimeInput,
        )
        advanceRecoveryPublicationState(
            runId = config.runId,
            state = RecoveryPublicationState.RECOVERY_COST_AND_EXIT_RECONCILED,
            authorityReferenceIds = setOf(
                "lifecycle-reconciliation:$lifecycleHash",
                "run-metrics-v1:${metrics.sha256}",
            )
        )
        val manifestInputsHash = AtlasHashing.hashText(
            listOf(
                config.runId,
                admissionDistributionHash,
                acceptanceAdmissionBytes?.let(::sha256Hex).orEmpty(),
                blueprint.blueprintHash,
                decisionPackage.packageHash,
                sourceSet.sourceSetHash,
                lifecycleHash,
                metrics.sha256,
            ).joinToString("|")
        )
        advanceRecoveryPublicationState(
            runId = config.runId,
            state = RecoveryPublicationState.RECOVERY_MANIFEST_HASHED,
            authorityReferenceIds = setOf("recovery-manifest-inputs:$manifestInputsHash")
        )

        val outcomeRepository = PublicationOutcomeRepository(
            substrateFactory.getAtlas(config.runId)
        )
        val persistedHistory = outcomeRepository.recoveryHistory(config.runId)
        val archivedEvent = nextRecoveryPublicationEvent(
            runId = config.runId,
            state = RecoveryPublicationState.ARCHIVED_RECOVERED,
            previous = requireNotNull(persistedHistory.lastOrNull()),
            authorityReferenceIds = setOf(
                "recovery-archive:recovery-results.zip",
                "decision-package:${decisionPackage.packageHash}"
            )
        )
        val completeHistory = persistedHistory + archivedEvent
        val mechanismActivationReport = Increment9ObservabilityService(substrateFactory)
            .snapshot(config.runId, groupIds, workerAtlas.atlasId)
            .toTerminalMechanismReport(
                outcomeClass =
                    com.achulkov.evomolior.core.deliverable.RunOutcomeClass
                        .PROVISIONAL_DECISION_PACKAGE,
                generatedAt = archivedEvent.occurredAt,
                terminalPublicationAuthorityId =
                    "recovery-archive-event/${archivedEvent.eventHash}",
            )
        val terminalOutcome = buildProvisionalTerminalOutcome(
            runId = config.runId,
            recoverySourceGroupId = recoveredOutput?.sourceGroupId
                ?: sourceSet.sourceGroupIds.firstOrNull(),
            recoveredOutput = recoveredOutput,
            failureClass = diagnosticSweepReport?.let {
                RunRecoveryFailureClass.DIAGNOSTIC_SWEEP
            } ?: RunRecoveryFailureClass.RUN_AUTHORITY_REJECTED,
            decisionPackageHash = decisionPackage.packageHash,
        )
        val archive = RunOutcomeArchiveService().archiveProvisional(
            ProvisionalOutcomeArchiveRequest(
                sourceReportInputs = com.achulkov.evomolior.agents.tools.ReportSourceInputs.load(
                    startingField.inputArtifacts, resolveRunArtifactsRoot(config.runId).absolutePath),
                runId = config.runId,
                admissionDistributionHash = admissionDistributionHash,
                acceptanceProfile = config.acceptanceProfile,
                acceptanceAdmissionBytes = acceptanceAdmissionBytes,
                startingFieldHash = CouncilContractHashing.hashStartingField(startingField),
                workerAtlasHash = workerAtlas.atlasHash,
                graderAtlasHash = roundGraderAtlasHash,
                sealedTestSuiteHash = activeSealedTestSuiteHash.takeIf(String::isNotBlank),
                lifecycleReconciliationHash = lifecycleHash,
                metricsHash = metrics.sha256,
                metricsBytes = metrics.bytes,
                blueprint = blueprint,
                decisionPackage = decisionPackage,
                recoverySourceSets = listOf(sourceSet),
                eligibleGroupIds = eligibleGroupIds,
                recoveryPublicationHistory = completeHistory,
                recoveredOutput = recoveredOutput,
                recoveredOutputSource = recoveredOutputSource,
                recoveredOutputRoundIndex = finalRoundIndex,
                diagnosticSweepReport = diagnosticSweepReport,
                mechanismActivationReport = mechanismActivationReport,
                terminalOutcome = terminalOutcome,
                archivedAtMs = archivedEvent.occurredAt.toEpochMilliseconds()
            )
        )
        durableArchive.writeOutcomeArchive(config.runId, archive)
        outcomeRepository.appendRecovery(archivedEvent)
        require(outcomeRepository.recoveryHistory(config.runId) == completeHistory) {
            "Recovery publication history readback differs from archived authority"
        }
        emit(
            EvoMoliorEvent.EngineProgress(
                config.runId,
                "provisional_decision_package",
                "Archived a non-accepted package with ${eligibleGroupIds.size} neutral sources"
            )
        )
        return EvoMoliorEvent.RunRecovered(
            runId = config.runId,
            sourceGroupId = null,
            recoveredOutputPaths = buildList {
                add("results/recovery-results.zip")
                add("provisional-decision-package.json")
                recoveredOutput?.let {
                    add("results/outputs/recovered/${it.relativePath}")
                }
            },
            reason = publicReason ?: buildString {
                append("Strict accepted publication authority was unavailable")
                if (winnerId == null) append(": winner unresolved")
                if (resolvedFinalOutputSpec == null) append(": output contract unresolved")
            },
            terminalOutcome = terminalOutcome,
        )
    }

    private suspend fun loadFinalDeliverablePackageInputs(
        runId: String,
        winnerGroupId: String,
        finalRoundIndex: Int
    ): FinalDeliverablePackageInputs {
        val workerAtlas = requireNotNull(atlasTransactionRepository.currentAtlas(runId)) {
            "Final deliverable package requires current worker Atlas authority"
        }
        val receiverContract = requireNotNull(
            atlasTransactionRepository.currentReceiverContract(runId, workerAtlas.atlasId)
        ) { "Final deliverable package requires current receiver contract authority" }
        val expectedBlueprint =
            SealedRoundDeliverableSnapshotCompiler.compileBlueprint(receiverContract)
        requireCouncilSealedDeliverableBlueprint(
            runId = runId,
            blueprint = expectedBlueprint,
            requireSeal = true,
        )
        val repository = DeliverablePersistenceRepository(substrateFactory.getAtlas(runId))
        val blueprint = requireNotNull(repository.blueprint(expectedBlueprint.blueprintId)) {
            "Final deliverable package lacks persisted blueprint authority"
        }
        require(blueprint == expectedBlueprint) {
            "Final deliverable blueprint differs from the sealed receiver contract"
        }
        val snapshot = requireNotNull(
            repository.snapshot(runId, winnerGroupId, finalRoundIndex)
        ) { "Final deliverable package lacks the winner's final-round snapshot" }
        require(
            snapshot.runId == runId &&
                snapshot.groupId == winnerGroupId &&
                snapshot.blueprintHash == blueprint.blueprintHash &&
                snapshot.hasValidHash()
        ) { "Final winner snapshot is invalid or foreign" }
        return FinalDeliverablePackageInputs(blueprint, snapshot)
    }

    private suspend fun publishAcceptedOutcome(
        config: RunConfig,
        groupIds: List<String>,
        winnerId: String,
        finalRoundIndex: Int,
        generatedOutput: GeneratedOutputFile,
        completionRecord: RunCompletionRecord,
        packageInputs: FinalDeliverablePackageInputs
    ) {
        val archive = archiveService ?: RunArchiveService(substrateFactory)
        val admissionDistributionHash = startupDistributionManifestHash()
        val acceptanceAdmissionBytes = archive.acceptanceAdmissionBytesForOutcome(
            runId = config.runId,
            acceptanceProfile = config.acceptanceProfile,
            distributionManifestSha256 = admissionDistributionHash,
        )
        val startingField = requireNotNull(loadSealedStartingFieldSnapshot(config.runId)) {
            "Accepted outcome requires sealed StartingField authority"
        }
        val startingFieldJson = runConfigJson.encodeToString(
            StartingField.serializer(),
            startingField
        )
        val exactVerdict = requireNotNull(exactGraderVerdictsByRound[finalRoundIndex]) {
            "Accepted outcome requires exact final-round grader authority"
        }
        require(exactVerdict.status == ExactGraderRoundStatus.VALID) {
            "Accepted outcome requires a valid exact final-round grader verdict"
        }
        val winnerAuthorityHash = AtlasHashing.hashText(
            runConfigJson.encodeToString(
                com.achulkov.evomolior.competition.grader.ExactGraderRoundVerdict.serializer(),
                exactVerdict
            )
        )
        val selectedNodeIds = generatedOutput.componentNodeIds.values
            .flatten()
            .toSet()
        require(selectedNodeIds.isNotEmpty()) {
            "Accepted outcome requires exact selected source nodes"
        }
        val finalReferences = ContributionGraphRepository(
            database = substrateFactory.get(config.runId, winnerId),
            atlasDatabase = substrateFactory.getAtlas(config.runId)
        ).finalDeliverableReferencesForRun(config.runId, winnerId)
            .filter {
                it.round == finalRoundIndex &&
                    it.contentHash == generatedOutput.contentHash &&
                    it.writerAgentId == generatedOutput.sourceAgentId &&
                    it.writerSubmissionId == generatedOutput.sourceSubmissionId
            }
        require(
            finalReferences.isNotEmpty() &&
                finalReferences.filter { it.nodeId != null }
                    .mapTo(linkedSetOf()) { requireNotNull(it.nodeId) } == selectedNodeIds &&
                finalReferences.all { it.hasValidHash() }
        ) { "Accepted outcome final references do not match the exact writer source selection" }
        val acceptedAuthority = AcceptedDeliverableAuthority(
            winnerAuthorityId = "exact-grader-${winnerAuthorityHash.take(24)}",
            winnerAuthorityHash = winnerAuthorityHash,
            sourceAuthorityIds = selectedNodeIds,
            writerAuthorityId = requireNotNull(generatedOutput.sourceAgentId),
            writerOutputHash = generatedOutput.contentHash
        )
        val publicationHistoryBeforePackage = PublicationStateRepository(
            substrateFactory.getAtlas(config.runId)
        ).history(config.runId)
        require(
            PublicationOutcomeRepository(substrateFactory.getAtlas(config.runId))
                .acceptedHistory(config.runId) == publicationHistoryBeforePackage
        ) { "Accepted outcome history differs from the strict publication barrier" }
        val generatedAt = requireNotNull(publicationHistoryBeforePackage.lastOrNull())
            .occurredAt
        val decisionPackage = AcceptedDecisionPackageCompiler.compile(
            AcceptedDecisionPackageRequest(
                blueprint = packageInputs.blueprint,
                runId = config.runId,
                winnerGroupId = winnerId,
                finalWinnerSnapshot = packageInputs.snapshot,
                acceptedAuthority = acceptedAuthority,
                generatedOutput = generatedOutput,
                generatedAt = generatedAt
            )
        )
        val closureRuntimeInput = RunClosureMetricRuntimeInput(
            lifecycle = terminalLifecycleClosureMeasurements(
                runId = config.runId,
                groupIds = groupIds,
            ),
            toolTransport = toolTransportClosureMeasurementProvider(config.runId),
        )
        val metrics = archive.compileAcceptedRunMetrics(
            runId = config.runId,
            groupIds = groupIds,
            startingFieldJson = startingFieldJson,
            finalGeneration = completionRecord.completedRoundCount,
            requestedOutputSpec = completionRecord.requestedOutputSpec,
            completionRecord = completionRecord,
            acceptedDecisionPackage = decisionPackage,
            closureRuntimeInput = closureRuntimeInput,
        )
        val metricsEvent = nextAcceptedPublicationEvent(
            runId = config.runId,
            state = PublicationState.METRICS_EXPORTED,
            previous = requireNotNull(publicationHistoryBeforePackage.lastOrNull()),
            authorityReferenceIds = setOf("run-metrics-v1:${metrics.sha256}")
        )
        val lifecycleHash = provisionalLifecycleReconciliationHash(
            runId = config.runId,
            groupIds = groupIds
        )
        val manifestInputHash = AtlasHashing.hashText(
            listOf(
                config.runId,
                decisionPackage.packageHash,
                metrics.sha256,
                lifecycleHash,
                packageInputs.blueprint.blueprintHash,
                acceptanceAdmissionBytes?.let(::sha256Hex).orEmpty(),
            ).joinToString("|")
        )
        val manifestEvent = nextAcceptedPublicationEvent(
            runId = config.runId,
            state = PublicationState.MANIFEST_HASHED,
            previous = metricsEvent,
            authorityReferenceIds = setOf(
                "accepted-manifest-inputs:$manifestInputHash"
            )
        )
        val archivedEvent = nextAcceptedPublicationEvent(
            runId = config.runId,
            state = PublicationState.ARCHIVED,
            previous = manifestEvent,
            authorityReferenceIds = setOf(
                "accepted-archive:results.zip",
                "decision-package:${decisionPackage.packageHash}"
            )
        )
        val completeHistory =
            publicationHistoryBeforePackage + metricsEvent + manifestEvent + archivedEvent
        val publicationHistoryHash = AtlasHashing.hashText(
            runConfigJson.encodeToString(
                ListSerializer(PublicationStateEvent.serializer()),
                completeHistory
            )
        )
        val workerAtlas = requireNotNull(
            atlasTransactionRepository.currentAtlas(config.runId)
        )
        val graderAtlas = requireNotNull(
            graderAuthorityRepository.currentAtlas(config.runId)
        ) { "Accepted outcome requires current Grader Atlas authority" }
        val mechanismActivationReport = Increment9ObservabilityService(substrateFactory)
            .snapshot(config.runId, groupIds, workerAtlas.atlasId)
            .toTerminalMechanismReport(
                outcomeClass =
                    com.achulkov.evomolior.core.deliverable.RunOutcomeClass
                        .ACCEPTED_DECISION_PACKAGE,
                generatedAt = archivedEvent.occurredAt,
                terminalPublicationAuthorityId =
                    "accepted-archive-event/${archivedEvent.eventHash}",
            )
        val persistedDecisionPackage = DeliverablePersistenceRepository(
            substrateFactory.getAtlas(config.runId)
        ).appendDecisionPackage(decisionPackage)
        require(persistedDecisionPackage == decisionPackage) {
            "Persisted accepted semantic package differs from compiled authority"
        }
        val outcomeArchive = archive.buildOutcomeAwareAcceptedBundle(
            AcceptedOutcomeArchiveRequest(
                sourceReportInputs = com.achulkov.evomolior.agents.tools.ReportSourceInputs.load(
                    startingField.inputArtifacts, resolveRunArtifactsRoot(config.runId).absolutePath),
                runId = config.runId,
                objective = config.objective,
                groupCount = startingField.groupCount,
                generationCeiling = startingField.generationCeiling,
                admissionDistributionHash = admissionDistributionHash,
                acceptanceAdmissionBytes = acceptanceAdmissionBytes,
                startingFieldHash = CouncilContractHashing.hashStartingField(startingField),
                workerAtlasHash = workerAtlas.atlasHash,
                graderAtlasHash = graderAtlas.atlasHash,
                sealedTestSuiteHash = activeSealedTestSuiteHash
                    .takeIf(String::isNotBlank)
                    ?: error("Accepted outcome requires sealed test-suite authority"),
                lifecycleReconciliationHash = lifecycleHash,
                metricsHash = metrics.sha256,
                metricsBytes = metrics.bytes,
                publicationHistoryHash = publicationHistoryHash,
                publicationHistory = completeHistory,
                blueprint = packageInputs.blueprint,
                decisionPackage = persistedDecisionPackage,
                mechanismActivationReport = mechanismActivationReport,
                councilExecutionPurpose = config.councilExecutionPurpose,
                acceptanceProfile = config.acceptanceProfile
            )
        )
        archive.writeOutcomeArchive(config.runId, outcomeArchive)
        archive.persistCompiledRunMetrics(metrics)
        val publicationRepository = PublicationStateRepository(
            substrateFactory.getAtlas(config.runId)
        )
        publicationRepository.append(metricsEvent)
        publicationRepository.append(manifestEvent)
        publicationRepository.append(archivedEvent)
        val outcomeRepository = PublicationOutcomeRepository(
            substrateFactory.getAtlas(config.runId)
        )
        outcomeRepository.appendAccepted(metricsEvent)
        outcomeRepository.appendAccepted(manifestEvent)
        outcomeRepository.appendAccepted(archivedEvent)
        require(publicationRepository.history(config.runId) == completeHistory) {
            "Accepted publication history readback differs from archived authority"
        }
        require(outcomeRepository.acceptedHistory(config.runId) == completeHistory) {
            "Accepted outcome history readback differs from archived authority"
        }
        runFinalOutputSpecs.remove(config.runId)
    }

    private suspend fun terminalLifecycleClosureMeasurements(
        runId: String,
        groupIds: List<String>,
    ): LifecycleClosureMeasurements {
        val rows = groupIds.distinct().sorted().map { groupId ->
            val manager = requireNotNull(liveLifecycleManagers[groupId]) {
                "Accepted publication lacks lifecycle manager for $groupId"
            }
            val accounting = manager.publicationAccounting()
            require(accounting.isQuiescent) {
                "Accepted publication lacks quiescent lifecycle accounting for $groupId"
            }
            val metrics = manager.lifecycleReconciliationMetrics()
            require(metrics.unsettledCostReservationCount == 0) {
                "Accepted publication has unsettled lifecycle reservations for $groupId"
            }
            groupId to metrics
        }
        return LifecycleClosureMeasurements(
            exitIdentityConflicts = rows.sumOf { it.second.exitIdentityConflictCount },
            exitFinalizationRetries = rows.sumOf { it.second.exitFinalizationRetryCount },
            evidenceRefs = rows.map { (groupId, metrics) ->
                "lifecycle-reconciliation/$groupId/" + AtlasHashing.hashText(
                    listOf(
                        runId,
                        groupId,
                        metrics.exitIdentityConflictCount,
                        metrics.exitFinalizationRetryCount,
                        metrics.registeredWithoutExitCount,
                        metrics.exitWithoutRegisteredAgentCount,
                        metrics.liveExecutionAfterArchiveBarrier,
                        metrics.deathCallbackDuplicateCount,
                        metrics.unsettledCostReservationCount,
                        metrics.maxExitToQuiescenceMs ?: "unavailable",
                    ).joinToString("|")
                )
            },
        )
    }

    private fun nextAcceptedPublicationEvent(
        runId: String,
        state: PublicationState,
        previous: PublicationStateEvent,
        authorityReferenceIds: Set<String>
    ): PublicationStateEvent = PublicationStateEvent(
        eventId = CouncilDeterministicIds.create(
            "publication-state",
            runId,
            state.name
        ),
        runId = runId,
        state = state,
        sequence = previous.sequence + 1,
        previousEventHash = previous.eventHash,
        authorityReferenceIds = authorityReferenceIds,
        occurredAt = Clock.System.now()
    ).withComputedHash()

    private suspend fun advanceRecoveryPublicationState(
        runId: String,
        state: RecoveryPublicationState,
        authorityReferenceIds: Set<String>
    ): RecoveryPublicationStateEvent {
        val repository = PublicationOutcomeRepository(substrateFactory.getAtlas(runId))
        val history = repository.recoveryHistory(runId)
        history.singleOrNull { it.state == state }?.let { existing ->
            require(existing.authorityReferenceIds == authorityReferenceIds) {
                "Recovery publication state ${state.name} conflicts with persisted authority"
            }
            return existing
        }
        val event = nextRecoveryPublicationEvent(
            runId = runId,
            state = state,
            previous = history.lastOrNull(),
            authorityReferenceIds = authorityReferenceIds
        )
        return repository.appendRecovery(event)
    }

    private fun nextRecoveryPublicationEvent(
        runId: String,
        state: RecoveryPublicationState,
        previous: RecoveryPublicationStateEvent?,
        authorityReferenceIds: Set<String>
    ): RecoveryPublicationStateEvent {
        require(authorityReferenceIds.isNotEmpty() && authorityReferenceIds.none(String::isBlank)) {
            "Recovery publication state requires explicit authority"
        }
        return RecoveryPublicationStateEvent(
            eventId = CouncilDeterministicIds.create(
                "recovery-publication-state",
                runId,
                state.name
            ),
            runId = runId,
            state = state,
            sequence = (previous?.sequence ?: -1) + 1,
            previousEventHash = previous?.eventHash,
            authorityReferenceIds = authorityReferenceIds,
            occurredAt = Clock.System.now()
        ).withComputedHash()
    }

    private suspend fun provisionalLifecycleReconciliationHash(
        runId: String,
        groupIds: List<String>
    ): String {
        requirePublicationQuiescence(
            groupIds = groupIds,
            publicationClass = "Recovery publication",
        )
        val rows = groupIds.sorted().map { groupId ->
            val manager = liveLifecycleManagers.getValue(groupId)
            val metrics = manager.lifecycleReconciliationMetrics()
            require(metrics.unsettledCostReservationCount == 0) {
                "Recovery publication blocked by unsettled lifecycle reservations for $groupId"
            }
            listOf(
                groupId,
                metrics.exitIdentityConflictCount,
                metrics.exitFinalizationRetryCount,
                metrics.registeredWithoutExitCount,
                metrics.exitWithoutRegisteredAgentCount,
                metrics.liveExecutionAfterArchiveBarrier,
                metrics.deathCallbackDuplicateCount,
                metrics.unsettledCostReservationCount,
                metrics.maxExitToQuiescenceMs ?: "unavailable"
            ).joinToString("|")
        }
        val costRepository = CostLedgerRepository(
            substrateFactory.get(runId, COST_LEDGER_GROUP_ID)
        )
        val costClosure = costRepository.closeForPublication(runId)
        val costSummary = costRepository.summary(runId)
        return AtlasHashing.hashText(
            buildString {
                rows.forEach { append(it).append('\n') }
                append(costClosure.entryHash).append('\n')
                append(runConfigJson.encodeToString(
                    com.achulkov.evomolior.core.contract.CostLedgerSummary.serializer(),
                    costSummary
                ))
            }
        )
    }

    private suspend fun finalizePublicationQuiescence(
        runId: String,
        groupIds: List<String>,
        generatedOutputFile: GeneratedOutputFile
    ) {
        val deadline = System.currentTimeMillis() + PUBLICATION_QUIESCENCE_TIMEOUT_MS
        while (
            System.currentTimeMillis() < deadline &&
            liveLifecycleManagers.values.any {
                it.getLiveAgentCount() > 0 || it.getLiveNestedGroupCount() > 0
            }
        ) {
            delay(250L)
        }
        liveLifecycleManagers.values.forEach {
            it.terminateAll()
            it.awaitQuiescence(PUBLICATION_QUIESCENCE_TIMEOUT_MS)
        }

        val costRepository = CostLedgerRepository(
            substrateFactory.get(runId, COST_LEDGER_GROUP_ID),
            costLedgerPolicy(runId)
        )
        val costClosure = costRepository.closeForPublication(runId)
        val costSummary = costRepository.summary(runId)
        advancePublicationState(
            runId = runId,
            state = PublicationState.COST_FINALIZED,
            authorityReferenceIds = setOf(
                "cost-ledger:$runId:${costSummary.finalizedEntryCount}:" +
                    "${costSummary.unknownMonetaryEntryCount}:${costClosure.entryHash}"
            )
        )

        val sourceGroupId = requireNotNull(generatedOutputFile.sourceGroupId) {
            "Final output lacks source group authority"
        }
        val sourceAgentId = requireNotNull(generatedOutputFile.sourceAgentId) {
            "Final output lacks source agent authority"
        }
        val stagingDao = substrateFactory.get(runId, sourceGroupId).stagingDao()
        val deathNote = stagingDao
            .getByAgent(runId, sourceGroupId, sourceAgentId)
            .firstOrNull { it.type == com.achulkov.evomolior.core.model.SubmissionType.DEATH_NOTE.name }
        requireNotNull(deathNote) {
            "Publication blocked because final writer $sourceAgentId has no Death Note"
        }
        generatedOutputFile.componentLineageSubmissionId?.let { lineageSubmissionId ->
            val lineage = requireNotNull(stagingDao.getById(lineageSubmissionId)) {
                "Publication blocked because final component lineage authority disappeared"
            }
            require(
                lineage.agentId == sourceAgentId &&
                    lineage.type ==
                    com.achulkov.evomolior.core.model.SubmissionType.PLAN_UPDATE.name &&
                    lineage.submittedAtMs ==
                    generatedOutputFile.componentLineageSubmittedAtMs
            ) {
                "Publication requires the final writer's component lineage PLAN_UPDATE before its Death Note"
            }
            FinalComponentLineageOrder.requireBeforeDeathNote(
                lineageSubmittedAtMs = lineage.submittedAtMs,
                deathNoteSubmittedAtMs = deathNote.submittedAtMs
            )
        }
        advancePublicationState(
            runId = runId,
            state = PublicationState.DEATH_NOTE_FINALIZED,
            authorityReferenceIds = setOf(
                "death-note:${deathNote.id}:${AtlasHashing.hashText(deathNote.content)}"
            )
        )

        val accounting = requirePublicationQuiescence(
            groupIds = groupIds,
            publicationClass = "Accepted publication",
        )
        advancePublicationState(
            runId = runId,
            state = PublicationState.ALL_NON_DAEMON_AGENTS_TERMINATED,
            authorityReferenceIds = accounting.flatMapTo(linkedSetOf()) { (groupId, value) ->
                value.registeredNonDaemonAgentIds.map { agentId ->
                    "exit:$runId:$groupId:$agentId"
                }
            }.ifEmpty { setOf("no-non-daemon-agents:$runId") }
        )
    }

    /**
     * Requires actual runtime-cell settlement before accepted or recovery publication advances.
     *
     * @param groupIds Exact worker groups whose ordinary executions must be quiescent.
     * @param publicationClass Public diagnostic label for the attempted outcome class.
     * @param timeoutMillis Finite allowance for trailing runner and ledger finalization.
     * @return Exact publication accounting used by the caller's terminal authority.
     */
    internal suspend fun requirePublicationQuiescence(
        groupIds: List<String>,
        publicationClass: String,
        timeoutMillis: Long = PUBLICATION_QUIESCENCE_TIMEOUT_MS,
    ): Map<String, com.achulkov.evomolior.orchestration.lifecycle.AgentPublicationAccounting> {
        require(groupIds.isNotEmpty() && groupIds.distinct().size == groupIds.size) {
            "$publicationClass requires unique worker group identities"
        }
        val accounting = groupIds.sorted().associateWith { groupId ->
            val manager = requireNotNull(liveLifecycleManagers[groupId]) {
                "$publicationClass lacks lifecycle manager for $groupId"
            }
            try {
                manager.awaitQuiescence(timeoutMillis)
            } catch (timeout: TimeoutCancellationException) {
                if (!currentCoroutineContext().isActive) throw timeout
                throw IllegalArgumentException(
                    "$publicationClass blocked by non-quiescent runtime-cell accounting " +
                        "after ${timeoutMillis}ms: ${manager.publicationAccounting()}",
                    timeout,
                )
            }
        }
        val invalid = accounting.filterValues { !it.isQuiescent }
        require(invalid.isEmpty()) {
            "$publicationClass blocked by non-quiescent runtime-cell accounting: $invalid"
        }
        return accounting
    }

    /**
     * Spawns agents for all groups at the start of a round.
     *
     * Uses [EvolutionarySelector] to compute a role allocation for each group based on
     * substrate signals (KB density, staging backlog, tool failure rate, etc.), then calls
     * [LifecycleManager.spawnGeneration] to actually spin up the VMs.
     *
     * Failures are caught per-group so one broken group does not abort the whole round.
     */
    private suspend fun spawnAgentsForRound(
        config: RunConfig,
        groupIds: List<String>,
        round: Int,
        nodeSpawnCreditsByGroup: Map<String, Double>
    ) {
        for (groupId in groupIds) {
            try {
                val lm = liveLifecycleManagers[groupId]
                if (lm == null) {
                    Napier.w("EvoMoliorRunService: no LifecycleManager for group $groupId — skipping spawn")
                    continue
                }
                val db = substrateFactory.get(config.runId, groupId)
                val groupProfileType = activeGroupProfiles.getOrNull(groupIds.indexOf(groupId))
                    ?: activeGroupProfiles.firstOrNull()
                if (groupProfileType == null) {
                    Napier.w("EvoMoliorRunService: no GroupProfile for group $groupId")
                    continue
                }
                val profile = GroupProfile.default(groupProfileType)
                val nodeSpawnCredits = nodeSpawnCreditsByGroup.getOrDefault(groupId, 0.0)
                val observedLiveRemainingBudget = groupSummaries.firstOrNull { it.groupId == groupId }?.remainingBudget
                val budgetCeiling = computeGenerationSpawnBudgetCeiling(
                    profile = profile,
                    maxAgentsPerGroup = config.maxAgentsPerGroup,
                    observedLiveRemainingBudget = observedLiveRemainingBudget,
                    nodeSpawnCredits = nodeSpawnCredits,
                    maximumCreditAllocation = config.budgetOverride
                        ?.div(groupIds.size)
                        ?.div(config.rounds),
                )
                activeGenerationBudgetCeilings[groupId] = budgetCeiling
                activeGenerationBudgetRounds[groupId] = round
                activeWatchdog?.beginGenerationBudget(groupId, budgetCeiling)
                if (round > 0 && (observedLiveRemainingBudget ?: 0.0) <= 0.0 && budgetCeiling > 0.0) {
                    // Expected every round after the first: live balances reach zero once all agents
                    // exit (see computeGenerationSpawnBudgetCeiling), so this is not a warning.
                    Napier.i(
                        "EvoMoliorRunService: group=$groupId round=$round live remaining budget " +
                            "was ${observedLiveRemainingBudget ?: 0.0}; using configured generation " +
                            "spawn budget $budgetCeiling to preserve multi-round compounding"
                    )
                    emit(
                        EvoMoliorEvent.EngineProgress(
                            config.runId,
                            "budget_replenished_for_generation",
                            "[$groupId] Live budget telemetry was depleted; using generation spawn budget $budgetCeiling"
                        )
                    )
                }

                Napier.i("EvoMoliorRunService: computing role allocation for group=$groupId round=$round budgetCeiling=$budgetCeiling nodeSpawnCredits=$nodeSpawnCredits")
                emit(EvoMoliorEvent.EngineProgress(config.runId, "spawning", "[$groupId] Computing role allocation for round $round"))

                // B-6: Enforce 15% inter-group migration ceiling based on current KB size.
                // Must be called before agents spawn so the LeakOracle has the ceiling for this round.
                val coordinator = activeCoordinator
                if (coordinator != null) {
                    try {
                        val kbCount = db.knowledgeBaseDao().countActive(config.runId, groupId)
                        coordinator.updateMigrationCeiling(groupId, kbCount)
                    } catch (e: Exception) {
                        Napier.w("EvoMoliorRunService: migration ceiling update failed for $groupId: ${e.message}")
                    }
                }

                val deliverableTreeSummary = deliverableSkeletonTreeStore.readSummary(config.runId, groupId)
                val selector = EvolutionarySelector(
                    db = db,
                    profile = profile,
                    runId = config.runId,
                    groupId = groupId,
                    deliverableTreeSummary = deliverableTreeSummary
                )
                val stasis = stasisFlags[groupId] ?: false
                val allocation = selector.computeAllocation(
                    generation = round,
                    globalBudgetCeiling = budgetCeiling,
                    stasisDetected = stasis,
                    formalDispatchDue = !config.isTestRun &&
                        groupIds.size > 1 &&
                        round % activeDispatchCadenceGenerations == 0,
                    artifactWorkRequired = resolveFinalOutputSpec(config) != null,
                    maximumAgents = config.maxAgentsPerGroup,
                )
                // Reset stasis flag after it has been consumed for this round's allocation
                stasisFlags.remove(groupId)

                Napier.i("EvoMoliorRunService: allocation for group=$groupId round=$round: ${allocation.totalAgentCount} agents — ${allocation.rationale}")
                emit(EvoMoliorEvent.EngineProgress(
                    config.runId, "spawning",
                    "[$groupId] Spawning ${allocation.totalAgentCount} agents for round $round: " +
                        allocation.entries.joinToString { "${it.count}×${it.role.name}" }
                ))

                val stageDisclosure = if (activeWorkerAtlasId != null) {
                    // Compiled workers receive only hash-verified revealed node cards.
                    ""
                } else try {
                    activeDisclosureEngine?.currentStage(groupId)?.constraintFramedContent ?: ""
                } catch (_: Exception) { "" }
                val discriminatorAwareDisclosure = composeStageDisclosureWithDiscriminatorFeedback(
                    runId = config.runId,
                    groupId = groupId,
                    baseDisclosure = stageDisclosure
                )

                lm.spawnGeneration(
                    allocation = allocation,
                    stageDisclosure = discriminatorAwareDisclosure,
                    modelAssignments = activeModelAssignments, // Genesis Council assignments (empty = use LifecycleManager defaults)
                    modelPools = activeModelPools,
                    rolePrompts = activeRolePrompts,           // Genesis Council role prompts (empty = use defaults)
                    scope = scope
                )

                Napier.i("EvoMoliorRunService: spawn complete for group=$groupId round=$round")
                emit(EvoMoliorEvent.EngineProgress(
                    config.runId, "spawning",
                    "[$groupId] All agents spawned for round $round"
                ))
            } catch (e: Exception) {
                Napier.e("EvoMoliorRunService: spawn failed for group $groupId round $round: ${e.message}", e)
                emit(EvoMoliorEvent.EngineProgress(
                    config.runId, "spawn_error",
                    "[$groupId] Spawn error round $round: ${e.message}"
                ))
            }
        }
    }

    internal suspend fun applyDueNodeYields(
        runId: String,
        groupIds: List<String>,
        round: Int
    ): Map<String, Double> {
        val atlasId = activeWorkerAtlasId ?: return emptyMap()
        val authority = AtlasNodeYieldAuthority(
            runId = runId,
            atlasId = atlasId,
            atlasRepository = atlasTransactionRepository,
            captureRepository = nodeCaptureRepository
        )
        return groupIds.associateWith { groupId ->
            var spawnCredits = 0.0
            authority.applyDue(AtlasGroupId(groupId), round).forEach { applied ->
                applied.application.appliedRewards.forEach { reward ->
                    val spend = nodeCaptureRepository.appendRewardUseEvent(
                        RewardUseLineageEvent(
                            eventId = "reward-spend:${applied.application.applicationHash}:" +
                                reward.rewardType.name,
                            runId = runId,
                            groupId = applied.application.groupId,
                            nodeId = applied.application.nodeId,
                            captureDecisionHash = applied.schedule.captureDecisionHash,
                            yieldScheduleHash = applied.schedule.scheduleHash,
                            yieldApplicationHash = applied.application.applicationHash,
                            rewardType = reward.rewardType,
                            amountSpent = reward.amount,
                            spentRound = round
                        ).withComputedHash()
                    )
                    rewardSpendLineageByGroupRound
                        .computeIfAbsent(groupId to round) { ConcurrentLinkedQueue() }
                        .add(spend)
                    appendCompoundingRewardSpend(
                        runId = runId,
                        groupId = groupId,
                        round = round,
                        spend = spend,
                    )
                    if (reward.rewardType == InstrumentalRewardType.SPAWN_CREDITS) {
                        spawnCredits += reward.amount
                    }
                }
            }
            spawnCredits
        }
    }

    private suspend fun appendCompoundingRewardSpend(
        runId: String,
        groupId: String,
        round: Int,
        spend: RewardUseLineageEvent,
    ) {
        if (
            spend.rewardType == InstrumentalRewardType.SPAWN_CREDITS ||
            spend.amountSpent <= 0.0
        ) {
            return
        }
        val snapshot = artifactWorkspaceHandlesByGroup[groupId]?.let { handle ->
            ArtifactWorkspaceManager(
                handle.root.parentFile?.parentFile?.parentFile?.parentFile ?: handle.root
            ).bestSnapshot(handle)
        }
        val targetId = when (spend.rewardType) {
            InstrumentalRewardType.ARTIFACT_VALIDATION_CREDITS ->
                "arc-visible-loo:$runId:$groupId:$round"
            else -> "artifact-repair:$runId:$groupId:$round"
        }
        val targetHash = snapshot?.artifactHash
            ?: AtlasHashing.hashText("$targetId|pending")
        val purpose = RewardSpendPurposeV1(
            kind = when (spend.rewardType) {
                InstrumentalRewardType.ARTIFACT_VALIDATION_CREDITS ->
                    RewardSpendPurposeKind.BENCHMARK
                else -> RewardSpendPurposeKind.REPAIR_ACTION
            },
            targetId = targetId,
            targetHash = targetHash,
            actionId = "artifact-action:$runId:$groupId:$round:${spend.rewardType.name}",
        ).withComputedHash()
        BoundedCompoundingLedgerRepository(substrateFactory.get(runId, groupId))
            .appendRewardSpend(
                RewardSpendRecordV1(
                    spendId = "compounding-spend:${spend.eventHash}",
                    runId = runId,
                    groupId = groupId,
                    rewardApplicationHash = spend.yieldApplicationHash,
                    rewardType = spend.rewardType,
                    amountSpent = spend.amountSpent,
                    spentRound = round,
                    purpose = purpose,
                ).withComputedHash()
            )
    }

    internal suspend fun appendRewardArtifactLineage(
        runId: String,
        groupId: String,
        round: Int,
        submission: StagingSubmissionEntity
    ) {
        val spendEvents = rewardSpendLineageByGroupRound[groupId to round].orEmpty()
        if (spendEvents.isEmpty()) return
        val promoted = substrateFactory.get(runId, groupId).stagingDao()
            .getByIds(listOf(submission.id))
            .singleOrNull()
        val artifactIds = linkedSetOf(submission.id).apply {
            promoted?.promotedArtifactRef?.takeIf(String::isNotBlank)?.let(::add)
        }
        spendEvents.forEach { spend ->
            val child = nodeCaptureRepository.appendRewardUseEvent(
                spend.copy(
                    eventId = "reward-artifact:${spend.eventHash}:" +
                        AtlasHashing.hashText(artifactIds.sorted().joinToString("|")),
                    amountSpent = 0.0,
                    downstreamArtifactIds = artifactIds,
                    parentEventHash = spend.eventHash,
                    eventHash = ""
                ).withComputedHash()
            )
            artifactIds.forEach { artifactId ->
                rewardArtifactLineageByArtifactId
                    .computeIfAbsent(artifactId) { ConcurrentLinkedQueue() }
                    .add(child)
            }
        }
    }

    private suspend fun prepareRoundNodePortfolios(
        config: RunConfig,
        groupIds: List<String>,
        round: Int
    ) {
        val envelope = activeWorkerExecutionEnvelope ?: return
        val atlasId = activeWorkerAtlasId ?: return
        AtlasLeaseAuthority(atlasTransactionRepository).expireElapsed(config.runId, atlasId)
        val atlas = requireNotNull(atlasTransactionRepository.currentAtlas(config.runId, atlasId))
        val opportunityCommitments = originToolOpportunityCommitments(atlas)
        val states = atlasTransactionRepository.currentStates(config.runId, atlasId)
        val compiler = WorkerViewCompiler()
        val expectedReuseResolver = ExpectedToolReuseAuthorityResolver()
        val expectedReusePriorities = groupIds.associate { groupId ->
            val atlasGroupId = AtlasGroupId(groupId)
            atlasGroupId to expectedReuseResolver.resolve(
                database = substrateFactory.get(config.runId, groupId),
                atlas = atlas,
                groupId = atlasGroupId,
                opportunityCommitments = opportunityCommitments
            ).pendingNodeIds
        }
        repairApproachAssignments.entries.removeIf { it.key.round == round }
        val repairPriorities = groupIds.associate { groupId ->
            AtlasGroupId(groupId) to linkedSetOf<NodeId>()
        }.toMutableMap()
        val repairPlanner = RepairPriorityPlanner()
        val runRepairRepository = ToolRepairPersistenceRepository(
            substrateFactory.getAtlas(config.runId)
        )
        atlas.nodes.values
            .sortedBy { it.id.value }
            .forEach { node ->
                val opportunity = node.toolOpportunity ?: return@forEach
                val priorAttempts = runRepairRepository.attempts(opportunity.opportunityId)
                val remainingAttempts =
                    ApproachVector.MAX_LINEAGE_ATTEMPTS - priorAttempts.size
                if (remainingAttempts <= 0) return@forEach
                val diagnosticsByGroup = groupIds.sorted().mapNotNull { groupId ->
                    val atlasGroupId = AtlasGroupId(groupId)
                    if (atlasGroupId !in states.getValue(node.id).revealedToGroups) {
                        return@mapNotNull null
                    }
                    val diagnostics = ToolRepairPersistenceRepository(
                        substrateFactory.get(config.runId, groupId)
                    ).visibleDiagnostics(
                        runId = config.runId,
                        groupId = groupId,
                        opportunityId = opportunity.opportunityId,
                        round = round
                    )
                    diagnostics.takeIf(List<*>::isNotEmpty)?.let { groupId to diagnostics }
                }.take(
                    minOf(
                        remainingAttempts,
                        ApproachVector.MAX_LINEAGE_ATTEMPTS
                    )
                )
                if (diagnosticsByGroup.isEmpty()) return@forEach
                val plan = repairPlanner.plan(
                    runId = config.runId,
                    round = round,
                    nodeId = node.id,
                    opportunityId = opportunity.opportunityId,
                    groupIds = diagnosticsByGroup.mapTo(linkedSetOf()) {
                        AtlasGroupId(it.first)
                    },
                    diagnostics = diagnosticsByGroup.flatMap { it.second },
                    priorAttempts = priorAttempts
                )
                plan.priorityNodeIdsByGroup.forEach { (groupId, nodeIds) ->
                    repairPriorities.getValue(groupId).addAll(nodeIds)
                }
                plan.approachVectors.forEach { (groupId, approach) ->
                    repairApproachAssignments[
                        RepairApproachAssignmentKey(
                            groupId = groupId.value,
                            nodeId = node.id,
                            round = round
                        )
                    ] = approach
                }
            }
        val combinedPriorities = groupIds.associate { groupId ->
            val atlasGroupId = AtlasGroupId(groupId)
            atlasGroupId to (
                expectedReusePriorities.getValue(atlasGroupId) +
                    repairPriorities.getValue(atlasGroupId)
                )
        }
        val views = groupIds.map { groupId ->
            val provisionalHypotheses = ProvisionalNovelHypothesisLedger(
                substrateFactory.get(config.runId, groupId).stagingDao()
            ).getPromoted(
                runId = config.runId,
                groupId = groupId,
                limit = 32
            )
            compiler.compile(
                atlas = atlas,
                states = states,
                groupId = AtlasGroupId(groupId),
                round = round,
                provisionalHypotheses = provisionalHypotheses,
                opportunityCommitments = opportunityCommitments
            )
        }
        val portfolios = NodePortfolioAllocator().allocate(
            atlas = atlas,
            states = states,
            workerViews = views,
            maximumNodesPerGroup = envelope.maxAgentsPerGroupPerRound.coerceAtLeast(1),
            priorityNodeIdsByGroup = combinedPriorities
        )
        portfolios.forEach { (groupId, portfolio) ->
            WorkerExecutionRepository(
                substrateFactory.get(config.runId, groupId.value)
            ).appendNodePortfolio(portfolio)
            val selectedNodeIds = portfolio.bids.map { it.nodeId }
            val wipReconciliation = atlasWipProjectionReconciler.reconcile(
                runId = config.runId,
                groupId = groupId.value,
                nodeIds = selectedNodeIds,
                generation = round
            )
            liveLifecycleManagers[groupId.value]?.configureNodePortfolio(
                selectedNodeIds
            )
            if (wipReconciliation.blockedNodeIds.isNotEmpty()) {
                Napier.w(
                    "EvoMoliorRunService: group=${groupId.value} round=$round selected Atlas " +
                        "WIP rows remain non-claimable: " +
                        wipReconciliation.blockedNodeIds.joinToString(",") { it.value }
                )
            }
            Napier.i(
                "EvoMoliorRunService: group=${groupId.value} round=$round reactivated " +
                    "${wipReconciliation.reactivatedNodeIds.size}/${selectedNodeIds.size} " +
                    "selected Atlas WIP rows"
            )
            emit(
                EvoMoliorEvent.EngineProgress(
                    runId = config.runId,
                    phase = "wip_projection",
                    message = "[${groupId.value}] Reactivated " +
                        "${wipReconciliation.reactivatedNodeIds.size}/${selectedNodeIds.size} " +
                        "selected Atlas WIP rows for round $round"
                )
            )
            emit(
                EvoMoliorEvent.EngineProgress(
                    runId = config.runId,
                    phase = "node_portfolio",
                    message = "[${groupId.value}] ${portfolio.bids.size} Atlas nodes selected; " +
                        "nodes=${selectedNodeIds.joinToString(",") { it.value }}; " +
                        "portfolioHash=${portfolio.portfolioHash}; " +
                        "diversity=${"%.2f".format(Locale.ROOT, portfolio.diversityScore)}"
                )
            )
        }
    }

    private suspend fun originToolOpportunityCommitments(
        atlas: NodeAtlas
    ): Map<String, ToolOpportunityCommitment> {
        val history = atlasTransactionRepository.versionHistory(atlas.runId, atlas.atlasId)
        require(history.lastOrNull() == atlas) {
            "Tool opportunity authority requires complete history through the current Atlas"
        }
        return ToolOpportunityCommitmentAuthority.resolve(history).byOpportunityId
    }

    private suspend fun persistToolRepairAuthority(
        database: EvoMoliorDatabase,
        runId: String,
        groupId: String,
        round: Int,
        lifecycleSummary: CriticLifecycleSummary
    ) {
        val repository = ToolRepairPersistenceRepository(database)
        lifecycleSummary.repairDiagnostics.forEach { diagnostic ->
            repository.appendDiagnostic(diagnostic)
        }
        database.stagingDao()
            .getReviewedToolsByGeneration(runId, groupId, round)
            .forEach { submission ->
                val authority = submission.toolCandidateAuthorityJson
                    ?.let(::decodeToolCandidateAuthority)
                    ?: return@forEach
                val opportunityId = runCatching {
                    runConfigJson.parseToJsonElement(submission.content)
                        .jsonObject
                        .getValue("opportunityId")
                        .jsonPrimitive
                        .content
                }.getOrNull() ?: return@forEach
                authority.consumedDiagnosticIds.forEach { diagnosticId ->
                    val diagnostic = requireNotNull(repository.diagnostic(diagnosticId)) {
                        "Tool candidate consumed a missing repair diagnostic"
                    }
                    require(
                        diagnostic.runId == runId &&
                            diagnostic.groupId == groupId &&
                            diagnostic.opportunityId == opportunityId &&
                            diagnostic.opportunityHash ==
                            authority.opportunityCommitmentHash &&
                            diagnostic.visibleFromRound <= round
                    ) {
                        "Tool candidate consumed foreign or stale repair authority"
                    }
                }
                authority.repairApproachVector?.let { approach ->
                    require(
                        approach.hasValidHash() &&
                            approach.runId == runId &&
                            approach.groupId == groupId &&
                            approach.opportunityId == opportunityId &&
                            approach.round == round
                    ) {
                        "Tool candidate repair approach is foreign, stale, or invalid"
                    }
                }
                val promotionOutcome = lifecycleSummary.promotionOutcomes[submission.id]
                val preflightResult = if (
                    submission.criticAgentId == "system-tool-preflight"
                ) {
                    ToolValidationStatus.REJECTED
                } else {
                    ToolValidationStatus.PASSED
                }
                val criticResult = when {
                    preflightResult == ToolValidationStatus.REJECTED -> null
                    promotionOutcome != null -> ToolSemanticVerdict.PROMOTE
                    submission.status == "promoted" -> ToolSemanticVerdict.PROMOTE
                    submission.status == "rejected" -> ToolSemanticVerdict.REJECT
                    else -> null
                }
                val promotionResult = when {
                    preflightResult == ToolValidationStatus.REJECTED ->
                        ToolPromotionStatus.NOT_ATTEMPTED
                    promotionOutcome != null -> promotionOutcome.status
                    submission.status == "promoted" -> ToolPromotionStatus.PROMOTED
                    else -> ToolPromotionStatus.NOT_ATTEMPTED
                }
                val attempt = ToolRepairAttemptRecord(
                    attemptId = "tool-repair-attempt:${submission.id}",
                    opportunityId = opportunityId,
                    candidateHash = authority.candidateHash,
                    authorAgentId = submission.agentId,
                    round = submission.generation,
                    consumedDiagnosticIds = authority.consumedDiagnosticIds,
                    preflightResult = preflightResult,
                    criticResult = criticResult,
                    promotionResult = promotionResult,
                    approachVector = authority.repairApproachVector
                ).withComputedHash()
                ToolRepairPersistenceRepository(
                    substrateFactory.getAtlas(runId)
                ).appendAttempt(attempt)
                repository.appendAttempt(attempt)
            }
        val compoundingLedger = BoundedCompoundingLedgerRepository(database)
        lifecycleSummary.repairDiagnostics.forEach { diagnostic ->
            val consumed = repository.attempts(diagnostic.opportunityId)
                .any { diagnostic.diagnosticId in it.consumedDiagnosticIds }
            compoundingLedger.appendRepairWorkItem(
                RepairDiagnosticWorkItemV1(
                    workItemId = "repair-work:${diagnostic.diagnosticId}",
                    diagnosticId = diagnostic.diagnosticId,
                    diagnosticHash = diagnostic.diagnosticHash,
                    runId = diagnostic.runId,
                    groupId = diagnostic.groupId,
                    nodeId = diagnostic.nodeId,
                    opportunityId = diagnostic.opportunityId,
                    opportunityHash = diagnostic.opportunityHash,
                    nextRound = diagnostic.visibleFromRound,
                    disposition = if (consumed) {
                        RepairDiagnosticDisposition.CONSUMED
                    } else {
                        RepairDiagnosticDisposition.DEFERRED_WITH_REASON
                    },
                    dispositionReason = if (consumed) {
                        null
                    } else {
                        "No bounded repair candidate consumed this diagnostic by the round boundary."
                    },
                ).withComputedHash()
            )
        }
    }

    private fun decodeToolCandidateAuthority(
        content: String
    ): PersistedToolCandidateAuthority? = runCatching {
        val root = runConfigJson.parseToJsonElement(content).jsonObject
        val candidateHash = root.getValue("candidateHash").jsonPrimitive.content
        val opportunityCommitmentHash =
            root.getValue("opportunityCommitmentHash").jsonPrimitive.content
        val sourceHash = root.getValue("sourceHash").jsonPrimitive.content
        require(
            listOf(candidateHash, opportunityCommitmentHash, sourceHash)
                .all { it.matches(Regex("[0-9a-f]{64}")) }
        )
        val consumedDiagnosticIds = root.getValue("consumedRepairDiagnosticIds")
            .jsonArray
            .map { it.jsonPrimitive.content }
        require(
            consumedDiagnosticIds == consumedDiagnosticIds.distinct().sorted() &&
                consumedDiagnosticIds.size <= 3
        )
        val repairApproachVector = root["repairApproachVector"]
            ?.takeUnless { it is kotlinx.serialization.json.JsonNull }
            ?.let { element ->
                runConfigJson.decodeFromString(
                    ApproachVector.serializer(),
                    element.toString()
                )
            }
        require(repairApproachVector == null || repairApproachVector.hasValidHash())
        PersistedToolCandidateAuthority(
            candidateHash = candidateHash,
            opportunityCommitmentHash = opportunityCommitmentHash,
            consumedDiagnosticIds = consumedDiagnosticIds,
            repairApproachVector = repairApproachVector
        )
    }.getOrNull()

    /**
     * Runs [CriticPipeline.processPendingSubmissions] for every active group.
     * Called at the end of each round to review and promote staged agent submissions.
     */
    private suspend fun runCriticPipelinesForRound(
        config: RunConfig,
        groupIds: List<String>,
        round: Int
    ): Map<String, CriticSummary> = supervisorScope {
        groupIds.map { groupId ->
            async {
                try {
                    val db = substrateFactory.get(config.runId, groupId)
                    val pendingCount = db.stagingDao().countPending(config.runId, groupId)
                    Napier.i("EvoMoliorRunService: CriticPipeline starting for group=$groupId round=$round pending=$pendingCount")
                    emit(EvoMoliorEvent.EngineProgress(config.runId, "critic",
                        "[$groupId] CriticPipeline reviewing $pendingCount pending submissions"))
                    val toolsMountHostPath = substrateFactory.dbFilePath(config.runId, groupId).parentFile
                        ?.resolve("tools")
                        ?.absolutePath
                        ?: error("Missing tools mount host path for group=$groupId")
                    val criticAgentId = criticAgentIdFor(groupId, round)
                    val groupProfile = GroupProfile.default(
                        activeGroupProfiles.getOrElse(groupIds.indexOf(groupId)) {
                            activeGroupProfiles.firstOrNull() ?: GroupProfileType.Library
                        }
                    )
                    val criticBudgetConfig = BudgetConfig(groupId, groupProfile)
                    val criticBudget = groupProfile.spawnBudget
                    val currentAtlas = activeWorkerAtlasId?.let { atlasId ->
                        atlasTransactionRepository.currentAtlas(config.runId, atlasId)
                    }
                    val opportunityCommitments: Map<String, ToolOpportunityCommitment> =
                        if (currentAtlas == null) {
                            emptyMap()
                        } else {
                            originToolOpportunityCommitments(currentAtlas)
                        }
                    val toolPromotionService = ToolPromotionService(
                        database = db,
                        runId = config.runId,
                        groupId = groupId,
                        toolsMountHostPath = toolsMountHostPath,
                        opportunityResolver = { submission ->
                            val nodeId = submission.wipTaskRef?.let(::NodeId)
                            val opportunity = nodeId?.let { currentAtlas?.nodes?.get(it) }
                                ?.toolOpportunity
                            if (nodeId == null || opportunity == null || currentAtlas == null) {
                                null
                            } else {
                                requireNotNull(
                                    opportunityCommitments[opportunity.opportunityId]
                                ) {
                                    "Tool promotion lacks origin-version opportunity authority"
                                }.also { commitment ->
                                    require(
                                        commitment.runId == config.runId &&
                                            commitment.nodeId == nodeId &&
                                            commitment.opportunity == opportunity &&
                                            commitment.hasValidHash()
                                    ) {
                                        "Tool promotion opportunity authority does not match " +
                                            "the current sealed Atlas node"
                                    }
                                }
                            }
                        },
                        sealedPromotionTestResolver = if (
                            activeSealedTestSuiteJson.isNotBlank() &&
                            (
                                activeSealedTestSuiteValidationContext?.let { context ->
                                    sealedTestSuiteEvaluator.inspectCompiledSuite(
                                        activeSealedTestSuiteJson,
                                        context
                                    )
                                } ?: sealedTestSuiteEvaluator.inspectSuite(
                                    activeSealedTestSuiteJson
                                )
                            ).isComplete
                        ) {
                            { commitment ->
                                activeSealedTestSuiteValidationContext?.let { context ->
                                    sealedTestSuiteEvaluator.promotionFixtures(
                                        suiteJson = activeSealedTestSuiteJson,
                                        validationContext = context,
                                        nodeId = commitment.nodeId.value,
                                        opportunityId = commitment.opportunity.opportunityId,
                                        testIds = commitment.opportunity.promotionTestIds
                                    )
                                } ?: sealedTestSuiteEvaluator.promotionFixtures(
                                    suiteJson = activeSealedTestSuiteJson,
                                    nodeId = commitment.nodeId.value,
                                    opportunityId = commitment.opportunity.opportunityId,
                                    testIds = commitment.opportunity.promotionTestIds
                                )
                            }
                        } else {
                            null
                        },
                        costLedgerRepository = CostLedgerRepository(
                            substrateFactory.get(config.runId, "__cost_ledger__"),
                            CostLedgerPolicy(
                                cumulativeCapUsd =
                                    activeWorkerExecutionEnvelope?.providerCostCapUsd
                            )
                        )
                    )
                    val toolAuthoringAuthorityVerifier =
                        ToolAuthoringNodeAuthorityVerifier(db)
                    val lifecycle = LifecycleBoundCriticPipeline(
                        db = db,
                        runId = config.runId,
                        groupId = groupId,
                        generation = round,
                        criticAgentId = criticAgentId,
                        ttlMs = CRITIC_PIPELINE_TTL_MS,
                        budgetLedger = BudgetLedger(
                            agentId = criticAgentId,
                            initialBudget = criticBudget,
                            floor = criticBudgetConfig.floorFor(criticBudget),
                            config = criticBudgetConfig
                        ),
                        pipelineFactory = { lifecycleCriticId, criticAuthorBlocklist ->
                            CriticPipeline(
                                db = db,
                                runId = config.runId,
                                groupId = groupId,
                                generation = round,
                                openRouterApiKey = config.openRouterApiKey,
                                llmBackend = config.llmBackend,
                                codexReasoningEffort = config.codexRuntimePreset
                                    ?.reasoningEffort(Role.Critic, config.codexReasoningEffort)
                                    ?: config.codexReasoningEffort,
                                criticModel = config.codexRuntimePreset?.let { preset ->
                                    config.modelOverrides[Role.Critic.name] ?: preset.defaultModel(Role.Critic)
                                }
                                    ?: criticModelOverride ?: ModelSelectionConfig.DEFAULT_CRITIC_PIPELINE_MODEL,
                                criticAgentId = lifecycleCriticId,
                                criticAuthorBlocklist = criticAuthorBlocklist + criticLineageFor(groupId, round),
                                toolsMountHostPath = toolsMountHostPath,
                                criticReviewRunner = workerCriticReviewRunner,
                                toolSemanticReviewRunner = workerToolSemanticReviewRunner,
                                // B-1: Pass the council-designed KG schema so KGConsistencyChecker
                                // can validate KG_FACT submissions before they go to the LLM Critic.
                                kgSchema = activeKgSchema,
                                onWipTaskCompleted = { completedGroupId, nodeId, agentId, generation ->
                                    recordWipCompletion(completedGroupId, nodeId)
                                    emit(EvoMoliorEvent.EngineProgress(
                                        config.runId,
                                        "wip_completed",
                                        "[$completedGroupId] WiP task $nodeId completed by $agentId at generation $generation"
                                    ))
                                },
                                onSubmissionPromoted = { submission, trustScore ->
                                    appendRewardArtifactLineage(
                                        runId = config.runId,
                                        groupId = groupId,
                                        round = round,
                                        submission = submission
                                    )
                                    if (submission.type == "DISPATCH") {
                                        routePromotedDispatch(
                                            runId = config.runId,
                                            groupId = groupId,
                                            submission = submission,
                                            trustScore = trustScore
                                        )
                                    }
                                },
                                deliverableTreeStore = deliverableSkeletonTreeStore,
                                toolPromotionService = toolPromotionService,
                                wipCompletionAuthorizer = { submission, artifactRef ->
                                    val nodeId = submission.wipTaskRef?.let(::NodeId)
                                    val node = nodeId?.let { currentAtlas?.nodes?.get(it) }
                                    val opportunity = node?.toolOpportunity
                                    if (nodeId == null) {
                                        WipCompletionAuthorityDecision.AUTHORIZED
                                    } else if (currentAtlas == null) {
                                        val projectedWip = db.wipTaskDao().getByNodeId(
                                            nodeId = nodeId.value,
                                            runId = config.runId,
                                            groupId = groupId
                                        )
                                        if (projectedWip == null) {
                                            WipCompletionAuthorityDecision.AUTHORIZED
                                        } else {
                                            WipCompletionAuthorityDecision.denied(
                                                "Atlas-backed WIP completion lacks current " +
                                                    "sealed Atlas authority"
                                            )
                                        }
                                    } else if (node == null) {
                                        val projectedWip = db.wipTaskDao().getByNodeId(
                                            nodeId = nodeId.value,
                                            runId = config.runId,
                                            groupId = groupId
                                        )
                                        if (projectedWip == null) {
                                            // Deliverable-only references such as dst:root are
                                            // intentionally outside the Atlas WIP projection.
                                            WipCompletionAuthorityDecision.AUTHORIZED
                                        } else {
                                            WipCompletionAuthorityDecision.denied(
                                                "WIP completion references a node outside the " +
                                                    "current sealed Atlas"
                                            )
                                        }
                                    } else if (opportunity == null) {
                                        WipCompletionAuthorityDecision.AUTHORIZED
                                    } else {
                                        val toolVersionId = artifactRef
                                            .takeIf { it.startsWith("tool:") }
                                            ?.removePrefix("tool:")
                                            ?.takeIf(String::isNotBlank)
                                            ?.let(::ToolVersionId)
                                        if (toolVersionId == null) {
                                            WipCompletionAuthorityDecision.denied(
                                                "sealed tool-opportunity nodes require an exact " +
                                                    "active promoted version"
                                            )
                                        } else {
                                            runCatching {
                                                toolAuthoringAuthorityVerifier
                                                    .requireActivePromotedVersion(
                                                        commitment = requireNotNull(
                                                            opportunityCommitments[
                                                                opportunity.opportunityId
                                                            ]
                                                        ) {
                                                            "WIP completion lacks origin-version " +
                                                                "opportunity authority"
                                                        },
                                                        submission = submission,
                                                        toolVersionId = toolVersionId
                                                    )
                                            }.fold(
                                                onSuccess = {
                                                    WipCompletionAuthorityDecision.AUTHORIZED
                                                },
                                                onFailure = { error ->
                                                    WipCompletionAuthorityDecision.denied(
                                                        error.message
                                                            ?: "missing tool authoring authority"
                                                    )
                                                }
                                            )
                                        }
                                    }
                                },
                                costLedgerRepository = CostLedgerRepository(
                                    substrateFactory.get(
                                        config.runId,
                                        "__cost_ledger__"
                                    ),
                                    CostLedgerPolicy(
                                        cumulativeCapUsd =
                                            activeWorkerExecutionEnvelope
                                                ?.providerCostCapUsd
                                    )
                                )
                            )
                        }
                    )
                    val lifecycleSummary = lifecycle.run()
                    lifecycleSummary.promotionOutcomes
                        .toSortedMap()
                        .forEach { (submissionId, outcome) ->
                            emit(
                                EvoMoliorEvent.EngineProgress(
                                    config.runId,
                                    "tool_promotion_outcome",
                                    "[$groupId] submission=$submissionId " +
                                        "status=${outcome.status.name} " +
                                        "reason=${outcome.rejectionReasonCode ?: "none"}"
                                )
                            )
                        }
                    persistToolRepairAuthority(
                        database = db,
                        runId = config.runId,
                        groupId = groupId,
                        round = round,
                        lifecycleSummary = lifecycleSummary
                    )
                    val summary: CriticSummary = lifecycleSummary.summary
                    sealCriticAcceptedNodeEvidence(
                        config = config,
                        groupId = groupId,
                        round = round
                    ).forEach { packet ->
                        sealedNodeEvidenceByRound
                            .computeIfAbsent(round) { ConcurrentLinkedQueue() }
                            .add(packet)
                    }
                    Napier.i("EvoMoliorRunService: CriticPipeline processed ${summary.total} for group=$groupId round=$round: promoted=${summary.promoted} rejected=${summary.rejected} arbitrated=${summary.arbitrated}")
                    emit(EvoMoliorEvent.CriticPipelineCompleted(
                        runId = config.runId,
                        groupId = groupId,
                        roundIndex = round,
                        promoted = summary.promoted,
                        rejected = summary.rejected
                    ))
                    groupId to summary
                } catch (e: Exception) {
                    Napier.e("EvoMoliorRunService: CriticPipeline failed for group=$groupId round=$round: ${e.message}", e)
                    groupId to CriticSummary(promoted = 0, rejected = 0, arbitrated = 0)
                }
            }
        }.awaitAll().toMap()
    }

    internal suspend fun sealCriticAcceptedNodeEvidence(
        config: RunConfig,
        groupId: String,
        round: Int
    ): List<NodeEvidencePacket> {
        val atlasId = activeWorkerAtlasId ?: return emptyList()
        val atlas = atlasTransactionRepository.currentAtlas(config.runId, atlasId)
            ?: return emptyList()
        val db = substrateFactory.get(config.runId, groupId)
        val promotions = db.stagingDao()
            .getPromotedByGeneration(config.runId, groupId, round)
            .filter { submission ->
                submission.promotedArtifactHash != null &&
                    submission.promotedArtifactRef != null &&
                    submission.wipTaskRef != null
            }
        val workerRepository = WorkerExecutionRepository(db)
        val sealer = NodeEvidencePacketSealer(substrateFactory, nodeCaptureRepository)
        val contributionsByNode = atlas.nodes.keys.associateWith { nodeId ->
            workerRepository
                .contributionsForNode(config.runId, groupId, nodeId.value)
                .filter { contribution ->
                    contribution.round == round &&
                        contribution.commitType != CommitType.NO_CONTRIBUTION
                }
        }
        val nodeIds = (
            promotions.mapNotNull(StagingSubmissionEntity::wipTaskRef).map(::NodeId) +
                contributionsByNode.filterValues(List<ContributionCommit>::isNotEmpty).keys
            ).toSet()
        return nodeIds.sortedBy(NodeId::value).mapNotNull { nodeId ->
            val node = atlas.nodes[nodeId] ?: return@mapNotNull null
            val contributions = contributionsByNode.getValue(nodeId)
            if (contributions.isEmpty()) return@mapNotNull null
            val nodePromotions = promotions.filter { it.wipTaskRef == nodeId.value }
                .filter { promotion ->
                    contributions.any { contribution ->
                        promotion.id in contribution.artifactIds ||
                            promotion.id in contribution.claimIds ||
                            promotion.promotedArtifactRef in contribution.artifactIds ||
                            promotion.promotedArtifactRef in contribution.claimIds
                    }
                }
            val acceptedClaimIds = nodePromotions
                .filter { it.type == "FACT" || it.type == "KG_CONSTRAINT" }
                .map(StagingSubmissionEntity::id)
            val acceptedArtifactIds = nodePromotions
                .filterNot { it.id in acceptedClaimIds }
                .map(StagingSubmissionEntity::id)
            val negativeResultIds = contributions
                .filter {
                    it.commitType == CommitType.FALSIFIED_HYPOTHESIS ||
                        it.commitType == CommitType.STRUCTURED_DEAD_END
                }
                .flatMap { it.artifactIds + it.claimIds }
                .distinct()
            runCatching {
                sealer.seal(
                    SealNodeEvidenceRequest(
                        runId = config.runId,
                        round = round,
                        groupId = AtlasGroupId(groupId),
                        nodeId = nodeId,
                        atlasVersion = atlas.version,
                        atlasHash = atlas.atlasHash,
                        nodePayloadHash = node.payloadHash,
                        acceptedArtifactIds = acceptedArtifactIds,
                        acceptedClaimIds = acceptedClaimIds,
                        toolRunIds = contributions.flatMap(ContributionCommit::toolRunIds).distinct(),
                        testResultIds = contributions.flatMap(ContributionCommit::testsRun).distinct(),
                        negativeResultIds = negativeResultIds
                    )
                )
            }.onFailure { error ->
                Napier.w(
                    "EvoMoliorRunService: node evidence sealing failed closed for " +
                        "group=$groupId node=${nodeId.value} round=$round: ${error.message}"
                )
            }.getOrNull()
        }
    }

    internal suspend fun reviewGraderCriteriaForRound(
        runId: String,
        openRouterApiKey: String,
        graderGroupIds: List<String>,
        roundIndex: Int,
        llmBackend: LlmBackend = LlmBackend.OPENROUTER,
        onProgress: ((String) -> Unit)? = null
    ) {
        if (graderGroupIds.isEmpty()) return

        graderCriteriaReviewRunner?.let { reviewer ->
            reviewer.invoke(runId, openRouterApiKey, graderGroupIds, roundIndex, onProgress)
            return
        }

        supervisorScope {
            graderGroupIds.map { graderGroupId ->
                async {
                    try {
                        val graderDb = substrateFactory.getGrader(runId, graderGroupId)
                        val pendingCount = graderDb.stagingDao().countPending(runId, graderGroupId)
                        Napier.i("EvoMoliorRunService: Grader criteria CriticPipeline starting for grader=$graderGroupId round=$roundIndex pending=$pendingCount")
                        onProgress?.invoke("[$graderGroupId] CriticPipeline reviewing $pendingCount grader criteria submissions")
                        val toolsMountHostPath = substrateFactory.graderDbFilePath(runId, graderGroupId)
                            .parentFile
                            ?.resolve("tools")
                            ?.absolutePath
                            ?: error("Missing tools mount host path for grader=$graderGroupId")
                        val criticAgentId = criticAgentIdFor(graderGroupId, roundIndex)
                        val criticBudgetConfig = BudgetConfig(graderGroupId, GroupProfile.Library)
                        val criticBudget = GroupProfile.Library.spawnBudget
                        val lifecycle = LifecycleBoundCriticPipeline(
                            db = graderDb,
                            runId = runId,
                            groupId = graderGroupId,
                            generation = roundIndex,
                            criticAgentId = criticAgentId,
                            ttlMs = CRITIC_PIPELINE_TTL_MS,
                            budgetLedger = BudgetLedger(
                                agentId = criticAgentId,
                                initialBudget = criticBudget,
                                floor = criticBudgetConfig.floorFor(criticBudget),
                                config = criticBudgetConfig
                            ),
                            pipelineFactory = { lifecycleCriticId, criticAuthorBlocklist ->
                                CriticPipeline(
                                    db = graderDb,
                                    runId = runId,
                                    groupId = graderGroupId,
                                    generation = roundIndex,
                                    openRouterApiKey = openRouterApiKey,
                                    llmBackend = llmBackend,
                                    criticModel = criticModelOverride ?: ModelSelectionConfig.DEFAULT_CRITIC_PIPELINE_MODEL,
                                    criticAgentId = lifecycleCriticId,
                                    criticAuthorBlocklist = criticAuthorBlocklist + criticLineageFor(graderGroupId, roundIndex),
                                    toolsMountHostPath = toolsMountHostPath,
                                    kgSchema = activeKgSchema,
                                    onWipTaskCompleted = null,
                                    deliverableTreeStore = deliverableSkeletonTreeStore,
                                    legacyDirectToolPromotionEnabled = false,
                                    costLedgerRepository = CostLedgerRepository(
                                        substrateFactory.get(
                                            runId,
                                            COST_LEDGER_GROUP_ID
                                        ),
                                        costLedgerPolicy(runId)
                                    )
                                )
                            }
                        )
                        val summary: CriticSummary = lifecycle.run().summary
                        Napier.i("EvoMoliorRunService: Grader criteria CriticPipeline processed ${summary.total} for grader=$graderGroupId round=$roundIndex: promoted=${summary.promoted} rejected=${summary.rejected} arbitrated=${summary.arbitrated}")
                        onProgress?.invoke("[$graderGroupId] grader criteria review complete: promoted=${summary.promoted} rejected=${summary.rejected}")
                    } catch (e: Exception) {
                        Napier.e("EvoMoliorRunService: Grader criteria CriticPipeline failed for grader=$graderGroupId round=$roundIndex: ${e.message}", e)
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun hasPromotedTypedGraderMethodology(
        runId: String,
        graderGroupId: String,
        round: Int
    ): Boolean = substrateFactory.getGrader(runId, graderGroupId)
        .stagingDao()
        .getAll(runId, graderGroupId)
        .asSequence()
        .filter {
            it.type == "PLAN_UPDATE" &&
                it.status == "promoted" &&
                it.generation == round + 1 &&
                it.budgetSignature.startsWith(GRADER_METHODOLOGY_DRAFT_SIGNATURE_PREFIX)
        }
        .mapNotNull { entry ->
            runCatching {
                graderMethodologyJson.decodeFromString(
                    GraderMethodologyDraft.serializer(),
                    entry.content
                )
            }.getOrNull()
        }
        .any { it.requirements.isNotEmpty() }

    /** True when this round's typed methodology draft is staged but has not been reviewed yet. */
    private suspend fun hasPendingTypedGraderMethodology(
        runId: String,
        graderGroupId: String,
        round: Int
    ): Boolean = substrateFactory.getGrader(runId, graderGroupId)
        .stagingDao()
        .getPending(runId, graderGroupId)
        .any {
            it.type == "PLAN_UPDATE" &&
                it.generation == round + 1 &&
                it.budgetSignature.startsWith(GRADER_METHODOLOGY_DRAFT_SIGNATURE_PREFIX)
        }

    internal suspend fun repairRejectedGraderMethodologies(
        config: RunConfig,
        graderGroupIds: List<String>,
        round: Int
    ): List<String> {
        val criteriaRunner = graderCriteriaRunner ?: return graderGroupIds
        var missingGroups = graderGroupIds.filterNot { graderGroupId ->
            hasPromotedTypedGraderMethodology(config.runId, graderGroupId, round)
        }
        // A draft the deadline-bounded Critic never reached is pending, not rejected. Finish its
        // review before paying for a full re-authoring cycle.
        val pendingDraftGroups = missingGroups.filter { graderGroupId ->
            hasPendingTypedGraderMethodology(config.runId, graderGroupId, round)
        }
        if (pendingDraftGroups.isNotEmpty()) {
            emit(
                EvoMoliorEvent.EngineProgress(
                    config.runId,
                    "grader_criteria_repair",
                    "Review-only pass for unreviewed methodology drafts: " +
                        pendingDraftGroups.joinToString()
                )
            )
            reviewGraderCriteriaForRound(
                runId = config.runId,
                openRouterApiKey = config.openRouterApiKey,
                graderGroupIds = pendingDraftGroups,
                roundIndex = round,
                llmBackend = config.llmBackend
            )
            missingGroups = missingGroups.filterNot { graderGroupId ->
                hasPromotedTypedGraderMethodology(config.runId, graderGroupId, round)
            }
        }
        repeat(POST_CRITIC_METHODOLOGY_REPAIR_CYCLES) { cycleIndex ->
            if (missingGroups.isEmpty()) return emptyList()
            val groupsForCycle = missingGroups
            emit(
                EvoMoliorEvent.EngineProgress(
                    config.runId,
                    "grader_criteria_repair",
                    "Structured methodology repair cycle ${cycleIndex + 1}/" +
                        "$POST_CRITIC_METHODOLOGY_REPAIR_CYCLES after Critic rejection for " +
                        groupsForCycle.joinToString()
                )
            )
            criteriaRunner.invoke(
                config.runId,
                config.openRouterApiKey,
                groupsForCycle,
                round,
                config.objective,
                config.scoringDimensions
            ) { progressMsg ->
                scope.launch {
                    emit(
                        EvoMoliorEvent.EngineProgress(
                            config.runId,
                            "grader_criteria_repair",
                            progressMsg
                        )
                    )
                }
            }
            reviewGraderCriteriaForRound(
                runId = config.runId,
                openRouterApiKey = config.openRouterApiKey,
                graderGroupIds = groupsForCycle,
                roundIndex = round,
                llmBackend = config.llmBackend
            )
            missingGroups = groupsForCycle.filterNot { graderGroupId ->
                hasPromotedTypedGraderMethodology(config.runId, graderGroupId, round)
            }
        }
        return missingGroups
    }

    private suspend fun rolloverGraderCivilizations(
        runId: String,
        graderGroupIds: List<String>,
        generation: Int
    ) = supervisorScope {
        graderGroupIds.map { graderGroupId ->
            async {
                val result = GraderCivilizationController(
                    db = substrateFactory.getGrader(runId, graderGroupId),
                    runId = runId,
                    graderGroupId = graderGroupId
                ).completeGeneration(
                    generation = generation,
                    allowLegacyExtraction =
                        graderAuthorityRepository.currentAtlas(runId) == null
                )
                emit(
                    EvoMoliorEvent.EngineProgress(
                        runId,
                        "grader_generation_rollover",
                        "[$graderGroupId] grader generation $generation: " +
                            "fitness=${result.scoredAgents}, legacy=${result.extractedProtocols}, " +
                            "expired=${result.expiredProtocols}, deadEnds=${result.deadEndsInserted}"
                    )
                )
            }
        }.awaitAll()
    }

    /**
     * Runs the Grader Group AI civilisation for the current round.
     *
     * Calls [graderRunner] if configured. On failure, logs a warning and falls back gracefully
     * (substrate-based scoring still runs after this).
     *
     * Emits [EvoMoliorEvent.GraderRoundStarted] and [EvoMoliorEvent.GraderRoundCompleted] events.
     */
    private suspend fun runGraderAgents(
        config: RunConfig,
        graderGroupIds: List<String>,
        competingGroupIds: List<String>,
        round: Int,
        graderGroupManager: GraderGroupManager
    ) {
        if (graderRunner == null) {
            Napier.d("EvoMoliorRunService: no graderRunner configured — skipping Phase 6 grader evaluation for round=$round")
            return
        }
        if (graderGroupIds.isEmpty()) {
            Napier.d("EvoMoliorRunService: graderGroupCount=0 — skipping grader evaluation for round=$round")
            return
        }

        for (graderGroupId in graderGroupIds) {
            emit(EvoMoliorEvent.GraderRoundStarted(config.runId, graderGroupId, round, competingGroupIds))
        }

        try {
            Napier.i("EvoMoliorRunService: starting grader evaluation round=$round graders=${graderGroupIds.size}")
            emit(EvoMoliorEvent.EngineProgress(config.runId, "grader_start",
                "Grader Group evaluation starting for round $round"))

            graderRunner.invoke(
                config.runId,
                config.openRouterApiKey,
                graderGroupIds,
                competingGroupIds,
                round,
                config.objective,
                config.scoringDimensions,
                graderGroupManager
            ) { progressMsg ->
                scope.launch {
                    emit(EvoMoliorEvent.EngineProgress(config.runId, "grader_agent", progressMsg))
                }
            }

            // Build score summary per group for the completion event
            val scoresPerGroup = competingGroupIds.associateWith {
                "[graded by ${graderGroupIds.size} grader(s)]"
            }
            val totalScores = scoresPerGroup.size * graderGroupIds.size * config.scoringDimensions.size

            for (graderGroupId in graderGroupIds) {
                emit(EvoMoliorEvent.GraderRoundCompleted(
                    runId = config.runId,
                    graderGroupId = graderGroupId,
                    roundIndex = round,
                    scoresPerGroup = scoresPerGroup,
                    totalScoresCollected = totalScores
                ))
            }

            Napier.i("EvoMoliorRunService: grader evaluation complete for round=$round")
        } catch (e: Exception) {
            Napier.w("EvoMoliorRunService: grader evaluation failed for round=$round: ${e.message}")
            emit(EvoMoliorEvent.EngineProgress(config.runId, "grader_error",
                "Grader evaluation failed for round $round: ${e.message?.take(150)} — falling back to substrate scoring"))
        }
    }

    /**
     * Runs the Phase B RoundJudge for the new two-phase grader architecture.
     *
     * Called post-round only when [graderJudgingRunner] is configured. The judging runner
     * should call `GraderRunner.runJudgingPhase` which spawns the RoundJudge agent.
     */
    internal suspend fun freezeCompetitiveGraderCriteria(
        runId: String,
        graderGroupIds: List<String>,
        competingGroupIds: List<String>,
        round: Int,
        roundAuthority: RoundAtlasAuthority? = null
    ): Map<String, GraderCriteriaFreeze> {
        val graderAtlas = roundAuthority?.graderAtlas
            ?: graderAuthorityRepository.currentAtlas(runId)
            ?: return emptyMap()
        val workerAtlas = roundAuthority?.workerAtlas
            ?: requireNotNull(atlasTransactionRepository.currentAtlas(runId)) {
            "Competitive grader criteria require the sealed worker Atlas"
        }
        require(
            workerAtlas.atlasHash == graderAtlas.workerAtlasHash &&
                workerAtlas.version == graderAtlas.version
        ) {
            "Competitive grader criteria reference a different worker Atlas"
        }
        workerAtlas.validate().requireValid()
        graderNodeLeaseAuthority.initialize(graderAtlas)
        val targetSnapshotHashes = competingGroupIds.associate { groupId ->
            val snapshot = CanonSnapshotExporter.exportForJudge(
                substrateFactory.get(runId, groupId),
                runId,
                groupId,
                round
            )
            AtlasGroupId(groupId) to AtlasHashing.hashText(snapshot)
        }
        return graderGroupIds.mapNotNull { graderGroupId ->
            runCatching {
                graderAuthorityRepository.criteriaFreezeForRound(
                    runId,
                    round,
                    graderGroupId
                )?.let { existing ->
                    require(existing.graderAtlasHash == graderAtlas.atlasHash) {
                        "Existing criteria freeze belongs to a different round Atlas authority"
                    }
                    require(existing.targetSnapshotHashes == targetSnapshotHashes) {
                        "Existing criteria freeze targets a different worker snapshot"
                    }
                    return@runCatching graderGroupId to existing
                }
                val methodology = substrateFactory.getGrader(runId, graderGroupId)
                    .stagingDao()
                    .getAll(runId, graderGroupId)
                    .asSequence()
                    .filter {
                        it.type == "PLAN_UPDATE" &&
                            it.status == "promoted" &&
                            it.generation == round + 1 &&
                            it.budgetSignature.startsWith(
                                GRADER_METHODOLOGY_DRAFT_SIGNATURE_PREFIX
                            )
                    }
                    .sortedByDescending(StagingSubmissionEntity::submittedAtMs)
                    .mapNotNull { entry ->
                        runCatching {
                            graderMethodologyJson.decodeFromString(
                                GraderMethodologyDraft.serializer(),
                                entry.content
                            )
                        }.getOrNull()
                    }
                    .firstOrNull { it.requirements.isNotEmpty() }
                    ?: error("No promoted typed grader methodology exists")
                var graderView = graderNodeLeaseAuthority.view(
                    graderAtlas,
                    graderGroupId,
                    round
                )
                if (graderView.nodes.none { it.leasedByThisGroup }) {
                    /*
                     * Criteria development can legitimately outlive its wall-clock lease due to
                     * bounded model retries or host suspension. Reacquire the deterministic
                     * frontier before freezing; the exact methodology-to-frontier check below
                     * prevents an expired draft from gaining authority over a different node.
                     */
                    graderNodeLeaseAuthority.acquireNext(
                        atlas = graderAtlas,
                        graderGroupId = graderGroupId,
                        round = round,
                        ttlMillis = CRITERIA_FREEZE_LEASE_TTL_MS
                    )
                    graderView = graderNodeLeaseAuthority.view(
                        graderAtlas,
                        graderGroupId,
                        round
                    )
                }
                val leasedViewNodes = graderView.nodes.filter { it.leasedByThisGroup }
                val leasedNodeIds = leasedViewNodes
                    .mapTo(linkedSetOf()) { it.nodeId }
                require(leasedNodeIds.isNotEmpty()) {
                    "Grader group has no active frontier lease"
                }
                require(
                    methodology.requirements.all {
                        it.graderAtlasNodeId in leasedNodeIds
                    }
                ) {
                    "Promoted methodology exceeds the grader group's leased frontier"
                }
                require(
                    methodology.requirements.all { requirement ->
                        requirement.mappedWorkerNodeIds.size == 1 &&
                            requirement.mappedWorkerNodeIds.single() in workerAtlas.nodes &&
                            graderAtlas.nodes[requirement.graderAtlasNodeId]?.let { graderNode ->
                                graderNode.mappedWorkerNodeIds.containsAll(
                                    requirement.mappedWorkerNodeIds
                                ) &&
                                    graderNode.receiverObligationRefs.containsAll(
                                        requirement.mappedReceiverObligationRefs
                                    )
                            } == true
                    }
                ) {
                    "Promoted methodology contains a non-atomic or out-of-scope worker requirement"
                }
                leasedViewNodes.forEach { viewNode ->
                    val coveredWorkerNodeIds = methodology.requirements
                        .filter { it.graderAtlasNodeId == viewNode.nodeId }
                        .flatMapTo(linkedSetOf()) { it.mappedWorkerNodeIds }
                    require(coveredWorkerNodeIds == viewNode.mappedWorkerNodeIds) {
                        "Promoted methodology does not exactly cover its leased worker frontier"
                    }
                }

                val requirementIds = methodology.requirements.associate { draft ->
                    draft.requirementId to GradingRequirementId(
                        AtlasHashing.hashText(
                            "grader-requirement|$runId|$round|$graderGroupId|" +
                                draft.requirementId
                        )
                    )
                }
                val testIds = methodology.automatedTests.associate { draft ->
                    draft.testId to HiddenTestId(
                        AtlasHashing.hashText(
                            "grader-hidden-test|$runId|$round|$graderGroupId|${draft.testId}"
                        )
                    )
                }
                val requirements = methodology.requirements.map { draft ->
                    GradingRequirement(
                        requirementId = requirementIds.getValue(draft.requirementId),
                        runId = runId,
                        graderAtlasHash = graderAtlas.atlasHash,
                        graderAtlasNodeId = draft.graderAtlasNodeId,
                        graderGroupId = graderGroupId,
                        round = round,
                        mappedWorkerNodeIds = draft.mappedWorkerNodeIds,
                        mappedReceiverObligationRefs = draft.mappedReceiverObligationRefs,
                        criterion = draft.criterion,
                        observableEvidence = draft.observableEvidence,
                        passThreshold = draft.passThreshold,
                        failureLevels = draft.failureLevels,
                        adversarialCases = draft.adversarialCases,
                        executableTestIds = draft.executableTestIds.map(testIds::getValue),
                        disclosureSensitivity = draft.disclosureSensitivity,
                        rationale = draft.rationale,
                        noveltyFingerprint = draft.noveltyFingerprint,
                        maximumEvaluationCost = draft.maximumEvaluationCost,
                        origin = GradingRequirementOrigin.GRADER_AUTHORED
                    ).withComputedHash()
                }
                val hiddenTests = methodology.automatedTests.map { draft ->
                    HiddenTestDefinition(
                        testId = testIds.getValue(draft.testId),
                        runId = runId,
                        graderAtlasHash = graderAtlas.atlasHash,
                        graderGroupId = graderGroupId,
                        roundCreated = round,
                        mappedRequirementIds = draft.mappedRequirementIds
                            .mapTo(linkedSetOf(), requirementIds::getValue),
                        executorRef = "grader-regex:${draft.testId}:" +
                            AtlasHashing.hashText(draft.expectedOutputPattern),
                        inputSchemaHash = draft.inputSchemaHash,
                        expectedOutcomeHash = draft.expectedOutcomeHash,
                        difficulty = draft.difficulty,
                        status = HiddenTestStatus.SEALED
                    ).withComputedHash()
                }
                val persisted = graderAuthorityRepository.appendCriteriaBatch(
                    runId,
                    requirements,
                    hiddenTests
                )
                val sealedAt = System.currentTimeMillis()
                val freeze = GraderCriteriaFreeze(
                    freezeId = AtlasHashing.hashText(
                        "grader-criteria-freeze|$runId|$round|$graderGroupId"
                    ),
                    runId = runId,
                    round = round,
                    graderGroupId = graderGroupId,
                    graderAtlasHash = graderAtlas.atlasHash,
                    requirementHashes = persisted.first
                        .mapTo(linkedSetOf(), GradingRequirement::requirementHash),
                    hiddenTestHashes = persisted.second
                        .mapTo(linkedSetOf(), HiddenTestDefinition::testHash),
                    targetSnapshotHashes = targetSnapshotHashes,
                    sealedAtEpochMillis = sealedAt,
                    workerRevealEpochMillis = sealedAt
                ).withComputedHash()
                val persistedFreeze = graderAuthorityRepository.appendCriteriaFreeze(
                    freeze,
                    expectedTargetSnapshotHashes = targetSnapshotHashes
                )
                leasedNodeIds.forEach { nodeId ->
                    val state = requireNotNull(
                        graderAuthorityRepository.currentState(
                            runId,
                            graderAtlas.atlasHash,
                            graderGroupId,
                            nodeId
                        )
                    )
                    val lease = requireNotNull(
                        state.leaseId?.let {
                            graderAuthorityRepository.leaseById(runId, it)
                        }
                    ) {
                        "Leased grader frontier node has no immutable lease authority"
                    }
                    graderNodeLeaseAuthority.commit(
                        atlas = graderAtlas,
                        lease = lease,
                        committedRequirementHashes = persisted.first
                            .filter { it.graderAtlasNodeId == nodeId }
                            .mapTo(linkedSetOf(), GradingRequirement::requirementHash)
                    )
                }
                graderGroupId to persistedFreeze
            }.onFailure { error ->
                Napier.w(
                    "EvoMoliorRunService: grader criteria freeze failed closed for " +
                        "grader=$graderGroupId round=$round: ${error.message}"
                )
            }.getOrNull()
        }.toMap()
    }

    private suspend fun runGraderJudging(
        config: RunConfig,
        graderGroupIds: List<String>,
        competingGroupIds: List<String>,
        round: Int,
        graderGroupManager: GraderGroupManager,
        roundAuthority: RoundAtlasAuthority?
    ): Map<String, String> {
        if (graderJudgingRunner == null || graderGroupIds.isEmpty()) return emptyMap()

        for (graderGroupId in graderGroupIds) {
            emit(EvoMoliorEvent.GraderRoundStarted(config.runId, graderGroupId, round, competingGroupIds))
        }

        try {
            Napier.i("EvoMoliorRunService: starting RoundJudge evaluation round=$round")
            emit(EvoMoliorEvent.EngineProgress(config.runId, "grader_judge_start",
                "RoundJudge starting for round $round — scoring ${competingGroupIds.size} groups"))

            val criteriaFreezes = freezeCompetitiveGraderCriteria(
                config.runId,
                graderGroupIds,
                competingGroupIds,
                round,
                roundAuthority
            )
            val gradingAtlas = roundAuthority?.workerAtlas
                ?: activeWorkerAtlasId?.let { atlasId ->
                    atlasTransactionRepository.currentAtlas(config.runId, atlasId)
                }
            val nodeAssignments = buildNodeGradingAssignments(
                runId = config.runId,
                round = round,
                graderCount = graderGroupIds.size,
                criteriaFreezeHashes = criteriaFreezes.mapValues { it.value.freezeHash },
                competitiveGraderAtlasPresent =
                    roundAuthority != null ||
                        graderAuthorityRepository.currentAtlas(config.runId) != null,
                workerAtlas = gradingAtlas
            )
            val revealedNodeIds = activeWorkerAtlasId?.let { atlasId ->
                atlasTransactionRepository.currentStates(config.runId, atlasId)
                    .values
                    .filter {
                        it.revealedToGroups.isNotEmpty() &&
                            (gradingAtlas == null || it.nodeId in gradingAtlas.nodes)
                    }
                    .mapTo(linkedSetOf()) { it.nodeId }
            }.orEmpty()
            val judgeOutputs = graderJudgingRunner.invoke(
                config.runId,
                config.openRouterApiKey,
                graderGroupIds,
                competingGroupIds,
                round,
                config.objective,
                config.scoringDimensions,
                graderGroupManager,
                nodeAssignments,
                { bundles ->
                    nodeGradeBundlesByRound[round] = completeNoEvidenceNodeGradeBundles(
                        atlas = gradingAtlas,
                        assignments = nodeAssignments,
                        revealedNodeIds = revealedNodeIds,
                        round = round,
                        workerGroupIds = competingGroupIds,
                        graderIds = graderGroupIds,
                        criteriaFreezeHashes = criteriaFreezes.mapValues { it.value.freezeHash },
                        gradedBundles = bundles
                    )
                },
                { progressMsg ->
                    scope.launch {
                        emit(EvoMoliorEvent.EngineProgress(config.runId, "grader_judge", progressMsg))
                    }
                }
            )
            if (graderAuthorityRepository.currentAtlas(config.runId) != null) {
                val exactGraderVerdict = ExactGraderBundleResolver().resolve(
                    runId = config.runId,
                    round = round,
                    expectedGraderGroupIds = graderGroupIds.toSet(),
                    expectedTargetGroupIds =
                        competingGroupIds.mapTo(linkedSetOf(), ::AtlasGroupId),
                    bundles = graderAuthorityRepository.scoreBundlesForRound(
                        config.runId,
                        round
                    )
                )
                exactGraderVerdictsByRound[round] = exactGraderVerdict
                if (exactGraderVerdict.status != ExactGraderRoundStatus.VALID) {
                    nodeGradeBundlesByRound.remove(round)
                    Napier.w(
                        "EvoMoliorRunService: exact grader round failed closed for round=$round: " +
                            exactGraderVerdict.reason
                    )
                } else {
                    persistCompetitiveGraderFitness(
                        runId = config.runId,
                        round = round,
                        graderGroupIds = graderGroupIds,
                        graderAtlas = roundAuthority?.graderAtlas
                            ?: requireNotNull(
                                graderAuthorityRepository.currentAtlas(config.runId)
                            ),
                        bundles = graderAuthorityRepository.scoreBundlesForRound(
                            config.runId,
                            round
                        ),
                        consensusScores = exactGraderVerdict.groupScores
                    )
                    persistEvaluationIndependenceProfile(config)
                }
            }

            val scoresPerGroup = competingGroupIds.associateWith { "[judged by RoundJudge]" }
            val totalScores = scoresPerGroup.size * config.scoringDimensions.size
            for (graderGroupId in graderGroupIds) {
                emit(EvoMoliorEvent.GraderRoundCompleted(
                    runId = config.runId,
                    graderGroupId = graderGroupId,
                    roundIndex = round,
                    scoresPerGroup = scoresPerGroup,
                    totalScoresCollected = totalScores
                ))
            }
            Napier.i("EvoMoliorRunService: RoundJudge evaluation complete round=$round")
            return judgeOutputs
        } catch (e: Exception) {
            if (graderAuthorityRepository.currentAtlas(config.runId) != null) {
                nodeGradeBundlesByRound.remove(round)
            }
            Napier.w("EvoMoliorRunService: RoundJudge evaluation failed round=$round: ${e.message}")
            emit(EvoMoliorEvent.EngineProgress(config.runId, "grader_judge_error",
                "RoundJudge failed for round $round: ${e.message?.take(150)}"))
            return emptyMap()
        }
    }

    private suspend fun snapshotRoundAtlasAuthority(runId: String): RoundAtlasAuthority? {
        val graderAtlas = graderAuthorityRepository.currentAtlas(runId) ?: return null
        val workerAtlas = requireNotNull(atlasTransactionRepository.currentAtlas(runId)) {
            "Grader Atlas exists without a sealed worker Atlas"
        }
        return RoundAtlasAuthority(workerAtlas, graderAtlas)
    }

    private suspend fun processOutlierNoveltyAtRoundBoundary(
        config: RunConfig,
        workerGroupIds: List<String>,
        graderGroupIds: List<String>,
        round: Int
    ): OutlierNoveltyMetrics? {
        val atlasId = activeWorkerAtlasId
        if (graderGroupIds.isEmpty() || atlasId == null) return null
        val result = OutlierNoveltyRoundProcessor(
            substrateFactory = substrateFactory,
            atlasRepository = atlasTransactionRepository,
            assessmentRunner = { proposals ->
                graderNoveltyAssessmentRunner?.invoke(
                    config.runId,
                    config.openRouterApiKey,
                    graderGroupIds.toSet(),
                    round,
                    config.objective,
                    proposals
                ) { progressMsg ->
                    scope.launch {
                        emit(
                            EvoMoliorEvent.EngineProgress(
                                config.runId,
                                "outlier_novelty_grading",
                                progressMsg
                            )
                        )
                    }
                }.orEmpty()
            }
        ).process(
            runId = config.runId,
            atlasId = atlasId,
            workerGroupIds = workerGroupIds,
            graderGroupIds = graderGroupIds.toSet(),
            round = round
        )
        outlierNoveltyMetricsByRound[round] = result.metrics
        atlasTransactionRepository.currentProjections(
            config.runId,
            result.atlas.atlasId
        )?.let { projections ->
            activeCoordinator?.registerResourceNodes(
                ResourceAtlasProjectionAdapter.fromProjection(projections.resources)
            )
        }
        emit(
            EvoMoliorEvent.EngineProgress(
                config.runId,
                "outlier_novelty",
                "Round $round novelty: proposals=${result.metrics.proposalCount}, " +
                    "acceptedShadow=${result.metrics.acceptedShadowNodeCount}, " +
                    "tested=${result.metrics.testedProposalCount}, " +
                    "amendments=${result.metrics.acceptedAmendmentCount}"
            )
        )
        return result.metrics
    }

    private suspend fun visibleShadowNodes(
        runId: String,
        groupId: AtlasGroupId,
        round: Int
    ): List<VisibleShadowNodeCard> {
        val repository = OutlierNoveltyRepository(substrateFactory.getAtlas(runId))
        return repository.proposalsBeforeRound(runId, round).mapNotNull { proposal ->
            if (proposal.groupId != groupId) return@mapNotNull null
            val currentState = repository.lifecycle(proposal.proposalId).lastOrNull()?.toState
            if (currentState != com.achulkov.evomolior.core.atlas.ShadowNodeState.SHADOW_NODE) {
                return@mapNotNull null
            }
            val screening = repository.screeningForProposal(proposal.proposalHash)
                ?.takeIf { it.hasValidHash() }
                ?: return@mapNotNull null
            val verdict = repository.verdictForProposal(proposal.proposalHash)
                ?.takeIf {
                    it.hasValidHash() &&
                        it.decision ==
                        com.achulkov.evomolior.core.atlas.ShadowNodeDecision.ACCEPT
                }
                ?: return@mapNotNull null
            VisibleShadowNodeCard(
                proposalId = proposal.proposalId,
                proposalHash = proposal.proposalHash,
                noveltyType = proposal.noveltyType,
                affectedVisibleNodeIds = proposal.affectedVisibleNodeIds,
                proposedVector = proposal.proposedVector,
                falsifiablePrediction = proposal.falsifiablePrediction,
                minimalTestOrArtifact = proposal.minimalTestOrArtifact,
                expectedReceiverImpact = proposal.expectedReceiverImpact,
                screeningHash = screening.screeningHash,
                graderVerdictHash = verdict.verdictHash
            )
        }.sortedBy(VisibleShadowNodeCard::proposalId)
    }

    internal suspend fun persistCompetitiveGraderFitness(
        runId: String,
        round: Int,
        graderGroupIds: List<String>,
        graderAtlas: com.achulkov.evomolior.core.grader.GraderAtlas,
        bundles: List<com.achulkov.evomolior.core.grader.GraderScoreBundle>,
        consensusScores: Map<AtlasGroupId, Map<String, Double?>>
    ) {
        val requirementsByGroup = graderGroupIds.associateWith { graderGroupId ->
            graderAuthorityRepository.requirementsForRound(runId, round, graderGroupId)
        }
        val previousBundles = if (round > 0) {
            graderAuthorityRepository.scoreBundlesForRound(runId, round - 1)
        } else {
            emptyList()
        }
        val scorer = com.achulkov.evomolior.competition.grader.GraderQualityScorer()
        graderGroupIds.forEach { graderGroupId ->
            val freeze = requireNotNull(
                graderAuthorityRepository.criteriaFreezeForRound(
                    runId,
                    round,
                    graderGroupId
                )
            )
            val requirements = requirementsByGroup.getValue(graderGroupId)
            val tests = graderAuthorityRepository.hiddenTestsForFreeze(freeze)
            val groupBundles = bundles.filter { it.graderGroupId == graderGroupId }
            val scoreDifferences = groupBundles.flatMap { bundle ->
                bundle.groupScores.mapNotNull { (dimensionId, score) ->
                    val own = score.value ?: return@mapNotNull null
                    val consensus = consensusScores[bundle.targetGroupId]
                        ?.get(dimensionId)
                        ?: return@mapNotNull null
                    kotlin.math.abs(own - consensus)
                }
            }
            val calibration = if (scoreDifferences.isEmpty()) {
                0.0
            } else {
                (1.0 - scoreDifferences.average()).coerceIn(0.0, 1.0)
            }
            val targetTotals = groupBundles.mapNotNull { bundle ->
                bundle.groupScores.values.mapNotNull { it.value }
                    .takeIf { it.isNotEmpty() }
                    ?.average()
            }
            val discrimination = if (targetTotals.size < 2) {
                0.0
            } else {
                (targetTotals.max() - targetTotals.min()).coerceIn(0.0, 1.0)
            }
            val previousByTarget = previousBundles
                .filter { it.graderGroupId == graderGroupId }
                .associate { bundle ->
                    bundle.targetGroupId to bundle.groupScores.values
                        .mapNotNull { it.value }
                        .averageOrNull()
                }
            val predictiveDifferences = consensusScores.mapNotNull { (target, scores) ->
                val previous = previousByTarget[target] ?: return@mapNotNull null
                val current = scores.values.mapNotNull { it }.averageOrNull()
                    ?: return@mapNotNull null
                kotlin.math.abs(previous - current)
            }
            val predictiveValidity = if (predictiveDifferences.isEmpty()) {
                0.0
            } else {
                (1.0 - predictiveDifferences.average()).coerceIn(0.0, 1.0)
            }
            val detectedFailures = groupBundles.sumOf { bundle ->
                bundle.requirementResults.values.count {
                    it.status == com.achulkov.evomolior.core.grader.RequirementResultStatus.FAIL ||
                        it.status ==
                        com.achulkov.evomolior.core.grader.RequirementResultStatus.CONTESTED
                }
            }
            val previousFailureFingerprints = if (round > 0) {
                val previousRequirements = graderAuthorityRepository.requirementsForRound(
                    runId,
                    round - 1,
                    graderGroupId
                ).associateBy { it.requirementId }
                previousBundles
                    .filter { it.graderGroupId == graderGroupId }
                    .flatMap { it.requirementResults.entries }
                    .filter {
                        it.value.status ==
                            com.achulkov.evomolior.core.grader.RequirementResultStatus.FAIL
                    }
                    .mapNotNullTo(linkedSetOf()) {
                        previousRequirements[it.key]?.noveltyFingerprint
                    }
            } else {
                emptySet()
            }
            val improvedFingerprints = groupBundles
                .flatMap { it.requirementResults.entries }
                .filter {
                    it.value.status ==
                        com.achulkov.evomolior.core.grader.RequirementResultStatus.PASS
                }
                .mapNotNullTo(linkedSetOf()) { result ->
                    requirements.find { it.requirementId == result.key }
                        ?.noveltyFingerprint
                        ?.takeIf(previousFailureFingerprints::contains)
                }
            val actionability = if (previousFailureFingerprints.isEmpty()) {
                0.0
            } else {
                improvedFingerprints.size.toDouble() / previousFailureFingerprints.size
            }
            val baseEvidence =
                com.achulkov.evomolior.competition.grader.GraderQualityEvidence(
                    runId = runId,
                    round = round,
                    graderGroupId = graderGroupId,
                    receiverObligationUniverse = graderAtlas.nodes.values
                        .flatMapTo(linkedSetOf()) { it.receiverObligationRefs },
                    workerNodeUniverse = graderAtlas.nodes.values
                        .flatMapTo(linkedSetOf()) { it.mappedWorkerNodeIds },
                    requirements = requirements,
                    hiddenTests = tests,
                    peerNoveltyFingerprints = requirementsByGroup
                        .filterKeys { it != graderGroupId }
                        .values
                        .flatten()
                        .mapTo(linkedSetOf()) { it.noveltyFingerprint },
                    calibrationScore = 0.0,
                    discriminationScore = 0.0,
                    predictiveValidity = 0.0,
                    actionabilityScore = actionability,
                    detectedFailureCount = 0,
                    totalEvaluationCost = requirements.sumOf {
                        it.maximumEvaluationCost
                    }
                )
            graderAuthorityRepository.appendFitnessRecord(
                scorer.score(
                    baseEvidence,
                    com.achulkov.evomolior.core.grader.GraderFitnessPlane.REQUIREMENT_DISCOVERY
                )
            )
            graderAuthorityRepository.appendFitnessRecord(
                scorer.score(
                    baseEvidence.copy(
                        calibrationScore = calibration,
                        discriminationScore = discrimination,
                        predictiveValidity = predictiveValidity,
                        detectedFailureCount = detectedFailures
                    ),
                    com.achulkov.evomolior.core.grader.GraderFitnessPlane.JUDGMENT_QUALITY
                )
            )
        }
    }

    private fun List<Double>.averageOrNull(): Double? =
        if (isEmpty()) null else average()

    private suspend fun persistEvaluationIndependenceProfile(config: RunConfig) {
        val envelope = requireNotNull(activeWorkerExecutionEnvelope) {
            "Competitive grader independence requires the sealed execution envelope"
        }
        val profile = deriveEvaluationIndependenceProfile(
            config,
            envelope,
            activeModelAssignments,
            activeGraderStartingField?.modelAssignment.orEmpty()
        )
        graderAuthorityRepository.appendIndependenceProfile(config.runId, profile)
        emit(
            EvoMoliorEvent.EngineProgress(
                config.runId,
                "grader_independence",
                "Evaluation independence: ${profile.independenceLabel.name}; " +
                    "families=${profile.distinctModelFamilies}, " +
                    "providers=${profile.distinctProviders}"
            )
        )
    }

    internal fun deriveEvaluationIndependenceProfile(
        config: RunConfig,
        envelope: ExecutionEnvelope,
        modelAssignments: Map<String, String>,
        graderModelAssignments: Map<String, String> = emptyMap()
    ): com.achulkov.evomolior.core.grader.EvaluationIndependenceProfile {
        val cardsByModel = envelope.availableModels.associateBy { it.modelId }
        val assignments = modelAssignments + config.modelOverrides
        val workerModelIds = assignments.mapNotNull { (roleName, modelId) ->
            Role.entries.firstOrNull { it.name == roleName }
                ?.takeUnless { it.isGraderRole || it.name.startsWith("Council") }
                ?.let { modelId }
        }.toSet()
        val graderAssignments = assignments + graderModelAssignments
        val graderModelIds = graderAssignments.mapNotNull { (roleName, modelId) ->
            Role.entries.firstOrNull { it.name == roleName }
                ?.takeIf(Role::isGraderRole)
                ?.let { modelId }
        }.toMutableSet().apply {
            if (isEmpty()) {
                add(
                    envelope.availableModels
                        .filter { "judge" in it.capabilities }
                        .minByOrNull { it.modelId }
                        ?.modelId
                        ?: error("No grader-capable model exists in the execution envelope")
                )
            }
        }
        require(workerModelIds.isNotEmpty()) {
            "No actual worker model assignments are available for independence profiling"
        }
        val selectedCards = (workerModelIds + graderModelIds).map { modelId ->
            requireNotNull(cardsByModel[modelId]) {
                "Selected model '$modelId' has no structured capability metadata"
            }
        }
        val workerFamilies = workerModelIds.mapTo(linkedSetOf()) {
            cardsByModel.getValue(it).familyId
        }
        val graderFamilies = graderModelIds.mapTo(linkedSetOf()) {
            cardsByModel.getValue(it).familyId
        }
        val providers = selectedCards.mapTo(linkedSetOf()) { card ->
            card.providerId.ifBlank { config.llmBackend.id }
        }
        return com.achulkov.evomolior.core.grader.EvaluationIndependenceProfiler.profile(
                workerFamilies = workerFamilies,
                graderFamilies = graderFamilies,
                providers = providers,
                // All current grader groups use the same frozen RolePromptBuilder template.
                sharedPromptTemplateFraction = 1.0
            )
    }

    private suspend fun buildNodeGradingAssignments(
        runId: String,
        round: Int,
        graderCount: Int,
        criteriaFreezeHashes: Map<String, String> = emptyMap(),
        competitiveGraderAtlasPresent: Boolean = false,
        workerAtlas: NodeAtlas? = null
    ): List<NodeGradingAssignment> {
        if (graderCount <= 0) return emptyList()
        if (competitiveGraderAtlasPresent && criteriaFreezeHashes.size != graderCount) {
            Napier.w(
                "EvoMoliorRunService: exact node assignments withheld because competitive " +
                    "criteria freeze cardinality is ${criteriaFreezeHashes.size}/$graderCount"
            )
            return emptyList()
        }
        val atlasId = activeWorkerAtlasId ?: return emptyList()
        val atlas = workerAtlas
            ?: atlasTransactionRepository.currentAtlas(runId, atlasId)
            ?: return emptyList()
        return sealedNodeEvidenceByRound[round]
            .orEmpty()
            .toList()
            .sortedWith(compareBy<NodeEvidencePacket>({ it.groupId.value }, { it.nodeId.value }, { it.packetId }))
            .mapNotNull { packet ->
                val node = atlas.nodes[packet.nodeId] ?: return@mapNotNull null
                val criteriaHash = AtlasHashing.hashNodeGradingAuthority(
                    node,
                    criteriaFreezeHashes
                )
                val capturePolicyHash = AtlasHashing.hashCapturePolicy(node.capturePolicy)
                val assignmentId = AtlasHashing.hashText(
                    listOf(
                        "node-grading-assignment",
                        packet.packetHash,
                        criteriaHash,
                        capturePolicyHash,
                        graderCount.toString()
                    ).joinToString("|")
                )
                NodeGradingAssignment(
                    assignmentId = assignmentId,
                    packet = packet,
                    criteriaHash = criteriaHash,
                    graderCriteriaFreezeHashes = criteriaFreezeHashes,
                    capturePolicy = node.capturePolicy,
                    capturePolicyHash = capturePolicyHash,
                    requiredQuorum = graderCount
                ).withComputedHash()
            }
    }

    private fun completeNoEvidenceNodeGradeBundles(
        atlas: NodeAtlas?,
        assignments: List<NodeGradingAssignment>,
        revealedNodeIds: Set<NodeId>,
        round: Int,
        workerGroupIds: List<String>,
        graderIds: List<String>,
        criteriaFreezeHashes: Map<String, String>,
        gradedBundles: List<NodeGradeBundle>
    ): List<NodeGradeBundle> {
        atlas ?: return gradedBundles
        val representedNodeIds = linkedSetOf<NodeId>().apply {
            addAll(revealedNodeIds)
            addAll(assignments.map { it.packet.nodeId })
        }
        val existing = gradedBundles.associateBy { it.nodeId to it.groupId }
        val completed = representedNodeIds.flatMap { nodeId ->
            val node = atlas.nodes[nodeId] ?: return@flatMap emptyList()
            val criteriaHash = AtlasHashing.hashNodeGradingAuthority(
                node,
                criteriaFreezeHashes
            )
            val capturePolicyHash = AtlasHashing.hashCapturePolicy(node.capturePolicy)
            workerGroupIds.map { groupId ->
                existing[nodeId to AtlasGroupId(groupId)] ?: NodeGradeBundle(
                    bundleId = AtlasHashing.hashText(
                        listOf("node-grade-no-evidence", atlas.runId, round.toString(), nodeId.value, groupId)
                            .joinToString("|")
                    ),
                    runId = atlas.runId,
                    round = round,
                    groupId = AtlasGroupId(groupId),
                    nodeId = nodeId,
                    atlasVersion = atlas.version,
                    atlasHash = atlas.atlasHash,
                    nodePayloadHash = node.payloadHash,
                    criteriaHash = criteriaHash,
                    capturePolicyHash = capturePolicyHash,
                    verdict = NodeGradeBundleVerdict.NO_EVIDENCE,
                    requiredQuorum = graderIds.size,
                    graderIds = graderIds.toSet(),
                    completedGraderIds = graderIds.toSet(),
                    graderResponseHashes = graderIds.mapTo(linkedSetOf()) { graderId ->
                        AtlasHashing.hashText(
                            listOf(
                                "node-grade-no-evidence-response",
                                atlas.atlasHash,
                                nodeId.value,
                                groupId,
                                graderId,
                                round.toString()
                            ).joinToString("|")
                        )
                    }
                ).withComputedHash()
            }
        }
        return completed.sortedWith(
            compareBy<NodeGradeBundle>({ it.nodeId.value }, { it.groupId.value }, { it.bundleHash })
        )
    }

    /**
     * Computes substrate-based scores for each group from its Room DB.
     *
     * These scores drive deterministic telemetry and are the explicit fallback when grader
     * civilisations do not provide complete round coverage.
     *
     * Score mapping (approximate):
     * - `kb_density`      → count of active KB entries (normalised)
     * - `kg_coherence`    → count of active KG facts (normalised)
     * - `tool_utility`    → count of active tools * avg success rate (normalised)
     * - `plan_execution`  → WIP task completion rate (normalised)
     * - (default)         → 0.0 with explanation logged
     */
    private suspend fun computeSubstrateScores(
        config: RunConfig,
        groupIds: List<String>,
        round: Int,
        roundSubmissionSummaries: Map<String, String?>
    ) = supervisorScope {
        groupIds.map { groupId ->
            async {
                try {
                    val db = substrateFactory.get(config.runId, groupId)
                    val snapshot = CanonSnapshotExporter.export(db, config.runId, groupId, round)
                    val sealedAssessment = activeSealedSuiteAssessment(
                        runId = config.runId,
                        groupId = groupId,
                        roundIndex = round,
                        canonicalSnapshot = snapshot,
                        roundSubmissionSummary = roundSubmissionSummaries[groupId],
                        scoringDimensions = normaliseScoringDimensions(config.scoringDimensions)
                    )
                    val substrateAssessment = assessSubstrateGroup(
                        db = db,
                        runId = config.runId,
                        groupId = groupId,
                        round = round,
                        scoringDimensions = normaliseScoringDimensions(config.scoringDimensions),
                        runObjective = config.objective
                    )
                    latestGroupDimensionScores[groupId] = substrateAssessment.dimensionScores.associate { it.dimensionId to it.rawScore }
                    latestGroupSubstrateAssessments[groupId] = substrateAssessment
                    if (sealedAssessment != null) {
                        latestGroupRoundAssessments[groupId] = sealedAssessment
                    } else {
                        latestGroupRoundAssessments.remove(groupId)
                    }

                    val substrateScoresSummary = substrateAssessment.dimensionScores.joinToString(", ") {
                        "${it.dimensionId}=${String.format(java.util.Locale.ROOT, "%.1f", it.rawScore)}"
                    }
                    Napier.i(
                        "[$groupId] Substrate scores round=$round: $substrateScoresSummary " +
                            "kb=${substrateAssessment.kbCount} kg=${substrateAssessment.kgCount} " +
                            "tools=${substrateAssessment.toolCount} wip=${substrateAssessment.completedWipTasks}/${substrateAssessment.totalWipTasks}"
                    )
                    if (sealedAssessment != null) {
                        val sealedScoresSummary = sealedAssessment.dimensionScores.joinToString(", ") {
                            "${it.dimensionId}=${String.format(java.util.Locale.ROOT, "%.1f", it.rawScore)}"
                        }
                        Napier.i(
                            "[$groupId] Supplemental sealed-suite scores round=$round: $sealedScoresSummary " +
                                "tests=${sealedAssessment.automatedTestsPassed}/${sealedAssessment.automatedTestsPassed + sealedAssessment.automatedTestsFailed} " +
                                "mandatoryFailure=${sealedAssessment.mandatoryFailure}"
                        )
                    }
                    emit(EvoMoliorEvent.EngineProgress(config.runId, "scoring",
                        "[$groupId] Scores submitted round=$round: $substrateScoresSummary"))
                } catch (e: Exception) {
                    Napier.w("EvoMoliorRunService: score computation failed for group=$groupId round=$round: ${e.message}")
                }
            }
        }.awaitAll()
    }

    private fun selectAuthoritativeRoundScores(
        round: Int,
        graderRoundResult: com.achulkov.evomolior.core.model.GradingResult?
    ) {
        if (graderRoundResult == null) {
            authoritativeGraderRoundResults.remove(round)
            return
        }
        authoritativeGraderRoundResults[round] = graderRoundResult
        graderRoundResult.scores.forEach { score ->
            latestGroupDimensionScores[score.groupId] =
                score.dimensionScores.associate { it.dimensionId to it.rawScore }
        }
    }

    private suspend fun assessSubstrateGroup(
        db: EvoMoliorDatabase,
        runId: String,
        groupId: String,
        round: Int,
        scoringDimensions: List<ScoringDimension>,
        runObjective: String
    ): SubstrateRoundAssessment = coroutineScope {
        val kbCountDeferred = async { db.knowledgeBaseDao().countActive(runId, groupId) }
        val kgCountDeferred = async { db.knowledgeGraphFactDao().countActive(runId, groupId) }
        val kgConflictDeferred = async { db.knowledgeGraphFactDao().countConflicts(runId, groupId) }
        val toolCountDeferred = async { db.toolRegistryDao().getActive(runId, groupId, null).size }
        val toolFailureRateDeferred = async { db.toolRegistryDao().getAverageFailureRate(runId, groupId) ?: 0.0 }
        val wipCompletedDeferred = async { db.wipTaskDao().countCompleted(runId, groupId) }
        val wipTotalDeferred = async { db.wipTaskDao().countAll(runId, groupId) }

        val kbCount = kbCountDeferred.await()
        val kgCount = kgCountDeferred.await()
        val kgConflicts = kgConflictDeferred.await()
        val toolCount = toolCountDeferred.await()
        val toolFailureRate = toolFailureRateDeferred.await().coerceIn(0.0, 1.0)
        val completedWipTasks = wipCompletedDeferred.await()
        val totalWipTasks = wipTotalDeferred.await()

        val toolSuccessRate = (1.0 - toolFailureRate).coerceIn(0.0, 1.0)
        val requirements = detectDeclaredSubstrateRequirements(runObjective)
        val failureModes = buildList {
            if (requirements.requiresKg && kgCount <= 0) add("missing_required_kg_fact")
            if (requirements.requiresTool && toolCount <= 0) add("missing_required_tool")
        }
        val mandatorySubstrateFailure = failureModes.isNotEmpty()
        val dimensionScores = scoringDimensions.map { dimension ->
            val baseRawScore = substrateRawScore(
                dimensionId = dimension.id,
                maxScore = dimension.maxScore,
                kbCount = kbCount,
                kgCount = kgCount,
                kgConflicts = kgConflicts,
                toolCount = toolCount,
                toolSuccessRate = toolSuccessRate,
                completedWipTasks = completedWipTasks,
                totalWipTasks = totalWipTasks
            )
            val rawScore = if (mandatorySubstrateFailure) 0.0 else baseRawScore
            DimensionScore(
                dimensionId = dimension.id,
                rawScore = rawScore,
                justification = buildSubstrateJustification(
                    groupId = groupId,
                    round = round,
                    dimensionId = dimension.id,
                    kbCount = kbCount,
                    kgCount = kgCount,
                    kgConflicts = kgConflicts,
                    toolCount = toolCount,
                    toolSuccessRate = toolSuccessRate,
                    completedWipTasks = completedWipTasks,
                    totalWipTasks = totalWipTasks,
                    failureModes = failureModes
                )
            )
        }

        val totalWeight = scoringDimensions.sumOf { it.weight }.takeIf { it > 0.0 } ?: 1.0
        val overallScore = scoringDimensions.sumOf { dimension ->
            val raw = dimensionScores.firstOrNull { it.dimensionId == dimension.id }?.rawScore ?: 0.0
            (raw / dimension.maxScore) * (dimension.weight / totalWeight)
        }.coerceIn(0.0, 1.0)

        SubstrateRoundAssessment(
            dimensionScores = dimensionScores,
            overallScore = overallScore,
            kbCount = kbCount,
            kgCount = kgCount,
            toolCount = toolCount,
            toolSuccessRate = toolSuccessRate,
            completedWipTasks = completedWipTasks,
            totalWipTasks = totalWipTasks,
            failureModesTriggered = failureModes
        )
    }

    private suspend fun activeSealedSuiteAssessment(
        runId: String,
        groupId: String,
        roundIndex: Int,
        canonicalSnapshot: String,
        roundSubmissionSummary: String?,
        scoringDimensions: List<ScoringDimension>
    ): DeterministicRoundAssessment? {
        val suiteJson = activeSealedTestSuiteJson.takeIf { it.isNotBlank() } ?: return null
        val toolExecutions = ToolInfrastructureRepository(
            substrateFactory.get(runId, groupId)
        ).executionsForGroupThroughRound(runId, groupId, roundIndex)
        activeSealedTestSuiteValidationContext?.let { context ->
            return sealedTestSuiteEvaluator.assessGroup(
                suiteJson = suiteJson,
                validationContext = context,
                input = SealedTestEvaluationInputV3(
                    runId = runId,
                    groupId = groupId,
                    roundIndex = roundIndex,
                    finalOutput = roundSubmissionSummary
                        ?.let { sealedTestOutputArtifact(it, context) },
                    promotionExecutions = toolExecutions
                ),
                scoringDimensions = scoringDimensions
            )
        }
        val suiteHash = activeSealedTestSuiteHash.ifBlank { canonicalTestSuiteHash(suiteJson) }
        return sealedTestSuiteEvaluator.assessGroup(
            runId = runId,
            groupId = groupId,
            roundIndex = roundIndex,
            suiteJson = suiteJson,
            suiteHash = suiteHash,
            canonicalSnapshot = canonicalSnapshot,
            scoringDimensions = scoringDimensions,
            toolExecutions = toolExecutions
        )
    }

    private fun sealedTestOutputArtifact(
        roundSubmissionSummary: String,
        context: SealedTestSuiteV3ValidationContext
    ): SealedTestOutputArtifactV3? {
        val outputSpec = FinalOutputSpec(
            relativePath = context.finalOutputSpec.relativePath,
            mediaType = context.finalOutputSpec.mediaType,
            description = context.finalOutputSpec.description
        )
        val candidate = extractRoundCompletionCandidate(roundSubmissionSummary, outputSpec)
        return extractGeneratedOutputFile(candidate, outputSpec)
            ?.let(::sealedTestOutputArtifact)
    }

    private fun sealedTestOutputArtifact(
        output: GeneratedOutputFile
    ): SealedTestOutputArtifactV3 {
        require(
            AtlasHashing.constantTimeEquals(
                output.contentHash,
                AtlasHashing.hashText(output.content)
            )
        ) {
            "Generated output content hash changed before sealed-test evaluation"
        }
        return SealedTestOutputArtifactV3(
            relativePath = output.relativePath,
            mediaType = output.mediaType.substringBefore(';').trim().lowercase(Locale.ROOT),
            content = output.content,
            contentHash = output.contentHash
        )
    }

    private data class DeclaredSubstrateRequirements(
        val requiresKg: Boolean,
        val requiresTool: Boolean
    )

    private fun detectDeclaredSubstrateRequirements(runObjective: String): DeclaredSubstrateRequirements {
        val normalized = runObjective.lowercase(Locale.ROOT)
        val hasRequirementLanguage = listOf("mandatory", "must", "required", "success criteria")
            .any { it in normalized }
        val requiresKg = hasRequirementLanguage && listOf(
            "kg fact",
            "kg facts",
            "emit_fact",
            "kgsubmissions",
            "knowledge graph"
        ).any { it in normalized }
        val requiresTool = hasRequirementLanguage && listOf(
            "tool artifact",
            "tool artifacts",
            "tool submission",
            "tool submissions",
            "toolsubmissions",
            "reusable tool"
        ).any { it in normalized }
        return DeclaredSubstrateRequirements(
            requiresKg = requiresKg,
            requiresTool = requiresTool
        )
    }

    private fun buildRequiredSubstrateWinnerCaveat(
        runObjective: String,
        substrateHealthByGroup: List<RunSubstrateHealthSummary>
    ): String? {
        if (substrateHealthByGroup.isEmpty()) return null
        val requirements = detectDeclaredSubstrateRequirements(runObjective)
        val missingRequiredSubstrate = buildList {
            if (requirements.requiresKg && substrateHealthByGroup.all { it.kgCount <= 0 }) {
                add("active KG facts")
            }
            if (requirements.requiresTool && substrateHealthByGroup.all { it.activeToolCount <= 0 }) {
                add("active tools")
            }
        }
        if (missingRequiredSubstrate.isEmpty()) return null
        return "Required substrate caveat: all scored groups are missing ${missingRequiredSubstrate.joinToString(" and ")}; " +
            "winner is a tie-break among zeroed required-substrate scores, not evidence of task success."
    }

    /**
     * Resolves the publication winner from the authoritative scoring result only.
     *
     * Compatibility ranks, summaries, and substrate telemetry remain diagnostics only. They
     * cannot convert unresolved scoring into winner or accepted-publication authority.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun selectFinalWinnerId(
        config: RunConfig,
        finalRoundIndex: Int,
        finalRoundResult: com.achulkov.evomolior.core.model.GradingResult?,
        groupSummaries: List<GroupSummary>,
        submissionSummariesByGroup: Map<String, String?>,
        substrateHealthByGroup: List<RunSubstrateHealthSummary>
    ): String? {
        val winnerId = finalRoundResult
            ?.takeIf {
                it.resolution == com.achulkov.evomolior.core.model.ScoringResolution.RESOLVED
            }
            ?.winnerId
            ?: return null
        val exactVerdict = exactGraderVerdictsByRound[finalRoundIndex] ?: return null
        val expectedTargets = groupSummaries.mapTo(linkedSetOf()) {
            AtlasGroupId(it.groupId)
        }
        val expectedBundleCount = activeGraderGroupIds.size * expectedTargets.size
        return winnerId.takeIf {
            activeGraderGroupIds.isNotEmpty() &&
                exactVerdict.status == ExactGraderRoundStatus.VALID &&
                exactVerdict.acceptedBundleHashes.size == expectedBundleCount &&
                exactVerdict.targetSnapshotHashes.keys == expectedTargets &&
                AtlasGroupId(winnerId) in exactVerdict.targetSnapshotHashes
        }
    }

    private fun hasEmbeddedFinalDeliverable(summary: String): Boolean {
        val markerIndex = summary.indexOf("FINAL DELIVERABLE HTML", ignoreCase = true)
        if (markerIndex < 0) return false
        return summary.indexOf(
            "<!doctype html",
            startIndex = markerIndex,
            ignoreCase = true
        ) >= 0 ||
            summary.indexOf("<html", startIndex = markerIndex, ignoreCase = true) >= 0
    }

    private fun substrateRawScore(
        dimensionId: String,
        maxScore: Double,
        kbCount: Int,
        kgCount: Int,
        kgConflicts: Int,
        toolCount: Int,
        toolSuccessRate: Double,
        completedWipTasks: Int,
        totalWipTasks: Int
    ): Double {
        val raw = when (dimensionId) {
            "kb_density" -> kbCount * 10.0
            "kg_coherence" -> {
                val coherenceFactor = if (kgCount <= 0) 0.0
                else (1.0 - (kgConflicts.toDouble() / kgCount.toDouble())).coerceIn(0.0, 1.0)
                kgCount * 10.0 * coherenceFactor
            }
            "tool_utility" -> toolCount * toolSuccessRate * 20.0
            "plan_execution" -> if (totalWipTasks > 0) {
                (completedWipTasks.toDouble() / totalWipTasks.toDouble()) * maxScore
            } else 0.0
            else -> 0.0
        }
        return raw.coerceIn(0.0, maxScore)
    }

    private fun buildSubstrateJustification(
        groupId: String,
        round: Int,
        dimensionId: String,
        kbCount: Int,
        kgCount: Int,
        kgConflicts: Int,
        toolCount: Int,
        toolSuccessRate: Double,
        completedWipTasks: Int,
        totalWipTasks: Int,
        failureModes: List<String> = emptyList()
    ): String {
        val base = when (dimensionId) {
            "kb_density" ->
                "[$groupId] round $round: $kbCount active KB entries in substrate."
            "kg_coherence" ->
                "[$groupId] round $round: $kgCount active KG facts with $kgConflicts conflicts."
            "tool_utility" ->
                "[$groupId] round $round: $toolCount active tools with ${(toolSuccessRate * 100).toInt()}% average success."
            "plan_execution" ->
                "[$groupId] round $round: $completedWipTasks/$totalWipTasks WIP tasks completed."
            else ->
                "[$groupId] round $round: no substrate scoring heuristic configured for '$dimensionId'."
        }
        return if (failureModes.isEmpty()) {
            base
        } else {
            "$base Mandatory substrate requirement failure(s): ${failureModes.joinToString(", ")}."
        }
    }

    /**
     * Ticks the MetaPlanner runner once per round for each group.
     *
     * WiP completions reset the group counter; a round with no completions increments
     * the stasis count and may trigger a MetaPlanner spawn once the threshold is hit.
     *
     * @param config Current run configuration.
     * @param groupIds Competing group IDs for the run.
     * @param round Current round index.
     * @param completedWipTasksByGroup WiP task IDs completed during this round.
     */
    private suspend fun processMetaPlannerSignals(
        config: RunConfig,
        groupIds: List<String>,
        round: Int,
        completedWipTasksByGroup: Map<String, List<String>>
    ) {
        val runner = metaPlannerRunner ?: return
        for (groupId in groupIds) {
            if (completedWipTasksByGroup[groupId].isNullOrEmpty()) {
                runner.onGenerationTick(groupId) { targetGroupId, role ->
                    spawnMetaPlannerForGroup(config, groupIds, targetGroupId, role, round)
                }
            } else {
                runner.onWipCompleted(groupId)
            }
        }
    }

    /**
     * Spawns a MetaPlanner as a special-purpose agent with no WIP claim and no legacy protocol.
     *
     * @param config Current run configuration.
     * @param groupIds All competing group IDs in this run.
     * @param targetGroupId The group that should receive the MetaPlanner.
     * @param role The role requested by the runner.
     * @param round Current round index.
     */
    private suspend fun spawnMetaPlannerForGroup(
        config: RunConfig,
        groupIds: List<String>,
        targetGroupId: String,
        role: com.achulkov.evomolior.core.model.Role,
        round: Int
    ) {
        val lm = liveLifecycleManagers[targetGroupId] ?: return
        val profileType = activeGroupProfiles.getOrNull(groupIds.indexOf(targetGroupId)) ?: return
        val profile = GroupProfile.default(profileType)
        val budgetPerAgent = profile.spawnBudget * 2.0

        emit(EvoMoliorEvent.EngineProgress(
            config.runId,
            "meta_planner_spawn",
            "[$targetGroupId] Spawning MetaPlanner after sustained stasis at round $round"
        ))

        maybeSpawnNestedHierarchyForGroup(
            config = config,
            targetGroupId = targetGroupId,
            round = round,
            profile = profile
        )

        lm.spawnSpecialAgent(
            role = role,
            generation = round + 1,
            budgetPerAgent = budgetPerAgent,
            stageDisclosure = "",
            modelAssignments = activeModelAssignments,
            modelPools = activeModelPools,
            rolePrompt = activeRolePrompts[role.name] ?: "You are a MetaPlanner. Rebuild the plan registry and unstitch stasis.",
            scope = scope
        )
    }

    /**
     * Narrow Phase 9 production trigger: when a group hits sustained planning stasis and still
     * has multiple unresolved WiP tasks, spin up one nested inner instance for that group.
     *
     * This intentionally fires at most once per group per run until a richer planner-owned
     * decomposition policy exists.
     */
    private suspend fun maybeSpawnNestedHierarchyForGroup(
        config: RunConfig,
        targetGroupId: String,
        round: Int,
        profile: GroupProfile
    ) {
        val lm = liveLifecycleManagers[targetGroupId] ?: return
        if (lm.getLiveNestedGroupCount() > 0) return

        val db = runCatching { substrateFactory.get(config.runId, targetGroupId) }
            .getOrElse { return }
        val openTasks = runCatching {
            db.wipTaskDao().getOpenTasks(config.runId, targetGroupId, NESTED_GROUP_OPEN_TASK_LIMIT)
        }.getOrElse { return }
        if (!nestedHierarchyTriggeredGroups.add(targetGroupId)) return

        val remainingBudget = groupSummaries.firstOrNull { it.groupId == targetGroupId }?.remainingBudget
            ?: (profile.spawnBudget * config.maxAgentsPerGroup)
        val plan = nestedHierarchySpawnPlanner.plan(
            NestedHierarchySpawnRequest(
                runId = config.runId,
                outerGroupId = targetGroupId,
                round = round,
                runObjective = config.objective,
                profileType = profile.profileType,
                profile = profile,
                remainingBudget = remainingBudget,
                openTaskDescriptions = openTasks.map { it.description },
                openRouterApiKey = config.openRouterApiKey,
                agentJarPath = resolveAgentJarPath(),
                modelAssignment = activeModelAssignments,
                modelPools = activeModelPools
            )
        )
        if (plan == null) {
            nestedHierarchyTriggeredGroups.remove(targetGroupId)
            return
        }

        emit(EvoMoliorEvent.EngineProgress(
            config.runId,
            "nested_group_spawn",
            plan.progressMessage
        ))

        runCatching {
            lm.spawnNestedGroup(
                config = plan.config,
                scope = scope,
                onCompleted = { result ->
                    emitNestedGroupCompletion(config.runId, result)
                }
            )
        }.onFailure { error ->
            nestedHierarchyTriggeredGroups.remove(targetGroupId)
            Napier.w("EvoMoliorRunService: nested group spawn failed for group=$targetGroupId round=$round: ${error.message}")
        }
    }

    private suspend fun emitNestedGroupCompletion(
        runId: String,
        result: NestedGroupResult
    ) {
        emit(EvoMoliorEvent.EngineProgress(
            runId,
            "nested_group_result",
            "[${result.outerGroupId}] Nested group ${result.nestedGroupId} completed; imported " +
                "${result.importedKbDocumentIds.size} KB, ${result.importedKgFactIds.size} KG, " +
                "${result.importedToolIds.size} tools"
        ))
    }

    /**
     * Spawns a short-lived round-end summary agent for each competing group and waits for the
     * summary text to appear in staging.
     *
     * The spawned agent writes a `PLAN_UPDATE` entry that begins with the exact marker
     * `ROUND SUBMISSION SUMMARY`, which is then harvested for archival.
     */
    private suspend fun spawnRoundSubmissionSummaries(
        config: RunConfig,
        groupIds: List<String>,
        round: Int
    ): Map<String, RoundSubmissionSummaryCandidate?> = supervisorScope {
        groupIds.mapIndexed { index, groupId ->
            async {
                val lm = liveLifecycleManagers[groupId]
                    ?: return@async groupId to null
                val profileType = activeGroupProfiles.getOrNull(index)
                    ?: GroupProfileType.Library
                val profile = GroupProfile.default(profileType)
                val budgetPerAgent = maxOf(5.0, profile.spawnBudget * 0.15)

                waitForLiveAgentsToDrain(groupId, lm, "post-round termination")
                var summary: RoundSubmissionSummaryCandidate? = null
                for (attempt in 1..ROUND_SUMMARY_MAX_ATTEMPTS) {
                    val prompt = RolePromptBuilder.buildRoundSubmissionSummary(
                        runObjective = config.objective,
                        roundIndex = round,
                        groupId = groupId,
                        attempt = attempt
                    )
                    val summaryStartedAtMs = System.currentTimeMillis()

                    emit(EvoMoliorEvent.EngineProgress(
                        config.runId,
                        "round_summary_spawn",
                        "[$groupId] Spawning round submission summariser for round ${round + 1} (attempt $attempt/$ROUND_SUMMARY_MAX_ATTEMPTS)"
                    ))

                    try {
                        lm.spawnSpecialAgent(
                            role = Role.Archivist,
                            generation = round + 1,
                            budgetPerAgent = budgetPerAgent,
                            stageDisclosure = buildString {
                                append("Round ${round + 1} submission summary generation for group $groupId.")
                                if (attempt > 1) {
                                    append(" Retry attempt $attempt after no summary was recorded from the previous attempt.")
                                }
                            },
                            modelAssignment = null,
                            modelAssignments = activeModelAssignments,
                            modelPools = activeModelPools,
                            rolePrompt = prompt,
                            scope = scope
                        )
                    } catch (e: Exception) {
                        Napier.w("EvoMoliorRunService: round submission summariser spawn failed for group=$groupId attempt=$attempt: ${e.message}")
                        break
                    }

                    summary = awaitRoundSubmissionSummary(
                        runId = config.runId,
                        groupId = groupId,
                        lifecycleManager = lm,
                        startedAtMs = summaryStartedAtMs,
                        expectedGeneration = round + 1
                    )
                    if (summary != null) {
                        break
                    }

                    if (attempt < ROUND_SUMMARY_MAX_ATTEMPTS) {
                        Napier.w(
                            "EvoMoliorRunService: no round submission summary recorded for group=$groupId round=$round on attempt=$attempt; retrying"
                        )
                    }
                }

                groupId to summary
            }
        }.awaitAll().toMap()
    }

    /**
     * Waits for the group's live agents to drain without blocking indefinitely.
     */
    private suspend fun waitForLiveAgentsToDrain(
        groupId: String,
        lm: LifecycleManager,
        phase: String
    ) {
        withTimeoutOrNull(90_000L) {
            while (lm.getLiveAgentCount() > 0) {
                delay(250L)
            }
        } ?: Napier.w(
            "EvoMoliorRunService: $phase drain timed out for group=$groupId; continuing without it"
        )
    }

    /**
     * Reads the newest round submission summary that was written after the summary agents
     * were spawned.
     */
    private suspend fun readRoundSubmissionSummary(
        runId: String,
        groupId: String,
        startedAtMs: Long,
        expectedGeneration: Int
    ): RoundSubmissionSummaryCandidate? {
        val db = runCatching { substrateFactory.get(runId, groupId) }
            .getOrElse { return null }
        val summariserAgentIds = WorkerExecutionRepository(db)
            .registrations(runId, groupId)
            .asSequence()
            .filter { registration ->
                registration.role == Role.Archivist.name &&
                    registration.generation == expectedGeneration &&
                    registration.registeredAt.toEpochMilliseconds() >= startedAtMs
            }
            .mapTo(linkedSetOf()) { it.agentId.value }
        if (summariserAgentIds.isEmpty()) return null

        val marker = "ROUND SUBMISSION SUMMARY"
        return runCatching {
            db.stagingDao().getAll(runId, groupId, limit = 100)
                .asSequence()
                .filter { it.submittedAtMs >= startedAtMs }
                .filter {
                    it.agentId in summariserAgentIds &&
                        it.role == Role.Archivist.name &&
                        it.generation == expectedGeneration
                }
                .filter { it.type == com.achulkov.evomolior.core.model.SubmissionType.PLAN_UPDATE.name }
                .sortedByDescending { it.submittedAtMs }
                .firstOrNull { it.content.trimStart().startsWith(marker) }
                ?.let { submission ->
                    RoundSubmissionSummaryCandidate(
                        submissionId = submission.id,
                        agentId = submission.agentId,
                        content = submission.content
                    )
                }
        }.getOrNull()
    }

    /**
     * Accepts only the exact spawned summariser output that crossed the Critic promotion boundary.
     */
    private suspend fun promotedRoundSubmissionSummaries(
        runId: String,
        round: Int,
        candidates: Map<String, RoundSubmissionSummaryCandidate?>
    ): Map<String, String?> = candidates.mapValues { (groupId, candidate) ->
        candidate ?: return@mapValues null
        val submission = substrateFactory.get(runId, groupId)
            .stagingDao()
            .getById(candidate.submissionId)
            ?: return@mapValues null
        submission.content.takeIf {
            submission.agentId == candidate.agentId &&
                submission.content == candidate.content &&
                submission.role == Role.Archivist.name &&
                submission.type ==
                com.achulkov.evomolior.core.model.SubmissionType.PLAN_UPDATE.name &&
                submission.generation == round + 1 &&
                submission.status == "promoted" &&
                !submission.criticAgentId.isNullOrBlank() &&
                submission.promotedArtifactRef == "submission:${submission.id}" &&
                submission.promotedArtifactHash == AtlasHashing.hashText(candidate.content)
        }
    }

    private fun archivedLowTrustKbFromTrustDecayPayload(payloadJson: String): Int =
        runCatching {
            runConfigJson.parseToJsonElement(payloadJson)
                .jsonObject["archivedLowTrustKb"]
                ?.jsonPrimitive
                ?.content
                ?.toIntOrNull()
                ?: 0
        }.getOrDefault(0)

    /**
     * Waits for the required round-summary staging marker instead of the agent's full teardown.
     *
     * Once the PLAN_UPDATE exists, post-round evaluation can proceed deterministically even if the
     * summariser is still wrapping up its death note.
     */
    private suspend fun awaitRoundSubmissionSummary(
        runId: String,
        groupId: String,
        lifecycleManager: LifecycleManager,
        startedAtMs: Long,
        expectedGeneration: Int,
        timeoutMs: Long = 120_000L
    ): RoundSubmissionSummaryCandidate? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val summary = readRoundSubmissionSummary(
                runId,
                groupId,
                startedAtMs,
                expectedGeneration
            )
            if (summary != null) {
                return summary
            }
            if (lifecycleManager.getLiveAgentCount() == 0) {
                return null
            }
            delay(250L)
        }

        return readRoundSubmissionSummary(
            runId,
            groupId,
            startedAtMs,
            expectedGeneration
        ).also { summary ->
            if (summary == null) {
                Napier.w(
                    "EvoMoliorRunService: timed out waiting for round submission summary marker for group=$groupId; continuing without it"
                )
            }
        }
    }

    private fun resolveFinalOutputSpec(config: RunConfig): FinalOutputSpec? {
        config.finalOutputSpec?.let { return it }
        return if (RunObjectiveHeuristics.requestsBrowserDeliverable(config.objective)) {
            FinalOutputSpec(
                relativePath = "final-article.html",
                mediaType = "text/html",
                description = "Final browser-renderable deliverable",
                kind = FinalOutputKind.HTML_ARTICLE
            )
        } else if (!config.isTestRun) {
            FinalOutputSpec(
                relativePath = "final-answer.md",
                mediaType = "text/markdown",
                description = "Deterministic default receiver-facing final answer",
                kind = FinalOutputKind.GENERIC_FILE
            )
        } else {
            null
        }
    }

    private suspend fun evaluateAutoCompletionRound(
        config: RunConfig,
        round: Int,
        blindResult: com.achulkov.evomolior.core.model.GradingResult,
        graderRoundResult: com.achulkov.evomolior.core.model.GradingResult?,
        roundSubmissionSummaries: Map<String, String?>
    ): RunAutoCompletionRoundEvidence {
        val rejectionReasons = linkedSetOf<String>()
        val authoritativeWinnerId = graderRoundResult
            ?.takeIf {
                it.resolution == com.achulkov.evomolior.core.model.ScoringResolution.RESOLVED &&
                    it.winnerId == blindResult.winnerId
            }
            ?.winnerId
        if (authoritativeWinnerId == null) {
            rejectionReasons += "authoritative_winner_unresolved"
        }

        val exactVerdict = exactGraderVerdictsByRound[round]
        val exactGraderAuthorityValid = authoritativeWinnerId != null &&
            exactVerdict?.status == ExactGraderRoundStatus.VALID &&
            exactVerdict.acceptedBundleHashes.isNotEmpty() &&
            AtlasGroupId(authoritativeWinnerId) in exactVerdict.targetSnapshotHashes
        if (!exactGraderAuthorityValid) {
            rejectionReasons += "exact_grader_authority_invalid"
        }

        val winnerBundles = authoritativeWinnerId?.let { winnerId ->
            graderAuthorityRepository.scoreBundlesForRound(config.runId, round)
                .filter { it.targetGroupId == AtlasGroupId(winnerId) }
        }.orEmpty()
        val expectedGraderIds = activeGraderGroupIds.toSet()
        val exactWinnerBundleCoverage = expectedGraderIds.isNotEmpty() &&
            winnerBundles.size == expectedGraderIds.size &&
            winnerBundles.mapTo(linkedSetOf()) { it.graderGroupId } == expectedGraderIds &&
            winnerBundles.all {
                it.status ==
                    com.achulkov.evomolior.core.grader.GraderScoreBundleStatus.VALID &&
                    it.requirementResults.isNotEmpty()
            }
        val acceptedRequirementStatuses = setOf(
            com.achulkov.evomolior.core.grader.RequirementResultStatus.PASS,
            com.achulkov.evomolior.core.grader.RequirementResultStatus.NOT_APPLICABLE
        )
        val exactRequirementsPassed = exactWinnerBundleCoverage &&
            winnerBundles.all { bundle ->
                bundle.requirementResults.values.all { result ->
                    result.status in acceptedRequirementStatuses &&
                        !result.criticalFailure
                } &&
                    bundle.requirementResults.values.any {
                        it.status ==
                            com.achulkov.evomolior.core.grader.RequirementResultStatus.PASS
                    }
            }
        val deterministicAssessment = authoritativeWinnerId
            ?.let(latestGroupRoundAssessments::get)
        val deterministicMandatoryTestsPassed = deterministicAssessment != null &&
            deterministicAssessment.automatedTestsPassed +
            deterministicAssessment.automatedTestsFailed > 0 &&
            !deterministicAssessment.mandatoryFailure
        val mandatoryTestsPassed =
            deterministicMandatoryTestsPassed && exactRequirementsPassed
        if (!mandatoryTestsPassed) {
            rejectionReasons += "mandatory_tests_not_fully_passed"
        }

        val outputSpec = resolveFinalOutputSpec(config)
        val candidateOutput = if (authoritativeWinnerId != null && outputSpec != null) {
            roundSubmissionSummaries[authoritativeWinnerId]
                ?.let { extractRoundCompletionCandidate(it, outputSpec) }
                ?.let { extractGeneratedOutputFile(it, outputSpec) }
        } else {
            null
        }
        val finalOutputValidated = candidateOutput != null &&
            outputSpec != null &&
            validateFinalOutputAgainstObjective(
                output = candidateOutput,
                outputSpec = outputSpec,
                runObjective = config.objective
            ) == null
        if (!finalOutputValidated) {
            rejectionReasons += "final_output_contract_not_satisfied"
        }

        return RunAutoCompletionRoundEvidence(
            roundIndex = round,
            winnerId = authoritativeWinnerId,
            exactGraderAuthorityValid = exactGraderAuthorityValid,
            mandatoryTestsPassed = mandatoryTestsPassed,
            finalOutputValidated = finalOutputValidated,
            rejectionReasons = rejectionReasons
        )
    }

    private fun extractRoundCompletionCandidate(
        summary: String,
        outputSpec: FinalOutputSpec
    ): String {
        if (!outputSpec.mediaType.startsWith("text/html", ignoreCase = true)) {
            return summary
                .lineSequence()
                .dropWhile { !it.trim().equals("ROUND SUBMISSION SUMMARY", ignoreCase = true) }
                .drop(1)
                .joinToString("\n")
                .trim()
        }
        val marker = Regex(
            pattern = """(?im)^\s*FINAL DELIVERABLE HTML\s*$"""
        ).find(summary) ?: return ""
        return summary.substring(marker.range.last + 1).trim()
    }

    private suspend fun generateFinalDeliverableImageAssets(config: RunConfig): List<GeneratedImageAsset> {
        if (config.llmBackend != LlmBackend.QWEN_CLOUD) return emptyList()

        return runCatching {
            val outputsRoot = File(resolveRunRoot(config.runId), "results/outputs")
            QwenImageGenerationService().generateArticleHeroImage(
                apiKey = config.openRouterApiKey,
                runId = config.runId,
                objective = config.objective,
                outputDirectory = outputsRoot
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            Napier.w(
                "EvoMoliorRunService: Qwen image generation skipped for run=${config.runId}: ${error.message}"
            )
        }.getOrNull()?.let { listOf(it) }.orEmpty()
    }

    /**
     * Creates the host-owned ARC artifact spine before ordinary workers run.
     *
     * The scaffold is a real, validated primary artifact and is persisted independently for every
     * group. It is not acceptance authority; it only guarantees that later writer, repair, and
     * recovery paths have durable material to continue from.
     */
    private suspend fun initializeArcArtifactBackbones(
        config: RunConfig,
        startingField: StartingField,
        groupIds: List<String>,
    ) {
        val outputSpec = resolveFinalOutputSpec(config)
        if (outputSpec == null || !ArcAgi2NotebookRecoveryFallback.supportsRecoveryTarget(outputSpec)) {
            activeArcArtifactBlueprint = null
            artifactWorkspaceHandlesByGroup.clear()
            artifactOutputsByGroup.clear()
            writerSourceAuthoritiesByGroup.clear()
            return
        }
        val profile = ArcNotebookProfile.v2()
        val blueprint = arcArtifactBlueprint(outputSpec)
        val runRoot = requireNotNull(
            substrateFactory.runControlDir(config.runId).parentFile?.parentFile
        ) { "ARC artifact workspace requires a run root" }
        val manager = ArtifactWorkspaceManager(runRoot)
        val inputHashes = startingField.inputArtifacts
            .associate { it.relativePath to it.sha256 }
        activeArcArtifactBlueprint = blueprint
        artifactWorkspaceHandlesByGroup.clear()
        artifactOutputsByGroup.clear()
        writerSourceAuthoritiesByGroup.clear()

        groupIds.sorted().forEach { groupId ->
            val handle = manager.open(
                runId = config.runId,
                groupId = groupId,
                blueprint = blueprint,
                profile = profile,
            )
            val scaffold = ArcNotebookHostCompiler.compileScaffold(
                outputSpec = outputSpec,
                inputHashes = inputHashes,
                sourceGroupId = groupId,
            )
            val structure = ArcNotebookHostValidator.validateStructure(scaffold, outputSpec)
            require(structure.passed) {
                "Host ARC scaffold failed structural validation for $groupId: " +
                    structure.violations.joinToString("; ")
            }
            val static = ArcNotebookHostValidator.validateStatic(scaffold, outputSpec)
            require(static.passed) {
                "Host ARC scaffold failed static validation for $groupId: " +
                    static.violations.joinToString("; ")
            }
            val branchLineage = ArcArtifactBranchCompiler(manager).materialize(
                handle = handle,
                round = 0,
                family = ArcArtifactBranchCompiler.familyForGroupIndex(
                    groupIds.sorted().indexOf(groupId),
                ),
            )
            ArcArtifactSnapshotCoordinator(manager).record(
                handle = handle,
                round = 0,
                artifact = scaffold,
                structureReport = validationReportJson(structure) +
                    validationReportJson(static),
                branchLineage = branchLineage,
                patchLineageIds = setOf(branchLineage.lineageHash),
                authorityClass = ComponentAuthorityClass.PROVISIONAL,
            )
            val sourceAuthority = WriterSourceResolver.resolve(
                scaffold = WriterScaffoldSource(
                    primaryGroupId = groupId,
                    artifactRef = artifactRefFor(profile, scaffold),
                ),
            )
            manager.writeWriterSourceAuthority(handle, sourceAuthority)
            artifactWorkspaceHandlesByGroup[groupId] = handle
            artifactOutputsByGroup[groupId] = scaffold
            writerSourceAuthoritiesByGroup[groupId] = sourceAuthority
        }
        emit(
            EvoMoliorEvent.EngineProgress(
                config.runId,
                "artifact_scaffold_ready",
                "Prepared ${artifactWorkspaceHandlesByGroup.size} host-owned ARC artifact " +
                    "scaffold(s) before worker grading",
            )
        )
    }

    /**
     * Seals runtime-owned ARC artifact evidence after round one and later rounds.
     *
     * A missing benchmark input provider is represented as a rescue record. The method never
     * fabricates a score from comparative grading or allocation progress.
     *
     * @param config Active run configuration.
     * @param groupIds Competing groups.
     * @param round Completed round index.
     */
    private suspend fun persistArcArtifactRoundSnapshots(
        config: RunConfig,
        groupIds: List<String>,
        round: Int,
    ) {
        if (round < 1 || artifactWorkspaceHandlesByGroup.isEmpty()) return
        val firstHandle = artifactWorkspaceHandlesByGroup.values.firstOrNull() ?: return
        val manager = ArtifactWorkspaceManager(
            firstHandle.root.parentFile?.parentFile?.parentFile?.parentFile ?: firstHandle.root
        )
        val coordinator = ArcArtifactSnapshotCoordinator(manager)
        groupIds.sorted().forEach { groupId ->
            val handle = artifactWorkspaceHandlesByGroup[groupId] ?: return@forEach
            val output = artifactOutputsByGroup[groupId]
                ?: manager.bestSnapshot(handle)?.let { manager.readSnapshotArtifact(handle, it) }
                ?: return@forEach
            val structure = ArcNotebookHostValidator.validateStructure(
                output,
                resolveFinalOutputSpec(config) ?: return@forEach,
            )
            val static = ArcNotebookHostValidator.validateStatic(
                output,
                resolveFinalOutputSpec(config) ?: return@forEach,
            )
            val execution = runCatching {
                ArcNotebookHostValidator.validateExecution(
                    output = output,
                    workspace = File(handle.root, "round-execution/$round"),
                    timeoutMs = 8_000L,
                    stage = ArcNotebookValidationStage.FULL_VISIBLE,
                )
            }.getOrElse { failure ->
                ArcNotebookValidationReport(
                    stage = ArcNotebookValidationStage.FULL_VISIBLE,
                    passed = false,
                    violations = listOf(
                        "runtime-owned full-visible execution unavailable: " +
                            (failure.message ?: failure::class.simpleName.orEmpty())
                    ),
                    evidenceHash = AtlasHashing.hashText(
                        "FULL_VISIBLE|${failure::class.qualifiedName}|${failure.message.orEmpty()}",
                    ),
                )
            }
            val benchmark = runCatching {
                arcVisibleLooRowsProvider(config.runId, groupId, round, output)
                    ?.let { rows ->
                        ArcVisibleLooBenchmark(
                            arcVisibleLooDefinitionProvider(config.runId)
                        ).benchmark(
                            ArcVisibleLooCandidateSource.fromArtifactHash(
                                candidateId = "$groupId-r$round",
                                sourceArtifactHash = output.contentHash,
                            ),
                            rows,
                        )
                    }
            }.onFailure { failure ->
                Napier.w(
                    "EvoMoliorRunService: ARC visible LOO unavailable for group=$groupId " +
                        "round=$round: ${failure.message}",
                )
            }.getOrNull()
            val previous = manager.bestSnapshot(handle)
            val branchLineage = previous?.let {
                val backboneHash = it.backboneHash
                val branchHash = it.branchHash
                if (backboneHash == null || branchHash == null) {
                    null
                } else {
                    ArcArtifactBranchLineage(
                        runId = config.runId,
                        groupId = groupId,
                        round = round,
                        backboneHash = backboneHash,
                        solverFamily = ArcArtifactBranchCompiler.familyForGroupIndex(
                            groupIds.sorted().indexOf(groupId),
                        ),
                        branchHash = branchHash,
                    ).withComputedHash()
                }
            }
            val rescueReason = when {
                !structure.passed || !static.passed ->
                    "round artifact failed structural/static validation"
                !execution.passed ->
                    "round artifact full-visible execution did not pass"
                benchmark == null ->
                    "exact 358-row visible LOO input/report was unavailable"
                else -> null
            }
            val sealedSnapshot = coordinator.record(
                handle = handle,
                round = round,
                artifact = output,
                structureReport = validationReportJson(structure) +
                    validationReportJson(static),
                executionReport = validationReportJson(execution),
                executionPassed = execution.passed,
                benchmarkResult = benchmark,
                branchLineage = branchLineage,
                rescueReason = rescueReason,
                patchLineageIds = setOf("round-artifact-r$round"),
            )
            appendCompoundingArtifactDeltas(
                runId = config.runId,
                groupId = groupId,
                observedRound = round,
                before = previous,
                after = sealedSnapshot,
            )
        }
    }

    private suspend fun appendCompoundingArtifactDeltas(
        runId: String,
        groupId: String,
        observedRound: Int,
        before: ArtifactSnapshot?,
        after: ArtifactSnapshot,
    ) {
        val repository = BoundedCompoundingLedgerRepository(
            substrateFactory.get(runId, groupId)
        )
        repository.rewardSpends(runId, groupId)
            .filter { it.spentRound < observedRound }
            .forEach { spend ->
                if (repository.rewardArtifactDeltaForSpend(spend.spendId) != null) return@forEach
                repository.appendRewardArtifactDelta(
                    RewardArtifactDeltaV1(
                        deltaId = "artifact-delta:${spend.spendId}:$observedRound",
                        spendId = spend.spendId,
                        spendHash = spend.spendHash,
                        runId = runId,
                        groupId = groupId,
                        observedRound = observedRound,
                        artifactId = after.snapshotId,
                        beforeArtifactHash = before?.artifactHash ?: after.artifactHash,
                        afterArtifactHash = after.artifactHash,
                        metricDelta = ((after.top1 ?: 0) - (before?.top1 ?: 0)).toDouble(),
                    ).withComputedHash()
                )
            }
    }

    /**
     * Runs the main writer before strict acceptance is resolved for the ARC target.
     *
     * A failed or timed-out writer leaves the pre-existing scaffold as the usable source. A
     * successful provider notebook is always recompiled host-side before it can replace the
     * scaffold or become a provisional source.
     */
    private suspend fun prepareArcMainWriterBeforeAcceptance(
        config: RunConfig,
        groupId: String?,
        finalRoundIndex: Int,
        finalRoundSummary: String?,
        generatedImageAssets: List<GeneratedImageAsset>,
        outputSpec: FinalOutputSpec?,
    ): Pair<GeneratedOutputFile, RecoveredDeliverableSource>? {
        if (
            groupId == null ||
            outputSpec == null ||
            !ArcAgi2NotebookRecoveryFallback.supportsRecoveryTarget(outputSpec)
        ) {
            return null
        }
        val handle = artifactWorkspaceHandlesByGroup[groupId] ?: return null
        val profile = handle.profile
        val manager = ArtifactWorkspaceManager(
            handle.root.parentFile?.parentFile?.parentFile?.parentFile ?: handle.root
        )
        val scaffold = artifactOutputsByGroup[groupId]
            ?: ArcNotebookHostCompiler.compileScaffold(
                outputSpec = outputSpec,
                sourceGroupId = groupId,
            )
        manager.writeWriterProgress(
            handle,
            """{"stage":"main-writer-started","round":$finalRoundIndex}""",
        )

        var selectedOutput = scaffold
        var compiledAuthored: GeneratedOutputFile? = null
        var writerFailure: Throwable? = null
        var repairRequest: WriterRepairRequest? = null
        val immutableContractRefs = listOf(
            handle.blueprint.blueprintHash,
            handle.profile.profileHash,
            handle.profile.primaryArtifactSpec.artifactId,
        ).sorted()
        val typedViolation = { code: ArtifactViolationCode, message: String ->
            ArtifactContractViolation(
                code = code,
                cellHandle = "receiver",
                sourceFile = "src/receiver.py",
                symbol = null,
                publicMessage = message,
                safeRepairHint =
                    "Continue from the persisted snapshot and repair only the reported contract.",
            )
        }
        for (attempt in 1..3) {
            manager.writeWriterProgress(
                handle,
                """{"stage":"main-writer-attempt","round":$finalRoundIndex,"attempt":$attempt}""",
            )
            var authoredOutput: GeneratedOutputFile? = null
            try {
                authoredOutput = spawnFinalDeliverableFile(
                    config = config,
                    groupId = groupId,
                    finalRoundIndex = finalRoundIndex,
                    outputSpec = outputSpec,
                    generatedImageAssets = generatedImageAssets,
                    finalRoundSummary = finalRoundSummary,
                    publicationMode = FinalDeliverablePublicationMode.MAIN_PRE_ACCEPTANCE,
                    writerRepairRequest = repairRequest,
                )
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                writerFailure = failure
                repairRequest = WriterRepairRequest(
                    artifactSnapshotHash = requireNotNull(manager.bestSnapshot(handle)).snapshotHash,
                    violations = listOf(
                        typedViolation(
                            ArtifactViolationCode.UNWRITABLE_ARTIFACT,
                            "The main writer did not produce a usable notebook attempt.",
                        ),
                    ),
                    benchmarkRegression = null,
                    immutableContractRefs = immutableContractRefs,
                    repairAttempt = attempt,
                )
                manager.writeWriterRepairRequest(handle, repairRequest)
                Napier.w(
                    "EvoMoliorRunService: ARC main writer attempt $attempt failed before " +
                        "strict acceptance for group=$groupId; preserving workspace",
                    failure,
                )
                continue
            }
            authoredOutput?.let { manager.writePartialArtifact(handle, attempt, it) }
            val compiled = authoredOutput?.let { authored ->
                runCatching {
                    ArcNotebookHostCompiler.compile(
                        outputSpec = outputSpec,
                        sourceNotebook = authored.content,
                        sourceGroupId = groupId,
                    ).copy(
                        sourceAgentId = authored.sourceAgentId,
                        sourceSubmissionId = authored.sourceSubmissionId,
                        sourceRole = authored.sourceRole,
                        componentNodeIds = authored.componentNodeIds,
                        componentLineageSubmissionId = authored.componentLineageSubmissionId,
                        componentLineageSubmittedAtMs = authored.componentLineageSubmittedAtMs,
                    )
                }.getOrElse { failure ->
                    writerFailure = failure
                    repairRequest = WriterRepairRequest(
                        artifactSnapshotHash =
                            requireNotNull(manager.bestSnapshot(handle)).snapshotHash,
                        violations = listOf(
                            typedViolation(
                                ArtifactViolationCode.INVALID_NOTEBOOK_STRUCTURE,
                                "The provider notebook could not be host-compiled.",
                            ),
                        ),
                        benchmarkRegression = null,
                        immutableContractRefs = immutableContractRefs,
                        repairAttempt = attempt,
                    )
                    manager.writeWriterRepairRequest(handle, repairRequest)
                    Napier.w(
                        "EvoMoliorRunService: ARC host compilation rejected main writer " +
                            "attempt $attempt for group=$groupId",
                        failure,
                    )
                    null
                }
            }
            if (compiled == null) continue
            val structure = ArcNotebookHostValidator.validateStructure(compiled, outputSpec)
            val static = ArcNotebookHostValidator.validateStatic(compiled, outputSpec)
            if (!structure.passed || !static.passed) {
                val violations = (structure.violations + static.violations)
                    .distinct()
                    .map {
                        typedViolation(
                            if (it.contains("syntax", ignoreCase = true)) {
                                ArtifactViolationCode.CELL_COMPILATION_FAILED
                            } else {
                                ArtifactViolationCode.VALIDATOR_FAILED
                            },
                            it,
                        )
                    }
                repairRequest = WriterRepairRequest(
                    artifactSnapshotHash = requireNotNull(manager.bestSnapshot(handle)).snapshotHash,
                    violations = violations,
                    benchmarkRegression = null,
                    immutableContractRefs = immutableContractRefs,
                    repairAttempt = attempt,
                )
                manager.writeWriterRepairRequest(handle, repairRequest)
                writerFailure = IllegalArgumentException(
                    "Host validation rejected main writer attempt: " +
                        violations.joinToString("; ") { it.publicMessage },
                )
                continue
            }
            compiledAuthored = compiled
            selectedOutput = compiled
            writerFailure = null
            break
        }

        val structure = ArcNotebookHostValidator.validateStructure(selectedOutput, outputSpec)
        val static = ArcNotebookHostValidator.validateStatic(selectedOutput, outputSpec)
        require(structure.passed && static.passed) {
            "ARC primary artifact failed host validation after main-writer attempt: " +
                (structure.violations + static.violations).distinct().joinToString("; ")
        }
        val finalExecution = runCatching {
            ArcNotebookHostValidator.validateExecution(
                output = selectedOutput,
                workspace = File(handle.root, "final-execution/$finalRoundIndex"),
                timeoutMs = 8_000L,
                stage = ArcNotebookValidationStage.FULL_VISIBLE,
            )
        }.getOrElse { failure ->
            if (failure is CancellationException) throw failure
            ArcNotebookValidationReport(
                stage = ArcNotebookValidationStage.FULL_VISIBLE,
                passed = false,
                violations = listOf(
                    "final full-visible execution unavailable: " +
                        (failure.message ?: failure::class.simpleName.orEmpty())
                ),
                evidenceHash = AtlasHashing.hashText(
                    "FULL_VISIBLE|${failure::class.qualifiedName}|${failure.message.orEmpty()}",
                ),
            )
        }
        val finalBenchmark = runCatching {
            arcVisibleLooRowsProvider(config.runId, groupId, finalRoundIndex, selectedOutput)
                ?.let { rows ->
                    ArcVisibleLooBenchmark(
                        arcVisibleLooDefinitionProvider(config.runId)
                    ).benchmark(
                        ArcVisibleLooCandidateSource.fromArtifactHash(
                            candidateId = "$groupId-final",
                            sourceArtifactHash = selectedOutput.contentHash,
                        ),
                        rows,
                    )
                }
        }.onFailure { failure ->
            if (failure is CancellationException) throw failure
            Napier.w(
                "EvoMoliorRunService: final ARC visible LOO unavailable for group=$groupId: " +
                    failure.message,
            )
        }.getOrNull()
        val priorSnapshot = manager.bestSnapshot(handle)
        val finalBranchLineage = priorSnapshot?.let {
            val backboneHash = it.backboneHash
            val branchHash = it.branchHash
            if (backboneHash == null || branchHash == null) {
                null
            } else {
                ArcArtifactBranchLineage(
                    runId = config.runId,
                    groupId = groupId,
                    round = finalRoundIndex + 1,
                    backboneHash = backboneHash,
                    solverFamily = ArcArtifactBranchCompiler.familyForGroupIndex(
                        groupSummaries
                            .map { summary -> summary.groupId }
                            .sorted()
                            .indexOf(groupId),
                    ),
                    branchHash = branchHash,
                ).withComputedHash()
            }
        }
        val snapshot = ArcArtifactSnapshotCoordinator(manager).record(
            handle = handle,
            round = finalRoundIndex + 1,
            artifact = selectedOutput,
            structureReport = validationReportJson(structure) +
                validationReportJson(static),
            executionReport = validationReportJson(finalExecution),
            executionPassed = finalExecution.passed,
            benchmarkResult = finalBenchmark,
            branchLineage = finalBranchLineage,
            rescueReason = when {
                !finalExecution.passed ->
                    "final candidate full-visible execution did not pass"
                finalBenchmark == null ->
                    "exact 358-row final visible LOO report was unavailable"
                else -> null
            },
            patchLineageIds = setOf("main-writer-r$finalRoundIndex")
                .takeIf { compiledAuthored != null }
                .orEmpty(),
            authorityClass = ComponentAuthorityClass.PROVISIONAL,
        )
        val candidate = WriterSourceCandidate(
            groupId = groupId,
            snapshot = snapshot,
            artifactRef = artifactRefFor(profile, selectedOutput),
            failedGateDiagnostics = listOfNotNull(
                "strict-acceptance-pending",
                writerFailure?.let { "main-writer-failed-before-acceptance" },
            ),
        )
        val authority = if (compiledAuthored != null) {
            WriterSourceResolver.resolve(
                validExecutableCandidates = if (finalExecution.passed) {
                    listOf(candidate)
                } else {
                    emptyList()
                },
                benchmarkedCandidates = if (finalBenchmark != null) {
                    listOf(candidate)
                } else {
                    emptyList()
                },
                finalComparativeLeader = candidate,
                scaffold = WriterScaffoldSource(
                    primaryGroupId = groupId,
                    artifactRef = artifactRefFor(profile, scaffold),
                ),
            )
        } else {
            WriterSourceResolver.resolve(
                scaffold = WriterScaffoldSource(
                    primaryGroupId = groupId,
                    artifactRef = artifactRefFor(profile, scaffold),
                    failedGateDiagnostics = listOf("main-writer-unavailable"),
                ),
            )
        }
        manager.writeWriterSourceAuthority(handle, authority)
        manager.writeWriterProgress(
            handle,
            """{"stage":"main-writer-sealed","round":$finalRoundIndex,"artifactHash":"${selectedOutput.contentHash}"}""",
        )
        artifactOutputsByGroup[groupId] = selectedOutput
        writerSourceAuthoritiesByGroup[groupId] = authority
        return selectedOutput to if (compiledAuthored != null) {
            RecoveredDeliverableSource.STAGED_WRITER_OUTPUT
        } else {
            RecoveredDeliverableSource.DETERMINISTIC_RECOVERY_FALLBACK
        }
    }

    private suspend fun preparePreAcceptanceWriterOutput(
        config: RunConfig,
        groupId: String?,
        finalRoundIndex: Int,
        finalRoundSummary: String?,
        outputSpec: FinalOutputSpec?,
        generatedImageAssets: List<GeneratedImageAsset>,
    ): Pair<GeneratedOutputFile, RecoveredDeliverableSource>? {
        if (groupId == null || outputSpec == null) return null
        return try {
            if (ArcAgi2NotebookRecoveryFallback.supportsRecoveryTarget(outputSpec)) {
                prepareArcMainWriterBeforeAcceptance(
                    config = config,
                    groupId = groupId,
                    finalRoundIndex = finalRoundIndex,
                    finalRoundSummary = finalRoundSummary,
                    generatedImageAssets = generatedImageAssets,
                    outputSpec = outputSpec,
                )
            } else {
                // Candidate authorship is task-neutral. Only the later publication boundary
                // may confer acceptance, winner, sealed-test, or admitted-lineage authority.
                spawnFinalDeliverableFile(
                    config = config,
                    groupId = groupId,
                    finalRoundIndex = finalRoundIndex,
                    outputSpec = outputSpec,
                    generatedImageAssets = generatedImageAssets,
                    finalRoundSummary = finalRoundSummary,
                    publicationMode = FinalDeliverablePublicationMode.MAIN_PRE_ACCEPTANCE,
                )?.let { it to RecoveredDeliverableSource.STAGED_WRITER_OUTPUT }
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            Napier.w(
                "EvoMoliorRunService: pre-acceptance writer preparation failed; " +
                    "strict authority remains unchanged",
                failure,
            )
            null
        }
    }

    private fun attachPreAcceptanceWriterOutput(
        context: LatePublicationRecoveryContext?,
        prepared: Pair<GeneratedOutputFile, RecoveredDeliverableSource>?,
    ): LatePublicationRecoveryContext? = context?.copy(
        candidateOutput = prepared?.first,
        candidateOutputSource = prepared?.second,
    )

    private fun arcArtifactBlueprint(outputSpec: FinalOutputSpec): DeliverableBlueprintV2 {
        val profile = ArcNotebookProfile.v2()
        val receiverContractHash = AtlasHashing.hashText(
            runConfigJson.encodeToString(FinalOutputSpec.serializer(), outputSpec),
        )
        return DeliverableBlueprintV2(
            blueprintId = "arc-artifact-blueprint-${receiverContractHash.take(24)}",
            receiverContractHash = receiverContractHash,
            primaryArtifacts = listOf(profile.primaryArtifactSpec),
            narrativePackageFormats = setOf(
                DeliverableFormat.MARKDOWN,
                DeliverableFormat.JSON,
            ),
            componentSpecs = emptyList(),
            recoveryPolicy = DeliverableRecoveryPolicy(),
        ).withComputedHash()
    }

    private fun artifactRefFor(
        profile: ArcNotebookProfile,
        output: GeneratedOutputFile,
    ): ArtifactRef = ArtifactRef(
        artifactId = profile.primaryArtifactSpec.artifactId,
        relativePath = profile.primaryArtifactSpec.relativePath,
        mediaType = profile.primaryArtifactSpec.mediaType,
        artifactHash = output.contentHash,
    )

    private fun validationReportJson(report: ArcNotebookValidationReport): String =
        buildString {
            append("{\"stage\":\"")
            append(report.stage.name)
            append("\",\"passed\":")
            append(report.passed)
            append(",\"evidenceHash\":\"")
            append(report.evidenceHash)
            append("\",\"violations\":[")
            append(report.violations.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" })
            append("]}")
        }

    /**
     * Spawns a winner-side Builder agent that writes the final generated deliverable file.
     */
    private suspend fun spawnFinalDeliverableFile(
        config: RunConfig,
        groupId: String,
        finalRoundIndex: Int,
        outputSpec: FinalOutputSpec,
        generatedImageAssets: List<GeneratedImageAsset> = emptyList(),
        finalRoundSummary: String? = null,
        publicationMode: FinalDeliverablePublicationMode =
            FinalDeliverablePublicationMode.ACCEPTED,
        recoveryReason: String? = null,
        writerRepairRequest: WriterRepairRequest? = null,
    ): GeneratedOutputFile? {
        val lm = liveLifecycleManagers[groupId] ?: return null
        val groupIndex = groupSummaries.indexOfFirst { it.groupId == groupId }
        val profileType = activeGroupProfiles.getOrNull(groupIndex) ?: GroupProfileType.Library
        val profile = GroupProfile.default(profileType)
        val isArcNotebookOutput = ArcAgi2NotebookRecoveryFallback.supportsRecoveryTarget(outputSpec)
        val writerRole = Role.Builder
        val builderModel = config.modelOverrides[writerRole.name] ?: activeModelAssignments[writerRole.name]
        val candidateModels = listOf(
            builderModel,
            builderModel,
            config.modelOverrides[Role.Archivist.name] ?: activeModelAssignments[Role.Archivist.name],
            config.modelOverrides[Role.Researcher.name] ?: activeModelAssignments[Role.Researcher.name]
        ).filterNotNull()
        val componentLineageRequirements = buildFinalComponentLineageRequirements(
            runId = config.runId,
            groupId = groupId,
            outputSpec = outputSpec
        )
        val scoringDimensions = normaliseScoringDimensions(config.scoringDimensions)
        var finalFile: GeneratedOutputFile? = null
        var retryReason: String? = null
        val maxAttempts = when (publicationMode) {
            FinalDeliverablePublicationMode.MAIN_PRE_ACCEPTANCE -> 1
            FinalDeliverablePublicationMode.ACCEPTED ->
                maxOf(
                    FINAL_DELIVERABLE_MAX_ATTEMPTS,
                    candidateModels.size.coerceAtLeast(1)
                )

            FinalDeliverablePublicationMode.RECOVERY_NON_ACCEPTED ->
                minOf(
                    RECOVERY_DELIVERABLE_MAX_ATTEMPTS,
                    candidateModels.size.coerceAtLeast(1)
                )
        }
        for (attempt in 1..maxAttempts) {
            val selectedModel = candidateModels.getOrElse(attempt - 1) {
                candidateModels.lastOrNull()
            }
            val startedAtMs = System.currentTimeMillis()
            emit(
                EvoMoliorEvent.EngineProgress(
                    config.runId,
                    when (publicationMode) {
                        FinalDeliverablePublicationMode.ACCEPTED ->
                            "final_deliverable_spawn"
                        FinalDeliverablePublicationMode.MAIN_PRE_ACCEPTANCE ->
                            "main_writer_spawn"
                        FinalDeliverablePublicationMode.RECOVERY_NON_ACCEPTED ->
                            "recovery_deliverable_spawn"
                    },
                    "[$groupId] Spawning ${
                        when (publicationMode) {
                            FinalDeliverablePublicationMode.ACCEPTED -> "final"
                            FinalDeliverablePublicationMode.MAIN_PRE_ACCEPTANCE ->
                                "main pre-acceptance"
                            FinalDeliverablePublicationMode.RECOVERY_NON_ACCEPTED ->
                                "non-accepted recovery"
                        }
                    } deliverable writer (attempt $attempt/$maxAttempts, " +
                        "model=${selectedModel ?: "default"})"
                )
            )

            lm.spawnSpecialAgent(
                role = writerRole,
                generation = maxOf(finalRoundIndex + 1, 0),
                budgetPerAgent = if (
                    isArcNotebookOutput &&
                    publicationMode != FinalDeliverablePublicationMode.RECOVERY_NON_ACCEPTED
                ) {
                    // ARC's exact notebook contract is source-dense; preserve the generic
                    // quarter-budget writer path for every other output target and mode.
                    profile.spawnBudget
                } else {
                    maxOf(8.0, profile.spawnBudget * 0.25)
                },
                stageDisclosure = buildString {
                    when (publicationMode) {
                        FinalDeliverablePublicationMode.ACCEPTED ->
                            append("Generate the final standalone deliverable for the completed run.")
                        FinalDeliverablePublicationMode.MAIN_PRE_ACCEPTANCE -> append(
                            "Generate the main candidate artifact before strict acceptance is " +
                                "resolved. Work from the durable group source and produce a " +
                                "structurally valid receiver artifact; acceptance authority is " +
                                "withheld until independent gates complete."
                        )
                        FinalDeliverablePublicationMode.RECOVERY_NON_ACCEPTED -> {
                            append(
                                "Generate the best useful recovery deliverable from public run " +
                                    "material. This output is explicitly non-accepted and must not " +
                                    "claim that mandatory tests, metrics, or normal publication passed."
                            )
                            recoveryReason?.let { append(" Public recovery reason: $it") }
                        }
                    }
                    if (attempt > 1) {
                        append(
                            " Retry attempt $attempt after the previous attempt failed final output validation."
                        )
                        retryReason?.let { reason -> append(" Previous rejection reason: $reason") }
                    }
                    if (selectedModel != null) {
                        append(" Use model assignment $selectedModel for this attempt.")
                    }
                    writerRepairRequest?.let { request ->
                        append(
                            " Continue from the exact prior artifact snapshot and apply only " +
                                "this typed repair authority: "
                        )
                        append(
                            runConfigJson.encodeToString(
                                WriterRepairRequest.serializer(),
                                request,
                            )
                        )
                    }
                },
                modelAssignment = selectedModel,
                modelAssignments = activeModelAssignments,
                modelPools = activeModelPools,
                rolePrompt = (if (outputSpec.kind == FinalOutputKind.PATCH_BUNDLE) {
                    RolePromptBuilder.buildPatchBundleFile(
                        runObjective = config.objective,
                        roundCount = finalRoundIndex + 1,
                        groupId = groupId,
                        relativePath = outputSpec.relativePath,
                        mediaType = outputSpec.mediaType,
                        description = outputSpec.description,
                        attempt = attempt
                    ).withFinalComponentLineageRequirements(componentLineageRequirements)
                } else {
                    buildFinalDeliverableWriterPrompt(
                        config = config,
                        groupId = groupId,
                        roundCount = finalRoundIndex + 1,
                        outputSpec = outputSpec,
                        attempt = attempt,
                        retryReason = retryReason,
                        generatedImageAssets = generatedImageAssets,
                        finalRoundSummary = finalRoundSummary,
                        componentLineageRequirements = componentLineageRequirements
                    )
                }).let { prompt ->
                    if (
                        publicationMode ==
                        FinalDeliverablePublicationMode.RECOVERY_NON_ACCEPTED
                    ) {
                        buildString {
                            appendLine("RECOVERY MODE — NON-ACCEPTED OUTPUT")
                            appendLine(
                                "Compose the strongest useful artifact supported by the " +
                                    "available public run evidence. Do not claim acceptance, " +
                                    "test success, winner authority, or normal publication."
                            )
                            recoveryReason?.let {
                                appendLine("Public recovery reason: $it")
                            }
                            appendLine()
                            append(prompt)
                        }
                    } else {
                        prompt
                    }
                },
                scope = scope
            )

            finalFile = try {
                awaitGeneratedOutputFile(
                    runId = config.runId,
                    groupId = groupId,
                    lifecycleManager = lm,
                    startedAtMs = startedAtMs,
                    outputSpec = outputSpec,
                    timeoutMs = finalDeliverableAttemptTimeoutMs(config),
                    requireComponentLineage =
                        publicationMode == FinalDeliverablePublicationMode.ACCEPTED,
                )
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                // Extraction can fail before the retry loop sees a candidate. Stop this
                // writer before an adapter or recovery path attempts another one.
                try {
                    lm.terminateAll()
                    lm.awaitQuiescence()
                } catch (cleanupFailure: Exception) {
                    if (cleanupFailure is CancellationException) throw cleanupFailure
                    failure.addSuppressed(cleanupFailure)
                }
                throw failure
            }
            if (finalFile != null) {
                val stagedWriterId = requireNotNull(finalFile.sourceAgentId) {
                    "Staged candidate lacks writer identity"
                }
                emit(EvoMoliorEvent.EngineProgress(
                    config.runId, "writer_candidate_staged",
                    "[$groupId] Candidate staged by $stagedWriterId; waiting within the original " +
                        "allowance for source correction and writer finalization"))
                val disposition = settleFinalWriter(
                    remainingAttemptMs = finalDeliverableAttemptTimeoutMs(config) -
                        (System.currentTimeMillis() - startedAtMs),
                    awaitSettlement = { lm.awaitAgentQuiescence(stagedWriterId, Long.MAX_VALUE) },
                    requestStagedStop = { lm.terminateAgentAfterCandidateStaged(stagedWriterId) },
                    drainAfterStop = {
                        lm.awaitAgentQuiescence(stagedWriterId, PUBLICATION_QUIESCENCE_TIMEOUT_MS)
                    },
                )
                emit(EvoMoliorEvent.EngineProgress(
                    config.runId, "writer_settled",
                    "[$groupId] Writer $stagedWriterId settled ($disposition); selecting its newest contract-valid candidate"))
                // A successful write_staging response may ask for one advisory source correction.
                // Do not freeze the first DOC while its author is still producing that correction.
                finalFile = requireNotNull(readFinalDeliverableCandidate(
                    runId = config.runId,
                    groupId = groupId,
                    startedAtMs = startedAtMs,
                    outputSpec = outputSpec,
                    requireComponentLineage =
                        publicationMode == FinalDeliverablePublicationMode.ACCEPTED,
                    requiredWriterId = stagedWriterId,
                    acceptCandidate = { candidate ->
                        validateFinalOutputAgainstObjective(
                            output = candidate,
                            outputSpec = outputSpec,
                            runObjective = config.objective,
                            generatedImageAssets = generatedImageAssets,
                        ) == null
                    },
                )) { "Settled writer has no contract-valid candidate" }
                val rejectionReason = when (publicationMode) {
                    FinalDeliverablePublicationMode.ACCEPTED -> try {
                        validateFinalOutputCandidate(
                            output = finalFile,
                            outputSpec = outputSpec,
                            runObjective = config.objective,
                            generatedImageAssets = generatedImageAssets,
                            runId = config.runId,
                            groupId = groupId,
                            roundIndex = finalRoundIndex,
                            scoringDimensions = scoringDimensions,
                            selection = SealedTestEvaluationSelection.FINAL_OUTPUT_ADDRESSABLE,
                            requireEvaluatedTests = false,
                            sealedFailureMessage =
                                "Final output failed active sealed-tests-v3 output authority"
                        )
                        null
                    } catch (rejection: IllegalArgumentException) {
                        rejection.message
                            ?.takeIf(String::isNotBlank)
                            ?.let { message ->
                                if (
                                    message.startsWith(
                                        "Staged final output failed active sealed-tests-v3 authority"
                                    ) ||
                                    message.startsWith(
                                        "Final output failed active sealed-tests-v3 output authority"
                                    )
                                ) {
                                    "Final output failed mandatory sealed-test authority; " +
                                        "revise it against the run objective and public " +
                                        "output contract"
                                } else {
                                    message
                                }
                            }
                            ?: "Final output failed authoritative validation"
                    }

                    FinalDeliverablePublicationMode.MAIN_PRE_ACCEPTANCE,
                    FinalDeliverablePublicationMode.RECOVERY_NON_ACCEPTED ->
                        runCatching {
                            val validated = validateGeneratedOutputFile(
                                requireNotNull(finalFile)
                            )
                            val rejection = validateFinalOutputAgainstObjective(
                                output = validated,
                                outputSpec = outputSpec,
                                runObjective = config.objective,
                                generatedImageAssets = generatedImageAssets
                            )
                            require(rejection == null) {
                                "Candidate output failed the public output contract: " +
                                    (rejection ?: "unknown validation failure")
                            }
                            finalFile = validated
                        }.onFailure { failure ->
                            if (failure is CancellationException) throw failure
                        }.exceptionOrNull()?.message
                }
                if (rejectionReason == null) {
                    recordCandidateOutputValidation(
                        config, groupId, finalRoundIndex, requireNotNull(finalFile), outputSpec)
                    if (publicationMode == FinalDeliverablePublicationMode.RECOVERY_NON_ACCEPTED) {
                        runCatching {
                            lm.terminateAll()
                            lm.awaitQuiescence()
                        }.onFailure { error ->
                            if (error is CancellationException) throw error
                            Napier.w(
                                "EvoMoliorRunService: recovery writer cleanup failed for " +
                                    "group=$groupId",
                                error
                            )
                        }
                    }
                    break
                }
                Napier.w(
                    "EvoMoliorRunService: final deliverable file ${outputSpec.relativePath} " +
                        "rejected for group=$groupId attempt=$attempt: $rejectionReason"
                )
                retryReason = rejectionReason
                finalFile = null
                lm.terminateAll()
                lm.awaitQuiescence()
            } else {
                lm.terminateAll()
                lm.awaitQuiescence()
            }
            if (attempt < maxAttempts) {
                Napier.w(
                    "EvoMoliorRunService: no valid final deliverable file recorded for group=$groupId " +
                        "round=$finalRoundIndex on attempt=$attempt; retrying"
                )
            }
        }
        if (finalFile == null) {
            throw IllegalStateException(
                "${
                    if (publicationMode != FinalDeliverablePublicationMode.RECOVERY_NON_ACCEPTED) {
                        "Final"
                    } else {
                        "Recovery"
                    }
                } output ${outputSpec.relativePath} (${outputSpec.mediaType}) was not " +
                    "published for group=$groupId after $maxAttempts attempt(s)"
            )
        }
        return finalFile
    }

    private suspend fun buildFinalComponentLineageRequirements(
        runId: String,
        groupId: String,
        outputSpec: FinalOutputSpec
    ): String? {
        val requiredComponents = outputSpec.validation
            ?.requiredMarkdownSectionSignals
            .orEmpty()
            .toSet()
        if (requiredComponents.isEmpty()) return null
        val sources = ContributionGraphRepository(
            database = substrateFactory.get(runId, groupId),
            atlasDatabase = substrateFactory.getAtlas(runId)
        ).finalDeliverableSources(runId, groupId)
        val atlas = atlasTransactionRepository.currentAtlas(runId)
        val availableNodes = sources.mapNotNull { source ->
            val nodeId = source.nodeId ?: return@mapNotNull null
            val node = atlas?.nodes?.get(NodeId(nodeId))
            nodeId to listOfNotNull(
                node?.limitationStatement,
                node?.capabilityTarget
            ).joinToString(" → ")
        }.toMap()
        return FinalComponentLineageV1Codec.promptRequirements(
            outputRelativePath = outputSpec.relativePath,
            requiredComponentIds = requiredComponents,
            availableNodes = availableNodes
        )
    }

    private fun finalDeliverableAttemptTimeoutMs(config: RunConfig): Long {
        finalDeliverableAttemptTimeoutMsOverride?.let { return it }
        if (config.llmBackend == LlmBackend.CODEX_CHATGPT) return DEFAULT_FINAL_DELIVERABLE_ATTEMPT_TIMEOUT_MS
        if (!config.isTestRun) return DEFAULT_FINAL_DELIVERABLE_ATTEMPT_TIMEOUT_MS

        val roundBudgetMs = config.roundDurationMinutes.coerceAtLeast(0) * 45_000L
        return roundBudgetMs
            .coerceAtLeast(TEST_FINAL_DELIVERABLE_MIN_ATTEMPT_TIMEOUT_MS)
            .coerceAtMost(TEST_FINAL_DELIVERABLE_MAX_ATTEMPT_TIMEOUT_MS)
    }

    private fun buildFinalDeliverableWriterPrompt(
        config: RunConfig,
        groupId: String,
        roundCount: Int,
        outputSpec: FinalOutputSpec,
        attempt: Int,
        retryReason: String?,
        generatedImageAssets: List<GeneratedImageAsset>,
        finalRoundSummary: String?,
        componentLineageRequirements: String? = null
    ): String = RolePromptBuilder.buildFinalDeliverableFile(
        runObjective = config.objective,
        roundCount = roundCount,
        groupId = groupId,
        relativePath = outputSpec.relativePath,
        mediaType = outputSpec.mediaType,
        description = outputSpec.description,
        attempt = attempt,
        retryReason = retryReason,
        generatedImageAssets = generatedImageAssets,
        finalRoundSummary = finalRoundSummary,
        validationRequirements = finalDeliverableWriterValidationRequirements(outputSpec)
    ).withFinalComponentLineageRequirements(componentLineageRequirements)

    private fun String.withFinalComponentLineageRequirements(
        requirements: String?
    ): String = requirements?.let { "$this\n\n$it" } ?: this

    /**
     * Refreshes only the cached DOC's admitted component lineage. The original writer may still
     * be submitting its post-DOC PLAN_UPDATE; wait within the existing writer timeout, without
     * starting another writer or substituting a newer artifact.
     */
    private suspend fun refreshAcceptedCandidateLineage(
        config: RunConfig,
        groupId: String,
        outputSpec: FinalOutputSpec,
        candidate: GeneratedOutputFile,
    ): GeneratedOutputFile {
        if (outputSpec.validation?.requiredMarkdownSectionSignals.isNullOrEmpty()) {
            return candidate
        }
        readFinalDeliverableFile(
            runId = config.runId,
            groupId = groupId,
            startedAtMs = 0L,
            outputSpec = outputSpec,
            exactCandidate = candidate,
        )?.let { return it }
        return requireNotNull(
            awaitGeneratedOutputFile(
                runId = config.runId,
                groupId = groupId,
                lifecycleManager = requireNotNull(liveLifecycleManagers[groupId]) {
                    "Cached final output lacks its original writer lifecycle"
                },
                startedAtMs = 0L,
                outputSpec = outputSpec,
                timeoutMs = finalDeliverableAttemptTimeoutMs(config),
                exactCandidate = candidate,
            )
        ) {
            "Cached final output lacks valid same-writer component lineage"
        }
    }

    /**
     * Polls for a generated output. Accepted writers also wait for declared component lineage;
     * provisional candidates retain staging provenance without requiring admission authority.
     * The trailing Death Note remains a later publication gate.
     */
    private suspend fun awaitGeneratedOutputFile(
        runId: String,
        groupId: String,
        lifecycleManager: LifecycleManager,
        startedAtMs: Long,
        outputSpec: FinalOutputSpec,
        timeoutMs: Long = 300_000L,
        requireComponentLineage: Boolean = true,
        exactCandidate: GeneratedOutputFile? = null,
    ): GeneratedOutputFile? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var noLiveAgentSinceMs: Long? = null
        while (System.currentTimeMillis() < deadline) {
            readFinalDeliverableCandidate(
                runId = runId,
                groupId = groupId,
                startedAtMs = startedAtMs,
                outputSpec = outputSpec,
                requireComponentLineage = requireComponentLineage,
                exactCandidate = exactCandidate,
                acceptCandidate = { true },
            )?.let { return it }
            val now = System.currentTimeMillis()
            if (lifecycleManager.getLiveAgentCount() == 0) {
                val quietSince = noLiveAgentSinceMs ?: now.also {
                    noLiveAgentSinceMs = it
                }
                if (now - quietSince >= FINAL_DELIVERABLE_OUTPUT_POST_EXIT_GRACE_MS) {
                    break
                }
            } else {
                noLiveAgentSinceMs = null
            }
            delay(250L)
        }

        return readFinalDeliverableCandidate(
            runId = runId,
            groupId = groupId,
            startedAtMs = startedAtMs,
            outputSpec = outputSpec,
            requireComponentLineage = requireComponentLineage,
            exactCandidate = exactCandidate,
            acceptCandidate = { true },
        ).also { finalFile ->
            if (finalFile == null) {
                // Distinguish a writer that exited without output from a real deadline expiry.
                val reason = if (noLiveAgentSinceMs != null) {
                    "writer exited without a valid generated output"
                } else {
                    "timed out after ${timeoutMs}ms waiting for generated output"
                }
                Napier.w(
                    "EvoMoliorRunService: $reason for group=$groupId; continuing without it"
                )
            }
        }
    }

    /**
     * Reads the newest authored final deliverable file written after the final deliverable
     * writer was spawned.
     */
    private suspend fun readFinalDeliverableFile(
        runId: String,
        groupId: String,
        startedAtMs: Long,
        outputSpec: FinalOutputSpec,
        requireComponentLineage: Boolean = true,
        exactCandidate: GeneratedOutputFile? = null,
        requiredWriterId: String? = null,
    ): GeneratedOutputFile? = readFinalDeliverableCandidate(
        runId, groupId, startedAtMs, outputSpec, requireComponentLineage, exactCandidate,
        requiredWriterId, acceptCandidate = null,
    )

    /**
     * Selects from the bounded staging window without losing an earlier valid report to a failed
     * advisory correction. Filtered selection retains exact-DOC lineage ordering fences.
     */
    private suspend fun readFinalDeliverableCandidate(
        runId: String,
        groupId: String,
        startedAtMs: Long,
        outputSpec: FinalOutputSpec,
        requireComponentLineage: Boolean = true,
        exactCandidate: GeneratedOutputFile? = null,
        requiredWriterId: String? = null,
        acceptCandidate: ((GeneratedOutputFile) -> Boolean)?,
    ): GeneratedOutputFile? {
        val db = runCatching { substrateFactory.get(runId, groupId) }
            .getOrElse {
                if (it is CancellationException) throw it
                return null
            }
        // Large, long-running stages can emit many staging records after the final writer starts.
        // Use a wider window so the canonical output is not missed behind later staging traffic.
        val submissions = runCatching {
            db.stagingDao().getAll(runId, groupId, limit = 1000)
                .filter { it.submittedAtMs >= startedAtMs }
        }.getOrElse {
            if (it is CancellationException) throw it
            return null
        }
        val requiredComponents = outputSpec.validation
            ?.requiredMarkdownSectionSignals
            .orEmpty()
            .toSet()
        val availableNodeIds = if (requiredComponents.isEmpty()) {
            emptySet()
        } else {
            runCatching {
                ContributionGraphRepository(
                    database = db,
                    atlasDatabase = substrateFactory.getAtlas(runId)
                ).finalDeliverableSources(runId, groupId)
                    .mapNotNullTo(linkedSetOf()) { it.nodeId }
            }.getOrElse {
                if (it is CancellationException) throw it
                return null
            }
        }
        val candidateSubmissions = if (exactCandidate == null) {
            submissions
        } else {
            val submissionId = exactCandidate.sourceSubmissionId ?: return null
            // Keep the DOC inside the bounded read window so intervening same-writer DOCs
            // cannot age out while a later, unrelated lineage record is still visible.
            submissions
                .filter {
                    it.id == submissionId &&
                        it.runId == runId &&
                        it.groupId == groupId &&
                        it.groupId == exactCandidate.sourceGroupId &&
                        it.agentId == exactCandidate.sourceAgentId &&
                        it.role == exactCandidate.sourceRole?.name
                }
        }
        return candidateSubmissions.asSequence()
            .filter { it.type == com.achulkov.evomolior.core.model.SubmissionType.DOC.name }
            .filter { requiredWriterId == null || it.agentId == requiredWriterId }
            .sortedWith(
                compareByDescending<StagingSubmissionEntity> { it.submittedAtMs }
                    .thenByDescending { it.id }
            )
            .mapNotNull { submission ->
                val output = try {
                    extractGeneratedOutputFile(submission.content.trim(), outputSpec)
                } catch (invalid: RuntimeException) {
                    if (acceptCandidate == null || invalid is CancellationException ||
                        (invalid !is IllegalArgumentException && invalid !is IllegalStateException)) throw invalid
                    // Malformed corrections remain staged evidence, not deletion of prior work.
                    return@mapNotNull null
                } ?: return@mapNotNull null
                if (
                    exactCandidate != null &&
                    (output.relativePath != exactCandidate.relativePath ||
                        output.mediaType != exactCandidate.mediaType ||
                        output.content != exactCandidate.content ||
                        !AtlasHashing.constantTimeEquals(
                            output.contentHash,
                            exactCandidate.contentHash,
                        ))
                ) {
                    return@mapNotNull null
                }
                // V1 lineage names the output path, not the DOC id. Do not borrow lineage
                // written after this author has moved on to another receiver artifact.
                // Ordinary DOC notes cannot replace the artifact named by this contract.
                val nextWriterDocAtMs = if (exactCandidate == null && acceptCandidate == null) null else submissions
                    .asSequence()
                    .filter {
                        it.type == SubmissionType.DOC.name &&
                            it.agentId == submission.agentId &&
                            it.id != submission.id &&
                            it.submittedAtMs >= submission.submittedAtMs
                    }
                    .filter {
                        runCatching {
                            extractGeneratedOutputFile(it.content.trim(), outputSpec)
                        }.getOrNull() != null
                    }
                    .minOfOrNull { it.submittedAtMs }
                val lineageSubmission = if (requiredComponents.isEmpty()) {
                    null
                } else {
                    submissions.asSequence()
                        .filter {
                            it.type ==
                                com.achulkov.evomolior.core.model.SubmissionType.PLAN_UPDATE.name
                        }
                        .filter { it.agentId == submission.agentId }
                        .filter { it.submittedAtMs > submission.submittedAtMs }
                        .filter { nextWriterDocAtMs == null || it.submittedAtMs < nextWriterDocAtMs }
                        .filter {
                            it.content.trimStart().startsWith(
                                FINAL_COMPONENT_LINEAGE_V1_MARKER
                            )
                        }
                        .maxWithOrNull(
                            compareBy<StagingSubmissionEntity>(
                                { it.submittedAtMs },
                                { it.id }
                            )
                        )
                        ?: if (requireComponentLineage) return@mapNotNull null else null
                }
                val componentNodeIds = if (lineageSubmission == null) {
                    emptyMap()
                } else {
                    runCatching {
                        FinalComponentLineageV1Codec.parse(
                            content = lineageSubmission.content,
                            expectedOutputRelativePath = outputSpec.relativePath,
                            requiredComponentIds = requiredComponents,
                            availableNodeIds = availableNodeIds
                        )
                    }.getOrNull() ?: if (requireComponentLineage) {
                        return@mapNotNull null
                    } else {
                        emptyMap()
                    }
                }
                val validatedLineageSubmission = lineageSubmission?.takeIf {
                    componentNodeIds.keys == requiredComponents
                }
                output.copy(
                    sourceGroupId = submission.groupId,
                    sourceAgentId = submission.agentId,
                    sourceSubmissionId = submission.id,
                    sourceRole = runCatching { Role.valueOf(submission.role) }.getOrNull(),
                    componentNodeIds = componentNodeIds,
                    componentLineageSubmissionId = validatedLineageSubmission?.id,
                    componentLineageSubmittedAtMs = validatedLineageSubmission?.submittedAtMs
                )
            }
            .firstOrNull { acceptCandidate?.invoke(it) ?: true }
    }

    private fun extractGeneratedOutputFile(
        candidate: String,
        outputSpec: FinalOutputSpec
    ): GeneratedOutputFile? {
        if (outputSpec.mediaType.normalizedBaseMediaType() == "text/html") {
            if (candidate.startsWith("<!doctype html>", ignoreCase = true) ||
                candidate.startsWith("<html", ignoreCase = true)
            ) {
                return validateGeneratedOutputFile(
                    GeneratedOutputFile(
                    relativePath = outputSpec.relativePath,
                    mediaType = outputSpec.mediaType,
                    content = candidate
                    )
                )
            }
        }

        directBodyGeneratedOutputFile(candidate, outputSpec)?.let { return validateGeneratedOutputFile(it) }

        if (!candidate.startsWith("FINAL DELIVERABLE FILE")) {
            return null
        }
        val lines = candidate.lines()
        val pathLine = lines.firstOrNull { it.startsWith("Path:", ignoreCase = true) } ?: return null
        val mediaLine = lines.firstOrNull { it.startsWith("Media-Type:", ignoreCase = true) } ?: return null
        val contentMarkerIndex = lines.indexOfFirst { it.trim().equals("CONTENT", ignoreCase = true) }
        if (contentMarkerIndex < 0 || contentMarkerIndex + 1 >= lines.size) {
            return null
        }
        val path = pathLine.substringAfter(':').trim()
        val mediaType = mediaLine.substringAfter(':').trim()
        if (
            path != outputSpec.relativePath ||
            mediaType.normalizedBaseMediaType() !=
            outputSpec.mediaType.normalizedBaseMediaType()
        ) {
            return null
        }
        val content = lines.drop(contentMarkerIndex + 1).joinToString("\n").trim()
        if (content.isBlank()) return null
        return validateGeneratedOutputFile(
            GeneratedOutputFile(
                relativePath = path,
                mediaType = mediaType,
                content = content
            )
        )
    }

    private fun directBodyGeneratedOutputFile(
        candidate: String,
        outputSpec: FinalOutputSpec
    ): GeneratedOutputFile? {
        val body = candidate.trim()
        if (body.isBlank()) return null

        val mediaType = outputSpec.mediaType.normalizedBaseMediaType()
        val acceptsBody = when {
            mediaType == "text/html" -> false
            mediaType == "application/json" || mediaType.endsWith("+json") ->
                runCatching { Json.parseToJsonElement(body) }.isSuccess
            mediaType.startsWith("text/") -> true
            else -> false
        }

        if (!acceptsBody) return null

        return GeneratedOutputFile(
            relativePath = outputSpec.relativePath,
            mediaType = outputSpec.mediaType,
            content = body
        )
    }

    private fun validateFinalOutputAgainstObjective(
        output: GeneratedOutputFile,
        outputSpec: FinalOutputSpec,
        runObjective: String,
        generatedImageAssets: List<GeneratedImageAsset> = emptyList()
    ): String? {
        FinalOutputContractValidator.validate(output, outputSpec)
            .takeIf { it.isNotEmpty() }
            ?.let { return it.joinToString("; ") }
        validateUniqueCorpusIdRows(output.content)?.let { return it }
        validateSystematicReviewCorpusIdCompleteness(output.content)?.let { return it }
        if (!output.mediaType.startsWith("text/html", ignoreCase = true)) return null
        if (generatedImageAssets.isNotEmpty()) {
            val missingAssets = generatedImageAssets.filterNot { asset ->
                containsImageTagForGeneratedAsset(output.content, asset)
            }
            if (missingAssets.isNotEmpty()) {
                return "HTML output did not embed generated image asset path(s) in <img src=\"...\"> tag(s): " +
                    missingAssets.joinToString(", ") { it.relativePath }
            }
        }
        validateEvidenceTraceability(output.content, runObjective)?.let { return it }
        val minimumWords = extractMinimumVisibleWords(runObjective) ?: return null
        val visibleWords = countVisibleHtmlWords(output.content)
        return if (visibleWords < minimumWords) {
            "HTML body has $visibleWords visible words but the objective requires at least $minimumWords visible words."
        } else {
            null
        }
    }

    private fun validateUniqueCorpusIdRows(content: String): String? {
        val duplicateRows = extractCorpusIdTableRows(content)
            .filterNot { it.tableName in multiRowCorpusIdTables }
            .groupBy { Triple(it.blockIndex, it.tableSegmentIndex, it.corpusId) }
            .filterValues { rows -> rows.size > 1 }
            .keys
            .map { it.third }
            .distinct()
            .sorted()
        return duplicateRows
            .takeIf { it.isNotEmpty() }
            ?.joinToString(
                prefix = "Final output contains duplicate corpus_id row(s) in fenced CSV table(s): "
            )
    }

    private data class CorpusIdTableRow(
        val blockIndex: Int,
        val tableSegmentIndex: Int,
        val tableName: String?,
        val corpusId: String,
        val row: String
    )

    private fun extractCorpusIdTableRows(content: String): List<CorpusIdTableRow> {
        val rows = mutableListOf<CorpusIdTableRow>()
        var insideCsvFence = false
        var expectingHeader = false
        var corpusIdColumnIndex: Int? = null
        var blockIndex = -1
        var tableSegmentIndex = -1
        var previousNonBlankLine: String? = null
        var currentTableName: String? = null
        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.startsWith("```")) {
                insideCsvFence = line.equals("```csv", ignoreCase = true)
                expectingHeader = insideCsvFence
                corpusIdColumnIndex = null
                if (insideCsvFence) {
                    blockIndex += 1
                    tableSegmentIndex = 0
                    currentTableName = normalizeCorpusIdTableName(previousNonBlankLine)
                } else {
                    currentTableName = null
                }
                return@forEach
            }
            if (!insideCsvFence) {
                if (line.isNotBlank()) previousNonBlankLine = line
                return@forEach
            }
            if (line.isBlank()) return@forEach
            val fields = splitCsvRecord(line)
            if (expectingHeader) {
                corpusIdColumnIndex = fields.indexOfFirst { it.equals("corpus_id", ignoreCase = true) }
                    .takeIf { it >= 0 }
                currentTableName = currentTableName ?: inferCorpusIdTableNameFromHeader(fields)
                expectingHeader = false
                return@forEach
            }
            val repeatedCorpusHeaderIndex = fields.indexOfFirst { it.equals("corpus_id", ignoreCase = true) }
            if (repeatedCorpusHeaderIndex >= 0) {
                tableSegmentIndex += 1
                corpusIdColumnIndex = repeatedCorpusHeaderIndex
                currentTableName = inferCorpusIdTableNameFromHeader(fields) ?: currentTableName
                return@forEach
            }
            val index = corpusIdColumnIndex ?: return@forEach
            val corpusId = fields.getOrNull(index)?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
            rows += CorpusIdTableRow(
                blockIndex = blockIndex,
                tableSegmentIndex = tableSegmentIndex,
                tableName = currentTableName,
                corpusId = corpusId,
                row = line
            )
        }
        return rows
    }

    private val multiRowCorpusIdTables: Set<String> = setOf("exclusion_reasons.csv")

    private fun inferCorpusIdTableNameFromHeader(fields: List<String>): String? {
        val normalized = fields.map { it.trim().lowercase() }.toSet()
        return when {
            setOf("inclusion_decision", "effect_direction", "mental_health_result", "weight_result")
                .all { it in normalized } -> "included_studies.csv"
            setOf("primary_exclusion_reason", "evidence")
                .all { it in normalized } -> "excluded_hard_negatives.csv"
            setOf("criterion_failed", "reason")
                .all { it in normalized } -> "exclusion_reasons.csv"
            setOf("population", "intervention", "comparator", "outcome", "design", "decision")
                .all { it in normalized } -> "evidence_table.csv"
            else -> null
        }
    }

    private fun validateSystematicReviewCorpusIdCompleteness(content: String): String? {
        if (!looksLikeSystematicReviewCorpusTables(content)) return null
        val rows = extractCorpusIdTableRows(content)
        if (rows.isEmpty()) {
            return "Final systematic-review output contains required CSV sections but no corpus_id row(s)."
        }
        val allCorpusIds = rows.map { it.corpusId }.toSet()
        if (allCorpusIds.size < 4) return null

        val rowsByTable = rows.groupBy { it.tableName }
        rowsByTable["evidence_table.csv"]?.let { evidenceRows ->
            val missing = allCorpusIds - evidenceRows.map { it.corpusId }.toSet()
            if (missing.isNotEmpty()) {
                return missing.sorted().joinToString(
                    prefix = "Final systematic-review evidence_table.csv omits corpus_id row(s): "
                )
            }
        }

        val includedRows = rowsByTable["included_studies.csv"].orEmpty()
        val excludedRows = rowsByTable["excluded_hard_negatives.csv"].orEmpty()
        val decisionRows = includedRows + excludedRows
        if (decisionRows.isNotEmpty()) {
            val decisionIds = decisionRows.map { it.corpusId }.toSet()
            val missing = allCorpusIds - decisionIds
            if (missing.isNotEmpty()) {
                return missing.sorted().joinToString(
                    prefix = "Final systematic-review included_studies.csv/excluded_hard_negatives.csv coverage omits corpus_id row(s): "
                )
            }
        }

        return null
    }

    private fun looksLikeSystematicReviewCorpusTables(content: String): Boolean {
        val lower = content.lowercase()
        return listOf(
            "included_studies.csv",
            "excluded_hard_negatives.csv",
            "evidence_table.csv",
            "exclusion_reasons.csv"
        ).count { lower.contains(it) } >= 2
    }

    private fun normalizeCorpusIdTableName(line: String?): String? {
        val cleaned = line
            ?.trim()
            ?.trimStart('#')
            ?.trim()
            ?.trim('`')
            ?.substringBeforeLast(' ')
            ?.trim()
            ?: return null
        return cleaned.takeIf { it.endsWith(".csv", ignoreCase = true) }?.lowercase()
    }

    private fun splitCsvRecord(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index += 1
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    fields += current.toString()
                    current.clear()
                }
                else -> current.append(char)
            }
            index += 1
        }
        fields += current.toString()
        return fields
    }

    private fun containsImageTagForGeneratedAsset(html: String, asset: GeneratedImageAsset): Boolean {
        val escapedPath = Regex.escape(asset.relativePath)
        return Regex(
            """(?is)<img\b[^>]*\bsrc\s*=\s*(?:"\s*$escapedPath\s*"|'\s*$escapedPath\s*'|$escapedPath(?=[\s>/]))[^>]*>"""
        ).containsMatchIn(html)
    }

    private fun validateEvidenceTraceability(html: String, runObjective: String): String? {
        if (!requiresEvidenceTraceability(runObjective)) return null

        val visibleText = visibleHtmlText(html)
        val hasEvidenceSection = Regex(
            """(?i)\b(?:source-grounded\s+evidence\s+notes|evidence\s+traceability)\b"""
        ).containsMatchIn(visibleText)
        if (!hasEvidenceSection) {
            return "HTML output must include a visible Source-grounded evidence notes or Evidence traceability section."
        }

        val placeholderEvidence = listOf(
            Regex("""(?i)\(\s*source\s*\)"""),
            Regex("""(?i)\bsource:\s*research_synthesis_source_pack\.md\b"""),
            Regex("""(?i)\bsource:\s*source\s+pack\b""")
        )
        if (placeholderEvidence.any { it.containsMatchIn(visibleText) }) {
            return "HTML output uses placeholder evidence markers; cite source-pack notes by specific short labels or label recommendations as inference."
        }

        return null
    }

    private fun requiresEvidenceTraceability(runObjective: String): Boolean {
        val normalized = runObjective.lowercase()
        return listOf(
            "source-grounded evidence notes",
            "evidence traceability",
            "source fidelity",
            "use only the source pack",
            "do not invent citations",
            "research-grounded",
            "evidence-synthesis"
        ).any { it in normalized }
    }

    private fun extractMinimumVisibleWords(runObjective: String): Int? {
        val patterns = listOf(
            Regex("""(?i)\bat\s+least\s+([0-9][0-9,]*)\s+(?:visible\s+)?(?:article\s+body\s+|body\s+)?words?\b"""),
            Regex("""(?i)\bminimum(?:\s+of)?\s+([0-9][0-9,]*)\s+(?:visible\s+)?(?:article\s+body\s+|body\s+)?words?\b""")
        )
        return patterns
            .firstNotNullOfOrNull { pattern ->
                pattern.find(runObjective)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.replace(",", "")
                    ?.toIntOrNull()
            }
    }

    private fun countVisibleHtmlWords(html: String): Int {
        val withoutTags = visibleHtmlText(html)
        return Regex("""[A-Za-z][A-Za-z0-9'’:-]*""")
            .findAll(withoutTags)
            .count()
    }

    private fun visibleHtmlText(html: String): String {
        val withoutScript = html.replace(Regex("(?is)<script\\b[^>]*>.*?</script>"), " ")
        val withoutStyle = withoutScript.replace(Regex("(?is)<style\\b[^>]*>.*?</style>"), " ")
        return withoutStyle
            .replace(Regex("(?is)<[^>]+>"), " ")
            .replace(Regex("&[a-zA-Z0-9#]+;"), " ")
    }

    /**
     * Applies per-round trust decay to all groups' trust score tables.
     * Also archives KB entries whose trust has dropped below the minimum threshold.
     */
    private suspend fun applyTrustDecayForRound(runId: String, groupIds: List<String>) = supervisorScope {
        val now = System.currentTimeMillis()
        val archiveThreshold = 0.30 // archive KB entries that fall below 30% trust
        groupIds.map { groupId ->
            async {
                try {
                    val db = substrateFactory.get(runId, groupId)
                    db.trustScoreDao().applyDecay(runId, groupId, trustDecayFactor, now)
                    val archived = db.knowledgeBaseDao().archiveBelowTrust(runId, groupId, archiveThreshold)
                    db.eventLogDao().insert(
                        EventLogEntryEntity(
                            id = UUID.randomUUID().toString(),
                            timestampMs = now,
                            eventType = EventTypes.TRUST_DECAY,
                            agentId = null,
                            groupId = groupId,
                            runId = runId,
                            generation = -1,
                            payloadJson = """{"decayFactor":$trustDecayFactor,"archiveThreshold":$archiveThreshold,"archivedLowTrustKb":$archived}"""
                        )
                    )
                    if (archived > 0) {
                        Napier.i("EvoMoliorRunService: trust decay — archived $archived low-trust KB entries in group=$groupId")
                    }
                } catch (e: Exception) {
                    Napier.w("EvoMoliorRunService: trust decay failed for group=$groupId: ${e.message}")
                }
            }
        }.awaitAll()
    }

    /**
     * Keeps each group's KB working set compact between generations.
     *
     * Old entries are not deleted. They move from `active` to `archived` when they are old enough,
     * have not been reused through full KB reads, and do not carry high trust. Dead Ends remain
     * visible because they encode known failed approaches.
     */
    internal suspend fun organizeKnowledgeBasesForGeneration(
        runId: String,
        groupIds: List<String>,
        currentGeneration: Int
    ) = supervisorScope {
        val now = System.currentTimeMillis()
        groupIds.map { groupId ->
            async {
                try {
                    val db = substrateFactory.get(runId, groupId)
                    val archivedStale = db.knowledgeBaseDao().archiveStaleForGeneration(
                        runId = runId,
                        groupId = groupId,
                        currentGeneration = currentGeneration,
                        staleGenerationWindow = KB_STALE_GENERATION_WINDOW,
                        minCitationCountToKeep = KB_STALE_MIN_CITATIONS_TO_KEEP,
                        minTrustScoreToKeep = KB_STALE_MIN_TRUST_SCORE_TO_KEEP
                    )
                    db.eventLogDao().insert(
                        EventLogEntryEntity(
                            id = UUID.randomUUID().toString(),
                            timestampMs = now,
                            eventType = EventTypes.KB_ORGANIZED,
                            agentId = null,
                            groupId = groupId,
                            runId = runId,
                            generation = currentGeneration,
                            payloadJson = buildString {
                                append("""{"staleGenerationWindow":$KB_STALE_GENERATION_WINDOW,""")
                                append(""""minCitationCountToKeep":$KB_STALE_MIN_CITATIONS_TO_KEEP,""")
                                append(""""minTrustScoreToKeep":$KB_STALE_MIN_TRUST_SCORE_TO_KEEP,""")
                                append(""""archivedStaleKb":$archivedStale}""")
                            }
                        )
                    )
                    if (archivedStale > 0) {
                        Napier.i(
                            "EvoMoliorRunService: KB organisation — archived $archivedStale stale " +
                                "KB entries in group=$groupId at generation=$currentGeneration"
                        )
                        emit(
                            EvoMoliorEvent.EngineProgress(
                                runId,
                                "kb_organized",
                                "[$groupId] Archived $archivedStale stale KB entr" +
                                    if (archivedStale == 1) "y" else "ies"
                            )
                        )
                    }
                } catch (e: Exception) {
                    Napier.w("EvoMoliorRunService: KB organisation failed for group=$groupId: ${e.message}")
                    emit(
                        EvoMoliorEvent.EngineProgress(
                            runId,
                            "kb_organization_failed",
                            "[$groupId] KB organisation failed at generation $currentGeneration: ${e.message}"
                        )
                    )
                    runCatching {
                        val db = substrateFactory.get(runId, groupId)
                        recordKbOrganizationFailure(
                            db = db,
                            runId = runId,
                            groupId = groupId,
                            currentGeneration = currentGeneration,
                            errorMessage = e.message.orEmpty(),
                            now = now
                        )
                    }.onFailure { eventError ->
                        Napier.w(
                            "EvoMoliorRunService: failed to write KB organisation failure event " +
                                "for group=$groupId: ${eventError.message}"
                        )
                    }
                }
            }
        }.awaitAll()
    }

    internal suspend fun recordKbOrganizationFailure(
        db: EvoMoliorDatabase,
        runId: String,
        groupId: String,
        currentGeneration: Int,
        errorMessage: String,
        now: Long = System.currentTimeMillis()
    ) {
        db.eventLogDao().insert(
            EventLogEntryEntity(
                id = UUID.randomUUID().toString(),
                timestampMs = now,
                eventType = EventTypes.KB_ORGANIZATION_FAILED,
                agentId = null,
                groupId = groupId,
                runId = runId,
                generation = currentGeneration,
                payloadJson = """{"error":"${escapeJsonForEvent(errorMessage)}"}"""
            )
        )
    }

    private fun escapeJsonForEvent(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

    /**
     * Notifies [ProgressiveDisclosureEngine] about group check-in scores after each round.
     *
     * Derives a [GroupCheckinResult] from the current group summaries and the per-dimension
     * scores cached by [computeSubstrateScores] and authoritative grader selection in
     * [latestGroupDimensionScores] (A-3). This must be called after both steps.
     *
     * @param runId The active run identifier.
     * @param groupIds The competing group IDs.
     * @param round The round index for this check-in.
     */
    private suspend fun processDisclosureCheckIns(runId: String, groupIds: List<String>, round: Int) {
        val engine = activeDisclosureEngine ?: return
        val atlas = activeWorkerAtlasId?.let { atlasId ->
            atlasTransactionRepository.currentAtlas(runId, atlasId)
        }
        if (atlas != null) {
            val projections = requireNotNull(
                atlasTransactionRepository.currentProjections(runId, atlas.atlasId)
            ) {
                "Node Atlas ${atlas.atlasId} has no disclosure projection"
            }
            val exactVerdict = exactGraderVerdictsByRound[round]
            val disclosureBundles = disclosureNodeGradeBundlesThrough(round)
            groupIds.forEach { groupId ->
                val stageIndex = projections.disclosure.groupStageIndices[groupId] ?: 0
                val stage = atlas.stageDefinitions.single { it.stageIndex == stageIndex }
                val contested = exactVerdict
                    ?.contestedCriticalRequirements
                    ?.get(AtlasGroupId(groupId))
                    .orEmpty()
                val evaluationStatus = when (exactVerdict?.status) {
                    ExactGraderRoundStatus.VALID ->
                        com.achulkov.evomolior.core.atlas.DisclosureEvaluationStatus.VALID
                    ExactGraderRoundStatus.ABSTAINED ->
                        com.achulkov.evomolior.core.atlas.DisclosureEvaluationStatus.ABSTAINED
                    ExactGraderRoundStatus.INVALID, null ->
                        com.achulkov.evomolior.core.atlas.DisclosureEvaluationStatus.INVALID
                }
                val decision = engine.onRoundEvaluated(
                    com.achulkov.evomolior.competition.disclosure.DisclosureRoundEvaluation(
                        atlas = atlas,
                        groupId = AtlasGroupId(groupId),
                        round = round,
                        currentStageIndex = stageIndex,
                        evaluationStatus = evaluationStatus,
                        nodeGradeBundles = disclosureBundles,
                        unresolvedHardFailureNodeIds =
                            if (contested.isEmpty()) emptySet() else stage.mandatoryNodeIds
                    )
                )
                val stageAdvanced =
                    decision.status ==
                        com.achulkov.evomolior.core.atlas.DisclosureDecisionStatus.ADVANCE
                disclosureStageAdvancedByGroupRound[groupId to round] = stageAdvanced
                if (stageAdvanced) {
                    emit(
                        EvoMoliorEvent.StageAdvanced(
                            runId = runId,
                            groupId = groupId,
                            fromStage = decision.fromStageIndex,
                            toStage = requireNotNull(decision.toStageIndex),
                            checkinScore = 0.0
                        )
                    )
                }
                if (decision.status ==
                    com.achulkov.evomolior.core.atlas.DisclosureDecisionStatus.HOLD_VALID_FAILURE
                ) {
                    disclosureFeedbackByGroup[groupId] = buildDisclosureFeedback(
                        atlas = atlas,
                        groupId = AtlasGroupId(groupId),
                        stageIndex = stageIndex,
                        decision = decision
                    )
                } else {
                    disclosureFeedbackByGroup.remove(groupId)
                }
            }
            applyLeakPressureForNextRound(groupIds)
            return
        }
        groupIds.forEach { groupId ->
            try {
                val checkin = buildDisclosureCheckinResult(groupId, round)
                engine.onCheckinComplete(groupId, checkin)
            } catch (e: Exception) {
                Napier.w("EvoMoliorRunService: progressive disclosure check-in failed for $groupId: ${e.message}")
            }
        }

        applyLeakPressureForNextRound(groupIds)
    }

    /**
     * Applies exact disclosure, fitness, capture, yield, and reward mutations only after exact
     * authority is known.
     *
     * Legacy and deterministic modes without an exact grader Atlas remain authoritative in their
     * own right. Once exact grader authority is configured, a missing, invalid, or abstained
     * verdict freezes disclosure and skips fitness, capture, yield, and reward mutations.
     */
    private suspend fun processAuthoritativeRoundMutationBoundary(
        config: RunConfig,
        groupIds: List<String>,
        gradingResult: com.achulkov.evomolior.core.model.GradingResult,
        round: Int,
        workerAtlas: NodeAtlas?
    ): Map<String, EpigeneticRoundSummary> {
        val exactVerdict = exactGraderVerdictsByRound[round]
        val exactAuthorityConfigured =
            graderAuthorityRepository.currentAtlas(config.runId) != null
        val exactAuthorityValid = when {
            exactVerdict != null -> exactVerdict.status == ExactGraderRoundStatus.VALID
            exactAuthorityConfigured -> false
            else -> true
        }

        if (!exactAuthorityValid) {
            Napier.w(
                "EvoMoliorRunService: round mutations failed closed for round=$round: " +
                    (exactVerdict?.reason ?: "exact grader verdict is missing")
            )
            return groupIds.associateWith { EpigeneticRoundSummary(0, 0, 0, 0, 0) }
        }

        processDisclosureCheckInsAtRoundBoundary(config.runId, groupIds, round)
        val epigeneticSummaries = runFitnessAndEpigenetics(config, groupIds, round)
        publishGradedResourceNodeClaims(
            config = config,
            gradingResult = gradingResult,
            round = round,
            workerAtlas = workerAtlas
        )
        return epigeneticSummaries
    }

    private suspend fun processDisclosureCheckInsAtRoundBoundary(
        runId: String,
        groupIds: List<String>,
        round: Int
    ) {
        val watchdogForCheckIn = activeWatchdog
        if (watchdogForCheckIn != null) {
            watchdogForCheckIn.openCheckInGate(round)
            // The signal collector updates the gate asynchronously.
            kotlinx.coroutines.yield()
        }
        try {
            if (checkInGateOpenRound == round || activeDisclosureEngine != null) {
                processDisclosureCheckIns(runId, groupIds, round)
            }
        } finally {
            watchdogForCheckIn?.closeCheckInGate(round)
        }
    }

    internal fun disclosureNodeGradeBundlesThrough(round: Int): List<NodeGradeBundle> =
        nodeGradeBundlesByRound.entries
            .asSequence()
            .filter { (bundleRound, _) -> bundleRound <= round }
            .flatMap { (_, bundles) -> bundles.asSequence() }
            .filter { it.round <= round }
            .sortedWith(
                compareBy<NodeGradeBundle> { it.round }
                    .thenBy { it.groupId.value }
                    .thenBy { it.nodeId.value }
                    .thenBy(NodeGradeBundle::bundleHash)
            )
            .toList()

    private fun buildDisclosureFeedback(
        atlas: NodeAtlas,
        groupId: AtlasGroupId,
        stageIndex: Int,
        decision: com.achulkov.evomolior.core.atlas.DisclosureDecision
    ): com.achulkov.evomolior.core.contract.GroupFeedbackPacket {
        val stage = atlas.stageDefinitions.single { it.stageIndex == stageIndex }
        val failedNodes = (
            decision.coverage.failedMandatoryNodeIds +
                decision.coverage.missingMandatoryNodeIds +
                decision.coverage.noEvidenceMandatoryNodeIds +
                decision.coverage.unresolvedHardFailureNodeIds
            ).intersect(stage.nodeIds)
        val packet = com.achulkov.evomolior.core.contract.GroupFeedbackPacket(
            feedbackId = "${decision.decisionHash}:worker",
            stageId = stage.stageId,
            failedVisibleNodeIds = failedNodes,
            evidenceGaps = failedNodes.sortedBy(NodeId::value).map { nodeId ->
                "Current-stage node ${nodeId.value} lacks accepted evidence at its required threshold."
            },
            actionableNextSteps = failedNodes.sortedBy(NodeId::value).map { nodeId ->
                val node = atlas.nodes.getValue(nodeId)
                "Address ${node.limitationStatement}; produce " +
                    "${node.proofContract.method.name.lowercase()} evidence."
            },
            feedbackHash = ""
        )
        val revealedNodeIds = atlas.stageDefinitions
            .filter { it.stageIndex <= stageIndex }
            .flatMapTo(linkedSetOf()) { it.nodeIds }
        return com.achulkov.evomolior.competition.disclosure.DisclosureFeedbackLinter()
            .validateAndSeal(
                atlas = atlas,
                groupId = groupId,
                currentStageIndex = stageIndex,
                revealedNodeIds = revealedNodeIds,
                split = com.achulkov.evomolior.core.atlas.DisclosureFeedbackSplit(
                    internalRationale = decision.reason,
                    workerFeedback = packet,
                    futureDisclosureNote = null
                )
            )
            .workerFeedback
            ?: error("Valid stage failure must produce worker feedback")
    }

    /** Builds a disclosure check-in from the authoritative round score plus test telemetry. */
    internal fun buildDisclosureCheckinResult(groupId: String, round: Int): GroupCheckinResult {
        val graderScore = authoritativeGraderRoundResults[round]
            ?.scores
            ?.firstOrNull { it.groupId == groupId }
        val assessment = latestGroupSubstrateAssessments[groupId]
            ?: latestGroupRoundAssessments[groupId]?.let { deterministic ->
                SubstrateRoundAssessment(
                    dimensionScores = deterministic.dimensionScores,
                    overallScore = deterministic.overallScore,
                    kbCount = 0,
                    kgCount = 0,
                    toolCount = 0,
                    toolSuccessRate = 0.0,
                    completedWipTasks = deterministic.automatedTestsPassed,
                    totalWipTasks = deterministic.automatedTestsPassed + deterministic.automatedTestsFailed
                )
            }
        val score = graderScore?.weightedTotal?.div(100.0)
            ?: assessment?.overallScore
            ?: 0.0
        val dimScores = graderScore?.dimensionScores?.associate { it.dimensionId to it.rawScore }
            ?: assessment?.dimensionScores?.associate { it.dimensionId to it.rawScore }
            ?: emptyMap()
        val totalTasks = assessment?.totalWipTasks ?: 0
        val testPassRate = if (totalTasks > 0) {
            assessment?.completedWipTasks?.toDouble()?.div(totalTasks) ?: 0.0
        } else 0.0

        return GroupCheckinResult(
            groupId = groupId,
            overallScore = score,
            passed = score >= 0.6,
            dimensionScores = dimScores,
            testPassRate = testPassRate,
            failedTestCount = if (latestGroupSubstrateAssessments.containsKey(groupId)) {
                (totalTasks - (assessment?.completedWipTasks ?: 0)).coerceAtLeast(0)
            } else {
                latestGroupRoundAssessments[groupId]?.automatedTestsFailed ?: 0
            },
            gradingSheetApplied = if (graderScore != null) {
                "grader-civilisation-round-$round"
            } else if (latestGroupSubstrateAssessments.containsKey(groupId)) {
                "substrate-round-$round"
            } else {
                latestGroupRoundAssessments[groupId]?.gradingSheetApplied ?: "round-$round"
            },
            stageAdvanced = disclosureStageAdvancedByGroupRound[groupId to round] == true
        )
    }

    /**
     * Raises outbound leak ceilings for groups adjacent to below-threshold groups.
     *
     * The disclosure engine tracks how badly a group missed check-in. We project that signal
     * onto the adjacent source groups so the next round increases leak traffic toward the
     * underperforming group.
     */
    private fun applyLeakPressureForNextRound(groupIds: List<String>) {
        val engine = activeDisclosureEngine ?: return
        val coordinator = activeCoordinator ?: return
        if (groupIds.size < 2) return

        groupIds.forEachIndexed { index, sourceGroupId ->
            val leftGroupId = groupIds[(index - 1 + groupIds.size) % groupIds.size]
            val rightGroupId = groupIds[(index + 1) % groupIds.size]
            val pressure = maxOf(
                engine.leakMultiplierFor(leftGroupId),
                engine.leakMultiplierFor(rightGroupId)
            )
            if (pressure <= 1.0) return@forEachIndexed

            val currentCeiling = coordinator.leakOracle.ceilingFor(sourceGroupId)
            val elevatedCeiling = maxOf(currentCeiling, kotlin.math.ceil(currentCeiling * pressure).toInt())
            coordinator.leakOracle.patchGroupCeiling(sourceGroupId, elevatedCeiling)
        }
    }

    /** Calls [RunArchiveService.archiveRun] after a successful run completion. */
    private suspend fun archiveCompletedRun(
        config: RunConfig,
        groupIds: List<String>,
        winnerId: String?,
        completionRecord: RunCompletionRecord? = null
    ) {
        val service = archiveService ?: return
        try {
            // Completion reuses the sealed Council snapshot. RunConfig projections intentionally
            // omit Council-authored stages, seed substrate, tests, and capture-map metadata.
            val startingField = loadSealedStartingFieldSnapshot(config.runId)
                ?: config.toArchiveStartingField()
            val startingFieldJson = runConfigJson.encodeToString(
                StartingField.serializer(),
                startingField
            )
            service.archiveRun(
                runId = config.runId,
                groupIds = groupIds,
                winnerId = winnerId,
                objective = config.objective,
                startingFieldJson = startingFieldJson,
                finalGeneration = completionRecord?.completedRoundCount
                    ?.takeIf { it > 0 }
                    ?: config.rounds,
                requestedOutputSpec = completionRecord?.requestedOutputSpec ?: config.finalOutputSpec,
                completionRecord = completionRecord
            )
            runFinalOutputSpecs.remove(config.runId)
            Napier.i("EvoMoliorRunService: run ${config.runId} archived successfully")
        } catch (e: Exception) {
            Napier.e("EvoMoliorRunService: failed to archive run ${config.runId}: ${e.message}", e)
            throw e
        }
    }

    /**
     * Writes a durable completion marker into the run meta-archive.
     *
     * Continuation gating and the run picker both need a completion signal that is available
     * even when the downloadable results bundle is not being written.
     *
     * @param runId The completed run identifier.
     * @param winnerId The winning group ID, if any.
     */
    private suspend fun persistRunCompletionMarker(completionRecord: RunCompletionRecord) {
        val archiveDb = substrateFactory.get(completionRecord.runId, META_ARCHIVE_GROUP_ID)
        val markerExists = runCatching {
            archiveDb.metaArchiveDao().getByType(RunArchiveService.ENTRY_RUN_COMPLETED)
        }.getOrElse { emptyList() }.any { it.runId == completionRecord.runId }
        if (markerExists) return

        archiveDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "run-completed-${completionRecord.runId}",
                runId = completionRecord.runId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run Completed — ${completionRecord.runId}",
                contentJson = runConfigJson.encodeToString(
                    RunCompletionRecord.serializer(),
                    completionRecord
                ),
                runObjective = archiveDb.metaArchiveDao()
                    .getByType(RunArchiveService.ENTRY_STARTING_FIELD)
                    .firstOrNull { it.runId == completionRecord.runId }
                    ?.runObjective.orEmpty(),
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )
    }

    /**
     * Persists the explicit graph-message envelope as a first-class archive entry.
     *
     * The envelope is stored separately from the completion record so continuation routing can
     * consume a dedicated graph message without reinterpreting the rest of the completion payload.
     */
    internal suspend fun persistRunGraphMessage(completionRecord: RunCompletionRecord) {
        val graphMessage = completionRecord.graphMessage ?: return
        val archiveDb = substrateFactory.get(completionRecord.runId, META_ARCHIVE_GROUP_ID)
        val markerExists = runCatching {
            archiveDb.metaArchiveDao().getByType(RunArchiveService.ENTRY_RUN_GRAPH_MESSAGE)
        }.getOrElse { emptyList() }.any { it.runId == completionRecord.runId }
        if (markerExists) return

        archiveDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "run-graph-message-${completionRecord.runId}",
                runId = completionRecord.runId,
                entryType = RunArchiveService.ENTRY_RUN_GRAPH_MESSAGE,
                groupId = null,
                label = "Run Graph Message — ${completionRecord.runId}",
                contentJson = runConfigJson.encodeToString(
                    RunGraphMessage.serializer(),
                    graphMessage
                ),
                runObjective = archiveDb.metaArchiveDao()
                    .getByType(RunArchiveService.ENTRY_STARTING_FIELD)
                    .firstOrNull { it.runId == completionRecord.runId }
                    ?.runObjective.orEmpty(),
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )
    }

    private suspend fun clearRunCompletionAuthorities(runId: String) {
        val archiveDao = substrateFactory.get(runId, META_ARCHIVE_GROUP_ID).metaArchiveDao()
        archiveDao.deleteByRunAndType(runId, RunArchiveService.ENTRY_RUN_GRAPH_MESSAGE)
        archiveDao.deleteByRunAndType(runId, RunArchiveService.ENTRY_RUN_COMPLETED)
        val survivingAuthorities = archiveDao.getByRun(runId).filter {
            it.entryType == RunArchiveService.ENTRY_RUN_GRAPH_MESSAGE ||
                it.entryType == RunArchiveService.ENTRY_RUN_COMPLETED
        }
        check(survivingAuthorities.isEmpty()) {
            "Failed to clear false completion authority for run=$runId: " +
                survivingAuthorities.joinToString { it.entryType }
        }
    }

    private fun RunConfig.toArchiveStartingField(): StartingField {
        val profilesByGroup = groupProfiles.mapIndexed { index, profile ->
            "group-$index" to profile
        }.toMap()
        return StartingField.default(
            objective = objective,
            runId = runId,
            groupCount = groupCount,
            groupProfilesMap = profilesByGroup,
            budgetCeiling = budgetOverride ?: 10_000.0,
            generationCeiling = rounds
        )
    }

    private suspend fun collectRunSubstrateHealth(
        runId: String,
        groupIds: List<String>,
        submissionSummariesByGroup: Map<String, String?>
    ): List<RunSubstrateHealthSummary> = groupIds.mapNotNull { groupId ->
        runCatching {
            val db = substrateFactory.get(runId, groupId)
            val stagedSubmissions = db.stagingDao().getAll(runId, groupId, limit = 10_000)
            val toolExecutionCount = db.eventLogDao().queryFiltered(
                runId = runId,
                groupId = groupId,
                agentId = null,
                eventType = EventTypes.TOOL_EXEC,
                minGeneration = null,
                maxGeneration = null,
                limit = 10_000,
                offset = 0
            ).size
            val trustDecayEvents = db.eventLogDao().queryFiltered(
                runId = runId,
                groupId = groupId,
                agentId = null,
                eventType = EventTypes.TRUST_DECAY,
                minGeneration = null,
                maxGeneration = null,
                limit = 10_000,
                offset = 0
            )
            RunSubstrateHealthSummary(
                groupId = groupId,
                kbCount = db.knowledgeBaseDao().countActive(runId, groupId),
                kgCount = db.knowledgeGraphFactDao().countActive(runId, groupId),
                activeToolCount = db.toolRegistryDao().getActive(runId, groupId, domain = null).size,
                toolExecutionCount = toolExecutionCount,
                kgFactSubmissionCount = stagedSubmissions.count { it.type == "KG_FACT" },
                toolSubmissionCount = stagedSubmissions.count { it.type == "TOOL" },
                kgAbstentionRationaleCount = stagedSubmissions.count {
                    it.content.contains("KG ABSTENTION RATIONALE", ignoreCase = true)
                },
                toolAbstentionRationaleCount = stagedSubmissions.count {
                    it.content.contains("TOOL ABSTENTION RATIONALE", ignoreCase = true)
                },
                noActionFallbackSubmissionCount = stagedSubmissions.count {
                    it.budgetSignature.startsWith("NO_ACTION_FALLBACK:")
                },
                trustDecayEventCount = trustDecayEvents.size,
                lowTrustArchivedByDecayCount = trustDecayEvents
                    .sumOf { event -> archivedLowTrustKbFromTrustDecayPayload(event.payloadJson) },
                stagedSubmissionCount = stagedSubmissions.size,
                promotedSubmissionCount = stagedSubmissions.count { it.status == "promoted" },
                pendingSubmissionCount = stagedSubmissions.count { it.status == "pending" },
                rejectedSubmissionCount = stagedSubmissions.count { it.status == "rejected" },
                outlierSubmissionCount = stagedSubmissions.count { it.outlier },
                roundSubmissionSummaryPresent = !submissionSummariesByGroup[groupId].isNullOrBlank()
            )
        }.onFailure { error ->
            Napier.w(
                "EvoMoliorRunService: failed to collect substrate health for run=$runId group=$groupId: ${error.message}"
            )
        }.getOrNull()
    }

    /**
     * Builds the completion-time graph envelope used by continuation routing.
     *
     * The message is intentionally compact and route-ready so a later continuation can trust the
     * archived envelope before touching file-system artefacts.
     */
    private fun buildRunGraphMessage(
        runId: String,
        winnerId: String?,
        finalAnswerSummary: String,
        finalDeliverableHtml: String?,
        generatedOutputFiles: List<GeneratedOutputFile>,
        generatedImageAssets: List<GeneratedImageAsset> = emptyList(),
        judgeOutputsByGrader: Map<String, String>,
        submissionSummariesByGroup: Map<String, String?>,
        substrateHealthByGroup: List<RunSubstrateHealthSummary> = emptyList()
    ): RunGraphMessage {
        val routedContextSummaries = buildList {
            val highLevelRunLogSummary = buildString {
                append("winner=")
                append(winnerId ?: "none")
                if (finalAnswerSummary.isNotBlank()) {
                    append(" | finalAnswer=")
                    append(summarizeRunGraphSnippet(finalAnswerSummary, 200))
                }
                if (generatedOutputFiles.isNotEmpty()) {
                    append(" | outputs=")
                    append(
                        generatedOutputFiles.joinToString(", ") { output ->
                            "${output.relativePath}=${summarizeRunGraphSnippet(output.content, 120)}"
                        }
                    )
                }
                if (generatedImageAssets.isNotEmpty()) {
                    append(" | images=")
                    append(generatedImageAssets.joinToString(", ") { it.relativePath })
                }
                if (substrateHealthByGroup.isNotEmpty()) {
                    append(" | substrateHealth=")
                    append(
                        substrateHealthByGroup.joinToString(";") { health ->
                            "${health.groupId}:kb=${health.kbCount},kg=${health.kgCount},kgSubmissions=${health.kgFactSubmissionCount}," +
                                "tools=${health.activeToolCount},toolSubmissions=${health.toolSubmissionCount}," +
                                "toolExec=${health.toolExecutionCount},kgAbstain=${health.kgAbstentionRationaleCount}," +
                                "toolAbstain=${health.toolAbstentionRationaleCount},noActionFallback=${health.noActionFallbackSubmissionCount}," +
                                "decayEvents=${health.trustDecayEventCount}," +
                                "decayArchived=${health.lowTrustArchivedByDecayCount},staged=${health.stagedSubmissionCount}," +
                                "summaries=${health.roundSubmissionSummaryPresent},outlier=${health.outlierSubmissionCount}"
                        }
                    )
                }
            }
            add("high-level-run-log: ${highLevelRunLogSummary.ifBlank { "run completed" }}")
            finalAnswerSummary.takeIf { it.isNotBlank() }?.let { summary ->
                add("final-answer-summary: ${summarizeRunGraphSnippet(summary, 240)}")
            }
            finalDeliverableHtml?.takeIf { it.isNotBlank() }?.let { html ->
                add("final-deliverable-html: ${summarizeRunGraphSnippet(stripHtmlMarkup(html), 240)}")
            }
            generatedOutputFiles.forEach { output ->
                add("generated-output:${output.relativePath}: ${summarizeRunGraphSnippet(output.content, 180)}")
            }
            submissionSummariesByGroup.forEach { (groupId, summary) ->
                summary?.takeIf { it.isNotBlank() }?.let {
                    add("group-summary:$groupId: ${summarizeRunGraphSnippet(it, 180)}")
                }
            }
            judgeOutputsByGrader.forEach { (graderId, output) ->
                output.takeIf { it.isNotBlank() }?.let {
                    add("judge-output:$graderId: ${summarizeRunGraphSnippet(it, 180)}")
                }
            }
        }.distinct().take(MAX_RUN_GRAPH_MESSAGE_SUMMARIES)

        return RunGraphMessage(
            runId = runId,
            headline = summarizeRunGraphSnippet(
                finalAnswerSummary.ifBlank { "run $runId completed${winnerId?.let { " winner=$it" }.orEmpty()}" },
                240
            ),
            routedContextSummaries = routedContextSummaries
        )
    }

    private fun summarizeRunGraphSnippet(text: String, maxChars: Int): String = removeProtocolPlanningLines(text)
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(maxChars)

    private fun removeProtocolPlanningLines(text: String): String {
        val filtered = text.lineSequence()
            .filterNot { line -> isProtocolPlanningLine(line) }
            .joinToString(" ")
            .trim()
        return filtered.ifBlank { "[protocol-only content omitted]" }
    }

    private fun isProtocolPlanningLine(line: String): Boolean {
        val normalized = line.lowercase(Locale.ROOT)
        return listOf(
            "write_death_note",
            "write my death note",
            "write my mandatory death note",
            "write my mandatory",
            "write a death note",
            "mandatory death note",
            "now i must write",
            "now i should write",
            "now i'll write",
            "now writing my mandatory",
            "tool call",
            "call the tool"
        ).any { marker -> marker in normalized }
    }

    private fun stripHtmlMarkup(html: String): String = summarizeRunGraphSnippet(html, Int.MAX_VALUE)

    private fun adversarialDiscriminatorBriefCacheKey(runId: String, groupId: String): String = "$runId::$groupId"

    /**
     * Restores exact completed-round authority when resume starts after the configured last round.
     */
    private suspend fun loadCompletedRoundAuthority(
        runId: String,
        expectedCompletedRoundCount: Int,
        expectedGroupIds: List<String>
    ): RoundSummaryRecord {
        require(expectedCompletedRoundCount > 0) {
            "Completed-round restoration requires a positive round count"
        }
        val records = substrateFactory.get(runId, META_ARCHIVE_GROUP_ID)
            .metaArchiveDao()
            .getByRun(runId)
            .asSequence()
            .filter {
                it.entryType == RunArchiveService.ENTRY_ROUND_SUMMARY &&
                    it.groupId == null
            }
            .map { entry ->
                runConfigJson.decodeFromString(
                    RoundSummaryRecord.serializer(),
                    entry.contentJson
                )
            }
            .toList()
        require(records.all { it.runId == runId }) {
            "Completed-round authority belongs to another run"
        }
        val byRound = records.groupBy(RoundSummaryRecord::roundIndex)
        require(byRound.values.all { it.size == 1 }) {
            "Completed-round authority contains duplicate run-level summaries"
        }
        require(byRound.keys == (0 until expectedCompletedRoundCount).toSet()) {
            "Completed-round authority is not contiguous through round " +
                (expectedCompletedRoundCount - 1)
        }
        return byRound.getValue(expectedCompletedRoundCount - 1).single().also { final ->
            require(
                final.groupSummaries.map(RoundGroupSummaryRecord::groupId).toSet() ==
                    expectedGroupIds.toSet() &&
                    final.groupSummaries.size == expectedGroupIds.size
            ) {
                "Final round authority does not cover the exact worker groups"
            }
        }
    }

    private fun RoundSummaryRecord.toGradingResult():
        com.achulkov.evomolior.core.model.GradingResult {
        val scores = groupSummaries.map { summary ->
            com.achulkov.evomolior.core.model.RoundScore(
                groupId = summary.groupId,
                roundIndex = roundIndex,
                dimensionScores = summary.blindScore.dimensionScores
                    .toSortedMap()
                    .map { (dimensionId, rawScore) ->
                        DimensionScore(
                            dimensionId = dimensionId,
                            rawScore = rawScore,
                            justification =
                                "Restored from durable round-summary authority."
                        )
                    },
                weightedTotal = summary.blindScore.weightedTotal,
                rank = summary.blindScore.rank
            )
        }.sortedWith(
            compareBy<com.achulkov.evomolior.core.model.RoundScore>(
                com.achulkov.evomolior.core.model.RoundScore::rank
            ).thenBy(com.achulkov.evomolior.core.model.RoundScore::groupId)
        )
        val resolution = if (roundWinnerId != null) {
            require(scores.any { it.groupId == roundWinnerId }) {
                "Completed-round winner is absent from exact group scores"
            }
            com.achulkov.evomolior.core.model.ScoringResolution.RESOLVED
        } else {
            require(scores.map { it.weightedTotal }.distinct().size <= 1) {
                "Unresolved completed-round authority contains unequal scores"
            }
            if (scores.all { it.weightedTotal == 0.0 }) {
                com.achulkov.evomolior.core.model.ScoringResolution.ALL_ZERO_DEGENERACY
            } else {
                com.achulkov.evomolior.core.model.ScoringResolution.ALL_EQUAL_TIE
            }
        }
        return com.achulkov.evomolior.core.model.GradingResult(
            roundIndex = roundIndex,
            scores = scores,
            winnerId = roundWinnerId,
            resolution = resolution
        )
    }

    private suspend fun sealRoundAtlasPhase(
        runId: String,
        round: Int,
        roundAuthority: RoundAtlasAuthority?
    ): NodeAtlas {
        val workerAtlas = requireNotNull(
            roundAuthority?.workerAtlas ?: atlasTransactionRepository.currentAtlas(runId)
        ) { "Round start requires sealed worker Atlas authority" }
        advanceRoundPhase(
            runId,
            round,
            RoundPhaseV1.ATLAS_MATCHED_FROZEN,
            listOf(
                "worker-atlas:${workerAtlas.atlasHash}",
                roundAuthority?.graderAtlas?.atlasHash
                    ?.let { "grader-atlas:$it" }
                    ?: "grader-atlas:not-configured"
            )
        )
        return workerAtlas
    }

    private suspend fun prepareAndSpawnRoundWorkers(
        config: RunConfig,
        groupIds: List<String>,
        round: Int,
        workerAtlas: NodeAtlas,
        roundAuthority: RoundAtlasAuthority?
    ) {
        prepareRoundNodePortfolios(config, groupIds, round)
        val spawnCredits = applyDueNodeYields(config.runId, groupIds, round)
        advanceRoundPhase(
            config.runId,
            round,
            RoundPhaseV1.DUE_YIELDS_AND_DIAGNOSTICS_APPLIED,
            groupIds.sorted().map { groupId ->
                roundPhaseAuthority(
                    "round-inputs:$groupId",
                    config.runId,
                    round.toString(),
                    spawnCredits.getOrDefault(groupId, 0.0).toString()
                )
            }
        )
        advanceRoundPhase(
            config.runId,
            round,
            RoundPhaseV1.PORTFOLIOS_AND_RUNTIME_CELLS_COMPILED,
            groupIds.sorted().map { groupId ->
                roundPhaseAuthority(
                    "portfolio-runtime:$groupId",
                    config.runId,
                    round.toString(),
                    groupId,
                    workerAtlas.atlasHash
                )
            }
        )
        advanceRoundPhase(
            config.runId,
            round,
            RoundPhaseV1.GRADER_FRONTIER_FROZEN,
            listOf(
                roundAuthority?.graderAtlas?.atlasHash
                    ?.let { "grader-frontier:$it" }
                    ?: "grader-frontier:not-configured"
            )
        )
        spawnAgentsForRound(config, groupIds, round, spawnCredits)
        advanceRoundPhase(
            config.runId,
            round,
            RoundPhaseV1.WORKERS_SPAWNED,
            groupIds.sorted().map { groupId ->
                val registered = liveLifecycleManagers[groupId]
                    ?.publicationAccounting()
                    ?.registeredNonDaemonAgentIds
                    ?.sorted()
                    .orEmpty()
                roundPhaseAuthority(
                    "workers:$groupId",
                    *(registered.ifEmpty { listOf("none") }.toTypedArray())
                )
            }
        )
    }

    private suspend fun recordCriteriaSpawnedPhase(
        runId: String,
        round: Int,
        graderGroupIds: List<String>,
        spawned: Boolean
    ) {
        advanceRoundPhase(
            runId,
            round,
            RoundPhaseV1.CRITERIA_SPAWNED,
            listOf(
                if (spawned) {
                    roundPhaseAuthority(
                        "criteria",
                        runId,
                        round.toString(),
                        *graderGroupIds.sorted().toTypedArray()
                    )
                } else {
                    "criteria:not-configured"
                }
            )
        )
    }

    private suspend fun settleRoundExecutionPhases(
        runId: String,
        groupIds: List<String>,
        round: Int
    ) {
        val reviewedToolAuthority = groupIds.flatMap { groupId ->
            substrateFactory.get(runId, groupId)
                .stagingDao()
                .getReviewedToolsByGeneration(runId, groupId, round)
                .map { submission ->
                    roundPhaseAuthority(
                        "public-preflight:$groupId:${submission.id}",
                        submission.toolCandidateAuthorityJson.orEmpty(),
                        submission.status
                    )
                }
        }
        advanceRoundPhase(
            runId,
            round,
            RoundPhaseV1.PUBLIC_PREFLIGHT_COMPLETED,
            reviewedToolAuthority.ifEmpty { listOf("public-preflight:no-tool-candidates") }
        )
        val accounting = groupIds.associateWith { groupId ->
            requireNotNull(liveLifecycleManagers[groupId]) {
                "Round settlement lacks lifecycle manager for $groupId"
            }.awaitQuiescence()
        }
        require(accounting.values.all { it.isQuiescent }) {
            "Round settlement requires actual runner quiescence and settled reservations"
        }
        advanceRoundPhase(
            runId,
            round,
            RoundPhaseV1.LINEARIZABLE_EXECUTION_SETTLED,
            accounting.flatMap { (groupId, value) ->
                value.registeredNonDaemonAgentIds.sorted().map { agentId ->
                    "exit:$groupId:$agentId"
                }.ifEmpty { listOf("exit:$groupId:none") }
            }
        )
    }

    private suspend fun recordCriticRoundPhase(
        runId: String,
        groupIds: List<String>,
        round: Int,
        criticSummaries: Map<String, CriticSummary>
    ) {
        advanceRoundPhase(
            runId,
            round,
            RoundPhaseV1.CRITIC_AND_SEMANTIC_REVIEW_COMPLETED,
            groupIds.sorted().map { groupId ->
                val summary = criticSummaries[groupId] ?: CriticSummary(0, 0, 0)
                roundPhaseAuthority(
                    "critic:$groupId",
                    summary.promoted.toString(),
                    summary.rejected.toString(),
                    summary.arbitrated.toString()
                )
            }
        )
    }

    private suspend fun recordEvidenceAndCriteriaRoundPhases(
        runId: String,
        groupIds: List<String>,
        graderGroupIds: List<String>,
        round: Int,
        workerAtlas: NodeAtlas
    ) {
        val packetRefs = sealedNodeEvidenceByRound[round]
            .orEmpty()
            .map { "evidence-packet:${it.packetId}:${it.packetHash}" }
            .sorted()
        advanceRoundPhase(
            runId,
            round,
            RoundPhaseV1.EVIDENCE_AND_TOOL_RECORDS_SEALED,
            packetRefs.ifEmpty {
                groupIds.sorted().map { groupId ->
                    roundPhaseAuthority(
                        "sealed-evidence-tools:$groupId",
                        runId,
                        round.toString(),
                        groupId,
                        workerAtlas.atlasHash
                    )
                }
            }
        )
        val criteriaRefs = if (graderCriteriaRunner != null && graderGroupIds.isNotEmpty()) {
            graderGroupIds.sorted().map { graderGroupId ->
                val freeze = requireNotNull(
                    graderAuthorityRepository.criteriaFreezeForRound(
                        runId,
                        round,
                        graderGroupId
                    )
                ) { "Round closure lacks criteria freeze for $graderGroupId" }
                "criteria-freeze:$graderGroupId:${freeze.freezeHash}"
            }
        } else {
            listOf("criteria-freeze:legacy-or-not-configured")
        }
        advanceRoundPhase(runId, round, RoundPhaseV1.CRITERIA_FROZEN, criteriaRefs)
    }

    private suspend fun recordEvidenceRevealRoundPhase(
        runId: String,
        graderGroupIds: List<String>,
        round: Int,
        judgeOutputs: Map<String, String>
    ) {
        val refs = judgeOutputs.entries.sortedBy { it.key }.map { (graderId, output) ->
            "evidence-reveal:$graderId:${AtlasHashing.hashText(output)}"
        }.ifEmpty {
            listOf(
                if (graderJudgingRunner == null || graderGroupIds.isEmpty()) {
                    "evidence-reveal:legacy-or-not-configured"
                } else {
                    "evidence-reveal:no-valid-output"
                }
            )
        }
        advanceRoundPhase(runId, round, RoundPhaseV1.EVIDENCE_REVEALED, refs)
    }

    private suspend fun recordGraderOutcomeRoundPhase(
        runId: String,
        round: Int,
        result: com.achulkov.evomolior.core.model.GradingResult?
    ) {
        advanceRoundPhase(
            runId,
            round,
            RoundPhaseV1.GRADER_OUTCOMES_PERSISTED,
            listOf(
                roundPhaseAuthority(
                    "grader-outcome",
                    result?.winnerId.orEmpty(),
                    result?.resolution?.name.orEmpty(),
                    *result?.scores.orEmpty()
                        .sortedBy { it.groupId }
                        .map { "${it.groupId}:${it.weightedTotal}" }
                        .toTypedArray()
                )
            )
        )
    }

    private suspend fun sealRoundMutationSnapshotsAndArchive(
        config: RunConfig,
        groupIds: List<String>,
        graderGroupIds: List<String>,
        round: Int,
        criticSummaries: Map<String, CriticSummary>,
        epigeneticSummaries: Map<String, EpigeneticRoundSummary>,
        blindResult: com.achulkov.evomolior.core.model.GradingResult,
        judgeOutputs: Map<String, String>,
        roundSubmissionSummaries: Map<String, String?>
    ) {
        val outlierMetrics = processOutlierNoveltyAtRoundBoundary(
            config,
            groupIds,
            graderGroupIds,
            round
        )
        val postMutationAtlas = requireNotNull(
            atlasTransactionRepository.currentAtlas(config.runId)
        ) { "Round mutation boundary lost sealed Atlas authority" }
        advanceRoundPhase(
            config.runId,
            round,
            RoundPhaseV1.MUTATION_CAPTURE_YIELD_DISCLOSURE_AND_OUTLIER_RESOLVED,
            listOf(
                "post-mutation-atlas:${postMutationAtlas.atlasHash}",
                outlierMetrics?.let {
                    roundPhaseAuthority(
                        "outlier",
                        it.proposalCount.toString(),
                        it.acceptedShadowNodeCount.toString(),
                        it.testedProposalCount.toString(),
                        it.acceptedAmendmentCount.toString()
                    )
                } ?: "outlier:not-configured"
            )
        )
        advanceRoundPhase(
            config.runId,
            round,
            RoundPhaseV1.FUTURE_DIAGNOSTICS_PUBLISHED,
            groupIds.sorted().map { groupId ->
                val summary = criticSummaries[groupId] ?: CriticSummary(0, 0, 0)
                roundPhaseAuthority(
                    "future-diagnostics:$groupId",
                    summary.rejected.toString(),
                    summary.arbitrated.toString(),
                    round.toString()
                )
            }
        )
        beforeRoundSnapshotCompilation(config.runId, round)
        val snapshotHashes = compileAndPersistInterimDeliverableSnapshots(
            config,
            groupIds,
            round,
            roundSubmissionSummaries
        )
        advanceRoundPhase(
            config.runId,
            round,
            RoundPhaseV1.INTERIM_SNAPSHOTS_COMPILED,
            snapshotHashes.entries.sortedBy { it.key }.map { (groupId, hash) ->
                "interim-snapshot:$groupId:$hash"
            }
        )
        val summaries = archiveRoundSummaries(
            runId = config.runId,
            runObjective = config.objective,
            groupIds = groupIds,
            round = round,
            criticSummaries = criticSummaries,
            epigeneticSummaries = epigeneticSummaries,
            blindResult = blindResult,
            judgeOutputsByGrader = judgeOutputs,
            roundSubmissionSummaries = roundSubmissionSummaries
        )
        advanceRoundPhase(
            config.runId,
            round,
            RoundPhaseV1.ROUND_LEDGER_ARCHIVED,
            summaries.entries.sortedBy { it.key }.map { (id, hash) ->
                "round-summary:$id:$hash"
            }
        )
        persistArcArtifactRoundSnapshots(
            config = config,
            groupIds = groupIds,
            round = round,
        )
    }

    private suspend fun compileAndPersistInterimDeliverableSnapshots(
        config: RunConfig,
        groupIds: List<String>,
        round: Int,
        roundSubmissionSummaries: Map<String, String?>
    ): Map<String, String> {
        val atlas = requireNotNull(atlasTransactionRepository.currentAtlas(config.runId)) {
            "Interim deliverable snapshots require current sealed Atlas authority"
        }
        val receiverContract = requireNotNull(
            atlasTransactionRepository.currentReceiverContract(config.runId, atlas.atlasId)
        ) { "Interim deliverable snapshots require the exact sealed receiver contract" }
        val repository = DeliverablePersistenceRepository(
            substrateFactory.getAtlas(config.runId)
        )
        val blueprint = repository.appendBlueprint(
            SealedRoundDeliverableSnapshotCompiler.compileBlueprint(receiverContract)
        )
        requireCouncilSealedDeliverableBlueprint(
            runId = config.runId,
            blueprint = blueprint,
            requireSeal = false,
        )
        require(blueprint.receiverContractHash == atlas.receiverContractHash) {
            "Interim deliverable blueprint does not match the current sealed Atlas"
        }

        val packets = nodeCaptureRepository.evidencePacketsForRun(config.runId)
            .filter { it.round <= round }
        val grades = nodeCaptureRepository.gradeBundlesForRun(config.runId)
            .filter { it.round <= round }
        val decisions = nodeCaptureRepository.captureDecisionsForRun(config.runId)
            .filter { it.round <= round }
        val snapshotHashes = linkedMapOf<String, String>()
        groupIds.sorted().forEach { groupId ->
            val assessment = latestGroupRoundAssessments[groupId]
            val failedGates = buildSet {
                if (assessment?.mandatoryFailure == true) {
                    add("sealed-suite:mandatory-failure")
                }
                if ((assessment?.automatedTestsFailed ?: 0) > 0) {
                    add("sealed-suite:public-test-failure")
                }
                exactGraderVerdictsByRound[round]
                    ?.takeIf { it.status != ExactGraderRoundStatus.VALID }
                    ?.let { add("exact-grader:${it.status.name}") }
            }
            val snapshot = SealedRoundDeliverableSnapshotCompiler.compile(
                SealedRoundDeliverableSnapshotRequest(
                    receiverContract = receiverContract,
                    atlas = atlas,
                    atlasHistory = atlasTransactionRepository.versionHistory(
                        config.runId,
                        atlas.atlasId,
                    ),
                    runId = config.runId,
                    round = round,
                    groupId = groupId,
                    evidencePackets = packets.filter { it.groupId.value == groupId },
                    gradeBundles = grades,
                    captureDecisions = decisions,
                    submissionSummaryPresent =
                        !roundSubmissionSummaries[groupId].isNullOrBlank(),
                    additionalFailedGateIds = failedGates,
                )
            )
            require(snapshot.blueprintHash == blueprint.blueprintHash) {
                "Interim snapshot blueprint authority changed during round closure"
            }
            val persisted = repository.appendSnapshot(snapshot)
            require(
                repository.snapshot(config.runId, groupId, round) == persisted
            ) { "Interim snapshot readback failed for $groupId round=$round" }
            snapshotHashes[groupId] = persisted.snapshotHash
        }
        require(snapshotHashes.keys == groupIds.toSortedSet()) {
            "Round closure requires exactly one interim snapshot for every active group"
        }
        return snapshotHashes
    }

    private suspend fun requireCouncilSealedDeliverableBlueprint(
        runId: String,
        blueprint: com.achulkov.evomolior.core.deliverable.DeliverableBlueprint,
        requireSeal: Boolean,
    ) {
        val seal = loadSealedStartingFieldSnapshot(runId)?.seal
        if (requireSeal) {
            requireNotNull(seal) {
                "Accepted deliverable blueprint requires a Genesis Council field seal"
            }
        }
        seal?.let {
            require(
                it.schemaVersion == CouncilFieldSealing.SEAL_SCHEMA_VERSION &&
                    CouncilContractHashing.constantTimeEquals(
                        it.deliverableBlueprintHash,
                        blueprint.blueprintHash,
                    )
            ) {
                "Deliverable blueprint differs from the Genesis Council seal"
            }
        }
    }

    private suspend fun advanceRoundPhase(
        runId: String,
        round: Int,
        phase: RoundPhaseV1,
        authorityReferenceIds: Collection<String>
    ): RoundPhaseLedgerEventV1 {
        val refs = authorityReferenceIds.filter(String::isNotBlank).distinct().sorted()
        require(refs.isNotEmpty()) { "Round phase $phase requires explicit authority" }
        val repository = RoundPhaseLedgerRepository(substrateFactory.getAtlas(runId))
        val history = repository.history(runId, round)
        history.singleOrNull { it.phase == phase }?.let { existing ->
            require(existing.authorityReferenceIds == refs) {
                "Round phase $phase authority changed during replay"
            }
            return existing
        }
        require(history.size == phase.ordinal) {
            "Round phase $phase cannot skip or replay an incomplete predecessor"
        }
        val event = RoundPhaseLedgerEventV1(
            runId = runId,
            round = round,
            phase = phase,
            sequence = phase.ordinal + 1,
            previousEventHash = history.lastOrNull()?.eventHash,
            authorityReferenceIds = refs,
            occurredAt = Clock.System.now()
        ).withComputedHash()
        return if (phase == RoundPhaseV1.ROUND_LEDGER_ARCHIVED) {
            repository.archive(event)
        } else {
            repository.append(event)
        }
    }

    private fun roundPhaseAuthority(
        label: String,
        vararg values: String
    ): String = "$label:${AtlasHashing.hashText(values.toList().sorted().joinToString("|"))}"

    /**
     * Persists one round summary per competing group so the end-of-round output is queryable
     * outside the transient log stream.
     */
    private suspend fun archiveRoundSummaries(
        runId: String,
        runObjective: String,
        groupIds: List<String>,
        round: Int,
        criticSummaries: Map<String, CriticSummary>,
        epigeneticSummaries: Map<String, EpigeneticRoundSummary>,
        blindResult: com.achulkov.evomolior.core.model.GradingResult,
        judgeOutputsByGrader: Map<String, String>,
        roundSubmissionSummaries: Map<String, String?>
    ): Map<String, String> {
        val archiveDb = substrateFactory.get(runId, META_ARCHIVE_GROUP_ID)
        val now = System.currentTimeMillis()
        val roundSummary = RoundSummaryRecord(
            runId = runId,
            roundIndex = round,
            roundWinnerId = blindResult.winnerId,
            judgeOutputsByGrader = judgeOutputsByGrader,
            groupSummaries = groupIds.map { groupId ->
                val critic = criticSummaries[groupId] ?: CriticSummary(0, 0, 0)
                val epigenetic = epigeneticSummaries[groupId] ?: EpigeneticRoundSummary(0, 0, 0, 0, 0)
                val deterministicAssessment = latestGroupRoundAssessments[groupId]
                val blindScore = blindResult.scores.firstOrNull { it.groupId == groupId }
                RoundGroupSummaryRecord(
                    runId = runId,
                    roundIndex = round,
                    groupId = groupId,
                    criticPromoted = critic.promoted,
                    criticRejected = critic.rejected,
                    criticArbitrated = critic.arbitrated,
                    fitnessScoredAgents = epigenetic.fitnessScoredAgents,
                    fitnessTopEligibleAgents = epigenetic.fitnessTopEligibleAgents,
                    legacyProtocolsExtracted = epigenetic.legacyProtocolsExtracted,
                    legacyProtocolsExpired = epigenetic.legacyProtocolsExpired,
                    deadEndsInserted = epigenetic.deadEndsInserted,
                    deterministicScore = DeterministicAssessmentRecord(
                        overallScore = deterministicAssessment?.overallScore ?: 0.0,
                        automatedTestsPassed = deterministicAssessment?.automatedTestsPassed ?: 0,
                        automatedTestsFailed = deterministicAssessment?.automatedTestsFailed ?: 0,
                        mandatoryFailure = deterministicAssessment?.mandatoryFailure ?: false,
                        gradingSheetApplied = deterministicAssessment?.gradingSheetApplied ?: "sealed-suite-$round",
                        // Counts remain public telemetry; private test identities never enter the
                        // meta-archive or its downloadable bundle projection.
                        failedTestIds = emptyList(),
                        dimensionScores = deterministicAssessment?.dimensionScores?.associate { it.dimensionId to it.rawScore }.orEmpty()
                    ),
                    disclosureCheckin = runCatching { buildDisclosureCheckinResult(groupId, round) }
                        .getOrElse {
                            GroupCheckinResult(
                                groupId = groupId,
                                overallScore = 0.0,
                                passed = false,
                                dimensionScores = emptyMap(),
                                testPassRate = 0.0,
                                failedTestCount = 0,
                                gradingSheetApplied = "sealed-suite-$round",
                                stageAdvanced = false
                            )
                        }.let { checkin ->
                            DisclosureCheckinRecord(
                                overallScore = checkin.overallScore,
                                passed = checkin.passed,
                                testPassRate = checkin.testPassRate,
                                failedTestCount = checkin.failedTestCount,
                                gradingSheetApplied = checkin.gradingSheetApplied,
                                stageAdvanced = checkin.stageAdvanced,
                                dimensionScores = checkin.dimensionScores
                            )
                        },
                    blindScore = BlindScoreRecord(
                        weightedTotal = blindScore?.weightedTotal ?: 0.0,
                        rank = blindScore?.rank ?: groupIds.size,
                        dimensionScores = blindScore?.dimensionScores?.associate { it.dimensionId to it.rawScore }.orEmpty()
                    ),
                    submissionSummary = roundSubmissionSummaries[groupId]
                )
            }
        )

        val archived = linkedMapOf<String, String>()
        appendImmutableMetaArchiveEntry(
            archiveDb,
            MetaArchiveEntryEntity(
                id = "round-summary-$runId-$round",
                runId = runId,
                entryType = RunArchiveService.ENTRY_ROUND_SUMMARY,
                groupId = null,
                label = "Round summary — round ${round + 1}",
                contentJson = runConfigJson.encodeToString(
                    RoundSummaryRecord.serializer(),
                    roundSummary
                ),
                runObjective = runObjective,
                finalScore = null,
                isFromWinner = false,
                generation = round,
                archivedAtMs = now
            )
        ).also { entry -> archived[entry.id] = AtlasHashing.hashText(entry.contentJson) }

        groupIds.forEach { groupId ->
            val groupSummary = requireNotNull(
                roundSummary.groupSummaries.singleOrNull { it.groupId == groupId }
            ) { "Round summary lacks exact group authority for $groupId" }
            appendImmutableMetaArchiveEntry(
                archiveDb,
                MetaArchiveEntryEntity(
                    id = "round-summary-$runId-$round-$groupId",
                    runId = runId,
                    entryType = RunArchiveService.ENTRY_ROUND_SUMMARY,
                    groupId = groupId,
                    label = "Round summary — $groupId — round ${round + 1}",
                    contentJson = runConfigJson.encodeToString(
                        RoundGroupSummaryRecord.serializer(),
                        groupSummary
                    ),
                    runObjective = runObjective,
                    finalScore = groupSummary.blindScore.weightedTotal,
                    isFromWinner = groupSummary.groupId == blindResult.winnerId,
                    generation = round,
                    archivedAtMs = now
                )
            ).also { entry -> archived[entry.id] = AtlasHashing.hashText(entry.contentJson) }
        }

        emit(EvoMoliorEvent.EngineProgress(
            runId,
            "round_summary",
            "Round ${round + 1} summaries archived for ${groupIds.size} groups"
        ))
        require(archived.size == groupIds.size + 1) {
            "Round summary archive must contain one run record and every group record"
        }
        return archived.toSortedMap()
    }

    private suspend fun appendImmutableMetaArchiveEntry(
        database: EvoMoliorDatabase,
        candidate: MetaArchiveEntryEntity
    ): MetaArchiveEntryEntity {
        val dao = database.metaArchiveDao()
        dao.getById(candidate.id)?.let { existing ->
            require(existing.sameImmutableArchiveAuthority(candidate)) {
                "Meta-archive identity ${candidate.id} is immutable"
            }
            return existing
        }
        dao.insertImmutable(candidate)
        return requireNotNull(dao.getById(candidate.id)) {
            "Meta-archive append conflicted for ${candidate.id}"
        }.also { persisted ->
            require(persisted.sameImmutableArchiveAuthority(candidate)) {
                "Meta-archive identity ${candidate.id} is immutable"
            }
        }
    }

    private fun MetaArchiveEntryEntity.sameImmutableArchiveAuthority(
        other: MetaArchiveEntryEntity
    ): Boolean = copy(archivedAtMs = other.archivedAtMs) == other

    /**
     * Canonicalises the Genesis Council output by filling in the sealed test suite hash
     * when the suite JSON is present.
     *
     * @param startingField Raw council output.
     * @return The same field with a canonical suite hash if a suite was provided.
     */
    private fun canonicalizeStartingField(startingField: StartingField): StartingField {
        val canonicalSuiteField = if (startingField.sealedTestSuiteJson.isBlank()) {
            startingField
        } else {
            val suiteHash = canonicalTestSuiteHash(startingField.sealedTestSuiteJson)
            if (startingField.sealedTestSuiteHash == suiteHash) startingField
            else startingField.copy(sealedTestSuiteHash = suiteHash)
        }
        return canonicalSuiteField.copy(
            graderGroupCount = normalizeGraderGroupCount(
                workerGroupCount = canonicalSuiteField.groupCount,
                requestedGraderGroupCount = canonicalSuiteField.graderGroupCount
            )
        )
    }

    /**
     * Reconciles council-authored worker topology with the run request before the field is sealed.
     *
     * Council output is a design proposal, while the requested worker count and active run
     * identity are execution invariants. Profiles retain the council's ring order where possible;
     * requested profiles fill any missing positions. Runtime IDs are canonical so every sealed
     * topology, persisted profile map, and worker database refers to the same groups.
     */
    private fun canonicalizeWorkerStartingField(
        startingField: StartingField,
        runId: String,
        requestedGroupCount: Int,
        fallbackProfiles: List<GroupProfileType>
    ): StartingField {
        require(requestedGroupCount > 0) { "requestedGroupCount must be positive" }
        require(fallbackProfiles.isNotEmpty()) { "fallbackProfiles must not be empty" }

        val councilProfiles = buildList {
            startingField.ringTopology
                .distinct()
                .mapNotNullTo(this) { groupId -> startingField.groupProfiles[groupId] }
            startingField.groupProfiles
                .filterKeys { it !in startingField.ringTopology }
                .values
                .forEach { add(it) }
        }
        val canonicalGroupIds = (0 until requestedGroupCount).map { index -> "group-$index" }
        val canonicalProfiles = canonicalGroupIds.mapIndexed { index, groupId ->
            groupId to (councilProfiles.getOrNull(index) ?: fallbackProfiles[index % fallbackProfiles.size])
        }.toMap()

        if (
            startingField.runId != runId ||
            startingField.groupCount != requestedGroupCount ||
            startingField.ringTopology != canonicalGroupIds ||
            startingField.groupProfiles != canonicalProfiles
        ) {
            Napier.w(
                "EvoMoliorRunService: canonicalized worker StartingField " +
                    "runId=${startingField.runId}→$runId " +
                    "groupCount=${startingField.groupCount}→$requestedGroupCount " +
                    "topology=${startingField.ringTopology}→$canonicalGroupIds"
            )
        }

        return startingField.copy(
            runId = runId,
            groupCount = requestedGroupCount,
            groupProfiles = canonicalProfiles,
            ringTopology = canonicalGroupIds
        )
    }

    private fun normalizeGraderGroupCount(
        workerGroupCount: Int,
        requestedGraderGroupCount: Int
    ): Int {
        if (workerGroupCount <= 1) return 0
        return requestedGraderGroupCount.coerceAtLeast(DEFAULT_MULTI_GRADER_GROUP_COUNT)
    }

    private suspend fun resolveGraderStartingField(
        config: RunConfig,
        workerStartingField: StartingField
    ): StartingField? {
        if (workerStartingField.groupCount <= 1 && config.graderGroupCountOverride == null) {
            return null
        }

        val desiredCount = config.graderGroupCountOverride ?: normalizeGraderGroupCount(
            workerGroupCount = workerStartingField.groupCount,
            requestedGraderGroupCount = workerStartingField.graderGroupCount
        )
        val fallbackField = buildFallbackGraderStartingField(
            runId = config.runId,
            workerStartingField = workerStartingField,
            graderGroupCount = desiredCount
        )
        return fallbackField
    }

    private fun buildGraderCouncilObjective(workerStartingField: StartingField): String = buildString {
        append("Design a grader civilisation for the worker objective: ")
        append(workerStartingField.objective)
        append(". Produce several competing grader groups with distinct evaluation philosophies. ")
        append("Their task is to create deep, diverse, adversarial criteria for judging worker-group results. ")
        append("They must expose shallow or gamed outputs, reward reproducibility and evidence, ")
        append("and prefer hard-to-fake competence over surface polish.")
    }

    private fun normalizeGraderStartingField(
        runId: String,
        candidate: StartingField,
        desiredCount: Int,
        workerStartingField: StartingField
    ): StartingField {
        val canonical = canonicalizeStartingField(candidate)
        val resolvedProfiles = (0 until desiredCount).associate { index ->
            "grader-$index" to (
                canonical.groupProfiles.values.elementAtOrNull(index)
                    ?: GroupProfileType.entries[index % GroupProfileType.entries.size]
                )
        }
        val fallbackField = buildFallbackGraderStartingField(
            runId = runId,
            workerStartingField = workerStartingField,
            graderGroupCount = desiredCount
        )
        return canonical.copy(
            runId = runId,
            objective = canonical.objective.ifBlank { fallbackField.objective },
            groupCount = desiredCount,
            groupProfiles = resolvedProfiles,
            ringTopology = resolvedProfiles.keys.toList(),
            graderGroupCount = 0,
            primordialKb = canonical.primordialKb.ifEmpty { fallbackField.primordialKb },
            norms = canonical.norms.ifBlank { fallbackField.norms }
        )
    }

    private fun buildFallbackGraderStartingField(
        runId: String,
        workerStartingField: StartingField,
        graderGroupCount: Int
    ): StartingField {
        val profiles = (0 until graderGroupCount).associate { index ->
            "grader-$index" to GroupProfileType.entries[index % GroupProfileType.entries.size]
        }
        return StartingField.default(
            objective = buildGraderCouncilObjective(workerStartingField),
            runId = runId,
            groupCount = graderGroupCount,
            groupProfilesMap = profiles,
            budgetCeiling = workerStartingField.globalBudgetCeiling,
            generationCeiling = workerStartingField.generationCeiling
        ).copy(
            primordialKb = listOf(
                com.achulkov.evomolior.core.contract.SeedKBDocument(
                    id = "grader-seed-mission",
                    domain = "grader_seed",
                    content = """
                        Mission: evaluate worker civilisations with deep, adversarial rigor.
                        Reward evidence, reproducibility, comparative strength, and resilient execution.
                        Penalise unsupported claims, superficial polish, and brittle output.
                    """.trimIndent()
                ),
                com.achulkov.evomolior.core.contract.SeedKBDocument(
                    id = "grader-seed-objective",
                    domain = "grader_seed",
                    content = "Worker objective under evaluation: ${workerStartingField.objective}"
                )
            ),
            norms = "Be evidence-based. Prefer hard-to-fake signals. Design tie-breakers and failure-mode checks."
        )
    }

    /**
     * Applies explicit run-level model overrides so they win over council-generated pools.
     *
     * Pools are rewritten to singleton lists for overridden roles because the lifecycle
     * model resolver prefers pools before assignments.
     */
    private fun applyModelOverrides(
        startingField: StartingField,
        modelOverrides: Map<String, String>
    ): StartingField {
        if (modelOverrides.isEmpty()) return startingField

        val mergedAssignments = startingField.modelAssignment + modelOverrides
        val mergedPools = startingField.modelPools.toMutableMap()
        modelOverrides.forEach { (role, modelId) ->
            mergedPools[role] = listOf(modelId)
        }

        return startingField.copy(
            modelAssignment = mergedAssignments,
            modelPools = mergedPools
        )
    }

    /** Projects the same operator policy for diagnostic/continuation paths without a Council. */
    private fun applyCodexRuntimePreset(startingField: StartingField, config: RunConfig): StartingField {
        val preset = config.codexRuntimePreset ?: return startingField
        return startingField.copy(
            modelAssignment = preset.runtimeAssignments(),
            modelPools = preset.runtimeModelPools(),
        )
    }

    private fun normalizeStartingFieldModelsForBackend(
        startingField: StartingField,
        llmBackend: LlmBackend
    ): StartingField {
        if (llmBackend != LlmBackend.QWEN_CLOUD) return startingField

        val supportedQwenModelIds = setOf(
            ModelSelectionConfig.QWEN_CLOUD_QWEN_PLUS_MODEL,
            ModelSelectionConfig.QWEN_CLOUD_QWEN_FAST_MODEL,
            ModelSelectionConfig.QWEN_CLOUD_DEEPSEEK_FLASH_MODEL
        )
        val qwenDefaults = ModelSelectionConfig.qwenCloudRuntimeAssignments()
        val roleNames = (
            startingField.modelAssignment.keys +
                startingField.modelPools.keys +
                qwenDefaults.keys
            ).toSortedSet()

        val normalizedAssignments = roleNames.associateWith { roleName ->
            startingField.modelAssignment[roleName]
                ?.takeIf { it in supportedQwenModelIds }
                ?: qwenDefaults[roleName]
                ?: ModelSelectionConfig.QWEN_CLOUD_QWEN_PLUS_MODEL
        }
        val normalizedPools = roleNames.associateWith { roleName ->
            val defaultModel = normalizedAssignments[roleName]
                ?: qwenDefaults[roleName]
                ?: ModelSelectionConfig.QWEN_CLOUD_QWEN_PLUS_MODEL
            if (roleName in startingField.modelAssignment && startingField.modelPools[roleName] == listOf(defaultModel)) {
                return@associateWith listOf(defaultModel)
            }
            val secondaryModel = supportedQwenModelIds
                .firstOrNull { it != defaultModel }
                ?: defaultModel
            listOf(
                listOf(defaultModel),
                startingField.modelPools[roleName]
                .orEmpty()
                .filter { it in supportedQwenModelIds }
                    .filterNot { it == defaultModel },
                listOf(secondaryModel)
            ).flatten().distinct()
        }

        return startingField.copy(
            modelAssignment = normalizedAssignments,
            modelPools = normalizedPools
        )
    }

    /**
     * Fills in a small objective-driven expert bundle when the council did not author one.
     *
     * This keeps the MoE baseline additive: council-authored assignments still win, but
     * test runs and fallback starts get a deterministic expert profile instead of a blank map.
     */
    internal suspend fun applyObjectiveExpertRouting(startingField: StartingField): StartingField {
        if (startingField.modelAssignment.isNotEmpty() || startingField.modelPools.isNotEmpty()) return startingField
        val bundle = ObjectiveExpertRouter.select(startingField.objective)
        Napier.i("EvoMoliorRunService: objective expert router selected ${bundle.name} for run ${startingField.runId}")
        emit(
            EvoMoliorEvent.ObjectiveExpertBundleSelected(
                runId = startingField.runId,
                bundleName = bundle.name,
                objective = startingField.objective
            )
        )
        return startingField.copy(
            modelAssignment = bundle.modelAssignment,
            modelPools = bundle.modelPools
        )
    }

    /**
     * Persists the sealed test suite once, in the first competing group's substrate DB.
     *
     * The suite is stored write-once and locked. If the first group is unavailable or the
     * insert fails, startup aborts because there is no safe alternate canonical location.
     *
     * @param runId The active run identifier.
     * @param firstGroupId The first competing group ID.
     * @param startingField The council output containing the sealed suite.
     */
    private suspend fun persistSealedTestSuite(
        runId: String,
        firstGroupId: String?,
        startingField: StartingField
    ) {
        val groupId = firstGroupId
            ?: failStartup(runId, "sealed test suite could not be stored because no competing group was available")

        runCatching {
            val db = substrateFactory.get(runId, groupId)
            val suiteHash = canonicalTestSuiteHash(startingField.sealedTestSuiteJson)
            db.sealedTestSuiteDao().insert(
                com.achulkov.evomolior.substrate.db.entity.SealedTestSuiteEntity(
                    id = "sealed-suite-$runId",
                    runId = runId,
                    groupId = groupId,
                    suiteJson = startingField.sealedTestSuiteJson,
                    suiteHash = suiteHash,
                    isLocked = true,
                    createdAtMs = System.currentTimeMillis()
                )
            )
            Napier.i("EvoMoliorRunService: sealed test suite stored for run=$runId group=$groupId hash=$suiteHash")
        }.getOrElse { e ->
            failStartup(runId, "failed to store sealed test suite for run=$runId group=$groupId: ${e.message}", e)
        }
    }

    /**
     * Persists the full Genesis Council output as a write-once sealed archive snapshot.
     *
     * This is the canonical record for council-authored disclosure stage content and all
     * other StartingField fields used to boot the run.
     *
     * @param runId The active run identifier.
     * @param startingField The council output to seal.
     */
    private suspend fun persistSealedStartingField(
        runId: String,
        startingField: StartingField
    ) {
        val archiveDb = substrateFactory.get(runId, META_ARCHIVE_GROUP_ID)
        val existing = runCatching {
            archiveDb.metaArchiveDao().getByType(RunArchiveService.ENTRY_STARTING_FIELD)
        }.getOrElse { emptyList() }.firstOrNull { it.runId == runId }
        if (existing != null) {
            failStartup(runId, "sealed StartingField already exists for run=$runId")
        }

        val startingFieldJson = runConfigJson.encodeToString(
            StartingField.serializer(),
            startingField
        )

        runCatching {
            archiveDb.metaArchiveDao().insert(
                MetaArchiveEntryEntity(
                    id = "starting-field-$runId",
                    runId = runId,
                    entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                    groupId = null,
                    label = "StartingField — $runId",
                    contentJson = startingFieldJson,
                    runObjective = startingField.objective,
                    finalScore = null,
                    isFromWinner = false,
                    generation = 0,
                    archivedAtMs = System.currentTimeMillis()
                )
            )
        }.getOrElse { e ->
            failStartup(runId, "failed to seal StartingField for run=$runId: ${e.message}", e)
        }

        val stored = runCatching {
            archiveDb.metaArchiveDao().getByType(RunArchiveService.ENTRY_STARTING_FIELD)
        }.getOrElse { emptyList() }.firstOrNull { it.id == "starting-field-$runId" }
            ?: failStartup(runId, "sealed StartingField not retrievable for run=$runId")

        val decoded = decodeCurrentStartingField(
            runId = runId,
            subject = "sealed StartingField readback",
            content = stored.contentJson
        )

        if (decoded != startingField) {
            failStartup(runId, "sealed StartingField readback mismatch for run=$runId")
        }

        Napier.i("EvoMoliorRunService: sealed StartingField stored for run=$runId stageCount=${startingField.stageContents.size}")
    }

    private data class PersistedCompiledCouncilCandidate(
        val archiveEntryId: String,
        val manifest: CompiledCouncilCandidateManifestV3
    )

    /**
     * Persists one immutable, non-active compiled Council candidate and strictly reads it back.
     */
    private suspend fun persistCompiledCouncilCandidate(
        runId: String,
        compiled: StartingFieldCompileResult.Valid,
        envelope: ExecutionEnvelope
    ): PersistedCompiledCouncilCandidate {
        val manifest = CompiledCouncilCandidateManifestV3(
            runId = runId,
            workerStartingField = compiled.workerField,
            graderStartingField = compiled.graderField,
            workerAtlas = compiled.atlas,
            graderAtlas = compiled.graderAtlas,
            receiverContract = compiled.receiverContract,
            executionEnvelope = envelope,
            consistencyReport = compiled.report,
            privateSuite = compiled.sealedTests.privateSuite,
            workerCommitmentView = compiled.sealedTests.workerCommitmentView,
            validationContext = compiled.sealedTests.validationContext
        ).withComputedHash()
        runCatching(manifest::requireValid).getOrElse { error ->
            failStartup(
                runId,
                "compiled Council candidate failed v3 authority validation: ${error.message}",
                error
            )
        }
        val contentJson = CompiledCouncilCandidateManifestV3Codec.encode(manifest)
        val archiveEntryId = "compiled-council-candidate-v3-$runId-${manifest.manifestHash}"
        val archiveDb = substrateFactory.get(runId, META_ARCHIVE_GROUP_ID)
        val existing = runCatching {
            archiveDb.metaArchiveDao()
                .getByType(RunArchiveService.ENTRY_COMPILED_COUNCIL_CANDIDATE_MANIFEST)
                .filter { it.runId == runId }
        }.getOrElse { error ->
            failStartup(
                runId,
                "failed to inspect compiled Council candidate manifests: ${error.message}",
                error
            )
        }
        if (existing.isNotEmpty()) {
            failStartup(runId, "compiled Council candidate already exists for run=$runId")
        }
        runCatching {
            archiveDb.metaArchiveDao().insert(
                MetaArchiveEntryEntity(
                    id = archiveEntryId,
                    runId = runId,
                    entryType =
                        RunArchiveService.ENTRY_COMPILED_COUNCIL_CANDIDATE_MANIFEST,
                    groupId = null,
                    label = "Compiled Council candidate v3 - $runId",
                    contentJson = contentJson,
                    runObjective = compiled.workerField.objective,
                    finalScore = null,
                    isFromWinner = false,
                    generation = 0,
                    archivedAtMs = System.currentTimeMillis()
                )
            )
        }.getOrElse { error ->
            failStartup(
                runId,
                "failed to persist compiled Council candidate manifest: ${error.message}",
                error
            )
        }
        val stored = runCatching {
            archiveDb.metaArchiveDao().getByRun(runId)
                .singleOrNull { it.id == archiveEntryId }
        }.getOrElse { error ->
            failStartup(
                runId,
                "failed to read back compiled Council candidate manifest: ${error.message}",
                error
            )
        } ?: failStartup(runId, "compiled Council candidate manifest is missing after persistence")
        if (stored.contentJson != contentJson) {
            failStartup(runId, "compiled Council candidate manifest changed during persistence")
        }
        val readback = runCatching {
            CompiledCouncilCandidateManifestV3Codec.decodeVerified(stored.contentJson)
        }.getOrElse { error ->
            failStartup(
                runId,
                "compiled Council candidate manifest failed strict readback: ${error.message}",
                error
            )
        }
        /*
         * The persisted canonical JSON and its self-hash are the authority here. Do not compare
         * the in-memory and decoded Kotlin data classes: collection implementations and decoded
         * numeric representations can be semantically identical while producing a false
         * data-class inequality. The exact bytes were already checked above, and decodeVerified
         * revalidates every hash-bound field and invariant.
         */
        if (
            !CouncilContractHashing.constantTimeEquals(
                readback.manifestHash,
                manifest.manifestHash
            ) ||
            CompiledCouncilCandidateManifestV3Codec.encode(readback) != contentJson
        ) {
            failStartup(
                runId,
                "compiled Council candidate manifest canonical authority changed during typed readback"
            )
        }
        return PersistedCompiledCouncilCandidate(archiveEntryId, readback)
    }

    private suspend fun verifyCompiledCouncilCandidateStorage(
        runId: String,
        candidate: PersistedCompiledCouncilCandidate
    ) {
        val manifest = candidate.manifest
        val archiveEntry = runCatching {
            substrateFactory.get(runId, META_ARCHIVE_GROUP_ID)
                .metaArchiveDao()
                .getByRun(runId)
                .singleOrNull { it.id == candidate.archiveEntryId }
        }.getOrElse { error ->
            failStartup(
                runId,
                "failed to re-read compiled Council candidate before activation: ${error.message}",
                error
            )
        } ?: failStartup(runId, "compiled Council candidate disappeared before activation")
        if (
            archiveEntry.entryType !=
                RunArchiveService.ENTRY_COMPILED_COUNCIL_CANDIDATE_MANIFEST
        ) {
            failStartup(runId, "compiled Council candidate changed archive entry type")
        }
        val archivedManifest = runCatching {
            CompiledCouncilCandidateManifestV3Codec.decodeVerified(archiveEntry.contentJson)
        }.getOrElse { error ->
            failStartup(
                runId,
                "compiled Council candidate failed final strict readback: ${error.message}",
                error
            )
        }
        if (archivedManifest != manifest) {
            failStartup(runId, "compiled Council candidate changed before activation")
        }
    }

    private suspend fun activateCompiledCouncilCandidate(
        runId: String,
        candidate: PersistedCompiledCouncilCandidate
    ): ActiveCompiledCouncilManifestV3 {
        verifyCompiledCouncilCandidateStorage(runId, candidate)
        val active = ActiveCompiledCouncilManifestV3(
            runId = runId,
            candidateArchiveEntryId = candidate.archiveEntryId,
            candidateManifestHash = candidate.manifest.manifestHash,
            activatedAtEpochMs = System.currentTimeMillis()
        ).withComputedHash()
        val contentJson = ActiveCompiledCouncilManifestV3Codec.encode(active)
        val archiveDb = substrateFactory.get(runId, META_ARCHIVE_GROUP_ID)
        val existing = runCatching {
            archiveDb.metaArchiveDao()
                .getByType(RunArchiveService.ENTRY_ACTIVE_COMPILED_COUNCIL_MANIFEST)
                .filter { it.runId == runId }
        }.getOrElse { error ->
            failStartup(
                runId,
                "failed to inspect active compiled Council manifests: ${error.message}",
                error
            )
        }
        if (existing.isNotEmpty()) {
            failStartup(runId, "ACTIVE compiled Council manifest already exists for run=$runId")
        }
        val entryId = "active-compiled-council-v3-$runId"
        runCatching {
            archiveDb.metaArchiveDao().insert(
                MetaArchiveEntryEntity(
                    id = entryId,
                    runId = runId,
                    entryType = RunArchiveService.ENTRY_ACTIVE_COMPILED_COUNCIL_MANIFEST,
                    groupId = null,
                    label = "ACTIVE compiled Council manifest v3 - $runId",
                    contentJson = contentJson,
                    runObjective = candidate.manifest.workerStartingField.objective,
                    finalScore = null,
                    isFromWinner = false,
                    generation = 0,
                    archivedAtMs = System.currentTimeMillis()
                )
            )
        }.getOrElse { error ->
            failStartup(
                runId,
                "failed to persist ACTIVE compiled Council manifest: ${error.message}",
                error
            )
        }
        val stored = runCatching {
            archiveDb.metaArchiveDao().getByRun(runId).singleOrNull { it.id == entryId }
        }.getOrElse { error ->
            failStartup(
                runId,
                "failed to read back ACTIVE compiled Council manifest: ${error.message}",
                error
            )
        } ?: failStartup(runId, "ACTIVE compiled Council manifest is missing after persistence")
        if (stored.contentJson != contentJson) {
            failStartup(runId, "ACTIVE compiled Council manifest changed during persistence")
        }
        val readback = runCatching {
            ActiveCompiledCouncilManifestV3Codec.decodeVerified(stored.contentJson)
        }.getOrElse { error ->
            failStartup(
                runId,
                "ACTIVE compiled Council manifest failed strict readback: ${error.message}",
                error
            )
        }
        if (
            ActiveCompiledCouncilManifestV3Codec.encode(readback) != contentJson ||
            readback.runId != active.runId ||
            readback.candidateArchiveEntryId != candidate.archiveEntryId ||
            !CouncilContractHashing.constantTimeEquals(
                readback.candidateManifestHash,
                candidate.manifest.manifestHash
            ) ||
            !CouncilContractHashing.constantTimeEquals(
                readback.activationHash,
                active.activationHash
            )
        ) {
            failStartup(
                runId,
                "ACTIVE compiled Council manifest canonical authority changed during typed readback"
            )
        }
        return readback
    }

    /**
     * Verifies canonical runtime projections only after the immutable candidate is ACTIVE.
     *
     * Candidate activation must be derivable solely from candidate-contained authority. The
     * mutable canonical field, suite, Atlas, and grader projections are intentionally checked
     * here, after their ordered materialisation.
     */
    private suspend fun verifyActiveCompiledCouncilCanonicalProjections(
        runId: String,
        candidate: PersistedCompiledCouncilCandidate,
        active: ActiveCompiledCouncilManifestV3
    ) {
        if (
            active.candidateArchiveEntryId != candidate.archiveEntryId ||
            !CouncilContractHashing.constantTimeEquals(
                active.candidateManifestHash,
                candidate.manifest.manifestHash
            )
        ) {
            failStartup(runId, "ACTIVE compiled Council manifest no longer binds its candidate")
        }
        val manifest = candidate.manifest
        val storedWorkerField = loadSealedStartingFieldSnapshot(runId)
            ?: failStartup(runId, "ACTIVE compiled Council worker StartingField is missing")
        if (storedWorkerField != manifest.workerStartingField) {
            failStartup(runId, "ACTIVE compiled Council worker StartingField changed after activation")
        }
        verifySealedTestSuite(
            runId = runId,
            firstGroupId = manifest.workerStartingField.ringTopology.firstOrNull(),
            expectedHash = manifest.graderStartingField.sealedTestSuiteHash,
            expectedSuiteJson = manifest.graderStartingField.sealedTestSuiteJson
        )
        val storedWorkerAtlas = atlasTransactionRepository.currentAtlas(
            runId,
            manifest.workerAtlas.atlasId
        ) ?: failStartup(runId, "ACTIVE compiled Council worker Atlas is missing")
        val storedReceiver = atlasTransactionRepository.currentReceiverContract(
            runId,
            manifest.workerAtlas.atlasId
        ) ?: failStartup(runId, "ACTIVE compiled Council receiver contract is missing")
        val storedGraderAtlas = graderAuthorityRepository.currentAtlas(runId)
            ?: failStartup(runId, "ACTIVE compiled Council Grader Atlas is missing")
        if (
            storedWorkerAtlas != manifest.workerAtlas ||
            storedReceiver != manifest.receiverContract ||
            storedGraderAtlas != manifest.graderAtlas
        ) {
            failStartup(runId, "ACTIVE compiled Council canonical projections changed after activation")
        }
    }

    /**
     * Loads the sealed [StartingField] snapshot for a previously completed run.
     *
     * Continuation relies on the archived snapshot rather than any live runtime state so the
     * source run stays immutable and reproducible.
     *
     * @param runId The source run identifier.
     * @return The sealed [StartingField], or null if the run has no archived snapshot yet.
     */
    private suspend fun loadSealedStartingField(
        runId: String,
        startupRunId: String
    ): StartingField? = withContext(Dispatchers.IO) {
        val archiveDb = substrateFactory.get(runId, META_ARCHIVE_GROUP_ID)
        val completed = runCatching {
            archiveDb.metaArchiveDao().getByType(RunArchiveService.ENTRY_RUN_COMPLETED)
        }.getOrElse { emptyList() }.any { it.runId == runId }
        if (!completed) {
            failStartup(startupRunId, "continuation source run=$runId is not completed yet")
        }
        val storedEntries = runCatching {
            archiveDb.metaArchiveDao().getByRun(runId)
        }.getOrElse { emptyList() }
            .filter { it.entryType == RunArchiveService.ENTRY_STARTING_FIELD }
        val currentEntries = storedEntries.filter { entry ->
            StartingFieldWireContract.validationError(
                entry.contentJson,
                "continuation source StartingField for run=$runId"
            ) == null
        }
        if (currentEntries.isEmpty() && storedEntries.isNotEmpty()) {
            failStartup(
                startupRunId,
                checkNotNull(
                    StartingFieldWireContract.validationError(
                        storedEntries.first().contentJson,
                        "continuation source StartingField for run=$runId"
                    )
                )
            )
        }
        val stored = currentEntries
            .sortedWith(
                compareByDescending<MetaArchiveEntryEntity> { it.id == "starting-field-$runId" }
                    .thenByDescending { it.contentJson.length }
            )
            .firstOrNull()
            ?: return@withContext null

        decodeCurrentStartingField(
            runId = startupRunId,
            subject = "continuation source StartingField",
            content = stored.contentJson
        )
    }

    /**
     * Builds a continuation seed from a sealed source run.
     *
     * The source run provides the immutable baseline; the new launch config only overrides the
     * objective and generation ceiling so the continuation keeps the original group layout and
     * council-authored substrate seed intact.
     *
     * @param source The source run's sealed [StartingField].
     * @param config The new run configuration.
     * @return A derived [StartingField] for the fresh run ID.
     */
    private fun buildContinuationStartingField(
        source: StartingField,
        config: RunConfig,
        routedContextSummaries: List<String>
    ): StartingField = source.copy(
        runId = config.runId,
        objective = config.objective,
        generationCeiling = config.rounds,
        continuedFromRunId = source.runId,
        routedContextSummaries = routedContextSummaries,
        feedforwardLayer = source.feedforwardLayer?.nextLayer(source.runId) ?: source.feedforwardLayer,
        runGraphEdges = source.runGraphEdges + RunGraphEdge(
            sourceRunId = source.runId,
            targetRunId = config.runId,
            edgeType = "continuation",
            weight = 1.0
        )
    )

    internal fun seedFeedforwardLayerIfMissing(startingField: StartingField): StartingField {
        if (startingField.feedforwardLayer != null) return startingField
        return startingField.copy(
            feedforwardLayer = FeedforwardLayerSpec(
                layerIndex = 0,
                layerLabel = "L1",
                upstreamRunIds = emptyList<String>(),
                layerObjective = startingField.objective
            )
        )
    }

    internal suspend fun routeContinuationContextSummaries(
        sourceRunId: String,
        sourceStartingField: StartingField,
        objective: String,
        maxSummaries: Int = 3
    ): List<String> = ContinuationContextGraphRouter(
        substrateFactory = substrateFactory,
        runConfigJson = runConfigJson,
        resolveRunRoot = ::resolveRunRoot
    ).routeContinuationContextSummaries(
        sourceRunId = sourceRunId,
        sourceStartingField = sourceStartingField,
        objective = objective,
        loadSealedStartingField = ::loadSealedStartingFieldSnapshot,
        maxSummaries = maxSummaries
    )

    /**
     * Copies the source run's artifact directory into the new run's artifact root.
     *
     * The agent tools read from the mounted filesystem, so the continuation run must receive the
     * actual files, not only their metadata snapshots.
     *
     * @param sourceRunId The completed run being continued.
     * @param targetRunId The fresh run that will consume the copied artifacts.
     */
    private suspend fun copyContinuationArtifacts(
        sourceRunId: String,
        targetRunId: String,
        sourceArtifacts: List<RunInputArtifact>
    ) {
        if (sourceArtifacts.isEmpty()) return
        val sourceRoot = resolveRunArtifactsRoot(sourceRunId)
        if (!sourceRoot.exists()) {
            failStartup(
                targetRunId,
                "continuation source run=$sourceRunId artifact root is missing"
            )
        }

        val targetRoot = resolveRunArtifactsRoot(targetRunId)
        targetRoot.mkdirs()

        for (artifact in sourceArtifacts) {
            val sourceFile = File(sourceRoot, artifact.relativePath)
            if (!sourceFile.exists()) {
                failStartup(
                    targetRunId,
                    "continuation source run=$sourceRunId artifact missing: " +
                        artifact.relativePath
                )
            }

            val targetFile = File(targetRoot, artifact.relativePath)
            targetFile.parentFile?.mkdirs()
            Files.copy(
                sourceFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES
            )
        }
    }

    /**
     * Merges source-run and launch-time input artifacts into a single canonical list.
     *
     * New uploads take precedence on path collisions so users can replace a continued artifact
     * without mutating the source run.
     *
     * @param sourceArtifacts Artifacts inherited from the sealed continuation seed.
     * @param uploadedArtifacts Fresh uploads materialised for the new run.
     * @return A de-duplicated list keyed by relative path.
     */
    private fun mergeInputArtifacts(
        sourceArtifacts: List<RunInputArtifact>,
        uploadedArtifacts: List<RunInputArtifact>
    ): List<RunInputArtifact> {
        if (sourceArtifacts.isEmpty() && uploadedArtifacts.isEmpty()) return emptyList()

        val mergedByPath = LinkedHashMap<String, RunInputArtifact>(sourceArtifacts.size + uploadedArtifacts.size)
        sourceArtifacts.forEach { artifact ->
            mergedByPath[artifact.relativePath] = artifact
        }
        uploadedArtifacts.forEach { artifact ->
            mergedByPath[artifact.relativePath] = artifact
        }
        return mergedByPath.values.toList()
    }

    /**
     * Verifies that the sealed test suite written at genesis is immediately readable.
     *
     * This provides a bootstrap-time consumer path for the suite so the next stage can rely
     * on a persisted canonical copy instead of only trusting the in-memory council output.
     *
     * @param runId The active run identifier.
     * @param firstGroupId The first competing group ID.
     * @param expectedHash The canonical suite hash we expect to read back.
     */
    private suspend fun verifySealedTestSuite(
        runId: String,
        firstGroupId: String?,
        expectedHash: String,
        expectedSuiteJson: String? = null
    ) {
        val groupId = firstGroupId
            ?: failStartup(runId, "sealed test suite could not be verified because no competing group was available")

        val stored = runCatching {
            substrateFactory.get(runId, groupId).sealedTestSuiteDao().get(runId, groupId)
        }.getOrElse { e ->
            failStartup(runId, "failed to read sealed test suite for run=$runId group=$groupId: ${e.message}", e)
        } ?: failStartup(runId, "sealed test suite not retrievable for run=$runId group=$groupId")

        val storedHash = canonicalTestSuiteHash(stored.suiteJson)
        if (stored.suiteHash != expectedHash || storedHash != expectedHash) {
            failStartup(
                runId,
                "sealed test suite hash mismatch for run=$runId group=$groupId expected=$expectedHash actual=${stored.suiteHash} canonical=$storedHash"
            )
        }
        if (expectedSuiteJson != null && stored.suiteJson != expectedSuiteJson) {
            failStartup(
                runId,
                "sealed test suite bytes changed for run=$runId group=$groupId"
            )
        }

        if (!stored.isLocked) {
            failStartup(runId, "sealed test suite for run=$runId group=$groupId was not locked after persistence")
        }

        Napier.i("EvoMoliorRunService: sealed test suite verified for run=$runId group=$groupId")
    }

    /**
     * Computes the canonical SHA-256 hash for the sealed test suite JSON.
     *
     * @param suiteJson Raw JSON emitted by the Genesis Council.
     * @return Lowercase hex SHA-256 digest.
     */
    private fun canonicalTestSuiteHash(suiteJson: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(suiteJson.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    /**
     * Materialises uploaded run-start artifacts to the run artifact directory and returns
     * their canonical metadata records.
     *
     * @param runId The current run identifier.
     * @param uploads Uploaded artifacts supplied at run start.
     * @return Canonical, file-backed artifact metadata.
     */
    private fun materializeRunInputArtifacts(
        runId: String,
        uploads: List<RunInputArtifactUpload>
    ): MaterializedRunInputArtifacts {
        if (uploads.isEmpty()) return MaterializedRunInputArtifacts(emptyList(), emptySet())

        val artifactsRoot = resolveRunArtifactsRoot(runId)
        artifactsRoot.mkdirs()

        val allowedHosts = mutableSetOf<String>()
        val artifacts = uploads.map { upload ->
            val bytes = runCatching { Base64.getDecoder().decode(upload.contentBase64) }
                .getOrElse { e -> throw IllegalArgumentException("Invalid base64 content for ${upload.relativePath}: ${e.message}", e) }

            require(upload.relativePath.isNotBlank()) {
                "Artifact path must not be blank"
            }

            val normalizedPath = java.nio.file.Path.of(upload.relativePath).normalize()
            require(!normalizedPath.isAbsolute) {
                "Artifact path must be relative to the run artifact root: ${upload.relativePath}"
            }
            val normalisedRelativePath = normalizedPath.toString()
            require(!normalisedRelativePath.startsWith("..")) {
                "Artifact path must stay within the run artifact root: ${upload.relativePath}"
            }

            val targetFile = File(artifactsRoot, normalisedRelativePath)
            targetFile.parentFile?.mkdirs()
            targetFile.writeBytes(bytes)

            if (upload.kind == RunInputArtifactKind.TEXT) {
                allowedHosts += ContextUrlAllowlistExtractor.extractHosts(bytes.toString(Charsets.UTF_8))
            }

            RunInputArtifact(
                relativePath = normalisedRelativePath,
                sourceName = upload.sourceName,
                mimeType = upload.mimeType,
                sizeBytes = bytes.size.toLong(),
                sha256 = sha256Hex(bytes),
                kind = upload.kind,
                textPreview = upload.textPreview?.takeIf { it.isNotBlank() }
                    ?.let { compactArtifactPreview(it) }
                    ?: extractTextPreview(bytes, upload.kind)
            )
        }

        return MaterializedRunInputArtifacts(
            artifacts = artifacts,
            allowedHosts = allowedHosts
        )
    }

    /** Returns the shared run-artifacts root directory for this run. */
    private fun resolveRunArtifactsRoot(runId: String): File = File(
        resolveRunRoot(runId),
        "artifacts"
    )

    private fun resolveRunRoot(runId: String): File = substrateFactory.dbFilePath(runId, "group-0")
        .parentFile?.parentFile?.parentFile
        ?: error("Unable to resolve run root for $runId")

    private fun extractTextPreview(bytes: ByteArray, kind: RunInputArtifactKind): String? = when (kind) {
        RunInputArtifactKind.TEXT -> buildTextArtifactPreview(bytes)
        RunInputArtifactKind.IMAGE -> "Image artifact (${bytes.size} bytes). Use read_run_artifact or the archive download for the raw file."
        RunInputArtifactKind.BINARY -> "Binary artifact (${bytes.size} bytes). Use read_run_artifact or the archive download for the raw file."
    }

    private fun buildTextArtifactPreview(bytes: ByteArray): String {
        val text = bytes.toString(Charsets.UTF_8)
        val snippet = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(6)
            .joinToString(" ")
            .ifBlank {
                text.replace(Regex("\\s+"), " ").trim()
            }
            .take(MAX_RUN_ARTIFACT_PREVIEW_CHARS)

        return snippet.ifBlank {
            "Text artifact (${bytes.size} bytes). Use read_run_artifact for the full file."
        }
    }

    private fun compactArtifactPreview(preview: String): String =
        preview.replace(Regex("\\s+"), " ").trim().take(MAX_RUN_ARTIFACT_PREVIEW_CHARS)

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun initializeStartupRejectionArtifact(config: RunConfig) {
        startupRejectionArtifact = StartupRejectionArtifact(
            runId = config.runId,
            phase = StartupRejectionPhase.ADMISSION,
            reasons = listOf(StartupRejectionReason.ADMISSION_REJECTED),
            errors = listOf(StartupRejectionError(
                kind = StartupRejectionErrorKind.ADMISSION,
                message = "Startup admission has not completed"
            )),
            declaredWorkerGroupCount = config.groupCount,
            declaredGraderGroupCount = config.graderGroupCountOverride ?: 0,
            sourceManifestHash = startupSourceManifestHash(config),
            distributionManifestHash = startupDistributionManifestHash(),
            activationFlags = startupActivationFlags(),
            createdAt = startupRejectionTimestamp()
        )
    }

    private fun startupRejectionArtifactForInvalidCompile(
        config: RunConfig,
        envelope: ExecutionEnvelope,
        result: StartingFieldCompileResult.Invalid
    ): StartupRejectionArtifact = StartupRejectionArtifact(
        runId = config.runId,
        phase = StartupRejectionPhase.COUNCIL_COMPILE,
        reasons = listOf(StartupRejectionReason.COUNCIL_COMPILE_FAILED),
        errors = result.errors.map { error ->
            StartupRejectionError(
                code = error.code,
                kind = StartupRejectionErrorKind.COUNCIL_COMPILE,
                path = error.path.toString(),
                message = error.message.take(StartupRejectionError.MAX_DIAGNOSTIC_CHARS),
                testId = error.hiddenTestIdOrNull(),
                definitionHash = error.hiddenTestIdOrNull()
                    ?.value
                    ?.let(result.definitionHashesByTestId::get)
            )
        },
        declaredTestCount = result.declaredTestCount,
        compiledTestCount = result.compiledTestCount,
        declaredWorkerGroupCount = envelope.requiredWorkerGroupCount.minimum,
        declaredGraderGroupCount = envelope.requiredGraderGroupCount.minimum,
        sourceManifestHash = startupSourceManifestHash(config),
        distributionManifestHash = startupDistributionManifestHash(),
        providerExecutionRefs = startupRejectionArtifact?.providerExecutionRefs.orEmpty(),
        providerExecutionCount = startupRejectionArtifact?.providerExecutionCount ?: 0,
        activationFlags = startupActivationFlags(),
        createdAt = startupRejectionTimestamp()
    )

    /**
     * Records the single logical Council invocation after the timeout block has actually begun.
     *
     * Transport retries remain an implementation detail of the Council runner; startup rejection
     * evidence names the bounded Council authority invocation, not unbounded provider attempts.
     */
    private fun recordGenesisCouncilInvocation(config: RunConfig, envelope: ExecutionEnvelope) {
        val reference = "genesis-council:${config.runId}:" +
            CouncilContractHashing.hashExecutionEnvelope(envelope)
        startupRejectionArtifact = startupRejectionArtifact?.copy(
            providerExecutionRefs = listOf(reference),
            providerExecutionCount = 1
        )
    }

    /**
     * Extracts a sealed-test identity only from the compiler's typed synthesis path.
     *
     * The compiler can emit multiple diagnostics for one declared test, so the rejection count is
     * based on distinct typed identities rather than the number of errors.
     */
    private fun com.achulkov.evomolior.core.council.FieldCompileError.hiddenTestIdOrNull(): HiddenTestId? {
        val segments = path.segments
        val sealedTestsIndex = segments.indexOf("sealedTests")
        return segments.getOrNull(sealedTestsIndex + 1)
            ?.takeIf(String::isNotBlank)
            ?.let(::HiddenTestId)
    }

    private fun startupRejectionArtifactForPreflight(
        config: RunConfig,
        startingField: StartingField,
        graderStartingField: StartingField?,
        envelope: ExecutionEnvelope?,
        declaredTestCount: Int,
        compiledTestCount: Int,
        failures: List<String>
    ): StartupRejectionArtifact = StartupRejectionArtifact(
        runId = config.runId,
        phase = StartupRejectionPhase.TEST_EXECUTABILITY_PREFLIGHT,
        reasons = listOf(StartupRejectionReason.TEST_SUITE_NOT_EXECUTABLE),
        errors = failures.map { failure ->
            StartupRejectionError(
                kind = StartupRejectionErrorKind.TEST_EXECUTABILITY,
                message = failure.take(StartupRejectionError.MAX_DIAGNOSTIC_CHARS)
            )
        },
        candidateFieldHash = sha256Hex(
            runConfigJson.encodeToString(StartingField.serializer(), startingField).toByteArray()
        ),
        testSuiteHash = graderStartingField?.sealedTestSuiteHash
            ?.takeIf { it.isNotBlank() }
            ?: startingField.sealedTestSuiteHash.takeIf { it.isNotBlank() },
        declaredTestCount = declaredTestCount,
        compiledTestCount = compiledTestCount,
        declaredWorkerGroupCount = envelope?.requiredWorkerGroupCount?.minimum ?: config.groupCount,
        compiledWorkerGroupCount = startingField.groupCount,
        declaredGraderGroupCount =
            envelope?.requiredGraderGroupCount?.minimum ?: (config.graderGroupCountOverride ?: 0),
        compiledGraderGroupCount = graderStartingField?.groupCount ?: startingField.graderGroupCount,
        sourceManifestHash = startupSourceManifestHash(config),
        distributionManifestHash = startupDistributionManifestHash(),
        providerExecutionRefs = startupRejectionArtifact?.providerExecutionRefs.orEmpty(),
        providerExecutionCount = startupRejectionArtifact?.providerExecutionCount ?: 0,
        activationFlags = startupActivationFlags(),
        createdAt = startupRejectionTimestamp()
    )

    private fun startupSourceManifestHash(config: RunConfig): String = sha256Hex(
        buildString {
            append(config.runId).append('\n')
            append(config.objective).append('\n')
            config.contextArtifacts.forEach { artifact ->
                append(artifact.relativePath).append('|')
                append(artifact.sourceName).append('|')
                append(artifact.kind).append('|')
                append(artifact.mimeType).append('|')
                append(sha256Hex(artifact.contentBase64.toByteArray())).append('\n')
            }
        }.toByteArray()
    )

    private fun startupDistributionManifestHash(): String {
        distributionManifestHashProvider?.invoke()?.let { hash ->
            require(hash.matches(Regex("[0-9a-f]{64}"))) {
                "Distribution manifest hash provider returned an invalid SHA-256 commitment"
            }
            return hash
        }
        System.getenv("EVOMOLIOR_ACCEPTANCE_MANIFEST")
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?.takeIf(File::isFile)
            ?.let { return sha256Hex(it.readBytes()) }
        val codeSource = runCatching {
            File(EvoMoliorRunService::class.java.protectionDomain.codeSource.location.toURI())
        }.getOrNull()
        val agentArtifact = File(resolveAgentJarPath())
        return sha256Hex(
            buildString {
                append("engine=")
                append(codeSource?.takeIf(File::exists)?.let(::sha256FileOrDirectory) ?: "unavailable")
                append('\n')
                append("agent=")
                append(agentArtifact.takeIf { it.isFile }?.let(::sha256FileOrDirectory) ?: "unavailable")
            }.toByteArray()
        )
    }

    private fun sha256FileOrDirectory(file: File): String =
        if (file.isFile) {
            sha256Hex(file.readBytes())
        } else {
            sha256Hex(
                file.walkTopDown()
                    .filter { it.isFile }
                    .sortedBy { it.relativeTo(file).invariantSeparatorsPath }
                    .joinToString("\n") { child ->
                        "${child.relativeTo(file).invariantSeparatorsPath}:" +
                            sha256Hex(child.readBytes())
                    }
                    .toByteArray()
            )
        }

    private fun startupActivationFlags(): StartupActivationFlags = StartupActivationFlags(
        runStartedEmitted = startupRunStartedEmitted,
        workerInfrastructureActivated = startupWorkerInfrastructureActivated,
        graderInfrastructureActivated = startupGraderInfrastructureActivated,
        successArchiveCreated = false,
        winnerAuthorityCreated = false
    )

    private suspend fun rejectAdmissionBeforeOwnership(
        config: RunConfig,
        reason: String
    ): Nothing {
        val artifact = StartupRejectionArtifact(
            runId = config.runId,
            phase = StartupRejectionPhase.ADMISSION,
            reasons = listOf(StartupRejectionReason.ADMISSION_REJECTED),
            errors = listOf(
                StartupRejectionError(
                    kind = StartupRejectionErrorKind.ADMISSION,
                    message = reason.take(StartupRejectionError.MAX_DIAGNOSTIC_CHARS)
                )
            ),
            declaredWorkerGroupCount = config.groupCount,
            declaredGraderGroupCount = config.graderGroupCountOverride ?: 0,
            sourceManifestHash = startupSourceManifestHash(config),
            distributionManifestHash = startupDistributionManifestHash(),
            activationFlags = StartupActivationFlags(
                runStartedEmitted = false,
                workerInfrastructureActivated = false,
                graderInfrastructureActivated = false,
                successArchiveCreated = false,
                winnerAuthorityCreated = false
            ),
            createdAt = startupRejectionTimestamp()
        )
        startupRejectionStore.persist(artifact)
        publishStartupDiagnosticOutcome(artifact)
        throw IllegalStateException(reason)
    }

    /**
     * Aborts startup after a sealed-suite invariant failure and clears any partial state.
     *
     * @param runId The active run identifier.
     * @param reason Human-readable failure reason.
     * @param cause Optional underlying exception.
     * @throws IllegalStateException always.
     */
    private suspend fun failStartup(
        runId: String,
        reason: String,
        cause: Throwable? = null,
        rejection: StartupRejectionArtifact? = null
    ): Nothing {
        val message = "EvoMoliorRunService: startup aborted for run=$runId - $reason"
        if (cause == null) Napier.e(message) else Napier.e(message, cause)
        val baseArtifact = startupRejectionArtifact
        val artifact = rejection?.copy(
            activationFlags = startupActivationFlags()
        ) ?: baseArtifact?.copy(
            phase = baseArtifact.phase,
            reasons = listOf(
                when (baseArtifact.phase) {
                    StartupRejectionPhase.ADMISSION ->
                        StartupRejectionReason.ADMISSION_REJECTED
                    StartupRejectionPhase.COUNCIL_COMPILE ->
                        baseArtifact.reasons.firstOrNull()
                            ?: StartupRejectionReason.COUNCIL_COMPILE_FAILED
                    StartupRejectionPhase.TEST_EXECUTABILITY_PREFLIGHT ->
                        StartupRejectionReason.TEST_SUITE_NOT_EXECUTABLE
                    StartupRejectionPhase.PRE_ACTIVATION ->
                        StartupRejectionReason.PRE_ACTIVATION_INVARIANT_FAILED
                }
            ),
            errors = listOf(
                StartupRejectionError(
                    kind = when (baseArtifact.phase) {
                        StartupRejectionPhase.ADMISSION ->
                            StartupRejectionErrorKind.ADMISSION
                        StartupRejectionPhase.COUNCIL_COMPILE ->
                            StartupRejectionErrorKind.COUNCIL_COMPILE
                        StartupRejectionPhase.TEST_EXECUTABILITY_PREFLIGHT ->
                            StartupRejectionErrorKind.TEST_EXECUTABILITY
                        StartupRejectionPhase.PRE_ACTIVATION ->
                            StartupRejectionErrorKind.PRE_ACTIVATION
                    },
                    message = reason.take(StartupRejectionError.MAX_DIAGNOSTIC_CHARS)
                )
            ),
            activationFlags = startupActivationFlags()
        )
        val persistenceError = artifact?.let {
            runCatching { startupRejectionStore.persist(it) }.exceptionOrNull()
        }
        persistenceError?.let {
            Napier.e("EvoMoliorRunService: failed to persist startup rejection for run=$runId", it)
        }
        val diagnosticArchiveError = artifact
            ?.takeIf { persistenceError == null }
            ?.let {
                runCatching { publishStartupDiagnosticOutcome(it) }.exceptionOrNull()
            }
        diagnosticArchiveError?.let {
            Napier.e(
                "EvoMoliorRunService: failed to publish startup diagnostic for run=$runId",
                it
            )
        }
        startupRejectionArtifact = null
        val statePersistenceError = runCatching {
            updatePersistedRunStatus(runId, PersistedRunStatus.FAILED)
        }.exceptionOrNull()
        statePersistenceError?.let {
            Napier.e("EvoMoliorRunService: failed to persist startup failure state for run=$runId", it)
        }
        cleanup(runId)
        emit(EvoMoliorEvent.RunAborted(runId, reason))
        throw IllegalStateException(reason, cause).also { failure ->
            persistenceError?.let(failure::addSuppressed)
            diagnosticArchiveError?.let(failure::addSuppressed)
            statePersistenceError?.let(failure::addSuppressed)
        }
    }

    private suspend fun publishStartupDiagnosticOutcome(
        rejection: StartupRejectionArtifact
    ) {
        val durableArchive = archiveService ?: RunArchiveService(substrateFactory)
        durableArchive.readStartupDiagnosticArchive(rejection.runId)?.let { bytes ->
            reconcileStartupOutcomeHistoryFromArchive(rejection.runId, bytes)
            return
        }
        val repository = PublicationOutcomeRepository(
            substrateFactory.getAtlas(rejection.runId)
        )
        val rejectionHash = AtlasHashing.hashText(
            runConfigJson.encodeToString(
                StartupRejectionArtifact.serializer(),
                rejection
            )
        )
        val first = advanceStartupDiagnosticState(
            rejection.runId,
            StartupDiagnosticState.ADMISSION_OR_COUNCIL_FAILURE,
            setOf("startup-rejection:$rejectionHash")
        )
        advanceStartupDiagnosticState(
            rejection.runId,
            StartupDiagnosticState.REJECTION_AUTHORITY_FROZEN,
            setOf("frozen-startup-rejection:$rejectionHash")
        )
        val compilation = StartupDiagnosticOutcomeCompiler.compile(
            rejection = rejection,
            generatedAt = first.occurredAt
        )
        val deliverables = DeliverablePersistenceRepository(
            substrateFactory.getAtlas(rejection.runId)
        )
        val blueprint = deliverables.appendBlueprint(compilation.blueprint)
        val decisionPackage = deliverables.appendDecisionPackage(
            compilation.decisionPackage
        )
        advanceStartupDiagnosticState(
            rejection.runId,
            StartupDiagnosticState.STARTUP_DIAGNOSTIC_ASSEMBLED,
            setOf(
                "startup-diagnostic:${compilation.diagnosticPackage.packageHash}",
                "decision-package:${decisionPackage.packageHash}"
            )
        )
        val manifestInputsHash = AtlasHashing.hashText(
            listOf(
                rejection.runId,
                blueprint.blueprintHash,
                decisionPackage.packageHash,
                compilation.diagnosticPackage.packageHash,
                rejectionHash
            ).joinToString("|")
        )
        advanceStartupDiagnosticState(
            rejection.runId,
            StartupDiagnosticState.DIAGNOSTIC_MANIFEST_HASHED,
            setOf("startup-manifest-inputs:$manifestInputsHash")
        )
        val persisted = repository.startupHistory(rejection.runId)
        val archived = nextStartupDiagnosticEvent(
            runId = rejection.runId,
            state = StartupDiagnosticState.ARCHIVED_REJECTED_PRE_RUNTIME,
            previous = requireNotNull(persisted.lastOrNull()),
            authorityReferenceIds = setOf(
                "startup-archive:startup-diagnostic.zip",
                "startup-diagnostic:${compilation.diagnosticPackage.packageHash}"
            )
        )
        val completeHistory = persisted + archived
        val lifecycleHash = AtlasHashing.hashText(
            "startup-no-runtime|${rejection.runId}|$rejectionHash|" +
                "${rejection.activationFlags.workerInfrastructureActivated}|" +
                rejection.activationFlags.graderInfrastructureActivated
        )
        val mechanismActivationReport = startupMechanismActivationReport(
            runId = rejection.runId,
            generatedAt = archived.occurredAt,
            rejectionAuthorityId = "startup-rejection:$rejectionHash",
            terminalPublicationAuthorityId =
                "startup-archive-event/${archived.eventHash}",
        )
        val archive = RunOutcomeArchiveService().archiveStartupDiagnostic(
            StartupDiagnosticArchiveRequest(
                runId = rejection.runId,
                admissionDistributionHash =
                    requireNotNull(rejection.distributionManifestHash),
                startingFieldHash = rejection.candidateFieldHash,
                lifecycleReconciliationHash = lifecycleHash,
                blueprint = blueprint,
                decisionPackage = decisionPackage,
                diagnosticPackage = compilation.diagnosticPackage,
                publicationHistory = completeHistory,
                mechanismActivationReport = mechanismActivationReport,
                archivedAtMs = archived.occurredAt.toEpochMilliseconds()
            )
        )
        durableArchive.writeOutcomeArchive(rejection.runId, archive)
        repository.appendStartup(archived)
        require(repository.startupHistory(rejection.runId) == completeHistory) {
            "Startup diagnostic history readback differs from archived authority"
        }
    }

    private suspend fun advanceStartupDiagnosticState(
        runId: String,
        state: StartupDiagnosticState,
        authorityReferenceIds: Set<String>
    ): StartupDiagnosticStateEvent {
        val repository = PublicationOutcomeRepository(substrateFactory.getAtlas(runId))
        val history = repository.startupHistory(runId)
        history.singleOrNull { it.state == state }?.let { existing ->
            require(existing.authorityReferenceIds == authorityReferenceIds) {
                "Startup diagnostic state ${state.name} conflicts with persisted authority"
            }
            return existing
        }
        return repository.appendStartup(
            nextStartupDiagnosticEvent(
                runId = runId,
                state = state,
                previous = history.lastOrNull(),
                authorityReferenceIds = authorityReferenceIds
            )
        )
    }

    private fun nextStartupDiagnosticEvent(
        runId: String,
        state: StartupDiagnosticState,
        previous: StartupDiagnosticStateEvent?,
        authorityReferenceIds: Set<String>
    ): StartupDiagnosticStateEvent = StartupDiagnosticStateEvent(
        eventId = CouncilDeterministicIds.create(
            "startup-diagnostic-state",
            runId,
            state.name
        ),
        runId = runId,
        state = state,
        sequence = (previous?.sequence ?: -1) + 1,
        previousEventHash = previous?.eventHash,
        authorityReferenceIds = authorityReferenceIds,
        occurredAt = Clock.System.now()
    ).withComputedHash()

    private suspend fun reconcileStartupOutcomeHistoryFromArchive(
        runId: String,
        archiveBytes: ByteArray
    ) {
        val presentation = RunOutcomeBundleInspector.inspect(archiveBytes)
        require(
            presentation.runId == runId &&
                presentation.outcomeClass ==
                com.achulkov.evomolior.core.deliverable.RunOutcomeClass
                    .STARTUP_DIAGNOSTIC_PACKAGE
        ) { "Startup diagnostic archive projection is invalid or foreign" }
        val archivedHistory =
            RunOutcomeArchiveService().startupPublicationHistory(archiveBytes)
        val repository = PublicationOutcomeRepository(
            substrateFactory.getAtlas(runId)
        )
        val persisted = repository.startupHistory(runId)
        require(archivedHistory.take(persisted.size) == persisted) {
            "Startup diagnostic archive history conflicts with durable state"
        }
        archivedHistory.drop(persisted.size).forEach {
            repository.appendStartup(it)
        }
        require(repository.startupHistory(runId) == archivedHistory) {
            "Startup diagnostic history readback differs from archived authority"
        }
    }

    private suspend fun reconcileAcceptedOutcomeHistoryFromArchive(
        runId: String,
        archiveBytes: ByteArray
    ) {
        val presentation = RunOutcomeBundleInspector.inspect(archiveBytes)
        require(
            presentation.runId == runId &&
                presentation.outcomeClass ==
                com.achulkov.evomolior.core.deliverable.RunOutcomeClass
                    .ACCEPTED_DECISION_PACKAGE
        ) { "Accepted archive projection is invalid or foreign" }
        val decisionPackage = requireNotNull(presentation.decisionPackage) {
            "Accepted archive lacks its receiver decision package"
        }
        val metrics = requireNotNull(presentation.metrics) {
            "Accepted archive lacks its canonical run metrics"
        }
        DeliverablePersistenceRepository(
            substrateFactory.getAtlas(runId)
        ).appendDecisionPackage(decisionPackage)
        RunMetricsV1ArtifactStore(substrateFactory).persist(metrics)
        val archivedHistory =
            RunOutcomeArchiveService().acceptedPublicationHistory(archiveBytes)
        if (archivedHistory.isEmpty()) return
        val publicationRepository = PublicationStateRepository(
            substrateFactory.getAtlas(runId)
        )
        val outcomeRepository = PublicationOutcomeRepository(
            substrateFactory.getAtlas(runId)
        )
        val publicationHistory = publicationRepository.history(runId)
        val outcomeHistory = outcomeRepository.acceptedHistory(runId)
        require(
            archivedHistory.take(publicationHistory.size) == publicationHistory &&
                archivedHistory.take(outcomeHistory.size) == outcomeHistory
        ) { "Accepted archive history conflicts with durable publication state" }
        archivedHistory.drop(publicationHistory.size).forEach {
            publicationRepository.append(it)
        }
        archivedHistory.drop(outcomeHistory.size).forEach {
            outcomeRepository.appendAccepted(it)
        }
    }

    /**
     * Seeds each group's substrate with primordial data from the [StartingField].
     *
     * Inserts:
     * - KB documents from [StartingField.primordialKb]
     * - KG facts from [StartingField.primordialKgFacts]
     * - WIP tasks parsed from [StartingField.nodeGraph] (JSON array of
     *   `{"nodeId", "description"}` objects; silently skips malformed nodes)
     *
     * Called once per run after the Genesis Council completes, before the first round spawn.
     */
    private suspend fun seedPrimordialData(
        runId: String,
        groupIds: List<String>,
        workerStartingField: StartingField,
        graderGroupIds: List<String>,
        graderStartingField: StartingField?
    ) {
        val workerCounts = seedPrimordialDataForGroups(
            runId = runId,
            groupIds = groupIds,
            startingField = workerStartingField,
            seedOverlayBuilder = { groupId, index ->
                buildGroupStrategySeedDocuments(
                    runId = runId,
                    groupId = groupId,
                    groupIndex = index,
                    startingField = workerStartingField
                )
            }
        )
        val graderCounts = graderStartingField?.let { seed ->
            seedPrimordialDataForGroups(
                runId = runId,
                groupIds = graderGroupIds,
                startingField = seed,
                seedOverlayBuilder = { groupId, index ->
                    buildGraderOverlaySeedDocuments(
                        runId = runId,
                        groupId = groupId,
                        workerStartingField = workerStartingField,
                        graderStartingField = seed,
                        profileType = seed.groupProfiles[groupId]
                            ?: GroupProfileType.entries[index % GroupProfileType.entries.size]
                    )
                }
            )
        } ?: SeedInsertCounts()

        Napier.i(
            "EvoMoliorRunService: primordial seeding complete — " +
                "KB=${workerCounts.kb + graderCounts.kb} KG=${workerCounts.kg + graderCounts.kg} " +
                "WIP=${workerCounts.wip + graderCounts.wip} tools=${workerCounts.tools + graderCounts.tools} " +
                "(across ${groupIds.size} competing + ${graderGroupIds.size} grader groups)"
        )
    }

    private suspend fun seedPrimordialDataForGroups(
        runId: String,
        groupIds: List<String>,
        startingField: StartingField,
        seedOverlayBuilder: (groupId: String, index: Int) -> List<KnowledgeBaseEntryEntity>
    ): SeedInsertCounts {
        if (groupIds.isEmpty()) return SeedInsertCounts()
        val now = System.currentTimeMillis()
        var kbCount = 0
        var kgCount = 0
        var wipCount = 0
        var toolCount = 0

        for ((index, groupId) in groupIds.withIndex()) {
            try {
                val db = substrateFactory.get(runId, groupId)
                val deliverableTree = deliverableSkeletonTreeStore.initialise(runId, groupId, startingField)

                if (startingField.primordialKb.isNotEmpty()) {
                    val kbEntities = startingField.primordialKb.map { seed ->
                        KnowledgeBaseEntryEntity(
                            id = "${seed.id}-$groupId",
                            content = seed.content,
                            domain = seed.domain,
                            sourceAgentId = "genesis-council",
                            generation = 0,
                            trustScore = 0.9,
                            status = "active",
                            groupId = groupId,
                            runId = runId,
                            createdAtMs = now
                        )
                    }
                    db.knowledgeBaseDao().insertAll(kbEntities)
                    kbCount += kbEntities.size
                }

                val overlayEntities = seedOverlayBuilder(groupId, index)
                if (overlayEntities.isNotEmpty()) {
                    db.knowledgeBaseDao().insertAll(overlayEntities)
                    kbCount += overlayEntities.size
                }

                val uploadedDocs = startingField.inputArtifacts.filter { it.kind == RunInputArtifactKind.TEXT }
                if (uploadedDocs.isNotEmpty()) {
                    val uploadedKbEntities = uploadedDocs.map { artifact ->
                        KnowledgeBaseEntryEntity(
                            id = "uploaded-${artifact.sha256.take(12)}-$groupId",
                            content = buildString {
                                appendLine("Uploaded context artifact: ${artifact.sourceName}")
                                appendLine("Path: ${artifact.relativePath}")
                                appendLine("MIME: ${artifact.mimeType}")
                                appendLine("SHA-256: ${artifact.sha256}")
                                appendLine()
                                appendLine(artifact.textPreview ?: "(no text preview)")
                            },
                            domain = "uploaded_context",
                            sourceAgentId = "user-upload",
                            generation = 0,
                            trustScore = 1.0,
                            status = "active",
                            groupId = groupId,
                            runId = runId,
                            createdAtMs = now
                        )
                    }
                    db.knowledgeBaseDao().insertAll(uploadedKbEntities)
                    kbCount += uploadedKbEntities.size
                }

                if (startingField.primordialKgFacts.isNotEmpty()) {
                    val kgEntities = startingField.primordialKgFacts.map { seed ->
                        com.achulkov.evomolior.substrate.db.entity.KnowledgeGraphFactEntity(
                            id = "${seed.id}-$groupId",
                            subject = seed.subject,
                            relation = seed.relation,
                            objectNode = seed.`object`,
                            confidence = seed.confidence,
                            sourceAgentId = "genesis-council",
                            generation = 0,
                            trustScore = 0.9,
                            status = "active",
                            groupId = groupId,
                            runId = runId,
                            createdAtMs = now
                        )
                    }
                    db.knowledgeGraphFactDao().insertAll(kgEntities)
                    kgCount += kgEntities.size
                }

                val wipNodes = deliverableTree.nodes.filter { node ->
                    node.nodeId != deliverableTree.rootNodeId ||
                        node.nodeId.startsWith("node_")
                }
                if (wipNodes.isNotEmpty()) {
                    val wipEntities = wipNodes.map { node -> initialWipTask(runId, groupId, node) }
                    db.wipTaskDao().insertAll(wipEntities)
                    wipCount += wipEntities.size
                }

                if (startingField.primordialTools.isNotEmpty()) {
                    val toolEntities = startingField.primordialTools.map { seed ->
                        com.achulkov.evomolior.substrate.db.entity.ToolRegistryEntryEntity(
                            id = "${seed.id}-$groupId",
                            name = seed.name,
                            version = 1,
                            authorAgentId = "genesis-council",
                            successRate = 1.0,
                            inputSchemaJson = seed.inputSchemaJson,
                            outputSchemaJson = seed.outputSchemaJson,
                            domain = seed.domain,
                            status = "archived",
                            code = seed.code,
                            codeLanguage = "shell",
                            generation = 0,
                            groupId = groupId,
                            runId = runId,
                            createdAtMs = now
                        )
                    }
                    for (seed in toolEntities) {
                        db.toolRegistryDao().insert(seed)
                    }
                    toolCount += toolEntities.size
                }
            } catch (e: Exception) {
                Napier.w("EvoMoliorRunService: primordial seed failed for group=$groupId: ${e.message}")
            }
        }

        return SeedInsertCounts(kb = kbCount, kg = kgCount, wip = wipCount, tools = toolCount)
    }

    private fun buildGraderOverlaySeedDocuments(
        runId: String,
        groupId: String,
        workerStartingField: StartingField,
        graderStartingField: StartingField,
        profileType: GroupProfileType
    ): List<KnowledgeBaseEntryEntity> {
        val now = System.currentTimeMillis()
        val profileLens = when (profileType) {
            GroupProfileType.Library -> "Emphasise evidence density, source traceability, and coverage breadth."
            GroupProfileType.Factory -> "Emphasise reproducibility, operational robustness, and concrete deliverables."
            GroupProfileType.Commune -> "Emphasise clarity, accessibility, and coherence across artefacts."
            GroupProfileType.Monastery -> "Emphasise rigor, internal consistency, and disciplined scoring."
            GroupProfileType.Mercenary -> "Emphasise competitive differentiation, decisive tradeoffs, and exposed weaknesses."
        }
        return listOf(
            KnowledgeBaseEntryEntity(
                id = "grader-overlay-mission-$groupId",
                content = """
                    You are $groupId, a grader civilisation created by a separate Genesis Council run.
                    Worker objective: ${workerStartingField.objective}
                    Your task: generate deep, diverse, adversarial criteria that expose shallow work.
                    ${graderStartingField.norms}
                """.trimIndent(),
                domain = "grader_seed",
                sourceAgentId = "grader-genesis-council",
                generation = 0,
                trustScore = 1.0,
                status = "active",
                groupId = groupId,
                runId = runId,
                createdAtMs = now
            ),
            KnowledgeBaseEntryEntity(
                id = "grader-overlay-lens-$groupId",
                content = "Evaluation lens for $groupId (${profileType.name}): $profileLens",
                domain = "grader_seed",
                sourceAgentId = "grader-genesis-council",
                generation = 0,
                trustScore = 1.0,
                status = "active",
                groupId = groupId,
                runId = runId,
                createdAtMs = now
            )
        )
    }

    private fun buildGroupStrategySeedDocuments(
        runId: String,
        groupId: String,
        groupIndex: Int,
        startingField: StartingField
    ): List<KnowledgeBaseEntryEntity> {
        val strategy = groupStrategyHypotheses[groupIndex % groupStrategyHypotheses.size]
        val profile = startingField.groupProfiles[groupId]
        val now = System.currentTimeMillis()
        return listOf(
            KnowledgeBaseEntryEntity(
                id = "group-strategy-${groupId.replace(Regex("[^A-Za-z0-9_-]"), "_")}",
                content = buildString {
                    appendLine("GROUP STRATEGY HYPOTHESIS")
                    appendLine("Group: $groupId")
                    appendLine("Strategy: ${strategy.title}")
                    appendLine("Hypothesis: ${strategy.hypothesis}")
                    appendLine("Expected differentiator: ${strategy.differentiator}")
                    if (profile != null) appendLine("Assigned profile: $profile")
                    appendLine()
                    appendLine("Round summaries must compare actual artifacts against this strategy.")
                    appendLine("If the group converges with another strategy, explain why convergence was justified.")
                    appendLine("Run objective: ${startingField.objective.take(500)}")
                },
                domain = "group_strategy",
                sourceAgentId = "genesis-council",
                generation = 0,
                trustScore = 0.95,
                status = "active",
                groupId = groupId,
                runId = runId,
                createdAtMs = now
            )
        )
    }

    private data class GroupStrategyHypothesis(
        val title: String,
        val hypothesis: String,
        val differentiator: String
    )

    private val groupStrategyHypotheses = listOf(
        GroupStrategyHypothesis(
            title = "Evidence-first compounding",
            hypothesis = "The best output will come from preserving concrete evidence and provenance before drafting.",
            differentiator = "highest citation density, clearest evidence trace, and strongest KB/KG grounding"
        ),
        GroupStrategyHypothesis(
            title = "Counterargument-first critique",
            hypothesis = "The best output will come from aggressively finding risks, weak claims, and missing comparisons before synthesis.",
            differentiator = "best handling of caveats, contradictions, and rejected alternatives"
        ),
        GroupStrategyHypothesis(
            title = "Tooling-first validation",
            hypothesis = "The best output will come from using or proposing repeatable tools for extraction, validation, or artifact inspection.",
            differentiator = "strongest reusable tool rationale, tool proposal, or tool execution evidence"
        ),
        GroupStrategyHypothesis(
            title = "User-value synthesis",
            hypothesis = "The best output will come from early synthesis into operator-readable structure, then refining gaps.",
            differentiator = "clearest final narrative, decision usefulness, and actionable structure"
        )
    )

    private data class SeedInsertCounts(
        val kb: Int = 0,
        val kg: Int = 0,
        val wip: Int = 0,
        val tools: Int = 0
    )

    /**
     * Runs post-mortem fitness scoring and Legacy Protocol extraction for all groups
     * after the CriticPipeline has processed each round's submissions.
     *
     * Per spec §13 (Epigenetic Inheritance):
     * - Top 20% of agents by fitness score are eligible for Legacy Protocol extraction.
     * - One protocol per role; merged via CrossoverEngine if multiple top agents share a role.
     * - Protocol expires after 2 generations; failure map is promoted to Dead Ends Registry on expiry.
     */
    private suspend fun runFitnessAndEpigenetics(
        config: RunConfig,
        groupIds: List<String>,
        round: Int
    ): Map<String, EpigeneticRoundSummary> = supervisorScope {
        groupIds.map { groupId ->
            async {
                try {
                    val db = substrateFactory.get(config.runId, groupId)
                    val causalDeriver = CausalFitnessDeriver(
                        database = db,
                        graphRepository = ContributionGraphRepository(
                            database = db,
                            atlasDatabase = substrateFactory.getAtlas(config.runId)
                        ),
                        fitnessRepository = CausalFitnessRepository(db),
                        toolInfrastructureRepository = ToolInfrastructureRepository(db)
                    )
                    val toolMetrics = ToolInfrastructureRepository(db).metrics(
                        config.runId,
                        AtlasGroupId(groupId)
                    )
                    db.eventLogDao().insert(
                        EventLogEntryEntity(
                            id = "tool-metrics:${config.runId}:$groupId:$round",
                            timestampMs = System.currentTimeMillis(),
                            eventType = EventTypes.TOOL_INFRASTRUCTURE_METRICS,
                            groupId = groupId,
                            runId = config.runId,
                            generation = round,
                            payloadJson = runConfigJson.encodeToString(
                                com.achulkov.evomolior.core.atlas.ToolInfrastructureMetrics.serializer(),
                                toolMetrics
                            )
                        )
                    )
                    val scores = causalDeriver.deriveGeneration(config.runId, groupId, round)
                    if (scores.isEmpty()) {
                        return@async groupId to EpigeneticRoundSummary(0, 0, 0, 0, 0)
                    }

                    val topScores = causalDeriver.topPercentileByPlane(scores, 0.20)
                    val topAgentIds = topScores.map { it.agentId }.toSet()

                    Napier.i("EvoMoliorRunService: fitness scored ${scores.size} agents for group=$groupId round=$round; top ${topAgentIds.size} eligible for legacy protocol")
                    emit(EvoMoliorEvent.EngineProgress(config.runId, "fitness",
                        "[$groupId] Fitness scored ${scores.size} agents (round $round) — top ${topAgentIds.size} extracting legacy protocol"))

                    val extractor = LegacyProtocolExtractor(
                        legacyProtocolDao = db.legacyProtocolDao(),
                        stagingDao = db.stagingDao(),
                        eventLogDao = db.eventLogDao()
                    )
                    val protocols = extractor.extractCausal(
                        fitnessRecords = topScores,
                        currentGeneration = round,
                        runId = config.runId,
                        groupId = groupId
                    )
                    if (protocols.isNotEmpty()) {
                        Napier.i("EvoMoliorRunService: extracted ${protocols.size} legacy protocol(s) for group=$groupId round=$round")
                        emit(EvoMoliorEvent.EngineProgress(config.runId, "legacy_protocol",
                            "[$groupId] ${protocols.size} legacy protocol(s) extracted for round $round"))
                    }

                    val expiryResult = sweepExpiredLegacyProtocols(db, config.runId, groupId, round)
                    if (expiryResult.expiredProtocols > 0) {
                        Napier.i("EvoMoliorRunService: expired ${expiryResult.expiredProtocols} legacy protocol(s), inserted ${expiryResult.deadEndsInserted} Dead End(s) for group=$groupId round=$round")
                        emit(EvoMoliorEvent.EngineProgress(config.runId, "legacy_expiry",
                            "[$groupId] ${expiryResult.expiredProtocols} protocol(s) expired, ${expiryResult.deadEndsInserted} failure map(s) promoted to Dead Ends"))
                    }

                    groupId to EpigeneticRoundSummary(
                        fitnessScoredAgents = scores.size,
                        fitnessTopEligibleAgents = topAgentIds.size,
                        legacyProtocolsExtracted = protocols.size,
                        legacyProtocolsExpired = expiryResult.expiredProtocols,
                        deadEndsInserted = expiryResult.deadEndsInserted
                    )
                } catch (e: Exception) {
                    Napier.w("EvoMoliorRunService: fitness/epigenetics failed for group=$groupId round=$round: ${e.message}")
                    groupId to EpigeneticRoundSummary(0, 0, 0, 0, 0)
                }
            }
        }.awaitAll().toMap()
    }

    /**
     * Promotes expired Legacy Protocol failure maps into the Dead Ends KB channel.
     *
     * Kept as a dedicated lifecycle step so protocol expiry can be verified independently
     * from the fitness scoring and extraction pass.
     */
    internal suspend fun sweepExpiredLegacyProtocols(
        db: EvoMoliorDatabase,
        runId: String,
        groupId: String,
        round: Int
    ): LegacyExpirySweepResult {
        val expiredProtocols = runCatching {
            db.legacyProtocolDao().getExpired(runId, groupId, round)
        }.getOrElse { emptyList() }

        var deadEndsInserted = 0
        for (protocol in expiredProtocols) {
            if (protocol.failureMapJson.isBlank() || protocol.failureMapJson == "{}") continue

            runCatching {
                db.knowledgeBaseDao().insert(
                    KnowledgeBaseEntryEntity(
                        id = "dead-end-${protocol.id}",
                        content = "DEAD END [Role: ${protocol.role}, Gen: ${protocol.extractedFromGeneration}]: " +
                            protocol.failureMapJson,
                        domain = "dead_ends",
                        sourceAgentId = "epigenetic_archive",
                        generation = round,
                        trustScore = 1.0,
                        isDeadEnd = true,
                        status = "active",
                        groupId = groupId,
                        runId = runId,
                        createdAtMs = System.currentTimeMillis()
                    )
                )
                deadEndsInserted++
            }.onFailure { e ->
                Napier.w("EvoMoliorRunService: failed to insert Dead End from protocol ${protocol.id}: ${e.message}")
            }
        }

        return LegacyExpirySweepResult(
            expiredProtocols = expiredProtocols.size,
            deadEndsInserted = deadEndsInserted
        )
    }

    /**
     * Phase 7.5: Invokes the Final Synthesiser for each group to produce a coherent
     * deliverable from the deliverable skeleton tree.
     *
     * The output is written to each group's control directory under `deliverable/`.
     *
     * @param config Current run configuration.
     * @param groupIds Competing group IDs.
     */
    private suspend fun composeStageDisclosureWithDiscriminatorFeedback(
        runId: String,
        groupId: String,
        baseDisclosure: String
    ): String = if (activeWorkerAtlasId != null) {
        baseDisclosure
    } else buildString {
        val discriminatorBrief = adversarialDiscriminatorBriefs[groupId]
            ?.takeIf { it.isNotBlank() }
            ?: readAdversarialDiscriminatorBrief(runId, groupId)
        if (discriminatorBrief != null) {
            appendLine("=== ADVERSARIAL DISCRIMINATOR FEEDBACK ===")
            appendLine(discriminatorBrief)
            appendLine()
        }
        val graderFeedback = graderFeedbackBriefs[groupId]
            ?.takeIf { it.isNotBlank() }
            ?: readGraderFeedbackBrief(runId, groupId)
        if (discriminatorBrief != null || graderFeedback != null) {
            appendLine("=== ROUND ADAPTATION CONTRACT ===")
            appendLine("Before continuing, evaluate the prior-round feedback like a compact Genesis Council rerun:")
            appendLine("- identify which graded weaknesses or discriminator objections change your plan;")
            appendLine("- adjust your prompt interpretation, task choice, and tool sequence accordingly;")
            appendLine("- if a task or deliverable appears complete, find a concrete improvement, expansion, validation, or robustness check instead of stopping;")
            appendLine("- persist the adjustment as a PLAN_UPDATE, KG fact, constraint, or improved artifact.")
            appendLine()
        }
        if (graderFeedback != null) {
            appendLine("=== GRADER GROUP FEEDBACK ===")
            appendLine(graderFeedback)
            appendLine()
        }
        if (baseDisclosure.isNotBlank()) {
            appendLine(baseDisclosure)
        }
    }

    /**
     * Reconstructs only deterministic node projections that pre-Atlas persisted runs may lack.
     *
     * Existing trees and WIP lifecycle rows are never replaced. A present but unreadable tree
     * fails closed because rebuilding it would discard historical ownership and grading state.
     */
    private suspend fun ensureLegacyNodeProjectionSeedsForResume(
        runId: String,
        startingField: StartingField,
        groupIds: List<String>
    ) {
        groupIds.forEach { groupId ->
            val treeFile = deliverableSkeletonTreeStore.resolveFile(runId, groupId)
            val existingTree = deliverableSkeletonTreeStore.read(runId, groupId)
            check(existingTree != null || !treeFile.exists()) {
                "Persisted deliverable tree is unreadable for $runId/$groupId"
            }
            val tree = existingTree
                ?: deliverableSkeletonTreeStore.initialise(runId, groupId, startingField)
            val wipDao = substrateFactory.get(runId, groupId).wipTaskDao()
            tree.nodes
                .asSequence()
                .filterNot { node -> node.nodeId == tree.rootNodeId }
                .map { node -> initialWipTask(runId, groupId, node) }
                .forEach { task -> wipDao.insertIfAbsent(task) }
        }
    }

    private fun initialWipTask(
        runId: String,
        groupId: String,
        node: DeliverableSkeletonNode
    ): WipTaskEntity {
        val description = listOfNotNull(node.title.takeIf { it.isNotBlank() }, node.pathHint)
            .joinToString(" - ")
            .ifBlank { node.nodeId }
        return WipTaskEntity(
            nodeId = node.nodeId,
            runId = runId,
            groupId = groupId,
            description = description,
            status = "unclaimed",
            lastChangedGeneration = 0,
            linkedNodeId = node.nodeId,
            dstNodeRef = node.nodeId
        )
    }

    private suspend fun updateGraderFeedbackBriefs(
        runId: String,
        roundIndex: Int,
        gradingResult: com.achulkov.evomolior.core.model.GradingResult
    ) {
        val briefs = buildGraderFeedbackBriefs(gradingResult)
        graderFeedbackBriefs.putAll(briefs)
        persistGraderFeedbackBriefs(runId, roundIndex, briefs)
    }

    private suspend fun updateAdversarialDiscriminatorBriefs(
        runId: String,
        roundIndex: Int,
        blindResult: com.achulkov.evomolior.core.model.GradingResult,
        roundSubmissionSummaries: Map<String, String?>
    ) {
        val briefs = buildAdversarialDiscriminatorBriefs(blindResult, roundSubmissionSummaries)
        val rewardMultiplier = computeAdversarialDiscriminatorBudgetMultiplier(blindResult)
        adversarialDiscriminatorBriefs.putAll(briefs)
        adversarialDiscriminatorBudgetMultipliers[runId] = rewardMultiplier
        persistAdversarialDiscriminatorBriefs(runId, roundIndex, briefs, rewardMultiplier)
    }

    /**
     * Returns the current discriminator budget multiplier for the given run.
     *
     * The multiplier is derived from the latest blind grading spread and is used to reward
     * stronger discriminator rounds with slightly more budget on the next cycle.
     *
     * @param runId Run identifier.
     * @return Budget multiplier, clamped to a modest reward band.
     */
    fun adversarialDiscriminatorBudgetMultiplier(runId: String): Double =
        adversarialDiscriminatorBudgetMultipliers[runId]
            ?: readArchivedAdversarialDiscriminatorBudgetMultiplier(runId)
            ?: 1.0

    private fun computeAdversarialDiscriminatorBudgetMultiplier(
        blindResult: com.achulkov.evomolior.core.model.GradingResult
    ): Double {
        val weightedTotals = blindResult.scores.map { it.weightedTotal }
        if (weightedTotals.isEmpty()) return 1.0
        val min = weightedTotals.minOrNull() ?: return 1.0
        val max = weightedTotals.maxOrNull() ?: return 1.0
        val spread = (max - min) / (max.takeIf { it > 0.0 } ?: 1.0)
        return (1.0 + spread.coerceIn(0.0, 0.5)).coerceAtMost(1.5)
    }

    /**
     * Persists per-group adversarial discriminator briefs so the critic signal survives restart.
     */
    internal suspend fun persistAdversarialDiscriminatorBriefs(
        runId: String,
        roundIndex: Int,
        briefs: Map<String, String>,
        rewardMultiplier: Double
    ) = withContext(Dispatchers.IO) {
        if (briefs.isEmpty()) return@withContext
        val archiveDb = substrateFactory.get(runId, META_ARCHIVE_GROUP_ID)
        val now = System.currentTimeMillis()
        briefs.forEach { (groupId, brief) ->
            if (brief.isBlank()) return@forEach
            archiveDb.metaArchiveDao().insert(
                MetaArchiveEntryEntity(
                    id = "adversarial-discriminator-$runId-$groupId-r$roundIndex",
                    runId = runId,
                    entryType = RunArchiveService.ENTRY_ADVERSARIAL_DISCRIMINATOR_FEEDBACK,
                    groupId = groupId,
                    label = "Adversarial Discriminator Feedback — $groupId — run $runId",
                    contentJson = runConfigJson.encodeToString(String.serializer(), brief),
                    runObjective = runCatching {
                        archiveDb.metaArchiveDao().getByType(RunArchiveService.ENTRY_STARTING_FIELD)
                    }.getOrElse { emptyList() }
                        .firstOrNull { it.runId == runId }
                        ?.runObjective.orEmpty(),
                    finalScore = null,
                    isFromWinner = false,
                    generation = roundIndex,
                    archivedAtMs = now
                )
            )
            archivedAdversarialDiscriminatorBriefs[adversarialDiscriminatorBriefCacheKey(runId, groupId)] = brief
        }
        archiveDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "adversarial-discriminator-reward-$runId-r$roundIndex",
                runId = runId,
                entryType = RunArchiveService.ENTRY_ADVERSARIAL_DISCRIMINATOR_REWARD,
                groupId = null,
                label = "Adversarial Discriminator Reward — run $runId",
                contentJson = runConfigJson.encodeToString(Double.serializer(), rewardMultiplier),
                runObjective = runCatching {
                    archiveDb.metaArchiveDao().getByType(RunArchiveService.ENTRY_STARTING_FIELD)
                }.getOrElse { emptyList() }
                    .firstOrNull { it.runId == runId }
                    ?.runObjective.orEmpty(),
                finalScore = null,
                isFromWinner = false,
                generation = roundIndex,
                archivedAtMs = now
            )
        )
    }

    /**
     * Reads a persisted adversarial discriminator brief from the run archive, if present.
     */
    internal suspend fun readAdversarialDiscriminatorBrief(
        runId: String,
        groupId: String
    ): String? = withContext(Dispatchers.IO) {
        archivedAdversarialDiscriminatorBriefs[adversarialDiscriminatorBriefCacheKey(runId, groupId)]
            ?.takeIf { it.isNotBlank() }
            ?.let { return@withContext it }
        val archiveDb = runCatching { substrateFactory.get(runId, META_ARCHIVE_GROUP_ID) }
            .getOrNull()
            ?: return@withContext null
        val entry = runCatching {
            archiveDb.metaArchiveDao().getByType(RunArchiveService.ENTRY_ADVERSARIAL_DISCRIMINATOR_FEEDBACK)
        }.getOrElse { emptyList() }
            .filter { it.runId == runId && it.groupId == groupId }
            .maxWithOrNull(compareBy<com.achulkov.evomolior.substrate.db.entity.MetaArchiveEntryEntity> { it.generation }
                .thenBy { it.archivedAtMs })
            ?: return@withContext null
        runCatching {
            runConfigJson.decodeFromString(String.serializer(), entry.contentJson)
        }.getOrNull()?.also { brief ->
            archivedAdversarialDiscriminatorBriefs[adversarialDiscriminatorBriefCacheKey(runId, groupId)] = brief
        }
    }

    internal suspend fun persistGraderFeedbackBriefs(
        runId: String,
        roundIndex: Int,
        briefs: Map<String, String>
    ) = withContext(Dispatchers.IO) {
        if (briefs.isEmpty()) return@withContext
        val archiveDb = substrateFactory.get(runId, META_ARCHIVE_GROUP_ID)
        val now = System.currentTimeMillis()
        briefs.forEach { (groupId, brief) ->
            if (brief.isBlank()) return@forEach
            archiveDb.metaArchiveDao().insert(
                MetaArchiveEntryEntity(
                    id = "grader-feedback-$runId-$groupId-r$roundIndex",
                    runId = runId,
                    entryType = RunArchiveService.ENTRY_GRADER_GROUP_FEEDBACK,
                    groupId = groupId,
                    label = "Grader Group Feedback — $groupId — run $runId",
                    contentJson = runConfigJson.encodeToString(String.serializer(), brief),
                    runObjective = runCatching {
                        archiveDb.metaArchiveDao().getByType(RunArchiveService.ENTRY_STARTING_FIELD)
                    }.getOrElse { emptyList() }
                        .firstOrNull { it.runId == runId }
                        ?.runObjective.orEmpty(),
                    finalScore = null,
                    isFromWinner = false,
                    generation = roundIndex,
                    archivedAtMs = now
                )
            )
            archivedGraderFeedbackBriefs[adversarialDiscriminatorBriefCacheKey(runId, groupId)] = brief
        }
    }

    internal suspend fun readGraderFeedbackBrief(
        runId: String,
        groupId: String
    ): String? = withContext(Dispatchers.IO) {
        archivedGraderFeedbackBriefs[adversarialDiscriminatorBriefCacheKey(runId, groupId)]
            ?.takeIf { it.isNotBlank() }
            ?.let { return@withContext it }
        val archiveDb = runCatching { substrateFactory.get(runId, META_ARCHIVE_GROUP_ID) }
            .getOrNull()
            ?: return@withContext null
        val entry = runCatching {
            archiveDb.metaArchiveDao().getByType(RunArchiveService.ENTRY_GRADER_GROUP_FEEDBACK)
        }.getOrElse { emptyList() }
            .filter { it.runId == runId && it.groupId == groupId }
            .maxWithOrNull(compareBy<MetaArchiveEntryEntity> { it.generation }.thenBy { it.archivedAtMs })
            ?: return@withContext null
        runCatching {
            runConfigJson.decodeFromString(String.serializer(), entry.contentJson)
        }.getOrNull()?.also { brief ->
            archivedGraderFeedbackBriefs[adversarialDiscriminatorBriefCacheKey(runId, groupId)] = brief
        }
    }

    internal fun readArchivedAdversarialDiscriminatorBudgetMultiplier(
        runId: String
    ): Double? = runBlocking(Dispatchers.IO) {
        val archiveDb = runCatching { substrateFactory.get(runId, META_ARCHIVE_GROUP_ID) }
            .getOrNull()
            ?: return@runBlocking null
        val entry = runCatching {
            archiveDb.metaArchiveDao().getByType(RunArchiveService.ENTRY_ADVERSARIAL_DISCRIMINATOR_REWARD)
        }.getOrElse { emptyList() }
            .filter { it.runId == runId }
            .maxWithOrNull(compareBy<MetaArchiveEntryEntity> { it.generation }
                .thenBy { it.archivedAtMs })
            ?: return@runBlocking null
        runCatching {
            runConfigJson.decodeFromString(Double.serializer(), entry.contentJson)
        }.getOrNull()?.also { multiplier ->
            adversarialDiscriminatorBudgetMultipliers[runId] = multiplier
        }
    }

    private suspend fun runFinalSynthesisers(config: RunConfig, groupIds: List<String>) {
        for (groupId in groupIds) {
            try {
                val db = substrateFactory.get(config.runId, groupId)
                val synthesiser = FinalSynthesiser(
                    treeStore = deliverableSkeletonTreeStore,
                    db = db,
                    runId = config.runId,
                    groupId = groupId
                )
                val outputDir = File(substrateFactory.groupControlDir(config.runId, groupId), "deliverable")
                val outputFile = synthesiser.synthesise(
                    format = FinalSynthesiser.OutputFormat.MARKDOWN,
                    outputDir = outputDir,
                    objective = config.objective
                )
                Napier.i("EvoMoliorRunService: final deliverable synthesised for group=$groupId → ${outputFile.absolutePath}")
            } catch (e: Exception) {
                Napier.w(e) { "EvoMoliorRunService: final synthesis failed for group=$groupId" }
            }
        }
    }

    private fun cleanup(runId: String) {
        failedRunDrain?.let { throw it.failure }
        activeWatchdog?.stop()
        activeWatchdog = null
        activeInspectorRunner?.stopAll()
        activeInspectorRunner = null
        activeBlindTournamentService?.stop()
        activeBlindTournamentService = null
        stasisFlags.clear()
        liveLifecycleManagers.clear()
        activeCoordinator = null
        activeGraderGroupManager = null
        activeDisclosureEngine = null
        activeResourceMapEngine = null
        activeWorkerAtlasId = null
        activeWorkerExecutionEnvelope = null
        activeYieldConstraintPolicy = null
        activeRunLoopJob = null
        activeRunId = null
        currentRound = null
        groupSummaries = emptyList()
        currentStatus = EngineStatus.IDLE
        activeModelAssignments = emptyMap()
        activeGraderGroupIds = emptyList()
        activeModelPools = emptyMap()
        activeGroupProfiles = emptyList()
        activeRolePrompts = emptyMap()
        activeGenerationCeiling = 100
        activeLeakIntervalGenerations = 1
        activeDispatchCadenceGenerations = 1
        activeGenerationBudgetCeilings.clear()
        activeGenerationBudgetRounds.clear()
        activeKgSchema = "{}"
        activeScoreNormalization = emptyMap()
        activeSealedTestSuiteJson = ""
        activeSealedTestSuiteHash = ""
        activeSealedTestSuiteValidationContext = null
        activeGraderStartingField = null
        checkInGateOpenRound = null
        latestGroupDimensionScores.clear()
        latestGroupSubstrateAssessments.clear()
        latestGroupRoundAssessments.clear()
        authoritativeGraderRoundResults.clear()
        adversarialDiscriminatorBriefs.clear()
        graderFeedbackBriefs.clear()
        adversarialDiscriminatorBudgetMultipliers.clear()
        pendingWipCompletions.clear()
        sealedNodeEvidenceByRound.clear()
        nodeGradeBundlesByRound.clear()
        exactGraderVerdictsByRound.clear()
        scoringWorkerExactGraderVerdictsByRound.clear()
        disclosureFeedbackByGroup.clear()
        disclosureStageAdvancedByGroupRound.clear()
        repairApproachAssignments.clear()
        rewardSpendLineageByGroupRound.clear()
        rewardArtifactLineageByArtifactId.clear()
        auditorSpawnedGroups.clear()
        perspectiveTakerSpawnedGroups.clear()
        nestedHierarchyTriggeredGroups.clear()
        archivedAdversarialDiscriminatorBriefs.clear()
        archivedGraderFeedbackBriefs.clear()
        runFinalOutputSpecs.remove(runId)
        Napier.i("EvoMoliorRunService: cleanup complete for run $runId")
    }

    private fun requireActiveRun(runId: String) {
        check(activeRunId == runId) { "Run $runId is not the active run (active=$activeRunId)" }
    }

    private suspend fun emit(event: EvoMoliorEvent) {
        _events.emit(event)
    }

    /**
     * Resolves the path to the agent runner JAR.
     *
     * In development, check the system property. In distribution, the JAR
     * is bundled alongside the app package.
     */
    private fun resolveAgentJarPath(): String {
        return System.getProperty(
            "evomolior.agent.jar.path",
            "agent-runner.jar"
        )
    }

    internal fun recordWipCompletion(groupId: String, nodeId: String) {
        pendingWipCompletions.computeIfAbsent(groupId) { ConcurrentLinkedQueue() }.add(nodeId)
    }

    /**
     * Writes anonymised scoring failures into every grader group's staging DB.
     *
     * Failure telemetry is system-generated, so it is inserted directly as promoted
     * staging content rather than waiting for Critic review.
     */
    internal suspend fun routeFailureTelemetry(
        runId: String,
        graderGroupIds: List<String>,
        telemetry: FailureTelemetry
    ) {
        val now = System.currentTimeMillis()
        val content = buildString {
            appendLine("Failure telemetry")
            appendLine("Round: ${telemetry.roundIndex}")
            appendLine("Failure modes: ${telemetry.failureModeIds.joinToString(",").ifBlank { "none" }}")
            appendLine("Automated tests failed: ${telemetry.automatedTestsFailed}")
            appendLine("Raw score: ${String.format(java.util.Locale.ROOT, "%.3f", telemetry.rawScore)}")
            appendLine("Weighted score: ${String.format(java.util.Locale.ROOT, "%.3f", telemetry.weightedScore)}")
            appendLine("Grading sheets: ${telemetry.gradingSheetIds.joinToString(",")}")
        }

        graderGroupIds.forEach { graderGroupId ->
            try {
                val db = substrateFactory.getGrader(runId, graderGroupId)
                db.stagingDao().insert(
                    com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity(
                        id = "failure-telemetry-${telemetry.groupId}-${telemetry.roundIndex}-$graderGroupId",
                        agentId = "system",
                        role = "ScoringEngine",
                        type = com.achulkov.evomolior.core.model.SubmissionType.FAILURE_TELEMETRY.name,
                        content = content,
                        wipTaskRef = null,
                        speculative = false,
                        outlier = false,
                        budgetSignature = "system:failure-telemetry",
                        status = "promoted",
                        rejectionReason = null,
                        criticAgentId = null,
                        initialTrustScore = 1.0,
                        generation = telemetry.roundIndex,
                        groupId = graderGroupId,
                        runId = runId,
                        submittedAtMs = now,
                        reviewedAtMs = now
                    )
                )
            } catch (e: Exception) {
                Napier.w("EvoMoliorRunService: failed to route failure telemetry to grader=$graderGroupId: ${e.message}")
            }
        }
    }

    /**
     * Builds disclosure stages from the council output when present.
     *
     * The council-authored `stageContents` is the canonical source. The injected
     * `disclosureStages` list is retained as a fallback for older callers.
     */
    internal fun buildDisclosureStages(startingField: StartingField): List<DisclosureStage> {
        if (startingField.stageContents.isNotEmpty()) {
            return startingField.stageContents.mapIndexed { index, content ->
                DisclosureStage(
                    stageIndex = index,
                    title = "Council Stage ${index + 1}",
                    constraintFramedContent = content,
                    // Ordinary agents only see the constraint frame; MetaPlanner gets the
                    // run objective as the hidden goal frame for progress assessment.
                    goalFramedContent = startingField.objective,
                    unlockCondition = "Pass check-in ${index + 1}"
                )
            }
        }
        return disclosureStages.orEmpty()
    }

    /**
     * Migrates the seeded legacy node systems into the run-global Atlas before any worker starts.
     */
    private suspend fun ensureNodeAtlasAuthority(
        config: RunConfig,
        startingField: StartingField,
        groupIds: List<String>,
        authoritativeAtlas: NodeAtlas? = null,
        authoritativeReceiverContract: AtlasReceiverContract? = null
    ) {
        val atlasId = authoritativeAtlas?.atlasId
            ?: resolveNodeAtlasId(config.runId, startingField)
        val receiverContract = authoritativeReceiverContract ?: buildReceiverContract(config)
        val existing = atlasTransactionRepository.currentAtlas(config.runId, atlasId)
        if (existing != null) {
            val expectedReceiverHash = com.achulkov.evomolior.core.atlas.AtlasHashing
                .hashReceiverContract(receiverContract)
            require(existing.receiverContractHash == expectedReceiverHash) {
                "Persisted Node Atlas receiver contract does not match the exact run objective"
            }
            authoritativeAtlas?.let { expected ->
                require(existing == expected) {
                    "Persisted Node Atlas differs from the Council-compiled authority"
                }
            }
            return
        }

        val resources = deriveResourceNodes(
            resourceNodeSeeds = startingField.resourceNodes,
            nodeGraph = startingField.nodeGraph,
            deliverableSkeletonTreeJson = startingField.deliverableSkeletonTreeJson
        )
        val result = legacyNodeAtlasMigrationAdapter.compile(
            atlasId = atlasId,
            runId = config.runId,
            receiverContract = receiverContract,
            groupIds = groupIds,
            resourceNodes = resources,
            disclosureStages = buildDisclosureStages(startingField),
            authoritativeAtlas = authoritativeAtlas
        )
        when (result) {
            is AtlasMigrationResult.Success -> {
                atlasTransactionRepository.initialize(result.migration)
                Napier.i(
                    "EvoMoliorRunService: sealed Node Atlas ${result.migration.atlas.atlasId} " +
                        "with ${result.migration.atlas.nodes.size} nodes"
                )
            }
            is AtlasMigrationResult.Failure -> {
                val detail = result.errors.joinToString(separator = "; ") { error ->
                    "${error.code}:${error.scopeId.orEmpty()}/${error.sourceNodeId.orEmpty()} ${error.message}"
                }
                error("Node Atlas migration failed: $detail")
            }
        }
    }

    private fun buildReceiverContract(config: RunConfig): AtlasReceiverContract {
        val output = config.finalOutputSpec
        return AtlasReceiverContract(
            rawObjective = config.objective,
            receiverId = "operator",
            receiverDescription = "The operator who supplied the exact run objective",
            situation = "The receiver will use the final publication after competitive evaluation",
            decisionOrUse = "Use the evidence-backed deliverable to satisfy the supplied objective",
            whyNeeded = "The objective requires a receiver-usable result rather than an internal summary",
            successOutcomes = listOf("The final output satisfies its deterministic publication contract"),
            unacceptableOutcomes = listOf("The objective or evidence obligations are silently rewritten"),
            constraints = listOf("Preserve the exact raw objective", "Retain auditable evidence lineage"),
            finalDeliverableContract = AtlasFinalOutputSpec(
                relativePath = output?.relativePath ?: "final-deliverable.md",
                mediaType = output?.mediaType ?: "text/markdown",
                description = output?.description.orEmpty(),
                requiredSections = output?.validation
                    ?.requiredMarkdownSectionSignals
                    .orEmpty()
                    .toSet(),
                minimumEvidenceItems = output?.validation?.minimumPublicUrlCount ?: 0
            ),
            evidenceProfile = AtlasEvidenceProfile(
                minimumIndependentSources = 1,
                acceptedEvidenceKinds = setOf(
                    AtlasEvidenceKind.PRIMARY_SOURCE,
                    AtlasEvidenceKind.OFFICIAL_DATA,
                    AtlasEvidenceKind.EXPERIMENTAL
                ),
                requirePrimarySources = false,
                requireReproducibility = false
            )
        )
    }

    private fun nodeAtlasId(runId: String): String = "atlas:$runId"

    private fun resolveNodeAtlasId(runId: String, startingField: StartingField): String =
        runCatching {
            runConfigJson.parseToJsonElement(startingField.nodeGraph)
                .jsonObject["atlasId"]
                ?.jsonPrimitive
                ?.content
                ?.takeIf(String::isNotBlank)
        }.getOrNull() ?: nodeAtlasId(runId)

    /**
     * Spawns an Auditor once per group when a high-severity Inspector flag arrives.
     *
     * Auditor spawning is rate-limited per group so repeated alerts do not flood the run
     * with redundant policy agents.
     */
    internal suspend fun handleInspectorFlag(
        config: RunConfig,
        signal: WatchdogSignal.InspectorFlag,
        groupIds: List<String>
    ) {
        if (signal.observationIds.isNotEmpty()) {
            val graph = ContributionGraphRepository(
                database = substrateFactory.get(config.runId, signal.groupId),
                atlasDatabase = substrateFactory.getAtlas(config.runId)
            )
            val observations = buildList {
                signal.observationIds.sorted().forEach { eventId ->
                    add(graph.controlObservation(eventId))
                }
            }
            graph.recordControlIntervention(
                ControlInterventionRecord(
                    interventionId = CouncilDeterministicIds.create(
                        "control-intervention",
                        config.runId,
                        signal.groupId,
                        signal.suggestedRole?.name ?: "inspector-only",
                        observations.joinToString("|") { it.eventId }
                    ),
                    runId = config.runId,
                    groupId = AtlasGroupId(signal.groupId),
                    round = currentRound ?: 0,
                    controllerAgentId = "inspector:${signal.groupId}",
                    controllerRole = Role.Inspector,
                    targetKind = ControlInterventionTargetKind.GROUP,
                    targetId = signal.groupId,
                    observations = observations,
                    outcome = ControlInterventionOutcome.TRIGGERED_UNASSESSED,
                    occurredAt = Clock.System.now()
                ).withComputedHash()
            )
        }
        if (signal.suggestedRole == Role.PerspectiveTaker || signal.description.contains("turn-taking", ignoreCase = true)) {
            if (!perspectiveTakerSpawnedGroups.add(signal.groupId)) return
            spawnPerspectiveTakerForGroup(config, groupIds, signal.groupId, signal.description)
            return
        }

        if (signal.severity != WatchdogSeverity.HIGH && signal.severity != WatchdogSeverity.CRITICAL) return
        if (signal.suggestedRole != null && signal.suggestedRole != Role.Auditor) return
        if (!auditorSpawnedGroups.add(signal.groupId)) return

        val targetGroupId = signal.groupId
        val lm = liveLifecycleManagers[targetGroupId]
        if (lm == null) {
            Napier.w("EvoMoliorRunService: cannot spawn Auditor for $targetGroupId — no LifecycleManager")
            auditorSpawnedGroups.remove(signal.groupId)
            return
        }

        val profileType = activeGroupProfiles.getOrElse(groupIds.indexOf(targetGroupId)) {
            activeGroupProfiles.firstOrNull() ?: GroupProfileType.Library
        }
        val profile = GroupProfile.default(profileType)
        val budgetPerAgent = (profile.spawnBudget * 0.5).coerceAtLeast(1.0)
        val rolePrompt = activeRolePrompts[Role.Auditor.name]
            ?: "You are an Auditor. Investigate high-severity constitutional anomalies, quarantine unsafe outputs, and write system flags."
        val stageDisclosure = buildString {
            appendLine("High-severity Inspector flag")
            appendLine("Group: $targetGroupId")
            appendLine("Severity: ${signal.severity}")
            appendLine("Description: ${signal.description}")
            appendLine("Action: inspect recent activity and quarantine unsafe submissions.")
        }

        emit(EvoMoliorEvent.EngineProgress(
            config.runId,
            "auditor_spawn",
            "[$targetGroupId] Spawning Auditor for Inspector flag ${signal.severity}: ${signal.description}"
        ))

        try {
            lm.spawnSpecialAgent(
                role = Role.Auditor,
                generation = currentRound ?: 0,
                budgetPerAgent = budgetPerAgent,
                stageDisclosure = stageDisclosure,
                modelAssignments = activeModelAssignments,
                modelPools = activeModelPools,
                rolePrompt = rolePrompt,
                scope = scope
            )
        } catch (e: Exception) {
            auditorSpawnedGroups.remove(signal.groupId)
            Napier.w("EvoMoliorRunService: Auditor spawn failed for $targetGroupId: ${e.message}")
            emit(EvoMoliorEvent.EngineProgress(
                config.runId,
                "auditor_spawn_failed",
                "[$targetGroupId] Auditor spawn failed: ${e.message ?: "unknown error"}"
            ))
        }
    }

    /**
     * Spawns a PerspectiveTaker when the Inspector detects a coordination blind spot.
     */
    private suspend fun spawnPerspectiveTakerForGroup(
        config: RunConfig,
        groupIds: List<String>,
        targetGroupId: String,
        description: String
    ) {
        val lm = liveLifecycleManagers[targetGroupId]
        if (lm == null) {
            Napier.w("EvoMoliorRunService: cannot spawn PerspectiveTaker for $targetGroupId — no LifecycleManager")
            perspectiveTakerSpawnedGroups.remove(targetGroupId)
            return
        }

        val profileType = activeGroupProfiles.getOrElse(groupIds.indexOf(targetGroupId)) {
            activeGroupProfiles.firstOrNull() ?: GroupProfileType.Library
        }
        val profile = GroupProfile.default(profileType)
        val budgetPerAgent = (profile.spawnBudget * 0.5).coerceAtLeast(1.0)
        val rolePrompt = activeRolePrompts[Role.PerspectiveTaker.name]
            ?: "You are a PerspectiveTaker. Read other agents' Death Notes, staging updates, and recent signals. Surface missing viewpoints, stakeholder blind spots, and coordination gaps."
        val stageDisclosure = buildString {
            appendLine("Coordination blind spot detected")
            appendLine("Group: $targetGroupId")
            appendLine("Description: $description")
            appendLine("Action: inspect recent activity for missing perspectives and underrepresented viewpoints.")
        }

        emit(EvoMoliorEvent.EngineProgress(
            config.runId,
            "perspective_taker_spawn",
            "[$targetGroupId] Spawning PerspectiveTaker: $description"
        ))

        try {
            lm.spawnSpecialAgent(
                role = Role.PerspectiveTaker,
                generation = currentRound ?: 0,
                budgetPerAgent = budgetPerAgent,
                stageDisclosure = stageDisclosure,
                modelAssignments = activeModelAssignments,
                modelPools = activeModelPools,
                rolePrompt = rolePrompt,
                scope = scope
            )
        } catch (e: Exception) {
            perspectiveTakerSpawnedGroups.remove(targetGroupId)
            Napier.w("EvoMoliorRunService: PerspectiveTaker spawn failed for $targetGroupId: ${e.message}")
            emit(EvoMoliorEvent.EngineProgress(
                config.runId,
                "perspective_taker_spawn_failed",
                "[$targetGroupId] PerspectiveTaker spawn failed: ${e.message ?: "unknown error"}"
            ))
        }
    }

    /**
     * Handles a single watchdog signal in the same way the live collector does.
     *
     * Exposed as an internal boundary so the budget-critical pause behavior and other
     * watchdog transitions can be regression-tested without standing up a full run loop.
     *
     * @param config Current run configuration.
     * @param signal The watchdog signal to handle.
     * @param groupIds Competing group IDs in the run.
     */
    internal suspend fun handleWatchdogSignal(
        config: RunConfig,
        signal: WatchdogSignal,
        groupIds: List<String>
    ) {
        when (signal) {
            is WatchdogSignal.AgentStasis -> {
                stasisFlags[signal.groupId] = true
                emit(EvoMoliorEvent.EngineProgress(
                    config.runId,
                    "watchdog_stasis",
                    "[${signal.groupId}] Stasis detected — agent ${signal.agentId} idle ${signal.idleMs / 1000}s"
                ))
                activeWatchdog?.requestLeakRateIncrease(listOf(signal.groupId))
            }
            is WatchdogSignal.InspectorFlag -> {
                emit(EvoMoliorEvent.EngineProgress(
                    config.runId,
                    "watchdog_flag",
                    "[${signal.groupId}] Inspector flag ${signal.severity}: ${signal.description}"
                ))
                handleInspectorFlag(config, signal, groupIds)
            }
            is WatchdogSignal.BudgetCritical -> {
                // Budget policy is generation-scoped. LifecycleManager enforces agent floors;
                // this signal records pressure without pausing later replenished generations.
                emit(EvoMoliorEvent.EngineProgress(
                    config.runId,
                    "generation_budget_critical",
                    "[${signal.groupId}] Generation ${signal.generation} budget critical — " +
                        "remaining=${signal.remainingBudget}, allocated=${signal.generationBudget}"
                ))
            }
            is WatchdogSignal.KgAnomaly -> {
                emit(EvoMoliorEvent.EngineProgress(
                    config.runId,
                    "watchdog_kg",
                    "[${signal.groupId}] KG anomaly ${signal.anomalyType}: ${signal.details}"
                ))
            }
            is WatchdogSignal.CheckInGateOpen -> {
                // B-5: Open the check-in gate — processDisclosureCheckIns will now run.
                checkInGateOpenRound = signal.roundIndex
                Napier.d("EvoMoliorRunService: check-in gate opened for round ${signal.roundIndex}")
            }
            is WatchdogSignal.CheckInGateClosed -> {
                // B-5: Close the check-in gate — no more check-ins for this round.
                if (checkInGateOpenRound == signal.roundIndex) {
                    checkInGateOpenRound = null
                }
                Napier.d("EvoMoliorRunService: check-in gate closed for round ${signal.roundIndex}")
            }
            is WatchdogSignal.DeliverLeak -> {
                deliverWatchdogLeaks(
                    runId = config.runId,
                    targetGroupIds = signal.targetGroupIds.filter { it in groupIds },
                    roundIndex = signal.roundIndex,
                    requestedDomain = signal.domain
                )
            }
            is WatchdogSignal.IncreaseLeakRate -> {
                increaseLeakRateForTargets(
                    targetGroupIds = signal.targetGroupIds.filter { it in groupIds },
                    multiplier = signal.multiplier
                )
            }
        }
    }

    private fun increaseLeakRateForTargets(targetGroupIds: List<String>, multiplier: Double) {
        val coordinator = activeCoordinator ?: return
        targetGroupIds
            .flatMap { targetGroupId -> coordinator.neighboursOf(targetGroupId) }
            .distinct()
            .forEach { sourceGroupId ->
                val currentCeiling = coordinator.leakOracle.ceilingFor(sourceGroupId)
                val elevatedCeiling = kotlin.math.ceil(currentCeiling * multiplier)
                    .toInt()
                    .coerceIn(currentCeiling, MAX_DYNAMIC_LEAK_CEILING)
                coordinator.leakOracle.patchGroupCeiling(sourceGroupId, elevatedCeiling)
            }
    }

    private suspend fun deliverWatchdogLeaks(
        runId: String,
        targetGroupIds: List<String>,
        roundIndex: Int,
        requestedDomain: String?
    ) {
        val coordinator = activeCoordinator ?: return
        for (targetGroupId in targetGroupIds.distinct()) {
            var sourceAndEntry: Pair<String, KnowledgeBaseEntryEntity>? = null
            for (sourceGroupId in coordinator.neighboursOf(targetGroupId)) {
                val entry = substrateFactory.get(runId, sourceGroupId)
                    .knowledgeBaseDao()
                    .listActiveOrganized(runId, sourceGroupId, 20)
                    .firstOrNull { candidate ->
                        !candidate.isDeadEnd &&
                            (requestedDomain == null || candidate.domain == requestedDomain) &&
                            coordinator.leakOracle.isDomainAllowed(candidate.domain)
                    }
                if (entry != null) {
                    sourceAndEntry = sourceGroupId to entry
                    break
                }
            }

            if (sourceAndEntry == null) {
                Napier.w("EvoMoliorRunService: no eligible leak source for target=$targetGroupId round=$roundIndex")
                continue
            }
            val (sourceGroupId, sourceEntry) = sourceAndEntry
            val packet = LeakPacket(
                leakId = "leak-${UUID.randomUUID()}",
                content = sourceEntry.content.take(4_000),
                domain = sourceEntry.domain,
                recipientGroupId = targetGroupId,
                deliveredAtGeneration = roundIndex,
                runId = runId
            )
            val delivered = try {
                coordinator.deliverLeak(sourceGroupId, targetGroupId, packet)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Napier.w(error) {
                    "EvoMoliorRunService: leak privacy transform failed; delivery skipped " +
                        "source=$sourceGroupId target=$targetGroupId leak=${packet.leakId}"
                }
                continue
            }
            if (!delivered) {
                Napier.w(
                    "EvoMoliorRunService: LeakOracle blocked ${packet.leakId} " +
                        "source=$sourceGroupId target=$targetGroupId"
                )
            }
        }
    }

    internal fun shouldRequestAutomaticLeak(
        isTestRun: Boolean,
        roundIndex: Int,
        intervalGenerations: Int,
        groupCount: Int
    ): Boolean =
        !isTestRun &&
            groupCount > 1 &&
            roundIndex > 0 &&
            roundIndex % intervalGenerations.coerceAtLeast(1) == 0

    /**
     * Returns every target whose disclosure-adjusted leak cadence is due this round.
     *
     * The multiplier belongs to the recipient: an underperforming group receives earlier
     * delivery opportunities while source quotas and privacy enforcement remain independent.
     */
    internal fun automaticLeakTargets(
        isTestRun: Boolean,
        roundIndex: Int,
        intervalGenerations: Int,
        groupIds: List<String>,
        leakMultipliers: Map<String, Double>
    ): List<String> {
        if (isTestRun || groupIds.size < 2 || roundIndex <= 0) return emptyList()
        val baseInterval = intervalGenerations.coerceAtLeast(1)
        return groupIds.filter { groupId ->
            val multiplier = leakMultipliers[groupId]
                ?.takeIf { it.isFinite() && it >= 1.0 }
                ?: 1.0
            val effectiveInterval = kotlin.math.ceil(baseInterval / multiplier)
                .toInt()
                .coerceAtLeast(1)
            roundIndex % effectiveInterval == 0
        }
    }

    internal suspend fun routePromotedDispatch(
        runId: String,
        groupId: String,
        submission: StagingSubmissionEntity,
        trustScore: Double,
        coordinator: GroupCoordinator = activeCoordinator
            ?: error("Competition coordinator is not active for run $runId")
    ) {
        require(submission.type == "DISPATCH") {
            "Only promoted DISPATCH submissions can enter the formal relay"
        }
        substrateFactory.get(runId, groupId).eventLogDao().insert(
            EventLogEntryEntity(
                id = UUID.randomUUID().toString(),
                timestampMs = System.currentTimeMillis(),
                eventType = EventTypes.DISPATCH_SENT,
                agentId = submission.agentId,
                groupId = groupId,
                runId = runId,
                generation = submission.generation,
                payloadJson = """{"submissionId":"${submission.id}","trustScore":$trustScore}"""
            )
        )
        coordinator.dispatchLeak(
            sourceGroupId = groupId,
            message = DispatchMessage.Text(
                content = submission.content,
                metadata = mapOf(
                    "submissionId" to submission.id,
                    "agentId" to submission.agentId,
                    "generation" to submission.generation.toString(),
                    "criticTrustScore" to trustScore.toString()
                )
            )
        )
        emit(
            EvoMoliorEvent.EngineProgress(
                runId,
                "dispatch_queued",
                "[$groupId] Promoted dispatch ${submission.id} entered the relay"
            )
        )
    }

    internal suspend fun persistCrossGroupDelivery(
        runId: String,
        sourceGroupId: String,
        targetGroupId: String,
        message: DispatchMessage,
        trustScore: Double
    ) {
        val db = substrateFactory.get(runId, targetGroupId)
        val nowMs = System.currentTimeMillis()
        when (message) {
            is DispatchMessage.Text -> {
                val deliveryId = "dispatch-inbox-${UUID.randomUUID()}"
                db.knowledgeBaseDao().insert(
                    KnowledgeBaseEntryEntity(
                        id = deliveryId,
                        content = buildString {
                            appendLine("FORMAL DISPATCH")
                            appendLine("Source group: $sourceGroupId")
                            appendLine("Trust at delivery: $trustScore")
                            message.metadata["submissionId"]?.let { appendLine("Submission: $it") }
                            appendLine()
                            append(message.content)
                        },
                        sourceAgentId = "dispatch:$sourceGroupId",
                        generation = message.metadata["generation"]?.toIntOrNull() ?: (currentRound ?: 0),
                        trustScore = trustScore,
                        domain = "dispatch_inbox",
                        groupId = targetGroupId,
                        runId = runId,
                        createdAtMs = nowMs
                    )
                )
                db.eventLogDao().insert(
                    EventLogEntryEntity(
                        id = UUID.randomUUID().toString(),
                        timestampMs = nowMs,
                        eventType = EventTypes.DISPATCH_DELIVERED,
                        groupId = targetGroupId,
                        runId = runId,
                        generation = currentRound ?: 0,
                        payloadJson = """{"deliveryId":"$deliveryId","sourceGroupId":"$sourceGroupId","trustScore":$trustScore}"""
                    )
                )
                emit(
                    EvoMoliorEvent.EngineProgress(
                        runId,
                        "dispatch_delivered",
                        "[$targetGroupId] Formal dispatch delivered from $sourceGroupId"
                    )
                )
            }
            is DispatchMessage.Leak -> {
                val packet = message.packet
                db.knowledgeBaseDao().insert(
                    KnowledgeBaseEntryEntity(
                        id = packet.leakId,
                        content = packet.content,
                        sourceAgentId = "watchdog-leak-oracle",
                        generation = packet.deliveredAtGeneration,
                        trustScore = if (packet.isCorrupted) 0.45 else 0.65,
                        domain = "intelligence_leaks",
                        groupId = targetGroupId,
                        runId = runId,
                        createdAtMs = nowMs
                    )
                )
                db.eventLogDao().insert(
                    EventLogEntryEntity(
                        id = UUID.randomUUID().toString(),
                        timestampMs = nowMs,
                        eventType = EventTypes.LEAK_DELIVERED,
                        groupId = targetGroupId,
                        runId = runId,
                        generation = packet.deliveredAtGeneration,
                        payloadJson = """{"leakId":"${packet.leakId}","domain":"${packet.domain}","corrupted":${packet.isCorrupted}}"""
                    )
                )
                emit(
                    EvoMoliorEvent.LeakSent(
                        runId = runId,
                        fromGroupId = sourceGroupId,
                        toGroupId = targetGroupId,
                        documentKey = packet.leakId,
                        wasCorrupted = packet.isCorrupted
                    )
                )
            }
        }
    }

    internal fun drainWipCompletions(groupId: String): List<String> {
        val queue = pendingWipCompletions[groupId] ?: return emptyList()
        val completions = buildList {
            while (true) {
                val nodeId = queue.poll() ?: break
                add(nodeId)
            }
        }
        if (queue.isEmpty()) {
            pendingWipCompletions.remove(groupId, queue)
        }
        return completions.distinct()
    }
    /**
     * Registers the sealed grading sheet for the round if it has not been attached yet.
     *
     * The live round loop uses this once per round so the grader manager can evaluate a
     * sealed suite-backed baseline rubric alongside the RoundJudge path.
     *
     * @param manager Active grader-group manager for this run.
     * @param runConfig Current run configuration.
     * @param groupIds Competing group IDs.
     * @param roundIndex Current round index.
     * @param sheetRegisteredRounds Mutable set tracking already-registered rounds.
     */
    private fun registerRoundWithGraderManager(
        manager: GraderGroupManager,
        runConfig: RunConfig,
        groupIds: List<String>,
        roundIndex: Int,
        sheetRegisteredRounds: MutableSet<Int>
    ) {
        if (roundIndex in sheetRegisteredRounds) return
        val suiteJson = activeSealedTestSuiteJson.takeIf { it.isNotBlank() } ?: return
        val sheet = activeSealedTestSuiteValidationContext?.let { context ->
            sealedTestSuiteEvaluator.buildGradingSheet(
                runId = runConfig.runId,
                roundIndex = roundIndex,
                suiteJson = suiteJson,
                validationContext = context,
                scoringDimensions = runConfig.scoringDimensions
            )
        } ?: sealedTestSuiteEvaluator.buildGradingSheet(
            runId = runConfig.runId,
            roundIndex = roundIndex,
            suiteJson = suiteJson,
            suiteHash = activeSealedTestSuiteHash.ifBlank {
                canonicalTestSuiteHash(suiteJson)
            },
            scoringDimensions = runConfig.scoringDimensions
        )
        val registeredSheet = sheet.copy(
            sheetId = UUID.randomUUID().toString(),
            graderGroupId = "grader-default",
            comparativePairs = buildList {
                for (i in groupIds.indices) {
                    for (j in (i + 1) until groupIds.size) {
                        add(ComparativePair(groupIds[i], groupIds[j], preferredGroupId = null))
                    }
                }
            }
        )
        manager.registerSheet(registeredSheet)
        sheetRegisteredRounds += roundIndex
    }

    /**
     * Registers a sealed baseline rubric with the blind scoring engine.
     *
     * The engine needs at least one sealed sheet per round to derive its verdict.
     *
     * @param engine Blind scoring engine for the active run.
     * @param runConfig Current run configuration.
     * @param roundIndex Current round index.
     */
    private fun buildBlindScoringSheets(
        runConfig: RunConfig,
        roundIndex: Int
    ): List<GradingSheet> {
        val suiteJson = activeSealedTestSuiteJson.takeIf { it.isNotBlank() }
        val scoringDimensions = normaliseScoringDimensions(runConfig.scoringDimensions)
        val sheet = if (suiteJson == null) {
            buildDimensionOnlyBlindGradingSheet(
                runConfig = runConfig,
                roundIndex = roundIndex,
                scoringDimensions = scoringDimensions
            )
        } else {
            activeSealedTestSuiteValidationContext?.let { context ->
                sealedTestSuiteEvaluator.buildGradingSheet(
                    runId = runConfig.runId,
                    roundIndex = roundIndex,
                    suiteJson = suiteJson,
                    validationContext = context,
                    scoringDimensions = scoringDimensions
                )
            } ?: sealedTestSuiteEvaluator.buildGradingSheet(
                runId = runConfig.runId,
                roundIndex = roundIndex,
                suiteJson = suiteJson,
                suiteHash = activeSealedTestSuiteHash.ifBlank {
                    canonicalTestSuiteHash(suiteJson)
                },
                scoringDimensions = scoringDimensions
            )
        }
        return listOf(sheet)
    }

    /**
     * Provides a sealed rubric sheet for diagnostic/CLI runs that intentionally bypass Genesis.
     *
     * Test and pipeline runs often have explicit scoring dimensions but no Council-produced sealed
     * suite. Registering this dimension-only sheet preserves the blind-gate and MetaJudge path
     * instead of forcing every such round through the synthetic fallback scorer. No automated tests
     * are invented here; the sheet only formalises the run's declared dimensions as the rubric.
     */
    private fun buildDimensionOnlyBlindGradingSheet(
        runConfig: RunConfig,
        roundIndex: Int,
        scoringDimensions: List<ScoringDimension>
    ): GradingSheet {
        val totalWeight = scoringDimensions.sumOf { it.weight }.takeIf { it > 0.0 } ?: 1.0
        return GradingSheet(
            sheetId = "dimension-rubric-$roundIndex-${Integer.toHexString(runConfig.runId.hashCode())}",
            runId = runConfig.runId,
            roundIndex = roundIndex,
            graderGroupId = "dimension-rubric",
            rubricDimensions = scoringDimensions.map { dimension ->
                RubricDimension(
                    dimensionId = dimension.id,
                    name = dimension.name,
                    weight = dimension.weight / totalWeight,
                    scoringGuidance = "Dimension-only rubric derived from the run configuration " +
                        "because no sealed test suite was available.",
                    maxScore = dimension.maxScore,
                    dstNodeRef = dimension.dstNodeRef
                )
            },
            automatedTests = emptyList(),
            failureModes = emptyList(),
            comparativePairs = emptyList(),
            sealedAt = System.currentTimeMillis()
        )
    }

    /**
     * Applies council-defined normalization overrides to the live scoring dimensions.
     *
     * If the council did not provide an override for a dimension, the dimension's own
     * `maxScore` remains the fallback.
     */
    private fun normaliseScoringDimensions(scoringDimensions: List<ScoringDimension>): List<ScoringDimension> {
        return scoringDimensions.map { dimension ->
            val normalizedMaxScore = activeScoreNormalization[dimension.id]
                ?.takeIf { it > 0.0 }
                ?: dimension.maxScore
            if (normalizedMaxScore == dimension.maxScore) dimension
            else dimension.copy(maxScore = normalizedMaxScore)
        }
    }

    /**
     * Derives deterministic resource nodes from the council's node graph seed.
     *
     * Typed Council resource nodes are authoritative. Legacy StartingFields without that field
     * retain the prior deterministic node-graph/DST derivation for archive compatibility.
     *
     * @param nodeGraph Raw council node graph JSON.
     * @param deliverableSkeletonTreeJson Raw council deliverable skeleton JSON.
     * @return Resource nodes used by the round-level resource map.
     */
    internal fun deriveResourceNodes(
        resourceNodeSeeds: List<ResourceNodeSeed> = emptyList(),
        nodeGraph: String,
        deliverableSkeletonTreeJson: String = "{}"
    ): List<ResourceNode> {
        if (resourceNodeSeeds.isNotEmpty()) {
            require(resourceNodeSeeds.map { it.nodeId }.distinct().size == resourceNodeSeeds.size) {
                "Council resourceNodes must use unique node IDs"
            }
            val deliverableNodeIds = runCatching {
                resourceSeedJson.decodeFromString(
                    DeliverableSkeletonTree.serializer(),
                    deliverableSkeletonTreeJson
                ).nodes.mapTo(mutableSetOf()) { it.nodeId }
            }.getOrElse { error ->
                throw IllegalArgumentException(
                    "Council resourceNodes require a valid deliverableSkeletonTreeJson",
                    error
                )
            }
            require(resourceNodeSeeds.all { seed ->
                seed.deliverableNodeRef != DeliverableSkeletonTree.DEFAULT_ROOT_NODE_ID &&
                    seed.deliverableNodeRef in deliverableNodeIds
            }) {
                "Every Council resource node must reference a non-root deliverable skeleton node"
            }
            return resourceNodeSeeds.map { seed ->
                ResourceNode(
                    id = seed.nodeId,
                    name = seed.name,
                    resourceType = ResourceType.valueOf(seed.resourceType.name),
                    yieldMultiplier = seed.yieldMultiplier,
                    deliverableNodeRef = seed.deliverableNodeRef,
                    scoringDimensionIds = seed.scoringDimensionIds.distinct(),
                    qualityThreshold = seed.qualityThreshold,
                    floorGroupId = seed.floorGroupId
                )
            }
        }

        @Serializable
        data class NodeEntry(
            val nodeId: String,
            val description: String = "",
            val title: String = ""
        )

        data class ResourceSeed(val nodeId: String, val description: String)

        @Serializable
        data class DeliverableNodeEntry(
            val nodeId: String,
            val title: String = "",
            val pathHint: String? = null
        )

        @Serializable
        data class DeliverableTreeEntry(
            val nodes: List<DeliverableNodeEntry> = emptyList()
        )

        val nodes: List<NodeEntry> = try {
            if (nodeGraph.trimStart().startsWith("[")) {
                resourceSeedJson.decodeFromString<List<NodeEntry>>(nodeGraph)
            } else emptyList()
        } catch (e: Exception) {
            Napier.w("EvoMoliorRunService: could not parse nodeGraph as resource nodes: ${e.message}")
            emptyList()
        }

        val graphSeeds = nodes.map { node ->
            ResourceSeed(
                nodeId = node.nodeId,
                description = node.description.ifBlank { node.title }.ifBlank { node.nodeId }
            )
        }
        val graphNodeIds = graphSeeds.mapTo(mutableSetOf()) { it.nodeId }
        val deliverableSeeds = try {
            val raw = deliverableSkeletonTreeJson.trim()
            if (raw.isBlank() || raw == "{}") {
                emptyList()
            } else {
                resourceSeedJson.decodeFromString<DeliverableTreeEntry>(raw)
                    .nodes
                    .asSequence()
                    .filterNot { it.nodeId == DeliverableSkeletonTree.DEFAULT_ROOT_NODE_ID }
                    .filter { graphNodeIds.add(it.nodeId) }
                    .map { node ->
                        ResourceSeed(
                            nodeId = node.nodeId,
                            description = node.title.ifBlank { node.pathHint ?: node.nodeId }
                        )
                    }
                    .toList()
            }
        } catch (e: Exception) {
            Napier.w("EvoMoliorRunService: could not parse deliverable skeleton as resource nodes: ${e.message}")
            emptyList()
        }

        return (graphSeeds + deliverableSeeds).mapIndexed { index, node ->
            val resourceType = ResourceType.entries[index % ResourceType.entries.size]
            val yieldMultiplier = 1.05 + ((index % 5) * 0.05)
            ResourceNode(
                id = node.nodeId,
                name = node.description,
                resourceType = resourceType,
                yieldMultiplier = yieldMultiplier,
                deliverableNodeRef = node.nodeId
            )
        }
    }

    /**
     * Compatibility boundary for old callers that explicitly award completion-driven nodes.
     * Production round ownership now uses [publishGradedResourceNodeClaims].
     *
     * @param runId Active run identifier.
     * @param completedWipTasksByGroup WiP task IDs completed during this round.
     * @param round Current round index.
     */
    internal suspend fun publishResourceNodeClaims(
        runId: String,
        completedWipTasksByGroup: Map<String, List<String>>,
        round: Int,
        legacyMode: LegacyResourceCaptureMode
    ) {
        val engine = activeResourceMapEngine ?: return
        val claimEvents: List<NodeClaimEvent> = engine.applyWipCompletions(
            completedWipTasksByGroup,
            legacyMode
        )
        persistResourceClaimEvents(runId, round, claimEvents, authority = "wip-completion")
    }

    /**
     * Awards and recaptures Council-authored nodes from grades for their mapped deliverable part.
     */
    internal suspend fun publishGradedResourceNodeClaims(
        config: RunConfig,
        gradingResult: com.achulkov.evomolior.core.model.GradingResult,
        round: Int,
        workerAtlas: NodeAtlas? = null
    ) {
        val exactRoundVerdict = exactGraderVerdictsByRound[round]
        if (exactRoundVerdict != null &&
            exactRoundVerdict.status != ExactGraderRoundStatus.VALID
        ) {
            Napier.w(
                "EvoMoliorRunService: exact node capture failed closed for round=$round: " +
                    exactRoundVerdict.reason
            )
            return
        }
        val exactBundles = gradingResult.nodeGradeBundles.filter { it.round == round }
        if (exactBundles.isEmpty()) return
        val atlasId = activeWorkerAtlasId ?: return
        val atlas = workerAtlas ?: requireNotNull(
            atlasTransactionRepository.currentAtlas(config.runId, atlasId)
        ) {
            "Node Atlas $atlasId disappeared before capture resolution"
        }
        require(atlas.atlasId == atlasId && atlas.runId == config.runId) {
            "Capture resolution received a foreign round Atlas snapshot"
        }
        val atlasHistory = atlasTransactionRepository.versionHistory(config.runId, atlasId)
        val expectedGroups = readPersistedRunState(config.runId)
            ?.groupIds
            .orEmpty()
            .mapTo(linkedSetOf(), ::AtlasGroupId)
            .ifEmpty { exactBundles.mapTo(linkedSetOf()) { it.groupId } }
        val expectedGraderIds = exactBundles
            .flatMapTo(sortedSetOf()) { it.graderIds }
        val criteriaFreezeHashes = expectedGraderIds.mapNotNull { graderId ->
            graderAuthorityRepository.criteriaFreezeForRound(
                config.runId,
                round,
                graderId
            )?.freezeHash?.let { graderId to it }
        }.toMap()
        val resolver = NodeCaptureResolver()
        val priorCaptureDecisions = nodeCaptureRepository
            .captureDecisionsForRun(config.runId)
            .groupBy(NodeCaptureDecision::nodeId)
        val authority = AtlasNodeCaptureAuthority(
            runId = config.runId,
            atlasId = atlasId,
            atlasRepository = atlasTransactionRepository,
            captureRepository = nodeCaptureRepository
        )
        val resolvedCaptures = mutableListOf<
            Pair<com.achulkov.evomolior.competition.scoring.NodeCaptureResolution, List<NodeGradeBundle>>
        >()
        exactBundles.groupBy { it.nodeId }
            .toSortedMap(compareBy(NodeId::value))
            .forEach { (nodeId, bundles) ->
                val persistedBundles = bundles.mapNotNull { bundle ->
                    runCatching { nodeCaptureRepository.appendGradeBundle(bundle) }
                        .onFailure { error ->
                            Napier.w(
                                "EvoMoliorRunService: rejecting node-grade bundle " +
                                    "${bundle.bundleId} for node=${nodeId.value}: ${error.message}"
                            )
                        }
                        .getOrNull()
                }
                val current = atlasTransactionRepository
                    .currentStates(config.runId, atlasId)
                    .getValue(nodeId)
                appendRewardScoreObservations(
                    runId = config.runId,
                    round = round,
                    currentState = current,
                    bundles = persistedBundles
                )
                val resolution = resolver.resolve(
                    NodeCaptureResolutionRequest(
                        atlas = atlas,
                        currentState = current,
                        round = round,
                        expectedGroupIds = expectedGroups,
                        bundles = persistedBundles,
                        graderCriteriaFreezeHashes = criteriaFreezeHashes,
                        previousCaptureDecision = resolver.priorOwnerAuthority(
                            atlas = atlas,
                            currentState = current,
                            decisions = priorCaptureDecisions[nodeId].orEmpty(),
                            atlasHistory = atlasHistory,
                        ),
                        atlasHistory = atlasHistory,
                    )
                )
                authority.commit(resolution)
                appendIntegrationNodeAuthority(
                    atlas = atlas,
                    resolution = resolution,
                    bundles = persistedBundles
                )
                resolvedCaptures += resolution to persistedBundles
            }
        val yieldSchedules = scheduleNodeYields(
            runId = config.runId,
            round = round,
            atlas = atlas,
            resolvedCaptures = resolvedCaptures
        )
        persistExactResourceClaimEvents(
            runId = config.runId,
            round = round,
            resolvedCaptures = resolvedCaptures,
            yieldSchedules = yieldSchedules
        )
        atlasTransactionRepository.currentProjections(config.runId, atlasId)?.let { projection ->
            activeResourceMapEngine?.restoreAtlasProjection(
                ResourceAtlasProjectionAdapter.fromProjection(projection.resources)
            )
        }
    }

    private suspend fun appendIntegrationNodeAuthority(
        atlas: NodeAtlas,
        resolution: com.achulkov.evomolior.competition.scoring.NodeCaptureResolution,
        bundles: List<NodeGradeBundle>
    ) {
        val decision = resolution.decision
        val node = atlas.nodes.getValue(decision.nodeId)
        if (node.kind != NodeKind.INTEGRATION) return
        if (decision.decisionType !in setOf(
                NodeCaptureDecisionType.CAPTURED,
                NodeCaptureDecisionType.RECAPTURED,
                NodeCaptureDecisionType.SHARED_VALIDATION_CREDIT
            )
        ) {
            return
        }
        val captureAuthority = ContributionAuthorityRef(
            kind = ContributionAuthorityKind.NODE_CAPTURE_DECISION,
            authorityId = decision.decisionId,
            authorityHash = decision.decisionHash
        )
        decision.resultingOwnerGroupIds.sortedBy(AtlasGroupId::value).forEach { ownerGroupId ->
            val bundle = requireNotNull(
                bundles.singleOrNull {
                    it.groupId == ownerGroupId &&
                        it.bundleHash in decision.authoritativeBundleHashes
                }
            ) {
                "Captured integration node lacks one exact owner grade bundle"
            }
            val packetRef = ContributionAuthorityRef(
                kind = ContributionAuthorityKind.NODE_EVIDENCE_PACKET,
                authorityId = requireNotNull(bundle.evidencePacketId) {
                    "Captured integration bundle lacks evidence packet identity"
                },
                authorityHash = requireNotNull(bundle.evidencePacketHash) {
                    "Captured integration bundle lacks evidence packet hash"
                }
            )
            val database = substrateFactory.get(decision.runId, ownerGroupId.value)
            val graph = ContributionGraphRepository(
                database = database,
                atlasDatabase = substrateFactory.getAtlas(decision.runId)
            )
            val sources = graph.toAuthority(
                decision.runId,
                ownerGroupId.value,
                packetRef
            ).filter { it.relation == ContributionRelation.INCLUDED_IN_EVIDENCE }
                .map { it.source }
                .distinct()
                .sortedWith(compareBy({ it.kind.name }, ContributionAuthorityRef::authorityId))
            require(sources.isNotEmpty()) {
                "Captured integration node requires exact accepted input authority"
            }
            val integrationId = CouncilDeterministicIds.create(
                "integration-node",
                decision.runId,
                ownerGroupId.value,
                decision.decisionHash
            )
            graph.integrationNode(integrationId)?.let { existing ->
                require(
                    existing.captureDecision == captureAuthority &&
                        existing.sourceAuthorities == sources
                ) {
                    "Existing integration-node authority conflicts with capture replay"
                }
                return@forEach
            }
            graph.recordIntegrationNode(
                IntegrationNodeRecord(
                    integrationId = integrationId,
                    runId = decision.runId,
                    groupId = ownerGroupId,
                    round = decision.round,
                    nodeId = decision.nodeId.value,
                    atlasVersion = decision.atlasVersion,
                    atlasHash = decision.atlasHash,
                    nodePayloadHash = decision.nodePayloadHash,
                    captureDecision = captureAuthority,
                    sourceAuthorities = sources,
                    integratedAt = Clock.System.now()
                ).withComputedHash()
            )
        }
    }

    private suspend fun appendRewardScoreObservations(
        runId: String,
        round: Int,
        currentState: com.achulkov.evomolior.core.atlas.NodeExecutionState,
        bundles: List<NodeGradeBundle>
    ) {
        val packets = nodeCaptureRepository.evidencePacketsForNode(runId, currentState.nodeId)
            .associateBy(NodeEvidencePacket::packetHash)
        bundles.forEach { bundle ->
            val currentScore = bundle.normalizedScore ?: return@forEach
            val priorScore = currentState.grades
                .filter { it.groupId == bundle.groupId && it.round < round }
                .maxByOrNull { it.round }
                ?.normalizedScore
            val packet = bundle.evidencePacketHash?.let(packets::get) ?: return@forEach
            appendToolReuseAttribution(
                runId = runId,
                round = round,
                packet = packet,
                currentScore = currentScore,
                priorScore = priorScore ?: currentScore
            )
            if (priorScore == null) return@forEach
            val artifactIds = packet.acceptedArtifactIds + packet.acceptedClaimIds +
                packet.toolRunIds + packet.testResultIds + packet.negativeResultIds
            val parents = artifactIds
                .flatMap { rewardArtifactLineageByArtifactId[it].orEmpty() }
                .distinctBy(RewardUseLineageEvent::eventHash)
            parents.forEach { parent ->
                runCatching {
                    nodeCaptureRepository.appendRewardUseEvent(
                        parent.copy(
                            eventId = "reward-score:${parent.eventHash}:${bundle.bundleHash}",
                            amountSpent = 0.0,
                            observedScoreDelta = currentScore - priorScore,
                            observationRound = round,
                            parentEventHash = parent.eventHash,
                            eventHash = ""
                        ).withComputedHash()
                    )
                }.onFailure { error ->
                    Napier.w(
                        "EvoMoliorRunService: reward score lineage failed for " +
                            "bundle=${bundle.bundleId}: ${error.message}"
                    )
                }
            }
        }
    }

    internal suspend fun appendToolReuseAttribution(
        runId: String,
        round: Int,
        packet: NodeEvidencePacket,
        currentScore: Double,
        priorScore: Double
    ) {
        if (packet.toolRunIds.isEmpty()) return
        val database = substrateFactory.get(runId, packet.groupId.value)
        val workerRepository = WorkerExecutionRepository(database)
        val toolRepository = ToolInfrastructureRepository(database)
        val contributionGraphRepository = ContributionGraphRepository(
            database = database,
            atlasDatabase = substrateFactory.getAtlas(runId)
        )
        val contributions = workerRepository
            .contributionsForNode(runId, packet.groupId.value, packet.nodeId.value)
            .filter { it.round == packet.round }
        val reusedVersions = linkedSetOf<com.achulkov.evomolior.core.atlas.ToolVersionId>()
        packet.toolRunIds.sorted().forEach { executionId ->
            val execution = toolRepository.execution(executionId) ?: return@forEach
            val contribution = contributions
                .filter { executionId in it.toolRunIds && it.agentId == execution.agentId }
                .minByOrNull(ContributionCommit::commitId)
                ?: return@forEach
            val origin = toolRepository.lifecycle(execution.toolVersionId).firstOrNull()
                ?: return@forEach
            if (origin.originNodeId == packet.nodeId) return@forEach
            val lineageId = "tool-reuse:${AtlasHashing.hashText(
                listOf(
                    executionId,
                    contribution.commitId,
                    packet.packetHash
                ).joinToString("|")
            )}"
            val reuse = ToolReuseLineage(
                lineageId = lineageId,
                runId = runId,
                toolVersionId = execution.toolVersionId,
                executionId = executionId,
                originNodeId = origin.originNodeId,
                reusedByAgentId = execution.agentId,
                reusedOnNodeId = packet.nodeId,
                contributionCommitId = contribution.commitId,
                nodeEvidencePacketHash = packet.packetHash,
                nodeScoreBefore = priorScore,
                nodeScoreAfter = currentScore,
                // No Council-authored repeated-cost baseline exists yet, so attribution stays zero.
                avoidedRepeatedCost = 0.0,
                attributedAt = Clock.System.now()
            ).withComputedHash()
            runCatching {
                contributionGraphRepository.recordDownstreamToolExecution(execution)
                toolRepository.appendReuseAndReference(
                    value = reuse,
                    lifecycleEventId = "reuse-lifecycle:$lineageId",
                    recordedAt = Clock.System.now()
                )
                reusedVersions += execution.toolVersionId
            }.onFailure { error ->
                Napier.w(
                    "EvoMoliorRunService: tool reuse attribution failed for " +
                        "execution=$executionId packet=${packet.packetId}: ${error.message}"
                )
            }
        }
        reusedVersions.forEach { toolVersionId ->
            runCatching {
                toolRepository.deriveAndAppendFitness(toolVersionId, throughRound = round)
            }.onFailure { error ->
                Napier.w(
                    "EvoMoliorRunService: delayed tool fitness failed for " +
                        "version=${toolVersionId.value} round=$round: ${error.message}"
                )
            }
        }
    }

    private suspend fun scheduleNodeYields(
        runId: String,
        round: Int,
        atlas: NodeAtlas,
        resolvedCaptures: List<
            Pair<com.achulkov.evomolior.competition.scoring.NodeCaptureResolution, List<NodeGradeBundle>>
        >
    ): List<NodeYieldSchedule> {
        val policy = activeYieldConstraintPolicy ?: return emptyList()
        val candidates = resolvedCaptures.flatMap { (resolution, bundles) ->
            val decision = resolution.decision
            if (!decision.yieldEligible || decision.round != round) return@flatMap emptyList()
            val node = atlas.nodes[decision.nodeId] ?: return@flatMap emptyList()
            val priorDecisions = nodeCaptureRepository.captureDecisionsForNode(runId, node.id)
                .filter { it.round < round && it.yieldEligible }
            decision.resultingOwnerGroupIds.mapNotNull { ownerGroupId ->
                val bundle = bundles
                    .filter {
                        it.groupId == ownerGroupId &&
                            it.bundleHash in decision.authoritativeBundleHashes
                    }
                    .maxWithOrNull(
                        compareBy<NodeGradeBundle> { it.normalizedScore ?: Double.NEGATIVE_INFINITY }
                            .thenBy { it.bundleHash }
                    )
                    ?: return@mapNotNull null
                val evidenceHash = bundle.evidencePacketHash ?: return@mapNotNull null
                val initialCapture = decision.previousOwnerGroupId == null
                val credits = node.rewardSpec.credits +
                    if (initialCapture) node.rewardSpec.firstCompletionBonus else 0.0
                if (!credits.isFinite() || credits <= 0.0) return@mapNotNull null
                NodeYieldCandidate(
                    decision = decision,
                    node = node,
                    groupId = ownerGroupId,
                    evidencePacketHash = evidenceHash,
                    scoreBundleHash = bundle.bundleHash,
                    baseRewards = listOf(
                        ScheduledReward(
                            rewardType = node.captureRewardType(),
                            amount = credits
                        )
                    ),
                    priorCaptureCount = priorDecisions.count {
                        ownerGroupId in it.resultingOwnerGroupIds
                    },
                    mandatoryFrontier = initialCapture && node.id in atlas.rootNodeIds,
                    independentValidation =
                        decision.decisionType == NodeCaptureDecisionType.SHARED_VALIDATION_CREDIT
                )
            }
        }
        if (candidates.isEmpty()) return emptyList()
        val groupIds = candidates.mapTo(linkedSetOf(), NodeYieldCandidate::groupId)
        val alreadyScheduled = groupIds.associateWith { groupId ->
            nodeCaptureRepository.yieldSchedulesForActivation(
                runId = runId,
                groupId = groupId.value,
                activationRound = round + 1
            ).sumOf { schedule -> schedule.rewards.sumOf(ScheduledReward::amount) }
        }
        val schedules = NodeYieldScheduler().schedule(
            NodeYieldSchedulingBatch(
                judgingValid = resolvedCaptures.all { (resolution, _) ->
                    resolution.decision.decisionType !=
                        NodeCaptureDecisionType.FROZEN_INVALID_AUTHORITY
                },
                policy = policy,
                candidates = candidates,
                alreadyScheduledForActivationByGroup = alreadyScheduled
            )
        )
        return schedules.map { schedule ->
            nodeCaptureRepository.appendYieldSchedule(schedule)
        }
    }

    private fun LimitationNode.captureRewardType(): InstrumentalRewardType = when (kind) {
        NodeKind.DELIVERY,
        NodeKind.INTEGRATION,
        NodeKind.VALIDATION -> InstrumentalRewardType.ARTIFACT_VALIDATION_CREDITS
        NodeKind.TOOL,
        NodeKind.SIMULATION,
        NodeKind.EXPERIMENT -> InstrumentalRewardType.TOOL_COMPUTE_CREDITS
        else -> InstrumentalRewardType.SPAWN_CREDITS
    }

    private suspend fun persistExactResourceClaimEvents(
        runId: String,
        round: Int,
        resolvedCaptures: List<
            Pair<com.achulkov.evomolior.competition.scoring.NodeCaptureResolution, List<NodeGradeBundle>>
        >,
        yieldSchedules: List<NodeYieldSchedule>
    ) {
        val schedulesByDecision = yieldSchedules.associateBy(NodeYieldSchedule::captureDecisionHash)
        resolvedCaptures.forEach { (resolution, bundles) ->
            val decision = resolution.decision
            if (decision.decisionType !in EXACT_CLAIM_DECISIONS) return@forEach
            decision.resultingOwnerGroupIds.sortedBy(AtlasGroupId::value).forEach { owner ->
                val bundle = bundles.firstOrNull {
                    it.groupId == owner && it.bundleHash in decision.authoritativeBundleHashes
                } ?: return@forEach
                val packetId = bundle.evidencePacketId ?: return@forEach
                val packetHash = bundle.evidencePacketHash ?: return@forEach
                val scheduleHash = schedulesByDecision[decision.decisionHash]?.scheduleHash
                val payload = buildJsonObject {
                    put("resourceNodeId", decision.nodeId.value)
                    put("previousOwnerId", decision.previousOwnerGroupId?.value)
                    put("newOwnerId", owner.value)
                    put("claimRound", round)
                    put("authority", "exact-node-grade")
                    put("evidencePacketId", packetId)
                    put("evidencePacketHash", packetHash)
                    put("scoreBundleId", bundle.bundleId)
                    put("scoreBundleHash", bundle.bundleHash)
                    put("captureDecisionId", decision.decisionId)
                    put("captureDecisionHash", decision.decisionHash)
                    put("captureDecisionType", decision.decisionType.name)
                    put("gradeVerdict", bundle.verdict.name)
                    put("atlasVersion", decision.atlasVersion)
                    put("atlasHash", decision.atlasHash)
                    put("nodePayloadHash", decision.nodePayloadHash)
                    decision.winningScore?.let { put("winningScore", it) }
                    decision.priorWinningScore?.let { put("priorWinningScore", it) }
                    scheduleHash?.let { put("yieldScheduleHash", it) }
                }.toString()
                substrateFactory.get(runId, owner.value).eventLogDao().insert(
                    EventLogEntryEntity(
                        id = AtlasHashing.hashText(
                            listOf("exact-node-claim", decision.decisionHash, owner.value)
                                .joinToString("|")
                        ),
                        timestampMs = System.currentTimeMillis(),
                        eventType = if (decision.decisionType == NodeCaptureDecisionType.RECAPTURED) {
                            EventTypes.NODE_RECAPTURED
                        } else {
                            EventTypes.NODE_CLAIMED
                        },
                        groupId = owner.value,
                        runId = runId,
                        generation = round,
                        payloadJson = payload
                    )
                )
                emit(
                    EvoMoliorEvent.ResourceNodeClaimed(
                        runId = runId,
                        nodeId = decision.nodeId.value,
                        groupId = owner.value,
                        round = round,
                        previousOwnerGroupId = decision.previousOwnerGroupId?.value,
                        evidencePacketId = packetId,
                        evidencePacketHash = packetHash,
                        scoreBundleId = bundle.bundleId,
                        scoreBundleHash = bundle.bundleHash,
                        captureDecisionId = decision.decisionId,
                        captureDecisionHash = decision.decisionHash,
                        captureDecisionType = decision.decisionType.name,
                        gradeVerdict = bundle.verdict.name,
                        atlasVersion = decision.atlasVersion,
                        atlasHash = decision.atlasHash,
                        nodePayloadHash = decision.nodePayloadHash,
                        winningScore = decision.winningScore,
                        priorWinningScore = decision.priorWinningScore,
                        yieldScheduleHash = scheduleHash
                    )
                )
            }
        }
    }

    private suspend fun persistResourceClaimEvents(
        runId: String,
        round: Int,
        claimEvents: List<NodeClaimEvent>,
        authority: String
    ) {
        claimEvents.forEach { event ->
            val eventType = if (event.previousOwnerId == null) {
                EventTypes.NODE_CLAIMED
            } else {
                EventTypes.NODE_RECAPTURED
            }
            substrateFactory.get(runId, event.newOwnerId).eventLogDao().insert(
                EventLogEntryEntity(
                    id = UUID.randomUUID().toString(),
                    timestampMs = System.currentTimeMillis(),
                    eventType = eventType,
                    groupId = event.newOwnerId,
                    runId = runId,
                    generation = round,
                    payloadJson = buildJsonObject {
                        put("resourceNodeId", event.nodeId)
                        put("resourceType", event.resourceType.name)
                        event.previousOwnerId?.let { put("previousOwnerId", it) }
                        put("newOwnerId", event.newOwnerId)
                        put("claimRound", round)
                        put("authority", authority)
                        put("winnerScore", event.winnerScore)
                        put("runnerUpScore", event.runnerUpScore)
                    }.toString()
                )
            )
            emit(EvoMoliorEvent.ResourceNodeClaimed(runId, event.nodeId, event.newOwnerId))
            Napier.i("EvoMoliorRunService: resource node ${event.nodeId} claimed by ${event.newOwnerId} round=$round")
        }
    }

    /**
     * Builds a deterministic reviewer identity for a critic pipeline instance.
     *
     * A stable identity keeps the self-review guard active in production runs instead of only
     * in tests that inject an explicit critic identity.
     */
    private fun criticAgentIdFor(groupId: String, round: Int): String = "critic-$groupId-round-$round"

    private fun criticLineageFor(groupId: String, round: Int): Set<String> =
        (1..round).map { previousRound -> criticAgentIdFor(groupId, previousRound) }.toSet()

    /**
     * Publishes the round's sealed substrate scores through the blind gate.
     *
     * The gate is opened only after all sealed submissions are ready, and the final
     * [GradingResult] is emitted only after the blind reveal completes.
     *
     * @param config Current run configuration.
     * @param groupIds Competing group IDs.
     * @param round Current round index.
     * @return Final blind-scored result for the round.
     */
    /**
     * Phase 7.5: Applies DST structural refinements and rubric policies accepted by the
     * Meta-Judge, across all competing groups.
     *
     * @param runId Active run identifier.
     * @param groupIds Competing group IDs.
     * @param blindResult The blind round result, carrying accepted refinements and policies.
     * @param round Current round index.
     */
    private suspend fun applyDstRefinementsFromRound(
        runId: String,
        groupIds: List<String>,
        blindResult: com.achulkov.evomolior.core.model.GradingResult,
        round: Int
    ) {
        val refinements = blindResult.acceptedRefinements
        val policies = blindResult.newRubricPolicies

        if (refinements.isEmpty() && policies.isEmpty()) return

        for (groupId in groupIds) {
            try {
                // Apply rubric policies first (may affect complexity levels)
                if (policies.isNotEmpty()) {
                    deliverableSkeletonTreeStore.applyPolicies(runId, groupId, policies)
                }

                // Apply structural refinements
                if (refinements.isNotEmpty()) {
                    val refiner = DeliverableSkeletonTreeRefiner(
                        store = deliverableSkeletonTreeStore,
                        runId = runId,
                        groupId = groupId
                    )
                    val result = refiner.applyRefinements(refinements, round)
                    if (result.structuralChangesCount > 0) {
                        Napier.i("EvoMoliorRunService: applied ${result.structuralChangesCount} DST refinements for group=$groupId round=$round")
                    }
                }
                deliverableWipReconciler.reconcile(runId, groupId, round)
            } catch (e: Exception) {
                Napier.w(e) { "EvoMoliorRunService: failed to apply DST refinements for group=$groupId round=$round" }
            }
        }
    }

    private suspend fun publishBlindRoundResult(
        config: RunConfig,
        groupIds: List<String>,
        round: Int
    ): com.achulkov.evomolior.core.model.GradingResult {
        val tournament = activeBlindTournamentService
            ?: error("BlindTournamentService is not initialised for run ${config.runId}")
        val exactGraderBundles = if (
            graderAuthorityRepository.currentAtlas(config.runId) != null
        ) {
            graderAuthorityRepository.scoreBundlesForRound(config.runId, round)
        } else {
            emptyList()
        }

        return tournament.publishRoundResult(
            config = config,
            groupIds = groupIds,
            round = round,
            expectedGraderGroupIds = if (exactGraderBundles.isNotEmpty()) {
                activeGraderGroupIds.toSet()
            } else {
                emptySet()
            },
            exactGraderBundles = exactGraderBundles,
            buildGradingSheets = ::buildBlindScoringSheets,
            buildGroupRoundResult = { groupId ->
                val substrateAssessment = latestGroupSubstrateAssessments[groupId]
                val sealedAssessment = latestGroupRoundAssessments[groupId]
                val graderScore = authoritativeGraderRoundResults[round]
                    ?.scores
                    ?.firstOrNull { it.groupId == groupId }
                GroupRoundResult(
                    groupId = groupId,
                    roundIndex = round,
                    dimensionScores = graderScore?.dimensionScores?.associate { it.dimensionId to it.rawScore }
                        ?: substrateAssessment?.dimensionScores?.associate { it.dimensionId to it.rawScore }
                        ?: sealedAssessment?.dimensionScores?.associate { it.dimensionId to it.rawScore }
                        ?: latestGroupDimensionScores[groupId].orEmpty(),
                    dimensionJustifications = graderScore?.dimensionScores
                        ?.associate { it.dimensionId to it.justification }
                        .orEmpty(),
                    failureModesTriggered = substrateAssessment?.failureModesTriggered.orEmpty(),
                    automatedTestsPassed = sealedAssessment?.automatedTestsPassed ?: 0,
                    automatedTestsFailed = sealedAssessment?.automatedTestsFailed ?: 0
                )
            }
        ).also { result ->
            verifyAcceptanceRunBoundary(
                config,
                AcceptanceRunBoundary.RoundCompleted(round)
            )
            emit(EvoMoliorEvent.RoundCompleted(config.runId, result))
            groupSummaries = groupSummaries.map { summary ->
                val score = result.scores.firstOrNull { it.groupId == summary.groupId }
                if (score != null) summary.copy(currentRoundScore = score.weightedTotal) else summary
            }
        }
    }

    internal fun verifyAcceptanceRunBoundary(
        config: RunConfig,
        boundary: AcceptanceRunBoundary
    ) {
        if (config.acceptanceProfile == AcceptanceProfile.NORMAL) return
        acceptanceRunBoundaryVerifier.verify(config.runId, boundary)
    }

    /** Returns whether the service has a real acceptance boundary authority rather than NONE. */
    private fun hasConfiguredAcceptanceRunBoundaryVerifier(): Boolean =
        acceptanceRunBoundaryVerifier !== AcceptanceRunBoundaryVerifier.NONE

    private fun snapshotLiveAgentBudgets(groupIds: List<String>): Map<String, Double> =
        groupIds.asSequence()
            .flatMap { groupId ->
                liveLifecycleManagers[groupId]
                    ?.snapshotLiveAgentBudgets()
                    .orEmpty()
                    .asSequence()
                    .map { (agentId, budget) -> "$groupId/$agentId" to budget }
            }
            .toMap()
}

/** Summary of a legacy-protocol expiry sweep. */
internal data class LegacyExpirySweepResult(
    val expiredProtocols: Int,
    val deadEndsInserted: Int
)

/**
 * Selects the configured round-scoring authority without treating absent grader execution as data.
 *
 * Deterministic substrate scoring is an explicit mode when no grader executor is configured.
 * Once grader authority is configured, incomplete evidence aborts before any downstream mutation.
 */
internal fun resolveAuthoritativeGraderRoundResult(
    manager: GraderGroupManager,
    groupIds: List<String>,
    roundIndex: Int,
    graderAuthorityConfigured: Boolean
): com.achulkov.evomolior.core.model.GradingResult? {
    if (!graderAuthorityConfigured) return null
    check(manager.hasCompleteRoundScores(groupIds, roundIndex)) {
        "Authoritative grader scores are incomplete for round=$roundIndex"
    }
    return manager.computeRoundResult(groupIds, roundIndex)
}

/**
 * Decides whether a run uses production budget-led round completion.
 *
 * A positive duration and every explicit duration override retain fixed-duration behavior.
 * Test runs also retain their existing semantics even when their configured duration is zero.
 *
 * @param config Run configuration whose production/test mode and configured duration are checked.
 * @param roundDurationMillisOverride Optional focused-test wall-clock override.
 * @return `true` only for production configurations with no override and non-positive duration.
 */
internal fun isBudgetLedProductionRound(
    config: RunConfig,
    roundDurationMillisOverride: Long?
): Boolean =
    !config.isTestRun &&
        roundDurationMillisOverride == null &&
        config.roundDurationMinutes <= 0

/** Five minutes without a live-set or remaining-budget change ends a budget-led round. */
internal const val BUDGET_LED_ROUND_STALL_GRACE_MS: Long = 5L * 60_000L
