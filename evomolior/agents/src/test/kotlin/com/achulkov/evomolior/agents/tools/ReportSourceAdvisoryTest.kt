package com.achulkov.evomolior.agents.tools

import com.achulkov.evomolior.core.source.SourceReportInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportSourceAdvisoryTest {
    private val source = SourceReportInput("launcher", "launcher", "exec \"\$JAVACMD\" \"\$@\"")

    @Test
    fun `external source limitations do not consume correction or ask for seed-file evidence`() = runTest {
        val advisory = ReportSourceAdvisory { listOf(source) }
        val limitation = advisory.check("Provider documentation: https://example.org/data")!!
        assertTrue(limitation.contains("EXTERNAL_SOURCES_NOT_CHECKED"))
        assertFalse(limitation.contains("Make one correction pass"))
        assertTrue(advisory.check("`wrong quote` (launcher:L1)")!!.contains("Make one correction pass"))
    }

    @Test
    fun `all DOCs checked but only first faulty DOC requests correction`() = runTest {
        var loads = 0
        val advisory = ReportSourceAdvisory { loads++; listOf(source) }
        val first = advisory.check("`exec \"\" \"\"` (launcher:L1)")!!
        assertTrue(first.contains("Make one correction pass"))
        val second = advisory.check("`exec \"\" \"\"` (launcher:L1)")!!
        assertTrue(second.contains("QUOTATION_MISMATCH"))
        assertFalse(second.contains("Make one correction pass"))
        assertNull(advisory.check("`exec \"\$JAVACMD\" \"\$@\"` (launcher:L1)"))
        assertEquals(1, loads)
    }

    @Test
    fun `passing report does not consume the correction request`() = runTest {
        val advisory = ReportSourceAdvisory { listOf(source) }
        assertNull(advisory.check("`exec \"\$JAVACMD\" \"\$@\"` (launcher:L1)"))
        assertTrue(advisory.check("`exec \"\" \"\"` (launcher:L1)")!!.contains("Make one correction pass"))
    }

    @Test
    fun `concurrent DOC checks request exactly one correction and load once`() = runTest {
        var loads = 0
        val advisory = ReportSourceAdvisory { loads++; listOf(source) }
        val results = (1..8).map {
            async(Dispatchers.Default) { advisory.check("`exec \"\" \"\"` (launcher:L1)")!! }
        }.awaitAll()
        assertEquals(1, results.count { it.contains("Make one correction pass") })
        assertEquals(1, loads)
    }

    @Test
    fun `absent sources remain an advisory without instructing invented facts`() = runTest {
        val feedback = ReportSourceAdvisory { emptyList() }.check("Useful report")!!
        assertTrue(feedback.contains("NO_SOURCE_AUTHORITY"))
        assertTrue(feedback.contains("Do not invent sources"))
        assertTrue(feedback.contains("retain the useful report"))
        assertTrue(feedback.contains("No correction is requested"))
        assertFalse(feedback.contains("Make one correction pass"))
    }

    @Test
    fun `unverified sources never request correction`() = runTest {
        val feedback = ReportSourceAdvisory {
            listOf(source.copy(text = null, unavailableReason = "SHA-256 mismatch"))
        }.check("Useful report")!!
        assertTrue(feedback.contains("SOURCE_UNAVAILABLE"))
        assertTrue(feedback.contains("No correction is requested"))
        assertFalse(feedback.contains("Make one correction pass"))
    }
}
