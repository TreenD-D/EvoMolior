package com.achulkov.evomolior.core.outcome

import com.achulkov.evomolior.core.deliverable.DeliverableHashing
import com.achulkov.evomolior.core.deliverable.RunOutcomeClass
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Closed outcome-aware artifact categories used by [RunOutcomeManifestV1]. */
@Serializable
enum class RunOutcomeManifestEntryCategory {
    DELIVERABLE_BLUEPRINT,
    RECEIVER_DECISION_PACKAGE,
    RENDERED_DELIVERABLE,
    ACCEPTANCE_ADMISSION,
    ACCEPTED_AUTHORITY,
    FINAL_REFERENCE_AUTHORITY,
    ACCEPTED_RUN_METRICS,
    ACCEPTED_PUBLICATION_HISTORY,
    RECOVERY_SOURCE_SET,
    RECOVERY_RUN_METRICS,
    RECOVERY_PUBLICATION_HISTORY,
    RECOVERY_DIAGNOSTIC_REPORT,
    STARTUP_DIAGNOSTIC_AUTHORITY,
    STARTUP_PUBLICATION_HISTORY,
    MECHANISM_ACTIVATION_REPORT,
}

/** One exact file commitment in an outcome-aware archive. */
@Serializable
data class RunOutcomeManifestEntry(
    val path: String,
    val mediaType: String,
    val sizeBytes: Long,
    val sha256: String,
    val category: RunOutcomeManifestEntryCategory,
) {
    init {
        require(path.isSafeOutcomePath() && mediaType.isNotBlank()) {
            "Outcome manifest entry path or media type is invalid"
        }
        require(sizeBytes >= 0L && sha256.isOutcomeHash()) {
            "Outcome manifest entry size or hash is invalid"
        }
    }
}

/**
 * Cross-outcome root authority for accepted, provisional, and startup-diagnostic archives.
 *
 * Fields belonging to another outcome class must be absent. The manifest intentionally does not
 * list itself, avoiding a recursive hash.
 */
@Serializable
data class RunOutcomeManifestV1(
    val schemaVersion: String = SCHEMA_VERSION,
    val runId: String,
    val outcomeClass: RunOutcomeClass,
    val admissionDistributionHash: String,
    val acceptanceAdmissionHash: String? = null,
    val startingFieldHash: String? = null,
    val workerAtlasHash: String? = null,
    val graderAtlasHash: String? = null,
    val sealedTestSuiteHash: String? = null,
    val deliverableBlueprintHash: String,
    val decisionPackageHash: String? = null,
    val acceptedAuthorityHash: String? = null,
    val recoverySourceSetHashes: List<String> = emptyList(),
    val startupDiagnosticPackageHash: String? = null,
    val lifecycleReconciliationHash: String,
    val metricsHash: String? = null,
    val publicationHistoryHash: String,
    val entries: List<RunOutcomeManifestEntry>,
    val manifestHash: String = "",
) {
    init {
        require(schemaVersion == SCHEMA_VERSION && runId.isNotBlank()) {
            "Run outcome manifest identity is invalid"
        }
        require(
            admissionDistributionHash.isOutcomeHash() &&
                acceptanceAdmissionHash?.isOutcomeHash() != false &&
                startingFieldHash?.isOutcomeHash() != false &&
                workerAtlasHash?.isOutcomeHash() != false &&
                graderAtlasHash?.isOutcomeHash() != false &&
                sealedTestSuiteHash?.isOutcomeHash() != false &&
                deliverableBlueprintHash.isOutcomeHash() &&
                lifecycleReconciliationHash.isOutcomeHash() &&
                publicationHistoryHash.isOutcomeHash()
        ) { "Run outcome manifest authority hashes are invalid" }
        require(entries.isNotEmpty() && entries == entries.sortedBy { it.path }) {
            "Run outcome manifest entries must be non-empty and sorted"
        }
        require(entries.distinctBy(RunOutcomeManifestEntry::path).size == entries.size) {
            "Run outcome manifest entry paths must be unique"
        }
        require(recoverySourceSetHashes == recoverySourceSetHashes.distinct().sorted()) {
            "Recovery source-set hashes must be sorted and unique"
        }
        require(recoverySourceSetHashes.all(String::isOutcomeHash)) {
            "Recovery source-set commitments must be SHA-256 hashes"
        }
    }

    /** Returns this manifest with its deterministic self-hash attached. */
    fun withComputedHash(): RunOutcomeManifestV1 =
        copy(manifestHash = hashOutcomeManifest(this))

    /** Returns whether this manifest is hash-valid and outcome-class separated. */
    fun hasValidHashAndSemantics(): Boolean =
        manifestHash.isOutcomeHash() &&
            DeliverableHashing.constantTimeEquals(manifestHash, hashOutcomeManifest(this)) &&
            runCatching { RunOutcomeManifestPolicy.requireValid(this) }.isSuccess

    companion object {
        /** Stable outcome manifest schema. */
        const val SCHEMA_VERSION: String = "run-outcome-manifest-v1"
    }
}

