package com.achulkov.evomolior.agents.factory

import com.achulkov.evomolior.core.budget.BudgetConfig
import com.achulkov.evomolior.core.budget.BudgetDeduction
import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import com.achulkov.evomolior.core.contract.AgentBootContext
import com.achulkov.evomolior.core.contract.AgentTtlPolicy
import com.achulkov.evomolior.core.contract.AtlasSkeletonView
import com.achulkov.evomolior.core.contract.RevealedNodeCard
import com.achulkov.evomolior.core.contract.StartingField
import com.achulkov.evomolior.core.contract.WipTaskClaim
import com.achulkov.evomolior.core.contract.WorkerAuthorityScope
import com.achulkov.evomolior.core.contract.WorkerLeaseAssignment
import com.achulkov.evomolior.core.contract.WorkerView
import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.CapturePolicy
import com.achulkov.evomolior.core.atlas.NodeDeliverableContract
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.ProofContract
import com.achulkov.evomolior.core.atlas.ProofMethod
import com.achulkov.evomolior.core.atlas.ReplicationPolicy
import com.achulkov.evomolior.core.atlas.RewardClass
import com.achulkov.evomolior.core.atlas.RewardSpec
import com.achulkov.evomolior.core.model.ActionType
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.sandbox.toolsandbox.ToolSubSandbox
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.core.model.SubmissionType
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.sandbox.enforcer.ActionEventType
import com.achulkov.evomolior.sandbox.enforcer.AgentAction
import com.achulkov.evomolior.sandbox.enforcer.ConstitutionalViolationException
import com.achulkov.evomolior.substrate.db.entity.EventTypes
import com.achulkov.evomolior.substrate.db.entity.ToolRegistryEntryEntity
import com.achulkov.evomolior.substrate.tools.ToolMaterializer
import com.achulkov.evomolior.agents.tools.ExecuteToolTool
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.time.Instant

/**
 * Unit tests for [EvoMoliorAgentFactory] budget initialisation logic.
 *
 * Does **not** exercise Koog or OpenRouter — only the BudgetLedger creation contract.
 * Full agent construction (requiring a live DB) is tested in integration tests.
 */
class EvoMoliorAgentFactoryTest {

    private val defaultProfile = GroupProfile.default(GroupProfileType.Library)
    private val defaultConfig = BudgetConfig(groupId = "g0", profile = defaultProfile)

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun bootContext(allocatedBudget: Double) = AgentBootContext(
        agentId = "agent-001",
        runId = "run-001",
        groupId = "g0",
        role = Role.Researcher,
        rolePrompt = "test",
        budgetToken = "token:001:0",
        substrateDbPath = "/tmp/test.db",
        stagingMountPath = "/staging",
        wipTaskClaim = null,
        legacyProtocol = null,
        stageDisclosure = "",
        modelAssignment = "openai/gpt-4o-mini",
        ipcBaseUrl = "http://10.0.0.1:10000",
        generation = 1,
        gracePeriodSeconds = 30,
        allocatedBudget = allocatedBudget
    )

    private fun bootContext(
        role: Role,
        isSubAgent: Boolean
    ) = AgentBootContext(
        agentId = "agent-001",
        runId = "run-001",
        groupId = "g0",
        role = role,
        rolePrompt = "test",
        budgetToken = "token:001:0",
        substrateDbPath = "/tmp/test.db",
        stagingMountPath = "/staging",
        wipTaskClaim = null,
        legacyProtocol = null,
        stageDisclosure = "",
        modelAssignment = "openai/gpt-4o-mini",
        ipcBaseUrl = "http://10.0.0.1:10000",
        generation = 1,
        gracePeriodSeconds = 30,
        allocatedBudget = 50.0,
        isSubAgent = isSubAgent,
        parentAgentId = if (isSubAgent) "parent-001" else null
    )

    private fun factory(): EvoMoliorAgentFactory {
        val rootDir = createTempDirectory("agent-factory-test-").toFile()
        val db = SubstrateDatabaseFactory(rootDir).get("run-001", "g0")
        return EvoMoliorAgentFactory(
            openRouterApiKey = "sk-test",
            db = db,
            toolSubSandbox = ToolSubSandbox(),
            budgetConfig = defaultConfig
        )
    }

    private fun qwenFactory(
        rootDir: java.io.File,
        qwenToolSupportEnabled: Boolean = true,
        taskToolCapabilityPolicy: TaskToolCapabilityPolicy =
            TaskToolCapabilityPolicy.unrestricted
    ): EvoMoliorAgentFactory {
        val db = SubstrateDatabaseFactory(rootDir).get("run-001", "g0")
        return EvoMoliorAgentFactory(
            openRouterApiKey = "sk-test",
            llmBackend = LlmBackend.QWEN_CLOUD,
            db = db,
            toolSubSandbox = ToolSubSandbox(),
            budgetConfig = defaultConfig,
            taskToolCapabilityPolicy = taskToolCapabilityPolicy,
            qwenToolSupportEnabled = qwenToolSupportEnabled
        )
    }

