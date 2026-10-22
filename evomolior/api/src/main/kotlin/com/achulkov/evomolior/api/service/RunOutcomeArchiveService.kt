@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.deliverable.BlueprintStandardPackageCompiler
import com.achulkov.evomolior.api.deliverable.RenderedDeliverableArtifact
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.config.CouncilExecutionPurpose
import com.achulkov.evomolior.core.contract.RecoveryPublicationState
import com.achulkov.evomolior.core.contract.RecoveryPublicationStateEvent
import com.achulkov.evomolior.core.contract.RecoveryPublicationStatePolicy
import com.achulkov.evomolior.core.contract.PublicationState
import com.achulkov.evomolior.core.contract.PublicationStateEvent
import com.achulkov.evomolior.core.contract.PublicationStatePolicy
import com.achulkov.evomolior.core.contract.StartupDiagnosticState
import com.achulkov.evomolior.core.contract.StartupDiagnosticStateEvent
import com.achulkov.evomolior.core.contract.StartupDiagnosticStatePolicy
import com.achulkov.evomolior.core.deliverable.DeliverableBlueprint
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
import com.achulkov.evomolior.core.outcome.RunOutcomeManifestEntry
import com.achulkov.evomolior.core.outcome.RunOutcomeManifestEntryCategory
import com.achulkov.evomolior.core.outcome.RunOutcomeManifestV1
import com.achulkov.evomolior.core.outcome.StartupDiagnosticPackageV1
import com.achulkov.evomolior.core.metrics.RunMetricsV1
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Complete metadata required to emit an accepted RunManifest-v5 archive. */
data class AcceptedOutcomeArchiveRequest(
    val runId: String,
    val objective: String,
    val groupCount: Int,
    val generationCeiling: Int,
    val admissionDistributionHash: String,
    val acceptanceAdmissionBytes: ByteArray? = null,
    val startingFieldHash: String,
    val workerAtlasHash: String,
    val graderAtlasHash: String,
    val sealedTestSuiteHash: String,
    val lifecycleReconciliationHash: String,
    val metricsHash: String,
    val metricsBytes: ByteArray,
    val publicationHistoryHash: String,
    val publicationHistory: List<PublicationStateEvent>,
    val blueprint: DeliverableBlueprint,
    val decisionPackage: ReceiverDecisionPackage,
    val mechanismActivationReport: MechanismActivationReportV1,
    val councilExecutionPurpose: CouncilExecutionPurpose = CouncilExecutionPurpose.STANDARD,
    val acceptanceProfile: AcceptanceProfile = AcceptanceProfile.NORMAL,
    val sourceReportInputs: List<com.achulkov.evomolior.core.source.SourceReportInput> = emptyList(),
)

/** Complete metadata required to emit a provisional recovery-v2 archive. */
data class ProvisionalOutcomeArchiveRequest(
    val runId: String,
    val admissionDistributionHash: String,
    val acceptanceProfile: AcceptanceProfile = AcceptanceProfile.NORMAL,
    val acceptanceAdmissionBytes: ByteArray? = null,
    val startingFieldHash: String?,
    val workerAtlasHash: String?,
    val graderAtlasHash: String?,
    val sealedTestSuiteHash: String?,
    val lifecycleReconciliationHash: String,
    val metricsHash: String,
    val metricsBytes: ByteArray,
    val blueprint: DeliverableBlueprint,
    val decisionPackage: ReceiverDecisionPackage,
    val recoverySourceSets: List<RecoverySourceSet>,
    val eligibleGroupIds: Set<String>,
    val recoveryPublicationHistory: List<RecoveryPublicationStateEvent>,
    val recoveredOutput: GeneratedOutputFile? = null,
    val recoveredOutputSource: RecoveredDeliverableSource? = null,
    val recoveredOutputRoundIndex: Int? = null,
    val diagnosticSweepReport: AcceptanceDiagnosticSweepReport? = null,
    val mechanismActivationReport: MechanismActivationReportV1? = null,
    val terminalOutcome: RunTerminalOutcomeV2? = null,
    val archivedAtMs: Long,
    val forbiddenPrivateFragments: Set<String> = emptySet(),
    val sourceReportInputs: List<com.achulkov.evomolior.core.source.SourceReportInput> = emptyList(),
)

/** Complete metadata required to emit a pre-runtime startup diagnostic archive. */
data class StartupDiagnosticArchiveRequest(
    val runId: String,
    val admissionDistributionHash: String,
    val startingFieldHash: String?,
    val lifecycleReconciliationHash: String,
    val blueprint: DeliverableBlueprint,
    val decisionPackage: ReceiverDecisionPackage,
    val diagnosticPackage: StartupDiagnosticPackageV1,
    val publicationHistory: List<StartupDiagnosticStateEvent>,
    val mechanismActivationReport: MechanismActivationReportV1? = null,
    val archivedAtMs: Long,
    val forbiddenPrivateFragments: Set<String> = emptySet(),
    val sourceReportInputs: List<com.achulkov.evomolior.core.source.SourceReportInput> = emptyList(),
)

/** Verified in-memory outcome archive and its root authority. */
data class OutcomeArchive(
    val fileName: String,
    val bytes: ByteArray,
    val outcomeManifest: RunOutcomeManifestV1,
)

/** Root authority stored in a startup-diagnostic ZIP beside its outcome manifest. */
@Serializable
data class StartupDiagnosticArchiveAuthorityV1(
    val schemaVersion: String = "startup-diagnostic-archive-v1",
    val runId: String,
    val diagnosticPackageHash: String,
    val outcomeManifestHash: String,
    val archivedAtMs: Long,
    val authorityHash: String = "",
) {
    init {
        require(schemaVersion == "startup-diagnostic-archive-v1" && runId.isNotBlank()) {
            "Startup diagnostic archive authority identity is invalid"
        }
        require(diagnosticPackageHash.isArchiveHash() && outcomeManifestHash.isArchiveHash()) {
            "Startup diagnostic archive commitments are invalid"
        }
    }

    /** Returns this authority with its deterministic self-hash attached. */
    fun withComputedHash(): StartupDiagnosticArchiveAuthorityV1 =
        copy(authorityHash = archiveAuthorityHash(serializer(), this, "authorityHash"))

    /** Returns whether this authority commits the diagnostic package and manifest. */
    fun hasValidHash(): Boolean = DeliverableHashing.constantTimeEquals(
        authorityHash,
        archiveAuthorityHash(serializer(), this, "authorityHash"),
    )

    companion object {
        /** Canonical startup archive authority path. */
        const val PATH: String = "diagnostic/startup-diagnostic-archive-v1.json"
    }
}

