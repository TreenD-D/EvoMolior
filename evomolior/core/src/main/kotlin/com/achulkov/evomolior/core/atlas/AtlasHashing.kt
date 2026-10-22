package com.achulkov.evomolior.core.atlas

import com.achulkov.evomolior.core.contract.NodePortfolio
import com.achulkov.evomolior.core.contract.AgentLifecycleRegistration
import com.achulkov.evomolior.core.contract.AgentExitRecord
import com.achulkov.evomolior.core.contract.AgentRuntimeState
import com.achulkov.evomolior.core.contract.AgentRuntimeStateEvent
import com.achulkov.evomolior.core.contract.ContributionCommit
import com.achulkov.evomolior.core.contract.ContributionEdge
import com.achulkov.evomolior.core.contract.CriticDecisionRecord
import com.achulkov.evomolior.core.contract.CausalFitnessRecord
import com.achulkov.evomolior.core.contract.CostLedgerEntry
import com.achulkov.evomolior.core.contract.DownstreamUseRecord
import com.achulkov.evomolior.core.contract.IntegrationNodeRecord
import com.achulkov.evomolior.core.contract.FinalDeliverableReferenceRecord
import com.achulkov.evomolior.core.contract.ReceiverScoreContributionRecord
import com.achulkov.evomolior.core.contract.BenchmarkScoreContributionRecord
import com.achulkov.evomolior.core.contract.ControlInterventionRecord
import com.achulkov.evomolior.core.contract.SummaryValidationRecord
import com.achulkov.evomolior.core.contract.PublicationStateEvent
import com.achulkov.evomolior.core.contract.FinalCandidateRecord
import com.achulkov.evomolior.core.contract.WorkerView
import com.achulkov.evomolior.core.contract.GroupFeedbackPacket
import com.achulkov.evomolior.core.contract.ProvisionalNovelHypothesis
import com.achulkov.evomolior.core.grader.EvaluationIndependenceProfile
import com.achulkov.evomolior.core.grader.GraderAtlas
import com.achulkov.evomolior.core.grader.GraderAtlasNode
import com.achulkov.evomolior.core.grader.GraderCriteriaFreeze
import com.achulkov.evomolior.core.grader.GraderFitnessRecord
import com.achulkov.evomolior.core.grader.GraderNodeExecutionState
import com.achulkov.evomolior.core.grader.GraderNodeLease
import com.achulkov.evomolior.core.grader.GraderScoreBundle
import com.achulkov.evomolior.core.grader.GradingRequirement
import com.achulkov.evomolior.core.grader.HiddenTestDefinition
import com.achulkov.evomolior.core.kg.KgFactAttemptRecord
import java.security.MessageDigest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Deterministic canonical JSON and SHA-256 operations for Atlas contracts.
 *
 * Object keys are lexicographically sorted. Contract fields that represent mathematical sets are
 * normalized by their canonical JSON value before hashing.
 */
object AtlasHashing {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    /**
     * Hashes a receiver contract without normalizing its raw objective.
     *
     * @param contract Receiver contract to hash.
     * @return Lowercase SHA-256 hexadecimal digest.
     */
    fun hashReceiverContract(contract: ReceiverContract): String =
        hashSerializable(ReceiverContract.serializer(), contract)

    /**
     * Hashes an Atlas while excluding [NodeAtlas.atlasHash].
     *
     * @param atlas Atlas to hash.
     * @return Lowercase SHA-256 hexadecimal digest.
     */
    fun hashAtlas(atlas: NodeAtlas): String {
        val element = json.encodeToJsonElement(NodeAtlas.serializer(), atlas)
        val withoutSelfHash = JsonObject(element.jsonObject.filterKeys { it != "atlasHash" })
        return sha256(canonicalJson(withoutSelfHash))
    }

    /**
     * Hashes the separately committed hidden portion of a node.
     *
     * @param payload Hidden node payload.
     * @return Lowercase SHA-256 hexadecimal digest.
     */
    fun hashHiddenPayload(payload: HiddenNodePayload): String =
        hashSerializable(HiddenNodePayload.serializer(), payload)

