package com.achulkov.evomolior.api.service

import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that run-log signature scanning catches the failures called out by the remediation log.
 */
class RunLogSignatureScannerTest {

    @Test
    fun `scanner detects deprecated model and empty tool response signatures`() {
        val logPath = createTempFile(prefix = "run-log-signatures", suffix = ".txt")
        logPath.writeText(
            """
            19:28:59.871 E [grader-0] Critic review failed for submission 7c9822e6-81ab-4df2-8756-0ef5c2adf805
            Status code: 404
            Error body:
            {"error":{"message":"Grok 4 Fast is deprecated. xAI recommends switching to Grok 4.3","code":404}}
            19:14:24.451 E [group-0] AIAgentStuckInTheNodeException: nodeSendToolResult
            19:14:24.452 E [group-0] AIAgentMaxNumberOfIterationsReachedException: agent timed out
            19:14:24.453 E [group-0] Unexpected response: no tool calls and no content
            19:14:24.454 E [group-0] OpenRouter API error: Provider returned error (code: 502)
            """.trimIndent()
        )

        val scanner = RunLogSignatureScanner()
        val matches = scanner.scan(logPath)

        assertEquals(5, matches.size)
        assertTrue(scanner.hasFailures(logPath))
        assertEquals("deprecated-grok-fast", matches[0].signatureId)
        assertEquals(4, matches[0].lineNumber)
        assertEquals("agent-stuck-in-node", matches[1].signatureId)
        assertEquals(5, matches[1].lineNumber)
        assertEquals("max-iterations-reached", matches[2].signatureId)
        assertEquals(6, matches[2].lineNumber)
        assertEquals("empty-tool-response", matches[3].signatureId)
        assertEquals(7, matches[3].lineNumber)
        assertEquals("openrouter-provider-502", matches[4].signatureId)
        assertEquals(8, matches[4].lineNumber)
    }

    @Test
    fun `scanner stays quiet when the log is clean`() {
        val logPath = createTempFile(prefix = "clean-run-log", suffix = ".txt")
        logPath.writeText(
            """
            19:28:59.871 I GraderRunner: judging phase complete round=1
            19:29:42.351 I GraderRunner.judge [grader-0] submitted 4 scores for group_a
            """.trimIndent()
        )

        val scanner = RunLogSignatureScanner()
        assertFalse(scanner.hasFailures(logPath))
        assertTrue(scanner.scan(logPath).isEmpty())
    }
}
