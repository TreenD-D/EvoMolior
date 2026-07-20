package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.model.DimensionScore
import com.achulkov.evomolior.core.model.GradingResult
import com.achulkov.evomolior.core.model.RoundScore
import com.achulkov.evomolior.substrate.db.entity.MetaArchiveEntryEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlin.io.path.createTempDirectory
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdversarialDiscriminatorRewardTest {

    @Test
    fun `adversarial brief updates raise the next discriminator budget multiplier`() = kotlinx.coroutines.test.runTest {
        val dataRoot = createTempDirectory("adversarial-discriminator-reward-").toFile()
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ -> error("unused") },
            groupCoordinatorProvider = { _, _ -> error("unused") },
            graderGroupManagerProvider = { _ -> error("unused") },
            substrateFactory = SubstrateDatabaseFactory(dataRoot),
            metaPlannerRunner = com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner()
        )

        val blindResult = GradingResult(
            roundIndex = 0,
            winnerId = "group-1",
            scores = listOf(
                RoundScore(
                    groupId = "group-0",
                    roundIndex = 0,
                    dimensionScores = listOf(
                        DimensionScore("kb_density", 42.0, "dense"),
                        DimensionScore("kg_coherence", 30.0, "coherent"),
                        DimensionScore("tool_utility", 28.0, "useful")
                    ),
                    weightedTotal = 33.33,
                    rank = 2
                ),
                RoundScore(
                    groupId = "group-1",
                    roundIndex = 0,
                    dimensionScores = listOf(
                        DimensionScore("kb_density", 88.0, "dense"),
                        DimensionScore("kg_coherence", 81.0, "coherent"),
                        DimensionScore("tool_utility", 76.0, "useful")
                    ),
                    weightedTotal = 81.67,
                    rank = 1
                )
            )
        )

        val update = service::class.declaredFunctions.first { it.name == "updateAdversarialDiscriminatorBriefs" }
            .apply { isAccessible = true }

        update.callSuspend(
            service,
            "run-reward",
            0,
            blindResult,
            mapOf("group-0" to "brief one", "group-1" to "brief two")
        )

        assertTrue(service.adversarialDiscriminatorBudgetMultiplier("run-reward") > 1.0)
    }

    @Test
    fun `adversarial reward multiplier is recovered from the archive after restart`() = runBlocking {
        val dataRoot = createTempDirectory("adversarial-discriminator-restart-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-restart"
        val archiveDb = substrateFactory.get(runId, "__meta_archive__")
        val json = Json { ignoreUnknownKeys = true }

        archiveDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "adversarial-discriminator-reward-$runId-r0",
                runId = runId,
                entryType = RunArchiveService.ENTRY_ADVERSARIAL_DISCRIMINATOR_REWARD,
                groupId = null,
                label = "Adversarial Discriminator Reward — run $runId",
                contentJson = json.encodeToString(Double.serializer(), 1.35),
                runObjective = "restart regression",
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )

        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ -> error("unused") },
            groupCoordinatorProvider = { _, _ -> error("unused") },
            graderGroupManagerProvider = { _ -> error("unused") },
            substrateFactory = substrateFactory,
            metaPlannerRunner = com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner()
        )

        assertEquals(1.35, service.adversarialDiscriminatorBudgetMultiplier(runId))
    }
}
