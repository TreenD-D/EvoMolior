package com.achulkov.evomolior.cli

import com.achulkov.evomolior.cli.selfimprovement.SelfImprovementLoopRunner
import com.achulkov.evomolior.cli.selfimprovement.SelfImprovementCycleRunner
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

    @Test
    fun `runOverseerCycle honors explicit execution flags and returns all requested cycle reports`() {
        val sourceRepo = createTempDirectory("self-improve-cli-source-")
        val workspaceRoot = createTempDirectory("self-improve-cli-workspace-")

        val options = SelfImprovementCliArguments.parse(
            arrayOf(
                "-source", sourceRepo.toString(),
                "-workspace-root", workspaceRoot.toString(),
                "-workspace-name", "cycle-002",
                "-overseer-cycle",
                "-overseer-execute",
                "-overseer-run-all-cycles",
                "-overseer-cycles", "3",
                "-env", "OPENROUTER_API_KEY=sk-test"
            )
        )
        assertNotNull(options)
        assertTrue(options.overseerExecuteExperiments)
        assertTrue(options.overseerRunAllCycles)

        var capturedCycleLimit: Int? = null
        var capturedReportsCount: Int? = null

        runOverseerCycle(
            options = options,
            runnerFactory = { _, _, _ ->
                object : SelfImprovementCycleRunner {
                    override fun runCycle(cycleLimit: Int): List<SelfImprovementLoopRunner.CycleReport> {
                        capturedCycleLimit = cycleLimit
                        val reports = listOf(
                            SelfImprovementLoopRunner.CycleReport(
                                cycleIndex = 0,
                                hypothesesCount = 1,
                                proposalsCount = 1,
                                formalizedCount = 1,
                                experimentResults = 1
                            ),
                            SelfImprovementLoopRunner.CycleReport(
                                cycleIndex = 1,
                                hypothesesCount = 2,
                                proposalsCount = 1,
                                formalizedCount = 1,
                                experimentResults = 1
                            ),
                            SelfImprovementLoopRunner.CycleReport(
                                cycleIndex = 2,
                                hypothesesCount = 2,
                                proposalsCount = 0,
                                formalizedCount = 0,
                                experimentResults = 0
                            )
                        )
                        capturedReportsCount = reports.size
                        return reports
                    }
                }
            }
        )

        assertEquals(3, capturedCycleLimit)
        assertEquals(3, capturedReportsCount)
    }
}
