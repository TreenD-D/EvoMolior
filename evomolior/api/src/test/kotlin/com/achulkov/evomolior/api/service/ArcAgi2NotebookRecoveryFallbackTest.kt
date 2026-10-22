package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.sandbox.toolsandbox.ToolSubSandbox
import com.achulkov.evomolior.api.model.FinalOutputSpec
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArcAgi2NotebookRecoveryFallbackTest {
    @Test
    fun `recovery accepts evolved ARC contract while normal writer authority stays exact`() {
        val canonicalSource =
            requireNotNull(
                ArcAgi2NotebookQualificationAuthority.notebookValidationContract
                    .jupyterNotebookSource
            )
        val evolvedContract =
            ArcAgi2NotebookQualificationAuthority.notebookValidationContract.copy(
                requiredMarkdownSectionSignals =
                    ArcAgi2NotebookQualificationAuthority.notebookValidationContract
                        .requiredMarkdownSectionSignals +
                        listOf("Kaggle receiver contract", "prior Kaggle baseline"),
                jupyterNotebookSource = canonicalSource.copy(
                    requiredCodeSignals = canonicalSource.requiredCodeSignals +
                        listOf(
                            "arc-agi_evaluation_challenges.json",
                            "prior-kaggle-submission.json",
                            "leave_one_out",
                            "baseline fallback",
                            "one result row per test input",
                        ),
                ),
            )
        val outputSpec = FinalOutputSpec(
            relativePath = "submission.ipynb",
            mediaType = "application/x-ipynb+json",
            validation = evolvedContract,
        )

        assertFalse(ArcAgi2NotebookRecoveryFallback.supports(outputSpec))
        assertTrue(ArcAgi2NotebookRecoveryFallback.supportsRecoveryTarget(outputSpec))
        assertTrue(
            FinalOutputContractValidator.validate(
                ArcAgi2NotebookRecoveryFallback.build(outputSpec, "group-arc"),
                outputSpec,
            ).isEmpty(),
        )
    }

    @Test
    fun `deterministic fallback satisfies the ARC notebook publication contract`() {
        val outputSpec = FinalOutputSpec(
            relativePath = "submission.ipynb",
            mediaType = "application/x-ipynb+json",
            validation = ArcAgi2NotebookQualificationAuthority.notebookValidationContract,
        )

        val output = ArcAgi2NotebookRecoveryFallback.build(outputSpec, "group-arc")
        val errors = FinalOutputContractValidator.validate(output, outputSpec)

        assertTrue(errors.isEmpty(), errors.joinToString())
        assertEquals("group-arc", output.sourceGroupId)
        val notebook = Json.parseToJsonElement(output.content).jsonObject
        assertEquals(4, notebook["nbformat"]?.toString()?.toInt())
        assertEquals(5, notebook["nbformat_minor"]?.toString()?.toInt())
        assertTrue(notebook["cells"]?.toString()?.contains("code-05") == true)
        assertTrue(notebook["cells"]?.toString()?.contains("def self_audit_notebook(_provider_path=None):") == true)
        assertTrue(notebook["cells"]?.toString()?.contains("family_1") == true)
    }

    @Test
    fun `deterministic fallback executes its clean notebook preflight`() = runTest {
        val outputSpec = FinalOutputSpec(
            relativePath = "submission.ipynb",
            mediaType = "application/x-ipynb+json",
            validation = ArcAgi2NotebookQualificationAuthority.notebookValidationContract,
        )
        val notebook = Json.parseToJsonElement(
            ArcAgi2NotebookRecoveryFallback.build(outputSpec, "group-arc").content
        ).jsonObject
        val python = notebook.getValue("cells").jsonArray
            .filter {
                (it.jsonObject["cell_type"] as? JsonPrimitive)?.content == "code"
            }
            .joinToString("\n") { cell ->
                when (val source = cell.jsonObject["source"]) {
                    is JsonPrimitive -> source.content
                    is JsonArray -> source.joinToString("") { (it as JsonPrimitive).content }
                    else -> error("Fallback notebook source is not textual")
                }
            }

        val root = createTempDirectory("arc-agi2-recovery-fallback-").toFile()
        val script = File(root, "submission-notebook.py")
        Files.writeString(script.toPath(), python)
        val runner = File(root, "submission-notebook")
        Files.writeString(
            runner.toPath(),
            "#!/bin/sh\nexec python3 ${shellQuote(script.absolutePath)}\n"
        )
        assertTrue(runner.setExecutable(true))
        val sandboxRoot = File(root, "sandbox")
        val workdir = File(sandboxRoot, runner.nameWithoutExtension).apply { mkdirs() }
        File(workdir, "arc-agi_evaluation_challenges.json").writeText(
            """{"task":{"train":[{"input":[[0]],"output":[[0]]}],"test":[{"input":[[1]]}]}}"""
        )
        File(workdir, "prior-kaggle-submission.json").writeText(
            """{"task":[{"attempt_1":[[1]],"attempt_2":[[1]]}]}"""
        )

        val result = ToolSubSandbox(
            workspaceDir = sandboxRoot,
            timeoutMs = 8_000L,
        ).execute(runner.absolutePath, "{}")

        assertTrue(!result.timedOut, result.stderr)
        assertEquals(0, result.exitCode, result.stderr)
        val submission = Json.parseToJsonElement(
            File(workdir, "submission.json").readText()
        ).jsonObject
        assertEquals(setOf("task"), submission.keys)
        assertTrue(File(workdir, "submission.json").isFile)
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
