@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.api.deliverable

import com.achulkov.evomolior.api.service.GeneratedOutputFile
import com.achulkov.evomolior.core.deliverable.AcceptedDeliverableAuthority
import com.achulkov.evomolior.core.deliverable.ComponentAuthorityClass
import com.achulkov.evomolior.core.deliverable.DeliverableAssemblyRequest
import com.achulkov.evomolior.core.deliverable.DeliverableBlueprint
import com.achulkov.evomolior.core.deliverable.DeliverableComponentProvenance
import com.achulkov.evomolior.core.deliverable.DeliverableContentOrigin
import com.achulkov.evomolior.core.deliverable.DeliverableEvidenceDisclosure
import com.achulkov.evomolior.core.deliverable.DeliverableEvidenceLink
import com.achulkov.evomolior.core.deliverable.DeliverableHashing
import com.achulkov.evomolior.core.deliverable.DeliverableKeyValue
import com.achulkov.evomolior.core.deliverable.DeliverableSemanticBlock
import com.achulkov.evomolior.core.deliverable.DeterministicDeliverableAssembler
import com.achulkov.evomolior.core.deliverable.InterimDeliverableSnapshot
import com.achulkov.evomolior.core.deliverable.ReceiverDecisionPackage
import com.achulkov.evomolior.core.deliverable.RunOutcomeClass
import com.achulkov.evomolior.core.deliverable.SuppliedDeliverableComponent
import kotlin.time.Instant

/**
 * Exact persisted authorities required to publish one accepted receiver decision package.
 *
 * [runId] and [winnerGroupId] are independent expected identities so a valid but foreign final
 * snapshot or writer output cannot silently redefine the package owner.
 *
 * @param blueprint Exact persisted and self-hashed receiver blueprint.
 * @param runId Expected owning run identity.
 * @param winnerGroupId Expected final winner group identity.
 * @param finalWinnerSnapshot Exact persisted final snapshot for the winning group.
 * @param acceptedAuthority Exact accepted winner/source/writer authority chain.
 * @param generatedOutput Exact final writer output, including structural metadata and content.
 * @param generatedAt Frozen caller-supplied package timestamp.
 */
data class AcceptedDecisionPackageRequest(
    val blueprint: DeliverableBlueprint,
    val runId: String,
    val winnerGroupId: String,
    val finalWinnerSnapshot: InterimDeliverableSnapshot,
    val acceptedAuthority: AcceptedDeliverableAuthority,
    val generatedOutput: GeneratedOutputFile,
    val generatedAt: Instant,
)

/**
 * Compiles exact accepted writer output into a deterministic receiver package.
 *
 * The compiler does not interpret, summarize, split, or embellish writer content. Every blueprint
 * component receives the exact writer content plus deterministic public commitments. Acceptance
 * is rejected unless the persisted snapshot, writer metadata, and supplied authority form one
 * complete chain.
 */
