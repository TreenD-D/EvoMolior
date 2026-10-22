package com.achulkov.evomolior.orchestration.repair

import com.achulkov.evomolior.core.atlas.ToolOpportunity
import com.achulkov.evomolior.core.atlas.TypedField
import com.achulkov.evomolior.core.repair.ToolPromotionStatus
import com.achulkov.evomolior.core.repair.ToolRepairCode
import com.achulkov.evomolior.core.repair.ToolRepairDisclosureClass
import com.achulkov.evomolior.core.repair.ToolRepairRemediationDictionary
import com.achulkov.evomolior.core.repair.ToolRepairSource
import com.achulkov.evomolior.orchestration.lifecycle.ToolPromotionResult
import com.achulkov.evomolior.orchestration.lifecycle.ToolPromotionRejectionAuthority

/** Closed execution failure classes from private promotion authority. */
enum class PrivatePromotionFailure {
    TIMEOUT,
    OUTPUT_LIMIT,
    NON_ZERO_EXIT,
    INVALID_OUTPUT_SCHEMA,
    EXPECTED_OUTPUT_MISMATCH
}

/** Structured promotion outcome that cannot contain fixture IDs, paths, prose, or values. */
data class StructuredToolPromotionOutcome(
    val status: ToolPromotionStatus,
    val rejectionReasonCode: String?,
    val source: ToolRepairSource?,
    val disclosureClass: ToolRepairDisclosureClass?,
    val repairCode: ToolRepairCode?,
    val affectedPublicField: String?,
    val remediationKey: String?
) {
    init {
        require(
            repairCode == null ||
                remediationKey == ToolRepairRemediationDictionary.keyFor(repairCode)
        ) { "Structured promotion outcomes require checked-in remediation keys" }
    }
}

/** Maps public and private promotion results into bounded typed outcomes. */
object StructuredToolPromotionOutcomeMapper {
    /** Maps the existing promotion result into a prose-free public/private outcome. */
    fun fromResult(
        result: ToolPromotionResult,
        opportunity: ToolOpportunity
    ): StructuredToolPromotionOutcome = when (result) {
        is ToolPromotionResult.Promoted -> PROMOTED
        is ToolPromotionResult.Rejected ->
            if (result.authority == ToolPromotionRejectionAuthority.PRIVATE_PROMOTION) {
                privateRejection(
                    failure = requireNotNull(result.privateFailure),
                    opportunity = opportunity
                )
            } else {
                result.publicRepairCode
                    ?.let { publicRejection(it, result.reason) }
                    ?: UNCLASSIFIED_PUBLIC_REJECTION.copy(
                        rejectionReasonCode = result.reason
                    )
            }
    }

    /** Maps one public preflight rejection without retaining raw failure text. */
    fun publicRejection(
        code: ToolRepairCode,
        rejectionReasonCode: String? = null
    ): StructuredToolPromotionOutcome =
        StructuredToolPromotionOutcome(
            status = ToolPromotionStatus.REJECTED_PUBLIC_AUTHORITY,
            rejectionReasonCode = rejectionReasonCode,
            source = ToolRepairSource.PUBLIC_PREFLIGHT,
            disclosureClass = ToolRepairDisclosureClass.PUBLIC_EXACT,
            repairCode = code,
            affectedPublicField = null,
            remediationKey = ToolRepairRemediationDictionary.keyFor(code)
        )

    /**
     * Maps a private failure to a safe category using only public quantity metadata.
     *
     * Exact private fixtures, expected outputs, actual outputs, and numeric differences are not
     * accepted as inputs, so they cannot leak through this boundary.
     */
    fun privateRejection(
        failure: PrivatePromotionFailure,
        opportunity: ToolOpportunity
    ): StructuredToolPromotionOutcome {
        val powerToEnergyOutput = opportunity.requiredOutputs.singleOrNull { output ->
            output.quantitySpec?.unitId in setOf("Wh", "kWh") &&
                opportunity.requiredInputs.any {
                    it.quantitySpec?.unitId in setOf("W", "kW")
                } &&
                opportunity.requiredInputs.any {
                    it.quantitySpec?.unitId in setOf("s", "min", "h")
                }
        }
        val mapped = when {
            failure == PrivatePromotionFailure.EXPECTED_OUTPUT_MISMATCH &&
                powerToEnergyOutput != null ->
                ToolRepairCode.POWER_TO_ENERGY_UNIT_CONVERSION to powerToEnergyOutput

            failure == PrivatePromotionFailure.INVALID_OUTPUT_SCHEMA ->
                ToolRepairCode.OUTPUT_SEMANTICS_MISMATCH to null

            else -> ToolRepairCode.PRIVATE_TEST_FAILED_UNCLASSIFIED to null
        }
        return StructuredToolPromotionOutcome(
            status = ToolPromotionStatus.REJECTED_PRIVATE_AUTHORITY,
            rejectionReasonCode = "tool_promotion_private_test_failed",
            source = ToolRepairSource.PRIVATE_PROMOTION_AUTHORITY,
            disclosureClass = if (
                mapped.first == ToolRepairCode.PRIVATE_TEST_FAILED_UNCLASSIFIED
            ) {
                ToolRepairDisclosureClass.PRIVATE_FAILURE_ONLY
            } else {
                ToolRepairDisclosureClass.SAFE_SEMANTIC_CATEGORY
            },
            repairCode = mapped.first,
            affectedPublicField = mapped.second?.name,
            remediationKey = ToolRepairRemediationDictionary.keyFor(mapped.first)
        )
    }

    /** Typed successful promotion outcome. */
    val PROMOTED = StructuredToolPromotionOutcome(
        status = ToolPromotionStatus.PROMOTED,
        rejectionReasonCode = null,
        source = null,
        disclosureClass = null,
        repairCode = null,
        affectedPublicField = null,
        remediationKey = null
    )

    /** Public or infrastructure rejection with no deterministic repair classification. */
    val UNCLASSIFIED_PUBLIC_REJECTION = StructuredToolPromotionOutcome(
        status = ToolPromotionStatus.REJECTED_PUBLIC_AUTHORITY,
        rejectionReasonCode = null,
        source = null,
        disclosureClass = null,
        repairCode = null,
        affectedPublicField = null,
        remediationKey = null
    )

}
