package com.achulkov.evomolior.agents.factory

import ai.koog.prompt.params.LLMParams
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import kotlin.io.path.deleteIfExists

internal const val CODEX_STRUCTURED_OUTPUT_DIAGNOSTIC_MAX_RESPONSE_BYTES = 1024 * 1024
private const val CODEX_STRUCTURED_OUTPUT_DIAGNOSTIC_MAX_RECORDS = 16
private const val CODEX_STRUCTURED_OUTPUT_DIAGNOSTIC_MAX_VIOLATIONS = 32
private const val CODEX_STRUCTURED_OUTPUT_DIAGNOSTIC_MAX_VIOLATION_CHARS = 512

/**
 * Exact UTF-8 JSON Schema bytes transported to Codex.
 *
 * @param name Koog schema identity.
 * @param bytes Exact bytes written to `--output-schema`.
 * @param sha256 Lowercase SHA-256 of [bytes].
 */
internal data class CodexOutputSchemaDocument(
    val name: String,
    val bytes: ByteArray,
    val sha256: String
) {
    init {
        require(bytes.size <= CODEX_OUTPUT_SCHEMA_MAX_BYTES) {
            "Codex/ChatGPT output schema exceeds $CODEX_OUTPUT_SCHEMA_MAX_BYTES bytes"
        }
        require(sha256 == digestSha256(bytes)) {
            "Codex output-schema hash must match its exact bytes"
        }
    }

    /** Writes the exact document to a new bounded temporary file. */
    fun materialize(): Path {
        val path = CodexTempFiles.createTempFile("evomolior-codex-schema-", ".json")
        return try {
            Files.write(
                path,
                bytes,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            )
            path
        } catch (failure: Throwable) {
            path.deleteIfExists()
            throw failure
        }
    }

    /** Writes the exact document to [path] for deterministic offline inspection. */
    fun capture(path: Path) {
        require(path.parent != null) { "Codex output-schema capture requires a parent directory" }
        Files.createDirectories(path.parent)
        writeAtomically(path, bytes)
    }

    companion object {
        /** Encodes the exact schema bytes used by the Codex transport. */
        fun from(schema: LLMParams.Schema): CodexOutputSchemaDocument {
            val jsonSchema = schema as? LLMParams.Schema.JSON
                ?: error("Codex/ChatGPT supports only JSON output schemas")
            val bytes = jsonSchema.schema.toString().toByteArray(Charsets.UTF_8)
            return CodexOutputSchemaDocument(
                name = schema.name,
                bytes = bytes,
                sha256 = digestSha256(bytes)
            )
        }
    }
}

/** Standards-based Draft 2020-12 validation of the exact Codex response transport. */
internal object CodexStructuredOutputValidator {
    private val mapper = ObjectMapper()
    private val registry = SchemaRegistry.withDefaultDialect(
        SpecificationVersion.DRAFT_2020_12
    )

    /**
     * Validates [response] against [schema].
     *
     * @return Deterministically ordered compact violations, or an empty list.
     */
    fun validate(schema: CodexOutputSchemaDocument, response: ByteArray): List<String> {
        val schemaNode = runCatching { mapper.readTree(schema.bytes) }.getOrElse { failure ->
            return listOf("schema_parse_error: ${failure.compactMessage()}")
        }
        val responseNode = runCatching { mapper.readTree(response) }.getOrElse { failure ->
            return listOf("response_parse_error: ${failure.compactMessage()}")
        }
        return runCatching {
            registry.getSchema(schemaNode)
                .validate(responseNode)
                .map { it.toString().take(CODEX_STRUCTURED_OUTPUT_DIAGNOSTIC_MAX_VIOLATION_CHARS) }
                .sorted()
                .take(CODEX_STRUCTURED_OUTPUT_DIAGNOSTIC_MAX_VIOLATIONS)
        }.getOrElse { failure ->
            listOf("schema_validation_error: ${failure.compactMessage()}")
        }
    }

