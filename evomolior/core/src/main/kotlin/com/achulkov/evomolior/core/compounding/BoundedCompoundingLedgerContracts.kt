package com.achulkov.evomolior.core.compounding

import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.InstrumentalRewardType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Schema version for the bounded compounding ledger authority. */
const val BOUNDED_COMPOUNDING_LEDGER_SCHEMA_V1 = "bounded-compounding-ledger-v1"

/** Terminal handling of an accepted repair diagnostic. */
@Serializable
enum class RepairDiagnosticDisposition {
    CONSUMED,
    DEFERRED_WITH_REASON,
    INVALIDATED
}

/** Terminal handling of an accepted Outlier proposal's bounded experiment. */
@Serializable
enum class OutlierExperimentDisposition {
    ADOPTED,
    REJECTED,
    DEFERRED_WITH_REASON
}

/** Concrete purpose category for an explicitly spent instrumental reward. */
@Serializable
enum class RewardSpendPurposeKind {
    ARTIFACT,
    BENCHMARK,
    REPAIR_ACTION
}

/**
 * One accepted repair diagnostic's explicit next-round work disposition.
 *
 * A persisted diagnostic is not considered handled merely because a round allocated an agent.
 * The work-item record is the durable acknowledgement that names what happened to that
 * diagnostic.
 *
 * @param workItemId Stable work-item identity.
 * @param diagnosticId Accepted diagnostic identity.
 * @param diagnosticHash Exact accepted diagnostic commitment.
 * @param runId Owning run.
 * @param groupId Owning group.
 * @param nodeId Sealed tool node receiving the work item.
 * @param opportunityId Sealed tool opportunity receiving the work item.
 * @param opportunityHash Exact opportunity commitment.
 * @param nextRound Round in which this work item becomes actionable.
 * @param disposition Explicit terminal handling.
 * @param dispositionReason Required for deferred and invalidated items.
 * @param workItemHash Deterministic self-hash.
 */
@Serializable
data class RepairDiagnosticWorkItemV1(
    val workItemId: String,
    val diagnosticId: String,
    val diagnosticHash: String,
    val runId: String,
    val groupId: String,
    val nodeId: String,
    val opportunityId: String,
    val opportunityHash: String,
    val nextRound: Int,
    val disposition: RepairDiagnosticDisposition,
    val dispositionReason: String? = null,
    val workItemHash: String = ""
) {
    init {
        require(
            workItemId.isNotBlank() && diagnosticId.isNotBlank() &&
                runId.isNotBlank() && groupId.isNotBlank() && nodeId.isNotBlank() &&
                opportunityId.isNotBlank()
        ) { "Repair work-item identity must not be blank" }
        require(diagnosticHash.isSha256() && opportunityHash.isSha256()) {
            "Repair work-item references must be SHA-256 commitments"
        }
        require(nextRound >= 0) { "Repair work item round must not be negative" }
        requireReason(
            dispositionReason,
            disposition != RepairDiagnosticDisposition.CONSUMED,
            "Deferred or invalidated repair work items require a reason",
        )
    }

    /** Returns this work item with its deterministic self-hash attached. */
    fun withComputedHash(): RepairDiagnosticWorkItemV1 =
        copy(workItemHash = BoundedCompoundingLedgerHashing.hashRepairWorkItem(this))

    /** Returns whether this work item commits its exact content. */
    fun hasValidHash(): Boolean =
        workItemHash.isSha256() &&
            AtlasHashing.constantTimeEquals(
                workItemHash,
                BoundedCompoundingLedgerHashing.hashRepairWorkItem(this)
            )
}

/**
 * An immediately materialized, bounded WIP experiment for an accepted Outlier proposal.
 *
 * The record exists even when the experiment is deferred or rejected. This closes the gap
 * between acceptance authority and later adoption: an accepted proposal can never vanish into
 * an allocation or an informal plan.
 *
 * @param experimentId Stable experiment identity.
 * @param proposalId Accepted Outlier proposal identity.
 * @param proposalHash Exact proposal commitment.
 * @param runId Owning run.
 * @param groupId Owning group.
 * @param sourceRound Proposal round.
 * @param startRound Earliest round in which the bounded WIP may run.
 * @param maxRounds Maximum experiment duration.
 * @param maxAttempts Maximum experiment attempts.
 * @param boundedWipTaskId Explicit WIP identity.
 * @param disposition Explicit experiment outcome or deferral.
 * @param dispositionReason Required for rejected and deferred experiments.
 * @param experimentHash Deterministic self-hash.
 */
