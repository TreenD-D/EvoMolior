package com.achulkov.evokammer.api.service

import kotlin.io.path.Path
import kotlin.io.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VerifiedSourceCatalogTest {

    @Test
    fun `parses the live source catalog fixture format`() {
        val fixture = checkNotNull(
            javaClass.classLoader?.getResource("verified_source_catalog/current_sources_fixture.md")
        ).readText()

        val records = VerifiedSourceCatalog.parse(fixture)

        assertEquals(5, records.size)
        assertEquals(
            "Pharmacological interventions for ADHD: a systematic review and dose-effect network meta-analysis",
            records.first().title
        )
        assertEquals("https://pubmed.ncbi.nlm.nih.gov/42134365/", records.first().url)
        assertEquals("2025 Jan;12(1):32-43", records[1].publicationDate)
        assertTrue(records.any { it.title == "Treatment of ADHD" })
    }
}
