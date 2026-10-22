package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.contract.RunInputArtifact
import com.achulkov.evomolior.core.config.CouncilExecutionPurpose
import com.achulkov.evomolior.core.contract.StartingField
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.contract.ContributionEdge
import com.achulkov.evomolior.core.contract.FinalCandidateRecord
import com.achulkov.evomolior.core.contract.FinalDeliverableReferenceRecord
import com.achulkov.evomolior.core.contract.PublicationStateEvent
import com.achulkov.evomolior.core.contract.PublicationStatePolicy
import com.achulkov.evomolior.core.contract.StartingFieldWireContract
import com.achulkov.evomolior.core.deliverable.AcceptedDeliverableAuthority
import com.achulkov.evomolior.core.deliverable.DeliverableHashing
import com.achulkov.evomolior.core.deliverable.RunOutcomeClass
import com.achulkov.evomolior.core.grader.GraderAtlas
import com.achulkov.evomolior.core.metrics.RunMetricsV1
import com.achulkov.evomolior.core.outcome.RunOutcomeManifestEntryCategory
import com.achulkov.evomolior.core.outcome.RunOutcomeManifestV1
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Verifiable one-run bundle manifest. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RunManifest(
    val schemaVersion: Int = 1,
    val runId: String,
    val councilExecutionPurpose: CouncilExecutionPurpose = CouncilExecutionPurpose.STANDARD,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val acceptanceProfile: AcceptanceProfile = AcceptanceProfile.NORMAL,
    val objective: String,
    val groupCount: Int,
    val generationCeiling: Int,
    val startingFieldArchiveId: String,
    val startingFieldSha256: String,
    val runMetricsSha256: String? = null,
    val acceptanceAdmissionSha256: String? = null,
    val outcomeManifestSha256: String? = null,
    val artifactCount: Int,
    val archiveEntryCount: Int,
    val inputArtifacts: List<RunInputArtifact>,
    val entries: List<RunManifestEntry>
)

/** Hash and provenance assertion for one file in a run bundle. */
@Serializable
data class RunManifestEntry(
    val runId: String,
    val path: String,
    val category: RunManifestEntryCategory,
    val sizeBytes: Long,
    val sha256: String,
    val mediaType: String? = null,
    val finalDeliverableReferences: List<RunManifestFinalReference> = emptyList()
)

/** Exact immutable final-output authority referenced by one manifest file entry. */
@Serializable
data class RunManifestFinalReference(
    val referenceId: String,
    val referenceHash: String,
    val nodeId: String?,
    val componentId: String? = null,
    val relativePath: String? = null,
    val mediaType: String? = null,
    val contentSha256: String? = null
)

/** Exact append-only event-log row exported independently from its database file. */
@Serializable
data class RunManifestEventLogRecord(
    val id: String,
    val timestampMs: Long,
    val eventType: String,
    val agentId: String?,
    val groupId: String?,
    val runId: String,
    val generation: Int,
    val payloadJson: String
)

/** Evidence category used by required-attestation verification. */
@Serializable
enum class RunManifestEntryCategory {
    STARTING_FIELD,
    COUNCIL_FIELD,
    WORKER_ATLAS,
    GRADER_ATLAS,
    RUN_CONFIG_METADATA,
    BINARY_METADATA,
    LOG,
    DATABASE,
    INPUT,
    OUTPUT,
    CONTRIBUTION_LINEAGE,
    PUBLICATION_AUTHORITY,
    RUN_METRICS,
    ACCEPTANCE_ADMISSION,
    RUN_OUTCOME_MANIFEST,
    ARCHIVE
}

/** Non-secret hash metadata for the run configuration used to launch execution. */
@Serializable
data class RunConfigAttestation(
    val runId: String,
    val source: String,
    val sourceSizeBytes: Long,
    val sourceSha256: String
)

/** Runtime and code-source metadata for the engine that produced the bundle. */
@Serializable
data class RunBinaryAttestation(
    val runId: String,
    val component: String,
    val implementationVersion: String,
    val javaVersion: String,
    val source: String,
    val sourceSha256: String
)

/** Explicit result returned by [RunManifestVerifier]. */
data class RunManifestVerificationResult(
    val manifest: RunManifest?,
    val errors: List<String>
) {
    /** True only when the bundle is complete, internally consistent, and hash-valid. */
    val isValid: Boolean get() = errors.isEmpty() && manifest != null
}

/** Verifies hashes, required evidence categories, and one-run provenance for a results ZIP. */
object RunManifestVerifier {
    private val json = Json { ignoreUnknownKeys = false }
    private val requiredCategories = setOf(
        RunManifestEntryCategory.STARTING_FIELD,
        RunManifestEntryCategory.RUN_CONFIG_METADATA,
        RunManifestEntryCategory.BINARY_METADATA,
        RunManifestEntryCategory.LOG,
        RunManifestEntryCategory.DATABASE,
        RunManifestEntryCategory.OUTPUT
    )
    private val requiredV2Categories = requiredCategories + setOf(
        RunManifestEntryCategory.COUNCIL_FIELD,
        RunManifestEntryCategory.WORKER_ATLAS,
        RunManifestEntryCategory.GRADER_ATLAS,
        RunManifestEntryCategory.CONTRIBUTION_LINEAGE,
        RunManifestEntryCategory.PUBLICATION_AUTHORITY
    )
    private val requiredV3Categories =
        requiredV2Categories + RunManifestEntryCategory.RUN_METRICS
    private val requiredV4Categories =
        requiredV3Categories + RunManifestEntryCategory.ACCEPTANCE_ADMISSION
    private val requiredV5Categories = setOf(
        RunManifestEntryCategory.OUTPUT,
        RunManifestEntryCategory.RUN_OUTCOME_MANIFEST
    )

