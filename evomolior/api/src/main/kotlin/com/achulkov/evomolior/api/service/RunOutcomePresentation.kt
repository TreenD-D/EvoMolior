package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.deliverable.DeliverableHashing
import com.achulkov.evomolior.core.deliverable.AcceptanceStatus
import com.achulkov.evomolior.core.deliverable.ArtifactRef
import com.achulkov.evomolior.core.deliverable.ExecutionCompletionStatus
import com.achulkov.evomolior.core.deliverable.PrimaryArtifactValidationStatus
import com.achulkov.evomolior.core.deliverable.ReceiverDecisionPackage
import com.achulkov.evomolior.core.deliverable.RecoverySourceSet
import com.achulkov.evomolior.core.deliverable.RunOutcomeClass
import com.achulkov.evomolior.core.deliverable.RunTerminalOutcomeV2
import com.achulkov.evomolior.core.deliverable.WriterSourceAuthority
import com.achulkov.evomolior.core.deliverable.WriterSourceAuthorityClass
import com.achulkov.evomolior.core.metrics.RunMetricsV1
import com.achulkov.evomolior.core.outcome.RunOutcomeManifestEntryCategory
import com.achulkov.evomolior.core.outcome.RunOutcomeManifestV1
import com.achulkov.evomolior.core.outcome.StartupDiagnosticPackageV1
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Typed operator-facing projection of one verified terminal outcome archive. */
@Serializable
data class RunOutcomePresentation(
    val schemaVersion: String = "run-outcome-presentation-v1",
    val runId: String,
    val outcomeClass: RunOutcomeClass,
    val manifestHash: String,
    val artifactPaths: List<String>,
    val decisionPackage: ReceiverDecisionPackage? = null,
    val metrics: RunMetricsV1? = null,
    val startupDiagnostic: StartupDiagnosticPackageV1? = null,
    val recoverySourceLabels: Map<String, String> = emptyMap(),
    val mechanismActivationReport: MechanismActivationReportV1? = null,
    val executionStatus: ExecutionCompletionStatus? = null,
    val acceptanceStatus: AcceptanceStatus? = null,
    val primaryArtifactPath: String? = null,
    val primaryArtifactValidationStatus: PrimaryArtifactValidationStatus? = null,
    val terminalOutcome: RunTerminalOutcomeV2? = null,
) {
    init {
        require(schemaVersion == "run-outcome-presentation-v1" && runId.isNotBlank())
        require(manifestHash.matches(Regex("[0-9a-f]{64}")))
        require(artifactPaths.isNotEmpty() && artifactPaths == artifactPaths.distinct().sorted())
        require(decisionPackage?.runId == runId || decisionPackage == null)
        require(metrics?.runId == runId || metrics == null)
        require(startupDiagnostic?.runId == runId || startupDiagnostic == null)
        require(
            mechanismActivationReport?.let {
                it.runId == runId && it.outcomeClass == outcomeClass && it.hasValidHash()
            } != false
        )
        terminalOutcome?.let { outcome ->
            outcome.requireValid()
            require(executionStatus == null || executionStatus == outcome.executionStatus)
            require(acceptanceStatus == null || acceptanceStatus == outcome.acceptanceStatus)
        }
    }
}

