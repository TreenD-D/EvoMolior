package com.achulkov.evomolior.api.model

import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.core.contract.RunInputArtifactKind
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.config.CodexReasoningEffort
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.CouncilExecutionPurpose
import com.achulkov.evomolior.core.council.QuantitativeToolObligation
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Full configuration for a single EvoMolior civilisation run.
 *
 * Populated either manually by the user in the UI, or output by the Genesis Council agents.
 *
 * @param runId Unique run identifier (UUID).
 * @param objective Natural-language objective for the civilisation (e.g. "solve climate change").
 * @param groupCount Number of competing groups (2–8; or 1 for a test run).
 * @param groupProfiles Profile type for each group (ordered, same length as [groupCount]).
 * @param rounds Total number of evaluation rounds.
 *   When [autoCompletionPolicy] is present, this is the mandatory safety ceiling.
 * @param roundDurationMinutes Minutes per round before forced check-in. For production runs,
 *   zero or a negative value selects budget-led round completion when no test-only duration
 *   override is supplied; agents then run until completion or the round anti-stall guard fires.
 * @param maxAgentsPerGroup Maximum concurrent agents per group.
 * @param scoringDimensions Dimensions used by Grader agents.
 * @param openRouterApiKey API key for [llmBackend] (kept under the historical name for API compatibility).
 * @param llmBackend LLM backend used by runtime agents.
 * @param councilExecutionPurpose Explicit persisted purpose governing Council provider policy.
 * @param acceptanceProfile Explicit topology and evidence profile; never inferred from purpose.
 * @param acceptanceDiagnosticMode Optional non-accepting late-publication diagnostic sweep.
 * @param codexReasoningEffort Persisted per-run Codex reasoning authority.
 * @param quantitativeToolObligation Optional operator-owned typed quantitative-tool authority.
 * @param autoCompletionPolicy Optional grader-authoritative convergence policy.
 * @param modelOverrides Optional per-role model overrides (role name → model string).
 * @param contextArtifacts Uploaded run-start context artifacts.
 * @param continuationFromRunId Optional completed run to continue from by reusing its sealed
 *   `StartingField` snapshot as the baseline for this fresh run.
 * @param finalOutputSpec Optional spec for the run's operator-facing generated output file.
 * @param codexRuntimePreset Optional role/model policy; effort becomes a ceiling when selected.
 * @param isTestRun When true, enables a minimal single-group diagnostic run:
 *   - groupCount = 1 is allowed
 *   - Genesis Council is bypassed (defaults used)
 *   - [budgetOverride] is applied to cap spend for fast verification
 * @param budgetOverride Optional global budget ceiling override (used in test runs, e.g. 200.0).
 *   Has no effect unless [isTestRun] is true or the run service explicitly respects it.
 * @param graderGroupCountOverride Exact grader group count: any positive count for test/experiment
 *   runs, or 1-3 for normal production runs to trade evaluation independence for grading cost.
 *   Acceptance profiles always use the production count.
 * @param enforceProviderMonetaryCostCap Whether unknown provider pricing must fail a monetary cap.
 * @param ordinaryAgentTtlSeconds Optional lifetime of one ordinary worker in seconds (60-3600).
 *   Null keeps the runtime default. It bounds time, not credits or provider spend.
 */