    /**
     * Verifies [bundleBytes] and optionally requires [expectedRunId].
     *
     * @param bundleBytes Results ZIP bytes.
     * @param expectedRunId Operator-expected run ID, when known.
     * @param expectedCouncilExecutionPurpose Operator-expected Council purpose, when known.
     * @param expectedAcceptanceProfile Operator-expected acceptance profile, when known.
     * @return Complete verification result with every detected error.
     */
    fun verify(
        bundleBytes: ByteArray,
        expectedRunId: String? = null,
        expectedCouncilExecutionPurpose: CouncilExecutionPurpose? = null,
        expectedAcceptanceProfile: AcceptanceProfile? = null
    ): RunManifestVerificationResult {
        val errors = mutableListOf<String>()
        val files = linkedMapOf<String, ByteArray>()
        runCatching {
            ZipInputStream(ByteArrayInputStream(bundleBytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (files.put(entry.name, zip.readBytes()) != null) {
                        errors += "Duplicate ZIP entry: ${entry.name}"
                    }
                    zip.closeEntry()
                }
            }
        }.onFailure { errors += "Unreadable ZIP: ${it.message}" }
        val manifestBytes = files["manifest.json"]
            ?: return RunManifestVerificationResult(null, errors + "Missing manifest.json")
        val manifest = runCatching {
            json.decodeFromString<RunManifest>(manifestBytes.toString(Charsets.UTF_8))
        }.getOrElse {
            return RunManifestVerificationResult(null, errors + "Invalid manifest.json: ${it.message}")
        }
        if (manifest.schemaVersion !in 1..5) {
            errors += "Unsupported manifest schema version: ${manifest.schemaVersion}"
        }
        if (
            manifest.acceptanceProfile != AcceptanceProfile.NORMAL &&
            manifest.schemaVersion !in setOf(4, 5)
        ) {
            errors += "Non-normal acceptance manifest must use schemaVersion=4 or schemaVersion=5"
        }

        if (expectedRunId != null && manifest.runId != expectedRunId) {
            errors += "Manifest runId=${manifest.runId} does not match expected runId=$expectedRunId"
        }
        if (
            expectedCouncilExecutionPurpose != null &&
            manifest.councilExecutionPurpose != expectedCouncilExecutionPurpose
        ) {
            errors += "Manifest Council execution purpose=${manifest.councilExecutionPurpose.id} " +
                "does not match expected purpose=${expectedCouncilExecutionPurpose.id}"
        }
        if (
            expectedAcceptanceProfile != null &&
            manifest.acceptanceProfile != expectedAcceptanceProfile
        ) {
            errors += "Manifest acceptance profile=${manifest.acceptanceProfile.id} " +
                "does not match expected profile=${expectedAcceptanceProfile.id}"
        }
        val duplicatePaths = manifest.entries.groupingBy { it.path }.eachCount().filterValues { it > 1 }.keys
        duplicatePaths.forEach { errors += "Duplicate manifest path: $it" }
        val expectedPaths = manifest.entries.mapTo(linkedSetOf()) { it.path } + "manifest.json"
        (expectedPaths - files.keys).forEach { errors += "Missing attested entry: $it" }
        (files.keys - expectedPaths).forEach { errors += "Unattested ZIP entry: $it" }

        manifest.entries.forEach { entry ->
            if (entry.runId != manifest.runId) {
                errors += "Mixed-run entry ${entry.path}: ${entry.runId} != ${manifest.runId}"
            }
            files[entry.path]?.let { content ->
                if (content.size.toLong() != entry.sizeBytes) {
                    errors += "Size mismatch for ${entry.path}"
                }
                if (sha256Hex(content) != entry.sha256) {
                    errors += "Hash mismatch for ${entry.path}"
                }
            }
            entry.finalDeliverableReferences.forEach { reference ->
                if (reference.referenceId.isBlank() || reference.referenceHash.isBlank()) {
                    errors += "Blank final-deliverable authority on ${entry.path}"
                }
                if (reference.nodeId != null && reference.nodeId.isBlank()) {
                    errors += "Blank final-deliverable node ID on ${entry.path}"
                }
                if (reference.componentId != null && reference.componentId.isBlank()) {
                    errors += "Blank final-deliverable component ID on ${entry.path}"
                }
            }
        }
        val required = when (manifest.schemaVersion) {
            1 -> requiredCategories
            2 -> requiredV2Categories
            3 -> requiredV3Categories
            4 -> requiredV4Categories
            else -> requiredV5Categories
        }
        (required - manifest.entries.mapTo(mutableSetOf()) { it.category }).forEach {
            errors += "Missing required evidence category: $it"
        }
        val exportedArchiveEntryCount = manifest.entries.count {
            it.category == RunManifestEntryCategory.ARCHIVE
        }
        if (manifest.archiveEntryCount != exportedArchiveEntryCount) {
            errors += "Manifest archiveEntryCount=${manifest.archiveEntryCount} does not match " +
                "exported archive entries=$exportedArchiveEntryCount"
        }
        if (manifest.schemaVersion in 2..4) {
            verifyCanonicalAuthorityMappings(manifest, errors)
        }
        val startingFieldBytes = files["starting-field.json"]
        if (startingFieldBytes != null) {
            if (sha256Hex(startingFieldBytes) != manifest.startingFieldSha256) {
                errors += "StartingField hash does not match manifest"
            }
            val startingFieldJson = startingFieldBytes.toString(Charsets.UTF_8)
            val wireError = StartingFieldWireContract.validationError(
                startingFieldJson,
                "starting-field.json"
            )
            if (wireError != null) {
                errors += wireError
            } else {
                runCatching {
                    json.decodeFromString<StartingField>(startingFieldJson)
                }.onSuccess { field ->
                    if (field.runId != manifest.runId) {
                        errors += "StartingField belongs to runId=${field.runId}"
                    }
                    if (field.objective != manifest.objective) {
                        errors += "Manifest objective does not match StartingField"
                    }
                    if (field.groupCount != manifest.groupCount) {
                        errors += "Manifest groupCount does not match StartingField"
                    }
                    if (field.generationCeiling != manifest.generationCeiling) {
                        errors += "Manifest generationCeiling does not match StartingField"
                    }
                    if (field.inputArtifacts.size != manifest.artifactCount) {
                        errors += "Manifest artifactCount does not match StartingField"
                    }
                    if (field.inputArtifacts != manifest.inputArtifacts) {
                        errors += "Manifest inputArtifacts do not match StartingField"
                    }
                    field.inputArtifacts.forEach { artifact ->
                        val path = "inputs/${artifact.relativePath}"
                        val content = files[path]
                        if (content == null) {
                            errors += "Missing declared input artifact: $path"
                        } else if (sha256Hex(content) != artifact.sha256) {
                            errors += "Input artifact hash mismatch: $path"
                        }
                    }
                }.onFailure { errors += "Invalid starting-field.json: ${it.message}" }
            }
        }
        val persistedAuthority = verifyRunConfigAttestation(
            files = files,
            manifest = manifest,
            manifestRunId = manifest.runId,
            errors = errors
        )
        if (
            persistedAuthority != null &&
            persistedAuthority.councilExecutionPurpose != manifest.councilExecutionPurpose
        ) {
            errors += "Manifest Council execution purpose does not match persisted run config"
        }
        if (
            persistedAuthority != null &&
            persistedAuthority.acceptanceProfile != manifest.acceptanceProfile
        ) {
            errors += "Manifest acceptance profile does not match persisted run config"
        }
        verifyMetadataRunId<RunBinaryAttestation>(
            files = files,
            path = "metadata/binary.json",
            manifestRunId = manifest.runId,
            errors = errors
        ) { it.runId }
        if (manifest.schemaVersion in 2..4) {
            verifyV2Authorities(files, manifest, errors)
        }
        if (
            manifest.schemaVersion in 3..4 ||
            (manifest.schemaVersion < 5 && manifest.runMetricsSha256 != null)
        ) {
            verifyV3RunMetrics(files, manifest, errors)
        }
        if (
            manifest.schemaVersion == 4 ||
            manifest.acceptanceAdmissionSha256 != null ||
            manifest.acceptanceProfile != AcceptanceProfile.NORMAL
        ) {
            verifyV4AcceptanceAdmission(files, manifest, errors)
        }
        if (manifest.schemaVersion == 5 || manifest.outcomeManifestSha256 != null) {
            verifyV5OutcomeManifest(files, manifest, errors)
        }
        return RunManifestVerificationResult(manifest, errors.distinct())
    }

