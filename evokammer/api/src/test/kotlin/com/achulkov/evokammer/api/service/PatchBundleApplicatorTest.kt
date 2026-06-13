package com.achulkov.evokammer.api.service

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PatchBundleApplicatorTest {
    @Test
    fun `applies a patch bundle diff into the mutable repo copy`() {
        val repoRoot = createTempDirectory("patch-bundle-repo-")
        Files.createDirectories(repoRoot.resolve("src"))
        repoRoot.resolve("src/Example.kt").writeText(
            """
            fun message(): String = "original"

            fun sentinel(): String = "keep"
            """.trimIndent()
        )

        val resultsBundle = buildResultsBundle(
            patchBundleJson = """
                {
                  "summary": "Update Example.kt",
                  "changedFiles": [
                    {
                      "path": "src/Example.kt",
                      "status": "modified",
                      "diff": "--- a/src/Example.kt\n+++ b/src/Example.kt\n@@\n-fun message(): String = \"original\"\n+fun message(): String = \"updated\"",
                      "rationale": "The message function must return the updated string."
                    }
                  ],
                  "documentation": "Example.kt now returns the updated message.",
                  "verification": [
                    "Compile Example.kt",
                    "Invoke message() and confirm updated output"
                  ],
                  "notes": "Rollback restores the original return value."
                }
            """.trimIndent()
        )

        val report = PatchBundleApplicator.applyIfPresent(
            resultsBundle = resultsBundle,
            repoRoot = repoRoot,
            expectedRelativePath = "self-improvement-patch-bundle.json",
            runId = "run-1"
        )

        assertNotNull(report)
        assertEquals(listOf("src/Example.kt"), report.changedFiles)
        assertTrue(repoRoot.resolve("src/Example.kt").readText().contains("updated"))
        assertTrue(repoRoot.resolve("src/Example.kt").readText().contains("sentinel"))
    }

    private fun buildResultsBundle(patchBundleJson: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("outputs/self-improvement-patch-bundle.json"))
            zip.write(patchBundleJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return output.toByteArray()
    }
}
