package com.achulkov.evomolior.cli

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GraderGroupsOptionTest {
    private val input = createTempDirectory("grader-groups-input")
    private val output = createTempDirectory("grader-groups-output")

    private fun parse(vararg extra: String) = CliArguments.parse(
        arrayOf(
            "-prompt", "Compare two providers",
            "-input", input.toString(),
            "-output", output.toString(),
            "-openrouter-api-key", "sk-test",
            "-rounds", "1",
        ) + extra
    )!!

    @Test
    fun `grader groups default to production and accept one to three`() {
        assertNull(parse().graderGroupCount)
        assertEquals(1, parse("-grader-groups", "1").graderGroupCount)
        assertEquals(3, parse("--grader-groups", "3").graderGroupCount)
    }

    @Test
    fun `out of range values and acceptance profiles are rejected`() {
        listOf("0", "4", "two").forEach { value ->
            assertFailsWith<CliUsageException>(value) { parse("-grader-groups", value) }
        }
        val acceptance = assertFailsWith<CliUsageException> {
            parse(
                "-grader-groups", "1",
                "-acceptance-profile", "runtime-usability-smoke",
                "-llm-backend", "codex-chatgpt",
                "-council-execution-purpose", "appendix-b-acceptance"
            )
        }
        kotlin.test.assertTrue(acceptance.message.orEmpty().contains("-grader-groups"), acceptance.message)
    }
}
