package com.achulkov.evomolior.core.metrics

import kotlinx.serialization.Serializable

/**
 * Availability state for one run-metric observation.
 */
@Serializable
enum class MetricAvailability {
    /** The metric has a measured [RunMetricObservation.value]. */
    AVAILABLE,

    /** The metric applies, but its value cannot be computed from available evidence. */
    UNAVAILABLE,

    /** The metric does not apply to the run or its declared evaluation profile. */
    NOT_APPLICABLE
}

/**
 * One typed Section 18.2 run-metric observation.
 *
 * A numerator may remain known when its denominator, and therefore the metric value, is
 * unavailable. Unknown denominators are represented by `null`, never by zero.
 *
 * @param metricId Stable metric identifier from [RunMetricCatalog.orderedMetricIds].
 * @param status Availability of the metric value.
 * @param numerator Observed numerator, when known.
 * @param denominator Observed denominator, when known.
 * @param value Computed metric value when [status] is [MetricAvailability.AVAILABLE].
 * @param unit Optional non-blank unit or representation label for the value.
 * @param evidenceRefs Non-empty, sorted, distinct references to supporting run evidence.
 * @param unavailableReason Explanation when the metric is unavailable or not applicable.
 */
@Serializable
data class RunMetricObservation(
    val metricId: String,
    val status: MetricAvailability,
    val numerator: Double? = null,
    val denominator: Double? = null,
    val value: Double? = null,
    val unit: String?,
    val evidenceRefs: List<String>,
    val unavailableReason: String? = null,
) {
    init {
        require(metricId.isNotBlank()) { "Metric ID must not be blank" }
        require(unit == null || unit.isNotBlank()) {
            "Metric unit must be null or non-blank"
        }
        require(evidenceRefs.isNotEmpty() && evidenceRefs.none(String::isBlank)) {
            "Metric evidence references must be non-empty and non-blank"
        }
        require(evidenceRefs == evidenceRefs.sorted() && evidenceRefs.distinct() == evidenceRefs) {
            "Metric evidence references must be sorted and distinct"
        }
        listOf(numerator, denominator, value)
            .filterNotNull()
            .forEach { number ->
                require(number.isFinite() && number >= 0.0) {
                    "Metric numeric values must be finite and non-negative"
                }
            }

        when (status) {
            MetricAvailability.AVAILABLE -> {
                require(value != null) { "Available metric requires a value" }
                require(unavailableReason == null) {
                    "Available metric cannot carry an unavailable reason"
                }
                require(denominator == null || denominator > 0.0) {
                    "Available metric denominator must be null or positive"
                }
            }

            MetricAvailability.UNAVAILABLE -> {
                require(value == null) { "Unavailable metric cannot carry a value" }
                require(!unavailableReason.isNullOrBlank()) {
                    "Unavailable metric requires a non-blank reason"
                }
                require(denominator == null || denominator > 0.0) {
                    "Unavailable metric denominator must be null or positive, never zero"
                }
            }

            MetricAvailability.NOT_APPLICABLE -> {
                require(numerator == null && denominator == null && value == null) {
                    "Not-applicable metric cannot carry numeric fields"
                }
                require(!unavailableReason.isNullOrBlank()) {
                    "Not-applicable metric requires a non-blank reason"
                }
            }
        }
    }
}

/**
 * Fixed Section 18.2 metric catalog in canonical publication order.
 */
object RunMetricCatalog {
    private val canonicalMetricIds: List<String> = listOf(
        "useful_exit_rate",
        "atomic_node_completion_rate",
        "mandatory_node_coverage",
        "group_frontier_overlap",
        "unplanned_redundancy_cost",
        "outlier_proposal_acceptance",
        "outlier_downstream_adoption",
        "tool_native_agent_rate",
        "tool_reuse_factor",
        "simulation_influence_rate",
        "kg_syntactic_acceptance",
        "claim_grounding_coverage",
        "grader_bundle_validity",
        "grader_requirement_novelty",
        "grader_calibration",
        "evaluation_independence",
        "capture_concentration",
        "reward_usefulness",
        "node_to_final_lineage",
        "provider_cost_per_accepted_node",
        "score_replay_error",
    )

    /** Exact ordered metric IDs required in every [RunMetricsV1] record. */
    val orderedMetricIds: List<String>
        get() = canonicalMetricIds.toList()
}

/**
 * Complete, ordered Section 18.2 metric export for one run.
 *
 * @param schemaVersion Stable wire-schema identifier; must equal [SCHEMA_VERSION].
 * @param runId Non-blank run identifier.
 * @param observations Exactly one observation for every catalog metric in canonical order.
 */
@Serializable
data class RunMetricsV1(
    val schemaVersion: String = SCHEMA_VERSION,
    val runId: String,
    val observations: List<RunMetricObservation>,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) {
            "Run metrics schema version must be $SCHEMA_VERSION"
        }
        require(runId.isNotBlank()) { "Run ID must not be blank" }
        require(observations.map(RunMetricObservation::metricId) == RunMetricCatalog.orderedMetricIds) {
            "Run metrics must contain the exact catalog once in canonical order"
        }
    }

    /** Constants defining the run-metrics-v1 wire contract. */
    companion object {
        /** Stable schema-version literal serialized by this contract. */
        const val SCHEMA_VERSION: String = "run-metrics-v1"
    }
}