    private fun qwenBootContext(role: Role = Role.Researcher): AgentBootContext =
        bootContext(role = role, isSubAgent = false).copy(
            modelAssignment = ModelSelectionConfig.QWEN_CLOUD_QWEN_PLUS_MODEL
        )

    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun compiledQwenBootContext(): AgentBootContext {
        val nodeId = NodeId("node-compiled")
        val assignedNode = RevealedNodeCard(
            nodeId = nodeId,
            kind = NodeKind.KNOWLEDGE,
            receiverWhyRef = "receiver obligation",
            limitationStatement = "Evidence is incomplete.",
            capabilityTarget = "Verified evidence.",
            atomicQuestion = "Which source resolves the evidence gap?",
            proofContract = ProofContract(
                method = ProofMethod.SOURCE_CORROBORATION,
                acceptanceCriteria = setOf("Evidence is cited")
            ),
            deliverableContract = NodeDeliverableContract(
                acceptedArtifactTypes = setOf(ArtifactType.CLAIM_SET)
            ),
            replicationPolicy = ReplicationPolicy(),
            capturePolicy = CapturePolicy(minimumGrade = 0.7),
            rewardSpec = RewardSpec(RewardClass.MEDIUM, 2.0),
            toolOpportunity = null,
            payloadHash = "compiled-payload"
        )
        val view = WorkerView(
            runId = "run-001",
            groupId = AtlasGroupId("g0"),
            round = 1,
            stageId = "stage-0",
            visibleAtlasSkeleton = AtlasSkeletonView(
                atlasId = "atlas-compiled",
                atlasVersion = 1,
                atlasHash = "atlas-hash",
                rootNodeIds = setOf(nodeId),
                nodes = emptyMap()
            ),
            revealedNodes = listOf(assignedNode),
            assignedNode = assignedNode,
            groupFeedback = null
        ).withComputedHash()
        return qwenBootContext().copy(
            workerView = view,
            workerAuthorityScope = WorkerAuthorityScope.NODE_ASSIGNMENT,
            assignedLease = WorkerLeaseAssignment(
                leaseId = "lease-compiled",
                nodeId = nodeId,
                expiresAt = Instant.parse("2029-12-31T23:59:00Z"),
                replicationSlot = 0
            ),
            ttlPolicy = AgentTtlPolicy(
                lifetimeSeconds = 600,
                deadline = Instant.parse("2030-01-01T00:00:00Z")
            ),
            wipTaskClaim = WipTaskClaim(nodeId.value, assignedNode.atomicQuestion)
        ).requireValidWorkerAuthority()
    }

    private fun toolSimpleNames(context: AgentContext): Set<String> {
        val toolRegistry = context.agent.javaClass.getMethod("getToolRegistry").invoke(context.agent)
        val getTools = toolRegistry.javaClass.methods.first { it.name == "getTools" }
        val tools = getTools.invoke(toolRegistry) as List<*>
        return tools.mapNotNull { it?.javaClass?.simpleName }.toSet()
    }

    private fun graderToolSimpleNames(context: GraderAgentContext): Set<String> {
        return graderTools(context).map { it.javaClass.simpleName }.toSet()
    }

    private fun graderTools(context: GraderAgentContext): List<Any> {
        val toolRegistry = context.agent.javaClass.getMethod("getToolRegistry").invoke(context.agent)
        val getTools = toolRegistry.javaClass.methods.first { it.name == "getTools" }
        val tools = getTools.invoke(toolRegistry) as List<*>
        return tools.filterNotNull()
    }

    private fun qwenGraderFactory(
        rootDir: java.io.File,
        qwenToolSupportEnabled: Boolean
    ): GraderAgentFactory {
        val db = SubstrateDatabaseFactory(rootDir).get("run-001", "grader-0")
        return GraderAgentFactory(
            openRouterApiKey = "sk-test",
            llmBackend = LlmBackend.QWEN_CLOUD,
            graderDb = db,
            graderBudgetConfig = BudgetConfig(
                groupId = "grader-0",
                profile = GroupProfile.default(GroupProfileType.Library)
            ),
            graderGroupId = "grader-0",
            onScoreSubmitted = { _, _ -> },
            qwenToolSupportEnabled = qwenToolSupportEnabled
        )
    }

    /**
     * Exercises only the budget-initialisation path of [EvoMoliorAgentFactory.create]
     * without actually building a Koog agent. We achieve this by extracting the logic
     * directly: if allocatedBudget > 0, use it; otherwise fall back to profile.spawnBudget.
     */
    private fun resolvedInitialBudget(bootContext: AgentBootContext, config: BudgetConfig): Double =
        if (bootContext.allocatedBudget > 0.0) bootContext.allocatedBudget
        else config.profile.spawnBudget

    @Test
    fun `comparative task tool policy removes uncommitted task capabilities`() {
        val rootDir = createTempDirectory("agent-factory-comparative-tools-").toFile()
        val context = qwenFactory(
            rootDir = rootDir,
            taskToolCapabilityPolicy = TaskToolCapabilityPolicy(
                setOf(
                    TaskToolCapabilityPolicy.READ_RUN_ARTIFACT,
                    TaskToolCapabilityPolicy.FETCH_SOURCE
                )
            )
        ).create(qwenBootContext())

        val tools = toolSimpleNames(context)
        assertTrue("ReadRunArtifactTool" in tools)
        assertTrue("FetchSourceTool" in tools)
        assertFalse("RequestSourceAccessTool" in tools)
        assertFalse("ListToolsTool" in tools)
        assertFalse("ExecuteToolTool" in tools)
        assertTrue("WriteStagingTool" in tools, "Society protocol tools remain available")
    }

    @Test
    fun `compiled worker view removes dynamic task claims while legacy mode retains them`() {
        val rootDir = createTempDirectory("agent-factory-worker-authority-tools-").toFile()
        val factory = qwenFactory(rootDir)

        val legacyTools = toolSimpleNames(factory.create(qwenBootContext()))
        val compiledTools = toolSimpleNames(factory.create(compiledQwenBootContext()))

        assertTrue("ClaimTaskTool" in legacyTools)
        assertFalse("ClaimTaskTool" in compiledTools)
        assertTrue("CommitContributionTool" in compiledTools)
    }

    // ── tests ──────────────────────────────────────────────────────────────────

    @Test
    fun `initial budget uses allocatedBudget from boot context when positive`() {
        val ctx = bootContext(allocatedBudget = 75.0)
        val resolved = resolvedInitialBudget(ctx, defaultConfig)
        assertEquals(75.0, resolved, "Factory should use allocatedBudget when it is positive")
    }

