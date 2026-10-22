package com.achulkov.evomolior.core.deliverable

import com.achulkov.evomolior.core.atlas.AtlasHashing
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Stable SHA-256 representation required by the artifact contracts. */
private val SHA256_PATTERN = Regex("[0-9a-f]{64}")

/**
 * The executable or receiver-facing artifact required by a deliverable contract.
 *
 * @param artifactId Stable artifact identity.
 * @param relativePath Relative workspace path where the artifact is materialized.
 * @param mediaType Artifact media type.
 * @param profileId Runtime profile that defines the artifact contract.
 * @param requiredFromRound Earliest round in which the artifact must exist.
 * @param requiredForCompletedOutcome Whether a completed outcome must reference it.
 * @param sourceNodeIds Atlas nodes that authorize the artifact's source lineage.
 * @param structuralValidatorIds Validators that establish structural validity.
 * @param executionValidatorIds Validators that establish executable validity.
 * @param benchmarkIds Benchmarks applicable to the artifact.
 */
@Serializable
data class PrimaryArtifactSpec(
    val artifactId: String,
    val relativePath: String,
    val mediaType: String,
    val profileId: String,
    val requiredFromRound: Int,
    val requiredForCompletedOutcome: Boolean,
    val sourceNodeIds: Set<String>,
    val structuralValidatorIds: Set<String>,
    val executionValidatorIds: Set<String>,
    val benchmarkIds: Set<String>,
) {
    init {
        require(artifactId.isNotBlank()) { "Primary artifact ID must not be blank" }
        require(relativePath.isSafeArtifactPath()) {
            "Primary artifact path must be a non-absolute, traversal-free path"
        }
        require(mediaType.isNotBlank() && profileId.isNotBlank()) {
            "Primary artifact media type and profile must not be blank"
        }
        require(requiredFromRound >= 0) {
            "Primary artifact required round must not be negative"
        }
        requireNonBlank(sourceNodeIds, "Primary artifact source node IDs")
        requireNonBlank(
            structuralValidatorIds,
            "Primary artifact structural validator IDs",
            allowEmpty = !requiredForCompletedOutcome,
        )
        requireNonBlank(
            executionValidatorIds,
            "Primary artifact execution validator IDs",
            allowEmpty = !requiredForCompletedOutcome,
        )
        requireNonBlank(
            benchmarkIds,
            "Primary artifact benchmark IDs",
            allowEmpty = !requiredForCompletedOutcome,
        )
    }
}

/**
 * Versioned receiver blueprint whose primary artifact is independent of narrative packaging.
 *
 * @param blueprintId Stable blueprint identity.
 * @param receiverContractHash Hash of the exact receiver contract.
 * @param primaryArtifacts Required primary artifact specifications.
 * @param narrativePackageFormats Formats permitted for the companion package.
 * @param componentSpecs Narrative component requirements.
 * @param recoveryPolicy Policy for non-accepted publication.
 * @param blueprintHash Self-hash excluding this field.
 */
@Serializable
data class DeliverableBlueprintV2(
    val blueprintId: String,
    val receiverContractHash: String,
    val primaryArtifacts: List<PrimaryArtifactSpec>,
    val narrativePackageFormats: Set<DeliverableFormat>,
    val componentSpecs: List<DeliverableComponentSpec>,
    val recoveryPolicy: DeliverableRecoveryPolicy,
    val blueprintHash: String = "",
) {
    init {
        require(blueprintId.isNotBlank()) { "Artifact blueprint ID must not be blank" }
        requireSha256(receiverContractHash, "Receiver contract hash")
        require(primaryArtifacts.isNotEmpty()) {
            "Artifact blueprint requires at least one primary artifact"
        }
        require(primaryArtifacts.map(PrimaryArtifactSpec::artifactId).distinct().size ==
            primaryArtifacts.size) {
            "Primary artifact IDs must be unique"
        }
        require(primaryArtifacts.map(PrimaryArtifactSpec::relativePath).distinct().size ==
            primaryArtifacts.size) {
            "Primary artifact paths must be unique"
        }
        require(narrativePackageFormats.isNotEmpty()) {
            "Artifact blueprint requires a narrative package format"
        }
        require(componentSpecs.map(DeliverableComponentSpec::componentId).distinct().size ==
            componentSpecs.size) {
            "Artifact blueprint component IDs must be unique"
        }
    }

    /** Returns this blueprint with its deterministic self-hash attached. */
    fun withComputedHash(): DeliverableBlueprintV2 =
        copy(blueprintHash = ArtifactContractHashing.hashBlueprint(this))

    /**
     * Returns whether the self-hash commits the exact blueprint semantics.
     *
     * @return `true` when the blueprint hash is valid.
     */
    fun hasValidHash(): Boolean =
        blueprintHash.isArtifactSha256() &&
            DeliverableHashing.constantTimeEquals(
                blueprintHash,
                ArtifactContractHashing.hashBlueprint(this),
            )

    /**
     * Fails closed when this blueprint is not complete and hash-valid.
     */
    fun requireValid() {
        require(hasValidHash()) { "Artifact blueprint hash is invalid" }
    }
}

/**
 * ARC-AGI-2 notebook profile and its host-owned receiver contract.
 *
 * @param profileId Stable notebook profile identity.
 * @param primaryArtifact Primary artifact contract for the profile.
 * @param receiverSignals Host-owned receiver signals required by the profile.
 * @param notebookCells Deterministic notebook cell specifications.
 * @param profileHash Self-hash excluding this field.
 */
