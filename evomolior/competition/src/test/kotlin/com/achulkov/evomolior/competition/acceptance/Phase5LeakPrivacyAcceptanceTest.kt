package com.achulkov.evomolior.competition.acceptance

import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.dispatch.DispatchMessage
import com.achulkov.evomolior.competition.leaks.LeakOracleConfig
import com.achulkov.evomolior.core.contract.LeakPacket
import com.achulkov.evomolior.core.model.ScoringDimension
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase5LeakPrivacyAcceptanceTest {

    @Test
    fun `ten consecutive leaks contain no source trace after mandatory transformation`() = runTest {
        val sourceGroupId = "group-secret-origin"
        val targetGroupId = "group-recipient"
        val sourceTraceMarkers = listOf(
            sourceGroupId,
            "agent-secret-42",
            "/private/source/path",
            "RAW_SOURCE_SENTENCE"
        )
        val deliveredPackets = mutableListOf<LeakPacket>()
        val coordinator = GroupCoordinator(
            groupIds = listOf(sourceGroupId, targetGroupId),
            dimensions = listOf(
                ScoringDimension(
                    id = "accuracy",
                    name = "Accuracy",
                    description = "Reward accurate transformed intelligence.",
                    weight = 1.0
                )
            ),
            leakConfig = LeakOracleConfig(
                maxLeaksPerGroupPerRound = 10,
                maxMigrationPerPairPerRound = 10,
                corruptionProbability = 0.0,
                allowedDomains = setOf("general")
            ),
            privacyTransformerOverride = { packet ->
                val ordinal = packet.leakId.substringAfterLast("-")
                packet.copy(
                    content = "ANONYMIZED_EVIDENCE_$ordinal: bounded aggregate finding with source identifiers removed."
                )
            }
        )
        coordinator.configureDeliveryConsumer { _, _, message, _ ->
            if (message is DispatchMessage.Leak) deliveredPackets += message.packet
        }
        coordinator.onRoundStart(0)

        repeat(10) { index ->
            val delivered = coordinator.deliverLeak(
                sourceGroupId = sourceGroupId,
                targetGroupId = targetGroupId,
                packet = LeakPacket(
                    leakId = "privacy-audit-$index",
                    content = "RAW_SOURCE_SENTENCE from agent-secret-42 at /private/source/path in $sourceGroupId.",
                    domain = "general",
                    recipientGroupId = targetGroupId,
                    deliveredAtGeneration = 0,
                    runId = "run-phase-5-acceptance"
                )
            )
            assertTrue(delivered)
        }

        assertEquals(10, deliveredPackets.size)
        deliveredPackets.forEachIndexed { index, packet ->
            assertEquals("privacy-audit-$index", packet.leakId)
            assertEquals(targetGroupId, packet.recipientGroupId)
            assertTrue(packet.content.startsWith("ANONYMIZED_EVIDENCE_$index"))
            sourceTraceMarkers.forEach { marker ->
                assertFalse(
                    packet.content.contains(marker, ignoreCase = true),
                    "Leak ${packet.leakId} retained source-trace marker '$marker'"
                )
            }
        }
    }
}
