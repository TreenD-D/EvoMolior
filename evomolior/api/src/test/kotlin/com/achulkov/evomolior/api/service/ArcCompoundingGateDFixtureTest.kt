package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.InstrumentalRewardType
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NoveltyGraderVector
import com.achulkov.evomolior.core.atlas.NoveltyType
import com.achulkov.evomolior.core.atlas.NoveltyVectorProposal
import com.achulkov.evomolior.core.atlas.ShadowNodeDecision
import com.achulkov.evomolior.core.atlas.ShadowNodeGraderVerdict
import com.achulkov.evomolior.core.compounding.OutlierExperimentDisposition
import com.achulkov.evomolior.core.compounding.OutlierExperimentWipRecordV1
import com.achulkov.evomolior.core.compounding.RepairDiagnosticDisposition
import com.achulkov.evomolior.core.compounding.RepairDiagnosticWorkItemV1
import com.achulkov.evomolior.core.compounding.RewardArtifactDeltaV1
import com.achulkov.evomolior.core.compounding.RewardSpendPurposeKind
import com.achulkov.evomolior.core.compounding.RewardSpendPurposeV1
import com.achulkov.evomolior.core.compounding.RewardSpendRecordV1
import com.achulkov.evomolior.core.repair.ToolRepairCode
import com.achulkov.evomolior.core.repair.ToolRepairDiagnosticV1
import com.achulkov.evomolior.core.repair.ToolRepairDisclosureClass
import com.achulkov.evomolior.core.repair.ToolRepairSource
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.outlier.OutlierNoveltyRepository
import com.achulkov.evomolior.substrate.compounding.BoundedCompoundingLedgerRepository
import com.achulkov.evomolior.substrate.repair.ToolRepairPersistenceRepository
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Provider-free Gate-D compounding fixture for three isolated ARC group ledgers.
 *
 * The fixture deliberately records accepted diagnostic consumption, an accepted-but-deferred
 * Outlier experiment, and reward spend followed by a later artifact delta in every group. These
 * are substrate authorities, not accepted-winner or provider evidence.
 */
