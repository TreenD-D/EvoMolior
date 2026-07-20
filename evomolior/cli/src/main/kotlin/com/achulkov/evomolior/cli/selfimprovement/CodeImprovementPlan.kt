package com.achulkov.evomolior.cli.selfimprovement

import kotlinx.serialization.Serializable

/**
 * Machine-readable code-improvement plan emitted by the fast CLI pipeline.
 *
 * The current fast path uses this plan to describe a small, concrete edit set that can be
 * applied into an isolated repo copy before the next stage starts.
 */
@Serializable
internal data class CodeImprovementPlan(
    val title: String = "",
    val problem: String = "",
    val affectedFiles: List<String> = emptyList(),
    val proposedChanges: List<CodeImprovementProposedChange> = emptyList(),
    val verification: CodeImprovementVerification? = null,
    val risk: CodeImprovementRisk? = null
)

/**
 * One concrete change inside a code-improvement plan.
 */
@Serializable
internal data class CodeImprovementProposedChange(
    val changeId: String = "",
    val description: String = "",
    val implementationDetails: CodeImprovementImplementationDetails? = null
)

/**
 * Location and edit instructions for a proposed change.
 */
@Serializable
internal data class CodeImprovementImplementationDetails(
    val location: String = "",
    val method: String = "",
    val changes: List<CodeImprovementChangeInstruction> = emptyList()
)

/**
 * A single edit instruction.
 */
@Serializable
internal data class CodeImprovementChangeInstruction(
    val type: String = "",
    val detail: String = "",
    val codeSnippet: String? = null
)

/**
 * Verification guidance included in the emitted plan.
 */
@Serializable
internal data class CodeImprovementVerification(
    val steps: List<String> = emptyList(),
    val expectedOutcome: String = ""
)

/**
 * Risk notes included in the emitted plan.
 */
@Serializable
internal data class CodeImprovementRisk(
    val level: String = "",
    val details: List<String> = emptyList()
)

/**
 * Result of applying a code-improvement plan into a repo copy.
 *
 * @param planPath Source plan file that was consumed.
 * @param repoRoot Repo copy that was modified.
 * @param changedFiles Repository-relative paths that were rewritten.
 * @param reportPath Durable application report path, if written.
 */
@Serializable
internal data class CodeImprovementApplicationReport(
    val planPath: String,
    val repoRoot: String,
    val changedFiles: List<String>,
    val reportPath: String
)
