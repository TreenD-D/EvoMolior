package com.achulkov.evomolior.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcceptanceProviderWrapperTest {
    @Test
    fun `wrapper preserves launcher exit through tee and writes marker atomically`() {
        val root = createTempDirectory("acceptance-provider-wrapper-")
        val providerLog = root.resolve("provider.log")
        val exitMarker = root.resolve("provider.exit")
        val process = ProcessBuilder(
            "bash",
            wrapper().toString(),
            providerLog.toString(),
            exitMarker.toString(),
            "--",
            "bash",
            "-c",
            "echo provider-output; exit 7"
        ).redirectErrorStream(true).start()

        val output = process.inputStream.bufferedReader().readText()
        assertEquals(7, process.waitFor(), output)
        assertEquals("provider-output\n", providerLog.readText())
        assertEquals("7\n", exitMarker.readText())
        assertFalse(Files.exists(root.resolve("provider.exit.tmp.${process.pid()}")))
    }

    @Test
    fun `wrapper refuses authority overwrite and avoids reserved status variable`() {
        val root = createTempDirectory("acceptance-provider-wrapper-reuse-")
        val providerLog = root.resolve("provider.log")
        val exitMarker = root.resolve("provider.exit")
        Files.writeString(providerLog, "immutable\n")
        Files.writeString(exitMarker, "9\n")

        val process = ProcessBuilder(
            "bash",
            wrapper().toString(),
            providerLog.toString(),
            exitMarker.toString(),
            "--",
            "bash",
            "-c",
            "exit 0"
        ).redirectErrorStream(true).start()

        assertEquals(64, process.waitFor())
        assertEquals("immutable\n", providerLog.readText())
        assertEquals("9\n", exitMarker.readText())
        val script = wrapper().readText()
        assertTrue("pipeline_status=(\"\${PIPESTATUS[@]}\")" in script)
        assertFalse(Regex("""(?m)^\s*status=""").containsMatchIn(script))
    }

    @Test
    fun `wrapper fails closed when tee cannot preserve the provider log`() {
        val root = createTempDirectory("acceptance-provider-wrapper-tee-")
        val fakeBin = Files.createDirectories(root.resolve("bin"))
        val fakeTee = fakeBin.resolve("tee")
        Files.writeString(fakeTee, "#!/bin/sh\nexit 9\n")
        fakeTee.toFile().setExecutable(true)
        val providerLog = root.resolve("provider.log")
        val exitMarker = root.resolve("provider.exit")
        val process = ProcessBuilder(
            "bash",
            wrapper().toString(),
            providerLog.toString(),
            exitMarker.toString(),
            "--",
            "bash",
            "-c",
            "exit 0"
        ).redirectErrorStream(true).apply {
            environment()["PATH"] = "$fakeBin:${environment()["PATH"]}"
        }.start()

        assertEquals(74, process.waitFor())
        assertFalse(Files.exists(exitMarker))
    }

    @Test
    fun `wrapper rejects aliased provider log and exit marker`() {
        val root = createTempDirectory("acceptance-provider-wrapper-alias-")
        val sharedAuthority = root.resolve("provider-authority")
        val process = ProcessBuilder(
            "bash",
            wrapper().toString(),
            sharedAuthority.toString(),
            root.resolve(".").resolve("provider-authority").toString(),
            "--",
            "bash",
            "-c",
            "echo must-not-run"
        ).redirectErrorStream(true).start()

        assertEquals(64, process.waitFor())
        assertFalse(Files.exists(sharedAuthority))
    }

    private fun wrapper(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (current.parent != null) {
            val candidate = current.resolve("scripts/run_acceptance_provider.sh")
            if (Files.isRegularFile(candidate)) return candidate
            current = current.parent
        }
        error("Could not locate scripts/run_acceptance_provider.sh")
    }
}
