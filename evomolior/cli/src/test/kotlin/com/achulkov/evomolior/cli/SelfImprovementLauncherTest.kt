package com.achulkov.evomolior.cli

import com.achulkov.evomolior.cli.selfimprovement.ChildProcessResult
import com.achulkov.evomolior.cli.selfimprovement.ChildProcessRunner
import com.achulkov.evomolior.cli.selfimprovement.SelfImprovementLaunchManifest
import com.achulkov.evomolior.cli.selfimprovement.SelfImprovementLaunchSpec
import com.achulkov.evomolior.cli.selfimprovement.SelfImprovementLaunchStatus
import com.achulkov.evomolior.cli.selfimprovement.SelfImprovementLauncher
import com.achulkov.evomolior.cli.selfimprovement.SelfImprovementWorkspaceMode
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class SelfImprovementLauncherTest {

    @Test
    fun `launcher copies the repo into an isolated workspace and records a manifest`() {
        val sourceRepo = createTempDirectory("self-improve-source-").toFile().toPath()
        val workspaceRoot = sourceRepo.parent.resolve("self-improve-workspace-root-${System.nanoTime()}")
        sourceRepo.resolve("notes.txt").writeText("keep me")
        Files.createDirectories(sourceRepo.resolve("build"))
        sourceRepo.resolve("build/ignored.txt").writeText("ignore me")
        Files.createDirectories(sourceRepo.resolve("documentation/tmp"))
        sourceRepo.resolve("documentation/tmp/ignored.txt").writeText("ignore me too")
        assertFalse(Files.exists(workspaceRoot))

        val runner = RecordingProcessRunner()
        val launcher = SelfImprovementLauncher(
            processRunner = runner,
            clock = { Instant.parse("2026-06-06T00:00:00Z") }
        )

        val report = launcher.launch(
            SelfImprovementLaunchSpec(
                sourceRepoDir = sourceRepo.toString(),
                workspaceRootDir = workspaceRoot.toString(),
                workspaceName = "self-improve-001",
                mode = SelfImprovementWorkspaceMode.COPY,
                command = listOf("./gradlew", "test", "--no-daemon"),
                environment = mapOf("OPENROUTER_API_KEY" to "sk-test"),
                keepWorkspace = true
            )
        )

        assertEquals(0, report.exitCode)
        assertTrue(runner.invoked)
        assertEquals(listOf("./gradlew", "test", "--no-daemon"), runner.command)
        assertEquals("sk-test", runner.environment["OPENROUTER_API_KEY"])
        assertTrue(Files.isDirectory(workspaceRoot))
        assertTrue(Files.exists(Path.of(report.workspaceDir).resolve("notes.txt")))
        assertFalse(Files.exists(Path.of(report.workspaceDir).resolve("build/ignored.txt")))
        assertFalse(Files.exists(Path.of(report.workspaceDir).resolve("documentation/tmp/ignored.txt")))

        val manifest = Json.decodeFromString(
            SelfImprovementLaunchManifest.serializer(),
            Path.of(report.manifestPath).readText()
        )
        assertEquals(SelfImprovementLaunchStatus.COMPLETED, manifest.status)
        assertEquals(SelfImprovementWorkspaceMode.COPY, manifest.mode)
        assertEquals(report.workspaceDir, manifest.workspaceDir)
        assertEquals(report.logFilePath, manifest.logFilePath)
    }

    private class RecordingProcessRunner : ChildProcessRunner {
        var invoked: Boolean = false
        var command: List<String> = emptyList()
        var environment: Map<String, String> = emptyMap()

        override fun run(
            command: List<String>,
            workingDirectory: Path,
            environment: Map<String, String>,
            logFile: Path
        ): ChildProcessResult {
            invoked = true
            this.command = command
            this.environment = environment
            Files.createDirectories(logFile.parent)
            Files.writeString(logFile, "command executed in ${workingDirectory.fileName}")
            assertTrue(Files.exists(workingDirectory.resolve("notes.txt")))
            assertFalse(Files.exists(workingDirectory.resolve("build/ignored.txt")))
            return ChildProcessResult(exitCode = 0)
        }
    }
}
