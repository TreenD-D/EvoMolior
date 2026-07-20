package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.contract.GeneratedImageAsset
import java.io.File
import javax.imageio.ImageIO

/**
 * Deterministic validator for generated image files.
 *
 * This is intentionally local and provider-independent: after any provider returns bytes, the
 * runtime rechecks that the artifact is a bounded, decodable image before exposing it to writers
 * or result bundles.
 */
object GeneratedImageAssetValidator {
    private const val MIN_DIMENSION_PX = 256
    private const val MAX_SIZE_BYTES = 25L * 1024L * 1024L

    /**
     * Validates an image file and returns a reviewed asset descriptor.
     *
     * @param file Stored image file.
     * @param relativePath Path writers should use from the final output HTML.
     * @param prompt Prompt used to generate the asset.
     * @param model Provider model that produced the asset.
     * @return Verified image asset metadata.
     */
    fun validate(
        file: File,
        relativePath: String,
        prompt: String,
        model: String
    ): GeneratedImageAsset {
        require(file.exists() && file.isFile) { "Generated image file does not exist: ${file.path}" }
        val sizeBytes = file.length()
        require(sizeBytes in 1..MAX_SIZE_BYTES) {
            "Generated image size $sizeBytes bytes is outside the supported range 1..$MAX_SIZE_BYTES"
        }

        val mediaType = detectMediaType(file)
        require(mediaType in setOf("image/png", "image/jpeg", "image/webp")) {
            "Unsupported generated image media type: $mediaType"
        }

        val image = ImageIO.read(file)
            ?: throw IllegalArgumentException("Generated image is not decodable by ImageIO: ${file.path}")
        require(image.width >= MIN_DIMENSION_PX && image.height >= MIN_DIMENSION_PX) {
            "Generated image dimensions ${image.width}x${image.height} are below ${MIN_DIMENSION_PX}px"
        }

        return GeneratedImageAsset(
            relativePath = relativePath,
            mediaType = mediaType,
            width = image.width,
            height = image.height,
            sizeBytes = sizeBytes,
            prompt = prompt,
            model = model,
            reviewStatus = "verified"
        )
    }

    private fun detectMediaType(file: File): String {
        val header = file.inputStream().use { input ->
            ByteArray(12).also { input.read(it) }
        }
        return when {
            header.size >= 8 &&
                header[0] == 0x89.toByte() &&
                header[1] == 'P'.code.toByte() &&
                header[2] == 'N'.code.toByte() &&
                header[3] == 'G'.code.toByte() -> "image/png"
            header.size >= 3 &&
                header[0] == 0xFF.toByte() &&
                header[1] == 0xD8.toByte() &&
                header[2] == 0xFF.toByte() -> "image/jpeg"
            header.size >= 12 &&
                header.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
                header.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "image/webp"
            else -> "application/octet-stream"
        }
    }
}
