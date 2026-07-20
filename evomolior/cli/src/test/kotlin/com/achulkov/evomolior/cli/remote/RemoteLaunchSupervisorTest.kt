package com.achulkov.evomolior.cli.remote

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteLaunchSupervisorTest {
    @Test
    fun childCommandCannotExecuteBeforeDurableAuthorization() {
        val shell = Path.of("/bin/sh")
        if (!Files.isExecutable(shell)) return
        val root = Files.createTempDirectory("dashboard-supervisor-blocked")
        val marker = root.resolve("executed.txt")
        val pending = RemoteProcessSupervisor().start(
            command = listOf(shell.toString(), "-c", "echo executed > executed.txt"),
            workingDirectory = root,
            logPath = root.resolve("run.log")
        )

        try {
            Thread.sleep(300)
            assertFalse(Files.exists(marker))
            terminateProcessTree(pending.process.toHandle())
            assertTrue(pending.process.waitFor(5, TimeUnit.SECONDS))
            assertFalse(Files.exists(marker))
        } finally {
            pending.process.toHandle().takeIf { it.isAlive }?.let(::terminateProcessTree)
        }
    }

    @Test
    fun durableAuthorizationReleasesTheCommandAndPreservesItsExitCode() {
        val shell = Path.of("/bin/sh")
        if (!Files.isExecutable(shell)) return
        val root = Files.createTempDirectory("dashboard-supervisor-authorized")
        val marker = root.resolve("executed.txt")
        val pending = RemoteProcessSupervisor().start(
            command = listOf(shell.toString(), "-c", "echo executed > executed.txt; exit 7"),
            workingDirectory = root,
            logPath = root.resolve("run.log")
        )

        try {
            pending.authorize()
            assertTrue(pending.process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(7, pending.process.exitValue())
            assertTrue(Files.exists(marker))
        } finally {
            pending.process.toHandle().takeIf { it.isAlive }?.let(::terminateProcessTree)
        }
    }
}
