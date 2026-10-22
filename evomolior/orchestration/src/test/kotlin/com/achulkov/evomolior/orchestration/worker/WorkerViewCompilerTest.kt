package com.achulkov.evomolior.orchestration.worker

import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.CapturePolicy
import com.achulkov.evomolior.core.atlas.DisclosureStage
import com.achulkov.evomolior.core.atlas.EvidenceKind
import com.achulkov.evomolior.core.atlas.GradeVerdict
import com.achulkov.evomolior.core.atlas.LimitationNode
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.NodeDeliverableContract
import com.achulkov.evomolior.core.atlas.NodeExecutionState
import com.achulkov.evomolior.core.atlas.NodeGrade
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.ProofContract
import com.achulkov.evomolior.core.atlas.ProofMethod
import com.achulkov.evomolior.core.atlas.ReplicationPolicy
import com.achulkov.evomolior.core.atlas.ReplicationMode
import com.achulkov.evomolior.core.atlas.RevealMode
import com.achulkov.evomolior.core.atlas.RevealPolicy
import com.achulkov.evomolior.core.atlas.RewardClass
import com.achulkov.evomolior.core.atlas.RewardSpec
import com.achulkov.evomolior.core.atlas.ToolBehavior
import com.achulkov.evomolior.core.atlas.ToolExecutionPurpose
import com.achulkov.evomolior.core.atlas.ToolImplementationMode
import com.achulkov.evomolior.core.atlas.ToolOpportunity
import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitment
import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitmentAuthority
import com.achulkov.evomolior.core.atlas.ToolOpportunityType
import com.achulkov.evomolior.core.atlas.TypedField
import com.achulkov.evomolior.core.atlas.TypedFieldType
import com.achulkov.evomolior.core.contract.WorkerToolCard
import com.achulkov.evomolior.core.contract.WorkerToolRepairDiagnostic
import com.achulkov.evomolior.core.repair.ApproachVector
import com.achulkov.evomolior.core.repair.RepairStrategy
import com.achulkov.evomolior.core.repair.ToolRepairCode
import com.achulkov.evomolior.core.repair.ToolRepairSource
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WorkerViewCompilerTest {
    private val compiler = WorkerViewCompiler()
    private val allocator = NodePortfolioAllocator()
    private val groupA = AtlasGroupId("group-a")
    private val groupB = AtlasGroupId("group-b")

    @Test
    fun `hidden payloads remain commitments until group reveal`() {
        val atlas = atlas()
        val states = states(atlas, initiallyRevealedTo = setOf(groupA))

        val view = compiler.compile(atlas, states, groupA, round = 0)
        val serialized = Json.encodeToString(view)

        assertTrue(view.hasValidHash())
        assertEquals(setOf(NodeId("foundation"), NodeId("alternate")), view.revealedNodes.map { it.nodeId }.toSet())
        assertFalse(serialized.contains(FUTURE_LIMITATION))
        assertFalse(serialized.contains(FUTURE_QUESTION))
        assertFalse(serialized.contains(FINAL_OUTPUT_SENTINEL))
        assertTrue(serialized.contains(atlas.nodes.getValue(NodeId("future")).payloadHash))
        assertTrue(serialized.contains("\"future\""))

        assertFailsWith<IllegalArgumentException> {
            compiler.compile(atlas, states, groupA, round = 0, assignedNodeId = NodeId("future"))
        }

        val revealedStates = states.toMutableMap().apply {
            this[NodeId("future")] = getValue(NodeId("future")).copy(revealedToGroups = setOf(groupA))
        }
        val revealed = compiler.compile(
            atlas,
            revealedStates,
            groupA,
            round = 1,
            assignedNodeId = NodeId("future")
        )
        assertEquals(FUTURE_LIMITATION, revealed.assignedNode?.limitationStatement)
        assertEquals("stage-1", revealed.stageId)

        val stillHiddenFromB = compiler.compile(atlas, revealedStates, groupB, round = 1)
        assertFalse(Json.encodeToString(stillHiddenFromB).contains(FUTURE_LIMITATION))
    }

    @Test
    fun `view hashes are stable and detect tampering`() {
        val atlas = atlas()
        val states = states(atlas, initiallyRevealedTo = setOf(groupA))

        val first = compiler.compile(atlas, states, groupA, round = 0)
        val second = compiler.compile(
            atlas,
            states.entries.reversed().associate { it.toPair() },
            groupA,
            round = 0
        )

        assertEquals(first.viewHash, second.viewHash)
        assertFalse(first.copy(stageId = "tampered").hasValidHash())
        assertNotEquals(first.viewHash, first.copy(round = 1).withComputedHash().viewHash)
        assertFailsWith<IllegalArgumentException> {
            compiler.compile(atlas, states, groupA, round = 0, outlierMandate = "novelty", assignedNodeId = NodeId("foundation"))
        }
    }

    @Test
    fun `assigned tool node receives exact generated skeleton without widening other views`() {
        val opportunity = toolOpportunity()
        val atlas = atlas(opportunity)
        val states = states(atlas, initiallyRevealedTo = setOf(groupA))
        val opportunityCommitments = ToolOpportunityCommitmentAuthority.current(atlas)
            .byOpportunityId
        assertFailsWith<IllegalArgumentException> {
            compiler.compile(
                atlas,
                states,
                groupA,
                round = 0,
                assignedNodeId = NodeId("foundation")
            )
        }

        val unassigned = compiler.compile(
            atlas,
            states,
            groupA,
            round = 0,
            opportunityCommitments = opportunityCommitments
        )
        val ordinaryNode = compiler.compile(
            atlas,
            states,
            groupA,
            round = 0,
            assignedNodeId = NodeId("alternate"),
            opportunityCommitments = opportunityCommitments
        )
        val outlier = compiler.compile(
            atlas,
            states,
            groupA,
            round = 0,
            outlierMandate = "Propose one connected novelty.",
            opportunityCommitments = opportunityCommitments
        )
        val assignedTool = compiler.compile(
            atlas,
            states,
            groupA,
            round = 0,
            assignedNodeId = NodeId("foundation"),
            opportunityCommitments = opportunityCommitments
        )

        assertEquals(null, unassigned.assignedToolSkeleton)
        assertEquals(null, ordinaryNode.assignedToolSkeleton)
        assertEquals(null, outlier.assignedToolSkeleton)
        val skeleton = requireNotNull(assignedTool.assignedToolSkeleton)
        val expectedCommitment = ToolOpportunityCommitment(
            runId = atlas.runId,
            atlasHash = atlas.atlasHash,
            nodeId = NodeId("foundation"),
            opportunity = opportunity
        ).withComputedHash()
        assertTrue(skeleton.hasValidHash())
        assertEquals(expectedCommitment.commitmentHash, skeleton.opportunityCommitmentHash)
        assertEquals(ToolImplementationMode.PYTHON, skeleton.implementationMode)
        assertEquals(
            setOf("tool.py", "contract.json", "promotion-tests.json"),
            skeleton.files.mapTo(linkedSetOf()) { it.relativePath }
        )
        assertTrue(assignedTool.hasValidHash())
        assertFailsWith<IllegalArgumentException> {
            assignedTool.copy(assignedToolOpportunityCommitmentHash = null)
        }
        assertFailsWith<IllegalArgumentException> {
            assignedTool.copy(
                assignedToolSkeleton = skeleton.copy(skeletonHash = "tampered")
            )
        }
        assertFailsWith<IllegalArgumentException> {
            assignedTool.copy(assignedToolOpportunityOriginAtlasHash = "wrong-origin")
        }
    }

    @Test
    fun `assigned tool skeleton retains origin commitment after Atlas amendment`() {
        val opportunity = toolOpportunity()
        val parent = atlas(opportunity)
        val child = parent.copy(
            version = 2,
            parentAtlasHash = parent.atlasHash,
            amendmentIds = listOf("amendment-1"),
            atlasHash = ""
        ).withComputedHash()
        val originCommitments = ToolOpportunityCommitmentAuthority
            .resolve(listOf(parent, child))
            .byOpportunityId

        val view = compiler.compile(
            atlas = child,
            states = states(child, initiallyRevealedTo = setOf(groupA)),
            groupId = groupA,
            round = 1,
            assignedNodeId = NodeId("foundation"),
            opportunityCommitments = originCommitments
        )

        val skeleton = requireNotNull(view.assignedToolSkeleton)
        val origin = originCommitments.getValue(opportunity.opportunityId)
        assertEquals(parent.atlasHash, origin.atlasHash)
        assertEquals(origin.commitmentHash, skeleton.opportunityCommitmentHash)
        assertEquals(parent.atlasHash, view.assignedToolOpportunityOriginAtlasHash)
        assertEquals(origin.commitmentHash, view.assignedToolOpportunityCommitmentHash)
        assertTrue(view.hasValidHash())
    }

    @Test
    fun `tool repair diagnostics are hash bound to the exact prior authoring opportunity`() {
        val opportunity = toolOpportunity()
        val atlas = atlas(opportunity)
        val states = states(atlas, initiallyRevealedTo = setOf(groupA))
        val commitment = ToolOpportunityCommitmentAuthority.current(atlas)
            .byOpportunityId.getValue(opportunity.opportunityId)
        val opportunityCommitments = mapOf(opportunity.opportunityId to commitment)
        val diagnostic = WorkerToolRepairDiagnostic(
            diagnosticId = "repair-tool-1",
            runId = atlas.runId,
            groupId = groupA,
            opportunityId = opportunity.opportunityId,
            opportunityCommitmentHash = commitment.commitmentHash,
            authoringNodeId = NodeId("foundation"),
            source = ToolRepairSource.PRIVATE_PROMOTION_AUTHORITY,
            code = ToolRepairCode.OUTPUT_SEMANTICS_MISMATCH,
            createdRound = 0,
            visibleFromRound = 1,
            affectedPublicField = "ratio",
            diagnosticHash = "d".repeat(64)
        )
        val approach = ApproachVector(
            vectorId = "approach-run-atlas-group-a-1",
            runId = atlas.runId,
            groupId = groupA.value,
            opportunityId = opportunity.opportunityId,
            round = 1,
            lineageAttempt = 1,
            strategy = RepairStrategy.DIMENSIONAL_ANALYSIS_FIRST
        ).withComputedHash()

        val view = compiler.compile(
            atlas = atlas,
            states = states,
            groupId = groupA,
            round = 1,
            assignedNodeId = NodeId("foundation"),
            opportunityCommitments = opportunityCommitments,
            assignedToolRepairDiagnostics = listOf(diagnostic),
            assignedToolRepairApproach = approach
        )

        assertEquals(listOf(diagnostic), view.assignedToolRepairDiagnostics)
        assertTrue(view.hasValidHash())
        assertFalse(
            view.copy(
                assignedToolRepairDiagnostics = listOf(
                    diagnostic.copy(diagnosticHash = "e".repeat(64))
                ),
                assignedToolRepairApproach = approach
            ).hasValidHash()
        )
        listOf(
            diagnostic.copy(groupId = groupB),
            diagnostic.copy(authoringNodeId = NodeId("alternate")),
            diagnostic.copy(opportunityId = "other-opportunity"),
            diagnostic.copy(opportunityCommitmentHash = "other-commitment"),
            diagnostic.copy(visibleFromRound = 2),
            diagnostic.copy(affectedPublicField = "private_field")
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                compiler.compile(
                    atlas = atlas,
                    states = states,
                    groupId = groupA,
                    round = 1,
                    assignedNodeId = NodeId("foundation"),
                    opportunityCommitments = opportunityCommitments,
                    assignedToolRepairDiagnostics = listOf(invalid),
                    assignedToolRepairApproach = approach
                )
            }
        }
        assertFailsWith<IllegalArgumentException> {
            compiler.compile(
                atlas = atlas,
                states = states,
                groupId = groupA,
                round = 1,
                assignedNodeId = NodeId("alternate"),
                opportunityCommitments = opportunityCommitments,
                assignedToolRepairDiagnostics = listOf(diagnostic),
                assignedToolRepairApproach = approach
            )
        }
        assertFailsWith<IllegalArgumentException> {
            compiler.compile(
                atlas = atlas,
                states = states,
                groupId = groupA,
                round = 1,
                outlierMandate = "Novelty only.",
                opportunityCommitments = opportunityCommitments,
                assignedToolRepairDiagnostics = listOf(diagnostic),
                assignedToolRepairApproach = approach
            )
        }
    }

    @Test
    fun `assigned expected reuse node receives only exact Council matching tool cards`() {
        val opportunity = toolOpportunity()
        val atlas = atlas(opportunity)
        val revealedStates = states(atlas, initiallyRevealedTo = setOf(groupA))
            .toMutableMap()
            .apply {
                this[NodeId("future")] = getValue(NodeId("future")).copy(
                    revealedToGroups = setOf(groupA)
                )
            }
        val commitment = ToolOpportunityCommitment(
            runId = atlas.runId,
            atlasHash = atlas.atlasHash,
            nodeId = NodeId("foundation"),
            opportunity = opportunity
        ).withComputedHash()
        val opportunityCommitments = mapOf(opportunity.opportunityId to commitment)
        val card = WorkerToolCard(
            toolId = "foundation-calculator-v1",
            name = "foundation-calculator",
            version = 1,
            opportunityId = opportunity.opportunityId,
            opportunityCommitmentHash = commitment.commitmentHash,
            requiredForNodeId = NodeId("future"),
            description = opportunity.repeatedQuestionPattern,
            inputSchemaHash = "input-schema-hash",
            outputSchemaHash = "output-schema-hash"
        )

        val view = compiler.compile(
            atlas = atlas,
            states = revealedStates,
            groupId = groupA,
            round = 1,
            assignedNodeId = NodeId("future"),
            opportunityCommitments = opportunityCommitments,
            availableToolCards = listOf(card)
        )

        assertEquals(listOf(card), view.availableToolCards)
        assertTrue(view.hasValidHash())
        assertFailsWith<IllegalArgumentException> {
            compiler.compile(
                atlas = atlas,
                states = revealedStates,
                groupId = groupA,
                round = 1,
                assignedNodeId = NodeId("future"),
                opportunityCommitments = opportunityCommitments,
                availableToolCards = listOf(
                    card.copy(opportunityCommitmentHash = "wrong-commitment")
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            compiler.compile(
                atlas = atlas,
                states = revealedStates,
                groupId = groupA,
                round = 1,
                assignedNodeId = NodeId("alternate"),
                opportunityCommitments = opportunityCommitments,
                availableToolCards = listOf(
                    card.copy(requiredForNodeId = NodeId("alternate"))
                )
            )
        }
    }

    @Test
    fun `assigned tool validation node receives only validation scoped cards`() {
        val opportunity = toolOpportunity()
        val atlas = atlas(opportunity)
        val validationNodeId = NodeId("tool-validation")
        val revealedStates = states(atlas, initiallyRevealedTo = setOf(groupA))
            .toMutableMap()
            .apply {
                this[validationNodeId] = getValue(validationNodeId).copy(
                    revealedToGroups = setOf(groupA)
                )
            }
        val commitment = ToolOpportunityCommitment(
            runId = atlas.runId,
            atlasHash = atlas.atlasHash,
            nodeId = NodeId("foundation"),
            opportunity = opportunity
        ).withComputedHash()
        val opportunityCommitments = mapOf(opportunity.opportunityId to commitment)
        val card = WorkerToolCard(
            toolId = "foundation-calculator-v1",
            name = "foundation-calculator",
            version = 1,
            opportunityId = opportunity.opportunityId,
            opportunityCommitmentHash = commitment.commitmentHash,
            requiredForNodeId = validationNodeId,
            executionPurpose = ToolExecutionPurpose.VALIDATION_TEST,
            description = opportunity.validationStrategy,
            inputSchemaHash = "input-schema-hash",
            outputSchemaHash = "output-schema-hash"
        )

        val view = compiler.compile(
            atlas = atlas,
            states = revealedStates,
            groupId = groupA,
            round = 1,
            assignedNodeId = validationNodeId,
            opportunityCommitments = opportunityCommitments,
            availableToolCards = listOf(card)
        )

        assertEquals(listOf(card), view.availableToolCards)
        assertTrue(view.hasValidHash())
        assertFailsWith<IllegalArgumentException> {
            compiler.compile(
                atlas = atlas,
                states = revealedStates,
                groupId = groupA,
                round = 1,
                assignedNodeId = validationNodeId,
                opportunityCommitments = opportunityCommitments,
                availableToolCards = listOf(
                    card.copy(executionPurpose = ToolExecutionPurpose.AGENT_USE)
                )
            )
        }
    }

    @Test
    fun `portfolios are deterministic bounded and diversify non replicated work`() {
        val atlas = atlas()
        val states = states(atlas, initiallyRevealedTo = setOf(groupA, groupB))
        val views = listOf(
            compiler.compile(atlas, states, groupB, round = 0),
            compiler.compile(atlas, states, groupA, round = 0)
        )

        val portfolios = allocator.allocate(atlas, states, views, maximumNodesPerGroup = 1)
        val repeated = allocator.allocate(atlas, states, views.reversed(), maximumNodesPerGroup = 1)

        assertEquals(portfolios, repeated)
        assertTrue(portfolios.values.all { it.hasValidHash() && it.bids.size <= 1 })
        assertEquals(2, portfolios.values.flatMap { it.bids }.map { it.nodeId }.distinct().size)
        assertTrue(portfolios.values.none { portfolio -> portfolio.bids.any { it.nodeId == NodeId("future") } })

        val passedFoundation = states.toMutableMap().apply {
            this[NodeId("foundation")] = getValue(NodeId("foundation")).copy(
                grades = listOf(
                    NodeGrade(
                        gradeId = "grade-foundation",
                        groupId = groupA,
                        evidencePacketId = "evidence-foundation",
                        normalizedScore = 1.0,
                        verdict = GradeVerdict.VALID_PASS,
                        graderIds = setOf("grader-a", "grader-b"),
                        round = 0
                    )
                )
            )
            this[NodeId("future")] = getValue(NodeId("future")).copy(
                revealedToGroups = setOf(groupA, groupB)
            )
        }
        val nextViews = listOf(groupA, groupB).map {
            compiler.compile(atlas, passedFoundation, it, round = 1)
        }
        val next = allocator.allocate(atlas, passedFoundation, nextViews, maximumNodesPerGroup = 3)
        assertTrue(next.values.flatMap { it.bids }.any { it.nodeId == NodeId("future") })
    }

    @Test
    fun `portfolios distribute unique frontier across groups before assigning second nodes`() {
        val atlas = atlas()
        val states = states(atlas, initiallyRevealedTo = setOf(groupA, groupB))
        val views = listOf(
            compiler.compile(atlas, states, groupB, round = 0),
            compiler.compile(atlas, states, groupA, round = 0)
        )

        val portfolios = allocator.allocate(atlas, states, views, maximumNodesPerGroup = 3)
        val repeated = allocator.allocate(atlas, states, views.reversed(), maximumNodesPerGroup = 3)

        assertEquals(portfolios, repeated)
        assertEquals(setOf(groupA, groupB), portfolios.filterValues { it.bids.isNotEmpty() }.keys)
        assertTrue(portfolios.values.all { it.bids.size == 1 })
        assertEquals(
            2,
            portfolios.values.flatMap { it.bids }.map { it.nodeId }.distinct().size
        )
    }

    @Test
    fun `pending expected reuse outranks ordinary revealed frontier work`() {
        val opportunity = toolOpportunity()
        val atlas = atlas(opportunity)
        val opportunityCommitments = ToolOpportunityCommitmentAuthority.current(atlas)
            .byOpportunityId
        val states = states(atlas, initiallyRevealedTo = setOf(groupA))
            .withPassingGrade(NodeId("foundation"), groupA, round = 0)
            .toMutableMap()
            .apply {
                this[NodeId("future")] = getValue(NodeId("future")).copy(
                    revealedToGroups = setOf(groupA)
                )
            }
        val views = listOf(
            compiler.compile(
                atlas,
                states,
                groupA,
                round = 1,
                opportunityCommitments = opportunityCommitments
            )
        )

        val ordinary = allocator.allocate(
            atlas,
            states,
            views,
            maximumNodesPerGroup = 1
        )
        val prioritized = allocator.allocate(
            atlas,
            states,
            views,
            maximumNodesPerGroup = 1,
            priorityNodeIdsByGroup = mapOf(groupA to setOf(NodeId("future")))
        )

        assertEquals(
            listOf(NodeId("alternate")),
            ordinary.getValue(groupA).bids.map { it.nodeId }
        )
        assertEquals(
            listOf(NodeId("future")),
            prioritized.getValue(groupA).bids.map { it.nodeId }
        )
        assertContains(
            prioritized.getValue(groupA).bids.single().reason,
            "Council-sealed promoted-tool reuse"
        )
    }

    @Test
    fun `single replication slot rotates to groups that still need a passing grade`() {
        val atlas = singleNodeAtlas(ReplicationPolicy())
        val initialStates = states(atlas, initiallyRevealedTo = setOf(groupA, groupB))
        val initialViews = listOf(groupB, groupA).map {
            compiler.compile(atlas, initialStates, it, round = 0)
        }

        val initial = allocator.allocate(atlas, initialStates, initialViews, maximumNodesPerGroup = 1)

        assertEquals(listOf(NodeId("foundation")), initial.getValue(groupA).bids.map { it.nodeId })
        assertTrue(initial.getValue(groupB).bids.isEmpty())

        val passedByA = initialStates.withPassingGrade(NodeId("foundation"), groupA, round = 0)
        val roundOneViews = listOf(groupA, groupB).map {
            compiler.compile(atlas, passedByA, it, round = 1)
        }
        val rotated = allocator.allocate(atlas, passedByA, roundOneViews, maximumNodesPerGroup = 1)
        val replayed = allocator.allocate(
            atlas,
            passedByA.entries.reversed().associate { it.toPair() },
            roundOneViews.reversed(),
            maximumNodesPerGroup = 1
        )

        assertEquals(rotated, replayed)
        assertTrue(rotated.getValue(groupA).bids.isEmpty())
        assertEquals(listOf(NodeId("foundation")), rotated.getValue(groupB).bids.map { it.nodeId })

        val passedByBoth = passedByA.withPassingGrade(NodeId("foundation"), groupB, round = 1)
        val roundTwoViews = listOf(groupA, groupB).map {
            compiler.compile(atlas, passedByBoth, it, round = 2)
        }
        val complete = allocator.allocate(atlas, passedByBoth, roundTwoViews, maximumNodesPerGroup = 1)

        assertTrue(complete.values.all { it.bids.isEmpty() })
    }

    @Test
    fun `required replication still assigns every unpassed group`() {
        val atlas = singleNodeAtlas(
            ReplicationPolicy(
                mode = ReplicationMode.REQUIRED,
                requiredCount = 2
            )
        )
        val states = states(atlas, initiallyRevealedTo = setOf(groupA, groupB))
        val views = listOf(groupB, groupA).map {
            compiler.compile(atlas, states, it, round = 0)
        }

        val portfolios = allocator.allocate(atlas, states, views, maximumNodesPerGroup = 1)
        val replayed = allocator.allocate(atlas, states, views.reversed(), maximumNodesPerGroup = 1)

        assertEquals(portfolios, replayed)
        assertEquals(
            mapOf(groupA to listOf(NodeId("foundation")), groupB to listOf(NodeId("foundation"))),
            portfolios.mapValues { (_, portfolio) -> portfolio.bids.map { it.nodeId } }
        )
    }

    private fun atlas(toolOpportunity: ToolOpportunity? = null): NodeAtlas {
        val foundation = node(
            id = NodeId("foundation"),
            stageId = "stage-0",
            limitation = "VISIBLE_FOUNDATION_LIMITATION",
            question = "VISIBLE_FOUNDATION_QUESTION",
            toolOpportunity = toolOpportunity
        )
        val alternate = node(
            id = NodeId("alternate"),
            stageId = "stage-0",
            limitation = "VISIBLE_ALTERNATE_LIMITATION",
            question = "VISIBLE_ALTERNATE_QUESTION"
        )
        val future = node(
            id = NodeId("future"),
            stageId = "stage-1",
            limitation = FUTURE_LIMITATION,
            question = FUTURE_QUESTION,
            prerequisites = setOf(foundation.id),
            artifactType = ArtifactType.FINAL_DELIVERABLE
        )
        val validation = toolOpportunity?.let {
            node(
                id = NodeId("tool-validation"),
                stageId = "stage-1",
                limitation = "Validate the promoted implementation.",
                question = "Does the promoted implementation pass exact reproduction tests?",
                prerequisites = setOf(foundation.id),
                kind = NodeKind.VALIDATION,
                proofMethod = ProofMethod.REPRODUCTION,
                executableTestIds = setOf("tool-validation-case")
            )
        }
        val nodes = linkedMapOf(
            foundation.id to foundation,
            alternate.id to alternate,
            future.id to future
        ).apply {
            validation?.let { put(it.id, it) }
        }
        return NodeAtlas(
            atlasId = "atlas-worker-view",
            runId = "run-worker-view",
            version = 1,
            receiverContractHash = AtlasHashing.hashHiddenPayload(future.hiddenPayload()),
            rootNodeIds = setOf(foundation.id, alternate.id),
            nodes = nodes,
            stageDefinitions = listOf(
                stage("stage-0", 0, setOf(foundation.id, alternate.id), nodes),
                stage(
                    "stage-1",
                    1,
                    setOf(future.id) + listOfNotNull(validation).map(LimitationNode::id),
                    nodes
                )
            )
        ).withComputedHash()
    }

    private fun singleNodeAtlas(replicationPolicy: ReplicationPolicy): NodeAtlas {
        val foundation = node(
            id = NodeId("foundation"),
            stageId = "stage-0",
            limitation = "VISIBLE_FOUNDATION_LIMITATION",
            question = "VISIBLE_FOUNDATION_QUESTION",
            replicationPolicy = replicationPolicy
        )
        val nodes = mapOf(foundation.id to foundation)
        return NodeAtlas(
            atlasId = "atlas-worker-view-single",
            runId = "run-worker-view-single",
            version = 1,
            receiverContractHash = AtlasHashing.hashHiddenPayload(foundation.hiddenPayload()),
            rootNodeIds = setOf(foundation.id),
            nodes = nodes,
            stageDefinitions = listOf(stage("stage-0", 0, setOf(foundation.id), nodes))
        ).withComputedHash()
    }

    private fun node(
        id: NodeId,
        stageId: String,
        limitation: String,
        question: String,
        prerequisites: Set<NodeId> = emptySet(),
        artifactType: ArtifactType = ArtifactType.CLAIM_SET,
        toolOpportunity: ToolOpportunity? = null,
        replicationPolicy: ReplicationPolicy = ReplicationPolicy(),
        kind: NodeKind = if (artifactType == ArtifactType.FINAL_DELIVERABLE) {
            NodeKind.DELIVERY
        } else {
            NodeKind.KNOWLEDGE
        },
        proofMethod: ProofMethod = ProofMethod.SOURCE_CORROBORATION,
        executableTestIds: Set<String> = emptySet()
    ) = LimitationNode(
        id = id,
        kind = kind,
        layer = if (stageId == "stage-0") 0 else 1,
        parentIds = prerequisites,
        prerequisiteIds = prerequisites,
        receiverWhyRef = "receiver-ref-$id",
        limitationStatement = limitation,
        capabilityTarget = "capability-$id",
        atomicQuestion = question,
        proofContract = ProofContract(
            method = proofMethod,
            acceptanceCriteria = setOf("criterion-$id"),
            requiredEvidenceKinds = setOf(EvidenceKind.PRIMARY_SOURCE),
            executableTestIds = executableTestIds
        ),
        deliverableContract = NodeDeliverableContract(
            acceptedArtifactTypes = setOf(artifactType),
            requiredFields = setOf(if (artifactType == ArtifactType.FINAL_DELIVERABLE) FINAL_OUTPUT_SENTINEL else "claim")
        ),
        revealPolicy = RevealPolicy(
            stageId = stageId,
            mode = if (stageId == "stage-0") RevealMode.INITIAL else RevealMode.STAGE_PASS
        ),
        replicationPolicy = replicationPolicy,
        capturePolicy = CapturePolicy(minimumGrade = 0.7),
        rewardSpec = RewardSpec(RewardClass.SMALL, credits = 2.0),
        toolOpportunity = toolOpportunity
    ).withComputedPayloadHash()

    private fun toolOpportunity() = ToolOpportunity(
        opportunityId = "foundation-calculator",
        opportunityType = ToolOpportunityType.CALCULATION,
        repeatedQuestionPattern = "Calculate one bounded evidence ratio.",
        requiredInputs = listOf(
            TypedField("count", TypedFieldType.INTEGER, "Evidence item count.")
        ),
        requiredOutputs = listOf(
            TypedField("ratio", TypedFieldType.NUMBER, "Normalized evidence ratio.")
        ),
        validationStrategy = "Execute the exact committed boundary case.",
        expectedReuseNodeIds = setOf(NodeId("future")),
        preferredImplementationMode = ToolImplementationMode.PYTHON,
        behavior = ToolBehavior.DETERMINISTIC,
        requiredCapabilities = setOf("shell"),
        promotionTestIds = setOf("ratio-boundary"),
        maximumRuntimeMillis = 2_000,
        maximumOutputBytes = 4_096,
        assumptions = listOf("Count is non-negative."),
        knownInvalidDomains = listOf("Negative counts.")
    )

    private fun stage(
        id: String,
        index: Int,
        nodeIds: Set<NodeId>,
        nodes: Map<NodeId, LimitationNode>
    ) = DisclosureStage(
        stageId = id,
        stageIndex = index,
        title = "Stage $index",
        nodeIds = nodeIds,
        mandatoryNodeIds = nodeIds,
        evidenceCoverageThreshold = 0.8,
        integrationReadinessThreshold = 0.8,
        revealPayloadHash = AtlasHashing.hashDisclosurePayload(id, nodeIds, nodes)
    )

    private fun states(
        atlas: NodeAtlas,
        initiallyRevealedTo: Set<AtlasGroupId>
    ): Map<NodeId, NodeExecutionState> = atlas.nodes.mapValues { (nodeId, node) ->
        NodeExecutionState(
            nodeId = nodeId,
            round = 0,
            revealedToGroups = if (node.revealPolicy.stageId == "stage-0") initiallyRevealedTo else emptySet()
        )
    }

    private fun Map<NodeId, NodeExecutionState>.withPassingGrade(
        nodeId: NodeId,
        groupId: AtlasGroupId,
        round: Int
    ): Map<NodeId, NodeExecutionState> = toMutableMap().apply {
        this[nodeId] = getValue(nodeId).copy(
            grades = getValue(nodeId).grades + NodeGrade(
                gradeId = "grade-${groupId.value}-$round",
                groupId = groupId,
                evidencePacketId = "evidence-${groupId.value}-$round",
                normalizedScore = 1.0,
                verdict = GradeVerdict.VALID_PASS,
                graderIds = setOf("grader-a", "grader-b"),
                round = round
            )
        )
    }

    private companion object {
        const val FUTURE_LIMITATION = "HIDDEN_FUTURE_LIMITATION_SENTINEL"
        const val FUTURE_QUESTION = "HIDDEN_FUTURE_ATOMIC_QUESTION_SENTINEL"
        const val FINAL_OUTPUT_SENTINEL = "HIDDEN_FINAL_OUTPUT_REQUIREMENT_SENTINEL"
    }
}
