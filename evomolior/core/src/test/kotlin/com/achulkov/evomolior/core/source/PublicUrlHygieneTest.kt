package com.achulkov.evomolior.core.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PublicUrlHygieneTest {
    @Test
    fun `zero width characters inside a host are reported with their line`() {
        val report = "# References\n" +
            "- Sentinel-2: https://senti​​nels.copernicus.eu/web/sentinel/missions/sentinel-2 (check)\n"

        val candidate = PublicUrlHygiene.scan(report).single()

        assertEquals(UrlDefect.INVISIBLE_CHARACTER, candidate.defect)
        assertEquals(2, candidate.line)
        assertEquals(
            "https://senti<U+200B><U+200B>nels.copernicus.eu/web/sentinel/missions/sentinel-2",
            PublicUrlHygiene.escapeForDiagnostics(candidate.text)
        )
        assertTrue(PublicUrlHygiene.wellFormedUrls(report).isEmpty())
    }

    @Test
    fun `other invisible characters and invalid hosts are defects`() {
        listOf(
            "https://example.com/a b",       // no-break space
            "https://example.com/⁠x",        // word joiner
            "https://﻿example.com/",         // byte-order mark
        ).forEach { url ->
            assertEquals(UrlDefect.INVISIBLE_CHARACTER, PublicUrlHygiene.scan(url).single().defect, url)
        }
        listOf("https://-bad-.example.com/", "https://exa_mple.com", "https://:8080/x", "https://a..b.com").forEach { url ->
            assertEquals(UrlDefect.INVALID_HOST, PublicUrlHygiene.scan(url).single().defect, url)
        }
    }

    @Test
    fun `ordinary internationalized and decorated urls are accepted and deduplicated`() {
        val report = """
            See https://www.dwd.de/opendata. and https://www.dwd.de/opendata again.
            Port and credentials: https://user@api.met.no:443/weatherapi?x=1#top
            IDN: https://münchen.de/karten
            Emphasis: **https://land.copernicus.eu/en**
        """.trimIndent()

        val candidates = PublicUrlHygiene.scan(report)

        assertTrue(candidates.all { it.defect == null }, candidates.toString())
        assertEquals(
            setOf(
                "https://www.dwd.de/opendata",
                "https://user@api.met.no:443/weatherapi?x=1#top",
                "https://münchen.de/karten",
                "https://land.copernicus.eu/en",
            ),
            PublicUrlHygiene.wellFormedUrls(report)
        )
    }

    @Test
    fun `source checker reports malformed urls as correctable findings`() {
        val audit = SourceReportChecker.check(
            "Intro\nSentinel-2 at https://senti​nels.copernicus.eu/ and https://www.dwd.de/",
            emptyList()
        )

        val malformed = audit.issues.single { it.code == "MALFORMED_URL" }
        assertEquals(2, malformed.reportLine)
        assertTrue(malformed.detail.contains("<U+200B>"))
        assertTrue(audit.issues.any { it.code == "EXTERNAL_SOURCES_NOT_CHECKED" })
        val correction = assertNotNull(audit.correctionPrompt())
        assertTrue(correction.contains("MALFORMED_URL at report L2"))
        assertNull(SourceReportChecker.check("Only https://www.dwd.de/ here", emptyList()).correctionPrompt())
    }
}
