package com.achulkov.evomolior.api.service

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opt-in live smoke test for the Qwen image-generation endpoint.
 *
 * Set `EVOMOLIOR_LIVE_QWEN_IMAGE_SMOKE=true` and provide `QWEN_API_KEY` via environment or
 * `local.properties` to run it. It is intentionally skipped by default to avoid unrequested
 * provider spend during ordinary test runs.
 */
class QwenImageGenerationServiceLiveTest {
    @Test
    fun `generates downloads and validates qwen image asset`() = runBlocking {
        assumeTrue(
            System.getenv("EVOMOLIOR_LIVE_QWEN_IMAGE_SMOKE").equals("true", ignoreCase = true),
            "Set EVOMOLIOR_LIVE_QWEN_IMAGE_SMOKE=true to run the live Qwen image smoke test."
        )
        val apiKey = System.getenv("QWEN_API_KEY") ?: readLocalProperty("QWEN_API_KEY").orEmpty()
        assumeTrue(apiKey.isNotBlank(), "QWEN_API_KEY must be present to run the live Qwen image smoke test.")

        val outputDir = createTempDirectory("qwen-image-live-smoke-").toFile()
        val asset = QwenImageGenerationService().generateArticleHeroImage(
            apiKey = apiKey,
            runId = "qwen-image-live-smoke",
            objective = "Create a concise editorial article about safe test automation.",
            outputDirectory = outputDir
        )

        assertEquals("qwen-image-2.0-pro", asset.model)
        assertEquals("verified", asset.reviewStatus)
        assertTrue(asset.width >= 256)
        assertTrue(asset.height >= 256)
        assertTrue(outputDir.resolve(asset.relativePath).exists())
    }

    private fun readLocalProperty(key: String): String? {
        val file = generateSequence(Path.of("").toAbsolutePath().normalize()) { current ->
            current.parent
        }.map { it.resolve("local.properties") }
            .firstOrNull(Files::exists)
            ?: return null
        return Files.readAllLines(file)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val separatorIndex = line.indexOf('=')
                if (separatorIndex <= 0) null else line.substring(0, separatorIndex).trim() to
                    line.substring(separatorIndex + 1).trim()
            }
            .firstOrNull { (name, _) -> name == key }
            ?.second
    }
}
