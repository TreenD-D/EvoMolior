package com.achulkov.evomolior.orchestration.repair

import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitment
import com.achulkov.evomolior.core.atlas.TypedField
import com.achulkov.evomolior.core.atlas.TypedFieldType
import com.achulkov.evomolior.core.repair.PublicToolFinding
import com.achulkov.evomolior.core.repair.ToolRepairCode
import com.achulkov.evomolior.core.repair.ToolRepairDiagnosticV1
import com.achulkov.evomolior.core.repair.ToolRepairDisclosureClass
import com.achulkov.evomolior.core.repair.ToolRepairSource
import com.achulkov.evomolior.core.repair.ToolSemanticReview

/** Stable reasons a typed semantic review may be rejected at the deterministic boundary. */
enum class ToolSemanticCompatibilityRejection {
    INVALID_OPPORTUNITY_AUTHORITY,
    PRIVATE_CODE_IN_PUBLIC_REVIEW,
    UNKNOWN_PUBLIC_FIELD,
    QUANTITY_METADATA_MISMATCH,
    CODE_INCOMPATIBLE_WITH_PUBLIC_CONTRACT
}

/** Deterministic result of validating typed Critic semantics against public contract authority. */
sealed interface ToolSemanticCompatibilityResult {
    /** The exact review is compatible with public authority. */
    data object Accepted : ToolSemanticCompatibilityResult

    /** The review attempted an unsupported or non-public semantic claim. */
    data class Rejected(val reason: ToolSemanticCompatibilityRejection) :
        ToolSemanticCompatibilityResult
}

/** Validates and compiles typed semantic reviews without inspecting arbitrary prose. */
class ToolSemanticCompatibilityValidator {
    /**
     * Validates [review] against exact public [commitment].
     *
     * @return Stable accepted/rejected result containing no private fixture material.
     */
    fun validate(
        review: ToolSemanticReview,
        commitment: ToolOpportunityCommitment
    ): ToolSemanticCompatibilityResult {
        if (!commitment.hasValidHash()) {
            return rejected(ToolSemanticCompatibilityRejection.INVALID_OPPORTUNITY_AUTHORITY)
        }
        if (ToolRepairCode.PRIVATE_TEST_FAILED_UNCLASSIFIED in review.safeRepairCodes) {
            return rejected(ToolSemanticCompatibilityRejection.PRIVATE_CODE_IN_PUBLIC_REVIEW)
        }
        val inputs = commitment.opportunity.requiredInputs.associateBy(TypedField::name)
        val outputs = commitment.opportunity.requiredOutputs.associateBy(TypedField::name)
        val fields = inputs + outputs
        review.publicFindings.forEach { finding ->
            val field = finding.affectedPublicField?.let(fields::get)
            if (finding.affectedPublicField != null && field == null) {
                return rejected(ToolSemanticCompatibilityRejection.UNKNOWN_PUBLIC_FIELD)
            }
            if (
                finding.quantitySpec != null &&
                field?.quantitySpec != finding.quantitySpec
            ) {
                return rejected(ToolSemanticCompatibilityRejection.QUANTITY_METADATA_MISMATCH)
            }
            if (!isCompatible(finding, field, inputs, outputs)) {
                return rejected(
                    ToolSemanticCompatibilityRejection.CODE_INCOMPATIBLE_WITH_PUBLIC_CONTRACT
                )
            }
        }
        return ToolSemanticCompatibilityResult.Accepted
    }

