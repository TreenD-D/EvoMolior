@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.core.contract

import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.model.SubmissionType
import kotlinx.serialization.Serializable

/** Typed purpose of a redacted worker authority supplied at boot. */
@Serializable
enum class WorkerAuthorityScope {
    NODE_ASSIGNMENT,
    OUTLIER_MANDATE,
    PERSPECTIVE_TAKER,
    SPECIAL_PURPOSE
}

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
 * @param sourceAllowedHosts Hosts currently approved for source access in this run.
 * @param sourceAccessAuditPath Host path for append-only source-access decision records.
 * @param startingField Original run seed for this civilisation, if available.
 * @param workerView Redacted Atlas authority for an ordinary mortal worker.
 * @param workerAuthorityScope Typed reason this worker may receive node or no-node authority.
 * @param assignedLease Redacted lease identity for node-bound ordinary work.
 * @param ttlPolicy Typed mortality deadline and checkpoint schedule.
 * @param canonicalWorkingContext Bounded spawn-time snapshot of canonical substrate state for
 *   text-only backends that cannot call EvoMolior read tools.
 * @param textOnlySubmissionType Explicit output-media intent assigned by orchestration. This is
 *   used only when the backend cannot call typed staging tools; prompt prose cannot alter it.
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
    val sourceAllowedHosts: List<String> = emptyList(),
    val sourceAccessAuditPath: String? = null,
    val startingField: StartingField? = null,
    val workerView: WorkerView? = null,
    val workerAuthorityScope: WorkerAuthorityScope = WorkerAuthorityScope.NODE_ASSIGNMENT,
    val assignedLease: WorkerLeaseAssignment? = null,
    val ttlPolicy: AgentTtlPolicy? = null,
    val canonicalWorkingContext: String? = null,
    val textOnlySubmissionType: SubmissionType? = null,
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
     * If null, [com.achulkov.evomolior.agents.factory.RolePromptBuilder] falls back to the
     * hardcoded universal norms.  Norms can evolve: any agent can submit a
     * `write_staging(type="DOC", ...)` with domain `system:norms` to propose norm updates,
     * which go through Critic review like any other KB entry.
     */
    val dynamicNorms: String? = null
) {
    /**
     * Fails closed when an ordinary worker context crosses the full-field or lease boundary.
     *
     * Legacy/offline constructors remain decodable, but compiled production must invoke this
     * before execution.
     */
    fun requireValidWorkerAuthority(): AgentBootContext = apply {
        if (!role.isOrdinaryWorkerRole) return@apply
        require(startingField == null) { "Ordinary workers cannot receive a StartingField" }
        val view = requireNotNull(workerView) { "Ordinary workers require a WorkerView" }
        require(view.hasValidHash()) { "Ordinary worker view hash is invalid" }
        require(view.runId == runId && view.groupId.value == groupId) {
            "WorkerView run/group authority does not match the boot context"
        }
        val ttl = requireNotNull(ttlPolicy) {
            "Ordinary workers require a typed TTL policy"
        }
        require(ttl.lifetimeSeconds > 0) {
            "Ordinary worker TTL must be positive"
        }
        when (workerAuthorityScope) {
            WorkerAuthorityScope.NODE_ASSIGNMENT -> {
                require(role != Role.Outlier && role != Role.PerspectiveTaker) {
                    "Outliers and PerspectiveTakers cannot use node-assignment authority"
                }
                val assignedNode = requireNotNull(view.assignedNode) {
                    "Node-assigned workers require one assigned node card"
                }
                require(assignedNode in view.revealedNodes) {
                    "Assigned node must be present in the revealed worker view"
                }
                val lease = requireNotNull(assignedLease) {
                    "Node-assigned workers require an Atlas lease"
                }
                val wip = requireNotNull(wipTaskClaim) {
                    "Node-assigned workers require a matching WIP projection"
                }
                require(
                    assignedNode.nodeId == lease.nodeId &&
                        wip.nodeId == lease.nodeId.value
                ) {
                    "WorkerView assignment, Atlas lease, and WIP projection must match"
                }
                require(lease.leaseId.isNotBlank() && lease.replicationSlot >= 0) {
                    "Assigned Atlas lease identity is invalid"
                }
                require(lease.expiresAt <= ttl.deadline) {
                    "Assigned Atlas lease cannot outlive the worker TTL"
                }
            }
            WorkerAuthorityScope.OUTLIER_MANDATE -> {
                require(role == Role.Outlier) {
                    "Only Outliers may use Outlier-mandate authority"
                }
                require(view.outlierMandate?.isNotBlank() == true) {
                    "Outliers require an explicit novelty mandate"
                }
                require(view.assignedNode == null && assignedLease == null && wipTaskClaim == null) {
                    "Outliers cannot receive a node assignment, lease, or WIP claim"
                }
                require(legacyProtocol == null) {
                    "Outliers cannot receive a Legacy Protocol"
                }
            }
            WorkerAuthorityScope.PERSPECTIVE_TAKER -> {
                require(role == Role.PerspectiveTaker) {
                    "Only PerspectiveTakers may use perspective authority"
                }
                require(view.assignedNode == null && assignedLease == null && wipTaskClaim == null) {
                    "PerspectiveTakers cannot receive a node assignment, lease, or WIP claim"
                }
                require(legacyProtocol == null) {
                    "PerspectiveTakers cannot receive a Legacy Protocol"
                }
            }
            WorkerAuthorityScope.SPECIAL_PURPOSE -> {
                require(role != Role.Outlier && role != Role.PerspectiveTaker) {
                    "Outlier and PerspectiveTaker authority require their dedicated scope"
                }
                require(view.assignedNode == null && assignedLease == null && wipTaskClaim == null) {
                    "Special-purpose worker authority cannot carry ordinary node work"
                }
                require(legacyProtocol == null) {
                    "Special-purpose worker authority cannot receive a Legacy Protocol"
                }
            }
        }
    }
}

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
