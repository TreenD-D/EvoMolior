package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.deliverable.ArcNotebookProfile
import com.achulkov.evomolior.core.deliverable.ArtifactSnapshot
import com.achulkov.evomolior.core.deliverable.ArtifactRescueRecord
import com.achulkov.evomolior.core.deliverable.ArtifactWorkspaceState
import com.achulkov.evomolior.core.deliverable.ComponentAuthorityClass
import com.achulkov.evomolior.core.deliverable.DeliverableBlueprintV2
import com.achulkov.evomolior.core.deliverable.DeliverableHashing
import com.achulkov.evomolior.core.deliverable.GroupArtifactHealth
import com.achulkov.evomolior.core.deliverable.PrimaryArtifactSpec
import com.achulkov.evomolior.core.deliverable.WriterRepairRequest
import com.achulkov.evomolior.core.deliverable.WriterSourceAuthority
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Durable source workspace for one run/group artifact spine.
 *
 * The workspace is deliberately file-backed rather than tied to a mortal agent process. A writer
 * timeout therefore leaves its source edits, progress marker, and last valid snapshot available to
 * a continuation or recovery selector.
 *
 * @param rootDirectory Root directory containing run workspaces.
 */
class ArtifactWorkspaceManager(
    private val rootDirectory: File,
) {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = false
        classDiscriminator = "type"
    }

    init {
        require(!rootDirectory.path.contains('\u0000')) {
            "Artifact workspace root must not contain NUL"
        }
    }

    /**
     * Opens or creates one immutable group workspace.
     *
     * @param runId Owning run.
     * @param groupId Owning group.
     * @param blueprint V2 artifact blueprint.
     * @param profile Host-owned notebook profile.
     * @return Stable workspace handle.
     */
    fun open(
        runId: String,
        groupId: String,
        blueprint: DeliverableBlueprintV2,
        profile: ArcNotebookProfile = ArcNotebookProfile.v2(),
    ): ArtifactWorkspaceHandle {
        require(runId.isNotBlank() && groupId.isNotBlank()) {
            "Artifact workspace run and group IDs must not be blank"
        }
        blueprint.requireValid()
        profile.requireValid()
        val root = workspaceDirectory(runId, groupId)
        listOf(
            "contract",
            "src",
            "notebook",
            "generated",
            "state",
            "state/snapshots",
        ).forEach { File(root, it).mkdirs() }

        writeImmutableContract(
            File(root, "contract/primary-artifact-spec.json"),
            PrimaryArtifactSpec.serializer(),
            profile.primaryArtifactSpec,
        )
        writeImmutableContract(
            File(root, "contract/profile.json"),
            ArcNotebookProfile.serializer(),
            profile,
        )
        writeImmutableContract(
            File(root, "contract/blueprint-v2.json"),
            DeliverableBlueprintV2.serializer(),
            blueprint,
        )
        writeImmutableText(
            File(root, "contract/required-symbols.json"),
            json.encodeToString(
                JsonElement.serializer(),
                JsonObject(
                    profile.receiverSignals
                        .mapNotNull { signal ->
                            val symbol = (signal as? com.achulkov.evomolior.core.deliverable.ReceiverSignalSpec.PythonSymbol)
                                ?: return@mapNotNull null
                            signal.signalId to kotlinx.serialization.json.JsonPrimitive(symbol.symbolName)
                        }
                        .toMap(),
                ),
            ),
        )
        writeImmutableText(
            File(root, "notebook/cell-manifest.json"),
            json.encodeToString(
                JsonElement.serializer(),
                json.encodeToJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(
                        com.achulkov.evomolior.core.deliverable.NotebookCellSpec.serializer(),
                    ),
                    profile.notebookCells.sortedBy { it.order },
                ),
            ),
        )
        val stateFile = File(root, STATE_PATH)
        if (!stateFile.isFile) {
            val initial = ArtifactWorkspaceState(
                runId = runId,
                groupId = groupId,
                artifactSpecHash = hashPrimaryArtifactSpec(profile.primaryArtifactSpec),
                baseSnapshotHash = profile.profileHash,
                sourceFileHashes = emptyMap(),
                compiledArtifactHash = null,
                latestValidationReportHash = null,
                latestBenchmarkReportHash = null,
                bestSnapshotId = null,
                stateVersion = 0L,
            ).withComputedHash()
            writeJson(stateFile, ArtifactWorkspaceState.serializer(), initial)
        } else {
            readState(root).requireValid()
            require(readState(root).runId == runId && readState(root).groupId == groupId) {
                "Artifact workspace state belongs to another run/group"
            }
        }
        return ArtifactWorkspaceHandle(runId, groupId, root, blueprint, profile)
    }

    /**
     * Reads the current durable state.
     *
     * @param handle Workspace handle.
     * @return Hash-valid workspace state.
     */
    fun state(handle: ArtifactWorkspaceHandle): ArtifactWorkspaceState =
        readState(handle.root).also(ArtifactWorkspaceState::requireValid)

    /**
     * Writes one source module atomically.
     *
     * @param handle Workspace handle.
     * @param relativePath Safe source-relative path.
     * @param content Exact source bytes represented as UTF-8 text.
     */
    fun writeSource(
        handle: ArtifactWorkspaceHandle,
        relativePath: String,
        content: String,
    ) {
        requireSafeRelativePath(relativePath)
        val target = File(handle.root, "src/$relativePath")
        atomicWrite(target, content)
        updateState(handle) { current ->
            current.copy(
                sourceFileHashes = current.sourceFileHashes +
                    (relativePath to AtlasHashing.hashText(content)),
            )
        }
    }

    /**
     * Reads one source module, returning null when no such durable module exists.
     *
     * @param handle Workspace handle.
     * @param relativePath Safe source-relative path.
     * @return Exact source text, or null.
     */
    fun readSource(handle: ArtifactWorkspaceHandle, relativePath: String): String? {
        requireSafeRelativePath(relativePath)
        return File(handle.root, "src/$relativePath").takeIf(File::isFile)?.readText()
    }

    /**
     * Records an immutable artifact snapshot and updates best-so-far state.
     *
     * @param handle Workspace handle.
     * @param round Sealed round index.
     * @param artifact Compiled primary artifact.
     * @param structureReport Structural report bytes.
     * @param executionReport Optional execution report bytes.
     * @param benchmarkReport Optional benchmark report bytes.
     * @param top1 Top-1 benchmark count.
     * @param anyTwo Any-two benchmark count.
     * @param runtimeMs Runtime measurement.
     * @param sourceNodeIds Source lineage nodes.
     * @param patchLineageIds Applied patch lineage IDs.
     * @param authorityClass Snapshot authority class.
     * @param artifactHealth Runtime-owned artifact health.
     * @param backboneHash Common ARC backbone hash.
     * @param branchHash Group-specific solver branch hash.
     * @param benchmarkRowCount Exact benchmark row count, when available.
     * @param benchmarkDefinitionHash Exact benchmark definition hash, when available.
     * @param benchmarkReportBytesHash Hash of exact persisted benchmark report bytes.
     * @param benchmarkRepeatedBytesHash Hash of the independent repeat report bytes.
     * @param benchmarkCsv Optional canonical CSV benchmark projection.
     * @param benchmarkCsvBytesHash Hash of exact persisted benchmark CSV bytes.
     * @param benchmarkCandidateFamilyIds Candidate-family provenance IDs.
     * @param benchmarkRepeatable Whether the exact benchmark report repeated byte-identically.
     * @param rescueRecord Explicit rescue evidence, when candidate work is unavailable.
     * @return Hash-valid persisted snapshot.
     */
    fun recordSnapshot(
        handle: ArtifactWorkspaceHandle,
        round: Int,
        artifact: GeneratedOutputFile,
        structureReport: String,
        executionReport: String? = null,
        benchmarkReport: String? = null,
        top1: Int? = null,
        anyTwo: Int? = null,
        runtimeMs: Long? = null,
        sourceNodeIds: Set<String> = handle.profile.primaryArtifactSpec.sourceNodeIds,
        patchLineageIds: Set<String> = emptySet(),
        authorityClass: ComponentAuthorityClass = ComponentAuthorityClass.PROVISIONAL,
        artifactHealth: GroupArtifactHealth = inferredHealth(executionReport, benchmarkReport, top1),
        backboneHash: String? = null,
        branchHash: String? = null,
        benchmarkRowCount: Int? = null,
        benchmarkDefinitionHash: String? = null,
        benchmarkReportBytesHash: String? = null,
        benchmarkRepeatedBytesHash: String? = null,
        benchmarkCsv: String? = null,
        benchmarkCsvBytesHash: String? = null,
        benchmarkCandidateFamilyIds: List<String> = emptyList(),
        benchmarkRepeatable: Boolean? = null,
        rescueRecord: ArtifactRescueRecord? = null,
    ): ArtifactSnapshot {
        require(round >= 0) { "Artifact snapshot round must not be negative" }
        require(artifact.relativePath == handle.profile.primaryArtifactSpec.relativePath ||
            artifact.relativePath == "submission.ipynb") {
            "Snapshot artifact path does not match the workspace primary artifact"
        }
        require(sourceNodeIds.isNotEmpty()) { "Artifact snapshot requires source lineage" }
        val sourceTreeHash = hashSourceTree(handle)
        val snapshot = ArtifactSnapshot(
            snapshotId = snapshotId(handle, round, artifact.contentHash),
            runId = handle.runId,
            groupId = handle.groupId,
            round = round,
            artifactHash = artifact.contentHash,
            sourceTreeHash = sourceTreeHash,
            structureReportHash = AtlasHashing.hashText(structureReport),
            executionReportHash = executionReport?.let(AtlasHashing::hashText),
            benchmarkReportHash = benchmarkReport?.let(AtlasHashing::hashText),
            top1 = top1,
            anyTwo = anyTwo,
            runtimeMs = runtimeMs,
            authorityClass = authorityClass,
            sourceNodeIds = sourceNodeIds,
            patchLineageIds = patchLineageIds,
            artifactHealth = artifactHealth,
            backboneHash = backboneHash,
            branchHash = branchHash,
            benchmarkRowCount = benchmarkRowCount,
            benchmarkDefinitionHash = benchmarkDefinitionHash,
            benchmarkReportBytesHash = benchmarkReportBytesHash,
            benchmarkRepeatedBytesHash = benchmarkRepeatedBytesHash,
            benchmarkCsvBytesHash = benchmarkCsvBytesHash,
            benchmarkCandidateFamilyIds = benchmarkCandidateFamilyIds,
            benchmarkRepeatable = benchmarkRepeatable,
            rescueRecord = rescueRecord,
        ).withComputedHash()
        val snapshotFile = File(handle.root, "state/snapshots/${snapshot.snapshotId}.json")
        if (snapshotFile.isFile) {
            val existing = readJson(snapshotFile, ArtifactSnapshot.serializer())
            require(existing == snapshot) {
                "Artifact snapshot identity is immutable"
            }
        } else {
            writeJson(snapshotFile, ArtifactSnapshot.serializer(), snapshot)
            atomicWrite(
                File(
                    handle.root,
                    "generated/snapshots/${snapshot.snapshotId}/" +
                        artifact.relativePath.substringAfterLast('/'),
                ),
                artifact.content,
            )
            atomicWrite(
                File(handle.root, "generated/${artifact.relativePath.substringAfterLast('/')}"),
                artifact.content,
            )
            atomicWrite(
                File(handle.root, "generated/reports/${snapshot.snapshotId}-structure.json"),
                structureReport,
            )
            executionReport?.let {
                atomicWrite(
                    File(handle.root, "generated/reports/${snapshot.snapshotId}-execution.json"),
                    it,
                )
            }
            benchmarkReport?.let {
                atomicWrite(
                    File(handle.root, "generated/reports/${snapshot.snapshotId}-benchmark.json"),
                    it,
                )
            }
            benchmarkCsv?.let {
                atomicWrite(
                    File(handle.root, "generated/reports/${snapshot.snapshotId}-benchmark.csv"),
                    it,
                )
            }
        }
        updateState(handle) { current ->
            val best = current.bestSnapshotId?.let { existingId ->
                val existing = readSnapshot(handle, existingId)
                if (isBetter(existing, snapshot)) snapshot.snapshotId else existing.snapshotId
            } ?: snapshot.snapshotId
            current.copy(
                baseSnapshotHash = snapshot.snapshotHash,
                compiledArtifactHash = snapshot.artifactHash,
                latestValidationReportHash = snapshot.structureReportHash,
                latestBenchmarkReportHash = snapshot.benchmarkReportHash,
                bestSnapshotId = best,
            )
        }
        return snapshot
    }

    /**
     * Reads a snapshot by its immutable ID.
     *
     * @param handle Workspace handle.
     * @param snapshotId Snapshot identity.
     * @return Hash-valid snapshot.
     */
    fun readSnapshot(handle: ArtifactWorkspaceHandle, snapshotId: String): ArtifactSnapshot {
        require(snapshotId.isNotBlank()) { "Snapshot ID must not be blank" }
        return readJson(
            File(handle.root, "state/snapshots/$snapshotId.json"),
            ArtifactSnapshot.serializer(),
        ).also(ArtifactSnapshot::requireValid)
    }

    /**
     * Returns the best historical snapshot, if one exists.
     *
     * @param handle Workspace handle.
     * @return Best snapshot or null.
     */
    fun bestSnapshot(handle: ArtifactWorkspaceHandle): ArtifactSnapshot? =
        state(handle).bestSnapshotId?.let { readSnapshot(handle, it) }

    /**
     * Reads the immutable primary artifact bytes retained for one snapshot.
     *
     * @param handle Workspace handle.
     * @param snapshot Hash-valid snapshot whose artifact should be read.
     * @return Generated artifact reconstructed from the snapshot bytes.
     */
    fun readSnapshotArtifact(
        handle: ArtifactWorkspaceHandle,
        snapshot: ArtifactSnapshot,
    ): GeneratedOutputFile {
        snapshot.requireValid()
        require(snapshot.runId == handle.runId && snapshot.groupId == handle.groupId) {
            "Artifact snapshot belongs to another workspace"
        }
        val file = File(
            handle.root,
            "generated/snapshots/${snapshot.snapshotId}/submission.ipynb",
        )
        require(file.isFile) {
            "Missing immutable artifact bytes for snapshot ${snapshot.snapshotId}"
        }
        return GeneratedOutputFile(
            relativePath = "submission.ipynb",
            mediaType = handle.profile.primaryArtifactSpec.mediaType,
            content = file.readText(),
            sourceGroupId = handle.groupId,
        ).also {
            require(it.contentHash == snapshot.artifactHash) {
                "Snapshot artifact bytes do not match ${snapshot.snapshotId}"
            }
        }
    }

    /**
     * Persists a writer progress marker without deleting partial work.
     *
     * @param handle Workspace handle.
     * @param progressJson Exact public progress JSON.
     */
    fun writeWriterProgress(handle: ArtifactWorkspaceHandle, progressJson: String) {
        require(progressJson.isNotBlank()) { "Writer progress must not be blank" }
        atomicWrite(File(handle.root, "state/writer-progress.json"), progressJson)
    }

    /**
     * Retains a provider-authored partial artifact before host compilation or repair.
     *
     * @param handle Workspace handle.
     * @param attempt One-based writer attempt.
     * @param artifact Partial artifact bytes.
     */
    fun writePartialArtifact(
        handle: ArtifactWorkspaceHandle,
        attempt: Int,
        artifact: GeneratedOutputFile,
    ) {
        require(attempt > 0) { "Writer attempt must be one-based" }
        requireSafeRelativePath(artifact.relativePath)
        atomicWrite(
            File(
                handle.root,
                "state/partial-writer/${attempt.toString().padStart(3, '0')}-${artifact.relativePath.substringAfterLast('/')}",
            ),
            artifact.content,
        )
    }

    /**
     * Appends one typed writer repair request without deleting earlier attempts.
     *
     * @param handle Workspace handle.
     * @param request Narrow repair authority for the current snapshot.
     */
    fun writeWriterRepairRequest(
        handle: ArtifactWorkspaceHandle,
        request: WriterRepairRequest,
    ) {
        require(request.violations.isNotEmpty()) {
            "Writer repair request must contain a typed violation"
        }
        val directory = File(handle.root, "state/repair-queue").also(File::mkdirs)
        writeJson(
            File(directory, "attempt-${request.repairAttempt.toString().padStart(3, '0')}.json"),
            WriterRepairRequest.serializer(),
            request,
        )
    }

    /**
     * Reads all durable writer repair requests in deterministic attempt order.
     *
     * @param handle Workspace handle.
     * @return Typed repair requests retained by the workspace.
     */
    fun writerRepairRequests(handle: ArtifactWorkspaceHandle): List<WriterRepairRequest> =
        File(handle.root, "state/repair-queue")
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "json" }
            .sortedBy(File::getName)
            .map {
                readJson(it, WriterRepairRequest.serializer())
            }

    /**
     * Persists the non-null source authority used by the main writer.
     *
     * @param handle Workspace handle.
     * @param authority Hash-valid source authority.
     */
    fun writeWriterSourceAuthority(
        handle: ArtifactWorkspaceHandle,
        authority: WriterSourceAuthority,
    ) {
        authority.requireValid()
        writeJson(
            File(handle.root, "state/writer-source-set.json"),
            WriterSourceAuthority.serializer(),
            authority,
        )
    }

    /**
     * Returns the workspace directory for a run/group.
     *
     * @param runId Owning run.
     * @param groupId Owning group.
     * @return Stable workspace directory.
     */
    fun workspaceDirectory(runId: String, groupId: String): File =
        File(rootDirectory, "$runId/groups/$groupId/artifact-workspace")

    private fun readState(root: File): ArtifactWorkspaceState =
        readJson(File(root, STATE_PATH), ArtifactWorkspaceState.serializer())

    private fun updateState(
        handle: ArtifactWorkspaceHandle,
        transform: (ArtifactWorkspaceState) -> ArtifactWorkspaceState,
    ) {
        val current = state(handle)
        val next = transform(current).copy(stateVersion = current.stateVersion + 1L).withComputedHash()
        writeJson(File(handle.root, STATE_PATH), ArtifactWorkspaceState.serializer(), next)
    }

    private fun hashSourceTree(handle: ArtifactWorkspaceHandle): String {
        val sourceRoot = File(handle.root, "src")
        val entries = sourceRoot.walkTopDown()
            .filter(File::isFile)
            .map { file ->
                val relative = sourceRoot.toPath().relativize(file.toPath()).toString()
                    .replace(File.separatorChar, '/')
                "$relative=${AtlasHashing.hashText(file.readText())}"
            }
            .sorted()
            .toList()
        return AtlasHashing.hashText(entries.joinToString("\n"))
    }

    private fun isBetter(previous: ArtifactSnapshot, candidate: ArtifactSnapshot): Boolean {
        val previousScore = score(previous)
        val candidateScore = score(candidate)
        val scoreComparison = candidateScore.indices
            .asSequence()
            .map { index -> candidateScore[index].compareTo(previousScore[index]) }
            .firstOrNull { it != 0 }
            ?: 0
        return scoreComparison > 0 ||
            scoreComparison == 0 && (
                candidate.round > previous.round ||
                    candidate.round == previous.round &&
                        candidate.snapshotId < previous.snapshotId
                )
    }

    private fun score(snapshot: ArtifactSnapshot): List<Int> = listOf(
        snapshot.top1 ?: -1,
        snapshot.anyTwo ?: -1,
        if (snapshot.executionReportHash != null) 1 else 0,
        if (snapshot.benchmarkReportHash != null) 1 else 0,
        snapshot.benchmarkRowCount ?: -1,
        if (snapshot.authorityClass == ComponentAuthorityClass.ACCEPTED) 1 else 0,
    )

    private fun inferredHealth(
        executionReport: String?,
        benchmarkReport: String?,
        top1: Int?,
    ): GroupArtifactHealth = when {
        benchmarkReport != null && top1 != null && top1 > 0 -> GroupArtifactHealth.IMPROVED
        benchmarkReport != null -> GroupArtifactHealth.BENCHMARKED
        executionReport != null -> GroupArtifactHealth.EXECUTABLE
        else -> GroupArtifactHealth.STRUCTURALLY_VALID
    }

    private fun snapshotId(
        handle: ArtifactWorkspaceHandle,
        round: Int,
        artifactHash: String,
    ): String = buildString {
        append(safeId(handle.runId))
        append('-')
        append(safeId(handle.groupId))
        append("-r")
        append(round)
        append('-')
        append(artifactHash.take(16))
    }

    private fun hashPrimaryArtifactSpec(spec: PrimaryArtifactSpec): String =
        DeliverableHashing.hashText(
            DeliverableHashing.canonicalJson(
                json.encodeToJsonElement(PrimaryArtifactSpec.serializer(), spec),
            ),
        )

    private fun writeImmutableContract(
        file: File,
        serializer: KSerializer<*>,
        value: Any,
    ) {
        val content = when (value) {
            is PrimaryArtifactSpec -> json.encodeToString(
                PrimaryArtifactSpec.serializer(),
                value,
            )
            is ArcNotebookProfile -> json.encodeToString(
                ArcNotebookProfile.serializer(),
                value,
            )
            is DeliverableBlueprintV2 -> json.encodeToString(
                DeliverableBlueprintV2.serializer(),
                value,
            )
            else -> error("Unsupported immutable artifact contract ${value::class}")
        }
        writeImmutableText(file, content)
    }

    private fun writeImmutableText(file: File, content: String) {
        if (file.isFile) {
            require(file.readText() == content) {
                "Artifact workspace contract is immutable: ${file.path}"
            }
        } else {
            atomicWrite(file, content)
        }
    }

    private fun <T> writeJson(file: File, serializer: KSerializer<T>, value: T) {
        atomicWrite(file, json.encodeToString(serializer, value))
    }

    private fun <T> readJson(file: File, serializer: KSerializer<T>): T {
        require(file.isFile) { "Missing artifact workspace authority ${file.path}" }
        return json.decodeFromString(serializer, file.readText())
    }

    private fun atomicWrite(file: File, content: String) {
        requireSafeRelativePath(
            rootDirectory.toPath().relativize(file.toPath()).toString()
                .replace(File.separatorChar, '/'),
        )
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.tmp")
        temporary.writeText(content)
        runCatching {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun requireSafeRelativePath(relativePath: String) {
        require(
            relativePath.isNotBlank() &&
                !relativePath.startsWith('/') &&
                !relativePath.contains('\\') &&
                relativePath.split('/').none { it.isBlank() || it == "." || it == ".." },
        ) { "Artifact workspace path is unsafe: $relativePath" }
    }

    private fun safeId(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private companion object {
        const val STATE_PATH = "state/artifact-workspace-state.json"
    }
}

/**
 * Stable handle for one persistent artifact workspace.
 *
 * @param runId Owning run.
 * @param groupId Owning group.
 * @param root Workspace directory.
 * @param blueprint Immutable artifact blueprint.
 * @param profile Host-owned notebook profile.
 */
data class ArtifactWorkspaceHandle(
    val runId: String,
    val groupId: String,
    val root: File,
    val blueprint: DeliverableBlueprintV2,
    val profile: ArcNotebookProfile,
)