object AcceptedDecisionPackageCompiler {
    /**
     * Compiles one accepted decision package from exact persisted authorities.
     *
     * @param request Exact accepted compilation inputs.
     * @return A hashed `ACCEPTED_DECISION_PACKAGE` with complete accepted evidence and provenance.
     */
    fun compile(request: AcceptedDecisionPackageRequest): ReceiverDecisionPackage {
        requireReceiverSafeIdentifier(request.runId, "Accepted package run ID")
        requireReceiverSafeIdentifier(request.winnerGroupId, "Accepted winner group ID")
        requireReceiverSafeBlueprint(request.blueprint)

        val snapshot = request.finalWinnerSnapshot
        require(
            snapshot.hasValidHash() &&
                snapshot.runId == request.runId &&
                snapshot.groupId == request.winnerGroupId &&
                snapshot.blueprintHash == request.blueprint.blueprintHash
        ) { "Accepted winner snapshot is invalid, foreign, or references another blueprint" }
        requireReceiverSafeSnapshot(snapshot)

        val requiredComponentIds = request.blueprint.componentSpecs
            .map { it.componentId }
            .sorted()
        require(
            snapshot.acceptedComponentIds == requiredComponentIds &&
                snapshot.provisionalComponentIds.isEmpty() &&
                snapshot.withheldComponentIds.isEmpty() &&
                snapshot.failedGateIds.isEmpty() &&
                snapshot.missingAuthorityComponentIds.isEmpty()
        ) { "Accepted winner snapshot must accept every blueprint component without open gates" }

        val output = request.generatedOutput
        requireValidWriterOutput(output, request.winnerGroupId, requiredComponentIds)
        val authority = request.acceptedAuthority
        requireReceiverSafeAuthority(authority)
        val selectedSourceIds = output.componentNodeIds.values.flatten().toSet()
        require(
            DeliverableHashing.constantTimeEquals(
                    authority.writerOutputHash,
                    output.contentHash,
                ) &&
                authority.writerAuthorityId == output.sourceAgentId &&
                authority.sourceAuthorityIds == selectedSourceIds
        ) { "Accepted winner/source/writer authority does not match the exact persisted inputs" }

        val snapshotEvidenceId = "accepted-final-winner-snapshot"
        val winnerEvidenceId = "accepted-winner-authority"
        val outputEvidenceId = "accepted-final-writer-output"
        val evidenceIds = linkedSetOf(snapshotEvidenceId, winnerEvidenceId, outputEvidenceId)
        val evidence = listOf(
            DeliverableEvidenceLink(
                evidenceId = snapshotEvidenceId,
                evidenceHash = snapshot.snapshotHash,
                authorityClass = ComponentAuthorityClass.ACCEPTED,
                disclosure = DeliverableEvidenceDisclosure.PUBLIC_COMMITTED,
                publicSummary = "Accepted final winner snapshot commitment.",
            ),
            DeliverableEvidenceLink(
                evidenceId = winnerEvidenceId,
                evidenceHash = authority.winnerAuthorityHash,
                authorityClass = ComponentAuthorityClass.ACCEPTED,
                disclosure = DeliverableEvidenceDisclosure.PUBLIC_COMMITTED,
                publicSummary = "Accepted winner authority commitment.",
                provenanceHashes = setOf(snapshot.snapshotHash),
            ),
            DeliverableEvidenceLink(
                evidenceId = outputEvidenceId,
                evidenceHash = output.contentHash,
                authorityClass = ComponentAuthorityClass.ACCEPTED,
                disclosure = DeliverableEvidenceDisclosure.PUBLIC_COMMITTED,
                publicSummary = "Accepted final writer output commitment.",
                provenanceHashes = setOf(authority.winnerAuthorityHash, snapshot.snapshotHash),
            ),
        )
        val components = request.blueprint.componentSpecs.map { spec ->
            SuppliedDeliverableComponent(
                componentId = spec.componentId,
                kind = spec.kind,
                title = "Accepted ${spec.kind.name.lowercase().replace('_', ' ')}",
                authorityClass = ComponentAuthorityClass.ACCEPTED,
                blocks = listOf(
                    DeliverableSemanticBlock.KeyValue(
                        entries = listOf(
                            DeliverableKeyValue("Writer output path", output.relativePath),
                            DeliverableKeyValue("Writer output media type", output.mediaType),
                            DeliverableKeyValue("Writer output hash", output.contentHash),
                            DeliverableKeyValue("Final winner snapshot hash", snapshot.snapshotHash),
                        )
                    ),
                    DeliverableSemanticBlock.Narrative(
                        text = output.content,
                        origin = DeliverableContentOrigin.MODEL_AUTHORED_SUPPLIED,
                    ),
                ),
                provenance = DeliverableComponentProvenance(
                    evidenceIds = evidenceIds,
                    sourceNodeIds = output.componentNodeIds.getValue(spec.componentId),
                ),
            )
        }

        return DeterministicDeliverableAssembler.assemble(
            DeliverableAssemblyRequest(
                blueprint = request.blueprint,
                packageId = "accepted-${packageIdentity(request)}",
                runId = request.runId,
                outcomeClass = RunOutcomeClass.ACCEPTED_DECISION_PACKAGE,
                suppliedComponents = components,
                evidenceLedger = evidence,
                knownFailures = emptyList(),
                acceptedAuthority = authority,
                generatedAt = request.generatedAt,
            )
        )
    }

    private fun requireValidWriterOutput(
        output: GeneratedOutputFile,
        winnerGroupId: String,
        requiredComponentIds: List<String>,
    ) {
        require(
            output.content.isNotBlank() &&
                DeliverableHashing.constantTimeEquals(
                    output.contentHash,
                    DeliverableHashing.hashText(output.content),
                )
        ) { "Accepted writer output content hash is invalid" }
        require(output.relativePath.isSafeRelativeOutputPath()) {
            "Accepted writer output path is unsafe"
        }
        require(output.mediaType.matches(Regex("[A-Za-z0-9][A-Za-z0-9.+-]*/[A-Za-z0-9][A-Za-z0-9.+-]*"))) {
            "Accepted writer output media type is unsafe"
        }
        require(output.sourceGroupId == winnerGroupId) {
            "Accepted writer output belongs to another winner group"
        }
        require(
            output.sourceAgentId?.isSafeAcceptedIdentifier() == true &&
                output.sourceSubmissionId?.isSafeAcceptedIdentifier() == true &&
                output.sourceRole?.name?.isSafeAcceptedIdentifier() == true &&
                output.componentLineageSubmissionId?.isSafeAcceptedIdentifier() == true &&
                output.componentLineageSubmittedAtMs != null &&
                output.componentLineageSubmittedAtMs >= 0
        ) { "Accepted writer output structural authority is incomplete or unsafe" }
        require(output.componentNodeIds.keys.sorted() == requiredComponentIds) {
            "Accepted writer output lineage must cover exactly the blueprint components"
        }
        require(
            output.componentNodeIds.values.flatten().isNotEmpty() &&
                output.componentNodeIds.all { (componentId, sourceIds) ->
                    componentId.isSafeAcceptedIdentifier() &&
                        sourceIds.all(String::isSafeAcceptedIdentifier)
                }
        ) { "Accepted writer output source lineage is empty or unsafe" }
        requireReceiverSafeText(output.content, "Accepted writer output content")
    }

