@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.atlas.CaptureState
import com.achulkov.evomolior.core.atlas.ToolVersionId
import com.achulkov.evomolior.core.contract.CostLedgerPolicy
import com.achulkov.evomolior.core.contract.CostLedgerSummary
import com.achulkov.evomolior.core.contract.ContributionAuthorityKind
import com.achulkov.evomolior.core.contract.PublicationState
import com.achulkov.evomolior.core.contract.RoundPhaseV1
import com.achulkov.evomolior.core.grader.EvaluationIndependenceProfile
import com.achulkov.evomolior.core.grader.GraderScoreBundleStatus
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.atlas.AtlasTransactionRepository
import com.achulkov.evomolior.substrate.cost.CostLedgerRepository
import com.achulkov.evomolior.substrate.grader.GraderAuthorityRepository
import com.achulkov.evomolior.substrate.publication.PublicationStateRepository
import com.achulkov.evomolior.substrate.repair.ToolRepairPersistenceRepository
import com.achulkov.evomolior.substrate.roundledger.RoundPhaseLedgerRepository
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

/** Closed activation state required by completion-blueprint Section 11.4. */
@Serializable
enum class MechanismActivationState {
    NOT_CONFIGURED,
    CONFIGURED_NOT_REACHED,
    ATTEMPTED_FAILED,
    ACTIVATED_NO_DOWNSTREAM_USE,
    ACTIVATED_WITH_DOWNSTREAM_USE,
}

/** Exact five-state activation report for one society mechanism. */
@Serializable
data class MechanismActivation(
    val mechanismId: String,
    val state: MechanismActivationState,
    val authorityIds: List<String>
) {
    init {
        require(mechanismId.isNotBlank()) { "Mechanism ID must not be blank" }
        require(
            authorityIds.isNotEmpty() &&
                authorityIds.none(String::isBlank) &&
                authorityIds == authorityIds.distinct().sorted()
        ) { "Mechanism authority IDs must be non-empty, sorted, and distinct" }
    }

    /** Whether the mechanism was configured for this run. */
    val configured: Boolean
        get() = state != MechanismActivationState.NOT_CONFIGURED

    /** Whether the mechanism crossed its activation boundary. */
    val activated: Boolean
        get() = state == MechanismActivationState.ACTIVATED_NO_DOWNSTREAM_USE ||
            state == MechanismActivationState.ACTIVATED_WITH_DOWNSTREAM_USE
}

/**
 * Resolves the exact five-state mechanism classification from ordered authority layers.
 *
 * @param mechanismId Stable mechanism identity.
 * @param configured Whether the mechanism was present in immutable run configuration.
 * @param configurationAuthorityIds Evidence for the configured/not-configured decision.
 * @param attemptAuthorityIds Evidence that the activation boundary was attempted.
 * @param activationAuthorityIds Evidence that the mechanism activated successfully.
 * @param downstreamAuthorityIds Evidence that a later independent operation used the activation.
 */
