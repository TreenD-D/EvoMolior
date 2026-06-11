package com.achulkov.evokammer.cli

import com.achulkov.evokammer.cli.selfimprovement.SelfImprovementWorkspaceMode
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SelfImprovementCliArgumentsTest {

    @Test
    fun `parses isolated workspace launcher arguments`() {
        val sourceRepo = createTempDirectory("self-improve-source-").toFile()
        val workspaceRoot = sourceRepo.toPath().resolveSibling("self-improve-workspace-${System.nanoTime()}")

        val options = SelfImprovementCliArguments.parse(
            arrayOf(
                "-source",
                sourceRepo.absolutePath,
                "-workspace-root",
                workspaceRoot.toString(),
                "-workspace-name",
                "experiment-001",
                "-mode",
                "worktree",
                "-command",
                "./gradlew",
                "-arg",
                "test",
                "-arg",
                "--no-daemon",
                "-env",
                "OPENROUTER_API_KEY=sk-test",
                "-cleanup"
            )
        )

        assertNotNull(options)
        assertEquals(sourceRepo.toPath().toAbsolutePath().normalize(), options.sourceRepoDir)
        assertEquals(workspaceRoot.toAbsolutePath().normalize(), options.workspaceRootDir)
        assertEquals("experiment-001", options.workspaceName)
        assertEquals(SelfImprovementWorkspaceMode.WORKTREE, options.mode)
        assertEquals(listOf("./gradlew", "test", "--no-daemon"), options.command)
        assertEquals("sk-test", options.environment["OPENROUTER_API_KEY"])
        assertTrue(!options.keepWorkspace)
    }
}
