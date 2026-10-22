package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.events.EvoMoliorEvent
import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.api.model.FinalOutputValidationContract
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.core.contract.GeneratedImageAsset
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.core.source.SourceReportInput
import com.achulkov.evomolior.orchestration.lifecycle.LifecycleManager
import com.achulkov.evomolior.sandbox.shuru.ShuruVmManager
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.io.path.createTempDirectory
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FinalWriterCandidateSelectionTest {
    @Test
    fun `publication selects corrected same-writer DOC only after natural settlement`() =
        verifySelection("## Summary\nCorrected source quote.", "corrected-doc")

    @Test
    fun `invalid advisory correction retains original report and unresolved source side-report`() =
        verifySelection("## Wrong heading\nThe correction lost the required section.", "first-doc")

    @Test
    fun `incomplete HTML correction does not discard complete original`() =
        verifySelection(
            correction = "<html><body>Incomplete correction",
            expectedSubmissionId = "first-doc",
            original = "<!doctype html><html><head><title>Review</title></head><body>" +
                "<p>`wrong quotation` (source.md:L1).</p><footer>Review</footer></body></html>",
            outputSpec = FinalOutputSpec("review.html", "text/html"),
        )

    private fun verifySelection(
        correction: String,
        expectedSubmissionId: String,
        original: String = "## Summary\n`wrong quotation` (source.md:L1).",
        outputSpec: FinalOutputSpec = FinalOutputSpec(
            "review.md", "text/markdown",
            validation = FinalOutputValidationContract(exactMarkdownHeadings = listOf("Summary")),
        ),
    ) = runBlocking<Unit> {
        val root = createTempDirectory("writer-correction-selection-").toFile()
        val factory = SubstrateDatabaseFactory(root)
        val runId = "writer-correction"
        val groupId = "group-0"
        val observed = CompletableDeferred<Unit>()
        val manager = LifecycleManager(
            runId, groupId, ShuruVmManager(), factory, "/unused/agent.jar", "provider-free",
            localAgentRunner = { boot, _ ->
                val now = System.currentTimeMillis()
                val first = StagingSubmissionEntity(
                    id = "first-doc", agentId = boot.agentId, role = boot.role.name,
                    type = "DOC", content = original,
                    budgetSignature = "fixture", generation = boot.generation,
                    groupId = groupId, runId = runId, submittedAtMs = now,
                )
                val dao = factory.get(runId, groupId).stagingDao()
                dao.insert(first)
                observed.await()
                dao.insert(first.copy(id = "corrected-doc", content = correction,
                    submittedAtMs = maxOf(now + 1, System.currentTimeMillis())))
                dao.insert(first.copy(id = "death-note", type = "DEATH_NOTE",
                    content = "Natural completion after one correction", status = "promoted",
                    submittedAtMs = maxOf(now + 2, System.currentTimeMillis())))
            },
        )
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ -> manager },
            groupCoordinatorProvider = { groups, dimensions -> GroupCoordinator(groups, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = factory,
            finalDeliverableAttemptTimeoutMsOverride = 5_000,
        )
        @Suppress("UNCHECKED_CAST")
        val managers = service.javaClass.getDeclaredField("liveLifecycleManagers").apply {
            isAccessible = true
        }.get(service) as MutableMap<String, LifecycleManager>
        managers[groupId] = manager
        val progress = mutableListOf<EvoMoliorEvent.EngineProgress>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            service.events.collect { event ->
                if (event is EvoMoliorEvent.EngineProgress) {
                    progress += event
                    if (event.phase == "writer_candidate_staged") observed.complete(Unit)
                }
            }
        }
        try {
            val config = RunConfig(
                runId = runId, objective = "Produce a source review.",
                groupCount = 1, groupProfiles = listOf(GroupProfileType.Library),
                scoringDimensions = listOf(ScoringDimension("quality", "Quality", "Review", 1.0)),
                openRouterApiKey = "provider-free", isTestRun = true,
            )
            val method = service::class.declaredFunctions.first { it.name == "preparePreAcceptanceWriterOutput" }
                .apply { isAccessible = true }
            val result = withTimeout(10_000) {
                @Suppress("UNCHECKED_CAST")
                val prepared = method.callSuspend(
                    service, config, groupId, 0, null, outputSpec,
                    emptyList<GeneratedImageAsset>(),
                ) as Pair<GeneratedOutputFile, *>?
                prepared?.first
            }
            val expected = if (expectedSubmissionId == "first-doc") original else correction
            assertEquals(expected, assertNotNull(result).content)
            assertEquals(expectedSubmissionId, result.sourceSubmissionId)
            assertEquals(correction, factory.get(runId, groupId).stagingDao()
                .getById("corrected-doc")?.content, "An invalid correction must remain staged evidence")
            if (expectedSubmissionId == "first-doc") {
                val sidecar = ReportSourceSidecars.render(
                    runId, "outputs/recovered/${outputSpec.relativePath}", result.content.encodeToByteArray(),
                    listOf(SourceReportInput("source.md", "source.md", "correct quotation")),
                ).decodeToString()
                assertTrue(sidecar.contains("QUOTATION_MISMATCH"))
                assertTrue(sidecar.contains(result.contentHash))
            }
            assertTrue(manager.publicationAccounting().isQuiescent)
            val record = factory.get(runId, "__meta_archive__").metaArchiveDao()
                .getByType(CandidateOutputValidationRecord.ENTRY_TYPE).single()
            assertTrue(record.contentJson.contains(result.contentHash))
            assertTrue(record.contentJson.contains("\"acceptanceGranted\":false"))
        } finally {
            observed.complete(Unit)
            manager.terminateAll()
            manager.awaitQuiescence(5_000)
            collector.cancelAndJoin()
            factory.close(runId, groupId)
            factory.close(runId, "__meta_archive__")
            root.deleteRecursively()
        }
    }
}
