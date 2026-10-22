package com.achulkov.evomolior.core.contract

import kotlin.test.Test
import kotlin.test.assertTrue

class MechanismActivationContractsTest {
    @Test
    fun `objective contract distinguishes required optional and inapplicable mechanisms`() {
        val contract = MechanismActivationContract(
            toolAuthoring = MechanismRequirement.OPTIONAL_VALUE_ADD,
            independentToolReuse = MechanismRequirement.NOT_APPLICABLE,
            simulation = MechanismRequirement.NOT_APPLICABLE,
            outlierAmendment = MechanismRequirement.OPTIONAL_VALUE_ADD,
            crossGroupLeak = MechanismRequirement.NOT_APPLICABLE,
            nestedGroup = MechanismRequirement.NOT_APPLICABLE,
            claimLedger = MechanismRequirement.REQUIRED,
            externalGrounding = MechanismRequirement.REQUIRED,
        )

        assertTrue(contract.hasExplicitRequirements())
    }
}
