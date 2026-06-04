package com.achulkov.evokammer.core.contract

import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.core.model.Role
import kotlinx.serialization.Serializable

/**
 * The complete configuration produced by the Genesis Council.
 *
 * This is the single source of truth for a run. It is produced once, before any competing
 * group is spawned, and is immutable for the duration of the run.
 *
 * @param runId The run this StartingField initialises.
 * @param objective The overarching problem to solve (human-supplied).
 * @param globalBudgetCeiling Total budget available across all groups for the entire run.
 * @param generationCeiling Maximum number of agent generations to run.
 * @param groupCount Number of competing groups.
 * @param groupProfiles Profile assignment per group (groupId → profile type).
 * @param ringTopology Ring topology of group IDs (ordered list defining the ring).
 * @param checkinMilestones Generation numbers at which check-in gates open.
 * @param leakIntervalGenerations How many generations between automatic leak deliveries.
 * @param dispatchLagGenerations How many generations a dispatch sits in the relay before delivery.
 * @param graderGroupCount Number of Grader Group civilisations.
 * @param rolePrompts Role-specific system prompt templates keyed by [Role].
 * @param modelAssignment LLM model assignment keyed by [Role] (OpenRouter model strings).
 * @param modelPools Candidate OpenRouter model pools keyed by role name. The runtime draws
 *   one model per agent spawn from the pool when present, then falls back to
 *   [modelAssignment] for compatibility.
 * @param kgSchema JSON-serialised typed KG schema (entities, relations, constraints).
 * @param actionCosts Cost overrides per ActionType (for custom council-designed economics).
 * @param scoreNormalization Council-defined per-dimension max-score overrides used by the
 *   scoring pipeline when the deterministic sealed-suite evaluator normalizes raw scores.
 * @param nodeGraph JSON-serialised knowledge node graph (ResourceMapEngine seed).
 * @param primordialKb Seed KB documents to pre-populate each group's KB at genesis.
 * @param primordialKgFacts Seed KG facts to pre-populate each group's KG at genesis.
 * @param primordialTools Seed tools to pre-populate each group's Tool Registry at genesis.
 * @param inputArtifacts User-supplied run-start artifacts available read-only to the groups.
 * @param continuedFromRunId Optional source run that this starting field continues from.
 * @param deathNoteSchema JSON schema for the expected Death Note structure.
 * @param legacyProtocolTemplate JSON template for Legacy Protocol extraction.
 * @param sealedTestSuiteJson JSON-serialised sealed test suite produced by CouncilSynthesis.
 * @param sealedTestSuiteHash SHA-256 hash of the sealed test suite (for integrity verification).
 * @param norms Founding norms and coordination conventions injected into all role prompts.
 * @param stageContents Constraint-framed stage disclosure content per stage index.
 * @param consistencyReport Human-readable report from the CouncilSynthesis describing the
 *   consistency check performed on all role outputs. Empty if the council was not invoked
 *   or if the synthesis did not produce one.
 */