@Serializable
data class OutlierExperimentWipRecordV1(
    val experimentId: String,
    val proposalId: String,
    val proposalHash: String,
    val runId: String,
    val groupId: String,
    val sourceRound: Int,
    val startRound: Int,
    val maxRounds: Int,
    val maxAttempts: Int,
    val boundedWipTaskId: String,
    val disposition: OutlierExperimentDisposition,
    val dispositionReason: String? = null,
    val experimentHash: String = ""
) {
    init {
        require(
            experimentId.isNotBlank() && proposalId.isNotBlank() &&
                runId.isNotBlank() && groupId.isNotBlank() && boundedWipTaskId.isNotBlank()
        ) { "Outlier experiment identity must not be blank" }
        require(proposalHash.isSha256()) {
            "Outlier experiment proposal reference must be a SHA-256 commitment"
        }
        require(sourceRound >= 0 && startRound > sourceRound) {
            "Outlier experiment must start after its proposal round"
        }
        require(maxRounds in 1..3 && maxAttempts in 1..3) {
            "Outlier experiments are bounded to at most three rounds and attempts"
        }
        requireReason(
            dispositionReason,
            disposition != OutlierExperimentDisposition.ADOPTED,
            "Rejected or deferred Outlier experiments require a reason",
        )
    }

    /** Returns this experiment with its deterministic self-hash attached. */
    fun withComputedHash(): OutlierExperimentWipRecordV1 =
        copy(experimentHash = BoundedCompoundingLedgerHashing.hashOutlierExperiment(this))

    /** Returns whether this experiment commits its exact content. */
    fun hasValidHash(): Boolean =
        experimentHash.isSha256() &&
            AtlasHashing.constantTimeEquals(
                experimentHash,
                BoundedCompoundingLedgerHashing.hashOutlierExperiment(this)
            )
}

/**
 * Concrete identity and action for a reward spend.
 *
 * @param kind Concrete target category.
 * @param targetId Artifact, benchmark, or repair-action identity.
 * @param targetHash Exact target commitment.
 * @param actionId Explicit action performed using the reward.
 * @param purposeHash Deterministic self-hash.
 */
@Serializable
data class RewardSpendPurposeV1(
    val kind: RewardSpendPurposeKind,
    val targetId: String,
    val targetHash: String,
    val actionId: String,
    val purposeHash: String = ""
) {
    init {
        require(targetId.isNotBlank() && actionId.isNotBlank()) {
            "Reward spend purpose target and action must not be blank"
        }
        require(targetHash.isSha256()) {
            "Reward spend purpose target must be hash-bound"
        }
    }

    /** Returns this purpose with its deterministic self-hash attached. */
    fun withComputedHash(): RewardSpendPurposeV1 =
        copy(purposeHash = BoundedCompoundingLedgerHashing.hashRewardPurpose(this))

    /** Returns whether this purpose commits its exact content. */
    fun hasValidHash(): Boolean =
        purposeHash.isSha256() &&
            AtlasHashing.constantTimeEquals(
                purposeHash,
                BoundedCompoundingLedgerHashing.hashRewardPurpose(this)
            )
}

/**
 * One explicit spend of a delayed reward against a concrete action.
 *
 * @param spendId Stable spend identity.
 * @param runId Owning run.
 * @param groupId Owning group.
 * @param rewardApplicationHash Explicit applied-reward commitment, never an allocation.
 * @param rewardType Instrumental reward category.
 * @param amountSpent Positive amount consumed.
 * @param spentRound Round in which the action was performed.
 * @param purpose Concrete artifact, benchmark, or repair action.
 * @param spendHash Deterministic self-hash.
 */
@Serializable
data class RewardSpendRecordV1(
    val spendId: String,
    val runId: String,
    val groupId: String,
    val rewardApplicationHash: String,
    val rewardType: InstrumentalRewardType,
    val amountSpent: Double,
    val spentRound: Int,
    val purpose: RewardSpendPurposeV1,
    val spendHash: String = ""
) {
    init {
        require(spendId.isNotBlank() && runId.isNotBlank() && groupId.isNotBlank()) {
            "Reward spend identity must not be blank"
        }
        require(rewardApplicationHash.isSha256()) {
            "Reward spend must reference a hash-bound applied reward"
        }
        require(amountSpent.isFinite() && amountSpent > 0.0) {
            "Reward spend amount must be finite and positive"
        }
        require(spentRound >= 0 && purpose.hasValidHash()) {
            "Reward spend round or purpose authority is invalid"
        }
    }

    /** Returns this spend with its deterministic self-hash attached. */
    fun withComputedHash(): RewardSpendRecordV1 =
        copy(spendHash = BoundedCompoundingLedgerHashing.hashRewardSpend(this))

    /** Returns whether this spend commits its exact content. */
    fun hasValidHash(): Boolean =
        spendHash.isSha256() &&
            AtlasHashing.constantTimeEquals(
                spendHash,
                BoundedCompoundingLedgerHashing.hashRewardSpend(this)
            )
}

/**
 * Later immutable artifact delta linked to one explicit reward spend.
 *
 * @param deltaId Stable delta identity.
 * @param spendId Source reward spend identity.
 * @param spendHash Exact source spend commitment.
 * @param runId Owning run.
 * @param groupId Owning group.
 * @param observedRound Later observation round.
 * @param artifactId Concrete artifact whose committed content changed.
 * @param beforeArtifactHash Artifact commitment before the action.
 * @param afterArtifactHash Artifact commitment after the action.
 * @param metricDelta Measured numeric delta attributable to the action.
 * @param deltaHash Deterministic self-hash.
 */
