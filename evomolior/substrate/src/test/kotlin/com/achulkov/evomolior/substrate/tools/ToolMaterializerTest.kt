package com.achulkov.evomolior.substrate.tools

import com.achulkov.evomolior.core.atlas.ToolDslBinaryOperation
import com.achulkov.evomolior.core.atlas.ToolDslExpression
import com.achulkov.evomolior.core.atlas.ToolDslKind
import com.achulkov.evomolior.core.atlas.ToolDslOutput
import com.achulkov.evomolior.core.atlas.ToolDslProgram
import com.achulkov.evomolior.substrate.db.entity.ToolRegistryEntryEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that promoted tools are turned into executable files on the host side.
 */
class ToolMaterializerTest {

    @Test
    fun `materializes shell tool into executable file`() = runTest {
        val toolsRoot = createTempDirectory("tool-materializer-test").toFile()
        val tool = ToolRegistryEntryEntity(
            id = "echo-v1",
            name = "echo",
            version = 1,
            authorAgentId = "agent-1",
            inputSchemaJson = "{}",
            outputSchemaJson = "{}",
            domain = "general",
            code = "echo hello",
            codeLanguage = "shell",
            generation = 1,
            groupId = "group-0",
            runId = "run-1",
            createdAtMs = 1_000L
        )

        val file = ToolMaterializer(toolsRoot).materialize(tool)

        assertTrue(file.exists(), "Materialized tool file must exist")
        assertTrue(file.canExecute(), "Materialized tool file must be executable")
        assertEquals("${tool.name}-v${tool.version}", file.name)
        assertContains(file.readText(), "#!/bin/sh")
        assertContains(file.readText(), "echo hello")
    }

    @Test
    fun `materializes closed DSL AST into executable deterministic wrapper`() = runTest {
        val toolsRoot = createTempDirectory("tool-dsl-materializer-test").toFile()
        val program = ToolDslProgram(
            kind = ToolDslKind.FORMULA,
            outputs = listOf(
                ToolDslOutput(
                    field = "doubled",
                    expression = ToolDslExpression.Binary(
                        operation = ToolDslBinaryOperation.MULTIPLY,
                        left = ToolDslExpression.Input("value"),
                        right = ToolDslExpression.Literal(JsonPrimitive(2))
                    )
                )
            )
        )
        val tool = ToolRegistryEntryEntity(
            id = "double-v1",
            name = "double",
            version = 1,
            authorAgentId = "agent-1",
            inputSchemaJson = "{}",
            outputSchemaJson = "{}",
            domain = "formula",
            code = Json.encodeToString(program),
            codeLanguage = "dsl",
            generation = 1,
            groupId = "group-0",
            runId = "run-1",
            createdAtMs = 1_000L
        )

        val file = ToolMaterializer(toolsRoot).materialize(tool)
        val process = ProcessBuilder(file.absolutePath).start()
        process.outputStream.bufferedWriter().use {
            it.write("""{"value":3}""")
        }
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()

        assertEquals(0, process.waitFor(), error)
        assertEquals("""{"doubled":6}""", output)
    }
}