@Serializable
data class StartingField(
    val runId: String,
    val objective: String,
    val globalBudgetCeiling: Double,
    val generationCeiling: Int,
    val groupCount: Int,
    val groupProfiles: Map<String, GroupProfileType>,
    val ringTopology: List<String>,
    val checkinMilestones: List<Int>,
    val leakIntervalGenerations: Int,
    val dispatchLagGenerations: Int,
    val graderGroupCount: Int,
    val rolePrompts: Map<String, String>,
    val modelAssignment: Map<String, String>,
    val modelPools: Map<String, List<String>> = emptyMap(),
    val kgSchema: String,
    val actionCosts: Map<String, Double>,
    val scoreNormalization: Map<String, Double> = emptyMap(),
    val nodeGraph: String,
    val primordialKb: List<SeedKBDocument>,
    val primordialKgFacts: List<SeedKGFact>,
    val primordialTools: List<SeedTool>,
    val inputArtifacts: List<RunInputArtifact> = emptyList(),
    val continuedFromRunId: String? = null,
    val deathNoteSchema: String,
    val legacyProtocolTemplate: String,
    val sealedTestSuiteJson: String = "",
    val sealedTestSuiteHash: String,
    val norms: String,
    val stageContents: List<String>,
    /** Consistency report from CouncilSynthesis (B-3). Empty string for default/fallback fields. */
    val consistencyReport: String = ""
) {
    companion object {
        /**
         * Returns a minimal [StartingField] with sensible defaults.
         * Used as a fallback when the Genesis Council output cannot be parsed,
         * and as the base configuration for test runs.
         *
         * @param objective The run objective.
         * @param runId The run ID (default: random UUID placeholder).
         * @param groupCount Number of competing groups. Default 2; use 1 for a test run.
         * @param groupProfilesMap Optional group-id → profile override. If null, defaults
         *   are generated for [groupCount] groups using Library/Factory cycle.
         * @param budgetCeiling Global budget ceiling override. Default 10 000; use lower
         *   values (e.g. 200.0) for short diagnostic runs.
         * @param generationCeiling Maximum agent generations override.
         */
        fun default(
            objective: String,
            runId: String = "default-run",
            groupCount: Int = 2,
            groupProfilesMap: Map<String, GroupProfileType>? = null,
            budgetCeiling: Double = 10_000.0,
            generationCeiling: Int = 100
        ): StartingField {
            val resolvedGroupCount = groupCount.coerceAtLeast(1)
            val resolvedProfiles = groupProfilesMap ?: (0 until resolvedGroupCount).associate { idx ->
                "group-$idx" to GroupProfileType.entries[idx % GroupProfileType.entries.size]
            }
            val resolvedRing = resolvedProfiles.keys.toList()
            return StartingField(
                runId = runId,
                objective = objective,
                globalBudgetCeiling = budgetCeiling,
                generationCeiling = generationCeiling,
                groupCount = resolvedGroupCount,
                groupProfiles = resolvedProfiles,
                ringTopology = resolvedRing,
                checkinMilestones = listOf(10, 25, 50, 75, 100),
                leakIntervalGenerations = 20,
                dispatchLagGenerations = 5,
                graderGroupCount = if (resolvedGroupCount == 1) 0 else 3,
                rolePrompts = emptyMap(),
                modelAssignment = emptyMap(),
                modelPools = emptyMap(),
                kgSchema = "{}",
                actionCosts = emptyMap(),
                scoreNormalization = emptyMap(),
                nodeGraph = "{}",
                primordialKb = emptyList(),
                primordialKgFacts = emptyList(),
                primordialTools = emptyList(),
                inputArtifacts = emptyList(),
                continuedFromRunId = null,
                deathNoteSchema = "{}",
                legacyProtocolTemplate = "{}",
                sealedTestSuiteJson = "",
                sealedTestSuiteHash = "",
                norms = "",
                stageContents = emptyList(),
                consistencyReport = ""
            )
        }
    }
}

/** A seed KB document for primordial substrate population. */
@Serializable
data class SeedKBDocument(
    val id: String,
    val content: String,
    val domain: String
)

/** A seed KG fact for primordial substrate population. */
@Serializable
data class SeedKGFact(
    val id: String,
    val subject: String,
    val relation: String,
    val `object`: String,
    val confidence: Double,
    val domain: String
)

/** A seed tool for primordial substrate population. */
@Serializable
data class SeedTool(
    val id: String,
    val name: String,
    val description: String,
    val inputSchemaJson: String,
    val outputSchemaJson: String,
    val code: String,
    val domain: String
)

/** Kind of run-start input artifact provided by the user. */
@Serializable
enum class RunInputArtifactKind {
    TEXT,
    IMAGE,
    BINARY
}

/**
 * User-supplied artifact that is available to the groups during the run.
 *
 * The binary bytes are persisted separately in the run artifact directory.
 * This contract only carries the canonical metadata and preview information.
 *
 * @param relativePath Path of the artifact within the uploaded bundle.
 * @param sourceName Original file name or logical source label.
 * @param mimeType MIME type, if known.
 * @param sizeBytes Size in bytes.
 * @param sha256 Lowercase hex SHA-256 hash of the persisted bytes.
 * @param kind Artifact kind.
 * @param textPreview Optional derived text preview for binary inputs.
 */
@Serializable
data class RunInputArtifact(
    val relativePath: String,
    val sourceName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val kind: RunInputArtifactKind,
    val textPreview: String? = null
)
