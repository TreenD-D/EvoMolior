package com.achulkov.evomolior.core.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SourceReportCheckerTest {
    private val text = "header\ntrap 'rm -rf \"${'$'}SNAPSHOT_DIR\"' EXIT HUP INT TERM\nexec \"${'$'}JAVACMD\" \"${'$'}@\""
    private val sources = listOf(SourceReportInput("launcher", "input/launcher", text))

    @Test
    fun `exact source quotes including variables are preserved`() {
        val result = SourceReportChecker.check(
            "Registers `trap 'rm -rf \"${'$'}SNAPSHOT_DIR\"' EXIT HUP INT TERM` (launcher:L2).\n" +
                "Executes `exec \"${'$'}JAVACMD\" \"${'$'}@\"` (input/launcher:L3).", sources)
        assertTrue(result.issues.isEmpty(), result.renderMarkdown())
        assertEquals(2, result.checkedCitations)
        assertEquals(2, result.checkedQuotations)
    }

    @Test
    fun `corrupted quotes remain advisory and produce bounded correction feedback`() {
        val result = SourceReportChecker.check("Registers `trap 'rm -rf \"\"'` (launcher:L2).", sources)
        assertEquals("QUOTATION_MISMATCH", result.issues.single().code)
        assertNotNull(result.correctionPrompt())
        assertTrue(result.renderMarkdown().contains("Advisory only"))
    }

    @Test
    fun `plain single-quoted source excerpt is checked`() {
        val result = SourceReportChecker.check("Registers trap 'rm -rf \"\"' (launcher:L2).", sources)
        assertEquals("QUOTATION_MISMATCH", result.issues.single().code)
    }

    @Test
    fun `paraphrases and detached quoted prose are not exact quotes`() {
        val result = SourceReportChecker.check(
            "The launcher replaces its shell (launcher:L3).\n" +
                "\"safe\" is a description, not a quote of the source (launcher:L2-L3).", sources)
        assertTrue(result.issues.isEmpty(), result.renderMarkdown())
        assertEquals(0, result.checkedQuotations)
    }

    @Test
    fun `bad ranges aliases and external references are explicit limitations`() {
        val result = SourceReportChecker.check(
            "launcher:L0 launcher:L5-L2 launcher:L999999999999999999999 launcher:L1-L999999999999999999999 unknown:L1 https://example.test",
            sources)
        assertEquals(4, result.issues.count { it.code == "SOURCE_RANGE_INVALID" })
        assertTrue(result.issues.any { it.code == "UNKNOWN_SOURCE" })
        assertTrue(result.issues.any { it.code == "EXTERNAL_SOURCES_NOT_CHECKED" })
    }

    @Test
    fun `duplicate basename is never selected arbitrarily`() {
        val result = SourceReportChecker.check("launcher:L1",
            sources + SourceReportInput("launcher", "other/launcher", "other"))
        assertTrue(result.issues.any { it.code == "AMBIGUOUS_SOURCE" })
    }

    @Test
    fun `absent or unreadable sources do not fabricate successful checking`() {
        assertEquals("NO_SOURCE_AUTHORITY",
            SourceReportChecker.check("A report", emptyList()).issues.single().code)
        val result = SourceReportChecker.check("launcher:L2",
            listOf(SourceReportInput("launcher", "input/launcher", null, "hash mismatch")))
        assertEquals("SOURCE_UNAVAILABLE", result.issues.single().code)
        assertEquals(0, result.checkedCitations)
    }
}
