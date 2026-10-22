package com.achulkov.evomolior.substrate.compounding

import com.achulkov.evomolior.core.atlas.NoveltyVectorProposal
import com.achulkov.evomolior.core.atlas.ShadowNodeDecision
import com.achulkov.evomolior.core.atlas.ShadowNodeGraderVerdict
import com.achulkov.evomolior.core.compounding.BoundedCompoundingLedgerValidation
import com.achulkov.evomolior.core.compounding.OutlierExperimentWipRecordV1
import com.achulkov.evomolior.core.compounding.RepairDiagnosticWorkItemV1
import com.achulkov.evomolior.core.compounding.RewardArtifactDeltaV1
import com.achulkov.evomolior.core.compounding.RewardSpendRecordV1
import com.achulkov.evomolior.core.repair.ToolRepairDiagnosticV1
import com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
import com.achulkov.evomolior.substrate.db.entity.CompoundingOutlierExperimentEntity
import com.achulkov.evomolior.substrate.db.entity.CompoundingRepairWorkItemEntity
import com.achulkov.evomolior.substrate.db.entity.CompoundingRewardArtifactDeltaEntity
import com.achulkov.evomolior.substrate.db.entity.CompoundingRewardSpendEntity
import com.achulkov.evomolior.substrate.db.entity.NoveltyVectorProposalEntity
import com.achulkov.evomolior.substrate.db.entity.ShadowNodeGraderVerdictEntity
import com.achulkov.evomolior.substrate.db.entity.ToolRepairDiagnosticEntity
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Small append-only authority for cross-round compounding obligations.
 *
 * This repository records explicit accepted-diagnostic handling, accepted-Outlier experiment
 * materialization, reward spend purpose, and later artifact deltas. It never reads allocations
 * or agent counts as evidence that work happened.
 *
 * @param database Isolated substrate database owning the ledger.
 * @param json Strict JSON codec used for immutable payload preservation.
 */
