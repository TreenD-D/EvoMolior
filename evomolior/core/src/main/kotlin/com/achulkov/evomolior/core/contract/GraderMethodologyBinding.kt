package com.achulkov.evomolior.core.contract

import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.NodeDeliverableContract
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.ProofContract
import com.achulkov.evomolior.core.grader.AdversarialCase
import com.achulkov.evomolior.core.grader.DisclosureSensitivity
import com.achulkov.evomolior.core.grader.FailureLevel
import com.achulkov.evomolior.core.grader.GraderNodeId
import com.achulkov.evomolior.core.grader.GraderNodeKind
import com.achulkov.evomolior.core.grader.GraderNodeStatus
import com.achulkov.evomolior.core.grader.GraderView
import kotlinx.serialization.Serializable

/**
 * Model-wire methodology whose identity bindings use short catalog handles.
 *
 * All non-binding fields remain model-authored and are projected without modification.
 *
 * @param rubricDimensions Model-authored scoring guidance.
 * @param automatedTests Model-authored deterministic checks.
 * @param failureModes Model-authored failure patterns.
 * @param requirements Model-authored handle-bound requirements.
 */
@Serializable
data class GraderMethodologyWireDraft(
    val rubricDimensions: List<GraderRubricDraft>,
    val automatedTests: List<GraderAutomatedTestDraft>,
    val failureModes: List<GraderFailureModeDraft>,
    val requirements: List<GraderRequirementWireDraft> = emptyList()
)

/**
 * Model-wire requirement carrying handles instead of exact Atlas identities.
 *
 * @param requirementId Stable grader-local identity.
 * @param graderNodeHandle Short handle for the governing grader row.
 * @param mappedWorkerNodeHandles Short handles for governed worker nodes.
 * @param mappedReceiverObligationRefs Receiver obligations governed by the requirement.
 * @param criterion Falsifiable criterion.
 * @param observableEvidence Allowed observable evidence.
 * @param passThreshold Explicit pass threshold.
 * @param failureLevels Ordered failure severities.
 * @param adversarialCases Bounded adversarial cases.
 * @param executableTestIds Tests backing the requirement.
 * @param disclosureSensitivity Visibility classification.
 * @param rationale Receiver-relevant rationale.
 * @param noveltyFingerprint Deduplication/stronger-than commitment.
 * @param maximumEvaluationCost Bounded evaluation cost.
 */
@Serializable
data class GraderRequirementWireDraft(
    val requirementId: String,
    val graderNodeHandle: String,
    val mappedWorkerNodeHandles: Set<String>,
    val mappedReceiverObligationRefs: Set<String>,
    val criterion: String,
    val observableEvidence: String,
    val passThreshold: String,
    val failureLevels: List<FailureLevel>,
    val adversarialCases: List<AdversarialCase>,
    val executableTestIds: List<String>,
    val disclosureSensitivity: DisclosureSensitivity,
    val rationale: String,
    val noveltyFingerprint: String,
    val maximumEvaluationCost: Double
)

/**
 * Exact lease metadata sealing one catalog capability.
 *
 * @param graderNodeId Exact leased grader node.
 * @param leaseId Exact active lease identity.
 * @param leaseHash Exact active lease commitment.
 */
@Serializable
data class GraderMethodologyLeaseBinding(
    val graderNodeId: GraderNodeId,
    val leaseId: String,
    val leaseHash: String
)

/**
 * Exact identities associated with one leased grader-row handle.
 *
 * @param graderNodeHandle Short model-facing grader handle.
 * @param graderNodeId Exact grader node identity.
 * @param workerBindings Exact worker identities keyed by their short handles.
 * @param receiverObligationRefs Receiver references valid for this row.
 */
@Serializable
data class GraderMethodologyNodeBinding(
    val graderNodeHandle: String,
    val graderNodeId: GraderNodeId,
    val workerBindings: List<GraderMethodologyWorkerBinding>,
    val receiverObligationRefs: Set<String>
)

/**
 * One short-to-exact worker-node binding.
 *
 * @param workerNodeHandle Short model-facing worker handle.
 * @param workerNodeId Exact worker node identity.
 */
@Serializable
data class GraderMethodologyWorkerBinding(
    val workerNodeHandle: String,
    val workerNodeId: NodeId
)

/**
 * Redacted capability view safe to place in a model prompt.
 *
 * @param rows Leased grader rows represented only by short handles.
 */
@Serializable
data class GraderMethodologyModelView(
    val rows: List<GraderMethodologyModelRow>
)

