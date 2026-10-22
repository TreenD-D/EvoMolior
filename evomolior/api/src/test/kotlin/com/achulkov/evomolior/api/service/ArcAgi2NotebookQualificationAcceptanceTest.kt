@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.agents.tools.ExecuteToolTool
import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.PhysicalDimension
import com.achulkov.evomolior.core.atlas.ToolBehavior
import com.achulkov.evomolior.core.atlas.ToolImplementationMode
import com.achulkov.evomolior.core.atlas.ToolOpportunity
import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitment
import com.achulkov.evomolior.core.atlas.ToolOpportunityType
import com.achulkov.evomolior.core.atlas.TypedField
import com.achulkov.evomolior.core.atlas.TypedFieldType
import com.achulkov.evomolior.core.atlas.TypedToolSchemaCompiler
import com.achulkov.evomolior.core.budget.BudgetConfig
import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.contract.WorkerToolCard
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.repair.AggregationSemantics
import com.achulkov.evomolior.core.repair.QuantitySpec
import com.achulkov.evomolior.core.repair.TimeBasis
import com.achulkov.evomolior.orchestration.lifecycle.SealedPromotionTestFixture
import com.achulkov.evomolior.orchestration.lifecycle.ToolPromotionResult
import com.achulkov.evomolior.orchestration.lifecycle.ToolPromotionService
import com.achulkov.evomolior.sandbox.toolsandbox.ToolSubSandbox
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.tools.ToolInfrastructureRepository
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Executes only checked-in trusted qualification fixtures.
 *
 * Provider-authored notebook bytes remain static publication inputs and are not host-executed by
 * this test or by [FinalOutputContractValidator].
 */
class ArcAgi2NotebookQualificationAcceptanceTest {
    @Test
    fun `sealed audit fixtures and extracted notebook execute in bounded sandboxes`() = runTest {
        val root = createTempDirectory("arc-agi2-notebook-qualification-").toFile()
        val runId = "arc-agi2-notebook-qualification"
        val groupId = "group-arc"
        val originNodeId = NodeId("node-arc-audit")
        val reuseNodeId = NodeId("node-arc-audit-reuse")
        val toolsRoot = File(root, "tools")
        val database = SubstrateDatabaseFactory(root).get(runId, groupId)
        try {
            val commitment = commitment(runId, originNodeId, reuseNodeId)
            val submission = submission(runId, groupId, originNodeId, commitment)
            val privateFixtures = ArcAgi2NotebookQualificationAuthority.promotionFixtures.map {
                SealedPromotionTestFixture(
                    id = it.testId,
                    input = it.input,
                    expectedOutput = it.expectedOutput
                )
            }
            val promoted = assertIs<ToolPromotionResult.Promoted>(
                ToolPromotionService(
                    database = database,
                    runId = runId,
                    groupId = groupId,
                    toolsMountHostPath = toolsRoot.absolutePath,
                    opportunityResolver = { commitment },
                    sealedPromotionTestResolver = { privateFixtures }
                ).promote(submission, "critic-arc-audit")
            )

            val repository = ToolInfrastructureRepository(database)
            val promotionExecutions = repository.executionsForGroupThroughRound(
                runId = runId,
                groupId = groupId,
                maximumRound = 1
            )
            assertEquals(4, promotionExecutions.size)
            assertTrue(promotionExecutions.all { it.durationMillis <= 8_000L })
            assertTrue(promotionExecutions.all { it.result.name == "SUCCEEDED" })

            val registry = requireNotNull(database.toolRegistryDao().getById(promoted.toolVersionId))
            val laterExecutionId = "arc-audit-later-execution"
            val laterResponse = ExecuteToolTool(
                toolRegistryDao = database.toolRegistryDao(),
                toolSubSandbox = ToolSubSandbox(
                    workspaceDir = File(root, "later-audit-workspace"),
                    timeoutMs = 8_000L
                ),
                budgetLedger = BudgetLedger(
                    agentId = "agent-arc-later",
                    initialBudget = 100.0,
                    floor = 1.0,
                    config = BudgetConfig(
                        groupId,
                        GroupProfile.default(GroupProfileType.Factory)
                    )
                ),
                runId = runId,
                groupId = groupId,
                toolsMountPath = toolsRoot.absolutePath,
                agentId = "agent-arc-later",
                assignedNodeId = reuseNodeId,
                availableToolCards = listOf(
                    WorkerToolCard(
                        toolId = registry.id,
                        name = registry.name,
                        version = registry.version,
                        opportunityId = commitment.opportunity.opportunityId,
                        opportunityCommitmentHash = commitment.commitmentHash,
                        requiredForNodeId = reuseNodeId,
                        description = commitment.opportunity.repeatedQuestionPattern,
                        inputSchemaHash = AtlasHashing.hashText(registry.inputSchemaJson),
                        outputSchemaHash = AtlasHashing.hashText(registry.outputSchemaJson)
                    )
                ),
                round = 2,
                infrastructureRepository = repository,
                executionIdFactory = { laterExecutionId }
            ).execute(
                ExecuteToolTool.Args(
                    toolName = registry.name,
                    toolVersion = registry.version,
                    argsJson = ArcAgi2NotebookQualificationAuthority.promotionFixtures
                        .single { it.testId == "rate_denominator_boundary" }
                        .input
                        .toString()
                )
            )
            assertTrue(laterResponse.contains("Status: SUCCESS"))
            assertEquals(
                ArcAgi2NotebookQualificationAuthority.promotionFixtures
                    .single { it.testId == "rate_denominator_boundary" }
                    .expectedOutput,
                requireNotNull(repository.execution(laterExecutionId)).outputJson
            )

            executeExtractedNotebook(root)
        } finally {
            database.close()
        }
    }

