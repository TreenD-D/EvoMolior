package com.achulkov.evomolior.agents.factory

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.prompt
import com.achulkov.evomolior.agents.tools.FetchSourceTool
import com.achulkov.evomolior.agents.tools.ReadRunArtifactTool
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import com.achulkov.evomolior.core.contract.RunInputArtifact
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.sandbox.enforcer.ConstitutionalActionGate

/**
 * Executes one isolated Codex/ChatGPT iterative baseline with the matched task-facing tools.
 */
object CodexIterativeSingleAgentRunner {
    /**
     * Runs one prompt without society, substrate, grader, or delegation capabilities.
     *
     * @param cliPath Authenticated Codex CLI executable.
     * @param runId Logical comparative run identity.
     * @param objective Exact benchmark objective.
     * @param artifactsRoot Read-only benchmark artifact root.
     * @param inputArtifacts Canonical metadata for [artifactsRoot].
     * @param allowedSourceHosts Exact source hosts approved for `fetch_source`.
     * @param sourceAuditPath Append-only source decision log.
     * @return Final model response text.
     */
    suspend fun run(
        cliPath: String,
        runId: String,
        objective: String,
        artifactsRoot: String,
        inputArtifacts: List<RunInputArtifact>,
        allowedSourceHosts: Set<String>,
        sourceAuditPath: String
    ): String {
        val tools = ToolRegistry {
            tool(ReadRunArtifactTool(artifactsRoot, inputArtifacts))
            tool(
                FetchSourceTool(
                    runId = runId,
                    groupId = "iterative-single-agent",
                    agentId = "iterative-single-agent",
                    role = Role.Researcher.name,
                    generation = 0,
                    runObjective = objective,
                    currentAllowedHosts = allowedSourceHosts,
                    auditFilePath = sourceAuditPath,
                    actionGate = ConstitutionalActionGate.NONE
                )
            )
        }
        val client = CodexChatGptLLMClient(
            cliPath = cliPath,
            nativeSearchEnabled = false,
            codeModeToolRuntime = CodexCodeModeToolRuntime(
                toolRegistry = tools,
                actionGate = ConstitutionalActionGate.NONE,
                agentId = "iterative-single-agent"
            )
        )
        return try {
            client.execute(
                prompt("comparative-iterative-single-agent") {
                    system(
                        """
                        You are the iterative single-agent baseline for an EvoMolior benchmark.
                        Use only the exposed read_run_artifact and fetch_source APIs for task
                        information. Do not delegate, simulate a society, or use native web search.
                        Return one JSON object matching the benchmark contract, including evidence_path.
                        """.trimIndent()
                    )
                    user(objective)
                },
                CodexChatGptModelResolver.resolve(
                    ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL
                ),
                tools.tools.map { it.descriptor }
            ).textContent()
        } finally {
            client.close()
        }
    }
}
