package com.achulkov.evomolior.core.contract

import kotlinx.serialization.Serializable

/**
 * Verified generated image asset available to final deliverables.
 *
 * @param relativePath Path relative to the final output directory, for example
 *   `assets/images/run-hero.png`.
 * @param mediaType Verified image MIME type.
 * @param width Decoded image width in pixels.
 * @param height Decoded image height in pixels.
 * @param sizeBytes Stored file size in bytes.
 * @param prompt Prompt used to generate the image.
 * @param model Image model that generated the asset.
 * @param reviewStatus Deterministic post-generation review result.
 */
@Serializable
data class GeneratedImageAsset(
    val relativePath: String,
    val mediaType: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val prompt: String,
    val model: String,
    val reviewStatus: String = "verified"
)
