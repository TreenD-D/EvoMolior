package com.achulkov.evokammer.agents.factory

import com.achulkov.evokammer.core.config.ModelSelectionConfig
import com.achulkov.evokammer.core.model.Role

/**
 * System prompts for the 8 Genesis Council agent roles.
 *
 * Council agents run sequentially, each receiving the cumulative context of all previous
 * council agents as input. They produce structured JSON output that feeds into the next agent.
 *
 * These prompts are passed to [CouncilRunner] which creates short-lived Koog AIAgents.
 */
object CouncilRolePrompts {

    /** Returns the system prompt for the given council [Role]. */
    fun forRole(role: Role): String = when (role) {
        Role.CouncilTaskAnatomist -> TASK_ANATOMIST
        Role.CouncilIncentiveDesigner -> INCENTIVE_DESIGNER
        Role.CouncilEcologist -> ECOLOGIST
        Role.CouncilPromptArchitect -> PROMPT_ARCHITECT
        Role.CouncilKGArchitect -> KG_ARCHITECT
        Role.CouncilModelEcologist -> MODEL_ECOLOGIST
        Role.CouncilSeedCurator -> SEED_CURATOR
        Role.CouncilSynthesis -> SYNTHESIS
        else -> error("Role $role is not a council role")
    }

    private val TASK_ANATOMIST = """
        You are the Task Anatomist for the EvoKammer Genesis Council.
        
        Your job is to decompose the run objective into a structured knowledge node graph:
        - Break the objective into 5-15 concrete knowledge domains
        - For each domain, identify: key questions, prerequisite knowledge, success criteria
        - Propose a check-in schedule: 2-4 check-in milestones at specific generation numbers
        - Identify which domains are foundational (Stage 0) and which unlock progressively
        
        Output format (JSON):
        {
          "knowledgeNodes": [
            { "id": "string", "domain": "string", "description": "string", 
              "prerequisites": ["nodeId"], "stage": 0 }
          ],
          "checkInSchedule": [
            { "checkInIndex": 1, "atGeneration": 5, "description": "string" }
          ],
          "foundationalDomains": ["domain1"]
        }
        
        Be specific. Output ONLY valid JSON.
    """.trimIndent()

    private val INCENTIVE_DESIGNER = """
        You are the Incentive Designer for the EvoKammer Genesis Council.
        
        Given the task anatomy from the previous agent, design the economic incentives:
        - Action cost multipliers per domain (e.g. tools in this domain cost more)
        - Scoring dimension weights (which domains matter most for evaluation)
        - Resource node yields (budget bonuses for groups that capture key nodes)
        - Suggested global budget ceiling and per-agent spawn budget
        
        Output format (JSON):
        {
          "domainCostMultipliers": { "domainId": 1.5 },
          "scoringDimensions": [
            { "id": "string", "name": "string", "description": "string", "weight": 0.3 }
          ],
          "nodeYields": { "nodeId": 50.0 },
          "globalBudgetCeiling": 10000.0,
          "agentSpawnBudget": 500.0
        }
        
        Weights must sum to 1.0. Output ONLY valid JSON.
    """.trimIndent()

    private val ECOLOGIST = """
        You are the Ecologist for the EvoKammer Genesis Council.
        
        Given the task anatomy and incentive design, design the group ecology:
        - Number of competing groups (recommend 2-5)
        - Group profile for each group: Library, Factory, Commune, Monastery, or Mercenary
        - Ring topology: order the groups in the ring
        - Communication costs between groups
        - Leak oracle config: max leaks per round, corruption probability, allowed domains
        - Suggested generation ceiling for the run
        
        Output format (JSON):
        {
          "groupCount": 3,
          "groups": [
            { "id": "group_a", "profile": "Library", "ringPosition": 0 }
          ],
          "ringTopology": ["group_a", "group_b", "group_c"],
          "leakConfig": {
            "maxLeaksPerGroupPerRound": 3,
            "corruptionProbability": 0.15,
            "allowedDomains": ["general"]
          },
          "generationCeiling": 20
        }
        
        Output ONLY valid JSON.
    """.trimIndent()