    private suspend fun executeExtractedNotebook(root: File) {
        val notebook = notebookSource()
        val validationErrors = FinalOutputContractValidator.validate(
            output = GeneratedOutputFile(
                relativePath = "submission.ipynb",
                mediaType = "Application/X-IPYNB+JSON; charset=utf-8",
                content = notebook
            ),
            outputSpec = FinalOutputSpec(
                relativePath = "submission.ipynb",
                mediaType = "application/x-ipynb+json",
                validation =
                    ArcAgi2NotebookQualificationAuthority.notebookValidationContract
            )
        )
        assertTrue(validationErrors.isEmpty(), validationErrors.joinToString())

        val rootObject = Json.parseToJsonElement(notebook).jsonObject
        val python = rootObject["cells"]!!.jsonArray
            .map { it.jsonObject }
            .filter { (it["cell_type"] as? JsonPrimitive)?.content == "code" }
            .joinToString("\n") { cell ->
                when (val source = cell["source"]) {
                    is JsonPrimitive -> source.content
                    is JsonArray -> source.joinToString("") { (it as JsonPrimitive).content }
                    else -> error("Invalid fixture notebook source")
                }
            }
        val script = File(root, "submission-notebook.py")
        Files.writeString(script.toPath(), python)
        val runner = File(root, "submission-notebook")
        Files.writeString(
            runner.toPath(),
            "#!/bin/sh\nexec python3 ${shellQuote(script.absolutePath)}\n"
        )
        assertTrue(runner.setExecutable(true))

        val sandboxRoot = File(root, "notebook-sandbox")
        val workdir = File(sandboxRoot, runner.nameWithoutExtension).apply { mkdirs() }
        File(workdir, "arc-agi_test_challenges.json").writeText(
            """
            {
              "geometry": {
                "train": [{"input":[[1,2],[3,4]],"output":[[2,1],[4,3]]}],
                "test": [{"input":[[5,6],[7,8]],"expected_output":[[6,5],[8,7]]}]
              },
              "object_relational": {
                "train": [{
                  "input":[[0,0,0],[0,3,0],[0,0,0]],
                  "output":[[3]]
                }],
                "test": [{
                  "input":[[0,0,0,0],[0,4,4,0],[0,4,4,0],[0,0,0,0]],
                  "expected_output":[[4,4],[4,4]]
                }]
              },
              "color_layout": {
                "train": [{"input":[[1,0],[0,1]],"output":[[2,0],[0,2]]}],
                "test": [{"input":[[1,1],[0,1]],"expected_output":[[2,2],[0,2]]}]
              },
              "fallback": {
                "train": [{"input":[[5,0],[0,5]],"output":[[5,0],[0,5]]}],
                "test": [{"input":[[6,0],[0,6]],"expected_output":[[6,0],[0,6]]}]
              }
            }
            """.trimIndent()
        )
        File(workdir, "sample_submission.json").writeText(
            """
            {
              "geometry":[{"attempt_1":[[0]],"attempt_2":[[0]]}],
              "object_relational":[{"attempt_1":[[0]],"attempt_2":[[0]]}],
              "color_layout":[{"attempt_1":[[0]],"attempt_2":[[0]]}],
              "fallback":[{"attempt_1":[[0]],"attempt_2":[[0]]}]
            }
            """.trimIndent()
        )

        val started = System.nanoTime()
        val result = ToolSubSandbox(
            workspaceDir = sandboxRoot,
            timeoutMs = 8_000L
        ).execute(runner.absolutePath, "{}")
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000L

        assertFalse(result.timedOut, result.stderr)
        assertEquals(0, result.exitCode, result.stderr)
        assertTrue(elapsedMillis <= 8_000L, "Notebook smoke took ${elapsedMillis}ms")
        val submission = Json.parseToJsonElement(result.stdout).jsonObject
        val expectedAttemptOne = mapOf(
            "geometry" to Json.parseToJsonElement("[[6,5],[8,7]]"),
            "object_relational" to Json.parseToJsonElement("[[4,4],[4,4]]"),
            "color_layout" to Json.parseToJsonElement("[[2,2],[0,2]]"),
            "fallback" to Json.parseToJsonElement("[[6,0],[0,6]]")
        )
        assertEquals(expectedAttemptOne.keys, submission.keys)
        submission.forEach { (key, value) ->
            value.jsonArray.forEach { row ->
                val attempts = row.jsonObject
                assertEquals(setOf("attempt_1", "attempt_2"), attempts.keys)
                assertEquals(expectedAttemptOne.getValue(key), attempts["attempt_1"])
                assertTrue(attempts.values.all(::isArcGrid))
            }
        }
        assertTrue(File(workdir, "submission.json").isFile)
        assertEquals(
            submission,
            Json.parseToJsonElement(File(workdir, "submission.json").readText())
        )
    }

