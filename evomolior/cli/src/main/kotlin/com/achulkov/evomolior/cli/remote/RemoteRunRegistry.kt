package com.achulkov.evomolior.cli.remote

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText

private val runRegistryJson = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

@Serializable
internal enum class RemoteRunStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED
}

@Serializable
internal data class RemoteRunLaunch(
    val backend: String? = null,
    val runner: String? = null,
    val fast: Boolean? = null,
    val benchmark: String? = null,
    val limit: Int? = null,
    val offset: Int? = null
)

@Serializable
internal data class RemoteRunRecord(
    val id: String,
    val type: String,
    val status: RemoteRunStatus,
    val startedAt: String,
    val completedAt: String? = null,
    val command: List<String>,
    val outputDir: String,
    val logPath: String,
    val exitCode: Int? = null,
    val error: String? = null,
    val launch: RemoteRunLaunch? = null,
    val processId: Long? = null,
    val processStartedAt: String? = null,
    val schemaVersion: Int = 1
)

internal data class RemoteOutputFile(
    val relativePath: String,
    val sizeBytes: Long
)

internal data class RemoteOpenedOutput(
    val fileName: String,
    val sizeBytes: Long,
    val channel: SeekableByteChannel,
    val modifiedAt: Instant? = null
) : AutoCloseable {
    override fun close() {
        channel.close()
    }
}

internal data class RemoteRunRegistryLimits(
    val maxRunDirectories: Long = 10_000,
    val maxMetadataBytes: Int = 1024 * 1024,
    val maxLoadWarnings: Int = 100
) {
    init {
        require(maxRunDirectories > 0) { "maxRunDirectories must be positive" }
        require(maxMetadataBytes > 0) { "maxMetadataBytes must be positive" }
        require(maxLoadWarnings > 0) { "maxLoadWarnings must be positive" }
    }
}

internal data class RemoteRunRegistryLoadReport(
    val loadedCount: Int,
    val skippedCount: Int,
    val warnings: List<String>
)

internal fun interface RemoteActiveProcessReconciler {
    fun reconcile(record: RemoteRunRecord): String
}

internal interface RemoteRunReadAccess {
    fun openArtifact(record: RemoteRunRecord, relativePath: String): RemoteOpenedOutput?

    fun openLog(record: RemoteRunRecord): RemoteOpenedOutput?
}

/**
 * Thread-safe run status store backed by JSON metadata in the dashboard data volume.
 *
 * @param dataDir Persistent dashboard data directory.
 * @param limits Hard bounds for registry startup work.
 * @param activeProcessReconciler Reconciles persisted active child-process authority on restart.
 */