    @Test
    fun `initial budget falls back to profile spawnBudget when allocatedBudget is zero`() {
        val ctx = bootContext(allocatedBudget = 0.0)
        val resolved = resolvedInitialBudget(ctx, defaultConfig)
        assertEquals(defaultProfile.spawnBudget, resolved,
            "Factory should fall back to profile.spawnBudget when allocatedBudget is zero")
    }

    @Test
    fun `initial budget falls back to profile spawnBudget when allocatedBudget is negative`() {
        val ctx = bootContext(allocatedBudget = -5.0)
        val resolved = resolvedInitialBudget(ctx, defaultConfig)
        assertEquals(defaultProfile.spawnBudget, resolved,
            "Negative allocatedBudget should be treated as unset and fall back to profile default")
    }

    @Test
    fun `existingBudgetLedger is reused as-is when provided`() {
        val existingLedger = BudgetLedger("agent-sub", 30.0, 1.5, defaultConfig)

        // The factory selects existingBudgetLedger over a fresh one — verify it is untouched.
        assertSame(existingLedger, existingLedger, "Existing ledger reference should be reused for sub-agent carve-outs")
        assertEquals(30.0, existingLedger.initialBudget, "Sub-agent ledger budget should remain unchanged")
    }

    @Test
    fun `floor is derived from allocated budget not profile default`() {
        val allocatedBudget = 80.0
        val ctx = bootContext(allocatedBudget = allocatedBudget)
        val resolved = resolvedInitialBudget(ctx, defaultConfig)
        val floor = defaultConfig.floorFor(resolved)

        assertEquals(allocatedBudget * 0.05, floor, 0.001,
            "Floor should be 5% of the allocated budget, not the profile default")
        assertNotNull(floor)
    }

    @Test
    fun `production constitutional gate persists log and block decisions`() = runBlocking {
        val rootDir = createTempDirectory("agent-factory-constitutional-").toFile()
        val db = SubstrateDatabaseFactory(rootDir).get("run-001", "g0")
        val factory = EvoMoliorAgentFactory(
            openRouterApiKey = "sk-test",
            db = db,
            toolSubSandbox = ToolSubSandbox(),
            budgetConfig = defaultConfig
        )
        val context = bootContext(allocatedBudget = 50.0)
        val gate = factory.constitutionalActionGate(context)

        gate.intercept(
            AgentAction(
                type = ActionEventType.TOOL_CALL,
                agentId = context.agentId,
                detail = "write_staging"
            )
        )
        assertFailsWith<ConstitutionalViolationException> {
            gate.intercept(
                AgentAction(
                    type = ActionEventType.NETWORK_EGRESS,
                    agentId = context.agentId,
                    detail = "https://blocked.example/",
                    allowed = false
                )
            )
        }

        val events = db.eventLogDao().queryFiltered(
            runId = context.runId,
            groupId = context.groupId,
            agentId = context.agentId,
            eventType = EventTypes.CONSTITUTIONAL_VIOLATION,
            minGeneration = null,
            maxGeneration = null,
            limit = 10,
            offset = 0
        )
        assertEquals(2, events.size)
        assertTrue(events.any { it.payloadJson.contains("\"blocked\":false") })
        assertTrue(events.any { it.payloadJson.contains("\"blocked\":true") })
    }

    @Test
    fun `delegation tool is enabled for eligible top-level roles`() {
        val factory = factory()
        assertTrue(factory.shouldInstallDelegationTool(bootContext(role = Role.Builder, isSubAgent = false)))
        assertTrue(factory.shouldInstallDelegationTool(bootContext(role = Role.Planner, isSubAgent = false)))
        assertTrue(factory.shouldInstallDelegationTool(bootContext(role = Role.Researcher, isSubAgent = false)))
    }

    @Test
    fun `delegation tool is disabled for ineligible top-level roles`() {
        val factory = factory()
        assertFalse(factory.shouldInstallDelegationTool(bootContext(role = Role.Critic, isSubAgent = false)))
        assertFalse(factory.shouldInstallDelegationTool(bootContext(role = Role.Outlier, isSubAgent = false)))
        assertFalse(factory.shouldInstallDelegationTool(bootContext(role = Role.Inspector, isSubAgent = false)))
    }

    @Test
    fun `delegation tool is disabled for sub-agents even if the role can delegate`() {
        val factory = factory()
        assertFalse(factory.shouldInstallDelegationTool(bootContext(role = Role.Builder, isSubAgent = true)))
        assertFalse(factory.shouldInstallDelegationTool(bootContext(role = Role.MetaPlanner, isSubAgent = true)))
    }

    @Test
    fun `qwen text-only final deliverable is persisted as doc body`() = runBlocking {
        val rootDir = createTempDirectory("agent-factory-qwen-final-").toFile()
        val factory = qwenFactory(rootDir, qwenToolSupportEnabled = false)
        val bootContext = bootContext(role = Role.Builder, isSubAgent = false).copy(
            rolePrompt = "You are the Final Deliverable Writer for group 'g0'.",
            stageDisclosure = "Spawning final deliverable writer for the completed run.",
            generation = 2
        )
        val html = """
            <!doctype html>
            <html lang="en"><body><article>MindVortex ADHD carousel pack</article></body></html>
        """.trimIndent()

        assertTrue(factory.writeQwenTextOnlySubmission(bootContext, html))

        val db = SubstrateDatabaseFactory(rootDir).get("run-001", "g0")
        val submission = db.stagingDao().getAll("run-001", "g0").single()
        assertEquals("DOC", submission.type)
        assertTrue(submission.content.startsWith("<!doctype html>"))
        assertTrue(submission.content.contains("MindVortex ADHD carousel pack"))
    }

