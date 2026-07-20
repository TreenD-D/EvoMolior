package com.achulkov.evomolior.substrate.atlas

import com.achulkov.evomolior.core.atlas.AmendmentDecision
import com.achulkov.evomolior.core.atlas.AmendmentType
import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasAmendment
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.DisclosureStage
import com.achulkov.evomolior.core.atlas.DisclosureLineageEntry
import com.achulkov.evomolior.core.atlas.DisclosureRevealAuthorization
import com.achulkov.evomolior.core.atlas.EvidenceKind
import com.achulkov.evomolior.core.atlas.EvidenceProfile
import com.achulkov.evomolior.core.atlas.FinalOutputSpec
import com.achulkov.evomolior.core.atlas.GroupRevealPayload
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.LeaseState
import com.achulkov.evomolior.core.atlas.ReceiverContract
import com.achulkov.evomolior.core.contract.DeliverableArtifactType
import com.achulkov.evomolior.core.contract.DeliverableNodeGradeHistoryEntry
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNode
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNodeStatus
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNodeType
import com.achulkov.evomolior.core.contract.DeliverableSkeletonTree
import com.achulkov.evomolior.core.contract.toRevealedNodeCard
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Increment 1 acceptance coverage for migration, round-trip projections, and transaction-only
 * ownership/disclosure changes.
 */
class AtlasMigrationAndTransactionTest {

