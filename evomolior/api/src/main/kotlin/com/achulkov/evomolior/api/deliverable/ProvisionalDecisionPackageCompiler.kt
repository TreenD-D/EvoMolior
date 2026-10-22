@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.api.deliverable

import com.achulkov.evomolior.core.deliverable.ComponentAuthorityClass
import com.achulkov.evomolior.core.deliverable.DeliverableAssemblyRequest
import com.achulkov.evomolior.core.deliverable.DeliverableBlueprint
import com.achulkov.evomolior.core.deliverable.DeliverableComponentKind
import com.achulkov.evomolior.core.deliverable.DeliverableComponentProvenance
import com.achulkov.evomolior.core.deliverable.DeliverableContentOrigin
import com.achulkov.evomolior.core.deliverable.DeliverableEvidenceDisclosure
import com.achulkov.evomolior.core.deliverable.DeliverableEvidenceLink
import com.achulkov.evomolior.core.deliverable.DeliverableHashing
import com.achulkov.evomolior.core.deliverable.DeliverableKeyValue
import com.achulkov.evomolior.core.deliverable.DeliverableSemanticBlock
import com.achulkov.evomolior.core.deliverable.DeterministicDeliverableAssembler
import com.achulkov.evomolior.core.deliverable.FailedAcceptanceGate
import com.achulkov.evomolior.core.deliverable.InterimDeliverableSnapshot
import com.achulkov.evomolior.core.deliverable.PROVISIONAL_DECISION_OR_USE_PREFIX
import com.achulkov.evomolior.core.deliverable.RecoverySourceCandidate
import com.achulkov.evomolior.core.deliverable.RecoverySourceSet
import com.achulkov.evomolior.core.deliverable.ReceiverDecisionPackage
import com.achulkov.evomolior.core.deliverable.RunOutcomeClass
import com.achulkov.evomolior.core.deliverable.SuppliedDeliverableComponent
import kotlin.time.Instant

/**
 * Exact, already-sealed inputs for a non-accepted receiver package.
 *
 * [latestSnapshots] contains exactly one latest valid snapshot for every [eligibleGroupIds] member.
 * This boundary intentionally accepts no prose, score, candidate, hidden test, or writer output.
 *
 * @param blueprint Exact sealed receiver blueprint.
 * @param runId Owning run identity.
 * @param eligibleGroupIds Every group eligible to contribute a recovery source.
 * @param latestSnapshots One latest sealed snapshot for each eligible group.
 * @param generatedAt Frozen caller-supplied package timestamp.
 */
data class ProvisionalDecisionPackageRequest(
    val blueprint: DeliverableBlueprint,
    val runId: String,
    val eligibleGroupIds: Set<String>,
    val latestSnapshots: List<InterimDeliverableSnapshot>,
    val generatedAt: Instant,
)

/**
 * Deterministic non-accepted package and its neutral recovery source authority.
 *
 * @param decisionPackage Receiver-safe provisional semantic package.
 * @param recoverySourceSet Every eligible source, ordered only by group identity.
 */
data class ProvisionalDecisionPackageCompilation(
    val decisionPackage: ReceiverDecisionPackage,
    val recoverySourceSet: RecoverySourceSet,
)

/**
 * Compiles receiver-safe structural recovery output without selecting a winner or authoring claims.
 *
 * Snapshot content is reduced to public hashes and counts. In particular, group identities never
 * enter receiver narrative, and snapshot evidence references are not re-published.
 */
