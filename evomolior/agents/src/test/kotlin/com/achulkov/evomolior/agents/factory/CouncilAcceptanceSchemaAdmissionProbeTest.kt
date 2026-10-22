package com.achulkov.evomolior.agents.factory

import com.achulkov.evomolior.core.atlas.PhysicalDimension
import com.achulkov.evomolior.core.atlas.ToolOpportunityType
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.council.QuantitativeToolFieldLocation
import com.achulkov.evomolior.core.council.QuantitativeToolFieldObligation
import com.achulkov.evomolior.core.council.QuantitativeToolFieldShape
import com.achulkov.evomolior.core.council.QuantitativeToolObligation
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.repair.AggregationSemantics
import com.achulkov.evomolior.core.repair.QuantitySpec
import com.achulkov.evomolior.core.repair.TimeBasis
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

    @Test
    fun `admission hashes the actual quantitative Task schema authority`() {
        val profileOnly = CouncilAcceptanceSchemaAdmissionProbe.validate(
            AcceptanceProfile.APPENDIX_B_FULL
        )
        val quantitative = CouncilAcceptanceSchemaAdmissionProbe.validate(
            AcceptanceProfile.APPENDIX_B_FULL,
            QuantitativeToolObligation(
                horizonTimeBasis = TimeBasis.SECOND,
                horizonIntervalCount = 1,
                allowedOpportunityTypes = setOf(ToolOpportunityType.CALCULATION),
                requiredOpportunityId = "bounded_count",
                requiredFields = listOf(
                    QuantitativeToolFieldObligation(
                        roleId = "count",
                        fieldName = "count",
                        location = QuantitativeToolFieldLocation.INPUT,
                        shape = QuantitativeToolFieldShape.SCALAR,
                        quantitySpec = QuantitySpec(
                            dimension = PhysicalDimension(),
                            unitId = "1",
                            scaleToSi = 1.0,
                            aggregation = AggregationSemantics.INSTANTANEOUS
                        )
                    )
                )
            )
        )

        assertTrue(
            profileOnly.schemaSha256ByRole.getValue(Role.CouncilTaskAnatomist.name) !=
                quantitative.schemaSha256ByRole.getValue(Role.CouncilTaskAnatomist.name)
        )
        Role.entries.filter(Role::isCouncilRole)
            .filterNot {
                it == Role.CouncilTaskAnatomist ||
                    it == Role.CouncilSynthesis
            }
            .forEach { role ->
                assertEquals(
                    profileOnly.schemaSha256ByRole.getValue(role.name),
                    quantitative.schemaSha256ByRole.getValue(role.name)
                )
            }
    }
}