/**
 * Sealed worker-node semantics exposed behind a redacted short handle.
 *
 * Exact node, graph, and lease identities remain runtime-owned. The descriptor exposes only the
 * node-local contract required to author an independently satisfiable capture criterion.
 *
 * @param workerNodeHandle Short worker handle used in model output.
 * @param kind Semantic worker-node kind.
 * @param layer Worker Atlas decomposition layer.
 * @param stageIndex Progressive-disclosure stage index.
 * @param receiverWhyRef Receiver obligation addressed by the node.
 * @param limitationStatement Exact node-local limitation.
 * @param capabilityTarget Exact node-local capability target.
 * @param atomicQuestion Exact bounded question assigned to workers.
 * @param proofContract Observable node-local proof contract.
 * @param deliverableContract Accepted node-local artifact contract.
 */
@Serializable
data class GraderMethodologyWorkerDescriptor(
    val workerNodeHandle: String,
    val kind: NodeKind,
    val layer: Int,
    val stageIndex: Int,
    val receiverWhyRef: String,
    val limitationStatement: String,
    val capabilityTarget: String,
    val atomicQuestion: String,
    val proofContract: ProofContract,
    val deliverableContract: NodeDeliverableContract
)

/**
 * Redacted model-facing grader row.
 *
 * @param graderNodeHandle Short grader-row handle.
 * @param kind Evaluation capability represented by the row.
 * @param title Human-readable title.
 * @param evaluationQuestion Atomic evaluation question.
 * @param receiverObligationRefs Receiver references valid for this row.
 * @param workerNodes Semantically described worker handles valid for this row.
 * @param status Current grader-node status.
 */
@Serializable
data class GraderMethodologyModelRow(
    val graderNodeHandle: String,
    val kind: GraderNodeKind,
    val title: String,
    val evaluationQuestion: String,
    val receiverObligationRefs: Set<String>,
    val workerNodes: List<GraderMethodologyWorkerDescriptor>,
    val status: GraderNodeStatus
) {
    /** Short worker handles valid for this row. */
    val workerNodeHandles: Set<String>
        get() = workerNodes.mapTo(linkedSetOf()) {
            it.workerNodeHandle
        }
}

/**
 * Deterministic, round-scoped identity projection authority derived from one [GraderView].
 *
 * The catalog retains exact identities and lease commitments while [modelView] exposes only short
 * handles. Handles deliberately exclude lease metadata, so same-node lease reacquisition does not
 * force the model to learn new handles; [catalogHash] still changes for runtime audit.
 *
 * @param runId Owning run.
 * @param graderGroupId Owning grader group.
 * @param round Grading round.
 * @param graderAtlasHash Exact Grader Atlas authority.
 * @param workerAtlasHash Exact worker Atlas authority supplying model-facing node semantics.
 * @param workerAtlasVersion Exact worker Atlas version.
 * @param activeLeases Exact active lease identities and commitments.
 * @param nodeBindings Exact handle bindings for leased rows.
 * @param modelView Redacted model-facing capability view.
 * @param catalogHash Deterministic commitment including active lease metadata.
 */
