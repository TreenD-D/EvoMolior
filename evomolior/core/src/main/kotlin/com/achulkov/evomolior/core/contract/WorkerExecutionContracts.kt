@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.core.contract

import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.CapturePolicy
import com.achulkov.evomolior.core.atlas.LimitationNode
import com.achulkov.evomolior.core.atlas.NodeDeliverableContract
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.NodeStatus
import com.achulkov.evomolior.core.atlas.NoveltyType
import com.achulkov.evomolior.core.atlas.ProofContract
import com.achulkov.evomolior.core.atlas.ProofMethod
import com.achulkov.evomolior.core.atlas.ReplicationPolicy
import com.achulkov.evomolior.core.atlas.RewardSpec
import com.achulkov.evomolior.core.atlas.ToolExecutionPurpose
import com.achulkov.evomolior.core.atlas.ToolOpportunity
import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitment
import com.achulkov.evomolior.core.atlas.ToolProjectSkeleton
import com.achulkov.evomolior.core.atlas.ToolProjectSkeletonCompiler
import com.achulkov.evomolior.core.repair.QuantitySpec
import com.achulkov.evomolior.core.repair.ApproachVector
import com.achulkov.evomolior.core.repair.ToolRepairCode
import com.achulkov.evomolior.core.repair.ToolRepairRemediationDictionary
import com.achulkov.evomolior.core.repair.ToolRepairSource
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Information classes that must remain outside an ordinary worker context. */
@Serializable
enum class VisibilityClass {
    RECEIVER_PRIVATE,
    COUNCIL_PRIVATE,
    GRADER_PRIVATE,
    FUTURE_STAGE,
    OTHER_GROUP_PRIVATE
}

/** Redacted identity and graph shape for one Atlas node. */
@Serializable
data class AtlasSkeletonNodeView(
    val nodeId: NodeId,
    val kind: NodeKind,
    val layer: Int,
    val parentIds: Set<NodeId>,
    val prerequisiteIds: Set<NodeId>,
    val alternativeToIds: Set<NodeId>,
    val stageId: String,
    val payloadHash: String,
    val status: NodeStatus,
    val congestionLevel: Int
)

/** Worker-visible Atlas structure containing no hidden node payload fields. */
@Serializable
data class AtlasSkeletonView(
    val atlasId: String,
    val atlasVersion: Int,
    val atlasHash: String,
    val rootNodeIds: Set<NodeId>,
    val nodes: Map<NodeId, AtlasSkeletonNodeView>
)

/** Complete node payload exposed only after the node has been revealed to this group. */
@Serializable
data class RevealedNodeCard(
    val nodeId: NodeId,
    val kind: NodeKind,
    val receiverWhyRef: String,
    val limitationStatement: String,
    val capabilityTarget: String,
    val atomicQuestion: String,
    val proofContract: ProofContract,
    val deliverableContract: NodeDeliverableContract,
    val replicationPolicy: ReplicationPolicy,
    val capturePolicy: CapturePolicy,
    val rewardSpec: RewardSpec,
    val toolOpportunity: ToolOpportunity?,
    val payloadHash: String
)

/**
 * Converts one Atlas node into the exact payload projection delivered after reveal authorization.
 *
 * @return Worker-visible node card preserving the node payload commitment.
 */
fun LimitationNode.toRevealedNodeCard(): RevealedNodeCard = RevealedNodeCard(
    nodeId = id,
    kind = kind,
    receiverWhyRef = receiverWhyRef,
    limitationStatement = limitationStatement,
    capabilityTarget = capabilityTarget,
    atomicQuestion = atomicQuestion,
    proofContract = proofContract,
    deliverableContract = deliverableContract,
    replicationPolicy = replicationPolicy,
    capturePolicy = capturePolicy,
    rewardSpec = rewardSpec,
    toolOpportunity = toolOpportunity,
    payloadHash = payloadHash
)

/** Redacted grader feedback safe for the group's current disclosure stage. */
@Serializable
data class GroupFeedbackPacket(
    val feedbackId: String,
    val stageId: String,
    val failedVisibleNodeIds: Set<NodeId>,
    val evidenceGaps: List<String>,
    val actionableNextSteps: List<String>,
    val feedbackHash: String
)

