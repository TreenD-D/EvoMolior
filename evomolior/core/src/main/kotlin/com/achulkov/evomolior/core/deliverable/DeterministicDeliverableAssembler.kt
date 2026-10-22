@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.core.deliverable

import kotlin.time.Instant

/**
 * Complete caller-supplied input to deterministic semantic assembly.
 *
 * @param blueprint Sealed and hashed blueprint.
 * @param packageId Stable output package ID.
 * @param runId Owning run.
 * @param outcomeClass Requested outcome authority.
 * @param suppliedComponents Exact component semantics; no component prose is generated.
 * @param evidenceLedger Exact public or hash-only evidence.
 * @param knownFailures Exact known acceptance failures.
 * @param missingEvidenceByComponent Supplied evidence gaps by component.
 * @param missingAuthorityReasons Optional supplied public explanations.
 * @param acceptedAuthority Accepted winner/source/writer chain.
 * @param decisionOrUseOverride Explicit caveat for a provisional package.
 * @param missingAuthorityRequirements Stronger authority gaps supplied by recovery.
 * @param generatedAt Caller-supplied frozen timestamp.
 */
data class DeliverableAssemblyRequest(
    val blueprint: DeliverableBlueprint,
    val packageId: String,
    val runId: String,
    val outcomeClass: RunOutcomeClass,
    val suppliedComponents: List<SuppliedDeliverableComponent>,
    val evidenceLedger: List<DeliverableEvidenceLink>,
    val knownFailures: List<FailedAcceptanceGate>,
    val missingEvidenceByComponent: Map<String, Set<String>> = emptyMap(),
    val missingAuthorityReasons: Map<String, String> = emptyMap(),
    val acceptedAuthority: AcceptedDeliverableAuthority? = null,
    val decisionOrUseOverride: String? = null,
    val missingAuthorityRequirements: Map<String, ComponentAuthorityClass> = emptyMap(),
    val generatedAt: Instant,
)

/**
 * Assembles typed supplied evidence into blueprint order without synthesizing domain semantics.
 *
 * Missing components become structural `WITHHELD` placeholders. Their labels and reasons describe
 * only the contract gap; they do not infer a value, conclusion, recommendation, or test result.
 */
