@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.core.benchmark

import com.achulkov.evomolior.core.atlas.AtlasHashing
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Deterministic hashing helpers for comparative experiment authority. */
object ComparativeBenchmarkHashing {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    /** Hashes shared matched-input commitments. */
    fun hashCommitments(value: ComparativeInputCommitments): String =
        AtlasHashing.hashText(json.encodeToString(value))

    /** Hashes one exact benchmark task contract. */
    fun hashTask(value: Tier0BenchmarkTask): String =
        AtlasHashing.hashText(json.encodeToString(value))

    /** Hashes a plan while excluding its self-hash. */
    fun hashPlan(value: ComparativeExperimentPlan): String =
        AtlasHashing.hashText(json.encodeToString(value.copy(planHash = "")))

    /** Hashes an assignment while excluding its self-hash. */
    fun hashAssignment(value: ComparativeExperimentAssignment): String =
        AtlasHashing.hashText(json.encodeToString(value.copy(assignmentHash = "")))

    /** Hashes a result while excluding its self-hash. */
    fun hashResult(value: ComparativeExperimentResult): String =
        AtlasHashing.hashText(json.encodeToString(value.copy(resultHash = "")))

    /** Hashes a report while excluding its self-hash. */
    fun hashReport(value: ComparativeExperimentReport): String =
        AtlasHashing.hashText(json.encodeToString(value.copy(reportHash = "")))
}
