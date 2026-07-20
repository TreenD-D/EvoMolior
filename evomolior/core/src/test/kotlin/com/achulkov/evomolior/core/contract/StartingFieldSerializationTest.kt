package com.achulkov.evomolior.core.contract

import com.achulkov.evomolior.core.model.GroupProfileType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
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
    fun `council seed artifacts without domains default to general`() {
        val json = Json { ignoreUnknownKeys = true }
        val original = StartingField.default(
            objective = "resilient council seeds",
            runId = "run-resilient-council-seeds"
        ).copy(
            primordialKb = listOf(SeedKBDocument("kb-1", "seed", "research")),
            primordialKgFacts = listOf(
                SeedKGFact("fact-1", "A", "RELATES_TO", "B", 0.9, "research")
            ),
            primordialTools = listOf(
                SeedTool(
                    id = "tool-1",
                    name = "parser",
                    description = "Parses a document.",
                    inputSchemaJson = "{}",
                    outputSchemaJson = "{}",
                    code = "return input",
                    domain = "research"
                )
            )
        )
        val encoded = json.encodeToJsonElement(StartingField.serializer(), original).jsonObject
        val withoutSeedDomains = buildJsonObject {
            encoded.forEach { (key, value) ->
                val normalized = when (key) {
                    "primordialKb", "primordialKgFacts", "primordialTools" -> JsonArray(
                        value.jsonArray.map { element ->
                            JsonObject(element.jsonObject - "domain")
                        }
                    )
                    else -> value
                }
                put(key, normalized)
            }
        }

        val decoded = json.decodeFromJsonElement(StartingField.serializer(), withoutSeedDomains)

        assertEquals("general", decoded.primordialKb.single().domain)
        assertEquals("general", decoded.primordialKgFacts.single().domain)
        assertEquals("general", decoded.primordialTools.single().domain)
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
    fun `starting field json preserves typed council resource capture map`() {
        val json = Json { ignoreUnknownKeys = true }
        val original = StartingField.default(
            objective = "capture graded deliverable parts",
            runId = "run-resource-map"
        ).copy(
            resourceNodes = listOf(
                ResourceNodeSeed(
                    nodeId = "resource-market",
                    name = "Market evidence",
                    deliverableNodeRef = "dst:section:market",
                    scoringDimensionIds = listOf("evidence-quality"),
                    resourceType = ResourceYieldType.WEB_CACHE,
                    qualityThreshold = 0.7,
                    yieldMultiplier = 1.2,
                    floorGroupId = null
                )
            )
        )

        val decoded = json.decodeFromString(
            StartingField.serializer(),
            json.encodeToString(StartingField.serializer(), original)
        )

        assertEquals(original.resourceNodes, decoded.resourceNodes)
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
