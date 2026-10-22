package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.ScoringWorkerRequest
import com.achulkov.evomolior.competition.grader.GradingSheet
import com.achulkov.evomolior.competition.grader.ExactGraderRoundStatus
import com.achulkov.evomolior.competition.grader.RubricDimension
import com.achulkov.evomolior.competition.scoring.GroupRoundResult
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeScoreVector
import com.achulkov.evomolior.core.grader.BoundedScore
import com.achulkov.evomolior.core.grader.GraderScoreBundle
import com.achulkov.evomolior.core.grader.GraderScoreBundleStatus
import com.achulkov.evomolior.core.grader.GradingRequirementId
import com.achulkov.evomolior.core.grader.RequirementResult
import com.achulkov.evomolior.core.grader.RequirementResultStatus
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks down the public runtime surface so scoring remains behind the engine boundary.
 */
class ScoringIsolationSurfaceTest {

    @Test
    fun `engine service exposes no scoring-specific control methods`() {
        val methodNames = EvoMoliorEngineService::class.java.methods.map { it.name }

        assertTrue(methodNames.contains("startRun"))
        assertTrue(methodNames.contains("pauseRun"))
        assertTrue(methodNames.contains("resumeRun"))
        assertTrue(methodNames.contains("abortRun"))
        assertTrue(methodNames.contains("getState"))
        assertTrue(methodNames.contains("getMetaArchiveSummary"))

        assertFalse(methodNames.any { name ->
            name.contains("score", ignoreCase = true) ||
                name.contains("grading", ignoreCase = true) ||
                name.contains("gate", ignoreCase = true)
        }, "Engine service must not expose scoring-specific control methods")
    }

    @Test
    fun `ipc server only exposes lifecycle entry points`() {
        val methodNames = EvoMoliorIpcServer::class.java.methods.map { it.name }.distinct()

        assertTrue(methodNames.contains("start"))
        assertTrue(methodNames.contains("stop"))

        assertFalse(methodNames.any { name ->
            name.contains("score", ignoreCase = true) ||
                name.contains("grading", ignoreCase = true) ||
                name.contains("gate", ignoreCase = true)
        }, "IPC server must not expose scoring-specific entry points")
    }

    @Test
    fun `scoring worker request survives a strict serialization round trip`() {
        val request = scoringRequest()
        val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

        assertEquals(request, json.decodeFromString<ScoringWorkerRequest>(json.encodeToString(request)))
    }

    @Test
    fun `api scoring coordinator owns no blind gate or scoring engine state`() {
        val fieldTypes = BlindTournamentService::class.java.declaredFields.map { it.type.name }

        assertFalse(fieldTypes.any { it.endsWith("BlindSubmissionGate") })
        assertFalse(fieldTypes.any { it.endsWith("ScoringEngine") })
        assertTrue(fieldTypes.any { it.endsWith("ScoringWorkerProcess") })
    }

    @Test
    fun `worker death fails closed without local scoring fallback`() = runBlocking {
        val worker = ScoringWorkerProcess()
        worker.start(listOf("group-0", "group-1"))
        worker.terminateForTest()
        repeat(100) {
            if (!worker.isAlive()) return@repeat
            kotlinx.coroutines.delay(10)
        }

        assertFailsWith<IllegalStateException> {
            worker.score(scoringRequest())
        }
        worker.stop()
    }

    @Test
    fun `scoring child replays exact grader cardinality and aggregation`() = runBlocking {
        val worker = ScoringWorkerProcess()
        worker.start(listOf("group-0", "group-1"))

        val response = try {
            worker.score(scoringRequest())
        } finally {
            worker.stop()
        }

        val exact = checkNotNull(response.exactGraderVerdict)
        assertEquals(ExactGraderRoundStatus.VALID, exact.status)
        assertEquals(2, exact.acceptedBundleHashes.size)
        assertEquals(
            0.8,
            exact.nodeScores.getValue(AtlasGroupId("group-0"))
                .getValue(NodeId("node-1"))
                .correctness
        )
    }