@Serializable
data class ArcNotebookProfile(
    val profileId: String,
    val primaryArtifact: PrimaryArtifactSpec,
    val receiverSignals: List<ReceiverSignalSpec>,
    val notebookCells: List<NotebookCellSpec>,
    val profileHash: String = "",
) {
    init {
        require(profileId.isNotBlank()) { "Notebook profile ID must not be blank" }
        require(primaryArtifact.profileId == profileId) {
            "Primary artifact profile must match notebook profile"
        }
        require(receiverSignals.isNotEmpty()) {
            "Notebook profile requires receiver signals"
        }
        require(receiverSignals.map(ReceiverSignalSpec::signalId).distinct().size ==
            receiverSignals.size) {
            "Notebook receiver signal IDs must be unique"
        }
        require(notebookCells.isNotEmpty()) {
            "Notebook profile requires cell specifications"
        }
        require(notebookCells.map(NotebookCellSpec::cellHandle).distinct().size ==
            notebookCells.size) {
            "Notebook cell handles must be unique"
        }
        require(notebookCells.map(NotebookCellSpec::order).distinct().size ==
            notebookCells.size) {
            "Notebook cell orders must be unique"
        }
    }

    /** Returns this profile with its deterministic self-hash attached. */
    fun withComputedHash(): ArcNotebookProfile =
        copy(profileHash = ArtifactContractHashing.hashProfile(this))

    /**
     * Returns whether the profile self-hash is valid.
     *
     * @return `true` when the profile hash is valid.
     */
    fun hasValidHash(): Boolean =
        profileHash.isArtifactSha256() &&
            DeliverableHashing.constantTimeEquals(
                profileHash,
                ArtifactContractHashing.hashProfile(this),
            )

    /** Fails closed when the profile self-hash is invalid. */
    fun requireValid() {
        require(hasValidHash()) { "Notebook profile hash is invalid" }
    }

    /** The primary artifact specification under the conventional profile property name. */
    val primaryArtifactSpec: PrimaryArtifactSpec
        get() = primaryArtifact

    companion object {
        /** Stable ARC-AGI-2 notebook profile identifier. */
        const val PROFILE_ID: String = "arc-agi-2-notebook-v2"

        /**
         * Returns the deterministic built-in ARC notebook profile.
         *
         * @return Hashed ARC notebook profile.
         */
        fun v2(): ArcNotebookProfile = ArcNotebookProfile(
            profileId = PROFILE_ID,
            primaryArtifact = PrimaryArtifactSpec(
                artifactId = "arc-submission-notebook",
                relativePath = "primary/submission.ipynb",
                mediaType = "application/x-ipynb+json",
                profileId = PROFILE_ID,
                requiredFromRound = 0,
                requiredForCompletedOutcome = true,
                sourceNodeIds = setOf(
                    "notebook-scaffold",
                    "solver-portfolio",
                    "loo-validation",
                    "receiver-generation",
                ),
                structuralValidatorIds = setOf("nbformat-4.5", "arc-required-symbols"),
                executionValidatorIds = setOf(
                    "compile-each-cell",
                    "self-tests",
                    "challenge-execution",
                ),
                benchmarkIds = setOf("visible-loo-358"),
            ),
            receiverSignals = listOf(
                ReceiverSignalSpec.PythonSymbol(
                    signalId = "challenge-data",
                    symbolName = "challenge_data",
                    expectedKind = PythonSymbolKind.VALUE,
                ),
                ReceiverSignalSpec.PythonSymbol(
                    signalId = "prior-data",
                    symbolName = "prior_data",
                    expectedKind = PythonSymbolKind.VALUE,
                ),
                ReceiverSignalSpec.PythonSymbol(
                    signalId = "sample-submission",
                    symbolName = "sample_submission",
                    expectedKind = PythonSymbolKind.VALUE,
                ),
                ReceiverSignalSpec.NotebookMetadataPointer(
                    signalId = "counterfactual-baseline",
                    jsonPointer = "/metadata/counterfactual/baseline",
                ),
                ReceiverSignalSpec.RuntimePredicate(
                    signalId = "one-result-row-per-test-input",
                    validatorId = "result-row-cardinality",
                ),
            ),
            notebookCells = listOf(
                NotebookCellSpec(
                    cellHandle = "imports",
                    kind = NotebookCellKind.CODE,
                    sourceFile = null,
                    sourceSection = null,
                    requiredSymbols = emptySet(),
                    order = 0,
                ),
                NotebookCellSpec(
                    cellHandle = "solver-definitions",
                    kind = NotebookCellKind.CODE,
                    sourceFile = "src/solver_families.py",
                    sourceSection = "solver-families",
                    requiredSymbols = setOf("build_task_solvers"),
                    order = 1,
                ),
                NotebookCellSpec(
                    cellHandle = "receiver",
                    kind = NotebookCellKind.CODE,
                    sourceFile = "src/receiver.py",
                    sourceSection = "receiver",
                    requiredSymbols = setOf(
                        "challenge_data",
                        "prior_data",
                        "sample_submission",
                    ),
                    order = 2,
                ),
            ),
        ).withComputedHash()

        /** Returns the built-in profile under a discoverable constant-like API. */
        val DEFAULT: ArcNotebookProfile
            get() = v2()
    }
}

/** Python symbol categories that may be required by a notebook profile. */
@Serializable
enum class PythonSymbolKind {
    VALUE,
    VARIABLE,
    CONSTANT,
    FUNCTION,
    CALLABLE,
    CLASS,
    MODULE,
}

/** Notebook cell kinds emitted by the deterministic scaffold compiler. */
@Serializable
enum class NotebookCellKind {
    CODE,
    MARKDOWN,
    RAW,
}

