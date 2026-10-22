package com.achulkov.evomolior.core.deliverable

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArtifactContractsV2Test {
    @Test
    fun `ARC profile carries notebook lineage validators benchmark and host signals`() {
        val profile = ArcNotebookProfile.v2()
        val artifact = profile.primaryArtifactSpec

        assertEquals(ArcNotebookProfile.PROFILE_ID, profile.profileId)
        assertEquals("arc-submission-notebook", artifact.artifactId)
        assertEquals("primary/submission.ipynb", artifact.relativePath)
        assertEquals(
            setOf(
                "notebook-scaffold",
                "solver-portfolio",
                "loo-validation",
                "receiver-generation",
            ),
            artifact.sourceNodeIds,
        )
        assertEquals(setOf("visible-loo-358"), artifact.benchmarkIds)
        assertTrue(profile.receiverSignals.any {
            it is ReceiverSignalSpec.PythonSymbol && it.symbolName == "challenge_data"
        })
        assertTrue(profile.hasValidHash())
    }

    @Test
    fun `blueprint and snapshots hash deterministically across set insertion order`() {
        val first = arcBlueprint(
            sourceNodes = linkedSetOf("node-b", "node-a"),
            validatorIds = linkedSetOf("validator-b", "validator-a"),
        )
        val second = arcBlueprint(
            sourceNodes = linkedSetOf("node-a", "node-b"),
            validatorIds = linkedSetOf("validator-a", "validator-b"),
        )

        assertEquals(first.blueprintHash, second.blueprintHash)
        assertTrue(first.hasValidHash())
        assertTrue(second.hasValidHash())

        val snapshotOne = snapshot(
            sourceNodes = linkedSetOf("node-b", "node-a"),
            patches = linkedSetOf("patch-b", "patch-a"),
        )
        val snapshotTwo = snapshot(
            sourceNodes = linkedSetOf("node-a", "node-b"),
            patches = linkedSetOf("patch-a", "patch-b"),
        )
        assertEquals(snapshotOne.snapshotHash, snapshotTwo.snapshotHash)
        assertTrue(snapshotOne.hasValidHash())
        assertTrue(snapshotTwo.hasValidHash())
        assertFailsWith<IllegalArgumentException> {
            snapshotOne.copy(snapshotHash = "invalid").requireValid()
        }
    }

    @Test
    fun `terminal authority rejects incomplete accepted and artifactless completed outcomes`() {
        val authority = acceptedWriterAuthority()

        assertFailsWith<IllegalArgumentException> {
            RunTerminalOutcomeV2(
                executionStatus = ExecutionCompletionStatus.COMPLETED,
                acceptanceStatus = AcceptanceStatus.PROVISIONAL,
                primaryArtifactRef = null,
                decisionPackageRef = null,
                acceptedWinnerId = null,
                writerSourceAuthority = null,
                failedGateIds = listOf("strict-benchmark"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RunTerminalOutcomeV2(
                executionStatus = ExecutionCompletionStatus.COMPLETED,
                acceptanceStatus = AcceptanceStatus.ACCEPTED,
                primaryArtifactRef = artifactRef("primary"),
                decisionPackageRef = null,
                acceptedWinnerId = "winner-1",
                writerSourceAuthority = authority,
                failedGateIds = listOf("strict-benchmark"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RunTerminalOutcomeV2(
                executionStatus = ExecutionCompletionStatus.COMPLETED,
                acceptanceStatus = AcceptanceStatus.PROVISIONAL,
                primaryArtifactRef = artifactRef("primary"),
                decisionPackageRef = null,
                acceptedWinnerId = "winner-1",
                writerSourceAuthority = null,
                failedGateIds = emptyList(),
            )
        }

        val accepted = RunTerminalOutcomeV2(
            executionStatus = ExecutionCompletionStatus.COMPLETED,
            acceptanceStatus = AcceptanceStatus.ACCEPTED,
            primaryArtifactRef = artifactRef("primary"),
            decisionPackageRef = artifactRef("decision"),
            acceptedWinnerId = "winner-1",
            writerSourceAuthority = authority,
            failedGateIds = emptyList(),
            primaryArtifactValidationStatus = PrimaryArtifactValidationStatus.PASS,
        ).withComputedHash()
        assertTrue(accepted.hasValidHash())
        assertFailsWith<IllegalArgumentException> {
            accepted.copy(
                primaryArtifactValidationStatus = PrimaryArtifactValidationStatus.FAIL,
            ).requireValid()
        }
    }

    @Test
    fun `typed repair preserves exact violation and immutable contract refs`() {
        val violation = ArtifactContractViolation(
            code = ArtifactViolationCode.MISSING_REQUIRED_SYMBOL,
            cellHandle = "receiver",
            sourceFile = "src/receiver.py",
            symbol = "challenge_data",
            publicMessage = "Required receiver symbol is absent.",
            safeRepairHint = "Declare challenge_data in the receiver cell.",
        )
        val request = WriterRepairRequest(
            artifactSnapshotHash = HASH_A,
            violations = listOf(violation),
            benchmarkRegression = null,
            immutableContractRefs = listOf(HASH_A, HASH_B).sorted(),
            repairAttempt = 1,
        )

        assertEquals(ArtifactViolationCode.MISSING_REQUIRED_SYMBOL, request.violations.single().code)
        assertEquals("challenge_data", request.violations.single().symbol)
        assertEquals(listOf(HASH_A, HASH_B).sorted(), request.immutableContractRefs)
    }

    private fun arcBlueprint(
        sourceNodes: Set<String>,
        validatorIds: Set<String>,
    ): DeliverableBlueprintV2 = DeliverableBlueprintV2(
        blueprintId = "blueprint-v2",
        receiverContractHash = HASH_A,
        primaryArtifacts = listOf(
            PrimaryArtifactSpec(
                artifactId = "notebook",
                relativePath = "primary/submission.ipynb",
                mediaType = "application/x-ipynb+json",
                profileId = ArcNotebookProfile.PROFILE_ID,
                requiredFromRound = 0,
                requiredForCompletedOutcome = true,
                sourceNodeIds = sourceNodes,
                structuralValidatorIds = validatorIds,
                executionValidatorIds = setOf("execute"),
                benchmarkIds = setOf("loo-358"),
            )
        ),
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

    private fun snapshot(
        sourceNodes: Set<String>,
        patches: Set<String>,
    ): ArtifactSnapshot = ArtifactSnapshot(
        snapshotId = "snapshot-1",
        runId = "run-1",
        groupId = "group-1",
        round = 1,
        artifactHash = HASH_A,
        sourceTreeHash = HASH_B,
        structureReportHash = HASH_C,
        executionReportHash = HASH_A,
        benchmarkReportHash = HASH_B,
        top1 = 10,
        anyTwo = 12,
        runtimeMs = 100,
        authorityClass = ComponentAuthorityClass.PROVISIONAL,
        sourceNodeIds = sourceNodes,
        patchLineageIds = patches,
    ).withComputedHash()

    private fun acceptedWriterAuthority(): WriterSourceAuthority =
        WriterSourceAuthority(
            primaryGroupId = "group-1",
            authorityClass = WriterSourceAuthorityClass.ACCEPTED_WINNER,
            sourceSnapshotIds = listOf("snapshot-1"),
            donorArtifactRefs = listOf("primary/submission.ipynb"),
            failedGateDiagnostics = emptyList(),
            writerIdentity = "writer-1",
            finalArtifactHash = HASH_A,
            requiredValidatorHashes = listOf(HASH_B),
            finalBenchmarkHash = HASH_C,
        ).withComputedHash()

    private fun artifactRef(id: String): ArtifactRef = ArtifactRef(
        artifactId = id,
        relativePath = "primary/$id.ipynb",
        mediaType = "application/x-ipynb+json",
        artifactHash = HASH_A,
    )

    private companion object {
        val HASH_A = "a".repeat(64)
        val HASH_B = "b".repeat(64)
        val HASH_C = "c".repeat(64)
    }
}
