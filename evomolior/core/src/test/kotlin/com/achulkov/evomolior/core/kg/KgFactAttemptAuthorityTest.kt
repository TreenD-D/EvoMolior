@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.core.kg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class KgFactAttemptAuthorityTest {
    @Test
    fun `classification preserves exact content hash and resolves aliases to live schema spelling`() {
        val content = "SUBJECT: A\nRELATION: risks\nOBJECT: B"
        val record = KgFactAttemptAuthority.classify(
            runId = "run",
            groupId = "group",
            submissionId = "submission",
            agentId = "agent",
            generation = 2,
            content = content,
            allowedRelations = listOf("CONCERNS"),
            recordedAt = Instant.parse("2026-07-19T10:00:00Z")
        )

        assertEquals(KgFactAttemptStatus.SCHEMA_RESOLVED, record.status)
        assertEquals("risks", record.requestedRelation)
        assertEquals("CONCERNS", record.resolvedRelation)
        assertEquals(null, record.failureCode)
        assertTrue(record.hasValidHashes(content))
    }

    @Test
    fun `classification reports every required field with a typed failure code`() {
        val cases = listOf(
            "RELATION: concerns\nOBJECT: B" to KgFactAttemptFailureCode.MISSING_SUBJECT,
            "SUBJECT: A\nOBJECT: B" to KgFactAttemptFailureCode.MISSING_RELATION,
            "SUBJECT: A\nRELATION: concerns" to KgFactAttemptFailureCode.MISSING_OBJECT
        )

        cases.forEachIndexed { index, (content, expectedFailure) ->
            val record = KgFactAttemptAuthority.classify(
                runId = "run",
                groupId = "group",
                submissionId = "submission-$index",
                agentId = "agent",
                generation = 2,
                content = content,
                allowedRelations = listOf("CONCERNS"),
                recordedAt = Instant.parse("2026-07-19T10:00:00Z")
            )

            assertEquals(KgFactAttemptStatus.SYNTAX_REJECTED, record.status)
            assertEquals(expectedFailure, record.failureCode)
            assertEquals(null, record.resolvedRelation)
        }
    }
}
