package com.achulkov.evokammer.core.contract

import com.achulkov.evokammer.core.model.Role
import kotlinx.serialization.Serializable

/**
 * The full context injected into an agent's VM at spawn time.
 *
 * Serialised as JSON and passed to the agent process via stdin. The agent deserialises
 * this once at startup and uses it for the entire lifecycle.
 *
 * @param agentId Unique identifier for this agent instance.
 * @param runId Run this agent belongs to.
 * @param groupId Group this agent belongs to.
 * @param role The role this agent is assigned.
 * @param rolePrompt The role-specific system prompt (assembled from StartingField + role).
 * @param budgetToken Signed token granting access to the BudgetLedger for this agent.
 * @param substrateDbPath Path to the group's Room database file inside the VM filesystem.
 * @param stagingMountPath Path to the write-only /staging mount inside the VM.
 * @param artifactsMountPath Path to the read-only run-artifacts mount inside the VM.
 * @param inputArtifacts Metadata for uploaded run-start context artifacts.
 * @param startingField Original run seed for this civilisation, if available.
 * @param wipTaskClaim The WiP task claimed for this agent; null for Outliers.
 * @param legacyProtocol The Legacy Protocol injected as privileged context; null if not available.
 * @param stageDisclosure The current stage disclosure content (constraint-framed task description).
 * @param modelAssignment The OpenRouter model string assigned to this role.
 * @param ipcBaseUrl The base URL for IPC communication with the Lifecycle Manager.
 * @param generation The current generation number for this agent.
 * @param gracePeriodSeconds How long the agent has to write Death Note after receiving SIGTERM.
 * @param allocatedBudget The exact budget amount allocated to this agent by the EvolutionarySelector.
 *   Used by [AgentRunner] to initialise the [BudgetLedger] with the correct initial balance,
 *   which may differ from [GroupProfile.spawnBudget] when the selector adjusts per-role allocations.
 */
@Serializable
data class AgentBootContext(
    val agentId: String,
    val runId: String,
    val groupId: String,
    val role: Role,
    val rolePrompt: String,
    val budgetToken: String,
    val substrateDbPath: String,
    val stagingMountPath: String,
    val artifactsMountPath: String? = null,
    val inputArtifacts: List<RunInputArtifact> = emptyList(),
    val startingField: StartingField? = null,
    val wipTaskClaim: WipTaskClaim?,
    val legacyProtocol: LegacyProtocolContext?,
    val stageDisclosure: String,
    val modelAssignment: String,
    val ipcBaseUrl: String,
    val generation: Int,
    val gracePeriodSeconds: Int = 30,
    /** Exact budget allocated to this agent by the EvolutionarySelector. */
    val allocatedBudget: Double = 0.0,
    /**
     * If this agent was spawned by a parent agent (sub-agent delegation), the parent's agentId.
     * Null for top-level agents.
     */
    val parentAgentId: String? = null,
    /**
     * True if this is a sub-agent (spawned via [SubAgentCoordinator], not by the LifecycleManager).
     * Sub-agents cannot spawn further sub-agents.
     */
    val isSubAgent: Boolean = false,
    /**
     * Evolving social norms for this group, loaded from the Knowledge Base at spawn time.
     *
     * If null, [com.achulkov.evokammer.agents.factory.RolePromptBuilder] falls back to the
     * hardcoded universal norms.  Norms can evolve: any agent can submit a
     * `write_staging(type="DOC", ...)` with domain `system:norms` to propose norm updates,
     * which go through Critic review like any other KB entry.
     */
    val dynamicNorms: String? = null
)

/**
 * A WiP task claim passed to an agent at spawn.
 *
 * @param nodeId The WiP task node identifier.
 * @param description Human-readable task description.
 */
@Serializable
data class WipTaskClaim(
    val nodeId: String,
    val description: String
)

/**
 * A Legacy Protocol injected as privileged context prefix into the agent's system prompt.
 *
 * @param protocolId Unique identifier for this protocol (for audit).
 * @param role The role this protocol was extracted from.
 * @param extractedFromGeneration The generation in which this protocol was extracted.
 * @param expiresAtGeneration The generation after which this protocol should not be used.
 * @param reasoningPosture Free-text summary of effective reasoning approaches.
 * @param toolHeuristics JSON-formatted tool usage hints.
 * @param failureMap JSON-formatted failure patterns and mitigations.
 * @param kgHabits JSON-formatted KG emission habits.
 */
@Serializable
data class LegacyProtocolContext(
    val protocolId: String,
    val role: Role,
    val extractedFromGeneration: Int,
    val expiresAtGeneration: Int,
    val reasoningPosture: String,
    val toolHeuristics: String,
    val failureMap: String,
    val kgHabits: String
)
