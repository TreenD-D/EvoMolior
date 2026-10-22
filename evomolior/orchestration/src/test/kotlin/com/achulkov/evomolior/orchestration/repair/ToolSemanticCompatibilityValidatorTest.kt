package com.achulkov.evomolior.orchestration.repair

import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.PhysicalDimension
import com.achulkov.evomolior.core.atlas.ToolBehavior
import com.achulkov.evomolior.core.atlas.ToolImplementationMode
import com.achulkov.evomolior.core.atlas.ToolOpportunity
import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitment
import com.achulkov.evomolior.core.atlas.ToolOpportunityType
import com.achulkov.evomolior.core.atlas.TypedField
import com.achulkov.evomolior.core.atlas.TypedFieldType
import com.achulkov.evomolior.core.repair.AggregationSemantics
import com.achulkov.evomolior.core.repair.PublicToolFinding
import com.achulkov.evomolior.core.repair.QuantitySpec
import com.achulkov.evomolior.core.repair.TimeBasis
import com.achulkov.evomolior.core.repair.ToolRepairCode
import com.achulkov.evomolior.core.repair.ToolSemanticReview
import com.achulkov.evomolior.core.repair.ToolSemanticVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ToolSemanticCompatibilityValidatorTest {
    private val validator = ToolSemanticCompatibilityValidator()
    private val commitment = powerToEnergyCommitment()

    @Test
    fun `power to energy review compiles a delayed safe diagnostic without private leakage`() {
        val output = commitment.opportunity.requiredOutputs.single()
        val review = ToolSemanticReview(
            verdict = ToolSemanticVerdict.REJECT,
            publicFindings = listOf(
                PublicToolFinding(
                    code = ToolRepairCode.POWER_TO_ENERGY_UNIT_CONVERSION,
                    affectedPublicField = output.name,
                    quantitySpec = output.quantitySpec
                )
            ),
            safeRepairCodes = setOf(ToolRepairCode.POWER_TO_ENERGY_UNIT_CONVERSION),
            privateRationaleCommitment = AtlasHashing.hashText("private critic rationale")
        )

        assertEquals(
            ToolSemanticCompatibilityResult.Accepted,
            validator.validate(review, commitment)
        )
        val diagnostic = validator.compileDiagnostics(
            review = review,
            commitment = commitment,
            candidateHash = AtlasHashing.hashText("first candidate"),
            groupId = "group-a",
            createdRound = 1
        ).single()
        val encoded = Json.encodeToString(diagnostic)

        assertEquals(2, diagnostic.visibleFromRound)
        assertTrue(diagnostic.hasValidHash())
        assertTrue(encoded.contains("POWER_TO_ENERGY_UNIT_CONVERSION"))
        listOf(
            "private-test",
            "expectedOutput",
            "actualOutput",
            "/Users/",
            "1000.0"
        ).forEach { forbidden -> assertFalse(encoded.contains(forbidden)) }
    }

    @Test
    fun `semantic validator rejects invented fields and incompatible quantity categories`() {
        val unknownField = ToolSemanticReview(
            verdict = ToolSemanticVerdict.REJECT,
            publicFindings = listOf(
                PublicToolFinding(
                    code = ToolRepairCode.POWER_TO_ENERGY_UNIT_CONVERSION,
                    affectedPublicField = "private_expected_value"
                )
            ),
            safeRepairCodes = setOf(ToolRepairCode.POWER_TO_ENERGY_UNIT_CONVERSION),
            privateRationaleCommitment = "a".repeat(64)
        )
        assertEquals(
            ToolSemanticCompatibilityResult.Rejected(
                ToolSemanticCompatibilityRejection.UNKNOWN_PUBLIC_FIELD
            ),
            validator.validate(unknownField, commitment)
        )

        val wrongField = unknownField.copy(
            publicFindings = listOf(
                PublicToolFinding(
                    code = ToolRepairCode.PERCENT_NORMALIZATION_ERROR,
                    affectedPublicField = "energy_kwh",
                    quantitySpec = commitment.opportunity.requiredOutputs.single().quantitySpec
                )
            ),
            safeRepairCodes = setOf(ToolRepairCode.PERCENT_NORMALIZATION_ERROR)
        )
        assertEquals(
            ToolSemanticCompatibilityResult.Rejected(
                ToolSemanticCompatibilityRejection.CODE_INCOMPATIBLE_WITH_PUBLIC_CONTRACT
            ),
            validator.validate(wrongField, commitment)
        )
    }
}

internal fun powerToEnergyCommitment(): ToolOpportunityCommitment {
    val power = PhysicalDimension(mapOf("power" to 1))
    val time = PhysicalDimension(mapOf("time" to 1))
    val energy = PhysicalDimension(mapOf("energy" to 1))
    return ToolOpportunityCommitment(
        runId = "run-power-energy",
        atlasHash = "atlas-power-energy",
        nodeId = NodeId("node-power-energy"),
        opportunity = ToolOpportunity(
            opportunityId = "power-to-energy",
            opportunityType = ToolOpportunityType.CALCULATION,
            repeatedQuestionPattern = "Convert constant power over hours to total energy.",
            requiredInputs = listOf(
                TypedField(
                    name = "power_watts",
                    type = TypedFieldType.NUMBER,
                    description = "Constant input power.",
                    physicalDimension = power,
                    quantitySpec = QuantitySpec(
                        dimension = power,
                        unitId = "W",
                        scaleToSi = 1.0,
                        aggregation = AggregationSemantics.INSTANTANEOUS
                    )
                ),
                TypedField(
                    name = "duration_hours",
                    type = TypedFieldType.NUMBER,
                    description = "Duration in hours.",
                    physicalDimension = time,
                    quantitySpec = QuantitySpec(
                        dimension = time,
                        unitId = "h",
                        scaleToSi = 3_600.0,
                        aggregation = AggregationSemantics.INSTANTANEOUS
                    )
                )
            ),
            requiredOutputs = listOf(
                TypedField(
                    name = "energy_kwh",
                    type = TypedFieldType.NUMBER,
                    description = "Total energy in kilowatt-hours.",
                    physicalDimension = energy,
                    quantitySpec = QuantitySpec(
                        dimension = energy,
                        unitId = "kWh",
                        scaleToSi = 3_600_000.0,
                        aggregation = AggregationSemantics.SUM_OVER_INTERVALS,
                        timeBasis = TimeBasis.HOUR
                    )
                )
            ),
            validationStrategy = "Compare public dimensional reference calculations.",
            expectedReuseNodeIds = setOf(NodeId("node-power-energy-reuse")),
            preferredImplementationMode = ToolImplementationMode.PYTHON,
            behavior = ToolBehavior.DETERMINISTIC,
            promotionTestIds = setOf("sealed-power-energy"),
            maximumRuntimeMillis = 2_000,
            maximumOutputBytes = 4_096
        )
    ).withComputedHash()
}
