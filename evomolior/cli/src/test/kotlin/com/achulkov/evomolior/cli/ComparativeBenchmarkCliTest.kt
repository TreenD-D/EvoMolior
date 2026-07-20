@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.cli

import com.achulkov.evomolior.core.benchmark.ComparativeArmDefinition
import com.achulkov.evomolior.core.benchmark.ComparativeBenchmarkArm
import com.achulkov.evomolior.core.benchmark.ComparativeBenchmarkHashing
import com.achulkov.evomolior.core.benchmark.ComparativeConstraintMode
import com.achulkov.evomolior.core.benchmark.ComparativeConstraintPolicy
import com.achulkov.evomolior.core.benchmark.ComparativeEvaluatorContract
import com.achulkov.evomolior.core.benchmark.ComparativeExperimentPlan
import com.achulkov.evomolior.core.benchmark.ComparativeInputCommitments
import com.achulkov.evomolior.core.benchmark.ComparativeTaskCommitment
import com.achulkov.evomolior.core.benchmark.Tier0BenchmarkKind
import com.achulkov.evomolior.core.benchmark.Tier0BenchmarkTask
import com.achulkov.evomolior.core.config.LlmBackend
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class ComparativeBenchmarkCliTest {
    @Test
    fun `parser requires matched commitments seeds and explicit resource policy`() {
        val output = createTempDirectory("comparative-parser-")
        val options = ComparativeBenchmarkCliArguments.parse(
            arrayOf(
                "-benchmark", "frames",
                "-limit", "1",
                "-output", output.toString(),
                "-qwen-api-key", "test-key",
                "-experiment-id", "comparison-1",
                "-seeds", "11,22",
                "-constraint", "iso-cost",
                "-max-cost-usd", "5.0",
                "-source-bundle-hash", "sources",
                "-tool-bundle-hash", "tools",
                "-atlas-hash", "atlas",
                "-output-contract-hash", "output",
                "-worker-model-family", "qwen",
                "-evaluator-contract-hash", "evaluator",
                "-evaluator-model-family", "independent-family"
            )
        )!!

        assertEquals("comparison-1", options.experimentId)
        assertEquals(listOf(11L, 22L), options.seeds)
        assertEquals(ComparativeConstraintMode.ISO_COST, options.constraintPolicy.mode)
        assertEquals(LlmBackend.QWEN_CLOUD, options.base.llmBackend)
        assertEquals("atlas", options.commitments.atlasHash)

        assertFailsWith<CliUsageException> {
            ComparativeBenchmarkCliArguments.parse(
                arrayOf(
                    "-benchmark", "frames",
                    "-limit", "1",
                    "-output", output.toString(),
                    "-qwen-api-key", "test-key",
                    "-constraint", "iso-cost"
                )
            )
        }
    }

    @Test
    fun `runner executes explicit single one-group and full-society adapters`() =
        kotlinx.coroutines.test.runTest {
            val output = createTempDirectory("comparative-runner-")
            val task = Tier0BenchmarkTask(
                id = "task-a",
                benchmark = Tier0BenchmarkKind.FRAMES,
                question = "What is the answer?",
                acceptedAnswers = listOf("answer")
            )
            val baseOptions = BenchmarkCliOptions(
                benchmark = Tier0BenchmarkKind.FRAMES,
                scholarQaBenchVariant = ScholarQaBenchVariant.QASA,
                limit = 1,
                offset = 0,
                outputDir = output,
                apiKey = "test-key",
                llmBackend = LlmBackend.QWEN_CLOUD,
                fast = true,
                groupCount = 2,
                rounds = 1,
                roundDurationMinutes = 1,
                maxAgentsPerGroup = 1,
                budgetOverride = 20.0,
                taskTimeoutMinutes = 1,
                costOverrideUsdPerTask = 1.0,
                tasksJsonl = null,
                modelOverrides = emptyMap()
            )
            val calls = mutableListOf<Triple<BenchmarkCliRunnerKind, Int, String>>()
            val executor = ComparativeBenchmarkTaskExecutor { options, _, runId, directory ->
                assertTrue(options.comparativePlanHash?.isNotBlank() == true)
                assertTrue(options.comparativeSeed in setOf(11L, 22L))
                assertEquals("atlas", options.comparativeCommitments?.atlasHash)
                assertTrue(
                    options.modelOverrides.values.all {
                        it == com.achulkov.evomolior.core.config.ModelSelectionConfig
                            .QWEN_CLOUD_QWEN_PLUS_MODEL
                    }
                )
                calls += Triple(options.runner, options.groupCount, directory.toString())
                val society = options.runner == BenchmarkCliRunnerKind.EVOMOLIOR_SOCIETY
                BenchmarkCliTaskResult(
                    taskId = task.id,
                    runId = runId,
                    status = "COMPLETED",
                    outputDirectory = directory.toString(),
                    finalAnswer = "answer",
                    goldAnswers = task.acceptedAnswers,
                    exactCorrect = true,
                    evidencePathPresent = true,
                    normalizedPrediction = "answer",
                    matchedGoldAnswer = "answer",
                    totalCost = 1.0,
                    finalDeliverableReferenceId = if (society) "reference-$runId" else null,
                    finalDeliverableReferenceHash = if (society) "reference-hash-$runId" else null
                )
            }
            val adapters = listOf(
                IterativeSingleAgentBenchmarkAdapter(baseOptions, executor),
                OneGroupSocietyBenchmarkAdapter(baseOptions, executor),
                FullSocietyBenchmarkAdapter(baseOptions, executor)
            ).associateBy(ComparativeBenchmarkArmAdapter::arm)
            val plan = plan(task)

            val execution = ComparativeBenchmarkCliRunner(
                adapters,
                FixedClock(Instant.parse("2026-07-12T12:00:00Z"))
            ).run(plan, listOf(task), output)

            assertEquals(6, execution.results.size)
            assertEquals(2, calls.count { it.first == BenchmarkCliRunnerKind.SINGLE_AGENT })
            assertEquals(
                2,
                calls.count {
                    it.first == BenchmarkCliRunnerKind.EVOMOLIOR_SOCIETY && it.second == 1
                }
            )
            assertEquals(
                2,
                calls.count {
                    it.first == BenchmarkCliRunnerKind.EVOMOLIOR_SOCIETY && it.second == 2
                }
            )
            assertEquals(6, calls.map { it.third }.distinct().size)
            assertTrue(
                calls.all { (_, _, path) ->
                    ComparativeBenchmarkArm.entries.any { path.endsWith(it.id) }
                }
            )
            assertTrue(Files.isRegularFile(output.resolve("comparative-experiment-plan.json")))
            assertTrue(Files.isRegularFile(output.resolve("comparative-experiment-results.json")))
            assertTrue(Files.isRegularFile(output.resolve("comparative-experiment-report.json")))
            assertTrue(execution.report.hasValidHash())
        }

    private fun plan(task: Tier0BenchmarkTask): ComparativeExperimentPlan =
        ComparativeExperimentPlan(
            experimentId = "comparison-1",
            experimentVersion = 1,
            benchmarkId = task.benchmark.name,
            benchmarkVersion = 1,
            tasks = listOf(
                ComparativeTaskCommitment(
                    task.id,
                    ComparativeBenchmarkHashing.hashTask(task)
                )
            ),
            seeds = listOf(11L, 22L),
            arms = listOf(
                ComparativeArmDefinition(
                    ComparativeBenchmarkArm.ITERATIVE_SINGLE_AGENT,
                    0,
                    0,
                    "qwen"
                ),
                ComparativeArmDefinition(
                    ComparativeBenchmarkArm.ONE_GROUP_SOCIETY,
                    1,
                    1,
                    "qwen"
                ),
                ComparativeArmDefinition(
                    ComparativeBenchmarkArm.FULL_SOCIETY,
                    2,
                    2,
                    "qwen"
                )
            ),
            commitments = ComparativeInputCommitments(
                "sources",
                "tools",
                "atlas",
                "output",
                "qwen"
            ),
            constraintPolicy = ComparativeConstraintPolicy(
                ComparativeConstraintMode.ISO_COST,
                maximumCostUsd = 5.0
            ),
            evaluator = ComparativeEvaluatorContract(
                "evaluator",
                1,
                "evaluator-hash",
                "independent-family",
                independent = true,
                calibrated = true
            ),
            artifactRoot = "matched-comparison-1",
            createdAt = Instant.parse("2026-07-12T11:00:00Z")
        ).withComputedHash()

    private class FixedClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }
}
