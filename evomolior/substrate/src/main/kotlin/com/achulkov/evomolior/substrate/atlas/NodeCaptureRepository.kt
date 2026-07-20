package com.achulkov.evomolior.substrate.atlas

import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.NodeCaptureDecision
import com.achulkov.evomolior.core.atlas.NodeEvidencePacket
import com.achulkov.evomolior.core.atlas.NodeGradeBundle
import com.achulkov.evomolior.core.atlas.NodeGradeBundleVerdict
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeYieldApplication
import com.achulkov.evomolior.core.atlas.NodeYieldSchedule
import com.achulkov.evomolior.core.atlas.RewardUseLineageEvent
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.NodeCaptureDecisionEntity
import com.achulkov.evomolior.substrate.db.entity.NodeEvidencePacketEntity
import com.achulkov.evomolior.substrate.db.entity.NodeGradeBundleEntity
import com.achulkov.evomolior.substrate.db.entity.NodeYieldApplicationEntity
import com.achulkov.evomolior.substrate.db.entity.NodeYieldScheduleEntity
import com.achulkov.evomolior.substrate.db.entity.RewardUseLineageEventEntity
import com.achulkov.evomolior.substrate.worker.ContributionGraphRepository
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Validates and persists append-only node evidence, grades, capture decisions, and reward lineage.
 *
 * Records live in the run-global Atlas authority database. Identical IDs and hashes are
 * idempotent; an ID reused for different semantic content is rejected.
 */
