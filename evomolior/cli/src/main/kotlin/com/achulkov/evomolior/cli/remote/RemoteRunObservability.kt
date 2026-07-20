package com.achulkov.evomolior.cli.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.zip.ZipInputStream
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo

@Serializable
internal data class RemoteDashboardOverview(
    val generatedAt: String,
    val stats: RemoteOverviewStats,
    val statusDistribution: List<RemoteDistributionValue>,
    val typeDistribution: List<RemoteDistributionValue>,
    val runs: List<RemoteRunSummary>
)

@Serializable
internal data class RemoteOverviewStats(
    val totalRuns: Int,
    val activeRuns: Int,
    val succeededRuns: Int,
    val failedRuns: Int,
    val successRate: Double?,
    val completedRunsPerRuntimeHour: Double?,
    val completedDurationMillis: Long,
    val artifactCount: Int,
    val outputBytes: Long,
    val artifactTotalsPartial: Boolean
)

@Serializable
internal data class RemoteDistributionValue(val label: String, val value: Int)

@Serializable
internal data class RemoteRunSummary(
    val id: String,
    val type: String,
    val status: RemoteRunStatus,
    val startedAt: String,
    val completedAt: String?,
    val elapsedMillis: Long?,
    val backend: String?,
    val runner: String?,
    val benchmark: String?,
    val fast: Boolean?,
    val phase: String,
    val progressPercent: Double?,
    val artifactCount: Int,
    val artifactInventoryTruncated: Boolean,
    val outputBytes: Long,
    val primaryScore: Double?,
    val latestActivityAt: String?,
    val error: String?
)

@Serializable
internal data class RemoteRunDetail(
    val generatedAt: String,
    val record: RemoteRunRecord,
    val summary: RemoteRunSummary,
    val metrics: List<RemoteMetric>,
    val series: List<RemoteMetricSeries>,
    val events: List<RemoteRunEvent>,
    val artifacts: List<RemoteArtifact>,
    val artifactKinds: List<RemoteDistributionValue>,
    val log: RemoteLogTail,
    val benchmark: RemoteBenchmarkSummary?,
    val comparison: RemoteComparisonSummary?,
    val warnings: List<String>
)

@Serializable
internal data class RemoteMetric(
    val id: String,
    val label: String,
    val category: String,
    val numericValue: Double? = null,
    val unit: String? = null,
    val availability: String,
    val provenance: String? = null,
    val description: String
)

@Serializable
internal data class RemoteMetricSeries(
    val id: String,
    val label: String,
    val unit: String,
    val points: List<RemoteMetricPoint>
)

@Serializable
internal data class RemoteMetricPoint(val timestamp: String, val value: Double)

@Serializable
internal data class RemoteRunEvent(
    val timestamp: String?,
    val kind: String,
    val title: String,
    val detail: String
)

@Serializable
internal data class RemoteArtifact(
    val relativePath: String,
    val sizeBytes: Long,
    val modifiedAt: String,
    val kind: String,
    val previewable: Boolean,
    val downloadable: Boolean
)

@Serializable
internal data class RemoteArtifactPreview(
    val relativePath: String,
    val kind: String,
    val content: String,
    val truncated: Boolean
)

@Serializable
internal data class RemoteLogTail(
    val lines: List<String>,
    val sizeBytes: Long,
    val truncated: Boolean,
    val withheldLines: Int
)

@Serializable
internal data class RemoteBenchmarkSummary(
    val benchmark: String?,
    val runner: String?,
    val total: Int?,
    val correct: Int?,
    val accuracy: Double?,
    val tasks: List<RemoteBenchmarkTask>
)

@Serializable
internal data class RemoteBenchmarkTask(
    val taskId: String,
    val correct: Boolean?,
    val score: Double?,
    val latencyMillis: Long?,
    val error: String?
)

@Serializable
internal data class RemoteComparisonSummary(
    val experimentId: String?,
    val baselineArm: String,
    val arms: List<RemoteComparisonArm>,
    val comparisons: List<RemoteComparisonCue>
)

@Serializable
internal data class RemoteComparisonArm(
    val arm: String,
    val cellCount: Int?,
    val successCount: Int?,
    val failureCount: Int?,
    val abstentionCount: Int?,
    val meanQuality: Double?,
    val meanCostUsd: Double?,
    val meanLatencyMillis: Double?
)

@Serializable
internal data class RemoteComparisonCue(
    val comparatorArm: String,
    val sampleCount: Int?,
    val meanDifference: Double?,
    val lower95: Double?,
    val upper95: Double?,
    val interpretation: String
)

internal data class RemoteObservabilityLimits(
    val maxArtifactFiles: Long = 1_000,
    val maxArtifactEntries: Long = 5_000,
    val maxArtifactDepth: Int = 12,
    val maxMetricDocuments: Int = 40,
    val maxPreviewBytes: Int = 128 * 1024,
    val maxLogBytes: Int = 256 * 1024,
    val maxLogLines: Int = 300,
    val maxEventBytes: Int = 1024 * 1024,
    val maxBenchmarkTasks: Int = 250
)

/**
 * Builds bounded, privacy-preserving dashboard snapshots from durable run metadata and artifacts.
 *
 * The analyzer only exposes an allowlist of numeric metrics. Arbitrary JSON strings and raw model
 * content never enter a dashboard snapshot.
 *
 * @param readAccess Descriptor-confined access to persisted run artifacts and logs.
 * @param now Clock used for deterministic elapsed-time and snapshot generation.
 * @param limits Hard limits for artifact, preview, log, event, and benchmark processing.
 */
