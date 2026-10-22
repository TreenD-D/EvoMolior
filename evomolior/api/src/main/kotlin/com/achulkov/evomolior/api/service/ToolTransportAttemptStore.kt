@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.ToolCandidateWireCompilation
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Instant

/** Public-safe result class for one runtime-owned tool wire transport attempt. */
@Serializable
enum class ToolTransportAttemptOutcome {
    COMPILED,
    REJECTED,
}

/**
 * Immutable public-safe evidence for one runtime-owned tool wire compilation attempt.
 *
 * Raw drafts, generated source, compiler details, private fixtures, and model reasoning are never
 * persisted here.
 */
@Serializable
data class ToolTransportAttemptRecordV1(
    val schemaVersion: String = SCHEMA_VERSION,
    val attemptId: String,
    val runId: String,
    val groupId: String,
    val agentId: String,
    val round: Int,
    val outcome: ToolTransportAttemptOutcome,
    val rejectionCode: String? = null,
    val candidateHash: String? = null,
    val sequence: Int,
    val previousRecordHash: String? = null,
    val occurredAt: Instant,
    val recordHash: String = "",
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) {
            "Tool transport attempt schema must be $SCHEMA_VERSION"
        }
        require(
            listOf(attemptId, runId, groupId, agentId).none(String::isBlank) &&
                round >= 0 &&
                sequence >= 0
        ) { "Tool transport attempt identity and position are invalid" }
        require(
            (sequence == 0 && previousRecordHash == null) ||
                (sequence > 0 && previousRecordHash?.matches(SHA_256_REGEX) == true)
        ) { "Tool transport attempt chain position is invalid" }
        when (outcome) {
            ToolTransportAttemptOutcome.COMPILED -> require(
                rejectionCode == null && candidateHash?.matches(SHA_256_REGEX) == true
            ) { "Compiled tool transport evidence requires only a candidate hash" }

            ToolTransportAttemptOutcome.REJECTED -> require(
                rejectionCode?.matches(REJECTION_CODE_REGEX) == true && candidateHash == null
            ) { "Rejected tool transport evidence requires only a safe rejection code" }
        }
        require(recordHash.isEmpty() || recordHash.matches(SHA_256_REGEX)) {
            "Tool transport attempt hash is invalid"
        }
    }

    /** Returns this record with its exact deterministic self hash. */
    fun withComputedHash(): ToolTransportAttemptRecordV1 =
        copy(recordHash = hashRecord(this))

    /** Returns whether this record carries its exact deterministic self hash. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(recordHash, hashRecord(this))

    companion object {
        const val SCHEMA_VERSION: String = "tool-transport-attempt-v1"
        private val SHA_256_REGEX = Regex("^[0-9a-f]{64}$")
        private val REJECTION_CODE_REGEX = Regex("^[a-z0-9_]{1,96}$")
    }
}

/** Ordered run-level ledger containing only public-safe tool transport evidence. */
@Serializable
data class ToolTransportAttemptLedgerV1(
    val schemaVersion: String = SCHEMA_VERSION,
    val runId: String,
    val attempts: List<ToolTransportAttemptRecordV1>,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION && runId.isNotBlank()) {
            "Tool transport ledger identity is invalid"
        }
        require(attempts.map(ToolTransportAttemptRecordV1::attemptId).distinct().size ==
            attempts.size
        ) { "Tool transport attempt identities must be unique" }
        attempts.forEachIndexed { index, attempt ->
            require(
                attempt.runId == runId &&
                    attempt.sequence == index &&
                    attempt.previousRecordHash == attempts.getOrNull(index - 1)?.recordHash &&
                    attempt.hasValidHash()
            ) { "Tool transport attempt ledger chain is invalid at sequence $index" }
        }
    }

    companion object {
        const val SCHEMA_VERSION: String = "tool-transport-attempt-ledger-v1"
    }
}

/**
 * Atomic append/read store for public-safe runtime tool transport accounting.
 *
 * @param databaseFactory Run control-directory authority.
 */