/**
 * A receiver-owned signal that the writer must satisfy.
 *
 * @property signalId Stable signal identity.
 */
@Serializable
sealed interface ReceiverSignalSpec {
    /** Stable signal identity. */
    val signalId: String

    /**
     * A required Python symbol.
     *
     * @param signalId Stable signal identity.
     * @param symbolName Required Python symbol.
     * @param expectedKind Expected symbol category.
     */
    @Serializable
    @SerialName("python-symbol")
    data class PythonSymbol(
        override val signalId: String,
        val symbolName: String,
        val expectedKind: PythonSymbolKind,
    ) : ReceiverSignalSpec {
        init {
            require(signalId.isNotBlank() && symbolName.isNotBlank()) {
                "Python symbol signal fields must not be blank"
            }
        }
    }

    /**
     * A JSON Pointer into immutable notebook metadata or an audit object.
     *
     * @param signalId Stable signal identity.
     * @param jsonPointer RFC 6901 JSON Pointer.
     */
    @Serializable
    @SerialName("notebook-metadata-pointer")
    data class NotebookMetadataPointer(
        override val signalId: String,
        val jsonPointer: String,
    ) : ReceiverSignalSpec {
        init {
            require(signalId.isNotBlank() && jsonPointer.startsWith("/")) {
                "Notebook metadata signals require a nonblank JSON Pointer"
            }
        }
    }

    /**
     * A host validator predicate that must hold at runtime.
     *
     * @param signalId Stable signal identity.
     * @param validatorId Host-owned validator identity.
     */
    @Serializable
    @SerialName("runtime-predicate")
    data class RuntimePredicate(
        override val signalId: String,
        val validatorId: String,
    ) : ReceiverSignalSpec {
        init {
            require(signalId.isNotBlank() && validatorId.isNotBlank()) {
                "Runtime predicate signal fields must not be blank"
            }
        }
    }
}

/**
 * Deterministic notebook cell source mapping.
 *
 * @param cellHandle Stable host-assigned cell handle.
 * @param kind Cell kind.
 * @param sourceFile Optional source module contributing the cell.
 * @param sourceSection Optional source section contributing the cell.
 * @param requiredSymbols Symbols that must be present after compilation.
 * @param order Deterministic cell order, starting at zero.
 */
@Serializable
data class NotebookCellSpec(
    val cellHandle: String,
    val kind: NotebookCellKind,
    val sourceFile: String?,
    val sourceSection: String?,
    val requiredSymbols: Set<String>,
    val order: Int,
) {
    init {
        require(cellHandle.isNotBlank()) { "Notebook cell handle must not be blank" }
        require(sourceFile == null || sourceFile.isSafeArtifactPath()) {
            "Notebook source file must be a safe relative path"
        }
        require(sourceSection == null || sourceSection.isNotBlank()) {
            "Notebook source section must not be blank when present"
        }
        requireNonBlank(requiredSymbols, "Notebook required symbols", allowEmpty = true)
        require(order >= 0) { "Notebook cell order must not be negative" }
    }
}

/**
 * Immutable reference to a materialized artifact.
 *
 * @param artifactId Stable artifact identity.
 * @param relativePath Relative archive or workspace path.
 * @param mediaType Artifact media type.
 * @param artifactHash SHA-256 of the exact artifact bytes.
 */
@Serializable
data class ArtifactRef(
    val artifactId: String,
    val relativePath: String,
    val mediaType: String,
    val artifactHash: String,
) {
    init {
        require(artifactId.isNotBlank() && mediaType.isNotBlank()) {
            "Artifact reference identity must not be blank"
        }
        require(relativePath.isSafeArtifactPath()) {
            "Artifact reference path must be safe and relative"
        }
        requireSha256(artifactHash, "Artifact reference hash")
    }

    /** Alias used by callers that describe the committed bytes as content. */
    val contentHash: String
        get() = artifactHash
}

/**
 * Authority used by the main writer to select a candidate source.
 *
 * @param primaryGroupId Group supplying the primary source, when one exists.
 * @param authorityClass Strength of source authority.
 * @param sourceSnapshotIds Snapshot lineage used by the writer.
 * @param donorArtifactRefs Public artifact IDs or paths donated to the writer.
 * @param failedGateDiagnostics Known failed gates preserved with provisional authority.
 * @param writerIdentity Accepted writer identity, when upgraded.
 * @param finalArtifactHash Final artifact hash committed by the writer, when upgraded.
 * @param requiredValidatorHashes Required validator report hashes, when upgraded.
 * @param finalBenchmarkHash Final benchmark report hash, when upgraded.
 * @param sourceSetHash Self-hash excluding this field.
 */