class BoundedCompoundingLedgerRepository(
    private val database: EvoMoliorDatabase,
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
    },
    /**
     * Independent Outlier acceptance authority. The experiment ledger remains in [database],
     * while accepted proposal/verdict reads may come from the run-global Atlas database.
     */
    private val outlierAuthorityDatabase: EvoMoliorDatabase = database,
) {
    private val dao = database.boundedCompoundingLedgerDao()
    private val completionDao = database.completionPersistenceDao()
    private val noveltyDao = outlierAuthorityDatabase.outlierNoveltyDao()

    /**
     * Appends an explicit next-round work item for a persisted accepted repair diagnostic.
     *
     * @param value Hash-valid work item.
     * @return Exact persisted work item.
     */
    suspend fun appendRepairWorkItem(
        value: RepairDiagnosticWorkItemV1
    ): RepairDiagnosticWorkItemV1 {
        require(value.hasValidHash()) { "Repair work-item hash is invalid" }
        val diagnostic = requireNotNull(completionDao.diagnostic(value.diagnosticId)) {
            "Repair work item references an unknown diagnostic"
        }.toDiagnostic()
        require(
            diagnostic.diagnosticHash == value.diagnosticHash &&
                diagnostic.runId == value.runId &&
                diagnostic.groupId == value.groupId &&
                diagnostic.nodeId == value.nodeId &&
                diagnostic.opportunityId == value.opportunityId &&
                diagnostic.opportunityHash == value.opportunityHash &&
                value.nextRound >= diagnostic.visibleFromRound
        ) { "Repair work item does not match its accepted diagnostic authority" }

        dao.repairWorkItem(value.workItemId)?.let { existing ->
            return requireSame("repair work item", existing.toRepairWorkItem(), value)
        }
        dao.repairWorkItemByDiagnosticHash(value.diagnosticHash)?.let { existing ->
            return requireSame("repair diagnostic disposition", existing.toRepairWorkItem(), value)
        }
        dao.insertRepairWorkItem(value.toEntity())
        return requireSame(
            "repair work item",
            requireNotNull(dao.repairWorkItem(value.workItemId)) {
                "Repair work-item append did not persist ${value.workItemId}"
            }.toRepairWorkItem(),
            value
        )
    }

    /**
     * Appends an explicit work item for an accepted diagnostic.
     *
     * @param value Hash-valid diagnostic work item.
     * @return Exact persisted work item.
     */
    suspend fun appendDiagnosticWorkItem(
        value: RepairDiagnosticWorkItemV1
    ): RepairDiagnosticWorkItemV1 = appendRepairWorkItem(value)

    /** Returns one exact repair work item by stable identity. */
    suspend fun repairWorkItem(workItemId: String): RepairDiagnosticWorkItemV1? =
        dao.repairWorkItem(workItemId)?.toRepairWorkItem()

    /** Returns all repair work items for a scope in deterministic next-round order. */
    suspend fun repairWorkItems(
        runId: String,
        groupId: String
    ): List<RepairDiagnosticWorkItemV1> =
        dao.repairWorkItems(runId, groupId).map { it.toRepairWorkItem() }

    /**
     * Materializes the bounded WIP experiment for an explicitly accepted Outlier proposal.
     *
     * Acceptance is read only from the persisted independent verdict. The method does not infer
     * acceptance from allocation, lifecycle scheduling, or a later adoption event.
     *
     * @param value Hash-valid bounded experiment/WIP record.
     * @return Exact persisted experiment/WIP record.
     */
    suspend fun appendOutlierExperiment(
        value: OutlierExperimentWipRecordV1
    ): OutlierExperimentWipRecordV1 {
        require(value.hasValidHash()) { "Outlier experiment hash is invalid" }
        val proposal = requireNotNull(noveltyDao.proposalByHash(value.proposalHash)) {
            "Outlier experiment references an unknown proposal"
        }.toProposal()
        val verdict = requireNotNull(noveltyDao.verdictByProposalHash(value.proposalHash)) {
            "Outlier experiment requires an explicit Outlier acceptance verdict"
        }.toVerdict()
        require(verdict.decision == ShadowNodeDecision.ACCEPT) {
            "Outlier experiment requires an accepted proposal verdict"
        }
        require(
            proposal.proposalId == value.proposalId &&
                proposal.runId == value.runId &&
                proposal.groupId.value == value.groupId &&
                proposal.round == value.sourceRound &&
                value.startRound > proposal.round
        ) { "Outlier experiment does not match its accepted proposal authority" }

        dao.outlierExperiment(value.experimentId)?.let { existing ->
            return requireSame("Outlier experiment", existing.toOutlierExperiment(), value)
        }
        dao.outlierExperimentByProposalHash(value.proposalHash)?.let { existing ->
            return requireSame("accepted Outlier experiment", existing.toOutlierExperiment(), value)
        }
        dao.insertOutlierExperiment(value.toEntity())
        return requireSame(
            "Outlier experiment",
            requireNotNull(dao.outlierExperiment(value.experimentId)) {
                "Outlier experiment append did not persist ${value.experimentId}"
            }.toOutlierExperiment(),
            value
        )
    }

    /**
     * Materializes an explicit bounded WIP record for an accepted Outlier proposal.
     *
     * @param value Hash-valid bounded experiment/WIP record.
     * @return Exact persisted experiment/WIP record.
     */
    suspend fun appendOutlierExperimentWip(
        value: OutlierExperimentWipRecordV1
    ): OutlierExperimentWipRecordV1 = appendOutlierExperiment(value)

    /** Returns one exact Outlier experiment/WIP record by stable identity. */
    suspend fun outlierExperiment(experimentId: String): OutlierExperimentWipRecordV1? =
        dao.outlierExperiment(experimentId)?.toOutlierExperiment()

    /** Returns all bounded Outlier experiments for a scope in deterministic start order. */
    suspend fun outlierExperiments(
        runId: String,
        groupId: String
    ): List<OutlierExperimentWipRecordV1> =
        dao.outlierExperiments(runId, groupId).map { it.toOutlierExperiment() }

    /**
     * Appends an explicit reward spend tied to a concrete action.
     *
     * @param value Hash-valid reward spend.
     * @return Exact persisted reward spend.
     */
    suspend fun appendRewardSpend(value: RewardSpendRecordV1): RewardSpendRecordV1 {
        require(value.hasValidHash()) { "Reward spend hash is invalid" }
        dao.rewardSpend(value.spendId)?.let { existing ->
            return requireSame("reward spend", existing.toRewardSpend(), value)
        }
        dao.insertRewardSpend(value.toEntity())
        return requireSame(
            "reward spend",
            requireNotNull(dao.rewardSpend(value.spendId)) {
                "Reward spend append did not persist ${value.spendId}"
            }.toRewardSpend(),
            value
        )
    }

    /** Returns one exact reward spend by stable identity. */
    suspend fun rewardSpend(spendId: String): RewardSpendRecordV1? =
        dao.rewardSpend(spendId)?.toRewardSpend()

    /** Returns all explicit reward spends for a scope in deterministic spend-round order. */
    suspend fun rewardSpends(
        runId: String,
        groupId: String
    ): List<RewardSpendRecordV1> =
        dao.rewardSpends(runId, groupId).map { it.toRewardSpend() }

    /**
     * Appends the later artifact delta for one explicit reward spend.
     *
     * @param value Hash-valid artifact delta.
     * @return Exact persisted artifact delta.
     */
    suspend fun appendRewardArtifactDelta(
        value: RewardArtifactDeltaV1
    ): RewardArtifactDeltaV1 {
        require(value.hasValidHash()) { "Reward artifact delta hash is invalid" }
        val spend = requireNotNull(dao.rewardSpend(value.spendId)) {
            "Artifact delta references an unknown reward spend"
        }.toRewardSpend()
        require(
            spend.spendHash == value.spendHash &&
                spend.runId == value.runId &&
                spend.groupId == value.groupId &&
                value.observedRound > spend.spentRound
        ) { "Artifact delta does not describe a later observation of its reward spend" }
        when (spend.purpose.kind) {
            com.achulkov.evomolior.core.compounding.RewardSpendPurposeKind.ARTIFACT ->
                require(spend.purpose.targetId == value.artifactId) {
                    "Artifact reward delta must name the rewarded artifact"
                }
            com.achulkov.evomolior.core.compounding.RewardSpendPurposeKind.BENCHMARK,
            com.achulkov.evomolior.core.compounding.RewardSpendPurposeKind.REPAIR_ACTION -> Unit
        }

        dao.rewardArtifactDelta(value.deltaId)?.let { existing ->
            return requireSame("reward artifact delta", existing.toRewardArtifactDelta(), value)
        }
        dao.rewardArtifactDeltaForSpend(value.spendId)?.let { existing ->
            return requireSame("reward spend artifact delta", existing.toRewardArtifactDelta(), value)
        }
        dao.insertRewardArtifactDelta(value.toEntity())
        return requireSame(
            "reward artifact delta",
            requireNotNull(dao.rewardArtifactDelta(value.deltaId)) {
                "Reward artifact delta append did not persist ${value.deltaId}"
            }.toRewardArtifactDelta(),
            value
        )
    }

    /** Returns one exact artifact delta by stable identity. */
    suspend fun rewardArtifactDelta(deltaId: String): RewardArtifactDeltaV1? =
        dao.rewardArtifactDelta(deltaId)?.toRewardArtifactDelta()

    /** Returns the later artifact delta linked to one reward spend, when observed. */
    suspend fun rewardArtifactDeltaForSpend(spendId: String): RewardArtifactDeltaV1? =
        dao.rewardArtifactDeltaForSpend(spendId)?.toRewardArtifactDelta()

    /** Returns all artifact deltas for a scope in deterministic observation order. */
    suspend fun rewardArtifactDeltas(
        runId: String,
        groupId: String
    ): List<RewardArtifactDeltaV1> =
        dao.rewardArtifactDeltas(runId, groupId).map { it.toRewardArtifactDelta() }

    /**
     * Validates deterministic ledger closure for one scope.
     *
     * Missing diagnostic work items and missing accepted-Outlier experiment records are invalid
     * because those authorities would otherwise silently disappear. Reward spends without a
     * later observation are reported as pending, not synthesized into activity.
     *
     * @param runId Scope run.
     * @param groupId Scope group.
     * @return Stable validation result with sorted identities.
     */
    suspend fun validateState(
        runId: String,
        groupId: String
    ): BoundedCompoundingLedgerValidation {
        require(runId.isNotBlank() && groupId.isNotBlank()) {
            "Ledger validation scope must not be blank"
        }
        val invalid = linkedSetOf<String>()
        val diagnostics = completionDao.diagnosticsForScope(runId, groupId).mapNotNull { row ->
            runCatching { row.toDiagnostic() }
                .onFailure { invalid += "diagnostic:${row.diagnosticId}" }
                .getOrNull()
        }
        val workItems = dao.repairWorkItems(runId, groupId).mapNotNull { row ->
            runCatching { row.toRepairWorkItem() }
                .onFailure { invalid += "repair-work-item:${row.workItemId}" }
                .getOrNull()
        }
        val workByDiagnostic = workItems.associateBy { it.diagnosticHash }
        val unresolvedDiagnostics = diagnostics
            .filter { it.diagnosticHash !in workByDiagnostic }
            .map { it.diagnosticId }
        workItems.forEach { item ->
            val diagnostic = diagnostics.firstOrNull { it.diagnosticHash == item.diagnosticHash }
            if (
                diagnostic == null ||
                diagnostic.diagnosticId != item.diagnosticId ||
                diagnostic.opportunityId != item.opportunityId ||
                diagnostic.opportunityHash != item.opportunityHash
            ) {
                invalid += "repair-work-item:${item.workItemId}:diagnostic-reference"
            }
        }

        val acceptedProposals = noveltyDao.acceptedProposalsForRun(runId)
            .mapNotNull { row ->
                runCatching { row.toProposal() }
                    .onFailure { invalid += "outlier-proposal:${row.proposalId}" }
                    .getOrNull()
            }
            .filter { it.groupId.value == groupId }
        val experiments = dao.outlierExperiments(runId, groupId).mapNotNull { row ->
            runCatching { row.toOutlierExperiment() }
                .onFailure { invalid += "outlier-experiment:${row.experimentId}" }
                .getOrNull()
        }
        val experimentsByProposal = experiments.associateBy { it.proposalHash }
        val unresolvedOutliers = acceptedProposals
            .filter { it.proposalHash !in experimentsByProposal }
            .map { it.proposalHash }
        experiments.forEach { experiment ->
            val proposal = acceptedProposals.firstOrNull { it.proposalHash == experiment.proposalHash }
            if (
                proposal == null ||
                proposal.proposalId != experiment.proposalId ||
                proposal.groupId.value != experiment.groupId ||
                proposal.round != experiment.sourceRound
            ) {
                invalid += "outlier-experiment:${experiment.experimentId}:proposal-reference"
            }
        }

        val spends = dao.rewardSpends(runId, groupId).mapNotNull { row ->
            runCatching { row.toRewardSpend() }
                .onFailure { invalid += "reward-spend:${row.spendId}" }
                .getOrNull()
        }
        val pendingSpends = spends
            .filter { dao.rewardArtifactDeltaForSpend(it.spendId) == null }
            .map { it.spendId }
        dao.rewardArtifactDeltas(runId, groupId).forEach { row ->
            val delta = runCatching { row.toRewardArtifactDelta() }
                .onFailure { invalid += "reward-artifact-delta:${row.deltaId}" }
                .getOrNull()
            if (delta != null) {
                val spend = spends.firstOrNull { it.spendId == delta.spendId }
                if (
                    spend == null ||
                    spend.spendHash != delta.spendHash ||
                    delta.observedRound <= spend.spentRound
                ) {
                    invalid += "reward-artifact-delta:${delta.deltaId}:spend-reference"
                }
            }
        }

        return BoundedCompoundingLedgerValidation(
            runId = runId,
            groupId = groupId,
            invalidReferences = invalid.toList().sorted(),
            unresolvedDiagnosticIds = unresolvedDiagnostics.sorted(),
            unresolvedOutlierProposalHashes = unresolvedOutliers.sorted(),
            pendingRewardSpendIds = pendingSpends.sorted()
        )
    }

    private fun RepairDiagnosticWorkItemV1.toEntity() = CompoundingRepairWorkItemEntity(
        workItemId = workItemId,
        diagnosticId = diagnosticId,
        diagnosticHash = diagnosticHash,
        runId = runId,
        groupId = groupId,
        nodeId = nodeId,
        opportunityId = opportunityId,
        opportunityHash = opportunityHash,
        nextRound = nextRound,
        disposition = disposition.name,
        dispositionReason = dispositionReason,
        workItemHash = workItemHash,
        contentJson = encode(RepairDiagnosticWorkItemV1.serializer(), this)
    )

    private fun CompoundingRepairWorkItemEntity.toRepairWorkItem(): RepairDiagnosticWorkItemV1 =
        decode(RepairDiagnosticWorkItemV1.serializer(), contentJson).also { value ->
            require(
                value.hasValidHash() &&
                    value.workItemId == workItemId &&
                    value.diagnosticHash == diagnosticHash &&
                    value.disposition.name == disposition &&
                    value.workItemHash == workItemHash
            ) { "Persisted repair work-item metadata does not match its content" }
        }

    private fun CompoundingOutlierExperimentEntity.toOutlierExperiment(): OutlierExperimentWipRecordV1 =
        decode(OutlierExperimentWipRecordV1.serializer(), contentJson).also { value ->
            require(
                value.hasValidHash() &&
                    value.experimentId == experimentId &&
                    value.proposalHash == proposalHash &&
                    value.experimentHash == experimentHash
            ) { "Persisted Outlier experiment metadata does not match its content" }
        }

    private fun OutlierExperimentWipRecordV1.toEntity() = CompoundingOutlierExperimentEntity(
        experimentId = experimentId,
        proposalId = proposalId,
        proposalHash = proposalHash,
        runId = runId,
        groupId = groupId,
        sourceRound = sourceRound,
        startRound = startRound,
        maxRounds = maxRounds,
        maxAttempts = maxAttempts,
        boundedWipTaskId = boundedWipTaskId,
        disposition = disposition.name,
        dispositionReason = dispositionReason,
        experimentHash = experimentHash,
        contentJson = encode(OutlierExperimentWipRecordV1.serializer(), this)
    )

    private fun RewardSpendRecordV1.toEntity() = CompoundingRewardSpendEntity(
        spendId = spendId,
        runId = runId,
        groupId = groupId,
        rewardApplicationHash = rewardApplicationHash,
        rewardType = rewardType.name,
        amountSpent = amountSpent,
        spentRound = spentRound,
        purposeHash = purpose.purposeHash,
        purposeKind = purpose.kind.name,
        purposeTargetId = purpose.targetId,
        purposeTargetHash = purpose.targetHash,
        purposeActionId = purpose.actionId,
        spendHash = spendHash,
        contentJson = encode(RewardSpendRecordV1.serializer(), this)
    )

    private fun CompoundingRewardSpendEntity.toRewardSpend(): RewardSpendRecordV1 =
        decode(RewardSpendRecordV1.serializer(), contentJson).also { value ->
            require(
                value.hasValidHash() &&
                    value.spendId == spendId &&
                    value.spendHash == spendHash &&
                    value.purpose.purposeHash == purposeHash &&
                    value.rewardType.name == rewardType
            ) { "Persisted reward spend metadata does not match its content" }
        }

    private fun RewardArtifactDeltaV1.toEntity() = CompoundingRewardArtifactDeltaEntity(
        deltaId = deltaId,
        spendId = spendId,
        spendHash = spendHash,
        runId = runId,
        groupId = groupId,
        observedRound = observedRound,
        artifactId = artifactId,
        beforeArtifactHash = beforeArtifactHash,
        afterArtifactHash = afterArtifactHash,
        metricDelta = metricDelta,
        deltaHash = deltaHash,
        contentJson = encode(RewardArtifactDeltaV1.serializer(), this)
    )

    private fun CompoundingRewardArtifactDeltaEntity.toRewardArtifactDelta(): RewardArtifactDeltaV1 =
        decode(RewardArtifactDeltaV1.serializer(), contentJson).also { value ->
            require(
                value.hasValidHash() &&
                    value.deltaId == deltaId &&
                    value.spendId == spendId &&
                    value.spendHash == spendHash &&
                    value.deltaHash == deltaHash
            ) { "Persisted reward artifact delta metadata does not match its content" }
        }

    private fun ToolRepairDiagnosticEntity.toDiagnostic(): ToolRepairDiagnosticV1 =
        decode(ToolRepairDiagnosticV1.serializer(), contentJson).also { value ->
            require(
                value.hasValidHash() &&
                    value.diagnosticId == diagnosticId &&
                    value.runId == runId &&
                    value.groupId == groupId &&
                    value.diagnosticHash == diagnosticHash
            ) { "Persisted diagnostic metadata does not match its content" }
        }

    private fun NoveltyVectorProposalEntity.toProposal(): NoveltyVectorProposal =
        decode(NoveltyVectorProposal.serializer(), contentJson).also { value ->
            require(
                value.hasValidHash() &&
                    value.proposalId == proposalId &&
                    value.proposalHash == proposalHash &&
                    value.runId == runId
            ) { "Persisted Outlier proposal metadata does not match its content" }
        }

    private fun ShadowNodeGraderVerdictEntity.toVerdict(): ShadowNodeGraderVerdict =
        decode(ShadowNodeGraderVerdict.serializer(), contentJson).also { value ->
            require(value.hasValidHash() && value.proposalHash == proposalHash) {
                "Persisted Outlier verdict metadata does not match its content"
            }
        }

    private fun <T> encode(serializer: KSerializer<T>, value: T): String =
        json.encodeToString(serializer, value)

    private fun <T> decode(serializer: KSerializer<T>, value: String): T =
        json.decodeFromString(serializer, value)

    private fun <T> requireSame(
        kind: String,
        existing: T,
        requested: T
    ): T {
        require(existing == requested) { "$kind identity is immutable" }
        return existing
    }
}
