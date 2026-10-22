package com.achulkov.evomolior.api.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse

class ScoringWorkerOwnerTest {
    @Test
    fun `scoring server settles when owner dies without an authenticated shutdown request`() = runBlocking {
        val root = createTempDirectory("scoring-owner-")
        val ready = root.resolve("ready.port")
        val owner = ProcessBuilder("/bin/sh", "-c", "exec sleep 30").start()
        val server = async(Dispatchers.IO) {
            ScoringWorkerServer(0, "fixture-token", listOf("group-0"), ready, owner.toHandle()).run()
        }
        try {
            withTimeout(10_000) {
                while (!Files.isRegularFile(ready)) delay(25)
            }
            owner.destroy()
            withTimeout(5_000) { server.await() }
            assertFalse(owner.isAlive)
        } finally {
            owner.destroyForcibly()
            owner.waitFor(5, TimeUnit.SECONDS)
            withTimeout(5_000) { server.await() }
            root.toFile().deleteRecursively()
        }
    }
}
