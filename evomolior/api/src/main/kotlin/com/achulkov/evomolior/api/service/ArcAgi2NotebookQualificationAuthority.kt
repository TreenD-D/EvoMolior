package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.api.model.FinalOutputValidationContract
import com.achulkov.evomolior.api.model.JupyterNotebookSourceContract
import com.achulkov.evomolior.core.atlas.PhysicalDimension
import com.achulkov.evomolior.core.atlas.ToolOpportunityType
import com.achulkov.evomolior.core.atlas.TypedFieldType
import com.achulkov.evomolior.core.council.QuantitativePromotionFixtureObligation
import com.achulkov.evomolior.core.council.QuantitativeToolFieldLocation
import com.achulkov.evomolior.core.council.QuantitativeToolFieldObligation
import com.achulkov.evomolior.core.council.QuantitativeToolFieldShape
import com.achulkov.evomolior.core.council.QuantitativeToolObligation
import com.achulkov.evomolior.core.repair.AggregationSemantics
import com.achulkov.evomolior.core.repair.QuantitySpec
import com.achulkov.evomolior.core.repair.TimeBasis
import kotlinx.serialization.json.Json

/**
 * Operator-owned executable authority for the EvoARC-2 symbolic baseline qualification.
 *
 * The checked-in pipeline configuration is required to equal this catalog. Council compilation
 * then seals these exact fixtures, and production tool promotion executes the compiled private
 * copies in the bounded tool sandbox.
 */
object ArcAgi2NotebookQualificationAuthority {
    /** Immutable pipeline-stage identity selecting this target authority at admission. */
    const val PIPELINE_STAGE_ID: String = "gate-d-arc-agi-2-notebook-qualification"

    /** Exact target opportunity identity. */
    const val OPPORTUNITY_ID: String = "arc_candidate_portfolio_audit"

    /** Inclusive target runtime ceiling. */
    const val MAXIMUM_RUNTIME_MILLIS: Long = 8_000L

    /** Canonical receiver-facing notebook path for this target. */
    const val NOTEBOOK_RELATIVE_PATH: String = "submission.ipynb"

    /** Canonical receiver-facing notebook media type for this target. */
    const val NOTEBOOK_MEDIA_TYPE: String = "application/x-ipynb+json"

    /** Complete target promotion-test identity set. */
    val promotionTestIds: Set<String> = linkedSetOf(
        "duplicate_output_diversity_boundary",
        "exactly_two_attempt_boundary",
        "eight_second_runtime_boundary",
        "rate_denominator_boundary"
    )

    /** Exact synthetic promotion fixtures defining every target boundary. */
    val promotionFixtures: List<QuantitativePromotionFixtureObligation> = listOf(
        fixture(
            testId = "duplicate_output_diversity_boundary",
            generated = 2,
            exactFit = 2,
            duplicate = 1,
            distinctOutput = 1,
            distinctFamily = 1,
            exactFitRate = 100.0,
            familyDiversityRate = 50.0,
            totalExactFit = 16,
            selectedDistinctOutputCount = 1
        ),
        fixture(
            testId = "exactly_two_attempt_boundary",
            generated = 4,
            exactFit = 2,
            duplicate = 0,
            distinctOutput = 2,
            distinctFamily = 2,
            exactFitRate = 50.0,
            familyDiversityRate = 100.0,
            totalExactFit = 16,
            selectedDistinctOutputCount = 2
        ),
        fixture(
            testId = "eight_second_runtime_boundary",
            generated = 1,
            exactFit = 1,
            duplicate = 0,
            distinctOutput = 1,
            distinctFamily = 1,
            exactFitRate = 100.0,
            familyDiversityRate = 100.0,
            totalExactFit = 8,
            selectedDistinctOutputCount = 1
        ),
        mixedRateDenominatorFixture()
    )

