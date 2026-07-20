@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.core.config

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Operator-declared purpose for Genesis Council execution.
 *
 * The purpose is persisted as run authority. It must never be inferred from an objective, run ID,
 * output path, environment variable, or command name.
 *
 * @param id Stable command-line and diagnostic identifier.
 */
@Serializable
enum class CouncilExecutionPurpose(val id: String) {
    STANDARD("standard"),
    APPENDIX_B_ACCEPTANCE("appendix-b-acceptance");

    companion object {
        /**
         * Parses an operator-supplied purpose identifier.
         *
         * @param value Purpose identifier or enum name.
         * @return Typed execution purpose.
         */
        fun parse(value: String): CouncilExecutionPurpose = entries.firstOrNull {
            it.id.equals(value, ignoreCase = true) ||
                it.name.equals(value, ignoreCase = true)
        } ?: throw IllegalArgumentException(
            "Unsupported Council execution purpose '$value'. Supported purposes: " +
                entries.joinToString(", ") { it.id }
        )
    }
}

/**
 * Canonical timeout policy for provider execution and the Genesis Council envelope.
 */
object LlmTimeoutPolicy {
    /** Default maximum for one Codex process execution. */
    val DEFAULT_CODEX_EXECUTION: Duration = 30.minutes

    /** Maximum for one Codex process execution in an explicit Appendix B acceptance run. */
    val APPENDIX_B_CODEX_EXECUTION: Duration = 45.minutes

    /** Overall Genesis Council envelope for network-backed providers. */
    val DEFAULT_GENESIS_COUNCIL_ENVELOPE: Duration = 8.minutes

    /** Overall Genesis Council envelope for Codex/ChatGPT. */
    val CODEX_GENESIS_COUNCIL_ENVELOPE: Duration = 45.minutes

    /**
     * Selects the per-process Codex timeout for an explicit execution purpose.
     *
     * @param purpose Persisted operator purpose.
     * @return Finite positive process timeout.
     */
    fun codexExecutionTimeout(purpose: CouncilExecutionPurpose): Duration = when (purpose) {
        CouncilExecutionPurpose.STANDARD -> DEFAULT_CODEX_EXECUTION
        CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE -> APPENDIX_B_CODEX_EXECUTION
    }

    /**
     * Selects the unchanged overall Genesis Council envelope for a backend.
     *
     * @param backend Active LLM backend.
     * @return Finite positive Council timeout.
     */
    fun genesisCouncilEnvelope(backend: LlmBackend): Duration = when (backend) {
        LlmBackend.CODEX_CHATGPT -> CODEX_GENESIS_COUNCIL_ENVELOPE
        LlmBackend.OPENROUTER,
        LlmBackend.QWEN_CLOUD -> DEFAULT_GENESIS_COUNCIL_ENVELOPE
    }

    /**
     * Rejects timeout values that cannot safely govern a provider boundary.
     *
     * @param name Diagnostic policy name.
     * @param duration Candidate timeout.
     * @return The validated timeout.
     */
    fun requireFinitePositive(name: String, duration: Duration): Duration {
        require(duration.isFinite() && duration > Duration.ZERO) {
            "$name must be finite and positive"
        }
        return duration
    }
}
