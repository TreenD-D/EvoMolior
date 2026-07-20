package com.achulkov.evomolior.orchestration.epigenetic

import com.achulkov.evomolior.core.atlas.ToolVersionId
import com.achulkov.evomolior.core.contract.CausalFitnessComponent
import com.achulkov.evomolior.core.contract.CausalFitnessRecord
import com.achulkov.evomolior.core.contract.ContributionAuthorityKind
import com.achulkov.evomolior.core.contract.ContributionAuthorityRef
import com.achulkov.evomolior.core.contract.ContributionEdge
import com.achulkov.evomolior.core.contract.ContributionRelation
import com.achulkov.evomolior.core.contract.CriticOutcome
import com.achulkov.evomolior.core.contract.ControlInterventionOutcome
import com.achulkov.evomolior.core.contract.ControlInterventionRecord
import com.achulkov.evomolior.core.contract.SummaryPrimaryContentAssessment
import com.achulkov.evomolior.core.contract.SummaryValidationRecord
import com.achulkov.evomolior.core.contract.FitnessComponentKind
import com.achulkov.evomolior.core.contract.FitnessPlane
import com.achulkov.evomolior.core.contract.fitnessPlane
import com.achulkov.evomolior.core.council.CouncilDeterministicIds
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
import com.achulkov.evomolior.substrate.tools.ToolInfrastructureRepository
import com.achulkov.evomolior.substrate.worker.CausalFitnessRepository
import com.achulkov.evomolior.substrate.worker.ContributionGraphRepository
import kotlinx.serialization.json.Json

/**
 * Derives Worker, Review, and assessed Control fitness from immutable causal authority.
 *
 * Grader fitness remains governed by the separate Grader Atlas repository.
 */
