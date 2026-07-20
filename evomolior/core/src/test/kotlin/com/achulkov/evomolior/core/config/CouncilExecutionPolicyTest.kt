package com.achulkov.evomolior.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class CouncilExecutionPolicyTest {
    @Test
    fun `timeout policy preserves exact default and acceptance bounds`() {
        assertEquals(30.minutes, LlmTimeoutPolicy.DEFAULT_CODEX_EXECUTION)
        assertEquals(45.minutes, LlmTimeoutPolicy.APPENDIX_B_CODEX_EXECUTION)
        assertEquals(8.minutes, LlmTimeoutPolicy.DEFAULT_GENESIS_COUNCIL_ENVELOPE)
        assertEquals(45.minutes, LlmTimeoutPolicy.CODEX_GENESIS_COUNCIL_ENVELOPE)

        assertEquals(
            30.minutes,
            LlmTimeoutPolicy.codexExecutionTimeout(CouncilExecutionPurpose.STANDARD)
        )
        assertEquals(
            45.minutes,
            LlmTimeoutPolicy.codexExecutionTimeout(
                CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE
            )
        )
        assertEquals(
            45.minutes,
            LlmTimeoutPolicy.genesisCouncilEnvelope(LlmBackend.CODEX_CHATGPT)
        )
        assertEquals(
            8.minutes,
            LlmTimeoutPolicy.genesisCouncilEnvelope(LlmBackend.OPENROUTER)
        )
        assertEquals(
            8.minutes,
            LlmTimeoutPolicy.genesisCouncilEnvelope(LlmBackend.QWEN_CLOUD)
        )
        listOf(
            LlmTimeoutPolicy.DEFAULT_CODEX_EXECUTION,
            LlmTimeoutPolicy.APPENDIX_B_CODEX_EXECUTION,
            LlmTimeoutPolicy.DEFAULT_GENESIS_COUNCIL_ENVELOPE,
            LlmTimeoutPolicy.CODEX_GENESIS_COUNCIL_ENVELOPE
        ).forEach { timeout ->
            assertTrue(timeout.isFinite())
            assertTrue(timeout.isPositive())
        }
    }

    @Test
    fun `execution purpose parser accepts stable ids`() {
        assertEquals(
            CouncilExecutionPurpose.STANDARD,
            CouncilExecutionPurpose.parse("standard")
        )
        assertEquals(
            CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE,
            CouncilExecutionPurpose.parse("appendix-b-acceptance")
        )
    }
}