    /**
     * Compiles one delayed safe diagnostic per public finding after successful validation.
     *
     * @param review Valid typed review.
     * @param commitment Exact public opportunity authority.
     * @param candidateHash Workstream B candidate hash.
     * @param groupId Owning group.
     * @param createdRound Review round.
     * @return Deterministically ordered, self-hashed diagnostics visible next round.
     */
    fun compileDiagnostics(
        review: ToolSemanticReview,
        commitment: ToolOpportunityCommitment,
        candidateHash: String,
        groupId: String,
        createdRound: Int
    ): List<ToolRepairDiagnosticV1> {
        require(validate(review, commitment) == ToolSemanticCompatibilityResult.Accepted) {
            "Cannot compile incompatible typed semantic review"
        }
        require(candidateHash.matches(Regex("[0-9a-f]{64}")) && groupId.isNotBlank()) {
            "Typed semantic diagnostics require candidate and group authority"
        }
        return review.publicFindings
            .sortedWith(compareBy<PublicToolFinding> { it.code.name }
                .thenBy { it.affectedPublicField.orEmpty() })
            .mapIndexed { index, finding ->
                val diagnosticId = "repair-${AtlasHashing.hashText(
                    listOf(
                        commitment.runId,
                        groupId,
                        commitment.commitmentHash,
                        candidateHash,
                        createdRound.toString(),
                        index.toString(),
                        finding.code.name,
                        finding.affectedPublicField.orEmpty()
                    ).joinToString("|")
                ).take(24)}"
                ToolRepairDiagnosticV1(
                    diagnosticId = diagnosticId,
                    runId = commitment.runId,
                    groupId = groupId,
                    nodeId = commitment.nodeId.value,
                    opportunityId = commitment.opportunity.opportunityId,
                    opportunityHash = commitment.commitmentHash,
                    candidateHash = candidateHash,
                    source = ToolRepairSource.CRITIC_SEMANTIC_REVIEW,
                    disclosureClass = ToolRepairDisclosureClass.SAFE_SEMANTIC_CATEGORY,
                    code = finding.code,
                    createdRound = createdRound,
                    visibleFromRound = createdRound + 1,
                    affectedPublicField = finding.affectedPublicField,
                    publicQuantitySpec = finding.quantitySpec,
                    rationaleCommitment = review.privateRationaleCommitment
                ).withComputedHash()
            }
    }

    private fun isCompatible(
        finding: PublicToolFinding,
        field: TypedField?,
        inputs: Map<String, TypedField>,
        outputs: Map<String, TypedField>
    ): Boolean = when (finding.code) {
        ToolRepairCode.CONTRACT_SCHEMA_MISMATCH,
        ToolRepairCode.MISSING_REQUIRED_CONTRACT_FIELD -> true

        ToolRepairCode.NON_CANONICAL_NUMBER,
        ToolRepairCode.DIMENSIONAL_MISMATCH,
        ToolRepairCode.SIGN_CONVENTION_ERROR,
        ToolRepairCode.BOUNDARY_CONDITION_ERROR ->
            field == null || field.isNumeric

        ToolRepairCode.PUBLIC_TEST_EXPECTED_OUTPUT_MISMATCH,
        ToolRepairCode.OUTPUT_SEMANTICS_MISMATCH ->
            field == null || field.name in outputs

        ToolRepairCode.INPUT_NOT_INFLUENCING_OUTPUT ->
            field != null && field.name in inputs

        ToolRepairCode.POWER_TO_ENERGY_UNIT_CONVERSION ->
            field != null &&
                field.name in outputs &&
                field.quantitySpec?.unitId in setOf("Wh", "kWh") &&
                inputs.values.any { it.quantitySpec?.unitId in setOf("W", "kW") } &&
                inputs.values.any { it.quantitySpec?.unitId in setOf("s", "min", "h") }

        ToolRepairCode.RATE_VERSUS_TOTAL_CONFUSION,
        ToolRepairCode.AGGREGATION_WINDOW_MISMATCH,
        ToolRepairCode.TEMPORAL_INDEX_ALIGNMENT_ERROR ->
            field == null || field.quantitySpec != null

        ToolRepairCode.PERCENT_NORMALIZATION_ERROR ->
            field != null && field.quantitySpec?.unitId in setOf("%", "1")

        ToolRepairCode.SCOPE_MISMATCH -> true
        ToolRepairCode.PRIVATE_TEST_FAILED_UNCLASSIFIED -> false
    }

    private val TypedField.isNumeric: Boolean
        get() = type == TypedFieldType.INTEGER ||
            type == TypedFieldType.NUMBER ||
            (
                type == TypedFieldType.ARRAY &&
                    itemType in setOf(TypedFieldType.INTEGER, TypedFieldType.NUMBER)
                )

    private fun rejected(
        reason: ToolSemanticCompatibilityRejection
    ): ToolSemanticCompatibilityResult.Rejected =
        ToolSemanticCompatibilityResult.Rejected(reason)
}