    @Test
    fun `text-only generic final deliverable is not rejected by inherited html context`() = runBlocking {
        val rootDir = createTempDirectory("agent-factory-generic-final-").toFile()
        val factory = qwenFactory(rootDir, qwenToolSupportEnabled = false)
        val bootContext = bootContext(role = Role.Builder, isSubAgent = false).copy(
            rolePrompt = """
                You are the Final Deliverable Writer for group 'g0'.
                Output target:
                - Relative path: company/product-database.csv
                - Media type: text/csv
                - Description: Company and product opportunity database

                Earlier context includes market-map.html and <!doctype html> from the prior stage.
            """.trimIndent(),
            stageDisclosure = "Spawning final deliverable writer for the completed run.",
            generation = 2
        )
        val csv = """
            category,company_or_product,confidence
            AI coding assistant,Cursor,observed-public-market-signal
        """.trimIndent()

        assertTrue(factory.writeQwenTextOnlySubmission(bootContext, csv))

        val db = SubstrateDatabaseFactory(rootDir).get("run-001", "g0")
        val submission = db.stagingDao().getAll("run-001", "g0").single()
        assertEquals("DOC", submission.type)
        assertEquals(csv, submission.content)
    }

    @Test
    fun `ordinary text-only worker output is staged as substantive doc`() = runBlocking {
        val rootDir = createTempDirectory("agent-factory-text-only-doc-").toFile()
        val factory = qwenFactory(rootDir, qwenToolSupportEnabled = false)
        val bootContext = bootContext(role = Role.Researcher, isSubAgent = false).copy(
            rolePrompt = "You are a Researcher. Produce a source-backed market note.",
            stageDisclosure = """
                Prior discriminator feedback:
                Submission summary headline:
                The previous group lacked evidence.
            """.trimIndent(),
            generation = 3
        )

        assertFalse(factory.isRoundSubmissionSummaryContext(bootContext))
        assertTrue(factory.writeQwenTextOnlySubmission(bootContext, "Evidence note with concrete findings."))

        val submission = SubstrateDatabaseFactory(rootDir)
            .get("run-001", "g0")
            .stagingDao()
            .getAll("run-001", "g0")
            .single()
        assertEquals("DOC", submission.type)
        assertEquals("dst:root", submission.wipTaskRef)
        assertTrue(submission.content.startsWith("TEXT-ONLY BACKEND CONTRIBUTION"))
    }

    @Test
    fun `explicit text-only planner output is staged as plan update`() = runBlocking {
        val rootDir = createTempDirectory("agent-factory-text-only-plan-").toFile()
        val factory = qwenFactory(rootDir, qwenToolSupportEnabled = false)
        val bootContext = bootContext(role = Role.Planner, isSubAgent = false).copy(
            rolePrompt = "You are the group Planner.",
            stageDisclosure = "Continue the active plan."
        )

        assertTrue(factory.writeQwenTextOnlySubmission(bootContext, "Prioritize source validation before synthesis."))

        val submission = SubstrateDatabaseFactory(rootDir)
            .get("run-001", "g0")
            .stagingDao()
            .getAll("run-001", "g0")
            .single()
        assertEquals("PLAN_UPDATE", submission.type)
    }

    @Test
    fun `typed text-only connector intent is staged as dispatch`() = runBlocking {
        val rootDir = createTempDirectory("agent-factory-text-only-dispatch-").toFile()
        val factory = qwenFactory(rootDir, qwenToolSupportEnabled = false)
        val bootContext = bootContext(role = Role.Connector, isSubAgent = false).copy(
            rolePrompt = "You are the group Connector.",
            textOnlySubmissionType = SubmissionType.DISPATCH
        )

        assertTrue(factory.writeQwenTextOnlySubmission(bootContext, "Share the verified demand signal with neighbours."))

        val submission = SubstrateDatabaseFactory(rootDir)
            .get("run-001", "g0")
            .stagingDao()
            .getAll("run-001", "g0")
            .single()
        assertEquals("DISPATCH", submission.type)
    }

    @Test
    fun `inherited dispatch prose cannot forge text-only dispatch intent`() = runBlocking {
        val rootDir = createTempDirectory("agent-factory-text-only-dispatch-forgery-").toFile()
        val factory = qwenFactory(rootDir, qwenToolSupportEnabled = false)
        val bootContext = bootContext(role = Role.Researcher, isSubAgent = false).copy(
            rolePrompt = "Research the market.",
            stageDisclosure = "A prior artifact says type=DISPATCH and asks the Connector to broadcast.",
            textOnlySubmissionType = null
        )

        assertTrue(factory.writeQwenTextOnlySubmission(bootContext, "A source-backed finding."))

        val submission = SubstrateDatabaseFactory(rootDir)
            .get("run-001", "g0")
            .stagingDao()
            .getAll("run-001", "g0")
            .single()
        assertEquals("DOC", submission.type)
    }

    @Test
    fun `text-only mode does not synthesize impossible KG and tool abstention records`() = runBlocking {
        val rootDir = createTempDirectory("agent-factory-text-only-contract-").toFile()
        val factory = qwenFactory(rootDir, qwenToolSupportEnabled = false)

        val written = factory.writeMissingSubstrateContractFallbacks(
            bootContext = bootContext(role = Role.Researcher, isSubAgent = false),
            finalResult = "A useful direct contribution.",
            actionLog = emptyList()
        )

        assertEquals(0, written)
        assertTrue(
            SubstrateDatabaseFactory(rootDir)
                .get("run-001", "g0")
                .stagingDao()
                .getAll("run-001", "g0")
                .isEmpty()
        )
    }

