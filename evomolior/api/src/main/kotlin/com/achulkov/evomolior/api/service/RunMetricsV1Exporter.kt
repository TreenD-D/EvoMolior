package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.deliverable.ReceiverDecisionPackage
import com.achulkov.evomolior.core.metrics.MetricAvailability
import com.achulkov.evomolior.core.metrics.RunClosureMetricCatalog
import com.achulkov.evomolior.core.metrics.RunMetricCatalog
import com.achulkov.evomolior.core.metrics.RunMetricObservation
import com.achulkov.evomolior.core.metrics.RunMetricsV1

/**
 * Exact live lifecycle counters that are not reconstructable from persisted final authority.
 *
 * @param exitIdentityConflicts Number of conflicting attempts to finalize one lifecycle identity.
 * @param exitFinalizationRetries Number of finalization retries after the first attempt.
 * @param evidenceRefs Exact runtime counter authorities.
 */
data class LifecycleClosureMeasurements(
    val exitIdentityConflicts: Int,
    val exitFinalizationRetries: Int,
    val evidenceRefs: List<String>,
) {
    init {
        require(exitIdentityConflicts >= 0 && exitFinalizationRetries >= 0) {
            "Lifecycle closure counters must be non-negative"
        }
        require(evidenceRefs.isNotEmpty() && evidenceRefs.none(String::isBlank)) {
            "Lifecycle closure counters require exact evidence"
        }
    }
}

/**
 * Exact runtime-owned tool-contract transport accounting.
 *
 * @param attempts Number of candidate transport attempts.
 * @param failures Number of attempts rejected by the handle/workspace transport boundary.
 * @param evidenceRefs Exact transport diagnostic authorities.
 */
data class ToolTransportClosureMeasurements(
    val attempts: Int,
    val failures: Int,
    val evidenceRefs: List<String>,
) {
    init {
        require(attempts >= 0 && failures in 0..attempts) {
            "Tool transport closure counters are inconsistent"
        }
        require(evidenceRefs.isNotEmpty() && evidenceRefs.none(String::isBlank)) {
            "Tool transport closure counters require exact evidence"
        }
    }
}

/**
 * Typed live-only closure seam for the production run owner.
 *
 * Repository-backed closure observations do not use this input. [EvoMoliorRunService] can supply
 * these counters once its live lifecycle and tool-transport diagnostics are wired to archiving.
 *
 * @param lifecycle Exact lifecycle finalization counters, when captured.
 * @param toolTransport Exact tool-contract transport counters, when captured.
 */
data class RunClosureMetricRuntimeInput(
    val lifecycle: LifecycleClosureMeasurements? = null,
    val toolTransport: ToolTransportClosureMeasurements? = null,
)

/**
 * Exact run scope supplied to Section 18.2 metric collectors.
 *
 * @param runId Run being exported.
 * @param workerGroupIds Exact persisted worker-group identities.
 * @param graderGroupIds Exact persisted grader-group identities.
 * @param atlasId Exact worker Atlas identity.
 * @param completedThroughRound Last completed zero-based round, or `-1` before any round.
 * @param acceptanceProfile Explicit publication profile.
 * @param finalOutputSpec Exact receiver-facing output contract, when publication is enabled.
 * @param completionRecord Durable run-completion authority, when already available.
 * @param claimLedgerJsonl Exact claim-ledger bytes when the output contract declares coverage.
 * @param closureRuntimeInput Live-only closure measurements that persistence cannot reconstruct.
 * @param acceptedDecisionPackage In-memory accepted package candidate used before atomic archive
 * publication; it is not treated as durable authority unless the terminal archive commits it.
 */
data class RunMetricsExportContext(
    val runId: String,
    val workerGroupIds: List<String>,
    val graderGroupIds: List<String>,
    val atlasId: String,
    val completedThroughRound: Int,
    val acceptanceProfile: AcceptanceProfile,
    val finalOutputSpec: FinalOutputSpec? = null,
    val completionRecord: RunCompletionRecord? = null,
    val claimLedgerJsonl: String? = null,
    val closureRuntimeInput: RunClosureMetricRuntimeInput? = null,
    val acceptedDecisionPackage: ReceiverDecisionPackage? = null,
) {
    init {
        require(runId.isNotBlank() && atlasId.isNotBlank()) {
            "Run-metrics scope requires exact run and Atlas identities"
        }
        require(workerGroupIds.isNotEmpty() && workerGroupIds.none(String::isBlank)) {
            "Run-metrics scope requires exact worker groups"
        }
        require(workerGroupIds == workerGroupIds.distinct().sorted()) {
            "Run-metrics worker groups must be sorted and distinct"
        }
        require(graderGroupIds.none(String::isBlank)) {
            "Run-metrics grader groups must not be blank"
        }
        require(graderGroupIds == graderGroupIds.distinct().sorted()) {
            "Run-metrics grader groups must be sorted and distinct"
        }
        require(completedThroughRound >= -1) {
            "Run-metrics completed round cannot be less than -1"
        }
        require(completionRecord == null || completionRecord.runId == runId) {
            "Run-metrics completion authority belongs to another run"
        }
        require(
            acceptedDecisionPackage == null ||
                acceptedDecisionPackage.runId == runId &&
                acceptedDecisionPackage.hasValidHashes()
        ) { "Run-metrics accepted package candidate is invalid or foreign" }
    }
}

