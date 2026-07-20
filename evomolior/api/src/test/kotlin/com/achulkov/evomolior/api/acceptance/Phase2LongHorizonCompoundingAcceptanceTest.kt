package com.achulkov.evomolior.api.acceptance

import com.achulkov.evomolior.agents.factory.EvoMoliorAgentFactory
import com.achulkov.evomolior.agents.runner.DeathNoteProtocol
import com.achulkov.evomolior.agents.tools.ReadKBTool
import com.achulkov.evomolior.agents.tools.WriteDeathNoteTool
import com.achulkov.evomolior.agents.tools.WriteStagingTool
import com.achulkov.evomolior.core.budget.BudgetConfig
import com.achulkov.evomolior.core.contract.CriticOutcome
import com.achulkov.evomolior.core.contract.CriticReview
import com.achulkov.evomolior.core.contract.RoleAllocation
import com.achulkov.evomolior.core.contract.RoleAllocationEntry
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.orchestration.lifecycle.CriticPipeline
import com.achulkov.evomolior.orchestration.lifecycle.LifecycleManager
import com.achulkov.evomolior.sandbox.shuru.ShuruVmManager
import com.achulkov.evomolior.sandbox.toolsandbox.ToolSubSandbox
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.reflect.full.callSuspend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Phase2LongHorizonCompoundingAcceptanceTest {

    @Test
    fun `generation ten builds on generation three through KB citation and Death Note lineage`() = runBlocking {
        val dataRoot = createTempDirectory("phase-2-long-horizon-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-phase-2-acceptance"
        val groupId = "group-0"
        val profile = GroupProfile.default(GroupProfileType.Library)
        val db = substrateFactory.get(runId, groupId)
        val toolsRoot = File(dataRoot, "tools")
        val workspace = File(dataRoot, "workspace")
        var generationFinished = CompletableDeferred<Unit>()
        var generationThreeKbId: String? = null

        val lifecycleManager = LifecycleManager(
            runId = runId,
            groupId = groupId,
            shuruVmManager = ShuruVmManager(),
            substrateDatabaseFactory = substrateFactory,
            agentJarPath = "/unused/in-process-agent.jar",
            openRouterApiKey = "sk-test",
            groupProfile = profile,
            localAgentRunner = { bootContext, onBudgetLedger ->
                try {
                    val agentFactory = EvoMoliorAgentFactory(
                        openRouterApiKey = "sk-test",
                        db = db,
                        toolSubSandbox = ToolSubSandbox(workspace),
                        budgetConfig = BudgetConfig(groupId = groupId, profile = profile),
                        toolsMountPath = toolsRoot.absolutePath
                    )
                    val deathNoteProtocol = DeathNoteProtocol(bootContext, db.stagingDao())
                    val context = agentFactory.create(bootContext, deathNoteProtocol = deathNoteProtocol)
                    onBudgetLedger?.invoke(context.budgetLedger)
                    val tools = tools(context)

                    when (bootContext.generation) {
                        3 -> {
                            executeTool(
                                tools.named("WriteStagingTool"),
                                WriteStagingTool.Args(
                                    type = "DOC",
                                    content = "GENERATION_THREE_SOURCE_MARKER: bounded evidence protocol."
                                )
                            )
                        }
                        10 -> {
                            val sourceId = requireNotNull(generationThreeKbId)
                            val source = executeTool(
                                tools.named("ReadKBTool"),
                                ReadKBTool.Args(entryIds = listOf(sourceId))
                            )
                            assertTrue(source.contains("GENERATION_THREE_SOURCE_MARKER"))
                            executeTool(
                                tools.named("WriteStagingTool"),
                                WriteStagingTool.Args(
                                    type = "DOC",
                                    content = "GENERATION_TEN_DERIVATION_MARKER builds on [$sourceId] by applying its bounded evidence protocol."
                                )
                            )
                            executeTool(
                                tools.named("WriteDeathNoteTool"),
                                WriteDeathNoteTool.Args(
                                    summary = "Generation 10 built on canonical Generation 3 entry $sourceId.",
                                    whatWorked = "Retrieved and cited $sourceId before producing the derived document.",
                                    whatFailed = "No lineage break occurred.",
                                    recommendations = "Continue from $sourceId and the generation-10 derivation."
                                )
                            )
                        }
                        else -> Unit
                    }
                    generationFinished.complete(Unit)
                } catch (error: Throwable) {
                    generationFinished.completeExceptionally(error)
                    throw error
                }
            },
            budgetPollingIntervalMs = 10L
        )

        for (generation in 1..10) {
            generationFinished = CompletableDeferred()
            lifecycleManager.spawnGeneration(
                allocation = RoleAllocation(
                    runId = runId,
                    groupId = groupId,
                    generation = generation,
                    entries = listOf(RoleAllocationEntry(Role.Researcher, 1, 100.0)),
                    totalBudgetCeiling = 100.0,
                    rationale = "Phase 2 generation $generation acceptance"
                ),
                stageDisclosure = "Generation $generation lineage acceptance.",
                modelAssignments = emptyMap(),
                rolePrompts = mapOf(Role.Researcher.name to "Preserve and extend canonical lineage."),
                scope = this
            )
            withTimeout(5_000L) { generationFinished.await() }
            withTimeout(5_000L) {
                while (lifecycleManager.getLiveAgentCount() != 0) {
                    kotlinx.coroutines.yield()
                }
            }

            CriticPipeline(
                db = db,
                runId = runId,
                groupId = groupId,
                generation = generation,
                openRouterApiKey = "sk-test",
                criticReviewRunner = { staged, _, _, criticId ->
                    CriticReview(
                        submissionId = staged.id,
                        outcome = CriticOutcome.PROMOTE,
                        criticAgentId = criticId,
                        initialTrustScore = 0.9
                    )
                },
                toolsMountHostPath = toolsRoot.absolutePath
            ).processPendingSubmissions()

            if (generation == 3) {
                generationThreeKbId = db.knowledgeBaseDao()
                    .listActiveOrganized(runId, groupId, 50)
                    .single { it.generation == 3 && it.content.contains("GENERATION_THREE_SOURCE_MARKER") }
                    .id
            }
        }

        val sourceId = assertNotNull(generationThreeKbId)
        val source = assertNotNull(db.knowledgeBaseDao().getById(sourceId))
        assertEquals(3, source.generation)
        assertTrue(source.citationCount >= 1)
        val generationTenDocument = db.knowledgeBaseDao()
            .listActiveOrganized(runId, groupId, 50)
            .single { it.generation == 10 && it.content.contains("GENERATION_TEN_DERIVATION_MARKER") }
        assertTrue(generationTenDocument.content.contains(sourceId))
        val generationTenDeathNote = db.stagingDao().getAll(runId, groupId)
            .single { it.generation == 10 && it.type == "DEATH_NOTE" }
        assertTrue(generationTenDeathNote.content.contains(sourceId))

        lifecycleManager.terminateAll()
    }

    private fun tools(context: Any): List<*> {
        val agent = context.javaClass.getMethod("getAgent").invoke(context)
        val registry = agent.javaClass.getMethod("getToolRegistry").invoke(agent)
        return registry.javaClass.methods.first { it.name == "getTools" }.invoke(registry) as List<*>
    }

    private fun List<*>.named(simpleName: String): Any =
        single { it?.javaClass?.simpleName == simpleName }!!

    private suspend fun executeTool(tool: Any, args: Any): String =
        tool::class.members.first { it.name == "execute" }.callSuspend(tool, args) as String
}
