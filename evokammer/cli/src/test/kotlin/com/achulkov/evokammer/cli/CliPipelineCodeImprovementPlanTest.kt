package com.achulkov.evokammer.cli

import com.achulkov.evokammer.api.events.EvoKammerEvent
import com.achulkov.evokammer.api.model.RunConfig
import com.achulkov.evokammer.api.service.EngineState
import com.achulkov.evokammer.api.service.EngineStatus
import com.achulkov.evokammer.api.service.EvoKammerEngineService
import com.achulkov.evokammer.api.service.MetaArchiveEntryRecord
import com.achulkov.evokammer.api.service.MetaArchiveSummary
import com.achulkov.evokammer.core.contract.OverseerDeadEnd
import com.achulkov.evokammer.core.contract.OverseerDeadEndHistoryEntry
import com.achulkov.evokammer.core.contract.OverseerExperimentPlan
import com.achulkov.evokammer.core.contract.OverseerExperimentPlanEligibility
import com.achulkov.evokammer.core.contract.OverseerExperimentPlanHistoryEntry
import com.achulkov.evokammer.core.contract.OverseerExperimentResult
import com.achulkov.evokammer.core.contract.OverseerHypothesis
import com.achulkov.evokammer.core.contract.OverseerKernelAuditEntry
import com.achulkov.evokammer.core.contract.OverseerKernelEligibility
import com.achulkov.evokammer.core.contract.OverseerKernelState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CliPipelineCodeImprovementPlanTest {

    @Test
    fun `pipeline applies a published code improvement plan into the next stage repo copy`() = runBlocking {
        val inputDir = createTempDirectory("cli-plan-input-").toFile().apply {
            resolve("Example.kt").writeText(
                """
                fun message(): String = "original"
                
                fun sentinel(): String = "keep"
                """.trimIndent()
            )
        }
        val outputDir = createTempDirectory("cli-plan-output-").toFile()
        val pipelineDir = createTempDirectory("cli-plan-pipeline-").toFile()
        val pipelineFile = pipelineDir.resolve("pipeline.json").apply {
            writeText(
                """
                {
                  "stages": [
                    {
                      "id": "verify",
                      "prompt": "Verify the modified repo copy",
                      "fast": true,
                      "rounds": 1
                    }
                  ]
                }
                """.trimIndent()
            )
        }
        val planJson = """
            {
              "title": "Rewrite the example function",
              "problem": "The input repo copy should be updated before the next stage starts.",
              "affectedFiles": ["Example.kt"],
              "proposedChanges": [
                {
                  "changeId": "change-1",
                  "description": "Update the returned message",
                  "implementationDetails": {
                    "location": "Example.kt",
                    "method": "message",
                    "changes": [
                      {
                        "type": "replace",
                        "detail": "Replace the function body",
                        "codeSnippet": "fun message(): String = \"updated\""
                      }
                    ]
                  }
                }
              ],
              "verification": {
                "steps": ["Re-open Example.kt in the next stage"],
                "expectedOutcome": "The Example.kt file should contain the updated function."
              },
              "risk": {
                "level": "low",
                "details": ["This test only rewrites a single Kotlin function."]
              }
            }
        """.trimIndent()
        val fakeEngine = PlanPublishingEngineService(
            bundleEntriesByRunId = mapOf(
                "cli-code-improve" to mapOf(
                    "outputs/improvements/code-improvement-plan.json" to planJson
                )
            )
        )

        val result = runCli(
            options = CliOptions(
                prompt = "Generate a code improvement plan",
                inputDir = inputDir.toPath(),
                outputDir = outputDir.toPath(),
                openRouterApiKey = "sk-test",
                modelOverrides = emptyMap(),
                debug = true,
                fast = true,
                rounds = 1,
                runId = "cli-code-improve",
                pipelineConfigPath = pipelineFile.toPath()
            ),
            runtimeFactory = { _, _ -> CliRuntime(fakeEngine) }
        )

        assertTrue(result.completedSuccessfully)

        val runRoot = outputDir.resolve("cli-code-improve")
        val publishedPlan = runRoot.resolve("outputs/improvements/code-improvement-plan.json")
        assertTrue(publishedPlan.exists())
        assertTrue(publishedPlan.readText().contains("\"changeId\": \"change-1\""))

        val stagedRepoCopy = runRoot.resolve(".pipeline-inputs/verify/Example.kt")
        assertTrue(stagedRepoCopy.exists())
        assertTrue(stagedRepoCopy.readText().contains("updated"))
        assertTrue(stagedRepoCopy.readText().contains("keep"))
        assertTrue(!stagedRepoCopy.readText().contains("original"))

        val verifyConfig = assertNotNull(fakeEngine.capturedConfigs.lastOrNull())
        val exampleArtifact = assertNotNull(verifyConfig.contextArtifacts.firstOrNull { it.relativePath == "Example.kt" })
        assertTrue(exampleArtifact.textPreview?.contains("updated") == true)
    }
}