@Serializable
@ConsistentCopyVisibility
data class GraderMethodologyBindingCatalog private constructor(
    val runId: String,
    val graderGroupId: String,
    val round: Int,
    val graderAtlasHash: String,
    val workerAtlasHash: String,
    val workerAtlasVersion: Int,
    val activeLeases: List<GraderMethodologyLeaseBinding>,
    val nodeBindings: List<GraderMethodologyNodeBinding>,
    val modelView: GraderMethodologyModelView,
    val catalogHash: String
) {
    /**
     * Resolves a model-wire draft into the unchanged exact-ID persistence contract.
     *
     * @param draft Handle-bound model output.
     * @return Exact projection or a recoverable list of deterministic binding errors.
     */
    fun resolve(draft: GraderMethodologyWireDraft): GraderMethodologyBindingResolution {
        val errors = mutableListOf<GraderMethodologyBindingError>()
        if (!hasValidHash()) {
            errors += bindingError(
                GraderMethodologyBindingErrorCode.INVALID_CATALOG,
                "catalogHash",
                catalogHash,
                "Binding catalog commitment is invalid"
            )
        }

        val rowsByHandle = nodeBindings.associateBy { it.graderNodeHandle }
        val coveredWorkerHandles = mutableMapOf<String, MutableSet<String>>()
        val exactRequirements = draft.requirements.mapIndexedNotNull { index, requirement ->
            val requirementPath = "requirements[$index]"
            if (requirement.graderNodeHandle.isBlank()) {
                errors += bindingError(
                    GraderMethodologyBindingErrorCode.EMPTY_HANDLE,
                    "$requirementPath.graderNodeHandle",
                    requirement.graderNodeHandle,
                    "Grader-node handle must not be empty"
                )
                return@mapIndexedNotNull null
            }
            val row = rowsByHandle[requirement.graderNodeHandle]
            if (row == null) {
                errors += bindingError(
                    if (isForeignHandle(requirement.graderNodeHandle, "g")) {
                        GraderMethodologyBindingErrorCode.FOREIGN_HANDLE
                    } else {
                        GraderMethodologyBindingErrorCode.UNKNOWN_GRADER_HANDLE
                    },
                    "$requirementPath.graderNodeHandle",
                    requirement.graderNodeHandle,
                    "Grader-node handle is not valid for this catalog"
                )
                return@mapIndexedNotNull null
            }
            val workersByHandle = row.workerBindings.associateBy {
                it.workerNodeHandle
            }
            if (requirement.mappedWorkerNodeHandles.isEmpty()) {
                errors += bindingError(
                    GraderMethodologyBindingErrorCode.EMPTY_WORKER_COVERAGE,
                    "$requirementPath.mappedWorkerNodeHandles",
                    "",
                    "Requirement must bind at least one worker-node handle"
                )
            }
            if (requirement.mappedWorkerNodeHandles.size > 1) {
                errors += bindingError(
                    GraderMethodologyBindingErrorCode.NON_ATOMIC_WORKER_COVERAGE,
                    "$requirementPath.mappedWorkerNodeHandles",
                    requirement.mappedWorkerNodeHandles.sorted().joinToString(),
                    "A capture-governing requirement must bind exactly one worker-node handle"
                )
            }
            val exactWorkerIds = requirement.mappedWorkerNodeHandles.mapNotNull { handle ->
                when {
                    handle.isBlank() -> {
                        errors += bindingError(
                            GraderMethodologyBindingErrorCode.EMPTY_HANDLE,
                            "$requirementPath.mappedWorkerNodeHandles",
                            handle,
                            "Worker-node handle must not be empty"
                        )
                        null
                    }
                    handle in workersByHandle -> workersByHandle.getValue(handle).workerNodeId
                    nodeBindings.any { binding ->
                        binding.workerBindings.any { it.workerNodeHandle == handle }
                    } -> {
                        errors += bindingError(
                            GraderMethodologyBindingErrorCode.WORKER_HANDLE_OUT_OF_ROW,
                            "$requirementPath.mappedWorkerNodeHandles",
                            handle,
                            "Worker-node handle does not belong to the selected grader row"
                        )
                        null
                    }
                    else -> {
                        errors += bindingError(
                            if (isForeignHandle(handle, "w")) {
                                GraderMethodologyBindingErrorCode.FOREIGN_HANDLE
                            } else {
                                GraderMethodologyBindingErrorCode.UNKNOWN_WORKER_HANDLE
                            },
                            "$requirementPath.mappedWorkerNodeHandles",
                            handle,
                            "Worker-node handle is not valid for this catalog"
                        )
                        null
                    }
                }
            }.toSet()
            coveredWorkerHandles
                .getOrPut(row.graderNodeHandle, ::linkedSetOf)
                .addAll(requirement.mappedWorkerNodeHandles.filter(workersByHandle::containsKey))

            if (requirement.mappedReceiverObligationRefs.isEmpty()) {
                errors += bindingError(
                    GraderMethodologyBindingErrorCode.EMPTY_RECEIVER_COVERAGE,
                    "$requirementPath.mappedReceiverObligationRefs",
                    "",
                    "Requirement must bind at least one receiver obligation"
                )
            }
            requirement.mappedReceiverObligationRefs
                .filterNot(row.receiverObligationRefs::contains)
                .forEach { receiverRef ->
                    errors += bindingError(
                        GraderMethodologyBindingErrorCode.RECEIVER_REF_OUT_OF_ROW,
                        "$requirementPath.mappedReceiverObligationRefs",
                        receiverRef,
                        "Receiver obligation does not belong to the selected grader row"
                    )
                }

            GraderRequirementDraft(
                requirementId = requirement.requirementId,
                graderAtlasNodeId = row.graderNodeId,
                mappedWorkerNodeIds = exactWorkerIds,
                mappedReceiverObligationRefs = requirement.mappedReceiverObligationRefs,
                criterion = requirement.criterion,
                observableEvidence = requirement.observableEvidence,
                passThreshold = requirement.passThreshold,
                failureLevels = requirement.failureLevels,
                adversarialCases = requirement.adversarialCases,
                executableTestIds = requirement.executableTestIds,
                disclosureSensitivity = requirement.disclosureSensitivity,
                rationale = requirement.rationale,
                noveltyFingerprint = requirement.noveltyFingerprint,
                maximumEvaluationCost = requirement.maximumEvaluationCost
            )
        }

        nodeBindings.forEach { row ->
            val expected = row.workerBindings.mapTo(linkedSetOf()) {
                it.workerNodeHandle
            }
            val covered = coveredWorkerHandles[row.graderNodeHandle].orEmpty()
            val missing = expected - covered
            if (missing.isNotEmpty()) {
                errors += bindingError(
                    GraderMethodologyBindingErrorCode.INCOMPLETE_WORKER_COVERAGE,
                    "requirements",
                    row.graderNodeHandle,
                    "Grader row is missing ${missing.size} worker-node handle binding(s): " +
                        missing.sorted().joinToString()
                )
            }
        }

        return if (errors.isEmpty()) {
            GraderMethodologyBindingResolution.Resolved(
                GraderMethodologyDraft(
                    rubricDimensions = draft.rubricDimensions,
                    automatedTests = draft.automatedTests,
                    failureModes = draft.failureModes,
                    requirements = exactRequirements
                )
            )
        } else {
            GraderMethodologyBindingResolution.Rejected(errors)
        }
    }

    /** Returns whether [catalogHash] still commits this catalog's exact content. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(catalogHash, computeCatalogHash(this))

    private fun isForeignHandle(handle: String, expectedPrefix: String): Boolean {
        val parts = handle.split('_')
        return parts.size == 3 &&
            parts[0] == expectedPrefix &&
            parts[1] != scopeTag(runId, graderGroupId, round, graderAtlasHash)
    }

    companion object {
        private const val HANDLE_SCOPE_HEX_LENGTH = 8
        private const val HANDLE_ID_HEX_LENGTH = 12

        /**
         * Constructs and seals a catalog from exactly one round-scoped grader view.
         *
         * Only rows leased by the receiving group become capabilities. Every such row must carry
         * non-empty active lease metadata. Any generated handle collision fails construction.
         *
         * @param view Exact view from which to derive the catalog.
         * @param workerAtlas Sealed worker Atlas supplying the node-local semantics.
         * @return Deterministic sealed catalog.
         * @throws IllegalArgumentException If active lease metadata is absent or handles collide.
         */
        fun from(
            view: GraderView,
            workerAtlas: NodeAtlas
        ): GraderMethodologyBindingCatalog {
            require(workerAtlas.runId == view.runId) {
                "Worker Atlas and grader view must belong to the same run"
            }
            workerAtlas.validate().requireValid()
            val scopeTag = scopeTag(
                view.runId,
                view.graderGroupId,
                view.round,
                view.graderAtlasHash
            )
            val leasedNodes = view.nodes.filter { it.leasedByThisGroup }.sortedBy {
                it.nodeId.value
            }
            require(leasedNodes.isNotEmpty()) {
                "Grader methodology binding requires at least one active leased node"
            }
            val leases = leasedNodes.map { node ->
                require(!node.activeLeaseId.isNullOrBlank() && !node.activeLeaseHash.isNullOrBlank()) {
                    "Leased grader node ${node.nodeId.value} requires active lease ID and hash"
                }
                GraderMethodologyLeaseBinding(
                    graderNodeId = node.nodeId,
                    leaseId = requireNotNull(node.activeLeaseId),
                    leaseHash = requireNotNull(node.activeLeaseHash)
                )
            }
            val bindings = leasedNodes.map { node ->
                require(workerAtlas.nodes.keys.containsAll(node.mappedWorkerNodeIds)) {
                    "Grader view references worker nodes outside the sealed worker Atlas"
                }
                GraderMethodologyNodeBinding(
                    graderNodeHandle = handle("g", scopeTag, "grader", node.nodeId.value),
                    graderNodeId = node.nodeId,
                    workerBindings = node.mappedWorkerNodeIds.sortedBy(NodeId::value).map { worker ->
                        GraderMethodologyWorkerBinding(
                            workerNodeHandle = handle("w", scopeTag, "worker", worker.value),
                            workerNodeId = worker
                        )
                    },
                    receiverObligationRefs = node.receiverObligationRefs
                )
            }
            requireNoHandleCollisions(bindings)
            val stageIndexByNodeId = workerAtlas.stageDefinitions
                .flatMap { stage -> stage.nodeIds.map { it to stage.stageIndex } }
                .toMap()
            val modelRows = leasedNodes.zip(bindings).map { (node, binding) ->
                GraderMethodologyModelRow(
                    graderNodeHandle = binding.graderNodeHandle,
                    kind = node.kind,
                    title = node.title,
                    evaluationQuestion = node.evaluationQuestion,
                    receiverObligationRefs = node.receiverObligationRefs,
                    workerNodes = binding.workerBindings.map { workerBinding ->
                        val workerNode = workerAtlas.nodes.getValue(workerBinding.workerNodeId)
                        GraderMethodologyWorkerDescriptor(
                            workerNodeHandle = workerBinding.workerNodeHandle,
                            kind = workerNode.kind,
                            layer = workerNode.layer,
                            stageIndex = stageIndexByNodeId.getValue(workerNode.id),
                            receiverWhyRef = workerNode.receiverWhyRef,
                            limitationStatement = workerNode.limitationStatement,
                            capabilityTarget = workerNode.capabilityTarget,
                            atomicQuestion = workerNode.atomicQuestion,
                            proofContract = workerNode.proofContract,
                            deliverableContract = workerNode.deliverableContract
                        )
                    },
                    status = node.status
                )
            }
            val provisional = GraderMethodologyBindingCatalog(
                runId = view.runId,
                graderGroupId = view.graderGroupId,
                round = view.round,
                graderAtlasHash = view.graderAtlasHash,
                workerAtlasHash = workerAtlas.atlasHash,
                workerAtlasVersion = workerAtlas.version,
                activeLeases = leases,
                nodeBindings = bindings,
                modelView = GraderMethodologyModelView(modelRows),
                catalogHash = ""
            )
            val hash = computeCatalogHash(provisional)
            return provisional.copy(catalogHash = hash)
        }

        private fun handle(
            prefix: String,
            scopeTag: String,
            kind: String,
            exactId: String
        ): String = "${prefix}_${scopeTag}_${
            hashFramed(listOf(kind, exactId)).take(HANDLE_ID_HEX_LENGTH)
        }"

        private fun scopeTag(
            runId: String,
            graderGroupId: String,
            round: Int,
            graderAtlasHash: String
        ): String = hashFramed(
            listOf(runId, graderGroupId, round.toString(), graderAtlasHash)
        ).take(HANDLE_SCOPE_HEX_LENGTH)

        private fun requireNoHandleCollisions(bindings: List<GraderMethodologyNodeBinding>) {
            val graderHandles = bindings.map(GraderMethodologyNodeBinding::graderNodeHandle)
            require(graderHandles.size == graderHandles.toSet().size) {
                "Deterministic grader-node handle collision"
            }
            val workerBindings = bindings.flatMap(GraderMethodologyNodeBinding::workerBindings)
            val conflictingWorkerHandles = workerBindings
                .groupBy(GraderMethodologyWorkerBinding::workerNodeHandle)
                .values
                .any { sameHandle -> sameHandle.map { it.workerNodeId }.toSet().size > 1 }
            require(!conflictingWorkerHandles) {
                "Deterministic worker-node handle collision"
            }
        }

        private fun computeCatalogHash(catalog: GraderMethodologyBindingCatalog): String =
            hashFramed(
                buildList {
                    add(catalog.runId)
                    add(catalog.graderGroupId)
                    add(catalog.round.toString())
                    add(catalog.graderAtlasHash)
                    add(catalog.workerAtlasHash)
                    add(catalog.workerAtlasVersion.toString())
                    catalog.activeLeases.sortedBy { it.graderNodeId.value }.forEach { lease ->
                        add("lease")
                        add(lease.graderNodeId.value)
                        add(lease.leaseId)
                        add(lease.leaseHash)
                    }
                    catalog.nodeBindings.sortedBy { it.graderNodeHandle }.forEach { row ->
                        add("grader")
                        add(row.graderNodeHandle)
                        add(row.graderNodeId.value)
                        row.workerBindings.sortedBy { it.workerNodeHandle }.forEach { worker ->
                            add("worker")
                            add(worker.workerNodeHandle)
                            add(worker.workerNodeId.value)
                        }
                        row.receiverObligationRefs.sorted().forEach {
                            add("receiver")
                            add(it)
                        }
                    }
                    catalog.modelView.rows.sortedBy { it.graderNodeHandle }.forEach { row ->
                        add("model-grader")
                        add(row.graderNodeHandle)
                        add(row.kind.name)
                        add(row.title)
                        add(row.evaluationQuestion)
                        add(row.status.name)
                        row.receiverObligationRefs.sorted().forEach {
                            add("model-receiver")
                            add(it)
                        }
                        row.workerNodes.sortedBy {
                            it.workerNodeHandle
                        }.forEach { worker ->
                            add("model-worker")
                            add(worker.workerNodeHandle)
                            add(worker.kind.name)
                            add(worker.layer.toString())
                            add(worker.stageIndex.toString())
                            add(worker.receiverWhyRef)
                            add(worker.limitationStatement)
                            add(worker.capabilityTarget)
                            add(worker.atomicQuestion)
                            add(worker.proofContract.method.name)
                            worker.proofContract.acceptanceCriteria.sorted().forEach {
                                add("proof-criterion")
                                add(it)
                            }
                            worker.proofContract.requiredEvidenceKinds
                                .map { it.name }
                                .sorted()
                                .forEach {
                                    add("proof-evidence-kind")
                                    add(it)
                                }
                            worker.proofContract.suggestedSourceDomains.sorted().forEach {
                                add("proof-source-domain")
                                add(it)
                            }
                            worker.proofContract.executableTestIds.sorted().forEach {
                                add("proof-test")
                                add(it)
                            }
                            add(worker.proofContract.failureIsUseful.toString())
                            worker.deliverableContract.acceptedArtifactTypes
                                .map { it.name }
                                .sorted()
                                .forEach {
                                    add("deliverable-artifact-type")
                                    add(it)
                                }
                            worker.deliverableContract.requiredFields.sorted().forEach {
                                add("deliverable-field")
                                add(it)
                            }
                            worker.deliverableContract.mediaTypes.sorted().forEach {
                                add("deliverable-media-type")
                                add(it)
                            }
                            add(worker.deliverableContract.schemaJson.orEmpty())
                            add(worker.deliverableContract.finalDeliverableRelationship)
                        }
                    }
                }
            )

        private fun hashFramed(values: List<String>): String =
            AtlasHashing.hashText(
                values.joinToString(separator = "") { value ->
                    "${value.encodeToByteArray().size}:$value"
                }
            )
    }
}