class CausalFitnessDeriver(
    private val database: EvoMoliorDatabase,
    private val graphRepository: ContributionGraphRepository,
    private val fitnessRepository: CausalFitnessRepository,
    private val toolInfrastructureRepository: ToolInfrastructureRepository =
        ToolInfrastructureRepository(database),
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }
) {
    /** Derives every causal Worker, Review, assessed Control, or validated Summary observation. */
    suspend fun deriveGeneration(
        runId: String,
        groupId: String,
        round: Int
    ): List<CausalFitnessRecord> {
        val edges = graphRepository.forRun(runId, groupId).filter { it.round <= round }
        val authoredAgentIds = edges.asSequence()
            .filter { it.relation == ContributionRelation.AUTHORED }
            .map { it.source.authorityId }
            .toSet()
        val outlierFitness = database.outlierNoveltyDao().fitnessThroughRound(runId, round)
        val workerRecords = (authoredAgentIds + outlierFitness.map { it.agentId })
            .mapNotNull { agentId ->
                val role = roleForAgent(runId, groupId, agentId) ?: return@mapNotNull null
                if (role.fitnessPlane() != FitnessPlane.WORKER) return@mapNotNull null
                deriveWorker(
                    runId = runId,
                    groupId = groupId,
                    round = round,
                    agentId = agentId,
                    role = role,
                    edges = edges,
                    outlierFitness = outlierFitness.filter { it.agentId == agentId }
                )
            }
        val reviewRecords = graphRepository.criticDecisionsForRound(runId, groupId, round)
            .map { it.resolvedReview.criticAgentId }
            .distinct()
            .mapNotNull { criticId ->
                deriveReview(runId, groupId, round, criticId, edges)
            }
        val controlRecords = graphRepository.controlInterventionsThroughRound(
            runId,
            groupId,
            round
        ).groupBy(ControlInterventionRecord::controllerAgentId)
            .mapNotNull { (_, interventions) ->
                deriveControl(runId, groupId, round, interventions)
            }
        val summaryRecords = graphRepository.summaryValidationsThroughRound(
            runId,
            groupId,
            round
        ).groupBy(SummaryValidationRecord::summarizerAgentId)
            .mapNotNull { (_, validations) ->
                deriveSummary(runId, groupId, round, validations)
            }
        return (workerRecords + reviewRecords + controlRecords + summaryRecords)
            .map { fitnessRepository.append(it) }
            .sortedWith(compareBy(CausalFitnessRecord::plane, { -it.total }, { it.agentId }))
    }

    /** Selects the top fraction independently inside each fitness plane. */
    fun topPercentileByPlane(
        records: List<CausalFitnessRecord>,
        percentile: Double = 0.20
    ): List<CausalFitnessRecord> {
        require(percentile > 0.0 && percentile <= 1.0) {
            "Fitness percentile must be in (0, 1]"
        }
        return records.groupBy(CausalFitnessRecord::plane)
            .toSortedMap()
            .values
            .flatMap { planeRecords ->
                val eligible = planeRecords.filter(CausalFitnessRecord::legacyEligible)
                    .sortedWith(compareByDescending<CausalFitnessRecord> { it.total }
                        .thenBy(CausalFitnessRecord::agentId))
                if (eligible.isEmpty()) {
                    emptyList()
                } else {
                    eligible.take((eligible.size * percentile).toInt().coerceAtLeast(1))
                }
            }
    }

    private suspend fun deriveWorker(
        runId: String,
        groupId: String,
        round: Int,
        agentId: String,
        role: Role,
        edges: List<ContributionEdge>,
        outlierFitness: List<com.achulkov.evomolior.substrate.db.entity.OutlierFitnessRecordEntity>
    ): CausalFitnessRecord? {
        val reachable = reachableEdges(agentId, edges)
        val components = buildList {
            addEdgeCount(
                FitnessComponentKind.ACCEPTED_NODE_VALUE,
                reachable.filter { it.relation == ContributionRelation.AUTHORIZED_CAPTURE }
            )
            addEdgeCount(
                FitnessComponentKind.DOWNSTREAM_REUSE,
                reachable.filter {
                    it.relation in setOf(
                        ContributionRelation.READ_DOWNSTREAM,
                        ContributionRelation.CITED_DOWNSTREAM,
                        ContributionRelation.EXECUTED_DOWNSTREAM
                    )
                }
            )
            val passingGradeEdges = reachable.filter {
                it.relation == ContributionRelation.GRADED_AS &&
                    graphRepository.nodeGradeBundle(it.target.authorityId)?.verdict ==
                    com.achulkov.evomolior.core.atlas.NodeGradeBundleVerdict.VALID_PASS
            }
            addEdgeCount(FitnessComponentKind.VERIFIER_PASS, passingGradeEdges)
            addEdgeCount(
                FitnessComponentKind.INTEGRATION_CONTRIBUTION,
                reachable.filter { it.relation == ContributionRelation.INTEGRATED_INTO_NODE }
            )
            addEdgeCount(
                FitnessComponentKind.EVIDENCE_LINEAGE_QUALITY,
                reachable.filter { it.relation == ContributionRelation.INCLUDED_IN_EVIDENCE }
            )
            addEdgeCount(
                FitnessComponentKind.STRUCTURED_NEGATIVE_VALUE,
                reachable.filter {
                    it.relation == ContributionRelation.ACCEPTED_AS_STRUCTURED_DEAD_END
                }
            )
            val rejectedEdges = reachable.filter { edge ->
                edge.relation == ContributionRelation.REVIEWED_AS &&
                    graphRepository.criticDecision(edge.target.authorityId)
                        ?.resolvedReview?.outcome == CriticOutcome.REJECT
            }
            if (rejectedEdges.isNotEmpty()) {
                add(
                    CausalFitnessComponent(
                        kind = FitnessComponentKind.REJECTED_OR_MALFORMED_WRITE,
                        value = -rejectedEdges.size.toDouble(),
                        contributionEdgeIds = rejectedEdges.mapTo(linkedSetOf()) { it.edgeId }
                    )
                )
            }
            database.toolRegistryDao().getByAuthor(runId, groupId, agentId).forEach { tool ->
                toolInfrastructureRepository.fitnessForVersion(ToolVersionId(tool.id))
                    .filter { it.throughRound <= round }
                    .maxByOrNull { it.throughRound }
                    ?.let { fitness ->
                        add(
                            CausalFitnessComponent(
                                kind = FitnessComponentKind.TOOL_FITNESS,
                                value = fitness.fitnessValue,
                                authorityRefs = setOf(
                                    ContributionAuthorityRef(
                                        kind = ContributionAuthorityKind.TOOL_FITNESS,
                                        authorityId = fitness.recordId,
                                        authorityHash = fitness.recordHash
                                    )
                                )
                            )
                        )
                    }
            }
            outlierFitness.forEach { entity ->
                val fitness = json.decodeFromString(
                    com.achulkov.evomolior.core.atlas.OutlierFitnessRecord.serializer(),
                    entity.contentJson
                )
                add(
                    CausalFitnessComponent(
                        kind = FitnessComponentKind.OUTLIER_FITNESS,
                        value = fitness.noveltyCredit + fitness.anticipationCredit +
                            fitness.downstreamAdoptionCredit,
                        authorityRefs = setOf(
                            ContributionAuthorityRef(
                                kind = ContributionAuthorityKind.OUTLIER_FITNESS,
                                authorityId = fitness.recordId,
                                authorityHash = fitness.recordHash
                            )
                        )
                    )
                )
            }
        }
        if (components.isEmpty()) return null
        return record(runId, groupId, round, agentId, role, components)
    }

    private suspend fun deriveReview(
        runId: String,
        groupId: String,
        round: Int,
        criticId: String,
        edges: List<ContributionEdge>
    ): CausalFitnessRecord? {
        val decisions = graphRepository.criticDecisionsForRound(runId, groupId, round)
            .filter { it.resolvedReview.criticAgentId == criticId }
        val components = buildList {
            decisions.filter { it.resolvedReview.outcome == CriticOutcome.PROMOTE }
                .forEach { decision ->
                    val resultId = decision.resultAuthority?.authorityId ?: return@forEach
                    val resultReachable = reachableFrom(
                        ContributionAuthorityKind.CANONICAL_ARTIFACT,
                        resultId,
                        edges
                    ) + reachableFrom(
                        ContributionAuthorityKind.STRUCTURED_DEAD_END,
                        resultId,
                        edges
                    )
                    val graded = resultReachable.filter {
                        it.relation == ContributionRelation.GRADED_AS
                    }
                    val passed = graded.any {
                        graphRepository.nodeGradeBundle(it.target.authorityId)?.verdict ==
                            com.achulkov.evomolior.core.atlas.NodeGradeBundleVerdict.VALID_PASS
                    }
                    val failed = graded.any {
                        graphRepository.nodeGradeBundle(it.target.authorityId)?.verdict ==
                            com.achulkov.evomolior.core.atlas.NodeGradeBundleVerdict.VALID_FAIL
                    }
                    if (passed) {
                        add(
                            CausalFitnessComponent(
                                kind = FitnessComponentKind.CORRECT_PROMOTION,
                                value = 1.0,
                                contributionEdgeIds = graded.mapTo(linkedSetOf()) { it.edgeId }
                            )
                        )
                    } else if (failed) {
                        add(
                            CausalFitnessComponent(
                                kind = FitnessComponentKind.LATER_REVIEW_ERROR,
                                value = -1.0,
                                contributionEdgeIds = graded.mapTo(linkedSetOf()) { it.edgeId }
                            )
                        )
                    }
                }
        }
        if (components.isEmpty()) return null
        return record(runId, groupId, round, criticId, Role.Critic, components)
    }

    private suspend fun deriveControl(
        runId: String,
        groupId: String,
        round: Int,
        interventions: List<ControlInterventionRecord>
    ): CausalFitnessRecord? {
        val assessed = interventions.filter {
            it.outcome == ControlInterventionOutcome.TRUE_POSITIVE ||
                it.outcome == ControlInterventionOutcome.FALSE_POSITIVE
        }
        if (assessed.isEmpty()) return null
        val role = assessed.first().controllerRole
        require(assessed.all { it.controllerRole == role }) {
            "One controller identity cannot cross control roles"
        }
        val components = assessed.flatMap { intervention ->
            val authority = ContributionAuthorityRef(
                kind = ContributionAuthorityKind.CONTROL_INTERVENTION,
                authorityId = intervention.interventionId,
                authorityHash = intervention.recordHash
            )
            buildList {
                when (intervention.outcome) {
                    ControlInterventionOutcome.TRUE_POSITIVE -> {
                        add(
                            CausalFitnessComponent(
                                kind = FitnessComponentKind.TRUE_POSITIVE_INTERVENTION,
                                value = 1.0,
                                authorityRefs = setOf(authority)
                            )
                        )
                        add(
                            CausalFitnessComponent(
                                kind = FitnessComponentKind.AVOIDED_LOSS,
                                value = intervention.avoidedLossAuthorities.size.toDouble(),
                                authorityRefs = setOf(authority)
                            )
                        )
                    }
                    ControlInterventionOutcome.FALSE_POSITIVE ->
                        add(
                            CausalFitnessComponent(
                                kind = FitnessComponentKind.FALSE_POSITIVE_INTERVENTION,
                                value = -1.0,
                                authorityRefs = setOf(authority)
                            )
                        )
                    ControlInterventionOutcome.TRIGGERED_UNASSESSED,
                    ControlInterventionOutcome.NO_ACTION_REQUIRED -> Unit
                }
                val knownControlCost = intervention.costAuthorities.sumOf { cost ->
                    requireNotNull(database.costLedgerDao().byId(cost.authorityId)?.knownTotalUsd) {
                        "Control fitness cannot zero-fill unknown cost ${cost.authorityId}"
                    }
                }
                if (knownControlCost > 0.0) {
                    add(
                        CausalFitnessComponent(
                            kind = FitnessComponentKind.CONTROL_COST,
                            value = -knownControlCost,
                            authorityRefs = setOf(authority)
                        )
                    )
                }
            }
        }
        if (components.isEmpty()) return null
        return record(
            runId,
            groupId,
            round,
            assessed.first().controllerAgentId,
            role,
            components
        )
    }

    private fun deriveSummary(
        runId: String,
        groupId: String,
        round: Int,
        validations: List<SummaryValidationRecord>
    ): CausalFitnessRecord? {
        if (validations.isEmpty()) return null
        val components = validations.flatMap { validation ->
            val authority = ContributionAuthorityRef(
                kind = ContributionAuthorityKind.SUMMARY_VALIDATION,
                authorityId = validation.validationId,
                authorityHash = validation.recordHash
            )
            buildList {
                add(
                    CausalFitnessComponent(
                        kind = FitnessComponentKind.SUMMARY_FIDELITY,
                        value = validation.fidelityChecksPassed.toDouble() /
                            validation.fidelityChecksTotal,
                        authorityRefs = setOf(authority)
                    )
                )
                add(
                    CausalFitnessComponent(
                        kind = FitnessComponentKind.SUMMARY_RETRIEVAL_VALUE,
                        value = validation.retrievalReferencesValidated.toDouble() /
                            validation.retrievalReferencesTotal,
                        authorityRefs = setOf(authority)
                    )
                )
                val sourceBytes = validation.sourceContentBytes
                val outputBytes = validation.outputContentBytes
                if (sourceBytes != null && outputBytes != null) {
                    add(
                        CausalFitnessComponent(
                            kind = FitnessComponentKind.SUMMARY_COMPRESSION,
                            value = (sourceBytes - outputBytes).toDouble() / sourceBytes,
                            authorityRefs = setOf(authority)
                        )
                    )
                }
                if (
                    validation.primaryContentAssessment ==
                    SummaryPrimaryContentAssessment.DETECTED
                ) {
                    add(
                        CausalFitnessComponent(
                            kind = FitnessComponentKind.SUMMARY_PRIMARY_CONTENT_PENALTY,
                            value = -1.0,
                            authorityRefs = setOf(authority)
                        )
                    )
                }
            }
        }
        return record(
            runId,
            groupId,
            round,
            validations.first().summarizerAgentId,
            Role.Archivist,
            components
        )
    }

    private fun record(
        runId: String,
        groupId: String,
        round: Int,
        agentId: String,
        role: Role,
        components: List<CausalFitnessComponent>
    ): CausalFitnessRecord {
        val id = CouncilDeterministicIds.create(
            "causal-fitness",
            runId,
            groupId,
            agentId,
            round.toString()
        )
        return CausalFitnessRecord(
            recordId = id,
            runId = runId,
            groupId = com.achulkov.evomolior.core.atlas.AtlasGroupId(groupId),
            round = round,
            agentId = agentId,
            role = role,
            plane = role.fitnessPlane(),
            components = components,
            total = components.sumOf(CausalFitnessComponent::value),
            legacyEligible = role != Role.Outlier &&
                components.sumOf(CausalFitnessComponent::value) > 0.0
        ).withComputedHash()
    }

    private suspend fun roleForAgent(
        runId: String,
        groupId: String,
        agentId: String
    ): Role? = database.stagingDao().getByAgent(runId, groupId, agentId)
        .firstNotNullOfOrNull { runCatching { Role.valueOf(it.role) }.getOrNull() }

    private fun reachableEdges(
        agentId: String,
        edges: List<ContributionEdge>
    ): List<ContributionEdge> =
        reachableFrom(ContributionAuthorityKind.AGENT, agentId, edges)

    private fun reachableFrom(
        kind: ContributionAuthorityKind,
        authorityId: String,
        edges: List<ContributionEdge>
    ): List<ContributionEdge> {
        val reachable = mutableSetOf(kind to authorityId)
        val selected = mutableListOf<ContributionEdge>()
        var changed = true
        while (changed) {
            changed = false
            edges.forEach { edge ->
                val source = edge.source.kind to edge.source.authorityId
                val target = edge.target.kind to edge.target.authorityId
                if (source in reachable && edge !in selected) {
                    selected += edge
                    if (reachable.add(target)) changed = true
                }
            }
        }
        return selected
    }

    private fun MutableList<CausalFitnessComponent>.addEdgeCount(
        kind: FitnessComponentKind,
        edges: List<ContributionEdge>
    ) {
        if (edges.isNotEmpty()) {
            add(
                CausalFitnessComponent(
                    kind = kind,
                    value = edges.size.toDouble(),
                    contributionEdgeIds = edges.mapTo(linkedSetOf()) { it.edgeId }
                )
            )
        }
    }
}
