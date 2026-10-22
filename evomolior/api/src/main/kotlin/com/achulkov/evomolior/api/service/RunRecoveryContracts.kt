package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.contract.PublicationState
import com.achulkov.evomolior.core.contract.RecoveryPublicationStateEvent
import com.achulkov.evomolior.core.contract.RecoveryPublicationStatePolicy
import com.achulkov.evomolior.core.deliverable.DeliverableHashing
import com.achulkov.evomolior.core.deliverable.AcceptanceStatus
import com.achulkov.evomolior.core.deliverable.ArtifactRef
import com.achulkov.evomolior.core.deliverable.ExecutionCompletionStatus
import com.achulkov.evomolior.core.deliverable.ReceiverDecisionPackage
import com.achulkov.evomolior.core.deliverable.RecoverySourceSet
import com.achulkov.evomolior.core.deliverable.RunOutcomeClass
import com.achulkov.evomolior.core.deliverable.RunTerminalOutcomeV2
import com.achulkov.evomolior.core.deliverable.WriterSourceAuthority
import com.achulkov.evomolior.core.deliverable.WriterSourceAuthorityClass
import com.achulkov.evomolior.core.outcome.RunOutcomeManifestV1
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject

/**
 * Terminal disposition for a run whose work cycle completed but whose normal publication did not.
 *
 * This authority is deliberately separate from the accepted publication state machine.
 */
@Serializable
enum class RunRecoveryDisposition {
    RECOVERED_NON_ACCEPTED
}

/** Public, non-secret classification of the normal-publication failure. */
@Serializable
enum class RunRecoveryFailureClass {
    DIAGNOSTIC_SWEEP,
    RUN_AUTHORITY_REJECTED,
    FINAL_OUTPUT_REJECTED,
    ARCHIVE_PUBLICATION_FAILED,
    PUBLICATION_FAILED
}

/** Provenance class for an artifact retained by the recovery gate. */
@Serializable
enum class RecoveredDeliverableSource {
    STAGED_WRITER_OUTPUT,
    RECOVERY_WRITER_OUTPUT,
    FINAL_ROUND_SUMMARY,
    /**
     * Deterministic receiver-contract fallback emitted only when all model-authored recovery
     * candidates fail validation. This source never carries accepted authority.
     */
    DETERMINISTIC_RECOVERY_FALLBACK
}

/**
 * Hash-bound metadata for one explicitly non-accepted recovered artifact.
 *
 * @param relativePath Path inside `outputs/recovered/` in the recovery bundle.
 * @param mediaType Declared artifact media type.
 * @param contentHash SHA-256-compatible EvoMolior content hash.
 * @param source Source class from which recovery retained the artifact.
 * @param sourceGroupId Quorum-resolved source group, never an accepted winner.
 * @param sourceRoundIndex Zero-based source round.
 * @param sourceSubmissionId Optional exact staging submission authority.
 */
@Serializable
data class RecoveredDeliverableRecord(
    val relativePath: String,
    val sourceRelativePath: String? = null,
    val mediaType: String,
    val contentHash: String,
    val source: RecoveredDeliverableSource,
    val sourceGroupId: String?,
    val sourceRoundIndex: Int,
    val sourceSubmissionId: String? = null
) {
    init {
        require(relativePath.isNotBlank() && !relativePath.startsWith("/")) {
            "Recovered deliverable path must be relative"
        }
        require(sourceRelativePath == null || sourceRelativePath.isNotBlank()) {
            "Recovered deliverable source path must be null or non-blank"
        }
        require(
            relativePath.substringAfterLast('/').lowercase() !in
                setOf("final_answer.md", "final-answer.md")
        ) {
            "Recovered deliverable cannot use a normal final-answer filename"
        }
        require(mediaType.isNotBlank() && contentHash.isNotBlank()) {
            "Recovered deliverable media type and hash must not be blank"
        }
        require(sourceRoundIndex >= 0) {
            "Recovered deliverable source round must not be negative"
        }
    }
}

