package com.achulkov.evomolior.core.atlas

import kotlinx.serialization.Serializable

/** Supported classes of falsifiable Outlier novelty. */
@Serializable
enum class NoveltyType {
    MISSING_STAKEHOLDER,
    HIDDEN_FAILURE_MODE,
    ALTERNATIVE_MECHANISM,
    ALTERNATIVE_REPRESENTATION,
    CROSS_DOMAIN_ANALOGY,
    TOOL_OR_SIMULATION_LAYER,
    ADVERSARIAL_ATTACK,
    SIMPLIFICATION,
    NEW_EVIDENCE_SOURCE_CLASS,
    MISSING_INTEGRATION_DEPENDENCY
}

/**
 * Typed novelty proposal authored by one taskless, Legacy-free Outlier.
 */
@Serializable
data class NoveltyVectorProposal(
    val proposalId: String,
    val runId: String,
    val atlasHash: String,
    val groupId: AtlasGroupId,
    val round: Int,
    val proposerAgentId: AtlasAgentId,
    val noveltyType: NoveltyType,
    val affectedVisibleNodeIds: Set<NodeId>,
    val missingLayerOrAssumption: String,
    val proposedVector: String,
    val whyCurrentMapMayMissIt: String,
    val falsifiablePrediction: String,
    val minimalTestOrArtifact: String,
    val expectedReceiverImpact: String? = null,
    val expectedCost: Double,
    val conflictWithExistingAssumptions: List<String> = emptyList(),
    val noveltyFingerprint: String,
    val proposalHash: String = ""
) {
    init {
        require(round >= 0) { "Novelty proposal round cannot be negative" }
        require(affectedVisibleNodeIds.isNotEmpty()) {
            "Novelty proposal must connect to a visible limitation"
        }
        require(missingLayerOrAssumption.isNotBlank() &&
            proposedVector.isNotBlank() &&
            whyCurrentMapMayMissIt.isNotBlank() &&
            falsifiablePrediction.isNotBlank() &&
            minimalTestOrArtifact.isNotBlank()
        ) {
            "Novelty proposal requires a connected vector and falsifiable test"
        }
        require(expectedCost.isFinite() && expectedCost >= 0.0) {
            "Novelty proposal cost must be finite and non-negative"
        }
        require(noveltyFingerprint.isNotBlank()) {
            "Novelty proposal fingerprint cannot be blank"
        }
    }

    /** Returns this proposal with its deterministic commitment attached. */
    fun withComputedHash(): NoveltyVectorProposal =
        copy(proposalHash = AtlasHashing.hashNoveltyVectorProposal(this))

    /** Verifies the proposal commitment. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(proposalHash, AtlasHashing.hashNoveltyVectorProposal(this))
}

/** Fail-closed duplicate and hidden-map screening result. */
@Serializable
enum class NoveltyScreeningStatus {
    UNIQUE,
    DUPLICATE_REVEALED,
    DUPLICATE_CANON,
    DISCONNECTED,
    UNTESTABLE,
    COST_UNJUSTIFIED,
    HIDDEN_INFORMATION_DEPENDENCY
}

/** Sealed hidden-map anticipation classification containing no hidden node identity. */
@Serializable
enum class HiddenMapAnticipation {
    NONE,
    PARTIAL,
    DIRECT
}

/**
 * Exact novelty screening result. Hidden comparison details remain committed but undisclosed.
 */
@Serializable
data class NoveltyScreeningResult(
    val screeningId: String,
    val proposalHash: String,
    val status: NoveltyScreeningStatus,
    val revealedDuplicateNodeIds: Set<NodeId> = emptySet(),
    val canonDuplicateArtifactIds: Set<String> = emptySet(),
    val hiddenMapAnticipation: HiddenMapAnticipation = HiddenMapAnticipation.NONE,
    val sealedHiddenComparisonHash: String,
    val reason: String,
    val screeningHash: String = ""
) {
    /** Returns this result with its deterministic commitment attached. */
    fun withComputedHash(): NoveltyScreeningResult =
        copy(screeningHash = AtlasHashing.hashNoveltyScreeningResult(this))

    /** Verifies the screening commitment. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(screeningHash, AtlasHashing.hashNoveltyScreeningResult(this))
}

/** Append-only shadow-node lifecycle. */
@Serializable
enum class ShadowNodeState {
    PROPOSED,
    DUPLICATE_CHECKED,
    SHADOW_NODE,
    TESTED,
    ACCEPTED_AMENDMENT,
    REJECTED,
    DEFERRED
}

/** Independent grader quality vector for one shadow-node proposal. */
@Serializable
data class NoveltyGraderVector(
    val novelty: Double,
    val receiverRelevance: Double,
    val testability: Double,
    val plausibility: Double,
    val expectedImpact: Double,
    val costDiscipline: Double,
    val downstreamAdoption: Double? = null
) {
    init {
        val values = listOf(
            novelty,
            receiverRelevance,
            testability,
            plausibility,
            expectedImpact,
            costDiscipline
        ) + listOfNotNull(downstreamAdoption)
        require(values.all { it.isFinite() && it in 0.0..1.0 }) {
            "Novelty grader dimensions must be normalized"
        }
    }
}

/** Exact status of one independent novelty assessment. */
@Serializable
enum class NoveltyAssessmentStatus { VALID, INVALID, ABSTAINED }

/**
 * One independent grader group's proposal assessment.
 */
@Serializable
data class NoveltyGraderAssessment(
    val assessmentId: String,
    val proposalHash: String,
    val graderGroupId: String,
    val status: NoveltyAssessmentStatus,
    val vector: NoveltyGraderVector? = null,
    val rationaleHash: String,
    val assessmentHash: String = ""
) {
    init {
        require(
            (status == NoveltyAssessmentStatus.VALID && vector != null) ||
                (status != NoveltyAssessmentStatus.VALID && vector == null)
        ) {
            "Only valid novelty assessments may carry a complete score vector"
        }
        require(rationaleHash.isNotBlank()) {
            "Novelty assessment rationale commitment cannot be blank"
        }
    }

    /** Returns this assessment with its deterministic commitment attached. */
    fun withComputedHash(): NoveltyGraderAssessment =
        copy(assessmentHash = AtlasHashing.hashNoveltyGraderAssessment(this))

    /** Verifies the assessment commitment. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(assessmentHash, AtlasHashing.hashNoveltyGraderAssessment(this))
}

/** Exact quorum decision for one proposal. */
@Serializable
enum class ShadowNodeDecision { ACCEPT, REJECT, DEFER, INVALID }

@Serializable
data class ShadowNodeGraderVerdict(
    val proposalHash: String,
    val decision: ShadowNodeDecision,
    val aggregateVector: NoveltyGraderVector?,
    val assessmentHashes: Set<String>,
    val requiredQuorum: Int,
    val reason: String,
    val verdictHash: String = ""
) {
    init {
        require(requiredQuorum > 0) { "Shadow-node grader quorum must be positive" }
        require(reason.isNotBlank()) { "Shadow-node grader verdict requires a reason" }
        require(decision != ShadowNodeDecision.ACCEPT || aggregateVector != null) {
            "Accepted shadow-node verdict requires an aggregate score vector"
        }
        require(
            decision !in setOf(ShadowNodeDecision.DEFER, ShadowNodeDecision.INVALID) ||
                aggregateVector == null
        ) {
            "Shadow-node verdict vector does not match its decision"
        }
    }

    /** Returns this verdict with its deterministic commitment attached. */
    fun withComputedHash(): ShadowNodeGraderVerdict =
        copy(verdictHash = AtlasHashing.hashShadowNodeGraderVerdict(this))

    /** Verifies the verdict commitment. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(verdictHash, AtlasHashing.hashShadowNodeGraderVerdict(this))
}

/**
 * One append-only lifecycle event for a proposal/shadow node.
 */
@Serializable
data class ShadowNodeLifecycleEvent(
    val eventId: String,
    val proposalId: String,
    val proposalHash: String,
    val runId: String,
    val sequence: Long,
    val previousEventHash: String?,
    val fromState: ShadowNodeState?,
    val toState: ShadowNodeState,
    val round: Int,
    val screeningHash: String? = null,
    val graderVerdictHash: String? = null,
    val amendmentId: String? = null,
    val evidenceArtifactIds: Set<String> = emptySet(),
    val eventHash: String = ""
) {
    /** Returns this event with its deterministic commitment attached. */
    fun withComputedHash(): ShadowNodeLifecycleEvent =
        copy(eventHash = AtlasHashing.hashShadowNodeLifecycleEvent(this))

    /** Verifies the event commitment. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(eventHash, AtlasHashing.hashShadowNodeLifecycleEvent(this))
}

/** Validates the append-only shadow-node state machine and event hash chain. */
object ShadowNodeLifecycleValidator {
    private val transitions = mapOf(
        ShadowNodeState.PROPOSED to setOf(ShadowNodeState.DUPLICATE_CHECKED),
        ShadowNodeState.DUPLICATE_CHECKED to setOf(
            ShadowNodeState.SHADOW_NODE,
            ShadowNodeState.REJECTED,
            ShadowNodeState.DEFERRED
        ),
        ShadowNodeState.SHADOW_NODE to setOf(
            ShadowNodeState.TESTED,
            ShadowNodeState.REJECTED,
            ShadowNodeState.DEFERRED
        ),
        ShadowNodeState.TESTED to setOf(
            ShadowNodeState.ACCEPTED_AMENDMENT,
            ShadowNodeState.REJECTED,
            ShadowNodeState.DEFERRED
        )
    )

    /** Requires a valid hash chain and legal next transition. */
    fun validateNext(
        previous: ShadowNodeLifecycleEvent?,
        next: ShadowNodeLifecycleEvent
    ) {
        require(next.hasValidHash()) { "Shadow-node lifecycle event hash is invalid" }
        if (previous == null) {
            require(next.sequence == 1L &&
                next.fromState == null &&
                next.toState == ShadowNodeState.PROPOSED &&
                next.previousEventHash == null
            ) {
                "Shadow-node lifecycle must begin at PROPOSED"
            }
            return
        }
        require(previous.hasValidHash() &&
            next.proposalId == previous.proposalId &&
            next.proposalHash == previous.proposalHash &&
            next.runId == previous.runId &&
            next.sequence == previous.sequence + 1 &&
            next.previousEventHash == previous.eventHash &&
            next.fromState == previous.toState &&
            next.toState in transitions[previous.toState].orEmpty()
        ) {
            "Invalid shadow-node lifecycle transition"
        }
    }
}

/** Evidence-backed later use or test of an accepted proposal. */
@Serializable
enum class OutlierDownstreamOutcome { TESTED, ADOPTED, REJECTED_WITH_EVIDENCE }

@Serializable
data class OutlierDownstreamLineage(
    val lineageId: String,
    val proposalId: String,
    val proposalHash: String,
    val sourceAgentId: AtlasAgentId,
    val sourceRound: Int,
    val observingGroupId: AtlasGroupId,
    val observingAgentId: AtlasAgentId?,
    val observationRound: Int,
    val outcome: OutlierDownstreamOutcome,
    val evidenceArtifactIds: Set<String>,
    val lineageHash: String = ""
) {
    init {
        require(observationRound > sourceRound) {
            "Outlier downstream evidence must come from a later round"
        }
        require(evidenceArtifactIds.isNotEmpty()) {
            "Outlier downstream lineage requires evidence"
        }
    }

    /** Returns this lineage with its deterministic commitment attached. */
    fun withComputedHash(): OutlierDownstreamLineage =
        copy(lineageHash = AtlasHashing.hashOutlierDownstreamLineage(this))

    /** Verifies the lineage commitment. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(lineageHash, AtlasHashing.hashOutlierDownstreamLineage(this))
}

/** Delayed Outlier fitness, created only from later downstream evidence. */
@Serializable
data class OutlierFitnessRecord(
    val recordId: String,
    val proposalId: String,
    val proposalHash: String,
    val agentId: AtlasAgentId,
    val sourceRound: Int,
    val observationRound: Int,
    val noveltyCredit: Double,
    val anticipationCredit: Double,
    val downstreamAdoptionCredit: Double,
    val evidenceLineageHashes: Set<String>,
    val recordHash: String = ""
) {
    init {
        require(observationRound > sourceRound) {
            "Outlier fitness cannot be awarded in the proposal round"
        }
        require(listOf(noveltyCredit, anticipationCredit, downstreamAdoptionCredit).all {
            it.isFinite() && it in 0.0..1.0
        }) {
            "Outlier fitness credits must be normalized"
        }
        require(evidenceLineageHashes.isNotEmpty()) {
            "Outlier fitness requires downstream lineage"
        }
    }

    /** Returns this record with its deterministic commitment attached. */
    fun withComputedHash(): OutlierFitnessRecord =
        copy(recordHash = AtlasHashing.hashOutlierFitnessRecord(this))

    /** Verifies the fitness commitment. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(recordHash, AtlasHashing.hashOutlierFitnessRecord(this))
}

/** Non-authoritative reporting summary derived from committed novelty lineage. */
@Serializable
data class OutlierNoveltyMetrics(
    val proposalCount: Int,
    val uniqueProposalCount: Int,
    val acceptedShadowNodeCount: Int,
    val testedProposalCount: Int,
    val acceptedAmendmentCount: Int,
    val downstreamAdoptionCount: Int
) {
    val acceptanceRatio: Double?
        get() = proposalCount.takeIf { it > 0 }
            ?.let { acceptedShadowNodeCount.toDouble() / it }

    val downstreamAdoptionRatio: Double?
        get() = acceptedShadowNodeCount.takeIf { it > 0 }
            ?.let { downstreamAdoptionCount.toDouble() / it }
}