/**
 * Typed reason an applicable metric has no numeric value.
 *
 * Only [MISSING_MEASUREMENT_INFRASTRUCTURE] blocks non-normal acceptance publication. Source data
 * may be honestly unavailable without pretending that the exporter itself is missing.
 */
enum class RunMetricUnavailableCause {
    MISSING_MEASUREMENT_INFRASTRUCTURE,
    SOURCE_AUTHORITY_UNAVAILABLE,
    PROVIDER_PRICE_UNAVAILABLE,
}

/** Typed internal result returned by one canonical metric collector. */
sealed interface RunMetricCollectionResult {
    /** Catalog metric represented by this result. */
    val metricId: String

    /** Exact evidence references supporting the result. */
    val evidenceRefs: List<String>

    /**
     * Measured metric.
     *
     * @param metricId Catalog metric.
     * @param numerator Measured numerator, when the metric exposes one.
     * @param denominator Positive measured denominator, when the metric exposes one.
     * @param value Measured finite non-negative value.
     * @param unit Optional representation label.
     * @param evidenceRefs Exact sorted evidence references.
     */
    data class Available(
        override val metricId: String,
        val numerator: Double? = null,
        val denominator: Double? = null,
        val value: Double,
        val unit: String?,
        override val evidenceRefs: List<String>,
    ) : RunMetricCollectionResult {
        init {
            require(value.isFinite() && value >= 0.0) {
                "Available metric value must be finite and non-negative"
            }
            require(numerator == null || numerator.isFinite() && numerator >= 0.0) {
                "Available metric numerator must be finite and non-negative"
            }
            require(denominator == null || denominator.isFinite() && denominator > 0.0) {
                "Available metric denominator must be null or positive"
            }
        }
    }

    /**
     * Applicable metric whose value is unavailable.
     *
     * @param metricId Catalog metric.
     * @param cause Typed distinction between missing infrastructure and honest source absence.
     * @param reason Human-readable diagnostic serialized into the metric document.
     * @param numerator Known numerator, when available.
     * @param denominator Known positive denominator, or null when unknown.
     * @param unit Optional representation label.
     * @param evidenceRefs Exact sorted evidence references.
     */
    data class Unavailable(
        override val metricId: String,
        val cause: RunMetricUnavailableCause,
        val reason: String,
        val numerator: Double? = null,
        val denominator: Double? = null,
        val unit: String? = null,
        override val evidenceRefs: List<String>,
    ) : RunMetricCollectionResult {
        init {
            require(reason.isNotBlank()) { "Unavailable metric requires a reason" }
            require(numerator == null || numerator.isFinite() && numerator >= 0.0) {
                "Unavailable metric numerator must be finite and non-negative"
            }
            require(denominator == null || denominator.isFinite() && denominator > 0.0) {
                "Unavailable metric denominator must be null or positive"
            }
        }
    }

    /**
     * Metric with known zero eligibility or an inapplicable declared run profile.
     *
     * @param metricId Catalog metric.
     * @param reason Exact applicability diagnostic.
     * @param unit Optional representation label.
     * @param evidenceRefs Exact sorted evidence references.
     */
    data class NotApplicable(
        override val metricId: String,
        val reason: String,
        val unit: String? = null,
        override val evidenceRefs: List<String>,
    ) : RunMetricCollectionResult {
        init {
            require(reason.isNotBlank()) { "Not-applicable metric requires a reason" }
        }
    }
}

/** Read-only authority boundary used by [RunMetricsV1Exporter]. */
fun interface RunMetricCollector {
    /**
     * Collects one exact catalog metric.
     *
     * @param metricId Catalog metric requested in canonical order.
     * @param context Exact run scope.
     * @return Typed metric result.
     */
    suspend fun collect(
        metricId: String,
        context: RunMetricsExportContext,
    ): RunMetricCollectionResult
}

