package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.deliverable.ArcNotebookProfile
import com.achulkov.evomolior.core.deliverable.ArcVisibleLooBenchmarkDefinition
import com.achulkov.evomolior.core.deliverable.ArcVisibleLooCandidateSource
import com.achulkov.evomolior.core.deliverable.ArcVisibleLooFixtureFactory
import com.achulkov.evomolior.core.deliverable.ComponentAuthorityClass
import com.achulkov.evomolior.core.deliverable.DeliverableBlueprintV2
import com.achulkov.evomolior.core.deliverable.DeliverableComponentKind
import com.achulkov.evomolior.core.deliverable.DeliverableComponentSpec
import com.achulkov.evomolior.core.deliverable.DeliverableFormat
import com.achulkov.evomolior.core.deliverable.DeliverableRecoveryPolicy
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ArcArtifactBackboneAndSnapshotTest {
    @Test
    fun `three groups share backbone and retain divergent branches with benchmark facts`() {
        val root = createTempDirectory("arc-backbone-").toFile()
        val manager = ArtifactWorkspaceManager(root)
        val profile = ArcNotebookProfile.v2()
        val blueprint = blueprint(profile)
        val branchCompiler = ArcArtifactBranchCompiler(manager)
        val benchmark = ArcVisibleLooBenchmark(ArcVisibleLooBenchmarkDefinition.fixture())
        val rows = ArcVisibleLooFixtureFactory.rows(top1CorrectRows = 1, anyTwoCorrectRows = 2)
        val snapshots = listOf("group-a", "group-b", "group-c").mapIndexed { index, groupId ->
            val handle = manager.open("run-d", groupId, blueprint, profile)
            val lineage = branchCompiler.materialize(
                handle = handle,
                round = 0,
                family = ArcArtifactBranchCompiler.familyForGroupIndex(index),
            )
            val output = ArcNotebookHostCompiler.compileScaffold(
                outputSpec = arcSpec(),
                sourceGroupId = groupId,
            )
            val structure = ArcNotebookHostValidator.validateStructure(output, arcSpec())
            val loo = benchmark.benchmark(
                ArcVisibleLooCandidateSource.fromArtifactHash(groupId, output.contentHash),
                rows,
            )
            ArcArtifactSnapshotCoordinator(manager).record(
                handle = handle,
                round = 1,
                artifact = output,
                structureReport = """{"passed":${structure.passed}}""",
                executionReport = """{"passed":true}""",
                executionPassed = true,
                benchmarkResult = loo,
                branchLineage = lineage,
                authorityClass = ComponentAuthorityClass.PROVISIONAL,
            )
        }

        assertEquals(1, snapshots.mapNotNull { it.backboneHash }.distinct().size)
        assertEquals(3, snapshots.mapNotNull { it.branchHash }.distinct().size)
        assertTrue(snapshots.all { it.benchmarkRowCount == 358 })
        assertTrue(snapshots.all { it.benchmarkReportBytesHash == it.benchmarkRepeatedBytesHash })
        assertTrue(snapshots.all { it.artifactHealth.name == "IMPROVED" })
    }

    private fun blueprint(profile: ArcNotebookProfile) = DeliverableBlueprintV2(
        blueprintId = "arc-backbone-test",
        receiverContractHash = "a".repeat(64),
        primaryArtifacts = listOf(profile.primaryArtifactSpec),
        narrativePackageFormats = setOf(DeliverableFormat.JSON),
        componentSpecs = listOf(
            DeliverableComponentSpec(
                componentId = "summary",
                kind = DeliverableComponentKind.EXECUTIVE_SUMMARY,
                required = true,
                minimumAuthority = ComponentAuthorityClass.PROVISIONAL,
            )
        ),
        recoveryPolicy = DeliverableRecoveryPolicy(),
    ).withComputedHash()

    private fun arcSpec() =
        com.achulkov.evomolior.api.model.FinalOutputSpec(
            relativePath = "submission.ipynb",
            mediaType = "application/x-ipynb+json",
            validation = ArcAgi2NotebookQualificationAuthority.notebookValidationContract,
        )
}
