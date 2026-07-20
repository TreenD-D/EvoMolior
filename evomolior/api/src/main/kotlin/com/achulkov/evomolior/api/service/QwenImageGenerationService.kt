package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.config.ModelSelectionConfig
import com.achulkov.evomolior.core.contract.GeneratedImageAsset
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Qwen Cloud image generator for final deliverable assets.
 *
 * The default endpoint targets DashScope's synchronous multimodal generation API. Operators can
 * override it with `EVOMOLIOR_QWEN_IMAGE_ENDPOINT` when their Qwen Cloud account requires a
 * region-specific or workspace-specific URL.
 */
class QwenImageGenerationService(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val endpoint: String = System.getenv("EVOMOLIOR_QWEN_IMAGE_ENDPOINT")
        ?: "https://dashscope-intl.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Generates, downloads, and validates one image asset for a final article.
     *
     * @param apiKey Qwen Cloud API key.
     * @param runId Current run identifier used for stable filenames.
     * @param objective Run objective used to form the image prompt.
     * @param outputDirectory Directory corresponding to `outputs/` in the results bundle.
     * @return Verified generated asset.
     */
    suspend fun generateArticleHeroImage(
        apiKey: String,
        runId: String,
        objective: String,
        outputDirectory: File
    ): GeneratedImageAsset = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "QWEN_API_KEY is required for Qwen image generation." }
        val prompt = buildArticleImagePrompt(objective)
        val responseBody = requestImage(prompt, apiKey)
        val imageUrl = extractImageUrl(responseBody)
        val relativePath = "assets/images/${safeSlug(runId)}-hero.png"
        val imageFile = File(outputDirectory, relativePath).also { it.parentFile?.mkdirs() }
        downloadImage(imageUrl, imageFile)
        GeneratedImageAssetValidator.validate(
            file = imageFile,
            relativePath = relativePath,
            prompt = prompt,
            model = ModelSelectionConfig.QWEN_CLOUD_IMAGE_MODEL
        ).also { asset ->
            Napier.i(
                "QwenImageGenerationService: generated verified image ${asset.relativePath} " +
                    "${asset.width}x${asset.height} ${asset.mediaType}"
            )
        }
    }

    private fun buildArticleImagePrompt(objective: String): String {
        val compactObjective = objective.replace(Regex("\\s+"), " ").trim().take(800)
        return "Create a polished editorial hero illustration for this article objective: " +
            compactObjective +
            ". Use a professional publication style, no logos, no readable text, no UI screenshots."
    }

    private fun requestImage(prompt: String, apiKey: String): String {
        val payload = buildJsonObject {
            put("model", ModelSelectionConfig.QWEN_CLOUD_IMAGE_MODEL)
            put("input", buildJsonObject {
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray {
                            add(buildJsonObject { put("text", prompt) })
                        })
                    })
                })
            })
            put("parameters", buildJsonObject {
                put("n", 1)
                put("size", "1328*1328")
                put("prompt_extend", true)
                put("watermark", false)
            })
        }.toString()

        val request = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofMinutes(3))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            "Qwen image generation failed with HTTP ${response.statusCode()}: ${response.body().take(240)}"
        }
        return response.body()
    }

    private fun extractImageUrl(responseBody: String): String {
        val root = json.parseToJsonElement(responseBody).jsonObject
        root["output"]?.jsonObject?.let { output ->
            output["choices"]?.jsonArray
                ?.asSequence()
                ?.flatMap { choice ->
                    choice.jsonObject["message"]?.jsonObject
                        ?.get("content")
                        ?.jsonArray
                        ?.asSequence()
                        .orEmpty()
                }
                ?.mapNotNull { content ->
                    content.jsonObject["image"]?.jsonPrimitive?.contentOrNull
                        ?: content.jsonObject["image_url"]?.jsonPrimitive?.contentOrNull
                        ?: content.jsonObject["url"]?.jsonPrimitive?.contentOrNull
                }
                ?.firstOrNull()
                ?.let { return it }

            output["results"]?.jsonArray
                ?.asSequence()
                ?.mapNotNull { result ->
                    val obj = result.jsonObject
                    obj["url"]?.jsonPrimitive?.contentOrNull
                        ?: obj["image_url"]?.jsonPrimitive?.contentOrNull
                }
                ?.firstOrNull()
                ?.let { return it }
        }

        error("Qwen image generation response did not contain an image URL.")
    }

    private fun downloadImage(imageUrl: String, targetFile: File) {
        val request = HttpRequest.newBuilder(URI.create(imageUrl))
            .timeout(Duration.ofMinutes(3))
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        check(response.statusCode() in 200..299) {
            "Generated image download failed with HTTP ${response.statusCode()}"
        }
        targetFile.writeBytes(response.body())
    }

    private fun safeSlug(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('-')
        .ifBlank { "run" }
        .take(80)
}
