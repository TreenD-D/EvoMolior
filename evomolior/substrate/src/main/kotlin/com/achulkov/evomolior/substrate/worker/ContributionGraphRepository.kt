@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.substrate.worker

import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.CaptureState
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.NodeCaptureDecision
import com.achulkov.evomolior.core.atlas.NodeCaptureDecisionType
import com.achulkov.evomolior.core.atlas.NodeEvidencePacket
import com.achulkov.evomolior.core.atlas.NodeExecutionState
import com.achulkov.evomolior.core.atlas.NodeGradeBundle
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.ToolExecutionPurpose
import com.achulkov.evomolior.core.atlas.ToolExecutionRecord
import com.achulkov.evomolior.core.atlas.ToolExecutionResult
import com.achulkov.evomolior.core.contract.ContributionAuthorityKind
import com.achulkov.evomolior.core.contract.ContributionAuthorityRef
import com.achulkov.evomolior.core.contract.ContributionEdge
import com.achulkov.evomolior.core.contract.ContributionPlane
import com.achulkov.evomolior.core.contract.ContributionRelation
import com.achulkov.evomolior.core.contract.ContributionCommit
import com.achulkov.evomolior.core.contract.CriticDecisionRecord
import com.achulkov.evomolior.core.contract.CriticOutcome
import com.achulkov.evomolior.core.contract.DownstreamUseKind
import com.achulkov.evomolior.core.contract.DownstreamUseRecord
import com.achulkov.evomolior.core.contract.IntegrationNodeRecord
import com.achulkov.evomolior.core.contract.FinalDeliverableReferenceRecord
import com.achulkov.evomolior.core.contract.ReceiverScoreContributionRecord
import com.achulkov.evomolior.core.contract.BenchmarkScoreContributionRecord
import com.achulkov.evomolior.core.contract.ControlInterventionRecord
import com.achulkov.evomolior.core.contract.ControlObservationAuthority
import com.achulkov.evomolior.core.contract.SummaryValidationRecord
import com.achulkov.evomolior.core.council.CouncilDeterministicIds
import com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
import com.achulkov.evomolior.substrate.db.entity.ContributionEdgeEntity
import com.achulkov.evomolior.substrate.db.entity.CriticDecisionEntity
import com.achulkov.evomolior.substrate.db.entity.DownstreamUseRecordEntity
import com.achulkov.evomolior.substrate.db.entity.IntegrationNodeRecordEntity
import com.achulkov.evomolior.substrate.db.entity.FinalDeliverableReferenceRecordEntity
import com.achulkov.evomolior.substrate.db.entity.ReceiverScoreContributionRecordEntity
import com.achulkov.evomolior.substrate.db.entity.BenchmarkScoreContributionRecordEntity
import com.achulkov.evomolior.substrate.db.entity.ControlInterventionRecordEntity
import com.achulkov.evomolior.substrate.db.entity.SummaryValidationRecordEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One current final-output source, with a node ID only for exact capture ancestry.
 *
 * @param nodeId Accepted Atlas node, or null for a non-node fallback authority.
 * @param authority Exact immutable source authority.
 */
data class FinalDeliverableSource(
    val nodeId: String?,
    val authority: ContributionAuthorityRef
)

private val ACCEPTED_CAPTURE_DECISION_TYPES = setOf(
    NodeCaptureDecisionType.CAPTURED,
    NodeCaptureDecisionType.RECAPTURED,
    NodeCaptureDecisionType.SHARED_VALIDATION_CREDIT
)

private val ACCEPTED_CAPTURE_STATES = setOf(
    CaptureState.CAPTURED,
    CaptureState.SHARED
)

/**
 * Validates and persists the typed contribution graph without copying authority payloads.
 *
 * Endpoint existence and hashes are enforced for authority kinds whose records are currently
 * available in the group database. Other kinds remain fail-closed until their repository
 * integration is added.
 */
