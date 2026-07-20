package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.metrics.MetricAvailability
import com.achulkov.evomolior.core.metrics.RunMetricCatalog
import com.achulkov.evomolior.core.metrics.RunMetricObservation
import com.achulkov.evomolior.core.metrics.RunMetricsV1

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
 * @param metrics Complete 21-observation document.
 * @param missingMeasurementInfrastructureMetricIds Catalog IDs whose collectors are unavailable.
 */
data class RunMetricsV1ExportResult(
    val metrics: RunMetricsV1,
    val missingMeasurementInfrastructureMetricIds: Set<String>,
) {
    init {
        require(
            missingMeasurementInfrastructureMetricIds.all {
                it in RunMetricCatalog.orderedMetricIds
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
     * Collects all 21 metrics in canonical order.
     *
     * @param context Exact run scope.
     * @return Complete document and typed missing-infrastructure IDs.
     */
    suspend fun export(context: RunMetricsExportContext): RunMetricsV1ExportResult {
        val missingInfrastructure = linkedSetOf<String>()
        val observations = RunMetricCatalog.orderedMetricIds.map { metricId ->
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
            when (result) {
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
        return RunMetricsV1ExportResult(
            metrics = RunMetricsV1(
                runId = context.runId,
                observations = observations,
            ),
            missingMeasurementInfrastructureMetricIds = missingInfrastructure,
        )
    }
}
