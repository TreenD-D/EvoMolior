package com.achulkov.evomolior.substrate

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.SQLiteConnection
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue

class SubstrateDatabaseFactoryMigrationSafetyTest {
    @Test
    fun `authority paths reject traversal and existing symlink descendants`() {
        val root = createTempDirectory("substrate-path-safety-").toFile()
        val factory = SubstrateDatabaseFactory(root)

        assertFails { factory.dbFilePath("../escape", "group") }
        assertFails { factory.dbFilePath("run", "..") }
        assertFails { factory.graderDbFilePath("run/other", "grader") }

        val runsRoot = root.toPath().resolve("evomolior/runs")
        Files.createDirectories(runsRoot)
        val outside = createTempDirectory("substrate-path-safety-outside-")
        Files.createSymbolicLink(runsRoot.resolve("linked-run"), outside)
        assertFails { factory.dbFilePath("linked-run", "group") }

        val safeRun = runsRoot.resolve("safe-run")
        Files.createDirectories(safeRun)
        Files.createSymbolicLink(safeRun.resolve("control"), outside)
        assertFails { factory.runControlDir("safe-run") }
    }

    @Test
    fun `Atlas authority fails closed and preserves bytes when migration is unavailable`() =
        runBlocking {
            val root = createTempDirectory("atlas-migration-safety-").toFile()
            val factory = SubstrateDatabaseFactory(root)
            val databaseFile = factory.atlasDbFilePath("run")
            seedUnsupportedDatabase(databaseFile.absolutePath)

            assertFails {
                factory.getAtlas("run").workerExecutionDao().exits("run", null)
            }
            assertTrue(hasSentinelTable(databaseFile.absolutePath))
        }

    @Test
    fun `group and grader authorities also reject destructive fallback`() = runBlocking {
        val root = createTempDirectory("substrate-migration-safety-").toFile()
        val factory = SubstrateDatabaseFactory(root)
        val groupFile = factory.dbFilePath("run", "group")
        val graderFile = factory.graderDbFilePath("run", "grader")
        seedUnsupportedDatabase(groupFile.absolutePath)
        seedUnsupportedDatabase(graderFile.absolutePath)

        assertFails {
            factory.get("run", "group").workerExecutionDao().exits("run", "group")
        }
        assertFails {
            factory.getGrader("run", "grader").workerExecutionDao().exits("run", "grader")
        }
        assertTrue(hasSentinelTable(groupFile.absolutePath))
        assertTrue(hasSentinelTable(graderFile.absolutePath))
    }

    private fun seedUnsupportedDatabase(path: String) {
        java.io.File(path).parentFile?.mkdirs()
        BundledSQLiteDriver().open(path).use { connection ->
            connection.execute("CREATE TABLE migration_sentinel(value TEXT NOT NULL)")
            connection.execute("INSERT INTO migration_sentinel(value) VALUES ('preserve-me')")
            connection.execute("PRAGMA user_version = 999")
        }
    }

    private fun hasSentinelTable(path: String): Boolean =
        BundledSQLiteDriver().open(path).use { connection ->
            connection.prepare(
                """
                SELECT name FROM sqlite_master
                WHERE type = 'table' AND name = 'migration_sentinel'
                """.trimIndent()
            ).use { statement ->
                statement.step()
            }
        }

    private fun SQLiteConnection.execute(sql: String) {
        prepare(sql).use { statement -> statement.step() }
    }
}