/**
 * Durable authority for a useful but explicitly non-accepted terminal recovery.
 *
 * `accepted` and `acceptedWinnerId` are fixed fail-closed fields so this record cannot be
 * reinterpreted as Appendix-B acceptance or normal completion authority.
 */
@Serializable
data class RecoveredRunRecord(
    val schemaVersion: String = SCHEMA_VERSION,
    val runId: String,
    val disposition: RunRecoveryDisposition =
        RunRecoveryDisposition.RECOVERED_NON_ACCEPTED,
    val accepted: Boolean = false,
    val acceptedWinnerId: String? = null,
    val recoverySourceGroupId: String? = null,
    val acceptanceProfile: String,
    val completedRoundCount: Int,
    val failureClass: RunRecoveryFailureClass,
    val failureSummary: String,
    val opaqueFailureDigest: String,
    val normalPublicationState: PublicationState? = null,
    val requestedOutputSpec: FinalOutputSpec? = null,
    val recoveredDeliverables: List<RecoveredDeliverableRecord> = emptyList(),
    val diagnosticSweepReport: AcceptanceDiagnosticSweepReport? = null,
    val committedBundleEntries: List<RecoveryBundleEntry>,
    val manifestAuthority: RecoveryBundleEntry,
    val metricsAccepted: Boolean = false,
    val startingFieldHash: String,
    val preRecoveryPersistedRunStateHash: String?,
    val recoveredAtMs: Long
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) {
            "Unsupported recovered-run schema $schemaVersion"
        }
        require(runId.isNotBlank() && acceptanceProfile.isNotBlank()) {
            "Recovered-run identity and acceptance profile must not be blank"
        }
        require(!accepted && acceptedWinnerId == null && !metricsAccepted) {
            "Recovered runs cannot carry accepted publication authority"
        }
        require(completedRoundCount > 0) {
            "Recovery requires at least one completed round"
        }
        require(failureSummary.isNotBlank() && opaqueFailureDigest.isNotBlank()) {
            "Recovered-run failure authority must not be blank"
        }
        require(startingFieldHash.isNotBlank()) {
            "Recovered-run StartingField authority must not be blank"
        }
        require(recoveredDeliverables.distinctBy { it.relativePath }.size == recoveredDeliverables.size) {
            "Recovered deliverable paths must be unique"
        }
        require(
            recoveredDeliverables.all { it.sourceGroupId == recoverySourceGroupId }
        ) {
            "Recovered deliverables must bind the exact recovery source group"
        }
        require(
            committedBundleEntries.map(RecoveryBundleEntry::path) ==
                committedBundleEntries.map(RecoveryBundleEntry::path).sorted() &&
                committedBundleEntries.distinctBy(RecoveryBundleEntry::path).size ==
                committedBundleEntries.size
        ) {
            "Recovered-run bundle commitments must be sorted and unique"
        }
        require(
            committedBundleEntries.none {
                it.category == RecoveryBundleEntryCategory.RECOVERY_AUTHORITY ||
                    it.category == RecoveryBundleEntryCategory.RECOVERY_MANIFEST ||
                    it.path == RecoveryBundleManifest.RECOVERY_RECORD_PATH ||
                    it.path == RecoveryBundleManifest.MANIFEST_PATH
            }
        ) {
            "Recovered-run authority cannot recursively commit itself or its manifest"
        }
        recoveredDeliverables.forEach { deliverable ->
            val path = "outputs/recovered/${deliverable.relativePath}"
            val authority = committedBundleEntries.singleOrNull { it.path == path }
            require(
                authority?.category ==
                    RecoveryBundleEntryCategory.RECOVERED_DELIVERABLE &&
                    authority.sha256 == deliverable.contentHash
            ) {
                "Recovered deliverable $path lacks matching external bundle authority"
            }
        }
        diagnosticSweepReport?.let { report ->
            require(report.runId == runId && !report.accepted) {
                "Acceptance diagnostic report belongs to another run or claims acceptance"
            }
            val encoded = encodeRecoveryDiagnosticSweepReport(report)
            val authority = committedBundleEntries.singleOrNull {
                it.path == AcceptanceDiagnosticSweepReport.BUNDLE_PATH
            }
            require(
                authority?.category == RecoveryBundleEntryCategory.DIAGNOSTIC_GATE_REPORT &&
                    authority.sha256 == recoveryContractSha256(encoded) &&
                    authority.sizeBytes == encoded.size.toLong()
            ) {
                "Acceptance diagnostic report lacks matching external bundle authority"
            }
        }
        require(
            manifestAuthority.path == RecoveryBundleManifest.MANIFEST_PATH &&
                manifestAuthority.category ==
                RecoveryBundleEntryCategory.RECOVERY_MANIFEST
        ) {
            "Recovered-run authority must commit the exact recovery manifest"
        }
    }

    companion object {
        /** Stable schema identifier for recovery authority. */
        const val SCHEMA_VERSION: String = "recovered-run-v1"
    }
}

