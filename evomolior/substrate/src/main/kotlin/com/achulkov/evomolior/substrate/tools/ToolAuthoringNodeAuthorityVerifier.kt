package com.achulkov.evomolior.substrate.tools

import com.achulkov.evomolior.core.atlas.ToolExecutionPurpose
import com.achulkov.evomolior.core.atlas.ToolExecutionResult
import com.achulkov.evomolior.core.atlas.ToolLifecyclePolicy
import com.achulkov.evomolior.core.atlas.ToolLifecycleState
import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitment
import com.achulkov.evomolior.core.atlas.ToolVersionId
import com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.db.entity.ToolRegistryEntryEntity

/**
 * Verifies that one staged TOOL created exact active authority for its sealed authoring node.
 *
 * Generic canonical artifacts are intentionally outside this authority plane.
 *
 * @param database Group-local substrate containing tool lifecycle and registry authority.
 */
class ToolAuthoringNodeAuthorityVerifier(
    private val database: EvoMoliorDatabase
) {
    private val repository = ToolInfrastructureRepository(database)

    /**
     * Returns the exact active version or throws when any authoring authority is missing.
     *
     * @param commitment Sealed Atlas opportunity commitment.
     * @param submission Exact staged TOOL authored for the commitment's node.
     * @param toolVersionId Version claimed as the promotion result.
     * @return Active registry authority for the exact version.
     */
    suspend fun requireActivePromotedVersion(
        commitment: ToolOpportunityCommitment,
        submission: StagingSubmissionEntity,
        toolVersionId: ToolVersionId
    ): ToolRegistryEntryEntity {
        require(commitment.hasValidHash()) {
            "Tool authoring authority requires a valid opportunity commitment"
        }
        require(
            submission.type == "TOOL" &&
                submission.runId == commitment.runId &&
                submission.wipTaskRef == commitment.nodeId.value
        ) {
            "Tool authoring submission does not match its sealed opportunity"
        }
        require(repository.opportunity(commitment.opportunity.opportunityId) == commitment) {
            "Tool authoring opportunity was not persisted exactly"
        }
        val registry = requireNotNull(database.toolRegistryDao().getById(toolVersionId.value)) {
            "Tool authoring version is absent from the registry"
        }
        require(
            registry.runId == commitment.runId &&
                registry.groupId == submission.groupId &&
                registry.authorAgentId == submission.agentId &&
                registry.generation == submission.generation &&
                registry.status == "active"
        ) {
            "Tool authoring registry version is not active exact author authority"
        }

        val lifecycle = repository.lifecycle(toolVersionId)
        require(
            lifecycle.isNotEmpty() &&
                lifecycle.all { it.hasValidHash() } &&
                ToolLifecyclePolicy.allows(null, lifecycle.first()) &&
                lifecycle.zipWithNext().all { (previous, next) ->
                    ToolLifecyclePolicy.allows(previous, next)
                }
        ) {
            "Tool authoring lifecycle is absent, invalid, or not hash-linked"
        }
        require(lifecycle.all { event ->
            event.runId == commitment.runId &&
                event.groupId.value == submission.groupId &&
                event.opportunityId == commitment.opportunity.opportunityId &&
                event.originNodeId == commitment.nodeId &&
                event.toolVersionId == toolVersionId
        }) {
            "Tool authoring lifecycle crosses sealed provenance"
        }
        val staticValidated = lifecycle.singleOrNull {
            it.state == ToolLifecycleState.STATIC_VALIDATED
        }
        require(
            staticValidated != null &&
                staticValidated.authorityReferenceIds
                    .containsAll(commitment.opportunity.promotionTestIds)
        ) {
            "Tool authoring lifecycle lacks the exact committed promotion-test IDs"
        }
        val sandboxTested = lifecycle.singleOrNull {
            it.state == ToolLifecycleState.SANDBOX_TESTED
        }
        val promotionExecutionIds = requireNotNull(sandboxTested)
            .authorityReferenceIds
        require(
            promotionExecutionIds.size == commitment.opportunity.promotionTestIds.size
        ) {
            "Tool authoring lifecycle has incomplete promotion-test execution authority"
        }
        val promotionExecutions = promotionExecutionIds.map { executionId ->
            requireNotNull(repository.execution(executionId)) {
                "Tool authoring lifecycle references an unknown promotion execution"
            }
        }
        require(promotionExecutions.all { execution ->
            execution.hasValidHash() &&
                execution.runId == commitment.runId &&
                execution.groupId.value == submission.groupId &&
                execution.toolVersionId == toolVersionId &&
                execution.opportunityId == commitment.opportunity.opportunityId &&
                execution.nodeId == commitment.nodeId &&
                execution.round == submission.generation &&
                execution.purpose == ToolExecutionPurpose.PROMOTION_TEST &&
                execution.result == ToolExecutionResult.SUCCEEDED
        }) {
            "Tool authoring promotion executions are not exact successful authority"
        }
        require(lifecycle.any { event ->
            event.state == ToolLifecycleState.CRITIC_ACCEPTED &&
                submission.id in event.authorityReferenceIds
        }) {
            "Tool authoring lifecycle is not bound to the staged submission"
        }
        require(lifecycle.any { event ->
            event.state == ToolLifecycleState.PROMOTED &&
                registry.id in event.authorityReferenceIds
        }) {
            "Tool authoring lifecycle lacks active registry promotion authority"
        }
        return registry
    }
}
