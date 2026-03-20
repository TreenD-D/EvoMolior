package com.achulkov.evokammer.substrate

import com.achulkov.evokammer.substrate.db.entity.DeadEndsEntity
import com.achulkov.evokammer.substrate.db.entity.KGSchemaEntity
import com.achulkov.evokammer.substrate.db.entity.TrustScoreEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Unit tests for the new Phase 9 substrate entities.
 *
 * Tests validate:
 * - Default field values
 * - Business-rule constraints via entity construction
 * - JSON field defaults
 */
class SubstrateEntityTest {

    // ── TrustScoreEntity ───────────────────────────────────────────────────────

    @Test
    fun `TrustScoreEntity has expected default counts`() {
        val entity = TrustScoreEntity(
            id = "ts-1",
            sourceId = "doc-1",
            sourceType = "kb_entry",
            runId = "run-1",
            groupId = "G1",
            score = 0.75,
            lastUpdatedMs = 1000L
        )
        assertEquals(0, entity.decayCount)
        assertEquals(0, entity.promotionCount)
        assertEquals(0, entity.rejectionCount)
    }

    @Test
    fun `TrustScoreEntity score is within 0-1 range by construction`() {
        val validScore = TrustScoreEntity(
            id = "ts-2",
            sourceId = "doc-2",
            sourceType = "agent",
            runId = "run-1",
            groupId = "G1",
            score = 0.5,
            lastUpdatedMs = 0L
        )
        assertTrue(validScore.score in 0.0..1.0)
    }

    @Test
    fun `TrustScoreEntity supports all sourceType values`() {
        val sourceTypes = listOf("kb_entry", "dispatch", "agent", "staging")
        sourceTypes.forEach { st ->
            val entity = TrustScoreEntity(
                id = "ts-$st",
                sourceId = "src",
                sourceType = st,
                runId = "run-1",
                groupId = "G1",
                score = 0.8,
                lastUpdatedMs = 0L
            )
            assertEquals(st, entity.sourceType)
        }
    }

    @Test
    fun `TrustScoreEntity promotion increases logical score`() {
        val base = TrustScoreEntity(
            id = "ts-3",
            sourceId = "doc-3",
            sourceType = "staging",
            runId = "run-1",
            groupId = "G1",
            score = 0.6,
            lastUpdatedMs = 0L,
            promotionCount = 1
        )
        val boosted = base.copy(score = (base.score + 0.1).coerceAtMost(1.0), promotionCount = 2)
        assertTrue(boosted.score > base.score)
        assertEquals(2, boosted.promotionCount)
    }

    // ── KGSchemaEntity ────────────────────────────────────────────────────────

    @Test
    fun `KGSchemaEntity has empty JSON array defaults`() {
        val schema = KGSchemaEntity(
            id = "schema-1",
            runId = "run-1",
            groupId = "G1",
            createdAtMs = 0L
        )
        assertEquals("[]", schema.entityTypesJson)
        assertEquals("[]", schema.relationTypesJson)
        assertEquals("[]", schema.hardConstraintsJson)
        assertEquals("[]", schema.softConstraintsJson)
        assertEquals(1, schema.schemaVersion)
    }

    @Test
    fun `KGSchemaEntity stores entity and relation type JSON`() {
        val entityTypes = """["Concept","Algorithm","Organisation"]"""
        val relationTypes = """["CAUSES","SUPPORTS","CONTRADICTS","IMPLEMENTS"]"""
        val schema = KGSchemaEntity(
            id = "schema-2",
            runId = "run-1",
            groupId = "G1",
            entityTypesJson = entityTypes,
            relationTypesJson = relationTypes,
            createdAtMs = System.currentTimeMillis()
        )
        assertEquals(entityTypes, schema.entityTypesJson)
        assertEquals(relationTypes, schema.relationTypesJson)
    }

    @Test
    fun `KGSchemaEntity hard and soft constraints are independent`() {
        val hardConstraints = """[{"id":"HC-001","description":"No self-relations","expression":"subject != object"}]"""
        val softConstraints = """[{"id":"SC-001","description":"Avoid UNKNOWN relation","penaltyFactor":0.2}]"""
        val schema = KGSchemaEntity(
            id = "schema-3",
            runId = "run-1",
            groupId = "G1",
            hardConstraintsJson = hardConstraints,
            softConstraintsJson = softConstraints,
            createdAtMs = 0L
        )
        assertTrue(schema.hardConstraintsJson.contains("HC-001"))
        assertTrue(schema.softConstraintsJson.contains("SC-001"))
        assertFalse(schema.hardConstraintsJson.contains("SC-001"))
        assertFalse(schema.softConstraintsJson.contains("HC-001"))
    }

    @Test
    fun `KGSchemaEntity version increments on schema evolution`() {
        val v1 = KGSchemaEntity("s4", "run-1", "G1", createdAtMs = 0L, schemaVersion = 1)
        val v2 = v1.copy(schemaVersion = 2, entityTypesJson = """["NewType"]""")
        assertEquals(1, v1.schemaVersion)
        assertEquals(2, v2.schemaVersion)
    }

    // ── DeadEndsEntity ────────────────────────────────────────────────────────

    @Test
    fun `DeadEndsEntity has zero reference count by default`() {
        val deadEnd = DeadEndsEntity(
            id = "de-1",
            runId = "run-1",
            groupId = "G1",
            approach = "Chain-of-thought for ambiguous schema",
            reason = "Consistently rejected by Critic due to unverifiable premises",
            domain = "kg_inference",
            generation = 2,
            sourceAgentId = "agent-x",
            createdAtMs = 0L
        )
        assertEquals(0, deadEnd.referenceCount)
    }

    @Test
    fun `DeadEndsEntity referenceCount increments on copy`() {
        val base = DeadEndsEntity(
            id = "de-2",
            runId = "run-1",
            groupId = "G1",
            approach = "Bulk web fetch",
            reason = "AllowlistProxy blocks most domains",
            domain = "search",
            generation = 1,
            sourceAgentId = "agent-y",
            createdAtMs = 0L,
            referenceCount = 3
        )
        val updated = base.copy(referenceCount = base.referenceCount + 1)
        assertEquals(4, updated.referenceCount)
    }

    @Test
    fun `DeadEndsEntity captures expected domain tags`() {
        val domains = listOf("search", "tool_execution", "kg_inference", "inter_agent", "staging")
        domains.forEach { domain ->
            val de = DeadEndsEntity(
                id = "de-$domain",
                runId = "run-1",
                groupId = "G1",
                approach = "Approach for $domain",
                reason = "Failed consistently",
                domain = domain,
                generation = 1,
                sourceAgentId = "agent-z",
                createdAtMs = 0L
            )
            assertEquals(domain, de.domain)
        }
    }

    @Test
    fun `DeadEndsEntity approach and reason are non-empty`() {
        val de = DeadEndsEntity(
            id = "de-5",
            runId = "run-1",
            groupId = "G1",
            approach = "Iterative entity resolution without schema alignment",
            reason = "Creates inconsistent relations that fail hard constraint HC-003",
            domain = "kg_inference",
            generation = 3,
            sourceAgentId = "agent-w",
            createdAtMs = 0L
        )
        assertTrue(de.approach.isNotBlank())
        assertTrue(de.reason.isNotBlank())
    }
}
