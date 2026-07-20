package com.achulkov.evomolior.core.council

import com.achulkov.evomolior.core.atlas.FinalOutputSpec
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.contract.GroupTopologyKind
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.Role
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AcceptanceProfileAuthorityTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    @Test
    fun `normal envelope bytes and hash omit the new default authority`() {
        val normal = envelope()
        val encoded = json.encodeToString(ExecutionEnvelope.serializer(), normal)

        assertFalse(encoded.contains("acceptanceProfile"))
        assertEquals(normal, json.decodeFromString(ExecutionEnvelope.serializer(), encoded))
        assertEquals(
            CouncilContractHashing.hashExecutionEnvelope(normal),
            CouncilContractHashing.hashExecutionEnvelope(
                json.decodeFromString(ExecutionEnvelope.serializer(), encoded)
            )
        )

        val full = normal.copy(acceptanceProfile = AcceptanceProfile.APPENDIX_B_FULL)
        val fullEncoded = json.encodeToString(ExecutionEnvelope.serializer(), full)
        assertTrue(fullEncoded.contains("\"acceptanceProfile\":\"appendix-b-full\""))
        assertNotEquals(
            CouncilContractHashing.hashExecutionEnvelope(normal),
            CouncilContractHashing.hashExecutionEnvelope(full)
        )
    }

    @Test
    fun `Council execution request carries explicit profile authority`() {
        val normal = CouncilRoleExecutionRequest(
            role = Role.CouncilTaskAnatomist,
            contextJson = "{}"
        )
        val smoke = normal.copy(
            acceptanceProfile = AcceptanceProfile.RUNTIME_USABILITY_SMOKE
        )

        assertEquals(AcceptanceProfile.NORMAL, normal.acceptanceProfile)
        assertEquals(AcceptanceProfile.RUNTIME_USABILITY_SMOKE, smoke.acceptanceProfile)
    }

    private fun envelope(): ExecutionEnvelope = ExecutionEnvelope(
        requiredWorkerGroupCount = InclusiveIntRange(2, 2),
        requiredGraderGroupCount = InclusiveIntRange(2, 2),
        permittedProfiles = setOf(GroupProfileType.Library, GroupProfileType.Factory),
        availableModels = listOf(
            ModelCapabilityCard(
                modelId = "test/model",
                familyId = "test",
                structuredOutputReliability = 1.0,
                toolCallReliability = 1.0,
                codeBuildReliability = 1.0,
                longContextSynthesisReliability = 1.0
            )
        ),
        maxAgentsPerGroupPerRound = 2,
        maxRounds = 2,
        perGenerationCreditPolicy = GenerationCreditPolicy(10.0, 20.0),
        allowedTopologyKinds = setOf(GroupTopologyKind.RING),
        sourceAccessPolicy = SourceAccessPolicySummary(networkAccessEnabled = false),
        sandboxCapabilities = SandboxCapabilitySummary(
            maximumToolRuntimeSeconds = 30,
            maximumWorkspaceBytes = 1_000
        ),
        outputSpec = FinalOutputSpec("result.md", "text/markdown"),
        atomicNodeEffortBound = 2
    )
}