    /** Complete canonical quantitative tool obligation for the ARC qualification. */
    val quantitativeToolObligation: QuantitativeToolObligation =
        QuantitativeToolObligation(
            horizonTimeBasis = TimeBasis.SECOND,
            horizonIntervalCount = 8,
            allowedOpportunityTypes = linkedSetOf(
                ToolOpportunityType.CALCULATION,
                ToolOpportunityType.VALIDATION
            ),
            requiredOpportunityId = OPPORTUNITY_ID,
            exactPromotionTestIds = promotionTestIds,
            exactPromotionFixtures = promotionFixtures,
            maximumOpportunityRuntimeMillis = MAXIMUM_RUNTIME_MILLIS,
            requiredFields = listOf(
                countField(
                    "training_pair_count",
                    QuantitativeToolFieldLocation.INPUT
                ),
                field(
                    roleId = "search_interval_seconds",
                    location = QuantitativeToolFieldLocation.INPUT,
                    shape = QuantitativeToolFieldShape.SCALAR,
                    dimension = PhysicalDimension(mapOf("time" to 1)),
                    unitId = "s",
                    aggregation = AggregationSemantics.INSTANTANEOUS
                ),
                countSeries("candidate_generated_per_interval"),
                countSeries("candidate_exact_fit_per_interval"),
                countSeries("candidate_duplicate_output_per_interval"),
                countSeries("candidate_distinct_output_per_interval"),
                countSeries("candidate_distinct_family_per_interval"),
                field(
                    roleId = "interval_exact_fit_rate",
                    fieldName = "interval_exact_fit_rate_percent",
                    location = QuantitativeToolFieldLocation.OUTPUT,
                    shape = QuantitativeToolFieldShape.HORIZON_SERIES,
                    dimension = PhysicalDimension(),
                    unitId = "%",
                    scaleToSi = 0.01,
                    aggregation = AggregationSemantics.AVERAGE_OVER_INTERVALS,
                    timeBasis = TimeBasis.SECOND
                ),
                field(
                    roleId = "interval_family_diversity",
                    fieldName = "interval_family_diversity_percent",
                    location = QuantitativeToolFieldLocation.OUTPUT,
                    shape = QuantitativeToolFieldShape.HORIZON_SERIES,
                    dimension = PhysicalDimension(),
                    unitId = "%",
                    scaleToSi = 0.01,
                    aggregation = AggregationSemantics.AVERAGE_OVER_INTERVALS,
                    timeBasis = TimeBasis.SECOND
                ),
                countField(
                    "total_exact_fit_candidates",
                    QuantitativeToolFieldLocation.OUTPUT
                ),
                countField(
                    "selected_attempt_count",
                    QuantitativeToolFieldLocation.OUTPUT
                ),
                countField(
                    "selected_distinct_output_count",
                    QuantitativeToolFieldLocation.OUTPUT
                ),
                field(
                    roleId = "selection_runtime_seconds",
                    location = QuantitativeToolFieldLocation.OUTPUT,
                    shape = QuantitativeToolFieldShape.SCALAR,
                    dimension = PhysicalDimension(mapOf("time" to 1)),
                    unitId = "s",
                    aggregation = AggregationSemantics.INSTANTANEOUS
                )
            )
        )

