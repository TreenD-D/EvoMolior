package com.achulkov.evomolior.api.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Increment9ObservabilityServiceTest {
    @Test
    fun `zero denominator is unavailable rather than zero filled`() {
        val unavailable = AvailableRatio.of(0, 0)
        val available = AvailableRatio.of(2, 4)

        assertNull(unavailable.value)
        assertEquals(0.5, available.value)
    }

    @Test
    fun `mechanism activation resolves all five closed states`() {
        assertEquals(
            MechanismActivationState.NOT_CONFIGURED,
            resolveMechanismActivation(
                "none",
                configured = false,
                configurationAuthorityIds = listOf("configuration/none"),
            ).state,
        )
        assertEquals(
            MechanismActivationState.CONFIGURED_NOT_REACHED,
            resolveMechanismActivation(
                "idle",
                configured = true,
                configurationAuthorityIds = listOf("configuration/idle"),
            ).state,
        )
        assertEquals(
            MechanismActivationState.ATTEMPTED_FAILED,
            resolveMechanismActivation(
                "failed",
                configured = true,
                configurationAuthorityIds = listOf("configuration/failed"),
                attemptAuthorityIds = listOf("attempt/1"),
            ).state,
        )
        assertEquals(
            MechanismActivationState.ACTIVATED_NO_DOWNSTREAM_USE,
            resolveMechanismActivation(
                "activated",
                configured = true,
                configurationAuthorityIds = listOf("configuration/activated"),
                attemptAuthorityIds = listOf("attempt/1"),
                activationAuthorityIds = listOf("activation/1"),
            ).state,
        )
        val used = resolveMechanismActivation(
            "used",
            configured = true,
            configurationAuthorityIds = listOf("configuration/used"),
            attemptAuthorityIds = listOf("attempt/1"),
            activationAuthorityIds = listOf("activation/1"),
            downstreamAuthorityIds = listOf("downstream/1"),
        )
        assertEquals(MechanismActivationState.ACTIVATED_WITH_DOWNSTREAM_USE, used.state)
        assertTrue(used.configured)
        assertTrue(used.activated)
    }

    @Test
    fun `unconfigured mechanism cannot claim runtime authority`() {
        assertFailsWith<IllegalArgumentException> {
            resolveMechanismActivation(
                mechanismId = "tool-reuse",
                configured = false,
                configurationAuthorityIds = listOf("configuration/tool-reuse"),
                activationAuthorityIds = listOf("reuse-1"),
            )
        }
    }

    @Test
    fun `configured but unreached mechanism is not activated`() {
        val activation = resolveMechanismActivation(
            mechanismId = "publication-barrier",
            configured = true,
            configurationAuthorityIds = listOf("publication-policy/run"),
        )

        assertTrue(activation.configured)
        assertFalse(activation.activated)
    }
}
