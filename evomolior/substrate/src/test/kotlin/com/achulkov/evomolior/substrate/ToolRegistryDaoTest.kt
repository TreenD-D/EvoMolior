package com.achulkov.evomolior.substrate

import com.achulkov.evomolior.substrate.db.entity.ToolRegistryEntryEntity
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies registry versioning and lookup semantics for promoted tools.
 */
class ToolRegistryDaoTest {

    @Test
    fun `latest version lookup sees archived rows while active lookup returns current tool`() = runTest {
        val dataRoot = createTempDirectory("tool-registry-dao-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val db = factory.get("run-1", "group-0")

        val toolV1 = ToolRegistryEntryEntity(
            id = "parser-v1",
            name = "parser",
            version = 1,
            authorAgentId = "agent-1",
            inputSchemaJson = "{}",
            outputSchemaJson = "{}",
            domain = "general",
            status = "active",
            code = "#!/bin/sh\necho v1",
            codeLanguage = "shell",
            generation = 1,
            groupId = "group-0",
            runId = "run-1",
            createdAtMs = 1_000L
        )
        val toolV2 = toolV1.copy(
            id = "parser-v2",
            version = 2,
            status = "archived",
            code = "#!/bin/sh\necho v2",
            createdAtMs = 2_000L,
            previousVersionId = toolV1.id
        )

        db.toolRegistryDao().insert(toolV1)
        db.toolRegistryDao().insert(toolV2)

        val active = db.toolRegistryDao().getLatestByName("run-1", "group-0", "parser")
        assertNotNull(active)
        assertEquals(1, active.version)
        assertEquals("active", active.status)

        val latestVersion = db.toolRegistryDao().getLatestVersionByName("run-1", "group-0", "parser")
        assertNotNull(latestVersion)
        assertEquals(2, latestVersion.version)
        assertEquals("archived", latestVersion.status)
        assertEquals(toolV1.id, latestVersion.previousVersionId)

        val missing = db.toolRegistryDao().getLatestVersionByName("run-1", "group-0", "missing")
        assertNull(missing)
        assertTrue(db.toolRegistryDao().getActive("run-1", "group-0", null).isNotEmpty())
    }
}