class ToolTransportAttemptStore(
    private val databaseFactory: SubstrateDatabaseFactory,
    private val clock: Clock = Clock.System,
) {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        prettyPrint = false
    }

    /**
     * Persists one exact compilation result before it can enter staging.
     *
     * @param runId Exact run identity.
     * @param groupId Exact worker-group identity.
     * @param agentId Exact ordinary-agent identity.
     * @param round Exact zero-based worker round.
     * @param compilation Runtime-owned compiler result.
     * @param attemptId Stable lifecycle-local invocation identity.
     */
    suspend fun append(
        runId: String,
        groupId: String,
        agentId: String,
        round: Int,
        compilation: ToolCandidateWireCompilation,
        attemptId: String = UUID.randomUUID().toString(),
    ): ToolTransportAttemptRecordV1 = withContext(Dispatchers.IO) {
        mutex(runId).withLock {
            require(attemptId.isNotBlank()) { "Tool transport attempt ID must not be blank" }
            withLedgerFileLock(runId) {
                val ledger = readInternal(runId)
                val outcome = when (compilation) {
                    is ToolCandidateWireCompilation.Compiled ->
                        ToolTransportAttemptOutcome.COMPILED
                    is ToolCandidateWireCompilation.Rejected ->
                        ToolTransportAttemptOutcome.REJECTED
                }
                val rejectionCode =
                    (compilation as? ToolCandidateWireCompilation.Rejected)?.code
                val candidateHash =
                    (compilation as? ToolCandidateWireCompilation.Compiled)
                        ?.candidate
                        ?.candidateHash
                ledger.attempts.singleOrNull { it.attemptId == attemptId }?.let { existing ->
                    require(
                        existing.runId == runId &&
                            existing.groupId == groupId &&
                            existing.agentId == agentId &&
                            existing.round == round &&
                            existing.outcome == outcome &&
                            existing.rejectionCode == rejectionCode &&
                            existing.candidateHash == candidateHash
                    ) { "Tool transport attempt identity is immutable" }
                    return@withLedgerFileLock existing
                }
                val previous = ledger.attempts.lastOrNull()
                val record = ToolTransportAttemptRecordV1(
                    attemptId = attemptId,
                    runId = runId,
                    groupId = groupId,
                    agentId = agentId,
                    round = round,
                    outcome = outcome,
                    rejectionCode = rejectionCode,
                    candidateHash = candidateHash,
                    sequence = ledger.attempts.size,
                    previousRecordHash = previous?.recordHash,
                    occurredAt = clock.now(),
                ).withComputedHash()
                persist(
                    ToolTransportAttemptLedgerV1(
                        runId = runId,
                        attempts = ledger.attempts + record,
                    )
                )
                readInternal(runId).attempts.last().also { persisted ->
                    require(persisted == record) {
                        "Tool transport attempt changed during durable readback"
                    }
                }
            }
        }
    }

    /** Returns exact accepted-publication measurements, including an authoritative zero ledger. */
    suspend fun measurements(runId: String): ToolTransportClosureMeasurements =
        withContext(Dispatchers.IO) {
            mutex(runId).withLock {
                withLedgerFileLock(runId) {
                    val ledger = readInternal(runId)
                    if (!file(runId).isFile) persist(ledger)
                    val bytes = Files.readAllBytes(file(runId).toPath())
                    ToolTransportClosureMeasurements(
                        attempts = ledger.attempts.size,
                        failures = ledger.attempts.count {
                            it.outcome == ToolTransportAttemptOutcome.REJECTED
                        },
                        evidenceRefs = listOf(
                            "tool-transport-ledger/${sha256Hex(bytes)}"
                        ) + ledger.attempts.map {
                            "tool-transport-attempt/${it.recordHash}"
                        },
                    )
                }
            }
        }

    /** Strictly reads the current ledger for [runId]. */
    suspend fun read(runId: String): ToolTransportAttemptLedgerV1 =
        withContext(Dispatchers.IO) {
            mutex(runId).withLock {
                withLedgerFileLock(runId) { readInternal(runId) }
            }
        }

    private fun readInternal(runId: String): ToolTransportAttemptLedgerV1 {
        val file = file(runId)
        if (!file.isFile) return ToolTransportAttemptLedgerV1(runId = runId, attempts = emptyList())
        return runCatching {
            json.decodeFromString<ToolTransportAttemptLedgerV1>(file.readText())
        }.getOrElse { error ->
            throw IllegalStateException("Tool transport attempt ledger is unreadable", error)
        }.also { ledger ->
            require(ledger.runId == runId) {
                "Tool transport attempt ledger belongs to another run"
            }
        }
    }

    private fun persist(ledger: ToolTransportAttemptLedgerV1) {
        val target = file(ledger.runId)
        target.parentFile?.mkdirs()
        val bytes = json.encodeToString(ledger).toByteArray(Charsets.UTF_8)
        val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            temporary.writeBytes(bytes)
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
        require(Files.readAllBytes(target.toPath()).contentEquals(bytes)) {
            "Tool transport attempt ledger changed during durable readback"
        }
    }

    private fun file(runId: String): File =
        File(databaseFactory.runControlDir(runId), FILE_NAME)

    private fun mutex(runId: String): Mutex =
        processMutexes.computeIfAbsent(file(runId).absolutePath) { Mutex() }

    private fun <T> withLedgerFileLock(runId: String, block: () -> T): T {
        val lockFile = File(databaseFactory.runControlDir(runId), "$FILE_NAME.lock")
        lockFile.parentFile?.mkdirs()
        return FileChannel.open(
            lockFile.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.lock().use { block() }
        }
    }

    companion object {
        const val FILE_NAME: String = "tool-transport-attempts-v1.json"
        private val processMutexes = ConcurrentHashMap<String, Mutex>()
    }
}

private val toolTransportRecordJson = Json {
    encodeDefaults = true
    explicitNulls = true
    prettyPrint = false
}

private fun hashRecord(record: ToolTransportAttemptRecordV1): String =
    AtlasHashing.hashText(
        toolTransportRecordJson.encodeToString(record.copy(recordHash = ""))
    )