class ArcCompoundingGateDFixtureTest {
    @Test
    fun `three isolated groups retain explicit diagnostic Outlier and reward dispositions`() =
        runTest {
            val runId = "gate-d-arc-three-group-compounding-v1"
            val factory = SubstrateDatabaseFactory(
                createTempDirectory("arc-gate-d-compounding-").toFile()
            )
            val groupIds = listOf("group-a", "group-b", "group-c")

            groupIds.forEachIndexed { index, groupId ->
                val database = factory.get(runId, groupId)
                val ledger = BoundedCompoundingLedgerRepository(database)
                val diagnostic = diagnostic(runId, groupId, index)
                ToolRepairPersistenceRepository(database).appendDiagnostic(diagnostic)

                val workItem = RepairDiagnosticWorkItemV1(
                    workItemId = "repair-work:$groupId",
                    diagnosticId = diagnostic.diagnosticId,
                    diagnosticHash = diagnostic.diagnosticHash,
                    runId = runId,
                    groupId = groupId,
                    nodeId = diagnostic.nodeId,
                    opportunityId = diagnostic.opportunityId,
                    opportunityHash = diagnostic.opportunityHash,
                    nextRound = 2,
                    disposition = RepairDiagnosticDisposition.CONSUMED,
                ).withComputedHash()
                assertEquals(workItem, ledger.appendRepairWorkItem(workItem))

                val proposal = proposal(runId, groupId, index)
                val novelty = OutlierNoveltyRepository(database)
                novelty.appendProposal(proposal)
                novelty.appendVerdict(
                    ShadowNodeGraderVerdict(
                        proposalHash = proposal.proposalHash,
                        decision = ShadowNodeDecision.ACCEPT,
                        aggregateVector = NoveltyGraderVector(
                            novelty = 0.9,
                            receiverRelevance = 0.8,
                            testability = 0.9,
                            plausibility = 0.8,
                            expectedImpact = 0.8,
                            costDiscipline = 0.9,
                        ),
                        assessmentHashes = setOf("assessment:$groupId"),
                        requiredQuorum = 1,
                        reason = "accepted for bounded next-round testing",
                    ).withComputedHash()
                )
                val experiment = OutlierExperimentWipRecordV1(
                    experimentId = "outlier-experiment:${proposal.proposalId}",
                    proposalId = proposal.proposalId,
                    proposalHash = proposal.proposalHash,
                    runId = runId,
                    groupId = groupId,
                    sourceRound = 1,
                    startRound = 2,
                    maxRounds = 2,
                    maxAttempts = 2,
                    boundedWipTaskId = "outlier-wip:${proposal.proposalId}",
                    disposition = OutlierExperimentDisposition.DEFERRED_WITH_REASON,
                    dispositionReason = "next round was outside this bounded fixture window",
                ).withComputedHash()
                assertEquals(experiment, ledger.appendOutlierExperiment(experiment))

                val spend = RewardSpendRecordV1(
                    spendId = "reward-spend:$groupId",
                    runId = runId,
                    groupId = groupId,
                    rewardApplicationHash = "c".repeat(64),
                    rewardType = InstrumentalRewardType.ARTIFACT_VALIDATION_CREDITS,
                    amountSpent = 1.0,
                    spentRound = 1,
                    purpose = RewardSpendPurposeV1(
                        kind = RewardSpendPurposeKind.BENCHMARK,
                        targetId = "arc-visible-loo:$groupId",
                        targetHash = "d".repeat(64),
                        actionId = "evaluate-round-1-artifact",
                    ).withComputedHash(),
                ).withComputedHash()
                assertEquals(spend, ledger.appendRewardSpend(spend))

                val delta = RewardArtifactDeltaV1(
                    deltaId = "artifact-delta:$groupId",
                    spendId = spend.spendId,
                    spendHash = spend.spendHash,
                    runId = runId,
                    groupId = groupId,
                    observedRound = 2,
                    artifactId = "submission.ipynb",
                    beforeArtifactHash = "e".repeat(64),
                    afterArtifactHash = "f".repeat(64),
                    metricDelta = 1.0,
                ).withComputedHash()
                assertEquals(delta, ledger.appendRewardArtifactDelta(delta))

                val validation = ledger.validateState(runId, groupId)
                assertTrue(validation.isValid, validation.invalidReferences.joinToString())
                assertTrue(validation.unresolvedDiagnosticIds.isEmpty())
                assertTrue(validation.unresolvedOutlierProposalHashes.isEmpty())
                assertTrue(validation.pendingRewardSpendIds.isEmpty())
                assertEquals(
                    RepairDiagnosticDisposition.CONSUMED,
                    ledger.repairWorkItem(workItem.workItemId)?.disposition,
                )
                assertEquals(
                    OutlierExperimentDisposition.DEFERRED_WITH_REASON,
                    ledger.outlierExperiment(experiment.experimentId)?.disposition,
                )
                assertEquals(delta, ledger.rewardArtifactDeltaForSpend(spend.spendId))
            }
        }

    private fun diagnostic(runId: String, groupId: String, index: Int) =
        ToolRepairDiagnosticV1(
            diagnosticId = "diagnostic:$groupId",
            runId = runId,
            groupId = groupId,
            nodeId = "node-$index",
            opportunityId = "opportunity-$index",
            opportunityHash = "a".repeat(64),
            candidateHash = "b".repeat(64),
            source = ToolRepairSource.PUBLIC_PREFLIGHT,
            disclosureClass = ToolRepairDisclosureClass.PUBLIC_EXACT,
            code = ToolRepairCode.CONTRACT_SCHEMA_MISMATCH,
            createdRound = 1,
            visibleFromRound = 2,
            affectedPublicField = "result",
        ).withComputedHash()

    private fun proposal(runId: String, groupId: String, index: Int) =
        NoveltyVectorProposal(
            proposalId = "proposal:$groupId",
            runId = runId,
            atlasHash = "1".repeat(64),
            groupId = AtlasGroupId(groupId),
            round = 1,
            proposerAgentId = AtlasAgentId("outlier-$groupId"),
            noveltyType = NoveltyType.ALTERNATIVE_MECHANISM,
            affectedVisibleNodeIds = setOf(NodeId("node-$index")),
            missingLayerOrAssumption = "the baseline omits one solver family",
            proposedVector = "a bounded alternative solver family",
            whyCurrentMapMayMissIt = "the current family is representation-specific",
            falsifiablePrediction = "the alternative improves one held-out row",
            minimalTestOrArtifact = "run a bounded two-case validation",
            expectedReceiverImpact = "improves exactness",
            expectedCost = 1.0,
            noveltyFingerprint = "fingerprint:$groupId",
        ).withComputedHash()
}
