package com.achulkov.evokammer.api.model

import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.core.model.ScoringDimension
import com.achulkov.evokammer.core.contract.RunInputArtifactKind
import kotlinx.serialization.Serializable

/**
 * Full configuration for a single EvoKammer civilisation run.
 *
 * Populated either manually by the user in the UI, or output by the Genesis Council agents.
 *
 * @param runId Unique run identifier (UUID).
 * @param objective Natural-language objective for the civilisation (e.g. "solve climate change").
 * @param groupCount Number of competing groups (2–8; or 1 for a test run).
 * @param groupProfiles Profile type for each group (ordered, same length as [groupCount]).
 * @param rounds Total number of evaluation rounds.
 * @param roundDurationMinutes Minutes per round before forced check-in.
 * @param maxAgentsPerGroup Maximum concurrent agents per group.
 * @param scoringDimensions Dimensions used by Grader agents.
 * @param openRouterApiKey API key for OpenRouter (used by Koog executors).
 * @param modelOverrides Optional per-role model overrides (role name → model string).
 * @param contextArtifacts Uploaded run-start context artifacts.
 * @param continuationFromRunId Optional completed run to continue from by reusing its sealed
 *   `StartingField` snapshot as the baseline for this fresh run.
 * @param finalOutputSpec Optional spec for the run's operator-facing generated output file.
 * @param isTestRun When true, enables a minimal single-group diagnostic run:
 *   - groupCount = 1 is allowed
 *   - Genesis Council is bypassed (defaults used)
 *   - [budgetOverride] is applied to cap spend for fast verification
 * @param budgetOverride Optional global budget ceiling override (used in test runs, e.g. 200.0).
 *   Has no effect unless [isTestRun] is true or the run service explicitly respects it.
 */
@Serializable
data class RunConfig(
    val runId: String,
    val objective: String,
    val groupCount: Int,
    val groupProfiles: List<GroupProfileType>,
    val rounds: Int = 5,
    val roundDurationMinutes: Int = 30,
    val maxAgentsPerGroup: Int = 6,
    val scoringDimensions: List<ScoringDimension>,
    val openRouterApiKey: String,
    val modelOverrides: Map<String, String> = emptyMap(),
    val contextArtifacts: List<RunInputArtifactUpload> = emptyList(),
    val continuationFromRunId: String? = null,
    val finalOutputSpec: FinalOutputSpec? = null,
    /** True for a minimal diagnostic run: 1 group, no Genesis Council, fixed budget. */
    val isTestRun: Boolean = false,
    /**
     * Optional global budget ceiling override.
     * If non-null, overrides the default [StartingField.globalBudgetCeiling].
     * Primarily used by test runs to cap spend (e.g. 200.0 budget points).
     */
    val budgetOverride: Double? = null
) {
    init {
        // Test runs allow a single group; full runs require at least 2 for competition.
        val minGroups = if (isTestRun) 1 else 2
        require(groupCount in minGroups..8) {
            if (isTestRun) "groupCount must be 1–8" else "groupCount must be 2–8"
        }
        require(groupProfiles.size == groupCount) { "groupProfiles size must equal groupCount" }
        require(rounds > 0) { "rounds must be positive" }
        require(scoringDimensions.isNotEmpty()) { "At least one scoring dimension is required" }
    }
}

/**
 * Canonical generated output file expected from a run.
 *
 * Used by the final deliverable writer and archive layer so the operator can retrieve a
 * deterministic named artifact from a run without scraping summaries.
 *
 * @param relativePath Relative path under the run's `outputs/` directory.
 * @param mediaType MIME type of the generated file.
 * @param description Human-readable purpose of the generated file.
 */
@Serializable
data class FinalOutputSpec(
    val relativePath: String,
    val mediaType: String,
    val description: String = ""
)

/**
 * Uploaded run-start artifact bytes encoded for transport over JSON or multipart bridging.
 *
 * The engine persists the decoded bytes to the run artifact directory and converts this
 * transport object into a stable [RunInputArtifact] metadata record.
 *
 * @param relativePath Path of this artifact within the uploaded bundle.
 * @param sourceName Original source filename or label.
 * @param mimeType MIME type, if known.
 * @param contentBase64 Base64-encoded bytes of the uploaded artifact.
 * @param kind Artifact kind.
 * @param textPreview Optional text preview supplied by the UI or upload parser.
 */
@Serializable
data class RunInputArtifactUpload(
    val relativePath: String,
    val sourceName: String,
    val mimeType: String,
    val contentBase64: String,
    val kind: RunInputArtifactKind = RunInputArtifactKind.TEXT,
    val textPreview: String? = null
)
