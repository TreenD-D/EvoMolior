package com.achulkov.evomolior.competition.coordinator

import com.achulkov.evomolior.competition.dispatch.DispatchMessage
import com.achulkov.evomolior.competition.leaks.LeakOracleConfig
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression coverage proving dispatch trust is written into the target group's substrate.
 */
class GroupCoordinatorDispatchTrustPersistenceTest {

    @Test
    fun `dispatch trust delivery and feedback are persisted per target source pair`() = runTest {
        val runId = "run-dispatch-trust-persistence"
        val substrateFactory = SubstrateDatabaseFactory(createTempDirectory("dispatch-trust-persistence").toFile())
        val targetGroupId = "group-1"
        val sourceGroupId = "group-0"
        val targetTrustDao = substrateFactory.get(runId, targetGroupId).trustScoreDao()

        val coordinator = GroupCoordinator(
            groupIds = listOf(sourceGroupId, targetGroupId),
            dimensions = listOf(
                ScoringDimension(
                    id = "accuracy",
                    name = "Accuracy",
                    description = "Score accuracy",
                    weight = 1.0
                )
            ),
            leakConfig = LeakOracleConfig(
                maxLeaksPerGroupPerRound = 3,
                maxMigrationPerPairPerRound = 3,
                corruptionProbability = 0.0,
                allowedDomains = setOf("general")
            )
        )

        coordinator.configureDispatchTrustPersistence(
            runId = runId,
            trustScoreDaosByGroup = mapOf(targetGroupId to targetTrustDao)
        )
        coordinator.onRoundStart(0)

        coordinator.dispatchLeak(
            sourceGroupId = sourceGroupId,
            message = DispatchMessage.Text("trust-me")
        )

        val deliveredTrust = targetTrustDao.getScore(runId, targetGroupId, sourceGroupId, "dispatch")
        assertEquals(0.5, deliveredTrust)

        coordinator.recordDispatchFeedback(
            targetGroupId = targetGroupId,
            sourceGroupId = sourceGroupId,
            accepted = false
        )

        val updatedTrust = targetTrustDao.getScore(runId, targetGroupId, sourceGroupId, "dispatch")
        assertEquals(0.42, updatedTrust)
        assertEquals(0.42, coordinator.dispatchTrustScoreFor(sourceGroupId, targetGroupId))
    }
}
