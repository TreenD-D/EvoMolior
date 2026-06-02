package com.achulkov.evokammer.agents.factory

import com.achulkov.evokammer.core.budget.BudgetLedger
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
    budgetLedger: BudgetLedger
): String {
    val trimmedResult = result.trim()
    val snapshot = budgetLedger.snapshot()
    val actionCount = snapshot.actionLog.size

    if (trimmedResult.isBlank() || trimmedResult.contains(EMPTY_ASSISTANT_RECOVERY_TEXT)) {
        val reason = "no-progress completion: empty-assistant recovery survived to the end of the run"
        Napier.w("[$agentLabel] warning-only no-progress completion: $reason")
        return "[no-progress completion: ${if (trimmedResult.isBlank()) "blank output" else "empty-assistant recovery"}]"
    }

    if (actionCount == 0) {
        Napier.w(
            "[$agentLabel] completed without tool/write actions; preserving final answer as a warning-only completion"
        )
    }

    return result
}
