@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.council.FieldCompileErrorCode
import com.achulkov.evomolior.core.grader.HiddenTestId
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Startup stage at which a run was rejected before normal run authority existed. */
@Serializable
enum class StartupRejectionPhase {
    ADMISSION,
    COUNCIL_COMPILE,
    TEST_EXECUTABILITY_PREFLIGHT,
    PRE_ACTIVATION
}

/** Closed disposition of this negative-evidence contract. */
@Serializable
enum class StartupRejectionDisposition {
    REJECTED_BEFORE_ACTIVATION
}

/** Machine-readable reason for a startup rejection. */
@Serializable
enum class StartupRejectionReason {
    ADMISSION_REJECTED,
    COUNCIL_EXECUTION_FAILED,
    COUNCIL_COMPILE_FAILED,
    TEST_SUITE_NOT_EXECUTABLE,
    PRE_ACTIVATION_INVARIANT_FAILED
}

/** Closed category for one startup rejection diagnostic. */
@Serializable
enum class StartupRejectionErrorKind {
    ADMISSION,
    COUNCIL_COMPILE,
    TEST_EXECUTABILITY,
    PRE_ACTIVATION
}

/** Activation state observed when a startup rejection was persisted. */
@Serializable
data class StartupActivationFlags(
    val runStartedEmitted: Boolean,
    val workerInfrastructureActivated: Boolean,
    val graderInfrastructureActivated: Boolean,
    val successArchiveCreated: Boolean,
    val winnerAuthorityCreated: Boolean
)

/** One typed compiler or preflight diagnostic retained in a rejection artifact. */
@Serializable
data class StartupRejectionError(
    val kind: StartupRejectionErrorKind,
    val code: FieldCompileErrorCode? = null,
    val path: String? = null,
    val message: String,
    val testId: HiddenTestId? = null,
    val definitionHash: String? = null
) {
    init {
        require(message.isNotBlank() && message.length <= MAX_DIAGNOSTIC_CHARS) {
            "Startup rejection diagnostic must be non-blank and bounded"
        }
        require(path == null || path.length <= MAX_PATH_CHARS) {
            "Startup rejection error path must be bounded"
        }
        require(testId == null || testId.value.length <= MAX_IDENTIFIER_CHARS) {
            "Startup rejection test identity must be bounded"
        }
        require(definitionHash == null || definitionHash.matches(Regex("[0-9a-f]{64}"))) {
            "Startup rejection definition hash must be lowercase SHA-256"
        }
    }

    companion object {
        const val MAX_DIAGNOSTIC_CHARS = 4_096
        const val MAX_PATH_CHARS = 1_024
        const val MAX_IDENTIFIER_CHARS = 512
    }
}

/**
 * Immutable negative-evidence record for a rejected startup.
 *
 * This contract is deliberately separate from success archives and active Council authority.
 */
@Serializable
data class StartupRejectionArtifact(
    val schemaVersion: String = SCHEMA_VERSION,
    val runId: String,
    val phase: StartupRejectionPhase,
    val disposition: StartupRejectionDisposition =
        StartupRejectionDisposition.REJECTED_BEFORE_ACTIVATION,
    val reasons: List<StartupRejectionReason>,
    val errors: List<StartupRejectionError>,
    val candidateFieldHash: String? = null,
    val testSuiteHash: String? = null,
    val declaredTestCount: Int = 0,
    val compiledTestCount: Int = 0,
    val declaredWorkerGroupCount: Int,
    val compiledWorkerGroupCount: Int = 0,
    val declaredGraderGroupCount: Int,
    val compiledGraderGroupCount: Int = 0,
    val sourceManifestHash: String? = null,
    val distributionManifestHash: String? = null,
    val providerExecutionRefs: List<String> = emptyList(),
    val providerExecutionCount: Int = 0,
    val activationFlags: StartupActivationFlags,
    val createdAt: String
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported startup rejection schema version" }
        require(runId.isNotBlank()) { "Startup rejection runId must not be blank" }
        require(reasons.isNotEmpty()) { "Startup rejection requires at least one reason" }
        require(errors.isNotEmpty()) { "Startup rejection requires at least one typed error" }
        require(reasons.size <= 16 && errors.size <= 128) {
            "Startup rejection diagnostics must be bounded"
        }
        require(declaredTestCount >= 0 && compiledTestCount >= 0) { "Test counts must be non-negative" }
        require(compiledTestCount <= declaredTestCount) {
            "Compiled test count must not exceed the declared test count"
        }
        require(declaredWorkerGroupCount >= 0 && compiledWorkerGroupCount >= 0) { "Worker counts must be non-negative" }
        require(declaredGraderGroupCount >= 0 && compiledGraderGroupCount >= 0) { "Grader counts must be non-negative" }
        require(providerExecutionCount == providerExecutionRefs.size) { "Provider execution count must match references" }
        require(providerExecutionRefs.all(String::isNotBlank)) {
            "Provider execution references must not be blank"
        }
        require(providerExecutionRefs.distinct().size == providerExecutionRefs.size) {
            "Provider execution references must be distinct"
        }
        require(providerExecutionRefs.size <= MAX_LOGICAL_PROVIDER_EXECUTIONS) {
            "Startup rejection records at most one logical Genesis Council invocation"
        }
        require(providerExecutionRefs.all { it.length <= 1_024 }) {
            "Provider execution references must be bounded"
        }
        require(
            providerExecutionRefs.all {
                it.matches(Regex("^genesis-council:${Regex.escape(runId)}:[0-9a-f]{64}$"))
            }
        ) {
            "Provider execution references must bind the Genesis Council to this run and a lowercase SHA-256"
        }
        if (phase == StartupRejectionPhase.ADMISSION) {
            require(providerExecutionCount == 0) {
                "Admission rejection must precede every provider execution"
            }
        }
        listOfNotNull(
            candidateFieldHash,
            testSuiteHash,
            sourceManifestHash,
            distributionManifestHash
        ).forEach { hash ->
            require(hash.matches(Regex("[0-9a-f]{64}"))) {
                "Startup rejection hashes must be lowercase SHA-256 values"
            }
        }
        require(!activationFlags.runStartedEmitted) {
            "Startup rejection must precede RunStarted"
        }
        require(!activationFlags.workerInfrastructureActivated) {
            "Startup rejection must precede worker activation"
        }
        require(!activationFlags.graderInfrastructureActivated) {
            "Startup rejection must precede grader activation"
        }
        require(!activationFlags.successArchiveCreated && !activationFlags.winnerAuthorityCreated) {
            "Startup rejection must not carry success authority"
        }
        Instant.parse(createdAt)
    }

    companion object {
        const val SCHEMA_VERSION = "startup-rejection-v1"
        const val MAX_LOGICAL_PROVIDER_EXECUTIONS = 1
    }
}

