package com.achulkov.evomolior.core.contract

import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.CapturePolicy
import com.achulkov.evomolior.core.atlas.NodeDeliverableContract
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.NodeStatus
import com.achulkov.evomolior.core.atlas.ProofContract
import com.achulkov.evomolior.core.atlas.ProofMethod
import com.achulkov.evomolior.core.atlas.ReplicationPolicy
import com.achulkov.evomolior.core.atlas.RewardClass
import com.achulkov.evomolior.core.atlas.RewardSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

class AgentBootContextSerializationTest {

    @OptIn(kotlin.time.ExperimentalTime::class)
    @Test
    fun `ordinary worker context round trips redacted authority and rejects full starting field`() {
        val json = Json { ignoreUnknownKeys = true }
        val nodeId = NodeId("node-1")
        val assignedNode = RevealedNodeCard(
            nodeId = nodeId,
            kind = NodeKind.KNOWLEDGE,
            receiverWhyRef = "receiver obligation",
            limitationStatement = "Evidence is incomplete.",
            capabilityTarget = "Verified evidence.",
            atomicQuestion = "Which evidence resolves the limitation?",
            proofContract = ProofContract(
                method = ProofMethod.SOURCE_CORROBORATION,
                acceptanceCriteria = setOf("Evidence is cited")
            ),
            deliverableContract = NodeDeliverableContract(
                acceptedArtifactTypes = setOf(ArtifactType.CLAIM_SET)
            ),
            replicationPolicy = ReplicationPolicy(),
            capturePolicy = CapturePolicy(minimumGrade = 0.7),
            rewardSpec = RewardSpec(RewardClass.MEDIUM, 2.0),
            toolOpportunity = null,
            payloadHash = "payload-hash"
        )
        val workerView = WorkerView(
            runId = "run-worker",
            groupId = AtlasGroupId("group-worker"),
            round = 2,
            stageId = "stage-0",
            visibleAtlasSkeleton = AtlasSkeletonView(
                atlasId = "atlas-worker",
                atlasVersion = 1,
                atlasHash = "atlas-hash",
                rootNodeIds = setOf(nodeId),
                nodes = mapOf(
                    nodeId to AtlasSkeletonNodeView(
                        nodeId = nodeId,
                        kind = NodeKind.KNOWLEDGE,
                        layer = 0,
                        parentIds = emptySet(),
                        prerequisiteIds = emptySet(),
                        alternativeToIds = emptySet(),
                        stageId = "stage-0",
                        payloadHash = "payload-hash",
                        status = NodeStatus.SEALED,
                        congestionLevel = 0
                    )
                )
            ),
            revealedNodes = listOf(assignedNode),
            assignedNode = assignedNode,
            groupFeedback = null,
            allowedKnowledgeRefs = listOf(
                WorkerArtifactRef("artifact-1", ArtifactType.CLAIM_SET, "Allowed", "content-hash")
            )
        ).withComputedHash()
        val original = AgentBootContext(
            agentId = "agent-worker",
            runId = "run-worker",
            groupId = "group-worker",
            role = Role.Researcher,
            rolePrompt = "Research within the assigned visible frontier.",
            budgetToken = "token",
            substrateDbPath = "/tmp/substrate.db",
            stagingMountPath = "/staging",
            startingField = null,
            workerView = workerView,
            workerAuthorityScope = WorkerAuthorityScope.NODE_ASSIGNMENT,
            assignedLease = WorkerLeaseAssignment(
                leaseId = "lease-1",
                nodeId = nodeId,
                expiresAt = Instant.parse("2026-07-11T10:09:00Z"),
                replicationSlot = 0
            ),
            ttlPolicy = AgentTtlPolicy(
                lifetimeSeconds = 600,
                deadline = Instant.parse("2026-07-11T10:10:00Z")
            ),
            wipTaskClaim = WipTaskClaim(nodeId.value, assignedNode.atomicQuestion),
            legacyProtocol = null,
            stageDisclosure = "Use only the redacted worker view.",
            modelAssignment = "openai/gpt-4o-mini",
            ipcBaseUrl = "http://127.0.0.1:10000",
            generation = 2
        ).requireValidWorkerAuthority()

        val decoded = json.decodeFromString(
            AgentBootContext.serializer(),
            json.encodeToString(AgentBootContext.serializer(), original)
        )

        assertEquals(original, decoded)
        decoded.requireValidWorkerAuthority()
        assertFailsWith<IllegalArgumentException> {
            decoded.copy(assignedLease = null).requireValidWorkerAuthority()
        }
        assertFailsWith<IllegalArgumentException> {
            decoded.copy(
                startingField = StartingField.default(
                    objective = "must remain hidden",
                    runId = decoded.runId,
                    groupCount = 2
                )
            ).requireValidWorkerAuthority()
        }
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    @Test
    fun `outlier worker authority rejects task lease and legacy`() {
        val view = WorkerView(
            runId = "run-outlier",
            groupId = AtlasGroupId("group-outlier"),
            round = 1,
            stageId = "stage-0",
            visibleAtlasSkeleton = AtlasSkeletonView(
                atlasId = "atlas-outlier",
                atlasVersion = 1,
                atlasHash = "atlas-hash",
                rootNodeIds = emptySet(),
                nodes = emptyMap()
            ),
            revealedNodes = emptyList(),
            assignedNode = null,
            groupFeedback = null,
            outlierMandate = "Challenge one visible assumption without requesting hidden targets."
        ).withComputedHash()
        val context = AgentBootContext(
            agentId = "agent-outlier",
            runId = "run-outlier",
            groupId = "group-outlier",
            role = Role.Outlier,
            rolePrompt = "Find novelty.",
            budgetToken = "token",
            substrateDbPath = "/tmp/substrate.db",
            stagingMountPath = "/staging",
            workerView = view,
            workerAuthorityScope = WorkerAuthorityScope.OUTLIER_MANDATE,
            ttlPolicy = AgentTtlPolicy(300, Instant.parse("2026-07-11T10:05:00Z")),
            wipTaskClaim = null,
            legacyProtocol = null,
            stageDisclosure = "Visible stage only.",
            modelAssignment = "openai/gpt-4o-mini",
            ipcBaseUrl = "http://127.0.0.1:10000",
            generation = 1
        )

        context.requireValidWorkerAuthority()
        assertFailsWith<IllegalArgumentException> {
            context.copy(wipTaskClaim = WipTaskClaim("node-1", "forbidden")).requireValidWorkerAuthority()
        }
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    @Test
    fun `PerspectiveTaker is an explicit redacted no-node authority`() {
        val view = WorkerView(
            runId = "run-perspective",
            groupId = AtlasGroupId("group-perspective"),
            round = 1,
            stageId = "stage-0",
            visibleAtlasSkeleton = AtlasSkeletonView(
                atlasId = "atlas-perspective",
                atlasVersion = 1,
                atlasHash = "atlas-hash",
                rootNodeIds = emptySet(),
                nodes = emptyMap()
            ),
            revealedNodes = emptyList(),
            assignedNode = null,
            groupFeedback = null
        ).withComputedHash()
        val context = AgentBootContext(
            agentId = "agent-perspective",
            runId = view.runId,
            groupId = view.groupId.value,
            role = Role.PerspectiveTaker,
            rolePrompt = "Find missing viewpoints.",
            budgetToken = "token",
            substrateDbPath = "/tmp/substrate.db",
            stagingMountPath = "/staging",
            workerView = view,
            workerAuthorityScope = WorkerAuthorityScope.PERSPECTIVE_TAKER,
            ttlPolicy = AgentTtlPolicy(300, Instant.parse("2026-07-11T10:05:00Z")),
            wipTaskClaim = null,
            legacyProtocol = null,
            stageDisclosure = "Visible stage only.",
            modelAssignment = "openai/gpt-4o-mini",
            ipcBaseUrl = "http://127.0.0.1:10000",
            generation = 1
        )

        context.requireValidWorkerAuthority()
        assertFailsWith<IllegalArgumentException> {
            context.copy(
                workerAuthorityScope = WorkerAuthorityScope.NODE_ASSIGNMENT
            ).requireValidWorkerAuthority()
        }
    }

    @Test
    fun `legacy agent boot context json without starting field still decodes`() {
        val json = Json { ignoreUnknownKeys = true }
        val original = AgentBootContext(
            agentId = "agent-legacy",
            runId = "run-legacy",
            groupId = "group-0",
            role = Role.Researcher,
            rolePrompt = "You are a Researcher.",
            budgetToken = "token",
            substrateDbPath = "/tmp/substrate.db",
            stagingMountPath = "/staging",
            inputArtifacts = emptyList(),
            startingField = StartingField.default(
                objective = "legacy compatibility",
                runId = "run-legacy",
                groupCount = 2
            ),
            wipTaskClaim = null,
            legacyProtocol = null,
            stageDisclosure = "Constraint: legacy path",
            modelAssignment = "openai/gpt-4o-mini",
            ipcBaseUrl = "http://127.0.0.1:10000",
            generation = 1
        )

        val legacyJson = buildJsonObject {
            json.encodeToJsonElement(AgentBootContext.serializer(), original).jsonObject.forEach { (key, value) ->
                if (key != "startingField") put(key, value)
            }
        }

        val decoded = json.decodeFromJsonElement(AgentBootContext.serializer(), legacyJson)

        assertNull(decoded.startingField)
        assertEquals(original.agentId, decoded.agentId)
        assertEquals(original.runId, decoded.runId)
    }

    @Test
    fun `agent boot context round trips populated starting field`() {
        val json = Json { ignoreUnknownKeys = true }
        val original = AgentBootContext(
            agentId = "agent-populated",
            runId = "run-populated",
            groupId = "group-1",
            role = Role.Builder,
            rolePrompt = "You are a Builder.",
            budgetToken = "token",
            substrateDbPath = "/tmp/substrate.db",
            stagingMountPath = "/staging",
            inputArtifacts = emptyList(),
            startingField = StartingField.default(
                objective = "populate seed",
                runId = "run-populated",
                groupCount = 2
            ),
            wipTaskClaim = null,
            legacyProtocol = null,
            stageDisclosure = "Constraint: round trip",
            modelAssignment = "openai/gpt-4o-mini",
            ipcBaseUrl = "http://127.0.0.1:10000",
            generation = 1
        )

        val decoded = json.decodeFromString(AgentBootContext.serializer(), json.encodeToString(AgentBootContext.serializer(), original))

        assertEquals(original.startingField?.runId, decoded.startingField?.runId)
        assertEquals(original.startingField?.objective, decoded.startingField?.objective)
        assertEquals(original, decoded)
    }
}