    /** Hashes one frozen node capture policy. */
    fun hashCapturePolicy(policy: CapturePolicy): String =
        hashSerializable(CapturePolicy.serializer(), policy)

    /** Hashes the exact node-specific proof and deliverable grading criteria. */
    fun hashNodeGradingCriteria(node: LimitationNode): String =
        hashSerializable(
            NodeGradingCriteriaCommitment.serializer(),
            NodeGradingCriteriaCommitment(
                nodeKind = node.kind,
                proofContract = node.proofContract,
                deliverableContract = node.deliverableContract
            )
        )

    /**
     * Hashes node criteria together with the exact competitive grader freezes authorizing them.
     *
     * An empty freeze map retains the node-only commitment used by legacy/offline evaluation.
     *
     * @param node Node whose proof and deliverable criteria are committed.
     * @param graderCriteriaFreezeHashes Exact grader-group to criteria-freeze commitments.
     * @return Deterministic authority hash used by assignments, bundles, and capture replay.
     */
    fun hashNodeGradingAuthority(
        node: LimitationNode,
        graderCriteriaFreezeHashes: Map<String, String>
    ): String {
        val nodeCriteriaHash = hashNodeGradingCriteria(node)
        if (graderCriteriaFreezeHashes.isEmpty()) return nodeCriteriaHash
        return hashText(
            buildList {
                add(nodeCriteriaHash)
                graderCriteriaFreezeHashes.toSortedMap().forEach { (graderId, freezeHash) ->
                    add("$graderId:$freezeHash")
                }
            }.joinToString("|")
        )
    }

    /**
     * Hashes one complete execution-state snapshot.
     *
     * @param state State snapshot to commit.
     * @return Lowercase SHA-256 hexadecimal digest.
     */
    fun hashExecutionState(state: NodeExecutionState): String =
        hashSerializable(NodeExecutionState.serializer(), state)

    /**
     * Hashes a complete current execution-state plane independent of map insertion order.
     *
     * @param states Current state snapshots.
     * @return Lowercase SHA-256 hexadecimal digest.
     */
    fun hashExecutionStates(states: Collection<NodeExecutionState>): String =
        hashSerializable(
            ExecutionStateCommitment.serializer(),
            ExecutionStateCommitment(states.sortedBy { it.nodeId.value })
        )

    /** Hashes a node evidence packet while excluding its self-referential hash field. */
    fun hashNodeEvidencePacket(packet: NodeEvidencePacket): String =
        hashWithoutField(NodeEvidencePacket.serializer(), packet, "packetHash")

    /** Hashes the exact accepted-reference provenance manifest for one evidence packet. */
    fun hashNodeEvidenceProvenance(manifest: NodeEvidenceProvenanceManifest): String =
        hashSerializable(NodeEvidenceProvenanceManifest.serializer(), manifest)

    /** Hashes exact UTF-8 text without normalization. */
    fun hashText(value: String): String = sha256(value)

    /** Hashes one immutable agent exit authority. */
    fun hashAgentExitRecord(record: AgentExitRecord): String =
        hashSerializable(AgentExitRecord.serializer(), record)

    /** Hashes one runtime-state snapshot while excluding its self-referential hash. */
    fun hashAgentRuntimeState(state: AgentRuntimeState): String =
        hashWithoutField(AgentRuntimeState.serializer(), state, "stateHash")

    /** Hashes one runtime-state transition while excluding its self-referential hash. */
    fun hashAgentRuntimeStateEvent(event: AgentRuntimeStateEvent): String =
        hashWithoutField(AgentRuntimeStateEvent.serializer(), event, "eventHash")

    /** Hashes an exact node-grade bundle while excluding its self-referential hash field. */
    fun hashNodeGradeBundle(bundle: NodeGradeBundle): String =
        hashWithoutField(NodeGradeBundle.serializer(), bundle, "bundleHash")