class NodeCaptureRepository(
    private val databaseFactory: SubstrateDatabaseFactory,
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    },
    private val now: () -> Long = System::currentTimeMillis
) {
    /** Appends one non-empty Critic-accepted evidence packet. */
    suspend fun appendEvidencePacket(packet: NodeEvidencePacket): NodeEvidencePacket {
        validateEvidencePacket(packet)
        val dao = databaseFactory.getAtlas(packet.runId).nodeCaptureDao()
        dao.evidencePacketById(packet.packetId)?.let { existing ->
            return requireSameHash(
                "evidence packet",
                packet.packetId,
                packet.packetHash,
                existing.packetHash
            ) { decode(NodeEvidencePacket.serializer(), existing.contentJson) }
        }
        dao.evidencePacketByHash(packet.packetHash)?.let {
            return decode(NodeEvidencePacket.serializer(), it.contentJson)
        }
        dao.insertEvidencePacket(
            NodeEvidencePacketEntity(
                packetId = packet.packetId,
                runId = packet.runId,
                round = packet.round,
                groupId = packet.groupId.value,
                nodeId = packet.nodeId.value,
                atlasVersion = packet.atlasVersion,
                atlasHash = packet.atlasHash,
                nodePayloadHash = packet.nodePayloadHash,
                packetHash = packet.packetHash,
                contentJson = encode(NodeEvidencePacket.serializer(), packet),
                createdAtMs = now()
            )
        )
        return requireNotNull(dao.evidencePacketByHash(packet.packetHash)) {
            "Evidence packet insert did not persist ${packet.packetId}"
        }.let { decode(NodeEvidencePacket.serializer(), it.contentJson) }
    }

    /** Appends one exact node-grade bundle after validating its evidence binding and quorum. */
    suspend fun appendGradeBundle(bundle: NodeGradeBundle): NodeGradeBundle {
        validateGradeBundle(bundle)
        val dao = databaseFactory.getAtlas(bundle.runId).nodeCaptureDao()
        val evidencePacketHash = bundle.evidencePacketHash
        val packet = if (evidencePacketHash == null) {
            null
        } else {
            dao.evidencePacketByHash(evidencePacketHash)
        }
        if (bundle.verdict in EVALUATED_PACKET_VERDICTS) {
            requireNotNull(packet) {
                "Evaluated grade bundle ${bundle.bundleId} references an unknown evidence packet"
            }
            require(packet.packetId == bundle.evidencePacketId) {
                "Grade bundle packet ID does not match the packet hash authority"
            }
            require(packet.runId == bundle.runId &&
                packet.groupId == bundle.groupId.value &&
                packet.nodeId == bundle.nodeId.value &&
                packet.atlasVersion == bundle.atlasVersion &&
                packet.atlasHash == bundle.atlasHash &&
                packet.nodePayloadHash == bundle.nodePayloadHash
            ) {
                "Grade bundle identity does not match its exact evidence packet"
            }
        }
        dao.gradeBundleById(bundle.bundleId)?.let { existing ->
            return withGradeLineage(requireSameHash(
                "grade bundle",
                bundle.bundleId,
                bundle.bundleHash,
                existing.bundleHash
            ) { decode(NodeGradeBundle.serializer(), existing.contentJson) }
            )
        }
        dao.gradeBundleByHash(bundle.bundleHash)?.let {
            return withGradeLineage(decode(NodeGradeBundle.serializer(), it.contentJson))
        }
        dao.insertGradeBundle(
            NodeGradeBundleEntity(
                bundleId = bundle.bundleId,
                runId = bundle.runId,
                round = bundle.round,
                groupId = bundle.groupId.value,
                nodeId = bundle.nodeId.value,
                atlasVersion = bundle.atlasVersion,
                atlasHash = bundle.atlasHash,
                nodePayloadHash = bundle.nodePayloadHash,
                evidencePacketId = bundle.evidencePacketId,
                evidencePacketHash = bundle.evidencePacketHash,
                verdict = bundle.verdict.name,
                normalizedScore = bundle.normalizedScore,
                bundleHash = bundle.bundleHash,
                contentJson = encode(NodeGradeBundle.serializer(), bundle),
                createdAtMs = now()
            )
        )
        return withGradeLineage(requireNotNull(dao.gradeBundleByHash(bundle.bundleHash)) {
            "Grade bundle insert did not persist ${bundle.bundleId}"
        }.let { decode(NodeGradeBundle.serializer(), it.contentJson) })
    }

    /** Appends one replayable capture decision whose bundle hashes already exist. */
    suspend fun appendCaptureDecision(decision: NodeCaptureDecision): NodeCaptureDecision {
        require(decision.hasValidHash()) { "Capture decision hash is invalid" }
        require(decision.round >= 0) { "Capture decision round cannot be negative" }
        require(decision.atlasVersion > 0) { "Capture decision Atlas version must be positive" }
        val winningScore = decision.winningScore
        require(winningScore == null || winningScore in 0.0..1.0) {
            "Capture decision winning score must be in [0, 1]"
        }
        val priorWinningScore = decision.priorWinningScore
        require(priorWinningScore == null || priorWinningScore in 0.0..1.0) {
            "Capture decision prior score must be in [0, 1]"
        }
        val frozenInvalidAuthority = decision.decisionType ==
            com.achulkov.evomolior.core.atlas.NodeCaptureDecisionType.FROZEN_INVALID_AUTHORITY
        if (frozenInvalidAuthority) {
            require(
                decision.authoritativeBundleHashes.isEmpty() &&
                    !decision.yieldEligible &&
                    decision.winningScore == null &&
                    decision.resultingOwnerGroupIds ==
                    decision.previousOwnerGroupId?.let(::setOf).orEmpty()
            ) {
                "Frozen invalid-authority decisions must remain audit-only"
            }
        }
        val dao = databaseFactory.getAtlas(decision.runId).nodeCaptureDao()
        decision.candidateBundleHashes.forEach { bundleHash ->
            val bundle = dao.gradeBundleByHash(bundleHash)
            val matchesDecisionAuthority = bundle != null &&
                bundle.runId == decision.runId &&
                bundle.nodeId == decision.nodeId.value &&
                bundle.atlasVersion == decision.atlasVersion &&
                bundle.atlasHash == decision.atlasHash &&
                bundle.nodePayloadHash == decision.nodePayloadHash
            if (!matchesDecisionAuthority) {
                require(frozenInvalidAuthority) {
                    if (bundle == null) {
                        "Capture decision references unknown candidate bundle $bundleHash"
                    } else {
                        "Capture candidate bundle does not match decision authority"
                    }
                }
            }
        }
        require(decision.authoritativeBundleHashes.all(decision.candidateBundleHashes::contains)) {
            "Authoritative capture bundles must be included among candidates"
        }
        dao.captureDecisionById(decision.decisionId)?.let { existing ->
            return withCaptureLineage(requireSameHash(
                "capture decision",
                decision.decisionId,
                decision.decisionHash,
                existing.decisionHash
            ) { decode(NodeCaptureDecision.serializer(), existing.contentJson) }
            )
        }
        dao.captureDecisionByHash(decision.decisionHash)?.let {
            return withCaptureLineage(decode(NodeCaptureDecision.serializer(), it.contentJson))
        }
        dao.insertCaptureDecision(
            NodeCaptureDecisionEntity(
                decisionId = decision.decisionId,
                runId = decision.runId,
                round = decision.round,
                nodeId = decision.nodeId.value,
                atlasVersion = decision.atlasVersion,
                atlasHash = decision.atlasHash,
                nodePayloadHash = decision.nodePayloadHash,
                decisionType = decision.decisionType.name,
                resultingCaptureState = decision.resultingCaptureState.name,
                yieldEligible = decision.yieldEligible,
                decisionHash = decision.decisionHash,
                contentJson = encode(NodeCaptureDecision.serializer(), decision),
                createdAtMs = now()
            )
        )
        return withCaptureLineage(requireNotNull(dao.captureDecisionByHash(decision.decisionHash)) {
            "Capture decision insert did not persist ${decision.decisionId}"
        }.let { decode(NodeCaptureDecision.serializer(), it.contentJson) })
    }

    private suspend fun withGradeLineage(bundle: NodeGradeBundle): NodeGradeBundle {
        val atlasDatabase = databaseFactory.getAtlas(bundle.runId)
        val persisted = requireNotNull(
            atlasDatabase.nodeCaptureDao().gradeBundleByHash(bundle.bundleHash)
        ) {
            "Grade bundle authority disappeared before contribution lineage"
        }
        ContributionGraphRepository(
            database = databaseFactory.get(bundle.runId, bundle.groupId.value),
            atlasDatabase = atlasDatabase
        ).recordGradeLineage(bundle, persisted.createdAtMs)
        return bundle
    }

    private suspend fun withCaptureLineage(decision: NodeCaptureDecision): NodeCaptureDecision {
        val atlasDatabase = databaseFactory.getAtlas(decision.runId)
        val persistedDecision = requireNotNull(
            atlasDatabase.nodeCaptureDao().captureDecisionByHash(decision.decisionHash)
        ) {
            "Capture decision authority disappeared before contribution lineage"
        }
        decision.authoritativeBundleHashes.forEach { bundleHash ->
            val bundleEntity = requireNotNull(
                atlasDatabase.nodeCaptureDao().gradeBundleByHash(bundleHash)
            ) {
                "Capture lineage references unknown authoritative bundle $bundleHash"
            }
            val bundle = decode(NodeGradeBundle.serializer(), bundleEntity.contentJson)
            ContributionGraphRepository(
                database = databaseFactory.get(decision.runId, bundle.groupId.value),
                atlasDatabase = atlasDatabase
            ).recordCaptureLineage(decision, bundle, persistedDecision.createdAtMs)
        }
        return decision
    }

    /** Appends a bounded schedule that cannot activate in its judging round. */
    suspend fun appendYieldSchedule(schedule: NodeYieldSchedule): NodeYieldSchedule {
        require(schedule.hasValidHash()) { "Yield schedule hash is invalid" }
        require(schedule.activationRound > schedule.judgingRound) {
            "Yield schedule must activate after its judging round"
        }
        require(schedule.rewards.isNotEmpty()) { "Yield schedule must contain a reward" }
        require(schedule.rewards.all { it.amount > 0.0 && it.amount.isFinite() }) {
            "Scheduled reward amounts must be finite and positive"
        }
        val dao = databaseFactory.getAtlas(schedule.runId).nodeCaptureDao()
        val decision = requireNotNull(
            dao.captureDecisionByHash(schedule.captureDecisionHash)
        ) {
            "Yield schedule references an unknown capture decision"
        }
        require(decision.decisionId == schedule.captureDecisionId &&
            decision.nodeId == schedule.nodeId.value &&
            decision.yieldEligible
        ) {
            "Yield schedule does not match an eligible capture decision"
        }
        require(schedule.scoreBundleHash in decode(
            NodeCaptureDecision.serializer(),
            decision.contentJson
        ).authoritativeBundleHashes) {
            "Yield schedule score bundle is not authoritative for its capture decision"
        }
        val bundle = requireNotNull(dao.gradeBundleByHash(schedule.scoreBundleHash)) {
            "Yield schedule references an unknown score bundle"
        }
        require(bundle.evidencePacketHash == schedule.evidencePacketHash) {
            "Yield schedule evidence hash does not match the authoritative score bundle"
        }
        dao.yieldScheduleById(schedule.scheduleId)?.let { existing ->
            return requireSameHash(
                "yield schedule",
                schedule.scheduleId,
                schedule.scheduleHash,
                existing.scheduleHash
            ) { decode(NodeYieldSchedule.serializer(), existing.contentJson) }
        }
        dao.yieldScheduleByHash(schedule.scheduleHash)?.let {
            return decode(NodeYieldSchedule.serializer(), it.contentJson)
        }
        dao.insertYieldSchedule(
            NodeYieldScheduleEntity(
                scheduleId = schedule.scheduleId,
                runId = schedule.runId,
                nodeId = schedule.nodeId.value,
                groupId = schedule.groupId.value,
                judgingRound = schedule.judgingRound,
                activationRound = schedule.activationRound,
                captureDecisionHash = schedule.captureDecisionHash,
                scheduleHash = schedule.scheduleHash,
                contentJson = encode(NodeYieldSchedule.serializer(), schedule),
                createdAtMs = now()
            )
        )
        return requireNotNull(dao.yieldScheduleByHash(schedule.scheduleHash)) {
            "Yield schedule insert did not persist ${schedule.scheduleId}"
        }.let { decode(NodeYieldSchedule.serializer(), it.contentJson) }
    }

    /** Appends the one allowed application of a delayed yield schedule. */
    suspend fun appendYieldApplication(
        application: NodeYieldApplication
    ): NodeYieldApplication {
        require(application.hasValidHash()) { "Yield application hash is invalid" }
        val dao = databaseFactory.getAtlas(application.runId).nodeCaptureDao()
        val scheduleEntity = requireNotNull(dao.yieldScheduleByHash(application.scheduleHash)) {
            "Yield application references an unknown schedule"
        }
        val schedule = decode(NodeYieldSchedule.serializer(), scheduleEntity.contentJson)
        require(schedule.scheduleId == application.scheduleId &&
            schedule.nodeId == application.nodeId &&
            schedule.groupId == application.groupId
        ) {
            "Yield application identity does not match its exact schedule"
        }
        require(application.round >= schedule.activationRound) {
            "Yield application cannot occur before its activation round"
        }
        require(application.appliedRewards == schedule.rewards) {
            "Yield application must apply the exact bounded scheduled rewards"
        }
        dao.yieldApplicationById(application.applicationId)?.let { existing ->
            return requireSameHash(
                "yield application",
                application.applicationId,
                application.applicationHash,
                existing.applicationHash
            ) { decode(NodeYieldApplication.serializer(), existing.contentJson) }
        }
        dao.yieldApplicationByScheduleId(application.scheduleId)?.let { existing ->
            return requireSameHash(
                "yield schedule application",
                application.scheduleId,
                application.applicationHash,
                existing.applicationHash
            ) { decode(NodeYieldApplication.serializer(), existing.contentJson) }
        }
        dao.insertYieldApplication(
            NodeYieldApplicationEntity(
                applicationId = application.applicationId,
                scheduleId = application.scheduleId,
                scheduleHash = application.scheduleHash,
                runId = application.runId,
                nodeId = application.nodeId.value,
                groupId = application.groupId.value,
                round = application.round,
                applicationHash = application.applicationHash,
                contentJson = encode(NodeYieldApplication.serializer(), application),
                createdAtMs = now()
            )
        )
        return requireNotNull(dao.yieldApplicationById(application.applicationId)) {
            "Yield application insert did not persist ${application.applicationId}"
        }.let { decode(NodeYieldApplication.serializer(), it.contentJson) }
    }

    /** Appends one spend or later score-observation event in a reward's causal lineage. */
    suspend fun appendRewardUseEvent(event: RewardUseLineageEvent): RewardUseLineageEvent {
        require(event.hasValidHash()) { "Reward-use event hash is invalid" }
        require(event.amountSpent >= 0.0 && event.amountSpent.isFinite()) {
            "Reward-use amount must be finite and non-negative"
        }
        require((event.observedScoreDelta == null) == (event.observationRound == null)) {
            "Score-delta observations require both value and round"
        }
        val observationRound = event.observationRound
        require(observationRound == null || observationRound >= event.spentRound) {
            "Reward-use score observation cannot precede spend"
        }
        val dao = databaseFactory.getAtlas(event.runId).nodeCaptureDao()
        val application = requireNotNull(
            dao.yieldApplicationByHash(event.yieldApplicationHash)
        ) {
            "Reward-use event references an unknown yield application"
        }
        require(application.runId == event.runId &&
            application.nodeId == event.nodeId.value &&
            application.groupId == event.groupId.value
        ) {
            "Reward-use event identity does not match its yield application"
        }
        val schedule = requireNotNull(dao.yieldScheduleByHash(event.yieldScheduleHash)) {
            "Reward-use event references an unknown yield schedule"
        }
        require(schedule.scheduleHash == application.scheduleHash &&
            schedule.captureDecisionHash == event.captureDecisionHash
        ) {
            "Reward-use event does not match its schedule and capture authority"
        }
        val decodedSchedule = decode(NodeYieldSchedule.serializer(), schedule.contentJson)
        val scheduledAmount = decodedSchedule.rewards
            .singleOrNull { it.rewardType == event.rewardType }
            ?.amount
        require(scheduledAmount != null && event.amountSpent <= scheduledAmount) {
            "Reward-use spend exceeds or does not exist in the applied schedule"
        }
        event.parentEventHash?.let { parentHash ->
            val parent = requireNotNull(dao.rewardUseEventByHash(parentHash)) {
                "Reward-use event references an unknown parent event"
            }
            require(parent.runId == event.runId &&
                parent.yieldApplicationHash == event.yieldApplicationHash
            ) {
                "Reward-use parent belongs to a different causal lineage"
            }
        }
        dao.rewardUseEventById(event.eventId)?.let { existing ->
            return requireSameHash(
                "reward-use event",
                event.eventId,
                event.eventHash,
                existing.eventHash
            ) { decode(RewardUseLineageEvent.serializer(), existing.contentJson) }
        }
        dao.rewardUseEventByHash(event.eventHash)?.let {
            return decode(RewardUseLineageEvent.serializer(), it.contentJson)
        }
        dao.insertRewardUseEvent(
            RewardUseLineageEventEntity(
                eventId = event.eventId,
                runId = event.runId,
                groupId = event.groupId.value,
                nodeId = event.nodeId.value,
                spentRound = event.spentRound,
                rewardType = event.rewardType.name,
                yieldApplicationHash = event.yieldApplicationHash,
                parentEventHash = event.parentEventHash,
                eventHash = event.eventHash,
                contentJson = encode(RewardUseLineageEvent.serializer(), event),
                createdAtMs = now()
            )
        )
        return requireNotNull(dao.rewardUseEventById(event.eventId)) {
            "Reward-use event insert did not persist ${event.eventId}"
        }.let { decode(RewardUseLineageEvent.serializer(), it.contentJson) }
    }

    /** Returns all immutable evidence packets for one node in round order. */
    suspend fun evidencePacketsForNode(runId: String, nodeId: NodeId): List<NodeEvidencePacket> =
        databaseFactory.getAtlas(runId).nodeCaptureDao()
            .evidencePacketsForNode(runId, nodeId.value)
            .map { decode(NodeEvidencePacket.serializer(), it.contentJson) }

    /** Returns all immutable evidence packets for one run in round order. */
    suspend fun evidencePacketsForRun(runId: String): List<NodeEvidencePacket> =
        databaseFactory.getAtlas(runId).nodeCaptureDao()
            .evidencePacketsForRun(runId)
            .map { decode(NodeEvidencePacket.serializer(), it.contentJson) }

    /** Returns all exact grade bundles for one node in round order. */
    suspend fun gradeBundlesForNode(runId: String, nodeId: NodeId): List<NodeGradeBundle> =
        databaseFactory.getAtlas(runId).nodeCaptureDao()
            .gradeBundlesForNode(runId, nodeId.value)
            .map { decode(NodeGradeBundle.serializer(), it.contentJson) }

    /** Returns all exact node-grade bundles for one run in round order. */
    suspend fun gradeBundlesForRun(runId: String): List<NodeGradeBundle> =
        databaseFactory.getAtlas(runId).nodeCaptureDao()
            .gradeBundlesForRun(runId)
            .map { decode(NodeGradeBundle.serializer(), it.contentJson) }

    /** Returns all replayable capture decisions for one node in round order. */
    suspend fun captureDecisionsForNode(
        runId: String,
        nodeId: NodeId
    ): List<NodeCaptureDecision> =
        databaseFactory.getAtlas(runId).nodeCaptureDao()
            .captureDecisionsForNode(runId, nodeId.value)
            .map { decode(NodeCaptureDecision.serializer(), it.contentJson) }

    /** Returns every replayable capture decision for one run in round order. */
    suspend fun captureDecisionsForRun(runId: String): List<NodeCaptureDecision> =
        databaseFactory.getAtlas(runId).nodeCaptureDao()
            .captureDecisionsForRun(runId)
            .map { decode(NodeCaptureDecision.serializer(), it.contentJson) }

    /** Returns delayed schedules activating for one group in one round. */
    suspend fun yieldSchedulesForActivation(
        runId: String,
        groupId: String,
        activationRound: Int
    ): List<NodeYieldSchedule> =
        databaseFactory.getAtlas(runId).nodeCaptureDao()
            .yieldSchedulesForActivation(runId, groupId, activationRound)
            .map { decode(NodeYieldSchedule.serializer(), it.contentJson) }

    /** Returns the immutable application for one schedule, when already applied. */
    suspend fun yieldApplicationForSchedule(
        runId: String,
        scheduleId: String
    ): NodeYieldApplication? =
        databaseFactory.getAtlas(runId).nodeCaptureDao()
            .yieldApplicationByScheduleId(scheduleId)
            ?.let { decode(NodeYieldApplication.serializer(), it.contentJson) }

    /** Returns all immutable yield applications for one run in round order. */
    suspend fun yieldApplicationsForRun(runId: String): List<NodeYieldApplication> =
        databaseFactory.getAtlas(runId).nodeCaptureDao()
            .yieldApplicationsForRun(runId)
            .map { decode(NodeYieldApplication.serializer(), it.contentJson) }

    /** Returns the causal use lineage for one applied yield. */
    suspend fun rewardUseEventsForApplication(
        runId: String,
        yieldApplicationHash: String
    ): List<RewardUseLineageEvent> =
        databaseFactory.getAtlas(runId).nodeCaptureDao()
            .rewardUseEventsForApplication(runId, yieldApplicationHash)
            .map { decode(RewardUseLineageEvent.serializer(), it.contentJson) }

    /** Returns every reward-spend and descendant observation for one run in causal order. */
    suspend fun rewardUseEventsForRun(runId: String): List<RewardUseLineageEvent> =
        databaseFactory.getAtlas(runId).nodeCaptureDao()
            .rewardUseEventsForRun(runId)
            .map { decode(RewardUseLineageEvent.serializer(), it.contentJson) }

    private fun validateEvidencePacket(packet: NodeEvidencePacket) {
        require(packet.hasValidHash()) { "Evidence packet hash is invalid" }
        require(packet.round >= 0) { "Evidence packet round cannot be negative" }
        require(packet.atlasVersion > 0) { "Evidence packet Atlas version must be positive" }
        require(packet.atlasHash.isNotBlank() && packet.nodePayloadHash.isNotBlank()) {
            "Evidence packet must bind exact Atlas and node payload hashes"
        }
        val references = packet.acceptedArtifactIds + packet.acceptedClaimIds +
            packet.toolRunIds + packet.testResultIds + packet.negativeResultIds
        require(references.isNotEmpty()) {
            "Evidence packet must contain at least one Critic-accepted reference"
        }
        require(references.all(String::isNotBlank) &&
            listOf(
                packet.acceptedArtifactIds,
                packet.acceptedClaimIds,
                packet.toolRunIds,
                packet.testResultIds,
                packet.negativeResultIds
            ).all { typedReferences -> typedReferences.size == typedReferences.distinct().size }
        ) {
            "Evidence packet references must be non-blank and unique within each reference type"
        }
        require(packet.provenanceRootHash.isNotBlank()) {
            "Evidence packet provenance root hash is required"
        }
        require(packet.cost.totalCredits >= 0.0 && packet.cost.totalCredits.isFinite()) {
            "Evidence packet total cost must be finite and non-negative"
        }
        require(packet.cost.llmTokens >= 0 &&
            packet.cost.toolExecutions >= 0 &&
            packet.cost.sourceFetches >= 0 &&
            packet.cost.wallClockMillis >= 0 &&
            packet.cost.resourceCosts.values.all { it >= 0.0 && it.isFinite() }
        ) {
            "Evidence packet cost components must be finite and non-negative"
        }
    }

    private fun validateGradeBundle(bundle: NodeGradeBundle) {
        require(bundle.hasValidHash()) { "Grade bundle hash is invalid" }
        require(bundle.round >= 0) { "Grade bundle round cannot be negative" }
        require(bundle.atlasVersion > 0) { "Grade bundle Atlas version must be positive" }
        require(bundle.requiredQuorum > 0) { "Grade bundle quorum must be positive" }
        require(bundle.completedGraderIds.all(bundle.graderIds::contains)) {
            "Completed graders must be members of the assigned grader set"
        }
        require(bundle.graderResponseHashes.size == bundle.completedGraderIds.size) {
            "Every completed grader must contribute one exact response hash"
        }
        val completeQuorum = bundle.completedGraderIds.size >= bundle.requiredQuorum
        if (bundle.verdict in QUORUM_VERDICTS) {
            require(completeQuorum) { "${bundle.verdict} requires complete grader quorum" }
        }
        if (bundle.verdict == NodeGradeBundleVerdict.INCOMPLETE_QUORUM) {
            require(!completeQuorum) { "INCOMPLETE_QUORUM cannot contain a complete quorum" }
        }
        if (bundle.verdict in EVALUATED_PACKET_VERDICTS) {
            require(bundle.evidencePacketId != null && bundle.evidencePacketHash != null) {
                "${bundle.verdict} must bind an exact evidence packet"
            }
            val vector = requireNotNull(bundle.scoreVector) {
                "${bundle.verdict} requires a complete score vector"
            }
            val normalizedScore = requireNotNull(bundle.normalizedScore) {
                "${bundle.verdict} requires a normalized score"
            }
            require(vector.values().all { it in 0.0..1.0 && it.isFinite() }) {
                "Node score vector values must be finite and in [0, 1]"
            }
            require(normalizedScore in 0.0..1.0 && normalizedScore.isFinite()) {
                "Normalized node score must be finite and in [0, 1]"
            }
        } else {
            require(bundle.scoreVector == null && bundle.normalizedScore == null) {
                "${bundle.verdict} cannot synthesize a score vector or normalized score"
            }
        }
        if (bundle.verdict == NodeGradeBundleVerdict.NO_EVIDENCE) {
            require(bundle.evidencePacketId == null && bundle.evidencePacketHash == null) {
                "NO_EVIDENCE must not invent an evidence packet"
            }
        }
    }

    private fun com.achulkov.evomolior.core.atlas.NodeScoreVector.values(): List<Double> = listOf(
        correctness,
        evidenceQuality,
        reproducibility,
        robustness,
        integrationUtility,
        receiverRelevance,
        novelty,
        efficiency,
        confidence
    )

    private fun <T> requireSameHash(
        recordType: String,
        id: String,
        expectedHash: String,
        actualHash: String,
        decodeExisting: () -> T
    ): T {
        require(AtlasHashing.constantTimeEquals(expectedHash, actualHash)) {
            "$recordType ID $id is already bound to different semantic content"
        }
        return decodeExisting()
    }

    private fun <T> encode(serializer: KSerializer<T>, value: T): String =
        json.encodeToString(serializer, value)

    private fun <T> decode(serializer: KSerializer<T>, value: String): T =
        json.decodeFromString(serializer, value)

    private companion object {
        val EVALUATED_PACKET_VERDICTS = setOf(
            NodeGradeBundleVerdict.VALID_PASS,
            NodeGradeBundleVerdict.VALID_FAIL
        )
        val QUORUM_VERDICTS = EVALUATED_PACKET_VERDICTS + NodeGradeBundleVerdict.NO_EVIDENCE
    }
}