internal class RemoteRunObservability(
    private val readAccess: RemoteRunReadAccess,
    private val now: () -> Instant = Instant::now,
    private val limits: RemoteObservabilityLimits = RemoteObservabilityLimits()
) {
    fun overview(records: List<RemoteRunRecord>): RemoteDashboardOverview {
        val summaries = records.map(::summary)
        val succeeded = records.count { it.status == RemoteRunStatus.SUCCEEDED }
        val failed = records.count { it.status == RemoteRunStatus.FAILED }
        val terminal = succeeded + failed
        val completedDurationMillis = records
            .filter { it.status !in ACTIVE_STATUSES }
            .sumOf { elapsedMillis(it) ?: 0L }
        return RemoteDashboardOverview(
            generatedAt = now().toString(),
            stats = RemoteOverviewStats(
                totalRuns = records.size,
                activeRuns = records.count { it.status in ACTIVE_STATUSES },
                succeededRuns = succeeded,
                failedRuns = failed,
                successRate = terminal.takeIf { it > 0 }?.let { succeeded.toDouble() / it },
                completedRunsPerRuntimeHour = completedDurationMillis.takeIf { it > 0 }?.let {
                    terminal * MILLIS_PER_HOUR.toDouble() / it
                },
                completedDurationMillis = completedDurationMillis,
                artifactCount = summaries.sumOf(RemoteRunSummary::artifactCount),
                outputBytes = summaries.sumOf(RemoteRunSummary::outputBytes),
                artifactTotalsPartial = summaries.any(RemoteRunSummary::artifactInventoryTruncated)
            ),
            statusDistribution = RemoteRunStatus.entries.map { status ->
                RemoteDistributionValue(status.name, records.count { it.status == status })
            },
            typeDistribution = records.groupingBy(RemoteRunRecord::type).eachCount()
                .toSortedMap()
                .map { RemoteDistributionValue(it.key, it.value) },
            runs = summaries
        )
    }

    fun publicRecord(record: RemoteRunRecord): RemoteRunRecord = redactRecord(record)

    fun log(record: RemoteRunRecord): RemoteLogTail = logTail(record)

    fun detail(record: RemoteRunRecord): RemoteRunDetail {
        val warnings = mutableListOf<String>()
        val artifactScan = artifacts(record)
        val artifacts = artifactScan.files
        if (artifactScan.truncated) {
            warnings += "Artifact inventory reached the configured limit; counts and bytes are partial."
        }
        val documents = metricDocuments(record, artifacts, warnings)
        val events = events(record)
        val metrics = METRIC_DEFINITIONS.map { definition ->
            definition.extract(documents)
        }.toMutableList()
        deriveTotalTokens(metrics)
        val benchmark = benchmark(documents, warnings)
        val comparison = comparison(documents)
        return RemoteRunDetail(
            generatedAt = now().toString(),
            record = redactRecord(record),
            summary = summary(record, artifactScan, benchmark, events),
            metrics = metrics,
            series = telemetrySeries(record, artifacts, warnings) + activitySeries(events),
            events = events,
            artifacts = artifacts,
            artifactKinds = artifacts.groupingBy(RemoteArtifact::kind).eachCount()
                .toSortedMap()
                .map { RemoteDistributionValue(it.key, it.value) },
            log = logTail(record),
            benchmark = benchmark,
            comparison = comparison,
            warnings = warnings.distinct()
        )
    }

    fun preview(record: RemoteRunRecord, relativePath: String): RemoteArtifactPreview? {
        if (!isDashboardPreviewableOutputPath(relativePath)) return null
        return readAccess.openArtifact(record, relativePath)?.use { opened ->
            preview(relativePath, opened)
        }
    }

    fun preview(relativePath: String, opened: RemoteOpenedOutput): RemoteArtifactPreview? {
        if (!isDashboardPreviewableOutputPath(relativePath)) return null
        val kind = artifactKind(Path.of(relativePath))
        if (kind !in PREVIEWABLE_KINDS) return null
        val read = Channels.newInputStream(opened.channel).readNBytes(limits.maxPreviewBytes + 1)
        val bytes = read.take(limits.maxPreviewBytes).toByteArray()
        val decoded = decodeUtf8(bytes) ?: return null
        val content = when (kind) {
            "json" -> runCatching {
                artifactJson.encodeToString(JsonElement.serializer(), redactJson(artifactJson.parseToJsonElement(decoded)))
            }.getOrElse { decoded }
            "jsonl" -> decoded.lineSequence().joinToString("\n") { line ->
                runCatching {
                    artifactJson.encodeToString(
                        JsonElement.serializer(),
                        redactJson(artifactJson.parseToJsonElement(line))
                    )
                }.getOrElse { "[unparseable JSON line omitted]" }
            }
            else -> decoded
        }
        return RemoteArtifactPreview(
            relativePath = relativePath,
            kind = kind,
            content = content,
            truncated = opened.sizeBytes > bytes.size || read.size > limits.maxPreviewBytes
        )
    }

    private fun summary(record: RemoteRunRecord): RemoteRunSummary {
        val artifactScan = artifacts(record)
        val benchmark = directBenchmark(record, artifactScan.files)
        val events = events(record)
        return summary(record, artifactScan, benchmark, events)
    }

    private fun directBenchmark(
        record: RemoteRunRecord,
        artifacts: List<RemoteArtifact>
    ): RemoteBenchmarkSummary? {
        val artifact = artifacts.firstOrNull {
            it.relativePath.substringAfterLast('/') == BENCHMARK_REPORT_FILE
        } ?: return null
        val document = runCatching {
            JsonDocument(
                artifact.relativePath,
                artifactJson.parseToJsonElement(readArtifactText(record, artifact.relativePath))
            )
        }.getOrNull() ?: return null
        return benchmark(listOf(document), mutableListOf())
    }

    private fun summary(
        record: RemoteRunRecord,
        artifactScan: ArtifactScan,
        benchmark: RemoteBenchmarkSummary?,
        events: List<RemoteRunEvent>
    ): RemoteRunSummary {
        val artifacts = artifactScan.files
        val latestArtifact = artifacts.maxByOrNull(RemoteArtifact::modifiedAt)?.modifiedAt
        return RemoteRunSummary(
            id = record.id,
            type = record.type,
            status = record.status,
            startedAt = record.startedAt,
            completedAt = record.completedAt,
            elapsedMillis = elapsedMillis(record),
            backend = record.launch?.backend,
            runner = record.launch?.runner,
            benchmark = record.launch?.benchmark ?: benchmark?.benchmark,
            fast = record.launch?.fast,
            phase = phase(record, events),
            progressPercent = progress(record, events),
            artifactCount = artifacts.size,
            artifactInventoryTruncated = artifactScan.truncated,
            outputBytes = artifacts.sumOf(RemoteArtifact::sizeBytes),
            primaryScore = benchmark?.accuracy,
            latestActivityAt = listOfNotNull(latestArtifact, events.lastOrNull()?.timestamp, record.completedAt)
                .maxOrNull(),
            error = safeError(record)
        )
    }

    private fun artifacts(record: RemoteRunRecord): ArtifactScan {
        val root = outputRoot(record) ?: return ArtifactScan(emptyList(), false)
        return runCatching {
            Files.walk(root, limits.maxArtifactDepth).use { stream ->
                val visited = stream
                    .limit(limits.maxArtifactEntries + 1)
                    .toList()
                val traversalTruncated = visited.size.toLong() > limits.maxArtifactEntries
                val discovered = visited.asSequence()
                    .take(limits.maxArtifactEntries.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    .filter { it.isRegularFile() }
                    .filter { file -> !Files.isSymbolicLink(file) }
                    .filter { file -> isDashboardVisibleOutputPath(file.relativeTo(root).toPortablePath()) }
                    .mapNotNull { file ->
                        val relativePath = file.relativeTo(root).toPortablePath()
                        readAccess.openArtifact(record, relativePath)?.use { opened ->
                            val modifiedAt = opened.modifiedAt ?: return@use null
                            RemoteArtifact(
                                relativePath = relativePath,
                                sizeBytes = opened.sizeBytes,
                                modifiedAt = modifiedAt.toString(),
                                kind = artifactKind(file),
                                previewable = artifactKind(file) in PREVIEWABLE_KINDS &&
                                    isDashboardPreviewableOutputPath(relativePath),
                                downloadable = isDashboardDownloadableOutputPath(relativePath)
                            )
                        }
                    }
                    .take((limits.maxArtifactFiles + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    .sortedWith(compareBy(RemoteArtifact::relativePath))
                    .toList()
                ArtifactScan(
                    files = discovered.take(limits.maxArtifactFiles.toInt()),
                    truncated = traversalTruncated || discovered.size.toLong() > limits.maxArtifactFiles
                )
            }
        }.getOrDefault(ArtifactScan(emptyList(), false))
    }

    private fun metricDocuments(
        record: RemoteRunRecord,
        artifacts: List<RemoteArtifact>,
        warnings: MutableList<String>
    ): List<JsonDocument> {
        val directDocuments = artifacts
            .asSequence()
            .filter { artifact ->
                artifact.relativePath.substringAfterLast('/') in OBSERVABILITY_JSON_FILES ||
                    artifact.relativePath.substringAfterLast('/').endsWith("-metrics.json")
            }
            .sortedBy { it.relativePath.count { character -> character == '/' } }
            .take(limits.maxMetricDocuments)
            .mapNotNull { artifact ->
                runCatching {
                    JsonDocument(
                        artifact.relativePath,
                        artifactJson.parseToJsonElement(readArtifactText(record, artifact.relativePath))
                    )
                }.getOrElse {
                    warnings += "Could not parse ${artifact.relativePath}; its metrics are unavailable."
                    null
                }
            }
            .toList()
        val archiveDocuments = artifacts
            .asSequence()
            .filter { it.relativePath.substringAfterLast('/') == "results.zip" }
            .take(MAX_RESULTS_ARCHIVES)
            .flatMap { artifact ->
                readAccess.openArtifact(record, artifact.relativePath)
                    ?.use { opened ->
                        readArchiveDocuments(opened, artifact.relativePath, warnings)
                    }
                    .orEmpty()
                    .asSequence()
            }
            .toList()
        return directDocuments + archiveDocuments
    }

    private fun readArchiveDocuments(
        archive: RemoteOpenedOutput,
        archivePath: String,
        warnings: MutableList<String>
    ): List<JsonDocument> = runCatching {
        val documents = linkedMapOf<String, JsonDocument>()
        ZipInputStream(Channels.newInputStream(archive.channel)).use { zip ->
            var visitedEntries = 0
            while (documents.size < ARCHIVE_JSON_FILES.size) {
                val entry = zip.nextEntry ?: break
                visitedEntries += 1
                if (visitedEntries > MAX_ARCHIVE_ENTRIES) {
                    warnings += "$archivePath exceeds the dashboard archive-entry limit."
                    break
                }
                val entryName = entry.name
                if (
                    entry.isDirectory ||
                    entryName !in ARCHIVE_JSON_FILES ||
                    entryName in documents
                ) {
                    zip.closeEntry()
                    continue
                }
                if (entry.size > MAX_METRIC_DOCUMENT_BYTES) {
                    warnings += "$archivePath!/$entryName exceeds the dashboard metric size limit."
                    zip.closeEntry()
                    continue
                }
                val bytes = zip.readNBytes((MAX_METRIC_DOCUMENT_BYTES + 1).toInt())
                if (bytes.size.toLong() > MAX_METRIC_DOCUMENT_BYTES) {
                    warnings += "$archivePath!/$entryName exceeds the dashboard metric size limit."
                    zip.closeEntry()
                    continue
                }
                val text = decodeUtf8(bytes)
                if (text == null) {
                    warnings += "$archivePath!/$entryName is not valid UTF-8."
                    zip.closeEntry()
                    continue
                }
                val element = runCatching { artifactJson.parseToJsonElement(text) }.getOrNull()
                if (element == null) {
                    warnings += "Could not parse $archivePath!/$entryName; its metrics are unavailable."
                    zip.closeEntry()
                    continue
                }
                documents[entryName] = JsonDocument("$archivePath!/$entryName", element)
                zip.closeEntry()
            }
        }
        documents.values.toList()
    }.getOrElse {
        warnings += "Could not inspect $archivePath; archive metrics are unavailable."
        emptyList()
    }

    private fun benchmark(
        documents: List<JsonDocument>,
        warnings: MutableList<String>
    ): RemoteBenchmarkSummary? {
        val document = documents.firstOrNull { it.path.substringAfterLast('/') == BENCHMARK_REPORT_FILE } ?: return null
        val root = document.element as? JsonObject ?: return null
        val results = sequenceOf("results", "tasks", "taskResults")
            .mapNotNull { root[it] as? JsonArray }
            .firstOrNull()
        val tasks = results.orEmpty().take(limits.maxBenchmarkTasks).mapNotNull { element ->
            val task = element as? JsonObject ?: return@mapNotNull null
            val id = task.string("taskId", "id", "questionId", "exampleId") ?: return@mapNotNull null
            RemoteBenchmarkTask(
                taskId = id,
                correct = task.boolean("exactCorrect", "correct", "passed", "success"),
                score = task.number("score", "accuracy", "reward"),
                latencyMillis = task.number("latencyMillis", "durationMillis", "elapsedMillis")?.toLong(),
                error = task.string("error", "failureReason")?.take(MAX_ERROR_CHARS)
            )
        }
        val total = root.integer("executedCount", "total", "totalTasks", "taskCount") ?: results?.size
        val correct = root.integer("exactCorrectCount", "correct", "correctTasks", "passed")
            ?: tasks.mapNotNull(RemoteBenchmarkTask::correct).count { it }.takeIf { tasks.isNotEmpty() }
        val accuracy = root.number("exactAccuracy", "accuracy", "score", "exactMatch")
            ?: if (total != null && correct != null && total > 0) correct.toDouble() / total else null
        if (results != null && tasks.size < minOf(results.size, limits.maxBenchmarkTasks)) {
            warnings += "Some benchmark task rows were omitted because they had no stable task identifier."
        }
        return RemoteBenchmarkSummary(
            benchmark = root.string("benchmark", "benchmarkId", "dataset"),
            runner = root.string("runner", "runnerId", "arm"),
            total = total,
            correct = correct,
            accuracy = accuracy,
            tasks = tasks
        )
    }

    private fun comparison(documents: List<JsonDocument>): RemoteComparisonSummary? {
        val document = documents.firstOrNull {
            it.path.substringAfterLast('/') == COMPARATIVE_REPORT_FILE
        } ?: return null
        val root = document.element as? JsonObject ?: return null
        val arms = (root["armMetrics"] as? JsonArray).orEmpty().mapNotNull { element ->
            val arm = element as? JsonObject ?: return@mapNotNull null
            val armName = arm.string("arm") ?: return@mapNotNull null
            RemoteComparisonArm(
                arm = armName,
                cellCount = arm.integer("cellCount"),
                successCount = arm.integer("successCount"),
                failureCount = arm.integer("failureCount"),
                abstentionCount = arm.integer("abstentionCount"),
                meanQuality = arm.number("meanQuality"),
                meanCostUsd = arm.number("meanCostUsd"),
                meanLatencyMillis = arm.number("meanLatencyMillis")
            )
        }
        if (arms.isEmpty()) return null
        val baseline = arms.firstOrNull { it.arm == COMPARATIVE_BASELINE_ARM }?.arm
            ?: arms.first().arm
        val confidence = root["confidenceByComparator"] as? JsonObject
        val cues = confidence.orEmpty().mapNotNull { (armName, element) ->
            val values = element as? JsonObject ?: return@mapNotNull null
            val difference = values.number("meanDifference")
            RemoteComparisonCue(
                comparatorArm = armName,
                sampleCount = values.integer("sampleCount"),
                meanDifference = difference,
                lower95 = values.number("lower95"),
                upper95 = values.number("upper95"),
                interpretation = when {
                    difference == null -> "NOT_COMPARABLE"
                    difference > COMPARISON_EPSILON -> "ABOVE_BASELINE"
                    difference < -COMPARISON_EPSILON -> "BELOW_BASELINE"
                    else -> "TIED"
                }
            )
        }
        return RemoteComparisonSummary(
            experimentId = root.string("experimentId"),
            baselineArm = baseline,
            arms = arms,
            comparisons = cues
        )
    }

    private fun telemetrySeries(
        record: RemoteRunRecord,
        artifacts: List<RemoteArtifact>,
        warnings: MutableList<String>
    ): List<RemoteMetricSeries> {
        val cost = mutableListOf<RemoteMetricPoint>()
        val tokens = mutableListOf<RemoteMetricPoint>()
        val calls = mutableListOf<RemoteMetricPoint>()
        artifacts.asSequence()
            .filter { it.relativePath.substringAfterLast('/') in TELEMETRY_FILES }
            .take(MAX_TELEMETRY_FILES)
            .forEach { artifact ->
                val text = runCatching {
                    readArtifactText(record, artifact.relativePath, limits.maxEventBytes.toLong())
                }.getOrElse {
                    warnings += "${artifact.relativePath} exceeds the telemetry size limit."
                    return@forEach
                }
                var malformedLines = 0
                text.lineSequence().filter(String::isNotBlank).take(MAX_TELEMETRY_POINTS).forEach { line ->
                    val row = runCatching {
                        artifactJson.parseToJsonElement(line) as? JsonObject
                    }.getOrNull()
                    val timestamp = row?.string("timestamp", "recordedAt", "createdAt")
                    if (row == null || timestamp == null || runCatching { Instant.parse(timestamp) }.isFailure) {
                        malformedLines += 1
                        return@forEach
                    }
                    row.number("totalCostUsd", "cumulativeCostUsd", "costUsd")
                        ?.let { cost += RemoteMetricPoint(timestamp, it) }
                    row.number("totalTokens", "cumulativeTokens", "tokenCount")
                        ?.let { tokens += RemoteMetricPoint(timestamp, it) }
                    row.number("llmCalls", "cumulativeLlmCalls", "requestCount")
                        ?.let { calls += RemoteMetricPoint(timestamp, it) }
                }
                if (malformedLines > 0) {
                    warnings += "${artifact.relativePath} contained $malformedLines malformed telemetry row(s)."
                }
            }
        return listOf(
            RemoteMetricSeries("cost", "Cumulative provider cost", "USD", cost.sortedBy(RemoteMetricPoint::timestamp)),
            RemoteMetricSeries("tokens", "Cumulative tokens", "tokens", tokens.sortedBy(RemoteMetricPoint::timestamp)),
            RemoteMetricSeries("calls", "Cumulative LLM calls", "calls", calls.sortedBy(RemoteMetricPoint::timestamp))
        )
    }

    private fun events(record: RemoteRunRecord): List<RemoteRunEvent> {
        val text = readAccess.openLog(record)?.use { opened ->
            readTail(opened, limits.maxEventBytes)
        } ?: return lifecycleEvents(record)
        val parsed = text.lineSequence().mapNotNull(::eventFromLine).toMutableList()
        if (parsed.none { it.kind == "STARTED" }) {
            parsed.add(0, RemoteRunEvent(record.startedAt, "STARTED", "Run created", "CLI process was queued."))
        }
        if (record.status == RemoteRunStatus.SUCCEEDED) {
            parsed += RemoteRunEvent(record.completedAt, "COMPLETED", "Run completed", "CLI process exited successfully.")
        } else if (record.status == RemoteRunStatus.FAILED) {
            parsed += RemoteRunEvent(
                record.completedAt,
                "FAILED",
                "Run failed",
                safeError(record)?.take(MAX_ERROR_CHARS) ?: "CLI process failed."
            )
        }
        return parsed.takeLast(MAX_EVENTS)
    }

    private fun lifecycleEvents(record: RemoteRunRecord): List<RemoteRunEvent> = buildList {
        add(RemoteRunEvent(record.startedAt, "STARTED", "Run created", "CLI process was queued."))
        if (record.status == RemoteRunStatus.SUCCEEDED) {
            add(RemoteRunEvent(record.completedAt, "COMPLETED", "Run completed", "CLI process exited successfully."))
        }
        if (record.status == RemoteRunStatus.FAILED) {
            add(RemoteRunEvent(record.completedAt, "FAILED", "Run failed", safeError(record) ?: "CLI process failed."))
        }
    }

    private fun eventFromLine(line: String): RemoteRunEvent? {
        val normalized = line.trim()
        if (normalized.isBlank()) return null
        val lower = normalized.lowercase()
        STRUCTURED_PROGRESS_PATTERN.find(normalized)?.let { progress ->
            val phase = progress.groupValues[1].take(MAX_PHASE_CHARS)
            return RemoteRunEvent(
                TIMESTAMP_PATTERN.find(normalized)?.value,
                "PROGRESS",
                phase.replace('_', ' ').replaceFirstChar(Char::uppercase),
                "Engine progress signal received."
            )
        }
        val kind = when {
            "genesis council" in lower -> "GENESIS"
            ROUND_PATTERN.containsMatchIn(normalized) -> "ROUND"
            "check-in" in lower || "check in" in lower -> "CHECK_IN"
            "grading" in lower || "grader" in lower || "scoring" in lower -> "GRADING"
            "final synth" in lower || "final writer" in lower -> "SYNTHESIS"
            "watchdog" in lower -> "WATCHDOG"
            "tool" in lower && ("promot" in lower || "execut" in lower) -> "TOOL"
            "started at" in lower -> "STARTED"
            "run completed" in lower -> "COMPLETED"
            "run aborted" in lower -> "FAILED"
            "benchmark report" in lower -> "BENCHMARK"
            else -> return null
        }
        val timestamp = TIMESTAMP_PATTERN.find(normalized)?.value
        val title = when (kind) {
            "GENESIS" -> "Genesis Council"
            "ROUND" -> ROUND_PATTERN.find(normalized)?.value ?: "Round activity"
            "CHECK_IN" -> "Check-in gate"
            "GRADING" -> "Grading"
            "SYNTHESIS" -> "Final synthesis"
            "WATCHDOG" -> "Watchdog signal"
            "TOOL" -> "Tool activity"
            "COMPLETED" -> "Run completed"
            "FAILED" -> "Run aborted"
            "BENCHMARK" -> "Benchmark report"
            else -> "Run started"
        }
        val detail = when (kind) {
            "ROUND" -> ROUND_PATTERN.find(normalized)?.value ?: "Round lifecycle activity observed."
            "GENESIS" -> "Council initialization activity observed."
            "CHECK_IN" -> "A check-in gate lifecycle signal was recorded."
            "GRADING" -> "Grading or scoring activity was recorded."
            "SYNTHESIS" -> "Final synthesis activity was recorded."
            "WATCHDOG" -> "A Watchdog lifecycle signal was recorded."
            "TOOL" -> "A tool execution or promotion signal was recorded."
            "COMPLETED" -> "The engine reported successful run completion."
            "FAILED" -> "The engine reported an aborted run."
            "BENCHMARK" -> "The benchmark report was persisted."
            else -> "The dashboard wrapper started the CLI process."
        }
        return RemoteRunEvent(timestamp, kind, title, detail.take(MAX_EVENT_DETAIL_CHARS))
    }

    private fun activitySeries(events: List<RemoteRunEvent>): List<RemoteMetricSeries> {
        var cumulative = 0.0
        val points = events.mapNotNull { event ->
            val timestamp = event.timestamp ?: return@mapNotNull null
            cumulative += 1.0
            RemoteMetricPoint(timestamp, cumulative)
        }
        return listOf(RemoteMetricSeries("activity", "Lifecycle activity", "events", points))
    }

    private fun logTail(record: RemoteRunRecord): RemoteLogTail {
        return readAccess.openLog(record)?.use { opened ->
            val size = opened.channel.size()
            val text = readTail(opened, limits.maxLogBytes)
            val allLines = text.lineSequence().filter(String::isNotBlank).toList()
            val boundedLines = allLines.takeLast(limits.maxLogLines)
            val lines = boundedLines.mapNotNull { safeLogLine(record, it) }
            RemoteLogTail(
                lines = lines,
                sizeBytes = size,
                truncated = size > limits.maxLogBytes || allLines.size > limits.maxLogLines,
                withheldLines = boundedLines.size - lines.size
            )
        }
            ?: RemoteLogTail(emptyList(), 0, false, 0)
    }

    private fun safeLogLine(record: RemoteRunRecord, line: String): String? {
        val normalized = line.trim()
        if (normalized.isBlank()) return null
        if (normalized.startsWith("Started at ")) {
            return "Started at ${TIMESTAMP_PATTERN.find(normalized)?.value ?: record.startedAt}"
        }
        if (normalized.startsWith("Command:")) {
            return "Command: ${redactRecord(record).command.joinToString(" ")}"
        }
        if (normalized.startsWith("Launch failed at ")) {
            return "Launch failed: ${safeError(record) ?: "CLI process could not start."}"
        }
        val event = eventFromLine(normalized) ?: return null
        return listOfNotNull(
            event.timestamp,
            "[${event.kind}]",
            event.title,
            event.detail
        ).joinToString(" · ")
    }

    private fun phase(record: RemoteRunRecord, events: List<RemoteRunEvent>): String = when (record.status) {
        RemoteRunStatus.QUEUED -> "Queued"
        RemoteRunStatus.SUCCEEDED -> "Completed"
        RemoteRunStatus.FAILED -> "Failed"
        RemoteRunStatus.RUNNING -> when (events.lastOrNull()?.kind) {
            "GRADING" -> "Grading"
            "SYNTHESIS" -> "Final synthesis"
            "CHECK_IN" -> "Check-in"
            "ROUND" -> "Competition rounds"
            "GENESIS" -> "Genesis Council"
            else -> "Running"
        }
    }

    private fun progress(record: RemoteRunRecord, events: List<RemoteRunEvent>): Double? = when (record.status) {
        RemoteRunStatus.QUEUED -> 0.0
        RemoteRunStatus.SUCCEEDED, RemoteRunStatus.FAILED -> 100.0
        RemoteRunStatus.RUNNING -> {
            val lastRound = events.lastOrNull { it.kind == "ROUND" }
            val match = lastRound?.detail?.let(ROUND_OF_PATTERN::find)
            when {
                events.any { it.kind == "GRADING" } -> 90.0
                events.any { it.kind == "SYNTHESIS" } -> 82.0
                match != null -> {
                    val current = match.groupValues[1].toDoubleOrNull()
                    val total = match.groupValues[2].toDoubleOrNull()
                    if (current != null && total != null && total > 0) 15.0 + (current / total * 65.0) else 35.0
                }
                events.any { it.kind == "GENESIS" } -> 12.0
                else -> null
            }
        }
    }

    private fun elapsedMillis(record: RemoteRunRecord): Long? {
        val start = runCatching { Instant.parse(record.startedAt) }.getOrNull() ?: return null
        val end = record.completedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: now()
        return Duration.between(start, end).toMillis().coerceAtLeast(0)
    }

    private fun redactRecord(record: RemoteRunRecord): RemoteRunRecord = record.copy(
        command = record.command.mapIndexed { index, value ->
            when {
                index == 0 -> runCatching { Path.of(value).fileName?.toString() }.getOrNull() ?: value
                record.command.getOrNull(index - 1) == "-promptfile" -> "runs/${record.id}/input/prompt.txt"
                record.command.getOrNull(index - 1) == "-input" -> "runs/${record.id}/input"
                record.command.getOrNull(index - 1) == "-output" -> "runs/${record.id}/output"
                else -> value
            }
        },
        outputDir = "runs/${record.id}/output",
        logPath = "runs/${record.id}/run.log",
        processId = null,
        processStartedAt = null,
        error = safeError(record)
    )

    private fun safeError(record: RemoteRunRecord): String? {
        var value = record.error ?: return null
        val runRoot = runCatching { Path.of(record.logPath).toAbsolutePath().normalize().parent }.getOrNull()
        if (runRoot != null) value = value.replace(runRoot.toString(), "runs/${record.id}")
        val executable = record.command.firstOrNull()
        if (!executable.isNullOrBlank()) {
            val executableName = runCatching { Path.of(executable).fileName?.toString() }.getOrNull() ?: executable
            value = value.replace(executable, executableName)
        }
        return value.take(MAX_PUBLIC_ERROR_CHARS)
    }

    private fun deriveTotalTokens(metrics: MutableList<RemoteMetric>) {
        val totalIndex = metrics.indexOfFirst { it.id == "llm.tokens.total" }
        if (totalIndex < 0 || metrics[totalIndex].numericValue != null) return
        val input = metrics.firstOrNull { it.id == "llm.tokens.input" }?.numericValue
        val output = metrics.firstOrNull { it.id == "llm.tokens.output" }?.numericValue
        if (input != null || output != null) {
            metrics[totalIndex] = metrics[totalIndex].copy(
                numericValue = (input ?: 0.0) + (output ?: 0.0),
                availability = "AVAILABLE",
                provenance = "Derived from input and output token metrics"
            )
        }
    }

    private fun outputRoot(record: RemoteRunRecord): Path? {
        val root = runCatching { Path.of(record.outputDir).toAbsolutePath().normalize() }.getOrNull() ?: return null
        val parent = root.parent ?: return null
        if (
            !Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(root) ||
            Files.isSymbolicLink(parent)
        ) {
            return null
        }
        return root.takeIf {
            runCatching { root.toRealPath().parent == parent.toRealPath() }.getOrDefault(false)
        }
    }

    private fun readArtifactText(
        record: RemoteRunRecord,
        relativePath: String,
        maximumBytes: Long = MAX_METRIC_DOCUMENT_BYTES
    ): String = readAccess.openArtifact(record, relativePath)?.use { opened ->
        readBoundedText(opened, maximumBytes)
    } ?: error("artifact is not available through descriptor-confined access")

    private fun readBoundedText(
        opened: RemoteOpenedOutput,
        maximumBytes: Long
    ): String {
        require(maximumBytes in 1 until Int.MAX_VALUE) { "invalid bounded-read limit" }
        val limit = maximumBytes.toInt()
        val bytes = Channels.newInputStream(opened.channel).readNBytes(limit + 1)
        require(bytes.size <= limit) { "document exceeds size limit" }
        return decodeUtf8(bytes) ?: error("document is not valid UTF-8")
    }

    private fun readTail(opened: RemoteOpenedOutput, maximumBytes: Int): String {
        val size = opened.channel.size()
        val readSize = minOf(size, maximumBytes.toLong()).toInt()
        val buffer = ByteBuffer.allocate(readSize)
        opened.channel.position((size - readSize).coerceAtLeast(0))
        while (buffer.hasRemaining()) {
            if (opened.channel.read(buffer) < 0) break
        }
        val bytes = buffer.array().copyOf(buffer.position())
        return decodeUtf8(bytes) ?: String(bytes, StandardCharsets.UTF_8)
    }
}

private data class ArtifactScan(val files: List<RemoteArtifact>, val truncated: Boolean)

private data class JsonDocument(val path: String, val element: JsonElement)

private data class MetricDefinition(
    val id: String,
    val label: String,
    val category: String,
    val unit: String?,
    val description: String,
    val aliases: List<String>
) {
    fun extract(documents: List<JsonDocument>): RemoteMetric {
        documents.forEach { document ->
            val match = findAggregateNumber(document.element, aliases)
            if (match != null) {
                val provenance = "${document.path}${match.jsonPointer}"
                if (unit == "ratio" && match.value !in 0.0..1.0) {
                    return RemoteMetric(
                        id = id,
                        label = label,
                        category = category,
                        unit = unit,
                        availability = "INVALID",
                        provenance = provenance,
                        description = description
                    )
                }
                return RemoteMetric(
                    id = id,
                    label = label,
                    category = category,
                    numericValue = match.value,
                    unit = unit,
                    availability = "AVAILABLE",
                    provenance = provenance,
                    description = description
                )
            }
        }
        return RemoteMetric(
            id = id,
            label = label,
            category = category,
            unit = unit,
            availability = "UNAVAILABLE",
            description = description
        )
    }
}

private data class AggregateMetricMatch(
    val value: Double,
    val jsonPointer: String
)

private fun findAggregateNumber(
    element: JsonElement,
    aliases: List<String>
): AggregateMetricMatch? {
    val root = element as? JsonObject ?: return null
    for (containerPath in AGGREGATE_METRIC_CONTAINERS) {
        var current = root
        val actualPath = mutableListOf<String>()
        var resolved = true
        for (normalizedSegment in containerPath) {
            val entry = current.entries.firstOrNull { normalizeKey(it.key) == normalizedSegment }
            val next = entry?.value as? JsonObject
            if (entry == null || next == null) {
                resolved = false
                break
            }
            current = next
            actualPath += entry.key
        }
        if (!resolved) continue
        for (alias in aliases) {
            val entry = current.entries.firstOrNull { normalizeKey(it.key) == alias }
                ?: continue
            val direct = (entry.value as? JsonPrimitive)?.doubleOrNull
            if (direct != null) {
                return AggregateMetricMatch(direct, jsonPointer(actualPath + entry.key))
            }
            val valueObject = entry.value as? JsonObject ?: continue
            val valueEntry = valueObject.entries.firstOrNull {
                normalizeKey(it.key) in AGGREGATE_VALUE_KEYS
            } ?: continue
            val nested = (valueEntry.value as? JsonPrimitive)?.doubleOrNull ?: continue
            return AggregateMetricMatch(
                nested,
                jsonPointer(actualPath + entry.key + valueEntry.key)
            )
        }
    }
    return null
}

private fun sensitiveKey(key: String): Boolean = SENSITIVE_KEY_PARTS.any(key::contains)

private fun normalizeKey(value: String): String = value.lowercase().filter(Char::isLetterOrDigit)

private fun jsonPointer(segments: List<String>): String =
    segments.joinToString(prefix = "#/", separator = "/") { segment ->
        segment.replace("~", "~0").replace("/", "~1")
    }

private fun metric(
    id: String,
    label: String,
    category: String,
    unit: String?,
    description: String,
    vararg aliases: String
) = MetricDefinition(id, label, category, unit, description, aliases.map(::normalizeKey).distinct())

private val METRIC_DEFINITIONS = listOf(
    metric("cost.total_usd", "Total cost", "Cost", "USD", "Estimated or recorded provider cost.", "totalCostUsd", "estimatedCostUsd", "costUsd", "observedBudgetCost", "usdCost", "totalCost"),
    metric("llm.calls", "LLM calls", "LLM", "calls", "Provider request count.", "llmCalls", "requestCount", "totalCalls", "apiCalls", "llmCallEventCount"),
    metric("llm.tokens.input", "Input tokens", "LLM", "tokens", "Prompt and input token count.", "inputTokens", "promptTokens"),
    metric("llm.tokens.output", "Output tokens", "LLM", "tokens", "Generated token count.", "outputTokens", "completionTokens"),
    metric("llm.tokens.total", "Total tokens", "LLM", "tokens", "Total token count.", "totalTokens", "tokenCount", "observedTokenCount"),
    metric("llm.latency_total_ms", "Total LLM latency", "LLM", "ms", "Recorded aggregate provider latency.", "totalLatencyMillis"),
    metric("llm.latency_mean_ms", "Mean LLM latency", "LLM", "ms", "Recorded mean provider latency.", "meanLatencyMillis", "averageLatencyMillis"),
    metric("society.groups", "Groups", "Society", "groups", "Competing group count.", "groups", "groupCount", "totalGroups"),
    metric("society.agents", "Agents", "Society", "agents", "Agent count.", "agents", "agentCount", "totalAgents"),
    metric("society.generations", "Observed generations", "Society", "generations", "Observed generation count.", "generations", "generationCount"),
    metric("society.generation_ceiling", "Configured generation ceiling", "Society", "generations", "Maximum generations configured for the run.", "generationCeiling"),
    metric("society.rounds", "Rounds", "Society", "rounds", "Round count.", "rounds", "roundCount"),
    metric("claims.accepted", "Accepted claims", "Knowledge", "claims", "Claims accepted into canonical knowledge.", "acceptedClaims", "claimsAccepted"),
    metric("claims.rejected", "Rejected claims", "Knowledge", "claims", "Claims rejected by critique or grading.", "rejectedClaims", "claimsRejected"),
    metric("claims.staged", "Staged claims", "Knowledge", "claims", "Claims written to staging.", "stagedClaims", "claimsStaged"),
    metric("quality.evidence_coverage", "Evidence coverage", "Quality", "ratio", "Fraction of required claims supported by evidence.", "evidenceCoverage", "evidencePathCoverage"),
    metric("quality.citation_support", "Citation support", "Quality", "ratio", "Fraction of citations judged supported.", "citationSupport", "citationSupportRate", "citationSupportCoverage"),
    metric("quality.claim_coverage", "Claim coverage", "Quality", "ratio", "Fraction of target claims covered.", "claimCoverage", "supportedClaimRate"),
    metric("quality.evidence_recall", "Evidence recall", "Quality", "ratio", "Archive-derived supported evidence recall.", "evidenceRecall"),
    metric("quality.citation_precision", "Citation precision", "Quality", "ratio", "Archive-derived citation precision.", "citationPrecision"),
    metric("quality.contradiction_handling", "Contradiction handling", "Quality", "score", "Archive-derived contradiction handling evidence.", "contradictionHandling"),
    metric("quality.mandatory_node_coverage", "Mandatory node coverage", "Atlas", "ratio", "Mandatory revealed nodes above threshold.", "mandatoryNodeCoverage"),
    metric("quality.grade", "Grade", "Quality", "score", "Aggregate grader score when released.", "averageGrade", "finalScore", "fitnessScore"),
    metric("tools.executions", "Tool executions", "Tools", "executions", "Executed tool calls.", "toolExecutions", "toolExecutionCount"),
    metric("tools.native_success", "Tool-native success", "Tools", "ratio", "Benchmark tasks completing through the intended native tool path.", "toolNativeSuccessRate"),
    metric("tools.active", "Active tools", "Tools", "tools", "Active promoted tools in the substrate.", "activeTools"),
    metric("knowledge.kg_facts", "Accepted KG facts", "Knowledge", "facts", "Accepted knowledge-graph facts.", "acceptedKgFacts"),
    metric("knowledge.claims", "Observed claims", "Knowledge", "claims", "Claims represented in the archive.", "claimCount"),
    metric("knowledge.supported_claims", "Source-supported claims", "Knowledge", "claims", "Claims with traceable source support.", "sourceSupportedClaimCount"),
    metric("tools.native_agent_rate", "Tool-native agent rate", "Tools", "ratio", "Eligible agents using intended tools.", "toolNativeAgentRate"),
    metric("tools.reuse_factor", "Tool reuse factor", "Tools", "ratio", "Later executions per promoted tool.", "toolReuseFactor"),
    metric("tools.simulation_influence", "Simulation influence", "Tools", "ratio", "Decisions citing executed simulations.", "simulationInfluenceRate"),
    metric("evolution.useful_exit_rate", "Useful exit rate", "Evolution", "ratio", "Accepted useful commits per ordinary mortal exit.", "usefulExitRate"),
    metric("atlas.atomic_completion", "Atomic node completion", "Atlas", "ratio", "Validly closed atomic nodes per attempted node.", "atomicNodeCompletionRate"),
    metric("atlas.frontier_overlap", "Group frontier overlap", "Atlas", "ratio", "Jaccard overlap of group node portfolios.", "groupFrontierOverlap"),
    metric("cost.unplanned_redundancy", "Unplanned redundancy cost", "Cost", "USD", "Cost spent on unplanned duplicate work.", "unplannedRedundancyCost"),
    metric("outliers.acceptance", "Outlier acceptance", "Outliers", "ratio", "Accepted shadow-node proposals.", "outlierProposalAcceptance"),
    metric("outliers.adoption", "Outlier adoption", "Outliers", "ratio", "Accepted proposals tested or adopted downstream.", "outlierDownstreamAdoption"),
    metric("grading.disagreement", "Grader disagreement", "Quality", "score", "Measured disagreement between grader groups.", "graderDisagreement"),
    metric("models.independence", "Model independence", "Society", "ratio", "Independence/diversity of model routing.", "modelIndependence")
)

private fun JsonObject.string(vararg keys: String): String? = keys.asSequence()
    .mapNotNull { this[it] as? JsonPrimitive }
    .mapNotNull(JsonPrimitive::contentOrNull)
    .firstOrNull()

private fun JsonObject.number(vararg keys: String): Double? = keys.asSequence()
    .mapNotNull { this[it] as? JsonPrimitive }
    .mapNotNull(JsonPrimitive::doubleOrNull)
    .firstOrNull()

private fun JsonObject.integer(vararg keys: String): Int? = keys.asSequence()
    .mapNotNull { this[it] as? JsonPrimitive }
    .mapNotNull(JsonPrimitive::intOrNull)
    .firstOrNull()

private fun JsonObject.boolean(vararg keys: String): Boolean? = keys.asSequence()
    .mapNotNull { this[it] as? JsonPrimitive }
    .mapNotNull(JsonPrimitive::booleanOrNull)
    .firstOrNull()

private fun artifactKind(path: Path): String = when (path.extension.lowercase()) {
    "md", "markdown" -> "markdown"
    "json" -> "json"
    "jsonl" -> "jsonl"
    "txt", "log", "csv", "tsv", "yaml", "yml", "xml" -> "text"
    "html", "htm" -> "html"
    "png", "jpg", "jpeg", "gif", "webp", "svg" -> "image"
    "pdf" -> "pdf"
    "zip", "gz", "tar", "tgz" -> "archive"
    "db", "sqlite", "sqlite3" -> "database"
    else -> "binary"
}

private fun Path.toPortablePath(): String = iterator().asSequence().joinToString("/") { it.name }

private fun decodeUtf8(bytes: ByteArray): String? = runCatching {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
}.getOrNull()

private fun redactJson(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.mapValues { (key, value) ->
        when {
            sensitiveKey(normalizeKey(key)) -> JsonPrimitive("[redacted]")
            normalizeKey(key) in PATH_KEYS -> JsonPrimitive("[dashboard-managed path]")
            else -> redactJson(value)
        }
    })
    is JsonArray -> JsonArray(element.map(::redactJson))
    else -> element
}

private val artifactJson = Json { ignoreUnknownKeys = true; isLenient = true }
private val ACTIVE_STATUSES = setOf(RemoteRunStatus.QUEUED, RemoteRunStatus.RUNNING)
private val PREVIEWABLE_KINDS = setOf("markdown", "json", "jsonl", "text")
private val OBSERVABILITY_JSON_FILES = setOf(
    "summary.json",
    "pipeline-summary.json",
    "tier0-benchmark-report.json",
    "comparative-experiment-report.json",
    "comparative-experiment-results.json",
    "metrics.json"
)
private val ARCHIVE_JSON_FILES = listOf("manifest.json", "cost_report.json", "citation_map.json")
private val SENSITIVE_KEY_PARTS = setOf(
    "rawllm",
    "contextwindow",
    "agentcontext",
    "prompt",
    "message",
    "completion",
    "transcript",
    "content",
    "finalanswer",
    "goldanswers",
    "normalizedprediction",
    "objective",
    "claim",
    "supportingpassage"
)
private val AGGREGATE_METRIC_CONTAINERS = listOf(
    emptyList(),
    listOf("metrics"),
    listOf("totals"),
    listOf("usage"),
    listOf("summary"),
    listOf("primarymetrics"),
    listOf("primarymetrics", "substratecompounding")
)
private val AGGREGATE_VALUE_KEYS = setOf("value", "numericvalue")
private val PATH_KEYS = setOf(
    "inputdirectory",
    "outputdirectory",
    "debuglogpath",
    "resultsbundlepath",
    "outputdir",
    "logpath"
)
private val TIMESTAMP_PATTERN = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z""")
private val STRUCTURED_PROGRESS_PATTERN = Regex("""EvoMolior CLI progress phase=([A-Za-z0-9_-]+)""")
private val ROUND_PATTERN = Regex("""(?i)\bround\s+\d+(?:\s+of\s+\d+)?""")
private val ROUND_OF_PATTERN = Regex("""(?i)\bround\s+(\d+)\s+of\s+(\d+)""")
private const val BENCHMARK_REPORT_FILE = "tier0-benchmark-report.json"
private const val COMPARATIVE_REPORT_FILE = "comparative-experiment-report.json"
private const val COMPARATIVE_BASELINE_ARM = "ITERATIVE_SINGLE_AGENT"
private const val COMPARISON_EPSILON = 1e-9
private const val MAX_METRIC_DOCUMENT_BYTES = 8L * 1024 * 1024
private const val MAX_EVENTS = 300
private const val MAX_RESULTS_ARCHIVES = 4
private const val MAX_ARCHIVE_ENTRIES = 10_000
private const val MAX_TELEMETRY_FILES = 4
private const val MAX_TELEMETRY_POINTS = 2_000
private const val MAX_EVENT_DETAIL_CHARS = 320
private const val MAX_PHASE_CHARS = 80
private const val MAX_ERROR_CHARS = 240
private const val MAX_PUBLIC_ERROR_CHARS = 500
private const val MILLIS_PER_HOUR = 3_600_000L
private val TELEMETRY_FILES = setOf("metrics.jsonl", "telemetry.jsonl", "llm-metrics.jsonl")
