package com.achulkov.evomolior.core.deliverable

/** Fail-closed validation for deliverable blueprints and assembled packages. */
object DeliverableContractValidator {
    /**
     * Validates a blueprint and its attached hash.
     *
     * @param blueprint Blueprint to validate.
     */
    fun requireValidBlueprint(blueprint: DeliverableBlueprint) {
        require(blueprint.schemaVersion == DELIVERABLE_BLUEPRINT_SCHEMA_V1) {
            "Unsupported deliverable blueprint schema ${blueprint.schemaVersion}"
        }
        require(
            blueprint.blueprintId.isNotBlank() &&
                blueprint.receiverContractHash.isSha256() &&
                blueprint.audience.isNotBlank() &&
                blueprint.decisionOrUse.isNotBlank()
        ) { "Deliverable blueprint receiver fields are incomplete" }
        require(blueprint.componentSpecs.isNotEmpty()) {
            "Deliverable blueprint requires semantic components"
        }
        require(blueprint.componentSpecs.map { it.componentId }.distinct().size ==
            blueprint.componentSpecs.size) {
            "Deliverable blueprint component IDs must be unique"
        }
        require(blueprint.primaryFormat !in blueprint.companionFormats) {
            "Primary format must not be repeated as a companion format"
        }
        val formats = blueprint.companionFormats + blueprint.primaryFormat
        require(formats.any(DeliverableFormat::isHumanReadable)) {
            "Deliverable blueprint requires a human-readable format"
        }
        require(formats.any(DeliverableFormat::isMachineReadable)) {
            "Deliverable blueprint requires a machine-readable format"
        }
        require(blueprint.hasValidHash()) { "Deliverable blueprint hash is invalid" }
    }

