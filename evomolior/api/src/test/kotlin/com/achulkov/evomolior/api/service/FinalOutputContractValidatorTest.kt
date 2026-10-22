package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.api.model.FinalOutputValidationContract
import com.achulkov.evomolior.api.model.JupyterNotebookSourceContract
import com.achulkov.evomolior.api.model.CsvRankedEvidenceContract
import com.achulkov.evomolior.api.model.CsvSourceMetadataContract
import com.achulkov.evomolior.api.model.ResearchClaimSupportCoverageContract
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class FinalOutputContractValidatorTest {
    @Test
    fun `source review contract checks all headings word limit and unexecuted-test statement`() {
        val contract = FinalOutputValidationContract(
            maximumWordCount = 40,
            exactMarkdownHeadings = listOf("Summary", "Evidence", "Proposed change", "Validation plan", "Risks"),
            requiredTextStatements = listOf("These tests were not executed"),
        )
        val report = "## Summary\nReview\n## Evidence\nSource\n## Proposed change\nProposal\n" +
            "## Validation plan\nThese tests were not executed.\n## Risks\nUnknown."
        assertTrue(validate("review.md", "text/markdown", report, contract).isEmpty())
        assertTrue(validate("review.md", "text/markdown", report.replace("## Risks", "## Safety"), contract)
            .any { it.contains("heading contract") })
        assertTrue(validate("review.md", "text/markdown", "$report\n${"extra ".repeat(41)}", contract)
            .any { it.contains("maximum 40") })
        assertTrue(validate("review.md", "text/markdown", report.replace("not executed", "executed"), contract)
            .any { it.contains("missing required statement") })
        val scaffold = "```\n$report\n```"
        assertTrue(validate("review.md", "text/markdown", scaffold, contract)
            .any { it.contains("heading contract") })
    }

    @Test
    fun `source-check observations are not blocking output-contract rules`() {
        assertTrue(validate("review.md", "text/markdown",
            "Unknown claim with `corrupted quote` (unknown:L8).",
            FinalOutputValidationContract()).isEmpty())
    }

    @Test
    fun `Markdown heading checks ignore fenced examples and support closing hashes`() {
        val contract = FinalOutputValidationContract(requiredMarkdownSectionSignals = listOf("Evidence"))
        val fenced = "````markdown\n```\n## Evidence\n```\n````"
        assertTrue(validate("review.md", "text/markdown", fenced, contract)
            .any { it.contains("missing a heading") })
        assertTrue(validate("review.md", "text/markdown", "## Evidence ##",
            FinalOutputValidationContract(exactMarkdownHeadings = listOf("Evidence"))).isEmpty())
    }

    @Test
    fun `Jupyter notebook contract accepts valid source and rejects malformed cells`() {
        val contract = FinalOutputValidationContract(
            requiredJsonRootKeys = listOf("cells", "metadata", "nbformat", "nbformat_minor"),
            minimumJsonArraySizes = mapOf("cells" to 1),
            requiredJsonArrayItemFields = mapOf(
                "cells" to listOf("cell_type", "metadata", "source")
            )
        )
        val valid = validate(
            path = "submission.ipynb",
            mediaType = "application/x-ipynb+json",
            content = """
                {
                  "cells": [
                    {
                      "cell_type": "markdown",
                      "id": "intro",
                      "metadata": {},
                      "source": ["# EvoARC-2\n"]
                    },
                    {
                      "cell_type": "code",
                      "id": "solver",
                      "execution_count": null,
                      "metadata": {},
                      "outputs": [],
                      "source": ["print('offline solver')\n"]
                    }
                  ],
                  "metadata": {},
                  "nbformat": 4,
                  "nbformat_minor": 5
                }
            """.trimIndent(),
            contract = contract
        )
        val invalid = validate(
            path = "submission.ipynb",
            mediaType = "application/x-ipynb+json",
            content = """
                {
                  "cells": [{
                    "cell_type": "code",
                    "metadata": [],
                    "source": 7,
                    "execution_count": "never"
                  }],
                  "metadata": [],
                  "nbformat": 3,
                  "nbformat_minor": -1
                }
            """.trimIndent(),
            contract = contract
        )

        assertTrue(valid.isEmpty(), valid.joinToString())
        assertTrue(invalid.any { it.contains("`nbformat` must be integer 4") })
        assertTrue(invalid.any { it.contains("cell 0 `source`") })
        assertTrue(invalid.any { it.contains("cell 0 `outputs`") })
        assertTrue(invalid.any { it.contains("cell 0 `execution_count`") })
    }

    @Test
    fun `Jupyter notebook source contract rejects weak executed and online source`() {
        val contract = FinalOutputValidationContract(
            jupyterNotebookSource = JupyterNotebookSourceContract(
                minimumCodeCellCount = 2,
                requiredCodeSignals = listOf(
                    "def is_grid",
                    "def select_two_outputs",
                    "def validate_submission",
                    "contextual_symbolic_relay",
                    "attempt_1",
                    "attempt_2"
                ),
                requiredMarkdownSignals = listOf(
                    "symbolic baseline",
                    "neural branches deferred"
                ),
                forbiddenCodeSignals = listOf(
                    "requests.",
                    "urllib.",
                    "kagglehub",
                    "http://",
                    "https://"
                )
            )
        )
        val errors = validate(
            path = "submission.ipynb",
            mediaType = "Application/X-IPYNB+JSON; charset=utf-8",
            content = """
                {
                  "cells": [
                    {
                      "cell_type": "markdown",
                      "id": "intro",
                      "metadata": {},
                      "source": ["# Symbolic baseline\n", "Neural branches deferred.\n"]
                    },
                    {
                      "cell_type": "code",
                      "id": "solver",
                      "execution_count": 1,
                      "metadata": {},
                      "outputs": [{"output_type":"stream","name":"stdout","text":"done\n"}],
                      "source": [
                        "import requests\n",
                        "requests.get('https://example.com')\n",
                        "def is_grid(value): return True\n",
                        "def select_two_outputs(values): return values[:2]\n"
                      ]
                    }
                  ],
                  "metadata": {},
                  "nbformat": 4,
                  "nbformat_minor": 5
                }
            """.trimIndent(),
            contract = contract
        )

        assertTrue(errors.any { it.contains("expected at least 2") })
        assertTrue(errors.any { it.contains("must be clean and unexecuted") })
        assertTrue(errors.any { it.contains("def validate_submission") })
        assertTrue(errors.any { it.contains("contextual_symbolic_relay") })
        assertTrue(errors.any { it.contains("forbidden signal `requests.`") })
        assertTrue(errors.any { it.contains("forbidden signal `https://`") })
    }

    @Test
    fun `Jupyter notebook rejects string integers duplicate ids and malformed outputs`() {
        val errors = validate(
            path = "submission.ipynb",
            mediaType = "application/x-ipynb+json",
            content = """
                {
                  "cells": [
                    {
                      "cell_type": "code",
                      "id": "duplicate",
                      "execution_count": "1",
                      "metadata": {},
                      "outputs": [
                        7,
                        {"output_type":"stream","name":"log","text":7},
                        {"output_type":"execute_result","data":[],"metadata":[],"execution_count":"1"},
                        {"output_type":"unknown"}
                      ],
                      "source": ["print('x')\n"]
                    },
                    {
                      "cell_type": "markdown",
                      "id": "duplicate",
                      "metadata": {},
                      "source": ["duplicate\n"]
                    }
                  ],
                  "metadata": {},
                  "nbformat": "4",
                  "nbformat_minor": "5"
                }
            """.trimIndent(),
            contract = FinalOutputValidationContract()
        )

        assertTrue(errors.any { it.contains("`nbformat` must be integer 4") })
        assertTrue(errors.any { it.contains("`nbformat_minor` must be a non-negative integer") })
        assertTrue(errors.any { it.contains("output 0 must be an object") })
        assertTrue(errors.any { it.contains("stream `name`") })
        assertTrue(errors.any { it.contains("stream `text`") })
        assertTrue(errors.any { it.contains("`data` must be an object") })
        assertTrue(errors.any { it.contains("invalid `output_type`") })
    }

    @Test
    fun `Jupyter notebook validates cell id grammar and update display output`() {
        val valid = validate(
            path = "submission.ipynb",
            mediaType = "application/x-ipynb+json",
            content = """
                {
                  "cells": [{
                    "cell_type": "code",
                    "id": "display-update_1",
                    "execution_count": null,
                    "metadata": {},
                    "outputs": [{
                      "output_type": "update_display_data",
                      "data": {"text/plain": "updated"},
                      "metadata": {},
                      "transient": {"display_id": "display-1"}
                    }, {
                      "output_type": "execute_result",
                      "data": {"text/plain": "value"},
                      "metadata": {},
                      "execution_count": null
                    }],
                    "source": ["print('updated')\n"]
                  }],
                  "metadata": {},
                  "nbformat": 4,
                  "nbformat_minor": 5
                }
            """.trimIndent(),
            contract = FinalOutputValidationContract(
                jupyterNotebookSource = JupyterNotebookSourceContract(
                    requireCleanCodeCells = false
                )
            )
        )
        val invalid = validate(
            path = "submission.ipynb",
            mediaType = "application/x-ipynb+json",
            content = """
                {
                  "cells": [{
                    "cell_type": "code",
                    "id": "invalid id ${"x".repeat(65)}",
                    "execution_count": null,
                    "metadata": {},
                    "outputs": [{
                      "output_type": "update_display_data",
                      "data": {},
                      "metadata": {},
                      "transient": {}
                    }],
                    "source": ["print('updated')\n"]
                  }],
                  "metadata": {},
                  "nbformat": 4,
                  "nbformat_minor": 5
                }
            """.trimIndent(),
            contract = FinalOutputValidationContract(
                jupyterNotebookSource = JupyterNotebookSourceContract(
                    requireCleanCodeCells = false
                )
            )
        )

        assertTrue(valid.isEmpty(), valid.joinToString())
        assertTrue(invalid.any { it.contains("1..64 ASCII") })
        assertTrue(invalid.any { it.contains("transient.display_id") })
    }

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
    fun `minimum word count rejects silently condensed reports`() {
        val contract = FinalOutputValidationContract(minimumWordCount = 50, maximumWordCount = 200)
        val condensed = validate("report.md", "text/markdown", "# Report\n\nShort summary only.", contract)
        assertTrue(condensed.any { it.contains("minimum 50") }, condensed.joinToString())

        val complete = validate(
            "report.md",
            "text/markdown",
            "# Report\n\n" + (1..60).joinToString(" ") { "finding$it" },
            contract
        )
        assertTrue(complete.isEmpty(), complete.joinToString())
        assertContains(
            FinalOutputContractValidator.promptRequirements(
                FinalOutputSpec(relativePath = "report.md", mediaType = "text/markdown", validation = contract)
            ).orEmpty(),
            "use at least 50 whitespace-delimited words"
        )
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            FinalOutputValidationContract(minimumWordCount = 300, maximumWordCount = 200)
        }
    }

    @Test
    fun `urls with invisible characters do not satisfy the public url minimum`() {
        val contract = FinalOutputValidationContract(minimumPublicUrlCount = 2)
        // Exact defect from the October 2026 synthesis report: two U+200B inside the host.
        val brokenSentinel = "https://senti\u200B\u200Bnels.copernicus.eu/web/sentinel/missions/sentinel-2"
        val errors = validate(
            path = "report.md",
            mediaType = "text/markdown",
            content = "# Sources\n\n- [Sentinel-2]($brokenSentinel)\n- https://www.dwd.de/opendata.",
            contract = contract
        )
        assertTrue(errors.any { it.contains("contains 1 distinct public URL(s), expected at least 2") }, errors.joinToString())
        assertTrue(errors.any { it.contains("INVISIBLE_CHARACTER at line 3") && it.contains("<U+200B>") }, errors.joinToString())

        val fixed = validate(
            path = "report.md",
            mediaType = "text/markdown",
            content = "# Sources\n\n- https://sentinels.copernicus.eu/web/sentinel/missions/sentinel-2\n- https://www.dwd.de/opendata.",
            contract = contract
        )
        assertTrue(fixed.isEmpty(), fixed.joinToString())
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

    @Test
    fun `notebook prompt requirements expose source structure`() {
        val requirements = FinalOutputContractValidator.promptRequirements(
            FinalOutputSpec(
                relativePath = "submission.ipynb",
                mediaType = "application/x-ipynb+json",
                validation = FinalOutputValidationContract(
                    requiredJsonRootKeys =
                        listOf("cells", "metadata", "nbformat", "nbformat_minor"),
                    jupyterNotebookSource = JupyterNotebookSourceContract(
                        minimumCodeCellCount = 3,
                        requiredCodeSignals = listOf("def is_grid"),
                        requiredMarkdownSignals = listOf("offline"),
                        forbiddenCodeSignals = listOf("requests.")
                    )
                )
            )
        ).orEmpty()

        assertContains(requirements, "Jupyter Notebook nbformat 4")
        assertContains(requirements, "execution_count")
        assertContains(requirements, "array of strings")
        assertContains(requirements, "unique nonblank ASCII `id`")
        assertContains(requirements, "at least 3 code cell")
        assertContains(requirements, "def is_grid")
        assertContains(requirements, "offline")
        assertContains(requirements, "requests.")
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
