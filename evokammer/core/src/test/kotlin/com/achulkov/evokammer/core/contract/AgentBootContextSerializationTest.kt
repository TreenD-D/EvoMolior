package com.achulkov.evokammer.core.contract

import com.achulkov.evokammer.core.model.Role
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentBootContextSerializationTest {

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
