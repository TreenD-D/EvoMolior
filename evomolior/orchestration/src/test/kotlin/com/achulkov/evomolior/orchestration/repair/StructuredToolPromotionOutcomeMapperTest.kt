package com.achulkov.evomolior.orchestration.repair

import com.achulkov.evomolior.core.repair.ToolPromotionStatus
import com.achulkov.evomolior.core.repair.ToolRepairCode
import com.achulkov.evomolior.core.repair.ToolRepairDisclosureClass
import com.achulkov.evomolior.orchestration.lifecycle.ToolPromotionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class StructuredToolPromotionOutcomeMapperTest {
    @Test
    fun `public rejection uses typed code and never classifies reason prose`() {
        val outcome = StructuredToolPromotionOutcomeMapper.fromResult(
            result = ToolPromotionResult.Rejected(
                reason = "misleading schema and noncanonical prose",
                publicRepairCode = ToolRepairCode.SCOPE_MISMATCH
            ),
            opportunity = powerToEnergyCommitment().opportunity
        )

        assertEquals(ToolRepairCode.SCOPE_MISMATCH, outcome.repairCode)
        assertEquals(
            ToolRepairDisclosureClass.PUBLIC_EXACT,
            outcome.disclosureClass
        )
    }

    @Test
    fun `untyped public rejection cannot synthesize a repair code from reason prose`() {
        val outcome = StructuredToolPromotionOutcomeMapper.fromResult(
            result = ToolPromotionResult.Rejected(
                reason = "schema noncanonical output mismatch"
            ),
            opportunity = powerToEnergyCommitment().opportunity
        )

        assertEquals(ToolPromotionStatus.REJECTED_PUBLIC_AUTHORITY, outcome.status)
        assertEquals(null, outcome.repairCode)
        assertEquals(null, outcome.remediationKey)
    }

    @Test
    fun `private exact mismatch maps through public quantity semantics only`() {
        val outcome = StructuredToolPromotionOutcomeMapper.privateRejection(
            failure = PrivatePromotionFailure.EXPECTED_OUTPUT_MISMATCH,
            opportunity = powerToEnergyCommitment().opportunity
        )

        assertEquals(ToolPromotionStatus.REJECTED_PRIVATE_AUTHORITY, outcome.status)
        assertEquals(
            ToolRepairDisclosureClass.SAFE_SEMANTIC_CATEGORY,
            outcome.disclosureClass
        )
        assertEquals(ToolRepairCode.POWER_TO_ENERGY_UNIT_CONVERSION, outcome.repairCode)
        assertEquals("energy_kwh", outcome.affectedPublicField)
        assertFalse(outcome.toString().contains("sealed-power-energy"))
        assertFalse(outcome.toString().contains("expected"))
        assertFalse(outcome.toString().contains("actual"))
    }
}
