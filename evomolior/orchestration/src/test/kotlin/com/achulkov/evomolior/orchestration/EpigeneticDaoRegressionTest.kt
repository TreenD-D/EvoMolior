package com.achulkov.evomolior.orchestration

import com.achulkov.evomolior.orchestration.epigenetic.FitnessScore
import com.achulkov.evomolior.orchestration.epigenetic.FitnessScorer
import com.achulkov.evomolior.orchestration.epigenetic.LegacyProtocolExtractor
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.KnowledgeBaseEntryEntity
import com.achulkov.evomolior.substrate.db.entity.EventLogEntryEntity
import com.achulkov.evomolior.substrate.db.entity.EventTypes
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DAO-backed regressions for post-mortem scoring and Legacy Protocol eligibility.
 */
class EpigeneticDaoRegressionTest {

    @Test
    fun `accepted WIP closer outranks incidental control artifacts`() = runTest {
        val fixture = fixture("wip-closer")
        val closerId = "agent-wip-closer"
        val incidentalId = "agent-incidental"
        fixture.db.stagingDao().insert(
            fixture.submission(
                id = "accepted-wip-artifact",
                agentId = closerId,
                status = "promoted",
                type = "DOC"
            ).copy(wipTaskRef = "node-required-1")
        )
        fixture.db.eventLogDao().insert(
            EventLogEntryEntity(
                id = "wip-complete-1",
                timestampMs = 1L,
                eventType = EventTypes.WIP_COMPLETE,
                agentId = closerId,
                groupId = fixture.groupId,
                runId = fixture.runId,
                generation = 1,
                payloadJson = """{"nodeId":"node-required-1","accepted":true}"""
            )
        )
        listOf(
            fixture.submission(
                id = "incidental-death-note",
                agentId = incidentalId,
                status = "promoted",
                type = "DEATH_NOTE",
                signature = "TEXT_ONLY_BACKEND:$incidentalId:1"
            ),
            fixture.submission(
                id = "incidental-fallback",
                agentId = incidentalId,
                status = "promoted",
                type = "PLAN_UPDATE",
                signature = "NO_ACTION_FALLBACK:$incidentalId:2"
            )
        ).forEach { fixture.db.stagingDao().insert(it) }
        fixture.db.eventLogDao().insert(
            EventLogEntryEntity(
                id = "incidental-inspector-flag",
                timestampMs = 2L,
                eventType = EventTypes.INSPECTOR_FLAG,
                agentId = incidentalId,
                groupId = fixture.groupId,
                runId = fixture.runId,
                generation = 1,
                payloadJson = """{"category":"summary"}"""
            )
        )
        val scorer = FitnessScorer(
            eventLogDao = fixture.db.eventLogDao(),
            knowledgeBaseDao = fixture.db.knowledgeBaseDao(),
            stagingDao = fixture.db.stagingDao(),
            toolRegistryDao = fixture.db.toolRegistryDao()
        )

        val closer = scorer.score(fixture.runId, fixture.groupId, closerId, generation = 1)
        val incidental = scorer.score(fixture.runId, fixture.groupId, incidentalId, generation = 1)

        assertEquals(20.0, closer.taskCompletionScore)
        assertEquals(0.0, incidental.total)
        assertTrue(closer.total > incidental.total)
    }

    @Test
    fun `fitness uses canonical citations and substantive reviewed acceptance`() = runTest {
        val fixture = fixture("fitness")
        fixture.db.stagingDao().insert(
            fixture.submission("doc", status = "promoted", type = "DOC")
        )
        fixture.db.stagingDao().insert(
            fixture.submission("rejected", status = "rejected", type = "KG_FACT")
        )
        fixture.db.stagingDao().insert(
            fixture.submission(
                "death-note",
                status = "promoted",
                type = "DEATH_NOTE",
                signature = "TEXT_ONLY_BACKEND:${fixture.agentId}:1"
            )
        )
        fixture.db.stagingDao().insert(
            fixture.submission(
                "fallback",
                status = "promoted",
                type = "PLAN_UPDATE",
                signature = "NO_ACTION_FALLBACK:${fixture.agentId}:2"
            )
        )
        fixture.db.knowledgeBaseDao().insert(
            fixture.kbEntry("canonical", citationCount = 4)
        )
        fixture.db.knowledgeBaseDao().insert(
            fixture.kbEntry("dead-end", citationCount = 100).copy(isDeadEnd = true)
        )

        val score = FitnessScorer(
            eventLogDao = fixture.db.eventLogDao(),
            knowledgeBaseDao = fixture.db.knowledgeBaseDao(),
            stagingDao = fixture.db.stagingDao(),
            toolRegistryDao = fixture.db.toolRegistryDao()
        ).score(fixture.runId, fixture.groupId, fixture.agentId, generation = 1)

        assertEquals(8.0, score.citationScore)
        assertEquals(12.5, score.stagingSuccessScore)
        assertEquals(0.0, score.kgContributionScore)
        assertEquals(20.5, score.total)
    }

