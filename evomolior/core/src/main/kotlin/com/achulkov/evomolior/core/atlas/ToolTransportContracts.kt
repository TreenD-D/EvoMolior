package com.achulkov.evomolior.core.atlas

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private val toolTransportJson = Json {
    encodeDefaults = true
    explicitNulls = true
}

/** Runtime family used to materialise and execute a generated tool candidate. */
@Serializable
enum class ToolRuntimeKind {
    SHELL,
    PYTHON,
    FORMULA_DSL,
    RULE_DSL,
    SIMULATION_DSL
}

/**
 * Capability scope against which a short-lived tool-opportunity catalog is resolved.
 *
 * @param runId Exact run authority.
 * @param groupId Exact isolated group authority.
 * @param round Exact worker round.
 * @param workerViewHash Exact boot-time worker-view commitment.
 */
@Serializable
data class ToolOpportunityBindingCapability(
    val runId: String,
    val groupId: String,
    val round: Int,
    val workerViewHash: String
) {
    init {
        require(runId.isNotBlank() && groupId.isNotBlank() && workerViewHash.isSha256()) {
            "Tool binding capability requires exact run, group, and worker-view authority"
        }
        require(round >= 0) { "Tool binding capability round must not be negative" }
    }
}

/**
 * One runtime-owned mapping from a short model handle to exact sealed tool authority.
 *
 * @param handle Short lifecycle-local handle such as `T1`.
 * @param nodeId Exact assigned Atlas node.
 * @param opportunityId Exact historical opportunity identity.
 * @param opportunityHash Exact historical opportunity commitment.
 * @param inputSchemaHash Exact generated input-schema bytes.
 * @param outputSchemaHash Exact generated output-schema bytes.
 * @param publicTestIds Explicitly public runtime-generated test IDs, independent of private
 * promotion authority.
 * @param runtimeKind Runtime implementation family.
 * @param projectSkeletonHash Exact generated skeleton commitment.
 */
@Serializable
data class ToolOpportunityBinding(
    val handle: String,
    val nodeId: String,
    val opportunityId: String,
    val opportunityHash: String,
    val inputSchemaHash: String,
    val outputSchemaHash: String,
    val publicTestIds: List<String>,
    val runtimeKind: ToolRuntimeKind,
    val projectSkeletonHash: String
) {
    init {
        require(handle.matches(Regex("T[1-9][0-9]{0,2}"))) {
            "Tool opportunity handle must be a short T-prefixed capability"
        }
        require(nodeId.isNotBlank() && opportunityId.isNotBlank()) {
            "Tool opportunity binding requires exact node and opportunity identities"
        }
        require(
            listOf(
                opportunityHash,
                inputSchemaHash,
                outputSchemaHash,
                projectSkeletonHash
            ).all(String::isSha256)
        ) { "Tool opportunity binding requires exact lowercase SHA-256 commitments" }
        require(publicTestIds.none(String::isBlank)) {
            "Tool opportunity binding public test IDs must not be blank"
        }
        require(publicTestIds.distinct().size == publicTestIds.size) {
            "Tool opportunity binding public test IDs must be unique"
        }
    }
}

/**
 * Immutable lifecycle-local catalog resolving short handles to exact tool authority.
 *
 * @param catalogId Stable capability-bound catalog identity.
 * @param runId Exact run authority.
 * @param groupId Exact isolated group authority.
 * @param round Exact worker round.
 * @param workerViewHash Exact worker-view commitment.
 * @param bindings Ordered exact bindings.
 * @param catalogHash Hash excluding this field.
 */