    private fun verifyV5OutcomeManifest(
        files: Map<String, ByteArray>,
        manifest: RunManifest,
        errors: MutableList<String>,
    ) {
        if (manifest.schemaVersion != 5) {
            errors += "Outcome-aware manifests must use schemaVersion=5"
            return
        }
        val entries = manifest.entries.filter {
            it.category == RunManifestEntryCategory.RUN_OUTCOME_MANIFEST
        }
        if (entries.size != 1 || entries.singleOrNull()?.path != OUTCOME_MANIFEST_PATH) {
            errors += "Schema-v5 manifest requires exactly one $OUTCOME_MANIFEST_PATH entry"
        }
        val commitment = manifest.outcomeManifestSha256
        if (commitment == null || !SHA_256_REGEX.matches(commitment)) {
            errors += "Schema-v5 manifest has no valid outcomeManifestSha256 commitment"
        }
        val bytes = files[OUTCOME_MANIFEST_PATH] ?: return
        val actualHash = sha256Hex(bytes)
        if (actualHash != commitment) {
            errors += "Run outcome manifest hash does not match manifest commitment"
        }
        if (entries.singleOrNull()?.sha256 != actualHash) {
            errors += "Run outcome manifest entry hash does not match its content"
        }
        val outcome = runCatching {
            json.decodeFromString<RunOutcomeManifestV1>(bytes.toString(Charsets.UTF_8))
        }.getOrElse {
            errors += "Invalid $OUTCOME_MANIFEST_PATH: ${it.message}"
            return
        }
        if (outcome.runId != manifest.runId) {
            errors += "Run outcome manifest belongs to runId=${outcome.runId}"
        }
        if (
            outcome.outcomeClass !=
            com.achulkov.evomolior.core.deliverable.RunOutcomeClass.ACCEPTED_DECISION_PACKAGE
        ) {
            errors += "RunManifest schema-v5 is reserved for accepted outcome archives"
        }
        if (!outcome.hasValidHashAndSemantics()) {
            errors += "Run outcome manifest hash or class separation is invalid"
        }
        if (outcome.acceptanceAdmissionHash != manifest.acceptanceAdmissionSha256) {
            errors += "Run outcome admission hash does not match manifest commitment"
        }
        if (
            manifest.acceptanceProfile != AcceptanceProfile.NORMAL &&
            outcome.acceptanceAdmissionHash == null
        ) {
            errors += "Non-normal outcome manifest lacks acceptance admission authority"
        }
        outcome.acceptanceAdmissionHash?.let { admissionHash ->
            val admissionBytes = files[ACCEPTANCE_ADMISSION_PATH]
            if (admissionBytes == null) {
                errors += "Run outcome admission bytes are missing"
            } else {
                if (sha256Hex(admissionBytes) != admissionHash) {
                    errors += "Run outcome admission bytes do not match outcome commitment"
                }
                runCatching {
                    decodeAcceptanceAdmissionArtifact(admissionBytes)
                }.onSuccess { admission ->
                    if (
                        admission.distributionManifestSha256 !=
                        outcome.admissionDistributionHash
                    ) {
                        errors += "Acceptance admission distribution hash does not match outcome manifest"
                    }
                }
            }
        }
        val outcomeEntriesByPath = outcome.entries.associateBy { it.path }
        if (outcomeEntriesByPath.size != outcome.entries.size) {
            errors += "Run outcome manifest contains duplicate paths"
        }
        outcome.entries.forEach { entry ->
            val content = files[entry.path]
            if (content == null) {
                errors += "Missing outcome-attested entry: ${entry.path}"
            } else {
                if (content.size.toLong() != entry.sizeBytes) {
                    errors += "Outcome entry size mismatch for ${entry.path}"
                }
                if (sha256Hex(content) != entry.sha256) {
                    errors += "Outcome entry hash mismatch for ${entry.path}"
                }
            }
        }
        val payloadPaths = files.keys - setOf("manifest.json", OUTCOME_MANIFEST_PATH)
        if (payloadPaths != outcomeEntriesByPath.keys) {
            (payloadPaths - outcomeEntriesByPath.keys).forEach {
                errors += "Outcome-unattested ZIP entry: $it"
            }
            (outcomeEntriesByPath.keys - payloadPaths).forEach {
                errors += "Missing outcome payload: $it"
            }
        }
        val acceptedAuthorityPaths = outcome.entries.filter {
            it.category == RunOutcomeManifestEntryCategory.ACCEPTED_AUTHORITY
        }.map { it.path }.toSet()
        if ("authority/final-reference.json" !in acceptedAuthorityPaths) {
            errors += "Accepted schema-v5 archive lacks final-reference package authority"
        }
        verifyV5AcceptedAuthority(files, outcome, errors)
        verifyV5AcceptedSupportAuthorities(files, outcome, errors)
    }