    /** Exact static notebook publication contract paired with the executable smoke fixture. */
    val notebookValidationContract: FinalOutputValidationContract =
        FinalOutputValidationContract(
            requiredJsonRootKeys = listOf(
                "cells",
                "metadata",
                "nbformat",
                "nbformat_minor"
            ),
            minimumJsonArraySizes = mapOf("cells" to 9),
            requiredJsonArrayItemFields = mapOf(
                "cells" to listOf("cell_type", "metadata", "source")
            ),
            jupyterNotebookSource = JupyterNotebookSourceContract(
                minimumCodeCellCount = 6,
                requiredCodeSignals = listOf(
                    "def is_grid",
                    "def select_two_outputs",
                    "def validate_submission",
                    "contextual_symbolic_relay",
                    "def synthetic_equivariant_views",
                    "def callable_signature_smoke_test",
                    "def contextual_symbolic_relay(grid)",
                    "def attempt_1(grid)",
                    "def attempt_2(grid)",
                    "def build_task_solvers(demos)",
                    "def load_local_inputs",
                    "def load_local_inputs()",
                    "\"challenge_data\": challenge_data",
                    "\"prior_data\": prior_data",
                    "def execute_challenge",
                    "def execute_challenge(challenges, prior_data)",
                    "\"counterfactual\": {\"baseline\": baseline_fallback}",
                    "def write_receiver",
                    "for task_id, task in challenges.items()",
                    "task.get(\"train\"",
                    "task.get(\"test\"",
                    "attempt_1_solver, attempt_2_solver = build_task_solvers(demos)",
                    "attempt_1_solver(inp)",
                    "attempt_2_solver(inp)",
                    "attempt_1",
                    "attempt_2",
                    "submission.json",
                    "sample_submission",
                    "time.monotonic",
                    "def run_self_tests",
                    "def self_audit_notebook(_provider_path=None)",
                    "\"family_1\"",
                    "\"family_2\"",
                    "\"counterfactual\"",
                    "callable_signature_smoke_test()\n",
                    "run_self_tests()\n",
                    "def rotate_grid",
                    "color_permutation",
                    "mutation"
                ),
                requiredMarkdownSignals = listOf(
                    "EvoARC-2 symbolic baseline qualification",
                    "offline",
                    "neural and task-time adaptation branches are deferred",
                    "equivariance",
                    "mutation"
                ),
                forbiddenCodeSignals = listOf(
                    "requests.",
                    "urllib.",
                    "http://",
                    "https://",
                    "kagglehub",
                    "pip install",
                    "!wget",
                    "!curl",
                    "attempt_1(inp, [])",
                    "attempt_2(inp, [])",
                    "demos = []",
                    "for task_id, test_inputs in challenge.items()",
                    "def contextual_symbolic_relay(inp, demos)",
                    "def attempt_1(inp, demos)",
                    "def attempt_2(inp, demos)"
                ),
                requireCleanCodeCells = true,
                requireUniqueCellIdsForNbformat45 = true
            )
        )

    /**
     * Copyable implementation blueprint appended only to the exact ARC notebook writer prompt.
     *
     * The validator intentionally checks source fragments inside executable code cells. A long
     * list of fragments is easy for a writer to satisfy accidentally or omit; this blueprint
     * turns the same authority into an ordered implementation checklist without changing the
     * generic final-writer contract used by other deliverable types.
     */
    val notebookWriterBlueprint: String = """
        === ARC NOTEBOOK WRITER BLUEPRINT — EXACT TARGET ONLY ===
        This checklist applies only when the output target is submission.ipynb with media type
        application/x-ipynb+json. Copy the requested definitions, literals, loops, and calls
        into executable code-cell source. Markdown descriptions do not satisfy code signals.

        Notebook envelope:
        1. Emit one valid JSON object in Jupyter nbformat 4.5 form with root keys
           cells, metadata, nbformat, and nbformat_minor.
        2. Provide at least 9 cells, including at least 6 clean code cells. Every code cell must
           have execution_count=null and outputs=[], and every nbformat 4.5 cell needs a unique
           nonblank ASCII id. Source must be a string or an array of strings.
        3. Keep all required implementation signals below inside code-cell source, not only in
           Markdown or prose.

        Exact definitions to copy into code cells:
        - def is_grid(value):
        - def select_two_outputs(candidates, inp):
        - def validate_submission(submission, challenges):
        - def contextual_symbolic_relay(grid):
        - def synthetic_equivariant_views(grid):
        - def callable_signature_smoke_test():
        - def build_task_solvers(demos):
        - def load_local_inputs():
        - def execute_challenge(challenges, prior_data)
        - def write_receiver(submission, path):
        - def run_self_tests():
        - def self_audit_notebook(_provider_path=None):
        - def rotate_grid(grid):
        - def color_permutation(grid, mapping):
        - def mutation(grid):
        - def attempt_1(grid):
        - def attempt_2(grid):

        Exact source literals to copy verbatim into code cells:
        - "challenge_data": challenge_data
        - "prior_data": prior_data
        - "counterfactual": {"baseline": baseline_fallback}
        - "family_1"
        - "family_2"
        - "counterfactual"
        - submission.json
        - sample_submission
        - time.monotonic
        - baseline fallback
        - one result row per test input

        Exact execution structure to copy into code cells:
        - for task_id, task in challenges.items():
        - task.get("train"
        - task.get("test"
        - attempt_1_solver, attempt_2_solver = build_task_solvers(demos)
        - attempt_1_solver(inp)
        - attempt_2_solver(inp)
        - callable_signature_smoke_test()
        - run_self_tests()

        Receiver behavior:
        - load challenge_data and prior_data from local files only; do not use network access.
        - fit or rank two callable solver families from visible demonstrations for each task.
        - emit exactly one result row per test input, with exactly attempt_1 and attempt_2.
        - record family_1, family_2, counterfactual, and baseline fallback audit metadata.
        - preserve the supplied prior receiver only as an explicit baseline fallback and label it.
        - call callable_signature_smoke_test() and run_self_tests() before write_receiver.
        - write the final receiver file to submission.json and leave the notebook clean/unexecuted.

        Required Markdown context:
        - include the heading EvoARC-2 symbolic baseline qualification;
        - state that the notebook is offline and that neural and task-time adaptation branches are deferred;
        - document equivariance and mutation as implementation concepts;
        - keep unsupported accuracy claims provisional and non-accepted.
    """.trimIndent()

