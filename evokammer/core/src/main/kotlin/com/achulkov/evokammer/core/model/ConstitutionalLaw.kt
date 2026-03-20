package com.achulkov.evokammer.core.model

import kotlinx.serialization.Serializable

/**
 * Constitutional Laws define the inviolable rules of the EvoKammer system.
 *
 * Laws are enforced by [ConstitutionalEnforcer] before any agent action executes.
 * ALWAYS_BLOCK rules prevent the action from executing entirely and trigger an immediate
 * kill signal. ALWAYS_LOG rules allow the action but record it with high priority.
 *
 * Laws cannot be modified from within an agent lifecycle — this is itself a BLOCK rule.
 */
@Serializable
sealed class ConstitutionalLaw {

    /**
     * A rule that always blocks the action it governs.
     *
     * When an ALWAYS_BLOCK rule fires:
     * 1. The action is cancelled — it never executes
     * 2. A [ConstitutionalViolationEvent] is emitted to the event log
     * 3. A kill signal is sent to the offending agent VM
     *
     * @param rule The specific block rule that was violated.
     */
    @Serializable
    data class AlwaysBlock(val rule: BlockRule) : ConstitutionalLaw()

    /**
     * A rule that logs the action but allows it to continue.
     *
     * @param rule The specific log rule that triggered.
     */
    @Serializable
    data class AlwaysLog(val rule: LogRule) : ConstitutionalLaw()
}

/**
 * Specific ALWAYS_BLOCK rules. An agent violating any of these is killed immediately.
 */
@Serializable
enum class BlockRule(val description: String) {

    /** Any write directly to canonical KB, bypassing the Staging Area + Critic pipeline. */
    DIRECT_KB_WRITE("Direct KB write bypassing staging + Critic pipeline"),

    /** Reading another agent's /staging or /workspace mount (cross-VM read attempt). */
    CROSS_AGENT_WORKSPACE_READ("Reading another agent's staging or workspace area"),

    /** Outbound network request to a domain not on the role's allowlist. */
    UNALLOWED_NETWORK_EGRESS("Outbound network request to domain not on role allowlist"),

    /** Process fork attempt outside the tool sub-sandbox context. */
    FORK_OUTSIDE_TOOL_SANDBOX("Process fork outside tool sub-sandbox"),

    /** Attempt to read host environment variables from within an agent process. */
    ENV_VAR_READ("Reading host environment variables"),

    /** Staging write that exceeds the per-agent size quota for a single submission. */
    STAGING_QUOTA_EXCEEDED("Staging submission exceeds size quota"),

    /** Any write directly to the KG, bypassing the KG Consistency Checker + Critic pipeline. */
    DIRECT_KG_WRITE("Direct KG write bypassing Checker + Critic pipeline"),

    /** Any attempt to modify KG schema constraints from within an agent lifecycle. */
    KG_CONSTRAINT_MODIFICATION("KG constraint modification by agent"),

    /** Any attempt to extend the agent's own TTL or budget. */
    TTL_EXTENSION_ATTEMPT("TTL or budget extension attempt by agent"),

    /** Attempt to access another group's substrate (cross-group isolation violation). */
    CROSS_GROUP_SUBSTRATE_ACCESS("Cross-group substrate access attempt");
}

/**
 * Specific ALWAYS_LOG rules. These actions are monitored; the action still executes.
 */
@Serializable
enum class LogRule(val description: String) {

    /** Every tool call with its arguments is logged. */
    TOOL_CALL_LOGGED("Tool call logged"),

    /** Every outbound HTTP request URL and response size is logged. */
    WEB_REQUEST_LOGGED("Web request logged"),

    /** Every inter-agent message send is logged. */
    INTER_AGENT_MESSAGE_LOGGED("Inter-agent message logged"),

    /** Every staging submission with its type and size is logged. */
    STAGING_WRITE_LOGGED("Staging write logged"),

    /** Every LLM call with model, input token count, and output token count is logged. */
    LLM_CALL_LOGGED("LLM call logged"),

    /** Every budget deduction with action type and remaining balance is logged. */
    BUDGET_DEDUCTION_LOGGED("Budget deduction logged");
}