    /** Hashes an exact node-grading assignment excluding its self-referential hash field. */
    fun hashNodeGradingAssignment(assignment: NodeGradingAssignment): String =
        hashWithoutField(NodeGradingAssignment.serializer(), assignment, "assignmentHash")

    /** Hashes one exact pre-grade evaluation context excluding its self-referential hash field. */
    fun hashExactNodeEvaluationContext(context: ExactNodeEvaluationContext): String =
        hashWithoutField(
            ExactNodeEvaluationContext.serializer(),
            context,
            "contextHash"
        )

    /** Hashes one independent node-grader response excluding its self-referential hash field. */
    fun hashNodeGraderResponse(response: NodeGraderResponse): String =
        hashWithoutField(NodeGraderResponse.serializer(), response, "responseHash")

    /** Hashes a capture decision while excluding its self-referential hash field. */
    fun hashNodeCaptureDecision(decision: NodeCaptureDecision): String =
        hashWithoutField(NodeCaptureDecision.serializer(), decision, "decisionHash")

    /** Hashes a yield constraint policy while excluding its self-referential hash field. */
    fun hashYieldConstraintPolicy(policy: YieldConstraintPolicy): String =
        hashWithoutField(YieldConstraintPolicy.serializer(), policy, "policyHash")

    /** Hashes a delayed yield schedule while excluding its self-referential hash field. */
    fun hashNodeYieldSchedule(schedule: NodeYieldSchedule): String =
        hashWithoutField(NodeYieldSchedule.serializer(), schedule, "scheduleHash")

    /** Hashes a yield application while excluding its self-referential hash field. */
    fun hashNodeYieldApplication(application: NodeYieldApplication): String =
        hashWithoutField(NodeYieldApplication.serializer(), application, "applicationHash")

    /** Hashes a reward-use event while excluding its self-referential hash field. */
    fun hashRewardUseLineageEvent(event: RewardUseLineageEvent): String =
        hashWithoutField(RewardUseLineageEvent.serializer(), event, "eventHash")

    /** Hashes one Grader Atlas node excluding its self-referential payload hash. */
    fun hashGraderAtlasNode(node: GraderAtlasNode): String =
        hashWithoutField(GraderAtlasNode.serializer(), node, "payloadHash")

    /** Hashes one complete Grader Atlas excluding its self-referential hash. */
    fun hashGraderAtlas(atlas: GraderAtlas): String =
        hashWithoutField(GraderAtlas.serializer(), atlas, "atlasHash")

    /** Hashes one Grader Atlas execution state excluding its self-referential hash. */
    fun hashGraderNodeExecutionState(state: GraderNodeExecutionState): String =
        hashWithoutField(GraderNodeExecutionState.serializer(), state, "stateHash")

    /** Hashes one grader-node lease excluding its self-referential hash. */
    fun hashGraderNodeLease(lease: GraderNodeLease): String =
        hashWithoutField(GraderNodeLease.serializer(), lease, "leaseHash")

    /** Hashes one typed grading requirement excluding its self-referential hash. */
    fun hashGradingRequirement(requirement: GradingRequirement): String =
        hashWithoutField(GradingRequirement.serializer(), requirement, "requirementHash")

    /** Hashes one hidden-test definition excluding its self-referential hash. */
    fun hashHiddenTestDefinition(test: HiddenTestDefinition): String =
        hashWithoutField(HiddenTestDefinition.serializer(), test, "testHash")

    /** Hashes one criteria freeze excluding its self-referential hash. */
    fun hashGraderCriteriaFreeze(freeze: GraderCriteriaFreeze): String =
        hashWithoutField(GraderCriteriaFreeze.serializer(), freeze, "freezeHash")

    /** Hashes one exact grader score bundle excluding its self-referential hash. */
    fun hashGraderScoreBundle(bundle: GraderScoreBundle): String =
        hashWithoutField(GraderScoreBundle.serializer(), bundle, "payloadHash")