@OptIn(ExperimentalSerializationApi::class)
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
    val llmBackend: LlmBackend = LlmBackend.OPENROUTER,
    val councilExecutionPurpose: CouncilExecutionPurpose = CouncilExecutionPurpose.STANDARD,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val acceptanceProfile: AcceptanceProfile = AcceptanceProfile.NORMAL,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val acceptanceDiagnosticMode: AcceptanceDiagnosticMode = AcceptanceDiagnosticMode.STRICT,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val codexReasoningEffort: CodexReasoningEffort = CodexReasoningEffort.HIGH,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val quantitativeToolObligation: QuantitativeToolObligation? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val codexRuntimePreset: com.achulkov.evomolior.core.config.CodexRuntimePreset? = null,
    val autoCompletionPolicy: RunAutoCompletionPolicy? = null,
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
    val budgetOverride: Double? = null,
    val graderGroupCountOverride: Int? = null,
    val enforceProviderMonetaryCostCap: Boolean = true,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val ordinaryAgentTtlSeconds: Int? = null
) {
    init {
        require(codexRuntimePreset == null || llmBackend == LlmBackend.CODEX_CHATGPT) {
            "Codex runtime presets require the codex-chatgpt backend"
        }
        codexRuntimePreset?.let { preset ->
            require(
                preset != com.achulkov.evomolior.core.config.CodexRuntimePreset.MIXED_FAMILIES ||
                    acceptanceProfile == AcceptanceProfile.NORMAL
            ) {
                "Existing acceptance profiles require Luna-only model authority"
            }
            require(preset.supportedModelIds.containsAll(modelOverrides.values)) {
                "Model overrides exceed the selected Codex preset"
            }
        }
        // Test runs allow a single group; full runs require at least 2 for competition.
        val minGroups = if (isTestRun) 1 else 2
        require(groupCount in minGroups..8) {
            if (isTestRun) "groupCount must be 1–8" else "groupCount must be 2–8"
        }
        require(groupProfiles.size == groupCount) { "groupProfiles size must equal groupCount" }
        require(rounds > 0) { "rounds must be positive" }
        autoCompletionPolicy?.let { policy ->
            require(rounds >= policy.consecutiveAcceptedRounds) {
                "rounds safety ceiling must be at least the auto-completion stability window"
            }
            require(!isTestRun) {
                "auto-completion is restricted to production runs with grader authority"
            }
        }
        require(scoringDimensions.isNotEmpty()) { "At least one scoring dimension is required" }
        require(
            councilExecutionPurpose != CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE ||
                llmBackend == LlmBackend.CODEX_CHATGPT
        ) {
            "Appendix B acceptance purpose requires the codex-chatgpt backend"
        }
        require(
            councilExecutionPurpose != CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE ||
                !isTestRun
        ) {
            "Appendix B acceptance purpose is restricted to production runs"
        }
        if (acceptanceProfile != AcceptanceProfile.NORMAL) {
            require(councilExecutionPurpose == CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE) {
                "Acceptance profile ${acceptanceProfile.id} requires the explicit " +
                    "appendix-b-acceptance Council execution purpose"
            }
            require(!isTestRun) {
                "Acceptance profile ${acceptanceProfile.id} is restricted to production Council runs"
            }
            require(continuationFromRunId.isNullOrBlank()) {
                "Acceptance profile ${acceptanceProfile.id} cannot bypass Council compilation through continuation"
            }
            require(autoCompletionPolicy == null) {
                "Acceptance profile ${acceptanceProfile.id} requires a fixed round horizon"
            }
            requireAcceptanceFinalOutputSpec(acceptanceProfile, finalOutputSpec)
            require(groupCount >= 2) {
                "Acceptance profile ${acceptanceProfile.id} requires at least two worker groups"
            }
        }
        if (acceptanceDiagnosticMode == AcceptanceDiagnosticMode.FULL_SWEEP_NON_ACCEPTING) {
            require(acceptanceProfile != AcceptanceProfile.NORMAL) {
                "The full non-accepting diagnostic sweep requires an acceptance profile"
            }
            require(councilExecutionPurpose == CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE) {
                "The full non-accepting diagnostic sweep requires Appendix-B Council purpose"
            }
        }
        when (acceptanceProfile) {
            AcceptanceProfile.NORMAL -> Unit
            AcceptanceProfile.RUNTIME_USABILITY_SMOKE -> require(rounds == 1) {
                "Runtime usability smoke requires exactly one round"
            }
            AcceptanceProfile.APPENDIX_B_FULL -> require(rounds == APPENDIX_B_FULL_ROUNDS) {
                "Full Appendix B acceptance requires exactly $APPENDIX_B_FULL_ROUNDS rounds"
            }
        }
        ordinaryAgentTtlSeconds?.let {
            require(it in 60..3_600) { "ordinaryAgentTtlSeconds must be between 60 and 3600" }
        }
        graderGroupCountOverride?.let {
            require(it > 0) { "graderGroupCountOverride must be positive" }
            require(isTestRun || (acceptanceProfile == AcceptanceProfile.NORMAL && it <= MAX_PRODUCTION_GRADER_GROUPS)) {
                "graderGroupCountOverride must be 1-$MAX_PRODUCTION_GRADER_GROUPS for normal production runs " +
                    "and is unavailable to acceptance profiles"
            }
        }
    }

    companion object {
        private const val APPENDIX_B_FULL_ROUNDS: Int = 6

        /** Largest operator-selected grader count for production runs (the production default). */
        const val MAX_PRODUCTION_GRADER_GROUPS: Int = 3
    }
}

