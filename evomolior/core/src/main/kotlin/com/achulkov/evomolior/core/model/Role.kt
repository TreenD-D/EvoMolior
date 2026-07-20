package com.achulkov.evomolior.core.model

import kotlinx.serialization.Serializable

/**
 * All agent roles in the EvoMolior system.
 *
 * Roles are used to determine which tools are available, which system prompt is injected,
 * which model is assigned, and which constitutional rules apply (network allowlist, etc.).
 *
 * Council and Grader roles are only instantiated during Genesis Council initialization
 * and grader group competition phases respectively.
 */
@Serializable
enum class Role {
    // --- Core civilisational roles ---

    /** Discovers, retrieves, and synthesises external knowledge into the KB. */
    Researcher,

    /** Builds, tests, and submits tools to the Tool Registry. */
    Builder,

    /** Decomposes the objective, manages WiP tasks, updates the Plan Registry. */
    Planner,

    /** Reviews staged submissions; issues PROMOTE, REJECT, or DISPUTE decisions. */
    Critic,

    /** Organises, cross-references, and improves existing KB documents. */
    Archivist,

    /** Creates teaching materials and onboarding documents from canon KB. */
    Educator,

    /** Identifies KB gaps, bridges isolated knowledge clusters, cross-domain synthesis. */
    Connector,

    /** Explores unconventional directions; ignores Legacy Protocols; never claims WiP tasks. */
    Outlier,

    // --- Tier 0 oversight roles (spawned by Watchdog, not EvolutionarySelector) ---

    /** Passive log reader that emits anomaly flags (no TTL, runs continuously). */
    Inspector,

    /** Mortal LLM agent spawned on high-severity Inspector flags; can quarantine docs. */
    Auditor,

    /** Mortal LLM agent spawned on coordination gaps; synthesises missing viewpoints. */
    PerspectiveTaker,

    /** Mortal LLM agent with elevated group-wide read; rewrites Plan Registry on stasis. */
    MetaPlanner,

    // --- Genesis Council roles (transient, single-run at genesis) ---

    /** Decomposes the objective into the knowledge node graph and check-in schedule. */
    CouncilTaskAnatomist,

    /** Designs action costs, scoring weights, and resource node yields. */
    CouncilIncentiveDesigner,

    /** Designs group count, profiles, ring topology, and communication economics. */
    CouncilEcologist,

    /** Generates role-specific system prompts, Death Note schema, Legacy Protocol template. */
    CouncilPromptArchitect,

    /** Defines the typed KG schema (entity types, relation types, hard/soft constraints). */
    CouncilKGArchitect,

    /** Assigns LLM models to roles, designs cognitive diversity layout. */
    CouncilModelEcologist,

    /** Assembles the primordial KB + KG seed, initial tools, and founding norms. */
    CouncilSeedCurator,

    /** Merges all council outputs; runs consistency check; produces the final StartingField. */
    CouncilSynthesis,

    // --- Grader Group roles (separate civilisation, no access to competing groups' KBs) ---

    /** Grader equivalent of Researcher — discovers evaluation approaches. */
    GraderResearcher,

    /** Grader equivalent of Builder — builds automated test tools. */
    GraderBuilder,

    /** Grader equivalent of Critic — reviews and validates GradingSheet dimensions. */
    GraderCritic;

    /** True if this role is a Genesis Council role. */
    val isCouncilRole: Boolean
        get() = name.startsWith("Council")

    /** True if this role is a Grader Group role. */
    val isGraderRole: Boolean
        get() = name.startsWith("Grader")

    /** True if this is a standard civilisational role (Researcher, Builder, Planner, etc.). */
    val isCivilisationalRole: Boolean
        get() = !isCouncilRole && !isGraderRole &&
            this != Inspector && this != Auditor && this != PerspectiveTaker && this != MetaPlanner

    /** True for mortal workers governed by redacted WorkerView context authority. */
    val isOrdinaryWorkerRole: Boolean
        get() = isCivilisationalRole || this == PerspectiveTaker

    /** True if this role should never receive Legacy Protocols (per spec). */
    val receivesLegacyProtocol: Boolean
        get() = isCivilisationalRole && this != Outlier

    /**
     * The set of roles this role is allowed to delegate to via [SubAgentCoordinator].
     *
     * Delegation rules (Phase 9):
     * - Researcher can delegate to Researcher (focus sub-tasks)
     * - Builder can delegate to Builder (component sub-tasks) or Researcher (discovery)
     * - Planner can delegate to any civilisational role except Outlier/Critic
     * - Critic cannot delegate (review integrity requires full agent focus)
     * - Archivist/Educator/Connector can delegate to Researcher (sub-queries)
     * - Outlier cannot delegate (unconventional direction requires full focus)
     * - Oversight and council/grader roles cannot delegate
     */
    val canDelegate: Set<Role>
        get() = when (this) {
            Researcher  -> setOf(Researcher)
            Builder     -> setOf(Builder, Researcher)
            Planner     -> setOf(Researcher, Builder, Archivist, Educator, Connector)
            Archivist   -> setOf(Researcher)
            Educator    -> setOf(Researcher, Archivist)
            Connector   -> setOf(Researcher)
            PerspectiveTaker -> setOf(Researcher, Archivist, Critic)
            MetaPlanner -> setOf(Planner, Researcher, Archivist)
            else        -> emptySet()
        }

    /** True if this role supports sub-agent delegation. */
    val canDelegateToSubAgents: Boolean get() = canDelegate.isNotEmpty()
}
