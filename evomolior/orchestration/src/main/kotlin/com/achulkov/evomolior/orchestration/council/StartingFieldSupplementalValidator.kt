package com.achulkov.evomolior.orchestration.council

import com.achulkov.evomolior.core.council.ContractPath
import com.achulkov.evomolior.core.council.ExecutionEnvelope
import com.achulkov.evomolior.core.council.FieldCompileError
import com.achulkov.evomolior.core.council.FieldCompileErrorCode
import com.achulkov.evomolior.core.model.Role

internal object StartingFieldSupplementalValidator {
    fun validateSpecialists(
        envelope: ExecutionEnvelope,
        outputs: CouncilSpecialistBundle
    ): List<FieldCompileError> = buildList {
        val receiver = outputs.taskAnatomist.receiverContract
        if (receiver.receiverKey.isBlank() ||
            receiver.receiverDescription.isBlank() ||
            receiver.situation.isBlank() ||
            receiver.decisionOrUse.isBlank() ||
            receiver.whyNeeded.isBlank() ||
            receiver.successOutcomes.isEmpty() ||
            receiver.successOutcomes.any(String::isBlank) ||
            receiver.unacceptableOutcomes.isEmpty() ||
            receiver.unacceptableOutcomes.any(String::isBlank) ||
            receiver.constraints.any(String::isBlank)
        ) {
            add(error("taskAnatomist.receiverContract", "Receiver contract fields and outcomes must be explicit", Role.CouncilTaskAnatomist))
        }
        val evidence = receiver.evidenceProfile
        val unavailableDomains = evidence.requiredSourceDomains -
            envelope.sourceAccessPolicy.allowedDomains
        if (evidence.minimumIndependentSources < 1 ||
            evidence.acceptedEvidenceKinds.isEmpty() ||
            evidence.maximumEvidenceAgeDays?.let { it < 0 } == true ||
            unavailableDomains.isNotEmpty() ||
            (!envelope.sourceAccessPolicy.networkAccessEnabled &&
                evidence.requiredSourceDomains.isNotEmpty())
        ) {
            add(error("taskAnatomist.receiverContract.evidenceProfile", "Evidence requirements are invalid or unavailable in the source-access envelope", Role.CouncilTaskAnatomist))
        }
        if (outputs.taskAnatomist.receiverObligations.isEmpty() ||
            outputs.taskAnatomist.receiverObligations.any { it.key.isBlank() || it.value.isBlank() }
        ) {
            add(error("taskAnatomist.receiverObligations", "Receiver obligations must use non-blank semantic keys and values", Role.CouncilTaskAnatomist))
        }
        val prompts = outputs.promptArchitect.rolePrompts
        if (prompts.isEmpty() ||
            prompts.any { (role, prompt) -> role.isCouncilRole || prompt.isBlank() }
        ) {
            add(error("promptArchitect.rolePrompts", "Runtime prompts must be non-blank and cannot target Council roles", Role.CouncilPromptArchitect))
        }
        val boundaryRoles = outputs.promptArchitect.informationBoundaries.mapTo(mutableSetOf()) { it.role }
        val unboundedWorkerRoles = prompts.keys.filter { it.isCivilisationalRole } - boundaryRoles
        if (unboundedWorkerRoles.isNotEmpty()) {
            add(error("promptArchitect.informationBoundaries", "Every prompted civilisational role requires an information boundary", Role.CouncilPromptArchitect))
        }
    }

    fun validateEnvelopeAuthority(
        envelope: ExecutionEnvelope
    ): List<FieldCompileError> = buildList {
        val output = envelope.outputSpec
        if (output.relativePath.isBlank() ||
            output.relativePath.startsWith("/") ||
            output.relativePath.split('/').any { it == ".." } ||
            output.mediaType.isBlank() ||
            output.requiredSections.any(String::isBlank) ||
            output.minimumEvidenceItems < 0
        ) {
            add(error("envelope.outputSpec", "Final output contract is unsafe or incomplete", Role.CouncilSynthesis))
        }
        val credits = envelope.perGenerationCreditPolicy
        if (!credits.baseCredits.isFinite() ||
            !credits.maximumCredits.isFinite() ||
            !credits.carryoverFraction.isFinite() ||
            envelope.cumulativeCostCap?.isFinite() == false
        ) {
            add(error("envelope.perGenerationCreditPolicy", "Envelope credit values must be finite", Role.CouncilSynthesis))
        }
        if (envelope.sandboxCapabilities.maximumToolRuntimeSeconds < 1 ||
            envelope.sandboxCapabilities.maximumWorkspaceBytes < 1L
        ) {
            add(error("envelope.sandboxCapabilities", "Sandbox bounds must be positive", Role.CouncilSynthesis))
        }
    }

    private fun error(path: String, message: String, role: Role) = FieldCompileError(
        code = FieldCompileErrorCode.ENVELOPE_VIOLATION,
        path = ContractPath(path.split('.')),
        message = message,
        responsibleCouncilRoles = setOf(role)
    )
}