/**
 * Controls whether a late strict-acceptance failure receives an exhaustive non-accepting sweep.
 *
 * The diagnostic branch cannot produce normal completion or accepted publication authority.
 */
@Serializable
enum class AcceptanceDiagnosticMode {
    STRICT,
    FULL_SWEEP_NON_ACCEPTING
}

/**
 * Validates the explicit receiver-facing output required by an acceptance profile.
 *
 * The path must remain relative and traversal-free. Textual authority is limited to the `text`
 * top-level type, `application/json`, and `application` subtypes ending in `+json`.
 *
 * @param acceptanceProfile Non-normal acceptance authority requesting the output.
 * @param finalOutputSpec Explicit operator/configuration-owned output contract.
 * @return The unchanged validated output contract.
 */
fun requireAcceptanceFinalOutputSpec(
    acceptanceProfile: AcceptanceProfile,
    finalOutputSpec: FinalOutputSpec?
): FinalOutputSpec {
    require(acceptanceProfile != AcceptanceProfile.NORMAL) {
        "Normal runs do not require acceptance-profile output validation"
    }
    val explicitOutput = requireNotNull(finalOutputSpec) {
        "Acceptance profile ${acceptanceProfile.id} requires an explicit FinalOutputSpec"
    }
    val normalizedOutputPath = explicitOutput.relativePath.replace('\\', '/')
    require(
        normalizedOutputPath.isNotBlank() &&
            !normalizedOutputPath.startsWith('/') &&
            normalizedOutputPath.split('/').none { it.isBlank() || it == "." || it == ".." }
    ) {
        "Acceptance profile ${acceptanceProfile.id} requires a safe receiver-facing output path"
    }
    val mediaType = explicitOutput.mediaType.substringBefore(';').trim().lowercase()
    require(
        mediaType.startsWith("text/") ||
            mediaType == "application/json" ||
            (mediaType.startsWith("application/") && mediaType.endsWith("+json"))
    ) {
        "Acceptance profile ${acceptanceProfile.id} requires a textual receiver-facing output"
    }
    return explicitOutput
}

/**
 * Fail-closed convergence policy for a run without a fixed requested round count.
 *
 * A round contributes only when exact grader authority resolves the same winner, mandatory tests
 * pass, and that winner's round output satisfies the final-output contract. Any failed predicate
 * or winner change resets the streak.
 *
 * @param consecutiveAcceptedRounds Stable accepted rounds required before completion.
 */
