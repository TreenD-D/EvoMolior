@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.core.outcome

import com.achulkov.evomolior.core.deliverable.DeliverableHashing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.time.Instant

/** Pre-runtime phase that prevented worker activation. */
@Serializable
enum class StartupFailurePhase {
    ADMISSION,
    COUNCIL,
    COMPILATION,
    PREFLIGHT,
}

/** One public, operator-actionable startup failure. */
@Serializable
data class StartupDiagnosticIssue(
    val code: String,
    val publicMessage: String,
    val remediation: String,
    val authorityHash: String,
) {
    init {
        require(code.isNotBlank() && publicMessage.isNotBlank() && remediation.isNotBlank()) {
            "Startup diagnostic issue fields must not be blank"
        }
        require(authorityHash.matches(Regex("[0-9a-f]{64}"))) {
            "Startup diagnostic issue authority must be a SHA-256 hash"
        }
    }
}

/**
 * Small engineering-oriented startup diagnostic authority with no receiver decision claims.
 */
@Serializable
data class StartupDiagnosticPackageV1(
    val schemaVersion: String = SCHEMA_VERSION,
    val runId: String,
    val failurePhase: StartupFailurePhase,
    val admissionDistributionHash: String,
    val startingFieldHash: String? = null,
    val candidateAuthorityHashes: List<String> = emptyList(),
    val declaredCardinality: Int,
    val compiledCardinality: Int,
    val preflightCardinality: Int,
    val issues: List<StartupDiagnosticIssue>,
    val generatedAt: Instant,
    val packageHash: String = "",
) {
    init {
        require(schemaVersion == SCHEMA_VERSION && runId.isNotBlank()) {
            "Startup diagnostic package identity is invalid"
        }
        require(admissionDistributionHash.isStartupHash()) {
            "Startup admission/distribution authority is invalid"
        }
        require(startingFieldHash?.isStartupHash() != false) {
            "Startup StartingField authority is invalid"
        }
        require(candidateAuthorityHashes == candidateAuthorityHashes.distinct().sorted()) {
            "Startup candidate authority hashes must be sorted and unique"
        }
        require(candidateAuthorityHashes.all(String::isStartupHash)) {
            "Startup candidate authority hashes must be SHA-256 values"
        }
        require(
            declaredCardinality >= 0 &&
                compiledCardinality >= 0 &&
                preflightCardinality >= 0
        ) { "Startup cardinalities must not be negative" }
        require(issues.isNotEmpty() && issues == issues.sortedBy(StartupDiagnosticIssue::code)) {
            "Startup diagnostic issues must be non-empty and sorted"
        }
        require(issues.distinctBy(StartupDiagnosticIssue::code).size == issues.size) {
            "Startup diagnostic issue codes must be unique"
        }
    }

    /** Returns this package with its deterministic self-hash attached. */
    fun withComputedHash(): StartupDiagnosticPackageV1 =
        copy(packageHash = hashStartupDiagnostic(this))

    /** Returns whether this package commits all safe diagnostic fields. */
    fun hasValidHash(): Boolean =
        packageHash.isStartupHash() &&
            DeliverableHashing.constantTimeEquals(packageHash, hashStartupDiagnostic(this))

    companion object {
        /** Stable startup diagnostic schema. */
        const val SCHEMA_VERSION: String = "startup-diagnostic-package-v1"
    }
}

private val STARTUP_DIAGNOSTIC_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
}

private fun hashStartupDiagnostic(value: StartupDiagnosticPackageV1): String {
    val element = STARTUP_DIAGNOSTIC_JSON.encodeToJsonElement(
        StartupDiagnosticPackageV1.serializer(),
        value,
    )
    return DeliverableHashing.hashText(
        DeliverableHashing.canonicalJson(
            JsonObject(element.jsonObject.filterKeys { it != "packageHash" })
        )
    )
}

private fun String.isStartupHash(): Boolean = matches(Regex("[0-9a-f]{64}"))