    private fun verifyV5AcceptedAuthority(
        files: Map<String, ByteArray>,
        outcome: RunOutcomeManifestV1,
        errors: MutableList<String>,
    ) {
        val packageEntries = outcome.entries.filter {
            it.category == RunOutcomeManifestEntryCategory.RECEIVER_DECISION_PACKAGE
        }
        if (packageEntries.isEmpty()) {
            errors += "Accepted schema-v5 archive lacks receiver decision-package authority"
            return
        }
        val packages = packageEntries.mapNotNull { entry ->
            val bytes = files[entry.path] ?: return@mapNotNull null
            runCatching {
                DeliverableHashing.decodePackage(bytes.toString(Charsets.UTF_8))
            }.getOrElse {
                errors += "Invalid receiver decision package ${entry.path}: ${it.message}"
                null
            }
        }
        val validPackages = packages.filter { decisionPackage ->
            decisionPackage.runId == outcome.runId &&
                decisionPackage.outcomeClass == RunOutcomeClass.ACCEPTED_DECISION_PACKAGE &&
                decisionPackage.packageHash == outcome.decisionPackageHash &&
                decisionPackage.hasValidHashes() &&
                decisionPackage.acceptedAuthority != null
        }
        if (validPackages.size != packages.size || validPackages.isEmpty()) {
            errors += "Accepted schema-v5 decision package lacks a valid winner/source/writer chain"
            return
        }
        val packageAuthority = requireNotNull(validPackages.first().acceptedAuthority)
        if (validPackages.any { it.acceptedAuthority != packageAuthority }) {
            errors += "Accepted schema-v5 decision packages disagree on accepted authority"
            return
        }
        val authorityEntry = outcome.entries.singleOrNull {
            it.path == ACCEPTED_AUTHORITY_PATH &&
                it.category == RunOutcomeManifestEntryCategory.ACCEPTED_AUTHORITY
        }
        if (authorityEntry == null) {
            errors += "Accepted schema-v5 archive requires exactly one $ACCEPTED_AUTHORITY_PATH entry"
            return
        }
        val authorityBytes = files[authorityEntry.path] ?: return
        val fileAuthority = runCatching {
            json.decodeFromString<AcceptedDeliverableAuthority>(
                authorityBytes.toString(Charsets.UTF_8)
            )
        }.getOrElse {
            errors += "Invalid accepted authority ${authorityEntry.path}: ${it.message}"
            return
        }
        if (fileAuthority != packageAuthority) {
            errors += "Accepted final-reference authority differs from the receiver decision package"
        }
        if (
            outcome.acceptedAuthorityHash !=
            acceptedDeliverableAuthorityHash(fileAuthority)
        ) {
            errors += "Accepted final-reference authority hash does not match outcome manifest"
        }
    }