/** Deterministic compiler and verifier for accepted-v5, provisional-v2, and startup archives. */
class RunOutcomeArchiveService(
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    },
) {
    /** Compiles and verifies an accepted RunManifest-v5 archive. */
    fun archiveAccepted(request: AcceptedOutcomeArchiveRequest): OutcomeArchive {
        require(
            request.runId == request.decisionPackage.runId &&
                request.decisionPackage.outcomeClass ==
                RunOutcomeClass.ACCEPTED_DECISION_PACKAGE
        ) { "Accepted archive requires an accepted package for the request run" }
        requireValidAcceptedHistory(request.runId, request.publicationHistory)
        val acceptanceAdmissionHash = validateAcceptanceAdmission(
            runId = request.runId,
            acceptanceProfile = request.acceptanceProfile,
            admissionDistributionHash = request.admissionDistributionHash,
            bytes = request.acceptanceAdmissionBytes,
        )
        val historyBytes = json.encodeToString(
            ListSerializer(PublicationStateEvent.serializer()),
            request.publicationHistory,
        ).encodeToByteArray()
        require(sha256Hex(historyBytes) == request.publicationHistoryHash) {
            "Accepted publication history hash differs from exact history"
        }
        val metrics = json.decodeFromString(
            RunMetricsV1.serializer(),
            request.metricsBytes.decodeToString(),
        )
        require(
            metrics.runId == request.runId &&
                sha256Hex(request.metricsBytes) == request.metricsHash
        ) {
            "Accepted run metrics bytes differ from their exact authority"
        }
        require(
            request.mechanismActivationReport.runId == request.runId &&
                request.mechanismActivationReport.outcomeClass ==
                RunOutcomeClass.ACCEPTED_DECISION_PACKAGE &&
                request.mechanismActivationReport.hasValidHash()
        ) {
            "Accepted archive mechanism activation report is invalid"
        }
        val compiled = BlueprintStandardPackageCompiler.compile(
            request.blueprint,
            request.decisionPackage,
        )
        val payload = linkedMapOf<String, PayloadFile>()
        request.acceptanceAdmissionBytes?.let { bytes ->
            payload.add(
                AcceptanceAdmissionArtifact.FILE_NAME,
                bytes,
                "application/json",
                RunOutcomeManifestEntryCategory.ACCEPTANCE_ADMISSION,
            )
        }
        compiled.files
            .filterNot { it.path == "manifest.json" }
            .forEach { payload.add(it, outcomeCategory(it.path, request.decisionPackage)) }
        payload.add(
            "deliverable-blueprint-v1.json",
            json.encodeToString(DeliverableBlueprint.serializer(), request.blueprint)
                .encodeToByteArray(),
            "application/json",
            RunOutcomeManifestEntryCategory.DELIVERABLE_BLUEPRINT,
        )
        payload.add(
            "accepted/run-metrics-v1.sha256",
            request.metricsHash.encodeToByteArray(),
            "text/plain; charset=utf-8",
            RunOutcomeManifestEntryCategory.ACCEPTED_RUN_METRICS,
        )
        payload.add(
            "accepted/run-metrics-v1.json",
            request.metricsBytes,
            "application/json",
            RunOutcomeManifestEntryCategory.ACCEPTED_RUN_METRICS,
        )
        payload.add(
            "accepted/publication-history.sha256",
            request.publicationHistoryHash.encodeToByteArray(),
            "text/plain; charset=utf-8",
            RunOutcomeManifestEntryCategory.ACCEPTED_PUBLICATION_HISTORY,
        )
        payload.add(
            ACCEPTED_PUBLICATION_HISTORY_PATH,
            historyBytes,
            "application/json",
            RunOutcomeManifestEntryCategory.ACCEPTED_PUBLICATION_HISTORY,
        )
        payload.add(
            MechanismActivationReportV1.ARCHIVE_PATH,
            json.encodeToString(
                MechanismActivationReportV1.serializer(),
                request.mechanismActivationReport,
            ).encodeToByteArray(),
            "application/json",
            RunOutcomeManifestEntryCategory.MECHANISM_ACTIVATION_REPORT,
        )
        payload.addSourceSidecars(request.runId, request.sourceReportInputs)
        val outcomeManifest = outcomeManifest(
            runId = request.runId,
            outcomeClass = request.decisionPackage.outcomeClass,
            admissionDistributionHash = request.admissionDistributionHash,
            acceptanceAdmissionHash = acceptanceAdmissionHash,
            startingFieldHash = request.startingFieldHash,
            workerAtlasHash = request.workerAtlasHash,
            graderAtlasHash = request.graderAtlasHash,
            sealedTestSuiteHash = request.sealedTestSuiteHash,
            blueprintHash = request.blueprint.blueprintHash,
            decisionPackageHash = request.decisionPackage.packageHash,
            acceptedAuthorityHash = request.decisionPackage.acceptedAuthority
                ?.let(::acceptedDeliverableAuthorityHash),
            recoverySourceSetHashes = emptyList(),
            startupDiagnosticHash = null,
            lifecycleHash = request.lifecycleReconciliationHash,
            metricsHash = request.metricsHash,
            publicationHistoryHash = request.publicationHistoryHash,
            payload = payload,
        )
        val outcomeBytes = json.encodeToString(
            RunOutcomeManifestV1.serializer(),
            outcomeManifest,
        ).encodeToByteArray()
        val runEntries = payload.values.map {
            RunManifestEntry(
                runId = request.runId,
                path = it.path,
                category = if (
                    it.category == RunOutcomeManifestEntryCategory.ACCEPTANCE_ADMISSION
                ) {
                    RunManifestEntryCategory.ACCEPTANCE_ADMISSION
                } else {
                    RunManifestEntryCategory.OUTPUT
                },
                sizeBytes = it.bytes.size.toLong(),
                sha256 = sha256Hex(it.bytes),
                mediaType = it.mediaType,
            )
        } + RunManifestEntry(
            runId = request.runId,
            path = RunManifestVerifier.OUTCOME_MANIFEST_PATH,
            category = RunManifestEntryCategory.RUN_OUTCOME_MANIFEST,
            sizeBytes = outcomeBytes.size.toLong(),
            sha256 = sha256Hex(outcomeBytes),
            mediaType = "application/json",
        )
        val manifest = RunManifest(
            schemaVersion = 5,
            runId = request.runId,
            councilExecutionPurpose = request.councilExecutionPurpose,
            acceptanceProfile = request.acceptanceProfile,
            objective = request.objective,
            groupCount = request.groupCount,
            generationCeiling = request.generationCeiling,
            startingFieldArchiveId = "",
            startingFieldSha256 = request.startingFieldHash,
            acceptanceAdmissionSha256 = acceptanceAdmissionHash,
            outcomeManifestSha256 = sha256Hex(outcomeBytes),
            artifactCount = 0,
            archiveEntryCount = 0,
            inputArtifacts = emptyList(),
            entries = runEntries.sortedBy(RunManifestEntry::path),
        )
        val all = payload.mapValues { it.value.bytes }.toMutableMap().apply {
            put(RunManifestVerifier.OUTCOME_MANIFEST_PATH, outcomeBytes)
            put(
                "manifest.json",
                json.encodeToString(RunManifest.serializer(), manifest).encodeToByteArray(),
            )
        }
        val archive = OutcomeArchive("results.zip", zip(all), outcomeManifest)
        val verification = RunManifestVerifier.verify(archive.bytes, expectedRunId = request.runId)
        require(verification.isValid) {
            "Accepted outcome archive failed verification: ${verification.errors.joinToString()}"
        }
        return archive
    }

    /** Compiles and verifies a provisional recovery-v2 archive containing every eligible source. */
    fun archiveProvisional(request: ProvisionalOutcomeArchiveRequest): OutcomeArchive {
        require(
            request.runId == request.decisionPackage.runId &&
                request.decisionPackage.outcomeClass ==
                RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE &&
                request.decisionPackage.acceptedAuthority == null
        ) { "Provisional archive requires a non-accepted decision package" }
        requireValidRecoveryHistory(request.runId, request.recoveryPublicationHistory)
        val acceptanceAdmissionHash = validateAcceptanceAdmission(
            runId = request.runId,
            acceptanceProfile = request.acceptanceProfile,
            admissionDistributionHash = request.admissionDistributionHash,
            bytes = request.acceptanceAdmissionBytes,
        )
        val metrics = json.decodeFromString(
            RunMetricsV1.serializer(),
            request.metricsBytes.decodeToString(),
        )
        require(
            metrics.runId == request.runId &&
                sha256Hex(request.metricsBytes) == request.metricsHash
        ) { "Provisional run metrics bytes differ from their exact authority" }
        val sourceGroups = request.recoverySourceSets
            .flatMap(RecoverySourceSet::sourceGroupIds)
            .toSet()
        require(sourceGroups == request.eligibleGroupIds && sourceGroups.isNotEmpty()) {
            "Recovery archive must include every eligible source group exactly once"
        }
        require(
            request.recoverySourceSets.sumOf { it.sourceGroupIds.size } == sourceGroups.size
        ) { "Recovery source sets cannot duplicate eligible groups" }
        val recoveredDeliverable = request.recoveredOutput?.let { output ->
            val sourceGroupId = requireNotNull(output.sourceGroupId) {
                "Recovered output requires a source-only group authority"
            }
            require(sourceGroupId in sourceGroups) {
                "Recovered output source group is not in the recovery source set"
            }
            val source = requireNotNull(request.recoveredOutputSource) {
                "Recovered output requires explicit provenance"
            }
            val sourceRoundIndex = requireNotNull(request.recoveredOutputRoundIndex) {
                "Recovered output requires a source round"
            }
            val normalizedPath = output.relativePath.replace('\\', '/')
            require(
                normalizedPath.isNotBlank() &&
                    !normalizedPath.startsWith('/') &&
                    normalizedPath.split('/').none { it.isBlank() || it == "." || it == ".." }
            ) { "Recovered output path is unsafe" }
            RecoveredDeliverableRecord(
                relativePath = normalizedPath,
                sourceRelativePath = normalizedPath,
                mediaType = output.mediaType,
                contentHash = output.contentHash,
                source = source,
                sourceGroupId = sourceGroupId,
                sourceRoundIndex = sourceRoundIndex,
                sourceSubmissionId = output.sourceSubmissionId,
            )
        }
        val terminalOutcome = request.terminalOutcome
            ?: defaultProvisionalTerminalOutcome(
                runId = request.runId,
                recoveredOutput = request.recoveredOutput,
                recoveredOutputSource = request.recoveredOutputSource,
                decisionPackageHash = request.decisionPackage.packageHash,
            )
        terminalOutcome.requireValid()
        require(terminalOutcome.acceptanceStatus != AcceptanceStatus.ACCEPTED) {
            "Provisional archive cannot carry accepted terminal status"
        }
        require(terminalOutcome.acceptedWinnerId == null) {
            "Provisional archive cannot carry an accepted winner"
        }
        require(
            terminalOutcome.primaryArtifactRef?.artifactHash ==
                request.recoveredOutput?.contentHash
        ) {
            "Provisional terminal outcome is detached from the recovered primary artifact"
        }
        request.mechanismActivationReport?.let { report ->
            require(
                report.runId == request.runId &&
                    report.outcomeClass == RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE &&
                    report.hasValidHash()
            ) { "Provisional archive mechanism activation report is invalid" }
        }
        val compiled = BlueprintStandardPackageCompiler.compile(
            request.blueprint,
            request.decisionPackage,
        )
        val payload = linkedMapOf<String, PayloadFile>()
        request.acceptanceAdmissionBytes?.let { bytes ->
            payload.add(
                AcceptanceAdmissionArtifact.FILE_NAME,
                bytes,
                "application/json",
                RunOutcomeManifestEntryCategory.ACCEPTANCE_ADMISSION,
            )
        }
        compiled.files
            .filterNot { it.path == "recovery-manifest.json" }
            .forEach { payload.add(it, outcomeCategory(it.path, request.decisionPackage)) }
        recoveredDeliverable?.let { deliverable ->
            val output = requireNotNull(request.recoveredOutput)
            payload.add(
                "outputs/recovered/${deliverable.relativePath}",
                output.content.encodeToByteArray(),
                output.mediaType,
                RunOutcomeManifestEntryCategory.RENDERED_DELIVERABLE,
            )
        }
        payload.add(
            "deliverable-blueprint-v1.json",
            json.encodeToString(DeliverableBlueprint.serializer(), request.blueprint)
                .encodeToByteArray(),
            "application/json",
            RunOutcomeManifestEntryCategory.DELIVERABLE_BLUEPRINT,
        )
        payload.add(
            "recovery/run-metrics-v1.json",
            request.metricsBytes,
            "application/json",
            RunOutcomeManifestEntryCategory.RECOVERY_RUN_METRICS,
        )
        request.recoverySourceSets.sortedBy(RecoverySourceSet::sourceSetHash)
            .forEachIndexed { index, sourceSet ->
                payload.add(
                    "recovery/source-sets/${index.toString().padStart(3, '0')}.json",
                    json.encodeToString(RecoverySourceSet.serializer(), sourceSet)
                        .encodeToByteArray(),
                    "application/json",
                    RunOutcomeManifestEntryCategory.RECOVERY_SOURCE_SET,
                )
            }
        val historyBytes = json.encodeToString(
            ListSerializer(RecoveryPublicationStateEvent.serializer()),
            request.recoveryPublicationHistory,
        ).encodeToByteArray()
        payload.add(
            "recovery/publication-history.json",
            historyBytes,
            "application/json",
            RunOutcomeManifestEntryCategory.RECOVERY_PUBLICATION_HISTORY,
        )
        request.diagnosticSweepReport?.let { report ->
            require(report.runId == request.runId && !report.accepted) {
                "Recovery diagnostic report is invalid or foreign"
            }
            payload.add(
                AcceptanceDiagnosticSweepReport.BUNDLE_PATH,
                encodeRecoveryDiagnosticSweepReport(report),
                "application/json",
                RunOutcomeManifestEntryCategory.RECOVERY_DIAGNOSTIC_REPORT,
            )
        }
        request.mechanismActivationReport?.let { report ->
            payload.add(
                MechanismActivationReportV1.ARCHIVE_PATH,
                json.encodeToString(MechanismActivationReportV1.serializer(), report)
                    .encodeToByteArray(),
                "application/json",
                RunOutcomeManifestEntryCategory.MECHANISM_ACTIVATION_REPORT,
            )
        }
        payload.addSourceSidecars(request.runId, request.sourceReportInputs)
        val outcomeManifest = outcomeManifest(
            runId = request.runId,
            outcomeClass = request.decisionPackage.outcomeClass,
            admissionDistributionHash = request.admissionDistributionHash,
            acceptanceAdmissionHash = acceptanceAdmissionHash,
            startingFieldHash = request.startingFieldHash,
            workerAtlasHash = request.workerAtlasHash,
            graderAtlasHash = request.graderAtlasHash,
            sealedTestSuiteHash = request.sealedTestSuiteHash,
            blueprintHash = request.blueprint.blueprintHash,
            decisionPackageHash = request.decisionPackage.packageHash,
            acceptedAuthorityHash = null,
            recoverySourceSetHashes = request.recoverySourceSets
                .map(RecoverySourceSet::sourceSetHash),
            startupDiagnosticHash = null,
            lifecycleHash = request.lifecycleReconciliationHash,
            metricsHash = request.metricsHash,
            publicationHistoryHash = sha256Hex(historyBytes),
            payload = payload,
        )
        val record = ProvisionalRecoveredRunRecordV2(
            runId = request.runId,
            acceptanceProfile = request.acceptanceProfile,
            decisionPackage = request.decisionPackage,
            recoverySourceSets = request.recoverySourceSets
                .sortedBy(RecoverySourceSet::sourceSetHash),
            recoveredDeliverables = listOfNotNull(recoveredDeliverable),
            recoveryPublicationHistory = request.recoveryPublicationHistory,
            diagnosticSweepReport = request.diagnosticSweepReport,
            terminalOutcome = terminalOutcome,
            outcomeManifest = outcomeManifest,
            archivedAtMs = request.archivedAtMs,
        ).withComputedHash()
        val all = payload.mapValues { it.value.bytes }.toMutableMap().apply {
            put(
                RunManifestVerifier.OUTCOME_MANIFEST_PATH,
                json.encodeToString(RunOutcomeManifestV1.serializer(), outcomeManifest)
                    .encodeToByteArray(),
            )
            put(
                ProvisionalRecoveredRunRecordV2.RECORD_PATH,
                json.encodeToString(ProvisionalRecoveredRunRecordV2.serializer(), record)
                    .encodeToByteArray(),
            )
        }
        requireSafeNonAcceptedPayload(all, request.forbiddenPrivateFragments)
        val archive = OutcomeArchive("recovery-results.zip", zip(all), outcomeManifest)
        verifyProvisional(archive.bytes, record, request.forbiddenPrivateFragments)
        return archive
    }

    /** Compiles and verifies a separate pre-runtime startup diagnostic archive. */
    fun archiveStartupDiagnostic(request: StartupDiagnosticArchiveRequest): OutcomeArchive {
        require(
            request.runId == request.decisionPackage.runId &&
                request.decisionPackage.outcomeClass ==
                RunOutcomeClass.STARTUP_DIAGNOSTIC_PACKAGE &&
                request.decisionPackage.acceptedAuthority == null &&
                request.diagnosticPackage.runId == request.runId &&
                request.diagnosticPackage.hasValidHash()
        ) { "Startup archive requires valid diagnostic-only authority" }
        requireValidStartupHistory(request.runId, request.publicationHistory)
        request.mechanismActivationReport?.let { report ->
            require(
                report.runId == request.runId &&
                    report.outcomeClass == RunOutcomeClass.STARTUP_DIAGNOSTIC_PACKAGE &&
                    report.hasValidHash()
            ) { "Startup archive mechanism activation report is invalid" }
        }
        val compiled = BlueprintStandardPackageCompiler.compile(
            request.blueprint,
            request.decisionPackage,
        )
        val payload = linkedMapOf<String, PayloadFile>()
        compiled.files
            .filterNot { it.path == "diagnostic-manifest.json" }
            .forEach { payload.add(it, outcomeCategory(it.path, request.decisionPackage)) }
        payload.add(
            "deliverable-blueprint-v1.json",
            json.encodeToString(DeliverableBlueprint.serializer(), request.blueprint)
                .encodeToByteArray(),
            "application/json",
            RunOutcomeManifestEntryCategory.DELIVERABLE_BLUEPRINT,
        )
        val diagnosticBytes = json.encodeToString(
            StartupDiagnosticPackageV1.serializer(),
            request.diagnosticPackage,
        ).encodeToByteArray()
        payload.add(
            "diagnostic/startup-diagnostic-package-v1.json",
            diagnosticBytes,
            "application/json",
            RunOutcomeManifestEntryCategory.STARTUP_DIAGNOSTIC_AUTHORITY,
        )
        val historyBytes = json.encodeToString(
            ListSerializer(StartupDiagnosticStateEvent.serializer()),
            request.publicationHistory,
        ).encodeToByteArray()
        payload.add(
            "diagnostic/publication-history.json",
            historyBytes,
            "application/json",
            RunOutcomeManifestEntryCategory.STARTUP_PUBLICATION_HISTORY,
        )
        request.mechanismActivationReport?.let { report ->
            payload.add(
                MechanismActivationReportV1.ARCHIVE_PATH,
                json.encodeToString(MechanismActivationReportV1.serializer(), report)
                    .encodeToByteArray(),
                "application/json",
                RunOutcomeManifestEntryCategory.MECHANISM_ACTIVATION_REPORT,
            )
        }
        payload.addSourceSidecars(request.runId, request.sourceReportInputs)
        val outcomeManifest = outcomeManifest(
            runId = request.runId,
            outcomeClass = RunOutcomeClass.STARTUP_DIAGNOSTIC_PACKAGE,
            admissionDistributionHash = request.admissionDistributionHash,
            startingFieldHash = request.startingFieldHash,
            workerAtlasHash = null,
            graderAtlasHash = null,
            sealedTestSuiteHash = null,
            blueprintHash = request.blueprint.blueprintHash,
            decisionPackageHash = null,
            acceptedAuthorityHash = null,
            recoverySourceSetHashes = emptyList(),
            startupDiagnosticHash = request.diagnosticPackage.packageHash,
            lifecycleHash = request.lifecycleReconciliationHash,
            metricsHash = null,
            publicationHistoryHash = sha256Hex(historyBytes),
            payload = payload,
        )
        val authority = StartupDiagnosticArchiveAuthorityV1(
            runId = request.runId,
            diagnosticPackageHash = request.diagnosticPackage.packageHash,
            outcomeManifestHash = outcomeManifest.manifestHash,
            archivedAtMs = request.archivedAtMs,
        ).withComputedHash()
        val all = payload.mapValues { it.value.bytes }.toMutableMap().apply {
            put(
                RunManifestVerifier.OUTCOME_MANIFEST_PATH,
                json.encodeToString(RunOutcomeManifestV1.serializer(), outcomeManifest)
                    .encodeToByteArray(),
            )
            put(
                StartupDiagnosticArchiveAuthorityV1.PATH,
                json.encodeToString(StartupDiagnosticArchiveAuthorityV1.serializer(), authority)
                    .encodeToByteArray(),
            )
        }
        requireSafeNonAcceptedPayload(all, request.forbiddenPrivateFragments)
        val archive = OutcomeArchive("startup-diagnostic.zip", zip(all), outcomeManifest)
        verifyStartup(archive.bytes, authority, request.forbiddenPrivateFragments)
        return archive
    }

    /** Verifies one provisional recovery-v2 ZIP against [expectedAuthority]. */
    fun verifyProvisional(
        bytes: ByteArray,
        expectedAuthority: ProvisionalRecoveredRunRecordV2? = null,
        forbiddenPrivateFragments: Set<String> = emptySet(),
    ): ProvisionalRecoveredRunRecordV2 {
        val files = unzip(bytes)
        requireSafeNonAcceptedPayload(files, forbiddenPrivateFragments)
        val record = json.decodeFromString(
            ProvisionalRecoveredRunRecordV2.serializer(),
            requireNotNull(files[ProvisionalRecoveredRunRecordV2.RECORD_PATH]).decodeToString(),
        )
        require(record.hasValidHash() && (expectedAuthority == null || record == expectedAuthority)) {
            "Provisional recovery-v2 authority is invalid or differs from expected authority"
        }
        require(files.keys == record.outcomeManifest.entries.map { it.path }.toSet() + setOf(
            RunManifestVerifier.OUTCOME_MANIFEST_PATH,
            ProvisionalRecoveredRunRecordV2.RECORD_PATH,
        )) { "Provisional recovery-v2 ZIP has unattested or missing entries" }
        verifyOutcomePayload(files, record.outcomeManifest)
        val manifestBytes = files.getValue(RunManifestVerifier.OUTCOME_MANIFEST_PATH)
        val decodedManifest = json.decodeFromString(
            RunOutcomeManifestV1.serializer(),
            manifestBytes.decodeToString(),
        )
        require(decodedManifest == record.outcomeManifest) {
            "Provisional recovery-v2 outcome manifest differs from its authority"
        }
        val metricsEntry = decodedManifest.entries.singleOrNull {
            it.path == "recovery/run-metrics-v1.json" &&
                it.category == RunOutcomeManifestEntryCategory.RECOVERY_RUN_METRICS
        }
        val metricsBytes = requireNotNull(metricsEntry?.let { files[it.path] }) {
            "Provisional recovery-v2 archive lacks canonical run metrics"
        }
        val metrics = json.decodeFromString(
            RunMetricsV1.serializer(),
            metricsBytes.decodeToString(),
        )
        require(
            metrics.runId == decodedManifest.runId &&
                sha256Hex(metricsBytes) == decodedManifest.metricsHash
        ) { "Provisional recovery-v2 metrics are invalid or detached from manifest authority" }
        val diagnosticEntry = decodedManifest.entries.singleOrNull {
            it.path == AcceptanceDiagnosticSweepReport.BUNDLE_PATH &&
                it.category ==
                RunOutcomeManifestEntryCategory.RECOVERY_DIAGNOSTIC_REPORT
        }
        if (record.diagnosticSweepReport == null) {
            require(diagnosticEntry == null) {
                "Provisional recovery-v2 carries an unattested diagnostic report"
            }
        } else {
            val diagnostic = json.decodeFromString(
                AcceptanceDiagnosticSweepReport.serializer(),
                requireNotNull(diagnosticEntry?.let { files[it.path] }).decodeToString(),
            )
            require(diagnostic == record.diagnosticSweepReport) {
                "Provisional recovery-v2 diagnostic report differs from record authority"
            }
        }
        validateAcceptanceAdmission(
            runId = record.runId,
            acceptanceProfile = record.acceptanceProfile,
            admissionDistributionHash = decodedManifest.admissionDistributionHash,
            bytes = files[AcceptanceAdmissionArtifact.FILE_NAME],
        ).also { admissionHash ->
            require(admissionHash == decodedManifest.acceptanceAdmissionHash) {
                "Provisional recovery-v2 admission bytes are detached from manifest authority"
            }
        }
        return record
    }

    /** Decodes and validates the exact accepted publication history embedded in a v5 archive. */
    fun acceptedPublicationHistory(bytes: ByteArray): List<PublicationStateEvent> {
        val files = unzip(bytes)
        val historyBytes = requireNotNull(files[ACCEPTED_PUBLICATION_HISTORY_PATH]) {
            "Accepted archive lacks canonical publication history"
        }
        val history = json.decodeFromString(
            ListSerializer(PublicationStateEvent.serializer()),
            historyBytes.decodeToString(),
        )
        val outcome = json.decodeFromString(
            RunOutcomeManifestV1.serializer(),
            requireNotNull(files[RunManifestVerifier.OUTCOME_MANIFEST_PATH]).decodeToString(),
        )
        requireValidAcceptedHistory(outcome.runId, history)
        require(
            sha256Hex(
                json.encodeToString(
                    ListSerializer(PublicationStateEvent.serializer()),
                    history,
                ).encodeToByteArray()
            ) == outcome.publicationHistoryHash
        ) { "Accepted archive publication history hash is invalid" }
        return history
    }

    /** Decodes and validates exact startup publication history embedded in its archive. */
    fun startupPublicationHistory(bytes: ByteArray): List<StartupDiagnosticStateEvent> {
        val files = unzip(bytes)
        val history = json.decodeFromString(
            ListSerializer(StartupDiagnosticStateEvent.serializer()),
            requireNotNull(files["diagnostic/publication-history.json"]).decodeToString(),
        )
        val outcome = json.decodeFromString(
            RunOutcomeManifestV1.serializer(),
            requireNotNull(files[RunManifestVerifier.OUTCOME_MANIFEST_PATH]).decodeToString(),
        )
        requireValidStartupHistory(outcome.runId, history)
        require(
            sha256Hex(
                json.encodeToString(
                    ListSerializer(StartupDiagnosticStateEvent.serializer()),
                    history,
                ).encodeToByteArray()
            ) == outcome.publicationHistoryHash
        ) { "Startup archive publication history hash is invalid" }
        return history
    }

    /** Verifies one startup diagnostic ZIP against [expectedAuthority]. */
    fun verifyStartup(
        bytes: ByteArray,
        expectedAuthority: StartupDiagnosticArchiveAuthorityV1? = null,
        forbiddenPrivateFragments: Set<String> = emptySet(),
    ): StartupDiagnosticArchiveAuthorityV1 {
        val files = unzip(bytes)
        requireSafeNonAcceptedPayload(files, forbiddenPrivateFragments)
        val authority = json.decodeFromString(
            StartupDiagnosticArchiveAuthorityV1.serializer(),
            requireNotNull(files[StartupDiagnosticArchiveAuthorityV1.PATH]).decodeToString(),
        )
        require(authority.hasValidHash() && (expectedAuthority == null || authority == expectedAuthority)) {
            "Startup diagnostic archive authority is invalid or differs from expected authority"
        }
        val manifest = json.decodeFromString(
            RunOutcomeManifestV1.serializer(),
            requireNotNull(files[RunManifestVerifier.OUTCOME_MANIFEST_PATH]).decodeToString(),
        )
        require(manifest.manifestHash == authority.outcomeManifestHash) {
            "Startup diagnostic outcome manifest is detached from archive authority"
        }
        require(authority.runId == manifest.runId) {
            "Startup diagnostic archive authority belongs to another run"
        }
        require(files.keys == manifest.entries.map { it.path }.toSet() + setOf(
            RunManifestVerifier.OUTCOME_MANIFEST_PATH,
            StartupDiagnosticArchiveAuthorityV1.PATH,
        )) { "Startup diagnostic ZIP has unattested or missing entries" }
        verifyOutcomePayload(files, manifest)
        val diagnosticEntry = manifest.entries.singleOrNull {
            it.path == STARTUP_DIAGNOSTIC_PACKAGE_PATH &&
                it.category ==
                RunOutcomeManifestEntryCategory.STARTUP_DIAGNOSTIC_AUTHORITY
        }
        requireNotNull(diagnosticEntry) {
            "Startup diagnostic archive lacks canonical package authority"
        }
        val diagnostic = json.decodeFromString(
            StartupDiagnosticPackageV1.serializer(),
            requireNotNull(files[diagnosticEntry.path]).decodeToString(),
        )
        require(
            diagnostic.runId == manifest.runId &&
                diagnostic.hasValidHash() &&
                diagnostic.packageHash == manifest.startupDiagnosticPackageHash &&
                diagnostic.packageHash == authority.diagnosticPackageHash
        ) {
            "Startup diagnostic package is invalid or detached from manifest authority"
        }
        return authority
    }

    private fun defaultProvisionalTerminalOutcome(
        runId: String,
        recoveredOutput: GeneratedOutputFile?,
        recoveredOutputSource: RecoveredDeliverableSource?,
        decisionPackageHash: String,
    ): RunTerminalOutcomeV2 {
        if (recoveredOutput == null) {
            return RunTerminalOutcomeV2(
                executionStatus = ExecutionCompletionStatus.FAILED_UNWRITABLE_OUTPUT,
                acceptanceStatus = AcceptanceStatus.NOT_EVALUATED,
                primaryArtifactRef = null,
                decisionPackageRef = null,
                acceptedWinnerId = null,
                writerSourceAuthority = null,
                failedGateIds = listOf("primary-artifact-unavailable"),
            ).withComputedHash()
        }
        val groupId = requireNotNull(recoveredOutput.sourceGroupId) {
            "Recovered primary artifact requires a source group"
        }
        val authority = WriterSourceAuthority(
            primaryGroupId = groupId,
            authorityClass = WriterSourceAuthorityClass.SCAFFOLD_ONLY,
            sourceSnapshotIds = emptyList(),
            donorArtifactRefs = listOf(recoveredOutput.relativePath),
            failedGateDiagnostics = listOfNotNull(
                recoveredOutputSource?.name?.let { "recovered-source-$it" },
            ),
        ).withComputedHash()
        return RunTerminalOutcomeV2(
            executionStatus = ExecutionCompletionStatus.COMPLETED,
            acceptanceStatus = AcceptanceStatus.PROVISIONAL,
            primaryArtifactRef = ArtifactRef(
                artifactId = "primary-artifact-$runId",
                relativePath = "outputs/recovered/${recoveredOutput.relativePath}",
                mediaType = recoveredOutput.mediaType,
                artifactHash = recoveredOutput.contentHash,
            ),
            decisionPackageRef = ArtifactRef(
                artifactId = "provisional-decision-package-$runId",
                relativePath = "provisional-decision-package.json",
                mediaType = "application/json",
                artifactHash = decisionPackageHash,
            ),
            acceptedWinnerId = null,
            writerSourceAuthority = authority,
            failedGateIds = listOf("strict-acceptance-provisional"),
            primaryArtifactValidationStatus =
                com.achulkov.evomolior.core.deliverable.PrimaryArtifactValidationStatus
                    .PASS,
        ).withComputedHash()
    }

    private fun outcomeManifest(
        runId: String,
        outcomeClass: RunOutcomeClass,
        admissionDistributionHash: String,
        acceptanceAdmissionHash: String? = null,
        startingFieldHash: String?,
        workerAtlasHash: String?,
        graderAtlasHash: String?,
        sealedTestSuiteHash: String?,
        blueprintHash: String,
        decisionPackageHash: String?,
        acceptedAuthorityHash: String?,
        recoverySourceSetHashes: List<String>,
        startupDiagnosticHash: String?,
        lifecycleHash: String,
        metricsHash: String?,
        publicationHistoryHash: String,
        payload: Map<String, PayloadFile>,
    ): RunOutcomeManifestV1 = RunOutcomeManifestV1(
        runId = runId,
        outcomeClass = outcomeClass,
        admissionDistributionHash = admissionDistributionHash,
        acceptanceAdmissionHash = acceptanceAdmissionHash,
        startingFieldHash = startingFieldHash,
        workerAtlasHash = workerAtlasHash,
        graderAtlasHash = graderAtlasHash,
        sealedTestSuiteHash = sealedTestSuiteHash,
        deliverableBlueprintHash = blueprintHash,
        decisionPackageHash = decisionPackageHash,
        acceptedAuthorityHash = acceptedAuthorityHash,
        recoverySourceSetHashes = recoverySourceSetHashes.distinct().sorted(),
        startupDiagnosticPackageHash = startupDiagnosticHash,
        lifecycleReconciliationHash = lifecycleHash,
        metricsHash = metricsHash,
        publicationHistoryHash = publicationHistoryHash,
        entries = payload.values.map {
            RunOutcomeManifestEntry(
                path = it.path,
                mediaType = it.mediaType,
                sizeBytes = it.bytes.size.toLong(),
                sha256 = sha256Hex(it.bytes),
                category = it.category,
            )
        }.sortedBy(RunOutcomeManifestEntry::path),
    ).withComputedHash().also {
        require(it.hasValidHashAndSemantics()) { "Outcome manifest class separation is invalid" }
    }

    private fun verifyOutcomePayload(
        files: Map<String, ByteArray>,
        manifest: RunOutcomeManifestV1,
    ) {
        require(manifest.hasValidHashAndSemantics()) { "Outcome manifest is invalid" }
        manifest.entries.forEach { entry ->
            val bytes = requireNotNull(files[entry.path]) {
                "Missing outcome payload ${entry.path}"
            }
            require(bytes.size.toLong() == entry.sizeBytes && sha256Hex(bytes) == entry.sha256) {
                "Outcome payload authority mismatch for ${entry.path}"
            }
        }
        val packageEntries = manifest.entries.filter {
            it.category == RunOutcomeManifestEntryCategory.RECEIVER_DECISION_PACKAGE
        }
        if (manifest.outcomeClass != RunOutcomeClass.STARTUP_DIAGNOSTIC_PACKAGE) {
            require(packageEntries.isNotEmpty()) { "Outcome archive lacks receiver package bytes" }
            val packages = packageEntries.map { entry ->
                com.achulkov.evomolior.core.deliverable.DeliverableHashing.decodePackage(
                    files.getValue(entry.path).decodeToString()
                )
            }
            require(packages.all {
                it.runId == manifest.runId &&
                    it.outcomeClass == manifest.outcomeClass &&
                    it.packageHash == manifest.decisionPackageHash &&
                    if (manifest.outcomeClass == RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE) {
                        it.acceptedAuthority == null
                    } else {
                        it.acceptedAuthority != null
                    }
            }) { "Outcome archive semantic package carries wrong-class authority" }
        }
    }

    private fun validateAcceptanceAdmission(
        runId: String,
        acceptanceProfile: AcceptanceProfile,
        admissionDistributionHash: String,
        bytes: ByteArray?,
    ): String? {
        if (acceptanceProfile == AcceptanceProfile.NORMAL) {
            require(bytes == null) {
                "Normal outcome archives cannot carry acceptance admission authority"
            }
            return null
        }
        val exactBytes = requireNotNull(bytes) {
            "Non-normal outcome archive requires ${AcceptanceAdmissionArtifact.FILE_NAME}"
        }
        decodeAcceptanceAdmissionArtifact(exactBytes).requireMatches(
            runId = runId,
            acceptanceProfile = acceptanceProfile,
            distributionManifestSha256 = admissionDistributionHash,
        )
        return sha256Hex(exactBytes)
    }

    private fun requireValidRecoveryHistory(
        runId: String,
        history: List<RecoveryPublicationStateEvent>,
    ) {
        require(history.isNotEmpty())
        history.forEachIndexed { index, event ->
            require(
                event.runId == runId &&
                    event.hasValidHash() &&
                    RecoveryPublicationStatePolicy.allows(history.getOrNull(index - 1), event)
            ) { "Invalid recovery publication history at sequence ${event.sequence}" }
        }
        require(history.last().state == RecoveryPublicationState.ARCHIVED_RECOVERED)
    }

    private fun requireValidAcceptedHistory(
        runId: String,
        history: List<PublicationStateEvent>,
    ) {
        require(history.isNotEmpty())
        history.forEachIndexed { index, event ->
            require(
                event.runId == runId &&
                    event.hasValidHash() &&
                    PublicationStatePolicy.allows(history.getOrNull(index - 1), event)
            ) { "Invalid accepted publication history at sequence ${event.sequence}" }
        }
        require(history.last().state == PublicationState.ARCHIVED)
    }

    private fun requireValidStartupHistory(
        runId: String,
        history: List<StartupDiagnosticStateEvent>,
    ) {
        require(history.isNotEmpty())
        history.forEachIndexed { index, event ->
            require(
                event.runId == runId &&
                    event.hasValidHash() &&
                    StartupDiagnosticStatePolicy.allows(history.getOrNull(index - 1), event)
            ) { "Invalid startup diagnostic history at sequence ${event.sequence}" }
        }
        require(history.last().state == StartupDiagnosticState.ARCHIVED_REJECTED_PRE_RUNTIME)
    }

    private fun outcomeCategory(
        path: String,
        decisionPackage: ReceiverDecisionPackage,
    ): RunOutcomeManifestEntryCategory = when {
        path == "semantic-package.json" ||
            path.endsWith("-decision-package.json") ||
            path == "decision-package.json" ||
            path == "provisional-decision-package.json" ->
            RunOutcomeManifestEntryCategory.RECEIVER_DECISION_PACKAGE
        path == "authority/final-reference.json" ->
            RunOutcomeManifestEntryCategory.ACCEPTED_AUTHORITY
        else -> RunOutcomeManifestEntryCategory.RENDERED_DELIVERABLE
    }.also { category ->
        require(
            decisionPackage.outcomeClass == RunOutcomeClass.ACCEPTED_DECISION_PACKAGE ||
                category != RunOutcomeManifestEntryCategory.ACCEPTED_AUTHORITY
        ) { "Non-accepted package emitted accepted authority" }
    }

    private fun requireSafeNonAcceptedPayload(
        files: Map<String, ByteArray>,
        forbiddenPrivateFragments: Set<String>,
    ) {
        require(forbiddenPrivateFragments.none(String::isBlank)) {
            "Private-content markers must not be blank"
        }
        files.forEach { (path, bytes) ->
            require(!path.hasAcceptedOnlyMeaning()) {
                "Non-accepted archive contains accepted-only path $path"
            }
            val content = bytes.decodeToString()
            require(FORBIDDEN_PRIVATE_FIELD_MARKERS.none(content::contains)) {
                "Non-accepted archive contains a private authority field in $path"
            }
            require(forbiddenPrivateFragments.none(content::contains)) {
                "Non-accepted archive contains forbidden private content in $path"
            }
        }
    }

    private fun zip(files: Map<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                files.toSortedMap().forEach { (path, bytes) ->
                    zip.putNextEntry(ZipEntry(path).apply { time = ZIP_EPOCH_1980_MS })
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && files.put(entry.name, zip.readBytes()) == null) {
                    "Outcome ZIP contains a directory or duplicate entry ${entry.name}"
                }
                zip.closeEntry()
            }
        }
        return files
    }

    private fun MutableMap<String, PayloadFile>.addSourceSidecars(
        runId: String,
        sources: List<com.achulkov.evomolior.core.source.SourceReportInput>,
    ) {
        values.filter {
            it.category in setOf(
                RunOutcomeManifestEntryCategory.RENDERED_DELIVERABLE,
                RunOutcomeManifestEntryCategory.RECEIVER_DECISION_PACKAGE,
            ) && ReportSourceSidecars.isReport(it.path, it.mediaType)
        }.forEach { report ->
            add(
                ReportSourceSidecars.pathFor(runId, report.path),
                ReportSourceSidecars.render(runId, report.path, report.bytes, sources),
                "text/markdown; charset=utf-8",
                RunOutcomeManifestEntryCategory.RENDERED_DELIVERABLE,
            )
        }
    }

    private data class PayloadFile(
        val path: String,
        val bytes: ByteArray,
        val mediaType: String,
        val category: RunOutcomeManifestEntryCategory,
    )

    private fun MutableMap<String, PayloadFile>.add(
        artifact: RenderedDeliverableArtifact,
        category: RunOutcomeManifestEntryCategory,
    ) = add(artifact.path, artifact.content.encodeToByteArray(), artifact.mediaType, category)

    private fun MutableMap<String, PayloadFile>.add(
        path: String,
        bytes: ByteArray,
        mediaType: String,
        category: RunOutcomeManifestEntryCategory,
    ) {
        require(put(path, PayloadFile(path, bytes, mediaType, category)) == null) {
            "Outcome archive path collision: $path"
        }
    }

    companion object {
        private const val ACCEPTED_PUBLICATION_HISTORY_PATH =
            "accepted/publication-history.json"
        private const val STARTUP_DIAGNOSTIC_PACKAGE_PATH =
            "diagnostic/startup-diagnostic-package-v1.json"
        private const val ZIP_EPOCH_1980_MS = 315_532_800_000L
        private val FORBIDDEN_PRIVATE_FIELD_MARKERS = setOf(
            "\"privateSuite\"",
            "\"hiddenTests\"",
            "\"judgeOutputsByGrader\"",
            "\"sealedTestSuiteJson\"",
            "\"privateRationale\"",
        )
    }
}

private fun String.hasAcceptedOnlyMeaning(): Boolean {
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

private fun <T> archiveAuthorityHash(
    serializer: kotlinx.serialization.KSerializer<T>,
    value: T,
    excludedField: String,
): String {
    val json = Json { encodeDefaults = true; explicitNulls = true }
    val element = json.encodeToJsonElement(serializer, value)
    return DeliverableHashing.hashText(
        DeliverableHashing.canonicalJson(
            kotlinx.serialization.json.JsonObject(
                element.jsonObject.filterKeys { it != excludedField }
            )
        )
    )
}

private fun String.isArchiveHash(): Boolean = matches(Regex("[0-9a-f]{64}"))