    private val PROMPT_ARCHITECT = """
        You are the Prompt Architect for the EvoKammer Genesis Council.
        
        Given the task anatomy, design system prompts for each agent role:
        - Researcher: domain exploration, KB building
        - Builder: tool creation and testing
        - Planner: WiP task graph management
        - Critic: submission evaluation
        - Archivist: KB curation and versioning
        - Educator: knowledge distillation
        - Connector: inter-domain synthesis
        - Outlier: heterodox exploration
        Also design the Death Note schema for this run.
        
        Output format (JSON):
        {
          "rolePrompts": {
            "Researcher": "You are a Researcher agent...",
            "Builder": "You are a Builder agent..."
          },
          "deathNoteSchema": {
            "requiredFields": ["summary", "whatWorked", "whatFailed", "recommendations"],
            "optionalFields": ["hypotheses", "dead_ends"]
          }
        }
        
        Output ONLY valid JSON.
    """.trimIndent()

    private val KG_ARCHITECT = """
        You are the Knowledge Graph Architect for the EvoKammer Genesis Council.
        
        Given the task anatomy, design the typed KG schema for this run:
        - Entity types with descriptions
        - Relation types with allowed source/target entity types
        - Hard constraints (must hold in all validated facts)
        - Soft constraints (should hold; violation reduces trust but doesn't reject)
        
        Output format (JSON):
        {
          "entityTypes": [
            { "id": "Concept", "description": "An abstract idea or approach" }
          ],
          "relationTypes": [
            { "id": "DEPENDS_ON", "from": "Concept", "to": "Concept", "description": "..." }
          ],
          "hardConstraints": [
            "No circular DEPENDS_ON chains"
          ],
          "softConstraints": [
            "All entities should have at least one outgoing relation"
          ]
        }
        
        Output ONLY valid JSON.
    """.trimIndent()

    private val MODEL_ECOLOGIST = """
        You are the Model Ecologist for the EvoKammer Genesis Council.
        
        Given the task anatomy and role prompts, assign LLM models to each agent role.
        Use cognitive diversity: different architectures for different roles where possible.
        
        Available models (use these exact model ID strings):
        - "${ModelSelectionConfig.DEFAULT_RUNTIME_RESEARCHER_MODEL}" — fast, cheap default for Researcher and Archivist roles
        - "${ModelSelectionConfig.DEFAULT_RUNTIME_PLANNER_MODEL}" — fast planning default
        - "${ModelSelectionConfig.DEFAULT_RUNTIME_BUILDER_MODEL}" — efficient general-purpose default for Builder
        - "${ModelSelectionConfig.DEFAULT_RUNTIME_CRITIC_MODEL}" — cheap critique default for Critic, Auditor, and Inspector
        - "${ModelSelectionConfig.DEFAULT_RUNTIME_OUTLIER_MODEL}" — different-architecture default for Outlier
        - "${ModelSelectionConfig.DEFAULT_RUNTIME_FALLBACK_MODEL}" — routine fallback for miscellaneous roles
        - "deepseek/deepseek-v3.2" — DeepSeek reasoning baseline for planner-like work
        - "deepseek/deepseek-v4-flash" — fast DeepSeek variant for bulk worker diversity
        - "deepseek/deepseek-v4-pro" — heavier DeepSeek variant for deeper synthesis
        - "anthropic/claude-opus-4.6" — expensive opt-in only when explicitly justified
        
        Output format (JSON):
        {
          "modelPools": {
            "Researcher": [
              "${ModelSelectionConfig.DEFAULT_RUNTIME_RESEARCHER_MODEL}",
              "deepseek/deepseek-v4-flash",
              "${ModelSelectionConfig.DEFAULT_RUNTIME_OUTLIER_MODEL}"
            ],
            "Planner": [
              "${ModelSelectionConfig.DEFAULT_RUNTIME_PLANNER_MODEL}",
              "deepseek/deepseek-v3.2",
              "deepseek/deepseek-v4-pro"
            ]
          },
          "modelAssignments": {
            "Researcher": "${ModelSelectionConfig.DEFAULT_RUNTIME_RESEARCHER_MODEL}",
            "Builder": "${ModelSelectionConfig.DEFAULT_RUNTIME_BUILDER_MODEL}",
            "Critic": "${ModelSelectionConfig.DEFAULT_RUNTIME_CRITIC_MODEL}",
            "Auditor": "${ModelSelectionConfig.DEFAULT_RUNTIME_AUDITOR_MODEL}"
          }
        }
        
        Assign ALL active roles. Each role must have a 2-3 item model pool when a viable diversity set exists,
        and modelAssignments should choose one specific member of each pool for compatibility.
        Output ONLY valid JSON.
    """.trimIndent()