    private fun verifyV5AcceptedSupportAuthorities(
        files: Map<String, ByteArray>,
        outcome: RunOutcomeManifestV1,
        errors: MutableList<String>,
    ) {
        val metricsBytes = files[ACCEPTED_RUN_METRICS_PATH]
        if (metricsBytes == null) {
            errors += "Accepted schema-v5 archive lacks $ACCEPTED_RUN_METRICS_PATH"
        } else {
            if (sha256Hex(metricsBytes) != outcome.metricsHash) {
                errors += "Accepted run metrics hash does not match outcome manifest"
            }
            runCatching {
                json.decodeFromString<RunMetricsV1>(metricsBytes.toString(Charsets.UTF_8))
            }.onSuccess { metrics ->
                if (metrics.runId != outcome.runId) {
                    errors += "Accepted run metrics belong to runId=${metrics.runId}"
                }
            }.onFailure {
                errors += "Invalid $ACCEPTED_RUN_METRICS_PATH: ${it.message}"
            }
        }

        val historyBytes = files[ACCEPTED_PUBLICATION_HISTORY_PATH]
        if (historyBytes == null) {
            errors += "Accepted schema-v5 archive lacks $ACCEPTED_PUBLICATION_HISTORY_PATH"
        } else {
            if (sha256Hex(historyBytes) != outcome.publicationHistoryHash) {
                errors += "Accepted publication history hash does not match outcome manifest"
            }
            runCatching {
                json.decodeFromString(
                    ListSerializer(PublicationStateEvent.serializer()),
                    historyBytes.toString(Charsets.UTF_8),
                )
            }.onSuccess { history ->
                if (history.isEmpty()) {
                    errors += "Accepted publication history is empty"
                } else {
                    history.forEachIndexed { index, event ->
                        if (
                            event.runId != outcome.runId ||
                            !event.hasValidHash() ||
                            !PublicationStatePolicy.allows(history.getOrNull(index - 1), event)
                        ) {
                            errors += "Invalid accepted publication event at index $index"
                        }
                    }
                    if (history.last().state !=
                        com.achulkov.evomolior.core.contract.PublicationState.ARCHIVED
                    ) {
                        errors += "Accepted publication history does not terminate at ARCHIVED"
                    }
                }
            }.onFailure {
                errors += "Invalid $ACCEPTED_PUBLICATION_HISTORY_PATH: ${it.message}"
            }
        }

        val reportBytes = files[MechanismActivationReportV1.ARCHIVE_PATH]
        if (reportBytes == null) {
            errors += "Accepted schema-v5 archive lacks ${MechanismActivationReportV1.ARCHIVE_PATH}"
        } else {
            runCatching {
                json.decodeFromString<MechanismActivationReportV1>(
                    reportBytes.toString(Charsets.UTF_8)
                )
            }.onSuccess { report ->
                if (
                    report.runId != outcome.runId ||
                    report.outcomeClass != RunOutcomeClass.ACCEPTED_DECISION_PACKAGE ||
                    !report.hasValidHash()
                ) {
                    errors += "Accepted mechanism activation report is invalid"
                }
            }.onFailure {
                errors += "Invalid ${MechanismActivationReportV1.ARCHIVE_PATH}: ${it.message}"
            }
        }
    }

    private fun verifyV4AcceptanceAdmission(
        files: Map<String, ByteArray>,
        manifest: RunManifest,
        errors: MutableList<String>
    ) {
        val entries = manifest.entries.filter {
            it.category == RunManifestEntryCategory.ACCEPTANCE_ADMISSION
        }
        if (
            entries.size != 1 ||
            entries.singleOrNull()?.path != ACCEPTANCE_ADMISSION_PATH
        ) {
            errors += "Acceptance manifest requires exactly one $ACCEPTANCE_ADMISSION_PATH " +
                "ACCEPTANCE_ADMISSION entry"
        }
        manifest.entries
            .filter { it.path == ACCEPTANCE_ADMISSION_PATH }
            .filter { it.category != RunManifestEntryCategory.ACCEPTANCE_ADMISSION }
            .forEach {
                errors += "$ACCEPTANCE_ADMISSION_PATH has category ${it.category}, " +
                    "expected ACCEPTANCE_ADMISSION"
            }
        val commitment = manifest.acceptanceAdmissionSha256
        if (commitment == null || !SHA_256_REGEX.matches(commitment)) {
            errors += "Acceptance manifest has no valid acceptanceAdmissionSha256 commitment"
        }
        val bytes = files[ACCEPTANCE_ADMISSION_PATH] ?: return
        val actualHash = sha256Hex(bytes)
        if (commitment != null && actualHash != commitment) {
            errors += "Acceptance admission hash does not match manifest"
        }
        if (entries.singleOrNull()?.sha256 != commitment) {
            errors += "Acceptance admission entry hash does not match manifest commitment"
        }
        runCatching {
            json.decodeFromString<AcceptanceAdmissionArtifact>(bytes.toString(Charsets.UTF_8))
        }.onSuccess { admission ->
            if (admission.runId != manifest.runId) {
                errors += "Acceptance admission belongs to runId=${admission.runId}"
            }
            if (admission.acceptanceProfile != manifest.acceptanceProfile) {
                errors += "Acceptance admission profile does not match manifest"
            }
        }.onFailure {
            errors += "Invalid $ACCEPTANCE_ADMISSION_PATH: ${it.message}"
        }
    }

    private fun verifyV3RunMetrics(
        files: Map<String, ByteArray>,
        manifest: RunManifest,
        errors: MutableList<String>
    ) {
        val metricEntries = manifest.entries.filter {
            it.category == RunManifestEntryCategory.RUN_METRICS
        }
        if (
            metricEntries.size != 1 ||
            metricEntries.singleOrNull()?.path != RUN_METRICS_PATH
        ) {
            errors += "Metric-committed manifest requires exactly one " +
                "$RUN_METRICS_PATH RUN_METRICS entry"
        }
        manifest.entries
            .filter { it.path == RUN_METRICS_PATH }
            .filter { it.category != RunManifestEntryCategory.RUN_METRICS }
            .forEach {
                errors += "$RUN_METRICS_PATH has category ${it.category}, expected RUN_METRICS"
            }

        val manifestHash = manifest.runMetricsSha256
        if (manifestHash == null || !SHA_256_REGEX.matches(manifestHash)) {
            errors += "Metric-committed manifest has no valid runMetricsSha256 commitment"
        }
        val bytes = files[RUN_METRICS_PATH] ?: return
        val entry = metricEntries.singleOrNull { it.path == RUN_METRICS_PATH }
        val actualHash = sha256Hex(bytes)
        if (manifestHash != null && actualHash != manifestHash) {
            errors += "Run metrics hash does not match manifest"
        }
        if (entry != null && entry.sha256 != manifestHash) {
            errors += "Run metrics entry hash does not match manifest commitment"
        }
        runCatching {
            json.decodeFromString<RunMetricsV1>(bytes.toString(Charsets.UTF_8))
        }.onSuccess { metrics ->
            if (metrics.runId != manifest.runId) {
                errors += "Run metrics belong to runId=${metrics.runId}"
            }
        }.onFailure {
            errors += "Invalid $RUN_METRICS_PATH: ${it.message}"
        }
    }