/** Closed category catalog for entries in a recovery-only bundle. */
@Serializable
enum class RecoveryBundleEntryCategory {
    RECOVERY_AUTHORITY,
    RECOVERY_MANIFEST,
    RECOVERY_NOTICE,
    ROUND_SUMMARY,
    NON_ACCEPTED_RUN_METRICS,
    DIAGNOSTIC_GATE_REPORT,
    RECOVERED_DELIVERABLE
}

/** One hash/size commitment in a recovery-only bundle. */
@Serializable
data class RecoveryBundleEntry(
    val path: String,
    val sha256: String,
    val sizeBytes: Long,
    val category: RecoveryBundleEntryCategory
) {
    init {
        require(path.isNotBlank() && !path.startsWith("/") && ".." !in path.split('/')) {
            "Recovery bundle entry path is unsafe"
        }
        require(sha256.isNotBlank() && sizeBytes >= 0L) {
            "Recovery bundle entry authority is invalid"
        }
    }
}

/** Manifest for the separate non-accepted recovery ZIP. */
@Serializable
data class RecoveryBundleManifest(
    val schemaVersion: String = SCHEMA_VERSION,
    val runId: String,
    val disposition: RunRecoveryDisposition =
        RunRecoveryDisposition.RECOVERED_NON_ACCEPTED,
    val accepted: Boolean = false,
    val acceptedWinnerId: String? = null,
    val entries: List<RecoveryBundleEntry>
) {
    init {
        require(schemaVersion == SCHEMA_VERSION && runId.isNotBlank()) {
            "Recovery bundle manifest identity is invalid"
        }
        require(!accepted && acceptedWinnerId == null) {
            "Recovery bundle cannot claim accepted publication authority"
        }
        require(entries.isNotEmpty() && entries.distinctBy { it.path }.size == entries.size) {
            "Recovery bundle entries must be non-empty and unique"
        }
        require(entries.none {
            it.path == RECOVERY_RECORD_PATH ||
                it.path == MANIFEST_PATH ||
                it.category == RecoveryBundleEntryCategory.RECOVERY_AUTHORITY ||
                it.category == RecoveryBundleEntryCategory.RECOVERY_MANIFEST
        }) {
            "Recovery manifest may describe only externally committed payload entries"
        }
        require(entries.none { it.path == "final_answer.md" || it.path == "final-answer.md" }) {
            "Recovery bundle cannot publish a normal final-answer authority"
        }
    }

    companion object {
        /** Stable schema identifier for the recovery-only bundle manifest. */
        const val SCHEMA_VERSION: String = "recovery-bundle-v1"

        /** Canonical path of the recovered-run authority inside the bundle. */
        const val RECOVERY_RECORD_PATH: String = "recovery/recovered-run-v1.json"

        /** Canonical path of the recovery-only manifest inside the bundle. */
        const val MANIFEST_PATH: String = "recovery-manifest.json"
    }
}

