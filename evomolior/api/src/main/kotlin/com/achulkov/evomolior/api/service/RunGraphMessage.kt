package com.achulkov.evomolior.api.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Durable run-graph envelope stored alongside a completed run.
 *
 * The envelope carries a compact, route-ready summary set so continuation routing can prefer a
 * first-class graph message instead of re-scraping artefacts when the source run has already
 * published its completion payload.
 *
 * @param runId The completed run that authored the message.
 * @param headline Short human-readable completion headline for quick inspection.
 * @param routedContextSummaries Compact context summaries exposed to continuation routing.
 * @param generatedAtMs Epoch timestamp for the message emission.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RunGraphMessage(
    val runId: String,
    val headline: String,
    val routedContextSummaries: List<String>,
    @EncodeDefault
    val generatedAtMs: Long = System.currentTimeMillis()
)
