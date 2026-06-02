package com.achulkov.evokammer.substrate.tools

import com.achulkov.evokammer.substrate.db.entity.ToolRegistryEntryEntity
import kotlinx.coroutines.test.runTest
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
}