/** Input to the recovery-only archive writer. */
internal data class RecoveredRunArchiveRequest(
    val runId: String,
    val acceptanceProfile: String,
    val completedRoundCount: Int,
    val failureClass: RunRecoveryFailureClass,
    val failureSummary: String,
    val opaqueFailureDigest: String,
    val normalPublicationState: PublicationState?,
    val recoverySourceGroupId: String?,
    val requestedOutputSpec: FinalOutputSpec?,
    val recoveredOutput: GeneratedOutputFile?,
    val recoveredOutputSource: RecoveredDeliverableSource?,
    val sourceRoundIndex: Int,
    val recoveryNotice: String,
    val diagnosticSweepReport: AcceptanceDiagnosticSweepReport? = null
)

private val RECOVERY_CONTRACT_JSON = kotlinx.serialization.json.Json {
    encodeDefaults = true
    explicitNulls = true
}

internal fun encodeRecoveryDiagnosticSweepReport(
    report: AcceptanceDiagnosticSweepReport
): ByteArray = RECOVERY_CONTRACT_JSON.encodeToString(
    AcceptanceDiagnosticSweepReport.serializer(),
    report
).toByteArray(Charsets.UTF_8)

private fun recoveryContractSha256(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

/**
 * Provisional recovery-v2 authority. Historical [RecoveredRunRecord] remains the immutable v1
 * wire contract and is not upgraded or reinterpreted.
 */
@Serializable
data class ProvisionalRecoveredRunRecordV2(
    val schemaVersion: String = SCHEMA_VERSION,
    val runId: String,
    val acceptanceProfile: AcceptanceProfile = AcceptanceProfile.NORMAL,
    val accepted: Boolean = false,
    val acceptedWinnerId: String? = null,
    val metricsAccepted: Boolean = false,
    val decisionPackage: ReceiverDecisionPackage,
    val recoverySourceSets: List<RecoverySourceSet>,
    val recoveredDeliverables: List<RecoveredDeliverableRecord> = emptyList(),
    val recoveryPublicationHistory: List<RecoveryPublicationStateEvent>,
    val diagnosticSweepReport: AcceptanceDiagnosticSweepReport? = null,
    val terminalOutcome: RunTerminalOutcomeV2? = null,
    val outcomeManifest: RunOutcomeManifestV1,
    val archivedAtMs: Long,
    val recordHash: String = "",
) {
    init {
        require(schemaVersion == SCHEMA_VERSION && runId.isNotBlank()) {
            "Provisional recovery-v2 identity is invalid"
        }
        require(!accepted && acceptedWinnerId == null && !metricsAccepted) {
            "Provisional recovery-v2 cannot carry accepted publication authority"
        }
        require(
            decisionPackage.runId == runId &&
                decisionPackage.outcomeClass == RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE &&
                decisionPackage.acceptedAuthority == null &&
                decisionPackage.hasValidHashes()
        ) { "Provisional recovery-v2 requires a valid non-accepted decision package" }
        terminalOutcome?.let { outcome ->
            outcome.requireValid()
            require(outcome.acceptanceStatus != AcceptanceStatus.ACCEPTED) {
                "Provisional recovery-v2 cannot carry accepted terminal status"
            }
            require(outcome.acceptedWinnerId == null) {
                "Provisional recovery-v2 cannot carry an accepted winner"
            }
        }
        require(
            recoverySourceSets.isNotEmpty() &&
                recoverySourceSets.all { it.runId == runId && it.hasValidHash() } &&
                recoverySourceSets.map { it.sourceSetHash } ==
                recoverySourceSets.map { it.sourceSetHash }.distinct().sorted()
        ) { "Provisional recovery-v2 source sets must be valid, unique, and sorted" }
        val recoveryGroupIds = recoverySourceSets
            .flatMap(RecoverySourceSet::sourceGroupIds)
            .toSet()
        require(
            recoveredDeliverables.distinctBy(RecoveredDeliverableRecord::relativePath).size ==
                recoveredDeliverables.size
        ) { "Provisional recovered deliverable paths must be unique" }
        recoveredDeliverables.forEach { deliverable ->
            require(deliverable.sourceGroupId in recoveryGroupIds) {
                "Provisional recovered deliverable source group is not in the recovery source set"
            }
            val entry = outcomeManifest.entries.singleOrNull {
                it.path == "outputs/recovered/${deliverable.relativePath}"
            }
            require(
                entry != null &&
                    entry.mediaType == deliverable.mediaType &&
                    entry.sha256 == deliverable.contentHash
            ) {
                "Provisional recovered deliverable lacks matching outcome-manifest authority"
            }
        }
        terminalOutcome?.let { outcome ->
            val primary = outcome.primaryArtifactRef
            if (outcome.executionStatus ==
                com.achulkov.evomolior.core.deliverable.ExecutionCompletionStatus.COMPLETED
            ) {
                require(primary != null && recoveredDeliverables.isNotEmpty()) {
                    "Completed provisional outcome requires a recovered primary deliverable"
                }
            }
            primary?.let { ref ->
                val recovered = recoveredDeliverables.singleOrNull {
                    "outputs/recovered/${it.relativePath}" == ref.relativePath &&
                        it.contentHash == ref.artifactHash &&
                        it.mediaType == ref.mediaType
                }
                require(recovered != null) {
                    "Provisional terminal primary artifact is detached from recovered deliverables"
                }
            }
        }
        require(recoveryPublicationHistory.isNotEmpty()) {
            "Provisional recovery-v2 requires recovery publication history"
        }
        recoveryPublicationHistory.forEachIndexed { index, event ->
            require(
                event.runId == runId &&
                    event.hasValidHash() &&
                    RecoveryPublicationStatePolicy.allows(
                        recoveryPublicationHistory.getOrNull(index - 1),
                        event,
                    )
            ) { "Invalid recovery publication event at sequence ${event.sequence}" }
        }
        require(
            recoveryPublicationHistory.last().state ==
                com.achulkov.evomolior.core.contract.RecoveryPublicationState.ARCHIVED_RECOVERED
        ) { "Provisional recovery-v2 history must reach ARCHIVED_RECOVERED" }
        diagnosticSweepReport?.let { report ->
            require(
                report.runId == runId &&
                    !report.accepted &&
                    report.acceptedWinnerId == null
            ) { "Provisional recovery-v2 diagnostic report is invalid or foreign" }
        }
        require(
            outcomeManifest.runId == runId &&
                outcomeManifest.outcomeClass == RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE &&
                outcomeManifest.decisionPackageHash == decisionPackage.packageHash &&
                outcomeManifest.recoverySourceSetHashes ==
                recoverySourceSets.map { it.sourceSetHash }.sorted() &&
                outcomeManifest.hasValidHashAndSemantics()
        ) { "Provisional recovery-v2 outcome manifest is inconsistent" }
    }

    /** Returns this record with its deterministic self-hash attached. */
    fun withComputedHash(): ProvisionalRecoveredRunRecordV2 =
        copy(recordHash = recoveryV2Hash(this))

    /** Returns whether [recordHash] commits the complete v2 authority. */
    fun hasValidHash(): Boolean =
        DeliverableHashing.constantTimeEquals(recordHash, recoveryV2Hash(this))

    companion object {
        /** Stable provisional recovery authority schema. */
        const val SCHEMA_VERSION: String = "recovered-run-v2"

        /** Canonical v2 authority path. */
        const val RECORD_PATH: String = "recovery/recovered-run-v2.json"
    }
}

private fun recoveryV2Hash(value: ProvisionalRecoveredRunRecordV2): String {
    val element = RECOVERY_CONTRACT_JSON.encodeToJsonElement(
        ProvisionalRecoveredRunRecordV2.serializer(),
        value,
    )
    return DeliverableHashing.hashText(
        DeliverableHashing.canonicalJson(
            kotlinx.serialization.json.JsonObject(
                element.jsonObject.filterKeys { it != "recordHash" }
            )
        )
    )
}
