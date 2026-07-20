package com.achulkov.evomolior.agents.factory

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.prompt as koogPrompt
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.serialization.typeToken
import com.achulkov.evomolior.core.budget.BudgetConfig
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import com.achulkov.evomolior.core.contract.AgentBootContext
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.sandbox.toolsandbox.ToolSubSandbox
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opt-in live smoke for DashScope tool-result continuation.
 *
 * This is skipped by default because it spends provider tokens. Run it only when validating that
 * Qwen Cloud can safely leave text-only compatibility mode:
 *
 * `EVOMOLIOR_LIVE_QWEN_TOOL_SMOKE=true ./gradlew :engine:agents:test --tests ...QwenCloudToolLoopLiveTest`
 */
class QwenCloudToolLoopLiveTest {
    @Test
    fun `qwen cloud completes a koog tool call and final continuation`() = runBlocking {
        val apiKey = requireLiveQwenToolSmokeApiKey()

        val tool = EchoProbeTool()
        val agent: AIAgent<String, String> = AIAgent.builder()
            .promptExecutor(LlmExecutorFactory.create(LlmBackend.QWEN_CLOUD, apiKey))
            .agentConfig(
                AIAgentConfig(
                    prompt = koogPrompt("qwen-cloud-tool-loop-smoke") {
                        system(
                            """
                            You are validating tool support.
                            You must call echo_probe exactly once with value EVOMOLIOR_QWEN_TOOL_OK.
                            After the tool result returns, answer with exactly:
                            FINAL: <tool result>
                            Do not answer from memory before calling the tool.
                            """.trimIndent()
                        )
                    },
                    model = LlmExecutorFactory.resolveModel(
                        LlmBackend.QWEN_CLOUD,
                        ModelSelectionConfig.QWEN_CLOUD_QWEN_PLUS_MODEL
                    ),
                    maxAgentIterations = 6,
                    serializer = KotlinxSerializer(kotlinx.serialization.json.Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    })
                )
            )
            .toolRegistry(ToolRegistry { tool(tool) })
            .graphStrategy(singleRunStrategy())
            .maxIterations(6)
            .build()

        val result = agent.run(
            "Call echo_probe with value EVOMOLIOR_QWEN_TOOL_OK, then return FINAL with the tool result."
        )

