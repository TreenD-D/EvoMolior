package com.achulkov.evomolior.core.deliverable

import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.ReceiverContract

/**
 * Compiles the exact receiver-oriented deliverable blueprint sealed by the Genesis Council.
 *
 * The compiler lives in `core` so Council compilation and runtime publication share one semantic
 * authority instead of independently recreating package policy.
 */
object ReceiverDeliverableBlueprintCompiler {
    /**
     * Compiles the immutable blueprint for [receiverContract].
     *
     * @param receiverContract Exact Council-authored receiver authority.
     * @return Self-hashed receiver deliverable blueprint.
     */
    fun compile(receiverContract: ReceiverContract): DeliverableBlueprint {
        val receiverHash = AtlasHashing.hashReceiverContract(receiverContract)
        val primaryFormat = receiverContract.finalDeliverableContract.mediaType.toFormat()
        val companionFormat = if (primaryFormat.isHumanReadable) {
            DeliverableFormat.JSON
        } else {
            DeliverableFormat.MARKDOWN
        }
        return DeliverableBlueprint(
            blueprintId = "deliverable-blueprint-${receiverHash.take(24)}",
            receiverContractHash = receiverHash,
            audience = receiverContract.receiverDescription,
            decisionOrUse = receiverContract.decisionOrUse,
            primaryFormat = primaryFormat,
            companionFormats = setOf(companionFormat),
            componentSpecs = COMPONENT_SPECS,
        ).withComputedHash()
    }

    private fun String.toFormat(): DeliverableFormat = when {
        contains("html", ignoreCase = true) -> DeliverableFormat.HTML5
        contains("json", ignoreCase = true) -> DeliverableFormat.JSON
        contains("csv", ignoreCase = true) -> DeliverableFormat.CSV_BUNDLE
        else -> DeliverableFormat.MARKDOWN
    }

    private val COMPONENT_SPECS = listOf(
        component("executive-summary", DeliverableComponentKind.EXECUTIVE_SUMMARY, true),
        component("decision-matrix", DeliverableComponentKind.DECISION_MATRIX, true),
        component(
            "recommendation",
            DeliverableComponentKind.RECOMMENDATION,
            true,
            ComponentAuthorityClass.ACCEPTED,
        ),
        component("evidence-ledger", DeliverableComponentKind.EVIDENCE_LEDGER, true),
        component("assumptions", DeliverableComponentKind.ASSUMPTIONS, true),
        component("uncertainties", DeliverableComponentKind.UNCERTAINTIES, true),
        component("risk-register", DeliverableComponentKind.RISK_REGISTER, true),
        component("implementation-plan", DeliverableComponentKind.IMPLEMENTATION_PLAN, false),
        component("action-checklist", DeliverableComponentKind.ACTION_CHECKLIST, false),
        component(
            "tool-and-simulation-results",
            DeliverableComponentKind.TOOL_AND_SIMULATION_RESULTS,
            false,
        ),
        component("alternative-views", DeliverableComponentKind.ALTERNATIVE_VIEWS, false),
        component(
            "limitations-and-next-evidence",
            DeliverableComponentKind.LIMITATIONS_AND_NEXT_EVIDENCE,
            true,
        ),
        component("technical-appendix", DeliverableComponentKind.TECHNICAL_APPENDIX, false),
    )

    private fun component(
        id: String,
        kind: DeliverableComponentKind,
        required: Boolean,
        minimumAuthority: ComponentAuthorityClass = ComponentAuthorityClass.PROVISIONAL,
    ): DeliverableComponentSpec = DeliverableComponentSpec(
        componentId = id,
        kind = kind,
        required = required,
        minimumAuthority = minimumAuthority,
    )
}