    @Test
    fun `extractor skips agents without substantive promotion and ignores automatic death notes`() = runTest {
        val fixture = fixture("legacy")
        val syntheticAgentId = "agent-synthetic"
        val rejectedAgentId = "agent-rejected"
        fixture.db.stagingDao().insert(
            fixture.submission("promoted-doc", status = "promoted", type = "DOC")
        )
        fixture.db.stagingDao().insert(
            fixture.submission(
                "manual-death-note",
                status = "promoted",
                type = "DEATH_NOTE",
                content = "MANUAL REASONING POSTURE",
                signature = "DEATH_NOTE:${fixture.agentId}:1"
            )
        )
        fixture.db.stagingDao().insert(
            fixture.submission(
                "automatic-death-note",
                status = "promoted",
                type = "DEATH_NOTE",
                content = "TEXT-ONLY AUTOMATIC POSTURE",
                signature = "TEXT_ONLY_BACKEND:${fixture.agentId}:2"
            )
        )
        fixture.db.stagingDao().insert(
            fixture.submission(
                "synthetic-fallback",
                agentId = syntheticAgentId,
                status = "promoted",
                type = "PLAN_UPDATE",
                signature = "SUBSTRATE_CONTRACT_FALLBACK:$syntheticAgentId:3"
            )
        )
        fixture.db.stagingDao().insert(
            fixture.submission(
                "rejected-work",
                agentId = rejectedAgentId,
                status = "rejected",
                type = "DOC"
            )
        )

        val scores = listOf(
            fitness(fixture.agentId, total = 80.0),
            fitness(syntheticAgentId, total = 90.0),
            fitness(rejectedAgentId, total = 70.0)
        )
        val protocols = LegacyProtocolExtractor(
            legacyProtocolDao = fixture.db.legacyProtocolDao(),
            stagingDao = fixture.db.stagingDao(),
            eventLogDao = fixture.db.eventLogDao()
        ).extract(
            fitnessScores = scores,
            topAgentIds = scores.mapTo(mutableSetOf()) { it.agentId },
            currentGeneration = 1,
            runId = fixture.runId,
            groupId = fixture.groupId
        )

        assertEquals(1, protocols.size)
        val protocol = protocols.single()
        assertEquals(fixture.agentId, protocol.sourceAgentIds)
        assertTrue(protocol.reasoningPosture.contains("MANUAL REASONING POSTURE"))
        assertFalse(protocol.reasoningPosture.contains("TEXT-ONLY AUTOMATIC POSTURE"))
    }

    private fun fitness(agentId: String, total: Double) = FitnessScore(
        agentId = agentId,
        generation = 1,
        citationScore = 0.0,
        kgContributionScore = 0.0,
        toolAdoptionScore = 0.0,
        stagingSuccessScore = 0.0,
        taskCompletionScore = total,
        total = total
    )

    private fun fixture(name: String): Fixture {
        val runId = "run-$name"
        val groupId = "group-$name"
        val db = SubstrateDatabaseFactory(
            createTempDirectory("epigenetic-$name-test").toFile()
        ).get(runId, groupId)
        return Fixture(runId, groupId, "agent-substantive", db)
    }

    private data class Fixture(
        val runId: String,
        val groupId: String,
        val agentId: String,
        val db: com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
    ) {
        fun submission(
            id: String,
            agentId: String = this.agentId,
            status: String,
            type: String,
            content: String = "content-$id",
            signature: String = "BUDGET:$id"
        ) = StagingSubmissionEntity(
            id = id,
            agentId = agentId,
            role = "Researcher",
            type = type,
            content = content,
            budgetSignature = signature,
            status = status,
            generation = 1,
            groupId = groupId,
            runId = runId,
            submittedAtMs = id.hashCode().toLong()
        )

        fun kbEntry(id: String, citationCount: Int) = KnowledgeBaseEntryEntity(
            id = id,
            content = "content-$id",
            sourceAgentId = agentId,
            generation = 1,
            citationCount = citationCount,
            domain = "test",
            groupId = groupId,
            runId = runId,
            createdAtMs = id.hashCode().toLong()
        )
    }
}
