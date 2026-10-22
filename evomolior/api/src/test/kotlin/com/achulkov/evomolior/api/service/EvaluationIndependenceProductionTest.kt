package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import com.achulkov.evomolior.core.grader.IndependenceLabel
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class EvaluationIndependenceProductionTest {
    @Test
    fun `actual same-family worker and grader assignments are low independence`() {
        val config = RunConfig(
            runId = "independence-run",
            objective = "Evaluate exact same-family metadata.",
            groupCount = 2,
            groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
            scoringDimensions = listOf(
                ScoringDimension("quality", "Quality", "Receiver quality.", 1.0)
            ),
            openRouterApiKey = "test"
        )
        val envelope = CouncilExecutionEnvelopeFactory.create(config)
        val sameModel = ModelSelectionConfig.DEFAULT_GRADER_MODEL
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ -> error("unused") },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = SubstrateDatabaseFactory(
                createTempDirectory("independence-production-").toFile()
            )
        )

        val profile = service.deriveEvaluationIndependenceProfile(
            config = config,
            envelope = envelope,
            modelAssignments = mapOf(
                Role.Researcher.name to sameModel,
                Role.Builder.name to sameModel,
                Role.GraderResearcher.name to sameModel,
                Role.GraderBuilder.name to sameModel,
                Role.GraderCritic.name to sameModel
            )
        )

        assertEquals(IndependenceLabel.LOW_SINGLE_FAMILY, profile.independenceLabel)
        assertEquals(1, profile.distinctModelFamilies)
        assertEquals(1, profile.distinctProviders)
    }

    @Test
    fun `grader assignments come from the sealed grader field for a codex envelope`() {
        val config = RunConfig(
            runId = "codex-independence-run",
            objective = "Evaluate backend-compatible grader metadata.",
            groupCount = 2,
            groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
            scoringDimensions = listOf(
                ScoringDimension("quality", "Quality", "Receiver quality.", 1.0)
            ),
            openRouterApiKey = "test",
            llmBackend = com.achulkov.evomolior.core.config.LlmBackend.CODEX_CHATGPT
        )
        val envelope = CouncilExecutionEnvelopeFactory.create(config)
        val model = ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ -> error("unused") },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = SubstrateDatabaseFactory(
                createTempDirectory("codex-independence-production-").toFile()
            )
        )

        val profile = service.deriveEvaluationIndependenceProfile(
            config = config,
            envelope = envelope,
            modelAssignments = mapOf(Role.Researcher.name to model),
            graderModelAssignments = mapOf(Role.GraderResearcher.name to model)
        )

        assertEquals(IndependenceLabel.LOW_SINGLE_FAMILY, profile.independenceLabel)
        assertEquals(1, profile.distinctModelFamilies)
        assertEquals(1, profile.distinctProviders)
    }
}