@Serializable
data class ToolOpportunityBindingCatalog(
    val catalogId: String,
    val runId: String,
    val groupId: String,
    val round: Int,
    val workerViewHash: String,
    val bindings: List<ToolOpportunityBinding>,
    val catalogHash: String = ""
) {
    init {
        require(catalogId.isNotBlank() && runId.isNotBlank() && groupId.isNotBlank()) {
            "Tool opportunity catalog identity and scope must not be blank"
        }
        require(round >= 0 && workerViewHash.isSha256()) {
            "Tool opportunity catalog requires an exact worker-view scope"
        }
        require(bindings.isNotEmpty()) { "Tool opportunity catalog requires at least one binding" }
        require(bindings.map(ToolOpportunityBinding::handle).distinct().size == bindings.size) {
            "Tool opportunity catalog handles must be unique"
        }
        require(bindings.map(ToolOpportunityBinding::nodeId).distinct().size == bindings.size) {
            "Tool opportunity catalog nodes must be unique"
        }
        require(
            bindings.map(ToolOpportunityBinding::opportunityId).distinct().size == bindings.size
        ) { "Tool opportunity catalog opportunity IDs must be unique" }
        require(catalogHash.isEmpty() || catalogHash.isSha256()) {
            "Tool opportunity catalog hash must be empty or lowercase SHA-256"
        }
    }

    /** Returns this catalog with its exact deterministic self hash. */
    fun withComputedHash(): ToolOpportunityBindingCatalog =
        copy(catalogHash = ToolTransportHashing.hashCatalog(this))

    /** Returns whether this catalog carries its exact deterministic self hash. */
    fun hasValidHash(): Boolean = AtlasHashing.constantTimeEquals(
        catalogHash,
        ToolTransportHashing.hashCatalog(this)
    )

    /**
     * Resolves [handle] only when [capability] exactly matches this catalog.
     *
     * Unknown, stale, foreign, duplicated, or tampered authority fails closed.
     */
    fun resolve(
        handle: String,
        capability: ToolOpportunityBindingCapability
    ): ToolOpportunityBindingResolution {
        if (!hasValidHash()) {
            return ToolOpportunityBindingResolution.Rejected(
                ToolOpportunityBindingRejection.TAMPERED_CATALOG
            )
        }
        if (capability.runId != runId || capability.groupId != groupId) {
            return ToolOpportunityBindingResolution.Rejected(
                ToolOpportunityBindingRejection.FOREIGN_CAPABILITY
            )
        }
        if (capability.round != round || capability.workerViewHash != workerViewHash) {
            return ToolOpportunityBindingResolution.Rejected(
                ToolOpportunityBindingRejection.STALE_CAPABILITY
            )
        }
        val matches = bindings.filter { it.handle == handle }
        return when (matches.size) {
            1 -> ToolOpportunityBindingResolution.Resolved(matches.single())
            0 -> ToolOpportunityBindingResolution.Rejected(
                ToolOpportunityBindingRejection.UNKNOWN_HANDLE
            )
            else -> ToolOpportunityBindingResolution.Rejected(
                ToolOpportunityBindingRejection.DUPLICATE_HANDLE
            )
        }
    }
}

/** Stable fail-closed reason for short-handle resolution. */
@Serializable
enum class ToolOpportunityBindingRejection {
    UNKNOWN_HANDLE,
    STALE_CAPABILITY,
    FOREIGN_CAPABILITY,
    DUPLICATE_HANDLE,
    TAMPERED_CATALOG
}

/** Result of resolving one lifecycle-local tool handle. */
sealed interface ToolOpportunityBindingResolution {
    /** Exact resolved runtime authority. */
    data class Resolved(val binding: ToolOpportunityBinding) :
        ToolOpportunityBindingResolution

    /** Rejected untrusted handle or capability. */
    data class Rejected(val reason: ToolOpportunityBindingRejection) :
        ToolOpportunityBindingResolution
}

/**
 * Model-authored candidate reference containing no exact opportunity IDs or copied schemas.
 *
 * @param opportunityHandle Lifecycle-local short handle.
 * @param sourceArtifactPath Workspace-relative implementation path.
 * @param entryPoint Runtime entry point declared by the author.
 * @param supplementalTestsPath Optional workspace-relative author test path.
 * @param declaredAssumptions Public author assumptions retained in compilation authority.
 * @param consumedRepairDiagnosticIds Safe diagnostic IDs used by this repair.
 */
