package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.metrics.MetricAvailability
import com.achulkov.evomolior.core.metrics.RunMetricCatalog
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunMetricsV1ExporterTest {
    @Test
    fun `export emits the exact 21 metric catalog in canonical order`() = runTest {
        val result = RunMetricsV1Exporter { metricId, _ ->
            available(metricId)
        }.export(context())

        assertEquals(
            RunMetricCatalog.orderedMetricIds,
            result.metrics.observations.map { it.metricId },
        )
        assertEquals(21, result.metrics.observations.size)
        assertTrue(result.missingMeasurementInfrastructureMetricIds.isEmpty())
    }

    @Test
    fun `known zero eligibility is not applicable rather than a zero ratio`() = runTest {
        val result = exporterWithOverride(
            "tool_native_agent_rate",
            RunMetricCollectionResult.NotApplicable(
                metricId = "tool_native_agent_rate",
                reason = "No ordinary agents were eligible for intended tool use",
                unit = "ratio",
                evidenceRefs = listOf("worker-registration/run-1"),
            ),
        ).export(context())

        val observation = result.metrics.observations
            .single { it.metricId == "tool_native_agent_rate" }
        assertEquals(MetricAvailability.NOT_APPLICABLE, observation.status)
        assertNull(observation.numerator)
        assertNull(observation.denominator)
        assertNull(observation.value)
    }

    @Test
    fun `unknown denominator remains null`() = runTest {
        val result = exporterWithOverride(
            "group_frontier_overlap",
            RunMetricCollectionResult.Unavailable(
                metricId = "group_frontier_overlap",
                cause = RunMetricUnavailableCause.SOURCE_AUTHORITY_UNAVAILABLE,
                reason = "Portfolio denominator is unknown",
                numerator = 2.0,
                denominator = null,
                unit = "jaccard-ratio",
                evidenceRefs = listOf("node-portfolio/run-1"),
            ),
        ).export(context())

        val observation = result.metrics.observations
            .single { it.metricId == "group_frontier_overlap" }
        assertEquals(MetricAvailability.UNAVAILABLE, observation.status)
        assertEquals(2.0, observation.numerator)
        assertNull(observation.denominator)
        assertNull(observation.value)
    }

    @Test
    fun `provider price unavailability is valid acceptance evidence`() = runTest {
        val result = exporterWithOverride(
            "provider_cost_per_accepted_node",
            RunMetricCollectionResult.Unavailable(
                metricId = "provider_cost_per_accepted_node",
                cause = RunMetricUnavailableCause.PROVIDER_PRICE_UNAVAILABLE,
                reason = "One or more finalized provider calls have unavailable pricing",
                denominator = 5.0,
                unit = "usd-per-accepted-node",
                evidenceRefs = listOf("cost-ledger/unknown-provider-price"),
            ),
        ).export(context(AcceptanceProfile.APPENDIX_B_FULL))

        result.requirePublishable(AcceptanceProfile.APPENDIX_B_FULL)
        assertTrue(result.missingMeasurementInfrastructureMetricIds.isEmpty())
        val observation = result.metrics.observations[19]
        assertEquals(MetricAvailability.UNAVAILABLE, observation.status)
        assertEquals(5.0, observation.denominator)
        assertNull(observation.value)
    }

    @Test
    fun `known provider and tool price exports a measured accepted-node cost`() = runTest {
        val result = exporterWithOverride(
            "provider_cost_per_accepted_node",
            RunMetricCollectionResult.Available(
                metricId = "provider_cost_per_accepted_node",
                numerator = 12.0,
                denominator = 4.0,
                value = 3.0,
                unit = "usd-per-accepted-node",
                evidenceRefs = listOf(
                    "capture-decision/accepted",
                    "cost-ledger/finalized",
                ),
            ),
        ).export(context(AcceptanceProfile.APPENDIX_B_FULL))

        result.requirePublishable(AcceptanceProfile.APPENDIX_B_FULL)
        val observation = result.metrics.observations[19]
        assertEquals(MetricAvailability.AVAILABLE, observation.status)
        assertEquals(12.0, observation.numerator)
        assertEquals(4.0, observation.denominator)
        assertEquals(3.0, observation.value)
    }

    @Test
    fun `typed missing collector blocks acceptance but not normal publication`() = runTest {
        val result = exporterWithOverride(
            "kg_syntactic_acceptance",
            RunMetricCollectionResult.Unavailable(
                metricId = "kg_syntactic_acceptance",
                cause = RunMetricUnavailableCause.MISSING_MEASUREMENT_INFRASTRUCTURE,
                reason = "Typed KG attempt collector is unavailable",
                unit = "ratio",
                evidenceRefs = listOf("measurement-infrastructure/kg_syntactic_acceptance"),
            ),
        ).export(context(AcceptanceProfile.APPENDIX_B_FULL))

        assertEquals(
            setOf("kg_syntactic_acceptance"),
            result.missingMeasurementInfrastructureMetricIds,
        )
        result.requirePublishable(AcceptanceProfile.NORMAL)
        val failure = assertFailsWith<IllegalStateException> {
            result.requirePublishable(AcceptanceProfile.APPENDIX_B_FULL)
        }
        assertTrue(failure.message.orEmpty().contains("kg_syntactic_acceptance"))
    }

    @Test
    fun `collector identity and evidence are fail closed`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            RunMetricsV1Exporter { _, _ ->
                available("wrong-metric")
            }.export(context())
        }
        assertFailsWith<IllegalArgumentException> {
            RunMetricsV1Exporter { metricId, _ ->
                available(metricId).copy(evidenceRefs = emptyList())
            }.export(context())
        }
        assertFailsWith<IllegalArgumentException> {
            RunMetricsV1Exporter { metricId, _ ->
                available(metricId).copy(evidenceRefs = listOf("z", "a"))
            }.export(context())
        }
    }

    @Test
    fun `available collection rejects a zero denominator`() {
        assertFailsWith<IllegalArgumentException> {
            RunMetricCollectionResult.Available(
                metricId = "useful_exit_rate",
                numerator = 0.0,
                denominator = 0.0,
                value = 0.0,
                unit = "ratio",
                evidenceRefs = listOf("exit/run-1"),
            )
        }
    }

    private fun exporterWithOverride(
        metricId: String,
        override: RunMetricCollectionResult,
    ): RunMetricsV1Exporter = RunMetricsV1Exporter { requestedMetricId, _ ->
        if (requestedMetricId == metricId) override else available(requestedMetricId)
    }

    private fun available(metricId: String) = RunMetricCollectionResult.Available(
        metricId = metricId,
        numerator = 1.0,
        denominator = 2.0,
        value = 0.5,
        unit = "ratio",
        evidenceRefs = listOf("authority/$metricId"),
    )

    private fun context(
        acceptanceProfile: AcceptanceProfile = AcceptanceProfile.NORMAL,
    ) = RunMetricsExportContext(
        runId = "run-1",
        workerGroupIds = listOf("group-0", "group-1"),
        graderGroupIds = listOf("grader-group-0", "grader-group-1"),
        atlasId = "atlas:run-1",
        completedThroughRound = 5,
        acceptanceProfile = acceptanceProfile,
    )
}