    private fun commitment(
        runId: String,
        originNodeId: NodeId,
        reuseNodeId: NodeId
    ): ToolOpportunityCommitment = ToolOpportunityCommitment(
        runId = runId,
        atlasHash = "a".repeat(64),
        nodeId = originNodeId,
        opportunity = ToolOpportunity(
            opportunityId = ArcAgi2NotebookQualificationAuthority.OPPORTUNITY_ID,
            opportunityType = ToolOpportunityType.VALIDATION,
            repeatedQuestionPattern =
                "Audit one bounded ARC candidate portfolio and select two attempts.",
            requiredInputs = inputFields(),
            requiredOutputs = outputFields(),
            validationStrategy = "Execute all exact operator-owned ARC audit fixtures.",
            expectedReuseNodeIds = setOf(reuseNodeId),
            preferredImplementationMode = ToolImplementationMode.PYTHON,
            behavior = ToolBehavior.DETERMINISTIC,
            requiredCapabilities = setOf("python"),
            promotionTestIds = ArcAgi2NotebookQualificationAuthority.promotionTestIds,
            maximumRuntimeMillis =
                ArcAgi2NotebookQualificationAuthority.MAXIMUM_RUNTIME_MILLIS,
            maximumOutputBytes = 32_768,
            assumptions = listOf("Counts were measured from one bounded symbolic search."),
            knownInvalidDomains = listOf("Negative counts or inconsistent interval lengths.")
        )
    ).withComputedHash()

