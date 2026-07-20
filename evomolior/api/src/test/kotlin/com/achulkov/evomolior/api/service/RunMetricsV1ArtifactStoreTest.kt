package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.metrics.MetricAvailability
import com.achulkov.evomolior.core.metrics.RunMetricCatalog
import com.achulkov.evomolior.core.metrics.RunMetricObservation
import com.achulkov.evomolior.core.metrics.RunMetricsV1
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RunMetricsV1ArtifactStoreTest {
    @Test
    fun `store persists exact canonical bytes and reuses them idempotently`() {
        val factory = SubstrateDatabaseFactory(createTempDirectory("metrics-store-").toFile())
        val store = RunMetricsV1ArtifactStore(factory)
        val metrics = metrics("run-1")

        val first = store.persist(metrics)
        val second = store.persist(metrics)
        val recovered = store.readRequired("run-1", first.sha256)

        assertContentEquals(first.bytes, second.bytes)
        assertContentEquals(first.bytes, recovered.bytes)
        assertEquals(metrics, recovered.metrics)
        assertTrue(first.file.invariantSeparatorsPath.endsWith("control/run-metrics-v1.json"))
    }

    @Test
    fun `store rejects changed missing malformed and cross-run authority`() {
        val factory = SubstrateDatabaseFactory(createTempDirectory("metrics-store-reject-").toFile())
        val store = RunMetricsV1ArtifactStore(factory)
        val persisted = store.persist(metrics("run-1"))

        assertFailsWith<IllegalArgumentException> {
            store.persist(
                metrics("run-1").copy(
                    observations = metrics("run-1").observations.toMutableList().apply {
                        this[0] = this[0].copy(unavailableReason = "Changed authority")
                    },
                ),
            )
        }
        assertFailsWith<IllegalStateException> {
            store.readRequired("run-1", "0".repeat(64))
        }
        persisted.file.writeText("{}")
        assertFailsWith<IllegalStateException> {
            store.readRequired("run-1")
        }
        persisted.file.writeBytes(persisted.bytes)
        val runTwoFile = store.file("run-2")
        runTwoFile.parentFile.mkdirs()
        runTwoFile.writeBytes(persisted.bytes)
        assertFailsWith<IllegalArgumentException> {
            store.readRequired("run-2")
        }
    }

    private fun metrics(runId: String) = RunMetricsV1(
        runId = runId,
        observations = RunMetricCatalog.orderedMetricIds.map { metricId ->
            RunMetricObservation(
                metricId = metricId,
                status = MetricAvailability.UNAVAILABLE,
                unit = null,
                evidenceRefs = listOf("authority/$metricId"),
                unavailableReason = "Fixture source is unavailable",
            )
        },
    )
}
