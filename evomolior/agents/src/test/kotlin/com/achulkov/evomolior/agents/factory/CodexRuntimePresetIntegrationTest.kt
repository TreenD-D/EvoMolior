package com.achulkov.evomolior.agents.factory

import ai.koog.prompt.dsl.prompt
import com.achulkov.evomolior.core.budget.BudgetConfig
import com.achulkov.evomolior.core.config.CodexRuntimePreset
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import com.achulkov.evomolior.core.contract.AgentBootContext
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.sandbox.toolsandbox.ToolSubSandbox
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Provider-free coverage of preset resolution, executor inputs, and bounded CLI effort. */
class CodexRuntimePresetIntegrationTest {
    private val exactModelIds = setOf(
        "gpt-5.6-luna", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-6-astra"
    )

    @Test
    fun `client advertises all exact catalog models without invented output limits`() = runBlocking {
        val client = CodexChatGptLLMClient(cliPath = "unused-codex")
        val models = client.models()

        assertEquals(exactModelIds, models.map { it.id }.toSet())
        assertEquals(4, models.size)
        models.forEach {
            assertEquals(272_000L, it.contextLength)
            assertNull(it.maxOutputTokens)
            assertEquals(it, LlmExecutorFactory.resolveModel(LlmBackend.CODEX_CHATGPT, it.id))
        }
        listOf("gpt-6", "gpt-5.6", "GPT-6-ASTRA", "gpt-6-astra ", "openai/gpt-6-astra")
            .forEach { unsupported ->
                assertFailsWith<IllegalStateException> {
                    CodexChatGptModelResolver.resolve(unsupported)
                }
            }
    }

    @Test
    fun `each exact model and bounded effort reaches process and command unchanged`() = runBlocking {
        for (modelId in exactModelIds) {
            for (effort in CodexReasoningEffort.entries) {
                val requests = mutableListOf<CodexProcessRequest>()
                val client = CodexChatGptLLMClient(
                    cliPath = "unused-codex",
                    reasoningEffort = effort,
                    executionTimeout = 17.minutes,
                    processRunner = { request ->
                        requests += request
                        Files.writeString(request.outputPath, "done")
                        CodexProcessResult(0, "", "")
                    }
                )
                client.execute(
                    prompt("preset-test") { user("Summarise supplied text.") },
                    CodexChatGptModelResolver.resolve(modelId),
                    emptyList()
                )
                val request = requests.single()
                assertEquals(modelId, request.modelId)
                assertEquals(effort, request.reasoningEffort)
                assertEquals(17.minutes, request.executionTimeout)
                val command = CodexExecProcess.command(
                    cliPath = request.cliPath,
                    modelId = request.modelId,
                    outputPath = request.outputPath,
                    workspace = Path.of("/unused-workspace"),
                    reasoningEffort = request.reasoningEffort
                )
                assertEquals(modelId, command[command.indexOf("-m") + 1])
                assertEquals(
                    listOf("model_reasoning_effort=\"${effort.configValue}\""),
                    command.filter { it.startsWith("model_reasoning_effort=") }
                )
            }
        }
        assertEquals(setOf("low", "medium", "high"), CodexReasoningEffort.entries.map { it.configValue }.toSet())
        listOf("xhigh", "max", "ultra").forEach {
            assertFailsWith<IllegalArgumentException> { CodexReasoningEffort.parse(it) }
        }
    }

    @Test
    fun `provider failure never falls back to Luna`() = runBlocking {
        val requestedModels = mutableListOf<String>()
        val client = CodexChatGptLLMClient(
            cliPath = "unused-codex",
            processRunner = { request ->
                requestedModels += request.modelId
                CodexProcessResult(1, "", "unsupported model")
            }
        )
        assertFailsWith<IllegalStateException> {
            client.execute(
                prompt("failure") { user("Return an answer.") },
                CodexChatGptModelResolver.resolve(CodexRuntimePreset.ASTRA),
                emptyList()
            )
        }
        assertEquals(listOf(CodexRuntimePreset.ASTRA), requestedModels)
    }

