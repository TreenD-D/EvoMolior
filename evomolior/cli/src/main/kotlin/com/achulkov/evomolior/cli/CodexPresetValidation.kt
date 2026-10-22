package com.achulkov.evomolior.cli

import com.achulkov.evomolior.core.config.CodexRuntimePreset
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.AcceptanceProfile

internal fun validateCodexPreset(
    preset: CodexRuntimePreset?,
    backend: LlmBackend,
    overrides: Map<String, String>,
    acceptanceProfile: AcceptanceProfile,
) {
    if (preset == null) return
    if (backend != LlmBackend.CODEX_CHATGPT) {
        throw CliUsageException("-codex-preset requires the codex-chatgpt backend")
    }
    if (acceptanceProfile != AcceptanceProfile.NORMAL && preset != CodexRuntimePreset.FULL_LUNA) {
        throw CliUsageException("Acceptance profiles retain Luna-only authority; use full-luna or omit -codex-preset")
    }
    val unsupported = overrides.values.filterNot { it in preset.supportedModelIds }
    if (unsupported.isNotEmpty()) {
        throw CliUsageException(
            "Codex preset '${preset.id}' does not support model overrides: ${unsupported.joinToString()}"
        )
    }
}
