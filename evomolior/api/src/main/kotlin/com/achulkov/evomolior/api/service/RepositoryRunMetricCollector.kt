package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.CaptureState
import com.achulkov.evomolior.core.atlas.GradeVerdict
import com.achulkov.evomolior.core.atlas.NodeCaptureDecision
import com.achulkov.evomolior.core.atlas.NodeCaptureDecisionType
import com.achulkov.evomolior.core.atlas.NodeEvidencePacket
import com.achulkov.evomolior.core.atlas.OutlierDownstreamOutcome
import com.achulkov.evomolior.core.atlas.ReplicationMode
import com.achulkov.evomolior.core.atlas.RewardUseLineageEvent
import com.achulkov.evomolior.core.atlas.ShadowNodeDecision
import com.achulkov.evomolior.core.atlas.ToolExecutionPurpose
import com.achulkov.evomolior.core.atlas.ToolExecutionResult
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.contract.AgentLifecycleClassification
import com.achulkov.evomolior.core.contract.CostLedgerEntryKind
import com.achulkov.evomolior.core.contract.CostSubjectType
import com.achulkov.evomolior.core.contract.MonetaryAmountStatus
import com.achulkov.evomolior.core.grader.GraderFitnessPlane
import com.achulkov.evomolior.core.grader.GraderScoreBundleStatus
import com.achulkov.evomolior.core.kg.KgFactAttemptStatus
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.atlas.AtlasTransactionRepository
import com.achulkov.evomolior.substrate.atlas.NodeCaptureRepository
import com.achulkov.evomolior.substrate.cost.CostLedgerRepository
import com.achulkov.evomolior.substrate.grader.GraderAuthorityRepository
import com.achulkov.evomolior.substrate.kg.KgFactAttemptRepository
import com.achulkov.evomolior.substrate.outlier.OutlierNoveltyRepository
import com.achulkov.evomolior.substrate.tools.ToolInfrastructureRepository
import com.achulkov.evomolior.substrate.worker.ContributionGraphRepository
import com.achulkov.evomolior.substrate.worker.WorkerExecutionRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Canonical Section 18.2 collector backed only by typed run authority.
 *
 * Dashboard projections and progress text are intentionally absent from this dependency graph.
 *
 * @param databaseFactory Exact per-run persistence authority.
 */
