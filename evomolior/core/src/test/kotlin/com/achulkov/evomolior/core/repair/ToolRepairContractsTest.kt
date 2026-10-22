package com.achulkov.evomolior.core.repair

import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.PhysicalDimension
import com.achulkov.evomolior.core.atlas.TypedField
import com.achulkov.evomolior.core.atlas.TypedFieldType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ToolRepairContractsTest {
    private val power = PhysicalDimension(mapOf("power" to 1))
    private val energy = PhysicalDimension(mapOf("energy" to 1))

    @Test
    fun `repair taxonomy exactly matches the completion blueprint`() {
        assertEquals(
            listOf(
                "CONTRACT_SCHEMA_MISMATCH",
                "MISSING_REQUIRED_CONTRACT_FIELD",
                "NON_CANONICAL_NUMBER",
                "PUBLIC_TEST_EXPECTED_OUTPUT_MISMATCH",
                "INPUT_NOT_INFLUENCING_OUTPUT",
                "POWER_TO_ENERGY_UNIT_CONVERSION",
                "RATE_VERSUS_TOTAL_CONFUSION",
                "AGGREGATION_WINDOW_MISMATCH",
                "DIMENSIONAL_MISMATCH",
                "PERCENT_NORMALIZATION_ERROR",
                "SIGN_CONVENTION_ERROR",
                "TEMPORAL_INDEX_ALIGNMENT_ERROR",
                "BOUNDARY_CONDITION_ERROR",
                "SCOPE_MISMATCH",
                "OUTPUT_SEMANTICS_MISMATCH",
                "PRIVATE_TEST_FAILED_UNCLASSIFIED"
            ),
            ToolRepairCode.entries.map(Enum<*>::name)
        )
        assertEquals(
            listOf(
                "PUBLIC_PREFLIGHT",
                "CRITIC_SEMANTIC_REVIEW",
                "PRIVATE_PROMOTION_AUTHORITY"
            ),
            ToolRepairSource.entries.map(Enum<*>::name)
        )
        assertEquals(
            listOf("PUBLIC_EXACT", "SAFE_SEMANTIC_CATEGORY", "PRIVATE_FAILURE_ONLY"),
            ToolRepairDisclosureClass.entries.map(Enum<*>::name)
        )
    }

    @Test
    fun `quantity metadata uses the closed unit catalog and interval semantics`() {
        val quantity = QuantitySpec(
            dimension = energy,
            unitId = "kWh",
            scaleToSi = 3_600_000.0,
            aggregation = AggregationSemantics.SUM_OVER_INTERVALS,
            timeBasis = TimeBasis.HOUR
        )

        assertTrue(ToolUnitCatalog.isCompatible(quantity))
        assertFailsWith<IllegalArgumentException> {
            quantity.copy(scaleToSi = 1_000.0)
        }
        assertFailsWith<IllegalArgumentException> {
            QuantitySpec(
                dimension = power,
                unitId = "W",
                scaleToSi = 1.0,
                aggregation = AggregationSemantics.SUM_OVER_INTERVALS
            )
        }
    }

    @Test
    fun `shelter geometry and envelope units have exact catalog dimensions and scales`() {
        val area = QuantitySpec(
            dimension = PhysicalDimension(mapOf("length" to 2)),
            unitId = "m2",
            scaleToSi = 1.0,
            aggregation = AggregationSemantics.INSTANTANEOUS
        )
        val uValue = QuantitySpec(
            dimension = PhysicalDimension(
                mapOf("power" to 1, "length" to -2, "temperature" to -1)
            ),
            unitId = "W/(m2*K)",
            scaleToSi = 1.0,
            aggregation = AggregationSemantics.INSTANTANEOUS
        )

        assertTrue(ToolUnitCatalog.isCompatible(area))
        assertTrue(ToolUnitCatalog.isCompatible(uValue))
        assertFailsWith<IllegalArgumentException> {
            area.copy(dimension = PhysicalDimension(mapOf("length" to 1)))
        }
        assertFailsWith<IllegalArgumentException> {
            uValue.copy(scaleToSi = 1_000.0)
        }
    }

    @Test
    fun `optional quantity metadata preserves legacy typed-field bytes and changes new commitments`() {
        val json = Json { encodeDefaults = true; explicitNulls = true }
        val legacy = TypedField(
            name = "power",
            type = TypedFieldType.NUMBER,
            description = "Instantaneous power.",
            physicalDimension = power
        )
        val legacyBytes = json.encodeToString(legacy)
        val typed = legacy.copy(
            quantitySpec = QuantitySpec(
                dimension = power,
                unitId = "W",
                scaleToSi = 1.0,
                aggregation = AggregationSemantics.INSTANTANEOUS
            )
        )

        assertFalse(legacyBytes.contains("quantitySpec"))
        assertTrue(json.encodeToString(typed).contains("quantitySpec"))
        assertNotEquals(
            AtlasHashing.hashText(legacyBytes),
            AtlasHashing.hashText(json.encodeToString(typed))
        )
    }

    @Test
    fun `diagnostics are delayed hash-bound and contain no prose channel`() {
        val diagnostic = ToolRepairDiagnosticV1(
            diagnosticId = "repair-power-energy",
            runId = "run",
            groupId = "group",
            nodeId = "node",
            opportunityId = "power-energy",
            opportunityHash = "a".repeat(64),
            candidateHash = "b".repeat(64),
            source = ToolRepairSource.PRIVATE_PROMOTION_AUTHORITY,
            disclosureClass = ToolRepairDisclosureClass.SAFE_SEMANTIC_CATEGORY,
            code = ToolRepairCode.POWER_TO_ENERGY_UNIT_CONVERSION,
            createdRound = 1,
            visibleFromRound = 2,
            affectedPublicField = "energy_kwh",
            publicQuantitySpec = QuantitySpec(
                dimension = energy,
                unitId = "kWh",
                scaleToSi = 3_600_000.0,
                aggregation = AggregationSemantics.SUM_OVER_INTERVALS,
                timeBasis = TimeBasis.HOUR
            ),
            rationaleCommitment = "c".repeat(64)
        ).withComputedHash()
        val encoded = Json.encodeToString(diagnostic)

        assertTrue(diagnostic.hasValidHash())
        assertFalse(encoded.contains("fixture"))
        assertFalse(encoded.contains("expectedOutput"))
        assertFalse(encoded.contains("actual"))
        assertFalse(encoded.contains("path"))
        assertFailsWith<IllegalArgumentException> {
            diagnostic.copy(visibleFromRound = 1)
        }
    }

    @Test
    fun `repair attempts and strategies are bounded to three`() {
        val attempt = ToolRepairAttemptRecord(
            attemptId = "attempt-1",
            opportunityId = "power-energy",
            candidateHash = "d".repeat(64),
            authorAgentId = "builder",
            round = 2,
            consumedDiagnosticIds = listOf("repair-power-energy"),
            preflightResult = ToolValidationStatus.PASSED,
            criticResult = ToolSemanticVerdict.PROMOTE,
            promotionResult = ToolPromotionStatus.PROMOTED
        ).withComputedHash()
        val vector = ApproachVector(
            vectorId = "vector-1",
            runId = "run",
            groupId = "group",
            opportunityId = "power-energy",
            round = 2,
            lineageAttempt = 3,
            strategy = RepairStrategy.DIMENSIONAL_ANALYSIS_FIRST
        ).withComputedHash()

        assertTrue(attempt.hasValidHash())
        assertTrue(vector.hasValidHash())
        assertFailsWith<IllegalArgumentException> {
            vector.copy(lineageAttempt = 4)
        }
    }
}
