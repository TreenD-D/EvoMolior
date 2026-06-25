package com.achulkov.evokammer.core

import com.achulkov.evokammer.core.model.Role
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoleTest {

    @Test
    fun `civilisational roles are correctly identified`() {
        assertTrue(Role.Researcher.isCivilisationalRole)
        assertTrue(Role.Builder.isCivilisationalRole)
        assertTrue(Role.Planner.isCivilisationalRole)
        assertTrue(Role.Critic.isCivilisationalRole)
        assertTrue(Role.Archivist.isCivilisationalRole)
        assertTrue(Role.Educator.isCivilisationalRole)
        assertTrue(Role.Connector.isCivilisationalRole)
        assertTrue(Role.Outlier.isCivilisationalRole)
    }

    @Test
    fun `council roles are not civilisational`() {
        assertFalse(Role.CouncilTaskAnatomist.isCivilisationalRole)
        assertFalse(Role.CouncilIncentiveDesigner.isCivilisationalRole)
        assertFalse(Role.CouncilEcologist.isCivilisationalRole)
        assertFalse(Role.CouncilPromptArchitect.isCivilisationalRole)
        assertFalse(Role.CouncilKGArchitect.isCivilisationalRole)
    }

    @Test
    fun `grader roles are not civilisational`() {
        assertFalse(Role.GraderResearcher.isCivilisationalRole)
        assertFalse(Role.GraderBuilder.isCivilisationalRole)
        assertFalse(Role.GraderCritic.isCivilisationalRole)
    }

    @Test
    fun `oversight roles are not civilisational`() {
        assertFalse(Role.Inspector.isCivilisationalRole)
        assertFalse(Role.Auditor.isCivilisationalRole)
        assertFalse(Role.PerspectiveTaker.isCivilisationalRole)
        assertFalse(Role.MetaPlanner.isCivilisationalRole)
    }

    @Test
    fun `Outlier does not receive legacy protocol`() {
        assertFalse(Role.Outlier.receivesLegacyProtocol)
    }

    @Test
    fun `PerspectiveTaker does not receive legacy protocol`() {
        assertFalse(Role.PerspectiveTaker.receivesLegacyProtocol)
    }

    @Test
    fun `civilisational roles except Outlier receive legacy protocol`() {
        val eligibleRoles = listOf(
            Role.Researcher, Role.Builder, Role.Planner,
            Role.Critic, Role.Archivist, Role.Educator, Role.Connector
        )
        eligibleRoles.forEach { role ->
            assertTrue(role.receivesLegacyProtocol, "${role.name} should receive legacy protocol")
        }
    }

    @Test
    fun `council roles do not receive legacy protocol`() {
        Role.entries.filter { it.isCouncilRole }.forEach { role ->
            assertFalse(role.receivesLegacyProtocol, "${role.name} should not receive legacy protocol")
        }
    }

    @Test
    fun `isCouncilRole prefix detection is correct`() {
        Role.entries.forEach { role ->
            if (role.name.startsWith("Council")) {
                assertTrue(role.isCouncilRole, "${role.name} should be a council role")
            } else {
                assertFalse(role.isCouncilRole, "${role.name} should NOT be a council role")
            }
        }
    }
}
