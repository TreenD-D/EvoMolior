package com.achulkov.evomolior.agents.tools

import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.ToolRegistryEntryEntity
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class ListToolsToolTest {

    @Test
    fun `empty registry tells agents how to propose a reusable tool`() = runTest {
        val runId = "run-list-tools-empty"
        val groupId = "group-0"
        val db = SubstrateDatabaseFactory(createTempDirectory("list-tools-empty-test-").toFile())
            .get(runId, groupId)

        val result = ListToolsTool(
            toolRegistryDao = db.toolRegistryDao(),
            runId = runId,
            groupId = groupId
        ).execute(ListToolsTool.Args(domain = "market-scanning"))

        assertContains(result, "No active tools found in domain: market-scanning")
        assertContains(result, "write_staging(type=\"TOOL\"")
    }

    @Test
    fun `lists active tools with schemas for execution discovery`() = runTest {
        val runId = "run-list-tools"
        val groupId = "group-0"
        val db = SubstrateDatabaseFactory(createTempDirectory("list-tools-test-").toFile())
            .get(runId, groupId)
        db.toolRegistryDao().insert(
            ToolRegistryEntryEntity(
                id = "tool-1",
                name = "health-summary",
                version = 1,
                authorAgentId = "agent-builder",
                inputSchemaJson = """{"type":"object","properties":{"archivePath":{"type":"string"}}}""",
                outputSchemaJson = """{"type":"object","properties":{"summary":{"type":"string"}}}""",
                domain = "audit",
                code = "cat >/dev/null\necho ok",
                codeLanguage = "shell",
                generation = 1,
                groupId = groupId,
                runId = runId,
                createdAtMs = 1_000L
            )
        )

        val result = ListToolsTool(
            toolRegistryDao = db.toolRegistryDao(),
            runId = runId,
            groupId = groupId
        ).execute(ListToolsTool.Args(domain = "audit"))

        assertContains(result, "health-summary")
        assertContains(result, "Version: 1")
        assertContains(result, "archivePath")
        assertContains(result, "summary")
    }

    @Test
    fun `exact version lookup returns complete same-group schemas`() = runTest {
        val runId = "run-list-tools-exact"
        val groupId = "group-exact"
        val db = SubstrateDatabaseFactory(createTempDirectory("list-tools-exact-test-").toFile())
            .get(runId, groupId)
        val longField = "x".repeat(900)
        db.toolRegistryDao().insert(
            ToolRegistryEntryEntity(
                id = "calculator-v2",
                name = "calculator",
                version = 2,
                authorAgentId = "agent-builder",
                inputSchemaJson = """{"type":"object","description":"$longField"}""",
                outputSchemaJson = """{"type":"object","required":["result"]}""",
                domain = "calculation",
                code = "echo",
                generation = 1,
                groupId = groupId,
                runId = runId,
                createdAtMs = 1_000L
            )
        )
        db.toolRegistryDao().insert(
            ToolRegistryEntryEntity(
                id = "calculator-v2-other-group",
                name = "calculator",
                version = 2,
                authorAgentId = "other-agent",
                inputSchemaJson = "{}",
                outputSchemaJson = "{}",
                domain = "calculation",
                code = "echo",
                generation = 1,
                groupId = "other-group",
                runId = runId,
                createdAtMs = 1_000L
            )
        )

        val result = ListToolsTool(
            toolRegistryDao = db.toolRegistryDao(),
            runId = runId,
            groupId = groupId
        ).execute(
            ListToolsTool.Args(
                toolName = "calculator",
                toolVersion = 2
            )
        )

        assertContains(result, longField)
        assertContains(result, "Version: 2")
        assertFalse(result.contains("other-agent"))
    }
}
