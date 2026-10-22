@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.api.deliverable

import com.achulkov.evomolior.api.service.StartupRejectionArtifact
import com.achulkov.evomolior.api.service.StartupRejectionPhase
import com.achulkov.evomolior.core.deliverable.ComponentAuthorityClass
import com.achulkov.evomolior.core.deliverable.DeliverableAssemblyRequest
import com.achulkov.evomolior.core.deliverable.DeliverableBlueprint
import com.achulkov.evomolior.core.deliverable.DeliverableComponentKind
import com.achulkov.evomolior.core.deliverable.DeliverableComponentProvenance
import com.achulkov.evomolior.core.deliverable.DeliverableComponentSpec
import com.achulkov.evomolior.core.deliverable.DeliverableFormat
import com.achulkov.evomolior.core.deliverable.DeliverableHashing
import com.achulkov.evomolior.core.deliverable.DeterministicDeliverableAssembler
import com.achulkov.evomolior.core.deliverable.RunOutcomeClass
import com.achulkov.evomolior.core.deliverable.SuppliedDeliverableComponent
import com.achulkov.evomolior.core.outcome.StartupDiagnosticIssue
import com.achulkov.evomolior.core.outcome.StartupDiagnosticPackageV1
import com.achulkov.evomolior.core.outcome.StartupFailurePhase
import kotlin.time.Instant

/** Complete deterministic startup-diagnostic output derived from one frozen rejection record. */
data class StartupDiagnosticOutcomeCompilation(
    val blueprint: DeliverableBlueprint,
    val decisionPackage: com.achulkov.evomolior.core.deliverable.ReceiverDecisionPackage,
    val diagnosticPackage: StartupDiagnosticPackageV1,
)

/** Compiles a small engineering-only startup package without receiver decision claims. */
object StartupDiagnosticOutcomeCompiler {
    /** Compiles one startup outcome from the already-sanitized rejection authority. */
    fun compile(
        rejection: StartupRejectionArtifact,
        generatedAt: Instant,
    ): StartupDiagnosticOutcomeCompilation {
        val distributionHash = requireNotNull(rejection.distributionManifestHash) {
            "Startup diagnostic requires admission/distribution authority"
        }
        val rejectionHash = DeliverableHashing.hashText(
            listOf(
                rejection.runId,
                rejection.phase.name,
                rejection.reasons.joinToString(",") { it.name },
                rejection.candidateFieldHash.orEmpty(),
                rejection.testSuiteHash.orEmpty(),
                rejection.declaredTestCount.toString(),
                rejection.compiledTestCount.toString(),
                rejection.declaredWorkerGroupCount.toString(),
                rejection.compiledWorkerGroupCount.toString(),
                rejection.declaredGraderGroupCount.toString(),
                rejection.compiledGraderGroupCount.toString(),
            ).joinToString("|")
        )
        val issues = rejection.reasons
            .distinct()
            .sortedBy { it.name }
            .map { reason ->
                StartupDiagnosticIssue(
                    code = reason.name,
                    publicMessage = publicMessage(rejection.phase),
                    remediation = remediation(rejection.phase),
                    authorityHash = DeliverableHashing.hashText(
                        "$rejectionHash|${reason.name}"
                    ),
                )
            }
        val diagnostic = StartupDiagnosticPackageV1(
            runId = rejection.runId,
            failurePhase = rejection.phase.toFailurePhase(),
            admissionDistributionHash = distributionHash,
            startingFieldHash = rejection.candidateFieldHash,
            candidateAuthorityHashes = listOfNotNull(
                rejection.candidateFieldHash,
                rejection.testSuiteHash,
            ).distinct().sorted(),
            declaredCardinality = rejection.declaredTestCount,
            compiledCardinality = rejection.compiledTestCount,
            preflightCardinality = 0,
            issues = issues,
            generatedAt = generatedAt,
        ).withComputedHash()
        val blueprint = DeliverableBlueprint(
            blueprintId = "startup-diagnostic-${rejectionHash.take(24)}",
            receiverContractHash = DeliverableHashing.hashText(
                "startup-diagnostic-contract|${rejection.runId}|$distributionHash"
            ),
            audience = "Operator",
            decisionOrUse = "Repair startup admission and retry",
            primaryFormat = DeliverableFormat.JSON,
            companionFormats = setOf(DeliverableFormat.MARKDOWN),
            componentSpecs = listOf(
                DeliverableComponentSpec(
                    componentId = "startup-diagnostic",
                    kind = DeliverableComponentKind.LIMITATIONS_AND_NEXT_EVIDENCE,
                    required = true,
                    minimumAuthority = ComponentAuthorityClass.WITHHELD,
                )
            ),
        ).withComputedHash()
        val decisionPackage = DeterministicDeliverableAssembler.assemble(
            DeliverableAssemblyRequest(
                blueprint = blueprint,
                packageId = "startup-diagnostic-${rejectionHash.take(24)}",
                runId = rejection.runId,
                outcomeClass = RunOutcomeClass.STARTUP_DIAGNOSTIC_PACKAGE,
                suppliedComponents = listOf(
                    SuppliedDeliverableComponent(
                        componentId = "startup-diagnostic",
                        kind = DeliverableComponentKind.LIMITATIONS_AND_NEXT_EVIDENCE,
                        title = "Startup diagnostic only",
                        authorityClass = ComponentAuthorityClass.WITHHELD,
                        blocks = emptyList(),
                        provenance = DeliverableComponentProvenance(),
                    )
                ),
                evidenceLedger = emptyList(),
                knownFailures = emptyList(),
                generatedAt = generatedAt,
            )
        )
        return StartupDiagnosticOutcomeCompilation(
            blueprint = blueprint,
            decisionPackage = decisionPackage,
            diagnosticPackage = diagnostic,
        )
    }

    private fun StartupRejectionPhase.toFailurePhase(): StartupFailurePhase = when (this) {
        StartupRejectionPhase.ADMISSION -> StartupFailurePhase.ADMISSION
        StartupRejectionPhase.COUNCIL_COMPILE -> StartupFailurePhase.COMPILATION
        StartupRejectionPhase.TEST_EXECUTABILITY_PREFLIGHT -> StartupFailurePhase.PREFLIGHT
        StartupRejectionPhase.PRE_ACTIVATION -> StartupFailurePhase.PREFLIGHT
    }

    private fun publicMessage(phase: StartupRejectionPhase): String = when (phase) {
        StartupRejectionPhase.ADMISSION ->
            "Startup admission did not satisfy the public run contract."
        StartupRejectionPhase.COUNCIL_COMPILE ->
            "The Council result did not compile into an admissible run field."
        StartupRejectionPhase.TEST_EXECUTABILITY_PREFLIGHT ->
            "The sealed public test contract was not executable."
        StartupRejectionPhase.PRE_ACTIVATION ->
            "A pre-activation invariant prevented worker startup."
    }

    private fun remediation(phase: StartupRejectionPhase): String = when (phase) {
        StartupRejectionPhase.ADMISSION ->
            "Correct the run admission inputs and submit a fresh run."
        StartupRejectionPhase.COUNCIL_COMPILE ->
            "Correct the declared Council field contract and retry compilation."
        StartupRejectionPhase.TEST_EXECUTABILITY_PREFLIGHT ->
            "Correct the sealed test definitions and rerun preflight."
        StartupRejectionPhase.PRE_ACTIVATION ->
            "Correct the reported pre-activation invariant and submit a fresh run."
    }
}