    private fun verifyV2Authorities(
        files: Map<String, ByteArray>,
        manifest: RunManifest,
        errors: MutableList<String>
    ) {
        val workerAtlas = decodeAuthority(
            files,
            "authority/worker-atlas.json",
            NodeAtlas.serializer(),
            errors
        )
        if (
            workerAtlas != null &&
            !AtlasHashing.constantTimeEquals(
                workerAtlas.atlasHash,
                AtlasHashing.hashAtlas(workerAtlas)
            )
        ) {
            errors += "Worker Atlas self-hash is invalid"
        }
        val graderAtlas = decodeAuthority(
            files,
            "authority/grader-atlas.json",
            GraderAtlas.serializer(),
            errors
        )
        if (
            graderAtlas != null &&
            !AtlasHashing.constantTimeEquals(
                graderAtlas.atlasHash,
                AtlasHashing.hashGraderAtlas(graderAtlas)
            )
        ) {
            errors += "Grader Atlas self-hash is invalid"
        }
        val candidates = decodeAuthority(
            files,
            "authority/final-candidates.json",
            ListSerializer(FinalCandidateRecord.serializer()),
            errors
        ).orEmpty()
        candidates.filter { it.runId != manifest.runId || !it.hasValidHash() }
            .forEach { errors += "Invalid final-candidate authority: ${it.candidateId}" }
        val finalReferences = decodeAuthority(
            files,
            "authority/final-deliverable-references.json",
            ListSerializer(FinalDeliverableReferenceRecord.serializer()),
            errors
        ).orEmpty()
        finalReferences.filter { it.runId != manifest.runId || !it.hasValidHash() }
            .forEach { errors += "Invalid final-deliverable authority: ${it.referenceId}" }
        val referencesById = finalReferences.associateBy(FinalDeliverableReferenceRecord::referenceId)
        manifest.entries
            .filter { it.finalDeliverableReferences.isNotEmpty() }
            .forEach { entry ->
                if (entry.category != RunManifestEntryCategory.OUTPUT) {
                    errors += "Final-deliverable lineage is attached to non-output entry: ${entry.path}"
                }
                entry.finalDeliverableReferences
                    .distinct()
                    .forEach { reference ->
                        val authority = referencesById[reference.referenceId]
                        if (
                            authority == null ||
                            !reference.matches(
                                authority = authority,
                                requireProjectionFields = manifest.schemaVersion >= 4
                            ) ||
                            entry.path != outputPath(authority.relativePath) ||
                            (manifest.schemaVersion >= 4 && entry.mediaType == null) ||
                            (entry.mediaType != null && entry.mediaType != authority.mediaType) ||
                            entry.sha256 != authority.contentHash
                        ) {
                            errors += "Manifest final-deliverable reference does not bind exact output: " +
                                "${reference.referenceId} on ${entry.path}"
                        }
                    }
            }
        finalReferences.forEach { authority ->
            val expectedPath = outputPath(authority.relativePath)
            val matchingEntry = manifest.entries.singleOrNull { it.path == expectedPath }
            if (
                matchingEntry == null ||
                matchingEntry.category != RunManifestEntryCategory.OUTPUT ||
                (manifest.schemaVersion >= 4 && matchingEntry.mediaType == null) ||
                (matchingEntry.mediaType != null &&
                    matchingEntry.mediaType != authority.mediaType) ||
                matchingEntry.sha256 != authority.contentHash ||
                matchingEntry.finalDeliverableReferences.none {
                    it.matches(
                        authority = authority,
                        requireProjectionFields = manifest.schemaVersion >= 4
                    )
                }
            ) {
                errors += "Final-deliverable authority is not bound to its exact output: " +
                    authority.referenceId
            }
        }
        val publication = decodeAuthority(
            files,
            "authority/publication-history.json",
            ListSerializer(PublicationStateEvent.serializer()),
            errors
        ).orEmpty()
        publication.forEachIndexed { index, event ->
            val previous = publication.getOrNull(index - 1)
            if (
                event.runId != manifest.runId ||
                !event.hasValidHash() ||
                !PublicationStatePolicy.allows(previous, event)
            ) {
                errors += "Invalid publication authority at sequence ${event.sequence}"
            }
        }
        files.filterKeys { it.startsWith("authority/contribution-lineage/") }
            .forEach { (path, bytes) ->
                val edges = runCatching {
                    json.decodeFromString(
                        ListSerializer(ContributionEdge.serializer()),
                        bytes.toString(Charsets.UTF_8)
                    )
                }.getOrElse {
                    errors += "Invalid contribution lineage $path: ${it.message}"
                    emptyList()
                }
                edges.filter { it.runId != manifest.runId || !it.hasValidHash() }
                    .forEach { errors += "Invalid contribution edge in $path: ${it.edgeId}" }
            }
        val eventIds = linkedSetOf<String>()
        files.filterKeys { it.startsWith("logs/") && it.endsWith(".json") }
            .forEach { (path, bytes) ->
                val events = runCatching {
                    json.decodeFromString(
                        ListSerializer(RunManifestEventLogRecord.serializer()),
                        bytes.toString(Charsets.UTF_8)
                    )
                }.getOrElse {
                    errors += "Invalid event-log export $path: ${it.message}"
                    emptyList()
                }
                events.forEach { event ->
                    if (event.runId != manifest.runId) {
                        errors += "Mixed-run event ${event.id} in $path"
                    }
                    if (!eventIds.add(event.id)) {
                        errors += "Duplicate event authority ${event.id}"
                    }
                }
            }
    }

