package com.achulkov.evomolior.substrate.atlas

import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.NodeEvidenceAuthorityType
import com.achulkov.evomolior.core.atlas.NodeEvidenceCostSummary
import com.achulkov.evomolior.core.atlas.NodeEvidencePacket
import com.achulkov.evomolior.core.atlas.NodeEvidenceProvenanceEntry
import com.achulkov.evomolior.core.atlas.NodeEvidenceProvenanceManifest
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.ToolExecutionPurpose
import com.achulkov.evomolior.core.atlas.ToolExecutionRecord
import com.achulkov.evomolior.core.atlas.ToolExecutionResult
import com.achulkov.evomolior.core.atlas.ToolLifecyclePolicy
import com.achulkov.evomolior.core.atlas.ToolLifecycleState
import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitment
import com.achulkov.evomolior.core.atlas.ToolVersionId
import com.achulkov.evomolior.core.atlas.toolValidationNodeIds
import com.achulkov.evomolior.core.contract.CommitType
import com.achulkov.evomolior.core.contract.ContributionCommit
import com.achulkov.evomolior.core.contract.DegradedExecutionRecord
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.db.entity.ToolRegistryEntryEntity
import com.achulkov.evomolior.substrate.tools.ToolInfrastructureRepository
import com.achulkov.evomolior.substrate.tools.ToolAuthoringNodeAuthorityVerifier
import com.achulkov.evomolior.substrate.worker.ContributionGraphRepository
import kotlinx.serialization.json.Json

/**
 * Request to seal one exact Critic-accepted node evidence packet.
 *
 * Artifact and claim IDs are staging submission IDs whose canonical promotion references were
 * recorded by [com.achulkov.evomolior.orchestration.lifecycle.CriticPipeline]. Tool, test, and
 * negative-result IDs must be claimed by a typed contribution from the same node and round.
 */
data class SealNodeEvidenceRequest(
    val runId: String,
    val round: Int,
    val groupId: AtlasGroupId,
    val nodeId: NodeId,
    val atlasVersion: Int,
    val atlasHash: String,
    val nodePayloadHash: String,
    val acceptedArtifactIds: List<String> = emptyList(),
    val acceptedClaimIds: List<String> = emptyList(),
    val toolRunIds: List<String> = emptyList(),
    val testResultIds: List<String> = emptyList(),
    val negativeResultIds: List<String> = emptyList()
)

/**
 * Seals node evidence only after exact Critic promotion and contribution authority are proven.
 *
 * Round summaries, rejected or pending staging rows, runtime fallbacks, Death Notes, degraded
 * artifacts, `NO_CONTRIBUTION`, mixed-scope references, and payload mismatches fail closed.
 */
