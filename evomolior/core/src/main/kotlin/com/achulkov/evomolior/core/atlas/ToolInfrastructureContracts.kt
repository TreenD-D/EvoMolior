@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.core.atlas

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.math.abs

private const val TOOL_FITNESS_TOLERANCE = 1e-9

/** Stable identity of one immutable promoted tool version. */
@JvmInline
@Serializable
value class ToolVersionId(val value: String)

/**
 * Atlas provenance commitment for one Council-authored tool opportunity.
 *
 * @param runId Owning run.
 * @param atlasHash Exact sealed Atlas version.
 * @param nodeId Atlas node carrying the opportunity.
 * @param opportunity Exact Council-authored contract.
 * @param commitmentHash Hash excluding this field.
 */
@Serializable
data class ToolOpportunityCommitment(
    val runId: String,
    val atlasHash: String,
    val nodeId: NodeId,
    val opportunity: ToolOpportunity,
    val commitmentHash: String = ""
) {
    init {
        require(runId.isNotBlank() && atlasHash.isNotBlank() && nodeId.value.isNotBlank()) {
            "Tool opportunity commitment provenance must not be blank"
        }
    }

    /** Returns this commitment with its deterministic self hash. */
    fun withComputedHash(): ToolOpportunityCommitment =
        copy(commitmentHash = AtlasHashing.hashToolOpportunityCommitment(this))

    /** Returns whether this commitment carries its exact deterministic hash. */
    fun hasValidHash(): Boolean = AtlasHashing.constantTimeEquals(
        commitmentHash,
        AtlasHashing.hashToolOpportunityCommitment(this)
    )
}

/** Append-only states in the reusable tool lifecycle. */
@Serializable
enum class ToolLifecycleState {
    TOOL_SPEC,
    IMPLEMENTED,
    STATIC_VALIDATED,
    SANDBOX_TESTED,
    CRITIC_ACCEPTED,
    PROMOTED,
    EXECUTED_BY_LATER_AGENT,
    RESULT_REFERENCED_BY_NODE,
    MAINTAINED,
    FORKED,
    RETIRED
}

/**
 * Typed immutable lineage for maintenance, fork, and retirement transitions.
 *
 * @param childToolVersionId Immutable child version for maintenance/fork, absent for retirement.
 * @param maintenanceCost Exact cost charged to this version transition.
 * @param reason Human-readable bounded transition rationale.
 */
@Serializable
data class ToolVersionTransitionDetails(
    val childToolVersionId: ToolVersionId? = null,
    val maintenanceCost: Double,
    val reason: String
) {
    init {
        require(maintenanceCost.isFinite() && maintenanceCost >= 0.0) {
            "Tool transition maintenance cost must be finite and non-negative"
        }
        require(reason.isNotBlank() && reason.length <= 2_000) {
            "Tool transition reason must be present and bounded"
        }
    }
}

/**
 * One immutable transition in a tool version's lifecycle.
 *
 * @param eventId Stable event identity.
 * @param runId Owning run.
 * @param opportunityId Council opportunity provenance.
 * @param toolVersionId Governed immutable version.
 * @param groupId Group owning the implementation.
 * @param originNodeId Atlas node which authored the immutable tool version.
 * @param contextNodeId Atlas node at which this transition occurred.
 * @param state New lifecycle state.
 * @param sequence Zero-based append order for this version.
 * @param previousEventHash Hash of the immediately preceding lifecycle event.
 * @param authorityReferenceIds Exact tests, executions, reviews, or evidence authorizing the state.
 * @param transitionDetails Typed child/cost/reason authority for terminal version transitions.
 * @param recordedAt Event time.
 * @param eventHash Hash excluding this field.
 */