@Serializable
data class WriterSourceAuthority(
    val primaryGroupId: String?,
    val authorityClass: WriterSourceAuthorityClass,
    val sourceSnapshotIds: List<String>,
    val donorArtifactRefs: List<String>,
    val failedGateDiagnostics: List<String>,
    val sourceSetHash: String = "",
    val writerIdentity: String? = null,
    val finalArtifactHash: String? = null,
    val requiredValidatorHashes: List<String> = emptyList(),
    val finalBenchmarkHash: String? = null,
) {
    init {
        require(primaryGroupId == null || primaryGroupId.isNotBlank()) {
            "Writer source group ID must not be blank when present"
        }
        requireNonBlank(sourceSnapshotIds, "Writer source snapshot IDs", allowEmpty = true)
        requireNonBlank(donorArtifactRefs, "Writer donor artifact references", allowEmpty = true)
        requireNonBlank(failedGateDiagnostics, "Writer failed gate diagnostics", allowEmpty = true)
        require(sourceSnapshotIds.distinct().size == sourceSnapshotIds.size) {
            "Writer source snapshot IDs must be unique"
        }
        require(donorArtifactRefs.distinct().size == donorArtifactRefs.size) {
            "Writer donor artifact references must be unique"
        }
        require(failedGateDiagnostics.distinct().size == failedGateDiagnostics.size) {
            "Writer failed gate diagnostics must be unique"
        }
        require(writerIdentity == null || writerIdentity.isNotBlank()) {
            "Writer identity must not be blank when present"
        }
        finalArtifactHash?.let { requireSha256(it, "Writer final artifact hash") }
        finalBenchmarkHash?.let { requireSha256(it, "Writer final benchmark hash") }
        require(requiredValidatorHashes.none(String::isBlank)) {
            "Writer validator hashes must not be blank"
        }
        require(requiredValidatorHashes.all(String::isArtifactSha256)) {
            "Writer validator hashes must be lowercase SHA-256 hashes"
        }
        require(requiredValidatorHashes == requiredValidatorHashes.distinct().sorted()) {
            "Writer validator hashes must be sorted and unique"
        }
    }

    /** Returns this authority with its deterministic self-hash attached. */
    fun withComputedHash(): WriterSourceAuthority =
        copy(sourceSetHash = ArtifactContractHashing.hashWriterSourceAuthority(this))

    /**
     * Returns whether the source authority self-hash is valid.
     *
     * @return `true` when the source authority hash is valid.
     */
    fun hasValidHash(): Boolean =
        sourceSetHash.isArtifactSha256() &&
            DeliverableHashing.constantTimeEquals(
                sourceSetHash,
                ArtifactContractHashing.hashWriterSourceAuthority(this),
            )

    /** Fails closed when the source authority self-hash is invalid. */
    fun requireValid() {
        require(hasValidHash()) { "Writer source authority hash is invalid" }
    }

    /** Whether this authority is eligible to establish accepted writer authority. */
    val isAcceptedWriterAuthority: Boolean
        get() =
            authorityClass == WriterSourceAuthorityClass.ACCEPTED_WINNER &&
                primaryGroupId != null &&
                sourceSnapshotIds.isNotEmpty() &&
                failedGateDiagnostics.isEmpty() &&
                writerIdentity != null &&
                finalArtifactHash != null &&
                requiredValidatorHashes.isNotEmpty() &&
                finalBenchmarkHash != null
}

/** Closed source-authority ladder used by the main writer. */
@Serializable
enum class WriterSourceAuthorityClass {
    ACCEPTED_WINNER,
    FINAL_COMPARATIVE_LEADER,
    CUMULATIVE_COMPARATIVE_LEADER,
    BENCHMARKED_ARTIFACT,
    BEST_VALID_ARTIFACT,
    MULTI_SOURCE_PROVISIONAL,
    SCAFFOLD_ONLY,
}

/**
 * Durable mutable workspace state for one competing group.
 *
 * @param runId Owning run.
 * @param groupId Owning group.
 * @param artifactSpecHash Primary artifact contract hash.
 * @param baseSnapshotHash Snapshot from which the workspace was derived.
 * @param sourceFileHashes Exact source file hashes keyed by relative path.
 * @param compiledArtifactHash Latest compiled primary artifact hash.
 * @param latestValidationReportHash Latest structural/execution report hash.
 * @param latestBenchmarkReportHash Latest benchmark report hash.
 * @param bestSnapshotId Best historical snapshot selected so far.
 * @param stateVersion Monotonic workspace state version.
 * @param stateHash Self-hash excluding this field.
 */
@Serializable
data class ArtifactWorkspaceState(
    val runId: String,
    val groupId: String,
    val artifactSpecHash: String,
    val baseSnapshotHash: String,
    val sourceFileHashes: Map<String, String>,
    val compiledArtifactHash: String?,
    val latestValidationReportHash: String?,
    val latestBenchmarkReportHash: String?,
    val bestSnapshotId: String?,
    val stateVersion: Long,
    val stateHash: String = "",
) {
    init {
        require(runId.isNotBlank() && groupId.isNotBlank()) {
            "Artifact workspace identity must not be blank"
        }
        requireSha256(artifactSpecHash, "Artifact specification hash")
        requireSha256(baseSnapshotHash, "Workspace base snapshot hash")
        require(sourceFileHashes.keys.none(String::isBlank)) {
            "Workspace source file paths must not be blank"
        }
        require(sourceFileHashes.keys.all(String::isSafeArtifactPath)) {
            "Workspace source file paths must be safe relative paths"
        }
        sourceFileHashes.values.forEach { requireSha256(it, "Workspace source file hash") }
        compiledArtifactHash?.let { requireSha256(it, "Compiled artifact hash") }
        latestValidationReportHash?.let { requireSha256(it, "Validation report hash") }
        latestBenchmarkReportHash?.let { requireSha256(it, "Benchmark report hash") }
        require(bestSnapshotId == null || bestSnapshotId.isNotBlank()) {
            "Best snapshot ID must not be blank when present"
        }
        require(stateVersion >= 0L) { "Workspace state version must not be negative" }
    }

    /** Returns this workspace state with its deterministic self-hash attached. */
    fun withComputedHash(): ArtifactWorkspaceState =
        copy(stateHash = ArtifactContractHashing.hashWorkspaceState(this))

    /**
     * Returns whether the workspace state self-hash is valid.
     *
     * @return `true` when the state hash is valid.
     */
    fun hasValidHash(): Boolean =
        stateHash.isArtifactSha256() &&
            DeliverableHashing.constantTimeEquals(
                stateHash,
                ArtifactContractHashing.hashWorkspaceState(this),
            )

    /** Fails closed when the workspace state self-hash is invalid. */
    fun requireValid() {
        require(hasValidHash()) { "Artifact workspace state hash is invalid" }
    }
}