    @Test
    fun `scoring child returns bounded transport diagnostic for incomplete exact authority`() =
        runBlocking {
            val worker = ScoringWorkerProcess()
            worker.start(listOf("group-0", "group-1"))

            val error = try {
                assertFailsWith<IllegalStateException> {
                    worker.score(scoringRequest().copy(exactGraderBundles = emptyList()))
                }
            } finally {
                worker.stop()
            }

            assertTrue(error.message.orEmpty().contains("HTTP 400"))
            assertTrue(
                error.message.orEmpty().contains(
                    "Exact grader quorum and bundles must be supplied together"
                )
            )
        }

    @Test
    fun `scoring child returns bounded diagnostic for incomplete scoring evidence`() = runBlocking {
        val worker = ScoringWorkerProcess()
        worker.start(listOf("group-0", "group-1"))
        val incompleteSheet = scoringRequest().gradingSheets.single().copy(
            rubricDimensions = listOf(
                RubricDimension(
                    dimensionId = "unsubmitted-dimension",
                    name = "Unsubmitted dimension",
                    weight = 1.0,
                    scoringGuidance = "Requires unavailable evidence"
                )
            )
        )

        val error = try {
            assertFailsWith<IllegalStateException> {
                worker.score(scoringRequest().copy(gradingSheets = listOf(incompleteSheet)))
            }
        } finally {
            worker.stop()
        }

        assertTrue(error.message.orEmpty().contains("HTTP 422"))
        assertTrue(error.message.orEmpty().contains("Scoring evidence rejected"))
        assertTrue(error.message.orEmpty().contains("unsubmitted-dimension"))
    }

    @Test
    fun `scoring child returns synchronous seal audits across repeated requests`() = runBlocking {
        val worker = ScoringWorkerProcess()
        worker.start(listOf("group-0", "group-1"))

        try {
            repeat(64) {
                val response = worker.score(scoringRequest())
                assertEquals(0, response.sealAudit.roundIndex)
                assertEquals(2, response.sealAudit.expectedGroupCount)
                assertEquals(2, response.sealAudit.sealedGroupCount)
            }
        } finally {
            worker.stop()
        }
    }

    @Test
    fun `cancelled cleanup still terminates scoring child`() = runBlocking {
        val worker = ScoringWorkerProcess()
        worker.start(listOf("group-0", "group-1"))

        try {
            val owner = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    worker.stop()
                }
            }
            owner.cancelAndJoin()

