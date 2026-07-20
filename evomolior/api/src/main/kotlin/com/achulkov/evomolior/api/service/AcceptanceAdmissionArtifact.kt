package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.config.AcceptanceProfile
import java.time.Instant
import kotlinx.serialization.Serializable

/** One successful local admission check and its bounded non-secret evidence. */
@Serializable
data class AcceptanceAdmissionCheck(
    val id: String,
    val evidence: String
) {
    init {
        require(id.isNotBlank() && id.length <= 128)
        require(evidence.isNotBlank() && evidence.length <= 8_192)
    }
}

/**
 * Immutable run-specific evidence that operational acceptance admission completed.
 *
 * This artifact is neither a run-completion record nor provider success evidence.
 */
@Serializable
data class AcceptanceAdmissionArtifact(
    val schemaVersion: String = SCHEMA_VERSION,
    val runId: String,
    val acceptanceProfile: AcceptanceProfile,
    val admittedAtUtc: String,
    val ownerPid: Long,
    val distributionManifestSha256: String,
    val sourceRevision: String,
    val configurationSha256: String,
    val modelAssignmentsSha256: String,
    val jvmVersion: String,
    val codexCliPath: String,
    val codexCliSha256: String,
    val codexCliVersion: String,
    val estimatedMaximumRunArchiveBytes: Long,
    val requiredFreeBytes: Long,
    val checks: List<AcceptanceAdmissionCheck>
) {
    init {
        require(schemaVersion == SCHEMA_VERSION)
        require(runId.isNotBlank())
        require(acceptanceProfile != AcceptanceProfile.NORMAL)
        Instant.parse(admittedAtUtc)
        require(ownerPid > 0L)
        require(distributionManifestSha256.isSha256())
        require(sourceRevision.isNotBlank())
        require(configurationSha256.isSha256())
        require(modelAssignmentsSha256.isSha256())
        require(jvmVersion.isNotBlank())
        require(codexCliPath.isNotBlank())
        require(codexCliSha256.isSha256())
        require(codexCliVersion.isNotBlank())
        require(estimatedMaximumRunArchiveBytes > 0L)
        require(requiredFreeBytes >= estimatedMaximumRunArchiveBytes)
        require(checks.isNotEmpty())
        require(checks.map { it.id } == checks.map { it.id }.sorted())
        require(checks.map { it.id }.distinct().size == checks.size)
    }

    companion object {
        const val SCHEMA_VERSION = "acceptance-admission-v1"
        const val FILE_NAME = "acceptance-admission-v1.json"
    }
}

private fun String.isSha256(): Boolean = matches(Regex("[0-9a-f]{64}"))
