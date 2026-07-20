package com.achulkov.evomolior.orchestration.selector

import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
import com.achulkov.evomolior.substrate.db.entity.EventTypes
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains

class EvolutionarySelectorPromotionSignalTest {

    @Test
    fun `prior generation substantive promotion clears no recent promotions`() = runTest {
        val fixture = fixture("promoted")
        fixture.db.stagingDao().insert(
            fixture.submission(
                id = "promoted-doc",
                generation = 4,
                status = "promoted",
                type = "DOC",
                budgetSignature = "BUDGET:doc"
            )
        )

        assertNoRecentPromotions(fixture, expected = false)
    }

    @Test
    fun `rejected-only prior generation reports no recent promotions`() = runTest {
        val fixture = fixture("rejected")
        listOf(
            fixture.submission(
                id = "rejected-doc",
                generation = 4,
                status = "rejected",
                type = "DOC",
                budgetSignature = "BUDGET:doc"
            ),
            fixture.submission(
                id = "pending-tool",
                generation = 4,
                status = "pending",
                type = "TOOL",
                budgetSignature = "BUDGET:tool"
            ),
            fixture.submission(
                id = "death-note",
                generation = 4,
                status = "promoted",
                type = "DEATH_NOTE",
                budgetSignature = "DEATH_NOTE:agent:4"
            ),
            fixture.submission(
                id = "runtime-fallback",
                generation = 4,
                status = "promoted",
                type = "PLAN_UPDATE",
                budgetSignature = "NO_ACTION_FALLBACK:agent:4"
            ),
            fixture.submission(
                id = "current-generation-promotion",
                generation = 5,
                status = "promoted",
                type = "DOC",
                budgetSignature = "BUDGET:current"
            )
        ).forEach { fixture.db.stagingDao().insert(it) }

        assertNoRecentPromotions(fixture, expected = true)
    }

    @Test
    fun `empty prior generation reports no recent promotions`() = runTest {
        assertNoRecentPromotions(fixture("empty"), expected = true)
    }

    private suspend fun assertNoRecentPromotions(fixture: Fixture, expected: Boolean) {
        EvolutionarySelector(
            db = fixture.db,
            profile = GroupProfile.default(GroupProfileType.Library),
            runId = fixture.runId,
            groupId = fixture.groupId
        ).computeAllocation(
            generation = 5,
            globalBudgetCeiling = 500.0
        )

        val payload = fixture.db.eventLogDao()
            .getLatestByType(fixture.runId, fixture.groupId, EventTypes.SELECTOR_DECISION, 1)
            .single()
            .payloadJson
        assertContains(payload, "\"noRecentPromotions\":$expected")
    }

    private fun fixture(suffix: String): Fixture {
        val runId = "run-selector-promotions-$suffix"
        val groupId = "group-0"
        val db = SubstrateDatabaseFactory(
            createTempDirectory("selector-promotions-$suffix-").toFile()
        ).get(runId, groupId)
        return Fixture(runId, groupId, db)
    }

    private data class Fixture(
        val runId: String,
        val groupId: String,
        val db: EvoMoliorDatabase
    ) {
        fun submission(
            id: String,
            generation: Int,
            status: String,
            type: String,
            budgetSignature: String
        ) = StagingSubmissionEntity(
            id = id,
            agentId = "agent-$id",
            role = "Researcher",
            type = type,
            content = "content-$id",
            budgetSignature = budgetSignature,
            status = status,
            generation = generation,
            groupId = groupId,
            runId = runId,
            submittedAtMs = generation * 1_000L + id.hashCode()
        )
    }
}
