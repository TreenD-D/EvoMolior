package com.achulkov.evomolior.core.contract

import kotlinx.serialization.Serializable

/** Whether one mechanism is required for the current run objective. */
@Serializable
enum class MechanismRequirement {
    REQUIRED,
    OPTIONAL_VALUE_ADD,
    NOT_APPLICABLE,
}

/** Objective-scoped activation requirements for major runtime mechanisms. */
@Serializable
data class MechanismActivationContract(
    val toolAuthoring: MechanismRequirement,
    val independentToolReuse: MechanismRequirement,
    val simulation: MechanismRequirement,
    val outlierAmendment: MechanismRequirement,
    val crossGroupLeak: MechanismRequirement,
    val nestedGroup: MechanismRequirement,
    val claimLedger: MechanismRequirement,
    val externalGrounding: MechanismRequirement,
) {
    /** Returns whether every required mechanism has an explicit disposition. */
    fun hasExplicitRequirements(): Boolean = listOf(
        toolAuthoring,
        independentToolReuse,
        simulation,
        outlierAmendment,
        crossGroupLeak,
        nestedGroup,
        claimLedger,
        externalGrounding,
    ).all { it in MechanismRequirement.entries }
}
