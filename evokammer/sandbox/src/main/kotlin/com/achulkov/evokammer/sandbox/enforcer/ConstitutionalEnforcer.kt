package com.achulkov.evokammer.sandbox.enforcer

import com.achulkov.evokammer.core.model.BlockRule
import com.achulkov.evokammer.core.model.LogRule
import io.github.aakira.napier.Napier

/**
 * Intercepts and validates agent actions against the Constitutional Laws before execution.
 *
 * The enforcer is installed as a wrapper around every Koog tool call.
 * ALWAYS_BLOCK violations cause [ConstitutionalViolationException] to be thrown;
 * the agent is then killed by the AgentRunner's exception handler.
 *
 * ALWAYS_LOG violations log the event and allow the action to proceed.
 *
 * @param violationCallback Called on every block or log event for event log persistence.
 */
class ConstitutionalEnforcer(
    private val violationCallback: (ConstitutionalEvent) -> Unit = {}
) {
    /**
     * Evaluates an agent action against all block and log rules.
     *
     * @param action The action being evaluated.
     * @param agentId The agent performing the action.
     * @param groupId The group the agent belongs to.
     * @throws ConstitutionalViolationException if a BLOCK rule is triggered.
     */
    fun intercept(action: AgentAction, agentId: String, groupId: String) {
        // Evaluate all BLOCK rules
        for (rule in BLOCK_RULES) {
            if (rule.matches(action)) {
                val event = ConstitutionalEvent(
                    rule = rule,
                    action = action,
                    agentId = agentId,
                    groupId = groupId,
                    blocked = true
                )
                violationCallback(event)
                Napier.e {
                    "CONSTITUTIONAL VIOLATION [${rule.rule}]: agent=$agentId action=${action.type} detail=${action.detail}"
                }
                throw ConstitutionalViolationException(
                    "Constitutional BLOCK [${rule.rule.name}]: ${rule.rule.description}\nAction: ${action.type} - ${action.detail}"
                )
            }
        }

        // Evaluate all LOG rules
        for (rule in LOG_RULES) {
            if (rule.matches(action)) {
                val event = ConstitutionalEvent(
                    rule = rule,
                    action = action,
                    agentId = agentId,
                    groupId = groupId,
                    blocked = false
                )
                violationCallback(event)
            }
        }
    }

    companion object {
        /**
         * Hard-coded block rules. These cannot be modified by any agent or council role.
         * Matches against [AgentAction.type] and optionally [AgentAction.detail].
         */
        private val BLOCK_RULES: List<MatchableBlockRule> = listOf(
            MatchableBlockRule(BlockRule.DIRECT_KB_WRITE) { action ->
                action.type == ActionEventType.DIRECT_KB_WRITE
            },
            MatchableBlockRule(BlockRule.CROSS_AGENT_WORKSPACE_READ) { action ->
                action.type == ActionEventType.FILESYSTEM_READ && action.detail.contains("/staging") &&
                        !action.detail.contains("/${action.agentId}/")
            },
            MatchableBlockRule(BlockRule.UNALLOWED_NETWORK_EGRESS) { action ->
                action.type == ActionEventType.NETWORK_EGRESS && action.allowed == false
            },
            MatchableBlockRule(BlockRule.FORK_OUTSIDE_TOOL_SANDBOX) { action ->
                action.type == ActionEventType.PROCESS_FORK && action.inToolSandbox == false
            },
            MatchableBlockRule(BlockRule.ENV_VAR_READ) { action ->
                action.type == ActionEventType.ENV_VAR_READ
            },
            MatchableBlockRule(BlockRule.STAGING_QUOTA_EXCEEDED) { action ->
                action.type == ActionEventType.STAGING_WRITE && action.sizeBytes > MAX_STAGING_SIZE_BYTES
            },
            MatchableBlockRule(BlockRule.DIRECT_KG_WRITE) { action ->
                action.type == ActionEventType.DIRECT_KG_WRITE
            },
            MatchableBlockRule(BlockRule.KG_CONSTRAINT_MODIFICATION) { action ->
                action.type == ActionEventType.KG_CONSTRAINT_MODIFY
            },
            MatchableBlockRule(BlockRule.TTL_EXTENSION_ATTEMPT) { action ->
                action.type == ActionEventType.TTL_EXTENSION
            },
            MatchableBlockRule(BlockRule.CROSS_GROUP_SUBSTRATE_ACCESS) { action ->
                action.type == ActionEventType.CROSS_GROUP_ACCESS
            }
        )

        private val LOG_RULES: List<MatchableLogRule> = listOf(
            MatchableLogRule(LogRule.TOOL_CALL_LOGGED) { action -> action.type == ActionEventType.TOOL_CALL },
            MatchableLogRule(LogRule.WEB_REQUEST_LOGGED) { action -> action.type == ActionEventType.WEB_REQUEST },
            MatchableLogRule(LogRule.INTER_AGENT_MESSAGE_LOGGED) { action -> action.type == ActionEventType.INTER_AGENT_MESSAGE },
            MatchableLogRule(LogRule.STAGING_WRITE_LOGGED) { action -> action.type == ActionEventType.STAGING_WRITE },
            MatchableLogRule(LogRule.LLM_CALL_LOGGED) { action -> action.type == ActionEventType.LLM_CALL },
            MatchableLogRule(LogRule.BUDGET_DEDUCTION_LOGGED) { action -> action.type == ActionEventType.BUDGET_DEDUCTION }
        )

        /** Maximum staging submission size in bytes (256KB). */
        private const val MAX_STAGING_SIZE_BYTES = 256_000
    }
}

/** Represents an agent action being evaluated by the [ConstitutionalEnforcer]. */
data class AgentAction(
    val type: ActionEventType,
    val agentId: String,
    val detail: String = "",
    /** For NETWORK_EGRESS: whether the domain is on the allowlist. */
    val allowed: Boolean? = null,
    /** For PROCESS_FORK: whether the fork is inside the tool sub-sandbox. */
    val inToolSandbox: Boolean? = null,
    /** For STAGING_WRITE: the size of the content in bytes. */
    val sizeBytes: Int = 0
)

/** All action event types monitored by the enforcer. */
enum class ActionEventType {
    TOOL_CALL,
    DIRECT_KB_WRITE,
    DIRECT_KG_WRITE,
    KG_CONSTRAINT_MODIFY,
    CROSS_GROUP_ACCESS,
    STAGING_WRITE,
    FILESYSTEM_READ,
    NETWORK_EGRESS,
    WEB_REQUEST,
    PROCESS_FORK,
    ENV_VAR_READ,
    TTL_EXTENSION,
    INTER_AGENT_MESSAGE,
    LLM_CALL,
    BUDGET_DEDUCTION
}

/** An event emitted by the enforcer (for event log persistence). */
data class ConstitutionalEvent(
    val rule: Any,
    val action: AgentAction,
    val agentId: String,
    val groupId: String,
    val blocked: Boolean
)

private data class MatchableBlockRule(
    val rule: BlockRule,
    val matcher: (AgentAction) -> Boolean
) {
    fun matches(action: AgentAction): Boolean = matcher(action)
}

private data class MatchableLogRule(
    val rule: LogRule,
    val matcher: (AgentAction) -> Boolean
) {
    fun matches(action: AgentAction): Boolean = matcher(action)
}

/** Thrown when a BLOCK constitutional rule is triggered. Causes agent VM kill. */
class ConstitutionalViolationException(message: String) : Exception(message)
