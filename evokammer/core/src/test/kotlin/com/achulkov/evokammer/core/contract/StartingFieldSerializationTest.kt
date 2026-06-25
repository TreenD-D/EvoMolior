package com.achulkov.evokammer.core.contract

import com.achulkov.evokammer.core.model.GroupProfileType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class StartingFieldSerializationTest {

    @Test
    fun `legacy starting field json without topology kind defaults to ring`() {
        val json = Json { ignoreUnknownKeys = true }
        val original = StartingField.default(
            objective = "legacy topology compatibility",
            runId = "run-legacy-topology",
            groupCount = 2,
            groupProfilesMap = mapOf(
                "group-0" to GroupProfileType.Library,
                "group-1" to GroupProfileType.Factory
            )
        ).copy(topologyKind = GroupTopologyKind.STAR)

        val legacyJson = buildJsonObject {
            json.encodeToJsonElement(StartingField.serializer(), original).jsonObject.forEach { (key, value) ->
                if (key != "topologyKind") put(key, value)
            }
        }

        val decoded = json.decodeFromJsonElement(StartingField.serializer(), legacyJson)

        assertEquals(GroupTopologyKind.RING, decoded.topologyKind)
        assertEquals(listOf("group-0", "group-1"), decoded.ringTopology)
        assertEquals(emptyList(), decoded.routedContextSummaries)
        assertEquals(null, decoded.feedforwardLayer)
        assertEquals(emptyList(), decoded.runGraphEdges)
    }

    @Test
    fun `starting field json preserves populated routed context and graph edges`() {
        val json = Json { ignoreUnknownKeys = true }
        val original = StartingField.default(
            objective = "graph serialization",
            runId = "run-graph-serialization",
            groupCount = 2,
            groupProfilesMap = mapOf(
                "group-0" to GroupProfileType.Library,
                "group-1" to GroupProfileType.Factory
            )
        ).copy(
            routedContextSummaries = listOf("high-level-run-log: headline"),
            runGraphEdges = listOf(
                RunGraphEdge(
                    sourceRunId = "ancestor-run",
                    targetRunId = "run-graph-serialization",
                    edgeType = "continuation",
                    weight = 0.75
                )
            )
        )

        val decoded = json.decodeFromString(StartingField.serializer(), json.encodeToString(StartingField.serializer(), original))

        assertEquals(original.routedContextSummaries, decoded.routedContextSummaries)
        assertEquals(original.runGraphEdges, decoded.runGraphEdges)
    }

    @Test
    fun `starting field json preserves feedforward layer metadata`() {
        val json = Json { ignoreUnknownKeys = true }
        val original = StartingField.default(
            objective = "feedforward serialization",
            runId = "run-feedforward-serialization",
            groupCount = 2,
            groupProfilesMap = mapOf(
                "group-0" to GroupProfileType.Library,
                "group-1" to GroupProfileType.Factory
            )
        ).copy(
            feedforwardLayer = FeedforwardLayerSpec(
                layerIndex = 1,
                layerLabel = "L2",
                upstreamRunIds = listOf("run-l1"),
                layerObjective = "Cross-domain synthesis"
            )
        )

        val decoded = json.decodeFromString(StartingField.serializer(), json.encodeToString(StartingField.serializer(), original))

        assertEquals(original.feedforwardLayer, decoded.feedforwardLayer)
    }

    @Test
    fun `starting field json preserves dispatch topology adaptation metadata`() {
        val json = Json { ignoreUnknownKeys = true }
        val original = StartingField.default(
            objective = "dispatch adaptation serialization",
            runId = "run-dispatch-adaptation-serialization",
            groupCount = 2,
            groupProfilesMap = mapOf(
                "group-0" to GroupProfileType.Library,
                "group-1" to GroupProfileType.Factory
            )
        ).copy(
            dispatchTopologyAdaptation = DispatchTopologyAdaptationSpec(
                minimumTrustToRetain = 0.6,
                maxRewiredNeighbours = 2
            )
        )

        val decoded = json.decodeFromString(StartingField.serializer(), json.encodeToString(StartingField.serializer(), original))

        assertEquals(original.dispatchTopologyAdaptation, decoded.dispatchTopologyAdaptation)
    }

    @Test
    fun `feedforward layer spec advances to the next layer with upstream chaining`() {
        val layer = FeedforwardLayerSpec(
            layerIndex = 0,
            layerLabel = "L1",
            upstreamRunIds = listOf("seed-run"),
            layerObjective = "Research"
        )

        val next = layer.nextLayer("analysis-run")

        assertEquals(1, next.layerIndex)
        assertEquals("L2", next.layerLabel)
        assertEquals(listOf("seed-run", "analysis-run"), next.upstreamRunIds)
        assertEquals("Research", next.layerObjective)
    }
}
