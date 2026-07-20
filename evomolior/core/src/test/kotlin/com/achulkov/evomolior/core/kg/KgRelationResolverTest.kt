package com.achulkov.evomolior.core.kg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KgRelationResolverTest {
    @Test
    fun `case variants and declared aliases resolve to Council spelling`() {
        val allowed = listOf("REQUIRES", "CONCERNS", "IMPLEMENTS")

        assertEquals("IMPLEMENTS", KgRelationResolver.resolve("implements", allowed))
        assertEquals("REQUIRES", KgRelationResolver.resolve("requires_rollout_gate", allowed))
        assertEquals("CONCERNS", KgRelationResolver.resolve("risks", allowed))
    }

    @Test
    fun `alias cannot silently extend Council schema`() {
        assertNull(KgRelationResolver.resolve("risks", listOf("REQUIRES")))
        assertNull(KgRelationResolver.resolve("unknown", listOf("REQUIRES")))
    }
}
