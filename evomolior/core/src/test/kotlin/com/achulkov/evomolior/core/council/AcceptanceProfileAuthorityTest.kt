package com.achulkov.evomolior.core.council

import com.achulkov.evomolior.core.atlas.FinalOutputSpec
import com.achulkov.evomolior.core.atlas.PhysicalDimension
import com.achulkov.evomolior.core.atlas.ToolOpportunityType
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.contract.GroupTopologyKind
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.repair.AggregationSemantics
import com.achulkov.evomolior.core.repair.QuantitySpec
import com.achulkov.evomolior.core.repair.TimeBasis
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
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

    @Test
    fun `quantitative tool obligation is serialized and changes envelope authority hash`() {
        val normal = envelope()
        val obligation = QuantitativeToolObligation(
            horizonTimeBasis = TimeBasis.HOUR,
            horizonIntervalCount = 72,
            allowedOpportunityTypes = setOf(ToolOpportunityType.CALCULATION),
            requiredOpportunityId = "hourly_power_audit",
            exactPromotionTestIds = setOf("runtime_boundary"),
            maximumOpportunityRuntimeMillis = 8_000,
            requiredFields = listOf(
                QuantitativeToolFieldObligation(
                    roleId = "hourly_power",
                    fieldName = "hourly_power_kw",
                    location = QuantitativeToolFieldLocation.OUTPUT,
                    shape = QuantitativeToolFieldShape.HORIZON_SERIES,
                    quantitySpec = QuantitySpec(
                        dimension = PhysicalDimension(mapOf("power" to 1)),
                        unitId = "kW",
                        scaleToSi = 1_000.0,
                        aggregation = AggregationSemantics.AVERAGE_OVER_INTERVALS,
                        timeBasis = TimeBasis.HOUR
                    )
                )
            )
        )
        val quantitative = normal.copy(quantitativeToolObligation = obligation)
        val encoded = json.encodeToString(ExecutionEnvelope.serializer(), quantitative)

        assertTrue(encoded.contains("\"quantitativeToolObligation\""))
        assertTrue(encoded.contains("\"requiredOpportunityId\":\"hourly_power_audit\""))
        assertTrue(encoded.contains("\"exactPromotionTestIds\":[\"runtime_boundary\"]"))
        assertTrue(encoded.contains("\"maximumOpportunityRuntimeMillis\":8000"))
        assertEquals(
            quantitative,
            json.decodeFromString(ExecutionEnvelope.serializer(), encoded)
        )
        assertNotEquals(
            CouncilContractHashing.hashExecutionEnvelope(normal),
            CouncilContractHashing.hashExecutionEnvelope(quantitative)
        )
    }

    @Test
    fun `quantitative tool obligation compiles copy-ready scalar and horizon field authority`() {
        val obligation = QuantitativeToolObligation(
            horizonTimeBasis = TimeBasis.HOUR,
            horizonIntervalCount = 72,
            allowedOpportunityTypes = setOf(ToolOpportunityType.CALCULATION),
            requiredOpportunityId = "hourly_power_audit",
            exactPromotionTestIds = linkedSetOf("unit_boundary", "runtime_boundary"),
            maximumOpportunityRuntimeMillis = 8_000,
            requiredFields = listOf(
                QuantitativeToolFieldObligation(
                    roleId = "sample_count",
                    fieldName = "sample_count",
                    location = QuantitativeToolFieldLocation.INPUT,
                    shape = QuantitativeToolFieldShape.SCALAR,
                    numericType = com.achulkov.evomolior.core.atlas.TypedFieldType.INTEGER,
                    quantitySpec = QuantitySpec(
                        dimension = PhysicalDimension(),
                        unitId = "1",
                        scaleToSi = 1.0,
                        aggregation = AggregationSemantics.INSTANTANEOUS
                    )
                ),
                QuantitativeToolFieldObligation(
                    roleId = "hourly_power",
                    fieldName = "hourly_power_kw",
                    location = QuantitativeToolFieldLocation.OUTPUT,
                    shape = QuantitativeToolFieldShape.HORIZON_SERIES,
                    quantitySpec = QuantitySpec(
                        dimension = PhysicalDimension(mapOf("power" to 1)),
                        unitId = "kW",
                        scaleToSi = 1_000.0,
                        aggregation = AggregationSemantics.AVERAGE_OVER_INTERVALS,
                        timeBasis = TimeBasis.HOUR
                    )
                )
            )
        )

        val authority = obligation.compileCouncilToolOpportunityAuthority()
        val scalar = authority.getValue("requiredInputs").jsonArray.single().jsonObject
        val series = authority.getValue("requiredOutputs").jsonArray.single().jsonObject
        val items = series.getValue("items").jsonObject

        assertEquals(
            listOf("runtime_boundary", "unit_boundary"),
            authority.getValue("promotionTestIds").jsonArray.map {
                it.jsonPrimitive.content
            }
        )
        assertEquals("INTEGER", scalar.getValue("type").jsonPrimitive.content)
        assertEquals(
            "null",
            scalar.getValue("quantitySpec").jsonObject
                .getValue("timeBasis").jsonPrimitive.content
        )
        assertEquals("ARRAY", series.getValue("type").jsonPrimitive.content)
        assertEquals(72, series.getValue("minimumItems").jsonPrimitive.content.toInt())
        assertEquals(72, series.getValue("maximumItems").jsonPrimitive.content.toInt())
        assertEquals("NUMBER_ITEMS", items.getValue("type").jsonPrimitive.content)
        assertEquals(
            "HOUR",
            items.getValue("quantitySpec").jsonObject
                .getValue("timeBasis").jsonPrimitive.content
        )
        assertFalse("roleId" in scalar)
        assertFalse("numericType" in scalar)
    }

    @Test
    fun `runtime usability smoke rejects an impossible quantitative obligation`() {
        val quantitative = envelope().copy(
            quantitativeToolObligation = QuantitativeToolObligation(
                horizonTimeBasis = TimeBasis.HOUR,
                horizonIntervalCount = 1,
                requiredFields = listOf(
                    QuantitativeToolFieldObligation(
                        roleId = "hourly_power",
                        fieldName = "hourly_power_kw",
                        location = QuantitativeToolFieldLocation.OUTPUT,
                        shape = QuantitativeToolFieldShape.HORIZON_SERIES,
                        quantitySpec = QuantitySpec(
                            dimension = PhysicalDimension(mapOf("power" to 1)),
                            unitId = "kW",
                            scaleToSi = 1_000.0,
                            aggregation = AggregationSemantics.AVERAGE_OVER_INTERVALS,
                            timeBasis = TimeBasis.HOUR
                        )
                    )
                )
            )
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            quantitative.copy(
                acceptanceProfile = AcceptanceProfile.RUNTIME_USABILITY_SMOKE
            )
        }

        assertTrue(failure.message.orEmpty().contains("forbids tool opportunities"))
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
