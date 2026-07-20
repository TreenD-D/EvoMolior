package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.competition.outlier.NoveltyAmendmentCompiler
import com.achulkov.evomolior.competition.outlier.OutlierNoveltyScreener
import com.achulkov.evomolior.competition.outlier.ShadowNodeGraderResolver
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.HiddenMapAnticipation
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.NoveltyGraderAssessment
import com.achulkov.evomolior.core.atlas.NoveltyScreeningStatus
import com.achulkov.evomolior.core.atlas.NoveltyVectorProposal
import com.achulkov.evomolior.core.atlas.OutlierDownstreamLineage
import com.achulkov.evomolior.core.atlas.OutlierDownstreamOutcome
import com.achulkov.evomolior.core.atlas.OutlierFitnessRecord
import com.achulkov.evomolior.core.atlas.OutlierNoveltyMetrics
import com.achulkov.evomolior.core.atlas.ShadowNodeDecision
import com.achulkov.evomolior.core.atlas.ShadowNodeLifecycleEvent
import com.achulkov.evomolior.core.atlas.ShadowNodeState
import com.achulkov.evomolior.core.contract.CommitType
import com.achulkov.evomolior.core.contract.ContributionCommit
import com.achulkov.evomolior.core.grader.GraderAtlas
import com.achulkov.evomolior.orchestration.council.GraderAtlasEvolutionCompiler
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.atlas.AtlasAmendmentAuthorityTransitionRequest
import com.achulkov.evomolior.substrate.atlas.AtlasProjectionSet
import com.achulkov.evomolior.substrate.atlas.AtlasTransactionRepository
import com.achulkov.evomolior.substrate.atlas.LegacyNodeMapping
import com.achulkov.evomolior.substrate.atlas.LegacyNodeSource
import com.achulkov.evomolior.substrate.atlas.ResourceProjectionNode
import com.achulkov.evomolior.substrate.atlas.WipProjectionTask
import com.achulkov.evomolior.substrate.grader.GraderAuthorityRepository
import com.achulkov.evomolior.substrate.outlier.OutlierNoveltyRepository
import com.achulkov.evomolior.substrate.worker.WorkerExecutionRepository

