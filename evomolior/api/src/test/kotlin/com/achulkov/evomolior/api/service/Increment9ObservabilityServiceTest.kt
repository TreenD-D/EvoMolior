package com.achulkov.evomolior.api.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class Increment9ObservabilityServiceTest {
    @Test
    fun `zero denominator is unavailable rather than zero filled`() {
        val unavailable = AvailableRatio.of(0, 0)
        val available = AvailableRatio.of(2, 4)

        assertNull(unavailable.value)
        assertEquals(0.5, available.value)
    }

    @Test
    fun `mechanism activation cannot claim an unconfigured mechanism`() {
        assertFailsWith<IllegalArgumentException> {
            MechanismActivation(
                mechanismId = "tool-reuse",
                configured = false,
                activated = true,
                authorityIds = listOf("reuse-1")
            )
        }
    }
}
