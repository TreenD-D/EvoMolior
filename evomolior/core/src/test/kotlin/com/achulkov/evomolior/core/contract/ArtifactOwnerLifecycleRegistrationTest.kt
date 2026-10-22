@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.core.contract

import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class ArtifactOwnerLifecycleRegistrationTest {
    @Test
    fun `ArtifactOwner duty and workspace lineage participate in registration hash`() {
        val registration = AgentLifecycleRegistration(
            registrationId = "registration-builder",
            runId = "run",
            groupId = AtlasGroupId("group"),
            agentId = AtlasAgentId("builder"),
            role = "Builder",
            generation = 1,
            classification = AgentLifecycleClassification.NON_DAEMON,
            registeredAt = Instant.parse("2026-08-02T12:00:00Z"),
            duty = AgentLifecycleDuty.ARTIFACT_OWNER,
            workspaceId = "workspace-1",
            workspaceSourceHash = "source-hash-1"
        ).withComputedHash()

        assertTrue(registration.hasValidHash())
        assertNotEquals(
            registration.registrationHash,
            registration.copy(workspaceSourceHash = "source-hash-2").withComputedHash()
                .registrationHash
        )
    }
}