/**
 * Complete canonical metric document plus typed publication-blocking collector failures.
 *
 * @param metrics Complete 21-observation document and exact 12-observation closure block.
 * @param missingMeasurementInfrastructureMetricIds Catalog IDs whose collectors are unavailable.
 */
data class RunMetricsV1ExportResult(
    val metrics: RunMetricsV1,
    val missingMeasurementInfrastructureMetricIds: Set<String>,
) {
    init {
        require(
            missingMeasurementInfrastructureMetricIds.all {
                it in RunMetricCatalog.orderedMetricIds ||
                    it in RunClosureMetricCatalog.orderedMetricIds
            }
        ) {
            "Missing-infrastructure IDs must belong to the run-metric catalog"
        }
    }

    /**
     * Fails publication when an explicit acceptance profile lacks measurement infrastructure.
     *
     * @param acceptanceProfile Explicit run profile.
     */
    fun requirePublishable(acceptanceProfile: AcceptanceProfile) {
        if (
            acceptanceProfile != AcceptanceProfile.NORMAL &&
            missingMeasurementInfrastructureMetricIds.isNotEmpty()
        ) {
            error(
                "Acceptance publication is missing run-metric measurement infrastructure: " +
                    missingMeasurementInfrastructureMetricIds.sorted().joinToString()
            )
        }
        if (acceptanceProfile != AcceptanceProfile.NORMAL) {
            check(
                metrics.closureObservations.map(RunMetricObservation::metricId) ==
                    RunClosureMetricCatalog.orderedMetricIds
            ) {
                "Acceptance publication requires the exact 12-observation closure block"
            }
        }
        if (acceptanceProfile == AcceptanceProfile.APPENDIX_B_FULL) {
            AppendixBFullPublicationPolicy.requireAccepted(metrics)
        }
    }
}

/**
 * Substantive, fail-closed publication policy for the full Appendix-B acceptance profile.
 *
 * Measurement availability and acceptance are deliberately separate: a complete metric document
 * can faithfully prove that a run failed its acceptance obligations.
 */
internal object AppendixBFullPublicationPolicy {
    private const val ATOMIC_COMPLETION = "atomic_node_completion_rate"
    private const val MANDATORY_COVERAGE = "mandatory_node_coverage"
    private const val TOOL_NATIVE_AGENT_RATE = "tool_native_agent_rate"
    private const val TOOL_REUSE_FACTOR = "tool_reuse_factor"
    private const val SIMULATION_INFLUENCE = "simulation_influence_rate"
    private const val GRADER_BUNDLE_VALIDITY = "grader_bundle_validity"
    private const val NODE_TO_FINAL_LINEAGE = "node_to_final_lineage"
    private const val SCORE_REPLAY_ERROR = "score_replay_error"
    private const val EXIT_IDENTITY_CONFLICTS = "exit_identity_conflicts"
    private const val EXIT_FINALIZATION_RETRIES = "exit_finalization_retries"
    private const val TOOL_TRANSPORT_FAILURE_RATE = "tool_contract_transport_failure_rate"
    private const val REPAIR_DIAGNOSTICS_EMITTED = "tool_repair_diagnostics_emitted"
    private const val REPAIR_DIAGNOSTIC_CONSUMPTION =
        "tool_repair_diagnostic_consumption_rate"
    private const val REPAIR_TO_PROMOTION = "repair_to_promotion_rate"
    private const val REPEATED_IDENTICAL_FAILURE =
        "repeated_identical_tool_failure_rate"
    private const val RECEIVER_COMPONENT_COVERAGE = "receiver_component_coverage"
    private const val INTERIM_SNAPSHOT_COVERAGE = "interim_snapshot_coverage"
    private const val RECOVERY_COMPONENT_COVERAGE = "recovery_component_coverage"
    private const val PROVISIONAL_CLAIM_AUTHORITY =
        "provisional_claim_with_authority_rate"
    private const val DETERMINISTIC_CONTENT =
        "accepted_package_deterministic_content_rate"

    fun requireAccepted(metrics: RunMetricsV1) {
        val failures = failures(metrics)
        check(failures.isEmpty()) {
            "Appendix-B full acceptance metrics failed: ${failures.joinToString("; ")}"
        }
    }

