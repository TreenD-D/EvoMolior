@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.substrate.kg

import com.achulkov.evomolior.core.kg.KgFactAttemptAuthority
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class KgFactAttemptRepositoryTest {
    @Test
    fun `attempt records append idempotently remain immutable and query deterministically`() = runTest {
        val root = createTempDirectory("kg-fact-attempt-repository-").toFile()
        val database = SubstrateDatabaseFactory(root).get("run", "group")
        try {
            val repository = KgFactAttemptRepository(database)
            val later = attempt(
                submissionId = "submission-b",
                generation = 2,
                content = "SUBJECT: A\nRELATION: concerns\nOBJECT: B"
            )
            val earlier = attempt(
                submissionId = "submission-a",
                generation = 1,
                content = "SUBJECT: C\nRELATION: concerns\nOBJECT: D"
            )

            assertEquals(later, repository.append(later))
            assertEquals(earlier, repository.append(earlier))
            assertEquals(earlier, repository.append(earlier))
            assertEquals(listOf(earlier, later), repository.forGroup("run", "group"))
            assertEquals(earlier, repository.bySubmission("run", "group", "submission-a"))
            assertFailsWith<IllegalArgumentException> {
                repository.append(
                    attempt(
                        submissionId = "submission-a",
                        generation = 1,
                        content = "SUBJECT: changed\nRELATION: concerns\nOBJECT: D"
                    )
                )
            }
        } finally {
            database.close()
        }
    }

    private fun attempt(
        submissionId: String,
        generation: Int,
        content: String
    ) = KgFactAttemptAuthority.classify(
        runId = "run",
        groupId = "group",
        submissionId = submissionId,
        agentId = "agent",
        generation = generation,
        content = content,
        allowedRelations = listOf("CONCERNS"),
        recordedAt = Instant.fromEpochMilliseconds(generation.toLong())
    )
}
