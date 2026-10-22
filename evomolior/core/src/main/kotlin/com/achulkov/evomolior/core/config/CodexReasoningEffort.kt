package com.achulkov.evomolior.core.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Closed per-run Codex model reasoning-effort authority.
 *
 * @property configValue Exact value emitted to the Codex CLI.
 */
@Serializable
enum class CodexReasoningEffort(
    val configValue: String
) {
    @SerialName("low")
    LOW("low"),

    @SerialName("medium")
    MEDIUM("medium"),

    @SerialName("high")
    HIGH("high");

    companion object {
        const val ENV_VAR: String = "EVOMOLIOR_CODEX_REASONING_EFFORT"

        /**
         * Parses an explicit serialized or CLI value.
         *
         * @param value Raw effort value.
         * @return The matching closed effort.
         */
        fun parse(value: String): CodexReasoningEffort =
            entries.firstOrNull { it.configValue == value.trim().lowercase() }
                ?: throw IllegalArgumentException(
                    "Codex reasoning effort ($ENV_VAR / CLI / config) must be one of " +
                        "low, medium, or high; received '$value'"
                )

        /**
         * Resolves an optional value with the product default.
         *
         * @param configuredValue Optional serialized, CLI, or environment value.
         * @return The configured effort, defaulting to [HIGH].
         */
        fun resolve(configuredValue: String?): CodexReasoningEffort =
            configuredValue?.let(::parse) ?: HIGH
    }
}
