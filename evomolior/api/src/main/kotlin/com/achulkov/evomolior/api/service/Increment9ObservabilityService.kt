package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.atlas.CaptureState
import com.achulkov.evomolior.core.atlas.ToolVersionId
import com.achulkov.evomolior.core.contract.CostLedgerPolicy
import com.achulkov.evomolior.core.contract.CostLedgerSummary
import com.achulkov.evomolior.core.grader.EvaluationIndependenceProfile
import com.achulkov.evomolior.core.grader.GraderScoreBundleStatus
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.atlas.AtlasTransactionRepository
import com.achulkov.evomolior.substrate.cost.CostLedgerRepository
import com.achulkov.evomolior.substrate.grader.GraderAuthorityRepository
import com.achulkov.evomolior.substrate.publication.PublicationStateRepository
import com.achulkov.evomolior.substrate.tools.ToolInfrastructureRepository
import com.achulkov.evomolior.substrate.worker.ContributionGraphRepository
import com.achulkov.evomolior.substrate.worker.WorkerExecutionRepository
import kotlinx.serialization.Serializable

/** Exact ratio whose value remains unavailable when its denominator is zero. */
@Serializable
data class AvailableRatio(
    val numerator: Long,
    val denominator: Long,
    val value: Double?
) {
    init {
        require(numerator >= 0L && denominator >= 0L && numerator <= denominator) {
            "Ratio counts must satisfy 0 <= numerator <= denominator"
        }
        require((denominator == 0L) == (value == null)) {
            "A zero-denominator ratio must be unavailable"
        }
    }

    companion object {
        /** Constructs a ratio without converting an unavailable denominator to zero. */
        fun of(numerator: Long, denominator: Long): AvailableRatio =
            AvailableRatio(
                numerator,
                denominator,
                denominator.takeIf { it > 0L }?.let { numerator.toDouble() / it }
            )
    }
}

/** Read-only exact worker-Atlas projection. */
@Serializable
data class NodeAtlasDashboard(
    val atlasId: String,
    val atlasVersion: Int,
    val atlasHash: String,
    val nodeCount: Int,
    val mandatoryNodeCount: Int,
    val revealedMandatoryCoverage: AvailableRatio,
    val capturedMandatoryCoverage: AvailableRatio,
    val contestedNodeCount: Int,
    val captureAuthorityIds: List<String>
)

/** Read-only exact competitive-grader projection. */
@Serializable
data class GraderAtlasDashboard(
    val graderAtlasId: String?,
    val graderAtlasVersion: Int?,
    val graderAtlasHash: String?,
    val graderNodeCount: Int,
    val validBundleCount: Int,
    val invalidOrAbstainedBundleCount: Int,
    val comparedNodeCount: Int,
    val meanNodeVectorDisagreement: Double?,
    val independenceProfile: EvaluationIndependenceProfile?
)

/** Exact promoted-tool execution and later-node reuse projection. */
@Serializable
data class ToolObservability(
    val groupId: String,
    val activeToolVersionCount: Int,
    val executionCount: Int,
    val successfulLaterNodeReuseCount: Int,
    val reusedToolVersionCount: Int,
    val executionAuthorityIds: List<String>,
    val reuseAuthorityIds: List<String>
)

/** Exact useful-exit projection which leaves an empty denominator unavailable. */
@Serializable
data class UsefulExitObservability(
    val groupId: String,
    val usefulExitCount: Int,
    val ordinaryExitCount: Int,
    val usefulExitRate: Double?
)

/** Configured-versus-activated status for one society mechanism. */
@Serializable
data class MechanismActivation(
    val mechanismId: String,
    val configured: Boolean,
    val activated: Boolean,
    val authorityIds: List<String>
) {
    init {
        require(mechanismId.isNotBlank()) { "Mechanism ID must not be blank" }
        require(!activated || configured) { "An unconfigured mechanism cannot be activated" }
        require(authorityIds.none(String::isBlank)) { "Mechanism authority IDs must not be blank" }
    }
}

/** Complete read-only Increment 9 observability snapshot. */
@Serializable
data class Increment9ObservabilitySnapshot(
    val runId: String,
    val nodeAtlas: NodeAtlasDashboard,
    val graderAtlas: GraderAtlasDashboard,
    val tools: List<ToolObservability>,
    val usefulExits: List<UsefulExitObservability>,
    val cost: CostLedgerSummary,
    val mechanisms: List<MechanismActivation>
)