/** Recoverable result of exact handle resolution. */
@Serializable
sealed interface GraderMethodologyBindingResolution {
    /**
     * Successful one-way projection.
     *
     * @param draft Unchanged exact-ID methodology draft.
     */
    @Serializable
    data class Resolved(val draft: GraderMethodologyDraft) : GraderMethodologyBindingResolution

    /**
     * Rejected wire draft that may be repaired by the model.
     *
     * @param errors Complete deterministic binding errors.
     */
    @Serializable
    data class Rejected(
        val errors: List<GraderMethodologyBindingError>
    ) : GraderMethodologyBindingResolution
}

/** Machine-readable model-wire binding failure category. */
@Serializable
enum class GraderMethodologyBindingErrorCode {
    INVALID_CATALOG,
    EMPTY_HANDLE,
    FOREIGN_HANDLE,
    UNKNOWN_GRADER_HANDLE,
    UNKNOWN_WORKER_HANDLE,
    WORKER_HANDLE_OUT_OF_ROW,
    EMPTY_WORKER_COVERAGE,
    NON_ATOMIC_WORKER_COVERAGE,
    INCOMPLETE_WORKER_COVERAGE,
    EMPTY_RECEIVER_COVERAGE,
    RECEIVER_REF_OUT_OF_ROW
}

/**
 * One deterministic, recoverable handle-resolution error.
 *
 * @param code Machine-readable category.
 * @param path Exact wire-contract path.
 * @param rejectedValue Rejected handle or receiver reference.
 * @param message Redacted repair guidance containing no exact IDs.
 */
@Serializable
data class GraderMethodologyBindingError(
    val code: GraderMethodologyBindingErrorCode,
    val path: String,
    val rejectedValue: String,
    val message: String
)

private fun bindingError(
    code: GraderMethodologyBindingErrorCode,
    path: String,
    rejectedValue: String,
    message: String
): GraderMethodologyBindingError =
    GraderMethodologyBindingError(code, path, rejectedValue, message)
