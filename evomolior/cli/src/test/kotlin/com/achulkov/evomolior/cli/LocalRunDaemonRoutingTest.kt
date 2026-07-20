package com.achulkov.evomolior.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class LocalRunDaemonRoutingTest {
    @Test
    fun `pipeline config options never delegate even when config is missing or malformed`() {
        val malformedConfig = createTempDirectory("daemon-routing-").resolve("malformed.json")
            .also { it.writeText("{ not valid JSON") }
        listOf("-pipelineconfig", "-pipeline-config", "--pipeline-config").forEach { option ->
            assertFalse(
                LocalRunDaemonClient.shouldDelegate(arrayOf("run", "-prompt", "x", option), emptyMap()),
                "Missing value for $option must stay inline"
            )
            assertFalse(
                LocalRunDaemonClient.shouldDelegate(
                    arrayOf("run", "-prompt", "x", option, "does-not-exist.json"),
                    emptyMap()
                ),
                "Missing config for $option must stay inline"
            )
            assertFalse(
                LocalRunDaemonClient.shouldDelegate(
                    arrayOf("run", "-prompt", "x", option, malformedConfig.toString()),
                    emptyMap()
                ),
                "Malformed config for $option must stay inline"
            )
        }
    }

    @Test
    fun `escaped acceptance profile pipeline config remains inline despite raw regex bypass`() {
        val escapedProfileConfig = createTempDirectory("daemon-routing-").resolve("escaped-profile.json")
            .also { it.writeText("""{"acceptance\u0050rofile":"appendix-b-full"}""") }
        val args = arrayOf("run", "-prompt", "x", "-pipeline-config", escapedProfileConfig.toString())

        // This captures the raw-regex blind spot that routing must no longer depend on.
        assertFalse(AcceptanceInvocationDetector.requestsAcceptance(args))
        assertFalse(
            LocalRunDaemonClient.shouldDelegate(args, emptyMap())
        )
    }

    @Test
    fun `daemon endpoint rejects every pipeline config request before parsing or execution`() {
        listOf("-pipelineconfig", "-pipeline-config", "--pipeline-config").forEach { option ->
            val rejection = LocalRunDaemonRouting.daemonRunRejection(
                arrayOf("run", "-prompt", "x", option)
            )

            assertNotNull(rejection)
            assertEquals(409, rejection.status)
            assertFalse(rejection.response.completedSuccessfully)
            assertEquals(
                "Pipeline-config runs must execute inline so admission and execution remain in one process.",
                rejection.response.message
            )
        }
    }

    @Test
    fun `normal direct daemon runs remain routable`() {
        val args = arrayOf("run", "-prompt", "x")

        assertNull(LocalRunDaemonRouting.daemonRunRejection(args))
        assertTrue(LocalRunDaemonClient.shouldDelegate(args, emptyMap()))
    }

    @Test
    fun `normal pipeline config is kept inline and rejected by daemon routing`() {
        val pipeline = createTempDirectory("daemon-routing-").resolve("normal.json")
            .also { it.writeText("""{"acceptanceProfile":"normal"}""") }
        val args = arrayOf("run", "-prompt", "x", "-pipeline-config", pipeline.toString())

        assertFalse(LocalRunDaemonClient.shouldDelegate(args, emptyMap()))
        assertNotNull(LocalRunDaemonRouting.daemonRunRejection(args))
    }
}
