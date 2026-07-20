package com.achulkov.evomolior.cli.remote

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteRunObservabilityTest {
    private val now = Instant.parse("2026-07-18T10:00:00Z")

    @Test
    fun overviewAggregatesFleetHealthAndArtifactFootprint() {
        val root = Files.createTempDirectory("dashboard-overview")
        val successfulOutput = root.resolve("successful").also(Files::createDirectories)
        Files.writeString(successfulOutput.resolve("answer.md"), "complete")
        val records = listOf(
            record(
                id = "active",
                status = RemoteRunStatus.RUNNING,
                startedAt = "2026-07-18T09:59:00Z",
                completedAt = null,
                outputDir = root.resolve("active").also(Files::createDirectories),
                launch = RemoteRunLaunch(
                    backend = "qwen-cloud",
                    runner = "evomolior-society",
                    fast = true
                )
            ),
            record(
                id = "successful",
                status = RemoteRunStatus.SUCCEEDED,
                startedAt = "2026-07-18T09:55:00Z",
                completedAt = "2026-07-18T09:57:00Z",
                outputDir = successfulOutput
            ),
            record(
                id = "failed",
                status = RemoteRunStatus.FAILED,
                startedAt = "2026-07-18T09:50:00Z",
                completedAt = "2026-07-18T09:51:00Z",
                outputDir = root.resolve("failed").also(Files::createDirectories)
            )
        )

        val overview = observability().overview(records)

        assertEquals(3, overview.stats.totalRuns)
        assertEquals(1, overview.stats.activeRuns)
        assertEquals(1, overview.stats.succeededRuns)
        assertEquals(1, overview.stats.failedRuns)
        assertEquals(0.5, overview.stats.successRate)
        assertEquals(180_000L, overview.stats.completedDurationMillis)
        assertEquals(40.0, overview.stats.completedRunsPerRuntimeHour)
        assertEquals(1, overview.stats.artifactCount)
        assertEquals(8L, overview.stats.outputBytes)
        assertEquals(60_000L, overview.runs.first { it.id == "active" }.elapsedMillis)
        assertEquals("qwen-cloud", overview.runs.first { it.id == "active" }.backend)
    }

    @Test
    fun detailExtractsAllowlistedMetricsEventsSeriesAndBenchmarkResults() {
        val root = Files.createTempDirectory("dashboard-detail")
        val output = root.resolve("output").also(Files::createDirectories)
        Files.writeString(
            output.resolve("summary.json"),
            """
            {
              "status": "COMPLETED",
              "totalCostUsd": 1.25,
              "llmCalls": 12,
              "inputTokens": 100,
              "outputTokens": 50,
              "groups": 3,
              "generations": 2,
              "acceptedClaims": 7,
              "rejectedClaims": 2,
              "evidenceCoverage": 0.8,
              "agentContext": "must-not-leak",
              "rawLlmCalls": [{"content": "must-not-leak"}]
            }
            """.trimIndent()
        )
        Files.writeString(
            output.resolve("tier0-benchmark-report.json"),
            """
            {
              "benchmark": "frames",
              "runner": "evomolior-society",
              "executedCount": 2,
              "exactCorrectCount": 1,
              "exactAccuracy": 0.5,
              "taskResults": [
                {"taskId": "frames-1", "exactCorrect": true, "latencyMillis": 1200, "finalAnswer": "private answer"},
                {"taskId": "frames-2", "exactCorrect": false, "latencyMillis": 1800, "error": "timeout"}
              ]
            }
            """.trimIndent()
        )
        Files.writeString(
            output.resolve("metrics.jsonl"),
            """
            {"timestamp":"2026-07-18T09:56:00Z","totalCostUsd":0.4,"totalTokens":120,"llmCalls":2}
            {"timestamp":"2026-07-18T09:58:00Z","totalCostUsd":1.25,"totalTokens":150,"llmCalls":12}
            """.trimIndent()
        )
        val log = root.resolve("run.log")
        Files.writeString(
            log,
            """
            2026-07-18T09:55:00Z Genesis Council started
            2026-07-18T09:56:00Z Round 1 of 3 started
            2026-07-18T09:57:00Z Check-in gate 1 opened
            2026-07-18T09:58:00Z Grading final submissions
            """.trimIndent()
        )
        val record = record(
            id = "measured",
            status = RemoteRunStatus.RUNNING,
            startedAt = "2026-07-18T09:55:00Z",
            outputDir = output,
            logPath = log
        )

        val detail = observability().detail(record)
        val metrics = detail.metrics.associateBy { it.id }

        assertEquals(1.25, metrics.getValue("cost.total_usd").numericValue)
        assertEquals(12.0, metrics.getValue("llm.calls").numericValue)
        assertEquals(150.0, metrics.getValue("llm.tokens.total").numericValue)
        assertEquals(3.0, metrics.getValue("society.groups").numericValue)
        assertEquals(0.8, metrics.getValue("quality.evidence_coverage").numericValue)
        assertEquals(0.5, detail.benchmark?.accuracy)
        assertEquals(2, detail.benchmark?.tasks?.size)
        assertEquals("frames-1", detail.benchmark?.tasks?.first()?.taskId)
        assertFalse(detail.benchmark?.tasks?.first()?.toString().orEmpty().contains("private answer"))
        assertTrue(detail.events.any { it.kind == "GENESIS" })
        assertTrue(detail.events.any { it.kind == "ROUND" })
        assertTrue(detail.events.any { it.kind == "CHECK_IN" })
        assertTrue(detail.events.any { it.kind == "GRADING" })
        assertTrue(detail.series.any { it.id == "activity" && it.points.isNotEmpty() })
        assertTrue(detail.artifactKinds.any { it.label == "json" && it.value == 2 })
        assertEquals(2, detail.series.first { it.id == "cost" }.points.size)
        assertEquals(150.0, detail.series.first { it.id == "tokens" }.points.last().value)
        assertEquals(12.0, detail.series.first { it.id == "calls" }.points.last().value)
        assertFalse(detail.toString().contains("must-not-leak"))
        assertFalse(detail.record.outputDir.startsWith("/"))
        assertFalse(detail.record.logPath.startsWith("/"))
    }

    @Test
    fun artifactPreviewIsBoundedTypedAndTraversalSafe() {
        val root = Files.createTempDirectory("dashboard-preview")
        val output = root.resolve("output").also(Files::createDirectories)
        Files.createDirectories(output.resolve("outputs"))
        Files.writeString(output.resolve("outputs/report.md"), "0123456789abcdef")
        Files.write(output.resolve("archive.zip"), byteArrayOf(0, 1, 2, 3))
        val record = record(outputDir = output)
        val observability = observability(RemoteObservabilityLimits(maxPreviewBytes = 10))

        val preview = assertNotNull(observability.preview(record, "outputs/report.md"))

        assertEquals("markdown", preview.kind)
        assertEquals("0123456789", preview.content)
        assertTrue(preview.truncated)
        assertNull(observability.preview(record, "../secret.txt"))
        assertNull(observability.preview(record, "archive.zip"))

        Files.writeString(
            output.resolve("summary.json"),
            """{"prompt":"secret prompt","outputDirectory":"/private/host/path","score":0.9}"""
        )
        val jsonPreview = assertNotNull(observability().preview(record, "summary.json"))
        assertFalse(jsonPreview.content.contains("secret prompt"))
        assertFalse(jsonPreview.content.contains("/private/host/path"))
        assertContains(jsonPreview.content, "[redacted]")
    }

    @Test
    fun metricReadsRemainBoundToTheOpenedDescriptorAfterPathReplacement() {
        val root = Files.createTempDirectory("dashboard-metric-descriptor")
        val runDir = root.resolve("runs/run-1").also(Files::createDirectories)
        val output = runDir.resolve("output").also(Files::createDirectories)
        val summary = output.resolve("summary.json")
        Files.writeString(summary, """{"totalCostUsd":1.25}""")
        val record = record(
            outputDir = output,
            logPath = runDir.resolve("run.log")
        )
        val registry = RemoteRunRegistry(root)
        var summaryOpenCount = 0
        val replacingAccess = object : RemoteRunReadAccess {
            override fun openArtifact(
                record: RemoteRunRecord,
                relativePath: String
            ): RemoteOpenedOutput? {
                val opened = registry.openArtifact(record, relativePath) ?: return null
                if (relativePath == "summary.json" && ++summaryOpenCount == 2) {
                    Files.delete(summary)
                    Files.writeString(summary, """{"totalCostUsd":999.0}""")
                }
                return opened
            }

            override fun openLog(record: RemoteRunRecord): RemoteOpenedOutput? =
                registry.openLog(record)
        }

        val metrics = RemoteRunObservability(
            readAccess = replacingAccess,
            now = { now }
        ).detail(record).metrics.associateBy { it.id }

        assertEquals(1.25, metrics.getValue("cost.total_usd").numericValue)
    }

    @Test
    fun malformedAndMissingArtifactsProduceHonestUnavailableMetrics() {
        val root = Files.createTempDirectory("dashboard-malformed")
        val output = root.resolve("output").also(Files::createDirectories)
        Files.writeString(output.resolve("summary.json"), "{broken")
        val detail = observability().detail(record(outputDir = output))

        assertTrue(detail.metrics.any { it.id == "cost.total_usd" && it.availability == "UNAVAILABLE" })
        assertTrue(detail.warnings.any { it.contains("summary.json") })
        assertNull(detail.benchmark)
    }

    @Test
    fun arbitraryNestedTaskValuesAreNotReportedAsRunAggregates() {
        val root = Files.createTempDirectory("dashboard-nested-metric")
        val output = root.resolve("output").also(Files::createDirectories)
        Files.writeString(
            output.resolve("summary.json"),
            """{"tasks":[{"taskId":"task-1","costUsd":999.0,"totalTokens":1000000}]}"""
        )

        val metrics = observability()
            .detail(record(outputDir = output))
            .metrics
            .associateBy { it.id }

        assertEquals("UNAVAILABLE", metrics.getValue("cost.total_usd").availability)
        assertEquals("UNAVAILABLE", metrics.getValue("llm.tokens.total").availability)
    }

    @Test
    fun resultsArchiveContributesTrustedCostEvidenceAndSubstrateMetrics() {
        val root = Files.createTempDirectory("dashboard-results-archive")
        val output = root.resolve("output").also(Files::createDirectories)
        ZipOutputStream(Files.newOutputStream(output.resolve("results.zip"))).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(
                """
                {"runId":"run-1","groupCount":4,"generationCeiling":3,"artifactCount":8,"archiveEntryCount":12,"entries":[]}
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("cost_report.json"))
            zip.write(
                """
                {
                  "llm_call_event_count": 18,
                  "observed_budget_cost": 2.5,
                  "observed_token_count": 900,
                  "claim_count": 10,
                  "source_supported_claim_count": 8,
                  "primary_metrics": {
                    "evidence_recall": {"value": 0.8, "status": "computed_from_archive"},
                    "substrate_compounding": {
                      "accepted_kg_facts": 6,
                      "active_tools": 2,
                      "tool_execution_count": 9,
                      "promoted_submissions": 5
                    }
                  }
                }
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()
        }

        val metrics = observability()
            .detail(record(outputDir = output))
            .metrics
            .associateBy { it.id }

        assertEquals(2.5, metrics.getValue("cost.total_usd").numericValue)
        assertEquals(18.0, metrics.getValue("llm.calls").numericValue)
        assertEquals(900.0, metrics.getValue("llm.tokens.total").numericValue)
        assertEquals(4.0, metrics.getValue("society.groups").numericValue)
        assertEquals(3.0, metrics.getValue("society.generation_ceiling").numericValue)
        assertEquals(0.8, metrics.getValue("quality.evidence_recall").numericValue)
        assertEquals(6.0, metrics.getValue("knowledge.kg_facts").numericValue)
        assertEquals(2.0, metrics.getValue("tools.active").numericValue)
        assertEquals(
            "results.zip!/cost_report.json#/observed_budget_cost",
            metrics.getValue("cost.total_usd").provenance
        )
    }

    @Test
    fun logTailIsBoundedToConfiguredLineAndByteLimits() {
        val root = Files.createTempDirectory("dashboard-log-tail")
        val log = root.resolve("run.log")
        Files.writeString(
            log,
            buildString {
                appendLine("objective: must-not-leak")
                (1..20).forEach { appendLine("2026-07-18T09:59:00Z Round $it of 20 started") }
            }
        )
        val observability = observability(
            RemoteObservabilityLimits(maxLogLines = 3, maxLogBytes = 4_096)
        )

        val tail = observability.detail(record(outputDir = root, logPath = log)).log

        assertEquals(3, tail.lines.size)
        assertContains(tail.lines.last(), "Round 20 of 20")
        assertTrue(tail.truncated)
        assertFalse(tail.lines.joinToString().contains("must-not-leak"))

        val privacyTail = observability(
            RemoteObservabilityLimits(maxLogLines = 30, maxLogBytes = 4_096)
        ).detail(record(outputDir = root, logPath = log)).log
        assertEquals(1, privacyTail.withheldLines)
        assertFalse(privacyTail.lines.joinToString().contains("must-not-leak"))
    }

    @Test
    fun comparativeReportProducesExplicitBaselineCues() {
        val root = Files.createTempDirectory("dashboard-comparison")
        val output = root.resolve("output").also(Files::createDirectories)
        Files.writeString(
            output.resolve("comparative-experiment-report.json"),
            """
            {
              "experimentId": "matched-1",
              "armMetrics": [
                {"arm":"ITERATIVE_SINGLE_AGENT","cellCount":4,"successCount":4,"failureCount":0,"abstentionCount":0,"meanQuality":0.7,"meanCostUsd":1.0,"meanLatencyMillis":1000},
                {"arm":"FULL_SOCIETY","cellCount":4,"successCount":4,"failureCount":0,"abstentionCount":0,"meanQuality":0.8,"meanCostUsd":1.1,"meanLatencyMillis":1200}
              ],
              "confidenceByComparator": {
                "FULL_SOCIETY":{"sampleCount":4,"meanDifference":0.1,"lower95":0.02,"upper95":0.18}
              }
            }
            """.trimIndent()
        )

        val comparison = assertNotNull(
            observability().detail(record(outputDir = output)).comparison
        )

        assertEquals("ITERATIVE_SINGLE_AGENT", comparison.baselineArm)
        assertEquals(2, comparison.arms.size)
        assertEquals("ABOVE_BASELINE", comparison.comparisons.single().interpretation)
    }

    @Test
    fun boundedArtifactInventoryIsMarkedPartial() {
        val root = Files.createTempDirectory("dashboard-artifact-limit")
        val output = root.resolve("output").also(Files::createDirectories)
        repeat(3) { Files.writeString(output.resolve("artifact-$it.md"), "$it") }

        val summary = observability(
            RemoteObservabilityLimits(maxArtifactFiles = 2)
        ).detail(record(outputDir = output)).summary

        assertEquals(2, summary.artifactCount)
        assertTrue(summary.artifactInventoryTruncated)
    }

    @Test
    fun artifactTraversalBudgetCountsRejectedEntriesBeforeFiltering() {
        val root = Files.createTempDirectory("dashboard-rejected-artifact-budget")
        val output = root.resolve("output").also(Files::createDirectories)
        val privateRoot = output.resolve(".evomolior-data").also(Files::createDirectories)
        repeat(25) { index ->
            val directory = privateRoot.resolve("private-$index").also(Files::createDirectories)
            Files.writeString(directory.resolve("substrate.db"), "private")
        }
        val detail = observability(
            RemoteObservabilityLimits(
                maxArtifactFiles = 100,
                maxArtifactEntries = 5
            )
        ).detail(record(outputDir = output))

        assertTrue(detail.summary.artifactInventoryTruncated)
        assertTrue(detail.artifacts.size <= 5)
        assertTrue(detail.artifacts.none { it.relativePath.contains(".evomolior-data") })
    }

    @Test
    fun oversizedTelemetryIsRejectedWithoutAnUnboundedRead() {
        val root = Files.createTempDirectory("dashboard-telemetry-limit")
        val output = root.resolve("output").also(Files::createDirectories)
        Files.writeString(
            output.resolve("metrics.jsonl"),
            """{"timestamp":"2026-07-18T09:56:00Z","totalCostUsd":123456789.0}"""
        )

        val detail = observability(
            RemoteObservabilityLimits(maxEventBytes = 24)
        ).detail(record(outputDir = output))

        assertTrue(detail.series.first { it.id == "cost" }.points.isEmpty())
        assertTrue(detail.warnings.any { it.contains("telemetry size limit") })
    }

    private val readAccess = object : RemoteRunReadAccess {
        override fun openArtifact(
            record: RemoteRunRecord,
            relativePath: String
        ): RemoteOpenedOutput? {
            if (!isDashboardVisibleOutputPath(relativePath)) return null
            val root = Path.of(record.outputDir).toAbsolutePath().normalize()
            val candidate = root.resolve(relativePath).toAbsolutePath().normalize()
            if (!candidate.startsWith(root)) return null
            return open(candidate)
        }

        override fun openLog(record: RemoteRunRecord): RemoteOpenedOutput? =
            open(Path.of(record.logPath).toAbsolutePath().normalize())

        private fun open(path: Path): RemoteOpenedOutput? {
            val before = runCatching {
                Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            }.getOrNull() ?: return null
            if (!before.isRegularFile || Files.isSymbolicLink(path)) return null
            val channel = runCatching {
                Files.newByteChannel(
                    path,
                    setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
                )
            }.getOrNull() ?: return null
            return runCatching {
                val after = Files.readAttributes(
                    path,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS
                )
                require(after.isRegularFile && before.fileKey() == after.fileKey())
                RemoteOpenedOutput(
                    fileName = path.fileName.toString(),
                    sizeBytes = after.size(),
                    channel = channel,
                    modifiedAt = after.lastModifiedTime().toInstant()
                )
            }.getOrElse {
                channel.close()
                null
            }
        }
    }

    private fun observability(
        limits: RemoteObservabilityLimits = RemoteObservabilityLimits()
    ): RemoteRunObservability = RemoteRunObservability(
        readAccess = readAccess,
        now = { now },
        limits = limits
    )

    private fun record(
        id: String = "run-1",
        status: RemoteRunStatus = RemoteRunStatus.SUCCEEDED,
        startedAt: String = "2026-07-18T09:00:00Z",
        completedAt: String? = "2026-07-18T09:01:00Z",
        outputDir: java.nio.file.Path,
        logPath: java.nio.file.Path = outputDir.resolveSibling("run.log"),
        launch: RemoteRunLaunch? = null
    ): RemoteRunRecord = RemoteRunRecord(
        id = id,
        type = "run",
        status = status,
        startedAt = startedAt,
        completedAt = completedAt,
        command = listOf("evomolior", "run"),
        outputDir = outputDir.toString(),
        logPath = logPath.toString(),
        launch = launch
    )
}