/**
 * Write-once strict-readback persistence for [StartupRejectionArtifact] files.
 *
 * @param runControlDirectory Resolves the control directory for a run.
 */
class StartupRejectionFileStore(
    private val runControlDirectory: (String) -> File
) {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
    }

    /** Persists [artifact] create-once and verifies exact forced-byte typed readback. */
    fun persist(artifact: StartupRejectionArtifact): File {
        val directory = File(runControlDirectory(artifact.runId), DIRECTORY_NAME)
        require(directory.mkdirs() || directory.isDirectory) {
            "Could not create startup rejection directory ${directory.absolutePath}"
        }
        setOwnerOnlyDirectoryPermissions(directory)
        val target = File(directory, "${safeRunId(artifact.runId)}.json")
        val encoded = json.encodeToString(artifact).toByteArray(Charsets.UTF_8)
        val temporary = File(directory, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileChannel.open(
                temporary.toPath(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            ).use { channel ->
                val bytes = java.nio.ByteBuffer.wrap(encoded)
                while (bytes.hasRemaining()) {
                    if (channel.write(bytes) <= 0) {
                        throw IOException("Could not completely write startup rejection artifact")
                    }
                }
                channel.force(true)
            }
            setOwnerReadOnlyFilePermissions(temporary)
            try {
                // Linking is an atomic same-directory create; unlike ATOMIC_MOVE it cannot replace.
                Files.createLink(target.toPath(), temporary.toPath())
            } catch (error: java.nio.file.FileAlreadyExistsException) {
                val existing = Files.readAllBytes(target.toPath())
                require(existing.contentEquals(encoded)) {
                    "Startup rejection artifact already exists with different immutable content for run=${artifact.runId}"
                }
            }
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
        return verify(target, artifact, encoded)
    }

    /** Strictly reads an artifact previously stored for [runId]. */
    fun read(runId: String): StartupRejectionArtifact? {
        val target = File(File(runControlDirectory(runId), DIRECTORY_NAME), "${safeRunId(runId)}.json")
        return target.takeIf { it.isFile }?.let { file ->
            json.decodeFromString(StartupRejectionArtifact.serializer(), file.readText()).also { artifact ->
                require(artifact.runId == runId) { "Startup rejection run identity changed during readback" }
            }
        }
    }

    private fun verify(
        file: File,
        expected: StartupRejectionArtifact,
        expectedBytes: ByteArray
    ): File {
        val bytes = Files.readAllBytes(file.toPath())
        require(bytes.contentEquals(expectedBytes)) {
            "Startup rejection artifact bytes changed during readback"
        }
        val readback = json.decodeFromString(
            StartupRejectionArtifact.serializer(),
            bytes.toString(Charsets.UTF_8)
        )
        require(readback == expected) { "Startup rejection artifact changed during readback" }
        return file
    }

    private fun safeRunId(runId: String): String =
        runId.replace(Regex("[^A-Za-z0-9._-]"), "_") +
            "-" + sha256(runId.toByteArray()).take(16)

    private fun setOwnerOnlyDirectoryPermissions(directory: File) {
        runCatching {
            Files.setPosixFilePermissions(
                directory.toPath(),
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE
                )
            )
        }
    }

    private fun setOwnerReadOnlyFilePermissions(file: File) {
        val path = file.toPath()
        val expected = setOf(PosixFilePermission.OWNER_READ)
        Files.setPosixFilePermissions(path, expected)
        check(Files.getPosixFilePermissions(path) == expected) {
            "Startup rejection artifact must be owner-read-only before publication: $path"
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val DIRECTORY_NAME = "startup-rejections"
    }
}

/** Returns a stable ISO-8601 timestamp for startup rejection evidence. */
internal fun startupRejectionTimestamp(epochMs: Long = System.currentTimeMillis()): String =
    Instant.ofEpochMilli(epochMs).toString()