object ProvisionalDecisionPackageCompiler {
    /**
     * Compiles one provisional package and one complete neutral recovery source set.
     *
     * @param request Exact sealed recovery inputs.
     * @return A valid provisional package with no accepted authority.
     */
    fun compile(request: ProvisionalDecisionPackageRequest): ProvisionalDecisionPackageCompilation {
        require(request.runId.isSafeStructuralIdentifier()) {
            "Provisional package run ID is unsafe"
        }
        require(request.eligibleGroupIds.isNotEmpty()) {
            "Provisional package requires at least one eligible group"
        }
        require(request.eligibleGroupIds.all(String::isSafeStructuralIdentifier)) {
            "Eligible group IDs must be receiver-safe structural identifiers"
        }
        requireReceiverSafeBlueprint(request.blueprint)

        val snapshotsByGroup = request.latestSnapshots.associateBy(InterimDeliverableSnapshot::groupId)
        require(snapshotsByGroup.size == request.latestSnapshots.size) {
            "Provisional package requires one latest snapshot per eligible group"
        }
        require(snapshotsByGroup.keys == request.eligibleGroupIds) {
            "Provisional package snapshots must cover exactly the eligible groups"
        }
        require(request.latestSnapshots.all { snapshot ->
            snapshot.hasValidHash() &&
                snapshot.runId == request.runId &&
                snapshot.blueprintHash == request.blueprint.blueprintHash &&
                snapshot.groupId.isSafeStructuralIdentifier() &&
                snapshot.failedGateIds.all(String::isSafeStructuralIdentifier) &&
                snapshot.acceptedComponentIds.all(String::isSafeStructuralIdentifier) &&
                snapshot.provisionalComponentIds.all(String::isSafeStructuralIdentifier) &&
                snapshot.withheldComponentIds.all(String::isSafeStructuralIdentifier) &&
                snapshot.missingAuthorityComponentIds.all(String::isSafeStructuralIdentifier)
        }) {
            "Provisional package snapshots are invalid, foreign, or contain unsafe structural data"
        }
        val orderedSources = request.latestSnapshots.sortedBy(InterimDeliverableSnapshot::groupId)
        val sourceSet = RecoverySourceSet.seal(
            runId = request.runId,
            candidates = orderedSources.map { snapshot ->
                // Snapshot evidence IDs can be operational identifiers; retain only their count.
                RecoverySourceCandidate(
                    groupId = snapshot.groupId,
                    sourceSnapshotHash = snapshot.snapshotHash,
                    publicEvidenceRefs = emptySet(),
                )
            },
        )
        val sourceRows = orderedSources.map { snapshot ->
            SourceRow(
                label = sourceSet.sourceLabels.getValue(snapshot.groupId),
                snapshot = snapshot,
            )
        }
        val evidence = sourceRows.map { source ->
            DeliverableEvidenceLink(
                evidenceId = "source-snapshot-${source.label.lowercase()}",
                evidenceHash = source.snapshot.snapshotHash,
                authorityClass = ComponentAuthorityClass.PROVISIONAL,
                disclosure = DeliverableEvidenceDisclosure.PUBLIC_COMMITTED,
                publicSummary = "Public committed non-accepted source snapshot ${source.label}.",
            )
        }
        val sourceEvidenceIds = evidence.mapTo(linkedSetOf(), DeliverableEvidenceLink::evidenceId)
        val components = request.blueprint.componentSpecs.map { spec ->
            suppliedComponent(
                kind = spec.kind,
                componentId = spec.componentId,
                minimumAuthority = spec.minimumAuthority,
                sourceRows = sourceRows,
                sourceEvidenceIds = sourceEvidenceIds,
            )
        }
        val knownFailures = knownFailures(request.blueprint, sourceRows)
        val missingReasons = request.blueprint.componentSpecs
            .filter { spec ->
                spec.minimumAuthority == ComponentAuthorityClass.ACCEPTED ||
                    spec.kind == DeliverableComponentKind.RECOMMENDATION
            }
            .associate { spec ->
                spec.componentId to
                    "Accepted winner and accepted writer authority remain unresolved; " +
                    "this package withholds the component."
            }

        val packageValue = DeterministicDeliverableAssembler.assemble(
            DeliverableAssemblyRequest(
                blueprint = request.blueprint,
                packageId = "provisional-${packageIdentity(request.runId, orderedSources)}",
                runId = request.runId,
                outcomeClass = RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE,
                suppliedComponents = components,
                evidenceLedger = evidence,
                knownFailures = knownFailures,
                missingAuthorityReasons = missingReasons,
                missingAuthorityRequirements = request.blueprint.componentSpecs
                    .filter { it.kind == DeliverableComponentKind.RECOMMENDATION }
                    .associate { it.componentId to ComponentAuthorityClass.ACCEPTED },
                decisionOrUseOverride = provisionalDecisionOrUse(),
                generatedAt = request.generatedAt,
            ),
        )
        return ProvisionalDecisionPackageCompilation(packageValue, sourceSet)
    }

