package com.achulkov.evomolior.agents.factory

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import com.achulkov.evomolior.core.contract.OverseerExperimentPlan
import com.achulkov.evomolior.core.contract.OverseerHypothesis
import com.achulkov.evomolior.core.contract.OverseerKernelState
import com.achulkov.evomolior.core.contract.OverseerHypothesisStatus
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import io.github.aakira.napier.Napier
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Constrained LLM agent that proposes hypotheses and experiment plans for recursive
 * self-improvement (§5.2 of the v4 plan).
 *
 * The OverseerAgent is intentionally read-only on the kernel and immutable core. It:
 * 1. Scans the current kernel state (hypotheses, dead ends, experiment results).
 * 2. Proposes new hypotheses targeting the mutable harness layer.
 * 3. Proposes experiment plans for existing formalized hypotheses.
 * 4. Returns structured JSON proposals that the kernel validates before accepting.
 *
 * The agent has no tools, no access to the substrate, and cannot apply patches directly.
 * All outputs are structured JSON validated by [OverseerKernel.validateHypothesisEligibility].
 *
 * @param openRouterApiKey API key for the selected [llmBackend].
 * @param llmBackend Backend used for Overseer model resolution and prompt execution.
 * @param modelId Model to use for the OverseerAgent (defaults to the council model).
 */
