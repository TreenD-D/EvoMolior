@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.core.atlas

import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolInfrastructureContractsTest {
    private val json = Json { encodeDefaults = true }
    private val at = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `rich opportunity survives serialization and rejects incomplete schemas`() {
        val opportunity = opportunity()

        assertEquals(
            opportunity,
            json.decodeFromString<ToolOpportunity>(json.encodeToString(opportunity))
        )
        assertFailsWith<IllegalArgumentException> {
            opportunity(requiredOutputs = emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            TypedField(
                name = "items",
                type = TypedFieldType.ARRAY,
                description = "Items without an item type.",
                minimumItems = 0,
                maximumItems = 1
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TypedField(
                name = "record",
                type = TypedFieldType.OBJECT,
                description = "Object without structural fields."
            )
        }
    }

    @Test
    fun `compiled schemas enforce fixed arrays and nested closed objects recursively`() {
        val inputSchema = Json.parseToJsonElement(
            TypedToolSchemaCompiler.compile(
                listOf(
                    TypedField(
                        name = "scenario",
                        type = TypedFieldType.OBJECT,
                        description = "Shelter scenario.",
                        objectFields = setOf(
                            TypedField(
                                name = "hours",
                                type = TypedFieldType.INTEGER,
                                description = "Forecast horizon."
                            ),
                            TypedField(
                                name = "outdoor_temperatures",
                                type = TypedFieldType.ARRAY,
                                description = "Exactly 72 outdoor temperatures.",
                                itemType = TypedFieldType.NUMBER,
                                minimumItems = 72,
                                maximumItems = 72
                            )
                        )
                    )
                )
            )
        ) as JsonObject
        val outputSchema = Json.parseToJsonElement(
            TypedToolSchemaCompiler.compile(
                listOf(
                    TypedField(
                        name = "hourly_results",
                        type = TypedFieldType.ARRAY,
                        description = "Exactly 72 hourly results.",
                        itemType = TypedFieldType.NUMBER,
                        minimumItems = 72,
                        maximumItems = 72
                    )
                )
            )
        ) as JsonObject
        val temperatures = JsonArray(List(72) { JsonPrimitive(20.0) })
        val validInput = JsonObject(
            mapOf(
                "scenario" to JsonObject(
                    mapOf(
                        "hours" to JsonPrimitive(72),
                        "outdoor_temperatures" to temperatures
                    )
                )
            )
        )
        val shortInput = JsonObject(
            mapOf(
                "scenario" to JsonObject(
                    mapOf(
                        "hours" to JsonPrimitive(72),
                        "outdoor_temperatures" to JsonArray(
                            List(71) { JsonPrimitive(20.0) }
                        )
                    )
                )
            )
        )
        val missingNestedInput = JsonObject(
            mapOf("scenario" to JsonObject(mapOf("outdoor_temperatures" to temperatures)))
        )
        val extraNestedInput = JsonObject(
            mapOf(
                "scenario" to JsonObject(
                    mapOf(
                        "hours" to JsonPrimitive(72),
                        "outdoor_temperatures" to temperatures,
                        "unknown" to JsonPrimitive(true)
                    )
                )
            )
        )
        val validOutput = JsonObject(
            mapOf(
                "hourly_results" to JsonArray(List(72) { JsonPrimitive(1.0) })
            )
        )
        val shortOutput = JsonObject(
            mapOf(
                "hourly_results" to JsonArray(List(71) { JsonPrimitive(1.0) })
            )
        )
        val longOutput = JsonObject(
            mapOf(
                "hourly_results" to JsonArray(List(73) { JsonPrimitive(1.0) })
            )
        )
        val scalarOutput = Json.parseToJsonElement("""{"hourly_results":1.0}""")

        assertNull(TypedToolSchemaValidator.validationError(validInput, inputSchema))
        val shortInputFailure = assertNotNull(
            TypedToolSchemaValidator.validationFailure(shortInput, inputSchema)
        )
        assertEquals(TypedToolSchemaValidationCode.TOO_FEW_ITEMS, shortInputFailure.code)
        assertEquals("root.scenario.outdoor_temperatures", shortInputFailure.path)
        assertEquals(71L, shortInputFailure.actualItemCount)
        assertEquals(72L, shortInputFailure.minimumItemCount)
        assertEquals(
            "too_few_items_root.scenario.outdoor_temperatures",
            shortInputFailure.stableCode
        )
        assertNotNull(
            TypedToolSchemaValidator.validationError(missingNestedInput, inputSchema)
        )
        assertNotNull(
            TypedToolSchemaValidator.validationError(extraNestedInput, inputSchema)
        )
        assertNull(TypedToolSchemaValidator.validationError(validOutput, outputSchema))
        val shortFailure = assertNotNull(
            TypedToolSchemaValidator.validationFailure(shortOutput, outputSchema)
        )
        assertEquals(TypedToolSchemaValidationCode.TOO_FEW_ITEMS, shortFailure.code)
        assertEquals("root.hourly_results", shortFailure.path)
        assertEquals(71L, shortFailure.actualItemCount)
        assertEquals(72L, shortFailure.minimumItemCount)
        assertEquals(72L, shortFailure.maximumItemCount)
        assertEquals("too_few_items_root.hourly_results", shortFailure.stableCode)
        assertEquals(
            shortFailure.stableCode,
            TypedToolSchemaValidator.validationError(shortOutput, outputSchema)
        )
        val longFailure = assertNotNull(
            TypedToolSchemaValidator.validationFailure(longOutput, outputSchema)
        )
        assertEquals(TypedToolSchemaValidationCode.TOO_MANY_ITEMS, longFailure.code)
        assertEquals(73L, longFailure.actualItemCount)
        assertEquals(72L, longFailure.maximumItemCount)
        assertEquals("too_many_items_root.hourly_results", longFailure.stableCode)
        assertNotNull(TypedToolSchemaValidator.validationError(scalarOutput, outputSchema))
    }

    @Test
    fun `opportunity commitments normalize nested field order and commit structural bounds`() {
        val hours = TypedField("hours", TypedFieldType.INTEGER, "Forecast horizon.")
        val area = TypedField("area", TypedFieldType.NUMBER, "Shelter area.")
        val base = opportunity().copy(
            requiredInputs = listOf(
                TypedField(
                    name = "scenario",
                    type = TypedFieldType.OBJECT,
                    description = "Shelter scenario.",
                    objectFields = linkedSetOf(hours, area)
                )
            ),
            requiredOutputs = listOf(
                TypedField(
                    name = "hourly_load",
                    type = TypedFieldType.ARRAY,
                    description = "Exactly 72 hourly loads.",
                    itemType = TypedFieldType.NUMBER,
                    minimumItems = 72,
                    maximumItems = 72
                )
            )
        )
        val reordered = base.copy(
            requiredInputs = listOf(
                base.requiredInputs.single().copy(
                    objectFields = linkedSetOf(area, hours)
                )
            )
        )
        val changedBound = base.copy(
            requiredOutputs = listOf(
                base.requiredOutputs.single().copy(maximumItems = 73)
            )
        )
        val changedNested = base.copy(
            requiredInputs = listOf(
                base.requiredInputs.single().copy(
                    objectFields = setOf(hours.copy(required = false), area)
                )
            )
        )

        assertEquals(commitment(base).commitmentHash, commitment(reordered).commitmentHash)
        assertNotEquals(commitment(base).commitmentHash, commitment(changedBound).commitmentHash)
        assertNotEquals(commitment(base).commitmentHash, commitment(changedNested).commitmentHash)
    }

    @Test
    fun `lifecycle requires exact hash linkage and legal ordered transitions`() {
        val specified = lifecycle(ToolLifecycleState.TOOL_SPEC).withComputedHash()
        val implemented = lifecycle(
            state = ToolLifecycleState.IMPLEMENTED,
            sequence = 1,
            previousEventHash = specified.eventHash
        ).withComputedHash()

        assertTrue(specified.hasValidHash())
        assertTrue(ToolLifecyclePolicy.allows(null, specified))
        assertTrue(ToolLifecyclePolicy.allows(specified, implemented))
        assertFalse(
            ToolLifecyclePolicy.allows(
                specified,
                implemented.copy(state = ToolLifecycleState.PROMOTED)
            )
        )
        assertFalse(implemented.copy(authorityReferenceIds = setOf("changed")).hasValidHash())
    }

    @Test
    fun `execution simulation reuse and delayed fitness hashes detect semantic mutation`() {
        val execution = execution().withComputedHash()
        val simulation = SimulationRunRecord(
            simulationRunId = "simulation-1",
            executionId = execution.executionId,
            toolVersionId = execution.toolVersionId,
            inputHash = execution.inputHash,
            parameters = mapOf("iterations" to JsonPrimitive(100)),
            assumptions = listOf("Observed rates remain stationary."),
            randomSeed = 42L,
            stochastic = true,
            outputArtifactId = "artifact-output",
            resultSummary = "The median result is inside the accepted range.",
            uncertainty = "95% interval reported in the artifact.",
            validationRefs = listOf("promotion-test-1"),
            executedBy = execution.agentId
        ).withComputedHash()
        val reuse = ToolReuseLineage(
            lineageId = "reuse-1",
            runId = execution.runId,
            toolVersionId = execution.toolVersionId,
            executionId = execution.executionId,
            originNodeId = NodeId("node-origin"),
            reusedByAgentId = execution.agentId,
            reusedOnNodeId = execution.nodeId,
            contributionCommitId = "commit-1",
            nodeEvidencePacketHash = "packet-hash",
            nodeScoreBefore = 0.5,
            nodeScoreAfter = 0.8,
            avoidedRepeatedCost = 2.0,
            attributedAt = at
        ).withComputedHash()
        val fitness = ToolFitnessRecord(
            recordId = "fitness-1",
            runId = execution.runId,
            toolVersionId = execution.toolVersionId,
            throughRound = 3,
            validationPasses = 2,
            uniqueLaterAgentIds = setOf(execution.agentId),
            successfulExecutionIds = setOf(execution.executionId),
            reuseLineageIds = setOf(reuse.lineageId),
            nodeScoreDeltaAttributedToResults = 0.3,
            avoidedRepeatedCost = 2.0,
            failureExecutionIds = setOf("failed-execution"),
            maintenanceCost = 0.5,
            fitnessValue = 4.8
        ).withComputedHash()

        assertTrue(execution.hasValidHash())
        assertTrue(simulation.hasValidHash())
        assertTrue(reuse.hasValidHash())
        assertTrue(fitness.hasValidHash())
        assertFalse(execution.copy(inputHash = "changed").hasValidHash())
        assertFalse(simulation.copy(randomSeed = 43L).hasValidHash())
        assertFalse(reuse.copy(avoidedRepeatedCost = 3.0).hasValidHash())
        assertFalse(fitness.copy(maintenanceCost = 1.0, fitnessValue = 4.3).hasValidHash())
    }

    @Test
    fun `failed runs and stochastic simulations fail closed when authority is inconsistent`() {
        assertEquals(null, ToolMetricRatio.of(0, 0).value)
        assertFailsWith<IllegalArgumentException> {
            execution().copy(
                result = ToolExecutionResult.FAILED,
                failure = null,
                outputHash = null
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SimulationRunRecord(
                simulationRunId = "simulation-1",
                executionId = "execution-1",
                toolVersionId = ToolVersionId("tool-v1"),
                inputHash = "input-hash",
                parameters = mapOf("iterations" to JsonPrimitive(10)),
                assumptions = listOf("One assumption."),
                randomSeed = null,
                stochastic = true,
                outputArtifactId = "artifact-1",
                resultSummary = "Result.",
                uncertainty = "Interval.",
                validationRefs = emptyList(),
                executedBy = AtlasAgentId("agent-1")
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ToolOpportunity(
                opportunityId = "deterministic-simulation",
                opportunityType = ToolOpportunityType.SIMULATION,
                repeatedQuestionPattern = "Run one deterministic bounded model.",
                requiredInputs = listOf(
                    TypedField("randomSeed", TypedFieldType.INTEGER, "Prohibited seed.")
                ),
                requiredOutputs = listOf(
                    TypedField("summary", TypedFieldType.STRING, "Result summary."),
                    TypedField("uncertainty", TypedFieldType.STRING, "Explicit uncertainty.")
                ),
                validationStrategy = "Compare exact outputs.",
                expectedReuseNodeIds = setOf(NodeId("node-later")),
                preferredImplementationMode = ToolImplementationMode.SIMULATION_DSL,
                behavior = ToolBehavior.DETERMINISTIC,
                promotionTestIds = setOf("deterministic-test"),
                maximumRuntimeMillis = 1_000,
                maximumOutputBytes = 4_096,
                assumptions = listOf("The model has no stochastic component."),
                simulationRecordBindings = SimulationRecordBindings(
                    resultSummaryOutputField = "summary",
                    uncertaintyOutputField = "uncertainty",
                    randomSeedInputField = "randomSeed"
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ToolFitnessRecord(
                recordId = "fitness-before-reuse",
                runId = "run-1",
                toolVersionId = ToolVersionId("tool-v1"),
                throughRound = 1,
                validationPasses = 1,
                uniqueLaterAgentIds = emptySet(),
                successfulExecutionIds = emptySet(),
                reuseLineageIds = emptySet(),
                nodeScoreDeltaAttributedToResults = 0.0,
                avoidedRepeatedCost = 0.0,
                failureExecutionIds = emptySet(),
                maintenanceCost = 0.0,
                fitnessValue = 2.0
            )
        }
    }

    private fun opportunity(
        requiredOutputs: List<TypedField> = listOf(
            TypedField("result", TypedFieldType.NUMBER, "Validated calculation result.")
        )
    ) = ToolOpportunity(
        opportunityId = "opportunity-1",
        opportunityType = ToolOpportunityType.CALCULATION,
        repeatedQuestionPattern = "Calculate the normalized result for each candidate.",
        requiredInputs = listOf(
            TypedField("value", TypedFieldType.NUMBER, "Candidate input value.")
        ),
        requiredOutputs = requiredOutputs,
        validationStrategy = "Compare exact outputs against committed boundary cases.",
        expectedReuseNodeIds = setOf(NodeId("node-later")),
        preferredImplementationMode = ToolImplementationMode.FORMULA_DSL,
        behavior = ToolBehavior.DETERMINISTIC,
        requiredCapabilities = setOf("shell"),
        promotionTestIds = setOf("promotion-test-1"),
        maximumRuntimeMillis = 1_000,
        maximumOutputBytes = 16_384,
        assumptions = listOf("Inputs are finite."),
        knownInvalidDomains = listOf("Non-finite numeric values.")
    )

    private fun commitment(opportunity: ToolOpportunity) = ToolOpportunityCommitment(
        runId = "run-1",
        atlasHash = "atlas-hash",
        nodeId = NodeId("node-origin"),
        opportunity = opportunity
    ).withComputedHash()

    private fun lifecycle(
        state: ToolLifecycleState,
        sequence: Long = 0,
        previousEventHash: String? = null
    ) = ToolLifecycleEvent(
        eventId = "event-${state.name}",
        runId = "run-1",
        opportunityId = "opportunity-1",
        toolVersionId = ToolVersionId("tool-v1"),
        groupId = AtlasGroupId("group-1"),
        originNodeId = NodeId("node-origin"),
        contextNodeId = NodeId("node-origin"),
        state = state,
        sequence = sequence,
        previousEventHash = previousEventHash,
        authorityReferenceIds = if (state == ToolLifecycleState.TOOL_SPEC) {
            setOf("atlas-hash")
        } else {
            setOf("implementation-hash")
        },
        recordedAt = at
    )

    private fun execution() = ToolExecutionRecord(
        executionId = "execution-1",
        runId = "run-1",
        toolVersionId = ToolVersionId("tool-v1"),
        opportunityId = "opportunity-1",
        groupId = AtlasGroupId("group-1"),
        agentId = AtlasAgentId("agent-later"),
        round = 3,
        nodeId = NodeId("node-later"),
        purpose = ToolExecutionPurpose.AGENT_USE,
        inputHash = "input-hash",
        outputHash = "output-hash",
        outputJson = JsonPrimitive(1),
        outputArtifactId = "artifact-output",
        startedAt = at,
        completedAt = at,
        durationMillis = 10,
        exitCode = 0,
        result = ToolExecutionResult.SUCCEEDED,
        sandboxRecordHash = "sandbox-record-hash"
    )
}