    private fun suppliedComponent(
        kind: DeliverableComponentKind,
        componentId: String,
        minimumAuthority: ComponentAuthorityClass,
        sourceRows: List<SourceRow>,
        sourceEvidenceIds: Set<String>,
    ): SuppliedDeliverableComponent {
        if (minimumAuthority == ComponentAuthorityClass.ACCEPTED) {
            return SuppliedDeliverableComponent(
                componentId = componentId,
                kind = kind,
                title = "Withheld pending accepted authority",
                authorityClass = ComponentAuthorityClass.WITHHELD,
                blocks = emptyList(),
                provenance = DeliverableComponentProvenance(),
            )
        }
        return SuppliedDeliverableComponent(
            componentId = componentId,
            kind = kind,
            title = provisionalTitle(kind),
            authorityClass = ComponentAuthorityClass.PROVISIONAL,
            blocks = structuralBlocks(kind, sourceRows),
            provenance = DeliverableComponentProvenance(evidenceIds = sourceEvidenceIds),
            uncertainty = "No accepted winner, selected source, or accepted writer authority exists.",
        )
    }

    private fun structuralBlocks(
        kind: DeliverableComponentKind,
        sourceRows: List<SourceRow>,
    ): List<DeliverableSemanticBlock> = when (kind) {
        DeliverableComponentKind.EXECUTIVE_SUMMARY -> listOf(
            narrative(
                "This is a provisional non-accepted decision package. " +
                    "No accepted winner or accepted recommendation exists."
            )
        )
        DeliverableComponentKind.DECISION_MATRIX -> listOf(
            DeliverableSemanticBlock.Table(
                columns = listOf(
                    "Non-accepted source",
                    "Snapshot hash",
                    "Accepted component count",
                    "Provisional component count",
                    "Withheld component count",
                    "Evidence reference count",
                    "Failed gate count",
                ),
                rows = sourceRows.map { source ->
                    listOf(
                        source.label,
                        source.snapshot.snapshotHash,
                        source.snapshot.acceptedComponentIds.size.toString(),
                        source.snapshot.provisionalComponentIds.size.toString(),
                        source.snapshot.withheldComponentIds.size.toString(),
                        source.snapshot.evidenceRefs.size.toString(),
                        source.snapshot.failedGateIds.size.toString(),
                    )
                },
            )
        )
        DeliverableComponentKind.EVIDENCE_LEDGER -> listOf(
            keyValues(sourceRows) { source ->
                source.label to source.snapshot.snapshotHash
            }
        )
        DeliverableComponentKind.ASSUMPTIONS -> listOf(
            keyValues(
                listOf(
                    "Authority status" to
                        "Accepted winner and writer authority are unresolved.",
                    "Source handling" to
                        "All eligible sources are included without selection or ranking.",
                )
            )
        )
        DeliverableComponentKind.UNCERTAINTIES,
        DeliverableComponentKind.RISK_REGISTER,
        DeliverableComponentKind.LIMITATIONS_AND_NEXT_EVIDENCE -> listOf(
            keyValues(
                listOf(
                    "Unresolved authority" to
                        "No accepted winner, selected source, or writer authority exists.",
                    "Withheld recommendation" to
                        "A receiver recommendation is withheld pending accepted authority.",
                )
            )
        )
        DeliverableComponentKind.RECOMMENDATION -> listOf(
            narrative(
                "No accepted recommendation is available. This provisional content records " +
                    "the unresolved authority state and is not a final recommendation."
            ),
            keyValues(
                listOf(
                    "Required next evidence" to
                        "Resolve accepted winner authority and accepted writer authority.",
                    "Receiver use" to
                        "Inspect preserved evidence and failed gates; do not act as if this " +
                            "were an accepted recommendation.",
                )
            ),
        )
        else -> listOf(
            keyValues(
                listOf(
                    "Authority status" to "Provisional structural content only.",
                    "Source count" to sourceRows.size.toString(),
                )
            )
        )
    }

