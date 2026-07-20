package com.achulkov.evomolior.api.acceptance

import com.achulkov.evomolior.agents.factory.CouncilRunner
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import com.achulkov.evomolior.core.contract.StartingField
import com.achulkov.evomolior.core.council.StartingFieldCompileResult
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.api.service.CouncilExecutionEnvelopeFactory
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.orchestration.council.GenesisCouncil
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opt-in live Phase 8 acceptance using the same Council pipeline and backend configuration as
 * production. It is skipped in ordinary suites because it performs provider-backed inference.
 */
class Phase8GenesisCouncilLiveAcceptanceTest {
    @Test
    fun `three objectives produce valid distinct starting fields without intervention`() = runBlocking {
        assumeTrue(
            System.getenv("EVOMOLIOR_LIVE_PHASE8_ACCEPTANCE").equals("true", ignoreCase = true),
            "Set EVOMOLIOR_LIVE_PHASE8_ACCEPTANCE=true to run the provider-backed Phase 8 acceptance."
        )
        val objectives = listOf(
            "Design a reproducible protocol for auditing municipal heat-risk interventions.",
            "Create an implementation plan for a privacy-preserving classroom feedback platform.",
            "Develop a field guide for restoring pollinator habitat across urban transport corridors."
        )
        val resolvedCodexCliPath = LlmBackend.resolveCodexCliPath()
        assumeTrue(resolvedCodexCliPath != null, "Codex CLI must be installed for the live Phase 8 acceptance.")
        val codexCliPath = requireNotNull(resolvedCodexCliPath)
        val fields = objectives.mapIndexed { index, objective ->
            withTimeout(10 * 60_000L) {
                val runId = "phase-8-live-${index + 1}"
                val envelope = CouncilExecutionEnvelopeFactory.create(
                    RunConfig(
                        runId = runId,
                        objective = objective,
                        groupCount = 2,
                        groupProfiles = listOf(
                            GroupProfileType.Library,
                            GroupProfileType.Factory
                        ),
                        rounds = 4,
                        maxAgentsPerGroup = 4,
                        scoringDimensions = listOf(
                            ScoringDimension(
                                id = "quality",
                                name = "Quality",
                                description = "Receiver-facing quality",
                                weight = 1.0
                            )
                        ),
                        openRouterApiKey = codexCliPath,
                        llmBackend = LlmBackend.CODEX_CHATGPT
                    )
                )
                val delegate = CouncilRunner(
                    openRouterApiKey = codexCliPath,
                    executionEnvelope = envelope,
                    councilModelId = ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL,
                    llmBackend = LlmBackend.CODEX_CHATGPT
                ).agentRunner()
                val compiled = GenesisCouncil(objective, envelope).runCompiled(
                    runId = runId,
                    agentRunner = { request ->
                        delegate.run(request).also { output ->
                            writeRawCouncilOutput(index, request.role, output)
                        }
                    }
                )
                (compiled as? StartingFieldCompileResult.Valid)?.workerField
                    ?: error("Council compilation failed: $compiled")
            }.also { field ->
                assertValidProviderField(field, objective)
            }
        }

        val structuralFingerprints = fields.map(::structuralFingerprint)
        assertEquals(
            fields.size,
            structuralFingerprints.toSet().size,
            "Council outputs must differ structurally beyond run ID and objective text."
        )
        writeEvidence(fields, structuralFingerprints)
    }

    private fun assertValidProviderField(field: StartingField, objective: String) {
        assertEquals(objective, field.objective)
        assertTrue(field.globalBudgetCeiling > 0.0)
        assertTrue(field.generationCeiling > 0)
        assertTrue(field.groupCount > 0)
        assertEquals(field.groupCount, field.groupProfiles.size)
        assertEquals(field.groupProfiles.keys, field.ringTopology.toSet())
        assertTrue(field.rolePrompts.isNotEmpty(), "Provider field must contain Council-authored role prompts.")
        assertTrue(field.modelAssignment.isNotEmpty(), "Provider field must contain model assignments.")
        assertTrue(field.kgSchema.isNotBlank() && field.kgSchema != "{}")
        assertTrue(field.nodeGraph.isNotBlank() && field.nodeGraph != "{}")
        assertTrue(field.consistencyReport.isNotBlank(), "Fallback/default StartingFields are not accepted.")
        assertTrue(
            !field.consistencyReport.contains("unresolved", ignoreCase = true) &&
                !field.consistencyReport.contains("conflict remains", ignoreCase = true),
            "Consistency report contains an unresolved conflict: ${field.consistencyReport}"
        )
    }

    private fun structuralFingerprint(field: StartingField): String =
        Json.encodeToString(
            StartingField.serializer(),
            field.copy(runId = "", objective = "")
        )

    private fun writeEvidence(fields: List<StartingField>, fingerprints: List<String>) {
        val report = Phase8Evidence(
            backend = LlmBackend.CODEX_CHATGPT.name,
            model = ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL,
            generatedAtMs = System.currentTimeMillis(),
            outputs = fields.zip(fingerprints).map { (field, fingerprint) ->
                Phase8FieldEvidence(
                    runId = field.runId,
                    objective = field.objective,
                    groupCount = field.groupCount,
                    rolePromptCount = field.rolePrompts.size,
                    modelAssignmentCount = field.modelAssignment.size,
                    resourceNodeCount = field.resourceNodes.size,
                    stageCount = field.stageContents.size,
                    consistencyReport = field.consistencyReport,
                    structuralFingerprintHash = fingerprint.hashCode().toString()
                )
            }
        )
        File("build/reports/phase8-genesis-council-acceptance.json").apply {
            parentFile.mkdirs()
            writeText(Json { prettyPrint = true }.encodeToString(Phase8Evidence.serializer(), report))
        }
    }

    private fun writeRawCouncilOutput(objectiveIndex: Int, role: Role, output: String) {
        File("build/reports/phase8-live-raw/objective-${objectiveIndex + 1}-${role.name}.txt").apply {
            parentFile.mkdirs()
            writeText(output)
        }
    }
}

@Serializable
private data class Phase8Evidence(
    val backend: String,
    val model: String,
    val generatedAtMs: Long,
    val outputs: List<Phase8FieldEvidence>
)

@Serializable
private data class Phase8FieldEvidence(
    val runId: String,
    val objective: String,
    val groupCount: Int,
    val rolePromptCount: Int,
    val modelAssignmentCount: Int,
    val resourceNodeCount: Int,
    val stageCount: Int,
    val consistencyReport: String,
    val structuralFingerprintHash: String
)