    private fun submission(
        runId: String,
        groupId: String,
        originNodeId: NodeId,
        commitment: ToolOpportunityCommitment
    ): StagingSubmissionEntity {
        val tests = ArcAgi2NotebookQualificationAuthority.promotionFixtures.joinToString(",") {
            """
            {
              "testId":${JsonPrimitive(it.testId)},
              "input":${it.input},
              "expectedOutput":${it.expectedOutput}
            }
            """.trimIndent()
        }
        return StagingSubmissionEntity(
            id = "submission-arc-audit",
            runId = runId,
            groupId = groupId,
            agentId = "agent-arc-builder",
            role = "Builder",
            type = "TOOL",
            content = """
                {
                  "opportunityId":"${commitment.opportunity.opportunityId}",
                  "name":"arc_candidate_portfolio_audit",
                  "domain":"validation",
                  "language":"python",
                  "inputSchema":${TypedToolSchemaCompiler.compile(inputFields())},
                  "outputSchema":${TypedToolSchemaCompiler.compile(outputFields())},
                  "code":${JsonPrimitive(auditToolSource())},
                  "tests":[$tests]
                }
            """.trimIndent(),
            wipTaskRef = originNodeId.value,
            budgetSignature = "budget-signature",
            generation = 1,
            submittedAtMs = 1L
        )
    }

    private fun inputFields(): List<TypedField> = listOf(
        countScalar("training_pair_count"),
        TypedField(
            name = "search_interval_seconds",
            type = TypedFieldType.NUMBER,
            description = "Seconds represented by one bounded search interval.",
            physicalDimension = PhysicalDimension(mapOf("time" to 1)),
            quantitySpec = QuantitySpec(
                dimension = PhysicalDimension(mapOf("time" to 1)),
                unitId = "s",
                scaleToSi = 1.0,
                aggregation = AggregationSemantics.INSTANTANEOUS
            )
        ),
        countSeries("candidate_generated_per_interval"),
        countSeries("candidate_exact_fit_per_interval"),
        countSeries("candidate_duplicate_output_per_interval"),
        countSeries("candidate_distinct_output_per_interval"),
        countSeries("candidate_distinct_family_per_interval")
    )

    private fun outputFields(): List<TypedField> = listOf(
        percentageSeries("interval_exact_fit_rate_percent"),
        percentageSeries("interval_family_diversity_percent"),
        countScalar("total_exact_fit_candidates"),
        countScalar("selected_attempt_count"),
        countScalar("selected_distinct_output_count"),
        TypedField(
            name = "selection_runtime_seconds",
            type = TypedFieldType.NUMBER,
            description = "Bounded audit horizon in seconds.",
            physicalDimension = PhysicalDimension(mapOf("time" to 1)),
            quantitySpec = QuantitySpec(
                dimension = PhysicalDimension(mapOf("time" to 1)),
                unitId = "s",
                scaleToSi = 1.0,
                aggregation = AggregationSemantics.INSTANTANEOUS
            )
        )
    )

    private fun countScalar(name: String): TypedField = TypedField(
        name = name,
        type = TypedFieldType.INTEGER,
        description = "Non-negative candidate count.",
        physicalDimension = PhysicalDimension(),
        quantitySpec = QuantitySpec(
            dimension = PhysicalDimension(),
            unitId = "1",
            scaleToSi = 1.0,
            aggregation = AggregationSemantics.INSTANTANEOUS
        )
    )