private class PlanPublishingEngineService(
    private val bundleEntriesByRunId: Map<String, Map<String, String>>
) : EvoKammerEngineService {
    private val _events = MutableSharedFlow<EvoKammerEvent>(replay = 2, extraBufferCapacity = 8)
    override val events: SharedFlow<EvoKammerEvent> = _events.asSharedFlow()

    val capturedConfigs = mutableListOf<RunConfig>()
    private val completedRuns = linkedSetOf<String>()

    override suspend fun startRun(config: RunConfig): String {
        capturedConfigs += config
        _events.emit(EvoKammerEvent.RunStarted(config.runId, config.objective))
        completedRuns += config.runId
        _events.emit(EvoKammerEvent.RunCompleted(config.runId, "group-0"))
        return config.runId
    }

    override suspend fun pauseRun(runId: String) = Unit
    override suspend fun resumeRun(runId: String) = Unit
    override suspend fun abortRun(runId: String, reason: String) = Unit
    override suspend fun getState(): EngineState = EngineState(
        status = EngineStatus.IDLE,
        activeRunId = null,
        currentRound = null,
        groupSummaries = emptyList()
    )
    override suspend fun getMetaArchiveSummary(runId: String): MetaArchiveSummary? = null
    override suspend fun getMetaArchiveEntries(runId: String, entryType: String?): List<MetaArchiveEntryRecord> = emptyList()
    override suspend fun getResultsBundle(runId: String): ByteArray? {
        check(runId in completedRuns) { "results bundle requested before run completion for $runId" }
        return buildBundleBytes(bundleEntriesByRunId[runId].orEmpty(), runId)
    }
    override suspend fun getOverseerKernelState(): OverseerKernelState = OverseerKernelState()
    override suspend fun preflightOverseerHypothesis(hypothesis: OverseerHypothesis): OverseerKernelEligibility =
        OverseerKernelEligibility(allowed = true)
    override suspend fun preflightOverseerExperimentPlan(experimentPlan: OverseerExperimentPlan): OverseerExperimentPlanEligibility =
        OverseerExperimentPlanEligibility(allowed = true)
    override suspend fun formalizeOverseerHypothesis(hypothesis: OverseerHypothesis): OverseerKernelState = OverseerKernelState()
    override suspend fun registerOverseerExperimentPlan(experimentPlan: OverseerExperimentPlan): OverseerKernelState = OverseerKernelState()
    override suspend fun scheduleOverseerExperimentPlans(): OverseerKernelState = OverseerKernelState()
    override suspend fun recordOverseerExperimentResult(experimentResult: OverseerExperimentResult): OverseerKernelState = OverseerKernelState()
    override suspend fun recordOverseerDeadEnd(deadEnd: OverseerDeadEnd): OverseerKernelState = OverseerKernelState()
    override suspend fun getOverseerAuditEntries(): List<OverseerKernelAuditEntry> = emptyList()
    override suspend fun getOverseerExperimentPlanHistory(experimentPlanId: String): List<OverseerExperimentPlanHistoryEntry> = emptyList()
    override suspend fun getOverseerDeadEndHistory(hypothesisId: String): List<OverseerDeadEndHistoryEntry> = emptyList()
    override suspend fun getOverseerAuditArchiveEntries(): List<MetaArchiveEntryRecord> = emptyList()

    private fun buildBundleBytes(entries: Map<String, String>, runId: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"runId":"$runId","artifactCount":${entries.size}}""".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return output.toByteArray()
    }
}
