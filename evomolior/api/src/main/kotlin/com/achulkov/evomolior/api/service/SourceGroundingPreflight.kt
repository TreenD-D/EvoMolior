package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.RunObjectiveHeuristics

private val SOURCE_PREFLIGHT_URL = Regex("""https?://[^\s]+""")

/**
 * Validates that a source-sensitive run has either supplied evidence or a discovery-capable backend.
 *
 * @param config Requested run configuration.
 */
internal fun requireSourceGroundingPath(config: RunConfig) {
    if (!RunObjectiveHeuristics.requiresExternalEvidence(config.objective)) return

    val hasSuppliedEvidence = config.contextArtifacts.isNotEmpty() ||
        SOURCE_PREFLIGHT_URL.containsMatchIn(config.objective)
    val hasAuditableDiscovery = config.llmBackend == LlmBackend.CODEX_CHATGPT
    require(hasSuppliedEvidence || hasAuditableDiscovery) {
        "Source-sensitive objective requires supplied evidence artifacts, explicit source URLs, " +
            "or an auditable discovery backend. No source path is available for backend " +
            "'${config.llmBackend.id}', so the run was rejected before execution."
    }
}