    private fun <T> decodeAuthority(
        files: Map<String, ByteArray>,
        path: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        errors: MutableList<String>
    ): T? {
        val bytes = files[path] ?: return null
        return runCatching {
            json.decodeFromString(serializer, bytes.toString(Charsets.UTF_8))
        }.getOrElse {
            errors += "Invalid authority $path: ${it.message}"
            null
        }
    }

    private inline fun <reified T> verifyMetadataRunId(
        files: Map<String, ByteArray>,
        path: String,
        manifestRunId: String,
        errors: MutableList<String>,
        runId: (T) -> String
    ) {
        val bytes = files[path] ?: return
        runCatching { json.decodeFromString<T>(bytes.toString(Charsets.UTF_8)) }
            .onSuccess { metadata ->
                if (runId(metadata) != manifestRunId) errors += "Mixed-run metadata: $path"
            }
            .onFailure { errors += "Invalid metadata $path: ${it.message}" }
    }

    private fun verifyRunConfigAttestation(
        files: Map<String, ByteArray>,
        manifest: RunManifest,
        manifestRunId: String,
        errors: MutableList<String>
    ): PersistedRunAuthority? {
        val path = "metadata/run-config.json"
        val bytes = files[path] ?: return null
        val attestation = runCatching {
            json.decodeFromString<RunConfigAttestation>(bytes.toString(Charsets.UTF_8))
        }.getOrElse {
            errors += "Invalid metadata $path: ${it.message}"
            return null
        }
        if (attestation.runId != manifestRunId) {
            errors += "Mixed-run metadata: $path"
        }
        val sourceBytes = files[attestation.source]
        if (sourceBytes == null) {
            errors += "Missing run-config attestation source: ${attestation.source}"
            return null
        }
        if (sourceBytes.size.toLong() != attestation.sourceSizeBytes) {
            errors += "Run-config attestation source size mismatch"
        }
        if (sha256Hex(sourceBytes) != attestation.sourceSha256) {
            errors += "Run-config attestation source hash mismatch"
        }
        if (attestation.source != "control/${PersistedRunStateStore.STATE_FILE_NAME}") {
            return null
        }
        val sourceEntry = manifest.entries.singleOrNull { it.path == attestation.source }
        if (sourceEntry?.category != RunManifestEntryCategory.RUN_CONFIG_METADATA) {
            errors += "Persisted run-config source ${attestation.source} has category " +
                "${sourceEntry?.category}, expected RUN_CONFIG_METADATA"
        }
        return runCatching {
            json.decodeFromString<PersistedRunState>(sourceBytes.toString(Charsets.UTF_8))
        }.onSuccess { state ->
            if (state.runId != manifestRunId) {
                errors += "Persisted run config belongs to runId=${state.runId}"
            }
        }.onFailure {
            errors += "Invalid persisted run config: ${it.message}"
        }.getOrNull()?.let { state ->
            PersistedRunAuthority(
                councilExecutionPurpose = state.councilExecutionPurpose,
                acceptanceProfile = state.acceptanceProfile
            )
        }
    }

    private data class PersistedRunAuthority(
        val councilExecutionPurpose: CouncilExecutionPurpose,
        val acceptanceProfile: AcceptanceProfile
    )

    private fun verifyCanonicalAuthorityMappings(
        manifest: RunManifest,
        errors: MutableList<String>
    ) {
        requireExactlyOne(
            manifest,
            "authority/council-field.json",
            RunManifestEntryCategory.COUNCIL_FIELD,
            errors
        )
        requireExactlyOne(
            manifest,
            "authority/worker-atlas.json",
            RunManifestEntryCategory.WORKER_ATLAS,
            errors
        )
        requireExactlyOne(
            manifest,
            "authority/grader-atlas.json",
            RunManifestEntryCategory.GRADER_ATLAS,
            errors
        )
        V2_PUBLICATION_AUTHORITIES.forEach { path ->
            requireExactlyOne(
                manifest,
                path,
                RunManifestEntryCategory.PUBLICATION_AUTHORITY,
                errors
            )
        }
        requireExactlyOne(
            manifest,
            RUN_CONFIG_ATTESTATION_PATH,
            RunManifestEntryCategory.RUN_CONFIG_METADATA,
            errors
        )
        SINGLETON_AUTHORITY_PATHS.forEach { (path, category) ->
            manifest.entries
                .filter { it.category == category && it.path != path }
                .forEach {
                    errors += "${it.path} has category ${it.category}, expected a canonical $path entry"
                }
        }
        manifest.entries
            .filter { it.category == RunManifestEntryCategory.RUN_CONFIG_METADATA }
            .filter { it.path !in RUN_CONFIG_METADATA_PATHS }
            .forEach {
                errors += "Run-config metadata has non-canonical path: ${it.path}"
            }
        manifest.entries
            .filter { it.category == RunManifestEntryCategory.PUBLICATION_AUTHORITY }
            .filter { it.path !in V2_PUBLICATION_AUTHORITIES }
            .forEach {
                errors += "Publication authority has non-canonical path: ${it.path}"
            }
        requireExactlyOne(
            manifest,
            BINARY_METADATA_PATH,
            RunManifestEntryCategory.BINARY_METADATA,
            errors
        )

        manifest.entries
            .filter { it.category == RunManifestEntryCategory.CONTRIBUTION_LINEAGE }
            .filter { !CONTRIBUTION_LINEAGE_PATH.matches(it.path) }
            .forEach { errors += "Contribution lineage has non-canonical path: ${it.path}" }
        manifest.entries
            .filter { CONTRIBUTION_LINEAGE_PATH.matches(it.path) }
            .filter { it.category != RunManifestEntryCategory.CONTRIBUTION_LINEAGE }
            .forEach {
                errors += "${it.path} has category ${it.category}, expected CONTRIBUTION_LINEAGE"
            }
        if (manifest.entries.none { it.category == RunManifestEntryCategory.CONTRIBUTION_LINEAGE }) {
            errors += "Manifest requires at least one canonical CONTRIBUTION_LINEAGE entry"
        }
        if (manifest.schemaVersion >= 3) {
            requireExactlyOne(
                manifest,
                RUN_METRICS_PATH,
                RunManifestEntryCategory.RUN_METRICS,
                errors
            )
        }
        if (manifest.schemaVersion >= 4) {
            requireExactlyOne(
                manifest,
                ACCEPTANCE_ADMISSION_PATH,
                RunManifestEntryCategory.ACCEPTANCE_ADMISSION,
                errors
            )
        }
    }

