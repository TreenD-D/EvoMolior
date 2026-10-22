package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunRecoverySecurityTest {
    @Test
    fun `recovery rejects normal final answer filenames at every nesting depth`() {
        listOf("final_answer.md", "final-answer.md", "nested/final-answer.md").forEach { path ->
            assertFailsWith<IllegalArgumentException> {
                RecoveredDeliverableRecord(
                    relativePath = path,
                    mediaType = "text/markdown",
                    contentHash = "a".repeat(64),
                    source = RecoveredDeliverableSource.RECOVERY_WRITER_OUTPUT,
                    sourceGroupId = "group-0",
                    sourceRoundIndex = 0
                )
            }
        }
    }

    @Test
    fun `round summary recovery removes private grader and sealed test channels`() {
        val service = RunArchiveService(
            SubstrateDatabaseFactory(
                createTempDirectory("run-recovery-sanitizer-").toFile()
            )
        )
        val sanitized = service.sanitizeRoundSummaryForRecovery(
            """
            {
              "runId":"run-secret",
              "roundIndex":5,
              "judgeOutputsByGrader":{"grader-0":"SECRET_JUDGE_TEST_ID and description"},
              "privateSuite":{
                "tests":[{
                  "testId":"SECRET_TEST_ID",
                  "definition":"SECRET_DEFINITION",
                  "compiledPlan":"SECRET_PLAN",
                  "input":"SECRET_INPUT",
                  "expectedOutput":"SECRET_EXPECTED_OUTPUT",
                  "executorRef":"SECRET_EXECUTOR",
                  "expectedOutcomeHash":"SECRET_OUTCOME_HASH"
                }]
              },
              "groupSummaries":[{
                "groupId":"group-0",
                "failedTestIds":["SECRET_FAILED_TEST_ID"],
                "sealedTestSuiteJson":"SECRET_FIXTURE",
                "hiddenTests":[{"testId":"SECRET_NESTED_TEST_ID"}],
                "submissionSummary":"PUBLIC_WORKER_SUMMARY"
              }]
            }
            """.trimIndent()
        ).toString(Charsets.UTF_8)

        assertFalse(sanitized.contains("SECRET_JUDGE_TEST_ID"))
        assertFalse(sanitized.contains("SECRET_FAILED_TEST_ID"))
        assertFalse(sanitized.contains("SECRET_FIXTURE"))
        assertFalse(sanitized.contains("SECRET_TEST_ID"))
        assertFalse(sanitized.contains("SECRET_DEFINITION"))
        assertFalse(sanitized.contains("SECRET_PLAN"))
        assertFalse(sanitized.contains("SECRET_INPUT"))
        assertFalse(sanitized.contains("SECRET_EXPECTED_OUTPUT"))
        assertFalse(sanitized.contains("SECRET_EXECUTOR"))
        assertFalse(sanitized.contains("SECRET_OUTCOME_HASH"))
        assertFalse(sanitized.contains("SECRET_NESTED_TEST_ID"))
        assertFalse(sanitized.contains("judgeOutputsByGrader"))
        assertFalse(sanitized.contains("failedTestIds"))
        assertFalse(sanitized.contains("privateSuite"))
        assertFalse(sanitized.contains("tests"))
        assertFalse(sanitized.contains("testId"))
        assertFalse(sanitized.contains("PUBLIC_WORKER_SUMMARY"))
    }
}
