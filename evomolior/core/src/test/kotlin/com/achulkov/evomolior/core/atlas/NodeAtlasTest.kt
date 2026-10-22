package com.achulkov.evomolior.core.atlas

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NodeAtlasTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `contracts survive serialization round trip`() {
        val atlas = fixtureAtlas()
        val encoded = json.encodeToString(atlas)

        assertEquals(atlas, json.decodeFromString<NodeAtlas>(encoded))
        assertTrue(atlas.validate().isValid)
    }

    @Test
    fun `hashes ignore map and set insertion order`() {
        val first = fixtureAtlas(reverseInsertion = false)
        val second = fixtureAtlas(reverseInsertion = true)

        assertEquals(first.atlasHash, second.atlasHash)
        assertEquals(
            first.nodes.getValue(NodeId("node-a")).payloadHash,
            second.nodes.getValue(NodeId("node-a")).payloadHash
        )
    }

    @Test
    fun `raw objective whitespace changes receiver hash`() {
        val compact = fixtureReceiver("Map the receiver need.")
        val spaced = fixtureReceiver("Map  the receiver need.")

        assertNotEquals(
            AtlasHashing.hashReceiverContract(compact),
            AtlasHashing.hashReceiverContract(spaced)
        )
    }

    @Test
    fun `payload verification rejects hidden content mutation`() {
        val node = fixtureNode(NodeId("node-a"))

        assertTrue(node.hasValidPayloadHash())
        assertFalse(node.copy(atomicQuestion = "${node.atomicQuestion} changed").hasValidPayloadHash())
    }

    @Test
    fun `version validation accepts exact parent lineage and rejects wrong lineage`() {
        val parent = fixtureAtlas()
        val child = parent.copy(
            version = 2,
            parentAtlasHash = parent.atlasHash,
            amendmentIds = listOf("amendment-1"),
            atlasHash = ""
        ).withComputedHash()
        val wrongParent = parent.copy(atlasHash = "0".repeat(64))

        assertTrue(child.validate(parent).isValid)
        assertTrue(
            child.validate(wrongParent).errors.any {
                it.code == AtlasValidationErrorCode.INVALID_PARENT_LINEAGE
            }
        )
    }

    @Test
    fun `dependency cycles are rejected`() {
        val aId = NodeId("node-a")
        val bId = NodeId("node-b")
        val a = fixtureNode(aId, prerequisiteIds = setOf(bId))
        val b = fixtureNode(bId, prerequisiteIds = setOf(aId))
        val cyclic = fixtureAtlas(
            suppliedNodes = linkedMapOf(aId to a, bId to b),
            rootNodeIds = setOf(aId)
        )

        assertTrue(
            cyclic.validate().errors.any {
                it.code == AtlasValidationErrorCode.DEPENDENCY_CYCLE
            }
        )
    }

    @Test
    fun `tool validation and expected reuse require disjoint sealed nodes`() {
        val validationOverlap = toolTopologyAtlas(
            expectedReuseNodeId = NodeId("node-validation")
        )
        val separated = toolTopologyAtlas(
            expectedReuseNodeId = NodeId("node-reuse")
        )

        assertTrue(
            validationOverlap.validate().errors.any {
                it.code == AtlasValidationErrorCode.TOOL_PURPOSE_OVERLAP &&
                    it.nodeId == NodeId("node-origin")
            }
        )
        assertTrue(separated.validate().isValid, separated.validate().errors.joinToString())
    }

    @Test
    fun `tool opportunity commitment remains bound to its origin Atlas version`() {
        val parent = toolTopologyAtlas(NodeId("node-reuse"))
        val child = parent.copy(
            version = 2,
            parentAtlasHash = parent.atlasHash,
            amendmentIds = listOf("amendment-1"),
            atlasHash = ""
        ).withComputedHash()

        val authority = ToolOpportunityCommitmentAuthority.resolve(listOf(parent, child))
        val commitment = authority.byOpportunityId.getValue("opportunity-1")

        assertEquals(parent.atlasHash, commitment.atlasHash)
        assertNotEquals(child.atlasHash, commitment.atlasHash)
        assertEquals(commitment, authority.byNodeId.getValue(NodeId("node-origin")))
    }

    @Test
    fun `amendment introduced opportunity binds to its introduction version`() {
        val parent = toolTopologyAtlas(NodeId("node-reuse"))
        val addedNodeId = NodeId("node-added-tool")
        val addedOpportunity = parent.nodes.getValue(NodeId("node-origin"))
            .toolOpportunity!!
            .copy(opportunityId = "opportunity-2")
        val addedNode = fixtureNode(addedNodeId).copy(
            kind = NodeKind.TOOL,
            toolOpportunity = addedOpportunity
        ).withComputedPayloadHash()
        val childNodes = parent.nodes + (addedNodeId to addedNode)
        val childStages = parent.stageDefinitions.map { stage ->
            if (stage.stageId != "stage-0") {
                stage
            } else {
                val nodeIds = stage.nodeIds + addedNodeId
                stage.copy(
                    nodeIds = nodeIds,
                    mandatoryNodeIds = stage.mandatoryNodeIds + addedNodeId,
                    revealPayloadHash = AtlasHashing.hashDisclosurePayload(
                        stage.stageId,
                        nodeIds,
                        childNodes
                    )
                )
            }
        }
        val child = parent.copy(
            version = 2,
            rootNodeIds = parent.rootNodeIds + addedNodeId,
            nodes = childNodes,
            stageDefinitions = childStages,
            parentAtlasHash = parent.atlasHash,
            amendmentIds = listOf("amendment-1"),
            atlasHash = ""
        ).withComputedHash()

        val authority = ToolOpportunityCommitmentAuthority.resolve(listOf(parent, child))

        assertEquals(
            parent.atlasHash,
            authority.byOpportunityId.getValue("opportunity-1").atlasHash
        )
        assertEquals(
            child.atlasHash,
            authority.byOpportunityId.getValue("opportunity-2").atlasHash
        )
    }

    @Test
    fun `tool opportunity authority rejects duplicate IDs and mutated existing nodes`() {
        val parent = toolTopologyAtlas(NodeId("node-reuse"))
        val originId = NodeId("node-origin")
        val mutatedOrigin = parent.nodes.getValue(originId).copy(
            atomicQuestion = "Mutated after sealing."
        ).withComputedPayloadHash()
        val mutated = parent.copy(
            version = 2,
            nodes = parent.nodes + (originId to mutatedOrigin),
            parentAtlasHash = parent.atlasHash,
            amendmentIds = listOf("amendment-mutated"),
            atlasHash = ""
        ).withComputedHash()
        assertFailsWith<IllegalArgumentException> {
            ToolOpportunityCommitmentAuthority.resolve(listOf(parent, mutated))
        }

        val duplicateId = NodeId("node-duplicate-tool")
        val duplicateNode = fixtureNode(duplicateId).copy(
            kind = NodeKind.TOOL,
            toolOpportunity = parent.nodes.getValue(originId).toolOpportunity
        ).withComputedPayloadHash()
        val duplicateNodes = parent.nodes + (duplicateId to duplicateNode)
        val duplicateStages = parent.stageDefinitions.map { stage ->
            if (stage.stageId != "stage-0") {
                stage
            } else {
                val nodeIds = stage.nodeIds + duplicateId
                stage.copy(
                    nodeIds = nodeIds,
                    mandatoryNodeIds = stage.mandatoryNodeIds + duplicateId,
                    revealPayloadHash = AtlasHashing.hashDisclosurePayload(
                        stage.stageId,
                        nodeIds,
                        duplicateNodes
                    )
                )
            }
        }
        val duplicate = parent.copy(
            version = 2,
            rootNodeIds = parent.rootNodeIds + duplicateId,
            nodes = duplicateNodes,
            stageDefinitions = duplicateStages,
            parentAtlasHash = parent.atlasHash,
            amendmentIds = listOf("amendment-duplicate"),
            atlasHash = ""
        ).withComputedHash()
        assertFailsWith<IllegalArgumentException> {
            ToolOpportunityCommitmentAuthority.resolve(listOf(parent, duplicate))
        }
    }

    private fun fixtureAtlas(
        reverseInsertion: Boolean = false,
        suppliedNodes: Map<NodeId, LimitationNode>? = null,
        rootNodeIds: Set<NodeId> = setOf(NodeId("node-a"))
    ): NodeAtlas {
        val a = fixtureNode(
            NodeId("node-a"),
            acceptanceCriteria = insertionSet(reverseInsertion, "criterion-a", "criterion-b")
        )
        val b = fixtureNode(
            NodeId("node-b"),
            parentIds = setOf(NodeId("node-a")),
            prerequisiteIds = setOf(NodeId("node-a"))
        )
        val nodes = suppliedNodes ?: if (reverseInsertion) {
            linkedMapOf(b.id to b, a.id to a)
        } else {
            linkedMapOf(a.id to a, b.id to b)
        }
        val receiver = fixtureReceiver("Map the receiver need.")
        val stageNodeIds = insertionSet(reverseInsertion, NodeId("node-a"), NodeId("node-b"))
        return NodeAtlas(
            atlasId = "atlas-1",
            runId = "run-1",
            version = 1,
            receiverContractHash = AtlasHashing.hashReceiverContract(receiver),
            rootNodeIds = rootNodeIds,
            nodes = nodes,
            stageDefinitions = listOf(
                DisclosureStage(
                    stageId = "stage-0",
                    stageIndex = 0,
                    title = "Foundations",
                    nodeIds = stageNodeIds,
                    mandatoryNodeIds = setOf(NodeId("node-a")),
                    evidenceCoverageThreshold = 0.8,
                    integrationReadinessThreshold = 0.6,
                    revealPayloadHash = AtlasHashing.hashDisclosurePayload(
                        stageId = "stage-0",
                        nodeIds = stageNodeIds,
                        nodes = nodes
                    )
                )
            )
        ).withComputedHash()
    }

    private fun fixtureReceiver(rawObjective: String) = ReceiverContract(
        rawObjective = rawObjective,
        receiverId = "receiver-1",
        receiverDescription = "An operator",
        situation = "A consequential decision",
        decisionOrUse = "Choose an implementation",
        whyNeeded = "Reduce uncertainty",
        successOutcomes = listOf("Actionable"),
        unacceptableOutcomes = listOf("Unsupported"),
        constraints = listOf("Auditable"),
        finalDeliverableContract = FinalOutputSpec(
            relativePath = "result.json",
            mediaType = "application/json",
            requiredSections = setOf("recommendation", "evidence")
        ),
        evidenceProfile = EvidenceProfile(
            acceptedEvidenceKinds = setOf(EvidenceKind.PRIMARY_SOURCE, EvidenceKind.EXPERIMENTAL),
            requiredSourceDomains = setOf("official", "peer-reviewed")
        )
    )

    private fun toolTopologyAtlas(expectedReuseNodeId: NodeId): NodeAtlas {
        val originId = NodeId("node-origin")
        val validationId = NodeId("node-validation")
        val reuseId = NodeId("node-reuse")
        val opportunity = ToolOpportunity(
            opportunityId = "opportunity-1",
            opportunityType = ToolOpportunityType.CALCULATION,
            repeatedQuestionPattern = "Calculate the bounded local result.",
            requiredInputs = listOf(
                TypedField("value", TypedFieldType.NUMBER, "Finite input value.")
            ),
            requiredOutputs = listOf(
                TypedField("result", TypedFieldType.NUMBER, "Calculated result.")
            ),
            validationStrategy = "Reproduce the committed executable boundary case.",
            expectedReuseNodeIds = setOf(expectedReuseNodeId),
            preferredImplementationMode = ToolImplementationMode.SHELL,
            behavior = ToolBehavior.DETERMINISTIC,
            promotionTestIds = setOf("promotion-test"),
            maximumRuntimeMillis = 1_000,
            maximumOutputBytes = 4_096
        )
        val origin = fixtureNode(originId).copy(
            kind = NodeKind.TOOL,
            toolOpportunity = opportunity
        ).withComputedPayloadHash()
        val validation = fixtureNode(
            id = validationId,
            parentIds = setOf(originId),
            prerequisiteIds = setOf(originId)
        ).copy(
            kind = NodeKind.VALIDATION,
            layer = 1,
            proofContract = ProofContract(
                method = ProofMethod.REPRODUCTION,
                acceptanceCriteria = setOf("The promoted version passes validation."),
                executableTestIds = setOf("validation-test")
            ),
            revealPolicy = RevealPolicy(
                stageId = "stage-1",
                mode = RevealMode.PREREQUISITES_COMPLETE,
                prerequisiteNodeIds = setOf(originId),
                minimumRound = 1
            )
        ).withComputedPayloadHash()
        val reuse = fixtureNode(
            id = reuseId,
            parentIds = setOf(originId),
            prerequisiteIds = setOf(originId)
        ).copy(
            kind = NodeKind.SIMULATION,
            layer = 1,
            revealPolicy = RevealPolicy(
                stageId = "stage-1",
                mode = RevealMode.PREREQUISITES_COMPLETE,
                prerequisiteNodeIds = setOf(originId),
                minimumRound = 1
            )
        ).withComputedPayloadHash()
        val nodes = linkedMapOf(
            originId to origin,
            validationId to validation,
            reuseId to reuse
        )
        val stage0Ids = setOf(originId)
        val stage1Ids = setOf(validationId, reuseId)
        return NodeAtlas(
            atlasId = "tool-topology-atlas",
            runId = "run-1",
            version = 1,
            receiverContractHash = AtlasHashing.hashReceiverContract(
                fixtureReceiver("Validate and reuse one promoted tool.")
            ),
            rootNodeIds = setOf(originId),
            nodes = nodes,
            stageDefinitions = listOf(
                DisclosureStage(
                    stageId = "stage-0",
                    stageIndex = 0,
                    title = "Author",
                    nodeIds = stage0Ids,
                    mandatoryNodeIds = stage0Ids,
                    evidenceCoverageThreshold = 1.0,
                    integrationReadinessThreshold = 1.0,
                    revealPayloadHash = AtlasHashing.hashDisclosurePayload(
                        "stage-0",
                        stage0Ids,
                        nodes
                    )
                ),
                DisclosureStage(
                    stageId = "stage-1",
                    stageIndex = 1,
                    title = "Validate and reuse",
                    nodeIds = stage1Ids,
                    mandatoryNodeIds = stage1Ids,
                    evidenceCoverageThreshold = 1.0,
                    integrationReadinessThreshold = 1.0,
                    revealPayloadHash = AtlasHashing.hashDisclosurePayload(
                        "stage-1",
                        stage1Ids,
                        nodes
                    )
                )
            )
        ).withComputedHash()
    }

    private fun fixtureNode(
        id: NodeId,
        parentIds: Set<NodeId> = emptySet(),
        prerequisiteIds: Set<NodeId> = emptySet(),
        acceptanceCriteria: Set<String> = setOf("criterion-a", "criterion-b")
    ): LimitationNode = LimitationNode(
        id = id,
        kind = NodeKind.KNOWLEDGE,
        layer = 0,
        parentIds = parentIds,
        prerequisiteIds = prerequisiteIds,
        receiverWhyRef = "outcome-1",
        limitationStatement = "A fact is unknown.",
        capabilityTarget = "Establish the fact.",
        atomicQuestion = "What is the fact?",
        proofContract = ProofContract(
            method = ProofMethod.SOURCE_CORROBORATION,
            acceptanceCriteria = acceptanceCriteria,
            requiredEvidenceKinds = setOf(EvidenceKind.PRIMARY_SOURCE)
        ),
        deliverableContract = NodeDeliverableContract(
            acceptedArtifactTypes = setOf(ArtifactType.CLAIM_SET),
            requiredFields = setOf("claim", "source")
        ),
        revealPolicy = RevealPolicy(stageId = "stage-0", mode = RevealMode.INITIAL),
        replicationPolicy = ReplicationPolicy(),
        capturePolicy = CapturePolicy(minimumGrade = 0.7),
        rewardSpec = RewardSpec(RewardClass.SMALL, credits = 2.0)
    ).withComputedPayloadHash()

    private fun <T> insertionSet(reverse: Boolean, first: T, second: T): Set<T> =
        if (reverse) linkedSetOf(second, first) else linkedSetOf(first, second)
}