    /**
     * Rejects a decoded target obligation that differs from this executable authority.
     *
     * @param obligation Decoded operator-owned quantitative contract.
     */
    fun requireExact(obligation: QuantitativeToolObligation) {
        require(obligation == quantitativeToolObligation) {
            "ARC qualification quantitative tool obligation differs from executable authority"
        }
    }

    /**
     * Returns whether [outputSpec] is the canonical ARC notebook target contract.
     *
     * Description and output-kind metadata may vary without changing the receiver contract; the
     * path, media type, and executable validation contract are the authority boundary.
     *
     * @param outputSpec Decoded receiver-facing output specification.
     * @return `true` only for the canonical ARC notebook contract.
     */
    fun isCanonicalNotebookOutputSpec(outputSpec: FinalOutputSpec): Boolean =
        outputSpec.relativePath == NOTEBOOK_RELATIVE_PATH &&
            outputSpec.mediaType.substringBefore(';').trim()
                .equals(NOTEBOOK_MEDIA_TYPE, ignoreCase = true) &&
            outputSpec.validation == notebookValidationContract

    /**
     * Rejects a receiver-facing output specification that drifts from the ARC notebook authority.
     *
     * @param outputSpec Decoded receiver-facing output specification.
     */
    fun requireExactNotebookOutputSpec(outputSpec: FinalOutputSpec) {
        require(isCanonicalNotebookOutputSpec(outputSpec)) {
            "ARC qualification final output specification differs from executable notebook authority"
        }
    }

    private fun countSeries(roleId: String): QuantitativeToolFieldObligation =
        field(
            roleId = roleId,
            location = QuantitativeToolFieldLocation.INPUT,
            shape = QuantitativeToolFieldShape.HORIZON_SERIES,
            numericType = TypedFieldType.INTEGER,
            dimension = PhysicalDimension(),
            unitId = "1",
            aggregation = AggregationSemantics.SUM_OVER_INTERVALS,
            timeBasis = TimeBasis.SECOND
        )

    private fun countField(
        roleId: String,
        location: QuantitativeToolFieldLocation
    ): QuantitativeToolFieldObligation =
        field(
            roleId = roleId,
            location = location,
            shape = QuantitativeToolFieldShape.SCALAR,
            numericType = TypedFieldType.INTEGER,
            dimension = PhysicalDimension(),
            unitId = "1",
            aggregation = AggregationSemantics.INSTANTANEOUS
        )

    private fun field(
        roleId: String,
        location: QuantitativeToolFieldLocation,
        shape: QuantitativeToolFieldShape,
        numericType: TypedFieldType = TypedFieldType.NUMBER,
        fieldName: String = roleId,
        dimension: PhysicalDimension,
        unitId: String,
        scaleToSi: Double = 1.0,
        aggregation: AggregationSemantics,
        timeBasis: TimeBasis? = null
    ): QuantitativeToolFieldObligation =
        QuantitativeToolFieldObligation(
            roleId = roleId,
            fieldName = fieldName,
            location = location,
            shape = shape,
            numericType = numericType,
            quantitySpec = QuantitySpec(
                dimension = dimension,
                unitId = unitId,
                scaleToSi = scaleToSi,
                aggregation = aggregation,
                timeBasis = timeBasis
            )
        )

