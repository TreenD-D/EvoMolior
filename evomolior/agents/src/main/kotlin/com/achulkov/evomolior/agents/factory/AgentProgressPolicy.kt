package com.achulkov.evomolior.agents.factory

import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.budget.BudgetDeduction
import com.achulkov.evomolior.core.contract.AgentBootContext
import com.achulkov.evomolior.core.model.ActionType
import io.github.aakira.napier.Napier

const val EMPTY_ASSISTANT_RECOVERY_TEXT =
    "The previous assistant response was empty. Respond with either a tool call or a concise answer."

/**
 * Validates the final agent output against the no-progress recovery policy.
 *
 * Empty-assistant recovery and blank outputs are treated as warning-only no-progress completions.
 * The caller receives a small placeholder string so downstream archiving and UI paths still have
 * a durable artefact to show, but the run is not aborted.
 *
 * @param agentLabel Human-readable agent label used in logs and exception text.
 * @param result Final text returned by the agent.
 * @param budgetLedger The live budget ledger used to detect whether any tool/write actions occurred.
 * @return The original [result] if the output is usable.
 */
fun requireProductiveAgentOutcome(
    agentLabel: String,
    result: String,
    budgetLedger: BudgetLedger,
    textOnlyCompatibilityMode: Boolean = false
): String {
    val trimmedResult = result.trim()
    val snapshot = budgetLedger.snapshot()
    val actionCount = countProductiveAgentActions(snapshot.actionLog)

    if (trimmedResult.isBlank() || trimmedResult.contains(EMPTY_ASSISTANT_RECOVERY_TEXT)) {
        val reason = "no-progress completion: empty-assistant recovery survived to the end of the run"
        Napier.w("[$agentLabel] warning-only no-progress completion: $reason")
        return "[no-progress completion: ${if (trimmedResult.isBlank()) "blank output" else "empty-assistant recovery"}]"
    }

    if (actionCount == 0) {
        if (textOnlyCompatibilityMode) {
            Napier.i(
                "[$agentLabel] completed without tool/write actions in text-only compatibility mode; " +
                    "preserving final answer as a compatibility completion"
            )
        } else {
            Napier.w(
                "[$agentLabel] completed without tool/write actions; preserving final answer as a warning-only completion"
            )
        }
    }

    return result
}

/**
 * Counts durable substrate, read, tool, and score actions while excluding model-completion ledger
 * entries. [ActionType.LLM_CALL] is runtime accounting, not agent progress.
 *
 * @param actionLog Budget ledger entries recorded during an agent turn.
 */
fun countProductiveAgentActions(actionLog: List<BudgetDeduction>): Int =
    actionLog.count { deduction -> deduction.actionType != ActionType.LLM_CALL }

/**
 * Returns true when this agent was spawned in a run that explicitly requires in-run source acquisition.
 *
 * @param bootContext Agent boot context.
 */
fun requiresRuntimeSourceAcquisition(bootContext: AgentBootContext): Boolean =
    bootContext.inputArtifacts.any { artifact ->
        artifact.relativePath.equals("source_acquisition_contract.md", ignoreCase = true)
    }

/**
 * Returns true when a tool-capable ordinary agent should receive one generic no-action recovery
 * turn instead of immediately preserving a low-trust fallback.
 *
 * @param bootContext Agent boot context.
 * @param previousResult The prior model text.
 */
fun requiresGenericNoActionRecovery(
    bootContext: AgentBootContext,
    previousResult: String
): Boolean {
    if (requiresRuntimeSourceAcquisition(bootContext)) return false
    val promptContext = "${bootContext.rolePrompt}\n${bootContext.stageDisclosure}"
    if (promptContext.contains("Final Deliverable Writer", ignoreCase = true)) return false
    if (promptContext.contains("ROUND SUBMISSION SUMMARY", ignoreCase = true)) return false
    if (bootContext.stageDisclosure.contains("submission summary", ignoreCase = true)) return false

    val trimmed = previousResult.trim()
    if (trimmed.isBlank()) return false
    if (trimmed.length > 1_500) return false
    if (trimmed.contains("```")) return false
    if (trimmed.contains("ROUND SUBMISSION SUMMARY", ignoreCase = true)) return false

    val lower = trimmed.lowercase()
    val futureIntentMarkers = listOf(
        "let me ",
        "i will ",
        "i'll ",
        "now i ",
        "i need to ",
        "i should ",
        "next i ",
        "will now ",
        "let's "
    )
    return futureIntentMarkers.any { marker -> lower.contains(marker) } &&
        listOf("read", "search", "screen", "check", "inspect", "parse", "write", "submit").any { verb ->
            lower.contains(verb)
        }
}

/**
 * Builds a generic recovery prompt for agents that ended with intent text and no durable action.
 *
 * @param bootContext Agent boot context.
 * @param previousResult The previous plain-text result, used only as diagnostic context.
 */