/**
 * Historical artifact candidate, including best-so-far benchmark facts.
 *
 * @param snapshotId Stable snapshot identity.
 * @param runId Owning run.
 * @param groupId Owning group.
 * @param round Round that produced the snapshot.
 * @param artifactHash Primary artifact byte hash.
 * @param sourceTreeHash Source tree hash.
 * @param structureReportHash Structural validation report hash.
 * @param executionReportHash Execution validation report hash, when reached.
 * @param benchmarkReportHash Benchmark report hash, when reached.
 * @param top1 Best-so-far top-1 benchmark count.
 * @param anyTwo Best-so-far any-two benchmark count.
 * @param runtimeMs Best-so-far runtime measurement.
 * @param authorityClass Snapshot source authority.
 * @param sourceNodeIds Source lineage nodes.
 * @param patchLineageIds Applied patch lineage IDs.
 * @param snapshotHash Self-hash excluding this field.
 */
@Serializable
data class ArtifactSnapshot(
    val snapshotId: String,
    val runId: String,
    val groupId: String,
    val round: Int,
    val artifactHash: String,
    val sourceTreeHash: String,
    val structureReportHash: String,
    val executionReportHash: String?,
    val benchmarkReportHash: String?,
    val top1: Int?,
    val anyTwo: Int?,
    val runtimeMs: Long?,
    val authorityClass: ComponentAuthorityClass,
    val sourceNodeIds: Set<String>,
    val patchLineageIds: Set<String>,
    val artifactHealth: GroupArtifactHealth = GroupArtifactHealth.SCAFFOLDED,
    val backboneHash: String? = null,
    val branchHash: String? = null,
    val benchmarkRowCount: Int? = null,
    val benchmarkDefinitionHash: String? = null,
    val benchmarkReportBytesHash: String? = null,
    val benchmarkRepeatedBytesHash: String? = null,
    val benchmarkCsvBytesHash: String? = null,
    val benchmarkCandidateFamilyIds: List<String> = emptyList(),
    val benchmarkRepeatable: Boolean? = null,
    val rescueRecord: ArtifactRescueRecord? = null,
    val snapshotHash: String = "",
) {
    init {
        require(snapshotId.isNotBlank() && runId.isNotBlank() && groupId.isNotBlank()) {
            "Artifact snapshot identity must not be blank"
        }
        require(round >= 0) { "Artifact snapshot round must not be negative" }
        requireSha256(artifactHash, "Snapshot artifact hash")
        requireSha256(sourceTreeHash, "Snapshot source tree hash")
        requireSha256(structureReportHash, "Snapshot structure report hash")
        executionReportHash?.let { requireSha256(it, "Snapshot execution report hash") }
        benchmarkReportHash?.let { requireSha256(it, "Snapshot benchmark report hash") }
        require(top1 == null || top1 >= 0) { "Snapshot top1 must not be negative" }
        require(anyTwo == null || anyTwo >= 0) { "Snapshot anyTwo must not be negative" }
        require(top1 == null || anyTwo == null || anyTwo >= top1) {
            "Snapshot anyTwo score cannot be below top1"
        }
        require(runtimeMs == null || runtimeMs >= 0L) {
            "Snapshot runtime must not be negative"
        }
        requireNonBlank(sourceNodeIds, "Snapshot source node IDs")
        requireNonBlank(patchLineageIds, "Snapshot patch lineage IDs", allowEmpty = true)
        backboneHash?.let { requireSha256(it, "Snapshot backbone hash") }
        branchHash?.let { requireSha256(it, "Snapshot branch hash") }
        benchmarkDefinitionHash?.let { requireSha256(it, "Snapshot benchmark definition hash") }
        benchmarkReportBytesHash?.let { requireSha256(it, "Snapshot benchmark report bytes hash") }
        benchmarkRepeatedBytesHash?.let {
            requireSha256(it, "Snapshot repeated benchmark report bytes hash")
        }
        benchmarkCsvBytesHash?.let {
            requireSha256(it, "Snapshot benchmark CSV bytes hash")
        }
        require(benchmarkCandidateFamilyIds.none(String::isBlank)) {
            "Snapshot benchmark candidate families must not be blank"
        }
        require(
            benchmarkCandidateFamilyIds == benchmarkCandidateFamilyIds.distinct().sorted()
        ) {
            "Snapshot benchmark candidate families must be sorted and unique"
        }
        if (benchmarkRepeatable == true) {
            require(benchmarkReportBytesHash != null && benchmarkRepeatedBytesHash != null) {
                "Repeatable benchmark snapshots require both report byte hashes"
            }
            require(benchmarkReportBytesHash == benchmarkRepeatedBytesHash) {
                "Repeatable benchmark snapshot byte hashes must match"
            }
        }
        require(benchmarkRowCount == null || benchmarkRowCount >= 0) {
            "Snapshot benchmark row count must not be negative"
        }
        rescueRecord?.let {
            require(it.runId == runId && it.groupId == groupId && it.round == round) {
                "Artifact rescue record must match its snapshot identity"
            }
            require(it.hasValidHash()) { "Artifact rescue record hash is invalid" }
        }
        require(snapshotId.isNotBlank())
    }

    /** Returns this snapshot with its deterministic self-hash attached. */
    fun withComputedHash(): ArtifactSnapshot =
        copy(snapshotHash = ArtifactContractHashing.hashSnapshot(this))

    /**
     * Returns whether the snapshot self-hash is valid.
     *
     * @return `true` when the snapshot hash is valid.
     */
    fun hasValidHash(): Boolean =
        snapshotHash.isArtifactSha256() &&
            DeliverableHashing.constantTimeEquals(
                snapshotHash,
                ArtifactContractHashing.hashSnapshot(this),
            )

    /** Fails closed when the snapshot is not fully hash-valid. */
    fun requireValid() {
        require(hasValidHash()) { "Artifact snapshot hash is invalid" }
    }
}