@Serializable
data class ToolCandidateWireDraft(
    val opportunityHandle: String,
    val sourceArtifactPath: String,
    val entryPoint: String,
    val supplementalTestsPath: String? = null,
    val declaredAssumptions: List<String> = emptyList(),
    val consumedRepairDiagnosticIds: List<String> = emptyList()
) {
    init {
        require(opportunityHandle.matches(Regex("T[1-9][0-9]{0,2}"))) {
            "Tool candidate requires a short opportunity handle"
        }
        require(sourceArtifactPath.isSafeRelativePath() && entryPoint.isNotBlank()) {
            "Tool candidate requires a safe source path and entry point"
        }
        require(
            supplementalTestsPath == null || supplementalTestsPath.isSafeRelativePath()
        ) { "Tool candidate supplemental tests path must be safe and relative" }
        require(declaredAssumptions.none(String::isBlank)) {
            "Tool candidate assumptions must not be blank"
        }
        require(
            consumedRepairDiagnosticIds.none(String::isBlank) &&
                consumedRepairDiagnosticIds.distinct().size ==
                consumedRepairDiagnosticIds.size
        ) { "Tool candidate diagnostic IDs must be non-blank and unique" }
    }
}

/**
 * One exact public test generated into the immutable contract workspace.
 *
 * Private promotion fixtures use a separate authority and must never be placed here.
 */
@Serializable
data class ToolPublicContractTest(
    val testId: String,
    val input: kotlinx.serialization.json.JsonElement,
    val expectedOutput: kotlinx.serialization.json.JsonElement
) {
    init {
        require(testId.isNotBlank()) { "Public tool test ID must not be blank" }
    }
}

/** Mutability class for one generated tool-workspace file. */
@Serializable
enum class ToolWorkspaceFileAccess {
    READ_ONLY,
    AUTHOR_EDITABLE,
    RUNTIME_OUTPUT
}

/**
 * Exact generated workspace file and its initial UTF-8 commitment.
 *
 * @param relativePath Safe workspace-relative path.
 * @param content Initial UTF-8 content.
 * @param access File authority class.
 * @param contentHash Exact initial-content hash.
 */
@Serializable
data class ToolContractWorkspaceFile(
    val relativePath: String,
    val content: String,
    val access: ToolWorkspaceFileAccess,
    val contentHash: String = AtlasHashing.hashText(content)
) {
    init {
        require(relativePath.isSafeRelativePath()) {
            "Tool workspace file path must be safe and relative"
        }
        require(contentHash.isSha256() && contentHash == AtlasHashing.hashText(content)) {
            "Tool workspace file content hash is invalid"
        }
    }
}

/**
 * Immutable generated tool-project workspace manifest.
 *
 * The manifest commits both read-only contracts and initial editable files. Runtime verification
 * permits author changes only outside the `READ_ONLY` class.
 */
