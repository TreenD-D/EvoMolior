package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.source.SourceReportInput
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ReportSourceSidecarsTest {
    @Test
    fun `advisory binds exact report bytes and identity without rewriting report`() {
        val bytes = "`wrong quotation` (source.md:L1)\r\n".encodeToByteArray()
        val original = bytes.copyOf()
        val sidecar = ReportSourceSidecars.render(
            "run-1", "outputs/report.md", bytes,
            listOf(SourceReportInput("source.md", "source.md", "correct quotation")),
        ).decodeToString()
        assertContentEquals(original, bytes)
        assertTrue(sidecar.contains(sha256Hex(bytes)))
        assertTrue(sidecar.contains("\"run-1\""))
        assertTrue(sidecar.contains("\"outputs/report.md\""))
        assertTrue(sidecar.contains("QUOTATION_MISMATCH"))
        assertTrue(sidecar.contains("Not semantic proof"))
        assertTrue(sidecar.contains("existing strict source gates remain independent"))
        assertNotEquals(
            sidecar,
            ReportSourceSidecars.render("run-2", "outputs/report.md", bytes, emptyList()).decodeToString(),
        )
    }

    @Test
    fun `safe identifiers preserve distinct paths and runs and cannot recurse`() {
        val first = ReportSourceSidecars.pathFor("run", "../../report.md")
        assertTrue(first.matches(Regex("source-checks/[0-9a-f]{64}\\.md")))
        assertNotEquals(first, ReportSourceSidecars.pathFor("run", ".._.._report.md"))
        assertNotEquals(first, ReportSourceSidecars.pathFor("other-run", "../../report.md"))
        assertFalse(ReportSourceSidecars.isReport(first, "text/markdown"))
        assertTrue(ReportSourceSidecars.isReport("outputs/report.csv", "text/csv"))
        assertTrue(ReportSourceSidecars.isReport("outputs/report.json", "application/json"))
        assertFalse(ReportSourceSidecars.isReport("outputs/image.png", "image/png"))
    }

    @Test
    fun `missing source inputs remain an explicit limitation`() {
        val sidecar = ReportSourceSidecars.render(
            "run", "report.md", "Useful report".encodeToByteArray(), emptyList(),
        ).decodeToString()
        assertTrue(sidecar.contains("no verified source inputs"))
    }
}