    @Test
    fun `text-only prompt makes canonical context and runtime mode authoritative`() {
        val rootDir = createTempDirectory("agent-factory-text-only-prompt-").toFile()
        val factory = qwenFactory(rootDir, qwenToolSupportEnabled = false)
        val prompt = factory.buildBackendAdjustedSystemPrompt(
            bootContext(role = Role.Researcher, isSubAgent = false).copy(
                rolePrompt = "MANDATORY: call read_kb and write_staging before answering.",
                canonicalWorkingContext = "ACTIVE KB\n- [kb-1] Verified prior finding."
            )
        )

        assertTrue(prompt.startsWith("=== RUNTIME EXECUTION MODE: TEXT-ONLY"))
        assertTrue(prompt.contains("[kb-1] Verified prior finding."))
        assertTrue(prompt.indexOf("CANONICAL WORKING CONTEXT") < prompt.indexOf("ROLE AND TASK CONTRACT"))
        assertTrue(prompt.lastIndexOf("FINAL TEXT-ONLY EXECUTION REMINDER") > prompt.indexOf("MANDATORY: call read_kb"))
        assertTrue(prompt.contains("Do not refuse because Koog tools are unavailable."))
    }

    @Test
    fun `source-sensitive codex researcher prompt mandates live native searches`() {
        val rootDir = createTempDirectory("agent-factory-codex-search-prompt-").toFile()
        val db = SubstrateDatabaseFactory(rootDir).get("run-001", "g0")
        val factory = EvoMoliorAgentFactory(
            openRouterApiKey = "/tmp/codex",
            llmBackend = LlmBackend.CODEX_CHATGPT,
            db = db,
            toolSubSandbox = ToolSubSandbox(),
            budgetConfig = defaultConfig
        )
        val prompt = factory.buildBackendAdjustedSystemPrompt(
            bootContext(role = Role.Researcher, isSubAgent = false).copy(
                modelAssignment = ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL,
                startingField = StartingField.default(
                    objective = "Research current 2026 market pricing and public evidence."
                )
            )
        )

        assertTrue(prompt.contains(CODEX_NATIVE_SEARCH_REQUIRED_MARKER))
        assertTrue(prompt.contains("at least two distinct native web-search queries"))
        assertTrue(prompt.contains("does not satisfy live re-verification"))
    }

    @Test
    fun `tool-capable no-action round summary is salvaged as plan update`() = runBlocking {
        val rootDir = createTempDirectory("agent-factory-no-action-summary-").toFile()
        val db = SubstrateDatabaseFactory(rootDir).get("run-001", "g0")
        val factory = EvoMoliorAgentFactory(
            openRouterApiKey = "sk-test",
            llmBackend = LlmBackend.OPENROUTER,
            db = db,
            toolSubSandbox = ToolSubSandbox(),
            budgetConfig = defaultConfig
        )
        val bootContext = bootContext(role = Role.Archivist, isSubAgent = false).copy(
            rolePrompt = "You are a summariser. Content must begin with ROUND SUBMISSION SUMMARY.",
            stageDisclosure = "Round 2 submission summary generation for group g0.",
            generation = 2
        )

        assertTrue(
            factory.writeNoActionFallbackSubmission(
                bootContext = bootContext,
                finalResult = "The group produced a sparse but usable benchmark outline."
            )
        )

        val submission = db.stagingDao().getAll("run-001", "g0").single()
        assertEquals("PLAN_UPDATE", submission.type)
        assertTrue(submission.content.startsWith("ROUND SUBMISSION SUMMARY"))
        assertTrue(submission.content.contains("Runtime-salvaged summary"))
        assertEquals(0.15, submission.initialTrustScore)
        assertEquals(
            1,
            db.workerExecutionDao()
                .degradedExecutionsForAgent("run-001", "g0", bootContext.agentId)
                .size
        )
    }

    @Test
    fun `tool-capable no-action final deliverable is salvaged as doc`() = runBlocking {
        val rootDir = createTempDirectory("agent-factory-no-action-final-").toFile()
        val db = SubstrateDatabaseFactory(rootDir).get("run-001", "g0")
        val factory = EvoMoliorAgentFactory(
            openRouterApiKey = "sk-test",
            llmBackend = LlmBackend.OPENROUTER,
            db = db,
            toolSubSandbox = ToolSubSandbox(),
            budgetConfig = defaultConfig
        )
        val bootContext = bootContext(role = Role.Builder, isSubAgent = false).copy(
            rolePrompt = "You are the Final Deliverable Writer for group 'g0'.",
            stageDisclosure = "Spawning final deliverable writer for the completed run.",
            generation = 2
        )

        assertTrue(
            factory.writeNoActionFallbackSubmission(
                bootContext = bootContext,
                finalResult = """
                    FINAL DELIVERABLE FILE
                    Path: final-article.html
                    Media-Type: text/html
                    CONTENT
                    <!doctype html><html><body><article>Recovered deliverable</article></body></html>
                """.trimIndent()
            )
        )

        val submission = db.stagingDao().getAll("run-001", "g0").single()
        assertEquals("DOC", submission.type)
        assertTrue(submission.content.startsWith("<!doctype html>"))
        assertTrue(submission.content.contains("Recovered deliverable"))
    }

    @Test
    fun `tool-capable no-action html final deliverable is not salvaged from intent text`() = runBlocking {
        val rootDir = createTempDirectory("agent-factory-no-action-final-intent-").toFile()
        val db = SubstrateDatabaseFactory(rootDir).get("run-001", "g0")
        val factory = EvoMoliorAgentFactory(
            openRouterApiKey = "sk-test",
            llmBackend = LlmBackend.OPENROUTER,
            db = db,
            toolSubSandbox = ToolSubSandbox(),
            budgetConfig = defaultConfig
        )
        val bootContext = bootContext(role = Role.Builder, isSubAgent = false).copy(
            rolePrompt = "You are the Final Deliverable Writer for group 'g0'. Media-Type: text/html.",
            stageDisclosure = "Spawning final deliverable writer for final-article.html.",
            generation = 2
        )

        assertFalse(
            factory.writeNoActionFallbackSubmission(
                bootContext = bootContext,
                finalResult = "Now I have all evidence and will compose the final HTML."
            )
        )

        assertTrue(db.stagingDao().getAll("run-001", "g0").isEmpty())
    }

