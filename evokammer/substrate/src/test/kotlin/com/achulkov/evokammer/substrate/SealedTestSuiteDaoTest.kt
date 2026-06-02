package com.achulkov.evokammer.substrate

import com.achulkov.evokammer.substrate.db.entity.SealedTestSuiteEntity
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies the sealed test suite write-once persistence contract.
 *
 * The Genesis Council suite is expected to be stored in the first group's substrate DB
 * and rejected on any subsequent attempt to overwrite the canonical row.
 */
class SealedTestSuiteDaoTest {

    @Test
    fun `sealed test suite is persisted once and rejects overwrite attempts`() = runTest {
        val dataRoot = createTempDirectory("sealed-suite-dao-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val db = factory.get("run-1", "group-0")

        val entity = SealedTestSuiteEntity(
            id = "sealed-suite-run-1",
            runId = "run-1",
            groupId = "group-0",
            suiteJson = """{"tests":[{"id":"alpha"}]}""",
            suiteHash = "hash-1",
            isLocked = true,
            createdAtMs = 1_000L
        )

        db.sealedTestSuiteDao().insert(entity)

        val stored = db.sealedTestSuiteDao().get("run-1", "group-0")
        assertNotNull(stored)
        assertEquals(entity.suiteJson, stored.suiteJson)
        assertEquals(entity.suiteHash, stored.suiteHash)
        assertTrue(stored.isLocked)

        val overwriteAttempt = entity.copy(suiteJson = """{"tests":[{"id":"beta"}]}""")
        assertFailsWith<Exception> {
            db.sealedTestSuiteDao().insert(overwriteAttempt)
        }
    }
}