/** Runtime-owned health state for one group's primary artifact. */
@Serializable
enum class GroupArtifactHealth {
    NO_SCAFFOLD,
    SCAFFOLDED,
    STRUCTURALLY_VALID,
    EXECUTABLE,
    BENCHMARKED,
    IMPROVED,
}

/**
 * Explicit rescue evidence retained when a group cannot produce a complete candidate.
 *
 * @param rescueId Stable rescue identity.
 * @param runId Owning run.
 * @param groupId Owning group.
 * @param round Round that requested rescue.
 * @param reason Public bounded rescue reason.
 * @param retainedSnapshotId Best snapshot retained during rescue, when present.
 * @param attemptedSourceHashes Source trees considered during rescue.
 * @param recordHash Self-hash excluding this field.
 */
@Serializable
data class ArtifactRescueRecord(
    val rescueId: String,
    val runId: String,
    val groupId: String,
    val round: Int,
    val reason: String,
    val retainedSnapshotId: String? = null,
    val attemptedSourceHashes: List<String> = emptyList(),
    val recordHash: String = "",
) {
    init {
        require(rescueId.isNotBlank() && runId.isNotBlank() && groupId.isNotBlank()) {
            "Artifact rescue identity must not be blank"
        }
        require(round >= 0) { "Artifact rescue round must not be negative" }
        require(reason.isNotBlank()) { "Artifact rescue reason must not be blank" }
        require(retainedSnapshotId == null || retainedSnapshotId.isNotBlank()) {
            "Retained rescue snapshot ID must not be blank when present"
        }
        require(attemptedSourceHashes.all(String::isArtifactSha256)) {
            "Artifact rescue source hashes must be lowercase SHA-256 hashes"
        }
        require(attemptedSourceHashes == attemptedSourceHashes.distinct().sorted()) {
            "Artifact rescue source hashes must be sorted and unique"
        }
    }

    /** Returns this record with its deterministic commitment attached. */
    fun withComputedHash(): ArtifactRescueRecord =
        copy(recordHash = ArtifactContractHashing.hashArtifactRescueRecord(this))

    /** Verifies the exact rescue commitment. */
    fun hasValidHash(): Boolean =
        recordHash.isArtifactSha256() &&
            DeliverableHashing.constantTimeEquals(
                recordHash,
                ArtifactContractHashing.hashArtifactRescueRecord(this),
            )
}

/** Typed failure categories that can be repaired without regenerating the whole artifact. */
@Serializable
enum class ArtifactViolationCode {
    INVALID_NOTEBOOK_STRUCTURE,
    MISSING_REQUIRED_SYMBOL,
    MISSING_RECEIVER_SIGNAL,
    INVALID_NOTEBOOK_METADATA,
    CELL_COMPILATION_FAILED,
    EXECUTION_FAILED,
    VALIDATOR_FAILED,
    BENCHMARK_REGRESSION,
    HASH_MISMATCH,
    SOURCE_LINEAGE_MISSING,
    UNWRITABLE_ARTIFACT,
    MISSING_SYMBOL,
    MISSING_SIGNAL,
}

/**
 * A bounded, typed artifact contract failure.
 *
 * @param code Closed repair category.
 * @param cellHandle Affected notebook cell, when known.
 * @param sourceFile Affected source file, when known.
 * @param symbol Affected symbol, when known.
 * @param publicMessage Receiver-safe failure description.
 * @param safeRepairHint Narrow repair guidance.
 */
@Serializable
data class ArtifactContractViolation(
    val code: ArtifactViolationCode,
    val cellHandle: String?,
    val sourceFile: String?,
    val symbol: String?,
    val publicMessage: String,
    val safeRepairHint: String,
) {
    init {
        require(cellHandle == null || cellHandle.isNotBlank()) {
            "Violation cell handle must not be blank when present"
        }
        require(sourceFile == null || sourceFile.isSafeArtifactPath()) {
            "Violation source file must be a safe relative path"
        }
        require(symbol == null || symbol.isNotBlank()) {
            "Violation symbol must not be blank when present"
        }
        require(publicMessage.isNotBlank() && safeRepairHint.isNotBlank()) {
            "Violation message and repair hint must not be blank"
        }
    }
}

/**
 * Benchmark delta that should be considered by a bounded repair.
 *
 * @param benchmarkId Benchmark identity.
 * @param previousTop1 Previous top-1 count.
 * @param currentTop1 Current top-1 count.
 * @param previousAnyTwo Previous any-two count.
 * @param currentAnyTwo Current any-two count.
 * @param reason Public regression explanation.
 */
