@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.atlas.CompiledToolCandidateWire
import com.achulkov.evomolior.core.atlas.ToolCandidateWireCompilation
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.time.Clock
import kotlin.time.Instant

class ToolTransportAttemptStoreTest {
    @TempDir
    lateinit var temporaryDirectory: File

    @Test
    fun `persists exact safe transport accounting without private compiler detail`() = runTest {
        val factory = SubstrateDatabaseFactory(temporaryDirectory)
        val store = ToolTransportAttemptStore(factory, FixedClock(NOW))
        store.append(
            runId = RUN_ID,
            groupId = "group-1",
            agentId = "agent-1",
            round = 0,
            compilation = ToolCandidateWireCompilation.Rejected(
                code = "tool_wire_workspace_tampered",
                detail = "PRIVATE expected=7 actual=9 /secret/fixture.json",
            ),
        )
        store.append(
            runId = RUN_ID,
            groupId = "group-1",
            agentId = "agent-2",
            round = 1,
            compilation = compiled(CANDIDATE_HASH),
        )

        val ledger = store.read(RUN_ID)
        assertEquals(2, ledger.attempts.size)
        assertEquals(ToolTransportAttemptOutcome.REJECTED, ledger.attempts[0].outcome)
        assertEquals(ToolTransportAttemptOutcome.COMPILED, ledger.attempts[1].outcome)
        assertTrue(ledger.attempts.all(ToolTransportAttemptRecordV1::hasValidHash))
        assertEquals(ledger.attempts[0].recordHash, ledger.attempts[1].previousRecordHash)

        val measurements = store.measurements(RUN_ID)
        assertEquals(2, measurements.attempts)
        assertEquals(1, measurements.failures)
        assertEquals(3, measurements.evidenceRefs.size)

        val persisted = Files.readString(
            factory.runControlDir(RUN_ID).toPath()
                .resolve(ToolTransportAttemptStore.FILE_NAME)
        )
        assertFalse(persisted.contains("PRIVATE"))
        assertFalse(persisted.contains("/secret/fixture.json"))
        assertFalse(persisted.contains("expected=7"))
    }

    @Test
    fun `serializes concurrent attempts into one valid hash chain`() = runTest {
        val factory = SubstrateDatabaseFactory(temporaryDirectory)
        val store = ToolTransportAttemptStore(factory, FixedClock(NOW))

        (0 until 24).map { index ->
            async {
                store.append(
                    runId = RUN_ID,
                    groupId = "group-${index % 3}",
                    agentId = "agent-$index",
                    round = index % 6,
                    compilation = if (index % 4 == 0) {
                        ToolCandidateWireCompilation.Rejected(
                            code = "tool_wire_source_missing",
                            detail = "bounded public-safe response",
                        )
                    } else {
                        compiled(CANDIDATE_HASH)
                    },
                )
            }
        }.awaitAll()

        val ledger = store.read(RUN_ID)
        assertEquals(24, ledger.attempts.size)
        assertEquals((0 until 24).toList(), ledger.attempts.map { it.sequence })
        assertEquals(24, ledger.attempts.map { it.attemptId }.distinct().size)
        assertTrue(ledger.attempts.all(ToolTransportAttemptRecordV1::hasValidHash))
        assertEquals(6, store.measurements(RUN_ID).failures)
    }

    @Test
    fun `tampered ledger fails strict read`() = runTest {
        val factory = SubstrateDatabaseFactory(temporaryDirectory)
        val store = ToolTransportAttemptStore(factory, FixedClock(NOW))
        store.append(
            runId = RUN_ID,
            groupId = "group-1",
            agentId = "agent-1",
            round = 0,
            compilation = compiled(CANDIDATE_HASH),
        )
        val file = factory.runControlDir(RUN_ID).toPath()
            .resolve(ToolTransportAttemptStore.FILE_NAME)
        Files.writeString(
            file,
            Files.readString(file).replace(CANDIDATE_HASH, "f".repeat(64)),
        )

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { store.read(RUN_ID) }
        }
    }

    @Test
    fun `zero-attempt measurement persists an authoritative empty ledger`() = runTest {
        val factory = SubstrateDatabaseFactory(temporaryDirectory)
        val store = ToolTransportAttemptStore(factory, FixedClock(NOW))

        val measurements = store.measurements(RUN_ID)

        assertEquals(0, measurements.attempts)
        assertEquals(0, measurements.failures)
        assertEquals(1, measurements.evidenceRefs.size)
        assertTrue(
            factory.runControlDir(RUN_ID).toPath()
                .resolve(ToolTransportAttemptStore.FILE_NAME)
                .toFile()
                .isFile
        )
    }

    @Test
    fun `stable attempt identity is idempotent across store instances`() = runTest {
        val factory = SubstrateDatabaseFactory(temporaryDirectory)
        val firstStore = ToolTransportAttemptStore(factory, FixedClock(NOW))
        val secondStore = ToolTransportAttemptStore(factory, FixedClock(NOW))

        val first = firstStore.append(
            runId = RUN_ID,
            groupId = "group-1",
            agentId = "agent-1",
            round = 0,
            compilation = compiled(CANDIDATE_HASH),
            attemptId = "stable-attempt-1",
        )
        val replay = secondStore.append(
            runId = RUN_ID,
            groupId = "group-1",
            agentId = "agent-1",
            round = 0,
            compilation = compiled(CANDIDATE_HASH),
            attemptId = "stable-attempt-1",
        )

        assertEquals(first, replay)
        assertEquals(1, firstStore.read(RUN_ID).attempts.size)
    }

    private fun compiled(candidateHash: String): ToolCandidateWireCompilation.Compiled =
        ToolCandidateWireCompilation.Compiled(
            CompiledToolCandidateWire(
                content = "{}",
                wipTaskRef = "node-1",
                opportunityCommitmentHash = "a".repeat(64),
                sourceHash = "b".repeat(64),
                supplementalTestsHash = null,
                candidateHash = candidateHash,
            )
        )

    private class FixedClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    private companion object {
        const val RUN_ID = "tool-transport-run"
        val NOW: Instant = Instant.parse("2026-07-24T12:00:00Z")
        val CANDIDATE_HASH: String = "c".repeat(64)
    }
}