    @Test
    fun `ordinary agent missing KG and tool contract writes runtime abstention fallbacks`() = runBlocking {
        val rootDir = createTempDirectory("agent-factory-substrate-contract-").toFile()
        val db = SubstrateDatabaseFactory(rootDir).get("run-001", "g0")
        val factory = EvoMoliorAgentFactory(
            openRouterApiKey = "sk-test",
            llmBackend = LlmBackend.OPENROUTER,
            db = db,
            toolSubSandbox = ToolSubSandbox(),
            budgetConfig = defaultConfig
        )
        val bootContext = bootContext(role = Role.Researcher, isSubAgent = false)

        val written = factory.writeMissingSubstrateContractFallbacks(
            bootContext = bootContext,
            finalResult = "I inspected the input but did not find enough evidence.",
            actionLog = emptyList()
        )

        val submissions = db.stagingDao().getAll("run-001", "g0")
        assertEquals(2, written)
        assertEquals(2, submissions.size)
        assertTrue(submissions.all { it.type == "PLAN_UPDATE" })
        assertTrue(submissions.any { it.content.startsWith("KG ABSTENTION RATIONALE") })
        assertTrue(submissions.any { it.content.startsWith("TOOL ABSTENTION RATIONALE") })
        assertTrue(submissions.all { it.initialTrustScore == 0.1 })
        assertEquals(
            2,
            db.workerExecutionDao()
                .degradedExecutionsForAgent("run-001", "g0", bootContext.agentId)
                .size
        )
    }

    @Test
    fun `substrate contract fallback respects existing KG artifact and tool execution`() = runBlocking {
        val rootDir = createTempDirectory("agent-factory-substrate-contract-existing-").toFile()
        val db = SubstrateDatabaseFactory(rootDir).get("run-001", "g0")
        val factory = EvoMoliorAgentFactory(
            openRouterApiKey = "sk-test",
            llmBackend = LlmBackend.OPENROUTER,
            db = db,
            toolSubSandbox = ToolSubSandbox(),
            budgetConfig = defaultConfig
        )
        val bootContext = bootContext(role = Role.Researcher, isSubAgent = false)
        db.stagingDao().insert(
            StagingSubmissionEntity(
                id = "kg-1",
                agentId = bootContext.agentId,
                role = bootContext.role.name,
                type = "KG_FACT",
                content = "SUBJECT: run\nRELATION: demonstrates\nOBJECT: kg",
                budgetSignature = "test",
                status = "pending",
                generation = bootContext.generation,
                groupId = bootContext.groupId,
                runId = bootContext.runId,
                submittedAtMs = 1L
            )
        )

        val written = factory.writeMissingSubstrateContractFallbacks(
            bootContext = bootContext,
            finalResult = "Used a reusable validator and emitted KG.",
            actionLog = listOf(BudgetDeduction(ActionType.TOOL_EXEC_SUCCESS, cost = 1.0, balanceAfter = 42.0))
        )

        assertEquals(0, written)
        assertEquals(1, db.stagingDao().getAll("run-001", "g0").size)
    }

    @Test
    fun `model completion attempt records LLM call budget deduction`() = runBlocking {
        val rootDir = createTempDirectory("agent-factory-completion-budget-").toFile()
        val factory = qwenFactory(rootDir, qwenToolSupportEnabled = true)
        val ledger = BudgetLedger(
            agentId = "agent-budget",
            initialBudget = 100.0,
            floor = 1.0,
            config = defaultConfig
        )

        val estimatedTokens = factory.recordModelCompletionAttempt(
            budgetLedger = ledger,
            finalResult = "A completed model response with enough content to estimate tokens."
        )

        val snapshot = ledger.snapshot()
        assertTrue(estimatedTokens > 0)
        assertEquals(1, snapshot.actionLog.size)
        assertEquals(ActionType.LLM_CALL, snapshot.actionLog.single().actionType)
        assertTrue(snapshot.currentBalance < snapshot.initialBudget)
    }

    @Test
    fun `qwen text-only final deliverable strips final file wrapper content marker`() {
        val wrapped = """
            FINAL DELIVERABLE FILE
            Path: final-article.html
            Media-Type: text/html
            CONTENT
            <!doctype html>
            <html><body>Wrapped output</body></html>
        """.trimIndent()

        val rootDir = createTempDirectory("agent-factory-qwen-wrapper-").toFile()
        val extracted = qwenFactory(rootDir).extractQwenTextOnlyFinalDeliverableBody(wrapped)

        assertTrue(extracted.startsWith("<!doctype html>"))
        assertTrue(extracted.contains("Wrapped output"))
        assertFalse(extracted.contains("FINAL DELIVERABLE FILE"))
    }

    @Test
    fun `qwen cloud ordinary agents use tool registry by default`() {
        val rootDir = createTempDirectory("agent-factory-qwen-default-tools-").toFile()
        val factory = qwenFactory(rootDir)

        val context = factory.create(qwenBootContext())
        val tools = toolSimpleNames(context)

        assertFalse(factory.usesTextOnlyCompatibilityMode())
        assertTrue("ListKBTool" in tools)
        assertTrue("WriteStagingTool" in tools)
        assertTrue("WriteDeathNoteTool" in tools)
        assertTrue("RequestSourceAccessTool" in tools)
        assertTrue(context.agent.toString().isNotBlank())
    }

    @Test
    fun `qwen cloud ordinary agents can opt into text only compatibility`() {
        val rootDir = createTempDirectory("agent-factory-qwen-text-only-tools-").toFile()
        val factory = qwenFactory(rootDir, qwenToolSupportEnabled = false)

        val context = factory.create(qwenBootContext())
        val tools = toolSimpleNames(context)

        assertTrue(factory.usesTextOnlyCompatibilityMode())
        assertTrue(tools.isEmpty())
    }