class NodeEvidencePacketSealer(
    private val databaseFactory: SubstrateDatabaseFactory,
    private val captureRepository: NodeCaptureRepository,
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }
) {
    /** Validates, deterministically seals, and append-only persists one packet. */
    suspend fun seal(request: SealNodeEvidenceRequest): NodeEvidencePacket {
        validateRequestShape(request)
        val atlas = validateAtlasBinding(request)

        val groupDatabase = databaseFactory.get(request.runId, request.groupId.value)
        val stagingIds = (request.acceptedArtifactIds + request.acceptedClaimIds).distinct()
        val promotions = groupDatabase.stagingDao().getByIds(stagingIds)
        require(promotions.size == stagingIds.size) {
            "Every accepted artifact and claim must reference an existing staging submission"
        }
        val promotionById = promotions.associateBy(StagingSubmissionEntity::id)
        stagingIds.forEach { id ->
            validatePromotion(request, promotionById.getValue(id))
        }

        val contributionEntities = groupDatabase.workerExecutionDao()
            .contributionsForNode(request.runId, request.groupId.value, request.nodeId.value)
        val contributions = contributionEntities
            .map { json.decodeFromString(ContributionCommit.serializer(), it.contentJson) }
            .filter { it.round == request.round && it.commitType != CommitType.NO_CONTRIBUTION }
        require(contributions.isNotEmpty()) {
            "Node evidence requires a typed useful contribution in the same round"
        }
        promotions.forEach { promotion ->
            require(contributions.any { contribution ->
                contribution.agentId.value == promotion.agentId &&
                    promotion.matches(contribution)
            }) {
                "Critic promotion ${promotion.id} is not bound to a same-round node contribution"
            }
        }
        val toolAuthoringAuthority = validateToolAuthoringAuthority(
            request = request,
            atlas = atlas,
            promotions = promotions,
            contributions = contributions,
            groupDatabase = groupDatabase
        )
        validateContributionReferences(request, contributions)
        val toolRepository = ToolInfrastructureRepository(groupDatabase)
        val toolExecutions = validateToolExecutions(
            request = request,
            atlas = atlas,
            contributions = contributions,
            repository = toolRepository
        )
        validateRequiredToolExecutions(
            request = request,
            atlas = atlas,
            executions = toolExecutions.values,
            repository = toolRepository,
            groupDatabase = groupDatabase
        )
        rejectDegradedReferences(request, groupDatabase.workerExecutionDao()
            .degradedExecutionsForNode(
                request.runId,
                request.groupId.value,
                request.nodeId.value
            )
            .map { json.decodeFromString(DegradedExecutionRecord.serializer(), it.contentJson) })

        val provenanceEntries = buildProvenanceEntries(
            request,
            promotions,
            contributions,
            toolExecutions,
            toolAuthoringAuthority
        )
        val cost = aggregateContributionCost(contributions)
        val provenanceRootHash = AtlasHashing.hashNodeEvidenceProvenance(
            NodeEvidenceProvenanceManifest(
                runId = request.runId,
                round = request.round,
                groupId = request.groupId,
                nodeId = request.nodeId,
                entries = provenanceEntries
            )
        )
        val sealedAt = maxOf(
            promotions.maxOf { it.reviewedAtMsOrThrow() },
            contributionEntities
                .filter { it.round == request.round }
                .maxOf { it.committedAtMs }
        )
        val preliminary = NodeEvidencePacket(
            packetId = "",
            runId = request.runId,
            round = request.round,
            groupId = request.groupId,
            nodeId = request.nodeId,
            atlasVersion = request.atlasVersion,
            atlasHash = request.atlasHash,
            nodePayloadHash = request.nodePayloadHash,
            acceptedArtifactIds = request.acceptedArtifactIds,
            acceptedClaimIds = request.acceptedClaimIds,
            toolRunIds = request.toolRunIds,
            testResultIds = request.testResultIds,
            negativeResultIds = request.negativeResultIds,
            provenanceRootHash = provenanceRootHash,
            cost = cost,
            sealedAtEpochMillis = sealedAt
        )
        val packetId = "evidence:${AtlasHashing.hashNodeEvidencePacket(preliminary)}"
        val packet = captureRepository.appendEvidencePacket(
            preliminary.copy(packetId = packetId).withComputedHash()
        )
        ContributionGraphRepository(
            database = groupDatabase,
            atlasDatabase = databaseFactory.getAtlas(request.runId)
        ).recordEvidenceLineage(packet)
        return packet
    }

    private fun aggregateContributionCost(
        contributions: List<ContributionCommit>
    ): NodeEvidenceCostSummary {
        val summaries = contributions.map { contribution ->
            requireNotNull(contribution.costSummary) {
                "Contribution ${contribution.commitId} lacks an exact typed cost snapshot"
            }
        }
        val resourceKeys = summaries.flatMap { it.resourceCosts.keys }.toSortedSet()
        return NodeEvidenceCostSummary(
            totalCredits = summaries.sumOf(NodeEvidenceCostSummary::totalCredits),
            llmTokens = summaries.sumOf(NodeEvidenceCostSummary::llmTokens),
            toolExecutions = summaries.sumOf(NodeEvidenceCostSummary::toolExecutions),
            sourceFetches = summaries.sumOf(NodeEvidenceCostSummary::sourceFetches),
            wallClockMillis = summaries.sumOf(NodeEvidenceCostSummary::wallClockMillis),
            resourceCosts = resourceKeys.associateWith { key ->
                summaries.sumOf { it.resourceCosts.getOrDefault(key, 0.0) }
            }
        )
    }

    private suspend fun validateAtlasBinding(request: SealNodeEvidenceRequest): NodeAtlas {
        val version = requireNotNull(
            databaseFactory.getAtlas(request.runId).atlasDao().versionByHash(request.atlasHash)
        ) {
            "Evidence packet references an unknown sealed Atlas hash"
        }
        require(version.runId == request.runId && version.version == request.atlasVersion) {
            "Evidence packet Atlas version does not match its exact hash"
        }
        val atlas = json.decodeFromString(NodeAtlas.serializer(), version.sealedAtlasJson)
        val node = requireNotNull(atlas.nodes[request.nodeId]) {
            "Evidence packet references a node outside its sealed Atlas"
        }
        require(
            AtlasHashing.constantTimeEquals(node.payloadHash, request.nodePayloadHash) &&
                node.hasValidPayloadHash()
        ) {
            "Evidence packet node payload commitment is invalid"
        }
        return atlas
    }

    private suspend fun validateRequiredToolExecutions(
        request: SealNodeEvidenceRequest,
        atlas: NodeAtlas,
        executions: Collection<ToolExecutionRecord>,
        repository: ToolInfrastructureRepository,
        groupDatabase: com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
    ) {
        requiredToolExecutions(atlas, request.nodeId).forEach { requirement ->
            val authoritativeExecutions = executions.filter { execution ->
                execution.opportunityId ==
                    requirement.commitment.opportunity.opportunityId &&
                    execution.purpose == requirement.purpose
            }
            require(authoritativeExecutions.isNotEmpty()) {
                "Node ${request.nodeId.value} lacks successful ${requirement.purpose.name} " +
                    "authority for opportunity " +
                    requirement.commitment.opportunity.opportunityId
            }
            require(authoritativeExecutions.any { execution ->
                val persistedCommitment = repository.opportunity(execution.opportunityId)
                val lifecycle = repository.lifecycle(execution.toolVersionId)
                val registryEntry = groupDatabase.toolRegistryDao()
                    .getById(execution.toolVersionId.value)
                persistedCommitment == requirement.commitment &&
                    lifecycle.isNotEmpty() &&
                    lifecycle.all { it.hasValidHash() } &&
                    ToolLifecyclePolicy.allows(null, lifecycle.first()) &&
                    lifecycle.zipWithNext().all { (previous, next) ->
                        ToolLifecyclePolicy.allows(previous, next)
                    } &&
                    lifecycle.first().runId == request.runId &&
                    lifecycle.first().groupId == request.groupId &&
                    lifecycle.first().opportunityId ==
                    requirement.commitment.opportunity.opportunityId &&
                    lifecycle.firstOrNull()?.originNodeId == requirement.commitment.nodeId &&
                    lifecycle.any { it.state == ToolLifecycleState.PROMOTED } &&
                    (
                        requirement.purpose != ToolExecutionPurpose.AGENT_USE ||
                            lifecycle.any { event ->
                                event.state == ToolLifecycleState.EXECUTED_BY_LATER_AGENT &&
                                    event.contextNodeId == request.nodeId &&
                                    execution.executionId in event.authorityReferenceIds
                            }
                        ) &&
                    registryEntry != null &&
                    registryEntry.runId == request.runId &&
                    registryEntry.groupId == request.groupId.value &&
                    registryEntry.status == "active" &&
                    registryEntry.authorAgentId.isNotBlank() &&
                    registryEntry.authorAgentId != execution.agentId.value &&
                    execution.round > registryEntry.generation
            }) {
                "${requirement.purpose.name} requires an exact promoted version executed in a " +
                    "later round by an independent mortal agent"
            }
        }
    }

    private suspend fun validateToolAuthoringAuthority(
        request: SealNodeEvidenceRequest,
        atlas: NodeAtlas,
        promotions: List<StagingSubmissionEntity>,
        contributions: List<ContributionCommit>,
        groupDatabase: com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
    ): ToolAuthoringEvidenceAuthority? {
        val opportunity = atlas.nodes.getValue(request.nodeId).toolOpportunity ?: return null
        val commitment = ToolOpportunityCommitment(
            runId = atlas.runId,
            atlasHash = atlas.atlasHash,
            nodeId = request.nodeId,
            opportunity = opportunity
        ).withComputedHash()
        val verifier = ToolAuthoringNodeAuthorityVerifier(groupDatabase)
        val authorized = promotions
            .filter { it.type == "TOOL" }
            .mapNotNull { submission ->
                val versionId = submission.promotedArtifactRef
                    ?.takeIf { it.startsWith(TOOL_ARTIFACT_PREFIX) }
                    ?.removePrefix(TOOL_ARTIFACT_PREFIX)
                    ?.takeIf(String::isNotBlank)
                    ?.let(::ToolVersionId)
                    ?: return@mapNotNull null
                runCatching {
                    verifier.requireActivePromotedVersion(
                        commitment = commitment,
                        submission = submission,
                        toolVersionId = versionId
                    )
                }.getOrNull()?.let { registry -> submission to registry }
            }
            .firstOrNull { (submission, _) ->
                contributions.any { contribution ->
                    contribution.commitType == CommitType.REUSABLE_TOOL &&
                        contribution.agentId.value == submission.agentId &&
                        submission.matches(contribution)
                }
            }
        val (submission, registry) = requireNotNull(authorized) {
            "Tool-opportunity node evidence requires an exact active promoted version " +
                "bound to the authoring agent's REUSABLE_TOOL contribution"
        }
        require(
            promotions.size == 1 &&
                promotions.single().id == submission.id &&
                request.acceptedClaimIds.isEmpty() &&
                request.toolRunIds.isEmpty()
        ) {
            "Tool-opportunity node evidence may contain only its exact promoted TOOL artifact"
        }
        require(contributions.all { contribution ->
            contribution.commitType == CommitType.REUSABLE_TOOL &&
                contribution.agentId.value == submission.agentId &&
                submission.matches(contribution)
        }) {
            "Tool-opportunity node evidence cannot mix generic or unrelated contributions"
        }
        val promotedEvent = ToolInfrastructureRepository(groupDatabase)
            .lifecycle(ToolVersionId(registry.id))
            .single { it.state == ToolLifecycleState.PROMOTED }
        return ToolAuthoringEvidenceAuthority(
            submission = submission,
            registry = registry,
            promotedEventId = promotedEvent.eventId,
            promotedEventHash = promotedEvent.eventHash
        )
    }

    private fun validatePromotion(
        request: SealNodeEvidenceRequest,
        submission: StagingSubmissionEntity
    ) {
        require(submission.runId == request.runId && submission.groupId == request.groupId.value) {
            "Evidence promotion belongs to a different run or group"
        }
        require(submission.status == "promoted" &&
            submission.reviewedAtMs != null &&
            submission.criticAgentId != null &&
            submission.promotedArtifactRef != null &&
            submission.promotedArtifactHash != null
        ) {
            "Evidence requires a completed Critic promotion with canonical artifact authority"
        }
        require(submission.wipTaskRef == request.nodeId.value) {
            "Evidence promotion belongs to a different node"
        }
        require(submission.type != "DEATH_NOTE") {
            "Auto-promoted Death Notes are not node-grade evidence"
        }
        require(!submission.isRuntimeFallback()) {
            "Runtime fallback submissions are not node-grade evidence"
        }
        require(
            AtlasHashing.constantTimeEquals(
                submission.promotedArtifactHash,
                AtlasHashing.hashText(submission.content)
            )
        ) {
            "Promoted artifact content no longer matches its Critic commitment"
        }
    }

    private fun validateContributionReferences(
        request: SealNodeEvidenceRequest,
        contributions: List<ContributionCommit>
    ) {
        fun requireClaimed(reference: String, selector: (ContributionCommit) -> List<String>) {
            require(contributions.any { reference in selector(it) }) {
                "Evidence reference $reference lacks same-round contribution authority"
            }
        }
        request.toolRunIds.forEach { reference ->
            requireClaimed(reference, ContributionCommit::toolRunIds)
        }
        request.testResultIds.forEach { reference ->
            requireClaimed(reference, ContributionCommit::testsRun)
        }
        request.negativeResultIds.forEach { reference ->
            require(contributions.any { contribution ->
                contribution.commitType in NEGATIVE_COMMIT_TYPES &&
                    (reference in contribution.artifactIds || reference in contribution.claimIds)
            }) {
                "Negative result $reference lacks typed falsification or dead-end authority"
            }
        }
    }

    private suspend fun validateToolExecutions(
        request: SealNodeEvidenceRequest,
        atlas: NodeAtlas,
        contributions: List<ContributionCommit>,
        repository: ToolInfrastructureRepository
    ): Map<String, ToolExecutionRecord> {
        val requirements = requiredToolExecutions(atlas, request.nodeId)
            .associateBy { it.commitment.opportunity.opportunityId }
        return request.toolRunIds.associateWith { executionId ->
            val execution = requireNotNull(repository.execution(executionId)) {
                "Tool evidence references unknown or unpersisted execution $executionId"
            }
            val requirement = requireNotNull(requirements[execution.opportunityId]) {
                "Tool execution $executionId is not required by node ${request.nodeId.value}"
            }
            require(
                execution.hasValidHash() &&
                    execution.result == ToolExecutionResult.SUCCEEDED &&
                    execution.purpose == requirement.purpose
            ) {
                "Tool evidence requires a successful ${requirement.purpose.name} execution"
            }
            require(
                execution.runId == request.runId &&
                    execution.groupId == request.groupId &&
                    execution.round == request.round &&
                    execution.nodeId == request.nodeId
            ) {
                "Tool execution crosses run, group, round, or node evidence authority"
            }
            require(contributions.any { contribution ->
                contribution.agentId == execution.agentId &&
                    executionId in contribution.toolRunIds
            }) {
                "Tool execution is not claimed by its exact executing agent contribution"
            }
            execution
        }
    }

    private fun requiredToolExecutions(
        atlas: NodeAtlas,
        nodeId: NodeId
    ): List<RequiredToolExecution> {
        val requirements = atlas.nodes.values.mapNotNull { sourceNode ->
            val opportunity = sourceNode.toolOpportunity ?: return@mapNotNull null
            val validationTarget = nodeId in atlas.toolValidationNodeIds(sourceNode.id)
            val reuseTarget = nodeId in opportunity.expectedReuseNodeIds
            require(!validationTarget || !reuseTarget) {
                "Atlas node ${nodeId.value} cannot be both validation and expected reuse for " +
                    opportunity.opportunityId
            }
            val purpose = when {
                validationTarget -> ToolExecutionPurpose.VALIDATION_TEST
                reuseTarget -> ToolExecutionPurpose.AGENT_USE
                else -> return@mapNotNull null
            }
            RequiredToolExecution(
                commitment = ToolOpportunityCommitment(
                    runId = atlas.runId,
                    atlasHash = atlas.atlasHash,
                    nodeId = sourceNode.id,
                    opportunity = opportunity
                ).withComputedHash(),
                purpose = purpose
            )
        }
        require(
            requirements.map { it.commitment.opportunity.opportunityId }
                .distinct().size == requirements.size
        ) {
            "Required tool execution opportunities must be unique for node ${nodeId.value}"
        }
        return requirements
    }

    private fun rejectDegradedReferences(
        request: SealNodeEvidenceRequest,
        degradedRecords: List<DegradedExecutionRecord>
    ) {
        val requestedReferences = request.acceptedArtifactIds.toSet() +
            request.acceptedClaimIds +
            request.toolRunIds +
            request.testResultIds +
            request.negativeResultIds
        val degradedReferences = degradedRecords.flatMapTo(mutableSetOf()) {
            it.artifactIds
        }
        require(requestedReferences.intersect(degradedReferences).isEmpty()) {
            "Degraded execution references cannot enter a node evidence packet"
        }
    }

    private fun buildProvenanceEntries(
        request: SealNodeEvidenceRequest,
        promotions: List<StagingSubmissionEntity>,
        contributions: List<ContributionCommit>,
        toolExecutions: Map<String, ToolExecutionRecord>,
        toolAuthoringAuthority: ToolAuthoringEvidenceAuthority?
    ): List<NodeEvidenceProvenanceEntry> {
        val promotionEntries = promotions.map { promotion ->
            NodeEvidenceProvenanceEntry(
                authorityType = NodeEvidenceAuthorityType.CRITIC_PROMOTION,
                referenceId = promotion.id,
                authorityId = promotion.id,
                authorityHash = requireNotNull(promotion.promotedArtifactHash)
            )
        }
        val contributionEntries = promotions.map { promotion ->
            val contribution = contributions
                .filter { candidate ->
                    candidate.agentId.value == promotion.agentId &&
                        promotion.matches(candidate)
                }
                .minBy(ContributionCommit::commitId)
            NodeEvidenceProvenanceEntry(
                authorityType = NodeEvidenceAuthorityType.CONTRIBUTION_COMMIT,
                referenceId = promotion.id,
                authorityId = contribution.commitId,
                authorityHash = contribution.dedupeHash
            )
        }
        val typedEntries = buildList {
            toolAuthoringAuthority?.let { authority ->
                add(
                    NodeEvidenceProvenanceEntry(
                        authorityType = NodeEvidenceAuthorityType.TOOL_VERSION,
                        referenceId = authority.registry.id,
                        authorityId = authority.promotedEventId,
                        authorityHash = authority.promotedEventHash
                    )
                )
            }
            request.toolRunIds.forEach { reference ->
                val execution = toolExecutions.getValue(reference)
                add(
                    NodeEvidenceProvenanceEntry(
                        authorityType = NodeEvidenceAuthorityType.TOOL_RUN,
                        referenceId = reference,
                        authorityId = execution.executionId,
                        authorityHash = execution.recordHash
                    )
                )
            }
            request.testResultIds.forEach { reference ->
                add(contributionEntry(reference, NodeEvidenceAuthorityType.TEST_RESULT, contributions) {
                    reference in it.testsRun
                })
            }
            request.negativeResultIds.forEach { reference ->
                add(
                    contributionEntry(
                        reference,
                        NodeEvidenceAuthorityType.NEGATIVE_RESULT,
                        contributions
                    ) {
                        it.commitType in NEGATIVE_COMMIT_TYPES &&
                            (reference in it.artifactIds || reference in it.claimIds)
                    }
                )
            }
        }
        return (promotionEntries + contributionEntries + typedEntries).sortedWith(
            compareBy<NodeEvidenceProvenanceEntry>(
                { it.authorityType.name },
                NodeEvidenceProvenanceEntry::referenceId,
                NodeEvidenceProvenanceEntry::authorityId
            )
        )
    }

    private fun contributionEntry(
        reference: String,
        type: NodeEvidenceAuthorityType,
        contributions: List<ContributionCommit>,
        matches: (ContributionCommit) -> Boolean
    ): NodeEvidenceProvenanceEntry {
        val authority = contributions.filter(matches).minBy(ContributionCommit::commitId)
        return NodeEvidenceProvenanceEntry(
            authorityType = type,
            referenceId = reference,
            authorityId = authority.commitId,
            authorityHash = authority.dedupeHash
        )
    }

    private fun validateRequestShape(request: SealNodeEvidenceRequest) {
        require(request.round >= 0) { "Evidence round cannot be negative" }
        require(request.atlasVersion > 0) { "Evidence Atlas version must be positive" }
        require(request.acceptedArtifactIds.isNotEmpty() ||
            request.acceptedClaimIds.isNotEmpty()
        ) {
            "Evidence packet requires at least one exact Critic-promoted artifact or claim"
        }
        listOf(
            request.acceptedArtifactIds,
            request.acceptedClaimIds,
            request.toolRunIds,
            request.testResultIds,
            request.negativeResultIds
        ).forEach { references ->
            require(references.all(String::isNotBlank) &&
                references.size == references.distinct().size
            ) {
                "Evidence references must be non-blank and unique within their type"
            }
        }
    }

    private fun StagingSubmissionEntity.matches(contribution: ContributionCommit): Boolean {
        val authorityRefs = contribution.artifactIds + contribution.claimIds
        return id in authorityRefs || promotedArtifactRef in authorityRefs
    }

    private fun StagingSubmissionEntity.isRuntimeFallback(): Boolean =
        budgetSignature.startsWith("SUBSTRATE_CONTRACT_FALLBACK:") ||
            budgetSignature.startsWith("NO_ACTION_FALLBACK:")

    private fun StagingSubmissionEntity.reviewedAtMsOrThrow(): Long =
        requireNotNull(reviewedAtMs) { "Critic promotion lacks review timestamp" }

    private data class ToolAuthoringEvidenceAuthority(
        val submission: StagingSubmissionEntity,
        val registry: ToolRegistryEntryEntity,
        val promotedEventId: String,
        val promotedEventHash: String
    )

    private data class RequiredToolExecution(
        val commitment: ToolOpportunityCommitment,
        val purpose: ToolExecutionPurpose
    )

    private companion object {
        val NEGATIVE_COMMIT_TYPES = setOf(
            CommitType.FALSIFIED_HYPOTHESIS,
            CommitType.STRUCTURED_DEAD_END
        )
        const val TOOL_ARTIFACT_PREFIX = "tool:"
    }
}