@Serializable
data class BenchmarkRegression(
    val benchmarkId: String,
    val previousTop1: Int?,
    val currentTop1: Int?,
    val previousAnyTwo: Int?,
    val currentAnyTwo: Int?,
    val reason: String,
) {
    init {
        require(benchmarkId.isNotBlank() && reason.isNotBlank()) {
            "Benchmark regression identity and reason must not be blank"
        }
        require(previousTop1 == null || previousTop1 >= 0)
        require(currentTop1 == null || currentTop1 >= 0)
        require(previousAnyTwo == null || previousAnyTwo >= 0)
        require(currentAnyTwo == null || currentAnyTwo >= 0)
    }
}

/**
 * Narrow repair request for the current artifact snapshot.
 *
 * @param artifactSnapshotHash Snapshot being repaired.
 * @param violations Typed failures supplied to the writer.
 * @param benchmarkRegression Optional benchmark regression.
 * @param immutableContractRefs Contract hashes or IDs that the writer must not alter.
 * @param repairAttempt One-based repair attempt number.
 */
@Serializable
data class WriterRepairRequest(
    val artifactSnapshotHash: String,
    val violations: List<ArtifactContractViolation>,
    val benchmarkRegression: BenchmarkRegression?,
    val immutableContractRefs: List<String>,
    val repairAttempt: Int,
) {
    init {
        requireSha256(artifactSnapshotHash, "Repair snapshot hash")
        require(violations.isNotEmpty()) { "Writer repair requires at least one violation" }
        require(immutableContractRefs.none(String::isBlank)) {
            "Immutable contract references must not be blank"
        }
        require(
            immutableContractRefs == immutableContractRefs.distinct().sorted()
        ) { "Immutable contract references must be sorted and unique" }
        require(repairAttempt > 0) { "Writer repair attempt must be one-based" }
    }
}

/** Process-level completion axis, independent from acceptance authority. */
@Serializable
enum class ExecutionCompletionStatus {
    COMPLETED,
    FAILED_PRE_ADMISSION,
    FAILED_CORRUPT_STATE,
    FAILED_UNWRITABLE_OUTPUT,
}

/** Epistemic/receiver acceptance axis, independent from process completion. */
@Serializable
enum class AcceptanceStatus {
    ACCEPTED,
    PROVISIONAL,
    REJECTED_STRICT_GATES,
    NOT_EVALUATED,
}

/** Public validation projection for the primary artifact in a terminal outcome. */
@Serializable
enum class PrimaryArtifactValidationStatus {
    PASS,
    FAIL,
    UNAVAILABLE,
    NOT_EVALUATED,
}

/**
 * Two-axis terminal run outcome that prevents strict-gate failure from masquerading as execution
 * failure.
 *
 * @param executionStatus Process completion status.
 * @param acceptanceStatus Strict acceptance status.
 * @param primaryArtifactRef Required artifact for completed outcomes.
 * @param decisionPackageRef Optional narrative decision package.
 * @param acceptedWinnerId Accepted winner identity, only for accepted outcomes.
 * @param writerSourceAuthority Writer source authority, upgraded only after validation.
 * @param failedGateIds Failed gate IDs retained for non-accepted outcomes.
 * @param primaryArtifactValidationStatus Public validation status for the primary artifact.
 * @param outcomeHash Self-hash excluding this field.
 */
@Serializable
data class RunTerminalOutcomeV2(
    val executionStatus: ExecutionCompletionStatus,
    val acceptanceStatus: AcceptanceStatus,
    val primaryArtifactRef: ArtifactRef?,
    val decisionPackageRef: ArtifactRef?,
    val acceptedWinnerId: String?,
    val writerSourceAuthority: WriterSourceAuthority?,
    val failedGateIds: List<String>,
    val primaryArtifactValidationStatus: PrimaryArtifactValidationStatus =
        PrimaryArtifactValidationStatus.NOT_EVALUATED,
    val outcomeHash: String = "",
) {
    init {
        require(acceptedWinnerId == null || acceptedWinnerId.isNotBlank()) {
            "Accepted winner ID must not be blank when present"
        }
        require(failedGateIds.none(String::isBlank)) {
            "Failed gate IDs must not be blank"
        }
        require(failedGateIds == failedGateIds.distinct().sorted()) {
            "Failed gate IDs must be sorted and unique"
        }
        require(
            acceptanceStatus == AcceptanceStatus.ACCEPTED || acceptedWinnerId == null
        ) { "Only accepted outcomes may carry an accepted winner ID" }
        if (executionStatus == ExecutionCompletionStatus.COMPLETED) {
            require(primaryArtifactRef != null) {
                "Completed outcomes require a primary artifact reference"
            }
        }
        if (acceptanceStatus == AcceptanceStatus.ACCEPTED) {
            require(executionStatus == ExecutionCompletionStatus.COMPLETED) {
                "Accepted outcomes require completed execution"
            }
            require(acceptedWinnerId != null) {
                "Accepted outcomes require an accepted winner"
            }
            require(writerSourceAuthority?.isAcceptedWriterAuthority == true) {
                "Accepted outcomes require accepted writer authority"
            }
            val authority = requireNotNull(writerSourceAuthority)
            require(authority.hasValidHash()) {
                "Accepted outcomes require a valid writer authority hash"
            }
            require(authority.finalArtifactHash == primaryArtifactRef?.artifactHash) {
                "Accepted writer authority must bind the primary artifact"
            }
            require(failedGateIds.isEmpty()) {
                "Accepted outcomes cannot contain failed acceptance gates"
            }
        } else {
            require(
                writerSourceAuthority?.authorityClass != WriterSourceAuthorityClass.ACCEPTED_WINNER
            ) {
                "Non-accepted outcomes cannot carry accepted writer authority"
            }
        }
    }

    /** Returns this outcome with its deterministic self-hash attached. */
    fun withComputedHash(): RunTerminalOutcomeV2 =
        copy(outcomeHash = ArtifactContractHashing.hashTerminalOutcome(this))

    /**
     * Returns whether the terminal outcome self-hash is valid.
     *
     * @return `true` when the outcome hash is valid.
     */
    fun hasValidHash(): Boolean =
        outcomeHash.isArtifactSha256() &&
            DeliverableHashing.constantTimeEquals(
                outcomeHash,
                ArtifactContractHashing.hashTerminalOutcome(this),
            )

    /** Fails closed when outcome authority or its self-hash is invalid. */
    fun requireValid() {
        require(hasValidHash()) { "Terminal outcome hash is invalid" }
    }
}