    /** Hashes one grader-fitness record excluding its self-referential hash. */
    fun hashGraderFitnessRecord(record: GraderFitnessRecord): String =
        hashWithoutField(GraderFitnessRecord.serializer(), record, "recordHash")

    /** Hashes one evaluation-independence profile excluding its self-referential hash. */
    fun hashEvaluationIndependenceProfile(profile: EvaluationIndependenceProfile): String =
        hashWithoutField(
            EvaluationIndependenceProfile.serializer(),
            profile,
            "profileHash"
        )

    /** Hashes a worker view while excluding its self-referential hash field. */
    fun hashWorkerView(view: WorkerView): String {
        val element = json.encodeToJsonElement(WorkerView.serializer(), view)
        return sha256(canonicalJson(JsonObject(element.jsonObject.filterKeys { it != "viewHash" })))
    }

    /** Hashes redacted worker feedback excluding its self-referential hash. */
    fun hashGroupFeedbackPacket(packet: GroupFeedbackPacket): String =
        hashWithoutField(GroupFeedbackPacket.serializer(), packet, "feedbackHash")

    /** Hashes a node portfolio while excluding its self-referential hash field. */
    fun hashNodePortfolio(portfolio: NodePortfolio): String {
        val element = json.encodeToJsonElement(NodePortfolio.serializer(), portfolio)
        return sha256(
            canonicalJson(JsonObject(element.jsonObject.filterKeys { it != "portfolioHash" }))
        )
    }

    /** Hashes one immutable KG fact-attempt authority record excluding its self-hash. */
    fun hashKgFactAttempt(record: KgFactAttemptRecord): String =
        hashWithoutField(KgFactAttemptRecord.serializer(), record, "recordHash")

    /**
     * Hashes the semantic content of a contribution independently of its persisted identity/time.
     *
     * @param commit Contribution to deduplicate.
     * @return Stable lowercase digest.
     */
    fun hashContributionDedupe(commit: ContributionCommit): String {
        val element = json.encodeToJsonElement(ContributionCommit.serializer(), commit)
        val semantic = JsonObject(
            element.jsonObject.filterKeys {
                it != "commitId" && it != "dedupeHash" && it != "committedAt"
            }
        )
        return sha256(canonicalJson(semantic))
    }

    /** Hashes one immutable agent lifecycle registration excluding its self-hash. */
    fun hashAgentLifecycleRegistration(value: AgentLifecycleRegistration): String =
        hashWithoutFields(
            AgentLifecycleRegistration.serializer(),
            value,
            if (
                value.duty == com.achulkov.evomolior.core.contract.AgentLifecycleDuty.NONE &&
                value.workspaceId == null &&
                value.workspaceSourceHash == null
            ) {
                setOf("registrationHash", "duty", "workspaceId", "workspaceSourceHash")
            } else {
                setOf("registrationHash")
            }
        )

    /** Hashes one publication transition excluding its self-hash. */
    fun hashPublicationStateEvent(value: PublicationStateEvent): String =
        hashWithoutField(PublicationStateEvent.serializer(), value, "eventHash")

    /** Hashes one exact final candidate excluding its self-hash. */
    fun hashFinalCandidate(value: FinalCandidateRecord): String =
        hashWithoutField(FinalCandidateRecord.serializer(), value, "recordHash")

    /** Hashes one causal contribution edge while excluding its self-referential hash. */
    fun hashContributionEdge(edge: ContributionEdge): String =
        hashWithoutField(ContributionEdge.serializer(), edge, "edgeHash")

    /** Hashes one immutable downstream read/citation record excluding its self-hash. */
    fun hashDownstreamUseRecord(record: DownstreamUseRecord): String =
        hashWithoutField(DownstreamUseRecord.serializer(), record, "recordHash")

