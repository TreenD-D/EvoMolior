@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.orchestration.lifecycle

import com.achulkov.evomolior.core.atlas.ToolLifecycleEvent
import com.achulkov.evomolior.core.atlas.ToolLifecyclePolicy
import com.achulkov.evomolior.core.atlas.ToolLifecycleState
import com.achulkov.evomolior.core.atlas.ToolVersionId
import com.achulkov.evomolior.core.atlas.ToolVersionTransitionDetails
import com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.tools.ToolInfrastructureRepository
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Result of one immutable maintenance, fork, or retirement request. */
sealed class ToolVersionTransitionResult {
    /** Transition committed with an optional immutable child version. */
    data class Committed(val state: ToolLifecycleState, val childVersionId: String?) :
        ToolVersionTransitionResult()

    /** Transition failed without intentionally deleting prior history. */
    data class Rejected(val reason: String) : ToolVersionTransitionResult()
}

/**
 * Governs immutable child-version maintenance/fork and active-version retirement.
 *
 * Child versions must independently pass the full typed promotion pipeline. Failed promotion
 * leaves the parent lifecycle and registry status unchanged.
 */
class ToolVersionTransitionService(
    private val database: EvoMoliorDatabase,
    private val promotionService: ToolPromotionService,
    private val repository: ToolInfrastructureRepository =
        ToolInfrastructureRepository(database),
    private val json: Json = Json { ignoreUnknownKeys = false },
    private val now: () -> Instant = { Clock.System.now() },
    private val idFactory: () -> String = { UUID.randomUUID().toString() }
) {
    /** Creates a same-name immutable child after full promotion validation. */
    suspend fun maintain(
        parentVersionId: String,
        submission: StagingSubmissionEntity,
        criticAgentId: String,
        maintenanceCost: Double,
        reason: String
    ): ToolVersionTransitionResult = createChild(
        state = ToolLifecycleState.MAINTAINED,
        parentVersionId = parentVersionId,
        submission = submission,
        criticAgentId = criticAgentId,
        maintenanceCost = maintenanceCost,
        reason = reason,
        requireSameName = true
    )

    /** Creates a differently named immutable child after full promotion validation. */
    suspend fun fork(
        parentVersionId: String,
        submission: StagingSubmissionEntity,
        criticAgentId: String,
        maintenanceCost: Double,
        reason: String
    ): ToolVersionTransitionResult = createChild(
        state = ToolLifecycleState.FORKED,
        parentVersionId = parentVersionId,
        submission = submission,
        criticAgentId = criticAgentId,
        maintenanceCost = maintenanceCost,
        reason = reason,
        requireSameName = false
    )

    /** Retires one active exact version atomically without deleting registry or execution history. */
    suspend fun retire(
        parentVersionId: String,
        maintenanceCost: Double,
        reason: String
    ): ToolVersionTransitionResult {
        val parent = database.toolRegistryDao().getById(parentVersionId)
            ?: return rejected("tool_transition_unknown_parent")
        if (parent.status != "active") return rejected("tool_transition_parent_not_active")
        val history = repository.lifecycle(ToolVersionId(parentVersionId))
        val previous = history.lastOrNull()
            ?: return rejected("tool_transition_parent_lacks_authority")
        val event = terminalEvent(
            previous = previous,
            state = ToolLifecycleState.RETIRED,
            childVersionId = null,
            maintenanceCost = maintenanceCost,
            reason = reason
        )
        return runCatching {
            repository.retireVersion(event)
            ToolVersionTransitionResult.Committed(ToolLifecycleState.RETIRED, null)
        }.getOrElse { rejected("tool_transition_retirement_failed:${it.message.orEmpty().take(200)}") }
    }

    private suspend fun createChild(
        state: ToolLifecycleState,
        parentVersionId: String,
        submission: StagingSubmissionEntity,
        criticAgentId: String,
        maintenanceCost: Double,
        reason: String,
        requireSameName: Boolean
    ): ToolVersionTransitionResult {
        val parent = database.toolRegistryDao().getById(parentVersionId)
            ?: return rejected("tool_transition_unknown_parent")
        if (parent.status != "active") return rejected("tool_transition_parent_not_active")
        val childName = runCatching {
            json.parseToJsonElement(submission.content).jsonObject["name"]
                ?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: return rejected("tool_transition_invalid_submission_name")
        if ((childName == parent.name) != requireSameName) {
            return rejected(
                if (requireSameName) "tool_maintenance_requires_same_name"
                else "tool_fork_requires_distinct_name"
            )
        }
        val promotion = promotionService.promote(
            submission = submission,
            criticAgentId = criticAgentId,
            lineageParentVersionId = parentVersionId,
            lineageTransition = ToolPromotionLineageTransition(
                state = state,
                maintenanceCost = maintenanceCost,
                reason = reason
            )
        )
        val childVersionId = when (promotion) {
            is ToolPromotionResult.Promoted -> promotion.toolVersionId
            is ToolPromotionResult.Rejected ->
                return rejected("tool_transition_child_${promotion.reason}")
        }
        return ToolVersionTransitionResult.Committed(state, childVersionId)
    }

    private fun terminalEvent(
        previous: ToolLifecycleEvent,
        state: ToolLifecycleState,
        childVersionId: ToolVersionId?,
        maintenanceCost: Double,
        reason: String
    ): ToolLifecycleEvent {
        val event = ToolLifecycleEvent(
            eventId = idFactory(),
            runId = previous.runId,
            opportunityId = previous.opportunityId,
            toolVersionId = previous.toolVersionId,
            groupId = previous.groupId,
            originNodeId = previous.originNodeId,
            contextNodeId = previous.contextNodeId,
            state = state,
            sequence = previous.sequence + 1,
            previousEventHash = previous.eventHash,
            authorityReferenceIds = setOfNotNull(childVersionId?.value),
            transitionDetails = ToolVersionTransitionDetails(
                childToolVersionId = childVersionId,
                maintenanceCost = maintenanceCost,
                reason = reason
            ),
            recordedAt = now()
        ).withComputedHash()
        require(ToolLifecyclePolicy.allows(previous, event)) {
            "Illegal terminal tool lifecycle transition"
        }
        return event
    }

    private fun rejected(reason: String) = ToolVersionTransitionResult.Rejected(reason)
}
