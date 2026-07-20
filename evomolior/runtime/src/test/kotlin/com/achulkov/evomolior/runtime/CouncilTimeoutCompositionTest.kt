package com.achulkov.evomolior.runtime

import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.api.service.CouncilExecutionEnvelopeFactory
import com.achulkov.evomolior.core.config.CouncilExecutionPurpose
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.ScoringDimension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

class CouncilTimeoutCompositionTest {
    @Test
    fun `standard production Council retains thirty minute Codex calls`() {
        val envelope = CouncilExecutionEnvelopeFactory.create(config())

        assertEquals(30.minutes, resolveCouncilCodexExecutionTimeout(envelope))
    }

    @Test
    fun `explicit Appendix B purpose selects forty five minute Codex calls`() {
        val envelope = CouncilExecutionEnvelopeFactory.create(
            config(CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE)
        )

        assertEquals(45.minutes, resolveCouncilCodexExecutionTimeout(envelope))
    }

    private fun config(
        purpose: CouncilExecutionPurpose = CouncilExecutionPurpose.STANDARD
    ) = RunConfig(
        runId = "timeout-composition",
        objective = "Test Council timeout composition.",
        groupCount = 2,
        groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
        rounds = 3,
        scoringDimensions = listOf(
            ScoringDimension("quality", "Quality", "Receiver quality", 1.0)
        ),
        openRouterApiKey = "/tmp/codex",
        llmBackend = LlmBackend.CODEX_CHATGPT,
        councilExecutionPurpose = purpose
    )
}
