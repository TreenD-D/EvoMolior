package com.achulkov.evomolior.orchestration.tooltransport

import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.ToolCandidateWireCompilation
import com.achulkov.evomolior.core.atlas.ToolCandidateWireDraft
import com.achulkov.evomolior.core.atlas.ToolOpportunityBinding
import com.achulkov.evomolior.core.atlas.ToolOpportunityBindingCapability
import com.achulkov.evomolior.core.atlas.ToolOpportunityBindingCatalog
import com.achulkov.evomolior.core.atlas.ToolProjectSkeletonCompiler
import com.achulkov.evomolior.core.atlas.ToolRuntimeKind
import com.achulkov.evomolior.core.repair.PublicToolFinding
import com.achulkov.evomolior.core.repair.ApproachVector
import com.achulkov.evomolior.core.repair.RepairStrategy
import com.achulkov.evomolior.core.repair.ToolRepairCode
import com.achulkov.evomolior.core.repair.ToolSemanticReview
import com.achulkov.evomolior.core.repair.ToolSemanticVerdict
import com.achulkov.evomolior.orchestration.repair.ToolSemanticCompatibilityValidator
import com.achulkov.evomolior.orchestration.repair.powerToEnergyCommitment
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PowerToEnergyRepairTransportTest {
    @Test
    fun `later candidate consumes delayed safe diagnostic and compiles corrected implementation`() {
        val commitment = powerToEnergyCommitment()
        val skeleton = ToolProjectSkeletonCompiler.compile(commitment)
        val capability = ToolOpportunityBindingCapability(
            runId = commitment.runId,
            groupId = "group-a",
            round = 2,
            workerViewHash = "a".repeat(64)
        )
        val binding = ToolOpportunityBinding(
            handle = "T1",
            nodeId = commitment.nodeId.value,
            opportunityId = commitment.opportunity.opportunityId,
            opportunityHash = commitment.commitmentHash,
            inputSchemaHash = AtlasHashing.hashText(skeleton.inputSchemaJson),
            outputSchemaHash = AtlasHashing.hashText(skeleton.outputSchemaJson),
            publicTestIds = emptyList(),
            runtimeKind = ToolRuntimeKind.PYTHON,
            projectSkeletonHash = skeleton.skeletonHash
        )
        val catalog = ToolOpportunityBindingCatalog(
            catalogId = "catalog-power-energy",
            runId = commitment.runId,
            groupId = capability.groupId,
            round = capability.round,
            workerViewHash = capability.workerViewHash,
            bindings = listOf(binding)
        ).withComputedHash()
        val workspace = ToolContractWorkspaceCompiler.compile(
            catalog = catalog,
            capability = capability,
            commitment = commitment,
            skeleton = skeleton,
            publicTests = emptyList()
        )
        val root = createTempDirectory("power-energy-repair-")
        ToolContractWorkspaceMaterializer.materialize(workspace, root)
        Files.writeString(
            root.resolve("src/main.py"),
            """
            import json
            import sys

            value = json.load(sys.stdin)
            energy_kwh = value["power_watts"] * value["duration_hours"] / 1000.0
            sys.stdout.write(json.dumps({"energy_kwh": energy_kwh}, separators=(",", ":")))
            """.trimIndent()
        )
        Files.writeString(
            root.resolve("tests/author-tests.json"),
            """{"cases":[{"input":{"power_watts":1500.0,"duration_hours":2.0},"expectedOutput":{"energy_kwh":3.0}}]}"""
        )
        val review = ToolSemanticReview(
            verdict = ToolSemanticVerdict.REJECT,
            publicFindings = listOf(
                PublicToolFinding(
                    code = ToolRepairCode.POWER_TO_ENERGY_UNIT_CONVERSION,
                    affectedPublicField = "energy_kwh",
                    quantitySpec = commitment.opportunity.requiredOutputs.single().quantitySpec
                )
            ),
            safeRepairCodes = setOf(ToolRepairCode.POWER_TO_ENERGY_UNIT_CONVERSION),
            privateRationaleCommitment = "b".repeat(64)
        )
        val diagnostic = ToolSemanticCompatibilityValidator().compileDiagnostics(
            review = review,
            commitment = commitment,
            candidateHash = AtlasHashing.hashText("first incorrect watts-hours candidate"),
            groupId = capability.groupId,
            createdRound = 1
        ).single()
        val draft = ToolCandidateWireDraft(
            opportunityHandle = "T1",
            sourceArtifactPath = "src/main.py",
            entryPoint = "run_tool",
            supplementalTestsPath = "tests/author-tests.json",
            consumedRepairDiagnosticIds = listOf(diagnostic.diagnosticId)
        )
        val approach = ApproachVector(
            vectorId = "approach-power-energy-group-a-2",
            runId = commitment.runId,
            groupId = capability.groupId,
            opportunityId = commitment.opportunity.opportunityId,
            round = capability.round,
            lineageAttempt = 1,
            strategy = RepairStrategy.DIMENSIONAL_ANALYSIS_FIRST
        ).withComputedHash()

        assertEquals(2, diagnostic.visibleFromRound)
        assertTrue(workspace.files.none { it.content.contains("sealed-power-energy") })
        assertFalse(Json.encodeToString(diagnostic).contains("sealed-power-energy"))
        assertFalse(Json.encodeToString(diagnostic).contains("private"))
        val staleCapability = capability.copy(workerViewHash = "c".repeat(64))
        val staleHandle = ToolCandidateWireCompiler(
            catalog,
            staleCapability,
            workspace,
            commitment,
            root,
            visibleRepairDiagnostics = listOf(diagnostic),
            repairApproachVector = approach
        ).compile(draft)
        assertEquals(
            "tool_wire_handle_stale_capability",
            assertIs<ToolCandidateWireCompilation.Rejected>(staleHandle).code
        )
        val early = ToolCandidateWireCompiler(
            catalog,
            capability,
            workspace,
            commitment,
            root,
            visibleRepairDiagnostics = emptyList()
        ).compile(draft)
        assertEquals(
            "tool_wire_repair_diagnostic_not_visible",
            assertIs<ToolCandidateWireCompilation.Rejected>(early).code
        )
        val foreign = ToolCandidateWireCompiler(
            catalog,
            capability,
            workspace,
            commitment,
            root,
            visibleRepairDiagnostics = listOf(
                diagnostic.copy(groupId = "group-b").withComputedHash()
            ),
            repairApproachVector = approach
        ).compile(draft)
        assertEquals(
            "tool_wire_repair_authority_invalid",
            assertIs<ToolCandidateWireCompilation.Rejected>(foreign).code
        )
        val future = ToolCandidateWireCompiler(
            catalog,
            capability,
            workspace,
            commitment,
            root,
            visibleRepairDiagnostics = listOf(
                diagnostic.copy(visibleFromRound = 3).withComputedHash()
            ),
            repairApproachVector = approach
        ).compile(draft)
        assertEquals(
            "tool_wire_repair_authority_invalid",
            assertIs<ToolCandidateWireCompilation.Rejected>(future).code
        )

        val compiled = assertIs<ToolCandidateWireCompilation.Compiled>(
            ToolCandidateWireCompiler(
                catalog,
                capability,
                workspace,
                commitment,
                root,
                visibleRepairDiagnostics = listOf(diagnostic),
                repairApproachVector = approach
            ).compile(draft)
        ).candidate

        assertEquals(listOf(diagnostic.diagnosticId), compiled.consumedRepairDiagnosticIds)
        assertEquals(approach, compiled.repairApproachVector)
        assertTrue(compiled.content.contains("/ 1000.0"))
        assertFalse(compiled.content.contains(diagnostic.diagnosticId))
    }
}