    /** Hashes one immutable integration-node record excluding its self-hash. */
    fun hashIntegrationNodeRecord(record: IntegrationNodeRecord): String =
        hashWithoutField(IntegrationNodeRecord.serializer(), record, "recordHash")

    /**
     * Hashes one immutable final-output reference excluding its self-hash.
     *
     * Null component IDs retain the pre-component wire hash so historical authority remains
     * verifiable; current component-level records commit the new field.
     */
    fun hashFinalDeliverableReference(record: FinalDeliverableReferenceRecord): String =
        hashWithoutFields(
            FinalDeliverableReferenceRecord.serializer(),
            record,
            if (record.componentId == null) {
                setOf("recordHash", "componentId")
            } else {
                setOf("recordHash")
            }
        )

    /** Hashes one exact receiver-score component excluding its self-hash. */
    fun hashReceiverScoreContribution(record: ReceiverScoreContributionRecord): String =
        hashWithoutField(ReceiverScoreContributionRecord.serializer(), record, "recordHash")

    /** Hashes one exact benchmark-score component excluding its self-hash. */
    fun hashBenchmarkScoreContribution(record: BenchmarkScoreContributionRecord): String =
        hashWithoutField(BenchmarkScoreContributionRecord.serializer(), record, "recordHash")

    /** Hashes one exact control intervention excluding its self-hash. */
    fun hashControlIntervention(record: ControlInterventionRecord): String =
        hashWithoutField(ControlInterventionRecord.serializer(), record, "recordHash")

    /** Hashes one exact summary validation excluding its self-hash. */
    fun hashSummaryValidation(record: SummaryValidationRecord): String =
        hashWithoutField(SummaryValidationRecord.serializer(), record, "recordHash")

    /** Hashes one final Critic decision while excluding its self-referential hash. */
    fun hashCriticDecision(decision: CriticDecisionRecord): String =
        hashWithoutField(CriticDecisionRecord.serializer(), decision, "decisionHash")

    /** Hashes one plane-separated causal fitness record excluding its self-hash. */
    fun hashCausalFitnessRecord(record: CausalFitnessRecord): String =
        hashWithoutField(CausalFitnessRecord.serializer(), record, "recordHash")

    /** Hashes one provider/tool cost lifecycle event excluding its self-hash. */
    fun hashCostLedgerEntry(entry: CostLedgerEntry): String =
        hashWithoutField(CostLedgerEntry.serializer(), entry, "entryHash")

    /**
     * Hashes the ordered disclosure-stage identity and its set of committed node payload hashes.
     *
     * @param stageId Stable disclosure stage ID.
     * @param nodeIds Nodes disclosed by the stage.
     * @param nodes Atlas nodes containing the committed hidden-payload hashes.
     * @return Lowercase SHA-256 hexadecimal digest.
     * @throws IllegalArgumentException when a stage references an unknown node.
     */
    fun hashDisclosurePayload(
        stageId: String,
        nodeIds: Set<NodeId>,
        nodes: Map<NodeId, LimitationNode>
    ): String {
        val payloadHashes = nodeIds.associate { nodeId ->
            nodeId.value to requireNotNull(nodes[nodeId]) {
                "Disclosure stage $stageId references unknown node ${nodeId.value}"
            }.payloadHash
        }
        return hashSerializable(
            DisclosurePayloadCommitment.serializer(),
            DisclosurePayloadCommitment(stageId, payloadHashes)
        )
    }

    /** Hashes an exact progressive-disclosure decision excluding its self-hash. */
    fun hashDisclosureDecision(value: DisclosureDecision): String =
        hashWithoutField(DisclosureDecision.serializer(), value, "decisionHash")

    /** Hashes one exact group-local reveal payload excluding its self-hash. */
    fun hashGroupRevealPayload(value: GroupRevealPayload): String =
        hashWithoutField(GroupRevealPayload.serializer(), value, "payloadHash")

    /** Hashes one disclosure reveal authorization excluding its self-hash. */
    fun hashDisclosureRevealAuthorization(value: DisclosureRevealAuthorization): String =
        hashWithoutField(
            DisclosureRevealAuthorization.serializer(),
            value,
            "authorizationHash"
        )