/** Fail-closed accepted/provisional/startup manifest separation policy. */
object RunOutcomeManifestPolicy {
    /** Throws when [manifest] carries missing or cross-class authority. */
    fun requireValid(manifest: RunOutcomeManifestV1) {
        val categories = manifest.entries.mapTo(mutableSetOf()) { it.category }
        if (manifest.acceptanceAdmissionHash != null) {
            requireCanonicalEntry(
                manifest,
                ACCEPTANCE_ADMISSION_PATH,
                RunOutcomeManifestEntryCategory.ACCEPTANCE_ADMISSION,
            )
        } else {
            require(RunOutcomeManifestEntryCategory.ACCEPTANCE_ADMISSION !in categories) {
                "Outcome manifest carries admission bytes without an admission commitment"
            }
        }
        when (manifest.outcomeClass) {
            RunOutcomeClass.ACCEPTED_DECISION_PACKAGE -> {
                require(
                    manifest.decisionPackageHash.isOutcomeHash() &&
                        manifest.acceptedAuthorityHash.isOutcomeHash() &&
                        manifest.metricsHash.isOutcomeHash() &&
                        manifest.recoverySourceSetHashes.isEmpty() &&
                        manifest.startupDiagnosticPackageHash == null
                ) { "Accepted outcome manifest lacks accepted authority or mixes outcome classes" }
                require(
                    RunOutcomeManifestEntryCategory.ACCEPTED_AUTHORITY in categories &&
                        RunOutcomeManifestEntryCategory.ACCEPTED_RUN_METRICS in categories &&
                        RunOutcomeManifestEntryCategory.ACCEPTED_PUBLICATION_HISTORY in categories
                ) { "Accepted outcome manifest lacks required accepted entries" }
                requireCanonicalEntry(
                    manifest,
                    ACCEPTED_METRICS_PATH,
                    RunOutcomeManifestEntryCategory.ACCEPTED_RUN_METRICS,
                )
                requireCanonicalEntry(
                    manifest,
                    ACCEPTED_PUBLICATION_HISTORY_PATH,
                    RunOutcomeManifestEntryCategory.ACCEPTED_PUBLICATION_HISTORY,
                )
                requireCanonicalEntry(
                    manifest,
                    MECHANISM_ACTIVATION_REPORT_PATH,
                    RunOutcomeManifestEntryCategory.MECHANISM_ACTIVATION_REPORT,
                )
                require(categories.none { it in RECOVERY_OR_STARTUP_CATEGORIES }) {
                    "Accepted outcome manifest contains recovery or startup authority"
                }
            }
            RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE -> {
                require(
                    manifest.decisionPackageHash.isOutcomeHash() &&
                        manifest.acceptedAuthorityHash == null &&
                        manifest.metricsHash.isOutcomeHash() &&
                        manifest.recoverySourceSetHashes.isNotEmpty() &&
                        manifest.startupDiagnosticPackageHash == null
                ) { "Provisional outcome manifest contains accepted/startup authority" }
                require(
                    RunOutcomeManifestEntryCategory.RECOVERY_SOURCE_SET in categories &&
                        RunOutcomeManifestEntryCategory.RECOVERY_RUN_METRICS in categories &&
                        RunOutcomeManifestEntryCategory.RECOVERY_PUBLICATION_HISTORY in categories
                ) { "Provisional outcome manifest lacks recovery authority" }
                requireCanonicalEntry(
                    manifest,
                    RECOVERY_METRICS_PATH,
                    RunOutcomeManifestEntryCategory.RECOVERY_RUN_METRICS,
                )
                require(categories.none { it in ACCEPTED_OR_STARTUP_CATEGORIES }) {
                    "Provisional outcome manifest contains accepted or startup authority"
                }
                require(manifest.entries.none { it.path.hasAcceptedOnlyPathMeaning() }) {
                    "Provisional outcome manifest contains an accepted-only path"
                }
            }
            RunOutcomeClass.STARTUP_DIAGNOSTIC_PACKAGE -> {
                require(
                    manifest.decisionPackageHash == null &&
                        manifest.acceptedAuthorityHash == null &&
                        manifest.metricsHash == null &&
                        manifest.recoverySourceSetHashes.isEmpty() &&
                        manifest.startupDiagnosticPackageHash.isOutcomeHash()
                ) { "Startup outcome manifest contains runtime or accepted authority" }
                require(
                    RunOutcomeManifestEntryCategory.STARTUP_DIAGNOSTIC_AUTHORITY in categories &&
                        RunOutcomeManifestEntryCategory.STARTUP_PUBLICATION_HISTORY in categories
                ) { "Startup outcome manifest lacks diagnostic authority" }
                requireCanonicalEntry(
                    manifest,
                    STARTUP_DIAGNOSTIC_PACKAGE_PATH,
                    RunOutcomeManifestEntryCategory.STARTUP_DIAGNOSTIC_AUTHORITY,
                )
                require(categories.none { it in ACCEPTED_OR_RECOVERY_CATEGORIES }) {
                    "Startup outcome manifest contains accepted or recovery authority"
                }
                require(manifest.entries.none { it.path.hasAcceptedOnlyPathMeaning() }) {
                    "Startup outcome manifest contains an accepted-only path"
                }
            }
        }
    }