class ContributionGraphRepository(
    private val database: EvoMoliorDatabase,
    private val atlasDatabase: EvoMoliorDatabase = database,
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }
) {
    private val dao = database.contributionGraphDao()

    /** Appends one hash-valid edge after verifying both endpoint authorities. */
    suspend fun append(edge: ContributionEdge): ContributionEdge {
        require(edge.hasValidHash()) { "Contribution edge hash does not match its content" }
        verifyAuthority(edge, edge.source)
        verifyAuthority(edge, edge.target)
        dao.insert(edge.toEntity())
        return edge
    }

    /**
     * Appends the agent-to-commit edge and every exact commit-to-staging edge discoverable from
     * [ContributionCommit.artifactIds].
     *
     * Tool-only and claim-only commits legitimately have no staging edge. Existing identical
     * edges are accepted idempotently so semantic contribution deduplication remains safe.
     */
    suspend fun recordContributionOrigin(commit: ContributionCommit): List<ContributionEdge> {
        val agentRef = ContributionAuthorityRef(
            kind = ContributionAuthorityKind.AGENT,
            authorityId = commit.agentId.value
        )
        val commitRef = ContributionAuthorityRef(
            kind = ContributionAuthorityKind.CONTRIBUTION_COMMIT,
            authorityId = commit.commitId,
            authorityHash = commit.dedupeHash
        )
        val authored = ContributionEdge(
            edgeId = CouncilDeterministicIds.create(
                "contribution-edge",
                commit.runId,
                commit.groupId.value,
                commit.agentId.value,
                commit.commitId,
                ContributionRelation.AUTHORED.name
            ),
            runId = commit.runId,
            groupId = commit.groupId,
            round = commit.round,
            plane = ContributionPlane.WORKER,
            relation = ContributionRelation.AUTHORED,
            source = agentRef,
            target = commitRef,
            occurredAt = commit.committedAt
        ).withComputedHash()
        val staged = commit.artifactIds.mapNotNull { artifactId ->
            val submission = database.stagingDao().getById(artifactId) ?: return@mapNotNull null
            require(
                submission.runId == commit.runId &&
                    submission.groupId == commit.groupId.value &&
                    submission.agentId == commit.agentId.value
            ) {
                "Contribution cannot claim another scope or agent's staged submission"
            }
            ContributionEdge(
                edgeId = CouncilDeterministicIds.create(
                    "contribution-edge",
                    commit.runId,
                    commit.groupId.value,
                    commit.commitId,
                    submission.id,
                    ContributionRelation.STAGED_AS.name
                ),
                runId = commit.runId,
                groupId = commit.groupId,
                round = commit.round,
                plane = ContributionPlane.WORKER,
                relation = ContributionRelation.STAGED_AS,
                source = commitRef,
                target = ContributionAuthorityRef(
                    kind = ContributionAuthorityKind.STAGING_SUBMISSION,
                    authorityId = submission.id,
                    authorityHash = AtlasHashing.hashText(submission.content)
                ),
                occurredAt = commit.committedAt
            ).withComputedHash()
        }
        val edges = listOf(authored) + staged
        edges.forEach { edge ->
            require(edge.hasValidHash()) { "Generated contribution origin edge is invalid" }
            verifyAuthority(edge, edge.source)
            verifyAuthority(edge, edge.target)
        }
        dao.insertAllIdempotent(edges.map { it.toEntity() })
        edges.forEach { expected ->
            val persisted = requireNotNull(dao.byId(expected.edgeId)) {
                "Contribution origin edge ${expected.edgeId} was not persisted"
            }
            require(AtlasHashing.constantTimeEquals(persisted.edgeHash, expected.edgeHash)) {
                "Contribution origin edge ID conflicts with different provenance"
            }
        }
        return edges
    }

    /**
     * Records explicit canonical citations declared by one immutable contribution commit.
     *
     * A citation is not inferred from a read. Its context hash binds the exact contribution
     * semantic hash, and every source must resolve through final Critic promotion authority.
     */
    suspend fun recordContributionCitations(
        commit: ContributionCommit
    ): List<DownstreamUseRecord> {
        val persistedCommit = requireNotNull(
            database.workerExecutionDao().contributionById(commit.commitId)
        ) {
            "Citation context requires persisted contribution ${commit.commitId}"
        }
        require(
            persistedCommit.runId == commit.runId &&
                persistedCommit.groupId == commit.groupId.value &&
                AtlasHashing.constantTimeEquals(persistedCommit.dedupeHash, commit.dedupeHash)
        ) {
            "Citation context does not match the persisted contribution"
        }
        return commit.citedArtifactIds.distinct().sorted().map { authorityId ->
        val source = requireNotNull(
            canonicalAuthority(commit.runId, commit.groupId.value, authorityId)
        ) {
            "Contribution citation requires immutable Critic authority for $authorityId"
        }
        val useId = CouncilDeterministicIds.create(
            "contribution-citation",
            commit.runId,
            commit.groupId.value,
            commit.commitId,
            authorityId
        )
        val record = DownstreamUseRecord(
            useId = useId,
            runId = commit.runId,
            groupId = commit.groupId,
            round = commit.round,
            agentId = commit.agentId.value,
            nodeId = commit.nodeId?.value,
            kind = DownstreamUseKind.CITATION,
            sourceAuthority = source,
            contextHash = commit.dedupeHash,
            occurredAt = commit.committedAt
        ).withComputedHash()
        downstreamUse(useId)?.let { existing ->
            require(existing == record) { "Contribution citation identity is immutable" }
            existing
        } ?: recordDownstreamUse(record)
        }
    }

    /** Appends exact accepted promotion and tool-execution lineage into one sealed packet. */
    suspend fun recordEvidenceLineage(packet: NodeEvidencePacket): List<ContributionEdge> {
        val packetRef = ContributionAuthorityRef(
            kind = ContributionAuthorityKind.NODE_EVIDENCE_PACKET,
            authorityId = packet.packetId,
            authorityHash = packet.packetHash
        )
        val promotionSources = (packet.acceptedArtifactIds + packet.acceptedClaimIds)
            .distinct()
            .map { submissionId ->
                val submission = requireNotNull(database.stagingDao().getById(submissionId)) {
                    "Evidence lineage references unknown staging submission $submissionId"
                }
                val decision = requireNotNull(
                    dao.criticDecisionForSubmission(
                        packet.runId,
                        packet.groupId.value,
                        submissionId
                    )
                ) {
                    "Evidence lineage requires immutable Critic authority for $submissionId"
                }
                require(submission.promotedArtifactRef == decision.resultAuthorityId) {
                    "Critic result authority does not match promoted staging authority"
                }
                ContributionAuthorityRef(
                    kind = ContributionAuthorityKind.valueOf(
                        requireNotNull(decision.resultKind) {
                            "Evidence Critic decision lacks result kind"
                        }
                    ),
                    authorityId = requireNotNull(decision.resultAuthorityId),
                    authorityHash = requireNotNull(decision.resultAuthorityHash)
                )
            }
        val toolSources = packet.toolRunIds.distinct().map { executionId ->
            val execution = requireNotNull(
                database.toolInfrastructureDao().execution(executionId)
            ) {
                "Evidence lineage references unknown tool execution $executionId"
            }
            ContributionAuthorityRef(
                kind = ContributionAuthorityKind.TOOL_EXECUTION,
                authorityId = execution.executionId,
                authorityHash = execution.recordHash
            )
        }
        val edges = (promotionSources + toolSources).map { source ->
            ContributionEdge(
                edgeId = CouncilDeterministicIds.create(
                    "contribution-edge",
                    packet.runId,
                    packet.groupId.value,
                    source.authorityId,
                    packet.packetId,
                    ContributionRelation.INCLUDED_IN_EVIDENCE.name
                ),
                runId = packet.runId,
                groupId = packet.groupId,
                round = packet.round,
                plane = ContributionPlane.GRADER,
                relation = ContributionRelation.INCLUDED_IN_EVIDENCE,
                source = source,
                target = packetRef,
                occurredAt = kotlin.time.Instant.fromEpochMilliseconds(packet.sealedAtEpochMillis)
            ).withComputedHash()
        }
        return appendIdempotently(edges)
    }

    /** Appends the exact evidence-to-grade edge for one evaluated bundle. */
    suspend fun recordGradeLineage(
        bundle: NodeGradeBundle,
        persistedAtEpochMillis: Long
    ): List<ContributionEdge> {
        val packetId = bundle.evidencePacketId ?: return emptyList()
        val packetHash = bundle.evidencePacketHash ?: return emptyList()
        val edge = ContributionEdge(
            edgeId = CouncilDeterministicIds.create(
                "contribution-edge",
                bundle.runId,
                bundle.groupId.value,
                packetId,
                bundle.bundleId,
                ContributionRelation.GRADED_AS.name
            ),
            runId = bundle.runId,
            groupId = bundle.groupId,
            round = bundle.round,
            plane = ContributionPlane.GRADER,
            relation = ContributionRelation.GRADED_AS,
            source = ContributionAuthorityRef(
                kind = ContributionAuthorityKind.NODE_EVIDENCE_PACKET,
                authorityId = packetId,
                authorityHash = packetHash
            ),
            target = ContributionAuthorityRef(
                kind = ContributionAuthorityKind.NODE_GRADE_BUNDLE,
                authorityId = bundle.bundleId,
                authorityHash = bundle.bundleHash
            ),
            occurredAt = kotlin.time.Instant.fromEpochMilliseconds(persistedAtEpochMillis)
        ).withComputedHash()
        return appendIdempotently(listOf(edge))
    }

    /** Appends one authoritative grade-to-capture edge in the owning worker group's graph. */
    suspend fun recordCaptureLineage(
        decision: NodeCaptureDecision,
        bundle: NodeGradeBundle,
        persistedAtEpochMillis: Long
    ): List<ContributionEdge> {
        require(bundle.bundleHash in decision.authoritativeBundleHashes) {
            "Only an authoritative grade bundle can authorize capture lineage"
        }
        val edge = ContributionEdge(
            edgeId = CouncilDeterministicIds.create(
                "contribution-edge",
                decision.runId,
                bundle.groupId.value,
                bundle.bundleId,
                decision.decisionId,
                ContributionRelation.AUTHORIZED_CAPTURE.name
            ),
            runId = decision.runId,
            groupId = bundle.groupId,
            round = decision.round,
            plane = ContributionPlane.GRADER,
            relation = ContributionRelation.AUTHORIZED_CAPTURE,
            source = ContributionAuthorityRef(
                kind = ContributionAuthorityKind.NODE_GRADE_BUNDLE,
                authorityId = bundle.bundleId,
                authorityHash = bundle.bundleHash
            ),
            target = ContributionAuthorityRef(
                kind = ContributionAuthorityKind.NODE_CAPTURE_DECISION,
                authorityId = decision.decisionId,
                authorityHash = decision.decisionHash
            ),
            occurredAt = kotlin.time.Instant.fromEpochMilliseconds(persistedAtEpochMillis)
        ).withComputedHash()
        return appendIdempotently(listOf(edge))
    }

    /** Atomically records one immutable downstream read/citation and its canonical lineage. */
    suspend fun recordDownstreamUse(record: DownstreamUseRecord): DownstreamUseRecord {
        require(record.hasValidHash()) {
            "Downstream-use record hash does not match its content"
        }
        val targetKind = when (record.kind) {
            DownstreamUseKind.READ -> ContributionAuthorityKind.DOWNSTREAM_READ
            DownstreamUseKind.CITATION -> ContributionAuthorityKind.CITATION
        }
        val target = ContributionAuthorityRef(
            kind = targetKind,
            authorityId = record.useId,
            authorityHash = record.recordHash
        )
        val relation = when (record.kind) {
            DownstreamUseKind.READ -> ContributionRelation.READ_DOWNSTREAM
            DownstreamUseKind.CITATION -> ContributionRelation.CITED_DOWNSTREAM
        }
        val edge = ContributionEdge(
            edgeId = CouncilDeterministicIds.create(
                "contribution-edge",
                record.runId,
                record.groupId.value,
                record.sourceAuthority.authorityId,
                record.useId,
                relation.name
            ),
            runId = record.runId,
            groupId = record.groupId,
            round = record.round,
            plane = ContributionPlane.WORKER,
            relation = relation,
            source = record.sourceAuthority,
            target = target,
            occurredAt = record.occurredAt
        ).withComputedHash()
        verifyCanonicalAuthority(edge, record.sourceAuthority)
        dao.insertDownstreamUseWithEdge(record.toEntity(), edge.toEntity())
        return record
    }

    /** Returns one exact immutable downstream read/citation authority. */
    suspend fun downstreamUse(useId: String): DownstreamUseRecord? =
        dao.downstreamUseById(useId)?.toContract()

    /** Atomically records one captured integration node and all exact accepted source edges. */
    suspend fun recordIntegrationNode(record: IntegrationNodeRecord): IntegrationNodeRecord {
        require(record.hasValidHash()) {
            "Integration-node record hash does not match its content"
        }
        dao.integrationNodeById(record.integrationId)?.let { entity ->
            val existing = entity.toContract()
            require(existing == record) { "Integration-node identity is immutable" }
            return existing
        }
        val target = ContributionAuthorityRef(
            kind = ContributionAuthorityKind.INTEGRATION_NODE,
            authorityId = record.integrationId,
            authorityHash = record.recordHash
        )
        val sources = (record.sourceAuthorities + record.captureDecision)
            .distinct()
            .sortedWith(compareBy({ it.kind.name }, ContributionAuthorityRef::authorityId))
        val edges = sources.map { source ->
            ContributionEdge(
                edgeId = CouncilDeterministicIds.create(
                    "contribution-edge",
                    record.runId,
                    record.groupId.value,
                    source.kind.name,
                    source.authorityId,
                    record.integrationId,
                    ContributionRelation.INTEGRATED_INTO_NODE.name
                ),
                runId = record.runId,
                groupId = record.groupId,
                round = record.round,
                plane = ContributionPlane.WORKER,
                relation = ContributionRelation.INTEGRATED_INTO_NODE,
                source = source,
                target = target,
                occurredAt = record.integratedAt
            ).withComputedHash()
        }
        edges.forEach { edge ->
            require(edge.hasValidHash()) { "Generated integration edge is invalid" }
            verifyAuthority(edge, edge.source)
        }
        dao.insertIntegrationNodeWithEdges(
            record = record.toEntity(),
            edges = edges.map { it.toEntity() }
        )
        return record
    }

    /** Returns one exact immutable integration-node authority. */
    suspend fun integrationNode(integrationId: String): IntegrationNodeRecord? =
        dao.integrationNodeById(integrationId)?.toContract()

    /** Returns all immutable captured integration authorities for one group run. */
    suspend fun integrationNodesForRun(
        runId: String,
        groupId: String
    ): List<IntegrationNodeRecord> =
        dao.integrationNodesForRun(runId, groupId).map { it.toContract() }

    /**
     * Resolves exact captured authorities admitted as final-output sources for one group.
     *
     * Captured integration nodes are preferred. Other captured nodes remain available through
     * their immutable capture decisions so final lineage does not depend on summary prose.
     */
    suspend fun finalDeliverableSources(
        runId: String,
        groupId: String
    ): List<FinalDeliverableSource> {
        val currentCaptures = currentAcceptedCaptureDecisions(runId, groupId)
        if (currentCaptures.isNotEmpty()) {
            val currentIntegrations = integrationNodesForRun(runId, groupId)
                .filter { integration ->
                    val capture = currentCaptures[integration.nodeId]
                    capture != null && integrationMatchesCapture(integration, capture)
                }
                .groupBy(IntegrationNodeRecord::nodeId)
                .mapValues { (_, integrations) -> integrations.last() }
            return currentCaptures.toSortedMap().map { (nodeId, capture) ->
                val integration = currentIntegrations[nodeId]
                if (integration != null) {
                    FinalDeliverableSource(
                        nodeId = nodeId,
                        authority = ContributionAuthorityRef(
                            kind = ContributionAuthorityKind.INTEGRATION_NODE,
                            authorityId = integration.integrationId,
                            authorityHash = integration.recordHash
                        )
                    )
                } else {
                    FinalDeliverableSource(
                        nodeId = nodeId,
                        authority = capture.toAuthorityRef()
                    )
                }
            }
        }
        return dao.promotedCriticDecisionsForRun(runId, groupId)
            .map { decision ->
                FinalDeliverableSource(
                    nodeId = null,
                    authority = ContributionAuthorityRef(
                        kind = ContributionAuthorityKind.valueOf(requireNotNull(decision.resultKind)),
                        authorityId = requireNotNull(decision.resultAuthorityId),
                        authorityHash = requireNotNull(decision.resultAuthorityHash)
                    )
                )
            }
    }

    /**
     * Verifies that [source] is the exact current accepted capture ancestry for [nodeId].
     *
     * This read is intentionally repeated by metric replay so a forged persisted `nodeId` cannot
     * become trusted merely because a final-reference row exists.
     */
    suspend fun isAcceptedFinalNodeSource(
        runId: String,
        groupId: String,
        nodeId: String,
        source: ContributionAuthorityRef
    ): Boolean {
        val capture = currentAcceptedCaptureDecisions(runId, groupId)[nodeId] ?: return false
        return when (source.kind) {
            ContributionAuthorityKind.NODE_CAPTURE_DECISION ->
                source == capture.toAuthorityRef()

            ContributionAuthorityKind.INTEGRATION_NODE -> {
                val integration = dao.integrationNodeById(source.authorityId)
                    ?.toContract()
                    ?: return false
                val sourceHash = source.authorityHash
                sourceHash != null &&
                    AtlasHashing.constantTimeEquals(
                        sourceHash,
                        integration.recordHash
                    ) &&
                    integration.nodeId == nodeId &&
                    integration.groupId.value == groupId &&
                    integrationMatchesCapture(integration, capture)
            }

            else -> false
        }
    }

    /**
     * Returns exact submission and canonical-result IDs admitted by final Critic promotion.
     *
     * Reward-use lineage may name either the staged submission or its promoted substrate
     * authority, so both identities remain part of the accepted-artifact set.
     */
    suspend fun acceptedArtifactAuthorityIdsForRun(
        runId: String,
        groupId: String
    ): Set<String> = dao.promotedCriticDecisionsForRun(runId, groupId)
        .flatMap { decision ->
            listOfNotNull(decision.submissionId, decision.resultAuthorityId)
        }
        .toSet()

    /** Atomically records one exact admitted source reference in a final output file. */
    suspend fun recordFinalDeliverableReference(
        record: FinalDeliverableReferenceRecord
    ): FinalDeliverableReferenceRecord {
        require(record.hasValidHash()) {
            "Final-deliverable reference hash does not match its content"
        }
        val writerSubmission = requireNotNull(
            database.stagingDao().getById(record.writerSubmissionId)
        ) { "Final-deliverable writer submission is unknown" }
        require(
            writerSubmission.runId == record.runId &&
                writerSubmission.groupId == record.groupId.value &&
                writerSubmission.agentId == record.writerAgentId
        ) { "Final-deliverable writer submission belongs to a different scope or agent" }
        record.nodeId?.let { nodeId ->
            require(
                isAcceptedFinalNodeSource(
                    runId = record.runId,
                    groupId = record.groupId.value,
                    nodeId = nodeId,
                    source = record.sourceAuthority
                )
            ) {
                "Final-deliverable node source is not the exact current accepted capture ancestry"
            }
        }
        dao.finalDeliverableReferenceById(record.referenceId)?.let { entity ->
            val existing = entity.toContract()
            require(existing == record) { "Final-deliverable reference identity is immutable" }
            return existing
        }
        val edge = ContributionEdge(
            edgeId = CouncilDeterministicIds.create(
                "contribution-edge",
                record.runId,
                record.groupId.value,
                record.sourceAuthority.kind.name,
                record.sourceAuthority.authorityId,
                record.referenceId,
                ContributionRelation.REFERENCED_BY_FINAL_DELIVERABLE.name
            ),
            runId = record.runId,
            groupId = record.groupId,
            round = record.round,
            plane = ContributionPlane.SUMMARY,
            relation = ContributionRelation.REFERENCED_BY_FINAL_DELIVERABLE,
            source = record.sourceAuthority,
            target = ContributionAuthorityRef(
                kind = ContributionAuthorityKind.FINAL_DELIVERABLE_REFERENCE,
                authorityId = record.referenceId,
                authorityHash = record.recordHash
            ),
            occurredAt = record.referencedAt
        ).withComputedHash()
        verifyAuthority(edge, edge.source)
        dao.insertFinalDeliverableReferenceWithEdge(record.toEntity(), edge.toEntity())
        return record
    }

    private suspend fun currentAcceptedCaptureDecisions(
        runId: String,
        groupId: String
    ): Map<String, NodeCaptureDecision> {
        val atlasEntity = atlasDatabase.atlasDao().latestVersionForRun(runId)
            ?: return emptyMap()
        val atlas = json.decodeFromString(NodeAtlas.serializer(), atlasEntity.sealedAtlasJson)
        val states = atlasDatabase.atlasDao().currentStates(atlas.atlasId)
            .associate { entity ->
                NodeId(entity.nodeId) to json.decodeFromString(
                    NodeExecutionState.serializer(),
                    entity.stateJson
                )
            }
        return atlasDatabase.nodeCaptureDao().captureDecisionsForRun(runId)
            .map { entity ->
                json.decodeFromString(NodeCaptureDecision.serializer(), entity.contentJson)
            }
            .groupBy { it.nodeId.value }
            .mapNotNull { (nodeId, decisions) ->
                val node = atlas.nodes[NodeId(nodeId)] ?: return@mapNotNull null
                val state = states[NodeId(nodeId)] ?: return@mapNotNull null
                val current = decisions.asReversed().firstOrNull { decision ->
                    decision.hasValidHash() &&
                        decision.nodePayloadHash == node.payloadHash &&
                        decision.decisionType in ACCEPTED_CAPTURE_DECISION_TYPES &&
                        decision.resultingCaptureState == state.captureState &&
                        decision.resultingCaptureState in ACCEPTED_CAPTURE_STATES &&
                        when (state.captureState) {
                            CaptureState.CAPTURED ->
                                state.ownerGroupId != null &&
                                    state.ownerGroupId in decision.resultingOwnerGroupIds

                            CaptureState.SHARED ->
                                decision.resultingOwnerGroupIds.isNotEmpty()

                            else -> false
                        }
                } ?: return@mapNotNull null
                if (current.resultingOwnerGroupIds.none { it.value == groupId }) {
                    return@mapNotNull null
                }
                if (
                    state.captureState == CaptureState.CAPTURED &&
                    state.ownerGroupId?.value != groupId
                ) {
                    return@mapNotNull null
                }
                nodeId to current
            }
            .toMap()
    }

    private fun integrationMatchesCapture(
        integration: IntegrationNodeRecord,
        capture: NodeCaptureDecision
    ): Boolean =
        integration.hasValidHash() &&
            integration.nodeId == capture.nodeId.value &&
            integration.atlasVersion == capture.atlasVersion &&
            integration.atlasHash == capture.atlasHash &&
            integration.nodePayloadHash == capture.nodePayloadHash &&
            integration.captureDecision == capture.toAuthorityRef()

    private fun NodeCaptureDecision.toAuthorityRef() = ContributionAuthorityRef(
        kind = ContributionAuthorityKind.NODE_CAPTURE_DECISION,
        authorityId = decisionId,
        authorityHash = decisionHash
    )

    /** Returns one immutable final-output reference authority. */
    suspend fun finalDeliverableReference(
        referenceId: String
    ): FinalDeliverableReferenceRecord? =
        dao.finalDeliverableReferenceById(referenceId)?.toContract()

    /** Returns all immutable final-output references for one group run. */
    suspend fun finalDeliverableReferencesForRun(
        runId: String,
        groupId: String
    ): List<FinalDeliverableReferenceRecord> =
        dao.finalDeliverableReferencesForRun(runId, groupId).map { it.toContract() }

    /** Atomically records one exact receiver-score component over a final output reference. */
    suspend fun recordReceiverScoreContribution(
        record: ReceiverScoreContributionRecord
    ): ReceiverScoreContributionRecord {
        require(record.hasValidHash()) {
            "Receiver-score contribution hash does not match its content"
        }
        dao.receiverScoreContributionById(record.contributionId)?.let { entity ->
            val existing = entity.toContract()
            require(existing == record) { "Receiver-score contribution identity is immutable" }
            return existing
        }
        val edge = scoreEdge(
            runId = record.runId,
            groupId = record.groupId,
            round = record.round,
            finalReference = record.finalReference,
            contributionId = record.contributionId,
            contributionHash = record.recordHash,
            contributionKind = ContributionAuthorityKind.RECEIVER_SCORE_CONTRIBUTION,
            relation = ContributionRelation.CONTRIBUTED_TO_RECEIVER_SCORE,
            occurredAt = record.scoredAt
        )
        verifyAuthority(edge, edge.source)
        dao.insertReceiverScoreContributionWithEdge(record.toEntity(), edge.toEntity())
        return record
    }

    /** Returns one immutable receiver-score contribution authority. */
    suspend fun receiverScoreContribution(
        contributionId: String
    ): ReceiverScoreContributionRecord? =
        dao.receiverScoreContributionById(contributionId)?.toContract()

    /** Atomically records one exact benchmark-score component over a final output reference. */
    suspend fun recordBenchmarkScoreContribution(
        record: BenchmarkScoreContributionRecord
    ): BenchmarkScoreContributionRecord {
        require(record.hasValidHash()) {
            "Benchmark-score contribution hash does not match its content"
        }
        dao.benchmarkScoreContributionById(record.contributionId)?.let { entity ->
            val existing = entity.toContract()
            require(existing == record) { "Benchmark-score contribution identity is immutable" }
            return existing
        }
        val edge = scoreEdge(
            runId = record.runId,
            groupId = record.groupId,
            round = record.round,
            finalReference = record.finalReference,
            contributionId = record.contributionId,
            contributionHash = record.recordHash,
            contributionKind = ContributionAuthorityKind.BENCHMARK_SCORE_CONTRIBUTION,
            relation = ContributionRelation.CONTRIBUTED_TO_BENCHMARK_SCORE,
            occurredAt = record.scoredAt
        )
        verifyAuthority(edge, edge.source)
        dao.insertBenchmarkScoreContributionWithEdge(record.toEntity(), edge.toEntity())
        return record
    }

    /** Returns one immutable benchmark-score contribution authority. */
    suspend fun benchmarkScoreContribution(
        contributionId: String
    ): BenchmarkScoreContributionRecord? =
        dao.benchmarkScoreContributionById(contributionId)?.toContract()

    /** Persists one exact control-plane intervention after verifying its observed events. */
    suspend fun recordControlIntervention(
        record: ControlInterventionRecord
    ): ControlInterventionRecord {
        require(record.hasValidHash()) {
            "Control-intervention hash does not match its content"
        }
        dao.controlInterventionById(record.interventionId)?.let { entity ->
            val existing = entity.toContract()
            require(existing == record) { "Control-intervention identity is immutable" }
            return existing
        }
        record.observations.forEach { observation ->
            val event = requireNotNull(database.eventLogDao().getById(observation.eventId)) {
                "Control intervention references unknown event ${observation.eventId}"
            }
            require(event.runId == record.runId && event.groupId == record.groupId.value) {
                "Control observation belongs to a different run or group"
            }
            require(
                AtlasHashing.constantTimeEquals(
                    observation.eventHash,
                    hashEventAuthority(event)
                )
            ) { "Control observation hash does not match ${observation.eventId}" }
        }
        record.falsePositiveAuthorities.forEach { observation ->
            val event = requireNotNull(database.eventLogDao().getById(observation.eventId)) {
                "False-positive authority references unknown event ${observation.eventId}"
            }
            require(event.runId == record.runId && event.groupId == record.groupId.value) {
                "False-positive observation belongs to a different run or group"
            }
            require(
                AtlasHashing.constantTimeEquals(
                    observation.eventHash,
                    hashEventAuthority(event)
                )
            ) { "False-positive observation hash does not match ${observation.eventId}" }
        }
        record.avoidedLossAuthorities.forEach { authority ->
            verifyControlOutcomeAuthority(record, authority)
        }
        record.costAuthorities.forEach { authority ->
            val entry = requireNotNull(database.costLedgerDao().byId(authority.authorityId)) {
                "Control intervention references unknown cost entry ${authority.authorityId}"
            }
            require(entry.runId == record.runId && entry.groupId == record.groupId.value) {
                "Control cost authority belongs to a different run or group"
            }
            val authorityHash = authority.authorityHash
            require(
                authorityHash != null &&
                    AtlasHashing.constantTimeEquals(authorityHash, entry.entryHash)
            ) { "Control cost authority hash does not match" }
        }
        dao.insertControlIntervention(record.toEntity())
        return record
    }

    /** Returns one immutable control intervention. */
    suspend fun controlIntervention(interventionId: String): ControlInterventionRecord? =
        dao.controlInterventionById(interventionId)?.toContract()

    /** Returns control interventions through one round without crossing planes. */
    suspend fun controlInterventionsThroughRound(
        runId: String,
        groupId: String,
        throughRound: Int
    ): List<ControlInterventionRecord> =
        dao.controlInterventionsThroughRound(runId, groupId, throughRound).map { it.toContract() }

    /** Builds the exact immutable observation reference for one persisted event. */
    suspend fun controlObservation(eventId: String): ControlObservationAuthority {
        val event = requireNotNull(database.eventLogDao().getById(eventId)) {
            "Unknown control observation event $eventId"
        }
        return ControlObservationAuthority(event.id, hashEventAuthority(event))
    }

    /** Persists one exact final-summary validation after verifying every output reference. */
    suspend fun recordSummaryValidation(
        record: SummaryValidationRecord
    ): SummaryValidationRecord {
        require(record.hasValidHash()) {
            "Summary-validation hash does not match its content"
        }
        dao.summaryValidationById(record.validationId)?.let { entity ->
            val existing = entity.toContract()
            require(existing == record) { "Summary-validation identity is immutable" }
            return existing
        }
        record.finalReferences.forEach { authority ->
            val reference = requireNotNull(
                dao.finalDeliverableReferenceById(authority.authorityId)
            ) { "Summary validation references unknown final output ${authority.authorityId}" }
            require(reference.runId == record.runId && reference.groupId == record.groupId.value) {
                "Summary final reference belongs to a different run or group"
            }
            val authorityHash = authority.authorityHash
            require(
                authorityHash != null &&
                    AtlasHashing.constantTimeEquals(authorityHash, reference.recordHash)
            ) { "Summary final-reference hash does not match" }
        }
        dao.insertSummaryValidation(record.toEntity())
        return record
    }

    /** Returns one immutable final-summary validation. */
    suspend fun summaryValidation(validationId: String): SummaryValidationRecord? =
        dao.summaryValidationById(validationId)?.toContract()

    /** Returns final-summary validations through one round. */
    suspend fun summaryValidationsThroughRound(
        runId: String,
        groupId: String,
        throughRound: Int
    ): List<SummaryValidationRecord> =
        dao.summaryValidationsThroughRound(runId, groupId, throughRound).map { it.toContract() }

    private suspend fun verifyControlOutcomeAuthority(
        record: ControlInterventionRecord,
        authority: ContributionAuthorityRef
    ) {
        val persistedHash = when (authority.kind) {
            ContributionAuthorityKind.CANONICAL_ARTIFACT,
            ContributionAuthorityKind.STRUCTURED_DEAD_END ->
                canonicalAuthority(record.runId, record.groupId.value, authority.authorityId)
                    ?.takeIf { it.kind == authority.kind }
                    ?.authorityHash
            ContributionAuthorityKind.TOOL_EXECUTION ->
                database.toolInfrastructureDao().execution(authority.authorityId)?.recordHash
            ContributionAuthorityKind.INTEGRATION_NODE ->
                dao.integrationNodeById(authority.authorityId)?.recordHash
            ContributionAuthorityKind.NODE_CAPTURE_DECISION ->
                atlasDatabase.nodeCaptureDao().captureDecisionById(authority.authorityId)
                    ?.decisionHash
            ContributionAuthorityKind.FINAL_DELIVERABLE_REFERENCE ->
                dao.finalDeliverableReferenceById(authority.authorityId)?.recordHash
            else -> null
        }
        val authorityHash = authority.authorityHash
        require(
            persistedHash != null && authorityHash != null &&
                AtlasHashing.constantTimeEquals(authorityHash, persistedHash)
        ) {
            "Unknown or mismatched control outcome authority " +
                "${authority.kind.name}:${authority.authorityId}"
        }
    }

    private fun hashEventAuthority(
        event: com.achulkov.evomolior.substrate.db.entity.EventLogEntryEntity
    ): String = AtlasHashing.hashText(
        json.encodeToString(
            EventAuthorityPayload.serializer(),
            EventAuthorityPayload(
                id = event.id,
                timestampMs = event.timestampMs,
                eventType = event.eventType,
                agentId = event.agentId,
                groupId = event.groupId,
                runId = event.runId,
                generation = event.generation,
                payloadJson = event.payloadJson
            )
        )
    )

    private fun scoreEdge(
        runId: String,
        groupId: com.achulkov.evomolior.core.atlas.AtlasGroupId,
        round: Int,
        finalReference: ContributionAuthorityRef,
        contributionId: String,
        contributionHash: String,
        contributionKind: ContributionAuthorityKind,
        relation: ContributionRelation,
        occurredAt: kotlin.time.Instant
    ): ContributionEdge = ContributionEdge(
        edgeId = CouncilDeterministicIds.create(
            "contribution-edge",
            runId,
            groupId.value,
            finalReference.authorityId,
            contributionId,
            relation.name
        ),
        runId = runId,
        groupId = groupId,
        round = round,
        plane = ContributionPlane.RECEIVER,
        relation = relation,
        source = finalReference,
        target = ContributionAuthorityRef(
            kind = contributionKind,
            authorityId = contributionId,
            authorityHash = contributionHash
        ),
        occurredAt = occurredAt
    ).withComputedHash()

    /** Resolves exact immutable Critic authority for one promoted canonical artifact ID. */
    suspend fun canonicalAuthority(
        runId: String,
        groupId: String,
        authorityId: String
    ): ContributionAuthorityRef? =
        dao.criticDecisionForResult(runId, groupId, authorityId)?.let { decision ->
            val kind = decision.resultKind?.let(ContributionAuthorityKind::valueOf)
                ?: return@let null
            val hash = decision.resultAuthorityHash ?: return@let null
            ContributionAuthorityRef(
                kind = kind,
                authorityId = authorityId,
                authorityHash = hash
            )
        }

    /**
     * Appends promoted-tool-to-execution lineage for one exact successful downstream use.
     *
     * The source is resolved through immutable Critic promotion authority, not mutable registry
     * status. Evidence-bound reuse remains a separate later authority.
     */
    suspend fun recordDownstreamToolExecution(
        execution: ToolExecutionRecord
    ): ContributionEdge {
        require(execution.hasValidHash()) { "Tool execution record hash is invalid" }
        require(
            execution.purpose == ToolExecutionPurpose.AGENT_USE &&
                execution.result == ToolExecutionResult.SUCCEEDED
        ) {
            "Downstream tool lineage requires a successful agent-use execution"
        }
        val toolAuthorityId = "tool:${execution.toolVersionId.value}"
        val source = requireNotNull(
            canonicalAuthority(
                runId = execution.runId,
                groupId = execution.groupId.value,
                authorityId = toolAuthorityId
            )
        ) {
            "Downstream tool execution requires immutable Critic promotion authority"
        }
        require(source.kind == ContributionAuthorityKind.CANONICAL_ARTIFACT) {
            "Promoted tool execution requires canonical artifact authority"
        }
        val edge = ContributionEdge(
            edgeId = CouncilDeterministicIds.create(
                "contribution-edge",
                execution.runId,
                execution.groupId.value,
                toolAuthorityId,
                execution.executionId,
                ContributionRelation.EXECUTED_DOWNSTREAM.name
            ),
            runId = execution.runId,
            groupId = execution.groupId,
            round = execution.round,
            plane = ContributionPlane.WORKER,
            relation = ContributionRelation.EXECUTED_DOWNSTREAM,
            source = source,
            target = ContributionAuthorityRef(
                kind = ContributionAuthorityKind.TOOL_EXECUTION,
                authorityId = execution.executionId,
                authorityHash = execution.recordHash
            ),
            occurredAt = execution.completedAt
        ).withComputedHash()
        return appendIdempotently(listOf(edge)).single()
    }

    /**
     * Atomically appends one immutable final Critic decision and its staging/result lineage.
     */
    suspend fun recordCriticDecision(decision: CriticDecisionRecord): CriticDecisionRecord {
        require(decision.hasValidHash()) { "Critic decision hash does not match its content" }
        val submissionRef = ContributionAuthorityRef(
            kind = ContributionAuthorityKind.STAGING_SUBMISSION,
            authorityId = decision.submissionId,
            authorityHash = decision.submissionContentHash
        )
        val decisionRef = ContributionAuthorityRef(
            kind = ContributionAuthorityKind.CRITIC_DECISION,
            authorityId = decision.decisionId,
            authorityHash = decision.decisionHash
        )
        val reviewedEdge = ContributionEdge(
            edgeId = CouncilDeterministicIds.create(
                "contribution-edge",
                decision.runId,
                decision.groupId.value,
                decision.submissionId,
                decision.decisionId,
                ContributionRelation.REVIEWED_AS.name
            ),
            runId = decision.runId,
            groupId = decision.groupId,
            round = decision.round,
            plane = ContributionPlane.REVIEW,
            relation = ContributionRelation.REVIEWED_AS,
            source = submissionRef,
            target = decisionRef,
            occurredAt = decision.decidedAt
        ).withComputedHash()
        val resultEdge = decision.resultAuthority?.let { result ->
            ContributionEdge(
                edgeId = CouncilDeterministicIds.create(
                    "contribution-edge",
                    decision.runId,
                    decision.groupId.value,
                    decision.decisionId,
                    result.authorityId,
                    if (result.kind == ContributionAuthorityKind.STRUCTURED_DEAD_END) {
                        ContributionRelation.ACCEPTED_AS_STRUCTURED_DEAD_END.name
                    } else {
                        ContributionRelation.PROMOTED_TO.name
                    }
                ),
                runId = decision.runId,
                groupId = decision.groupId,
                round = decision.round,
                plane = ContributionPlane.REVIEW,
                relation = if (result.kind == ContributionAuthorityKind.STRUCTURED_DEAD_END) {
                    ContributionRelation.ACCEPTED_AS_STRUCTURED_DEAD_END
                } else {
                    ContributionRelation.PROMOTED_TO
                },
                source = decisionRef,
                target = result,
                occurredAt = decision.decidedAt
            ).withComputedHash()
        }
        verifyAuthority(reviewedEdge, submissionRef)
        resultEdge?.let { verifyAuthority(it, it.target) }
        dao.insertCriticDecisionWithEdges(
            decision = decision.toEntity(),
            edges = listOfNotNull(reviewedEdge, resultEdge).map { it.toEntity() }
        )
        return decision
    }

    /** Returns one exact immutable Critic decision. */
    suspend fun criticDecision(decisionId: String): CriticDecisionRecord? =
        dao.criticDecisionById(decisionId)?.toContract()

    /** Returns final Critic decisions for one exact group round. */
    suspend fun criticDecisionsForRound(
        runId: String,
        groupId: String,
        round: Int
    ): List<CriticDecisionRecord> =
        dao.criticDecisionsForRound(runId, groupId, round).map { it.toContract() }

    /** Returns one exact node-grade authority visible to graph derivation. */
    suspend fun nodeGradeBundle(bundleId: String): NodeGradeBundle? =
        atlasDatabase.nodeCaptureDao().gradeBundleById(bundleId)?.let {
            json.decodeFromString(NodeGradeBundle.serializer(), it.contentJson)
        }

    /** Returns one exact edge by immutable ID. */
    suspend fun byId(edgeId: String): ContributionEdge? =
        dao.byId(edgeId)?.toContract()

    /** Returns the complete ordered contribution graph for one group run. */
    suspend fun forRun(runId: String, groupId: String): List<ContributionEdge> =
        dao.forRun(runId, groupId).map { it.toContract() }

    /** Returns ordered outgoing lineage from one authority. */
    suspend fun fromAuthority(
        runId: String,
        groupId: String,
        authority: ContributionAuthorityRef
    ): List<ContributionEdge> =
        dao.fromAuthority(runId, groupId, authority.kind.name, authority.authorityId)
            .map { it.toContract() }

    /** Returns ordered incoming lineage to one authority. */
    suspend fun toAuthority(
        runId: String,
        groupId: String,
        authority: ContributionAuthorityRef
    ): List<ContributionEdge> =
        dao.toAuthority(runId, groupId, authority.kind.name, authority.authorityId)
            .map { it.toContract() }

    private suspend fun verifyAuthority(
        edge: ContributionEdge,
        authority: ContributionAuthorityRef
    ) {
        when (authority.kind) {
            ContributionAuthorityKind.AGENT -> {
                require(authority.authorityHash == null) {
                    "Agent authority uses stable identity rather than a content hash"
                }
            }

            ContributionAuthorityKind.CONTRIBUTION_COMMIT -> {
                val commit = requireNotNull(
                    database.workerExecutionDao().contributionById(authority.authorityId)
                ) {
                    "Unknown contribution commit ${authority.authorityId}"
                }
                require(commit.runId == edge.runId && commit.groupId == edge.groupId.value) {
                    "Contribution commit belongs to a different run or group"
                }
                val authorityHash = authority.authorityHash
                require(
                    authorityHash != null &&
                        AtlasHashing.constantTimeEquals(authorityHash, commit.dedupeHash)
                ) {
                    "Contribution authority hash must match the persisted semantic hash"
                }
            }

            ContributionAuthorityKind.STAGING_SUBMISSION -> {
                val submission = requireNotNull(database.stagingDao().getById(authority.authorityId)) {
                    "Unknown staging submission ${authority.authorityId}"
                }
                require(submission.runId == edge.runId && submission.groupId == edge.groupId.value) {
                    "Staging submission belongs to a different run or group"
                }
                val contentHash = AtlasHashing.hashText(submission.content)
                val authorityHash = authority.authorityHash
                require(
                    authorityHash != null &&
                        AtlasHashing.constantTimeEquals(authorityHash, contentHash)
                ) {
                    "Staging authority hash must match the exact persisted content"
                }
            }

            ContributionAuthorityKind.CRITIC_DECISION -> {
                val decision = requireNotNull(dao.criticDecisionById(authority.authorityId)) {
                    "Unknown Critic decision ${authority.authorityId}"
                }
                require(decision.runId == edge.runId && decision.groupId == edge.groupId.value) {
                    "Critic decision belongs to a different run or group"
                }
                val authorityHash = authority.authorityHash
                require(
                    authorityHash != null &&
                        AtlasHashing.constantTimeEquals(authorityHash, decision.decisionHash)
                ) {
                    "Critic decision authority hash does not match"
                }
            }

            ContributionAuthorityKind.CANONICAL_ARTIFACT,
            ContributionAuthorityKind.STRUCTURED_DEAD_END ->
                verifyCanonicalAuthority(edge, authority)

            ContributionAuthorityKind.TOOL_EXECUTION -> {
                val execution = requireNotNull(
                    database.toolInfrastructureDao().execution(authority.authorityId)
                ) {
                    "Unknown tool execution ${authority.authorityId}"
                }
                require(execution.runId == edge.runId && execution.groupId == edge.groupId.value) {
                    "Tool execution belongs to a different run or group"
                }
                val authorityHash = authority.authorityHash
                require(
                    authorityHash != null &&
                        AtlasHashing.constantTimeEquals(
                            authorityHash,
                            execution.recordHash
                        )
                ) {
                    "Tool execution authority hash does not match"
                }
            }

            ContributionAuthorityKind.NODE_EVIDENCE_PACKET -> {
                val packet = requireNotNull(
                    atlasDatabase.nodeCaptureDao().evidencePacketById(authority.authorityId)
                ) {
                    "Unknown node evidence packet ${authority.authorityId}"
                }
                require(packet.runId == edge.runId && packet.groupId == edge.groupId.value) {
                    "Node evidence packet belongs to a different run or group"
                }
                val authorityHash = authority.authorityHash
                require(
                    authorityHash != null &&
                        AtlasHashing.constantTimeEquals(authorityHash, packet.packetHash)
                ) {
                    "Node evidence packet authority hash does not match"
                }
            }

            ContributionAuthorityKind.NODE_GRADE_BUNDLE -> {
                val bundle = requireNotNull(
                    atlasDatabase.nodeCaptureDao().gradeBundleById(authority.authorityId)
                ) {
                    "Unknown node grade bundle ${authority.authorityId}"
                }
                require(bundle.runId == edge.runId && bundle.groupId == edge.groupId.value) {
                    "Node grade bundle belongs to a different run or group"
                }
                val authorityHash = authority.authorityHash
                require(
                    authorityHash != null &&
                        AtlasHashing.constantTimeEquals(authorityHash, bundle.bundleHash)
                ) {
                    "Node grade bundle authority hash does not match"
                }
            }

            ContributionAuthorityKind.NODE_CAPTURE_DECISION -> {
                val decision = requireNotNull(
                    atlasDatabase.nodeCaptureDao().captureDecisionById(authority.authorityId)
                ) {
                    "Unknown node capture decision ${authority.authorityId}"
                }
                require(decision.runId == edge.runId) {
                    "Node capture decision belongs to a different run"
                }
                val authorityHash = authority.authorityHash
                require(
                    authorityHash != null &&
                        AtlasHashing.constantTimeEquals(authorityHash, decision.decisionHash)
                ) {
                    "Node capture decision authority hash does not match"
                }
            }

            ContributionAuthorityKind.DOWNSTREAM_READ,
            ContributionAuthorityKind.CITATION -> {
                val record = requireNotNull(
                    dao.downstreamUseById(authority.authorityId)
                ) {
                    "Unknown downstream-use authority ${authority.authorityId}"
                }
                require(record.runId == edge.runId && record.groupId == edge.groupId.value) {
                    "Downstream-use authority belongs to a different run or group"
                }
                val expectedKind = if (
                    record.kind == DownstreamUseKind.READ.name
                ) {
                    ContributionAuthorityKind.DOWNSTREAM_READ
                } else {
                    ContributionAuthorityKind.CITATION
                }
                require(authority.kind == expectedKind) {
                    "Downstream-use authority kind does not match persisted authority"
                }
                val authorityHash = authority.authorityHash
                require(
                    authorityHash != null &&
                        AtlasHashing.constantTimeEquals(authorityHash, record.recordHash)
                ) {
                    "Downstream-use authority hash does not match"
                }
            }

            ContributionAuthorityKind.INTEGRATION_NODE -> {
                val record = requireNotNull(
                    dao.integrationNodeById(authority.authorityId)
                ) {
                    "Unknown integration-node authority ${authority.authorityId}"
                }
                require(record.runId == edge.runId && record.groupId == edge.groupId.value) {
                    "Integration-node authority belongs to a different run or group"
                }
                val authorityHash = authority.authorityHash
                require(
                    authorityHash != null &&
                        AtlasHashing.constantTimeEquals(authorityHash, record.recordHash)
                ) {
                    "Integration-node authority hash does not match"
                }
            }

            ContributionAuthorityKind.FINAL_DELIVERABLE_REFERENCE -> {
                val record = requireNotNull(
                    dao.finalDeliverableReferenceById(authority.authorityId)
                ) {
                    "Unknown final-deliverable reference authority ${authority.authorityId}"
                }
                require(record.runId == edge.runId && record.groupId == edge.groupId.value) {
                    "Final-deliverable reference belongs to a different run or group"
                }
                val authorityHash = authority.authorityHash
                require(
                    authorityHash != null &&
                        AtlasHashing.constantTimeEquals(authorityHash, record.recordHash)
                ) {
                    "Final-deliverable reference authority hash does not match"
                }
            }

            else -> error(
                "Contribution authority ${authority.kind.name} is not connected to its " +
                    "immutable repository boundary"
            )
        }
    }

    private suspend fun verifyCanonicalAuthority(
        edge: ContributionEdge,
        authority: ContributionAuthorityRef
    ) {
        val (prefix, id) = authority.authorityId.split(":", limit = 2)
            .takeIf { it.size == 2 && it.all(String::isNotBlank) }
            ?: error("Canonical artifact authority must use a typed prefix")
        val belongsToScope = when (prefix) {
            "kb" -> database.knowledgeBaseDao().getById(id)?.let {
                it.runId == edge.runId && it.groupId == edge.groupId.value &&
                    (
                        authority.kind != ContributionAuthorityKind.STRUCTURED_DEAD_END ||
                            it.isDeadEnd
                        )
            }
            "kg" -> database.knowledgeGraphFactDao().getById(id)?.let {
                it.runId == edge.runId && it.groupId == edge.groupId.value
            }
            "tool" -> database.toolRegistryDao().getById(id)?.let {
                it.runId == edge.runId && it.groupId == edge.groupId.value
            }
            "constraint" -> database.kgSchemaConstraintDao().getById(id)?.let {
                it.runId == edge.runId && it.groupId == edge.groupId.value
            }
            "submission" -> database.stagingDao().getById(id)?.let {
                it.runId == edge.runId && it.groupId == edge.groupId.value &&
                    it.status == "promoted"
            }
            else -> null
        }
        require(belongsToScope == true) {
            "Unknown or cross-scope canonical authority ${authority.authorityId}"
        }
        require(authority.authorityHash != null) {
            "Canonical authority requires the exact promoted content hash"
        }
    }

    private suspend fun appendIdempotently(edges: List<ContributionEdge>): List<ContributionEdge> {
        edges.forEach { edge ->
            require(edge.hasValidHash()) { "Generated contribution edge is invalid" }
            verifyAuthority(edge, edge.source)
            verifyAuthority(edge, edge.target)
        }
        dao.insertAllIdempotent(edges.map { it.toEntity() })
        edges.forEach { expected ->
            val persisted = requireNotNull(dao.byId(expected.edgeId)) {
                "Contribution edge ${expected.edgeId} was not persisted"
            }
            require(AtlasHashing.constantTimeEquals(persisted.edgeHash, expected.edgeHash)) {
                "Contribution edge ID conflicts with different provenance"
            }
        }
        return edges
    }

    private fun ContributionEdge.toEntity(): ContributionEdgeEntity = ContributionEdgeEntity(
        edgeId = edgeId,
        runId = runId,
        groupId = groupId.value,
        round = round,
        plane = plane.name,
        relation = relation.name,
        sourceKind = source.kind.name,
        sourceAuthorityId = source.authorityId,
        sourceAuthorityHash = source.authorityHash,
        targetKind = target.kind.name,
        targetAuthorityId = target.authorityId,
        targetAuthorityHash = target.authorityHash,
        edgeHash = edgeHash,
        contentJson = json.encodeToString(ContributionEdge.serializer(), this),
        occurredAtMs = occurredAt.toEpochMilliseconds()
    )

    private fun ContributionEdgeEntity.toContract(): ContributionEdge =
        json.decodeFromString(ContributionEdge.serializer(), contentJson)

    private fun CriticDecisionRecord.toEntity(): CriticDecisionEntity = CriticDecisionEntity(
        decisionId = decisionId,
        runId = runId,
        groupId = groupId.value,
        round = round,
        submissionId = submissionId,
        submissionContentHash = submissionContentHash,
        criticAgentId = resolvedReview.criticAgentId,
        outcome = resolvedReview.outcome.name,
        resultKind = resultAuthority?.kind?.name,
        resultAuthorityId = resultAuthority?.authorityId,
        resultAuthorityHash = resultAuthority?.authorityHash,
        decisionHash = decisionHash,
        contentJson = json.encodeToString(CriticDecisionRecord.serializer(), this),
        decidedAtMs = decidedAt.toEpochMilliseconds()
    )

    private fun CriticDecisionEntity.toContract(): CriticDecisionRecord =
        json.decodeFromString(CriticDecisionRecord.serializer(), contentJson)

    private fun DownstreamUseRecord.toEntity(): DownstreamUseRecordEntity =
        DownstreamUseRecordEntity(
            useId = useId,
            runId = runId,
            groupId = groupId.value,
            round = round,
            agentId = agentId,
            nodeId = nodeId,
            kind = kind.name,
            sourceKind = sourceAuthority.kind.name,
            sourceAuthorityId = sourceAuthority.authorityId,
            sourceAuthorityHash = requireNotNull(sourceAuthority.authorityHash),
            contextHash = contextHash,
            recordHash = recordHash,
            contentJson = json.encodeToString(DownstreamUseRecord.serializer(), this),
            occurredAtMs = occurredAt.toEpochMilliseconds()
        )

    private fun DownstreamUseRecordEntity.toContract(): DownstreamUseRecord =
        json.decodeFromString(DownstreamUseRecord.serializer(), contentJson).also {
            require(AtlasHashing.constantTimeEquals(it.recordHash, recordHash)) {
                "Persisted downstream-use payload hash does not match indexed authority"
            }
        }

    private fun IntegrationNodeRecord.toEntity(): IntegrationNodeRecordEntity =
        IntegrationNodeRecordEntity(
            integrationId = integrationId,
            runId = runId,
            groupId = groupId.value,
            round = round,
            nodeId = nodeId,
            atlasVersion = atlasVersion,
            atlasHash = atlasHash,
            nodePayloadHash = nodePayloadHash,
            captureDecisionId = captureDecision.authorityId,
            captureDecisionHash = requireNotNull(captureDecision.authorityHash),
            recordHash = recordHash,
            contentJson = json.encodeToString(IntegrationNodeRecord.serializer(), this),
            integratedAtMs = integratedAt.toEpochMilliseconds()
        )

    private fun IntegrationNodeRecordEntity.toContract(): IntegrationNodeRecord =
        json.decodeFromString(IntegrationNodeRecord.serializer(), contentJson).also {
            require(AtlasHashing.constantTimeEquals(it.recordHash, recordHash)) {
                "Persisted integration-node payload hash does not match indexed authority"
            }
        }

    private fun FinalDeliverableReferenceRecord.toEntity() =
        FinalDeliverableReferenceRecordEntity(
            referenceId = referenceId,
            runId = runId,
            groupId = groupId.value,
            round = round,
            relativePath = relativePath,
            mediaType = mediaType,
            contentHash = contentHash,
            writerAgentId = writerAgentId,
            writerSubmissionId = writerSubmissionId,
            nodeId = nodeId,
            sourceKind = sourceAuthority.kind.name,
            sourceAuthorityId = sourceAuthority.authorityId,
            sourceAuthorityHash = requireNotNull(sourceAuthority.authorityHash),
            recordHash = recordHash,
            contentJson = json.encodeToString(FinalDeliverableReferenceRecord.serializer(), this),
            referencedAtMs = referencedAt.toEpochMilliseconds()
        )

    private fun FinalDeliverableReferenceRecordEntity.toContract() =
        json.decodeFromString(FinalDeliverableReferenceRecord.serializer(), contentJson).also {
            require(AtlasHashing.constantTimeEquals(it.recordHash, recordHash)) {
                "Persisted final-deliverable reference hash does not match indexed authority"
            }
        }

    private fun ReceiverScoreContributionRecord.toEntity() =
        ReceiverScoreContributionRecordEntity(
            contributionId = contributionId,
            runId = runId,
            groupId = groupId.value,
            round = round,
            finalReferenceId = finalReference.authorityId,
            finalReferenceHash = requireNotNull(finalReference.authorityHash),
            scoringContractId = scoringContractId,
            scoringContractVersion = scoringContractVersion,
            scoringContractHash = scoringContractHash,
            criterionId = criterionId,
            scoreContribution = scoreContribution,
            maximumContribution = maximumContribution,
            recordHash = recordHash,
            contentJson = json.encodeToString(ReceiverScoreContributionRecord.serializer(), this),
            scoredAtMs = scoredAt.toEpochMilliseconds()
        )

    private fun ReceiverScoreContributionRecordEntity.toContract() =
        json.decodeFromString(ReceiverScoreContributionRecord.serializer(), contentJson).also {
            require(AtlasHashing.constantTimeEquals(it.recordHash, recordHash)) {
                "Persisted receiver-score contribution hash does not match indexed authority"
            }
        }

    private fun BenchmarkScoreContributionRecord.toEntity() =
        BenchmarkScoreContributionRecordEntity(
            contributionId = contributionId,
            runId = runId,
            groupId = groupId.value,
            round = round,
            finalReferenceId = finalReference.authorityId,
            finalReferenceHash = requireNotNull(finalReference.authorityHash),
            benchmarkId = benchmarkId,
            benchmarkVersion = benchmarkVersion,
            benchmarkContractHash = benchmarkContractHash,
            componentId = componentId,
            scoreContribution = scoreContribution,
            maximumContribution = maximumContribution,
            recordHash = recordHash,
            contentJson = json.encodeToString(BenchmarkScoreContributionRecord.serializer(), this),
            scoredAtMs = scoredAt.toEpochMilliseconds()
        )

    private fun BenchmarkScoreContributionRecordEntity.toContract() =
        json.decodeFromString(BenchmarkScoreContributionRecord.serializer(), contentJson).also {
            require(AtlasHashing.constantTimeEquals(it.recordHash, recordHash)) {
                "Persisted benchmark-score contribution hash does not match indexed authority"
            }
        }

    private fun ControlInterventionRecord.toEntity() =
        ControlInterventionRecordEntity(
            interventionId = interventionId,
            runId = runId,
            groupId = groupId.value,
            round = round,
            controllerAgentId = controllerAgentId,
            controllerRole = controllerRole.name,
            targetKind = targetKind.name,
            targetId = targetId,
            outcome = outcome.name,
            recordHash = recordHash,
            contentJson = json.encodeToString(ControlInterventionRecord.serializer(), this),
            occurredAtMs = occurredAt.toEpochMilliseconds()
        )

    private fun ControlInterventionRecordEntity.toContract() =
        json.decodeFromString(ControlInterventionRecord.serializer(), contentJson).also {
            require(AtlasHashing.constantTimeEquals(it.recordHash, recordHash)) {
                "Persisted control-intervention hash does not match indexed authority"
            }
        }

    private fun SummaryValidationRecord.toEntity() =
        SummaryValidationRecordEntity(
            validationId = validationId,
            runId = runId,
            groupId = groupId.value,
            round = round,
            summarizerAgentId = summarizerAgentId,
            validatorContractHash = validatorContractHash,
            recordHash = recordHash,
            contentJson = json.encodeToString(SummaryValidationRecord.serializer(), this),
            validatedAtMs = validatedAt.toEpochMilliseconds()
        )

    private fun SummaryValidationRecordEntity.toContract() =
        json.decodeFromString(SummaryValidationRecord.serializer(), contentJson).also {
            require(AtlasHashing.constantTimeEquals(it.recordHash, recordHash)) {
                "Persisted summary-validation hash does not match indexed authority"
            }
        }

    @Serializable
    private data class EventAuthorityPayload(
        val id: String,
        val timestampMs: Long,
        val eventType: String,
        val agentId: String?,
        val groupId: String?,
        val runId: String,
        val generation: Int,
        val payloadJson: String
    )
}