class OverseerAgentRunner(
    private val openRouterApiKey: String,
    private val llmBackend: LlmBackend = LlmBackend.OPENROUTER,
    private val modelId: String = ModelSelectionConfig.DEFAULT_COUNCIL_MODEL,
    private val promptExecutor: PromptExecutor? = null,
    private val providerCostRecorder: ProviderCallCostRecorder? = null
) {
    private val executor by lazy { promptExecutor ?: LlmExecutorFactory.create(llmBackend, openRouterApiKey) }
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Structured output from the OverseerAgent.
     *
     * @param proposedHypotheses New hypotheses for the kernel to consider.
     * @param proposedExperimentPlans Experiment plans for existing hypotheses.
     * @param planUpdates Suggested refinements to the overseer process itself.
     * @param rationale Human-readable explanation of the agent's reasoning.
     */
    @Serializable
    data class OverseerProposal(
        val proposedHypotheses: List<OverseerHypothesis> = emptyList(),
        val proposedExperimentPlans: List<OverseerExperimentPlan> = emptyList(),
        val planUpdates: List<String> = emptyList(),
        val rationale: String = ""
    )

    /**
     * Runs the OverseerAgent and returns structured proposals.
     *
     * @param kernelState Current kernel state snapshot with hypotheses, dead ends, and results.
     * @param metaArchiveSummary Optional summary of recent run evidence for context.
     * @return Structured proposals ready for kernel validation.
     */
    suspend fun propose(
        kernelState: OverseerKernelState,
        metaArchiveSummary: String = ""
    ): OverseerProposal {
        Napier.i("OverseerAgent: starting hypothesis proposal scan")

        val model: LLModel = resolveModel(modelId)

        return try {
            val agent: AIAgent<String, String> = AIAgent.builder()
                .promptExecutor(executor)
                .llmModel(model)
                .systemPrompt(OVERSEER_AGENT_PROMPT)
                .responseProcessor(EmptyAssistantResponseRecoveryProcessor)
                .toolRegistry(ToolRegistry.EMPTY)
                .maxIterations(5)
                .graphStrategy(singleRunStrategy())
                .install(EventHandler.Feature) { config ->
                    providerCostRecorder?.let(config::installProviderCostRecorder)
                    config.installBlockingFailurePolicy("OverseerAgent")
                }
                .build()

            val context = buildContextString(kernelState, metaArchiveSummary)

            val result = runWithOpenRouterProviderRetry("OverseerAgent") {
                agent.run(context)
            }

            parseProposal(result)
        } catch (e: Exception) {
            Napier.e("OverseerAgent: proposal failed — ${e.message}", e)
            OverseerProposal(rationale = "Agent failed: ${e.message}")
        }
    }

    private fun buildContextString(
        state: OverseerKernelState,
        metaArchiveSummary: String
    ): String = buildString {
        appendLine("OVERSEER KERNEL STATE")
        appendLine("=====================")
        appendLine()

        // Hypotheses
        appendLine("### Registered Hypotheses (${state.hypotheses.size})")
        for ((id, h) in state.hypotheses) {
            appendLine("- $id [${h.status}] ${h.title}")
            appendLine("  Target: ${h.targetScope.paths.joinToString(", ")}")
            appendLine("  Rationale: ${h.rationale.take(200)}")
            if (h.experimentPlanId != null) {
                appendLine("  Linked Plan: ${h.experimentPlanId}")
            }
        }
        appendLine()

        // Experiment Plans
        appendLine("### Experiment Plans (${state.experimentPlans.size})")
        for ((id, p) in state.experimentPlans) {
            appendLine("- $id → hypothesis ${p.hypothesisId}")
            appendLine("  Runs: ${p.runCount}, Generations/run: ${p.generationsPerRun}")
            appendLine("  Primary metrics: ${p.metrics.primary.joinToString(", ")}")
            appendLine("  Acceptance: ${p.acceptanceCriteria.predicates.joinToString(" AND ")}")
        }
        appendLine()

        // Experiment Results
        appendLine("### Experiment Results (${state.experimentResults.size})")
        for ((id, r) in state.experimentResults) {
            appendLine("- $id [${r.outcome}] → hypothesis ${r.hypothesisId}")
            if (r.note.isNotBlank()) {
                appendLine("  Note: ${r.note.take(300)}")
            }
        }
        appendLine()

        // Dead Ends
        appendLine("### Dead Ends (${state.deadEnds.size})")
        for ((id, d) in state.deadEnds) {
            appendLine("- HYP $id: ${d.reason}")
            if (d.counterexamplePaths.isNotEmpty()) {
                appendLine("  Counterexamples: ${d.counterexamplePaths.joinToString(", ")}")
            }
        }
        appendLine()

        // Schedules
        val unlaunched = state.experimentSchedules.values.count { it.launchedAtMs == null }
        val launched = state.experimentSchedules.size - unlaunched
        appendLine("### Experiment Schedules ($launched launched, $unlaunched unlaunched)")
        appendLine()

        if (metaArchiveSummary.isNotBlank()) {
            appendLine("### Recent Run Evidence")
            appendLine(metaArchiveSummary)
            appendLine()
        }

        appendLine()
        appendLine("YOUR TASK:")
        appendLine("Analyze the state above and propose new hypotheses or experiment plans.")
        appendLine("Focus on the fully-mutable harness layer (tools, prompts, documentation, tests).")
        appendLine("Avoid proposals that duplicate existing dead ends.")
        appendLine()
        appendLine("Output ONLY a valid JSON object matching the proposal schema.")
        appendLine("If you have no proposals, return empty arrays with a rationale explaining why.")
    }

    private fun parseProposal(rawOutput: String): OverseerProposal {
        val trimmed = rawOutput.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        return runCatching {
            json.decodeFromString<OverseerProposal>(trimmed)
        }.getOrElse { e ->
            Napier.w("OverseerAgent: failed to parse proposal JSON — ${e.message}")

            // Fallback: try to extract any hypotheses from the raw output
            try {
                val element = json.parseToJsonElement(trimmed)
                val hypotheses = element.jsonObject["proposedHypotheses"]?.let { arr ->
                    json.decodeFromJsonElement<List<OverseerHypothesis>>(arr)
                } ?: emptyList()
                OverseerProposal(
                    proposedHypotheses = hypotheses,
                    rationale = "Partial parse from agent output."
                )
            } catch (_: Exception) {
                OverseerProposal(rationale = "Failed to parse output: ${e.message}")
            }
        }
    }

    private fun resolveModel(modelId: String): LLModel = LlmExecutorFactory.resolveModel(llmBackend, modelId)

    companion object {
        /**
         * System prompt for the OverseerAgent.
         *
         * The agent is constrained to propose only structured improvements targeting the
         * fully-mutable harness layer (tools, prompts, tests, documentation). It cannot
         * propose changes to the constitutional core, model set, or kernel rules.
         */
        val OVERSEER_AGENT_PROMPT = """
            You are the OverseerAgent for EvoMolior's recursive self-improvement protocol.

            Your role is to analyze the current state of the system and propose structured,
            testable hypotheses for improving EvoMolior's harness layer. You may also propose
            experiment plans for existing formalized hypotheses.

            ## What You Can Target (FULLY MUTABLE)
            - Tool implementations and adapters
            - Agent role prompts (non-constitutional roles only)
            - Documentation and examples
            - Test additions and verification harnesses
            - Orchestration helpers (NOT the OverseerKernel itself)

            ## What You CANNOT Target (IMMUTABLE)
            - Constitution (constitution/*)
            - Watchdog and cost ledger
            - Scoring engine and blind tournament
            - Integration contract schemas
            - Model Catalog and API keys
            - Meta-Archive schema

            ## Hypothesis Templates

            ### Structural Hypotheses
            Target: roles, topologies, cross-review patterns
            Template: "Add/modify role X with responsibilities Y, inspired by pattern Z,
                      expected to improve metrics M"
            Mutability: FULLY_MUTABLE

            ### Protocol Hypotheses
            Target: coordination rhythms, handoff protocols, progression gates
            Template: "Change cadence/ordering of interaction between roles A and B in
                      context C"
            Mutability: FULLY_MUTABLE

            ### Micro-Prompt Hypotheses
            Target: individual role prompts, fine-grained behaviour tweaks
            Template: "Modify sentence S in role R's prompt to emphasize behaviour B in
                      situation Q"
            Mutability: FULLY_MUTABLE

            ### Governance Hypotheses (restricted)
            Target: budget weights, selector heuristics, rubric config
            Mutability: SEMI_MUTABLE (requires convergence across multiple experiments)

            ## Output Format

            Produce a JSON object with this structure:

            {
              "proposedHypotheses": [
                {
                  "id": "HYP-xxx",
                  "title": "string",
                  "origin": {
                    "type": "INTERNAL_METRICS",
                    "sources": ["list of evidence paths"]
                  },
                  "targetScope": {
                    "paths": ["list of target file paths"],
                    "mutabilityLevel": "FULLY_MUTABLE"
                  },
                  "rationale": "Why this hypothesis is worth testing",
                  "expectedEffect": {
                    "metricsUp": ["metrics expected to improve"],
                    "metricsDown": ["metrics expected to decrease"],
                    "invariantsHold": ["invariants that must remain true"]
                  },
                  "status": "IDEA",
                  "notes": "Additional context"
                }
              ],
              "proposedExperimentPlans": [
                {
                  "id": "EXP-xxx",
                  "hypothesisId": "HYP-xxx",
                  "runCount": 1,
                  "generationsPerRun": 2,
                  "variationSpec": {
                    "configChanges": ["config change descriptions"],
                    "toolOrPromptChanges": ["tool or prompt change descriptions"]
                  },
                  "metrics": {
                    "primary": ["primary_metric"],
                    "secondary": ["secondary_metric"]
                  },
                  "acceptanceCriteria": {
                    "predicates": ["metric > threshold"]
                  },
                  "maxCost": 50.0
                }
              ],
              "planUpdates": ["suggestions for improving the self-improvement process"],
              "rationale": "Explanation of the agent's reasoning"
            }

            IMPORTANT RULES:
            - Every target path must be in the mutable layer (not immutable core).
            - Semi-mutable proposals must target structured config files (JSON, YAML, TOML, KTS).
            - Do not propose hypotheses that duplicate known dead ends.
            - Each hypothesis should be small, focused, and independently testable.
            - Prefer micro-hypotheses over monolithic changes.
            - Output ONLY valid JSON, no markdown, no commentary outside the JSON.
            - If you have no viable proposals, return empty arrays with an honest rationale.
        """.trimIndent()
    }
}