    /** Returns every substantive Appendix-B metric failure without short-circuiting. */
    fun failures(metrics: RunMetricsV1): List<String> {
        val observations = metrics.observations.associateBy(RunMetricObservation::metricId)
        val closure = metrics.closureObservations.associateBy(RunMetricObservation::metricId)
        val missingClosureMetricIds =
            RunClosureMetricCatalog.orderedMetricIds.filterNot(closure::containsKey)
        if (missingClosureMetricIds.isNotEmpty()) {
            return listOf(
                "required closure observations are missing: " +
                    missingClosureMetricIds.joinToString()
            )
        }
        return buildList {
            requireCompleteRatio(observations.getValue(ATOMIC_COMPLETION), ATOMIC_COMPLETION)
                ?.let(::add)
            requireCompleteRatio(observations.getValue(MANDATORY_COVERAGE), MANDATORY_COVERAGE)
                ?.let(::add)
            requirePositiveNumerator(
                observations.getValue(TOOL_NATIVE_AGENT_RATE),
                TOOL_NATIVE_AGENT_RATE
            )?.let(::add)
            requirePositiveNumerator(
                observations.getValue(TOOL_REUSE_FACTOR),
                TOOL_REUSE_FACTOR
            )?.let(::add)
            requirePositiveNumerator(
                observations.getValue(SIMULATION_INFLUENCE),
                SIMULATION_INFLUENCE
            )?.let(::add)
            requireCompleteRatio(
                observations.getValue(GRADER_BUNDLE_VALIDITY),
                GRADER_BUNDLE_VALIDITY
            )?.let(::add)
            requireCompleteRatio(
                observations.getValue(NODE_TO_FINAL_LINEAGE),
                NODE_TO_FINAL_LINEAGE
            )?.let(::add)
            requireExactZero(observations.getValue(SCORE_REPLAY_ERROR), SCORE_REPLAY_ERROR)
                ?.let(::add)
            requireExactZero(
                closure.getValue(EXIT_IDENTITY_CONFLICTS),
                EXIT_IDENTITY_CONFLICTS
            )?.let(::add)
            requireExactZero(
                closure.getValue(EXIT_FINALIZATION_RETRIES),
                EXIT_FINALIZATION_RETRIES
            )?.let(::add)
            requireAvailableOrNotApplicable(
                closure.getValue(TOOL_TRANSPORT_FAILURE_RATE),
                TOOL_TRANSPORT_FAILURE_RATE
            )?.let(::add)
            requireAvailableOrNotApplicable(
                closure.getValue(REPAIR_DIAGNOSTICS_EMITTED),
                REPAIR_DIAGNOSTICS_EMITTED
            )?.let(::add)
            requireAvailableOrNotApplicable(
                closure.getValue(REPAIR_DIAGNOSTIC_CONSUMPTION),
                REPAIR_DIAGNOSTIC_CONSUMPTION
            )?.let(::add)
            requirePositiveWhenApplicable(
                closure.getValue(REPAIR_TO_PROMOTION),
                REPAIR_TO_PROMOTION
            )?.let(::add)
            requireAvailableOrNotApplicable(
                closure.getValue(REPEATED_IDENTICAL_FAILURE),
                REPEATED_IDENTICAL_FAILURE
            )?.let(::add)
            requireCompleteRatio(
                closure.getValue(RECEIVER_COMPONENT_COVERAGE),
                RECEIVER_COMPONENT_COVERAGE
            )?.let(::add)
            requireCompleteRatio(
                closure.getValue(INTERIM_SNAPSHOT_COVERAGE),
                INTERIM_SNAPSHOT_COVERAGE
            )?.let(::add)
            requireCompleteWhenApplicable(
                closure.getValue(RECOVERY_COMPONENT_COVERAGE),
                RECOVERY_COMPONENT_COVERAGE
            )?.let(::add)
            requireCompleteWhenApplicable(
                closure.getValue(PROVISIONAL_CLAIM_AUTHORITY),
                PROVISIONAL_CLAIM_AUTHORITY
            )?.let(::add)
            requireAvailable(
                closure.getValue(DETERMINISTIC_CONTENT),
                DETERMINISTIC_CONTENT
            )?.let(::add)
        }
    }

    private fun requireAvailable(
        observation: RunMetricObservation,
        metricId: String,
    ): String? = if (observation.status == MetricAvailability.AVAILABLE) {
        null
    } else {
        "$metricId must be AVAILABLE"
    }

    private fun requireAvailableOrNotApplicable(
        observation: RunMetricObservation,
        metricId: String,
    ): String? = if (
        observation.status == MetricAvailability.AVAILABLE ||
        observation.status == MetricAvailability.NOT_APPLICABLE
    ) {
        null
    } else {
        "$metricId must be AVAILABLE or NOT_APPLICABLE"
    }

    private fun requireCompleteWhenApplicable(
        observation: RunMetricObservation,
        metricId: String,
    ): String? = if (observation.status == MetricAvailability.NOT_APPLICABLE) {
        null
    } else {
        requireCompleteRatio(observation, metricId)
    }