/** Strict parser that exposes only already-verified public outcome authorities. */
object RunOutcomeBundleInspector {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    /** Verifies and decodes one accepted, provisional, or startup outcome archive. */
    fun inspect(bytes: ByteArray): RunOutcomePresentation {
        val files = unzip(bytes)
        val manifest = json.decodeFromString(
            RunOutcomeManifestV1.serializer(),
            requireNotNull(files[RunManifestVerifier.OUTCOME_MANIFEST_PATH]).decodeToString(),
        )
        require(manifest.hasValidHashAndSemantics()) { "Outcome manifest is invalid" }
        when (manifest.outcomeClass) {
            RunOutcomeClass.ACCEPTED_DECISION_PACKAGE -> {
                val verification = RunManifestVerifier.verify(
                    bytes,
                    expectedRunId = manifest.runId,
                )
                require(verification.isValid) {
                    "Accepted outcome archive is invalid: ${verification.errors.joinToString()}"
                }
            }
            RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE ->
                RunOutcomeArchiveService().verifyProvisional(bytes)
            RunOutcomeClass.STARTUP_DIAGNOSTIC_PACKAGE ->
                RunOutcomeArchiveService().verifyStartup(bytes)
        }
        val packageValue = manifest.entries
            .filter {
                it.category == RunOutcomeManifestEntryCategory.RECEIVER_DECISION_PACKAGE
            }
            .mapNotNull { entry ->
                files[entry.path]?.decodeToString()?.let { encoded ->
                    runCatching { DeliverableHashing.decodePackage(encoded) }.getOrNull()
                }
            }
            .distinctBy(ReceiverDecisionPackage::packageHash)
            .singleOrNull()
        val metrics = manifest.entries.singleOrNull { entry ->
            when (entry.category) {
                RunOutcomeManifestEntryCategory.ACCEPTED_RUN_METRICS ->
                    entry.path == "accepted/run-metrics-v1.json"
                RunOutcomeManifestEntryCategory.RECOVERY_RUN_METRICS ->
                    entry.path == "recovery/run-metrics-v1.json"
                else -> false
            }
        }?.let { entry ->
            json.decodeFromString(
                RunMetricsV1.serializer(),
                files.getValue(entry.path).decodeToString(),
            )
        }
        val startup = files["diagnostic/startup-diagnostic-package-v1.json"]?.let { encoded ->
            json.decodeFromString(
                StartupDiagnosticPackageV1.serializer(),
                encoded.decodeToString(),
            )
        }
        val mechanismReport = files[MechanismActivationReportV1.ARCHIVE_PATH]?.let { encoded ->
            json.decodeFromString(
                MechanismActivationReportV1.serializer(),
                encoded.decodeToString(),
            ).also { report ->
                require(report.hasValidHash()) {
                    "Mechanism activation report is invalid"
                }
            }
        }
        val provisionalRecord = files[ProvisionalRecoveredRunRecordV2.RECORD_PATH]?.let { encoded ->
            json.decodeFromString(
                ProvisionalRecoveredRunRecordV2.serializer(),
                encoded.decodeToString(),
            ).also {
                require(it.hasValidHash()) { "Provisional recovery record is invalid" }
            }
        }
        val terminalOutcome = provisionalRecord?.terminalOutcome
            ?: provisionalRecord?.let { record ->
                legacyProvisionalTerminalOutcome(manifest.runId, record)
            }
        val primaryArtifactPath = provisionalRecord?.recoveredDeliverables
            ?.firstOrNull()
            ?.let { "outputs/recovered/${it.relativePath}" }
            ?: manifest.entries.firstOrNull {
                it.category == RunOutcomeManifestEntryCategory.RENDERED_DELIVERABLE &&
                    (it.path.endsWith(".ipynb") || it.path.startsWith("outputs/"))
            }?.path
        val executionStatus = terminalOutcome?.executionStatus ?: when {
            manifest.outcomeClass == RunOutcomeClass.STARTUP_DIAGNOSTIC_PACKAGE ->
                ExecutionCompletionStatus.FAILED_PRE_ADMISSION
            manifest.outcomeClass == RunOutcomeClass.ACCEPTED_DECISION_PACKAGE ->
                ExecutionCompletionStatus.COMPLETED
            primaryArtifactPath != null -> ExecutionCompletionStatus.COMPLETED
            else -> null
        }
        val acceptanceStatus = terminalOutcome?.acceptanceStatus ?: when {
            manifest.outcomeClass == RunOutcomeClass.ACCEPTED_DECISION_PACKAGE ->
                AcceptanceStatus.ACCEPTED
            manifest.outcomeClass == RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE &&
                primaryArtifactPath != null -> AcceptanceStatus.PROVISIONAL
            manifest.outcomeClass == RunOutcomeClass.STARTUP_DIAGNOSTIC_PACKAGE ->
                AcceptanceStatus.NOT_EVALUATED
            else -> null
        }
        val validationStatus = terminalOutcome?.primaryArtifactValidationStatus ?: when {
            primaryArtifactPath == null -> PrimaryArtifactValidationStatus.UNAVAILABLE
            manifest.outcomeClass == RunOutcomeClass.STARTUP_DIAGNOSTIC_PACKAGE ->
                PrimaryArtifactValidationStatus.NOT_EVALUATED
            else -> PrimaryArtifactValidationStatus.NOT_EVALUATED
        }
        val sourceLabels = manifest.entries
            .filter { it.category == RunOutcomeManifestEntryCategory.RECOVERY_SOURCE_SET }
            .flatMap { entry ->
                json.decodeFromString(
                    RecoverySourceSet.serializer(),
                    files.getValue(entry.path).decodeToString(),
                ).sourceLabels.entries
            }
            .associate { it.key to it.value }
        return RunOutcomePresentation(
            runId = manifest.runId,
            outcomeClass = manifest.outcomeClass,
            manifestHash = manifest.manifestHash,
            artifactPaths = files.keys.sorted(),
            decisionPackage = packageValue,
            metrics = metrics,
            startupDiagnostic = startup,
            recoverySourceLabels = sourceLabels,
            mechanismActivationReport = mechanismReport,
            executionStatus = executionStatus,
            acceptanceStatus = acceptanceStatus,
            primaryArtifactPath = primaryArtifactPath,
            primaryArtifactValidationStatus = validationStatus,
            terminalOutcome = terminalOutcome,
        )
    }

    private fun legacyProvisionalTerminalOutcome(
        runId: String,
        record: ProvisionalRecoveredRunRecordV2,
    ): RunTerminalOutcomeV2? {
        val recovered = record.recoveredDeliverables.firstOrNull() ?: return null
        val authority = WriterSourceAuthority(
            primaryGroupId = recovered.sourceGroupId,
            authorityClass = WriterSourceAuthorityClass.SCAFFOLD_ONLY,
            sourceSnapshotIds = emptyList(),
            donorArtifactRefs = listOf(recovered.relativePath),
            failedGateDiagnostics = listOf("legacy-terminal-outcome-derived"),
        ).withComputedHash()
        return RunTerminalOutcomeV2(
            executionStatus = ExecutionCompletionStatus.COMPLETED,
            acceptanceStatus = AcceptanceStatus.PROVISIONAL,
            primaryArtifactRef = ArtifactRef(
                artifactId = "primary-artifact-$runId",
                relativePath = "outputs/recovered/${recovered.relativePath}",
                mediaType = recovered.mediaType,
                artifactHash = recovered.contentHash,
            ),
            decisionPackageRef = null,
            acceptedWinnerId = null,
            writerSourceAuthority = authority,
            failedGateIds = listOf("strict-acceptance-provisional"),
            primaryArtifactValidationStatus =
                PrimaryArtifactValidationStatus.NOT_EVALUATED,
        ).withComputedHash()
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(files.put(entry.name, zip.readBytes()) == null) {
                    "Outcome archive contains duplicate path ${entry.name}"
                }
                zip.closeEntry()
            }
        }
        return files
    }
}
