package com.achulkov.evomolior.core.metrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class RunMetricsV1Test {
    @Test
    fun `catalog has the exact 21 metric IDs in Section 18_2 order`() {
        assertEquals(
            listOf(
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
            ),
            RunMetricCatalog.orderedMetricIds
        )
        assertEquals(21, RunMetricCatalog.orderedMetricIds.size)
        assertEquals(21, RunMetricCatalog.orderedMetricIds.distinct().size)
    }

    @Test
    fun `closure catalog has the exact 12 blueprint metric IDs in order`() {
        assertEquals(
            listOf(
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
            ),
            RunClosureMetricCatalog.orderedMetricIds,
        )
    }

    @Test
    fun `historical callers may omit closure block but partial closure is rejected`() {
        assertTrue(validRun().closureObservations.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            validRun().copy(
                closureObservations = listOf(
                    unavailable(RunClosureMetricCatalog.orderedMetricIds.first())
                )
            )
        }
    }

    @Test
    fun `available metrics support ratios counts and profile-compatible values`() {
        val ratio = available(
            metricId = "useful_exit_rate",
            numerator = 3.0,
            denominator = 4.0,
            value = 0.75,
            unit = "ratio"
        )
        val count = available(
            metricId = "grader_requirement_novelty",
            numerator = 7.0,
            value = 7.0,
            unit = "count"
        )
        val profileCompatible = available(
            metricId = "evaluation_independence",
            value = 1.0,
            unit = "profile-compatible"
        )

        val metrics = validRun(
            overrides = mapOf(
                ratio.metricId to ratio,
                count.metricId to count,
                profileCompatible.metricId to profileCompatible
            )
        )

        assertEquals(RunMetricsV1.SCHEMA_VERSION, metrics.schemaVersion)
        assertEquals(0.75, metrics.observations.first().value)
        assertEquals(7.0, metrics.observations[13].value)
        assertEquals(1.0, metrics.observations[15].value)
    }

    @Test
    fun `unknown denominator is null and never encoded as zero`() {
        val observation = unavailable(
            metricId = "useful_exit_rate",
            numerator = 3.0,
            denominator = null
        )

        assertEquals(3.0, observation.numerator)
        assertNull(observation.denominator)
        validRun(mapOf(observation.metricId to observation))
        available(
            metricId = "useful_exit_rate",
            numerator = 0.0,
            denominator = 1.0,
            value = 0.0
        )

        assertFailsWith<IllegalArgumentException> {
            observation.copy(denominator = 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            available(
                metricId = "useful_exit_rate",
                numerator = 0.0,
                denominator = 0.0,
                value = 0.0
            )
        }
    }

    @Test
    fun `provider price may be unavailable while accepted-node denominator is known`() {
        val observation = unavailable(
            metricId = "provider_cost_per_accepted_node",
            denominator = 5.0,
            reason = "Provider price was not supplied"
        )

        val metrics = validRun(mapOf(observation.metricId to observation))

        assertEquals(5.0, metrics.observations[19].denominator)
        assertNull(metrics.observations[19].value)
    }

    @Test
    fun `run requires exact unique complete canonical metric order`() {
        val observations = validObservations()

        assertFailsWith<IllegalArgumentException> {
            RunMetricsV1(runId = "run-1", observations = observations.dropLast(1))
        }
        assertFailsWith<IllegalArgumentException> {
            RunMetricsV1(
                runId = "run-1",
                observations = observations.toMutableList().apply {
                    this[lastIndex] = first()
                }
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RunMetricsV1(
                runId = "run-1",
                observations = observations.toMutableList().apply {
                    val first = removeAt(0)
                    add(1, first)
                }
            )
        }
    }

    @Test
    fun `all numeric fields reject nonfinite and negative values`() {
        val base = available(
            metricId = "useful_exit_rate",
            numerator = 1.0,
            denominator = 2.0,
            value = 0.5
        )
        val invalidNumbers = listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)

        invalidNumbers.forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { base.copy(numerator = invalid) }
            assertFailsWith<IllegalArgumentException> { base.copy(denominator = invalid) }
            assertFailsWith<IllegalArgumentException> { base.copy(value = invalid) }
        }
    }

    @Test
    fun `evidence references must be nonblank sorted and distinct`() {
        val base = unavailable("useful_exit_rate")

        assertFailsWith<IllegalArgumentException> {
            base.copy(evidenceRefs = emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            base.copy(evidenceRefs = listOf("evidence/b", "evidence/a"))
        }
        assertFailsWith<IllegalArgumentException> {
            base.copy(evidenceRefs = listOf("evidence/a", "evidence/a"))
        }
        assertFailsWith<IllegalArgumentException> {
            base.copy(evidenceRefs = listOf(""))
        }
    }

    @Test
    fun `availability statuses enforce value and reason invariants`() {
        assertFailsWith<IllegalArgumentException> {
            available("useful_exit_rate", value = null)
        }
        assertFailsWith<IllegalArgumentException> {
            available("useful_exit_rate").copy(unavailableReason = "Not measured")
        }
        assertFailsWith<IllegalArgumentException> {
            unavailable("useful_exit_rate").copy(value = 0.5)
        }
        assertFailsWith<IllegalArgumentException> {
            unavailable("useful_exit_rate", reason = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            unavailable("useful_exit_rate").copy(unavailableReason = null)
        }

        val notApplicable = RunMetricObservation(
            metricId = "useful_exit_rate",
            status = MetricAvailability.NOT_APPLICABLE,
            unit = null,
            evidenceRefs = listOf("evidence/profile"),
            unavailableReason = "No eligible ordinary agents"
        )
        assertNull(notApplicable.value)
        assertFailsWith<IllegalArgumentException> {
            notApplicable.copy(numerator = 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            notApplicable.copy(denominator = 1.0)
        }
        assertFailsWith<IllegalArgumentException> {
            notApplicable.copy(value = 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            notApplicable.copy(unavailableReason = "")
        }
        assertFailsWith<IllegalArgumentException> {
            notApplicable.copy(unavailableReason = null)
        }
    }

    @Test
    fun `unit must be null or nonblank`() {
        available("useful_exit_rate", unit = null)
        available("useful_exit_rate", unit = "ratio")

        assertFailsWith<IllegalArgumentException> {
            available("useful_exit_rate", unit = " ")
        }
    }

    @Test
    fun `schema and identifiers are nonblank and fixed`() {
        assertFailsWith<IllegalArgumentException> {
            validRun().copy(schemaVersion = "run-metrics-v2")
        }
        assertFailsWith<IllegalArgumentException> {
            validRun().copy(runId = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            unavailable(" ")
        }
    }

    private fun validRun(
        overrides: Map<String, RunMetricObservation> = emptyMap()
    ): RunMetricsV1 = RunMetricsV1(
        runId = "run-1",
        observations = validObservations(overrides)
    )

    private fun validObservations(
        overrides: Map<String, RunMetricObservation> = emptyMap()
    ): List<RunMetricObservation> =
        RunMetricCatalog.orderedMetricIds.map { metricId ->
            overrides[metricId] ?: unavailable(metricId)
        }

    private fun available(
        metricId: String,
        numerator: Double? = null,
        denominator: Double? = null,
        value: Double? = 1.0,
        unit: String? = null
    ): RunMetricObservation = RunMetricObservation(
        metricId = metricId,
        status = MetricAvailability.AVAILABLE,
        numerator = numerator,
        denominator = denominator,
        value = value,
        unit = unit,
        evidenceRefs = listOf("evidence/a", "evidence/b")
    )

    private fun unavailable(
        metricId: String,
        numerator: Double? = null,
        denominator: Double? = null,
        reason: String = "Measurement source unavailable"
    ): RunMetricObservation = RunMetricObservation(
        metricId = metricId,
        status = MetricAvailability.UNAVAILABLE,
        numerator = numerator,
        denominator = denominator,
        unit = null,
        evidenceRefs = listOf("evidence/a"),
        unavailableReason = reason
    )
}