            assertFalse(worker.isAlive())
        } finally {
            worker.stop()
        }
    }

    @Test
    fun `startup cancellation remains cancellation after child cleanup`() = runBlocking {
        val worker = ScoringWorkerProcess(
            startupTimeoutMs = 60_000,
            workerMainClass = HangingScoringWorkerMain::class.java.name
        )

        try {
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(1_000) {
                    worker.start(listOf("group-0"))
                }
            }
            assertFalse(worker.isAlive())
        } finally {
            worker.stop()
        }
    }

    @Test
    fun `child termination escalates through forced shutdown`() = runBlocking {
        val child = EscalatingProcess()
        var gracefulShutdowns = 0

        terminateScoringWorkerChildProcess(child, shutdownTimeoutMs = 1) {
            gracefulShutdowns += 1
        }

        assertEquals(1, gracefulShutdowns)
        assertEquals(1, child.destroyCalls)
        assertEquals(1, child.forcedDestroyCalls)
        assertFalse(child.isAlive)
    }

    @Test
    fun `child surviving forced shutdown fails closed`() = runBlocking {
        val child = EscalatingProcess(survivesForcedShutdown = true)

        assertFailsWith<IllegalStateException> {
            terminateScoringWorkerChildProcess(child, shutdownTimeoutMs = 1) {}
        }

        assertTrue(child.isAlive)
        assertEquals(1, child.destroyCalls)
        assertEquals(1, child.forcedDestroyCalls)
    }

    @Test
    fun `worker startup failure includes bounded child diagnostics`() = runBlocking {
        val worker = ScoringWorkerProcess(
            startupTimeoutMs = 5_000,
            workerMainClass = "com.achulkov.evomolior.api.service.MissingScoringWorkerMain"
        )

        val error = assertFailsWith<IllegalStateException> {
            worker.start(listOf("group-0"))
        }

        assertTrue(error.message.orEmpty().contains("exit="))
        assertTrue(error.message.orEmpty().contains("stderr="))
        assertTrue(error.message.orEmpty().contains("MissingScoringWorkerMain"))
    }

    private fun scoringRequest(): ScoringWorkerRequest = ScoringWorkerRequest(
        roundIndex = 0,
        gradingSheets = listOf(
            GradingSheet(
                sheetId = "sheet-0",
                runId = "run-0",
                roundIndex = 0,
                graderGroupId = "grader-0",
                rubricDimensions = listOf(
                    RubricDimension(
                        dimensionId = "accuracy",
                        name = "Accuracy",
                        weight = 1.0,
                        scoringGuidance = "Score accuracy"
                    )
                ),
                automatedTests = emptyList(),
                failureModes = emptyList(),
                comparativePairs = emptyList(),
                sealedAt = 1L
            )
        ),
        submissions = listOf("group-0", "group-1").map { groupId ->
            GroupRoundResult(
                groupId = groupId,
                roundIndex = 0,
                dimensionScores = mapOf("accuracy" to 5.0),
                failureModesTriggered = emptyList(),
                automatedTestsPassed = 0,
                automatedTestsFailed = 0
            )
        },
        expectedGraderGroupIds = setOf("grader-0"),
        exactGraderBundles = listOf("group-0", "group-1").map(::exactBundle)
    )

    private fun exactBundle(groupId: String) = GraderScoreBundle(
        bundleId = "bundle-$groupId",
        runId = "run-0",
        round = 0,
        graderGroupId = "grader-0",
        targetGroupId = AtlasGroupId(groupId),
        graderAtlasHash = "grader-atlas",
        criteriaFreezeHash = "freeze",
        targetSnapshotHash = "snapshot-$groupId",
        nodeScores = mapOf(
            NodeId("node-1") to NodeScoreVector(
                correctness = 0.8,
                evidenceQuality = 0.8,
                reproducibility = 0.8,
                robustness = 0.8,
                integrationUtility = 0.8,
                receiverRelevance = 0.8,
                novelty = 0.8,
                efficiency = 0.8,
                hardFailure = false,
                confidence = 0.9
            )
        ),
        groupScores = mapOf("accuracy" to BoundedScore(0.8)),
        requirementResults = mapOf(
            GradingRequirementId("requirement-1") to
                RequirementResult(RequirementResultStatus.PASS)
        ),
        rationaleHashes = mapOf("accuracy" to "rationale"),
        normalizationTrace = emptyList(),
        status = GraderScoreBundleStatus.VALID
    ).withComputedHash()
}

internal object HangingScoringWorkerMain {
    @JvmStatic
    fun main(@Suppress("UNUSED_PARAMETER") arguments: Array<String>) {
        while (true) {
            Thread.sleep(1_000)
        }
    }
}

private class EscalatingProcess(
    private val survivesForcedShutdown: Boolean = false
) : Process() {
    private var alive = true
    var destroyCalls: Int = 0
        private set
    var forcedDestroyCalls: Int = 0
        private set

    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

    override fun getInputStream(): InputStream = InputStream.nullInputStream()

    override fun getErrorStream(): InputStream = InputStream.nullInputStream()

    override fun waitFor(): Int {
        check(!alive) { "Test process is still alive" }
        return 0
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !alive

    override fun exitValue(): Int {
        check(!alive) { "Test process is still alive" }
        return 0
    }

    override fun destroy() {
        destroyCalls += 1
    }

    override fun destroyForcibly(): Process {
        forcedDestroyCalls += 1
        if (!survivesForcedShutdown) {
            alive = false
        }
        return this
    }

    override fun isAlive(): Boolean = alive
}
