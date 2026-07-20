package com.achulkov.evomolior.agents.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.achulkov.evomolior.sandbox.network.SourceAccessDecision
import com.achulkov.evomolior.sandbox.network.SourceAccessDecisionRecord
import com.achulkov.evomolior.sandbox.network.SourceAccessDecisionStore
import com.achulkov.evomolior.sandbox.network.SourceAccessPolicy
import com.achulkov.evomolior.sandbox.enforcer.ActionEventType
import com.achulkov.evomolior.sandbox.enforcer.AgentAction
import com.achulkov.evomolior.sandbox.enforcer.ConstitutionalActionGate
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Koog tool: request access to an external source URL.
 *
 * The tool does not fetch remote content. It validates the URL and records an allow/block
 * decision so future agent spawns can inherit newly approved hosts.
 */
class RequestSourceAccessTool(
    private val runId: String,
    private val groupId: String,
    private val agentId: String,
    private val role: String,
    private val generation: Int,
    private val runObjective: String,
    private val currentAllowedHosts: Set<String>,
    auditFilePath: String?,
    private val actionGate: ConstitutionalActionGate = ConstitutionalActionGate.NONE
) : SimpleTool<RequestSourceAccessTool.Args>(
    argsType = typeToken<Args>(),
    name = "request_source_access",
    description = """
        Request permission to use an external http(s) source URL.
        This validates scheme, host safety, current allowlist, and run-subject relevance.
        It records ALLOWED/BLOCKED decisions for audit; approved hosts become available to later agent spawns.
        Cost: FREE.
    """.trimIndent()
) {
    private val decisionStore = auditFilePath?.takeIf { it.isNotBlank() }?.let { SourceAccessDecisionStore(File(it)) }

    @Serializable
    data class Args(
        val url: String? = null,
        val purpose: String? = null,
        val value: ArgsValue? = null
    )

    @Serializable
    data class ArgsValue(
        val url: String? = null,
        val purpose: String? = null
    )

    override suspend fun execute(args: Args): String {
        val url = args.url ?: args.value?.url
            ?: return "BLOCKED: missing url. Provide request_source_access(url, purpose)."
        val purpose = (args.purpose ?: args.value?.purpose).orEmpty().trim()
        if (purpose.length < 12) {
            return "BLOCKED: purpose is too short. Explain why this source is needed for the run objective."
        }

        val decision = SourceAccessPolicy.evaluateRequest(
            rawUrl = url,
            purpose = purpose,
            runObjective = runObjective,
            currentlyAllowedHosts = currentAllowedHosts
        )
        val allowed = decision is SourceAccessDecision.Allowed
        actionGate.intercept(
            AgentAction(
                type = ActionEventType.SOURCE_ACCESS_DECISION,
                agentId = agentId,
                detail = decision.url,
                allowed = allowed
            )
        )
        decisionStore?.append(
            SourceAccessDecisionRecord(
                runId = runId,
                groupId = groupId,
                agentId = agentId,
                role = role,
                generation = generation,
                url = decision.url,
                host = decision.host,
                purpose = purpose,
                allowed = allowed,
                reason = decision.reason
            )
        )

        return if (allowed) {
            "ALLOWED: ${decision.host} for ${decision.url}. ${decision.reason} " +
                "This approval is audited and will be available to later agent spawns."
        } else {
            "BLOCKED: ${decision.host ?: url}. ${decision.reason}"
        }
    }
}
