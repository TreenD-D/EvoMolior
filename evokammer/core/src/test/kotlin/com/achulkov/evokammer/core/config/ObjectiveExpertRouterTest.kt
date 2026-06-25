package com.achulkov.evokammer.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObjectiveExpertRouterTest {

    @Test
    fun `research objectives select the research heavy expert bundle`() {
        val bundle = ObjectiveExpertRouter.select("Run a literature research survey with evidence gathering")

        assertEquals("research-heavy", bundle.name)
        assertEquals(ModelSelectionConfig.DEFAULT_RUNTIME_RESEARCHER_MODEL, bundle.modelAssignment["Researcher"])
        assertTrue(bundle.modelPools["Researcher"]!!.contains(ModelSelectionConfig.DEFAULT_RUNTIME_FALLBACK_MODEL))
    }

    @Test
    fun `build objectives select the build heavy expert bundle`() {
        val bundle = ObjectiveExpertRouter.select("Implement a toolchain patch and build pipeline")

        assertEquals("build-heavy", bundle.name)
        assertEquals(ModelSelectionConfig.DEFAULT_RUNTIME_BUILDER_MODEL, bundle.modelAssignment["Builder"])
        assertTrue(bundle.modelPools["Builder"]!!.contains(ModelSelectionConfig.DEFAULT_RUNTIME_FALLBACK_MODEL))
    }

    @Test
    fun `synthesis objectives select the synthesis heavy expert bundle`() {
        val bundle = ObjectiveExpertRouter.select("Design a strategy and roadmap for system synthesis")

        assertEquals("synthesis-heavy", bundle.name)
        assertEquals(ModelSelectionConfig.DEFAULT_RUNTIME_METAPLANNER_MODEL, bundle.modelAssignment["MetaPlanner"])
        assertTrue(bundle.modelPools["Planner"]!!.contains(ModelSelectionConfig.DEFAULT_RUNTIME_PLANNER_MODEL))
    }

    @Test
    fun `coordination objectives select the coordination heavy expert bundle`() {
        val bundle = ObjectiveExpertRouter.select("Coordinate integration across multiple workstreams")

        assertEquals("coordination-heavy", bundle.name)
        assertEquals(ModelSelectionConfig.DEFAULT_RUNTIME_METAPLANNER_MODEL, bundle.modelAssignment["MetaPlanner"])
        assertTrue(bundle.modelPools["Connector"]!!.contains(ModelSelectionConfig.DEFAULT_RUNTIME_FALLBACK_MODEL))
        assertTrue(bundle.modelPools["Connector"]!!.contains(ModelSelectionConfig.DEFAULT_RUNTIME_PLANNER_MODEL))
    }

    @Test
    fun `perspective objectives select the perspective heavy expert bundle`() {
        val bundle = ObjectiveExpertRouter.select("Evaluate stakeholder perspectives and blind spots")

        assertEquals("perspective-heavy", bundle.name)
        assertEquals(
            ModelSelectionConfig.DEFAULT_RUNTIME_PERSPECTIVE_TAKER_MODEL,
            bundle.modelAssignment["PerspectiveTaker"]
        )
        assertEquals(ModelSelectionConfig.DEFAULT_RUNTIME_CRITIC_MODEL, bundle.modelAssignment["Critic"])
        assertEquals(ModelSelectionConfig.DEFAULT_RUNTIME_AUDITOR_MODEL, bundle.modelAssignment["Auditor"])
        assertEquals(ModelSelectionConfig.DEFAULT_RUNTIME_RESEARCHER_MODEL, bundle.modelAssignment["Educator"])
        assertTrue(bundle.modelPools["PerspectiveTaker"]!!.contains(ModelSelectionConfig.DEFAULT_RUNTIME_PLANNER_MODEL))
        assertTrue(bundle.modelPools["Critic"]!!.contains(ModelSelectionConfig.DEFAULT_RUNTIME_FALLBACK_MODEL))
        assertTrue(bundle.modelPools["Educator"]!!.contains(ModelSelectionConfig.DEFAULT_RUNTIME_FALLBACK_MODEL))
        assertTrue(bundle.modelAssignment.containsKey("Inspector"))
    }

    @Test
    fun `coordination keywords take precedence over perspective keywords`() {
        val bundle = ObjectiveExpertRouter.select("Coordinate stakeholder perspectives across workstreams")

        assertEquals("coordination-heavy", bundle.name)
        assertEquals(ModelSelectionConfig.DEFAULT_RUNTIME_METAPLANNER_MODEL, bundle.modelAssignment["MetaPlanner"])
        assertTrue(bundle.modelAssignment.containsKey("Planner"))
        assertTrue(bundle.modelAssignment.containsKey("Connector"))
    }

    @Test
    fun `unknown objectives fall back to the balanced baseline bundle`() {
        val bundle = ObjectiveExpertRouter.select("Monitor a run")

        assertEquals("balanced-baseline", bundle.name)
        assertEquals(ModelSelectionConfig.defaultRuntimeModelPools(), bundle.modelPools)
        assertTrue(bundle.modelAssignment.containsKey("Researcher"))
        assertTrue(bundle.modelAssignment.containsKey("Builder"))
    }
}
