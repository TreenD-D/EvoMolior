package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.atlas.AtlasGroupId
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
import com.achulkov.evomolior.core.repair.QuantitySpec
import com.achulkov.evomolior.core.repair.TimeBasis
import com.achulkov.evomolior.core.repair.ToolRepairCode
import com.achulkov.evomolior.core.repair.ToolRepairDiagnosticV1
import com.achulkov.evomolior.core.repair.ToolRepairDisclosureClass
import com.achulkov.evomolior.core.repair.ToolRepairSource
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class WorkerToolRepairDiagnosticAssemblerTest {
    private val runId = "run-repair"
    private val groupId = AtlasGroupId("group-repair")
    private val nodeId = NodeId("node-tool")
    private val commitment = powerToEnergyCommitment()

    @Test
    fun `typed adapter exposes only delayed exact safe diagnostics`() {
        val visible = diagnostic("visible", createdRound = 1, visibleFromRound = 2)
        val future = diagnostic("future", createdRound = 2, visibleFromRound = 3)
        val privateOnly = diagnostic(
            id = "private-only",
            createdRound = 1,
            visibleFromRound = 2,
            code = ToolRepairCode.PRIVATE_TEST_FAILED_UNCLASSIFIED,
            disclosure = ToolRepairDisclosureClass.PRIVATE_FAILURE_ONLY,
            field = null,
            quantity = null
        )
        val foreign = diagnostic("foreign", 1, 2).copy(groupId = "other-group")
            .withComputedHash()

        val projected = WorkerToolRepairDiagnosticAssembler.project(
            diagnostics = listOf(future, foreign, privateOnly, visible),
            runId = runId,
            groupId = groupId,
            round = 2,
            commitment = commitment
        )

        assertEquals(listOf("visible"), projected.map { it.diagnosticId })
        val value = projected.single()
        assertEquals(ToolRepairCode.POWER_TO_ENERGY_UNIT_CONVERSION, value.code)
        assertEquals(
            "tool_repair.power_to_energy_unit_conversion",
            value.remediationKey
        )
        assertEquals("energy_kwh", value.affectedPublicField)
        assertFalse(value.toString().contains("fixture"))
        assertFalse(value.toString().contains("expected"))
        assertFalse(value.toString().contains("actual"))
    }

    @Test
    fun `row compatibility hook accepts typed JSON and ignores legacy prose`() {
        val typed = diagnostic("typed-row", 1, 2)
        val rows = listOf(
            row("legacy", "Arbitration: private fixture expects 42.0 at /Users/private"),
            row("typed", Json.encodeToString(typed))
        )

        val projected = WorkerToolRepairDiagnosticAssembler.assemble(
            rows = rows,
            runId = runId,
            groupId = groupId,
            round = 2,
            commitment = commitment
        )

        assertEquals(listOf("typed-row"), projected.map { it.diagnosticId })
    }

    @Test
    fun `disclosure boundary rejects diagnostic JSON carrying a number or path`() {
        val encoded = Json.encodeToString(diagnostic("injected-row", 1, 2))
        val injected = encoded.dropLast(1) +
            ""","privateExpected":1000.0,"fixturePath":"/Users/private/fixture.json"}"""

        val projected = WorkerToolRepairDiagnosticAssembler.assemble(
            rows = listOf(row("injected", injected)),
            runId = runId,
            groupId = groupId,
            round = 2,
            commitment = commitment
        )

        assertTrue(projected.isEmpty())
    }

    @Test
    fun `paged loader remains bounded while looking for typed authority`() = runTest {
        val rows = (1..12).map { index ->
            row("legacy-$index", "raw rejection prose $index")
        } + row("typed", Json.encodeToString(diagnostic("typed-later", 1, 2)))
        val requests = mutableListOf<Pair<Int, Int>>()

        val projected = WorkerToolRepairDiagnosticAssembler.load(
            pageLoader = { limit, offset ->
                requests += limit to offset
                rows.drop(offset).take(limit)
            },
            runId = runId,
            groupId = groupId,
            round = 2,
            commitment = commitment
        )

        assertEquals(listOf("typed-later"), projected.map { it.diagnosticId })
        assertEquals(listOf(12 to 0, 12 to 12), requests)
    }

    private fun diagnostic(
        id: String,
        createdRound: Int,
        visibleFromRound: Int,
        code: ToolRepairCode = ToolRepairCode.POWER_TO_ENERGY_UNIT_CONVERSION,
        disclosure: ToolRepairDisclosureClass =
            ToolRepairDisclosureClass.SAFE_SEMANTIC_CATEGORY,
        field: String? = "energy_kwh",
        quantity: QuantitySpec? = commitment.opportunity.requiredOutputs.single().quantitySpec
    ): ToolRepairDiagnosticV1 = ToolRepairDiagnosticV1(
        diagnosticId = id,
        runId = runId,
        groupId = groupId.value,
        nodeId = nodeId.value,
        opportunityId = commitment.opportunity.opportunityId,
        opportunityHash = commitment.commitmentHash,
        candidateHash = "b".repeat(64),
        source = ToolRepairSource.PRIVATE_PROMOTION_AUTHORITY,
        disclosureClass = disclosure,
        code = code,
        createdRound = createdRound,
        visibleFromRound = visibleFromRound,
        affectedPublicField = field,
        publicQuantitySpec = quantity,
        rationaleCommitment = "c".repeat(64)
    ).withComputedHash()

    private fun row(id: String, reason: String) = StagingSubmissionEntity(
        id = id,
        agentId = "builder-$id",
        role = "Builder",
        type = "TOOL",
        content = "{}",
        wipTaskRef = nodeId.value,
        budgetSignature = "signature",
        status = "rejected",
        rejectionReason = reason,
        criticAgentId = "critic",
        generation = 1,
        groupId = groupId.value,
        runId = runId,
        submittedAtMs = 1,
        reviewedAtMs = 2
    )

    private fun powerToEnergyCommitment(): ToolOpportunityCommitment {
        val power = PhysicalDimension(mapOf("power" to 1))
        val time = PhysicalDimension(mapOf("time" to 1))
        val energy = PhysicalDimension(mapOf("energy" to 1))
        return ToolOpportunityCommitment(
            runId = runId,
            atlasHash = "atlas-hash",
            nodeId = nodeId,
            opportunity = ToolOpportunity(
                opportunityId = "power-to-energy",
                opportunityType = ToolOpportunityType.CALCULATION,
                repeatedQuestionPattern = "Convert power over time to energy.",
                requiredInputs = listOf(
                    TypedField(
                        "power_watts",
                        TypedFieldType.NUMBER,
                        "Power in watts.",
                        physicalDimension = power,
                        quantitySpec = QuantitySpec(
                            power,
                            "W",
                            1.0,
                            AggregationSemantics.INSTANTANEOUS
                        )
                    ),
                    TypedField(
                        "duration_hours",
                        TypedFieldType.NUMBER,
                        "Duration in hours.",
                        physicalDimension = time,
                        quantitySpec = QuantitySpec(
                            time,
                            "h",
                            3_600.0,
                            AggregationSemantics.INSTANTANEOUS
                        )
                    )
                ),
                requiredOutputs = listOf(
                    TypedField(
                        "energy_kwh",
                        TypedFieldType.NUMBER,
                        "Energy in kilowatt-hours.",
                        physicalDimension = energy,
                        quantitySpec = QuantitySpec(
                            energy,
                            "kWh",
                            3_600_000.0,
                            AggregationSemantics.SUM_OVER_INTERVALS,
                            TimeBasis.HOUR
                        )
                    )
                ),
                validationStrategy = "Use typed quantity authority.",
                expectedReuseNodeIds = setOf(NodeId("reuse")),
                preferredImplementationMode = ToolImplementationMode.PYTHON,
                behavior = ToolBehavior.DETERMINISTIC,
                promotionTestIds = setOf("private"),
                maximumRuntimeMillis = 1_000,
                maximumOutputBytes = 4_096
            )
        ).withComputedHash()
    }
}
