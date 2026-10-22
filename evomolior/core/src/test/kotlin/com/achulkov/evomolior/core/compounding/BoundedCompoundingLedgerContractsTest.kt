package com.achulkov.evomolior.core.compounding

import com.achulkov.evomolior.core.atlas.InstrumentalRewardType
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BoundedCompoundingLedgerContractsTest {
    @Test
    fun `all ledger records are hash bound and require explicit bounded dispositions`() {
        val workItem = RepairDiagnosticWorkItemV1(
            workItemId = "work-item",
            diagnosticId = "diagnostic",
            diagnosticHash = "a".repeat(64),
            runId = "run",
            groupId = "group",
            nodeId = "node",
            opportunityId = "opportunity",
            opportunityHash = "b".repeat(64),
            nextRound = 2,
            disposition = RepairDiagnosticDisposition.DEFERRED_WITH_REASON,
            dispositionReason = "capacity held for the next closure window"
        ).withComputedHash()
        val purpose = RewardSpendPurposeV1(
            kind = RewardSpendPurposeKind.REPAIR_ACTION,
            targetId = "repair-action",
            targetHash = "c".repeat(64),
            actionId = "run-bounded-repair"
        ).withComputedHash()
        val spend = RewardSpendRecordV1(
            spendId = "spend",
            runId = "run",
            groupId = "group",
            rewardApplicationHash = "d".repeat(64),
            rewardType = InstrumentalRewardType.TOOL_COMPUTE_CREDITS,
            amountSpent = 1.0,
            spentRound = 2,
            purpose = purpose
        ).withComputedHash()

        assertTrue(workItem.hasValidHash())
        assertTrue(purpose.hasValidHash())
        assertTrue(spend.hasValidHash())
        assertFailsWith<IllegalArgumentException> {
            RepairDiagnosticWorkItemV1(
                workItemId = "invalid",
                diagnosticId = "diagnostic",
                diagnosticHash = "a".repeat(64),
                runId = "run",
                groupId = "group",
                nodeId = "node",
                opportunityId = "opportunity",
                opportunityHash = "b".repeat(64),
                nextRound = 2,
                disposition = RepairDiagnosticDisposition.DEFERRED_WITH_REASON
            )
        }
    }
}
