package com.achulkov.evomolior.core.config

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AcceptanceProfileTest {
    @Test
    fun `stable ids parse and serialize independently of enum names`() {
        val expected = mapOf(
            AcceptanceProfile.NORMAL to "normal",
            AcceptanceProfile.RUNTIME_USABILITY_SMOKE to "runtime-usability-smoke",
            AcceptanceProfile.APPENDIX_B_FULL to "appendix-b-full"
        )

        expected.forEach { (profile, id) ->
            assertEquals(id, profile.id)
            assertEquals(profile, AcceptanceProfile.parse(id))
            assertEquals(profile, AcceptanceProfile.parse(profile.name.lowercase()))
            assertEquals("\"$id\"", Json.encodeToString(AcceptanceProfile.serializer(), profile))
            assertEquals(
                profile,
                Json.decodeFromString(AcceptanceProfile.serializer(), "\"$id\"")
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AcceptanceProfile.parse("unknown")
        }
    }
}