fun buildGenericNoActionRecoveryPrompt(
    bootContext: AgentBootContext,
    previousResult: String
): String = buildString {
    appendLine("=== RUNTIME RECOVERY: INTENT TEXT WITHOUT ACTION ===")
    appendLine("Your previous response ended with intent text but recorded no durable write or budgeted action.")
    appendLine("Do not explain what you will do. Do the smallest concrete action now.")
    appendLine()
    appendLine("Required sequence:")
    val firstArtifact = bootContext.inputArtifacts.firstOrNull()
    if (firstArtifact != null) {
        appendLine("1. If you still need the uploaded artifact, call read_run_artifact(relativePath=\"${firstArtifact.relativePath}\", listOnly=false).")
        appendLine("2. Immediately call write_staging(type=\"PLAN_UPDATE\", ...) with one of:")
        appendLine("   - candidate-level decisions or extraction notes from the uploaded artifact;")
        appendLine("   - a table-completeness/checklist artifact listing IDs already covered and IDs still missing;")
        appendLine("   - a blocker headed RUNTIME RECOVERY BLOCKER that names the exact missing evidence or failed next step.")
        appendLine("3. If you can support an explicit claim, also call emit_fact with provenance. If not, include KG ABSTENTION RATIONALE in the PLAN_UPDATE.")
        appendLine("4. Finish with write_death_note.")
    } else {
        appendLine("1. Call write_staging(type=\"PLAN_UPDATE\", ...) with a concrete contribution or a blocker headed RUNTIME RECOVERY BLOCKER.")
        appendLine("2. If a claim is supported, call emit_fact. If no claim is supported, include KG ABSTENTION RATIONALE.")
        appendLine("3. Finish with write_death_note.")
    }
    appendLine()
    appendLine("Never stop with another \"let me\", \"I will\", or \"now I\" response.")
    previousResult.trim().takeIf { it.isNotBlank() }?.let { text ->
        appendLine()
        appendLine("Previous no-action response excerpt:")
        appendLine(text.take(800))
    }
    bootContext.stageDisclosure.takeIf { it.isNotBlank() }?.let { disclosure ->
        appendLine()
        appendLine("Stage disclosure:")
        appendLine(disclosure.take(1200))
    }
}

/**
 * Builds a final recovery prompt after a prior recovery turn still failed to produce a durable
 * substrate write. This prompt intentionally forbids reads/searches so the model has only one
 * productive path left: stage a concrete note or explicit blocker.
 *
 * @param bootContext Agent boot context.
 * @param previousResult The previous no-action recovery result.
 */
fun buildWriteOnlyNoActionRecoveryPrompt(
    bootContext: AgentBootContext,
    previousResult: String
): String = buildString {
    appendLine("=== FINAL RUNTIME RECOVERY: WRITE ONLY ===")
    appendLine("Your previous recovery turn still recorded no durable write.")
    appendLine("Do not read, search, inspect, parse, or explain a plan.")
    appendLine("Your only acceptable next action is write_staging(type=\"PLAN_UPDATE\", ...), followed by write_death_note.")
    appendLine()
    appendLine("Write one of these now:")
    appendLine("- concrete candidate-level findings or screening notes already visible from prior context;")
    appendLine("- a table-completeness checklist with covered IDs and missing IDs;")
    appendLine("- a blocker headed FINAL RUNTIME RECOVERY BLOCKER that states the exact evidence still missing.")
    appendLine()
    appendLine("Include KG ABSTENTION RATIONALE if you cannot emit a supported KG fact.")
    previousResult.trim().takeIf { it.isNotBlank() }?.let { text ->
        appendLine()
        appendLine("Previous failed recovery excerpt:")
        appendLine(text.take(800))
    }
    bootContext.stageDisclosure.takeIf { it.isNotBlank() }?.let { disclosure ->
        appendLine()
        appendLine("Stage disclosure:")
        appendLine(disclosure.take(1200))
    }
}

/**
 * Builds a bounded recovery prompt for source-acquisition agents that ended with no durable action.
 *
 * @param bootContext Agent boot context.
 * @param previousResult The previous plain-text result, used only as diagnostic context.
 */
fun buildSourceAcquisitionNoActionRecoveryPrompt(
    bootContext: AgentBootContext,
    previousResult: String
): String = buildString {
    appendLine("=== RUNTIME RECOVERY: NO ACTION WAS RECORDED ===")
    appendLine("Your previous response did not call any durable write or budgeted action.")
    appendLine("This run includes source_acquisition_contract.md, so a plain-text plan is not acceptable.")
    appendLine()
    appendLine("Do this now, in order:")
    appendLine("1. Call fetch_source for one relevant public source URL for the run objective.")
    appendLine("2. If fetch_source succeeds, immediately call write_staging(type=\"PLAN_UPDATE\", ...) with:")
    appendLine("   - the exact source URL,")
    appendLine("   - the extracted user complaint, market, store, or tool signal,")
    appendLine("   - one concrete implication for fast micro-SaaS/app hypothesis generation.")
    appendLine("3. If fetch_source is blocked or fails, immediately call write_staging(type=\"PLAN_UPDATE\", ...) with heading SOURCE ACQUISITION BLOCKER and list attempted URL, failure, and next URL candidates.")
    appendLine("4. Then call write_death_note.")
    appendLine()
    appendLine("Good first URLs for this run may include public Reddit app/productivity/dev-tool threads, Google Play app pages, Apple App Store pages, Product Hunt pages, or Indie Hackers posts.")
    appendLine("Do not stop with analysis or intent text. Use tools.")
    previousResult.trim().takeIf { it.isNotBlank() }?.let { text ->
        appendLine()
        appendLine("Previous no-action response excerpt:")
        appendLine(text.take(800))
    }
    bootContext.stageDisclosure.takeIf { it.isNotBlank() }?.let { disclosure ->
        appendLine()
        appendLine("Stage disclosure:")
        appendLine(disclosure.take(1200))
    }
}
