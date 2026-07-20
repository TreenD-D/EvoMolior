package com.achulkov.evomolior.agents.factory

import com.achulkov.evomolior.core.config.LlmBackend

/**
 * Central policy for backend tool support.
 *
 * OpenRouter and Qwen Cloud both run with tools enabled by default. Qwen Cloud can be forced back
 * into text-only compatibility with [QWEN_TOOL_SUPPORT_ENV] or [QWEN_TOOL_SUPPORT_PROPERTY] set
 * to a false-like value when investigating provider regressions. Codex/ChatGPT uses Codex code
 * mode and an isolated agent-scoped bridge rather than Koog-native function calls.
 */
object LlmBackendToolSupportPolicy {
    const val QWEN_TOOL_SUPPORT_ENV = "EVOMOLIOR_QWEN_TOOL_SUPPORT"
    const val QWEN_TOOL_SUPPORT_PROPERTY = "evomolior.qwenToolSupport"

    fun resolve(
        backend: LlmBackend,
        qwenToolSupportEnabled: Boolean = isQwenToolSupportEnabledByEnvironment()
    ): LlmBackendToolSupport = when (backend) {
        LlmBackend.OPENROUTER -> LlmBackendToolSupport(
            toolsEnabled = true,
            textOnlyCompatibilityMode = false,
            codeModeEnabled = false
        )
        LlmBackend.QWEN_CLOUD -> LlmBackendToolSupport(
            toolsEnabled = qwenToolSupportEnabled,
            textOnlyCompatibilityMode = !qwenToolSupportEnabled,
            codeModeEnabled = false
        )
        LlmBackend.CODEX_CHATGPT -> LlmBackendToolSupport(
            toolsEnabled = true,
            textOnlyCompatibilityMode = false,
            codeModeEnabled = true
        )
    }

    fun isQwenToolSupportEnabledByEnvironment(): Boolean =
        System.getProperty(QWEN_TOOL_SUPPORT_PROPERTY)?.toBooleanFlagOrNull()
            ?: System.getenv(QWEN_TOOL_SUPPORT_ENV)?.toBooleanFlagOrNull()
            ?: true

    private fun String.toBooleanFlagOrNull(): Boolean? = when (trim().lowercase()) {
        "true", "1", "enabled", "on", "yes" -> true
        "false", "0", "disabled", "off", "no" -> false
        else -> null
    }
}

data class LlmBackendToolSupport(
    val toolsEnabled: Boolean,
    val textOnlyCompatibilityMode: Boolean,
    val codeModeEnabled: Boolean
)
