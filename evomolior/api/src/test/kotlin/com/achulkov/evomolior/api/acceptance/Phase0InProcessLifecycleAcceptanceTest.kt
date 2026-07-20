@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.api.acceptance

import com.achulkov.evomolior.agents.factory.EvoMoliorAgentFactory
import com.achulkov.evomolior.agents.runner.DeathNoteProtocol
import com.achulkov.evomolior.agents.tools.ExecuteToolTool
import com.achulkov.evomolior.agents.tools.ReadKBTool
import com.achulkov.evomolior.agents.tools.WriteDeathNoteTool
import com.achulkov.evomolior.agents.tools.WriteStagingTool
import com.achulkov.evomolior.core.budget.BudgetConfig
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.contract.ContributionAuthorityKind
import com.achulkov.evomolior.core.contract.ContributionAuthorityRef
import com.achulkov.evomolior.core.contract.CriticDecisionRecord
import com.achulkov.evomolior.core.contract.CriticOutcome
import com.achulkov.evomolior.core.contract.CriticReview
import com.achulkov.evomolior.core.contract.RoleAllocation
import com.achulkov.evomolior.core.contract.RoleAllocationEntry
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.orchestration.lifecycle.LifecycleManager
import com.achulkov.evomolior.sandbox.shuru.ShuruVmManager
import com.achulkov.evomolior.sandbox.toolsandbox.ToolSubSandbox
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.EventTypes
import com.achulkov.evomolior.substrate.db.entity.KnowledgeBaseEntryEntity
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.db.entity.ToolRegistryEntryEntity
import com.achulkov.evomolior.substrate.tools.ToolMaterializer
import com.achulkov.evomolior.substrate.worker.ContributionGraphRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.reflect.full.callSuspend
import kotlin.time.Instant

class Phase0InProcessLifecycleAcceptanceTest {