    @Test
    fun `concurrent disjoint execution commits receive unique monotonic sequences`() = runTest {
        val migration = assertIs<AtlasMigrationResult.Success>(
            LegacyAtlasMigrationCompiler().compile(legacySnapshot())
        ).migration
        val factory = SubstrateDatabaseFactory(
            createTempDirectory("atlas-concurrent-commits").toFile()
        )
        val repository = AtlasTransactionRepository(factory, now = { 1_000L })
        repository.initialize(migration)
        val atlas = requireNotNull(repository.currentAtlas(RUN_ID, ATLAS_ID))
        assertEquals(atlas, repository.currentAtlas(RUN_ID))
        val projections = requireNotNull(repository.currentProjections(RUN_ID, ATLAS_ID))

        val sequences = buildList {
            repeat(20) {
                val states = repository.currentStates(RUN_ID, ATLAS_ID)
                val requests = listOf(ROOT_ID, LEAF_ID).map { nodeId ->
                    val current = states.getValue(nodeId)
                    AtlasExecutionCommitRequest(
                        runId = RUN_ID,
                        atlasId = ATLAS_ID,
                        expectedAtlasHash = atlas.atlasHash,
                        expectedStateHashes = mapOf(
                            nodeId to AtlasHashing.hashExecutionState(current)
                        ),
                        changedStates = listOf(current.copy(round = current.round + 1)),
                        projections = projections,
                        mutationType = "CONCURRENT_TEST"
                    )
                }
                addAll(
                    coroutineScope {
                        requests.map { request ->
                            async(Dispatchers.Default) {
                                repository.commitExecution(request).commitSequence
                            }
                        }.awaitAll()
                    }
                )
            }
        }

        assertEquals((2L..41L).toSet(), sequences.toSet())
        assertEquals(40, sequences.distinct().size)
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    @Test
    fun `lease authority acquires finalizes and expires without closing or capturing nodes`() = runTest {
        val migration = assertIs<AtlasMigrationResult.Success>(
            LegacyAtlasMigrationCompiler().compile(legacySnapshot())
        ).migration
        val factory = SubstrateDatabaseFactory(createTempDirectory("atlas-lease-authority").toFile())
        val repository = AtlasTransactionRepository(factory, now = { 1_000L })
        repository.initialize(migration)
        val atlas = requireNotNull(repository.currentAtlas(RUN_ID, ATLAS_ID))
        val initial = repository.currentStates(RUN_ID, ATLAS_ID).getValue(ROOT_ID)
        val projections = requireNotNull(repository.currentProjections(RUN_ID, ATLAS_ID))
        val revealed = initial.copy(
            revealedToGroups = initial.revealedToGroups +
                setOf(AtlasGroupId("group-a"), AtlasGroupId("group-b"))
        )
        repository.commitExecution(
            AtlasExecutionCommitRequest(
                runId = RUN_ID,
                atlasId = ATLAS_ID,
                expectedAtlasHash = atlas.atlasHash,
                expectedStateHashes = mapOf(ROOT_ID to AtlasHashing.hashExecutionState(initial)),
                changedStates = listOf(revealed),
                projections = projections,
                mutationType = "TEST_REVEAL"
            )
        )

        var clock = Instant.parse("2026-07-11T10:00:00Z")
        val authority = AtlasLeaseAuthority(repository, now = { clock })
        val lease = authority.acquire(
            runId = RUN_ID,
            atlasId = ATLAS_ID,
            groupId = AtlasGroupId("group-a"),
            agentId = AtlasAgentId("lease-agent-a"),
            nodeId = ROOT_ID,
            round = 2,
            expiresAt = Instant.parse("2026-07-11T10:10:00Z")
        )
        assertEquals(LeaseState.ACTIVE, lease.state)
        assertFailsWith<IllegalArgumentException> {
            authority.acquire(
                runId = RUN_ID,
                atlasId = ATLAS_ID,
                groupId = AtlasGroupId("group-b"),
                agentId = AtlasAgentId("lease-agent-b"),
                nodeId = ROOT_ID,
                round = 2,
                expiresAt = Instant.parse("2026-07-11T10:10:00Z")
            )
        }
        assertFailsWith<IllegalArgumentException> {
            authority.acquire(
                runId = RUN_ID,
                atlasId = ATLAS_ID,
                groupId = AtlasGroupId("unrevealed-group"),
                agentId = AtlasAgentId("lease-agent-hidden"),
                nodeId = ROOT_ID,
                round = 2,
                expiresAt = Instant.parse("2026-07-11T10:10:00Z"),
                replicationSlot = 1
            )
        }

        val committed = authority.finalize(RUN_ID, ATLAS_ID, lease.leaseId, committed = true)
        assertEquals(LeaseState.COMMITTED, committed.state)
        assertEquals(
            committed,
            authority.finalize(RUN_ID, ATLAS_ID, lease.leaseId, committed = true)
        )
        assertFailsWith<IllegalArgumentException> {
            authority.finalize(RUN_ID, ATLAS_ID, lease.leaseId, committed = false)
        }
        val afterCommit = repository.currentStates(RUN_ID, ATLAS_ID).getValue(ROOT_ID)
        assertEquals(initial.grades, afterCommit.grades)
        assertEquals(initial.ownerGroupId, afterCommit.ownerGroupId)
        assertEquals(initial.captureState, afterCommit.captureState)
        assertEquals(
            atlas.nodes.getValue(ROOT_ID).status,
            repository.currentAtlas(RUN_ID, ATLAS_ID)?.nodes?.getValue(ROOT_ID)?.status
        )

        val expiring = authority.acquire(
            runId = RUN_ID,
            atlasId = ATLAS_ID,
            groupId = AtlasGroupId("group-a"),
            agentId = AtlasAgentId("lease-agent-expiring"),
            nodeId = ROOT_ID,
            round = 3,
            expiresAt = Instant.parse("2026-07-11T10:20:00Z")
        )
        clock = Instant.parse("2026-07-11T10:21:00Z")
        assertTrue(
            authority.expireElapsed(RUN_ID, ATLAS_ID).map { it.leaseId }.contains(expiring.leaseId)
        )
        val expiredState = repository.currentStates(RUN_ID, ATLAS_ID).getValue(ROOT_ID)
        assertEquals(
            LeaseState.EXPIRED,
            expiredState.leases.first { it.leaseId == expiring.leaseId }.state
        )
        assertEquals(initial.ownerGroupId, expiredState.ownerGroupId)
        assertEquals(initial.captureState, expiredState.captureState)

        val reacquired = authority.acquire(
            runId = RUN_ID,
            atlasId = ATLAS_ID,
            groupId = AtlasGroupId("group-b"),
            agentId = AtlasAgentId("lease-agent-reacquired"),
            nodeId = ROOT_ID,
            round = 3,
            expiresAt = Instant.parse("2026-07-11T10:40:00Z")
        )
        assertEquals(LeaseState.ACTIVE, reacquired.state)
    }

    @Test
    fun `legacy projections map exactly and round trip ownership grades and dependencies`() = runTest {
        val snapshot = legacySnapshot()
        val migration = assertIs<AtlasMigrationResult.Success>(
            LegacyAtlasMigrationCompiler().compile(snapshot)
        ).migration
        val factory = SubstrateDatabaseFactory(createTempDirectory("atlas-migration").toFile())
        val repository = AtlasTransactionRepository(factory, now = { 100L })

        val initialCommit = repository.initialize(migration)
        val atlas = requireNotNull(repository.currentAtlas(RUN_ID, ATLAS_ID))
        val states = repository.currentStates(RUN_ID, ATLAS_ID)
        val projections = requireNotNull(repository.currentProjections(RUN_ID, ATLAS_ID))

        assertEquals(1L, initialCommit.commitSequence)
        assertEquals(
            snapshot.receiverContract.rawObjective,
            repository.currentReceiverContract(RUN_ID, ATLAS_ID)?.rawObjective
        )
        assertEquals(setOf(ROOT_ID), atlas.rootNodeIds)
        assertEquals(setOf(ROOT_ID), atlas.nodes.getValue(LEAF_ID).parentIds)
        assertEquals(snapshot.wip, projections.wip)
        assertEquals(snapshot.deliverableTrees, projections.deliverables)
        assertEquals(snapshot.resources, projections.resources)
        assertEquals(snapshot.disclosure.groupStageIndices, projections.disclosure.groupStageIndices)
        assertEquals("group-a", states.getValue(LEAF_ID).ownerGroupId?.value)
        assertEquals(2, states.getValue(LEAF_ID).grades.size)
        assertEquals(
            7,
            projections.mappings.size,
            "Every WIP, deliverable, and resource source identity must map exactly once"
        )

        val leafState = states.getValue(LEAF_ID)
        val targetStage = atlas.stageDefinitions.single { it.stageIndex == 1 }
        val revealPayload = GroupRevealPayload(
            runId = atlas.runId,
            atlasId = atlas.atlasId,
            atlasVersion = atlas.version,
            atlasHash = atlas.atlasHash,
            groupId = AtlasGroupId("group-b"),
            round = 2,
            stageId = targetStage.stageId,
            stageIndex = targetStage.stageIndex,
            stagePayloadHash = targetStage.revealPayloadHash,
            revealedNodes = targetStage.nodeIds.sortedBy(NodeId::value)
                .map { atlas.nodes.getValue(it).toRevealedNodeCard() }
        ).withComputedHash()
        val revealAuthorization = DisclosureRevealAuthorization(
            authorizationId = "reveal-group-b-stage-1",
            decisionHash = "decision-hash",
            groupId = AtlasGroupId("group-b"),
            round = 2,
            fromStageId = "stage-0",
            toStageId = targetStage.stageId,
            stagePayloadHash = targetStage.revealPayloadHash,
            deliveredPayloadHash = revealPayload.payloadHash,
            authoritativeBundleHashes = setOf("bundle-hash")
        ).withComputedHash()
        val revealedState = leafState.copy(
            revealedToGroups = leafState.revealedToGroups + AtlasGroupId("group-b"),
            disclosureLineage = leafState.disclosureLineage +
                DisclosureLineageEntry(revealAuthorization)
        )
        val disclosureProjections = projections.copy(
            disclosure = projections.disclosure.copy(
                groupStageIndices = mapOf("group-a" to 1, "group-b" to 1)
            )
        )
        val disclosureCommit = repository.commitExecution(
            AtlasExecutionCommitRequest(
                runId = RUN_ID,
                atlasId = ATLAS_ID,
                expectedAtlasHash = atlas.atlasHash,
                expectedStateHashes = mapOf(
                    LEAF_ID to AtlasHashing.hashExecutionState(leafState)
                ),
                mutationType = AtlasMutationType.DISCLOSURE.name,
                changedStates = listOf(revealedState),
                projections = disclosureProjections
            )
        )
        val nextState = revealedState.copy(
            round = 2,
            ownerGroupId = AtlasGroupId("group-b")
        )
        val nextProjections = disclosureProjections.copy(
            resources = disclosureProjections.resources.copy(
                nodes = projections.resources.nodes.map {
                    it.copy(claimedByGroupId = "group-b")
                }
            )
        )
        val ownershipCommit = repository.commitExecution(
            AtlasExecutionCommitRequest(
                runId = RUN_ID,
                atlasId = ATLAS_ID,
                expectedAtlasHash = atlas.atlasHash,
                expectedStateHashes = mapOf(
                    LEAF_ID to disclosureCommit.stateHashes.getValue(LEAF_ID)
                ),
                mutationType = AtlasMutationType.OWNERSHIP.name,
                changedStates = listOf(nextState),
                projections = nextProjections
            )
        )

        assertEquals(3L, ownershipCommit.commitSequence)
        assertEquals(
            "group-b",
            repository.currentStates(RUN_ID, ATLAS_ID).getValue(LEAF_ID).ownerGroupId?.value
        )
        assertEquals(
            1,
            repository.currentProjections(RUN_ID, ATLAS_ID)
                ?.disclosure
                ?.groupStageIndices
                ?.get("group-b")
        )

        val amendment = AtlasAmendment(
            amendmentId = "amendment-1",
            parentAtlasHash = atlas.atlasHash,
            proposedBy = AtlasAgentId("grader-agent"),
            proposalType = AmendmentType.VALIDATION,
            justificationArtifactIds = listOf("evidence-1"),
            graderDecision = AmendmentDecision.ACCEPTED,
            effectiveRound = 3
        )
        val child = atlas.copy(
            version = 2,
            parentAtlasHash = atlas.atlasHash,
            amendmentIds = listOf(amendment.amendmentId),
            atlasHash = ""
        ).withComputedHash()
        repository.appendAmendment(
            runId = RUN_ID,
            amendment = amendment,
            nextAtlas = child,
            projections = nextProjections
        )

        assertEquals(listOf(1, 2), repository.versionHistory(RUN_ID, ATLAS_ID).map { it.version })
    }

    @Test
    fun `explicit unknown legacy reference returns typed migration error`() {
        val snapshot = legacySnapshot().copy(
            wip = listOf(
                WipProjection(
                    runId = RUN_ID,
                    groupId = "group-a",
                    tasks = listOf(
                        WipProjectionTask(
                            taskId = "broken-task",
                            atlasNodeId = NodeId("ignored-during-input"),
                            description = "Cannot map",
                            lastChangedGeneration = 0,
                            deliverableNodeRef = "missing-node"
                        )
                    )
                )
            )
        )

        val failure = assertIs<AtlasMigrationResult.Failure>(
            LegacyAtlasMigrationCompiler().compile(snapshot)
        )
        assertTrue(
            failure.errors.any {
                it.code == AtlasMigrationErrorCode.MISSING_NODE_MAPPING &&
                    it.sourceNodeId == "broken-task"
            }
        )
    }

    @Test
    fun `authoritative compiled Atlas is preserved exactly without regeneration`() {
        val initialSnapshot = legacySnapshot()
        val authoritativeAtlas = assertIs<AtlasMigrationResult.Success>(
            LegacyAtlasMigrationCompiler().compile(initialSnapshot)
        ).migration.atlas
        val alignedSnapshot = initialSnapshot.copy(
            disclosure = initialSnapshot.disclosure.copy(
                stages = authoritativeAtlas.stageDefinitions
            )
        )

        val prepared = assertIs<AtlasMigrationResult.Success>(
            LegacyAtlasMigrationCompiler().compile(
                snapshot = alignedSnapshot,
                authoritativeAtlas = authoritativeAtlas
            )
        ).migration

        assertSame(authoritativeAtlas, prepared.atlas)
        assertEquals(authoritativeAtlas.atlasHash, prepared.atlas.atlasHash)
        assertEquals(authoritativeAtlas.stageDefinitions, prepared.projections.disclosure.stages)
        assertEquals(authoritativeAtlas.nodes.keys, prepared.executionStates.map { it.nodeId }.toSet())
    }

    @Test
    fun `projection outside authoritative Atlas returns typed mapping failure`() {
        val initialSnapshot = legacySnapshot()
        val authoritativeAtlas = assertIs<AtlasMigrationResult.Success>(
            LegacyAtlasMigrationCompiler().compile(initialSnapshot)
        ).migration.atlas
        val alignedSnapshot = initialSnapshot.copy(
            wip = initialSnapshot.wip.mapIndexed { index, projection ->
                if (index != 0) {
                    projection
                } else {
                    projection.copy(
                        tasks = projection.tasks.map { task ->
                            task.copy(
                                atlasNodeId = NodeId("outside-authoritative-atlas"),
                                deliverableNodeRef = null
                            )
                        }
                    )
                }
            },
            disclosure = initialSnapshot.disclosure.copy(
                stages = authoritativeAtlas.stageDefinitions
            )
        )

        val failure = assertIs<AtlasMigrationResult.Failure>(
            LegacyAtlasMigrationCompiler().compile(
                snapshot = alignedSnapshot,
                authoritativeAtlas = authoritativeAtlas
            )
        )

        assertTrue(
            failure.errors.any {
                it.code == AtlasMigrationErrorCode.MISSING_NODE_MAPPING &&
                    it.source == LegacyNodeSource.WIP &&
                    it.sourceNodeId == "task-a"
            }
        )
    }

    @Test
    fun `non-root deliverable tree root fails authoritative migration`() {
        val initialSnapshot = legacySnapshot()
        val authoritativeAtlas = assertIs<AtlasMigrationResult.Success>(
            LegacyAtlasMigrationCompiler().compile(initialSnapshot)
        ).migration.atlas
        val malformedSnapshot = initialSnapshot.copy(
            deliverableTrees = initialSnapshot.deliverableTrees.map { tree ->
                tree.copy(rootNodeId = LEAF_ID.value)
            },
            disclosure = initialSnapshot.disclosure.copy(
                stages = authoritativeAtlas.stageDefinitions
            )
        )

        val failure = assertIs<AtlasMigrationResult.Failure>(
            LegacyAtlasMigrationCompiler().compile(malformedSnapshot, authoritativeAtlas)
        )

        assertTrue(
            failure.errors.any {
                it.code == AtlasMigrationErrorCode.INVALID_DEPENDENCY &&
                    it.source == LegacyNodeSource.DELIVERABLE &&
                    it.sourceNodeId == LEAF_ID.value
            }
        )
    }

    @Test
    fun `tampered authoritative Atlas hash fails closed`() {
        val initialSnapshot = legacySnapshot()
        val compiledAtlas = assertIs<AtlasMigrationResult.Success>(
            LegacyAtlasMigrationCompiler().compile(initialSnapshot)
        ).migration.atlas
        val alignedSnapshot = initialSnapshot.copy(
            disclosure = initialSnapshot.disclosure.copy(stages = compiledAtlas.stageDefinitions)
        )
        val tamperedAtlas = compiledAtlas.copy(atlasHash = "tampered")

        val failure = assertIs<AtlasMigrationResult.Failure>(
            LegacyAtlasMigrationCompiler().compile(alignedSnapshot, tamperedAtlas)
        )

        assertTrue(
            failure.errors.any {
                it.code == AtlasMigrationErrorCode.INVALID_ATLAS &&
                    "INVALID_ATLAS_HASH" in it.message
            }
        )
    }

    @Test
    fun `deliverable parent mismatch fails authoritative migration`() {
        val initialSnapshot = legacySnapshot()
        val authoritativeAtlas = assertIs<AtlasMigrationResult.Success>(
            LegacyAtlasMigrationCompiler().compile(initialSnapshot)
        ).migration.atlas
        val malformedSnapshot = initialSnapshot.copy(
            deliverableTrees = initialSnapshot.deliverableTrees.map { tree ->
                tree.copy(
                    nodes = tree.nodes.map { node ->
                        if (node.nodeId == LEAF_ID.value) node.copy(parentId = null) else node
                    }
                )
            },
            disclosure = initialSnapshot.disclosure.copy(stages = authoritativeAtlas.stageDefinitions)
        )

        val failure = assertIs<AtlasMigrationResult.Failure>(
            LegacyAtlasMigrationCompiler().compile(malformedSnapshot, authoritativeAtlas)
        )

        assertTrue(
            failure.errors.any {
                it.code == AtlasMigrationErrorCode.INVALID_DEPENDENCY &&
                    it.source == LegacyNodeSource.DELIVERABLE &&
                    it.sourceNodeId == LEAF_ID.value
            }
        )
    }

    private fun legacySnapshot(): LegacyAtlasSnapshot {
        val groupATree = deliverableTree(
            groupId = "group-a",
            ownerAgentId = "agent-a",
            passed = true,
            score = 0.9
        )
        val groupBTree = deliverableTree(
            groupId = "group-b",
            ownerAgentId = "agent-b",
            passed = false,
            score = 0.4
        )
        val stages = listOf(
            DisclosureStage(
                stageId = "stage-0",
                stageIndex = 0,
                title = "Public root",
                nodeIds = setOf(ROOT_ID),
                mandatoryNodeIds = setOf(ROOT_ID),
                evidenceCoverageThreshold = 0.0,
                integrationReadinessThreshold = 0.0,
                revealPayloadHash = ""
            ),
            DisclosureStage(
                stageId = "stage-1",
                stageIndex = 1,
                title = "Evidence work",
                nodeIds = setOf(LEAF_ID),
                mandatoryNodeIds = setOf(LEAF_ID),
                evidenceCoverageThreshold = 0.7,
                integrationReadinessThreshold = 0.6,
                revealPayloadHash = ""
            )
        )
        return LegacyAtlasSnapshot(
            atlasId = ATLAS_ID,
            runId = RUN_ID,
            receiverContract = receiverContract(),
            deliverableTrees = listOf(groupATree, groupBTree),
            wip = listOf(
                WipProjection(
                    runId = RUN_ID,
                    groupId = "group-a",
                    tasks = listOf(
                        WipProjectionTask(
                            taskId = "task-a",
                            atlasNodeId = LEAF_ID,
                            description = "Resolve the evidence gap",
                            claimedByAgentId = "agent-a",
                            status = "completed",
                            lastChangedGeneration = 1,
                            linkedNodeId = "resource-evidence",
                            deliverableNodeRef = LEAF_ID.value
                        )
                    )
                ),
                WipProjection(
                    runId = RUN_ID,
                    groupId = "group-b",
                    tasks = listOf(
                        WipProjectionTask(
                            taskId = "task-b",
                            atlasNodeId = LEAF_ID,
                            description = "Resolve the evidence gap",
                            claimedByAgentId = "agent-b",
                            status = "claimed",
                            lastChangedGeneration = 1,
                            abandonCount = 1,
                            linkedNodeId = "resource-evidence",
                            deliverableNodeRef = LEAF_ID.value
                        )
                    )
                )
            ),
            resources = ResourceProjection(
                runId = RUN_ID,
                nodes = listOf(
                    ResourceProjectionNode(
                        resourceNodeId = "resource-evidence",
                        atlasNodeId = LEAF_ID,
                        name = "Evidence cache",
                        resourceType = "WEB_CACHE",
                        yieldMultiplier = 1.2,
                        deliverableNodeRef = LEAF_ID.value,
                        scoringDimensionIds = listOf("evidence-quality"),
                        qualityThreshold = 0.7,
                        claimedByGroupId = "group-a"
                    )
                )
            ),
            disclosure = DisclosureProjection(
                runId = RUN_ID,
                stages = stages,
                groupStageIndices = mapOf("group-a" to 1, "group-b" to 0),
                groupLeakMultipliers = mapOf("group-a" to 1.0, "group-b" to 2.0)
            )
        )
    }

    private fun deliverableTree(
        groupId: String,
        ownerAgentId: String,
        passed: Boolean,
        score: Double
    ) = DeliverableSkeletonTree(
        runId = RUN_ID,
        groupId = groupId,
        rootNodeId = ROOT_ID.value,
        nodes = listOf(
            DeliverableSkeletonNode(
                nodeId = ROOT_ID.value,
                children = listOf(LEAF_ID.value),
                nodeType = DeliverableSkeletonNodeType.ROOT,
                title = "Receiver decision",
                owningRole = Role.Planner,
                createdAtGeneration = 0,
                updatedAtGeneration = 0
            ),
            DeliverableSkeletonNode(
                nodeId = LEAF_ID.value,
                parentId = ROOT_ID.value,
                nodeType = DeliverableSkeletonNodeType.DOCUMENT_SECTION,
                title = "Evidence limitation",
                status = if (passed) {
                    DeliverableSkeletonNodeStatus.COMPLETE
                } else {
                    DeliverableSkeletonNodeStatus.CONTESTED
                },
                currentArtifactRef = "kb:$ownerAgentId",
                artifactVersion = 1,
                artifactType = DeliverableArtifactType.KB_DOC,
                owningGeneration = 1,
                owningAgentId = ownerAgentId,
                owningRole = Role.Researcher,
                trustScore = score,
                gradingHistory = listOf(
                    DeliverableNodeGradeHistoryEntry(
                        checkinId = "checkin-$groupId",
                        dimensionScores = mapOf("evidence-quality" to score),
                        graderIds = listOf("grader-0", "grader-1"),
                        rubricVersion = "v1",
                        passedThreshold = passed,
                        recordedAtMs = 10L
                    )
                ),
                createdAtGeneration = 0,
                updatedAtGeneration = 1
            )
        ),
        version = 2,
        createdAtMs = 1L,
        updatedAtMs = 10L
    )

    private fun receiverContract() = ReceiverContract(
        rawObjective = "  Preserve this exact operator objective.  ",
        receiverId = "operator",
        receiverDescription = "Decision maker",
        situation = "A consequential implementation choice",
        decisionOrUse = "Select an implementation",
        whyNeeded = "Resolve material uncertainty",
        successOutcomes = listOf("Auditable recommendation"),
        unacceptableOutcomes = listOf("Unsupported answer"),
        constraints = listOf("Keep exact objective bytes"),
        finalDeliverableContract = FinalOutputSpec(
            relativePath = "result.md",
            mediaType = "text/markdown",
            requiredSections = setOf("recommendation", "evidence")
        ),
        evidenceProfile = EvidenceProfile(
            acceptedEvidenceKinds = setOf(EvidenceKind.PRIMARY_SOURCE),
            requirePrimarySources = true
        )
    )

    private companion object {
        const val RUN_ID = "run-atlas-roundtrip"
        const val ATLAS_ID = "atlas-run-atlas-roundtrip"
        val ROOT_ID = NodeId("dst:root")
        val LEAF_ID = NodeId("dst:evidence")
    }
}