@Serializable
data class RewardArtifactDeltaV1(
    val deltaId: String,
    val spendId: String,
    val spendHash: String,
    val runId: String,
    val groupId: String,
    val observedRound: Int,
    val artifactId: String,
    val beforeArtifactHash: String,
    val afterArtifactHash: String,
    val metricDelta: Double,
    val deltaHash: String = ""
) {
    init {
        require(deltaId.isNotBlank() && spendId.isNotBlank() && runId.isNotBlank() &&
            groupId.isNotBlank() && artifactId.isNotBlank()) {
            "Reward artifact delta identity must not be blank"
        }
        require(
            spendHash.isSha256() && beforeArtifactHash.isSha256() && afterArtifactHash.isSha256()
        ) { "Reward artifact delta references must be SHA-256 commitments" }
        require(metricDelta.isFinite()) { "Reward artifact metric delta must be finite" }
        require(observedRound >= 0) { "Reward artifact observation round must not be negative" }
    }

    /** Returns this delta with its deterministic self-hash attached. */
    fun withComputedHash(): RewardArtifactDeltaV1 =
        copy(deltaHash = BoundedCompoundingLedgerHashing.hashRewardArtifactDelta(this))

    /** Returns whether this delta commits its exact content. */
    fun hasValidHash(): Boolean =
        deltaHash.isSha256() &&
            AtlasHashing.constantTimeEquals(
                deltaHash,
                BoundedCompoundingLedgerHashing.hashRewardArtifactDelta(this)
            )
}

/**
 * Deterministic validation result for one bounded compounding ledger scope.
 *
 * @param runId Scope run.
 * @param groupId Scope group.
 * @param invalidReferences Persisted rows whose hash or cross-record reference is invalid.
 * @param unresolvedDiagnosticIds Accepted diagnostics without an explicit work-item disposition.
 * @param unresolvedOutlierProposalHashes Accepted Outlier proposals without explicit experiment WIP.
 * @param pendingRewardSpendIds Explicit spends awaiting a later artifact delta.
 */
@Serializable
data class BoundedCompoundingLedgerValidation(
    val runId: String,
    val groupId: String,
    val invalidReferences: List<String> = emptyList(),
    val unresolvedDiagnosticIds: List<String> = emptyList(),
    val unresolvedOutlierProposalHashes: List<String> = emptyList(),
    val pendingRewardSpendIds: List<String> = emptyList()
) {
    init {
        require(runId.isNotBlank() && groupId.isNotBlank()) {
            "Ledger validation scope must not be blank"
        }
        require(invalidReferences == invalidReferences.distinct().sorted())
        require(unresolvedDiagnosticIds == unresolvedDiagnosticIds.distinct().sorted())
        require(unresolvedOutlierProposalHashes == unresolvedOutlierProposalHashes.distinct().sorted())
        require(pendingRewardSpendIds == pendingRewardSpendIds.distinct().sorted())
    }

    /** Whether the ledger contains no orphaned accepted authority or malformed lineage. */
    val isValid: Boolean
        get() = invalidReferences.isEmpty() &&
            unresolvedDiagnosticIds.isEmpty() &&
            unresolvedOutlierProposalHashes.isEmpty()
}

/** Hashing helpers for immutable bounded compounding authorities. */
object BoundedCompoundingLedgerHashing {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    /** Hashes a repair work item without its self-hash. */
    fun hashRepairWorkItem(value: RepairDiagnosticWorkItemV1): String =
        hashWithoutField(RepairDiagnosticWorkItemV1.serializer(), value, "workItemHash")

    /** Hashes an Outlier experiment without its self-hash. */
    fun hashOutlierExperiment(value: OutlierExperimentWipRecordV1): String =
        hashWithoutField(OutlierExperimentWipRecordV1.serializer(), value, "experimentHash")

    /** Hashes a reward purpose without its self-hash. */
    fun hashRewardPurpose(value: RewardSpendPurposeV1): String =
        hashWithoutField(RewardSpendPurposeV1.serializer(), value, "purposeHash")

    /** Hashes a reward spend without its self-hash. */
    fun hashRewardSpend(value: RewardSpendRecordV1): String =
        hashWithoutField(RewardSpendRecordV1.serializer(), value, "spendHash")

    /** Hashes an artifact delta without its self-hash. */
    fun hashRewardArtifactDelta(value: RewardArtifactDeltaV1): String =
        hashWithoutField(RewardArtifactDeltaV1.serializer(), value, "deltaHash")

    private fun <T> hashWithoutField(
        serializer: KSerializer<T>,
        value: T,
        field: String
    ): String {
        val encoded = json.encodeToJsonElement(serializer, value)
        return AtlasHashing.hashText(
            AtlasHashing.canonicalJson(JsonObject(encoded.jsonObject.filterKeys { it != field }))
        )
    }
}

private fun requireReason(reason: String?, required: Boolean, message: String) {
    require(
        if (required) !reason.isNullOrBlank() else reason == null
    ) { message }
}

private fun String.isSha256(): Boolean = matches(Regex("[0-9a-f]{64}"))