internal fun resolveMechanismActivation(
    mechanismId: String,
    configured: Boolean,
    configurationAuthorityIds: List<String>,
    attemptAuthorityIds: List<String> = emptyList(),
    activationAuthorityIds: List<String> = emptyList(),
    downstreamAuthorityIds: List<String> = emptyList(),
): MechanismActivation {
    require(
        configured ||
            attemptAuthorityIds.isEmpty() &&
            activationAuthorityIds.isEmpty() &&
            downstreamAuthorityIds.isEmpty()
    ) { "An unconfigured mechanism cannot carry attempt, activation, or downstream authority" }
    require(activationAuthorityIds.isNotEmpty() || downstreamAuthorityIds.isEmpty()) {
        "Downstream use requires an activated mechanism"
    }
    val state = when {
        !configured -> MechanismActivationState.NOT_CONFIGURED
        downstreamAuthorityIds.isNotEmpty() ->
            MechanismActivationState.ACTIVATED_WITH_DOWNSTREAM_USE
        activationAuthorityIds.isNotEmpty() ->
            MechanismActivationState.ACTIVATED_NO_DOWNSTREAM_USE
        attemptAuthorityIds.isNotEmpty() -> MechanismActivationState.ATTEMPTED_FAILED
        else -> MechanismActivationState.CONFIGURED_NOT_REACHED
    }
    return MechanismActivation(
        mechanismId = mechanismId,
        state = state,
        authorityIds = (
            configurationAuthorityIds +
                attemptAuthorityIds +
                activationAuthorityIds +
                downstreamAuthorityIds
            ).distinct().sorted(),
    )
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
        val controlRecords = graphByGroup.flatMap { (groupId, repository) ->
            repository.controlInterventionsThroughRound(runId, groupId, maxRound)
        }
        val controlIds = controlRecords.map { it.interventionId }.distinct().sorted()
        val controlDownstreamIds = controlRecords
            .flatMap { record ->
                record.avoidedLossAuthorities.map {
                    "control-downstream/${it.authorityId}"
                }
            }
            .distinct()
            .sorted()
        val summaryRecords = graphByGroup.flatMap { (groupId, repository) ->
            repository.summaryValidationsThroughRound(runId, groupId, maxRound)
        }
        val summaryIds = summaryRecords.map { it.validationId }.distinct().sorted()
        val finalReferenceIds = graphByGroup.flatMap { (groupId, repository) ->
            repository.finalDeliverableReferencesForRun(runId, groupId)
                .filter {
                    it.nodeId != null &&
                        it.sourceAuthority.kind ==
                        ContributionAuthorityKind.NODE_CAPTURE_DECISION
                }
                .map { it.referenceId }
        }.distinct().sorted()
        val toolReuseIds = toolDashboards.flatMap(ToolObservability::reuseAuthorityIds)
        val configuredToolOpportunityIds = atlas.nodes.values
            .mapNotNull { it.toolOpportunity?.opportunityId }
            .distinct()
            .sorted()
        val toolRepairAttemptIds = groupIds.flatMap { groupId ->
            val repository = ToolRepairPersistenceRepository(databaseFactory.get(runId, groupId))
            configuredToolOpportunityIds.flatMap { opportunityId ->
                repository.attempts(opportunityId).map { it.attemptId }
            }
        }.distinct().sorted()
        val toolTransportAttemptIds = ToolTransportAttemptStore(databaseFactory)
            .read(runId)
            .attempts
            .map { it.recordHash }
            .sorted()
        val promotedToolAuthorityIds = groupIds.flatMap { groupId ->
            val database = databaseFactory.get(runId, groupId)
            val infrastructure = ToolInfrastructureRepository(database)
            database.toolRegistryDao()
                .getActive(runId, groupId, null)
                .mapNotNull { tool ->
                    infrastructure.lifecycle(ToolVersionId(tool.id))
                        .firstOrNull { it.state == com.achulkov.evomolior.core.atlas.ToolLifecycleState.PROMOTED }
                        ?.eventId
                }
        }.distinct().sorted()
        val captureAttemptIds = states.values
            .flatMap { it.grades }
            .map { it.gradeId }
            .distinct()
            .sorted()
        val disclosureActivationIds = states.values
            .flatMap { state ->
                state.disclosureLineage.map { it.authorization.authorizationId }
            }
            .distinct()
            .sorted()
        val disclosureAttemptIds = if (atlas.stageDefinitions.size > 1) {
            val ledger = RoundPhaseLedgerRepository(databaseFactory.getAtlas(runId))
            (0..maxRound).mapNotNull { round ->
                ledger.history(runId, round).singleOrNull {
                    it.phase ==
                        RoundPhaseV1.MUTATION_CAPTURE_YIELD_DISCLOSURE_AND_OUTLIER_RESOLVED
                }?.eventHash
            }.map { "disclosure-attempt/$it" }
        } else {
            emptyList()
        }
        val disclosureDownstreamIds = states.values
            .filter {
                it.disclosureLineage.isNotEmpty() &&
                    it.captureState in setOf(CaptureState.CAPTURED, CaptureState.SHARED)
            }
            .flatMap { state ->
                state.disclosureLineage.map {
                    "disclosure-captured/${it.authorization.authorizationId}"
                }
            }
            .distinct()
            .sorted()
        val registrationsByGroup = groupIds.associateWith { groupId ->
            WorkerExecutionRepository(databaseFactory.get(runId, groupId))
                .registrations(runId, groupId)
        }
        val exitsByGroup = groupIds.associateWith { groupId ->
            WorkerExecutionRepository(databaseFactory.get(runId, groupId))
                .exits(runId, groupId)
        }
        val ordinaryExitIds = exitsByGroup.values.flatten().map { it.exitId }.distinct().sorted()
        val usefulExitIds = exitsByGroup.values.flatten()
            .filter { it.useful }
            .map { "useful-exit/${it.exitId}" }
            .distinct()
            .sorted()
        val mechanisms = listOf(
            resolveMechanismActivation(
                mechanismId = "progressive-disclosure",
                configured = atlas.stageDefinitions.size > 1,
                configurationAuthorityIds = listOf("worker-atlas/${atlas.atlasHash}"),
                attemptAuthorityIds = disclosureAttemptIds,
                activationAuthorityIds = disclosureActivationIds,
                downstreamAuthorityIds = disclosureDownstreamIds,
            ),
            resolveMechanismActivation(
                mechanismId = "competitive-node-capture",
                configured = atlas.nodes.isNotEmpty(),
                configurationAuthorityIds = listOf("worker-atlas/${atlas.atlasHash}"),
                attemptAuthorityIds = captureAttemptIds,
                activationAuthorityIds = captureAuthorityIds,
                downstreamAuthorityIds = finalReferenceIds,
            ),
            resolveMechanismActivation(
                mechanismId = "promoted-tool-later-node-reuse",
                configured = configuredToolOpportunityIds.isNotEmpty(),
                configurationAuthorityIds = if (configuredToolOpportunityIds.isEmpty()) {
                    listOf("worker-atlas/${atlas.atlasHash}:no-tool-opportunity")
                } else {
                    configuredToolOpportunityIds.map { "tool-opportunity/$it" }
                },
                attemptAuthorityIds = toolTransportAttemptIds + toolRepairAttemptIds,
                activationAuthorityIds = promotedToolAuthorityIds,
                downstreamAuthorityIds = toolReuseIds.sorted(),
            ),
            resolveMechanismActivation(
                mechanismId = "useful-exit-accounting",
                configured = true,
                configurationAuthorityIds = listOf("run-topology/$runId"),
                attemptAuthorityIds = registrationsByGroup.values.flatten()
                    .map { it.registrationId }
                    .distinct()
                    .sorted(),
                activationAuthorityIds = ordinaryExitIds,
                downstreamAuthorityIds = usefulExitIds,
            ),
            resolveMechanismActivation(
                mechanismId = "typed-control-intervention",
                configured = true,
                configurationAuthorityIds = listOf("worker-atlas/${atlas.atlasHash}"),
                attemptAuthorityIds = controlIds,
                activationAuthorityIds = controlIds,
                downstreamAuthorityIds = controlDownstreamIds,
            ),
            resolveMechanismActivation(
                mechanismId = "summary-validation",
                configured = true,
                configurationAuthorityIds = listOf("worker-atlas/${atlas.atlasHash}"),
                attemptAuthorityIds = emptyList(),
                activationAuthorityIds = summaryIds,
            ),
            resolveMechanismActivation(
                mechanismId = "publication-barrier",
                configured = true,
                configurationAuthorityIds = listOf("publication-policy/$runId"),
                attemptAuthorityIds = emptyList(),
                activationAuthorityIds = publication.map { it.eventId },
                downstreamAuthorityIds = publication
                    .filter { it.state == PublicationState.ARCHIVED }
                    .map { "accepted-archive/${it.eventHash}" },
            )
        )
        require(
            mechanisms.map(MechanismActivation::mechanismId) ==
                MechanismActivationCatalog.orderedMechanismIds
        ) { "Observability mechanism catalog drifted from terminal report authority" }
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