internal class RemoteRunRegistry(
    private val dataDir: Path,
    private val limits: RemoteRunRegistryLimits = RemoteRunRegistryLimits(),
    private val activeProcessReconciler: RemoteActiveProcessReconciler =
        RemoteActiveProcessReconciler(::reconcilePersistedActiveProcess)
) : RemoteRunReadAccess {
    private val mutex = Mutex()
    private val records = linkedMapOf<String, RemoteRunRecord>()
    val runsDir: Path = dataDir.resolve("runs").toAbsolutePath().normalize()

    suspend fun load(): RemoteRunRegistryLoadReport {
        runsDir.createDirectories()
        val runDirectories = Files.list(runsDir).use { stream ->
            stream.limit(limits.maxRunDirectories + 1).toList()
        }
        check(runDirectories.size.toLong() <= limits.maxRunDirectories) {
            "Dashboard registry exceeds the ${limits.maxRunDirectories}-run startup limit"
        }
        val warnings = mutableListOf<String>()
        var skippedCount = 0
        val loaded = runDirectories.mapNotNull { runDirectory ->
            if (
                !Files.isDirectory(runDirectory, LinkOption.NOFOLLOW_LINKS) ||
                Files.isSymbolicLink(runDirectory)
            ) {
                return@mapNotNull null
            }
            val metadata = runDirectory.resolve(METADATA_FILE)
            if (
                !Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS) ||
                Files.isSymbolicLink(metadata)
            ) {
                return@mapNotNull null
            }
            runCatching {
                val record = readMetadata(metadata)
                val normalizedRunDirectory = runDirectory.toAbsolutePath().normalize()
                require(
                    record.id == normalizedRunDirectory.fileName.toString() &&
                        record.id.isNotBlank() &&
                        !record.id.contains('/') &&
                        !record.id.contains('\\')
                ) {
                    "metadata id does not match its run directory"
                }
                record.copy(
                    outputDir = normalizedRunDirectory.resolve("output").toString(),
                    logPath = normalizedRunDirectory.resolve("run.log").toString()
                )
            }.getOrElse {
                skippedCount += 1
                if (warnings.size < limits.maxLoadWarnings) {
                    warnings += "Skipped run '${runDirectory.fileName}' because its metadata is invalid or exceeds limits."
                }
                null
            }
        }
        if (skippedCount > warnings.size) {
            warnings += "${skippedCount - warnings.size} additional invalid run record(s) were skipped."
        }
        mutex.withLock {
            records.clear()
            loaded.sortedByDescending { it.startedAt }.forEach { loadedRecord ->
                val record = if (loadedRecord.status in ACTIVE_STATUSES) {
                    val reconciliation = runCatching {
                        activeProcessReconciler.reconcile(loadedRecord)
                    }.getOrElse { error ->
                        throw IllegalStateException(
                            "Cannot safely reconcile active dashboard run '${loadedRecord.id}': ${error.message}",
                            error
                        )
                    }
                    loadedRecord.copy(
                        status = RemoteRunStatus.FAILED,
                        completedAt = Instant.now().toString(),
                        error = "Dashboard restart interrupted the active CLI process. $reconciliation",
                        processId = null,
                        processStartedAt = null,
                        schemaVersion = CURRENT_SCHEMA_VERSION
                    ).also(::persist)
                } else {
                    loadedRecord
                }
                records[record.id] = record
            }
        }
        return RemoteRunRegistryLoadReport(
            loadedCount = loaded.size,
            skippedCount = skippedCount,
            warnings = warnings
        )
    }

    suspend fun create(
        id: String,
        type: String,
        command: List<String>,
        outputDir: Path,
        logPath: Path,
        launch: RemoteRunLaunch? = null
    ): RemoteRunRecord {
        runsDir.createDirectories()
        val runDir = runsDir.resolve(id)
        runDir.createDirectories()
        val record = RemoteRunRecord(
            id = id,
            type = type,
            status = RemoteRunStatus.QUEUED,
            startedAt = Instant.now().toString(),
            command = command,
            outputDir = outputDir.toAbsolutePath().normalize().toString(),
            logPath = logPath.toAbsolutePath().normalize().toString(),
            launch = launch,
            schemaVersion = CURRENT_SCHEMA_VERSION
        )
        return upsert(record)
    }

    suspend fun markRunning(
        id: String,
        processId: Long,
        processStartedAt: Instant
    ): RemoteRunRecord = update(id) {
        it.copy(
            status = RemoteRunStatus.RUNNING,
            processId = processId,
            processStartedAt = processStartedAt.toString(),
            schemaVersion = CURRENT_SCHEMA_VERSION
        )
    }

    suspend fun markSucceeded(id: String, exitCode: Int = 0): RemoteRunRecord = update(id) {
        it.copy(
            status = RemoteRunStatus.SUCCEEDED,
            completedAt = Instant.now().toString(),
            exitCode = exitCode,
            processId = null,
            processStartedAt = null,
            schemaVersion = CURRENT_SCHEMA_VERSION
        )
    }

    suspend fun markFailed(id: String, error: String, exitCode: Int? = null): RemoteRunRecord = update(id) {
        it.copy(
            status = RemoteRunStatus.FAILED,
            completedAt = Instant.now().toString(),
            exitCode = exitCode,
            error = error,
            processId = null,
            processStartedAt = null,
            schemaVersion = CURRENT_SCHEMA_VERSION
        )
    }

    suspend fun list(): List<RemoteRunRecord> = mutex.withLock { records.values.sortedByDescending { it.startedAt } }

    suspend fun get(id: String): RemoteRunRecord? = mutex.withLock { records[id] }

    fun outputFiles(record: RemoteRunRecord): List<RemoteOutputFile> {
        val outputRoot = outputRoot(record) ?: return emptyList()
        if (!outputRoot.exists()) return emptyList()
        return Files.walk(outputRoot, MAX_OUTPUT_DEPTH).use { stream ->
            stream
                .filter { it.isRegularFile() }
                .filter { file -> !Files.isSymbolicLink(file) }
                .filter { file -> isDashboardDownloadableOutputPath(file.relativeTo(outputRoot).joinToStringPath()) }
                .limit(MAX_OUTPUT_FILES)
                .map {
                    RemoteOutputFile(
                        relativePath = it.relativeTo(outputRoot).joinToStringPath(),
                        sizeBytes = Files.size(it)
                    )
                }
                .sorted(Comparator.comparing(RemoteOutputFile::relativePath))
                .toList()
        }
    }

    fun resolveOutputFile(record: RemoteRunRecord, relativePath: String): Path? {
        if (!isDashboardDownloadableOutputPath(relativePath)) return null
        val outputRoot = outputRoot(record) ?: return null
        val candidate = outputRoot.resolve(relativePath).toAbsolutePath().normalize()
        if (
            !candidate.startsWith(outputRoot) ||
            !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(candidate) ||
            !candidate.toRealPath().startsWith(outputRoot.toRealPath())
        ) {
            return null
        }
        return candidate
    }

    fun openOutputFile(record: RemoteRunRecord, relativePath: String): RemoteOpenedOutput? {
        if (!isDashboardDownloadableOutputPath(relativePath)) return null
        return openOutputScopedFile(record, relativePath)
    }

    fun openPreviewFile(record: RemoteRunRecord, relativePath: String): RemoteOpenedOutput? {
        if (!isDashboardPreviewableOutputPath(relativePath)) return null
        return openOutputScopedFile(record, relativePath)
    }

    override fun openArtifact(record: RemoteRunRecord, relativePath: String): RemoteOpenedOutput? {
        if (!isDashboardVisibleOutputPath(relativePath)) return null
        return openOutputScopedFile(record, relativePath)
    }

    override fun openLog(record: RemoteRunRecord): RemoteOpenedOutput? =
        openRunRelativeFile(record, listOf("run.log"))

    private fun openOutputScopedFile(
        record: RemoteRunRecord,
        relativePath: String
    ): RemoteOpenedOutput? {
        val segments = relativePath.replace('\\', '/').split('/').filter(String::isNotBlank)
        if (
            segments.isEmpty() ||
            segments.any { it == "." || it == ".." || it.startsWith('.') }
        ) {
            return null
        }
        return openRunRelativeFile(record, listOf("output") + segments)
    }

    private fun openRunRelativeFile(
        record: RemoteRunRecord,
        segments: List<String>
    ): RemoteOpenedOutput? {
        val runRoot = runRoot(record) ?: return null
        if (
            segments.isEmpty() ||
            segments.any { it.isBlank() || it == "." || it == ".." || it.contains('/') || it.contains('\\') }
        ) {
            return null
        }
        val runsDirectory = Files.newDirectoryStream(runsDir)
        val secureRuns = runsDirectory as? SecureDirectoryStream<Path> ?: run {
            runsDirectory.close()
            return openRunFileFallback(runRoot, segments)
        }
        val openedDirectories = mutableListOf<SecureDirectoryStream<Path>>(secureRuns)
        var channel: SeekableByteChannel? = null
        return try {
            val secureRun = secureRuns.newDirectoryStream(Path.of(record.id), LinkOption.NOFOLLOW_LINKS)
            openedDirectories += secureRun
            var current = secureRun
            segments.dropLast(1).forEach { segment ->
                current = current.newDirectoryStream(Path.of(segment), LinkOption.NOFOLLOW_LINKS)
                openedDirectories += current
            }
            val fileName = Path.of(segments.last())
            val attributes = current
                .getFileAttributeView(fileName, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                .readAttributes()
            if (!attributes.isRegularFile) return null
            channel = current.newByteChannel(
                fileName,
                setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
            )
            val openedAttributes = current
                .getFileAttributeView(fileName, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                .readAttributes()
            if (
                !openedAttributes.isRegularFile ||
                attributes.fileKey() != openedAttributes.fileKey()
            ) {
                return null
            }
            RemoteOpenedOutput(
                fileName = segments.last(),
                sizeBytes = openedAttributes.size(),
                channel = channel,
                modifiedAt = openedAttributes.lastModifiedTime().toInstant()
            ).also { channel = null }
        } catch (_: Exception) {
            null
        } finally {
            channel?.close()
            openedDirectories.asReversed().forEach { directory -> runCatching { directory.close() } }
        }
    }

    private fun openRunFileFallback(
        runRoot: Path,
        segments: List<String>
    ): RemoteOpenedOutput? {
        val candidate = segments.fold(runRoot) { current, segment -> current.resolve(segment) }
            .toAbsolutePath()
            .normalize()
        if (!candidate.startsWith(runRoot)) return null
        var current = runRoot
        for (segment in segments) {
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) return null
        }
        var channel: SeekableByteChannel? = null
        return try {
            val before = Files.readAttributes(
                candidate,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS
            )
            if (!before.isRegularFile || !candidate.toRealPath().startsWith(runRoot.toRealPath())) return null
            channel = Files.newByteChannel(
                candidate,
                setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
            )
            val after = Files.readAttributes(
                candidate,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS
            )
            if (
                !after.isRegularFile ||
                before.fileKey() != after.fileKey() ||
                !candidate.toRealPath().startsWith(runRoot.toRealPath())
            ) {
                return null
            }
            RemoteOpenedOutput(
                fileName = segments.last(),
                sizeBytes = after.size(),
                channel = channel,
                modifiedAt = after.lastModifiedTime().toInstant()
            ).also { channel = null }
        } catch (_: Exception) {
            null
        } finally {
            channel?.close()
        }
    }

    private fun readMetadata(path: Path): RemoteRunRecord {
        val bytes = Files.newInputStream(path, StandardOpenOption.READ).use { input ->
            input.readNBytes(limits.maxMetadataBytes + 1)
        }
        require(bytes.size <= limits.maxMetadataBytes) { "metadata exceeds the configured size limit" }
        val text = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
        return runRegistryJson.decodeFromString(text)
    }

    private suspend fun update(id: String, transform: (RemoteRunRecord) -> RemoteRunRecord): RemoteRunRecord {
        return mutex.withLock {
            val current = records[id] ?: error("Unknown remote run id: $id")
            val updated = transform(current)
            records[id] = updated
            persist(updated)
            updated
        }
    }

    private suspend fun upsert(record: RemoteRunRecord): RemoteRunRecord {
        return mutex.withLock {
            records[record.id] = record
            persist(record)
            record
        }
    }

    private fun persist(record: RemoteRunRecord) {
        val runDir = runsDir.resolve(record.id)
        runDir.createDirectories()
        val metadata = runDir.resolve(METADATA_FILE)
        val temporary = runDir.resolve("$METADATA_FILE.tmp")
        Files.writeString(
            temporary,
            runRegistryJson.encodeToString(record),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        )
        runCatching {
            Files.move(
                temporary,
                metadata,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        }.getOrElse {
            Files.move(temporary, metadata, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun Path.joinToStringPath(): String = iterator().asSequence().joinToString("/") { it.name }

    private fun outputRoot(record: RemoteRunRecord): Path? {
        val runRoot = runRoot(record) ?: return null
        val output = runRoot.resolve("output").toAbsolutePath().normalize()
        if (
            !Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(output) ||
            output.toRealPath().parent != runRoot.toRealPath()
        ) {
            return null
        }
        return output
    }

    private fun runRoot(record: RemoteRunRecord): Path? {
        if (record.id.isBlank() || record.id.contains('/') || record.id.contains('\\')) return null
        val root = runsDir.resolve(record.id).toAbsolutePath().normalize()
        if (
            root.parent != runsDir ||
            !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(root)
        ) {
            return null
        }
        val runsReal = runCatching { runsDir.toRealPath() }.getOrNull() ?: return null
        return root.takeIf { runCatching { it.toRealPath().parent == runsReal }.getOrDefault(false) }
    }

    private companion object {
        const val METADATA_FILE = "run.json"
        const val MAX_OUTPUT_FILES = 1_000L
        const val MAX_OUTPUT_DEPTH = 12
        val ACTIVE_STATUSES = setOf(RemoteRunStatus.QUEUED, RemoteRunStatus.RUNNING)
    }
}

private const val CURRENT_SCHEMA_VERSION = 3
private const val PROCESS_TERMINATION_GRACE_MILLIS = 1_500L

private fun reconcilePersistedActiveProcess(record: RemoteRunRecord): String {
    if (record.status == RemoteRunStatus.QUEUED && record.processId == null) {
        return "Queued work had not authorized a supervised child process."
    }
    val processId = requireNotNull(record.processId) {
        "running metadata has no process identity; operator reconciliation is required"
    }
    val expectedStart = requireNotNull(record.processStartedAt)
        .let { encoded ->
            runCatching { Instant.parse(encoded) }.getOrElse {
                error("running metadata has an invalid process start instant")
            }
        }
    val process = ProcessHandle.of(processId).orElse(null)
        ?: return "No matching live child process remained."
    if (!process.isAlive) return "The recorded child process had already exited."
    val actualStart = process.info().startInstant().orElse(null)
        ?: error("the live process start instant is unavailable")
    check(actualStart == expectedStart) {
        "the recorded process id belongs to a different live process; operator reconciliation is required"
    }
    terminateProcessTree(process)
    return "The verified surviving child process was terminated before dashboard admission."
}

internal fun terminateProcessTree(root: ProcessHandle) {
    val descendants = root.descendants().use { stream -> stream.toList() }
    val processTree = descendants.asReversed() + root
    processTree.filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy)
    waitForProcessTree(processTree, PROCESS_TERMINATION_GRACE_MILLIS)
    processTree.filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly)
    waitForProcessTree(processTree, PROCESS_TERMINATION_GRACE_MILLIS)
    check(processTree.none(ProcessHandle::isAlive)) {
        "the verified dashboard child process could not be terminated"
    }
}

private fun waitForProcessTree(processes: List<ProcessHandle>, timeoutMillis: Long) {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
    while (processes.any(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
        Thread.sleep(25)
    }
}

internal fun newRemoteRunId(): String =
    "${Instant.now().toString().replace(':', '-')}-${UUID.randomUUID().toString().take(8)}"

internal fun isDashboardVisibleOutputPath(relativePath: String): Boolean {
    if (relativePath.isBlank()) return false
    val segments = relativePath.replace('\\', '/').split('/').filter(String::isNotBlank)
    if (segments.isEmpty() || segments.any { it == "." || it == ".." || it.startsWith('.') }) return false
    if (segments.any { it.lowercase() in BLOCKED_OUTPUT_SEGMENTS }) return false
    val filename = segments.last().lowercase()
    if (filename in BLOCKED_OUTPUT_FILES) return false
    if (SENSITIVE_OUTPUT_NAME_PARTS.any(filename::contains)) return false
    return filename.substringAfterLast('.', missingDelimiterValue = "") !in BLOCKED_OUTPUT_EXTENSIONS
}

internal fun isDashboardDownloadableOutputPath(relativePath: String): Boolean {
    if (!isDashboardVisibleOutputPath(relativePath) || !isDashboardPublicExportPath(relativePath)) return false
    val filename = relativePath.replace('\\', '/').substringAfterLast('/').lowercase()
    val extension = filename.substringAfterLast('.', missingDelimiterValue = "")
    return extension in PUBLIC_EXPORT_EXTENSIONS
}

internal fun isDashboardPreviewableOutputPath(relativePath: String): Boolean {
    if (!isDashboardVisibleOutputPath(relativePath)) return false
    val filename = relativePath.replace('\\', '/').substringAfterLast('/').lowercase()
    return filename in REDACTED_OBSERVABILITY_FILES || isDashboardPublicExportPath(relativePath)
}

private fun isDashboardPublicExportPath(relativePath: String): Boolean {
    val segments = relativePath.replace('\\', '/').split('/').filter(String::isNotBlank)
    val outputsIndex = segments.indexOfLast { it.equals("outputs", ignoreCase = true) }
    if (outputsIndex < 0 || outputsIndex == segments.lastIndex) return false
    return segments.drop(outputsIndex + 1).none { segment ->
        segment.startsWith('.') || segment.lowercase() in BLOCKED_OUTPUT_SEGMENTS
    }
}

private val BLOCKED_OUTPUT_SEGMENTS = setOf(
    "authority",
    "databases",
    "inputs",
    "logs"
)
private val BLOCKED_OUTPUT_FILES = setOf("debug-log.txt", "prompt.txt")
private val BLOCKED_OUTPUT_EXTENSIONS = setOf("db", "sqlite", "sqlite3", "lck")
private val REDACTED_OBSERVABILITY_FILES = setOf(
    "summary.json",
    "pipeline-summary.json",
    "tier0-benchmark-report.json",
    "comparative-experiment-plan.json",
    "comparative-experiment-report.json",
    "comparative-experiment-results.json",
    "metrics.json",
    "metrics.jsonl",
    "telemetry.jsonl",
    "llm-metrics.jsonl",
    "claim_ledger.jsonl",
    "citation_map.json",
    "cost_report.json",
    "results.zip",
    "pipeline-results.zip"
)
private val SENSITIVE_OUTPUT_NAME_PARTS = setOf(
    "transcript",
    "raw-llm",
    "raw_llm",
    "rawllm",
    "context-window",
    "context_window",
    "contextwindow",
    "messages",
    "objective",
    "prompt"
)
private val PUBLIC_EXPORT_EXTENSIONS = setOf(
    "md",
    "markdown",
    "txt",
    "csv",
    "tsv",
    "html",
    "htm",
    "svg",
    "png",
    "jpg",
    "jpeg",
    "gif",
    "webp",
    "pdf",
    "json"
)