    /** Hashes one three-way disclosure feedback split excluding its self-hash. */
    fun hashDisclosureFeedbackSplit(value: DisclosureFeedbackSplit): String =
        hashWithoutField(DisclosureFeedbackSplit.serializer(), value, "splitHash")

    /** Hashes one optional adjacent-node disclosure token excluding its self-hash. */
    fun hashAdjacentDisclosureToken(value: AdjacentDisclosureToken): String =
        hashWithoutField(AdjacentDisclosureToken.serializer(), value, "tokenHash")

    fun hashNoveltyVectorProposal(value: NoveltyVectorProposal): String =
        hashWithoutField(NoveltyVectorProposal.serializer(), value, "proposalHash")

    fun hashNoveltyScreeningResult(value: NoveltyScreeningResult): String =
        hashWithoutField(NoveltyScreeningResult.serializer(), value, "screeningHash")

    fun hashNoveltyGraderAssessment(value: NoveltyGraderAssessment): String =
        hashWithoutField(NoveltyGraderAssessment.serializer(), value, "assessmentHash")

    fun hashShadowNodeGraderVerdict(value: ShadowNodeGraderVerdict): String =
        hashWithoutField(ShadowNodeGraderVerdict.serializer(), value, "verdictHash")

    fun hashShadowNodeLifecycleEvent(value: ShadowNodeLifecycleEvent): String =
        hashWithoutField(ShadowNodeLifecycleEvent.serializer(), value, "eventHash")

    fun hashOutlierDownstreamLineage(value: OutlierDownstreamLineage): String =
        hashWithoutField(OutlierDownstreamLineage.serializer(), value, "lineageHash")

    fun hashOutlierFitnessRecord(value: OutlierFitnessRecord): String =
        hashWithoutField(OutlierFitnessRecord.serializer(), value, "recordHash")

    /** Hashes one provisional novel hypothesis excluding its self-referential hash. */
    fun hashProvisionalNovelHypothesis(value: ProvisionalNovelHypothesis): String =
        hashWithoutField(
            ProvisionalNovelHypothesis.serializer(),
            value,
            "hypothesisHash"
        )

    /** Hashes Atlas provenance for a tool opportunity excluding its self hash. */
    fun hashToolOpportunityCommitment(value: ToolOpportunityCommitment): String =
        hashWithoutField(
            ToolOpportunityCommitment.serializer(),
            value,
            "commitmentHash"
        )

    /** Hashes a tool lifecycle event excluding its self-referential hash. */
    fun hashToolLifecycleEvent(value: ToolLifecycleEvent): String =
        hashWithoutField(ToolLifecycleEvent.serializer(), value, "eventHash")

    /** Hashes a bounded tool execution record excluding its self-referential hash. */
    fun hashToolExecutionRecord(value: ToolExecutionRecord): String =
        hashWithoutField(ToolExecutionRecord.serializer(), value, "recordHash")

    /** Hashes a simulation run record excluding its self-referential hash. */
    fun hashSimulationRunRecord(value: SimulationRunRecord): String =
        hashWithoutField(SimulationRunRecord.serializer(), value, "recordHash")

    /** Hashes exact downstream tool reuse excluding its self-referential hash. */
    fun hashToolReuseLineage(value: ToolReuseLineage): String =
        hashWithoutField(ToolReuseLineage.serializer(), value, "lineageHash")

    /** Hashes delayed tool fitness excluding its self-referential hash. */
    fun hashToolFitnessRecord(value: ToolFitnessRecord): String =
        hashWithoutField(ToolFitnessRecord.serializer(), value, "recordHash")

    /** Hashes one generated atomic tool skeleton excluding its self hash. */
    fun hashToolProjectSkeleton(value: ToolProjectSkeleton): String =
        hashWithoutField(ToolProjectSkeleton.serializer(), value, "skeletonHash")