    private fun requireReceiverSafeBlueprint(blueprint: DeliverableBlueprint) {
        require(blueprint.hasValidHash()) { "Accepted package blueprint hash is invalid" }
        requireReceiverSafeIdentifier(blueprint.blueprintId, "Accepted package blueprint ID")
        requireReceiverSafeText(blueprint.audience, "Accepted package audience")
        requireReceiverSafeText(blueprint.decisionOrUse, "Accepted package decision or use")
        blueprint.componentSpecs.forEach { spec ->
            requireReceiverSafeIdentifier(spec.componentId, "Accepted package component ID")
            spec.sourceNodeIds.forEach {
                requireReceiverSafeIdentifier(it, "Accepted package source node ID")
            }
            spec.rendererHints.forEach { (key, value) ->
                requireReceiverSafeIdentifier(key, "Accepted package renderer hint key")
                requireReceiverSafeText(value, "Accepted package renderer hint value")
            }
        }
    }

    private fun requireReceiverSafeSnapshot(snapshot: InterimDeliverableSnapshot) {
        requireReceiverSafeIdentifier(snapshot.runId, "Accepted snapshot run ID")
        requireReceiverSafeIdentifier(snapshot.groupId, "Accepted snapshot group ID")
        listOf(
            snapshot.acceptedComponentIds,
            snapshot.provisionalComponentIds,
            snapshot.withheldComponentIds,
            snapshot.failedGateIds,
            snapshot.evidenceRefs,
            snapshot.missingAuthorityComponentIds,
        ).flatten().forEach {
            requireReceiverSafeIdentifier(it, "Accepted snapshot structural identifier")
        }
    }

    private fun requireReceiverSafeAuthority(authority: AcceptedDeliverableAuthority) {
        requireReceiverSafeIdentifier(authority.winnerAuthorityId, "Accepted winner authority ID")
        requireReceiverSafeIdentifier(authority.writerAuthorityId, "Accepted writer authority ID")
        authority.sourceAuthorityIds.forEach {
            requireReceiverSafeIdentifier(it, "Accepted source authority ID")
        }
    }

    private fun packageIdentity(request: AcceptedDecisionPackageRequest): String {
        val output = request.generatedOutput
        val values = buildList {
            add(request.runId)
            add(request.winnerGroupId)
            add(request.blueprint.blueprintHash)
            add(request.finalWinnerSnapshot.snapshotHash)
            add(request.acceptedAuthority.winnerAuthorityId)
            add(request.acceptedAuthority.winnerAuthorityHash)
            addAll(request.acceptedAuthority.sourceAuthorityIds.sorted())
            add(request.acceptedAuthority.writerAuthorityId)
            add(request.acceptedAuthority.writerOutputHash)
            add(output.relativePath)
            add(output.mediaType)
            add(output.contentHash)
            add(requireNotNull(output.sourceGroupId))
            add(requireNotNull(output.sourceAgentId))
            add(requireNotNull(output.sourceSubmissionId))
            add(output.sourceRole?.name.orEmpty())
            output.componentNodeIds.toSortedMap().forEach { (componentId, sourceIds) ->
                add(componentId)
                addAll(sourceIds.sorted())
            }
            add(requireNotNull(output.componentLineageSubmissionId))
            add(requireNotNull(output.componentLineageSubmittedAtMs).toString())
            add(request.generatedAt.toString())
        }
        val framed = values.joinToString(separator = "") { value -> "${value.length}:$value" }
        return DeliverableHashing.hashText(framed).take(24)
    }
}

private fun requireReceiverSafeIdentifier(value: String, label: String) {
    require(value.isSafeAcceptedIdentifier()) { "$label is unsafe" }
}

private fun requireReceiverSafeText(value: String, label: String) {
    require(
        value.isNotBlank() &&
            value.none { it.code < 0x20 && it != '\n' && it != '\r' && it != '\t' } &&
            !value.containsUnsafeAcceptedMarker()
    ) { "$label contains unsafe receiver content" }
}

private fun String.isSafeAcceptedIdentifier(): Boolean =
    matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) &&
        !containsUnsafeAcceptedMarker()

private fun String.isSafeRelativeOutputPath(): Boolean =
    isNotBlank() &&
        length <= 512 &&
        !startsWith('/') &&
        !startsWith('\\') &&
        !contains('\\') &&
        split('/').all { segment ->
            segment.isNotBlank() && segment != "." && segment != ".."
        } &&
        !containsUnsafeAcceptedMarker()

private fun String.containsUnsafeAcceptedMarker(): Boolean {
    val normalized = lowercase()
    return listOf(
        "private",
        "secret",
        "critic",
        "rationale",
        "payload",
        "stack trace",
        "reasoning",
        "trace",
        "chain-of-thought",
        "file://",
        "/users/",
        "/volumes/",
        "localhost",
        "127.0.0.1",
    ).any(normalized::contains)
}
