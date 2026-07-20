package com.achulkov.evomolior.substrate

import com.achulkov.evomolior.substrate.db.entity.AtlasAmendmentEntity
import com.achulkov.evomolior.substrate.db.entity.AtlasCommitEntity
import com.achulkov.evomolior.substrate.db.entity.AtlasExecutionStateEventEntity
import com.achulkov.evomolior.substrate.db.entity.AtlasProjectionEntity
import com.achulkov.evomolior.substrate.db.entity.AtlasVersionEntity
import com.achulkov.evomolior.substrate.db.entity.WipTaskEntity
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Verifies append-only Atlas persistence and all-or-nothing projection commits.
 */
class AtlasDaoTest {

    @Test
    fun `sealed versions and amendments retain immutable hash lineage`() = runTest {
        val factory = SubstrateDatabaseFactory(createTempDirectory("atlas-lineage").toFile())
        val dao = factory.get(RUN_ID, AUTHORITY_SCOPE).atlasDao()
        val versionOne = version(version = 1, hash = "atlas-hash-1", parentHash = null)

        dao.appendTransaction(
            commit = commit(sequence = 1, version = 1, hash = versionOne.atlasHash),
            version = versionOne,
            amendment = null,
            captureDecision = null,
            yieldApplication = null,
            stateEvents = emptyList(),
            projections = emptyList(),
            wipTasks = emptyList()
        )

        val versionTwo = version(
            version = 2,
            hash = "atlas-hash-2",
            parentHash = versionOne.atlasHash
        )
        dao.appendTransaction(
            commit = commit(sequence = 2, version = 2, hash = versionTwo.atlasHash),
            version = versionTwo,
            amendment = AtlasAmendmentEntity(
                amendmentId = "amendment-1",
                atlasId = ATLAS_ID,
                runId = RUN_ID,
                fromVersion = 1,
                toVersion = 2,
                parentAtlasHash = versionOne.atlasHash,
                resultAtlasHash = versionTwo.atlasHash,
                amendmentJson = """{"reason":"split node"}""",
                createdAtMs = 2L
            ),
            captureDecision = null,
            yieldApplication = null,
            stateEvents = emptyList(),
            projections = emptyList(),
            wipTasks = emptyList()
        )

        assertEquals(listOf(versionOne, versionTwo), dao.versionHistory(ATLAS_ID))
        assertEquals(versionTwo, dao.latestVersion(ATLAS_ID))
        assertEquals(versionOne, dao.versionByHash(versionOne.atlasHash))
        assertEquals(
            listOf(versionOne.atlasHash to versionTwo.atlasHash),
            dao.amendmentHistory(ATLAS_ID).map { it.parentAtlasHash to it.resultAtlasHash }
        )

        assertFailsWith<Exception> {
            dao.appendTransaction(
                commit = commit(sequence = 3, version = 1, hash = versionOne.atlasHash),
                version = versionOne.copy(sealedAtlasJson = """{"rewritten":true}"""),
                amendment = null,
                captureDecision = null,
                yieldApplication = null,
                stateEvents = emptyList(),
                projections = emptyList(),
                wipTasks = emptyList()
            )
        }
        assertEquals(
            """{"version":1}""",
            dao.versionByHash(versionOne.atlasHash)?.sealedAtlasJson
        )
    }

    @Test
    fun `failed projection write rolls back authority state and legacy WIP projection`() = runTest {
        val factory = SubstrateDatabaseFactory(createTempDirectory("atlas-rollback").toFile())
        val db = factory.get(RUN_ID, AUTHORITY_SCOPE)
        val dao = db.atlasDao()
        val version = version(version = 1, hash = "atlas-hash-1", parentHash = null)
        val duplicateProjection = projection(projectionId = "projection-duplicate")

        assertFailsWith<Exception> {
            dao.appendTransaction(
                commit = commit(sequence = 1, version = 1, hash = version.atlasHash),
                version = version,
                amendment = null,
                captureDecision = null,
                yieldApplication = null,
                stateEvents = listOf(stateEvent()),
                projections = listOf(duplicateProjection, duplicateProjection),
                wipTasks = listOf(wipTask())
            )
        }

        assertNull(dao.latestVersion(ATLAS_ID))
        assertNull(dao.latestCommit(ATLAS_ID))
        assertEquals(emptyList(), dao.currentStates(ATLAS_ID))
        assertNull(db.wipTaskDao().getByNodeId(NODE_ID, RUN_ID, GROUP_ID))
    }

    private fun version(version: Int, hash: String, parentHash: String?) = AtlasVersionEntity(
        atlasId = ATLAS_ID,
        runId = RUN_ID,
        version = version,
        receiverContractHash = "receiver-hash",
        receiverContractJson = """{"rawObjective":"exact"}""",
        atlasHash = hash,
        parentAtlasHash = parentHash,
        sealedAtlasJson = """{"version":$version}""",
        createdAtMs = version.toLong()
    )

    private fun commit(sequence: Long, version: Int, hash: String) = AtlasCommitEntity(
        commitId = "commit-$sequence",
        atlasId = ATLAS_ID,
        runId = RUN_ID,
        commitSequence = sequence,
        atlasVersion = version,
        atlasHash = hash,
        mutationType = "test",
        createdAtMs = sequence
    )

    private fun stateEvent() = AtlasExecutionStateEventEntity(
        eventId = "state-event-1",
        atlasId = ATLAS_ID,
        runId = RUN_ID,
        nodeId = NODE_ID,
        stateRevision = 1,
        commitSequence = 1,
        previousStateHash = null,
        stateHash = "state-hash-1",
        stateJson = """{"nodeId":"$NODE_ID"}""",
        mutationType = "initialize",
        createdAtMs = 1L
    )

    private fun projection(projectionId: String) = AtlasProjectionEntity(
        projectionId = projectionId,
        atlasId = ATLAS_ID,
        runId = RUN_ID,
        atlasVersion = 1,
        commitSequence = 1,
        projectionType = "WIP",
        scopeId = GROUP_ID,
        payloadJson = "[]",
        sourceAtlasHash = "atlas-hash-1",
        sourceStateHash = "state-hash-1",
        createdAtMs = 1L
    )

    private fun wipTask() = WipTaskEntity(
        nodeId = NODE_ID,
        runId = RUN_ID,
        groupId = GROUP_ID,
        description = "Projected atomic work",
        lastChangedGeneration = 0,
        linkedNodeId = NODE_ID,
        dstNodeRef = NODE_ID
    )

    private companion object {
        const val RUN_ID = "run-atlas"
        const val ATLAS_ID = "atlas-run-atlas"
        const val AUTHORITY_SCOPE = "__atlas__"
        const val GROUP_ID = "group-0"
        const val NODE_ID = "node-1"
    }
}
