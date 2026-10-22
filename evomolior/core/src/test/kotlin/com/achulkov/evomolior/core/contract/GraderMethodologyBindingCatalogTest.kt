package com.achulkov.evomolior.core.contract

import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.CapturePolicy
import com.achulkov.evomolior.core.atlas.DisclosureStage
import com.achulkov.evomolior.core.atlas.LimitationNode
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.NodeDeliverableContract
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.ProofContract
import com.achulkov.evomolior.core.atlas.ProofMethod
import com.achulkov.evomolior.core.atlas.ReplicationPolicy
import com.achulkov.evomolior.core.atlas.RevealMode
import com.achulkov.evomolior.core.atlas.RevealPolicy
import com.achulkov.evomolior.core.atlas.RewardClass
import com.achulkov.evomolior.core.atlas.RewardSpec
import com.achulkov.evomolior.core.grader.AdversarialCase
import com.achulkov.evomolior.core.grader.DisclosureSensitivity
import com.achulkov.evomolior.core.grader.FailureLevel
import com.achulkov.evomolior.core.grader.GraderNodeId
import com.achulkov.evomolior.core.grader.GraderNodeKind
import com.achulkov.evomolior.core.grader.GraderNodeStatus
import com.achulkov.evomolior.core.grader.GraderView
import com.achulkov.evomolior.core.grader.GraderViewNode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraderMethodologyBindingCatalogTest {
    @Test
    fun `handles are deterministic and stable across same-node lease reacquisition`() {
        val first = catalog(view())
        val retry = catalog(view())
        val reacquired = catalog(
            view(leaseId = "lease-2", leaseHash = "lease-hash-2")
        )

        assertEquals(first.nodeBindings, retry.nodeBindings)
        assertEquals(first.catalogHash, retry.catalogHash)
        assertEquals(first.nodeBindings, reacquired.nodeBindings)
        assertNotEquals(first.catalogHash, reacquired.catalogHash)
        assertTrue(first.hasValidHash())
    }

    @Test
    fun `valid wire draft projects exact IDs and preserves every semantic field`() {
        val catalog = catalog(view())
        val wire = wire(catalog)

        val result = assertIs<GraderMethodologyBindingResolution.Resolved>(
            catalog.resolve(wire)
        )

        assertEquals(wire.rubricDimensions, result.draft.rubricDimensions)
        assertEquals(wire.automatedTests, result.draft.automatedTests)
        assertEquals(wire.failureModes, result.draft.failureModes)
        assertEquals(GraderNodeId("grader-exact-a"), result.draft.requirements.single().graderAtlasNodeId)
        assertEquals(
            setOf(NodeId("worker-exact-a")),
            result.draft.requirements.single().mappedWorkerNodeIds
        )
        assertEquals(
            wire.requirements.single().criterion,
            result.draft.requirements.single().criterion
        )
    }

    @Test
    fun `persisted exact methodology projects back to the same redacted repair draft`() {
        val catalog = catalog(view())
        val wire = wire(catalog)
        val exact = assertIs<GraderMethodologyBindingResolution.Resolved>(
            catalog.resolve(wire)
        ).draft

        assertEquals(wire, catalog.projectForRepair(exact))
        assertNull(
            catalog.projectForRepair(
                exact.copy(
                    requirements = exact.requirements.map {
                        it.copy(mappedWorkerNodeIds = setOf(NodeId("worker-foreign")))
                    }
                )
            )
        )
    }

    @Test
    fun `accepted methodology carries into the next round only while its frontier is still leased`() {
        val previousRound = catalog(view())
        val accepted = assertIs<GraderMethodologyBindingResolution.Resolved>(
            previousRound.resolve(wire(previousRound))
        ).draft

        // Next round re-leases the same grader node under a new lease: the baseline re-binds.
        val nextRound = catalog(view(leaseId = "lease-r2", leaseHash = "lease-hash-r2").copy(round = 2))
        val baseline = kotlin.test.assertNotNull(nextRound.projectForRepair(accepted))
        assertEquals(
            accepted,
            assertIs<GraderMethodologyBindingResolution.Resolved>(nextRound.resolve(baseline)).draft
        )

        // A moved frontier cannot silently reuse bindings for nodes it no longer leases.
        val moved = catalog(
            view().copy(
                round = 2,
                nodes = listOf(
                    view().nodes.single().copy(
                        nodeId = GraderNodeId("grader-exact-b"),
                        mappedWorkerNodeIds = setOf(NodeId("worker-exact-c"))
                    )
                )
            )
        )
        assertNull(moved.projectForRepair(accepted))
    }

    @Test
    fun `repair feedback redacts exact Atlas lease and catalog authority`() {
        val catalog = catalog(view())
        val exactAuthorities = buildList {
            add(catalog.graderAtlasHash)
            add(catalog.workerAtlasHash)
            add(catalog.catalogHash)
            catalog.activeLeases.forEach { lease ->
                add(lease.graderNodeId.value)
                add(lease.leaseId)
                add(lease.leaseHash)
            }
            catalog.nodeBindings.forEach { row ->
                add(row.graderNodeId.value)
                row.workerBindings.forEach { add(it.workerNodeId.value) }
            }
        }
        val feedback = "Keep the semantic criterion; invalid authority: " +
            exactAuthorities.joinToString(" ")

        val redacted = catalog.redactExactAuthority(feedback)

        assertTrue(redacted.startsWith("Keep the semantic criterion"))
        assertTrue(exactAuthorities.none(redacted::contains), redacted)
        assertTrue("redacted-authority-" in redacted)
    }

    @Test
    fun `model-facing view redacts exact node and lease identities`() {
        val catalog = catalog(view())

        val encoded = Json.encodeToString(catalog.modelView)

        assertFalse("grader-exact-a" in encoded)
        assertFalse("worker-exact-a" in encoded)
        assertFalse("worker-exact-b" in encoded)
        assertFalse("lease-1" in encoded)
        assertFalse("lease-hash-1" in encoded)
        assertTrue(catalog.nodeBindings.single().graderNodeHandle in encoded)
    }

    @Test
    fun `unknown and out-of-row handles are rejected without projection`() {
        val catalog = catalog(twoRowView())
        val first = catalog.nodeBindings.first()
        val secondWorkerHandle = catalog.nodeBindings.last().workerBindings.single().workerNodeHandle
        val base = wire(catalog, row = first)
        val requirement = base.requirements.single()
        val result = catalog.resolve(
            base.copy(
                requirements = listOf(
                    requirement.copy(
                        mappedWorkerNodeHandles = setOf("w_unknown", secondWorkerHandle)
                    )
                )
            )
        )

        val errors = assertIs<GraderMethodologyBindingResolution.Rejected>(result).errors
        assertTrue(errors.any {
            it.code == GraderMethodologyBindingErrorCode.UNKNOWN_WORKER_HANDLE
        })
        assertTrue(errors.any {
            it.code == GraderMethodologyBindingErrorCode.WORKER_HANDLE_OUT_OF_ROW
        })
    }

    @Test
    fun `incomplete worker coverage is recoverably rejected`() {
        val catalog = catalog(multiWorkerView())
        val wire = wire(catalog)

        val result = catalog.resolve(
            wire.copy(
                requirements = wire.requirements.dropLast(1)
            )
        )

        val errors = assertIs<GraderMethodologyBindingResolution.Rejected>(result).errors
        val coverageError = errors.first {
            it.code == GraderMethodologyBindingErrorCode.INCOMPLETE_WORKER_COVERAGE
        }
        val missingHandle = wire.requirements.last().mappedWorkerNodeHandles.single()
        assertTrue(coverageError.message.contains(missingHandle))
        assertFalse(coverageError.message.contains("worker-exact-a"))
        assertFalse(coverageError.message.contains("worker-exact-b"))
    }

    @Test
    fun `one requirement cannot govern multiple atomic worker nodes`() {
        val catalog = catalog(multiWorkerView())
        val wire = wire(catalog)
        val combined = wire.requirements.first().copy(
            mappedWorkerNodeHandles = wire.requirements
                .flatMapTo(linkedSetOf()) { it.mappedWorkerNodeHandles }
        )

        val result = catalog.resolve(
            wire.copy(requirements = listOf(combined))
        )

        val errors = assertIs<GraderMethodologyBindingResolution.Rejected>(result).errors
        assertTrue(errors.any {
            it.code == GraderMethodologyBindingErrorCode.NON_ATOMIC_WORKER_COVERAGE
        })
    }

    @Test
    fun `model-facing worker handles include sealed atomic semantics without exact IDs`() {
        val catalog = catalog(view())
        val descriptor = catalog.modelView.rows.single().workerNodes.single()

        assertEquals(NodeKind.EVIDENCE, descriptor.kind)
        assertEquals("Can the exact local evidence claim be established?", descriptor.atomicQuestion)
        assertEquals(
            setOf("Provide one source-bound claim."),
            descriptor.proofContract.acceptanceCriteria
        )
        assertFalse(descriptor.workerNodeHandle.contains("worker-exact-a"))
    }

    @Test
    fun `empty worker handles and receiver coverage are rejected`() {
        val catalog = catalog(view())
        val wire = wire(catalog)

        val result = catalog.resolve(
            wire.copy(
                requirements = listOf(
                    wire.requirements.single().copy(
                        mappedWorkerNodeHandles = setOf(""),
                        mappedReceiverObligationRefs = emptySet()
                    )
                )
            )
        )

        val errors = assertIs<GraderMethodologyBindingResolution.Rejected>(result).errors
        assertTrue(errors.any { it.code == GraderMethodologyBindingErrorCode.EMPTY_HANDLE })
        assertTrue(errors.any {
            it.code == GraderMethodologyBindingErrorCode.EMPTY_RECEIVER_COVERAGE
        })
    }

    @Test
    fun `foreign handles are rejected by the local catalog`() {
        val local = catalog(view())
        val foreign = catalog(
            view(runId = "run-foreign", graderGroupId = "grader-foreign")
        )
        val foreignWire = wire(foreign)
        val result = local.resolve(foreignWire)

        val errors = assertIs<GraderMethodologyBindingResolution.Rejected>(result).errors
        assertTrue(errors.any {
            it.code == GraderMethodologyBindingErrorCode.FOREIGN_HANDLE
        })
    }

    @Test
    fun `foreign handles are rejected even inside a local wire payload`() {
        val local = catalog(view())
        val foreign = catalog(
            view(runId = "run-foreign", graderGroupId = "grader-foreign")
        )
        val localWire = wire(local)
        val foreignRow = foreign.nodeBindings.single()

        val result = local.resolve(
            localWire.copy(
                requirements = listOf(
                    localWire.requirements.single().copy(
                        graderNodeHandle = foreignRow.graderNodeHandle,
                        mappedWorkerNodeHandles = foreignRow.workerBindings
                            .mapTo(linkedSetOf()) { it.workerNodeHandle }
                    )
                )
            )
        )

        val errors = assertIs<GraderMethodologyBindingResolution.Rejected>(result).errors
        assertTrue(errors.any {
            it.code == GraderMethodologyBindingErrorCode.FOREIGN_HANDLE
        })
    }

    @Test
    fun `receiver references outside the selected row are rejected`() {
        val catalog = catalog(view())
        val wire = wire(catalog)
        val result = catalog.resolve(
            wire.copy(
                requirements = listOf(
                    wire.requirements.single().copy(
                        mappedReceiverObligationRefs = setOf("foreign-receiver")
                    )
                )
            )
        )

        val errors = assertIs<GraderMethodologyBindingResolution.Rejected>(result).errors
        assertTrue(errors.any {
            it.code == GraderMethodologyBindingErrorCode.RECEIVER_REF_OUT_OF_ROW
        })
    }

    @Test
    fun `catalog hash is sensitive to active lease ID and hash`() {
        val baseline = catalog(view())
        val changedId = catalog(view(leaseId = "lease-other"))
        val changedHash = catalog(view(leaseHash = "hash-other"))

        assertNotEquals(baseline.catalogHash, changedId.catalogHash)
        assertNotEquals(baseline.catalogHash, changedHash.catalogHash)
    }

    private fun wire(
        catalog: GraderMethodologyBindingCatalog,
        row: GraderMethodologyNodeBinding = catalog.nodeBindings.single()
    ) = GraderMethodologyWireDraft(
        rubricDimensions = listOf(GraderRubricDraft("quality", "Reward exact evidence.")),
        automatedTests = listOf(
            GraderAutomatedTestDraft(
                testId = "test-1",
                description = "Checks the exact evidence commitment.",
                expectedOutputPattern = "verified",
                mappedRequirementIds = setOf("requirement-1"),
                inputSchemaHash = "input",
                expectedOutcomeHash = "outcome"
            )
        ),
        failureModes = listOf(
            GraderFailureModeDraft("unsupported", "The conclusion lacks evidence.")
        ),
        requirements = row.workerBindings.mapIndexed { index, worker ->
            GraderRequirementWireDraft(
                requirementId = "requirement-${index + 1}",
                graderNodeHandle = row.graderNodeHandle,
                mappedWorkerNodeHandles = setOf(worker.workerNodeHandle),
                mappedReceiverObligationRefs = setOf("receiver-a"),
                criterion = "Every accepted conclusion has exact evidence.",
                observableEvidence = "Hash-bound evidence packets.",
                passThreshold = "All conclusions are supported.",
                failureLevels = listOf(FailureLevel(1, "One conclusion is unsupported.", 0.5)),
                adversarialCases = listOf(
                    AdversarialCase(
                        caseId = "case-1",
                        setup = "Substitute unrelated evidence.",
                        expectedDetection = "The substitution is rejected.",
                        boundedCost = 1.0
                    )
                ),
                executableTestIds = listOf("test-1"),
                disclosureSensitivity = DisclosureSensitivity.HIDDEN_TEST,
                rationale = "Prevents unsupported conclusions.",
                noveltyFingerprint = "exact-evidence-v1",
                maximumEvaluationCost = 1.0
            )
        }
    )

    private fun view(
        runId: String = "run-1",
        graderGroupId: String = "grader-group-a",
        leaseId: String = "lease-1",
        leaseHash: String = "lease-hash-1"
    ) = GraderView(
        runId = runId,
        graderAtlasHash = "grader-atlas-hash",
        graderGroupId = graderGroupId,
        round = 1,
        nodes = listOf(
            GraderViewNode(
                nodeId = GraderNodeId("grader-exact-a"),
                kind = GraderNodeKind.RECEIVER_COVERAGE,
                title = "Receiver coverage",
                evaluationQuestion = "Is every conclusion supported?",
                receiverObligationRefs = setOf("receiver-a"),
                mappedWorkerNodeIds = setOf(NodeId("worker-exact-a")),
                status = GraderNodeStatus.LEASED,
                leasedByThisGroup = true,
                activeLeaseId = leaseId,
                activeLeaseHash = leaseHash
            )
        )
    )

    private fun multiWorkerView(): GraderView = view().copy(
        nodes = listOf(
            view().nodes.single().copy(
                mappedWorkerNodeIds = setOf(
                    NodeId("worker-exact-a"),
                    NodeId("worker-exact-b")
                )
            )
        )
    )

    private fun twoRowView(): GraderView {
        val first = view().nodes.single()
        return view().copy(
            nodes = listOf(
                first,
                first.copy(
                    nodeId = GraderNodeId("grader-exact-b"),
                    mappedWorkerNodeIds = setOf(NodeId("worker-exact-c")),
                    activeLeaseId = "lease-2",
                    activeLeaseHash = "lease-hash-2"
                )
            )
        )
    }

    private fun catalog(view: GraderView): GraderMethodologyBindingCatalog =
        GraderMethodologyBindingCatalog.from(
            view,
            workerAtlas(
                runId = view.runId,
                nodeIds = view.nodes.flatMapTo(linkedSetOf()) { it.mappedWorkerNodeIds }
            )
        )

    private fun workerAtlas(
        runId: String,
        nodeIds: Set<NodeId>
    ): NodeAtlas {
        val nodes = nodeIds.associateWith { nodeId ->
            LimitationNode(
                id = nodeId,
                kind = NodeKind.EVIDENCE,
                layer = 0,
                parentIds = emptySet(),
                prerequisiteIds = emptySet(),
                receiverWhyRef = "receiver-a",
                limitationStatement = "The local evidence claim is not yet established.",
                capabilityTarget = "One source-bound local evidence claim.",
                atomicQuestion = "Can the exact local evidence claim be established?",
                proofContract = ProofContract(
                    method = ProofMethod.SOURCE_CORROBORATION,
                    acceptanceCriteria = setOf("Provide one source-bound claim.")
                ),
                deliverableContract = NodeDeliverableContract(
                    acceptedArtifactTypes = setOf(ArtifactType.CLAIM_SET),
                    requiredFields = setOf("claim", "source")
                ),
                revealPolicy = RevealPolicy(
                    stageId = "stage-0",
                    mode = RevealMode.INITIAL
                ),
                replicationPolicy = ReplicationPolicy(),
                capturePolicy = CapturePolicy(minimumGrade = 0.6),
                rewardSpec = RewardSpec(
                    rewardClass = RewardClass.SMALL,
                    credits = 1.0
                )
            ).withComputedPayloadHash()
        }
        val stage = DisclosureStage(
            stageId = "stage-0",
            stageIndex = 0,
            title = "Foundations",
            nodeIds = nodeIds,
            mandatoryNodeIds = nodeIds,
            evidenceCoverageThreshold = 1.0,
            integrationReadinessThreshold = 1.0,
            revealPayloadHash = AtlasHashing.hashDisclosurePayload(
                stageId = "stage-0",
                nodeIds = nodeIds,
                nodes = nodes
            )
        )
        return NodeAtlas(
            atlasId = "worker-atlas-$runId",
            runId = runId,
            version = 1,
            receiverContractHash = "receiver-contract-hash",
            rootNodeIds = nodeIds,
            nodes = nodes,
            stageDefinitions = listOf(stage)
        ).withComputedHash()
    }
}
