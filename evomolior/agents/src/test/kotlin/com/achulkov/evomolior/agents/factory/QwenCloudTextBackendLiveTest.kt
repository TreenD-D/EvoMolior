package com.achulkov.evomolior.agents.factory

import ai.koog.prompt.dsl.prompt as koogPrompt
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opt-in live smoke test for Qwen Cloud text execution through Koog Dashscope.
 *
 * Set `EVOMOLIOR_LIVE_QWEN_TEXT_SMOKE=true` and provide `QWEN_API_KEY` via environment or
 * `local.properties` to run it. It is skipped by default to avoid unrequested provider spend.
 */
class QwenCloudTextBackendLiveTest {
    @Test
    fun `qwen cloud backend executes both configured text models`() = runBlocking {
        assumeTrue(
            System.getenv("EVOMOLIOR_LIVE_QWEN_TEXT_SMOKE").equals("true", ignoreCase = true),
            "Set EVOMOLIOR_LIVE_QWEN_TEXT_SMOKE=true to run the live Qwen text smoke test."
        )
        val apiKey = System.getenv("QWEN_API_KEY") ?: readLocalProperty("QWEN_API_KEY").orEmpty()
        assumeTrue(apiKey.isNotBlank(), "QWEN_API_KEY must be present to run the live Qwen text smoke test.")

        val executor = LlmExecutorFactory.create(LlmBackend.QWEN_CLOUD, apiKey)
        val prompt = koogPrompt("qwen-cloud-text-smoke") {
            system("You are a smoke-test assistant. Reply with the exact token OK and no other text.")
            user("Reply with exactly OK.")
        }
        listOf(
            ModelSelectionConfig.QWEN_CLOUD_QWEN_PLUS_MODEL,
            ModelSelectionConfig.QWEN_CLOUD_DEEPSEEK_FLASH_MODEL
        ).forEach { modelId ->
            val response = executor.execute(
                prompt = prompt,
                model = LlmExecutorFactory.resolveModel(LlmBackend.QWEN_CLOUD, modelId),
                tools = emptyList()
            )
            assertTrue(
                response.textContent().contains("OK", ignoreCase = true),
                "Expected $modelId to return an OK smoke response, got: ${response.textContent().take(120)}"
            )
        }
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
