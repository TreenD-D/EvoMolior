package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.competition.atlas.AtlasDisclosureStageAuthority
import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.disclosure.DisclosureAtlasProjectionAdapter
import com.achulkov.evomolior.competition.disclosure.ProgressiveDisclosureEngine
import com.achulkov.evomolior.competition.grader.ExactGraderRoundStatus
import com.achulkov.evomolior.competition.grader.ExactGraderRoundVerdict
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.CapturePolicy
import com.achulkov.evomolior.core.atlas.DisclosureStage
import com.achulkov.evomolior.core.atlas.EvidenceKind
import com.achulkov.evomolior.core.atlas.EvidenceProfile
import com.achulkov.evomolior.core.atlas.FinalOutputSpec
import com.achulkov.evomolior.core.atlas.GroupRevealPayload
import com.achulkov.evomolior.core.atlas.LimitationNode
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.NodeDeliverableContract
import com.achulkov.evomolior.core.atlas.NodeExecutionState
import com.achulkov.evomolior.core.atlas.NodeGradeBundle
import com.achulkov.evomolior.core.atlas.NodeGradeBundleVerdict
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.NodeScoreVector
import com.achulkov.evomolior.core.atlas.ProofContract
import com.achulkov.evomolior.core.atlas.ProofMethod
import com.achulkov.evomolior.core.atlas.ReceiverContract
import com.achulkov.evomolior.core.atlas.ReplicationPolicy
import com.achulkov.evomolior.core.atlas.RevealMode
import com.achulkov.evomolior.core.atlas.RevealPolicy
import com.achulkov.evomolior.core.atlas.RewardClass
import com.achulkov.evomolior.core.atlas.RewardSpec
import com.achulkov.evomolior.core.contract.toRevealedNodeCard
import com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.atlas.AtlasProjectionSet
import com.achulkov.evomolior.substrate.atlas.AtlasTransactionRepository
import com.achulkov.evomolior.substrate.atlas.DisclosureProjection
import com.achulkov.evomolior.substrate.atlas.PreparedAtlasMigration
import com.achulkov.evomolior.substrate.atlas.ResourceProjection
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvoMoliorRunServiceHistoricalDisclosureTest {
    private val runId = "run-historical-disclosure"
    private val atlasId = "atlas-historical-disclosure"
    private val groupA = AtlasGroupId("group-a")
    private val groupB = AtlasGroupId("group-b")
    private val foundationId = NodeId("foundation")
    private val comparisonId = NodeId("comparison")
    private val futureId = NodeId("future")
    private val horizonId = NodeId("horizon")

    @Test
    fun `production checkin combines prior passing bundles with current exact authority`() = runTest {
        val factory = SubstrateDatabaseFactory(
            createTempDirectory("historical-disclosure-").toFile()
        )
        val migration = migration()
        val repository = AtlasTransactionRepository(factory)
        repository.initialize(migration)
        val service = service(factory)
        val engine = ProgressiveDisclosureEngine(
            stages = DisclosureAtlasProjectionAdapter.fromProjection(
                migration.projections.disclosure
            ),
            stageAuthority = AtlasDisclosureStageAuthority(runId, atlasId, repository)
        ).also {
            it.registerGroup(groupA.value)
            it.registerGroup(groupB.value)
        }
        service.setField("activeDisclosureEngine", engine)
        service.setField("activeWorkerAtlasId", atlasId)

        val historicalPass = bundle(migration.atlas, groupA, foundationId, 0)
        val currentAFoundation = bundle(
            migration.atlas,
            groupA,
            foundationId,
            1,
            NodeGradeBundleVerdict.NO_EVIDENCE
        )
        val currentAComparison = bundle(migration.atlas, groupA, comparisonId, 1)
        val currentBFoundation = bundle(
            migration.atlas,
            groupB,
            foundationId,
            1,
            NodeGradeBundleVerdict.NO_EVIDENCE
        )
        val currentBComparison = bundle(
            migration.atlas,
            groupB,
            comparisonId,
            1,
            NodeGradeBundleVerdict.NO_EVIDENCE
        )
        service.mapField<Int, List<NodeGradeBundle>>("nodeGradeBundlesByRound").apply {
            this[0] = listOf(historicalPass)
            this[1] = listOf(
                currentBComparison,
                currentAComparison,
                currentBFoundation,
                currentAFoundation
            )
        }
        service.mapField<Int, ExactGraderRoundVerdict>("exactGraderVerdictsByRound")[1] =
            ExactGraderRoundVerdict(
                status = ExactGraderRoundStatus.VALID,
                acceptedBundleHashes = setOf("exact-round-1"),
                targetSnapshotHashes = mapOf(groupA to "snapshot-a", groupB to "snapshot-b"),
                nodeScores = emptyMap(),
                groupScores = emptyMap(),
                maximumNodeDisagreement = 0.0
            )

        service::class.declaredFunctions
            .first { it.name == "processDisclosureCheckIns" }
            .apply { isAccessible = true }
            .callSuspend(service, runId, listOf(groupA.value, groupB.value), 1)

        val projections = requireNotNull(repository.currentProjections(runId, atlasId))
        assertEquals(1, projections.disclosure.groupStageIndices.getValue(groupA.value))
        assertEquals(0, projections.disclosure.groupStageIndices.getValue(groupB.value))
        assertEquals(1, engine.currentStage(groupA.value).stageIndex)
        assertEquals(0, engine.currentStage(groupB.value).stageIndex)

        val futureState = repository.currentStates(runId, atlasId).getValue(futureId)
        assertEquals(setOf(groupA), futureState.revealedToGroups)
        val authorization = futureState.disclosureLineage.single().authorization
        assertEquals(
            setOf(
                historicalPass.bundleHash,
                currentAFoundation.bundleHash,
                currentAComparison.bundleHash
            ),
            authorization.authoritativeBundleHashes
        )
        assertTrue(authorization.hasValidHash())
        assertEquals(listOf(historicalPass), service.disclosureNodeGradeBundlesThrough(0))
        assertEquals(
            listOf(
                historicalPass,
                currentAComparison,
                currentAFoundation,
                currentBComparison,
                currentBFoundation
            ),
            service.disclosureNodeGradeBundlesThrough(1)
        )
    }

    @Test
    fun `AtlasPayloadCommitmentTest every revealed payload matches its round zero commitment`() =
        runTest {
            val factory = SubstrateDatabaseFactory(
                createTempDirectory("atlas-payload-commitment-").toFile()
            )
            val migration = migration()
            val repository = AtlasTransactionRepository(factory)
            repository.initialize(migration)
            val roundZeroAtlas = requireNotNull(repository.currentAtlas(runId, atlasId))
            assertEquals(migration.atlas, roundZeroAtlas)

            val service = service(factory)
            val engine = ProgressiveDisclosureEngine(
                stages = DisclosureAtlasProjectionAdapter.fromProjection(
                    migration.projections.disclosure
                ),
                stageAuthority = AtlasDisclosureStageAuthority(runId, atlasId, repository)
            ).also {
                it.registerGroup(groupA.value)
                it.registerGroup(groupB.value)
            }
            service.setField("activeDisclosureEngine", engine)
            service.setField("activeWorkerAtlasId", atlasId)

            val stageZeroBundles = listOf(
                bundle(roundZeroAtlas, groupA, foundationId, 1),
                bundle(roundZeroAtlas, groupA, comparisonId, 1)
            )
            service.mapField<Int, List<NodeGradeBundle>>("nodeGradeBundlesByRound")[1] =
                stageZeroBundles
            service.setExactValidVerdict(round = 1)
            service.processDisclosureCheckIns(round = 1)

            val stageOneBundles = listOf(bundle(roundZeroAtlas, groupA, futureId, 2))
            service.mapField<Int, List<NodeGradeBundle>>("nodeGradeBundlesByRound")[2] =
                stageOneBundles
            service.setExactValidVerdict(round = 2)
            service.processDisclosureCheckIns(round = 2)

            assertEquals(2, engine.currentStage(groupA.value).stageIndex)
            assertEquals(
                2,
                requireNotNull(repository.currentProjections(runId, atlasId))
                    .disclosure.groupStageIndices.getValue(groupA.value)
            )
            assertEquals(roundZeroAtlas, repository.currentAtlas(runId, atlasId))

            val states = repository.currentStates(runId, atlasId)
            val revealAuthorizations = listOf(futureId, horizonId).map { nodeId ->
                states.getValue(nodeId).also { state ->
                    assertEquals(setOf(groupA), state.revealedToGroups)
                }.disclosureLineage.single().authorization
            }
            assertEquals(listOf("stage-1", "stage-2"), revealAuthorizations.map { it.toStageId })

            revealAuthorizations.forEach { authorization ->
                assertTrue(authorization.hasValidHash())
                val stage = roundZeroAtlas.stageDefinitions.single {
                    it.stageId == authorization.toStageId
                }
                val expectedCards = stage.nodeIds
                    .sortedBy(NodeId::value)
                    .map { roundZeroAtlas.nodes.getValue(it).toRevealedNodeCard() }
                val reconstructed = GroupRevealPayload(
                    runId = roundZeroAtlas.runId,
                    atlasId = roundZeroAtlas.atlasId,
                    atlasVersion = roundZeroAtlas.version,
                    atlasHash = roundZeroAtlas.atlasHash,
                    groupId = authorization.groupId,
                    round = authorization.round,
                    stageId = stage.stageId,
                    stageIndex = stage.stageIndex,
                    stagePayloadHash = authorization.stagePayloadHash,
                    revealedNodes = expectedCards
                ).withComputedHash()

                assertEquals(
                    AtlasHashing.hashDisclosurePayload(
                        stage.stageId,
                        stage.nodeIds,
                        roundZeroAtlas.nodes
                    ),
                    stage.revealPayloadHash
                )
                assertEquals(stage.revealPayloadHash, authorization.stagePayloadHash)
                assertEquals(
                    stage.nodeIds,
                    reconstructed.revealedNodes.mapTo(linkedSetOf()) { it.nodeId }
                )
                assertEquals(expectedCards, reconstructed.revealedNodes)
                assertTrue(reconstructed.revealedNodes.all { card ->
                    card == roundZeroAtlas.nodes.getValue(card.nodeId).toRevealedNodeCard() &&
                        card.payloadHash == roundZeroAtlas.nodes.getValue(card.nodeId).payloadHash
                })
                assertTrue(reconstructed.hasValidHashes(roundZeroAtlas))
                assertEquals(authorization.deliveredPayloadHash, reconstructed.payloadHash)

                val tamperedNodeId = stage.nodeIds.minBy(NodeId::value)
                val tamperedNode = roundZeroAtlas.nodes.getValue(tamperedNodeId)
                    .copy(limitationStatement = "Tampered hidden limitation.")
                    .withComputedPayloadHash()
                val tamperedNodes = roundZeroAtlas.nodes + (tamperedNodeId to tamperedNode)
                assertFalse(
                    AtlasHashing.constantTimeEquals(
                        authorization.stagePayloadHash,
                        AtlasHashing.hashDisclosurePayload(stage.stageId, stage.nodeIds, tamperedNodes)
                    )
                )
                val tampered = reconstructed.copy(
                    revealedNodes = reconstructed.revealedNodes.map { card ->
                        if (card.nodeId == tamperedNodeId) {
                            tamperedNode.toRevealedNodeCard()
                        } else {
                            card
                        }
                    }
                ).withComputedHash()
                assertFalse(tampered.hasValidHashes(roundZeroAtlas))
                assertFalse(
                    AtlasHashing.constantTimeEquals(
                        authorization.deliveredPayloadHash,
                        tampered.payloadHash
                    )
                )
            }

            assertEquals(
                setOf(
                    stageZeroBundles.single { it.nodeId == foundationId }.bundleHash,
                    stageZeroBundles.single { it.nodeId == comparisonId }.bundleHash
                ),
                revealAuthorizations[0].authoritativeBundleHashes
            )
            assertEquals(
                setOf(stageOneBundles.single().bundleHash),
                revealAuthorizations[1].authoritativeBundleHashes
            )
        }

    private fun service(factory: SubstrateDatabaseFactory) = EvoMoliorRunService(
        lifecycleManagerProvider = { _, _, _, _, _ ->
            error("LifecycleManager is outside this wiring regression")
        },
        groupCoordinatorProvider = { groupIds, dimensions ->
            GroupCoordinator(groupIds, dimensions)
        },
        graderGroupManagerProvider = ::GraderGroupManager,
        substrateFactory = factory,
        metaPlannerRunner = MetaPlannerRunner()
    )

    private fun migration(): PreparedAtlasMigration {
        val receiver = ReceiverContract(
            rawObjective = "Produce a verified recommendation.",
            receiverId = "receiver",
            receiverDescription = "Operator",
            situation = "Decision",
            decisionOrUse = "Choose",
            whyNeeded = "Reduce uncertainty",
            successOutcomes = listOf("Verified"),
            unacceptableOutcomes = listOf("Unsupported"),
            constraints = listOf("Auditable"),
            finalDeliverableContract = FinalOutputSpec("result.json", "application/json"),
            evidenceProfile = EvidenceProfile(
                acceptedEvidenceKinds = setOf(EvidenceKind.PRIMARY_SOURCE)
            )
        )
        val foundation = node(foundationId, "stage-0", 0)
        val comparison = node(comparisonId, "stage-0", 0)
        val future = node(futureId, "stage-1", 1)
        val horizon = node(horizonId, "stage-2", 2)
        val nodes = listOf(foundation, comparison, future, horizon).associateBy(LimitationNode::id)
        val initialIds = setOf(foundationId, comparisonId)
        val stages = listOf(
            stage("stage-0", 0, initialIds, nodes),
            stage("stage-1", 1, setOf(futureId), nodes),
            stage("stage-2", 2, setOf(horizonId), nodes)
        )
        val atlas = NodeAtlas(
            atlasId = atlasId,
            runId = runId,
            version = 1,
            receiverContractHash = AtlasHashing.hashReceiverContract(receiver),
            rootNodeIds = initialIds,
            nodes = nodes,
            stageDefinitions = stages
        ).withComputedHash()
        val groups = listOf(groupA, groupB)
        return PreparedAtlasMigration(
            receiverContract = receiver,
            atlas = atlas,
            executionStates = nodes.values.map { node ->
                NodeExecutionState(
                    nodeId = node.id,
                    round = 0,
                    revealedToGroups = if (node.id in initialIds) groups.toSet() else emptySet()
                )
            },
            projections = AtlasProjectionSet(
                wip = emptyList(),
                deliverables = emptyList(),
                resources = ResourceProjection(runId, emptyList()),
                disclosure = DisclosureProjection(
                    runId = runId,
                    stages = stages,
                    groupStageIndices = groups.associate { it.value to 0 }
                ),
                mappings = emptyList()
            )
        )
    }

    private fun node(id: NodeId, stageId: String, layer: Int) = LimitationNode(
        id = id,
        kind = if (layer == 0) NodeKind.EVIDENCE else NodeKind.DELIVERY,
        layer = layer,
        parentIds = if (layer == 0) emptySet() else setOf(foundationId, comparisonId),
        prerequisiteIds = if (layer == 0) emptySet() else setOf(foundationId, comparisonId),
        receiverWhyRef = "receiver-$id",
        limitationStatement = "Resolve $id.",
        capabilityTarget = "Establish $id.",
        atomicQuestion = "What proves $id?",
        proofContract = ProofContract(
            method = ProofMethod.SOURCE_CORROBORATION,
            acceptanceCriteria = setOf("Exact evidence"),
            requiredEvidenceKinds = setOf(EvidenceKind.PRIMARY_SOURCE)
        ),
        deliverableContract = NodeDeliverableContract(
            acceptedArtifactTypes = setOf(
                if (layer == 0) ArtifactType.EVIDENCE_TABLE else ArtifactType.FINAL_DELIVERABLE
            )
        ),
        revealPolicy = RevealPolicy(
            stageId = stageId,
            mode = if (layer == 0) RevealMode.INITIAL else RevealMode.STAGE_PASS
        ),
        replicationPolicy = ReplicationPolicy(),
        capturePolicy = CapturePolicy(minimumGrade = 0.7),
        rewardSpec = RewardSpec(RewardClass.SMALL, 1.0)
    ).withComputedPayloadHash()

    private fun stage(
        id: String,
        index: Int,
        nodeIds: Set<NodeId>,
        nodes: Map<NodeId, LimitationNode>
    ) = DisclosureStage(
        stageId = id,
        stageIndex = index,
        title = id,
        nodeIds = nodeIds,
        mandatoryNodeIds = nodeIds,
        evidenceCoverageThreshold = 1.0,
        integrationReadinessThreshold = 0.7,
        revealPayloadHash = AtlasHashing.hashDisclosurePayload(id, nodeIds, nodes)
    )

    private fun bundle(
        atlas: NodeAtlas,
        groupId: AtlasGroupId,
        nodeId: NodeId,
        round: Int,
        verdict: NodeGradeBundleVerdict = NodeGradeBundleVerdict.VALID_PASS
    ): NodeGradeBundle {
        val node = atlas.nodes.getValue(nodeId)
        val hasEvidence = verdict != NodeGradeBundleVerdict.NO_EVIDENCE
        return NodeGradeBundle(
            bundleId = "bundle-${groupId.value}-${nodeId.value}-$round",
            runId = runId,
            round = round,
            groupId = groupId,
            nodeId = nodeId,
            atlasVersion = atlas.version,
            atlasHash = atlas.atlasHash,
            nodePayloadHash = node.payloadHash,
            evidencePacketId = "packet-${groupId.value}-${nodeId.value}-$round".takeIf {
                hasEvidence
            },
            evidencePacketHash = "packet-hash-${groupId.value}-${nodeId.value}-$round".takeIf {
                hasEvidence
            },
            criteriaHash = "criteria-${nodeId.value}",
            capturePolicyHash = AtlasHashing.hashCapturePolicy(node.capturePolicy),
            scoreVector = vector().takeIf { hasEvidence },
            normalizedScore = 0.9.takeIf { hasEvidence },
            verdict = verdict,
            requiredQuorum = 2,
            graderIds = setOf("grader-a", "grader-b"),
            completedGraderIds = setOf("grader-a", "grader-b"),
            graderResponseHashes = setOf(
                "response-a-${groupId.value}-${nodeId.value}-$round",
                "response-b-${groupId.value}-${nodeId.value}-$round"
            )
        ).withComputedHash()
    }

    private fun vector() = NodeScoreVector(
        correctness = 0.9,
        evidenceQuality = 0.9,
        reproducibility = 0.9,
        robustness = 0.9,
        integrationUtility = 0.9,
        receiverRelevance = 0.9,
        novelty = 0.8,
        efficiency = 0.8,
        hardFailure = false,
        confidence = 0.9
    )

    private fun EvoMoliorRunService.setField(name: String, value: Any) {
        javaClass.getDeclaredField(name)
            .apply { isAccessible = true }
            .set(this, value)
    }

    private fun EvoMoliorRunService.setExactValidVerdict(round: Int) {
        mapField<Int, ExactGraderRoundVerdict>("exactGraderVerdictsByRound")[round] =
            ExactGraderRoundVerdict(
                status = ExactGraderRoundStatus.VALID,
                acceptedBundleHashes = setOf("exact-round-$round"),
                targetSnapshotHashes = mapOf(groupA to "snapshot-a-$round"),
                nodeScores = emptyMap(),
                groupScores = emptyMap(),
                maximumNodeDisagreement = 0.0
            )
    }

    private suspend fun EvoMoliorRunService.processDisclosureCheckIns(round: Int) {
        this::class.declaredFunctions
            .first { it.name == "processDisclosureCheckIns" }
            .apply { isAccessible = true }
            .callSuspend(this, runId, listOf(groupA.value), round)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <K, V> EvoMoliorRunService.mapField(name: String): MutableMap<K, V> =
        javaClass.getDeclaredField(name)
            .apply { isAccessible = true }
            .get(this) as MutableMap<K, V>
}