/** Bounded reference to a group-readable artifact. */
@Serializable
data class WorkerArtifactRef(
    val artifactId: String,
    val artifactType: ArtifactType,
    val label: String,
    val contentHash: String
)

/** Bounded worker-visible tool card. */
@Serializable
data class WorkerToolCard(
    val toolId: String,
    val name: String,
    val version: Int,
    val opportunityId: String,
    val opportunityCommitmentHash: String,
    val requiredForNodeId: NodeId,
    val executionPurpose: ToolExecutionPurpose = ToolExecutionPurpose.AGENT_USE,
    val description: String,
    val inputSchemaHash: String,
    val outputSchemaHash: String
) {
    init {
        require(
            toolId.isNotBlank() &&
                name.isNotBlank() &&
                opportunityId.isNotBlank() &&
                opportunityCommitmentHash.isNotBlank() &&
                requiredForNodeId.value.isNotBlank() &&
                description.isNotBlank() &&
                inputSchemaHash.isNotBlank() &&
                outputSchemaHash.isNotBlank()
        ) {
            "Worker tool-card authority fields must not be blank"
        }
        require(version > 0) { "Worker tool-card version must be positive" }
        require(executionPurpose != ToolExecutionPurpose.PROMOTION_TEST) {
            "Worker tool cards cannot grant promotion-test authority"
        }
    }
}

/** Bounded typed repair projection for the exact assigned tool opportunity. */
@Serializable
data class WorkerToolRepairDiagnostic(
    val diagnosticId: String,
    val runId: String,
    val groupId: AtlasGroupId,
    val opportunityId: String,
    val opportunityCommitmentHash: String,
    val authoringNodeId: NodeId,
    val source: ToolRepairSource,
    val code: ToolRepairCode,
    val createdRound: Int,
    val visibleFromRound: Int,
    val affectedPublicField: String? = null,
    val publicQuantitySpec: QuantitySpec? = null,
    val remediationKey: String = ToolRepairRemediationDictionary.keyFor(code),
    val diagnosticHash: String
) {
    init {
        require(
            diagnosticId.isNotBlank() &&
                runId.isNotBlank() &&
                groupId.value.isNotBlank() &&
                opportunityId.isNotBlank() &&
                opportunityCommitmentHash.isNotBlank() &&
                authoringNodeId.value.isNotBlank()
        ) {
            "Worker tool-repair diagnostic authority fields must not be blank"
        }
        require(createdRound >= 0 && visibleFromRound > createdRound) {
            "Worker tool-repair diagnostic must be delayed to a later round"
        }
        require(remediationKey == ToolRepairRemediationDictionary.keyFor(code)) {
            "Worker repair diagnostics must use checked-in remediation dictionary keys"
        }
        require(diagnosticHash.matches(Regex("[0-9a-f]{64}"))) {
            "Worker tool-repair diagnostic hash is invalid"
        }
    }
}

/** Bounded predecessor note reference; note content remains retrieval-controlled. */
@Serializable
data class WorkerDeathNoteRef(
    val noteId: String,
    val predecessorAgentId: AtlasAgentId,
    val nodeId: NodeId?,
    val summary: String,
    val contentHash: String
)

/** Identity-redacted accepted shadow discovery available for later independent testing. */
@Serializable
data class VisibleShadowNodeCard(
    val proposalId: String,
    val proposalHash: String,
    val noveltyType: NoveltyType,
    val affectedVisibleNodeIds: Set<NodeId>,
    val proposedVector: String,
    val falsifiablePrediction: String,
    val minimalTestOrArtifact: String,
    val expectedReceiverImpact: String?,
    val screeningHash: String,
    val graderVerdictHash: String
)

/**
 * Complete context authority for one ordinary worker.
 *
 * The hash excludes [viewHash]. A worker view never contains the raw receiver contract, grader
 * field, Council role outputs, or hidden future payloads.
 */