class RepositoryRunMetricCollector(
    private val databaseFactory: SubstrateDatabaseFactory,
) : RunMetricCollector {
    private val atlasRepository = AtlasTransactionRepository(databaseFactory)
    private val captureRepository = NodeCaptureRepository(databaseFactory)
    private val graderRepository = GraderAuthorityRepository(databaseFactory)
    private val scoreReplayStore = ScoreReplayAuthorityV1Store(databaseFactory)
    private val json = Json { ignoreUnknownKeys = false }

    override suspend fun collect(
        metricId: String,
        context: RunMetricsExportContext,
    ): RunMetricCollectionResult = when (metricId) {
        "useful_exit_rate" -> usefulExitRate(context)
        "atomic_node_completion_rate" -> atomicNodeCompletionRate(context)
        "mandatory_node_coverage" -> mandatoryNodeCoverage(context)
        "group_frontier_overlap" -> groupFrontierOverlap(context)
        "unplanned_redundancy_cost" -> unplannedRedundancyCost(context)
        "outlier_proposal_acceptance" -> outlierProposalAcceptance(context)
        "outlier_downstream_adoption" -> outlierDownstreamAdoption(context)
        "tool_native_agent_rate" -> toolNativeAgentRate(context)
        "tool_reuse_factor" -> toolReuseFactor(context)
        "simulation_influence_rate" -> simulationInfluenceRate(context)
        "kg_syntactic_acceptance" -> kgSyntacticAcceptance(context)
        "claim_grounding_coverage" -> claimGroundingCoverage(context)
        "grader_bundle_validity" -> graderBundleValidity(context)
        "grader_requirement_novelty" -> graderRequirementNovelty(context)
        "grader_calibration" -> graderCalibration(context)
        "evaluation_independence" -> evaluationIndependence(context)
        "capture_concentration" -> captureConcentration(context)
        "reward_usefulness" -> rewardUsefulness(context)
        "node_to_final_lineage" -> nodeToFinalLineage(context)
        "provider_cost_per_accepted_node" -> providerCostPerAcceptedNode(context)
        "score_replay_error" -> scoreReplayError(context)
        else -> error("Unsupported run metric collector ID: $metricId")
    }

    private suspend fun usefulExitRate(
        context: RunMetricsExportContext,
    ): RunMetricCollectionResult {
        val exits = context.workerGroupIds.flatMap { groupId ->
            WorkerExecutionRepository(databaseFactory.get(context.runId, groupId))
                .exits(context.runId, groupId)
        }
        return ratio(
            metricId = "useful_exit_rate",
            numerator = exits.count { it.useful },
            denominator = exits.size,
            unit = "ratio",
            evidenceRefs = exits.map { "worker-exit/${it.exitId}" }
                .ifEmpty { listOf("worker-exit-scope/${context.runId}") },
            zeroReason = "No ordinary mortal exits were recorded",
        )
    }

    private suspend fun atomicNodeCompletionRate(
        context: RunMetricsExportContext,
    ): RunMetricCollectionResult {
        val atlas = currentAtlas(context) ?: return sourceUnavailable(
            "atomic_node_completion_rate",
            "Worker Atlas authority is unavailable",
            listOf("worker-atlas/${context.atlasId}"),
        )
        val portfolios = portfolios(context)
        val attemptedNodeIds = portfolios.flatMap { it.bids }.mapTo(linkedSetOf()) { it.nodeId }
        val states = atlasRepository.currentStates(context.runId, context.atlasId)
        val closed = attemptedNodeIds.count { nodeId ->
            val state = states[nodeId]
            state?.captureState in setOf(CaptureState.CAPTURED, CaptureState.SHARED) &&
                state?.grades?.any { it.verdict == GradeVerdict.VALID_PASS } == true
        }
        return ratio(
            "atomic_node_completion_rate",
            closed,
            attemptedNodeIds.size,
            "ratio",
            portfolioEvidence(portfolios) + listOf("worker-atlas/${atlas.atlasHash}"),
            "No Atlas nodes were attempted",
        )
    }

    private suspend fun mandatoryNodeCoverage(
        context: RunMetricsExportContext,
    ): RunMetricCollectionResult {
        val atlas = currentAtlas(context) ?: return sourceUnavailable(
            "mandatory_node_coverage",
            "Worker Atlas authority is unavailable",
            listOf("worker-atlas/${context.atlasId}"),
        )
        val states = atlasRepository.currentStates(context.runId, context.atlasId)
        val mandatory = atlas.stageDefinitions.flatMapTo(linkedSetOf()) { it.mandatoryNodeIds }
        val revealed = mandatory.filterTo(linkedSetOf()) { nodeId ->
            states[nodeId]?.revealedToGroups?.isNotEmpty() == true
        }
        val aboveThreshold = revealed.count { nodeId ->
            val minimum = atlas.nodes.getValue(nodeId).capturePolicy.minimumGrade
            states.getValue(nodeId).grades.any { grade ->
                grade.verdict == GradeVerdict.VALID_PASS && grade.normalizedScore >= minimum
            }
        }
        val disclosureEvidence = revealed.flatMap { nodeId ->
            states.getValue(nodeId).disclosureLineage.map {
                "disclosure/${it.authorization.authorizationId}"
            }
        }
        return ratio(
            "mandatory_node_coverage",
            aboveThreshold,
            revealed.size,
            "ratio",
            disclosureEvidence + listOf("worker-atlas/${atlas.atlasHash}"),
            "No mandatory nodes were revealed",
        )
    }

    private suspend fun groupFrontierOverlap(
        context: RunMetricsExportContext,
    ): RunMetricCollectionResult {
        if (context.workerGroupIds.size < 2) {
            return notApplicable(
                "group_frontier_overlap",
                "Fewer than two worker groups participated",
                listOf("run-topology/${context.runId}"),
                "jaccard-ratio",
            )
        }
        val portfolios = portfolios(context)
        val expectedRounds = 0..context.completedThroughRound
        val byRoundAndGroup = portfolios.associateBy { it.round to it.groupId.value }
        val missing = expectedRounds.flatMap { round ->
            context.workerGroupIds.mapNotNull { groupId ->
                (round to groupId).takeIf { it !in byRoundAndGroup }
            }
        }
        if (missing.isNotEmpty()) {
            return sourceUnavailable(
                "group_frontier_overlap",
                "One or more completed-round NodePortfolio authorities are unavailable",
                portfolioEvidence(portfolios) + listOf("run-topology/${context.runId}"),
                unit = "jaccard-ratio",
            )
        }
        var intersections = 0
        var unions = 0
        expectedRounds.forEach { round ->
            context.workerGroupIds.forEachIndexed { index, leftGroup ->
                context.workerGroupIds.drop(index + 1).forEach { rightGroup ->
                    val left = byRoundAndGroup.getValue(round to leftGroup)
                        .bids.mapTo(linkedSetOf()) { it.nodeId }
                    val right = byRoundAndGroup.getValue(round to rightGroup)
                        .bids.mapTo(linkedSetOf()) { it.nodeId }
                    intersections += left.intersect(right).size
                    unions += left.union(right).size
                }
            }
        }
        return ratio(
            "group_frontier_overlap",
            intersections,
            unions,
            "micro-jaccard-ratio",
            portfolioEvidence(portfolios),
            "Group portfolios had no selected frontier nodes",
        )
    }

    private suspend fun unplannedRedundancyCost(
        context: RunMetricsExportContext,
    ): RunMetricCollectionResult {
        val atlas = currentAtlas(context) ?: return sourceUnavailable(
            "unplanned_redundancy_cost",
            "Worker Atlas authority is unavailable",
            listOf("worker-atlas/${context.atlasId}"),
            unit = "credits",
        )
        val portfolios = portfolios(context)
        val overlapping = portfolios
            .flatMap { portfolio ->
                portfolio.bids.map { bid -> (portfolio.round to bid.nodeId) to portfolio.groupId }
            }
            .groupBy({ it.first }, { it.second })
            .filterValues { groups -> groups.distinct().size > 1 }
            .keys
            .filterTo(linkedSetOf()) { (_, nodeId) ->
                atlas.nodes.getValue(nodeId).replicationPolicy.mode == ReplicationMode.NONE
            }
        val packets = captureRepository.evidencePacketsForRun(context.runId)
        val duplicatedPackets = packets.filter { (it.round to it.nodeId) in overlapping }
        val cost = duplicatedPackets.sumOf { it.cost.totalCredits }
        return available(
            "unplanned_redundancy_cost",
            numerator = duplicatedPackets.size.toDouble(),
            value = cost,
            unit = "credits",
            evidenceRefs = portfolioEvidence(portfolios) +
                duplicatedPackets.map { "node-evidence/${it.packetHash}" } +
                listOf("worker-atlas/${atlas.atlasHash}"),
        )
    }

    private suspend fun outlierProposalAcceptance(
        context: RunMetricsExportContext,
    ): RunMetricCollectionResult {
        val repository = OutlierNoveltyRepository(databaseFactory.getAtlas(context.runId))
        val proposals = repository.proposalsForRun(context.runId)
        val accepted = proposals.count { proposal ->
            repository.verdictForProposal(proposal.proposalHash)?.decision ==
                ShadowNodeDecision.ACCEPT
        }
        return ratio(
            "outlier_proposal_acceptance",
            accepted,
            proposals.size,
            "ratio",
            proposals.map { "outlier-proposal/${it.proposalHash}" }
                .ifEmpty { listOf("outlier-proposal-scope/${context.runId}") },
            "No Outlier proposals were submitted",
        )
    }

    private suspend fun outlierDownstreamAdoption(
        context: RunMetricsExportContext,
    ): RunMetricCollectionResult {
        val repository = OutlierNoveltyRepository(databaseFactory.getAtlas(context.runId))
        val proposals = repository.proposalsForRun(context.runId)
        val accepted = proposals.filter { proposal ->
            repository.verdictForProposal(proposal.proposalHash)?.decision ==
                ShadowNodeDecision.ACCEPT
        }
        val adopted = accepted.count { proposal ->
            repository.downstreamLineage(proposal.proposalId).any {
                it.outcome in setOf(
                    OutlierDownstreamOutcome.TESTED,
                    OutlierDownstreamOutcome.ADOPTED,
                )
            }
        }
        val evidence = accepted.flatMap { proposal ->
            repository.downstreamLineage(proposal.proposalId)
                .map { "outlier-lineage/${it.lineageHash}" }
        } + proposals.map { "outlier-proposal/${it.proposalHash}" }
        return ratio(
            "outlier_downstream_adoption",
            adopted,
            accepted.size,
            "ratio",
            evidence.ifEmpty { listOf("outlier-proposal-scope/${context.runId}") },
            "No Outlier proposals were accepted",
        )
    }

    private suspend fun toolNativeAgentRate(
        context: RunMetricsExportContext,
    ): RunMetricCollectionResult {
        val atlas = currentAtlas(context) ?: return sourceUnavailable(
            "tool_native_agent_rate",
            "Worker Atlas authority is unavailable",
            listOf("worker-atlas/${context.atlasId}"),
            unit = "ratio",
        )
        val states = atlasRepository.currentStates(context.runId, context.atlasId)
        val eligibleAssignments = states.values.flatMap { state ->
            if (atlas.nodes.getValue(state.nodeId).toolOpportunity == null) {
                emptyList()
            } else {
                state.leases.mapNotNull { lease ->
                    lease.agentId?.let { it to state.nodeId }
                }
            }
        }.toSet()
        val executions = context.workerGroupIds.flatMap { groupId ->
            ToolInfrastructureRepository(databaseFactory.get(context.runId, groupId))
                .executionsForGroupThroughRound(
                    context.runId,
                    groupId,
                    context.completedThroughRound.coerceAtLeast(0),
                )
        }.filter {
            it.purpose == ToolExecutionPurpose.AGENT_USE &&
                it.result == ToolExecutionResult.SUCCEEDED
        }
        val successfulAssignments = executions.mapTo(linkedSetOf()) { it.agentId to it.nodeId }
        val eligibleAgents = eligibleAssignments.mapTo(linkedSetOf()) { it.first }
        val successfulAgents = eligibleAssignments.intersect(successfulAssignments)
            .mapTo(linkedSetOf()) { it.first }
        val leaseEvidence = states.values.flatMap { state ->
            state.leases.filter { it.agentId != null }.map { "node-lease/${it.leaseId}" }
        }
        return ratio(
            "tool_native_agent_rate",
            successfulAgents.size,
            eligibleAgents.size,
            "ratio",
            leaseEvidence + executions.map { "tool-execution/${it.recordHash}" } +
                listOf("worker-atlas/${atlas.atlasHash}"),
            "No ordinary agents were eligible for intended tool use",
        )
    }

    private suspend fun toolReuseFactor(
        context: RunMetricsExportContext,
    ): RunMetricCollectionResult {
        val metrics = context.workerGroupIds.map { groupId ->
            ToolInfrastructureRepository(databaseFactory.get(context.runId, groupId))
                .metrics(context.runId, AtlasGroupId(groupId))
        }
        return ratio(
            "tool_reuse_factor",
            metrics.sumOf { it.laterExecutionCount },
            metrics.sumOf { it.promotedVersionCount },
            "later-executions-per-promoted-tool",
            context.workerGroupIds.map { "tool-infrastructure/${context.runId}/$it" },
            "No promoted tool versions were available",
        )
    }

    private suspend fun simulationInfluenceRate(
        context: RunMetricsExportContext,
    ): RunMetricCollectionResult {
        val atlas = currentAtlas(context) ?: return sourceUnavailable(
            "simulation_influence_rate",
            "Worker Atlas authority is unavailable",
            listOf("worker-atlas/${context.atlasId}"),
            unit = "ratio",
        )
        val simulations = context.workerGroupIds.flatMap { groupId ->
            ToolInfrastructureRepository(databaseFactory.get(context.runId, groupId))
                .simulationsForRun(context.runId)
        }
        val simulationExecutionIds = simulations.mapTo(linkedSetOf()) { it.executionId }
        val packetsByHash = captureRepository.evidencePacketsForRun(context.runId)
            .associateBy(NodeEvidencePacket::packetHash)
        val bundlesByHash = captureRepository.gradeBundlesForRun(context.runId)
            .associateBy { it.bundleHash }
        val decisions = captureRepository.captureDecisionsForRun(context.runId)
            .filter { decision ->
                atlas.nodes[decision.nodeId]?.toolOpportunity?.simulationRecordBindings != null
            }
        val influenced = decisions.count { decision ->
            decision.authoritativeBundleHashes.any { bundleHash ->
                val packetHash = bundlesByHash[bundleHash]?.evidencePacketHash
                packetHash != null &&
                    packetsByHash[packetHash]?.toolRunIds?.any(simulationExecutionIds::contains) ==
                    true
            }
        }
        return ratio(
            "simulation_influence_rate",
            influenced,
            decisions.size,
            "ratio",
            simulations.map { "simulation/${it.recordHash}" } +
                decisions.map { "capture-decision/${it.decisionHash}" } +
                listOf("worker-atlas/${atlas.atlasHash}"),
            "No simulation-eligible node decisions were recorded",
        )
    }

    private suspend fun kgSyntacticAcceptance(
        context: RunMetricsExportContext,
    ): RunMetricCollectionResult {
        val attempts = context.workerGroupIds.flatMap { groupId ->
            KgFactAttemptRepository(databaseFactory.get(context.runId, groupId))
                .forGroup(context.runId, groupId)
        }
        return ratio(
            "kg_syntactic_acceptance",
            attempts.count { it.status == KgFactAttemptStatus.SCHEMA_RESOLVED },
            attempts.size,
            "ratio",
            attempts.map { "kg-fact-attempt/${it.recordHash}" }
                .ifEmpty { listOf("kg-fact-attempt-scope/${context.runId}") },
            "No typed KG fact attempts were submitted",
        )
    }

    private fun claimGroundingCoverage(
        context: RunMetricsExportContext,
    ): RunMetricCollectionResult {
        val contract = context.finalOutputSpec
            ?.validation
            ?.researchClaimSupportCoverage
            ?: return notApplicable(
                "claim_grounding_coverage",
                "The final output contract does not declare claim-support coverage",
                listOf("output-contract/${context.runId}"),
                "ratio",
            )
        val ledger = context.claimLedgerJsonl ?: return missingInfrastructure(
            "claim_grounding_coverage",
            "The declared claim-support contract has no exact claim-ledger authority",
            listOf("measurement-infrastructure/claim-grounding/${context.runId}"),
            "ratio",
        )
        val claims = mutableListOf<JsonObject>()
        ledger.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEachIndexed { index, line ->
                val claim = runCatching {
                    json.parseToJsonElement(line) as? JsonObject
                }.getOrNull() ?: return sourceUnavailable(
                    "claim_grounding_coverage",
                    "Claim ledger line ${index + 1} is malformed",
                    listOf("claim-ledger/${sha256Hex(ledger.toByteArray())}"),
                    unit = "ratio",
                )
                claims += claim
            }
        val eligible = if (contract.usedInFinalAnswerOnly) {
            claims.filter { claim ->
                (claim["used_in_final_answer"] as? JsonPrimitive)?.booleanOrNull == true
            }
        } else {
            claims
        }
        val supported = eligible.count { claim ->
            (claim["supporting_sources"] as? JsonArray)
                ?.any { source ->
                    (source as? JsonPrimitive)?.content?.isNotBlank() == true
                } == true
        }
        return ratio(
            "claim_grounding_coverage",
            supported,
            eligible.size,
            "ratio",
            listOf("claim-ledger/${sha256Hex(ledger.toByteArray())}"),
            "The claim ledger has no eligible material claims",
        )
    }

    private suspend fun graderBundleValidity(
        context: RunMetricsExportContext,
    ): RunMetricCollectionResult {
        val freezes = graderRepository.criteriaFreezesForRun(context.runId)
        val bundles = graderRepository.scoreBundlesForRun(context.runId)
        val expected = freezes.sumOf { it.targetSnapshotHashes.size }
        val valid = bundles.count { it.status == GraderScoreBundleStatus.VALID }
        return ratio(
            "grader_bundle_validity",
            valid,
            expected,
            "ratio",
            freezes.map { "grader-freeze/${it.freezeHash}" } +
                bundles.map { "grader-bundle/${it.payloadHash}" },
            "No grader bundles were expected",
        )
    }

    private suspend fun graderRequirementNovelty(
        context: RunMetricsExportContext,
    ): RunMetricCollectionResult {
        val requirements = graderRepository.requirementsForRun(context.runId)
            .filter { it.isFitnessEligible }
        val unique = requirements.map { it.noveltyFingerprint }.distinct().size
        return available(
            "grader_requirement_novelty",
            numerator = unique.toDouble(),
            value = unique.toDouble(),
            unit = "unique-requirement-count",
            evidenceRefs = requirements.map { "grader-requirement/${it.requirementHash}" }
                .ifEmpty { listOf("grader-requirement-scope/${context.runId}") },
        )
    }

    private suspend fun graderCalibration(
        context: RunMetricsExportContext,
    ): RunMetricCollectionResult {
        val records = graderRepository.fitnessHistoryForRun(context.runId)
            .filter { it.plane == GraderFitnessPlane.JUDGMENT_QUALITY }
        val sum = records.sumOf { it.quality.calibration }
        return if (records.isEmpty()) {
            notApplicable(
                "grader_calibration",
                "No grader judgment-quality calibration observations were eligible",
                listOf("grader-fitness-scope/${context.runId}"),
                "mean-anchor-agreement",
            )
        } else {
            available(
                "grader_calibration",
                numerator = sum,
                denominator = records.size.toDouble(),
                value = sum / records.size,
                unit = "mean-anchor-agreement",
                evidenceRefs = records.map { "grader-fitness/${it.recordHash}" },
            )
        }
    }

    private suspend fun evaluationIndependence(
        context: RunMetricsExportContext,
    ): RunMetricCollectionResult {
        val profile = graderRepository.currentIndependenceProfile(context.runId)
            ?: return sourceUnavailable(
                "evaluation_independence",
                "Evaluation-independence profile authority is unavailable",
                listOf("evaluation-independence/${context.runId}"),
                unit = "profile-compatible",
            )
        if (profile.distinctProviders <= 0) {
            return sourceUnavailable(
                "evaluation_independence",
                "Evaluation-independence profile has no provider-count authority",
                listOf("evaluation-independence/${profile.profileHash}"),
                numerator = profile.distinctModelFamilies.toDouble(),
                unit = "model-families-per-provider;label=${profile.independenceLabel.name}",
            )
        }
        return available(
            "evaluation_independence",
            numerator = profile.distinctModelFamilies.toDouble(),
            denominator = profile.distinctProviders.toDouble(),
            value = profile.distinctModelFamilies.toDouble() / profile.distinctProviders,
            unit = "model-families-per-provider;label=${profile.independenceLabel.name}",
            evidenceRefs = listOf("evaluation-independence/${profile.profileHash}"),
        )
    }

    private suspend fun captureConcentration(
        context: RunMetricsExportContext,
    ): RunMetricCollectionResult {
        val atlas = currentAtlas(context) ?: return sourceUnavailable(
            "capture_concentration",
            "Worker Atlas authority is unavailable",
            listOf("worker-atlas/${context.atlasId}"),
            unit = "ratio",
        )
        val latest = captureRepository.captureDecisionsForRun(context.runId)
            .groupBy(NodeCaptureDecision::nodeId)
            .mapValues { (_, decisions) -> decisions.maxBy { it.round } }
            .values
            .filter {
                it.yieldEligible &&
                    it.resultingCaptureState in
                    setOf(CaptureState.CAPTURED, CaptureState.SHARED) &&
                    it.resultingOwnerGroupIds.isNotEmpty()
            }
        val yieldByGroup = linkedMapOf<AtlasGroupId, Double>()
        var totalYield = 0.0
        latest.forEach { decision ->
            val credits = atlas.nodes.getValue(decision.nodeId).rewardSpec.credits
            totalYield += credits
            val perOwner = credits / decision.resultingOwnerGroupIds.size
            decision.resultingOwnerGroupIds.forEach { groupId ->
                yieldByGroup.merge(groupId, perOwner, Double::plus)
            }
        }
        return if (totalYield == 0.0) {
            notApplicable(
                "capture_concentration",
                "No active captured yield was controlled by a worker group",
                latest.map { "capture-decision/${it.decisionHash}" }
                    .ifEmpty { listOf("capture-decision-scope/${context.runId}") },
                "ratio",
            )
        } else {
            val top = yieldByGroup.values.maxOrNull() ?: 0.0
            available(
                "capture_concentration",
                numerator = top,
                denominator = totalYield,
                value = top / totalYield,
                unit = "top-group-yield-share",
                evidenceRefs = latest.map { "capture-decision/${it.decisionHash}" } +
                    listOf("worker-atlas/${atlas.atlasHash}"),
            )
        }
    }

    private suspend fun rewardUsefulness(
        context: RunMetricsExportContext,
    ): RunMetricCollectionResult {
        val events = captureRepository.rewardUseEventsForRun(context.runId)
        val acceptedArtifactIds = context.workerGroupIds.flatMap { groupId ->
            ContributionGraphRepository(
                databaseFactory.get(context.runId, groupId),
                databaseFactory.getAtlas(context.runId),
            ).acceptedArtifactAuthorityIdsForRun(context.runId, groupId)
        }.toSet()
        val (useful, spent) = rewardUsefulnessCounts(events, acceptedArtifactIds)
        return ratio(
            "reward_usefulness",
            useful,
            spent,
            "ratio",
            events.map { "reward-use/${it.eventHash}" }
                .ifEmpty { listOf("reward-use-scope/${context.runId}") },
            "No instrumental rewards were spent",
        )
    }

    private suspend fun nodeToFinalLineage(
        context: RunMetricsExportContext,
    ): RunMetricCollectionResult {
        val atlas = currentAtlas(context) ?: return sourceUnavailable(
            "node_to_final_lineage",
            "Worker Atlas authority is unavailable",
            listOf("worker-atlas/${context.atlasId}"),
            unit = "ratio",
        )
        val receiverContract = atlasRepository.currentReceiverContract(
            context.runId,
            context.atlasId,
        ) ?: return sourceUnavailable(
            "node_to_final_lineage",
            "Receiver contract authority is unavailable",
            listOf("worker-atlas/${atlas.atlasHash}"),
            unit = "ratio",
        )
        val references = context.workerGroupIds.flatMap { groupId ->
            ContributionGraphRepository(
                databaseFactory.get(context.runId, groupId),
                databaseFactory.getAtlas(context.runId),
            ).finalDeliverableReferencesForRun(context.runId, groupId)
        }.distinctBy { it.referenceId }
        val requiredComponents = receiverContract.finalDeliverableContract.requiredSections
            .toList()
            .sorted()
        val finalComponents = if (requiredComponents.isNotEmpty()) {
            requiredComponents
        } else {
            val completion = context.completionRecord ?: return sourceUnavailable(
                "node_to_final_lineage",
                "Run-completion authority is unavailable for the final-component denominator",
                references.map { "final-reference/${it.recordHash}" }
                    .ifEmpty { listOf("run-completion/${context.runId}") },
                unit = "ratio",
            )
            completion.generatedOutputFiles.map { it.relativePath }.distinct().sorted()
        }
        if (finalComponents.isEmpty()) {
            return notApplicable(
                "node_to_final_lineage",
                "No final output components were declared",
                listOf("worker-atlas/${atlas.atlasHash}"),
                "ratio",
            )
        }
        val referencesByComponent = references.groupBy { reference ->
            reference.componentId
                ?: reference.relativePath.takeIf { requiredComponents.isEmpty() }
        }
        val missingComponentAuthority = finalComponents.filter { componentId ->
            referencesByComponent[componentId].orEmpty().none { it.nodeId == null }
        }
        if (missingComponentAuthority.isNotEmpty()) {
            return missingInfrastructure(
                "node_to_final_lineage",
                "Required final components lack exact component-level lineage authority: " +
                    missingComponentAuthority.joinToString(),
                references.map { "final-reference/${it.recordHash}" } +
                    listOf("worker-atlas/${atlas.atlasHash}"),
                "ratio",
            )
        }
        val acceptedDecisions = acceptedNodeDecisions(context)
        val repositories = context.workerGroupIds.associateWith { groupId ->
            ContributionGraphRepository(
                databaseFactory.get(context.runId, groupId),
                databaseFactory.getAtlas(context.runId),
            )
        }
        var backedComponents = 0
        finalComponents.forEach { componentId ->
            val hasAcceptedAncestry = referencesByComponent[componentId].orEmpty()
                .filter { it.nodeId != null }
                .any { reference ->
                    repositories[reference.groupId.value]?.isAcceptedFinalNodeSource(
                        runId = context.runId,
                        groupId = reference.groupId.value,
                        nodeId = requireNotNull(reference.nodeId),
                        source = reference.sourceAuthority,
                    ) == true
                }
            if (hasAcceptedAncestry) {
                backedComponents += 1
            }
        }
        return ratio(
            "node_to_final_lineage",
            backedComponents,
            finalComponents.size,
            "ratio",
            references.map { "final-reference/${it.recordHash}" } +
                acceptedDecisions.map { "capture-decision/${it.decisionHash}" } +
                listOf("worker-atlas/${atlas.atlasHash}"),
            "No final output components were declared",
        )
    }

    private suspend fun providerCostPerAcceptedNode(
        context: RunMetricsExportContext,
    ): RunMetricCollectionResult {
        val acceptedNodes = acceptedNodeDecisions(context)
        if (acceptedNodes.isEmpty()) {
            return notApplicable(
                "provider_cost_per_accepted_node",
                "No nodes were accepted by capture authority",
                listOf("capture-decision-scope/${context.runId}"),
                "usd-per-accepted-node",
            )
        }
        val entries = CostLedgerRepository(
            databaseFactory.get(context.runId, COST_LEDGER_GROUP_ID),
        ).entriesForRun(context.runId)
        val finalized = entries.filter { it.kind == CostLedgerEntryKind.FINALIZED }
        val evidence = finalized.map { "cost-ledger/${it.entryHash}" } +
            acceptedNodes.map { "capture-decision/${it.decisionHash}" }
        if (finalized.isEmpty()) {
            return sourceUnavailable(
                "provider_cost_per_accepted_node",
                "No finalized provider or tool cost authority is available",
                evidence.ifEmpty { listOf("cost-ledger-scope/${context.runId}") },
                denominator = acceptedNodes.size.toDouble(),
                unit = "usd-per-accepted-node",
            )
        }
        val providerUnknown = finalized.any {
            it.subjectType == CostSubjectType.LLM_PROVIDER_CALL &&
                it.providerCost.status == MonetaryAmountStatus.UNKNOWN
        }
        if (providerUnknown) {
            return unavailable(
                "provider_cost_per_accepted_node",
                RunMetricUnavailableCause.PROVIDER_PRICE_UNAVAILABLE,
                "One or more finalized provider calls have unavailable pricing",
                evidence,
                denominator = acceptedNodes.size.toDouble(),
                unit = "usd-per-accepted-node",
            )
        }
        val otherUnknown = finalized.any { it.knownTotalUsd() == null }
        if (otherUnknown) {
            return sourceUnavailable(
                "provider_cost_per_accepted_node",
                "One or more finalized tool or simulation costs are unavailable",
                evidence,
                denominator = acceptedNodes.size.toDouble(),
                unit = "usd-per-accepted-node",
            )
        }
        val total = finalized.sumOf { requireNotNull(it.knownTotalUsd()) }
        return available(
            "provider_cost_per_accepted_node",
            numerator = total,
            denominator = acceptedNodes.size.toDouble(),
            value = total / acceptedNodes.size,
            unit = "usd-per-accepted-node",
            evidenceRefs = evidence,
        )
    }

    private fun scoreReplayError(
        context: RunMetricsExportContext,
    ): RunMetricCollectionResult {
        if (context.graderGroupIds.isEmpty()) {
            return notApplicable(
                "score_replay_error",
                "No competitive grader groups participated",
                listOf("run-topology/${context.runId}"),
                "maximum-absolute-error",
            )
        }
        val authority = scoreReplayStore.read(context.runId)
            ?: return missingInfrastructure(
                "score_replay_error",
                "No isolated score-replay authority was persisted",
                listOf("measurement-infrastructure/score-replay/${context.runId}"),
                "maximum-absolute-error",
            )
        val expectedRounds = (0..context.completedThroughRound).toSet()
        val observedRounds = authority.observations.mapTo(linkedSetOf()) { it.round }
        if (observedRounds != expectedRounds) {
            return missingInfrastructure(
                "score_replay_error",
                "Score-replay authority does not cover every completed round",
                authority.observations.flatMap(::scoreReplayEvidence),
                "maximum-absolute-error",
            )
        }
        val maximum = authority.observations.maxOfOrNull { it.maximumAbsoluteError } ?: 0.0
        return available(
            "score_replay_error",
            numerator = authority.observations.sumOf { it.maximumAbsoluteError },
            denominator = authority.observations.size.toDouble(),
            value = maximum,
            unit = "maximum-absolute-error",
            evidenceRefs = authority.observations.flatMap(::scoreReplayEvidence),
        )
    }

    private suspend fun currentAtlas(context: RunMetricsExportContext) =
        atlasRepository.currentAtlas(context.runId, context.atlasId)

    private suspend fun portfolios(context: RunMetricsExportContext) =
        context.workerGroupIds.flatMap { groupId ->
            WorkerExecutionRepository(databaseFactory.get(context.runId, groupId))
                .nodePortfolios(context.runId, groupId)
        }

    private suspend fun acceptedNodeDecisions(
        context: RunMetricsExportContext,
    ): List<NodeCaptureDecision> =
        captureRepository.captureDecisionsForRun(context.runId)
            .groupBy(NodeCaptureDecision::nodeId)
            .mapValues { (_, decisions) -> decisions.maxBy { it.round } }
            .values
            .filter {
                it.decisionType in setOf(
                    NodeCaptureDecisionType.CAPTURED,
                    NodeCaptureDecisionType.RECAPTURED,
                    NodeCaptureDecisionType.SHARED_VALIDATION_CREDIT,
                ) &&
                    it.resultingCaptureState in
                    setOf(CaptureState.CAPTURED, CaptureState.SHARED)
            }

    private fun ratio(
        metricId: String,
        numerator: Int,
        denominator: Int,
        unit: String,
        evidenceRefs: List<String>,
        zeroReason: String,
    ): RunMetricCollectionResult =
        if (denominator == 0) {
            notApplicable(metricId, zeroReason, evidenceRefs, unit)
        } else {
            available(
                metricId,
                numerator = numerator.toDouble(),
                denominator = denominator.toDouble(),
                value = numerator.toDouble() / denominator,
                unit = unit,
                evidenceRefs = evidenceRefs,
            )
        }

    private fun available(
        metricId: String,
        numerator: Double? = null,
        denominator: Double? = null,
        value: Double,
        unit: String?,
        evidenceRefs: List<String>,
    ) = RunMetricCollectionResult.Available(
        metricId = metricId,
        numerator = numerator,
        denominator = denominator,
        value = value,
        unit = unit,
        evidenceRefs = evidence(evidenceRefs, metricId),
    )

    private fun sourceUnavailable(
        metricId: String,
        reason: String,
        evidenceRefs: List<String>,
        numerator: Double? = null,
        denominator: Double? = null,
        unit: String? = null,
    ) = unavailable(
        metricId,
        RunMetricUnavailableCause.SOURCE_AUTHORITY_UNAVAILABLE,
        reason,
        evidenceRefs,
        numerator,
        denominator,
        unit,
    )

    private fun missingInfrastructure(
        metricId: String,
        reason: String,
        evidenceRefs: List<String>,
        unit: String? = null,
    ) = unavailable(
        metricId,
        RunMetricUnavailableCause.MISSING_MEASUREMENT_INFRASTRUCTURE,
        reason,
        evidenceRefs,
        unit = unit,
    )

    private fun unavailable(
        metricId: String,
        cause: RunMetricUnavailableCause,
        reason: String,
        evidenceRefs: List<String>,
        numerator: Double? = null,
        denominator: Double? = null,
        unit: String? = null,
    ) = RunMetricCollectionResult.Unavailable(
        metricId = metricId,
        cause = cause,
        reason = reason,
        numerator = numerator,
        denominator = denominator,
        unit = unit,
        evidenceRefs = evidence(evidenceRefs, metricId),
    )

    private fun notApplicable(
        metricId: String,
        reason: String,
        evidenceRefs: List<String>,
        unit: String? = null,
    ) = RunMetricCollectionResult.NotApplicable(
        metricId = metricId,
        reason = reason,
        unit = unit,
        evidenceRefs = evidence(evidenceRefs, metricId),
    )

    private fun evidence(
        refs: List<String>,
        metricId: String,
    ): List<String> = refs
        .filter(String::isNotBlank)
        .distinct()
        .sorted()
        .ifEmpty { listOf("metric-scope/$metricId") }

    private fun portfolioEvidence(
        portfolios: List<com.achulkov.evomolior.core.contract.NodePortfolio>,
    ): List<String> = portfolios.map { "node-portfolio/${it.portfolioHash}" }

    private fun scoreReplayEvidence(
        observation: ScoreReplayObservationV1,
    ): List<String> = listOf(
        "score-live/${observation.liveVerdictSha256}",
        "score-replay/${observation.replayVerdictSha256}",
    )

    private companion object {
        const val COST_LEDGER_GROUP_ID = "__cost_ledger__"
    }
}

internal fun rewardUsefulnessCounts(
    events: List<RewardUseLineageEvent>,
    acceptedArtifactIds: Set<String>,
): Pair<Int, Int> {
    val spends = events.filter { it.amountSpent > 0.0 }
    val childrenByParent = events.filter { it.parentEventHash != null }
        .groupBy { it.parentEventHash }
    val useful = spends.count { spend ->
        childrenByParent[spend.eventHash].orEmpty().any { child ->
            child.downstreamArtifactIds.any(acceptedArtifactIds::contains)
        }
    }
    return useful to spends.size
}
