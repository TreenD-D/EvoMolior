package com.achulkov.evomolior.agents.factory

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.dsl.prompt as koogPrompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.LlmTimeoutPolicy
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import com.achulkov.evomolior.core.contract.BlockingAgentAbortException
import com.achulkov.evomolior.core.council.CouncilAgentRunner
import com.achulkov.evomolior.core.council.CouncilRoleExecutionRequest
import com.achulkov.evomolior.core.council.ExecutionEnvelope
import com.achulkov.evomolior.core.model.Role
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import java.nio.file.Path
import kotlin.time.Duration

/**
 * Creates and runs Genesis Council agents using Koog.
 *
 * Council agents are lightweight: no substrate tools, no budget ledger.
 * Each agent receives accumulated context from all previous council agents and
 * produces structured JSON output.
 *
 * This class provides the [agentRunner] callback consumed by `GenesisCouncil`.
 *
 * @param openRouterApiKey API key for the selected [llmBackend].
 * @param executionEnvelope Runtime constraints embedded in every Council role prompt.
 * @param llmBackend Backend used for council model resolution and prompt execution.
 * @param councilModelId The LLM model to use for all council agents. Defaults to the shared
 *   cost-conscious council model from [ModelSelectionConfig].
 * @param codexExecutionTimeout Maximum for each Codex process attempt.
 * @param structuredOutputDiagnosticRoot Optional owner-only directory for bounded Codex schema
 *   and invalid-response evidence.
 * @param maxIterations Maximum Koog iterations per council agent (defaults to 10 — single-shot
 *   agents still need ≥2 graph iterations: one for the LLM call and one to return the result).
 */
