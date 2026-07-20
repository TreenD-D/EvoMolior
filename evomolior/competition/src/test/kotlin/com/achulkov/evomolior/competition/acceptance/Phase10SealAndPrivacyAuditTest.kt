package com.achulkov.evomolior.competition.acceptance

import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.dispatch.DispatchMessage
import com.achulkov.evomolior.competition.leaks.LeakOracleConfig
import com.achulkov.evomolior.competition.scoring.BlindSubmissionGate
import com.achulkov.evomolior.competition.scoring.GroupRoundResult
import com.achulkov.evomolior.competition.scoring.IncompleteBlindSubmissionException
import com.achulkov.evomolior.core.contract.LeakPacket
import com.achulkov.evomolior.core.model.ScoringDimension
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Deterministic Phase 10 audit depth for the privacy and blind-score authority boundaries.
 */
class Phase10SealAndPrivacyAuditTest {
    @Test
    fun `fifty transformed leaks retain no explicit source trace`() = runTest {
        val sourceGroupId = "group-classified-origin"
        val targetGroupId = "group-audit-recipient"
        val markers = listOf(
            sourceGroupId,
            "agent-classified-99",
            "/classified/evidence/path",
            "VERBATIM_PRIVATE_SENTENCE",
            "source@example.invalid"
        )
        val delivered = mutableListOf<LeakPacket>()
        val coordinator = GroupCoordinator(
            groupIds = listOf(sourceGroupId, targetGroupId),
            dimensions = listOf(
                ScoringDimension("accuracy", "Accuracy", "Transformed evidence accuracy.", 1.0)
            ),
            leakConfig = LeakOracleConfig(
                maxLeaksPerGroupPerRound = 50,
                maxMigrationPerPairPerRound = 50,
                corruptionProbability = 0.0,
                allowedDomains = setOf("general")
            ),
            privacyTransformerOverride = { packet ->
                packet.copy(
                    content = "ANONYMIZED_AGGREGATE_${packet.leakId.substringAfterLast('-')}: " +
                        "a bounded finding remains after source-trace removal."
                )
            }
        )
        coordinator.configureDeliveryConsumer { _, _, message, _ ->
            if (message is DispatchMessage.Leak) delivered += message.packet
        }
        coordinator.onRoundStart(0)

        repeat(50) { index ->
            assertTrue(
                coordinator.deliverLeak(
                    sourceGroupId,
                    targetGroupId,
                    LeakPacket(
                        leakId = "phase-10-privacy-$index",
                        content = markers.joinToString(" | "),
                        domain = "general",
                        recipientGroupId = targetGroupId,
                        deliveredAtGeneration = 0,
                        runId = "phase-10-privacy-audit"
                    )
                )
            )
        }

        assertEquals(50, delivered.size)
        delivered.forEach { packet ->
            markers.forEach { marker ->
                assertFalse(
                    packet.content.contains(marker, ignoreCase = true),
                    "${packet.leakId} retained source marker '$marker'"
                )
            }
        }
    }

    @Test
    fun `one hundred checkins reveal nothing before close and emit intact seal audits`() = runTest {
        repeat(100) { roundIndex ->
            val gate = BlindSubmissionGate(listOf("group-0", "group-1"))
            val reveal = async { gate.revealedScores.first() }
            val audit = async { gate.sealAuditEvents.first() }
            yield()

            gate.openGate(roundIndex)
            gate.submitGroupResults("group-0", result("group-0", roundIndex, 8.0))
            val forceReveal = roundIndex % 2 == 1
            if (!forceReveal) {
                gate.submitGroupResults("group-1", result("group-1", roundIndex, 7.0))
            }
            yield()
            assertFalse(reveal.isCompleted, "Round $roundIndex revealed a score before gate close.")
            assertFalse(audit.isCompleted, "Round $roundIndex emitted a seal audit before gate close.")

            if (forceReveal) {
                assertFailsWith<IncompleteBlindSubmissionException> {
                    gate.closeAndReveal(forceReveal = true)
                }
            } else {
                gate.closeAndReveal()
            }
            val sealed = audit.await()
            assertEquals(roundIndex, sealed.roundIndex)
            assertEquals(2, sealed.expectedGroupCount)
            assertEquals(if (forceReveal) 1 else 2, sealed.sealedGroupCount)
            assertEquals(if (forceReveal) listOf("group-1") else emptyList(), sealed.missingGroupIds)
            assertEquals(forceReveal, sealed.forcedReveal)
            assertTrue(sealed.sealLatencyMs >= 0L)
            if (forceReveal) {
                assertFalse(reveal.isCompleted, "Incomplete round $roundIndex emitted authoritative scores.")
                reveal.cancel()
            } else {
                assertEquals(roundIndex, reveal.await().roundIndex)
            }
        }
    }

    private fun result(groupId: String, roundIndex: Int, score: Double) = GroupRoundResult(
        groupId = groupId,
        roundIndex = roundIndex,
        dimensionScores = mapOf("accuracy" to score),
        failureModesTriggered = emptyList(),
        automatedTestsPassed = 1,
        automatedTestsFailed = 0
    )
}