@Serializable
data class ToolLifecycleEvent(
    val eventId: String,
    val runId: String,
    val opportunityId: String,
    val toolVersionId: ToolVersionId,
    val groupId: AtlasGroupId,
    val originNodeId: NodeId,
    val contextNodeId: NodeId,
    val state: ToolLifecycleState,
    val sequence: Long,
    val previousEventHash: String? = null,
    val authorityReferenceIds: Set<String> = emptySet(),
    val transitionDetails: ToolVersionTransitionDetails? = null,
    val recordedAt: Instant,
    val eventHash: String = ""
) {
    init {
        require(eventId.isNotBlank() && runId.isNotBlank() && opportunityId.isNotBlank()) {
            "Tool lifecycle identity fields must not be blank"
        }
        require(toolVersionId.value.isNotBlank() && groupId.value.isNotBlank() &&
            originNodeId.value.isNotBlank() && contextNodeId.value.isNotBlank()
        ) {
            "Tool lifecycle authority IDs must not be blank"
        }
        require(sequence >= 0) { "Tool lifecycle sequence must not be negative" }
        require(authorityReferenceIds.none(String::isBlank)) {
            "Tool lifecycle references must not be blank"
        }
        when (state) {
            ToolLifecycleState.MAINTAINED,
            ToolLifecycleState.FORKED -> require(
                transitionDetails?.childToolVersionId != null
            ) { "Maintenance and fork transitions require immutable child-version authority" }
            ToolLifecycleState.RETIRED -> require(
                transitionDetails != null && transitionDetails.childToolVersionId == null
            ) { "Retirement requires transition details without a child version" }
            else -> require(transitionDetails == null) {
                "Only maintenance, fork, and retirement may carry transition details"
            }
        }
    }

    /** Returns this event with its deterministic self hash. */
    fun withComputedHash(): ToolLifecycleEvent =
        copy(eventHash = AtlasHashing.hashToolLifecycleEvent(this))

    /** Returns whether this event carries its exact deterministic hash. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(eventHash, AtlasHashing.hashToolLifecycleEvent(this))
}

/** Validates append-only lifecycle adjacency without consulting mutable registry state. */
object ToolLifecyclePolicy {
    private val nextStates = mapOf(
        ToolLifecycleState.TOOL_SPEC to setOf(ToolLifecycleState.IMPLEMENTED),
        ToolLifecycleState.IMPLEMENTED to setOf(ToolLifecycleState.STATIC_VALIDATED),
        ToolLifecycleState.STATIC_VALIDATED to setOf(ToolLifecycleState.SANDBOX_TESTED),
        ToolLifecycleState.SANDBOX_TESTED to setOf(ToolLifecycleState.CRITIC_ACCEPTED),
        ToolLifecycleState.CRITIC_ACCEPTED to setOf(ToolLifecycleState.PROMOTED),
        ToolLifecycleState.PROMOTED to setOf(
            ToolLifecycleState.EXECUTED_BY_LATER_AGENT,
            ToolLifecycleState.MAINTAINED,
            ToolLifecycleState.FORKED,
            ToolLifecycleState.RETIRED
        ),
        ToolLifecycleState.EXECUTED_BY_LATER_AGENT to setOf(
            ToolLifecycleState.EXECUTED_BY_LATER_AGENT,
            ToolLifecycleState.RESULT_REFERENCED_BY_NODE,
            ToolLifecycleState.MAINTAINED,
            ToolLifecycleState.FORKED,
            ToolLifecycleState.RETIRED
        ),
        ToolLifecycleState.RESULT_REFERENCED_BY_NODE to setOf(
            ToolLifecycleState.EXECUTED_BY_LATER_AGENT,
            ToolLifecycleState.RESULT_REFERENCED_BY_NODE,
            ToolLifecycleState.MAINTAINED,
            ToolLifecycleState.FORKED,
            ToolLifecycleState.RETIRED
        )
    )

    /**
     * Checks that [next] is the first event or a legal hash-linked successor to [previous].
     *
     * @param previous Previous lifecycle event, or null for `TOOL_SPEC`.
     * @param next Candidate appended event.
     * @return Whether the transition is legal.
     */
    fun allows(previous: ToolLifecycleEvent?, next: ToolLifecycleEvent): Boolean {
        if (previous == null) {
            return next.state == ToolLifecycleState.TOOL_SPEC &&
                next.sequence == 0L &&
                next.previousEventHash == null
        }
        return previous.runId == next.runId &&
            previous.opportunityId == next.opportunityId &&
            previous.toolVersionId == next.toolVersionId &&
            previous.groupId == next.groupId &&
            previous.originNodeId == next.originNodeId &&
            next.sequence == previous.sequence + 1 &&
            next.previousEventHash == previous.eventHash &&
            next.state in nextStates[previous.state].orEmpty()
    }
}

/** Result class recorded by the bounded execution boundary. */
@Serializable
enum class ToolExecutionResult {
    SUCCEEDED,
    FAILED
}

/** Authority context for one bounded execution. */
@Serializable
enum class ToolExecutionPurpose {
    PROMOTION_TEST,
    VALIDATION_TEST,
    AGENT_USE
}

/**
 * Actor-scoped authority to persist one exact validation execution.
 *
 * This grant is compiled from a sealed-Atlas worker card by trusted production composition. It is
 * intentionally separate from persisted opportunity and lifecycle state so unrelated repository
 * callers receive no validation authority by default.
 *
 * @param runId Owning run.
 * @param groupId Group receiving the validation assignment.
 * @param toolVersionId Exact active promoted version.
 * @param opportunityId Council opportunity identity.
 * @param opportunityCommitmentHash Exact persisted opportunity commitment.
 * @param validationNodeId Exact sealed validation target.
 */
data class ToolValidationExecutionAuthority(
    val runId: String,
    val groupId: AtlasGroupId,
    val toolVersionId: ToolVersionId,
    val opportunityId: String,
    val opportunityCommitmentHash: String,
    val validationNodeId: NodeId
) {
    init {
        require(
            runId.isNotBlank() &&
                groupId.value.isNotBlank() &&
                toolVersionId.value.isNotBlank() &&
                opportunityId.isNotBlank() &&
                opportunityCommitmentHash.isNotBlank() &&
                validationNodeId.value.isNotBlank()
        ) {
            "Tool validation execution authority fields must not be blank"
        }
    }
}

/**
 * Returns the sealed downstream nodes authorized to validate one promoted tool.
 *
 * Validation authority is derived from Atlas topology rather than model-authored prose: the node
 * must be a direct validation child of the tool-opportunity node, require reproduction, and carry
 * executable tests. Expected-reuse nodes remain separate authority.
 *
 * @param originNodeId Atlas node carrying the tool opportunity.
 * @return Deterministically ordered validation-node identities.
 */
fun NodeAtlas.toolValidationNodeIds(originNodeId: NodeId): Set<NodeId> {
    val origin = requireNotNull(nodes[originNodeId]) {
        "Tool validation authority references an unknown Atlas origin node"
    }
    require(origin.toolOpportunity != null) {
        "Tool validation authority requires an Atlas tool-opportunity origin"
    }
    return nodes.values
        .asSequence()
        .filter { node ->
            node.kind == NodeKind.VALIDATION &&
                originNodeId in node.parentIds &&
                originNodeId in node.prerequisiteIds &&
                node.proofContract.method == ProofMethod.REPRODUCTION &&
                node.proofContract.executableTestIds.isNotEmpty()
        }
        .map(LimitationNode::id)
        .sortedBy(NodeId::value)
        .toCollection(linkedSetOf())
}

/** Stable, typed classification for a failed bounded execution. */
@Serializable
enum class ToolExecutionFailure {
    STATIC_VALIDATION,
    CAPABILITY_DENIED,
    INVALID_INPUT_SCHEMA,
    TIMEOUT,
    NON_ZERO_EXIT,
    OUTPUT_LIMIT_EXCEEDED,
    INVALID_OUTPUT_SCHEMA,
    SANDBOX_FAILURE
}

/**
 * Immutable authority emitted by the bounded tool execution boundary.
 *
 * @param executionId Boundary-generated execution identity.
 * @param runId Owning run.
 * @param toolVersionId Exact immutable tool version.
 * @param opportunityId Council opportunity provenance.
 * @param groupId Executing worker group.
 * @param agentId Executing agent.
 * @param round Execution round.
 * @param nodeId Assigned Atlas node.
 * @param purpose Whether this is a committed promotion test or later worker use.
 * @param inputHash Hash of exact validated input.
 * @param outputHash Hash of exact output when present.
 * @param outputJson Exact bounded JSON output when present.
 * @param outputArtifactId Durable output artifact when present.
 * @param startedAt Boundary start time.
 * @param completedAt Boundary completion time.
 * @param durationMillis Measured execution duration.
 * @param exitCode Child-process exit code when one exists.
 * @param result Success or failure.
 * @param failure Typed failure reason.
 * @param sandboxRecordHash Hash of exact bounded-process telemetry.
 * @param recordHash Hash excluding this field.
 */
@Serializable
data class ToolExecutionRecord(
    val executionId: String,
    val runId: String,
    val toolVersionId: ToolVersionId,
    val opportunityId: String,
    val groupId: AtlasGroupId,
    val agentId: AtlasAgentId,
    val round: Int,
    val nodeId: NodeId,
    val purpose: ToolExecutionPurpose,
    val inputHash: String,
    val outputHash: String? = null,
    val outputJson: JsonElement? = null,
    val outputArtifactId: String? = null,
    val startedAt: Instant,
    val completedAt: Instant,
    val durationMillis: Long,
    val exitCode: Int? = null,
    val result: ToolExecutionResult,
    val failure: ToolExecutionFailure? = null,
    val sandboxRecordHash: String,
    val recordHash: String = ""
) {
    init {
        require(executionId.isNotBlank() && runId.isNotBlank() && opportunityId.isNotBlank()) {
            "Tool execution identity fields must not be blank"
        }
        require(toolVersionId.value.isNotBlank() && groupId.value.isNotBlank() &&
            agentId.value.isNotBlank() && nodeId.value.isNotBlank()
        ) { "Tool execution authority IDs must not be blank" }
        require(round >= 0 && durationMillis >= 0) {
            "Tool execution round and duration must not be negative"
        }
        require(inputHash.isNotBlank() && sandboxRecordHash.isNotBlank()) {
            "Tool execution input and sandbox hashes must not be blank"
        }
        require(completedAt >= startedAt) { "Tool execution cannot complete before it starts" }
        require(
            (result == ToolExecutionResult.SUCCEEDED && failure == null &&
                outputHash?.isNotBlank() == true && outputJson != null &&
                outputArtifactId?.isNotBlank() == true) ||
                (result == ToolExecutionResult.FAILED && failure != null)
        ) { "Tool execution result, failure, and output authority are inconsistent" }
    }

    /** Returns this record with its deterministic self hash. */
    fun withComputedHash(): ToolExecutionRecord =
        copy(recordHash = AtlasHashing.hashToolExecutionRecord(this))

    /** Returns whether this record carries its exact deterministic hash. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(recordHash, AtlasHashing.hashToolExecutionRecord(this))
}

/**
 * Simulation-specific authority attached to one successful execution.
 *
 * @param simulationRunId Stable simulation run identity.
 * @param executionId Backing persisted successful execution.
 * @param toolVersionId Exact simulator version.
 * @param inputHash Exact input hash.
 * @param parameters Exact simulation parameters.
 * @param assumptions Explicit model assumptions.
 * @param randomSeed Seed for stochastic behavior; null only for deterministic simulations.
 * @param stochastic Whether the implementation is explicitly stochastic.
 * @param outputArtifactId Durable output artifact.
 * @param resultSummary Bounded result summary.
 * @param uncertainty Explicit uncertainty statement.
 * @param validationRefs Independent validation references.
 * @param executedBy Executing agent.
 * @param recordHash Hash excluding this field.
 */
@Serializable
data class SimulationRunRecord(
    val simulationRunId: String,
    val executionId: String,
    val toolVersionId: ToolVersionId,
    val inputHash: String,
    val parameters: Map<String, JsonElement>,
    val assumptions: List<String>,
    val randomSeed: Long?,
    val stochastic: Boolean,
    val outputArtifactId: String,
    val resultSummary: String,
    val uncertainty: String,
    val validationRefs: List<String>,
    val executedBy: AtlasAgentId,
    val recordHash: String = ""
) {
    init {
        require(simulationRunId.isNotBlank() && executionId.isNotBlank() &&
            toolVersionId.value.isNotBlank() && inputHash.isNotBlank()
        ) { "Simulation identity and input authority must not be blank" }
        require(parameters.isNotEmpty()) { "Simulation parameters must not be empty" }
        require(assumptions.isNotEmpty() && assumptions.none(String::isBlank)) {
            "Simulation assumptions must be explicit"
        }
        require(!stochastic || randomSeed != null) {
            "Stochastic simulations require an explicit random seed"
        }
        require(stochastic || randomSeed == null) {
            "Deterministic simulations must not claim a random seed"
        }
        require(outputArtifactId.isNotBlank() && resultSummary.isNotBlank() &&
            uncertainty.isNotBlank() && executedBy.value.isNotBlank()
        ) { "Simulation output authority must not be blank" }
        require(validationRefs.none(String::isBlank)) {
            "Simulation validation references must not be blank"
        }
    }

    /** Returns this record with its deterministic self hash. */
    fun withComputedHash(): SimulationRunRecord =
        copy(recordHash = AtlasHashing.hashSimulationRunRecord(this))

    /** Returns whether this record carries its exact deterministic hash. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(recordHash, AtlasHashing.hashSimulationRunRecord(this))
}

/**
 * Causal attribution from a successful exact-version execution to later-node evidence.
 *
 * @param lineageId Stable lineage identity.
 * @param runId Owning run.
 * @param toolVersionId Reused immutable version.
 * @param executionId Successful persisted execution.
 * @param originNodeId Node which authored the tool.
 * @param reusedByAgentId Later agent using it.
 * @param reusedOnNodeId Different downstream Atlas node.
 * @param contributionCommitId Contribution referencing the result.
 * @param nodeEvidencePacketHash Sealed packet containing the exact execution.
 * @param nodeScoreBefore Score before attributed evidence.
 * @param nodeScoreAfter Score after attributed evidence.
 * @param avoidedRepeatedCost Estimated cost avoided through reuse.
 * @param attributedAt Attribution time.
 * @param lineageHash Hash excluding this field.
 */
@Serializable
data class ToolReuseLineage(
    val lineageId: String,
    val runId: String,
    val toolVersionId: ToolVersionId,
    val executionId: String,
    val originNodeId: NodeId,
    val reusedByAgentId: AtlasAgentId,
    val reusedOnNodeId: NodeId,
    val contributionCommitId: String,
    val nodeEvidencePacketHash: String,
    val nodeScoreBefore: Double,
    val nodeScoreAfter: Double,
    val avoidedRepeatedCost: Double,
    val attributedAt: Instant,
    val lineageHash: String = ""
) {
    init {
        require(lineageId.isNotBlank() && runId.isNotBlank() && executionId.isNotBlank() &&
            toolVersionId.value.isNotBlank() && reusedByAgentId.value.isNotBlank()
        ) { "Tool reuse identity fields must not be blank" }
        require(originNodeId != reusedOnNodeId) { "Full tool reuse must occur on a later node" }
        require(contributionCommitId.isNotBlank() && nodeEvidencePacketHash.isNotBlank()) {
            "Tool reuse requires contribution and sealed evidence authority"
        }
        require(nodeScoreBefore.isFinite() && nodeScoreAfter.isFinite() &&
            avoidedRepeatedCost.isFinite() && avoidedRepeatedCost >= 0.0
        ) { "Tool reuse score and cost attribution must be finite and bounded" }
    }

    /** Returns this lineage with its deterministic self hash. */
    fun withComputedHash(): ToolReuseLineage =
        copy(lineageHash = AtlasHashing.hashToolReuseLineage(this))

    /** Returns whether this lineage carries its exact deterministic hash. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(lineageHash, AtlasHashing.hashToolReuseLineage(this))
}

/**
 * Delayed tool fitness derived from validation and exact downstream reuse authority.
 *
 * @param recordId Stable record identity.
 * @param runId Owning run.
 * @param toolVersionId Governed immutable version.
 * @param throughRound Inclusive derivation round.
 * @param validationPasses Successful committed promotion validations.
 * @param uniqueLaterAgentIds Unique later users.
 * @param successfulExecutionIds Successful exact-version runs.
 * @param reuseLineageIds Evidence-bound downstream reuse.
 * @param nodeScoreDeltaAttributedToResults Sum of evidence-attributed node score deltas.
 * @param avoidedRepeatedCost Cost avoided through exact-version reuse.
 * @param failureExecutionIds Failed exact-version executions.
 * @param maintenanceCost Version maintenance cost.
 * @param fitnessValue Deterministically derived fitness value.
 * @param recordHash Hash excluding this field.
 */
@Serializable
data class ToolFitnessRecord(
    val recordId: String,
    val runId: String,
    val toolVersionId: ToolVersionId,
    val throughRound: Int,
    val validationPasses: Int,
    val uniqueLaterAgentIds: Set<AtlasAgentId>,
    val successfulExecutionIds: Set<String>,
    val reuseLineageIds: Set<String>,
    val nodeScoreDeltaAttributedToResults: Double,
    val avoidedRepeatedCost: Double,
    val failureExecutionIds: Set<String>,
    val maintenanceCost: Double,
    val fitnessValue: Double,
    val recordHash: String = ""
) {
    init {
        require(recordId.isNotBlank() && runId.isNotBlank() && toolVersionId.value.isNotBlank()) {
            "Tool fitness identity fields must not be blank"
        }
        require(throughRound >= 0 && validationPasses >= 0) {
            "Tool fitness round and validation count must not be negative"
        }
        require(
            (successfulExecutionIds + reuseLineageIds + failureExecutionIds).none(String::isBlank)
        ) { "Tool fitness authority references must not be blank" }
        require(nodeScoreDeltaAttributedToResults.isFinite() &&
            avoidedRepeatedCost.isFinite() && avoidedRepeatedCost >= 0.0 &&
            maintenanceCost.isFinite() && maintenanceCost >= 0.0 &&
            fitnessValue.isFinite()
        ) { "Tool fitness values must be finite and costs must not be negative" }
        require(abs(fitnessValue - computedFitness()) <= TOOL_FITNESS_TOLERANCE) {
            "Tool fitness value must equal its exact component derivation"
        }
        require(reuseLineageIds.isNotEmpty() || fitnessValue <= validationPasses.toDouble()) {
            "Full tool fitness requires downstream reuse lineage"
        }
    }

    /** Returns this record with its deterministic self hash. */
    fun withComputedHash(): ToolFitnessRecord =
        copy(recordHash = AtlasHashing.hashToolFitnessRecord(this))

    /** Returns whether this record carries its exact deterministic hash. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(recordHash, AtlasHashing.hashToolFitnessRecord(this))

    /**
     * Derives the section-9 fitness formula from exact component authority.
     *
     * @return Deterministic fitness value.
     */
    fun computedFitness(): Double =
        validationPasses +
            uniqueLaterAgentIds.size +
            successfulExecutionIds.size +
            nodeScoreDeltaAttributedToResults +
            avoidedRepeatedCost -
            failureExecutionIds.size -
            maintenanceCost
}

/** Exact ratio whose unavailable value explicitly represents a zero denominator. */
@Serializable
data class ToolMetricRatio(
    val numerator: Int,
    val denominator: Int,
    val value: Double?
) {
    init {
        require(numerator >= 0 && denominator >= 0 && numerator <= denominator) {
            "Tool metric ratio counts are invalid"
        }
        require(
            (denominator == 0 && value == null) ||
                (denominator > 0 && value != null &&
                    abs(value - numerator.toDouble() / denominator) <= TOOL_FITNESS_TOLERANCE)
        ) { "Tool metric ratio must expose zero denominators as unavailable" }
    }

    companion object {
        /** Creates an exact ratio without fabricating a percentage for a zero denominator. */
        fun of(numerator: Int, denominator: Int): ToolMetricRatio =
            ToolMetricRatio(
                numerator = numerator,
                denominator = denominator,
                value = denominator.takeIf { it > 0 }?.let { numerator.toDouble() / it }
            )
    }
}

/** Read-only production metrics for Atlas-bound tool and simulation compounding. */
@Serializable
data class ToolInfrastructureMetrics(
    val runId: String,
    val groupId: AtlasGroupId,
    val opportunityCount: Int,
    val promotionAttemptCount: Int,
    val promotedVersionCount: Int,
    val failedPromotionExecutionCount: Int,
    val laterExecutionCount: Int,
    val successfulLaterExecutionCount: Int,
    val failedLaterExecutionCount: Int,
    val simulationRunCount: Int,
    val evidenceBoundReuseCount: Int,
    val evidenceBoundSimulationReuseCount: Int,
    val delayedFitnessVersionCount: Int,
    val promotionSuccessRate: ToolMetricRatio,
    val laterExecutionSuccessRate: ToolMetricRatio,
    val evidenceBoundReuseRate: ToolMetricRatio,
    val simulationInfluenceRate: ToolMetricRatio
) {
    init {
        require(runId.isNotBlank() && groupId.value.isNotBlank()) {
            "Tool metrics scope must not be blank"
        }
        require(
            listOf(
                opportunityCount,
                promotionAttemptCount,
                promotedVersionCount,
                failedPromotionExecutionCount,
                laterExecutionCount,
                successfulLaterExecutionCount,
                failedLaterExecutionCount,
                simulationRunCount,
                evidenceBoundReuseCount,
                evidenceBoundSimulationReuseCount,
                delayedFitnessVersionCount
            ).all { it >= 0 }
        ) { "Tool metric counts must not be negative" }
    }
}