/** Shared fail-closed validation entry points for artifact V2 authorities. */
object ArtifactContractV2Validator {
    /**
     * Validates a blueprint and its self-hash.
     *
     * @param blueprint Blueprint to validate.
     */
    fun requireValidBlueprint(blueprint: DeliverableBlueprintV2) {
        blueprint.requireValid()
    }

    /**
     * Validates an ARC or custom notebook profile and its self-hash.
     *
     * @param profile Profile to validate.
     */
    fun requireValidProfile(profile: ArcNotebookProfile) {
        profile.requireValid()
    }

    /**
     * Validates a writer source authority and its self-hash.
     *
     * @param authority Source authority to validate.
     */
    fun requireValidWriterSourceAuthority(authority: WriterSourceAuthority) {
        authority.requireValid()
    }

    /**
     * Validates durable workspace state and its self-hash.
     *
     * @param state Workspace state to validate.
     */
    fun requireValidWorkspaceState(state: ArtifactWorkspaceState) {
        state.requireValid()
    }

    /**
     * Validates an artifact snapshot and its self-hash.
     *
     * @param snapshot Snapshot to validate.
     */
    fun requireValidSnapshot(snapshot: ArtifactSnapshot) {
        snapshot.requireValid()
    }

    /**
     * Validates a two-axis terminal outcome and its self-hash.
     *
     * @param outcome Terminal outcome to validate.
     */
    fun requireValidTerminalOutcome(outcome: RunTerminalOutcomeV2) {
        outcome.requireValid()
    }
}

/** Deterministic hashing for the self-hashing V2 contracts. */
private object ArtifactContractHashing {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        classDiscriminator = "type"
    }

    fun hashBlueprint(value: DeliverableBlueprintV2): String =
        hashWithoutField(DeliverableBlueprintV2.serializer(), value, "blueprintHash")

    fun hashProfile(value: ArcNotebookProfile): String =
        hashWithoutField(ArcNotebookProfile.serializer(), value, "profileHash")

    fun hashWriterSourceAuthority(value: WriterSourceAuthority): String =
        hashWithoutField(WriterSourceAuthority.serializer(), value, "sourceSetHash")

    fun hashWorkspaceState(value: ArtifactWorkspaceState): String =
        hashWithoutField(ArtifactWorkspaceState.serializer(), value, "stateHash")

    fun hashSnapshot(value: ArtifactSnapshot): String =
        hashWithoutField(ArtifactSnapshot.serializer(), value, "snapshotHash")

    fun hashArtifactRescueRecord(value: ArtifactRescueRecord): String =
        hashWithoutField(ArtifactRescueRecord.serializer(), value, "recordHash")

    fun hashTerminalOutcome(value: RunTerminalOutcomeV2): String =
        hashWithoutField(RunTerminalOutcomeV2.serializer(), value, "outcomeHash")

    private fun <T> hashWithoutField(
        serializer: KSerializer<T>,
        value: T,
        fieldName: String,
    ): String {
        val element = json.encodeToJsonElement(serializer, value)
        val withoutHash = JsonObject(element.jsonObject.filterKeys { it != fieldName })
        return AtlasHashing.hashText(canonicalJson(withoutHash))
    }

    private fun canonicalJson(element: JsonElement, fieldName: String? = null): String =
        when (element) {
            is JsonObject -> element.entries
                .sortedBy { it.key }
                .joinToString(prefix = "{", postfix = "}") { (key, value) ->
                    Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(key)) +
                        ":" + canonicalJson(value, key)
                }
            is JsonArray -> {
                val values = element.map { canonicalJson(it) }
                val ordered = if (fieldName in SET_FIELDS) values.sorted() else values
                ordered.joinToString(prefix = "[", postfix = "]")
            }
            is JsonPrimitive -> element.toString()
        }

    private val SET_FIELDS = setOf(
        "benchmarkIds",
        "executionValidatorIds",
        "immutableContractRefs",
        "narrativePackageFormats",
        "patchLineageIds",
        "requiredSymbols",
        "sourceNodeIds",
        "structuralValidatorIds",
    )
}

private fun String.isArtifactSha256(): Boolean = SHA256_PATTERN.matches(this)

private fun requireSha256(value: String, label: String) {
    require(value.isArtifactSha256()) { "$label must be a lowercase SHA-256 hash" }
}

private fun requireNonBlank(
    values: Collection<String>,
    label: String,
    allowEmpty: Boolean = false,
) {
    require(allowEmpty || values.isNotEmpty()) { "$label must not be empty" }
    require(values.none(String::isBlank)) { "$label must not contain blank values" }
}

private fun String.isSafeArtifactPath(): Boolean =
    isNotBlank() &&
        !startsWith("/") &&
        !contains('\\') &&
        !split('/').any { it.isBlank() || it == "." || it == ".." }