    @Test
    fun `agent spawns reads stages executes tool and dies with death note under budget`() = runBlocking {
        val dataRoot = createTempDirectory("phase-0-in-process-acceptance-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-phase-0-acceptance"
        val groupId = "group-0"
        val profile = GroupProfile.default(GroupProfileType.Factory)
        val db = substrateFactory.get(runId, groupId)
        val toolsRoot = substrateFactory.dbFilePath(runId, groupId).parentFile.resolve("tools")
        val workspace = dataRoot.resolve("workspace")
        val agentCompleted = CompletableDeferred<Double>()

        val seedContent = "PHASE_ZERO_KB_MARKER: validated lifecycle seed."
        val seedHash = AtlasHashing.hashText(seedContent)
        db.stagingDao().insert(
            StagingSubmissionEntity(
                id = "phase-0-seed-submission",
                agentId = "primordial-seed",
                role = Role.Researcher.name,
                type = "DOC",
                content = seedContent,
                wipTaskRef = null,
                budgetSignature = "phase-0-seed",
                status = "promoted",
                criticAgentId = "phase-0-seed-critic",
                initialTrustScore = 1.0,
                generation = 0,
                groupId = groupId,
                runId = runId,
                submittedAtMs = 900L,
                reviewedAtMs = 1_000L,
                promotedArtifactRef = "kb:phase-0-kb",
                promotedArtifactHash = seedHash
            )
        )
        db.knowledgeBaseDao().insert(
            KnowledgeBaseEntryEntity(
                id = "phase-0-kb",
                content = seedContent,
                sourceAgentId = "primordial-seed",
                generation = 0,
                trustScore = 1.0,
                citationCount = 0,
                domain = "foundation",
                status = "active",
                groupId = groupId,
                runId = runId,
                createdAtMs = 1_000L
            )
        )
        ContributionGraphRepository(db).recordCriticDecision(
            CriticDecisionRecord(
                decisionId = "phase-0-seed-decision",
                runId = runId,
                groupId = AtlasGroupId(groupId),
                round = 0,
                submissionId = "phase-0-seed-submission",
                submissionContentHash = seedHash,
                resolvedReview = CriticReview(
                    submissionId = "phase-0-seed-submission",
                    outcome = CriticOutcome.PROMOTE,
                    criticAgentId = "phase-0-seed-critic",
                    initialTrustScore = 1.0
                ),
                resultAuthority = ContributionAuthorityRef(
                    kind = ContributionAuthorityKind.CANONICAL_ARTIFACT,
                    authorityId = "kb:phase-0-kb",
                    authorityHash = seedHash
                ),
                decidedAt = Instant.fromEpochMilliseconds(1_000L)
            ).withComputedHash()
        )
        val executable = ToolRegistryEntryEntity(
            id = "phase-0-tool-v1",
            name = "phase-0-tool",
            version = 1,
            authorAgentId = "primordial-seed",
            inputSchemaJson = "{}",
            outputSchemaJson = "{}",
            domain = "foundation",
            code = "echo PHASE_ZERO_TOOL_MARKER",
            codeLanguage = "shell",
            generation = 0,
            groupId = groupId,
            runId = runId,
            createdAtMs = 1_100L
        )
        ToolMaterializer(toolsRoot).materialize(executable)
        db.toolRegistryDao().insert(executable)

        lateinit var lifecycleManager: LifecycleManager
        lifecycleManager = LifecycleManager(
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
                        toolsMountPath = toolsRoot.absolutePath,
                        authoritativeToolExecution = false
                    )
                    val deathNoteProtocol = DeathNoteProtocol(bootContext, db.stagingDao())
                    val context = agentFactory.create(bootContext, deathNoteProtocol = deathNoteProtocol)
                    onBudgetLedger?.invoke(context.budgetLedger)
                    val agent = context.javaClass.getMethod("getAgent").invoke(context)
                    val toolRegistry = agent.javaClass.getMethod("getToolRegistry").invoke(agent)
                    val tools = toolRegistry.javaClass.methods.first { it.name == "getTools" }
                        .invoke(toolRegistry) as List<*>

                    val kbResult = executeTool(
                        tools.single { it?.javaClass?.simpleName == "ReadKBTool" }!!,
                        ReadKBTool.Args(entryIds = listOf("phase-0-kb"))
                    )
                    assertTrue(kbResult.contains("PHASE_ZERO_KB_MARKER"))

                    val stagingResult = executeTool(
                        tools.single { it?.javaClass?.simpleName == "WriteStagingTool" }!!,
                        WriteStagingTool.Args(
                            type = "DOC",
                            content = "PHASE_ZERO_STAGING_MARKER: lifecycle acceptance artifact."
                        )
                    )
                    assertTrue(stagingResult.contains("Submission accepted"))

                    val executionResult = executeTool(
                        tools.single { it?.javaClass?.simpleName == "ExecuteToolTool" }!!,
                        ExecuteToolTool.Args(toolName = "phase-0-tool", argsJson = "{}")
                    )
                    assertTrue(executionResult.contains("PHASE_ZERO_TOOL_MARKER"))
                    assertTrue(executionResult.contains("SUCCESS"))

                    val deathNoteResult = executeTool(
                        tools.single { it?.javaClass?.simpleName == "WriteDeathNoteTool" }!!,
                        WriteDeathNoteTool.Args(
                            summary = "Completed the Phase 0 lifecycle acceptance path.",
                            whatWorked = "KB read, staging write, and materialized tool execution.",
                            whatFailed = "Nothing.",
                            recommendations = "Preserve the connected in-process authority boundary."
                        )
                    )
                    assertTrue(deathNoteResult.contains("Death Note written"))
                    assertTrue(deathNoteProtocol.isWritten)
                    agentCompleted.complete(context.budgetLedger.balance)
                } catch (error: Throwable) {
                    agentCompleted.completeExceptionally(error)
                    throw error
                }
            },
            budgetPollingIntervalMs = 10L
        )

        lifecycleManager.spawnGeneration(
            allocation = RoleAllocation(
                runId = runId,
                groupId = groupId,
                generation = 0,
                entries = listOf(RoleAllocationEntry(Role.Researcher, 1, 120.0)),
                totalBudgetCeiling = 120.0,
                rationale = "Phase 0 deterministic acceptance"
            ),
            stageDisclosure = "Exercise the connected Phase 0 lifecycle.",
            modelAssignments = emptyMap(),
            rolePrompts = mapOf(Role.Researcher.name to "Run the deterministic acceptance actions."),
            scope = this
        )

        val remainingBudget = withTimeout(5_000L) { agentCompleted.await() }
        withTimeout(5_000L) {
            while (lifecycleManager.getLiveAgentCount() != 0) {
                kotlinx.coroutines.yield()
            }
        }

        val stagingEntries = db.stagingDao().getAll(runId, groupId)
        assertEquals(1, stagingEntries.count { it.type == "DOC" && it.status == "pending" })
        assertEquals(1, stagingEntries.count { it.type == "DEATH_NOTE" && it.status == "promoted" })
        assertEquals(1, db.knowledgeBaseDao().getById("phase-0-kb")?.citationCount)
        assertTrue(remainingBudget in 0.0..<120.0)
        assertEquals(
            1,
            db.eventLogDao().getLatestByType(runId, groupId, EventTypes.AGENT_SPAWN, 10).size
        )
        assertEquals(
            1,
            db.eventLogDao().getLatestByType(runId, groupId, EventTypes.AGENT_DEATH, 10).size
        )

        lifecycleManager.terminateAll()
    }

    private suspend fun executeTool(tool: Any, args: Any): String =
        tool::class.members.first { it.name == "execute" }.callSuspend(tool, args) as String
}
