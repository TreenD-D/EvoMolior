package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.api.model.FinalOutputValidationContract
import com.achulkov.evomolior.api.model.CsvRankedEvidenceContract
import com.achulkov.evomolior.api.model.CsvSourceMetadataContract
import com.achulkov.evomolior.api.model.ResearchClaimSupportCoverageContract
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class FinalOutputContractValidatorTest {
    @Test
    fun `interactive html contract rejects static documents`() {
        val errors = validate(
            path = "market-map.html",
            mediaType = "text/html",
            content = "<!doctype html><html><body><article>Static map https://example.com</article></body></html>",
            contract = FinalOutputValidationContract(
                minimumPublicUrlCount = 1,
                requireInteractiveHtml = true
            )
        )

        assertContains(errors.joinToString(), "no interactive")
    }

    @Test
    fun `csv contract rejects malformed widths and missing row evidence`() {
        val errors = validate(
            path = "opportunity-matrix.csv",
            mediaType = "text/csv",
            content = """
                rank,concept,evidence_urls
                1,First,https://example.com/first
                2,Second
            """.trimIndent(),
            contract = FinalOutputValidationContract(
                requiredCsvColumns = listOf("rank", "concept", "evidence_urls"),
                exactCsvDataRows = 2,
                evidenceUrlColumn = "evidence_urls",
                rankColumn = "rank"
            )
        )

        assertTrue(errors.any { it.contains("fields, expected") })
        assertTrue(errors.any { it.contains("no public URL") })
    }

    @Test
    fun `json contract rejects missing keys and item fields`() {
        val errors = validate(
            path = "trend-graph.json",
            mediaType = "application/json",
            content = """{"nodes":[{"id":"n1"}],"edges":[]}""",
            contract = FinalOutputValidationContract(
                requiredJsonRootKeys = listOf("nodes", "edges", "sources"),
                minimumJsonArraySizes = mapOf("nodes" to 1, "edges" to 1),
                requiredJsonArrayItemFields = mapOf("nodes" to listOf("id", "label"))
            )
        )

        assertTrue(errors.any { it.contains("missing key `sources`") })
        assertTrue(errors.any { it.contains("`edges` has 0") })
        assertTrue(errors.any { it.contains("missing field `label`") })
    }

    @Test
    fun `markdown contract rejects missing sections and access date`() {
        val errors = validate(
            path = "weekly-update-feed.md",
            mediaType = "text/markdown",
            content = "# Weekly update\n\n## Signal digest\n\nhttps://example.com/source",
            contract = FinalOutputValidationContract(
                minimumPublicUrlCount = 1,
                requiredMarkdownSectionSignals = listOf("signal digest", "stale assumptions"),
                requireMarkdownAccessDate = true
            )
        )

        assertTrue(errors.any { it.contains("stale assumptions") })
        assertTrue(errors.any { it.contains("ISO YYYY-MM-DD") })
    }

    @Test
    fun `complete opportunity csv satisfies contract`() {
        val errors = validate(
            path = "opportunity-matrix.csv",
            mediaType = "text/csv",
            content = """
                rank,concept,evidence_urls
                1,First,https://example.com/first
                2,"Second, quoted",https://example.com/second
            """.trimIndent(),
            contract = FinalOutputValidationContract(
                minimumPublicUrlCount = 2,
                requiredCsvColumns = listOf("rank", "concept", "evidence_urls"),
                exactCsvDataRows = 2,
                evidenceUrlColumn = "evidence_urls",
                rankColumn = "rank"
            )
        )

        assertTrue(errors.isEmpty(), errors.joinToString())
    }

    @Test
    fun `ranked csv evidence enforces per-row and aggregate URL diversity`() {
        val errors = validate(
            path = "ranked.csv",
            mediaType = "text/csv",
            content = """
                rank,concept,evidence_urls
                1,First,https://example.com/shared https://example.com/one
                2,Second,https://example.com/shared
                3,Third,https://example.com/three
            """.trimIndent(),
            contract = FinalOutputValidationContract(
                csvRankedEvidence = CsvRankedEvidenceContract(
                    rankColumn = "rank",
                    evidenceUrlColumn = "evidence_urls",
                    conceptColumn = "concept",
                    topRows = 2,
                    minimumUrlsPerRow = 2,
                    minimumDistinctUrls = 4
                )
            )
        )

        assertTrue(errors.any { it.contains("ranked row 2 (`Second`) has 1") })
        assertTrue(errors.any { it.contains("top 2 ranked rows contain 2") })
    }

    @Test
    fun `csv source metadata supports combined metadata and access-date column`() {
        val errors = validate(
            path = "sources.csv",
            mediaType = "text/csv",
            content = """
                concept,source_note
                First,"Publisher page; accessed 2026-07-10"
                Second,
            """.trimIndent(),
            contract = FinalOutputValidationContract(
                csvSourceMetadata = CsvSourceMetadataContract(
                    sourceMetadataColumn = "source_note",
                    accessDateColumn = "source_note"
                )
            )
        )

        assertTrue(errors.any { it.contains("blank source metadata") })
        assertTrue(errors.any { it.contains("no ISO YYYY-MM-DD access date") })
    }

    @Test
    fun `claim support coverage is gated only when a ledger is available`() {
        val contract = FinalOutputValidationContract(
            researchClaimSupportCoverage = ResearchClaimSupportCoverageContract(
                minimumCoverage = 0.75
            )
        )
        val output = GeneratedOutputFile("brief.md", "text/markdown", "# Brief\n\nGrounded result.")
        val spec = FinalOutputSpec("brief.md", "text/markdown", validation = contract)

        assertTrue(FinalOutputContractValidator.validate(output, spec).isEmpty())

        val errors = FinalOutputContractValidator.validate(
            output,
            spec,
            FinalOutputValidationContext(
                claimLedgerJsonl = """
                    {"claim":"one","supporting_sources":["https://example.com/one"],"used_in_final_answer":true}
                    {"claim":"two","supporting_sources":[],"used_in_final_answer":true}
                """.trimIndent()
            )
        )

        assertContains(errors.joinToString(), "50.0% (1/2)")
        assertContains(errors.joinToString(), "75.0%")
    }

    @Test
    fun `prompt requirements expose every declared contract edge`() {
        val requirements = FinalOutputContractValidator.promptRequirements(
            FinalOutputSpec(
                relativePath = "opportunity-matrix.csv",
                mediaType = "text/csv",
                validation = FinalOutputValidationContract(
                    minimumPublicUrlCount = 1,
                    requiredCsvColumns = listOf("rank", "evidence_urls"),
                    exactCsvDataRows = 20,
                    evidenceUrlColumn = "evidence_urls",
                    rankColumn = "rank",
                    csvRankedEvidence = CsvRankedEvidenceContract(
                        rankColumn = "rank",
                        evidenceUrlColumn = "evidence_urls",
                        topRows = 5,
                        minimumUrlsPerRow = 2,
                        minimumDistinctUrls = 8
                    ),
                    csvSourceMetadata = CsvSourceMetadataContract(
                        accessDateColumn = "source_accessed_at"
                    ),
                    researchClaimSupportCoverage = ResearchClaimSupportCoverageContract(
                        minimumCoverage = 0.5
                    )
                )
            )
        ).orEmpty()

        assertContains(requirements, "20 CSV data rows")
        assertContains(requirements, "`evidence_urls`")
        assertContains(requirements, "consecutive integer sequence")
        assertContains(requirements, "top 5 rows")
        assertContains(requirements, "8 distinct URL")
        assertContains(requirements, "`source_accessed_at`")
        assertContains(requirements, "50.0%")
    }

    private fun validate(
        path: String,
        mediaType: String,
        content: String,
        contract: FinalOutputValidationContract
    ): List<String> {
        val output = GeneratedOutputFile(
            relativePath = path,
            mediaType = mediaType,
            content = content
        )
        return FinalOutputContractValidator.validate(
            output = output,
            outputSpec = FinalOutputSpec(
                relativePath = path,
                mediaType = mediaType,
                validation = contract
            )
        )
    }
}