    /** Compiles the exact schema under Draft 2020-12 without accepting a provider response. */
    fun validateSchema(schema: CodexOutputSchemaDocument): List<String> {
        val schemaNode = runCatching { mapper.readTree(schema.bytes) }.getOrElse { failure ->
            return listOf("schema_parse_error: ${failure.compactMessage()}")
        }
        return runCatching {
            registry.getSchema(schemaNode)
            emptyList<String>()
        }.getOrElse { failure ->
            listOf("schema_validation_error: ${failure.compactMessage()}")
        }
    }

    /** Returns a lowercase SHA-256 digest of exact response bytes. */
    fun sha256(response: ByteArray): String = digestSha256(response)

    private fun Throwable.compactMessage(): String =
        (message ?: this::class.simpleName.orEmpty())
            .replace(Regex("\\s+"), " ")
            .take(CODEX_STRUCTURED_OUTPUT_DIAGNOSTIC_MAX_VIOLATION_CHARS)
}

/**
 * Bounded owner-only evidence for a schema-bearing response that failed standards validation.
 *
 * Raw response bytes are retained only when they fit the fixed bound. Hashes and byte counts are
 * always retained. The recorder never captures prompts, credentials, environment variables, or
 * provider stdout/stderr.
 */
internal class CodexStructuredOutputDiagnosticRecorder(
    private val root: Path,
    private val maximumRecords: Int = CODEX_STRUCTURED_OUTPUT_DIAGNOSTIC_MAX_RECORDS,
    private val permissions: CodexDiagnosticPermissionEnforcer =
        PosixCodexDiagnosticPermissionEnforcer
) {
    private var initialized = false
    private var publishedRecords = 0
    private var nextRecordIndex = 0

    init {
        require(maximumRecords > 0) { "Structured-output diagnostic record bound must be positive" }
    }

    /** Retains one invalid response without exceeding [maximumRecords]. */
    @Synchronized
    fun record(
        promptId: String,
        schema: CodexOutputSchemaDocument,
        response: ByteArray,
        violations: List<String>
    ): CodexStructuredOutputDiagnosticRecord? {
        initialize()
        if (publishedRecords >= maximumRecords) return null
        val index = nextRecordIndex++
        val safePromptId = promptId
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(80)
            .ifBlank { "structured-output" }
        val recordRoot = root.resolve("%02d-%s".format(index + 1, safePromptId))
        val temporaryRoot = Files.createTempDirectory(root, ".record-")
        return try {
            permissions.enforceDirectory(temporaryRoot)
            val responseHash = digestSha256(response)
            writeAtomically(
                temporaryRoot.resolve("output-schema.json"),
                schema.bytes,
                permissions
            )
            val responseRetained =
                response.size <= CODEX_STRUCTURED_OUTPUT_DIAGNOSTIC_MAX_RESPONSE_BYTES &&
                    runCatching {
                        writeAtomically(
                            temporaryRoot.resolve("raw-response.json"),
                            response,
                            permissions
                        )
                    }.isSuccess
            val manifest = JsonObject(
                linkedMapOf(
                    "promptId" to JsonPrimitive(promptId),
                    "schemaName" to JsonPrimitive(schema.name),
                    "schemaSha256" to JsonPrimitive(schema.sha256),
                    "schemaBytes" to JsonPrimitive(schema.bytes.size),
                    "responseSha256" to JsonPrimitive(responseHash),
                    "responseBytes" to JsonPrimitive(response.size),
                    "responseRetained" to JsonPrimitive(responseRetained),
                    "violations" to JsonArray(
                        violations
                            .take(CODEX_STRUCTURED_OUTPUT_DIAGNOSTIC_MAX_VIOLATIONS)
                            .map(::JsonPrimitive)
                    )
                )
            )
            writeAtomically(
                temporaryRoot.resolve("manifest.json"),
                Json.encodeToString(JsonObject.serializer(), manifest).toByteArray(Charsets.UTF_8),
                permissions
            )
            Files.move(temporaryRoot, recordRoot, StandardCopyOption.ATOMIC_MOVE)
            publishedRecords += 1
            CodexStructuredOutputDiagnosticRecord(
                root = recordRoot,
                schemaSha256 = schema.sha256,
                responseSha256 = responseHash,
                responseRetained = responseRetained
            )
        } finally {
            deleteTemporaryRecord(temporaryRoot)
        }
    }

    private fun initialize() {
        if (initialized) return
        Files.createDirectories(root)
        permissions.enforceDirectory(root)
        reclaimTemporaryRecords(root, permissions)
        val state = existingRecordState(root)
        publishedRecords = state.count
        nextRecordIndex = state.nextIndex
        initialized = true
    }
}

