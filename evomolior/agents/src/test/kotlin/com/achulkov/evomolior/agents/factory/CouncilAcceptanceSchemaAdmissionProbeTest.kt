package com.achulkov.evomolior.agents.factory

import com.achulkov.evomolior.core.config.AcceptanceProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CouncilAcceptanceSchemaAdmissionProbeTest {
    @Test
    fun `smoke and full specialist schemas independently compile before provider use`() {
        listOf(
            AcceptanceProfile.RUNTIME_USABILITY_SMOKE,
            AcceptanceProfile.APPENDIX_B_FULL
        ).forEach { profile ->
            val evidence = CouncilAcceptanceSchemaAdmissionProbe.validate(profile)
            assertEquals(profile, evidence.acceptanceProfile)
            assertEquals(7, evidence.schemaSha256ByRole.size)
            assertTrue(evidence.schemaSha256ByRole.values.all { it.matches(Regex("[0-9a-f]{64}")) })
        }
    }

    @Test
    fun `normal profile cannot claim acceptance schema admission`() {
        assertFailsWith<IllegalArgumentException> {
            CouncilAcceptanceSchemaAdmissionProbe.validate(AcceptanceProfile.NORMAL)
        }
    }
}