    private fun fixture(
        testId: String,
        generated: Int,
        exactFit: Int,
        duplicate: Int,
        distinctOutput: Int,
        distinctFamily: Int,
        exactFitRate: Double,
        familyDiversityRate: Double,
        totalExactFit: Int,
        selectedDistinctOutputCount: Int
    ): QuantitativePromotionFixtureObligation {
        val generatedSeries = integerSeries(generated)
        val exactFitSeries = integerSeries(exactFit)
        val duplicateSeries = integerSeries(duplicate)
        val distinctOutputSeries = integerSeries(distinctOutput)
        val distinctFamilySeries = integerSeries(distinctFamily)
        val exactFitRateSeries = numberSeries(exactFitRate)
        val familyDiversitySeries = numberSeries(familyDiversityRate)
        return QuantitativePromotionFixtureObligation(
            testId = testId,
            input = Json.parseToJsonElement(
                """
                {
                  "training_pair_count":1,
                  "search_interval_seconds":1.0,
                  "candidate_generated_per_interval":$generatedSeries,
                  "candidate_exact_fit_per_interval":$exactFitSeries,
                  "candidate_duplicate_output_per_interval":$duplicateSeries,
                  "candidate_distinct_output_per_interval":$distinctOutputSeries,
                  "candidate_distinct_family_per_interval":$distinctFamilySeries
                }
                """.trimIndent()
            ),
            expectedOutput = Json.parseToJsonElement(
                """
                {
                  "interval_exact_fit_rate_percent":$exactFitRateSeries,
                  "interval_family_diversity_percent":$familyDiversitySeries,
                  "total_exact_fit_candidates":$totalExactFit,
                  "selected_attempt_count":2,
                  "selected_distinct_output_count":$selectedDistinctOutputCount,
                  "selection_runtime_seconds":8.0
                }
                """.trimIndent()
            )
        )
    }

    private fun mixedRateDenominatorFixture(): QuantitativePromotionFixtureObligation {
        val generated = listOf(0, 4, 4, 4, 4, 4, 4, 4)
        val exactFit = listOf(0, 2, 2, 2, 2, 2, 2, 2)
        val duplicate = listOf(0, 1, 1, 1, 1, 1, 1, 1)
        val distinctOutput = listOf(0, 3, 3, 3, 3, 3, 3, 3)
        val distinctFamily = listOf(0, 1, 1, 1, 1, 1, 1, 1)
        val rates = listOf(0.0, 50.0, 50.0, 50.0, 50.0, 50.0, 50.0, 50.0)
        return QuantitativePromotionFixtureObligation(
            testId = "rate_denominator_boundary",
            input = Json.parseToJsonElement(
                """
                {
                  "training_pair_count":1,
                  "search_interval_seconds":1.0,
                  "candidate_generated_per_interval":$generated,
                  "candidate_exact_fit_per_interval":$exactFit,
                  "candidate_duplicate_output_per_interval":$duplicate,
                  "candidate_distinct_output_per_interval":$distinctOutput,
                  "candidate_distinct_family_per_interval":$distinctFamily
                }
                """.trimIndent()
            ),
            expectedOutput = Json.parseToJsonElement(
                """
                {
                  "interval_exact_fit_rate_percent":$rates,
                  "interval_family_diversity_percent":$rates,
                  "total_exact_fit_candidates":14,
                  "selected_attempt_count":2,
                  "selected_distinct_output_count":2,
                  "selection_runtime_seconds":8.0
                }
                """.trimIndent()
            )
        )
    }

    private fun integerSeries(value: Int): String =
        List(8) { value }.joinToString(prefix = "[", postfix = "]")

    private fun numberSeries(value: Double): String =
        List(8) { value }.joinToString(prefix = "[", postfix = "]")
}
