package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.config.AcceptanceProfile
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    val javaHome: String,
    val javaExecutableSha256: String,
    val javaReleaseSha256: String,
    val javaModulesSha256: String,
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
        require(javaHome.isNotBlank())
        require(javaExecutableSha256.isSha256())
        require(javaReleaseSha256.isSha256())
        require(javaModulesSha256.isSha256())
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

private val acceptanceAdmissionJson = Json {
    ignoreUnknownKeys = false
}

/** Decodes exact persisted admission bytes without accepting undeclared fields. */
internal fun decodeAcceptanceAdmissionArtifact(bytes: ByteArray): AcceptanceAdmissionArtifact =
    acceptanceAdmissionJson.decodeFromString(
        AcceptanceAdmissionArtifact.serializer(),
        bytes.decodeToString(),
    )

/** Requires this admission to match the terminal archive authority it accompanies. */
internal fun AcceptanceAdmissionArtifact.requireMatches(
    runId: String,
    acceptanceProfile: AcceptanceProfile,
    distributionManifestSha256: String,
): AcceptanceAdmissionArtifact = also {
    require(it.runId == runId) {
        "Acceptance admission belongs to runId=${it.runId}, expected $runId"
    }
    require(it.acceptanceProfile == acceptanceProfile) {
        "Acceptance admission profile does not match terminal archive profile"
    }
    require(it.distributionManifestSha256 == distributionManifestSha256) {
        "Acceptance admission distribution hash does not match terminal archive commitment"
    }
}

private fun String.isSha256(): Boolean = matches(Regex("[0-9a-f]{64}"))