/** Executes one round of global shadow-node authority over isolated group and grader databases. */
class OutlierNoveltyRoundProcessor(
    private val substrateFactory: SubstrateDatabaseFactory,
    private val atlasRepository: AtlasTransactionRepository,
    private val graderAtlasRepository: GraderAuthorityRepository =
        GraderAuthorityRepository(substrateFactory),
    private val assessmentRunner: suspend (
        proposals: List<NoveltyVectorProposal>
    ) -> List<NoveltyGraderAssessment>,
    private val screener: OutlierNoveltyScreener = OutlierNoveltyScreener(),
    private val resolver: ShadowNodeGraderResolver = ShadowNodeGraderResolver(),
    private val amendmentCompiler: NoveltyAmendmentCompiler = NoveltyAmendmentCompiler()
) {
    /**
     * Imports, screens, grades, tests, and amends novelty authority for one completed round.
     */
    suspend fun process(
        runId: String,
        atlasId: String,
        workerGroupIds: List<String>,
        graderGroupIds: Set<String>,
        round: Int
    ): OutlierNoveltyRoundResult {
        require(graderGroupIds.isNotEmpty()) { "Outlier novelty requires independent graders" }
        val global = OutlierNoveltyRepository(substrateFactory.getAtlas(runId))
        val roundAtlas = requireNotNull(atlasRepository.currentAtlas(runId, atlasId)) {
            "Outlier novelty requires the active sealed Atlas"
        }
        val roundGraderAtlas = requireNotNull(graderAtlasRepository.currentAtlas(runId)) {
            "Outlier novelty requires the active sealed Grader Atlas"
        }
        require(
            roundGraderAtlas.version == roundAtlas.version &&
                roundGraderAtlas.workerAtlasHash == roundAtlas.atlasHash
        ) {
            "Outlier novelty requires a matched worker and Grader Atlas pair"
        }
        val states = atlasRepository.currentStates(runId, atlasId)
        val unique = mutableListOf<NoveltyVectorProposal>()
        var proposalCount = 0
        for (groupId in workerGroupIds.sorted()) {
            val groupDb = substrateFactory.get(runId, groupId)
            val groupRepository = OutlierNoveltyRepository(groupDb)
            val canon = groupDb.knowledgeBaseDao()
                .listActiveOrganized(runId, groupId, 1_000)
                .associate { it.id to it.content }
            for (proposal in groupRepository.proposalsForRound(runId, round)) {
                proposalCount += 1
                require(
                    proposal.groupId.value == groupId &&
                        proposal.atlasHash == roundAtlas.atlasHash &&
                        proposal.affectedVisibleNodeIds.all { nodeId ->
                            proposal.groupId in states.getValue(nodeId).revealedToGroups
                        }
                ) {
                    "Outlier proposal does not match its isolated group visibility authority"
                }
                val initial = groupRepository.lifecycle(proposal.proposalId).single()
                global.appendInitialProposal(proposal, initial)
                val screening = screener.screen(roundAtlas, states, proposal, canon)
                global.appendScreening(screening)
                appendTransition(
                    global,
                    proposal,
                    ShadowNodeState.DUPLICATE_CHECKED,
                    round,
                    screeningHash = screening.screeningHash
                )
                if (screening.status == NoveltyScreeningStatus.UNIQUE) {
                    appendTransition(global, proposal, ShadowNodeState.SHADOW_NODE, round)
                    unique += proposal
                } else {
                    appendTransition(global, proposal, ShadowNodeState.REJECTED, round)
                }
            }
        }
        val assessments = assessmentRunner(unique)
        assessments.forEach { global.appendAssessment(it) }
        var acceptedShadowNodes = 0
        for (proposal in unique) {
            val screening = requireNotNull(global.screeningForProposal(proposal.proposalHash))
            val verdict = resolver.resolve(
                proposal = proposal,
                screening = screening,
                expectedGraderGroupIds = graderGroupIds,
                assessments = assessments.filter { it.proposalHash == proposal.proposalHash }
            )
            global.appendVerdict(verdict)
            when (verdict.decision) {
                ShadowNodeDecision.ACCEPT -> acceptedShadowNodes += 1
                ShadowNodeDecision.REJECT ->
                    appendTransition(global, proposal, ShadowNodeState.REJECTED, round)
                ShadowNodeDecision.DEFER, ShadowNodeDecision.INVALID ->
                    appendTransition(global, proposal, ShadowNodeState.DEFERRED, round)
            }
        }
        val tested = processLaterEvidence(
            global = global,
            runId = runId,
            atlas = roundAtlas,
            graderAtlas = roundGraderAtlas,
            workerGroupIds = workerGroupIds,
            round = round
        )
        return OutlierNoveltyRoundResult(
            atlas = tested.atlas,
            metrics = OutlierNoveltyMetrics(
                proposalCount = proposalCount,
                uniqueProposalCount = unique.size,
                acceptedShadowNodeCount = acceptedShadowNodes,
                testedProposalCount = tested.testedCount,
                acceptedAmendmentCount = tested.amendmentCount,
                downstreamAdoptionCount = tested.adoptionCount
            )
        )
    }

    private suspend fun processLaterEvidence(
        global: OutlierNoveltyRepository,
        runId: String,
        atlas: NodeAtlas,
        graderAtlas: GraderAtlas,
        workerGroupIds: List<String>,
        round: Int
    ): LaterEvidenceResult {
        var currentAtlas = atlas
        var currentGraderAtlas = graderAtlas
        var testedCount = 0
        var amendmentCount = 0
        var adoptionCount = 0
        val contributions = workerGroupIds.flatMap { groupId ->
            WorkerExecutionRepository(substrateFactory.get(runId, groupId))
                .contributionsForRound(runId, groupId, round)
        }
        for (proposal in global.proposalsBeforeRound(runId, round)) {
            val history = global.lifecycle(proposal.proposalId)
            if (history.lastOrNull()?.toState != ShadowNodeState.SHADOW_NODE) continue
            val verdict = global.verdictForProposal(proposal.proposalHash)
                ?.takeIf { it.decision == ShadowNodeDecision.ACCEPT }
                ?: continue
            val references = contributions.filter {
                proposal.proposalId in it.referencedNoveltyProposalIds
            }
            val persistedLineages = global.downstreamLineage(proposal.proposalId)
            if (references.isEmpty() && persistedLineages.isEmpty()) continue
            val lineages = (
                persistedLineages + references.map { contribution ->
                global.appendDownstreamLineage(
                    contribution.toLineage(proposal)
                )
                }
                ).distinctBy(OutlierDownstreamLineage::lineageHash)
            val evidenceArtifactIds = lineages.flatMapTo(linkedSetOf()) {
                it.evidenceArtifactIds
            }
            val testedEvent = transitionAfter(
                repository = global,
                proposal,
                previous = history.last(),
                toState = ShadowNodeState.TESTED,
                round = round,
                evidenceArtifactIds = evidenceArtifactIds
            )
            val adopted = lineages.any { it.outcome == OutlierDownstreamOutcome.ADOPTED }
            val screening = requireNotNull(global.screeningForProposal(proposal.proposalHash))
            val fitness = outlierFitness(
                proposal = proposal,
                verdict = verdict,
                screening = screening,
                lineages = lineages,
                adopted = adopted,
                round = round
            )
            if (adopted) {
                val compiled = amendmentCompiler.compile(
                    atlas = currentAtlas,
                    proposal = proposal,
                    screening = screening,
                    verdict = verdict,
                    effectiveRound = round + 1
                )
                val nextGraderAtlas = GraderAtlasEvolutionCompiler.evolve(
                    currentAtlas = currentGraderAtlas,
                    currentWorkerAtlas = currentAtlas,
                    nextWorkerAtlas = compiled.nextAtlas
                )
                val acceptedEvent = transitionAfter(
                    repository = global,
                    proposal = proposal,
                    previous = testedEvent,
                    toState = ShadowNodeState.ACCEPTED_AMENDMENT,
                    round = round,
                    amendmentId = compiled.amendment.amendmentId
                )
                atlasRepository.appendAmendmentAuthorityTransition(
                    AtlasAmendmentAuthorityTransitionRequest(
                        runId = runId,
                        amendment = compiled.amendment,
                        nextAtlas = compiled.nextAtlas,
                        projections = amendmentProjections(
                            requireNotNull(
                                atlasRepository.currentProjections(
                                    runId,
                                    currentAtlas.atlasId
                                )
                            ),
                            compiled.nextAtlas,
                            compiled.addedNode.id,
                            round + 1
                        ),
                        nextGraderAtlas = nextGraderAtlas,
                        lifecycleEvents = listOf(testedEvent, acceptedEvent),
                        fitnessRecord = fitness
                    )
                )
                currentAtlas = compiled.nextAtlas
                currentGraderAtlas = nextGraderAtlas
                amendmentCount += 1
                adoptionCount += 1
            } else {
                val rejectedEvent = transitionAfter(
                    repository = global,
                    proposal,
                    previous = testedEvent,
                    toState = ShadowNodeState.REJECTED,
                    round = round
                )
                global.appendTestOutcome(
                    lifecycleEvents = listOf(testedEvent, rejectedEvent),
                    fitness = fitness
                )
            }
            testedCount += 1
        }
        return LaterEvidenceResult(currentAtlas, testedCount, amendmentCount, adoptionCount)
    }

    private suspend fun appendTransition(
        repository: OutlierNoveltyRepository,
        proposal: NoveltyVectorProposal,
        toState: ShadowNodeState,
        round: Int,
        screeningHash: String? = null,
        amendmentId: String? = null,
        evidenceArtifactIds: Set<String> = emptySet()
    ) {
        val previous = repository.lifecycle(proposal.proposalId).last()
        repository.appendLifecycle(
            transitionAfter(
                repository = repository,
                proposal = proposal,
                previous = previous,
                toState = toState,
                round = round,
                screeningHash = screeningHash,
                amendmentId = amendmentId,
                evidenceArtifactIds = evidenceArtifactIds
            )
        )
    }

    private suspend fun transitionAfter(
        repository: OutlierNoveltyRepository,
        proposal: NoveltyVectorProposal,
        previous: ShadowNodeLifecycleEvent,
        toState: ShadowNodeState,
        round: Int,
        screeningHash: String? = null,
        amendmentId: String? = null,
        evidenceArtifactIds: Set<String> = emptySet()
    ): ShadowNodeLifecycleEvent = ShadowNodeLifecycleEvent(
        eventId = "${proposal.proposalId}:${previous.sequence + 1}:$toState",
        proposalId = proposal.proposalId,
        proposalHash = proposal.proposalHash,
        runId = proposal.runId,
        sequence = previous.sequence + 1,
        previousEventHash = previous.eventHash,
        fromState = previous.toState,
        toState = toState,
        round = round,
        screeningHash = screeningHash,
        graderVerdictHash = if (
            toState in setOf(ShadowNodeState.REJECTED, ShadowNodeState.DEFERRED)
        ) {
            repository.verdictForProposal(proposal.proposalHash)?.verdictHash
        } else {
            null
        },
        amendmentId = amendmentId,
        evidenceArtifactIds = evidenceArtifactIds
    ).withComputedHash()

    private fun outlierFitness(
        proposal: NoveltyVectorProposal,
        verdict: com.achulkov.evomolior.core.atlas.ShadowNodeGraderVerdict,
        screening: com.achulkov.evomolior.core.atlas.NoveltyScreeningResult,
        lineages: List<OutlierDownstreamLineage>,
        adopted: Boolean,
        round: Int
    ) = OutlierFitnessRecord(
        recordId = AtlasHashing.hashText("${proposal.proposalHash}|fitness|$round"),
        proposalId = proposal.proposalId,
        proposalHash = proposal.proposalHash,
        agentId = proposal.proposerAgentId,
        sourceRound = proposal.round,
        observationRound = round,
        noveltyCredit = verdict.aggregateVector?.novelty ?: 0.0,
        anticipationCredit = when (screening.hiddenMapAnticipation) {
            HiddenMapAnticipation.NONE -> 0.0
            HiddenMapAnticipation.PARTIAL -> 0.5
            HiddenMapAnticipation.DIRECT -> 1.0
        },
        downstreamAdoptionCredit = when {
            adopted -> 1.0
            lineages.any { it.outcome == OutlierDownstreamOutcome.TESTED } -> 0.5
            else -> 0.25
        },
        evidenceLineageHashes = lineages.mapTo(linkedSetOf()) { it.lineageHash }
    ).withComputedHash()

    private fun ContributionCommit.toLineage(
        proposal: NoveltyVectorProposal
    ): OutlierDownstreamLineage {
        val evidence = (artifactIds + claimIds + toolRunIds + testsRun).toSet()
        require(evidence.isNotEmpty()) {
            "A novelty reference requires durable independent evidence"
        }
        val outcome = when (commitType) {
            CommitType.FALSIFIED_HYPOTHESIS, CommitType.STRUCTURED_DEAD_END ->
                OutlierDownstreamOutcome.REJECTED_WITH_EVIDENCE
            CommitType.VALIDATION_RESULT ->
                OutlierDownstreamOutcome.TESTED
            else -> OutlierDownstreamOutcome.ADOPTED
        }
        return OutlierDownstreamLineage(
            lineageId = AtlasHashing.hashText("${proposal.proposalHash}|$commitId"),
            proposalId = proposal.proposalId,
            proposalHash = proposal.proposalHash,
            sourceAgentId = proposal.proposerAgentId,
            sourceRound = proposal.round,
            observingGroupId = groupId,
            observingAgentId = agentId,
            observationRound = round,
            outcome = outcome,
            evidenceArtifactIds = evidence
        ).withComputedHash()
    }

    private fun amendmentProjections(
        current: AtlasProjectionSet,
        nextAtlas: NodeAtlas,
        addedNodeId: NodeId,
        effectiveRound: Int
    ): AtlasProjectionSet {
        val node = nextAtlas.nodes.getValue(addedNodeId)
        val taskId = "atlas:${addedNodeId.value}"
        val newWip = current.wip.map { projection ->
            projection.copy(
                tasks = projection.tasks + WipProjectionTask(
                    taskId = taskId,
                    atlasNodeId = addedNodeId,
                    description = node.limitationStatement,
                    lastChangedGeneration = effectiveRound,
                    linkedNodeId = addedNodeId.value
                )
            )
        }
        val resourceId = "atlas-resource:${addedNodeId.value}"
        return current.copy(
            wip = newWip,
            resources = current.resources.copy(
                nodes = current.resources.nodes + ResourceProjectionNode(
                    resourceNodeId = resourceId,
                    atlasNodeId = addedNodeId,
                    name = node.capabilityTarget,
                    resourceType = when (node.kind) {
                        NodeKind.TOOL -> "TOOL_CACHE"
                        NodeKind.EVIDENCE -> "WEB_CACHE"
                        NodeKind.MODEL -> "GPU_BURST"
                        NodeKind.INTEGRATION -> "GOSSIP_RELAY"
                        else -> "STAGING_SHORTCUT"
                    },
                    yieldMultiplier = node.rewardSpec.credits.coerceAtLeast(1.0),
                    qualityThreshold = node.capturePolicy.minimumGrade
                )
            ),
            disclosure = current.disclosure.copy(stages = nextAtlas.stageDefinitions),
            mappings = current.mappings +
                newWip.map { projection ->
                    LegacyNodeMapping(
                        source = LegacyNodeSource.WIP,
                        sourceNodeId = taskId,
                        scopeId = projection.groupId,
                        atlasNodeId = addedNodeId
                    )
                } +
                LegacyNodeMapping(
                    source = LegacyNodeSource.RESOURCE,
                    sourceNodeId = resourceId,
                    scopeId = nextAtlas.runId,
                    atlasNodeId = addedNodeId
                )
        )
    }

    private data class LaterEvidenceResult(
        val atlas: NodeAtlas,
        val testedCount: Int,
        val amendmentCount: Int,
        val adoptionCount: Int
    )
}

/** Result of one round's novelty authority processing. */
data class OutlierNoveltyRoundResult(
    val atlas: NodeAtlas,
    val metrics: OutlierNoveltyMetrics
)
