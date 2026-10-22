package com.achulkov.evomolior.cli

import com.achulkov.evomolior.api.service.RunOutcomePresentation
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.deliverable.RunOutcomeClass
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliPipelineStartupRejectionPublicationTest {
    @Test
    fun `startup rejection summary preserves the verified diagnostic terminal outcome`() {
        val outputRoot = createTempDirectory("startup-diagnostic-cli-output-")
        val runOutput = outputRoot.resolve("run")
        val stage = CliStagePlan(
            id = "startup-diagnostic-stage",
            prompt = "Reject this run before startup.",
            inputDir = createTempDirectory("startup-diagnostic-cli-input-"),
            outputDir = runOutput,
            runId = "startup-diagnostic-cli",
            fast = false,
            rounds = 1,
            groupCount = 2,
            maxAgentsPerGroup = null,
            roundDurationMinutes = null,
            budgetOverride = null,
            inheritsPreviousOutputs = false,
            modelOverrides = emptyMap(),
            finalOutputSpec = null,
            finalOutputExplicit = false,
            enforceProviderMonetaryCostCap = false,
        )
        val bundle = "verified-startup-diagnostic-zip".toByteArray()
        val outcome = RunOutcomePresentation(
            runId = stage.runId,
            outcomeClass = RunOutcomeClass.STARTUP_DIAGNOSTIC_PACKAGE,
            manifestHash = "a".repeat(64),
            artifactPaths = listOf("outcome-manifest.json"),
        )

        val persisted = persistCliStartupRejection(
            stage = stage,
            preferredOutputDir = runOutput,
            outputRoot = outputRoot,
            error = IllegalStateException("Admission rejected"),
            artifact = null,
            modelOverrides = emptyMap(),
            terminalBundle = bundle,
            terminalOutcome = outcome,
        )

        assertContentEquals(
            bundle,
            Files.readAllBytes(persisted.outputDirectory.resolve("startup-diagnostic.zip")),
        )
        assertEquals(
            persisted.outputDirectory.resolve("startup-diagnostic.zip")
                .toAbsolutePath()
                .toString(),
            persisted.summary.resultsBundlePath,
        )
        assertEquals(outcome, persisted.summary.outcome)
        assertEquals("REJECTED", persisted.summary.status)
        assertTrue(
            Files.readString(persisted.outputDirectory.resolve("summary.json"))
                .contains("STARTUP_DIAGNOSTIC_PACKAGE")
        )
    }

    @Test
    fun `startup rejection publishes an immutable byte-identical public copy before its summary`() {
        val input = createTempDirectory("startup-rejection-public-copy-input-")
        val output = createTempDirectory("startup-rejection-public-copy-output-")
        val pipeline = createTempDirectory("startup-rejection-public-copy-config-").resolve("pipeline.json")
        Files.writeString(
            pipeline,
            """
            {
              "primary": {
                "id": "appendix",
                "prompt": "Produce a validated final report.",
                "rounds": 1,
                "groupCount": 2,
                "councilExecutionPurpose": "APPENDIX_B_ACCEPTANCE",
                "acceptanceProfile": "runtime-usability-smoke"
              }
            }
            """.trimIndent()
        )
        val runtimeCreations = AtomicInteger()

        val result = runCli(
            options = CliOptions(
                prompt = "ignored in favor of configured primary",
                inputDir = input,
                outputDir = output,
                openRouterApiKey = "/tmp/codex",
                llmBackend = LlmBackend.CODEX_CHATGPT,
                modelOverrides = emptyMap(),
                debug = false,
                fast = false,
                rounds = 1,
                runId = "startup-rejection-public-copy",
                pipelineConfigPath = pipeline
            ),
            runtimeFactory = { _, _ ->
                runtimeCreations.incrementAndGet()
                error("provider runtime must not be created")
            }
        )

        val runRoot = output.resolve("startup-rejection-public-copy")
        val canonical = Files.list(runRoot.resolve("control/startup-rejections")).use { files ->
            files.findFirst().orElseThrow()
        }
        val publicCopy = runRoot.resolve("startup-rejection.json")
        assertFalse(result.completedSuccessfully)
        assertEquals(0, runtimeCreations.get())
        assertTrue(Files.isRegularFile(runRoot.resolve("prompt.txt")))
        assertTrue(Files.readString(runRoot.resolve("summary.json")).contains("\"status\": \"REJECTED\""))
        assertContentEquals(Files.readAllBytes(canonical), Files.readAllBytes(publicCopy))
        assertFalse(Files.exists(runRoot.resolve("results.zip")))
        assertFalse(Files.exists(runRoot.resolve("pipeline-results.zip")))
        assertFalse(Files.exists(runRoot.resolve("outputs")))
        runCatching { Files.getPosixFilePermissions(publicCopy) }.getOrNull()?.let { permissions ->
            assertEquals(setOf(PosixFilePermission.OWNER_READ), permissions)
        }
    }

    @Test
    fun `public startup rejection collision never replaces existing bytes`() {
        val directory = createTempDirectory("startup-rejection-public-copy-collision-")
        val canonical = directory.resolve("canonical.json")
        val publicCopy = directory.resolve("startup-rejection.json")
        val canonicalBytes = """{"canonical":true}""".toByteArray()
        val existingBytes = """{"existing":true}""".toByteArray()
        Files.write(canonical, canonicalBytes)
        Files.write(publicCopy, existingBytes)

        assertFailsWith<FileAlreadyExistsException> {
            publishImmutableStartupRejectionCopy(canonical, publicCopy)
        }

        assertContentEquals(existingBytes, Files.readAllBytes(publicCopy))
        assertTrue(
            Files.list(directory).use { entries ->
                entries.noneMatch { entry ->
                    entry.fileName.toString().startsWith(".startup-rejection.json.")
                }
            }
        )
    }

    @Test
    fun `startup rejection publication collision fails closed before completion artifacts`() {
        val outputRoot = createTempDirectory("startup-rejection-fail-closed-")
        val runOutput = outputRoot.resolve("run")
        val stage = CliStagePlan(
            id = "rejected-stage",
            prompt = "Reject this run before startup.",
            inputDir = createTempDirectory("startup-rejection-fail-closed-input-"),
            outputDir = runOutput,
            runId = "startup-rejection-fail-closed",
            fast = false,
            rounds = 1,
            groupCount = 2,
            maxAgentsPerGroup = null,
            roundDurationMinutes = null,
            budgetOverride = null,
            inheritsPreviousOutputs = false,
            modelOverrides = emptyMap(),
            finalOutputSpec = null,
            finalOutputExplicit = false,
            enforceProviderMonetaryCostCap = false
        )

        assertFailsWith<FileAlreadyExistsException> {
            persistCliStartupRejection(
                stage = stage,
                preferredOutputDir = runOutput,
                outputRoot = outputRoot,
                error = IllegalStateException("Admission rejected"),
                artifact = null,
                modelOverrides = emptyMap(),
                publicCopyPublisher = { canonical, publicCopy ->
                    Files.writeString(publicCopy, """{"preexisting":true}""")
                    publishImmutableStartupRejectionCopy(canonical, publicCopy)
                }
            )
        }

        assertTrue(Files.isRegularFile(runOutput.resolve("prompt.txt")))
        assertTrue(Files.isRegularFile(runOutput.resolve("control/startup-rejections").let { directory ->
            Files.list(directory).use { it.findFirst().orElseThrow() }
        }))
        assertFalse(Files.exists(runOutput.resolve("summary.json")))
        assertFalse(Files.exists(runOutput.resolve("pipeline-summary.json")))
        assertFalse(Files.exists(runOutput.resolve("results.zip")))
        assertFalse(Files.exists(runOutput.resolve("pipeline-results.zip")))
        assertFalse(Files.exists(runOutput.resolve("outputs")))
    }
}
