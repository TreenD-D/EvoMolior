package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.core.deliverable.ArtifactSnapshot
import com.achulkov.evomolior.core.deliverable.ComponentAuthorityClass
import com.achulkov.evomolior.core.deliverable.DeliverableBlueprintV2
import com.achulkov.evomolior.core.deliverable.DeliverableComponentKind
import com.achulkov.evomolior.core.deliverable.DeliverableComponentSpec
import com.achulkov.evomolior.core.deliverable.DeliverableFormat
import com.achulkov.evomolior.core.deliverable.DeliverableRecoveryPolicy
import com.achulkov.evomolior.core.deliverable.ArcNotebookProfile
import com.achulkov.evomolior.core.deliverable.ArtifactContractViolation
import com.achulkov.evomolior.core.deliverable.ArtifactViolationCode
import com.achulkov.evomolior.core.deliverable.WriterRepairRequest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArtifactWorkspaceManagerTest {
    @Test
    fun `partial writer output and typed repair queue survive timeout`() {
        val root = createTempDirectory("artifact-workspace-repair-").toFile()
        val manager = ArtifactWorkspaceManager(root)
        val profile = ArcNotebookProfile.v2()
        val handle = manager.open("run-repair", "group-a", blueprint(), profile)
        val partial = ArcNotebookHostCompiler.compileScaffold(
            outputSpec = arcSpec(),
            sourceGroupId = "group-a",
        )
        manager.recordSnapshot(
            handle = handle,
            round = 0,
            artifact = partial,
            structureReport = """{"passed":true}""",
            sourceNodeIds = profile.primaryArtifactSpec.sourceNodeIds,
        )
        manager.writeWriterProgress(handle, """{"stage":"timed-out","attempt":1}""")
        manager.writePartialArtifact(handle, 1, partial)
        val request = WriterRepairRequest(
            artifactSnapshotHash = requireNotNull(manager.bestSnapshot(handle)).snapshotHash,
            violations = listOf(
                ArtifactContractViolation(
                    code = ArtifactViolationCode.INVALID_NOTEBOOK_STRUCTURE,
                    cellHandle = "receiver",
                    sourceFile = "src/receiver.py",
                    symbol = null,
                    publicMessage = "Receiver cell needs host recompilation.",
                    safeRepairHint = "Repair only the receiver cell from the exact snapshot.",
                )
            ),
            benchmarkRegression = null,
            immutableContractRefs = listOf(
                blueprint().blueprintHash,
                profile.profileHash,
            ).sorted(),
            repairAttempt = 1,
        )
        manager.writeWriterRepairRequest(handle, request)

        val reopened = ArtifactWorkspaceManager(root).open(
            "run-repair",
            "group-a",
            blueprint(),
            profile,
        )
        assertEquals(
            request,
            ArtifactWorkspaceManager(root).writerRepairRequests(reopened).single(),
        )
        assertTrue(File(reopened.root, "state/writer-progress.json").isFile)
        assertTrue(
            File(reopened.root, "state/partial-writer/001-submission.ipynb").isFile,
        )
    }

    @Test
    fun `workspace persists sources snapshots and best historical artifact across manager instances`() {
        val root = createTempDirectory("artifact-workspace-").toFile()
        val blueprint = blueprint()
        val profile = ArcNotebookProfile.v2()
        val firstManager = ArtifactWorkspaceManager(root)
        val firstHandle = firstManager.open("run-1", "group-a", blueprint, profile)
        firstManager.writeSource(firstHandle, "receiver.py", "challenge_data = {}")

        val outputSpec = arcSpec()
        val firstArtifact = ArcNotebookHostCompiler.compileScaffold(
            outputSpec = outputSpec,
            sourceGroupId = "group-a",
        )
        val best = firstManager.recordSnapshot(
            handle = firstHandle,
            round = 0,
            artifact = firstArtifact,
            structureReport = """{"passed":true}""",
            executionReport = """{"passed":true}""",
            benchmarkReport = """{"top1":10,"anyTwo":12}""",
            top1 = 10,
            anyTwo = 12,
            sourceNodeIds = profile.primaryArtifactSpec.sourceNodeIds,
            authorityClass = ComponentAuthorityClass.PROVISIONAL,
        )
        firstManager.writeWriterProgress(firstHandle, """{"stage":"timed-out","attempt":1}""")

        val regressedArtifact = ArcNotebookHostCompiler.compileScaffold(
            outputSpec = outputSpec,
            inputHashes = mapOf("changed" to "c".repeat(64)),
            sourceGroupId = "group-a",
        )
        firstManager.recordSnapshot(
            handle = firstHandle,
            round = 1,
            artifact = regressedArtifact,
            structureReport = """{"passed":true}""",
            executionReport = """{"passed":true}""",
            benchmarkReport = """{"top1":4,"anyTwo":5}""",
            top1 = 4,
            anyTwo = 5,
            sourceNodeIds = profile.primaryArtifactSpec.sourceNodeIds,
            authorityClass = ComponentAuthorityClass.PROVISIONAL,
        )

        val reopened = ArtifactWorkspaceManager(root).open(
            runId = "run-1",
            groupId = "group-a",
            blueprint = blueprint,
            profile = profile,
        )

        assertEquals("challenge_data = {}", ArtifactWorkspaceManager(root).readSource(reopened, "receiver.py"))
        assertEquals(best.snapshotId, ArtifactWorkspaceManager(root).bestSnapshot(reopened)?.snapshotId)
        val historicalArtifact = ArtifactWorkspaceManager(root).readSnapshotArtifact(
            reopened,
            best,
        )
        assertEquals(firstArtifact.contentHash, historicalArtifact.contentHash)
        assertTrue(
            File(
                reopened.root,
                "generated/snapshots/${best.snapshotId}/submission.ipynb",
            ).isFile,
        )
        assertTrue(File(reopened.root, "state/writer-progress.json").isFile)
        assertTrue(File(reopened.root, "state/snapshots/${best.snapshotId}.json").isFile)
    }

    @Test
    fun `workspace prefers the newer snapshot when evidence scores tie`() {
        val root = createTempDirectory("artifact-workspace-tie-").toFile()
        val manager = ArtifactWorkspaceManager(root)
        val profile = ArcNotebookProfile.v2()
        val handle = manager.open("run-tie", "group-a", blueprint(), profile)
        val outputSpec = arcSpec()
        val firstArtifact = ArcNotebookHostCompiler.compileScaffold(
            outputSpec = outputSpec,
            sourceGroupId = "group-a",
        )
        manager.recordSnapshot(
            handle = handle,
            round = 0,
            artifact = firstArtifact,
            structureReport = """{"passed":true}""",
            executionReport = """{"passed":true}""",
            benchmarkReport = """{"top1":10,"anyTwo":12}""",
            top1 = 10,
            anyTwo = 12,
            sourceNodeIds = profile.primaryArtifactSpec.sourceNodeIds,
        )
        val laterArtifact = ArcNotebookHostCompiler.compileScaffold(
            outputSpec = outputSpec,
            inputHashes = mapOf("changed" to "c".repeat(64)),
            sourceGroupId = "group-a",
        )
        val later = manager.recordSnapshot(
            handle = handle,
            round = 1,
            artifact = laterArtifact,
            structureReport = """{"passed":true}""",
            executionReport = """{"passed":true}""",
            benchmarkReport = """{"top1":10,"anyTwo":12}""",
            top1 = 10,
            anyTwo = 12,
            sourceNodeIds = profile.primaryArtifactSpec.sourceNodeIds,
        )

        assertEquals(later.snapshotId, manager.bestSnapshot(handle)?.snapshotId)
        assertEquals(
            laterArtifact.contentHash,
            manager.readSnapshotArtifact(handle, requireNotNull(manager.bestSnapshot(handle)))
                .contentHash,
        )
    }

    @Test
    fun `workspace rejects snapshot mutation under the same immutable identity`() {
        val root = createTempDirectory("artifact-workspace-").toFile()
        val manager = ArtifactWorkspaceManager(root)
        val profile = ArcNotebookProfile.v2()
        val handle = manager.open("run-2", "group-a", blueprint(), profile)
        val artifact = ArcNotebookHostCompiler.compileScaffold(
            outputSpec = arcSpec(),
            sourceGroupId = "group-a",
        )
        val snapshot = manager.recordSnapshot(
            handle = handle,
            round = 0,
            artifact = artifact,
            structureReport = "structure-pass",
            sourceNodeIds = profile.primaryArtifactSpec.sourceNodeIds,
        )
        val same = manager.recordSnapshot(
            handle = handle,
            round = 0,
            artifact = artifact,
            structureReport = "structure-pass",
            sourceNodeIds = profile.primaryArtifactSpec.sourceNodeIds,
        )
        assertEquals(snapshot, same)
    }

    private fun blueprint() = DeliverableBlueprintV2(
        blueprintId = "blueprint-v2",
        receiverContractHash = "a".repeat(64),
        primaryArtifacts = listOf(ArcNotebookProfile.v2().primaryArtifactSpec),
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

    private fun arcSpec() = FinalOutputSpec(
        relativePath = "submission.ipynb",
        mediaType = "application/x-ipynb+json",
        validation = ArcAgi2NotebookQualificationAuthority.notebookValidationContract,
    )
}