    private fun requirePositiveWhenApplicable(
        observation: RunMetricObservation,
        metricId: String,
    ): String? = if (observation.status == MetricAvailability.NOT_APPLICABLE) {
        null
    } else {
        requirePositiveNumerator(observation, metricId)
    }

    private fun requireCompleteRatio(
        observation: RunMetricObservation,
        metricId: String
    ): String? {
        if (observation.status != MetricAvailability.AVAILABLE) {
            return "$metricId must be AVAILABLE"
        }
        val numerator = observation.numerator
            ?: return "$metricId requires an exact numerator"
        val denominator = observation.denominator
            ?: return "$metricId requires an exact denominator"
        return if (numerator == denominator && observation.value == 1.0) {
            null
        } else {
            "$metricId must be complete ($numerator/$denominator, value=${observation.value})"
        }
    }

    private fun requirePositiveNumerator(
        observation: RunMetricObservation,
        metricId: String
    ): String? {
        if (observation.status != MetricAvailability.AVAILABLE) {
            return "$metricId must be AVAILABLE"
        }
        val numerator = observation.numerator
            ?: return "$metricId requires an exact numerator"
        val denominator = observation.denominator
            ?: return "$metricId requires an exact denominator"
        return if (numerator > 0.0 && denominator > 0.0 && observation.value != null) {
            null
        } else {
            "$metricId requires at least one successful measured event"
        }
    }

    private fun requireExactZero(
        observation: RunMetricObservation,
        metricId: String
    ): String? = when {
        observation.status != MetricAvailability.AVAILABLE ->
            "$metricId must be AVAILABLE"
        observation.value != 0.0 ->
            "$metricId must be exactly zero (value=${observation.value})"
        else -> null
    }
}

/**
 * Exports the exact Section 18.2 catalog without deriving availability from diagnostic text.
 *
 * @param collector Typed read-only collector for exact run authority.
 */
class RunMetricsV1Exporter(
    private val collector: RunMetricCollector,
) {
    /**
     * Collects all 21 historical metrics and the separate 12-observation closure block.
     *
     * @param context Exact run scope.
     * @return Complete document and typed missing-infrastructure IDs.
     */
    suspend fun export(context: RunMetricsExportContext): RunMetricsV1ExportResult {
        val missingInfrastructure = linkedSetOf<String>()
        suspend fun observation(metricId: String): RunMetricObservation {
            val result = collector.collect(metricId, context)
            require(result.metricId == metricId) {
                "Metric collector returned ${result.metricId} while $metricId was requested"
            }
            require(
                result.evidenceRefs.isNotEmpty() &&
                    result.evidenceRefs.none(String::isBlank) &&
                    result.evidenceRefs == result.evidenceRefs.distinct().sorted()
            ) {
                "Metric collector evidence must be non-empty, sorted, and distinct for $metricId"
            }
            return when (result) {
                is RunMetricCollectionResult.Available -> RunMetricObservation(
                    metricId = metricId,
                    status = MetricAvailability.AVAILABLE,
                    numerator = result.numerator,
                    denominator = result.denominator,
                    value = result.value,
                    unit = result.unit,
                    evidenceRefs = result.evidenceRefs,
                )

                is RunMetricCollectionResult.Unavailable -> {
                    if (
                        result.cause ==
                        RunMetricUnavailableCause.MISSING_MEASUREMENT_INFRASTRUCTURE
                    ) {
                        missingInfrastructure += metricId
                    }
                    RunMetricObservation(
                        metricId = metricId,
                        status = MetricAvailability.UNAVAILABLE,
                        numerator = result.numerator,
                        denominator = result.denominator,
                        unit = result.unit,
                        evidenceRefs = result.evidenceRefs,
                        unavailableReason = result.reason,
                    )
                }

                is RunMetricCollectionResult.NotApplicable -> RunMetricObservation(
                    metricId = metricId,
                    status = MetricAvailability.NOT_APPLICABLE,
                    unit = result.unit,
                    evidenceRefs = result.evidenceRefs,
                    unavailableReason = result.reason,
                )
            }
        }
        val observations = RunMetricCatalog.orderedMetricIds.map { observation(it) }
        val closureObservations = RunClosureMetricCatalog.orderedMetricIds.map { observation(it) }
        return RunMetricsV1ExportResult(
            metrics = RunMetricsV1(
                runId = context.runId,
                decisionAuthorityThroughRound = context.completedThroughRound,
                observations = observations,
                closureObservations = closureObservations,
            ),
            missingMeasurementInfrastructureMetricIds = missingInfrastructure,
        )
    }
}