    /**
     * Validates package authority, component coverage, evidence lineage, and all hashes.
     *
     * @param blueprint Source blueprint.
     * @param decisionPackage Package to validate.
     */
    fun requireValidPackage(
        blueprint: DeliverableBlueprint,
        decisionPackage: ReceiverDecisionPackage,
    ) {
        requireValidBlueprint(blueprint)
        require(decisionPackage.schemaVersion == RECEIVER_DECISION_PACKAGE_SCHEMA_V1) {
            "Unsupported receiver package schema ${decisionPackage.schemaVersion}"
        }
        require(decisionPackage.blueprintHash == blueprint.blueprintHash) {
            "Decision package does not reference the supplied blueprint"
        }
        require(decisionPackage.receiverContractHash == blueprint.receiverContractHash) {
            "Decision package receiver contract does not match the blueprint"
        }
        require(
            decisionPackage.packageId.isNotBlank() &&
                decisionPackage.runId.isNotBlank() &&
                decisionPackage.audience == blueprint.audience
        ) { "Decision package receiver identity is invalid" }
        require(
            decisionPackage.decisionOrUse == blueprint.decisionOrUse ||
                (
                    decisionPackage.outcomeClass ==
                        RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE &&
                        decisionPackage.decisionOrUse.isSafeProvisionalDecisionOrUse()
                )
        ) { "Decision package decision/use is not authorized by the blueprint" }
        require(decisionPackage.hasValidHashes()) {
            "Decision package or component hash is invalid"
        }

        val specs = blueprint.componentSpecs.associateBy { it.componentId }
        val componentIds = decisionPackage.components.map { it.componentId }
        require(componentIds.distinct().size == componentIds.size) {
            "Decision package component IDs must be unique"
        }
        require(componentIds.all { it in specs }) {
            "Decision package contains components absent from the blueprint"
        }
        require(
            componentIds == blueprint.componentSpecs
                .map { it.componentId }
                .filter { it in componentIds.toSet() }
        ) {
            "Decision package components must follow blueprint order"
        }
        require(blueprint.componentSpecs.filter { it.required }.all {
            it.componentId in componentIds
        }) {
            "Decision package is missing a required blueprint component"
        }
        val evidenceById = decisionPackage.evidenceLedger.associateBy { it.evidenceId }
        require(evidenceById.size == decisionPackage.evidenceLedger.size) {
            "Evidence IDs must be unique"
        }
        require(
            blueprint.evidencePolicy.allowProvisionalEvidence ||
                decisionPackage.evidenceLedger.none {
                    it.authorityClass == ComponentAuthorityClass.PROVISIONAL
                }
        ) { "Blueprint forbids provisional evidence" }
        require(
            blueprint.evidencePolicy.allowHashOnlyWithheldEvidence ||
                decisionPackage.evidenceLedger.none {
                    it.disclosure == DeliverableEvidenceDisclosure.HASH_ONLY_WITHHELD
                }
        ) { "Blueprint forbids hash-only withheld evidence" }
        decisionPackage.components.forEach { component ->
            val spec = requireNotNull(specs[component.componentId])
            require(component.kind == spec.kind) {
                "Component ${component.componentId} kind does not match the blueprint"
            }
            require(
                spec.sourceNodeIds.isEmpty() ||
                    component.provenance.sourceNodeIds.all { it in spec.sourceNodeIds }
            ) { "Component ${component.componentId} uses an unauthorized source node" }
            val evidence = component.provenance.evidenceIds.map { evidenceId ->
                requireNotNull(evidenceById[evidenceId]) {
                    "Component ${component.componentId} references unknown evidence $evidenceId"
                }
            }
            require(
                component.authorityClass == ComponentAuthorityClass.WITHHELD ||
                    evidence.none {
                        it.disclosure == DeliverableEvidenceDisclosure.HASH_ONLY_WITHHELD
                    }
            ) {
                "Receiver-visible component ${component.componentId} references withheld evidence"
            }
            require(
                component.authorityClass != ComponentAuthorityClass.ACCEPTED ||
                    evidence.all { it.authorityClass == ComponentAuthorityClass.ACCEPTED }
            ) { "Accepted component ${component.componentId} escalates evidence authority" }
            require(
                !blueprint.evidencePolicy.requireEvidenceForAcceptedComponents ||
                    component.authorityClass != ComponentAuthorityClass.ACCEPTED ||
                    evidence.isNotEmpty()
            ) { "Accepted component ${component.componentId} has no evidence" }
        }

        val missingByComponent = decisionPackage.missingAuthority
            .associateBy { it.componentId }
        require(missingByComponent.size == decisionPackage.missingAuthority.size) {
            "Missing-authority records must be unique per component"
        }
        blueprint.componentSpecs.forEach { spec ->
            val component = decisionPackage.components
                .firstOrNull { it.componentId == spec.componentId }
            if (component == null) {
                require(!spec.required) {
                    "Required component ${spec.componentId} is missing"
                }
                require(spec.componentId !in missingByComponent) {
                    "Absent optional component ${spec.componentId} cannot carry missing authority"
                }
                return@forEach
            }
            val missing = missingByComponent[spec.componentId]
            val satisfiesMinimum = component.authorityClass.satisfies(spec.minimumAuthority)
            if (missing == null) {
                require(satisfiesMinimum) {
                    "Missing-authority record for ${spec.componentId} is absent"
                }
            } else {
                require(
                    missing.observedAuthority == null ||
                        missing.observedAuthority == component.authorityClass
                ) {
                    "Missing-authority observed class for ${spec.componentId} is inconsistent"
                }
                require(!component.authorityClass.satisfies(missing.requiredAuthority)) {
                    "Missing-authority record for ${spec.componentId} is already satisfied"
                }
                require(
                    !satisfiesMinimum ||
                        missing.requiredAuthority.satisfies(spec.minimumAuthority)
                ) {
                    "Missing-authority requirement for ${spec.componentId} is weaker than blueprint"
                }
            }
            require(!spec.required || component.blocks.isNotEmpty() ||
                component.authorityClass == ComponentAuthorityClass.WITHHELD) {
                "Required component ${spec.componentId} is empty without being withheld"
            }
        }

        when (decisionPackage.outcomeClass) {
            RunOutcomeClass.ACCEPTED_DECISION_PACKAGE -> {
                require(decisionPackage.acceptedAuthority != null) {
                    "Accepted package requires winner/source/writer authority"
                }
                require(decisionPackage.knownFailures.isEmpty()) {
                    "Accepted package cannot contain failed acceptance gates"
                }
                require(decisionPackage.missingAuthority.isEmpty()) {
                    "Accepted package cannot contain missing authority"
                }
                require(decisionPackage.components.none {
                    it.authorityClass != ComponentAuthorityClass.ACCEPTED
                }) { "Accepted package cannot contain provisional or withheld components" }
            }
            RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE -> {
                require(blueprint.recoveryPolicy.allowProvisionalPackage) {
                    "Blueprint forbids provisional recovery output"
                }
                require(decisionPackage.acceptedAuthority == null) {
                    "Provisional package cannot carry accepted winner/source/writer authority"
                }
                require(
                    !blueprint.recoveryPolicy.requireKnownFailures ||
                        decisionPackage.knownFailures.isNotEmpty()
                ) { "Provisional package must preserve known acceptance failures" }
                require(
                    !blueprint.recoveryPolicy.requireMissingAuthority ||
                        decisionPackage.missingAuthority.isNotEmpty()
                ) { "Provisional package must identify missing authority" }
            }
            RunOutcomeClass.STARTUP_DIAGNOSTIC_PACKAGE -> {
                require(decisionPackage.acceptedAuthority == null) {
                    "Startup diagnostic cannot carry accepted authority"
                }
                require(decisionPackage.components.all {
                    it.authorityClass == ComponentAuthorityClass.WITHHELD
                }) { "Startup diagnostic cannot carry receiver decision claims" }
            }
        }
    }
}
