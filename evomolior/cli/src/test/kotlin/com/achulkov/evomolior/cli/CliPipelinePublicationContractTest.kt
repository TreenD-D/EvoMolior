package com.achulkov.evomolior.cli

import com.achulkov.evomolior.api.model.CsvRankedEvidenceContract
import com.achulkov.evomolior.api.model.FinalOutputValidationContract
import com.achulkov.evomolior.api.model.ResearchClaimSupportCoverageContract
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CliPipelinePublicationContractTest {
    @Test
    fun `pipeline JSON transports typed grounding contracts`() {
        val config = cliJson.decodeFromString(
            CliPipelineConfig.serializer(),
            """
            {
              "stages": [{
                "id": "ranked",
                "finalOutput": {
                  "relativePath": "ranked.csv",
                  "mediaType": "text/csv",
                  "validation": {
                    "csvRankedEvidence": {
                      "rankColumn": "rank",
                      "evidenceUrlColumn": "evidence_urls",
                      "conceptColumn": "concept",
                      "topRows": 5,
                      "minimumUrlsPerRow": 2,
                      "minimumDistinctUrls": 8
                    },
                    "researchClaimSupportCoverage": {
                      "minimumCoverage": 0.5
                    }
                  }
                }
              }]
            }
            """.trimIndent()
        )

        val validation = assertNotNull(config.stages.single().finalOutput?.validation)
        assertEquals(5, validation.csvRankedEvidence?.topRows)
        assertEquals(2, validation.csvRankedEvidence?.minimumUrlsPerRow)
        assertEquals(8, validation.csvRankedEvidence?.minimumDistinctUrls)
        assertEquals(0.5, validation.researchClaimSupportCoverage?.minimumCoverage)
    }

    @Test
    fun `completed stage reuse applies claim ledger grounding gate`() {
        val stageDir = Files.createTempDirectory("cli-grounding-reuse-")
        val output = """
            rank,concept,evidence_urls
            1,First,https://example.com/one
            2,Second,https://example.com/two
        """.trimIndent()
        val outputSpec = CliPipelineFinalOutputSpec(
            relativePath = "ranked.csv",
            mediaType = "text/csv",
            validation = FinalOutputValidationContract(
                csvRankedEvidence = CsvRankedEvidenceContract(
                    rankColumn = "rank",
                    evidenceUrlColumn = "evidence_urls",
                    topRows = 2,
                    minimumDistinctUrls = 2
                ),
                researchClaimSupportCoverage = ResearchClaimSupportCoverageContract(
                    minimumCoverage = 0.5
                )
            )
        )

        stageDir.resolve("results.zip").writeBytes(
            zipBytes(
                mapOf(
                    "outputs/ranked.csv" to output,
                    "outputs/claim_ledger.jsonl" to """
                        {"claim":"one","supporting_sources":["https://example.com/one"]}
                        {"claim":"two","supporting_sources":[]}
                        {"claim":"three","supporting_sources":[]}
                    """.trimIndent()
                )
            )
        )
        assertNull(verifyPublishedStageOutput(stageDir, outputSpec))

        stageDir.resolve("results.zip").writeBytes(
            zipBytes(
                mapOf(
                    "outputs/ranked.csv" to output,
                    "claim_ledger.jsonl" to """
                        {"claim":"one","supporting_sources":["https://example.com/one"]}
                        {"claim":"two","supporting_sources":["source-id-2"]}
                    """.trimIndent()
                )
            )
        )
        assertNotNull(verifyPublishedStageOutput(stageDir, outputSpec))
    }

    @Test
    fun `completed provisional output can be verified from the recovery archive path`() {
        val stageDir = Files.createTempDirectory("cli-recovery-publication-")
        val output = "provisional receiver artifact"
        val outputDir = stageDir.resolve("published").also { it.createDirectories() }
        outputDir.resolve("submission.md").also {
            it.parent.createDirectories()
            it.writeBytes(output.toByteArray())
        }
        val outputSpec = CliPipelineFinalOutputSpec(
            relativePath = "submission.md",
            mediaType = "text/markdown",
        )

        stageDir.resolve("results.zip").writeBytes(
            zipBytes(
                mapOf("outputs/recovered/submission.md" to output),
            ),
        )

        assertNotNull(
            verifyPublishedStageOutput(
                stageOutputDir = stageDir,
                outputSpec = outputSpec,
                publishedOutputDir = outputDir,
            ),
        )
    }

    private fun zipBytes(entries: Map<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
