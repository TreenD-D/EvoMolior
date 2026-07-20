package com.achulkov.evomolior.agents.tools

import com.achulkov.evomolior.core.budget.BudgetConfig
import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.KGSchemaEntity
import com.achulkov.evomolior.substrate.db.entity.KnowledgeGraphFactEntity
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KgRelationCanonicalizationToolTest {
    @Test
    fun `preflight write conflict and query surfaces share canonical relation`() = runTest {
        val runId = "run-kg-alias"
        val groupId = "group-0"
        val db = SubstrateDatabaseFactory(
            createTempDirectory("kg-alias-tools-test").toFile()
        ).get(runId, groupId)
        db.kgSchemaDao().upsert(
            KGSchemaEntity(
                id = "schema-1",
                runId = runId,
                groupId = groupId,
                relationTypesJson = """["REQUIRES","CONCERNS"]""",
                createdAtMs = 1L
            )
        )
        db.knowledgeGraphFactDao().insert(
            KnowledgeGraphFactEntity(
                id = "fact-1",
                subject = "change",
                relation = "REQUIRES",
                objectNode = "old-gate",
                confidence = 0.9,
                sourceAgentId = "agent-existing",
                generation = 1,
                trustScore = 0.9,
                groupId = groupId,
                runId = runId,
                createdAtMs = 1L
            )
        )

        val preflight = CheckConsistencyTool(
            db.kgSchemaDao(),
            db.knowledgeGraphFactDao(),
            runId,
            groupId
        ).execute(
            CheckConsistencyTool.Args(
                listOf(
                    CheckConsistencyTool.FactCandidate(
                        subject = "change",
                        relation = "requires_rollout_gate",
                        obj = "new-gate",
                        confidence = 0.7
                    )
                )
            )
        )
        assertTrue(preflight.contains("DISPUTE"))

        val writeLedger = ledger(groupId)
        val write = EmitFactTool(
            stagingDao = db.stagingDao(),
            budgetLedger = writeLedger,
            runId = runId,
            groupId = groupId,
            agentId = "agent-new",
            role = "Researcher",
            generation = 1,
            kgSchemaDao = db.kgSchemaDao()
        ).execute(
            EmitFactTool.Args(
                subject = "service",
                relation = "risks",
                obj = "outage",
                domain = "operations"
            )
        )
        assertTrue(write.contains("(service, CONCERNS, outage)"))
        assertTrue(
            db.stagingDao().getByAgent(runId, groupId, "agent-new")
                .single()
                .content
                .contains("RELATION: CONCERNS")
        )

        val read = ReadKGTool(
            db.knowledgeGraphFactDao(),
            db.kgSchemaDao(),
            runId,
            groupId
        ).execute(ReadKGTool.Args(relation = "requires_rollout_gate"))
        assertTrue(read.contains("Relation: REQUIRES"))

        val queryLedger = ledger(groupId)
        val query = QueryPatternTool(
            db.knowledgeGraphFactDao(),
            db.kgSchemaDao(),
            queryLedger,
            runId,
            groupId
        ).execute(QueryPatternTool.Args(relation = "requires_rollout_gate"))
        assertTrue(query.contains("(change, REQUIRES, old-gate)"))

        val actionsBeforeInvalidQuery = queryLedger.actionLog.size
        val invalidQuery = QueryPatternTool(
            db.knowledgeGraphFactDao(),
            db.kgSchemaDao(),
            queryLedger,
            runId,
            groupId
        ).execute(QueryPatternTool.Args(relation = "unknown_relation"))
        assertTrue(invalidQuery.startsWith("Error: relation"))
        assertEquals(actionsBeforeInvalidQuery, queryLedger.actionLog.size)
    }

    private fun ledger(groupId: String) = BudgetLedger(
        agentId = "agent-test",
        initialBudget = 100.0,
        floor = 1.0,
        config = BudgetConfig(
            groupId = groupId,
            profile = GroupProfile.default(GroupProfileType.Library)
        )
    )
}