    private fun countSeries(name: String): TypedField = TypedField(
        name = name,
        type = TypedFieldType.ARRAY,
        description = "One candidate count per second.",
        itemType = TypedFieldType.INTEGER,
        minimumItems = 8,
        maximumItems = 8,
        physicalDimension = PhysicalDimension(),
        quantitySpec = QuantitySpec(
            dimension = PhysicalDimension(),
            unitId = "1",
            scaleToSi = 1.0,
            aggregation = AggregationSemantics.SUM_OVER_INTERVALS,
            timeBasis = TimeBasis.SECOND
        )
    )

    private fun percentageSeries(name: String): TypedField = TypedField(
        name = name,
        type = TypedFieldType.ARRAY,
        description = "One percentage per second.",
        itemType = TypedFieldType.NUMBER,
        minimumItems = 8,
        maximumItems = 8,
        physicalDimension = PhysicalDimension(),
        quantitySpec = QuantitySpec(
            dimension = PhysicalDimension(),
            unitId = "%",
            scaleToSi = 0.01,
            aggregation = AggregationSemantics.AVERAGE_OVER_INTERVALS,
            timeBasis = TimeBasis.SECOND
        )
    )

    private fun auditToolSource(): String = """
        import json
        import sys

        value = json.load(sys.stdin)

        def percentage(numerator, denominator):
            return 0.0 if denominator == 0 else 100.0 * numerator / denominator

        generated = value["candidate_generated_per_interval"]
        exact_fit = value["candidate_exact_fit_per_interval"]
        distinct_output = value["candidate_distinct_output_per_interval"]
        distinct_family = value["candidate_distinct_family_per_interval"]
        output = {
            "interval_exact_fit_rate_percent": [
                percentage(exact, total)
                for exact, total in zip(exact_fit, generated)
            ],
            "interval_family_diversity_percent": [
                percentage(families, exact)
                for families, exact in zip(distinct_family, exact_fit)
            ],
            "total_exact_fit_candidates": sum(exact_fit),
            "selected_attempt_count": 2 if max(generated, default=0) > 0 else 0,
            "selected_distinct_output_count": min(2, max(distinct_output, default=0)),
            "selection_runtime_seconds": min(
                8.0,
                len(generated) * value["search_interval_seconds"]
            )
        }
        sys.stdout.write(json.dumps(output, separators=(",", ":")))
    """.trimIndent()

    private fun notebookSource(): String {
        val markdown = """
            # EvoARC-2 symbolic baseline qualification

            Offline execution only. Neural and task-time adaptation branches are deferred.
            Deterministic equivariance and mutation checks are mandatory.
        """.trimIndent()
        return """
            {
              "cells": [
                {
                  "cell_type":"markdown",
                  "id":"scope",
                  "metadata":{},
                  "source":${JsonPrimitive(markdown)}
                },
                {
                  "cell_type":"markdown",
                  "id":"offline-assumptions",
                  "metadata":{},
                  "source":"No network, API key, download, or undeclared asset is required."
                },
                {
                  "cell_type":"code",
                  "id":"environment",
                  "execution_count":null,
                  "metadata":{},
                  "outputs":[],
                  "source":"# Hardware and path introspection is performed by the executable cell below.\n"
                },
                {
                  "cell_type":"code",
                  "id":"solver-families",
                  "execution_count":null,
                  "metadata":{},
                  "outputs":[],
                  "source":"# Geometric, object, color, layout, and fallback solver families.\n"
                },
                {
                  "cell_type":"code",
                  "id":"exact-verification",
                  "execution_count":null,
                  "metadata":{},
                  "outputs":[],
                  "source":"# Exact visible-training-pair verification and early rejection.\n"
                },
                {
                  "cell_type":"code",
                  "id":"portfolio-ranking",
                  "execution_count":null,
                  "metadata":{},
                  "outputs":[],
                  "source":"# Distinct-family and distinct-output portfolio ranking.\n"
                },
                {
                  "cell_type":"code",
                  "id":"self-test-plan",
                  "execution_count":null,
                  "metadata":{},
                  "outputs":[],
                  "source":"# Contextual Symbolic Relay, equivariance, and mutation self-tests.\n"
                },
                {
                  "cell_type":"code",
                  "id":"executable",
                  "execution_count":null,
                  "metadata":{},
                  "outputs":[],
                  "source":${JsonPrimitive(notebookPythonSource())}
                },
                {
                  "cell_type":"markdown",
                  "id":"extensions",
                  "metadata":{},
                  "source":"Future neural, recurrent, 2D-model, and TTT extension interfaces remain deferred."
                }
              ],
              "metadata":{},
              "nbformat":4,
              "nbformat_minor":5
            }
        """.trimIndent()
    }