    private fun knownFailures(
        blueprint: DeliverableBlueprint,
        sourceRows: List<SourceRow>,
    ): List<FailedAcceptanceGate> {
        val sourceCommitment = sourceRows.joinToString("|") { it.snapshot.snapshotHash }
        val fixed = listOf(
            "accepted-winner-authority-unresolved" to
                "No accepted winner authority was resolved.",
            "accepted-writer-authority-unresolved" to
                "No accepted writer authority was resolved.",
        )
        val snapshotGates = sourceRows
            .flatMap { it.snapshot.failedGateIds }
            .distinct()
            .sorted()
            .also { gateIds ->
                require(gateIds.none { gateId -> fixed.any { it.first == gateId } }) {
                    "Snapshot gate IDs cannot shadow provisional authority gates"
                }
            }
            .map { gateId ->
                gateId to "A sealed source snapshot reports gate $gateId."
            }
        return (fixed + snapshotGates).map { (gateId, message) ->
            FailedAcceptanceGate(
                gateId = gateId,
                code = if (fixed.any { it.first == gateId }) {
                    "ACCEPTED_AUTHORITY_UNRESOLVED"
                } else {
                    "SNAPSHOT_GATE_UNRESOLVED"
                },
                publicMessage = message,
                authorityHash = DeliverableHashing.hashText(
                    "$gateId|${blueprint.blueprintHash}|$sourceCommitment"
                ),
            )
        }
    }

    private fun packageIdentity(
        runId: String,
        snapshots: List<InterimDeliverableSnapshot>,
    ): String = DeliverableHashing.hashText(
        buildString {
            append(runId)
            snapshots.forEach {
                append('|')
                append(it.groupId)
                append('|')
                append(it.snapshotHash)
            }
        }
    ).take(24)

    private fun narrative(text: String) = DeliverableSemanticBlock.Narrative(
        text = text,
        origin = DeliverableContentOrigin.DETERMINISTIC_SUPPLIED,
    )

    private fun keyValues(entries: List<Pair<String, String>>) =
        DeliverableSemanticBlock.KeyValue(
            entries = entries.map { (key, value) -> DeliverableKeyValue(key, value) }
        )

    private fun keyValues(
        sourceRows: List<SourceRow>,
        value: (SourceRow) -> Pair<String, String>,
    ) = keyValues(sourceRows.map(value))

    private fun provisionalTitle(kind: DeliverableComponentKind): String = when (kind) {
        DeliverableComponentKind.EXECUTIVE_SUMMARY -> "Provisional non-accepted executive summary"
        DeliverableComponentKind.DECISION_MATRIX -> "Neutral non-accepted source comparison"
        DeliverableComponentKind.EVIDENCE_LEDGER -> "Snapshot-bound evidence ledger"
        DeliverableComponentKind.ASSUMPTIONS -> "Provisional authority assumptions"
        DeliverableComponentKind.UNCERTAINTIES -> "Unresolved authority"
        DeliverableComponentKind.RISK_REGISTER -> "Authority risk register"
        DeliverableComponentKind.LIMITATIONS_AND_NEXT_EVIDENCE ->
            "Limitations and required next authority"
        else -> "Provisional structural component"
    }

    private fun provisionalDecisionOrUse(): String =
        "$PROVISIONAL_DECISION_OR_USE_PREFIX inspect preserved evidence and failed gates; " +
            "do not treat this as an accepted recommendation or final artifact."

    private fun requireReceiverSafeBlueprint(blueprint: DeliverableBlueprint) {
        require(blueprint.hasValidHash()) { "Provisional package blueprint hash is invalid" }
        require(
            blueprint.blueprintId.isSafeStructuralIdentifier() &&
                blueprint.audience.isReceiverSafeText() &&
                blueprint.decisionOrUse.isReceiverSafeText() &&
                blueprint.componentSpecs.all { spec ->
                    spec.componentId.isSafeStructuralIdentifier() &&
                        spec.sourceNodeIds.all(String::isSafeStructuralIdentifier) &&
                        spec.rendererHints.all { (key, value) ->
                            key.isSafeStructuralIdentifier() && value.isReceiverSafeText()
                        }
                }
        ) { "Provisional package blueprint contains unsafe receiver content" }
    }

    private data class SourceRow(
        val label: String,
        val snapshot: InterimDeliverableSnapshot,
    )
}

private fun String.isSafeStructuralIdentifier(): Boolean =
    matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) &&
        !containsUnsafeMarker()

private fun String.isReceiverSafeText(): Boolean =
    isNotBlank() &&
        length <= 512 &&
        !contains("://") &&
        !contains('/') &&
        !contains('\\') &&
        !containsUnsafeMarker()

private fun String.containsUnsafeMarker(): Boolean {
    val normalized = lowercase()
    return listOf(
        "private",
        "secret",
        "critic",
        "rationale",
        "payload",
        "stack",
        "reasoning",
        "trace",
    ).any(normalized::contains)
}