object DeterministicDeliverableAssembler {
    /**
     * Assembles and validates one receiver package.
     *
     * @param request Exact assembly request.
     * @return Hashed, validated semantic package.
     */
    fun assemble(request: DeliverableAssemblyRequest): ReceiverDecisionPackage {
        DeliverableContractValidator.requireValidBlueprint(request.blueprint)
        require(request.packageId.isNotBlank() && request.runId.isNotBlank()) {
            "Deliverable package and run IDs must not be blank"
        }
        val suppliedById = request.suppliedComponents.associateBy { it.componentId }
        require(suppliedById.size == request.suppliedComponents.size) {
            "Supplied component IDs must be unique"
        }
        val specIds = request.blueprint.componentSpecs.map { it.componentId }.toSet()
        require(suppliedById.keys.all { it in specIds }) {
            "Supplied components contain IDs absent from the blueprint"
        }
        require(request.missingEvidenceByComponent.keys.all { it in specIds }) {
            "Missing evidence references an unknown component"
        }
        require(request.missingAuthorityReasons.keys.all { it in specIds }) {
            "Missing-authority reasons reference an unknown component"
        }
        require(request.missingAuthorityRequirements.keys.all { it in specIds }) {
            "Missing-authority requirements reference an unknown component"
        }
        require(
            request.decisionOrUseOverride == null ||
                request.outcomeClass == RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE
        ) { "Decision/use overrides are limited to provisional packages" }
        require(
            request.decisionOrUseOverride == null ||
                request.decisionOrUseOverride.isSafeProvisionalDecisionOrUse()
        ) { "Provisional decision/use overrides must carry an explicit caveat" }
        request.missingAuthorityRequirements.forEach { (componentId, requiredAuthority) ->
            val minimumAuthority = request.blueprint.componentSpecs
                .single { it.componentId == componentId }
                .minimumAuthority
            require(requiredAuthority.satisfies(minimumAuthority)) {
                "Missing authority requirement for $componentId is weaker than the blueprint"
            }
        }

        val evidenceById = request.evidenceLedger.associateBy { it.evidenceId }
        require(evidenceById.size == request.evidenceLedger.size) {
            "Evidence IDs must be unique"
        }
        require(
            request.blueprint.evidencePolicy.allowProvisionalEvidence ||
                request.evidenceLedger.none {
                    it.authorityClass == ComponentAuthorityClass.PROVISIONAL
                }
        ) { "Blueprint forbids provisional evidence" }
        require(
            request.blueprint.evidencePolicy.allowHashOnlyWithheldEvidence ||
                request.evidenceLedger.none {
                    it.disclosure == DeliverableEvidenceDisclosure.HASH_ONLY_WITHHELD
                }
        ) { "Blueprint forbids hash-only withheld evidence" }

        val missingAuthority = mutableListOf<MissingDeliverableAuthority>()
        val components = request.blueprint.componentSpecs.mapNotNull { spec ->
            val supplied = suppliedById[spec.componentId]
            if (supplied == null && !spec.required) return@mapNotNull null
            val component = if (supplied == null) {
                DeliverableComponent(
                    componentId = spec.componentId,
                    kind = spec.kind,
                    title = "Withheld: ${spec.componentId}",
                    authorityClass = ComponentAuthorityClass.WITHHELD,
                    blocks = emptyList(),
                    provenance = DeliverableComponentProvenance(),
                    uncertainty = null,
                )
            } else {
                require(supplied.kind == spec.kind) {
                    "Supplied component ${spec.componentId} kind does not match blueprint"
                }
                require(
                    spec.sourceNodeIds.isEmpty() ||
                        supplied.provenance.sourceNodeIds.all { it in spec.sourceNodeIds }
                ) { "Supplied component ${spec.componentId} uses an unauthorized source node" }
                val evidence = supplied.provenance.evidenceIds.map { evidenceId ->
                    requireNotNull(evidenceById[evidenceId]) {
                        "Supplied component ${spec.componentId} references unknown evidence $evidenceId"
                    }
                }
                require(
                    supplied.authorityClass == ComponentAuthorityClass.WITHHELD ||
                        evidence.none {
                            it.disclosure == DeliverableEvidenceDisclosure.HASH_ONLY_WITHHELD
                        }
                ) {
                    "Receiver-visible component ${spec.componentId} references withheld evidence"
                }
                require(
                    supplied.authorityClass != ComponentAuthorityClass.ACCEPTED ||
                        evidence.all { it.authorityClass == ComponentAuthorityClass.ACCEPTED }
                ) { "Accepted component ${spec.componentId} escalates supplied evidence" }
                DeliverableComponent(
                    componentId = supplied.componentId,
                    kind = supplied.kind,
                    title = supplied.title,
                    authorityClass = supplied.authorityClass,
                    blocks = supplied.blocks,
                    provenance = supplied.provenance,
                    uncertainty = supplied.uncertainty,
                )
            }.withComputedHash()

            val requiredAuthority = request.missingAuthorityRequirements[spec.componentId]
                ?: spec.minimumAuthority
            if (!component.authorityClass.satisfies(requiredAuthority)) {
                missingAuthority += MissingDeliverableAuthority(
                    componentId = spec.componentId,
                    requiredAuthority = requiredAuthority,
                    observedAuthority = supplied?.authorityClass,
                    missingEvidenceIds =
                        request.missingEvidenceByComponent[spec.componentId].orEmpty(),
                    publicReason = request.missingAuthorityReasons[spec.componentId]
                        ?: if (supplied == null) {
                            "No semantic component was supplied for the required blueprint component."
                        } else {
                            "Supplied component authority does not satisfy the blueprint requirement."
                        },
                )
            }
            component
        }

        val decisionPackage = ReceiverDecisionPackage(
            packageId = request.packageId,
            runId = request.runId,
            outcomeClass = request.outcomeClass,
            receiverContractHash = request.blueprint.receiverContractHash,
            blueprintHash = request.blueprint.blueprintHash,
            audience = request.blueprint.audience,
            decisionOrUse = request.decisionOrUseOverride ?: request.blueprint.decisionOrUse,
            components = components,
            evidenceLedger = request.evidenceLedger.sortedBy { it.evidenceId },
            knownFailures = request.knownFailures.sortedBy { it.gateId },
            missingAuthority = missingAuthority,
            acceptedAuthority = request.acceptedAuthority,
            generatedAt = request.generatedAt,
        ).withComputedHash()

        DeliverableContractValidator.requireValidPackage(request.blueprint, decisionPackage)
        return decisionPackage
    }
}

/** Deterministically compiles sealed-round progress from a validated semantic package. */
object InterimDeliverableSnapshotAssembler {
    /**
     * Creates a sorted, hashed snapshot for [groupId] and [round].
     *
     * @param decisionPackage Source package.
     * @param round Sealed round.
     * @param groupId Group whose control plane owns the snapshot.
     * @return Deterministic interim snapshot.
     */
    fun assemble(
        decisionPackage: ReceiverDecisionPackage,
        round: Int,
        groupId: String,
    ): InterimDeliverableSnapshot {
        require(decisionPackage.hasValidHashes()) {
            "Cannot snapshot an invalid decision package"
        }
        return InterimDeliverableSnapshot(
            runId = decisionPackage.runId,
            round = round,
            groupId = groupId,
            blueprintHash = decisionPackage.blueprintHash,
            acceptedComponentIds = decisionPackage.components
                .filter { it.authorityClass == ComponentAuthorityClass.ACCEPTED }
                .map { it.componentId }
                .sorted(),
            provisionalComponentIds = decisionPackage.components
                .filter { it.authorityClass == ComponentAuthorityClass.PROVISIONAL }
                .map { it.componentId }
                .sorted(),
            withheldComponentIds = decisionPackage.components
                .filter { it.authorityClass == ComponentAuthorityClass.WITHHELD }
                .map { it.componentId }
                .sorted(),
            failedGateIds = decisionPackage.knownFailures.map { it.gateId }.sorted(),
            evidenceRefs = decisionPackage.evidenceLedger.map { it.evidenceId }.sorted(),
            missingAuthorityComponentIds = decisionPackage.missingAuthority
                .map { it.componentId }
                .sorted(),
        ).withComputedHash()
    }
}