@Serializable
data class ToolContractWorkspace(
    val workspaceId: String,
    val catalogId: String,
    val opportunityHandle: String,
    val opportunityCommitmentHash: String,
    val files: List<ToolContractWorkspaceFile>,
    val workspaceHash: String = ""
) {
    init {
        require(
            workspaceId.isNotBlank() &&
                catalogId.isNotBlank() &&
                opportunityHandle.matches(Regex("T[1-9][0-9]{0,2}")) &&
                opportunityCommitmentHash.isSha256()
        ) { "Tool contract workspace requires exact binding authority" }
        require(files.isNotEmpty() && files.map { it.relativePath }.distinct().size == files.size) {
            "Tool contract workspace requires unique files"
        }
        require(
            files.filter { it.access == ToolWorkspaceFileAccess.READ_ONLY }
                .map { it.relativePath }
                .containsAll(
                    listOf(
                        "contract/tool-contract.json",
                        "contract/input-schema.json",
                        "contract/output-schema.json",
                        "contract/public-tests.json",
                        "contract/README.md"
                    )
                )
        ) { "Tool contract workspace is missing immutable contract files" }
        require(workspaceHash.isEmpty() || workspaceHash.isSha256()) {
            "Tool contract workspace hash must be empty or lowercase SHA-256"
        }
    }

    /** Returns this workspace with its exact deterministic self hash. */
    fun withComputedHash(): ToolContractWorkspace =
        copy(workspaceHash = ToolTransportHashing.hashWorkspace(this))

    /** Returns whether this workspace carries its exact deterministic self hash. */
    fun hasValidHash(): Boolean = AtlasHashing.constantTimeEquals(
        workspaceHash,
        ToolTransportHashing.hashWorkspace(this)
    )
}

/**
 * Exact output of compiling a model wire draft into the legacy TOOL payload.
 *
 * @param content Exact JSON consumed by existing deterministic validators.
 * @param wipTaskRef Exact Atlas node resolved from the handle.
 * @param opportunityCommitmentHash Historical commitment retained for audit.
 * @param sourceHash Exact source bytes.
 * @param supplementalTestsHash Exact optional author-test bytes.
 * @param candidateHash Hash of the complete compiled authority.
 * @param consumedRepairDiagnosticIds Exact visible typed diagnostics consumed by this candidate.
 */
data class CompiledToolCandidateWire(
    val content: String,
    val wipTaskRef: String,
    val opportunityCommitmentHash: String,
    val sourceHash: String,
    val supplementalTestsHash: String?,
    val candidateHash: String,
    val consumedRepairDiagnosticIds: List<String> = emptyList(),
    val repairApproachVector: com.achulkov.evomolior.core.repair.ApproachVector? = null
)

/** Result returned to the staging boundary by a runtime-owned wire compiler. */
sealed interface ToolCandidateWireCompilation {
    /** Exact legacy TOOL payload ready for deterministic preflight. */
    data class Compiled(val candidate: CompiledToolCandidateWire) :
        ToolCandidateWireCompilation

    /** One bounded actionable wire failure. */
    data class Rejected(val code: String, val detail: String) :
        ToolCandidateWireCompilation {
        init {
            require(code.matches(Regex("[a-z0-9_]{1,96}")) && detail.isNotBlank()) {
                "Tool wire rejection requires a stable code and bounded detail"
            }
        }
    }
}

/** Hashing operations local to runtime-owned tool transport contracts. */
object ToolTransportHashing {
    /** Hashes a catalog excluding its self hash. */
    fun hashCatalog(catalog: ToolOpportunityBindingCatalog): String =
        hashWithoutField(
            ToolOpportunityBindingCatalog.serializer(),
            catalog,
            "catalogHash"
        )

    /** Hashes a workspace manifest excluding its self hash. */
    fun hashWorkspace(workspace: ToolContractWorkspace): String =
        hashWithoutField(
            ToolContractWorkspace.serializer(),
            workspace,
            "workspaceHash"
        )

    private fun <T> hashWithoutField(
        serializer: kotlinx.serialization.KSerializer<T>,
        value: T,
        field: String
    ): String {
        val element = toolTransportJson.encodeToJsonElement(serializer, value).jsonObject
        return AtlasHashing.hashText(
            AtlasHashing.canonicalJson(JsonObject(element.filterKeys { it != field }))
        )
    }
}

internal fun String.isSafeRelativePath(): Boolean =
    isNotBlank() &&
        !startsWith("/") &&
        !startsWith("\\") &&
        split('/', '\\').none { it.isBlank() || it == "." || it == ".." }

internal fun String.isSha256(): Boolean = matches(Regex("[0-9a-f]{64}"))