internal data class CodexStructuredOutputDiagnosticRecord(
    val root: Path,
    val schemaSha256: String,
    val responseSha256: String,
    val responseRetained: Boolean
)

private data class ExistingRecordState(
    val count: Int,
    val nextIndex: Int
)

private fun existingRecordState(root: Path): ExistingRecordState {
    val indices = Files.list(root).use { paths ->
        paths.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
            .map { path ->
                RECORD_DIRECTORY_PATTERN.matchEntire(path.fileName.toString())
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
            }
            .filter { it != null }
            .map { requireNotNull(it) }
            .toList()
    }
    return ExistingRecordState(
        count = indices.size,
        nextIndex = (indices.maxOrNull() ?: 0)
    )
}

private fun reclaimTemporaryRecords(
    root: Path,
    permissions: CodexDiagnosticPermissionEnforcer
) {
    val owner = Files.getOwner(root, LinkOption.NOFOLLOW_LINKS)
    val temporaryRecords = Files.list(root).use { paths ->
        paths.filter { it.fileName.toString().startsWith(TEMPORARY_RECORD_PREFIX) }.toList()
    }
    temporaryRecords.forEach { temporaryRoot ->
        check(Files.isDirectory(temporaryRoot, LinkOption.NOFOLLOW_LINKS)) {
            "Structured-output temporary record is not a directory: $temporaryRoot"
        }
        check(Files.getOwner(temporaryRoot, LinkOption.NOFOLLOW_LINKS) == owner) {
            "Structured-output temporary record has an unexpected owner: $temporaryRoot"
        }
        permissions.enforceDirectory(temporaryRoot)
        Files.list(temporaryRoot).use { entries ->
            entries.forEach { entry ->
                check(Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    "Structured-output temporary record contains a non-file entry: $entry"
                }
                check(Files.getOwner(entry, LinkOption.NOFOLLOW_LINKS) == owner) {
                    "Structured-output temporary file has an unexpected owner: $entry"
                }
                permissions.enforceFile(entry)
                entry.deleteIfExists()
            }
        }
        temporaryRoot.deleteIfExists()
    }
}

private fun writeAtomically(
    path: Path,
    bytes: ByteArray,
    permissions: CodexDiagnosticPermissionEnforcer = PosixCodexDiagnosticPermissionEnforcer
) {
    val temporary = Files.createTempFile(path.parent, ".${path.fileName}.", ".tmp")
    try {
        permissions.enforceFile(temporary)
        Files.write(
            temporary,
            bytes,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
        Files.move(
            temporary,
            path,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
    } finally {
        temporary.deleteIfExists()
    }
}

internal interface CodexDiagnosticPermissionEnforcer {
    /** Establishes and verifies owner-only access for [path]. */
    fun enforceDirectory(path: Path)

    /** Establishes and verifies owner-only access for [path]. */
    fun enforceFile(path: Path)
}

private object PosixCodexDiagnosticPermissionEnforcer : CodexDiagnosticPermissionEnforcer {
    private val directoryPermissions = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE
    )
    private val filePermissions = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE
    )

    override fun enforceDirectory(path: Path) {
        Files.setPosixFilePermissions(
            path,
            directoryPermissions
        )
        check(Files.getPosixFilePermissions(path) == directoryPermissions) {
            "Structured-output diagnostic directory is not owner-only: $path"
        }
    }

    override fun enforceFile(path: Path) {
        Files.setPosixFilePermissions(
            path,
            filePermissions
        )
        check(Files.getPosixFilePermissions(path) == filePermissions) {
            "Structured-output diagnostic file is not owner-only: $path"
        }
    }
}

private fun deleteTemporaryRecord(path: Path) {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
    Files.list(path).use { entries ->
        entries.forEach { it.deleteIfExists() }
    }
    path.deleteIfExists()
}

private const val TEMPORARY_RECORD_PREFIX = ".record-"
private val RECORD_DIRECTORY_PATTERN = Regex("(\\d+)-.+")

private fun digestSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
