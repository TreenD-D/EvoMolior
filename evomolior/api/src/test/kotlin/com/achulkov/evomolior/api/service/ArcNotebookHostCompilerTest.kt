package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.FinalOutputSpec
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.io.path.createTempDirectory

class ArcNotebookHostCompilerTest {
    @Test
    fun `host compiler owns deterministic ids metadata and clean execution state`() {
        val spec = arcSpec()
        val source = ArcAgi2NotebookRecoveryFallback.build(
            outputSpec = spec,
            sourceGroupId = "provider",
        ).content
            .replace("\"execution_count\":null", "\"execution_count\":9")
            .replace(
                "\"outputs\":[]",
                "\"outputs\":[{\"output_type\":\"stream\",\"name\":\"stdout\",\"text\":\"stale\"}]",
            )

        val first = ArcNotebookHostCompiler.compile(
            outputSpec = spec,
            sourceNotebook = source,
            inputHashes = mapOf("challenge" to "a".repeat(64)),
            sourceGroupId = "group-a",
        )
        val second = ArcNotebookHostCompiler.compile(
            outputSpec = spec,
            sourceNotebook = source,
            inputHashes = mapOf("challenge" to "a".repeat(64)),
            sourceGroupId = "group-a",
        )

        assertEquals(first.content, second.content)
        assertEquals(first.contentHash, second.contentHash)
        val root = Json.parseToJsonElement(first.content).jsonObject
        val cells = root.getValue("cells").jsonArray
        val ids = cells.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.startsWith("arc-agi-2-notebook-v2-cell-") })
        val code = cells.first {
            it.jsonObject.getValue("cell_type").jsonPrimitive.content == "code"
        }.jsonObject
        assertEquals(JsonNull, code["execution_count"])
        assertEquals(0, code.getValue("outputs").jsonArray.size)
        assertTrue(
            root.getValue("metadata").jsonObject
                .getValue("evomolior").jsonObject
                .getValue("host_compiled").jsonPrimitive.content == "true",
        )
    }

    @Test
    fun `scaffold is independently structurally and statically validated`() {
        val output = ArcNotebookHostCompiler.compileScaffold(
            outputSpec = arcSpec(),
            inputHashes = mapOf("challenge" to "b".repeat(64)),
            sourceGroupId = "scaffold",
        )

        val structure = ArcNotebookHostValidator.validateStructure(output, arcSpec())
        val static = ArcNotebookHostValidator.validateStatic(output, arcSpec())

        assertTrue(structure.passed, structure.violations.joinToString())
        assertTrue(static.passed, static.violations.joinToString())
        assertNotEquals(structure.evidenceHash, static.evidenceHash)
    }

    @Test
    fun `host validator executes the scaffold smoke and full visible stages`() = runTest {
        val spec = arcSpec()
        val output = ArcNotebookHostCompiler.compileScaffold(
            outputSpec = spec,
            sourceGroupId = "scaffold",
        )
        val workspace = createTempDirectory("arc-host-execution-").toFile()
        File(workspace, "arc-agi_evaluation_challenges.json").writeText(
            """{"task":{"train":[{"input":[[0]],"output":[[0]]}],"test":[{"input":[[1]]}]}}""",
        )
        File(workspace, "prior-kaggle-submission.json").writeText(
            """{"task":[{"attempt_1":[[1]],"attempt_2":[[1]]}]}""",
        )
        val smoke = ArcNotebookHostValidator.validateExecution(
            output = output,
            workspace = workspace,
            stage = ArcNotebookValidationStage.SMOKE,
        )
        val full = ArcNotebookHostValidator.validateExecution(
            output = output,
            workspace = workspace,
            stage = ArcNotebookValidationStage.FULL_VISIBLE,
        )

        assertTrue(smoke.passed, smoke.violations.joinToString())
        assertTrue(full.passed, full.violations.joinToString())
    }

    private fun arcSpec() = FinalOutputSpec(
        relativePath = "submission.ipynb",
        mediaType = "application/x-ipynb+json",
        validation = ArcAgi2NotebookQualificationAuthority.notebookValidationContract,
    )
}