/**
 * Builds read-only dashboards and activation reports exclusively from immutable run authority.
 */
class Increment9ObservabilityService(
    private val databaseFactory: SubstrateDatabaseFactory
) {
    /** Returns one exact snapshot without mutating Atlas, grader, tool, or publication authority. */
    suspend fun snapshot(
        runId: String,
        groupIds: List<String>,
        atlasId: String = "atlas:$runId"
    ): Increment9ObservabilitySnapshot {
        require(runId.isNotBlank() && groupIds.isNotEmpty() && groupIds.none(String::isBlank)) {
            "Observability requires one run and at least one exact group"
        }
        val atlasRepository = AtlasTransactionRepository(databaseFactory)
        val atlas = requireNotNull(atlasRepository.currentAtlas(runId, atlasId)) {
            "Node Atlas is unavailable for observability"
        }
        val states = atlasRepository.currentStates(runId, atlasId)
        val mandatoryIds = atlas.stageDefinitions
            .flatMapTo(linkedSetOf()) { it.mandatoryNodeIds }
        val revealedMandatory = mandatoryIds.count { nodeId ->
            states[nodeId]?.revealedToGroups?.isNotEmpty() == true
        }
        val capturedMandatory = mandatoryIds.count { nodeId ->
            states[nodeId]?.captureState in setOf(CaptureState.CAPTURED, CaptureState.SHARED)
        }
        val graphByGroup = groupIds.associateWith { groupId ->
            ContributionGraphRepository(
                databaseFactory.get(runId, groupId),
                databaseFactory.getAtlas(runId)
            )
        }
        val captureAuthorityIds = graphByGroup.flatMap { (groupId, repository) ->
            repository.forRun(runId, groupId)
                .filter {
                    it.target.kind ==
                        com.achulkov.evomolior.core.contract.ContributionAuthorityKind
                            .NODE_CAPTURE_DECISION
                }
                .map { it.target.authorityId }
        }.distinct().sorted()
        val nodeDashboard = NodeAtlasDashboard(
            atlasId = atlas.atlasId,
            atlasVersion = atlas.version,
            atlasHash = atlas.atlasHash,
            nodeCount = atlas.nodes.size,
            mandatoryNodeCount = mandatoryIds.size,
            revealedMandatoryCoverage = AvailableRatio.of(
                revealedMandatory.toLong(),
                mandatoryIds.size.toLong()
            ),
            capturedMandatoryCoverage = AvailableRatio.of(
                capturedMandatory.toLong(),
                mandatoryIds.size.toLong()
            ),
            contestedNodeCount = states.values.count { it.captureState == CaptureState.CONTESTED },
            captureAuthorityIds = captureAuthorityIds
        )
        val maxRound = states.values.maxOfOrNull { it.round } ?: 0
        val graderRepository = GraderAuthorityRepository(databaseFactory)
        val graderAtlas = graderRepository.currentAtlas(runId)
        val bundles = (0..maxRound).flatMap { graderRepository.scoreBundlesForRound(runId, it) }
        val validBundles = bundles.filter { it.status == GraderScoreBundleStatus.VALID }
        val nodeVectors = validBundles.flatMap { bundle ->
            bundle.nodeScores.map { (nodeId, vector) ->
                Triple("${bundle.round}:${bundle.targetGroupId.value}:${nodeId.value}", bundle, vector)
            }
        }.groupBy { it.first }
        val disagreementValues = nodeVectors.values.mapNotNull { values ->
            val vectors = values.map { it.third }
            if (vectors.size < 2) {
                null
            } else {
                listOf(
                    vectors.map { it.correctness },
                    vectors.map { it.evidenceQuality },
                    vectors.map { it.reproducibility },
                    vectors.map { it.robustness },
                    vectors.map { it.integrationUtility },
                    vectors.map { it.receiverRelevance },
                    vectors.map { it.novelty },
                    vectors.map { it.efficiency }
                ).map { component -> component.max() - component.min() }.average()
            }
        }
        val graderDashboard = GraderAtlasDashboard(
            graderAtlasId = graderAtlas?.graderAtlasId,
            graderAtlasVersion = graderAtlas?.version,
            graderAtlasHash = graderAtlas?.atlasHash,
            graderNodeCount = graderAtlas?.nodes?.size ?: 0,
            validBundleCount = validBundles.size,
            invalidOrAbstainedBundleCount = bundles.size - validBundles.size,
            comparedNodeCount = disagreementValues.size,
            meanNodeVectorDisagreement = disagreementValues
                .takeIf(List<Double>::isNotEmpty)
                ?.average(),
            independenceProfile = graderRepository.currentIndependenceProfile(runId)
        )
        val toolDashboards = groupIds.map { groupId ->
            val database = databaseFactory.get(runId, groupId)
            val activeTools = database.toolRegistryDao().getActive(runId, groupId, null)
            val infrastructure = ToolInfrastructureRepository(database)
            val executions = activeTools.flatMap { tool ->
                infrastructure.executions(ToolVersionId(tool.id))
            }.distinctBy { it.executionId }
            val reuse = activeTools.flatMap { tool ->
                infrastructure.reuseForVersion(ToolVersionId(tool.id))
            }.distinctBy { it.lineageId }
            ToolObservability(
                groupId = groupId,
                activeToolVersionCount = activeTools.size,
                executionCount = executions.size,
                successfulLaterNodeReuseCount = reuse.size,
                reusedToolVersionCount = reuse.map { it.toolVersionId }.distinct().size,
                executionAuthorityIds = executions.map { it.executionId }.sorted(),
                reuseAuthorityIds = reuse.map { it.lineageId }.sorted()
            )
        }
        val usefulExitDashboards = groupIds.map { groupId ->
            val summary = WorkerExecutionRepository(databaseFactory.get(runId, groupId))
                .usefulExitSummary(runId, groupId)
            UsefulExitObservability(
                groupId = groupId,
                usefulExitCount = summary.usefulExitCount,
                ordinaryExitCount = summary.ordinaryExitCount,
                usefulExitRate = summary.ordinaryExitCount.takeIf { it > 0 }?.let {
                    summary.usefulExitCount.toDouble() / it
                }
            )
        }
        val cost = CostLedgerRepository(
            databaseFactory.get(runId, COST_LEDGER_GROUP_ID),
            CostLedgerPolicy()
        ).summary(runId)
        val publication = PublicationStateRepository(databaseFactory.getAtlas(runId)).history(runId)
        val controlIds = graphByGroup.flatMap { (groupId, repository) ->
            repository.controlInterventionsThroughRound(runId, groupId, maxRound)
                .map { it.interventionId }
        }.sorted()
        val summaryIds = graphByGroup.flatMap { (groupId, repository) ->
            repository.summaryValidationsThroughRound(runId, groupId, maxRound)
                .map { it.validationId }
        }.sorted()
        val toolReuseIds = toolDashboards.flatMap(ToolObservability::reuseAuthorityIds)
        val mechanisms = listOf(
            MechanismActivation(
                "progressive-disclosure",
                atlas.stageDefinitions.isNotEmpty(),
                states.values.any { it.disclosureLineage.isNotEmpty() },
                states.values.flatMap { it.disclosureLineage }
                    .map { it.authorization.authorizationId }
                    .sorted()
            ),
            MechanismActivation(
                "competitive-node-capture",
                atlas.nodes.isNotEmpty(),
                captureAuthorityIds.isNotEmpty(),
                captureAuthorityIds
            ),
            MechanismActivation(
                "promoted-tool-later-node-reuse",
                atlas.nodes.values.any { it.toolOpportunity != null },
                toolReuseIds.isNotEmpty(),
                toolReuseIds.sorted()
            ),
            MechanismActivation(
                "useful-exit-accounting",
                true,
                usefulExitDashboards.any { it.ordinaryExitCount > 0 },
                groupIds.flatMap { groupId ->
                    WorkerExecutionRepository(databaseFactory.get(runId, groupId))
                        .exits(runId, groupId)
                        .map { it.exitId }
                }.sorted()
            ),
            MechanismActivation(
                "typed-control-intervention",
                true,
                controlIds.isNotEmpty(),
                controlIds
            ),
            MechanismActivation(
                "summary-validation",
                true,
                summaryIds.isNotEmpty(),
                summaryIds
            ),
            MechanismActivation(
                "publication-barrier",
                true,
                publication.isNotEmpty(),
                publication.map { it.eventId }
            )
        )
        return Increment9ObservabilitySnapshot(
            runId,
            nodeDashboard,
            graderDashboard,
            toolDashboards,
            usefulExitDashboards,
            cost,
            mechanisms
        )
    }

    private companion object {
        const val COST_LEDGER_GROUP_ID = "__cost_ledger__"
    }
}
