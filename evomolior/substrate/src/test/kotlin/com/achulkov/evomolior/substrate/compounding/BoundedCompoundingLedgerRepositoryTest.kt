package com.achulkov.evomolior.substrate.compounding

import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.InstrumentalRewardType
import com.achulkov.evomolior.core.atlas.NoveltyGraderVector
import com.achulkov.evomolior.core.atlas.NoveltyType
import com.achulkov.evomolior.core.atlas.NoveltyVectorProposal
import com.achulkov.evomolior.core.atlas.ShadowNodeDecision
import com.achulkov.evomolior.core.atlas.ShadowNodeGraderVerdict
import com.achulkov.evomolior.core.atlas.NodeId
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
import com.achulkov.evomolior.substrate.repair.ToolRepairPersistenceRepository
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoundedCompoundingLedgerRepositoryTest {
    @Test
    fun `accepted diagnostic must become explicit next-round work or validation stays open`() =
        runTest {
            val database = database()
            try {
                val diagnostic = diagnostic()
                ToolRepairPersistenceRepository(database).appendDiagnostic(diagnostic)
                val repository = BoundedCompoundingLedgerRepository(database)

                val open = repository.validateState("run", "group")
                assertFalse(open.isValid)
                assertEquals(listOf(diagnostic.diagnosticId), open.unresolvedDiagnosticIds)

                val workItem = RepairDiagnosticWorkItemV1(
                    workItemId = "work-item",
                    diagnosticId = diagnostic.diagnosticId,
                    diagnosticHash = diagnostic.diagnosticHash,
                    runId = "run",
                    groupId = "group",
                    nodeId = "node",
                    opportunityId = "opportunity",
                    opportunityHash = "a".repeat(64),
                    nextRound = 2,
                    disposition = RepairDiagnosticDisposition.CONSUMED
                ).withComputedHash()
                assertEquals(workItem, repository.appendRepairWorkItem(workItem))
                assertEquals(workItem, repository.appendRepairWorkItem(workItem))
                assertTrue(repository.validateState("run", "group").unresolvedDiagnosticIds.isEmpty())
            } finally {
                database.close()
            }
        }

    @Test
    fun `accepted but not yet adopted Outlier immediately gets bounded experiment WIP`() =
        runTest {
            val database = database()
            try {
                val proposal = proposal()
                val outlier = OutlierNoveltyRepository(database)
                outlier.appendProposal(proposal)
                outlier.appendVerdict(
                    ShadowNodeGraderVerdict(
                        proposalHash = proposal.proposalHash,
                        decision = ShadowNodeDecision.ACCEPT,
                        aggregateVector = NoveltyGraderVector(
                            novelty = 0.9,
                            receiverRelevance = 0.8,
                            testability = 0.9,
                            plausibility = 0.7,
                            expectedImpact = 0.8,
                            costDiscipline = 0.9
                        ),
                        assessmentHashes = setOf("assessment"),
                        requiredQuorum = 1,
                        reason = "accepted for bounded testing"
                    ).withComputedHash()
                )
                val repository = BoundedCompoundingLedgerRepository(database)
                assertEquals(
                    listOf(proposal.proposalHash),
                    repository.validateState("run", "group").unresolvedOutlierProposalHashes
                )

                val experiment = OutlierExperimentWipRecordV1(
                    experimentId = "experiment",
                    proposalId = proposal.proposalId,
                    proposalHash = proposal.proposalHash,
                    runId = "run",
                    groupId = "group",
                    sourceRound = 1,
                    startRound = 2,
                    maxRounds = 2,
                    maxAttempts = 2,
                    boundedWipTaskId = "wip-outlier",
                    disposition = OutlierExperimentDisposition.DEFERRED_WITH_REASON,
                    dispositionReason = "starts in the next explicit work window"
                ).withComputedHash()

                assertEquals(experiment, repository.appendOutlierExperiment(experiment))
                assertEquals(experiment, repository.outlierExperiment(experiment.experimentId))
                assertTrue(repository.validateState("run", "group")
                    .unresolvedOutlierProposalHashes.isEmpty())
            } finally {
                database.close()
            }
        }

    @Test
    fun `reward spend names concrete purpose and later artifact delta`() = runTest {
        val database = database()
        try {
            val repository = BoundedCompoundingLedgerRepository(database)
            val purpose = RewardSpendPurposeV1(
                kind = RewardSpendPurposeKind.BENCHMARK,
                targetId = "arc-benchmark",
                targetHash = "b".repeat(64),
                actionId = "evaluate-artifact"
            ).withComputedHash()
            val spend = RewardSpendRecordV1(
                spendId = "spend",
                runId = "run",
                groupId = "group",
                rewardApplicationHash = "c".repeat(64),
                rewardType = InstrumentalRewardType.ARTIFACT_VALIDATION_CREDITS,
                amountSpent = 1.0,
                spentRound = 2,
                purpose = purpose
            ).withComputedHash()
            val delta = RewardArtifactDeltaV1(
                deltaId = "delta",
                spendId = spend.spendId,
                spendHash = spend.spendHash,
                runId = "run",
                groupId = "group",
                observedRound = 3,
                artifactId = "artifact-after-benchmark",
                beforeArtifactHash = "d".repeat(64),
                afterArtifactHash = "e".repeat(64),
                metricDelta = 0.25
            ).withComputedHash()

            assertEquals(spend, repository.appendRewardSpend(spend))
            assertEquals(listOf(spend), repository.rewardSpends("run", "group"))
            assertEquals(listOf(spend.spendId),
                repository.validateState("run", "group").pendingRewardSpendIds)
            assertEquals(delta, repository.appendRewardArtifactDelta(delta))
            assertEquals(delta, repository.appendRewardArtifactDelta(delta))
            assertEquals(delta, repository.rewardArtifactDeltaForSpend(spend.spendId))
            assertTrue(repository.validateState("run", "group").pendingRewardSpendIds.isEmpty())
        } finally {
            database.close()
        }
    }

    private fun database() = SubstrateDatabaseFactory(
        createTempDirectory("bounded-compounding-").toFile()
    ).get("run", "group")

    private fun diagnostic() = ToolRepairDiagnosticV1(
        diagnosticId = "diagnostic",
        runId = "run",
        groupId = "group",
        nodeId = "node",
        opportunityId = "opportunity",
        opportunityHash = "a".repeat(64),
        candidateHash = "b".repeat(64),
        source = ToolRepairSource.PUBLIC_PREFLIGHT,
        disclosureClass = ToolRepairDisclosureClass.PUBLIC_EXACT,
        code = ToolRepairCode.CONTRACT_SCHEMA_MISMATCH,
        createdRound = 1,
        visibleFromRound = 2,
        affectedPublicField = "result"
    ).withComputedHash()

    private fun proposal() = NoveltyVectorProposal(
        proposalId = "proposal",
        runId = "run",
        atlasHash = "f".repeat(64),
        groupId = AtlasGroupId("group"),
        round = 1,
        proposerAgentId = AtlasAgentId("outlier"),
        noveltyType = NoveltyType.ALTERNATIVE_MECHANISM,
        affectedVisibleNodeIds = setOf(NodeId("node")),
        missingLayerOrAssumption = "an omitted assumption",
        proposedVector = "a bounded alternative mechanism",
        whyCurrentMapMayMissIt = "the current map fixes one representation",
        falsifiablePrediction = "the alternative improves a held-out artifact",
        minimalTestOrArtifact = "run a two-case test",
        expectedReceiverImpact = "improves exactness",
        expectedCost = 1.0,
        noveltyFingerprint = "fingerprint"
    ).withComputedHash()
}