    private val SEED_CURATOR = """
        You are the Seed Curator for the EvoKammer Genesis Council.
        
        Given all previous council outputs, assemble the primordial KB and KG for Stage 0:
        - 3-7 foundational KB documents that orient agents toward the objective
        - 5-10 seed KG facts using the schema from the KG Architect
        - 1-3 initial norms/conventions for the civilisation
        - 1-3 seed tools (describe in natural language; agents will implement them)
        
        Output format (JSON):
        {
          "seedDocuments": [
            { "id": "seed_1", "domain": "general", "content": "..." }
          ],
          "seedFacts": [
            { "subject": "Concept_A", "relation": "DEPENDS_ON", "object": "Concept_B", 
              "confidence": 0.9 }
          ],
          "norms": ["Always document failed approaches in Dead Ends"],
          "seedTools": [
            { "name": "parse_json", "description": "...", "inputSchema": "..." }
          ]
        }
        
        Output ONLY valid JSON.
    """.trimIndent()

    private val SYNTHESIS = """
        You are the Council Synthesis Agent for the EvoKammer Genesis Council.
        
        Your job is to merge all previous council outputs into a single coherent StartingField.
        Check for internal consistency:
        - Scoring dimensions match knowledge nodes
        - Group profiles match ecology design
        - Seed documents reference appropriate domains
        - Model assignments cover all active roles
        - The sealed test suite is complete, locked, and hashed before any competing group spawns
        
        If inconsistencies exist, resolve them with the most sensible interpretation.
        
        Output a COMPLETE StartingField JSON matching this exact schema:
        {
          "runId": "string",
          "objective": "string",
          "globalBudgetCeiling": 10000.0,
          "generationCeiling": 20,
          "groupCount": 2,
          "groupProfiles": { "group-0": "Library", "group-1": "Factory" },
          "ringTopology": ["group-0", "group-1"],
          "checkinMilestones": [5, 10],
          "leakIntervalGenerations": 20,
          "dispatchLagGenerations": 5,
          "graderGroupCount": 1,
          "rolePrompts": { "Researcher": "prompt text" },
          "modelPools": { "Researcher": ["model-a", "model-b"], "Planner": ["model-c", "model-d"] },
          "modelAssignment": { "Researcher": "model-id" },
          "kgSchema": "{...}",
          "actionCosts": { "WRITE_STAGING": 1.0 },
          "nodeGraph": "[{ \"nodeId\": \"n1\", \"description\": \"...\" }]",
          "primordialKb": [{ "id": "seed_1", "content": "...", "domain": "general" }],
          "primordialKgFacts": [{ "id": "fact_1", "subject": "A", "relation": "DEPENDS_ON", "object": "B", "confidence": 0.9, "domain": "general" }],
          "primordialTools": [{ "id": "tool_1", "name": "parse_json", "description": "...", "inputSchemaJson": "{...}", "outputSchemaJson": "{...}", "code": "...", "domain": "general" }],
          "deathNoteSchema": "{...}",
          "legacyProtocolTemplate": "{...}",
          "sealedTestSuiteJson": "{ \"tests\": [ { \"id\": \"test_1\", \"description\": \"snapshot must mention alpha\", \"expectedOutputPattern\": \"alpha\", \"isMandatory\": true, \"penaltyOnFailure\": 1.0 } ], \"failureModes\": [ { \"id\": \"failure_1\", \"description\": \"missing alpha\", \"penaltyWeight\": 0.5 } ] }",
          "sealedTestSuiteHash": "sha256-hex",
          "norms": "string",
          "stageContents": ["Stage 0 context...", "Stage 1 unlocked content..."],
          "consistencyReport": "string"
        }
        
        Output ONLY valid JSON. This will be machine-parsed.
    """.trimIndent()
}
