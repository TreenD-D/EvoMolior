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
 * Fixed completion-blueprint Section 11.3 closure catalog in canonical publication order.
 */
object RunClosureMetricCatalog {
    private val canonicalMetricIds: List<String> = listOf(
        "exit_identity_conflicts",
        "exit_finalization_retries",
        "tool_contract_transport_failure_rate",
        "tool_repair_diagnostics_emitted",
        "tool_repair_diagnostic_consumption_rate",
        "repair_to_promotion_rate",
        "repeated_identical_tool_failure_rate",
        "receiver_component_coverage",
        "interim_snapshot_coverage",
        "recovery_component_coverage",
        "provisional_claim_with_authority_rate",
        "accepted_package_deterministic_content_rate",
    )

    /** Exact ordered closure metric IDs required by explicit acceptance publication. */
    val orderedMetricIds: List<String>
        get() = canonicalMetricIds.toList()
}

/** Declares what persisted activity the run-metric observations measure. */
@Serializable
enum class RunMetricMeasurementScope {
    /** Measures the complete terminal attempt, including work in a later partially closed round. */
    TERMINAL_ATTEMPTED_WORK
}

/**
 * Complete, ordered Section 18.2 metric export for one run.
 *
 * @param schemaVersion Stable wire-schema identifier; must equal [SCHEMA_VERSION].
 * @param runId Non-blank run identifier.
 * @param measurementScope Explicit activity scope represented by the observations.
 * @param decisionAuthorityThroughRound Last fully archived zero-based round supplying decision
 * authority, or `-1` when no round reached archival.
 * @param observations Exactly one observation for every catalog metric in canonical order.
 * @param closureObservations Exact Section 11.3 closure block, or empty for historical decoders
 * and callers that have not requested explicit acceptance publication.
 */
@Serializable
data class RunMetricsV1(
    val schemaVersion: String = SCHEMA_VERSION,
    val runId: String,
    val measurementScope: RunMetricMeasurementScope =
        RunMetricMeasurementScope.TERMINAL_ATTEMPTED_WORK,
    val decisionAuthorityThroughRound: Int = -1,
    val observations: List<RunMetricObservation>,
    val closureObservations: List<RunMetricObservation> = emptyList(),
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) {
            "Run metrics schema version must be $SCHEMA_VERSION"
        }
        require(runId.isNotBlank()) { "Run ID must not be blank" }
        require(decisionAuthorityThroughRound >= -1) {
            "Run metrics decision-authority round cannot be less than -1"
        }
        require(observations.map(RunMetricObservation::metricId) == RunMetricCatalog.orderedMetricIds) {
            "Run metrics must contain the exact catalog once in canonical order"
        }
        require(
            closureObservations.isEmpty() ||
                closureObservations.map(RunMetricObservation::metricId) ==
                RunClosureMetricCatalog.orderedMetricIds
        ) {
            "Run closure metrics must be empty or contain the exact closure catalog once " +
                "in canonical order"
        }
    }

    /** Constants defining the run-metrics-v1 wire contract. */
    companion object {
        /** Stable schema-version literal serialized by this contract. */
        const val SCHEMA_VERSION: String = "run-metrics-v1"
    }
}