@Serializable
data class WorkerView(
    val runId: String,
    val groupId: AtlasGroupId,
    val round: Int,
    val stageId: String,
    val visibleAtlasSkeleton: AtlasSkeletonView,
    val revealedNodes: List<RevealedNodeCard>,
    val assignedNode: RevealedNodeCard?,
    val groupFeedback: GroupFeedbackPacket?,
    val assignedToolSkeleton: ToolProjectSkeleton? = null,
    val assignedToolOpportunityOriginAtlasHash: String? = null,
    val assignedToolOpportunityCommitmentHash: String? = null,
    val assignedToolRepairDiagnostics: List<WorkerToolRepairDiagnostic> = emptyList(),
    val assignedToolRepairApproach: ApproachVector? = null,
    val allowedKnowledgeRefs: List<WorkerArtifactRef> = emptyList(),
    val availableToolCards: List<WorkerToolCard> = emptyList(),
    val predecessorNotes: List<WorkerDeathNoteRef> = emptyList(),
    val visibleShadowNodes: List<VisibleShadowNodeCard> = emptyList(),
    /** Group-local hypotheses promoted only as provisional artifacts. */
    val provisionalHypotheses: List<ProvisionalNovelHypothesis> = emptyList(),
    val forbiddenKnowledgeClasses: Set<VisibilityClass> = setOf(
        VisibilityClass.RECEIVER_PRIVATE,
        VisibilityClass.COUNCIL_PRIVATE,
        VisibilityClass.GRADER_PRIVATE,
        VisibilityClass.FUTURE_STAGE,
        VisibilityClass.OTHER_GROUP_PRIVATE
    ),
    val outlierMandate: String? = null,
    val viewHash: String = ""
) {
    init {
        assignedToolSkeleton?.let { skeleton ->
            val assignedOpportunity = requireNotNull(assignedNode?.toolOpportunity) {
                "Assigned tool skeleton requires an assigned Atlas tool opportunity"
            }
            val originAtlasHash = assignedToolOpportunityOriginAtlasHash
                ?: visibleAtlasSkeleton.atlasHash
            val commitment = ToolOpportunityCommitment(
                runId = runId,
                atlasHash = originAtlasHash,
                nodeId = requireNotNull(assignedNode).nodeId,
                opportunity = assignedOpportunity
            ).withComputedHash()
            val expectedSkeleton = ToolProjectSkeletonCompiler.compile(commitment)
            require(
                assignedToolOpportunityCommitmentHash == null ||
                    assignedToolOpportunityCommitmentHash == commitment.commitmentHash
            ) {
                "Assigned tool opportunity commitment hash does not match its origin authority"
            }
            require(
                skeleton == expectedSkeleton
            ) {
                "Assigned tool skeleton must match the exact assigned Atlas opportunity"
            }
        }
        require(
            assignedToolOpportunityOriginAtlasHash == null ||
                assignedToolOpportunityCommitmentHash != null
        ) {
            "Origin-version tool authority requires its commitment hash"
        }
        require(
            (
                assignedToolOpportunityOriginAtlasHash == null &&
                    assignedToolOpportunityCommitmentHash == null
                ) ||
                assignedToolSkeleton != null
        ) {
            "Assigned tool opportunity authority requires an assigned tool skeleton"
        }
        require(
            assignedToolRepairDiagnostics.isEmpty() ||
                assignedToolSkeleton != null
        ) {
            "Tool-repair diagnostics require an assigned tool opportunity"
        }
        require(assignedToolRepairDiagnostics.size <= 3) {
            "Worker tool-repair diagnostics exceed the bounded worker context"
        }
        require(assignedToolRepairDiagnostics.all { diagnostic ->
            val publicFields = assignedNode?.toolOpportunity
                ?.let { it.requiredInputs + it.requiredOutputs }
                ?.associateBy { it.name }
                .orEmpty()
            val affectedField = diagnostic.affectedPublicField?.let(publicFields::get)
            diagnostic.runId == runId &&
                diagnostic.groupId == groupId &&
                diagnostic.authoringNodeId == assignedNode?.nodeId &&
                diagnostic.opportunityId == assignedNode?.toolOpportunity?.opportunityId &&
                diagnostic.opportunityCommitmentHash ==
                assignedToolSkeleton?.opportunityCommitmentHash &&
                diagnostic.visibleFromRound <= round &&
                diagnostic.createdRound < round &&
                (
                    diagnostic.affectedPublicField == null ||
                        affectedField != null
                    ) &&
                (
                    diagnostic.publicQuantitySpec == null ||
                        affectedField?.quantitySpec == diagnostic.publicQuantitySpec
                    )
        }) {
            "Worker tool-repair diagnostics must match the exact prior assigned opportunity"
        }
        require(
            assignedToolRepairDiagnostics.map(WorkerToolRepairDiagnostic::diagnosticId)
                .distinct().size == assignedToolRepairDiagnostics.size
        ) {
            "Worker tool-repair diagnostics must have unique submissions"
        }
        require(
            (assignedToolRepairDiagnostics.isEmpty() &&
                assignedToolRepairApproach == null) ||
                (
                    assignedToolRepairDiagnostics.isNotEmpty() &&
                        assignedToolRepairApproach != null &&
                        assignedToolRepairApproach.hasValidHash() &&
                        assignedToolRepairApproach.runId == runId &&
                        assignedToolRepairApproach.groupId == groupId.value &&
                        assignedToolRepairApproach.opportunityId ==
                        assignedNode?.toolOpportunity?.opportunityId &&
                        assignedToolRepairApproach.round == round
                    )
        ) {
            "Worker tool-repair diagnostics require one exact sealed approach vector"
        }
        require(availableToolCards.isEmpty() || assignedNode != null) {
            "Promoted tool cards require an assigned Atlas node"
        }
        require(
            availableToolCards.all { it.requiredForNodeId == assignedNode?.nodeId } &&
                availableToolCards.map(WorkerToolCard::toolId).distinct().size ==
                availableToolCards.size &&
                availableToolCards.map(WorkerToolCard::opportunityId).distinct().size ==
                availableToolCards.size
        ) {
            "Worker tool cards must be unique and scoped to the exact assigned node"
        }
        require(
            provisionalHypotheses.map(ProvisionalNovelHypothesis::hypothesisId).distinct().size ==
                provisionalHypotheses.size &&
                provisionalHypotheses.map(ProvisionalNovelHypothesis::hypothesisId) ==
                provisionalHypotheses.map(ProvisionalNovelHypothesis::hypothesisId).sorted()
        ) {
            "Worker provisional hypotheses must be unique and deterministically ordered"
        }
        require(provisionalHypotheses.all { hypothesis ->
            hypothesis.runId == runId &&
                hypothesis.groupId == groupId &&
                hypothesis.round <= round &&
                hypothesis.hasValidHash()
        }) {
            "Worker provisional hypotheses must be same-run/group, historical, and hash-valid"
        }
    }

    /** Returns this view with its deterministic commitment attached. */
    fun withComputedHash(): WorkerView = copy(viewHash = AtlasHashing.hashWorkerView(this))

    /** Verifies the deterministic view commitment. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(viewHash, AtlasHashing.hashWorkerView(this))
}

/** One group's declared intent to work on a revealed node. */
@Serializable
data class NodeBid(
    val bidId: String,
    val groupId: AtlasGroupId,
    val nodeId: NodeId,
    val intendedArtifactType: ArtifactType,
    val estimatedCredits: Double,
    val expectedProofMethod: ProofMethod,
    val reason: String,
    val round: Int
)

/** Deterministic set of node bids selected for one group and round. */
@Serializable
data class NodePortfolio(
    val portfolioId: String,
    val runId: String,
    val atlasId: String,
    val atlasHash: String,
    val groupId: AtlasGroupId,
    val round: Int,
    val bids: List<NodeBid>,
    val diversityScore: Double,
    val portfolioHash: String = ""
) {
    /** Returns this portfolio with its deterministic commitment attached. */
    fun withComputedHash(): NodePortfolio =
        copy(portfolioHash = AtlasHashing.hashNodePortfolio(this))

    /** Verifies the deterministic portfolio commitment. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(portfolioHash, AtlasHashing.hashNodePortfolio(this))
}

/** Redacted identity of the Atlas lease assigned to one ordinary worker. */
@Serializable
data class WorkerLeaseAssignment(
    val leaseId: String,
    val nodeId: NodeId,
    val expiresAt: Instant,
    val replicationSlot: Int
)

/** Typed wall-clock lifetime and canonical checkpoint schedule for one mortal worker. */
@Serializable
data class AgentTtlPolicy(
    val lifetimeSeconds: Long,
    val deadline: Instant,
    val checkpointFractions: Map<TtlCheckpointKind, Double> = CANONICAL_CHECKPOINT_FRACTIONS
) {
    init {
        require(lifetimeSeconds > 0) { "Agent TTL lifetime must be positive" }
        require(checkpointFractions == CANONICAL_CHECKPOINT_FRACTIONS) {
            "Agent TTL checkpoints must use the canonical mortality schedule"
        }
    }

    companion object {
        val CANONICAL_CHECKPOINT_FRACTIONS = mapOf(
            TtlCheckpointKind.WORK_WARNING to 0.65,
            TtlCheckpointKind.COMMIT_SELECTION_REQUIRED to 0.80,
            TtlCheckpointKind.BROAD_EXPLORATION_DISABLED to 0.90,
            TtlCheckpointKind.DEATH_NOTE_GRACE to 0.95,
            TtlCheckpointKind.TERMINATED to 1.0
        )
    }
}

/** Ordinary-agent execution phases enforced by lifecycle state transitions. */
@Serializable
enum class AgentExecutionPhase {
    SPAWNED,
    ORIENTING,
    WORKING,
    COMMIT_REQUIRED,
    CONTRIBUTION_STAGED,
    NEGATIVE_RESULT_STAGED,
    NO_CONTRIBUTION,
    DEATH_NOTE_WINDOW,
    TERMINATED
}

/** Ordered TTL boundaries for ordinary mortal workers. */
@Serializable
enum class TtlCheckpointKind {
    WORK_WARNING,
    COMMIT_SELECTION_REQUIRED,
    BROAD_EXPLORATION_DISABLED,
    DEATH_NOTE_GRACE,
    TERMINATED
}

/** Whether an agent must be fully accounted before publication may proceed. */
@Serializable
enum class AgentLifecycleClassification {
    NON_DAEMON,
    DAEMON
}

/** Typed lifecycle duty carried independently from the worker's [Role]. */
@Serializable
enum class AgentLifecycleDuty {
    NONE,
    ARTIFACT_OWNER
}

/** Immutable registration of one successfully launched agent lifecycle. */
@Serializable
data class AgentLifecycleRegistration(
    val registrationId: String,
    val runId: String,
    val groupId: AtlasGroupId,
    val agentId: AtlasAgentId,
    val role: String,
    val generation: Int,
    val classification: AgentLifecycleClassification,
    val registeredAt: Instant,
    val registrationHash: String = "",
    val duty: AgentLifecycleDuty = AgentLifecycleDuty.NONE,
    val workspaceId: String? = null,
    val workspaceSourceHash: String? = null,
) {
    init {
        require(
            registrationId.isNotBlank() && runId.isNotBlank() &&
                groupId.value.isNotBlank() && agentId.value.isNotBlank() && role.isNotBlank()
        ) { "Agent lifecycle registration identity must not be blank" }
        require(generation >= 0) { "Agent lifecycle generation must not be negative" }
        require((workspaceId == null) == (workspaceSourceHash == null)) {
            "Workspace identity must contain both an id and a source hash"
        }
        require(workspaceId == null || workspaceId.isNotBlank()) {
            "Workspace identity must not contain a blank id"
        }
        require(workspaceSourceHash == null || workspaceSourceHash.isNotBlank()) {
            "Workspace identity must not contain a blank source hash"
        }
        if (duty == AgentLifecycleDuty.ARTIFACT_OWNER) {
            require(role.equals("Builder", ignoreCase = true)) {
                "Artifact-owner duty is reserved for Builder lifecycles"
            }
        }
    }

    /** Alias used by artifact-specific callers without duplicating serialized state. */
    val artifactWorkspaceId: String?
        get() = workspaceId

    /** Alias used by artifact-specific callers without duplicating serialized state. */
    val artifactWorkspaceSourceHash: String?
        get() = workspaceSourceHash

    /** Returns this registration with its deterministic self-hash. */
    fun withComputedHash(): AgentLifecycleRegistration =
        copy(registrationHash = AtlasHashing.hashAgentLifecycleRegistration(this))

    /** Verifies the exact immutable lifecycle registration. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(
            registrationHash,
            AtlasHashing.hashAgentLifecycleRegistration(this)
        )
}

/** Append-only observation of one agent crossing a TTL boundary. */
@Serializable
data class TtlCheckpointEvent(
    val eventId: String,
    val runId: String,
    val groupId: AtlasGroupId,
    val agentId: AtlasAgentId,
    val round: Int,
    val checkpoint: TtlCheckpointKind,
    val elapsedFraction: Double,
    val remainingBudget: Double,
    val phase: AgentExecutionPhase,
    val occurredAt: Instant
)

/** Durable contribution outcome selected before an ordinary agent exits. */
@Serializable
enum class CommitType {
    POSITIVE_RESULT,
    REUSABLE_TOOL,
    VALIDATION_RESULT,
    INTEGRATION_RESULT,
    FALSIFIED_HYPOTHESIS,
    STRUCTURED_DEAD_END,
    NOVELTY_PROPOSAL,
    NO_CONTRIBUTION
}

/** Typed, deduplicated handoff from one mortal agent to the persistent society. */
@Serializable
data class ContributionCommit(
    val commitId: String,
    val runId: String,
    val groupId: AtlasGroupId,
    val round: Int,
    val agentId: AtlasAgentId,
    val nodeId: NodeId?,
    val commitType: CommitType,
    val artifactIds: List<String>,
    val claimIds: List<String>,
    val citedArtifactIds: List<String> = emptyList(),
    val toolRunIds: List<String>,
    val testsRun: List<String>,
    val referencedNoveltyProposalIds: List<String> = emptyList(),
    val changedStateSummary: String,
    val evidenceSummary: String,
    val confidence: Double,
    val unresolvedRisks: List<String>,
    val successorRecommendations: List<String>,
    val costSummary: com.achulkov.evomolior.core.atlas.NodeEvidenceCostSummary? = null,
    val dedupeHash: String,
    val committedAt: Instant
) {
    /** Whether this commit can count as a useful mortal exit after acceptance. */
    val isPotentiallyUseful: Boolean
        get() = commitType != CommitType.NO_CONTRIBUTION
}

/** Dedicated record for runtime salvage that cannot count as a positive contribution. */
@Serializable
data class DegradedExecutionRecord(
    val recordId: String,
    val runId: String,
    val groupId: AtlasGroupId,
    val agentId: AtlasAgentId,
    val nodeId: NodeId?,
    val round: Int,
    val reasonCode: String,
    val detail: String,
    val artifactIds: List<String> = emptyList(),
    val occurredAt: Instant
)

/** Final ordinary-agent exit accounting record. */
@Serializable
data class AgentExitRecord(
    val exitId: String,
    val runId: String,
    val groupId: AtlasGroupId,
    val agentId: AtlasAgentId,
    val nodeId: NodeId?,
    val round: Int,
    val finalPhase: AgentExecutionPhase,
    val commitId: String?,
    val commitType: CommitType?,
    val acceptedContribution: Boolean,
    val acceptedStructuredDeadEnd: Boolean,
    val acceptedNoveltyProposal: Boolean,
    val degradedExecution: Boolean,
    val wasKilled: Boolean,
    val exitedAt: Instant
) {
    /** Whether this exit preserved an accepted useful contribution. */
    val useful: Boolean
        get() = acceptedContribution || acceptedStructuredDeadEnd || acceptedNoveltyProposal
}

/** Aggregate mortality metric for one run or group scope. */
@Serializable
data class UsefulExitSummary(
    val runId: String,
    val groupId: AtlasGroupId?,
    val usefulExitCount: Int,
    val ordinaryExitCount: Int
) {
    val usefulExitRate: Double
        get() = if (ordinaryExitCount == 0) 0.0 else usefulExitCount.toDouble() / ordinaryExitCount
}