    /**
     * Produces canonical JSON for a JSON element.
     *
     * @param element Element to canonicalize.
     * @return Whitespace-free canonical JSON.
     */
    fun canonicalJson(element: JsonElement): String = canonicalize(element).toString()

    /**
     * Compares hash strings without content-dependent early exit.
     *
     * @param expected Expected lowercase hexadecimal hash.
     * @param actual Actual lowercase hexadecimal hash.
     * @return Whether the hashes are byte-for-byte equal.
     */
    fun constantTimeEquals(expected: String, actual: String): Boolean =
        MessageDigest.isEqual(expected.encodeToByteArray(), actual.encodeToByteArray())

    private fun <T> hashSerializable(serializer: KSerializer<T>, value: T): String =
        sha256(canonicalJson(json.encodeToJsonElement(serializer, value)))

    private fun <T> hashWithoutField(
        serializer: KSerializer<T>,
        value: T,
        selfHashField: String
    ): String = hashWithoutFields(serializer, value, setOf(selfHashField))

    private fun <T> hashWithoutFields(
        serializer: KSerializer<T>,
        value: T,
        excludedFields: Set<String>
    ): String {
        val element = json.encodeToJsonElement(serializer, value)
        return sha256(
            canonicalJson(JsonObject(element.jsonObject.filterKeys { it !in excludedFields }))
        )
    }

    private fun canonicalize(element: JsonElement, fieldName: String? = null): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries
                .sortedBy { it.key }
                .associate { (key, value) -> key to canonicalize(value, key) }
        )
        is JsonArray -> {
            val values = element.map { canonicalize(it) }
            JsonArray(if (fieldName in SET_LIKE_FIELDS) values.sortedBy(JsonElement::toString) else values)
        }
        is JsonPrimitive -> element
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private val SET_LIKE_FIELDS = setOf(
        "rootNodeIds", "parentIds", "prerequisiteIds", "alternativeToIds",
        "requiredSections", "acceptedEvidenceKinds", "requiredSourceDomains",
        "acceptanceCriteria", "requiredEvidenceKinds", "executableTestIds",
        "suggestedSourceDomains",
        "acceptedArtifactTypes", "requiredFields", "mediaTypes",
        "capabilityYields", "requiredCapabilities", "promotionTestIds",
        "expectedReuseNodeIds", "enumValues", "validationRules",
        "authorityReferenceIds", "uniqueLaterAgentIds", "successfulExecutionIds",
        "reuseLineageIds", "failureExecutionIds",
        "nodeIds", "mandatoryNodeIds", "revealedToGroups", "graderIds",
        "forbiddenKnowledgeClasses", "failedVisibleNodeIds",
        "acceptedArtifactIds", "acceptedClaimIds", "toolRunIds", "testResultIds",
        "citedArtifactIds",
        "negativeResultIds", "completedGraderIds", "graderResponseHashes",
        "candidateBundleHashes", "authoritativeBundleHashes", "resultingOwnerGroupIds",
        "downstreamArtifactIds", "rewards", "appliedRewards", "entries"
    )
}

/**
 * Canonical disclosure commitment containing no hidden node content.
 *
 * @param stageId Stable disclosure stage ID.
 * @param nodePayloadHashes Hidden-payload hashes keyed by node ID.
 */
@kotlinx.serialization.Serializable
data class DisclosurePayloadCommitment(
    val stageId: String,
    val nodePayloadHashes: Map<String, String>
)

/**
 * Canonically ordered execution-state plane used for transaction commitments.
 */
@kotlinx.serialization.Serializable
private data class ExecutionStateCommitment(
    val states: List<NodeExecutionState>
)

/** Exact node criteria frozen before worker reveal. */
@kotlinx.serialization.Serializable
private data class NodeGradingCriteriaCommitment(
    val nodeKind: NodeKind,
    val proofContract: ProofContract,
    val deliverableContract: NodeDeliverableContract
)
