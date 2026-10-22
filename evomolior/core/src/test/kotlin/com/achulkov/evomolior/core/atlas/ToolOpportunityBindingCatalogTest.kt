package com.achulkov.evomolior.core.atlas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ToolOpportunityBindingCatalogTest {
    private val capability = ToolOpportunityBindingCapability(
        runId = "run-binding",
        groupId = "group-binding",
        round = 3,
        workerViewHash = "a".repeat(64)
    )
    private val binding = ToolOpportunityBinding(
        handle = "T1",
        nodeId = "node-tool",
        opportunityId = "opportunity-tool",
        opportunityHash = "b".repeat(64),
        inputSchemaHash = "c".repeat(64),
        outputSchemaHash = "d".repeat(64),
        publicTestIds = listOf("public-boundary"),
        runtimeKind = ToolRuntimeKind.PYTHON,
        projectSkeletonHash = "e".repeat(64)
    )
    private val catalog = ToolOpportunityBindingCatalog(
        catalogId = "catalog-binding",
        runId = capability.runId,
        groupId = capability.groupId,
        round = capability.round,
        workerViewHash = capability.workerViewHash,
        bindings = listOf(binding)
    ).withComputedHash()

    @Test
    fun `unknown stale and foreign handles reject without fuzzy resolution`() {
        assertEquals(
            ToolOpportunityBindingRejection.UNKNOWN_HANDLE,
            assertIs<ToolOpportunityBindingResolution.Rejected>(
                catalog.resolve("T2", capability)
            ).reason
        )
        assertEquals(
            ToolOpportunityBindingRejection.STALE_CAPABILITY,
            assertIs<ToolOpportunityBindingResolution.Rejected>(
                catalog.resolve("T1", capability.copy(round = 4))
            ).reason
        )
        assertEquals(
            ToolOpportunityBindingRejection.STALE_CAPABILITY,
            assertIs<ToolOpportunityBindingResolution.Rejected>(
                catalog.resolve(
                    "T1",
                    capability.copy(workerViewHash = "f".repeat(64))
                )
            ).reason
        )
        assertEquals(
            ToolOpportunityBindingRejection.FOREIGN_CAPABILITY,
            assertIs<ToolOpportunityBindingResolution.Rejected>(
                catalog.resolve("T1", capability.copy(groupId = "other-group"))
            ).reason
        )
    }

    @Test
    fun `duplicate handles and tampered catalogs fail closed`() {
        assertFailsWith<IllegalArgumentException> {
            catalog.copy(
                bindings = listOf(
                    binding,
                    binding.copy(
                        nodeId = "node-other",
                        opportunityId = "opportunity-other"
                    )
                ),
                catalogHash = ""
            )
        }
        assertEquals(
            ToolOpportunityBindingRejection.TAMPERED_CATALOG,
            assertIs<ToolOpportunityBindingResolution.Rejected>(
                catalog.copy(catalogId = "tampered").resolve("T1", capability)
            ).reason
        )
    }
}