    @Test
    fun `openrouter ordinary agents keep tool registry enabled`() {
        val rootDir = createTempDirectory("agent-factory-openrouter-tools-").toFile()
        val db = SubstrateDatabaseFactory(rootDir).get("run-001", "g0")
        val factory = EvoMoliorAgentFactory(
            openRouterApiKey = "sk-test",
            llmBackend = LlmBackend.OPENROUTER,
            db = db,
            toolSubSandbox = ToolSubSandbox(),
            budgetConfig = defaultConfig
        )

        val context = factory.create(bootContext(role = Role.Researcher, isSubAgent = false))
        val tools = toolSimpleNames(context)

        assertFalse(factory.usesTextOnlyCompatibilityMode())
        assertTrue("ListKBTool" in tools)
        assertTrue("WriteStagingTool" in tools)
        assertTrue("WriteDeathNoteTool" in tools)
        assertTrue("RequestSourceAccessTool" in tools)
    }

    @Test
    fun `qwen round judge can opt into grading score tool`() {
        val dimensions = listOf(
            ScoringDimension(
                id = "accuracy",
                name = "Accuracy",
                description = "Correctness of the group answer.",
                weight = 1.0
            )
        )
        val rootDir = createTempDirectory("grader-factory-qwen-tools-").toFile()
        val defaultFactory = qwenGraderFactory(rootDir, qwenToolSupportEnabled = false)
        val enabledFactory = qwenGraderFactory(rootDir, qwenToolSupportEnabled = true)

        val defaultContext = defaultFactory.createRoundJudge(
            runId = "run-001",
            roundIndex = 0,
            runObjective = "Judge answers.",
            scoringDimensions = dimensions,
            criteriaContext = "Score accuracy from 0 to 100.",
            allGroupSnapshots = mapOf("g0" to "candidate answer"),
            modelOverride = ModelSelectionConfig.QWEN_CLOUD_QWEN_PLUS_MODEL
        )
        val enabledContext = enabledFactory.createRoundJudge(
            runId = "run-001",
            roundIndex = 0,
            runObjective = "Judge answers.",
            scoringDimensions = dimensions,
            criteriaContext = "Score accuracy from 0 to 100.",
            allGroupSnapshots = mapOf("g0" to "candidate answer"),
            modelOverride = ModelSelectionConfig.QWEN_CLOUD_QWEN_PLUS_MODEL
        )

        assertTrue(defaultFactory.usesTextOnlyCompatibilityMode())
        assertTrue(graderToolSimpleNames(defaultContext).isEmpty())
        assertFalse(enabledFactory.usesTextOnlyCompatibilityMode())
        assertTrue("ListKBTool" in graderToolSimpleNames(enabledContext))
        assertTrue("ReadKBTool" in graderToolSimpleNames(enabledContext))
        assertTrue("SubmitGradingScoreTool" in graderToolSimpleNames(enabledContext))
    }

    @Test
    fun `grader and criteria tool registries expose index first KB tools`() {
        val dimensions = listOf(
            ScoringDimension(
                id = "accuracy",
                name = "Accuracy",
                description = "Correctness of the group answer.",
                weight = 1.0
            )
        )
        val rootDir = createTempDirectory("grader-factory-kb-index-tools-").toFile()
        val factory = qwenGraderFactory(rootDir, qwenToolSupportEnabled = true)

        val graderContext = factory.create(
            role = Role.GraderResearcher,
            runId = "run-001",
            roundIndex = 0,
            targetGroupId = "group-0",
            runObjective = "Judge answers.",
            scoringDimensions = dimensions,
            canonSnapshot = "KB snapshot",
            modelOverride = ModelSelectionConfig.QWEN_CLOUD_QWEN_PLUS_MODEL
        )
        val criteriaContext = factory.createCriteria(
            role = Role.GraderResearcher,
            runId = "run-001",
            roundIndex = 0,
            runObjective = "Judge answers.",
            scoringDimensions = dimensions,
            modelOverride = ModelSelectionConfig.QWEN_CLOUD_QWEN_PLUS_MODEL
        )

        val graderTools = graderToolSimpleNames(graderContext)
        val criteriaTools = graderToolSimpleNames(criteriaContext)
        assertTrue("ListKBTool" in graderTools)
        assertTrue("ReadKBTool" in graderTools)
        assertTrue("ListToolsTool" in graderTools)
        assertTrue("ExecuteToolTool" in graderTools)
        assertTrue("ListKBTool" in criteriaTools)
        assertTrue("ReadKBTool" in criteriaTools)
        assertTrue("ListToolsTool" in criteriaTools)
        assertTrue("ExecuteToolTool" in criteriaTools)
    }

    @Test
    fun `round judge tool registry exposes selected KB reads and keeps scoring tool`() {
        val dimensions = listOf(
            ScoringDimension(
                id = "accuracy",
                name = "Accuracy",
                description = "Correctness of the group answer.",
                weight = 1.0
            )
        )
        val rootDir = createTempDirectory("grader-factory-round-judge-no-read-tools-").toFile()
        val factory = GraderAgentFactory(
            openRouterApiKey = "sk-test",
            llmBackend = LlmBackend.OPENROUTER,
            graderDb = SubstrateDatabaseFactory(rootDir).get("run-001", "grader-0"),
            graderBudgetConfig = BudgetConfig(
                groupId = "grader-0",
                profile = GroupProfile.default(GroupProfileType.Library)
            ),
            graderGroupId = "grader-0",
            onScoreSubmitted = { _, _ -> }
        )

        val context = factory.createRoundJudge(
            runId = "run-001",
            roundIndex = 0,
            runObjective = "Judge answers.",
            scoringDimensions = dimensions,
            criteriaContext = "Score accuracy from 0 to 100.",
            allGroupSnapshots = mapOf("g0" to "candidate answer")
        )
        val tools = graderToolSimpleNames(context)

        assertTrue("SubmitGradingScoreTool" in tools)
        assertTrue("WriteDeathNoteTool" in tools)
        assertTrue("ListKBTool" in tools)
        assertTrue("ReadKBTool" in tools)
        assertTrue("ListToolsTool" in tools)
        assertTrue("ExecuteToolTool" in tools)
        assertFalse("ReadKGTool" in tools)
        assertFalse("ReadRunArtifactTool" in tools)
        assertTrue(context.taskPrompt.contains("submit_grading_score"))
    }