    @Test
    fun `full Luna factory rejects a non Luna boot before creating an executor`() {
        val root = createTempDirectory("codex-preset-boot-").toFile()
        val db = SubstrateDatabaseFactory(root).get("preset-run", "g0")
        try {
            val factory = EvoMoliorAgentFactory(
                openRouterApiKey = "provider-free",
                llmBackend = LlmBackend.CODEX_CHATGPT,
                codexRuntimePreset = CodexRuntimePreset.FULL_LUNA,
                db = db,
                toolSubSandbox = ToolSubSandbox(),
                budgetConfig = BudgetConfig("g0", GroupProfile.Library),
            )
            assertFailsWith<IllegalArgumentException> {
                factory.create(
                    AgentBootContext(
                        agentId = "worker",
                        runId = "preset-run",
                        groupId = "g0",
                        role = Role.Researcher,
                        rolePrompt = "Research the input.",
                        budgetToken = "fixture",
                        substrateDbPath = root.resolve("fixture.db").path,
                        stagingMountPath = root.resolve("staging").path,
                        wipTaskClaim = null,
                        legacyProtocol = null,
                        stageDisclosure = "",
                        modelAssignment = CodexRuntimePreset.ASTRA,
                        ipcBaseUrl = "http://127.0.0.1:1",
                        generation = 1,
                        gracePeriodSeconds = 1,
                    )
                )
            }
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `ordinary factory uses role effort bounded by ceiling and preserves null legacy`() {
        val root = createTempDirectory("codex-preset-factory-").toFile()
        val db = SubstrateDatabaseFactory(root).get("preset-run", "g0")
        try {
            for (preset in listOf<CodexRuntimePreset?>(null) + CodexRuntimePreset.entries) {
                for (ceiling in CodexReasoningEffort.entries) {
                    val factory = EvoMoliorAgentFactory(
                        openRouterApiKey = "unused-codex",
                        llmBackend = LlmBackend.CODEX_CHATGPT,
                        codexReasoningEffort = ceiling,
                        codexRuntimePreset = preset,
                        db = db,
                        toolSubSandbox = ToolSubSandbox(),
                        budgetConfig = BudgetConfig(
                            groupId = "g0",
                            profile = GroupProfile.default(GroupProfileType.Library)
                        )
                    )
                    for (role in Role.entries) {
                        val actual = factory.codexReasoningEffortFor(role)
                        val requested = when {
                            preset == null -> ceiling
                            role.isCouncilRole || role.isGraderRole ||
                                role in setOf(Role.MetaPlanner, Role.Auditor, Role.Critic) ->
                                CodexReasoningEffort.HIGH
                            role in setOf(Role.Archivist, Role.Educator, Role.Connector, Role.Inspector) ->
                                CodexReasoningEffort.LOW
                            else -> CodexReasoningEffort.MEDIUM
                        }
                        assertEquals(minOf(requested, ceiling), actual, "$preset / $role / $ceiling")
                        assertTrue(actual <= ceiling)
                    }
                }
            }
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun `council presets select Astra or Luna with high effort bounded by envelope`() {
        for (preset in CodexRuntimePreset.entries) {
            for (ceiling in CodexReasoningEffort.entries) {
                val runner = CouncilRunner(
                    openRouterApiKey = "unused-codex",
                    executionEnvelope = councilTestExecutionEnvelope().copy(
                        codexRuntimePreset = preset,
                        codexReasoningEffort = ceiling
                    ),
                    llmBackend = LlmBackend.CODEX_CHATGPT,
                    councilModelId = "must-not-be-used",
                    codexExecutionTimeout = 19.minutes
                )
                for (role in Role.entries.filter { it.isCouncilRole }) {
                    assertEquals(
                        if (preset == CodexRuntimePreset.FULL_LUNA) "gpt-5.6-luna" else "gpt-6-astra",
                        runner.councilModelFor(role).id
                    )
                    assertEquals(ceiling, runner.codexReasoningEffortFor(role))
                }
                assertEquals(19.minutes, runner.codexExecutionTimeout)
            }
        }
    }

    @Test
    fun `null council preset preserves explicit model and effort for every role`() {
        for (ceiling in CodexReasoningEffort.entries) {
            val runner = CouncilRunner(
                openRouterApiKey = "unused-codex",
                executionEnvelope = councilTestExecutionEnvelope().copy(codexReasoningEffort = ceiling),
                llmBackend = LlmBackend.CODEX_CHATGPT,
                councilModelId = CodexRuntimePreset.TERRA
            )
            for (role in Role.entries.filter { it.isCouncilRole }) {
                assertEquals(CodexRuntimePreset.TERRA, runner.councilModelFor(role).id)
                assertEquals(ceiling, runner.codexReasoningEffortFor(role))
            }
        }
    }

    @Test
    fun `Codex preset does not replace another backends council model`() {
        val runner = CouncilRunner(
            openRouterApiKey = "unused",
            executionEnvelope = councilTestExecutionEnvelope().copy(
                codexRuntimePreset = CodexRuntimePreset.MIXED_FAMILIES
            )
        )
        assertEquals(
            ModelSelectionConfig.DEFAULT_COUNCIL_MODEL,
            runner.councilModelFor(Role.CouncilTaskAnatomist).id
        )
    }
}