@Serializable
data class RunAutoCompletionPolicy(
    val consecutiveAcceptedRounds: Int = DEFAULT_CONSECUTIVE_ACCEPTED_ROUNDS
) {
    init {
        require(consecutiveAcceptedRounds in MIN_CONSECUTIVE_ACCEPTED_ROUNDS..MAX_CONSECUTIVE_ACCEPTED_ROUNDS) {
            "consecutiveAcceptedRounds must be 2 or 3"
        }
    }

    companion object {
        const val MIN_CONSECUTIVE_ACCEPTED_ROUNDS: Int = 2
        const val MAX_CONSECUTIVE_ACCEPTED_ROUNDS: Int = 3
        const val DEFAULT_CONSECUTIVE_ACCEPTED_ROUNDS: Int = 3
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
 * @param kind Structural classification used to route the final output writer.
 * @param validation Deterministic publication contract applied before the output is accepted.
 */
@Serializable
data class FinalOutputSpec(
    val relativePath: String,
    val mediaType: String,
    val description: String = "",
    val kind: FinalOutputKind = FinalOutputKind.GENERIC_FILE,
    val validation: FinalOutputValidationContract? = null
)

/**
 * Deterministic contract for an operator-facing generated output.
 *
 * Empty properties impose no additional requirement beyond basic media-type validity. The same
 * contract is enforced by the live writer retry loop and the CLI publication/reuse boundary.
 *
 * @param minimumPublicUrlCount Minimum number of distinct public HTTP(S) URLs in the artifact.
 * @param requireInteractiveHtml Whether HTML must expose an interactive browser control or script.
 * @param requiredCsvColumns Required CSV header names in declared order-independent form.
 * @param minimumCsvDataRows Minimum number of non-header CSV records.
 * @param exactCsvDataRows Exact number of non-header CSV records, when fixed.
 * @param evidenceUrlColumn CSV column that must contain a public URL in every data row.
 * @param rankColumn CSV rank column that must contain the sequence `1..N`.
 * @param requiredJsonRootKeys Required keys on the root JSON object.
 * @param minimumJsonArraySizes Minimum item counts for named root JSON arrays.
 * @param requiredJsonArrayItemFields Required object fields for every item in named root arrays.
 * @param requiredMarkdownSectionSignals Case-insensitive signals required in Markdown headings.
 * @param requireMarkdownAccessDate Whether Markdown must include an ISO `YYYY-MM-DD` access date.
 * @param csvRankedEvidence Evidence-density rules for the leading rows of a ranked CSV.
 * @param csvSourceMetadata Source metadata and access-date requirements for CSV records.
 * @param researchClaimSupportCoverage Claim-support coverage required when a claim ledger is available.
 * @param jupyterNotebookSource Optional static source contract for a Jupyter notebook deliverable.
 * @param maximumWordCount Maximum whitespace-delimited words, including code and headings.
 * @param minimumWordCount Minimum whitespace-delimited words; guards against silently condensed reports.
 * @param exactMarkdownHeadings Exact ordered headings, ignoring heading-level markers only.
 * @param requiredTextStatements Literal statements required in the report (case-insensitive).
 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class FinalOutputValidationContract(
    val minimumPublicUrlCount: Int = 0,
    val requireInteractiveHtml: Boolean = false,
    val requiredCsvColumns: List<String> = emptyList(),
    val minimumCsvDataRows: Int? = null,
    val exactCsvDataRows: Int? = null,
    val evidenceUrlColumn: String? = null,
    val rankColumn: String? = null,
    val requiredJsonRootKeys: List<String> = emptyList(),
    val minimumJsonArraySizes: Map<String, Int> = emptyMap(),
    val requiredJsonArrayItemFields: Map<String, List<String>> = emptyMap(),
    val requiredMarkdownSectionSignals: List<String> = emptyList(),
    val requireMarkdownAccessDate: Boolean = false,
    val csvRankedEvidence: CsvRankedEvidenceContract? = null,
    val csvSourceMetadata: CsvSourceMetadataContract? = null,
    val researchClaimSupportCoverage: ResearchClaimSupportCoverageContract? = null,
    val jupyterNotebookSource: JupyterNotebookSourceContract? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val maximumWordCount: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val exactMarkdownHeadings: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val requiredTextStatements: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val minimumWordCount: Int? = null,
) {
    init {
        require(minimumPublicUrlCount >= 0) { "minimumPublicUrlCount must be non-negative" }
        minimumCsvDataRows?.let { require(it >= 0) { "minimumCsvDataRows must be non-negative" } }
        exactCsvDataRows?.let { require(it >= 0) { "exactCsvDataRows must be non-negative" } }
        maximumWordCount?.let { require(it > 0) { "maximumWordCount must be positive" } }
        minimumWordCount?.let { minimum ->
            require(minimum > 0) { "minimumWordCount must be positive" }
            require(maximumWordCount == null || minimum <= maximumWordCount) {
                "minimumWordCount must not exceed maximumWordCount"
            }
        }
        require((exactMarkdownHeadings + requiredTextStatements).none(String::isBlank)) {
            "Exact headings and required statements must not be blank"
        }
        minimumJsonArraySizes.forEach { (key, size) ->
            require(key.isNotBlank()) { "minimumJsonArraySizes keys must not be blank" }
            require(size >= 0) { "minimumJsonArraySizes values must be non-negative" }
        }
    }
}

/**
 * Deterministic static source requirements for a Jupyter notebook deliverable.
 *
 * Executable behavior remains the responsibility of sandboxed promotion and acceptance tests.
 * This contract prevents a structurally valid but irrelevant or pre-executed notebook from
 * crossing the publication boundary.
 *
 * @param minimumCodeCellCount Minimum number of code cells.
 * @param requiredCodeSignals Case-insensitive source fragments required across code cells.
 * @param requiredMarkdownSignals Case-insensitive source fragments required across Markdown cells.
 * @param forbiddenCodeSignals Case-insensitive fragments forbidden across code cells.
 * @param requireCleanCodeCells Whether code cells must have null execution counts and no outputs.
 * @param requireUniqueCellIdsForNbformat45 Whether nbformat 4.5+ cells require unique nonblank IDs.
 */
@Serializable
data class JupyterNotebookSourceContract(
    val minimumCodeCellCount: Int = 1,
    val requiredCodeSignals: List<String> = emptyList(),
    val requiredMarkdownSignals: List<String> = emptyList(),
    val forbiddenCodeSignals: List<String> = emptyList(),
    val requireCleanCodeCells: Boolean = true,
    val requireUniqueCellIdsForNbformat45: Boolean = true
) {
    init {
        require(minimumCodeCellCount > 0) {
            "minimumCodeCellCount must be positive"
        }
        require(
            (requiredCodeSignals + requiredMarkdownSignals + forbiddenCodeSignals)
                .none(String::isBlank)
        ) {
            "Jupyter notebook source signals must not be blank"
        }
    }
}

/**
 * Evidence requirements for the leading records in a ranked CSV publication.
 *
 * @param rankColumn Column containing the integer rank used to identify the leading records.
 * @param evidenceUrlColumn Column containing one or more public HTTP(S) evidence URLs.
 * @param topRows Number of leading ranked records to validate.
 * @param minimumUrlsPerRow Minimum distinct evidence URLs required in each leading record.
 * @param minimumDistinctUrls Minimum distinct evidence URLs required across all leading records.
 * @param conceptColumn Optional label column included in record-specific validation errors.
 */
@Serializable
data class CsvRankedEvidenceContract(
    val rankColumn: String,
    val evidenceUrlColumn: String,
    val topRows: Int,
    val minimumUrlsPerRow: Int = 1,
    val minimumDistinctUrls: Int = 0,
    val conceptColumn: String? = null
) {
    init {
        require(rankColumn.isNotBlank()) { "csvRankedEvidence.rankColumn must not be blank" }
        require(evidenceUrlColumn.isNotBlank()) { "csvRankedEvidence.evidenceUrlColumn must not be blank" }
        require(topRows > 0) { "csvRankedEvidence.topRows must be positive" }
        require(minimumUrlsPerRow >= 0) { "csvRankedEvidence.minimumUrlsPerRow must be non-negative" }
        require(minimumDistinctUrls >= 0) { "csvRankedEvidence.minimumDistinctUrls must be non-negative" }
        conceptColumn?.let {
            require(it.isNotBlank()) { "csvRankedEvidence.conceptColumn must not be blank" }
        }
    }
}

/**
 * Optional provenance metadata requirements for CSV publications.
 *
 * A configured column must exist and contain a non-blank value in every record. Access dates must
 * contain an ISO `YYYY-MM-DD` date. The two requirements may point at the same combined column.
 *
 * @param sourceMetadataColumn Column containing source title, publisher, observation, or provenance metadata.
 * @param accessDateColumn Column containing an ISO `YYYY-MM-DD` source access date.
 */
@Serializable
data class CsvSourceMetadataContract(
    val sourceMetadataColumn: String? = null,
    val accessDateColumn: String? = null
) {
    init {
        sourceMetadataColumn?.let {
            require(it.isNotBlank()) { "csvSourceMetadata.sourceMetadataColumn must not be blank" }
        }
        accessDateColumn?.let {
            require(it.isNotBlank()) { "csvSourceMetadata.accessDateColumn must not be blank" }
        }
        require(sourceMetadataColumn != null || accessDateColumn != null) {
            "csvSourceMetadata must declare at least one column"
        }
    }
}

/**
 * Claim-level evidence coverage required when validation receives a research claim ledger.
 *
 * A claim is supported when its `supporting_sources` array contains at least one non-blank source
 * reference. If no ledger is available, this optional gate is not evaluated.
 *
 * @param minimumCoverage Inclusive supported-claim ratio in the range `0.0..1.0`.
 * @param usedInFinalAnswerOnly Whether the denominator is limited to claims marked `used_in_final_answer`.
 */
@Serializable
data class ResearchClaimSupportCoverageContract(
    val minimumCoverage: Double,
    val usedInFinalAnswerOnly: Boolean = false
) {
    init {
        require(minimumCoverage in 0.0..1.0) {
            "researchClaimSupportCoverage.minimumCoverage must be between 0.0 and 1.0"
        }
    }
}

/**
 * Structural classification for a run's operator-facing final output.
 *
 * The kind is intentionally separate from MIME type so the engine can route prompt contracts
 * without inferring intent from human-readable descriptions.
 */
@Serializable
enum class FinalOutputKind {
    HTML_ARTICLE,
    GENERIC_FILE,
    PATCH_BUNDLE
}

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