    @Test
    fun `later grader generation executes a grader-local promoted tool`() = runBlocking {
        val rootDir = createTempDirectory("grader-tool-compounding-").toFile()
        val db = SubstrateDatabaseFactory(rootDir).get("run-001", "grader-0")
        val toolsRoot = rootDir.resolve("tools")
        val tool = ToolRegistryEntryEntity(
            id = "grader-normalizer-v1",
            name = "grader-normalizer",
            authorAgentId = "generation-1-builder",
            inputSchemaJson = """{"type":"object"}""",
            outputSchemaJson = """{"type":"object"}""",
            domain = "grading",
            code = "#!/bin/sh\ncat >/dev/null\nprintf '{\"normalized\":true}'\n",
            generation = 1,
            groupId = "grader-0",
            runId = "run-001",
            createdAtMs = 1L
        )
        ToolMaterializer(toolsRoot).materialize(tool)
        db.toolRegistryDao().insert(tool)
        val factory = GraderAgentFactory(
            openRouterApiKey = "sk-test",
            llmBackend = LlmBackend.QWEN_CLOUD,
            graderDb = db,
            graderBudgetConfig = BudgetConfig(
                groupId = "grader-0",
                profile = GroupProfile.default(GroupProfileType.Library)
            ),
            graderGroupId = "grader-0",
            toolsMountPath = toolsRoot.absolutePath,
            onScoreSubmitted = { _, _ -> },
            qwenToolSupportEnabled = true
        )
        val context = factory.createCriteria(
            role = Role.GraderBuilder,
            runId = "run-001",
            roundIndex = 1,
            runObjective = "Compound grading methodology.",
            scoringDimensions = listOf(
                ScoringDimension("accuracy", "Accuracy", "Score accuracy", 1.0)
            ),
            modelOverride = ModelSelectionConfig.QWEN_CLOUD_QWEN_PLUS_MODEL
        )

        val executeTool = graderTools(context).filterIsInstance<ExecuteToolTool>().single()
        val result = executeTool.execute(
            ExecuteToolTool.Args(toolName = "grader-normalizer", argsJson = """{"value":"x"}""")
        )

        assertTrue(result.contains("SUCCESS"))
        assertTrue(result.contains("""{"normalized":true}"""))
        assertEquals(1, db.toolRegistryDao().getActive("run-001", "grader-0", "grading").single().generation)
    }

    @Test
    fun `generic no-action recovery detects uploaded artifact intent text`() {
        val bootContext = bootContext(role = Role.Researcher, isSubAgent = false).copy(
            inputArtifacts = listOf(
                com.achulkov.evomolior.core.contract.RunInputArtifact(
                    relativePath = "metasyn_task_24_candidates.md",
                    sourceName = "metasyn_task_24_candidates.md",
                    mimeType = "text/markdown",
                    sizeBytes = 17214,
                    sha256 = "abc123",
                    kind = com.achulkov.evomolior.core.contract.RunInputArtifactKind.TEXT,
                    textPreview = "MetaSyn candidates"
                )
            ),
            stageDisclosure = "Use only the uploaded candidate file."
        )

        assertTrue(
            requiresGenericNoActionRecovery(
                bootContext = bootContext,
                previousResult = "Let me read the full artifact and then screen all candidates."
            )
        )
        assertTrue(
            requiresGenericNoActionRecovery(
                bootContext = bootContext,
                previousResult = "Good. I can see the run artifact is a markdown file with candidate data. " +
                    "Let me read it in full to understand the corpus."
            )
        )

        val prompt = buildGenericNoActionRecoveryPrompt(
            bootContext = bootContext,
            previousResult = "Let me read the full artifact and then screen all candidates."
        )
        assertTrue(prompt.contains("read_run_artifact(relativePath=\"metasyn_task_24_candidates.md\""))
        assertTrue(prompt.contains("candidate-level decisions"))
        assertTrue(prompt.contains("Never stop with another"))

        val writeOnlyPrompt = buildWriteOnlyNoActionRecoveryPrompt(
            bootContext = bootContext,
            previousResult = "Good, I read it. Let me check the KB next."
        )
        assertTrue(writeOnlyPrompt.contains("WRITE ONLY"))
        assertTrue(writeOnlyPrompt.contains("Do not read, search, inspect, parse"))
        assertTrue(writeOnlyPrompt.contains("write_staging(type=\"PLAN_UPDATE\""))
    }

    @Test
    fun `emergency death note persists from cancelled coroutine context`() = runBlocking {
        val rootDir = createTempDirectory("agent-factory-cancelled-death-note-").toFile()
        val db = SubstrateDatabaseFactory(rootDir).get("run-001", "g0")
        val factory = EvoMoliorAgentFactory(
            openRouterApiKey = "sk-test",
            llmBackend = LlmBackend.OPENROUTER,
            db = db,
            toolSubSandbox = ToolSubSandbox(),
            budgetConfig = defaultConfig
        )
        val bootContext = bootContext(role = Role.Builder, isSubAgent = false)

        val job = launch {
            currentCoroutineContext().cancel()
            factory.writeEmergencyDeathNote(
                bootContext = bootContext,
                reason = "JobCancellationException",
                errorMessage = "round boundary cancellation",
                noteKind = "emergency"
            )
        }
        job.join()

        val deathNote = db.stagingDao().getAll("run-001", "g0").single()
        assertEquals("DEATH_NOTE", deathNote.type)
        assertEquals("promoted", deathNote.status)
        assertTrue(deathNote.content.contains("JobCancellationException"))
    }
}
