@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.substrate.deliverable

import com.achulkov.evomolior.core.deliverable.ComponentAuthorityClass
import com.achulkov.evomolior.core.deliverable.DeliverableBlueprint
import com.achulkov.evomolior.core.deliverable.DeliverableComponentKind
import com.achulkov.evomolior.core.deliverable.DeliverableComponentSpec
import com.achulkov.evomolior.core.deliverable.DeliverableFormat
import com.achulkov.evomolior.core.deliverable.InterimDeliverableSnapshot
import com.achulkov.evomolior.core.deliverable.ReceiverDecisionPackage
import com.achulkov.evomolior.core.deliverable.RunOutcomeClass
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class DeliverablePersistenceRepositoryTest {
    @Test
    fun `deliverable authorities round trip exactly and reject identity mutation`() = runTest {
        val database = SubstrateDatabaseFactory(
            createTempDirectory("deliverable-persistence-").toFile()
        ).get("run", "group")
        try {
            val repository = DeliverablePersistenceRepository(database)
            val blueprint = blueprint()
            val snapshot = snapshot(blueprint.blueprintHash)
            val decisionPackage = decisionPackage(blueprint)

            assertEquals(blueprint, repository.appendBlueprint(blueprint))
            assertEquals(blueprint, repository.appendBlueprint(blueprint))
            assertEquals(blueprint, repository.blueprint(blueprint.blueprintId))

            assertEquals(snapshot, repository.appendSnapshot(snapshot))
            assertEquals(snapshot, repository.appendSnapshot(snapshot))
            assertEquals(snapshot, repository.snapshot("run", "group", 3))
            assertEquals(listOf(snapshot), repository.snapshots("run", "group"))

            assertEquals(
                decisionPackage,
                repository.appendDecisionPackage(decisionPackage)
            )
            assertEquals(
                decisionPackage,
                repository.appendDecisionPackage(decisionPackage)
            )
            assertEquals(
                decisionPackage,
                repository.decisionPackage(decisionPackage.packageId)
            )
            assertEquals(listOf(decisionPackage), repository.decisionPackages("run"))

            assertFailsWith<IllegalArgumentException> {
                repository.appendBlueprint(
                    blueprint.copy(audience = "Changed receiver").withComputedHash()
                )
            }
            assertFailsWith<IllegalArgumentException> {
                repository.appendSnapshot(
                    snapshot.copy(failedGateIds = listOf("changed")).withComputedHash()
                )
            }
            assertFailsWith<IllegalArgumentException> {
                repository.appendDecisionPackage(
                    decisionPackage.copy(decisionOrUse = "Changed use").withComputedHash()
                )
            }
        } finally {
            database.close()
        }
    }

    private fun blueprint() = DeliverableBlueprint(
        blueprintId = "blueprint",
        receiverContractHash = "a".repeat(64),
        audience = "Operator",
        decisionOrUse = "Select an action",
        primaryFormat = DeliverableFormat.MARKDOWN,
        companionFormats = setOf(DeliverableFormat.JSON),
        componentSpecs = listOf(
            DeliverableComponentSpec(
                componentId = "summary",
                kind = DeliverableComponentKind.EXECUTIVE_SUMMARY,
                required = true,
                minimumAuthority = ComponentAuthorityClass.PROVISIONAL
            )
        )
    ).withComputedHash()

    private fun snapshot(blueprintHash: String) = InterimDeliverableSnapshot(
        runId = "run",
        round = 3,
        groupId = "group",
        blueprintHash = blueprintHash,
        acceptedComponentIds = emptyList(),
        provisionalComponentIds = listOf("summary"),
        withheldComponentIds = emptyList(),
        failedGateIds = listOf("gate"),
        evidenceRefs = listOf("evidence"),
        missingAuthorityComponentIds = listOf("summary")
    ).withComputedHash()

    private fun decisionPackage(
        blueprint: DeliverableBlueprint
    ) = ReceiverDecisionPackage(
        packageId = "package",
        runId = "run",
        outcomeClass = RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE,
        receiverContractHash = blueprint.receiverContractHash,
        blueprintHash = blueprint.blueprintHash,
        audience = blueprint.audience,
        decisionOrUse = blueprint.decisionOrUse,
        components = emptyList(),
        evidenceLedger = emptyList(),
        knownFailures = emptyList(),
        missingAuthority = emptyList(),
        generatedAt = Instant.parse("2026-07-24T12:00:00Z")
    ).withComputedHash()
}