class CouncilRunner private constructor(
    private val openRouterApiKey: String,
    private val executionEnvelope: ExecutionEnvelope,
    private val councilModelId: String,
    private val llmBackend: LlmBackend,
    private val maxIterations: Int,
    private val promptExecutor: PromptExecutor?,
    private val providerCostRecorder: ((Role) -> ProviderCallCostRecorder)?,
    internal val codexExecutionTimeout: Duration,
    private val structuredOutputDiagnosticRoot: Path?,
    private val structuredOutputSchemaValidator: (CodexOutputSchemaDocument) -> List<String>
) {

    constructor(
        openRouterApiKey: String,
        executionEnvelope: ExecutionEnvelope,
        councilModelId: String = ModelSelectionConfig.DEFAULT_COUNCIL_MODEL,
        llmBackend: LlmBackend = LlmBackend.OPENROUTER,
        maxIterations: Int = 10,
        promptExecutor: PromptExecutor? = null,
        providerCostRecorder: ((Role) -> ProviderCallCostRecorder)? = null,
        codexExecutionTimeout: Duration = LlmTimeoutPolicy.DEFAULT_CODEX_EXECUTION,
        structuredOutputDiagnosticRoot: Path? = null
    ) : this(
        openRouterApiKey = openRouterApiKey,
        executionEnvelope = executionEnvelope,
        councilModelId = councilModelId,
        llmBackend = llmBackend,
        maxIterations = maxIterations,
        promptExecutor = promptExecutor,
        providerCostRecorder = providerCostRecorder,
        codexExecutionTimeout = codexExecutionTimeout,
        structuredOutputDiagnosticRoot = structuredOutputDiagnosticRoot,
        structuredOutputSchemaValidator = CodexStructuredOutputValidator::validateSchema
    )

    /**
     * Creates a runner with a supplied schema compiler for focused boundary tests.
     *
     * Production callers use the public constructor, which always invokes
     * [CodexStructuredOutputValidator.validateSchema].
     */
    internal constructor(
        openRouterApiKey: String,
        executionEnvelope: ExecutionEnvelope,
        promptExecutor: PromptExecutor,
        structuredOutputSchemaValidator: (CodexOutputSchemaDocument) -> List<String>
    ) : this(
        openRouterApiKey = openRouterApiKey,
        executionEnvelope = executionEnvelope,
        councilModelId = ModelSelectionConfig.DEFAULT_COUNCIL_MODEL,
        llmBackend = LlmBackend.OPENROUTER,
        maxIterations = 10,
        promptExecutor = promptExecutor,
        providerCostRecorder = null,
        codexExecutionTimeout = LlmTimeoutPolicy.DEFAULT_CODEX_EXECUTION,
        structuredOutputDiagnosticRoot = null,
        structuredOutputSchemaValidator = structuredOutputSchemaValidator
    )

    private val executor by lazy {
        promptExecutor ?: LlmExecutorFactory.create(
            backend = llmBackend,
            apiKey = openRouterApiKey,
            codexExecutionTimeout = codexExecutionTimeout,
            codexStructuredOutputDiagnosticRoot = structuredOutputDiagnosticRoot
        )
    }

    /**
     * Returns a suspend function compatible with `GenesisCouncil.run`.
     *
     * Usage:
     * ```kotlin
     * val council = GenesisCouncil(objective, apiKey)
     * val runner = CouncilRunner(apiKey)
     * val startingField = council.run(runner.agentRunner())
     * ```
     */
    fun agentRunner(): CouncilAgentRunner = CouncilAgentRunner { request ->
        runCouncilAgent(request)
    }

    /**
     * Runs a single council agent for [request].
     *
     * @return The agent's output (expected to be a JSON string).
     */
    suspend fun runCouncilAgent(request: CouncilRoleExecutionRequest): String {
        val role = request.role
        require(role.isCouncilRole) { "CouncilRunner: role $role is not a council role" }
        require(request.acceptanceProfile == executionEnvelope.acceptanceProfile) {
            "CouncilRunner request acceptance profile=${request.acceptanceProfile.id} does not " +
                "match envelope profile=${executionEnvelope.acceptanceProfile.id}"
        }

        val systemPrompt = CouncilRolePrompts.forRole(role, executionEnvelope)
        val model: LLModel = resolveModel(councilModelId)

        Napier.i("CouncilRunner: spawning ${role.name} agent")

        return try {
            val roleSchema = CouncilRoleSchemas.forRole(
                role = role,
                synthesisAuthority = request.synthesisWireAuthority,
                acceptanceProfile = request.acceptanceProfile
            )
            val schemaDocument = CodexOutputSchemaDocument.from(roleSchema)
            val schemaFailures = structuredOutputSchemaValidator(schemaDocument)
            if (schemaFailures.isNotEmpty()) {
                throw CouncilRoleExecutionException(
                    role = role,
                    message = "Council role output schema failed independent Draft 2020-12 " +
                        "validation: ${schemaFailures.joinToString()}"
                )
            }

            val agent: AIAgent<String, String> = AIAgent.builder()
                .promptExecutor(executor)
                .agentConfig(
                    AIAgentConfig(
                        prompt = koogPrompt(
                            id = "council-${role.name}",
                            params = LLMParams(
                                schema = roleSchema
                            )
                        ) {
                            system(systemPrompt)
                        },
                        model = model,
                        maxAgentIterations = maxIterations,
                        responseProcessor = EmptyAssistantResponseRecoveryProcessor
                    )
                )
                .toolRegistry(ToolRegistry.EMPTY)
                .maxIterations(maxIterations)
                .graphStrategy(singleRunStrategy())
                .install(EventHandler.Feature) { config ->
                    providerCostRecorder?.invoke(role)?.let {
                        config.installProviderCostRecorder(it)
                    }
                    config.installBlockingFailurePolicy("Council[$role]")
                }
                .build()

            val result = runWithOpenRouterProviderRetry("Council[$role]") {
                agent.run(
                    """
                    COUNCIL CONTEXT (outputs from previous council agents):
                    ${request.contextJson}

                    Produce your structured JSON output now.
                    """.trimIndent()
                )
            }
            val output = result.trim()
            if (output.isBlank()) {
                throw CouncilRoleExecutionException(role, "Council role returned an empty response")
            }
            Napier.i("CouncilRunner: ${role.name} produced ${output.length} chars")
            output
        } catch (e: CancellationException) {
            throw e
        } catch (e: BlockingAgentAbortException) {
            Napier.e(e) { "CouncilRunner: ${role.name} aborted due to a blocking runtime error" }
            throw e
        } catch (e: CouncilRoleExecutionException) {
            Napier.e(e) { "CouncilRunner: ${role.name} produced no compilable response" }
            throw e
        } catch (e: Exception) {
            Napier.e("CouncilRunner: ${role.name} failed — ${e.message}")
            throw CouncilRoleExecutionException(role, "Council role execution failed", e)
        }
    }

    /**
     * Runs one non-Synthesis role without contextual Synthesis authority.
     *
     * This compatibility entry point is intended for direct adapter tests. Synthesis must use
     * [CouncilRoleExecutionRequest] so there is no static-schema production bypass.
     *
     * @param role Council role.
     * @param contextJson Complete strict task context.
     * @return One complete provider response.
     */
    suspend fun runCouncilAgent(role: Role, contextJson: String): String {
        require(role != Role.CouncilSynthesis) {
            "Council Synthesis requires a typed request with Task-derived wire authority"
        }
        return runCouncilAgent(CouncilRoleExecutionRequest(role, contextJson))
    }

    private fun resolveModel(modelId: String): LLModel = LlmExecutorFactory.resolveModel(llmBackend, modelId)
}

/**
 * Fail-closed boundary for one Genesis Council role execution.
 *
 * @param role Council role that did not produce a usable response.
 * @param message Failure summary.
 * @param cause Underlying provider or agent failure.
 */
class CouncilRoleExecutionException(
    val role: Role,
    message: String,
    cause: Throwable? = null
) : IllegalStateException("${role.name}: $message", cause)
