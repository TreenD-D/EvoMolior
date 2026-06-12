package com.achulkov.evokammer.cli

import com.achulkov.evokammer.cli.selfimprovement.SelfImprovementLoopRunner
import com.achulkov.evokammer.cli.selfimprovement.SelfImprovementCycleRunner
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.nio.file.Path

class SelfImprovementCliOverseerCycleWiringTest {

    @Test
    fun `runOverseerCycle threads source repo and cycle count into the runner`() {
        val sourceRepo = createTempDirectory("self-improve-cli-source-")
        val workspaceRoot = createTempDirectory("self-improve-cli-workspace-")

        val options = SelfImprovementCliArguments.parse(
            arrayOf(
                "-source", sourceRepo.toString(),
                "-workspace-root", workspaceRoot.toString(),
                "-workspace-name", "cycle-001",
                "-overseer-cycle",
                "-overseer-cycles", "2",
                "-env", "OPENROUTER_API_KEY=sk-test"
            )
        )
        assertNotNull(options)

        var capturedSourceRepo: Path? = null
        var capturedWorkspaceRoot: Path? = null
        var capturedApiKey: String? = null
        var capturedCycleLimit: Int? = null

        runOverseerCycle(
            options = options,
            runnerFactory = { sourceRepoRoot, outputRoot, apiKey ->
                capturedSourceRepo = sourceRepoRoot
                capturedWorkspaceRoot = outputRoot
                capturedApiKey = apiKey
                object : SelfImprovementCycleRunner {
                    override fun runCycle(cycleLimit: Int): List<SelfImprovementLoopRunner.CycleReport> {
                        capturedCycleLimit = cycleLimit
                        return listOf(
                            SelfImprovementLoopRunner.CycleReport(
                                cycleIndex = 0,
                                hypothesesCount = 1,
                                proposalsCount = 1,
                                formalizedCount = 1,
                                experimentResults = 0
                            )
                        )
                    }
                }
            }
        )

        assertEquals(sourceRepo.toAbsolutePath().normalize(), capturedSourceRepo)
        assertEquals(workspaceRoot.toAbsolutePath().resolve("cycle-001").normalize(), capturedWorkspaceRoot)
        assertEquals("sk-test", capturedApiKey)
        assertEquals(2, capturedCycleLimit)
        assertTrue(capturedWorkspaceRoot?.startsWith(workspaceRoot) == true)
    }
}