    private fun requireExactlyOne(
        manifest: RunManifest,
        path: String,
        category: RunManifestEntryCategory,
        errors: MutableList<String>
    ) {
        val entries = manifest.entries.filter { it.path == path }
        if (entries.size != 1) {
            errors += "Manifest requires exactly one $path entry"
            return
        }
        if (entries.single().category != category) {
            errors += "$path has category ${entries.single().category}, expected $category"
        }
    }

    private fun outputPath(relativePath: String): String = "outputs/$relativePath"

    /**
     * Matches legacy and current manifest projections to one immutable final-reference authority.
     *
     * Schema-v2/v3 archives written before the projection gained duplicate path/media/hash fields
     * remain verifiable. Schema-v4 requires the complete projection; the authority record and
     * output entry remain the canonical path/content binding.
     */
    private fun RunManifestFinalReference.matches(
        authority: FinalDeliverableReferenceRecord,
        requireProjectionFields: Boolean
    ): Boolean =
        referenceId == authority.referenceId &&
            referenceHash == authority.recordHash &&
            nodeId == authority.nodeId &&
            componentId == authority.componentId &&
            (
                if (requireProjectionFields) {
                    relativePath == authority.relativePath &&
                        mediaType == authority.mediaType &&
                        contentSha256 == authority.contentHash
                } else {
                    (relativePath == null || relativePath == authority.relativePath) &&
                        (mediaType == null || mediaType == authority.mediaType) &&
                        (contentSha256 == null || contentSha256 == authority.contentHash)
                }
            )

    private const val RUN_METRICS_PATH = "run-metrics-v1.json"
    private const val ACCEPTANCE_ADMISSION_PATH = "acceptance-admission-v1.json"
    /** Canonical root manifest entry used to identify and verify outcome-aware archives. */
    const val OUTCOME_MANIFEST_PATH = "run-outcome-manifest-v1.json"
    private const val ACCEPTED_AUTHORITY_PATH = "authority/final-reference.json"
    private const val ACCEPTED_RUN_METRICS_PATH = "accepted/run-metrics-v1.json"
    private const val ACCEPTED_PUBLICATION_HISTORY_PATH =
        "accepted/publication-history.json"
    private const val RUN_CONFIG_ATTESTATION_PATH = "metadata/run-config.json"
    private const val BINARY_METADATA_PATH = "metadata/binary.json"
    private val SINGLETON_AUTHORITY_PATHS = mapOf(
        "authority/council-field.json" to RunManifestEntryCategory.COUNCIL_FIELD,
        "authority/worker-atlas.json" to RunManifestEntryCategory.WORKER_ATLAS,
        "authority/grader-atlas.json" to RunManifestEntryCategory.GRADER_ATLAS,
        RUN_METRICS_PATH to RunManifestEntryCategory.RUN_METRICS,
        ACCEPTANCE_ADMISSION_PATH to RunManifestEntryCategory.ACCEPTANCE_ADMISSION,
        BINARY_METADATA_PATH to RunManifestEntryCategory.BINARY_METADATA
    )
    private val RUN_CONFIG_METADATA_PATHS = setOf(
        RUN_CONFIG_ATTESTATION_PATH,
        "control/${PersistedRunStateStore.STATE_FILE_NAME}"
    )
    private val V2_PUBLICATION_AUTHORITIES = setOf(
        "authority/final-candidates.json",
        "authority/final-deliverable-references.json",
        "authority/publication-history.json"
    )
    private val CONTRIBUTION_LINEAGE_PATH =
        Regex("^authority/contribution-lineage/[^/]+\\.json$")
    private val SHA_256_REGEX = Regex("^[0-9a-f]{64}$")
}

internal fun acceptedDeliverableAuthorityHash(
    authority: AcceptedDeliverableAuthority,
): String {
    val element = Json {
        encodeDefaults = true
        explicitNulls = true
    }.encodeToJsonElement(AcceptedDeliverableAuthority.serializer(), authority)
    return DeliverableHashing.hashText(DeliverableHashing.canonicalJson(element))
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