    private fun requireCanonicalEntry(
        manifest: RunOutcomeManifestV1,
        path: String,
        category: RunOutcomeManifestEntryCategory,
    ) {
        require(
            manifest.entries.singleOrNull { it.path == path }?.category == category
        ) {
            "Outcome manifest requires exactly one canonical $path entry with category $category"
        }
    }

    private const val ACCEPTED_METRICS_PATH = "accepted/run-metrics-v1.json"
    private const val ACCEPTANCE_ADMISSION_PATH = "acceptance-admission-v1.json"
    private const val ACCEPTED_PUBLICATION_HISTORY_PATH =
        "accepted/publication-history.json"
    private const val RECOVERY_METRICS_PATH = "recovery/run-metrics-v1.json"
    private const val MECHANISM_ACTIVATION_REPORT_PATH =
        "mechanism-activation-report-v1.json"
    private const val STARTUP_DIAGNOSTIC_PACKAGE_PATH =
        "diagnostic/startup-diagnostic-package-v1.json"

    private val RECOVERY_OR_STARTUP_CATEGORIES = setOf(
        RunOutcomeManifestEntryCategory.RECOVERY_SOURCE_SET,
        RunOutcomeManifestEntryCategory.RECOVERY_RUN_METRICS,
        RunOutcomeManifestEntryCategory.RECOVERY_PUBLICATION_HISTORY,
        RunOutcomeManifestEntryCategory.RECOVERY_DIAGNOSTIC_REPORT,
        RunOutcomeManifestEntryCategory.STARTUP_DIAGNOSTIC_AUTHORITY,
        RunOutcomeManifestEntryCategory.STARTUP_PUBLICATION_HISTORY,
    )
    private val ACCEPTED_OR_STARTUP_CATEGORIES = setOf(
        RunOutcomeManifestEntryCategory.ACCEPTED_AUTHORITY,
        RunOutcomeManifestEntryCategory.FINAL_REFERENCE_AUTHORITY,
        RunOutcomeManifestEntryCategory.ACCEPTED_RUN_METRICS,
        RunOutcomeManifestEntryCategory.ACCEPTED_PUBLICATION_HISTORY,
        RunOutcomeManifestEntryCategory.STARTUP_DIAGNOSTIC_AUTHORITY,
        RunOutcomeManifestEntryCategory.STARTUP_PUBLICATION_HISTORY,
    )
    private val ACCEPTED_OR_RECOVERY_CATEGORIES = setOf(
        RunOutcomeManifestEntryCategory.ACCEPTED_AUTHORITY,
        RunOutcomeManifestEntryCategory.FINAL_REFERENCE_AUTHORITY,
        RunOutcomeManifestEntryCategory.ACCEPTED_RUN_METRICS,
        RunOutcomeManifestEntryCategory.ACCEPTED_PUBLICATION_HISTORY,
        RunOutcomeManifestEntryCategory.RECOVERY_RUN_METRICS,
        RunOutcomeManifestEntryCategory.RECOVERY_SOURCE_SET,
        RunOutcomeManifestEntryCategory.RECOVERY_PUBLICATION_HISTORY,
        RunOutcomeManifestEntryCategory.RECOVERY_DIAGNOSTIC_REPORT,
    )
}

private val OUTCOME_MANIFEST_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
}

private fun hashOutcomeManifest(manifest: RunOutcomeManifestV1): String {
    val element = OUTCOME_MANIFEST_JSON.encodeToJsonElement(
        RunOutcomeManifestV1.serializer(),
        manifest,
    )
    return DeliverableHashing.hashText(
        DeliverableHashing.canonicalJson(
            JsonObject(element.jsonObject.filterKeys { it != "manifestHash" })
        )
    )
}

private fun String?.isOutcomeHash(): Boolean =
    this != null && matches(Regex("[0-9a-f]{64}"))

private fun String.isSafeOutcomePath(): Boolean =
    isNotBlank() && !startsWith("/") && split('/').none { it == ".." || it.isBlank() }

private fun String.hasAcceptedOnlyPathMeaning(): Boolean {
    val normalized = lowercase().replace('_', '-')
    return normalized.contains("final-reference") ||
        normalized.contains("final-deliverable-reference") ||
        normalized == "run-metrics-v1.json" ||
        (
            normalized.startsWith("accepted/") &&
                normalized.endsWith("/run-metrics-v1.json")
            ) ||
        normalized.contains("accepted-publication")
}