        assertEquals(1, tool.callCount.get(), "Qwen must call the probe tool exactly once.")
        assertTrue(
            result.contains("EVOMOLIOR_QWEN_TOOL_OK"),
            "Expected final answer to include the tool result, got: ${result.take(240)}"
        )
    }

    @Test
    fun `qwen cloud worker can write staging through evomolior tools`() = runBlocking {
        val apiKey = requireLiveQwenToolSmokeApiKey()
        val rootDir = createTempDirectory("qwen-worker-tool-live-").toFile()
        val db = SubstrateDatabaseFactory(rootDir).get("run-qwen-tool-live", "group-0")
        val budgetConfig = BudgetConfig(
            groupId = "group-0",
            profile = GroupProfile.default(GroupProfileType.Library)
        )
        val factory = EvoMoliorAgentFactory(
            openRouterApiKey = apiKey,
            llmBackend = LlmBackend.QWEN_CLOUD,
            db = db,
            toolSubSandbox = ToolSubSandbox(),
            budgetConfig = budgetConfig,
            qwenToolSupportEnabled = true
        )

        val context = factory.create(
            AgentBootContext(
                agentId = "agent-qwen-tool-live",
                runId = "run-qwen-tool-live",
                groupId = "group-0",
                role = Role.Researcher,
                rolePrompt = "Live smoke. Call write_staging exactly once with type DOC and content EVOMOLIOR_QWEN_WRITE_STAGING_OK, then stop.",
                budgetToken = "live-token",
                substrateDbPath = rootDir.resolve("run-qwen-tool-live/group-0.db").absolutePath,
                stagingMountPath = "/staging",
                wipTaskClaim = null,
                legacyProtocol = null,
                stageDisclosure = "",
                modelAssignment = ModelSelectionConfig.QWEN_CLOUD_QWEN_PLUS_MODEL,
                ipcBaseUrl = "http://127.0.0.1:1",
                generation = 0,
                gracePeriodSeconds = 5,
                allocatedBudget = 25.0
            )
        )

        context.agent.run("Use write_staging now. type=DOC content=EVOMOLIOR_QWEN_WRITE_STAGING_OK")

        val submissions = db.stagingDao().getAll("run-qwen-tool-live", "group-0")
        assertTrue(
            submissions.any { it.type == "DOC" && it.content.contains("EVOMOLIOR_QWEN_WRITE_STAGING_OK") },
            "Expected Qwen worker to create a DOC staging submission, got: $submissions"
        )
    }

    @Test
    fun `qwen cloud grader can submit grading score through evomolior tools`() = runBlocking {
        val apiKey = requireLiveQwenToolSmokeApiKey()
        val rootDir = createTempDirectory("qwen-grader-tool-live-").toFile()
        val db = SubstrateDatabaseFactory(rootDir).get("run-qwen-grader-tool-live", "grader-0")
        val submitted = mutableListOf<Pair<String, com.achulkov.evomolior.core.model.DimensionScore>>()
        val factory = GraderAgentFactory(
            openRouterApiKey = apiKey,
            llmBackend = LlmBackend.QWEN_CLOUD,
            graderDb = db,
            graderBudgetConfig = BudgetConfig(
                groupId = "grader-0",
                profile = GroupProfile.default(GroupProfileType.Library)
            ),
            graderGroupId = "grader-0",
            onScoreSubmitted = { groupId, score -> submitted += groupId to score },
            qwenToolSupportEnabled = true
        )
        val context = factory.createRoundJudge(
            runId = "run-qwen-grader-tool-live",
            roundIndex = 0,
            runObjective = "Live smoke for grader score submission.",
            scoringDimensions = listOf(
                ScoringDimension(
                    id = "accuracy",
                    name = "Accuracy",
                    description = "Smoke-test accuracy score.",
                    weight = 1.0
                )
            ),
            criteriaContext = "Use accuracy. Submit 87 for group-0.",
            allGroupSnapshots = mapOf("group-0" to "The answer includes EVOMOLIOR_QWEN_GRADER_SCORE_OK."),
            modelOverride = ModelSelectionConfig.QWEN_CLOUD_QWEN_PLUS_MODEL
        )

        context.agent.run(
            "Call submit_grading_score exactly once: targetGroupId=group-0 dimensionId=accuracy rawScore=87 rationale=EVOMOLIOR_QWEN_GRADER_SCORE_OK"
        )

        assertTrue(
            submitted.any { (groupId, score) ->
                groupId == "group-0" &&
                    score.dimensionId == "accuracy" &&
                    score.rawScore in 80.0..100.0 &&
                    score.justification.contains("EVOMOLIOR_QWEN_GRADER_SCORE_OK")
            },
            "Expected Qwen grader to submit a real grading score, got: $submitted"
        )
    }

    private class EchoProbeTool : SimpleTool<EchoProbeTool.Args>(
        argsType = typeToken<Args>(),
        name = "echo_probe",
        description = "Returns TOOL_RESULT:<value>. Use only for Qwen tool-loop smoke tests."
    ) {
        val callCount = AtomicInteger(0)

        @Serializable
        data class Args(val value: String)

        override suspend fun execute(args: Args): String {
            callCount.incrementAndGet()
            return "TOOL_RESULT:${args.value}"
        }
    }

    private fun requireLiveQwenToolSmokeApiKey(): String {
        assumeTrue(
            System.getenv("EVOMOLIOR_LIVE_QWEN_TOOL_SMOKE").equals("true", ignoreCase = true),
            "Set EVOMOLIOR_LIVE_QWEN_TOOL_SMOKE=true to run the live Qwen tool-loop smoke test."
        )
        val apiKey = System.getenv("QWEN_API_KEY") ?: readLocalProperty("QWEN_API_KEY").orEmpty()
        assumeTrue(apiKey.isNotBlank(), "QWEN_API_KEY must be present to run the live Qwen tool-loop smoke test.")
        return apiKey
    }

    private fun readLocalProperty(key: String): String? {
        val file = generateSequence(Path.of("").toAbsolutePath().normalize()) { current ->
            current.parent
        }.map { it.resolve("local.properties") }
            .firstOrNull(Files::exists)
            ?: return null
        return Files.readAllLines(file)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val separatorIndex = line.indexOf('=')
                if (separatorIndex <= 0) null else line.substring(0, separatorIndex).trim() to
                    line.substring(separatorIndex + 1).trim()
            }
            .firstOrNull { (name, _) -> name == key }
            ?.second
    }
}