    private fun notebookPythonSource(): String = """
        import json
        import time
        from pathlib import Path

        challenge_data = {}
        prior_data = {}
        sample_submission = {}
        baseline_fallback = None
        counterfactual = {"baseline": baseline_fallback}
        family_1 = "geometry_pixel"
        family_2 = "object_relational"
        solver_family_contract = {
            "challenge_data": challenge_data,
            "prior_data": prior_data,
            "counterfactual": {"baseline": baseline_fallback},
            "family_1": family_1,
            "family_2": family_2
        }

        def self_audit_notebook(_provider_path=None):
            return solver_family_contract

        started_at = time.monotonic()
        def contextual_symbolic_relay(grid):
            return [list(row) for row in grid]

        def attempt_1(grid):
            return contextual_symbolic_relay(grid)

        def attempt_2(grid):
            return rotate_grid(grid)

        def build_task_solvers(demos):
            return attempt_1, attempt_2

        def is_grid(value):
            return (
                isinstance(value, list)
                and len(value) > 0
                and all(isinstance(row, list) and len(row) > 0 for row in value)
                and len({len(row) for row in value}) == 1
                and all(
                    isinstance(cell, int) and 0 <= cell <= 9
                    for row in value
                    for cell in row
                )
            )

        def rotate_grid(grid):
            return [list(row) for row in zip(*grid[::-1])]

        def reflect_grid(grid):
            return [list(reversed(row)) for row in grid]

        def color_permutation(grid, mapping):
            return [[mapping.get(cell, cell) for cell in row] for row in grid]

        def synthetic_equivariant_views(grid):
            return [grid, rotate_grid(grid), reflect_grid(grid)]

        def callable_signature_smoke_test():
            family = lambda grid: contextual_symbolic_relay(grid)
            assert family([[1]]) == [[1]]

        def crop_nonzero(grid):
            occupied = [
                (row_index, column_index)
                for row_index, row in enumerate(grid)
                for column_index, cell in enumerate(row)
                if cell != 0
            ]
            if not occupied:
                return grid
            min_row = min(row for row, _ in occupied)
            max_row = max(row for row, _ in occupied)
            min_column = min(column for _, column in occupied)
            max_column = max(column for _, column in occupied)
            return [
                row[min_column:max_column + 1]
                for row in grid[min_row:max_row + 1]
            ]

        def exact_on_visible_train(task, transform, deadline, require_change=False):
            transformed = []
            for pair in task["train"]:
                if time.monotonic() >= deadline:
                    return None
                output = transform(pair["input"])
                if time.monotonic() >= deadline:
                    return None
                if output != pair["output"]:
                    return False
                transformed.append(output)
            if require_change and all(
                output == pair["input"]
                for output, pair in zip(transformed, task["train"])
            ):
                return False
            return True

        def geometry_pixel_family(task, deadline):
            for transform in (reflect_grid, rotate_grid):
                if exact_on_visible_train(
                    task,
                    transform,
                    deadline,
                    require_change=True
                ) is True:
                    return [transform(case["input"]) for case in task["test"]]
            return None

        def object_relational_family(task, deadline):
            if exact_on_visible_train(
                task,
                crop_nonzero,
                deadline,
                require_change=True
            ) is True:
                return [crop_nonzero(case["input"]) for case in task["test"]]
            return None

        def infer_color_mapping(task, deadline):
            mapping = {}
            changed = False
            for pair in task["train"]:
                if time.monotonic() >= deadline:
                    return None
                if (
                    len(pair["input"]) != len(pair["output"])
                    or any(
                        len(input_row) != len(output_row)
                        for input_row, output_row in zip(pair["input"], pair["output"])
                    )
                ):
                    return None
                for input_row, output_row in zip(pair["input"], pair["output"]):
                    if time.monotonic() >= deadline:
                        return None
                    for input_color, output_color in zip(input_row, output_row):
                        if input_color in mapping and mapping[input_color] != output_color:
                            return None
                        mapping[input_color] = output_color
                        changed = changed or input_color != output_color
            return mapping if changed else None

        def color_layout_family(task, deadline):
            mapping = infer_color_mapping(task, deadline)
            if mapping is None:
                return None
            transform = lambda grid: color_permutation(grid, mapping)
            if exact_on_visible_train(
                task,
                transform,
                deadline,
                require_change=True
            ) is not True:
                return None
            return [transform(case["input"]) for case in task["test"]]

        def fallback_family(task, deadline):
            if exact_on_visible_train(
                task,
                lambda grid: grid,
                deadline
            ) is True:
                return [case["input"] for case in task["test"]]
            return None

        def exact_candidate_families(task, deadline):
            candidates = []
            for family, solver in (
                ("geometry_pixel", geometry_pixel_family),
                ("object_relational", object_relational_family),
                ("color_layout", color_layout_family),
                ("fallback", fallback_family)
            ):
                if time.monotonic() >= deadline:
                    break
                outputs = solver(task, deadline)
                if time.monotonic() >= deadline:
                    return []
                if outputs is not None:
                    candidates.append((family, outputs))
            return candidates

        def deterministic_fallback_outputs(task):
            return [case["input"] for case in task["test"]]

        def select_two_outputs(candidates):
            distinct = []
            for family, grid in candidates:
                if not is_grid(grid):
                    continue
                if all(grid != previous_grid for _, previous_grid in distinct):
                    distinct.append((family, grid))
            if not distinct:
                raise ValueError("no valid candidate")
            attempts = [grid for _, grid in distinct[:2]]
            while len(attempts) < 2:
                attempts.append(attempts[0])
            return attempts

        def validate_submission(submission, challenges):
            if set(submission) != set(challenges):
                return False
            for task_id, task in challenges.items():
                rows = submission[task_id]
                if not isinstance(rows, list) or len(rows) != len(task["test"]):
                    return False
                if any(
                    set(record) != {"attempt_1", "attempt_2"}
                    or not is_grid(record["attempt_1"])
                    or not is_grid(record["attempt_2"])
                    for record in rows
                ):
                    return False
            return True

        def load_local_inputs():
            return challenge_data, prior_data

        def execute_challenge(challenges, prior_data):
            submission = {}
            audit_rows = []
            for task_id, task in challenges.items():
                demos = [
                    (pair["input"], pair["output"])
                    for pair in task.get("train", [])
                ]
                attempt_1_solver, attempt_2_solver = build_task_solvers(demos)
                rows = []
                for test in task.get("test", []):
                    inp = test["input"]
                    rows.append({
                        "attempt_1": attempt_1_solver(inp),
                        "attempt_2": attempt_2_solver(inp)
                    })
                submission[task_id] = rows
            return submission, audit_rows

        def write_receiver(submission, path):
            return path

        def run_self_tests():
            grid = [[1, 2], [3, 4]]
            mutation = [[1, 2], [3]]
            callable_signature_smoke_test()
            demos = [{"input": grid, "output": grid}]
            attempt_1_solver, attempt_2_solver = build_task_solvers(demos)
            inp = grid
            assert attempt_1_solver(inp) == grid
            assert attempt_2_solver(inp) == rotate_grid(grid)
            assert len(synthetic_equivariant_views(grid)) == 3
            selected = select_two_outputs([
                ("identity", grid),
                ("duplicate", grid),
                ("reflection", reflect_grid(grid))
            ])
            assert len(selected) == 2
            assert selected[0] != selected[1]
            assert not is_grid(mutation)
            assert rotate_grid(rotate_grid(rotate_grid(rotate_grid(grid)))) == grid
            assert reflect_grid(reflect_grid(grid)) == grid
            assert color_permutation(
                color_permutation(grid, {1: 5, 5: 1}),
                {1: 5, 5: 1}
            ) == grid
            assert not validate_submission(
                {"bad": {"attempt_1": mutation, "attempt_2": grid}},
                {"task_a": {"test": [{"input": grid}]}}
            )
            representative_tasks = {
                "geometry_pixel": {
                    "train": [{"input": grid, "output": reflect_grid(grid)}],
                    "test": [{"input": grid}]
                },
                "object_relational": {
                    "train": [{
                        "input": [[0, 0, 0], [0, 3, 0], [0, 0, 0]],
                        "output": [[3]]
                    }],
                    "test": [{"input": [[0, 4, 0]]}]
                },
                "color_layout": {
                    "train": [{
                        "input": [[1, 0], [0, 1]],
                        "output": [[2, 0], [0, 2]]
                    }],
                    "test": [{"input": [[1, 1]]}]
                },
                "fallback": {
                    "train": [{"input": grid, "output": grid}],
                    "test": [{"input": grid}]
                }
            }
            for expected_family, task in representative_tasks.items():
                families = exact_candidate_families(
                    task,
                    time.monotonic() + 1.0
                )
                assert any(family == expected_family for family, _ in families)

        run_self_tests()

        def discover_input(patterns):
            roots = [Path.cwd(), Path("/kaggle/input")]
            for root in roots:
                if not root.exists():
                    continue
                for pattern in patterns:
                    candidates = sorted(
                        path for path in root.rglob(pattern) if path.is_file()
                    )
                    if candidates:
                        return candidates[0]
            raise FileNotFoundError(f"missing Kaggle input matching {patterns}")

        challenge_path = discover_input((
            "*test_challenges*.json",
            "*challenges*.json"
        ))
        sample_path = discover_input(("sample_submission*.json",))
        challenges = json.loads(challenge_path.read_text())
        sample_submission = json.loads(sample_path.read_text())
        submission = {}
        for task_id, task in challenges.items():
            task_deadline = time.monotonic() + 8.0
            family_outputs = exact_candidate_families(task, task_deadline)
            if time.monotonic() >= task_deadline or not family_outputs:
                family_outputs = [
                    ("deterministic_fallback", deterministic_fallback_outputs(task))
                ]
            rows = []
            for test_index, test_case in enumerate(task["test"]):
                candidates = [
                    (family, outputs[test_index])
                    for family, outputs in family_outputs
                ]
                candidates.append(("duplicate_boundary", candidates[0][1]))
                attempts = select_two_outputs(candidates)
                if "expected_output" in test_case:
                    assert attempts[0] == test_case["expected_output"]
                rows.append({
                    "attempt_1": attempts[0],
                    "attempt_2": attempts[1]
                })
            submission[task_id] = rows
        assert set(submission) == set(sample_submission)
        assert validate_submission(submission, challenges)
        Path("submission.json").write_text(
            json.dumps(submission, separators=(",", ":"))
        )
        print(json.dumps(submission, separators=(",", ":")))
    """.trimIndent()

    private fun isArcGrid(value: kotlinx.serialization.json.JsonElement): Boolean {
        val rows = value as? JsonArray ?: return false
        if (rows.isEmpty()) return false
        val width = (rows.firstOrNull() as? JsonArray)?.size ?: return false
        if (width == 0) return false
        return rows.all { row ->
            row is JsonArray &&
                row.size == width &&
                row.all { cell ->
                    val primitive = cell as? JsonPrimitive
                    primitive != null &&
                        !primitive.isString &&
                        primitive.content.toIntOrNull() in 0..9
                }
        }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"
}
