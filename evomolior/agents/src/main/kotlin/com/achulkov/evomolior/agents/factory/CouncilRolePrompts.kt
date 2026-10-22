package com.achulkov.evomolior.agents.factory

import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.CaptureMode
import com.achulkov.evomolior.core.atlas.EvidenceKind
import com.achulkov.evomolior.core.atlas.FormulaDslReferenceSemantics
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.ProofMethod
import com.achulkov.evomolior.core.atlas.ReplicationMode
import com.achulkov.evomolior.core.atlas.RevealMode
import com.achulkov.evomolior.core.atlas.RewardClass
import com.achulkov.evomolior.core.atlas.ToolBehavior
import com.achulkov.evomolior.core.atlas.ToolImplementationMode
import com.achulkov.evomolior.core.atlas.ToolOpportunityType
import com.achulkov.evomolior.core.atlas.TypedFieldType
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.council.ExecutionEnvelope
import com.achulkov.evomolior.core.council.compileCouncilToolOpportunityAuthority
import com.achulkov.evomolior.core.model.Role
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Strict typed-output prompts for the eight Genesis Council roles.
 *
 * The execution envelope is embedded verbatim in every prompt before field design starts. Role
 * outputs use semantic local keys; deterministic runtime IDs are allocated by the compiler.
 */
object CouncilRolePrompts {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = true
    }

    /**
     * Builds the system prompt for one Council role under an immutable execution envelope.
     *
     * @param role Council role to prompt.
     * @param envelope Runtime constraints that the role must obey.
     * @return Complete role prompt with its exact typed JSON contract.
     */
    fun forRole(role: Role, envelope: ExecutionEnvelope): String {
        require(role.isCouncilRole) { "Role $role is not a Council role" }
        return """
            You are ${role.name}, one specialist in the EvoMolior Genesis Council.

            The operator's raw objective is supplied in the task context. It is immutable. Do not
            rewrite, normalize, summarize, or replace it. Design only within the execution envelope
            below. A deterministic compiler rejects unavailable models, profiles, topologies,
            excessive rewards, oversized atomic nodes, invalid references, and future-stage leaks.

            EXECUTION ENVELOPE (exact JSON):
            ${json.encodeToString(envelope)}

            OUTPUT RULES:
            - Output one JSON object matching the role contract below and no other text.
            - Use semantic local node keys, stage keys, group keys, and test keys. Do not invent
              UUIDs, hashes, Atlas IDs, or final runtime node IDs.
            - Use enum values exactly as listed.
            - Include every required field, including empty arrays/maps where appropriate.
            - Do not add prose outside JSON or wrap JSON in Markdown fences.
            - When the task context contains repairRequest, emit one complete replacement object
              matching this normal role contract. Correct only the exact reported defects and
              preserve every unaffected field from previousOutput.
            - Treat relatedNodeContracts and generatedToolSchemas, when supplied, as immutable
              repair authority. invalidOutput is diagnostic only and must never be copied, merged,
              or treated as a partial object.
            - Treat dependencyOutputs as immutable cross-role authority. When a dependency is
              present, make this output conjunctively compatible with its exact typed values.
            - Treat quantitativeToolContractAuthority, when supplied, as copy-ready immutable
              Task Anatomist provider-wire authority. Transplant every included property exactly
              into one complete toolOpportunity; never substitute easier schema branches.
            - Treat all repairRequest.errors as one conjunctive repair set. Fix every reported
              defect in the same complete replacement; never alternate between partial fixes.
            - A repair response must never be a patch, partial object, alternate schema, or prose.

            ${acceptanceProfileContract(role, envelope)}

            ${if (role == Role.CouncilTaskAnatomist) {
                quantitativeToolObligationContract(envelope)
            } else if (role == Role.CouncilSynthesis) {
                quantitativePromotionFixtureContract(envelope)
            } else {
                ""
            }}

            ${contractFor(role)}
        """.trimIndent()
    }

    private fun acceptanceProfileContract(
        role: Role,
        envelope: ExecutionEnvelope
    ): String = when {
        envelope.acceptanceProfile == AcceptanceProfile.NORMAL -> ""
        role == Role.CouncilTaskAnatomist &&
            envelope.acceptanceProfile == AcceptanceProfile.RUNTIME_USABILITY_SMOKE -> """
                ACCEPTANCE PROFILE: RUNTIME_USABILITY_SMOKE
                Emit exactly one disclosure-stage key across all nodes. Every node must include
                `toolOpportunity` with the literal JSON value null. Do not declare reusable tools,
                promotion tests, or expected reuse. Declare at least one executable GENERAL test.
            """.trimIndent()
        role == Role.CouncilTaskAnatomist &&
            envelope.acceptanceProfile == AcceptanceProfile.APPENDIX_B_FULL -> """
                ACCEPTANCE PROFILE: APPENDIX_B_FULL
                The provider wire replaces `nodes` with two required arrays:
                `nodesWithoutToolOpportunity` and `nodesWithToolOpportunity`. Every entry carries
                an `ordinal`; all ordinals together must be exactly 0 through nodeCount-1.
                Ordinary entries include `toolOpportunity:null`. Typed entries include one complete
                structured `toolOpportunity`, and `nodesWithToolOpportunity` must be non-empty.
                Use at least two disclosure-stage keys. Each opportunity needs exact promotion
                tests, a distinct executable validation node, a later independent expected-reuse
                node schedulable inside rounds 0..5, and a DAG path from reuse into later evidence,
                integration, decision, or final-deliverable authority.
                The later generic ROLE CONTRACT examples use `nodes` only to explain semantic node
                contents. They are not a valid full-profile top-level wire. Never emit a `nodes`
                property for APPENDIX_B_FULL; transpose every example entry into exactly one of
                the two ordinal-bearing partition arrays above.
            """.trimIndent()
        role == Role.CouncilSynthesis &&
            envelope.acceptanceProfile == AcceptanceProfile.RUNTIME_USABILITY_SMOKE -> """
                ACCEPTANCE PROFILE: RUNTIME_USABILITY_SMOKE
                Emit exactly one disclosure stage and at least one GENERAL sealed test. Promotion
                authority is forbidden.
            """.trimIndent()
        role == Role.CouncilSynthesis &&
            envelope.acceptanceProfile == AcceptanceProfile.APPENDIX_B_FULL -> """
                ACCEPTANCE PROFILE: APPENDIX_B_FULL
                Emit at least two ordered disclosure stages and exact PROMOTION sealed tests for
                every Task-authored opportunity. Preserve the fixed six-round scheduling authority.
            """.trimIndent()
        else -> """
            ACCEPTANCE PROFILE: ${envelope.acceptanceProfile.name}
            Obey the explicit acceptance profile in the execution envelope without inferring or
            weakening it.
        """.trimIndent()
    }

    private fun quantitativeToolObligationContract(envelope: ExecutionEnvelope): String {
        val obligation = envelope.quantitativeToolObligation ?: return """
            No operator-owned quantitativeToolObligation is present. Do not infer exact field
            names, units, aggregation semantics, or horizon cardinality from objective prose.
        """.trimIndent()
        val providerAuthority = obligation.compileCouncilToolOpportunityAuthority()
        return """
            OPERATOR-OWNED QUANTITATIVE TOOL CONTRACT
            One and the same `toolOpportunity` must satisfy every field role in this exact
            immutable authority:
            ${json.encodeToString(obligation)}

            COPY-READY TASK ANATOMIST TOOL-OPPORTUNITY AUTHORITY
            The following JSON is an exact fragment of the one complete `toolOpportunity` that
            must carry this contract:
            ${json.encodeToString(providerAuthority)}

            Copy every property and nested value from that fragment exactly. Its requiredInputs
            and requiredOutputs arrays are complete: do not add, omit, rename, reorder, simplify,
            or change the type branch of any field. In particular, never replace a numeric field
            with BOOLEAN or BOOLEAN_ITEMS and never omit physicalDimension or quantitySpec. During
            repair, delete every conflicting prior field object and replace it with the exact
            copy-ready object instead of preserving malformed previousOutput bytes.

            Opportunity-level authority is exact when present:
            - `requiredOpportunityId` must be copied exactly into `opportunityId`.
            - A non-empty `exactPromotionTestIds` is the complete promotionTestIds set; do not add,
              omit, rename, or substitute tests.
            - A non-empty `exactPromotionFixtures` is immutable operator-owned sealed-test
              authority. Council synthesis must copy each testId, input, and expectedOutput
              exactly into the matching PROMOTION definition; never paraphrase or regenerate it.
            - `maximumOpportunityRuntimeMillis` is an inclusive ceiling for
              `maximumRuntimeMillis`; a smaller positive bound is allowed.

            For each `requiredFields` entry:
            - Copy `fieldName` exactly into one top-level field on the declared `location`;
              INPUT means `requiredInputs`, OUTPUT means `requiredOutputs`. Do not nest, rename,
              combine, or substitute fields.
            - The field is required. SCALAR uses the exact `numericType`. HORIZON_SERIES uses
              ARRAY with `items.type` equal to the numeric type's `_ITEMS` discriminator and both
              minimumItems and maximumItems equal to `horizonIntervalCount`.
            - Copy `quantitySpec.dimension` exactly into `physicalDimension`, and copy the complete
              `quantitySpec` exactly. Do not infer unit aliases, scales, aggregation, or time basis.
            - On INTEGER or NUMBER fields, `physicalDimension` and `quantitySpec` are siblings of
              name/type/description/required/validationRules. On numeric ARRAY fields, both belong
              inside `items`; they are not properties of the outer ARRAY object.

            The closed quantitySpec shape is:
            {"dimension":{"exponents":[{"key":"...","value":1}]},"unitId":"...",
             "scaleToSi":1.0,"aggregation":"INSTANTANEOUS","timeBasis":null}
            Interval aggregations PER_INTERVAL_RATE, SUM_OVER_INTERVALS, and
            AVERAGE_OVER_INTERVALS require a non-null timeBasis. INSTANTANEOUS and MAXIMUM require
            `timeBasis:null`. Preserve the exact value from the obligation.
        """.trimIndent()
    }

    private fun quantitativePromotionFixtureContract(envelope: ExecutionEnvelope): String {
        val fixtures = envelope.quantitativeToolObligation
            ?.exactPromotionFixtures
            .orEmpty()
        if (fixtures.isEmpty()) return ""
        return """
            OPERATOR-OWNED PROMOTION FIXTURE CONTRACT
            Emit one PROMOTION sealed test for every entry below. Copy each testId, input, and
            expectedOutput exactly; do not derive, paraphrase, replace, or add fixture values:
            ${json.encodeToString(fixtures)}
        """.trimIndent()
    }

    private fun contractFor(role: Role): String = when (role) {
        Role.CouncilTaskAnatomist -> taskAnatomistContract()
        Role.CouncilIncentiveDesigner -> incentiveDesignerContract()
        Role.CouncilEcologist -> ecologistContract()
        Role.CouncilPromptArchitect -> promptArchitectContract()
        Role.CouncilKGArchitect -> kgArchitectContract()
        Role.CouncilModelEcologist -> modelEcologistContract()
        Role.CouncilSeedCurator -> seedCuratorContract()
        Role.CouncilSynthesis -> synthesisContract()
        else -> error("Role $role is not a Council role")
    }

    private fun taskAnatomistContract(): String = """
        ROLE CONTRACT: TaskAnatomistOutput
        Build obligations -> limitations -> capabilities -> independently verifiable atomic nodes.
        Each node estimatedEffort must fit envelope.atomicNodeEffortBound. Represent ambiguity as
        explicit branches using only branchKey, interpretation, and affectedNodeKeys. Every local
        reference must resolve. Do not add fields that are absent from the exact JSON contract.
        Across all nodes, use no more than executionEnvelope.maximumRounds distinct stageKey
        values. A two-round envelope therefore requires at most two stage keys, regardless of how
        many nodes exist. Declare at least one executable or promotion test ID across the field;
        an empty test authority cannot activate a run. Every executable and promotion test ID is
        globally unique across nodes.
        The same ID may appear in executableTestIds and promotionTestIds only when both fields
        belong to the same tool-authoring node; a separate validation node needs distinct IDs.
        A toolOpportunity is non-null only on the node that authors the reusable tool or simulator.
        Validation, independent-execution, reuse, analysis, and final-deliverable nodes must set
        toolOpportunity to null; they consume or verify the authored tool instead of declaring a
        second opportunity. For every opportunity, add a separate validation node with
        kind=VALIDATION, the authoring node key in both parentKeys and prerequisiteKeys,
        proofContract.method=REPRODUCTION, and at least one executableTestId distinct from every
        authoring or promotion test ID. The authoring node must be in an earlier stage than every
        expectedReuseNodeIds target. Never target a node in the same stage or an earlier stage.
        Scheduling is round-indexed from zero. A node's earliest executable round is the maximum
        of its disclosure-stage index and one plus every prerequisite node's earliest round,
        because prerequisite grades become usable only after round-end judging. Every expected
        reuse also requires a later agent, so its earliest reuse round is at least one plus the
        authoring node's earliest round. Every authoring and reuse round must be strictly less than
        executionEnvelope.maximumRounds. In a two-round run, the authoring node must therefore be
        round-0 lease eligible with no grade-gated prerequisite, and reuse must execute in round 1.
        Use only requiredCapabilities present in executionEnvelope.sandboxCapabilities; prefer an
        empty list when the formula or simulation DSL needs no sandbox capability.
        Typed fields use a closed type-discriminated shape. Every branch includes name, type,
        description, required, and validationRules. STRING also includes enumValues and omits
        collection, object, and physical-dimension properties. INTEGER and NUMBER include
        physicalDimension. OBJECT requires a non-empty objectFields array containing recursively
        typed closed properties. ARRAY requires minimumItems, maximumItems, and one nested `items`
        discriminator object. Set equal minimumItems and maximumItems for a fixed-length array.
        `items.type` is one of STRING_ITEMS, INTEGER_ITEMS, NUMBER_ITEMS, BOOLEAN_ITEMS, or
        OBJECT_ITEMS. INTEGER_ITEMS and NUMBER_ITEMS also include
        physicalDimension, OBJECT_ITEMS requires non-empty objectFields, and every other item
        branch contains only type. BOOLEAN omits branch-specific properties. Opaque JSON and
        JSON_ITEMS are absent from the strict tool-opportunity schema: use OBJECT with exact
        objectFields instead. Never emit an omitted branch property as null or an empty array.
        Every toolOpportunity uses two nested closed discriminator objects. `implementation`
        contains type with one ToolImplementationMode value. Its FORMULA_DSL branch also requires
        formulaDslReferenceSemantics and formulaDslReferenceProgram; SHELL, PYTHON, RULE_DSL, and
        SIMULATION_DSL contain only type and must omit both formula properties rather than emitting
        null. `execution` contains type with one ToolOpportunityType value. Every
        non-SIMULATION execution branch also contains behavior. The SIMULATION branch instead
        contains simulationConfiguration, whose type discriminator is DETERMINISTIC or
        STOCHASTIC. DETERMINISTIC requires resultSummaryOutputField and uncertaintyOutputField and
        omits randomSeedInputField. STOCHASTIC requires all three fields. Do not emit the old
        top-level opportunityType, preferredImplementationMode, behavior, simulationRecordBindings,
        formulaDslReferenceSemantics, or formulaDslReferenceProgram properties.
        Every FORMULA_DSL opportunity is semantically sealed before Atlas construction. In its
        implementation object, set formulaDslReferenceSemantics to DIMENSIONAL_ANALYSIS_V1 and
        provide a Council-authored formulaDslReferenceProgram whose kind is FORMULA, whose output
        bindings exactly equal requiredOutputs, and whose expression tree type-checks against
        requiredInputs. Every
        NUMBER or INTEGER scalar and every ARRAY whose `items.type` is NUMBER_ITEMS or
        INTEGER_ITEMS in requiredInputs and requiredOutputs must provide physicalDimension.
        Numeric fields may also carry quantitySpec. Its closed properties are dimension, unitId,
        scaleToSi, aggregation, and nullable timeBasis; quantitySpec.dimension must exactly equal
        physicalDimension. On numeric ARRAY fields, quantitySpec belongs inside `items` alongside
        physicalDimension. Use
        {"exponents":[]} for an explicitly dimensionless value. The canonical base-dimension
        identifiers are length, mass, time, temperature, electric_current, amount,
        luminous_intensity, angle, information, currency, count, and power; exponent entries omit
        zero entries. An aggregate power-per-temperature coefficient is exactly
        {"exponents":[{"key":"power","value":1},{"key":"temperature","value":-1}]}.
        A per-area coefficient is exactly
        {"exponents":[{"key":"length","value":-2},{"key":"power","value":1},{"key":"temperature","value":-1}]}.
        Never multiply an aggregate
        power-per-temperature coefficient by area: that adds length:2 and is dimensionally wrong.
        Multiplication by area is valid only when the coefficient is explicitly per-area.
        The reference program seals semantics and promotion expectations; the worker tool-authoring
        model must still author the promoted formula implementation. Field descriptions,
        assumptions, the reference program, validationStrategy, and promotion tests must describe
        and test the same formula.
        A literal expression uses a tagged JSON value. For example, numeric one is
        {"type":"literal","value":{"type":"integer","value":1}}, null is
        {"type":"literal","value":{"type":"null"}}, and an object value carries sorted
        {"key":"...","value":{...}} entries under its nested `entries` array.
        For a SIMULATION execution, simulationConfiguration is mandatory and its field names must
        reference fields declared in requiredOutputs/requiredInputs. Every other execution branch
        omits simulationConfiguration. Use SIMULATION_DSL only for a closed flat-input simulation
        whose outputs are expressible with literals, direct inputs, arithmetic, conditionals, or
        numeric array aggregates. Use PYTHON for nested OBJECT input, per-record or hourly
        iteration, dynamically constructed ARRAY/OBJECT output, or computed narrative STRING
        output. Keep descriptions concise enough to return one syntactically complete JSON value.

        Enum choices:
        NodeKind=${NodeKind.entries.joinToString { it.name }}
        ProofMethod=${ProofMethod.entries.joinToString { it.name }}
        ArtifactType=${ArtifactType.entries.joinToString { it.name }}
        RevealMode=${RevealMode.entries.joinToString { it.name }}
        ReplicationMode=${ReplicationMode.entries.joinToString { it.name }}
        CaptureMode=${CaptureMode.entries.joinToString { it.name }}
        RewardClass=${RewardClass.entries.joinToString { it.name }}
        EvidenceKind=${EvidenceKind.entries.joinToString { it.name }}
        ToolOpportunityType=${ToolOpportunityType.entries.joinToString { it.name }}
        ExecutableTypedFieldType=${
            TypedFieldType.entries
                .filterNot { it == TypedFieldType.JSON }
                .joinToString { it.name }
        }
        ToolImplementationMode=${ToolImplementationMode.entries.joinToString { it.name }}
        ToolBehavior=${ToolBehavior.entries.joinToString { it.name }}
        FormulaDslReferenceSemantics=${
            FormulaDslReferenceSemantics.entries.joinToString { it.name }
        }

        {
          "receiverContract": {
            "receiverKey": "operator",
            "receiverDescription": "...",
            "situation": "...",
            "decisionOrUse": "...",
            "whyNeeded": "...",
            "successOutcomes": ["..."],
            "unacceptableOutcomes": ["..."],
            "constraints": ["..."],
            "evidenceProfile": {
              "minimumIndependentSources": 1,
              "acceptedEvidenceKinds": ["PRIMARY_SOURCE"],
              "requiredSourceDomains": [],
              "requirePrimarySources": true,
              "requireReproducibility": false,
              "maximumEvidenceAgeDays": null
            }
          },
          "receiverObligations": [
            {"key":"obligation-key","value":"observable obligation"}
          ],
          "nodes": [{
            "key": "semantic-node-key",
            "kind": "EVIDENCE",
            "layer": 0,
            "parentKeys": [],
            "prerequisiteKeys": [],
            "alternativeToKeys": [],
            "receiverObligationKey": "obligation-key",
            "limitationStatement": "...",
            "capabilityTarget": "...",
            "atomicQuestion": "...",
            "estimatedEffort": 1,
            "proofContract": {
              "method": "SOURCE_CORROBORATION",
              "acceptanceCriteria": ["..."],
              "requiredEvidenceKinds": ["PRIMARY_SOURCE"],
              "suggestedSourceDomains": [],
              "executableTestIds": [],
              "failureIsUseful": true
            },
            "deliverableContract": {
              "acceptedArtifactTypes": ["EVIDENCE_TABLE"],
              "requiredFields": [],
              "mediaTypes": [],
              "schemaJson": null,
              "finalDeliverableRelationship": "..."
            },
            "stageKey": "stage-zero",
            "revealMode": "INITIAL",
            "replicationPolicy": {"mode":"NONE","requiredCount":1,"blind":false},
            "capturePolicy": {
              "mode":"NONE","minimumGrade":0.0,"recaptureMargin":0.0,
              "maximumConsecutiveRounds":null
            },
            "rewardSpec": {
              "rewardClass":"NONE","credits":0.0,"capabilityYields":[],
              "firstCompletionBonus":0.0,"congestionLevel":0
            },
            "toolOpportunity": {
              "opportunityId":"calculator-opportunity",
              "repeatedQuestionPattern":"...",
              "requiredInputs":[{
                "name":"input_value","type":"NUMBER","description":"...","required":true,
                "validationRules":[],
                "physicalDimension":{"exponents":[]}
              }],
              "requiredOutputs":[{
                "name":"result","type":"NUMBER","description":"...","required":true,
                "validationRules":[],
                "physicalDimension":{"exponents":[]}
              }],
              "validationStrategy":"...",
              "expectedReuseNodeIds":["later-semantic-node-key"],
              "implementation":{
                "type":"FORMULA_DSL",
                "formulaDslReferenceSemantics":"DIMENSIONAL_ANALYSIS_V1",
                "formulaDslReferenceProgram":{
                  "kind":"FORMULA",
                  "outputs":[{
                    "field":"result",
                    "expression":{"type":"input","field":"input_value"}
                  }],
                  "randomSeedInputField":null,
                  "version":1
                }
              },
              "execution":{
                "type":"CALCULATION",
                "behavior":"DETERMINISTIC"
              },
              "requiredCapabilities":[],
              "promotionTestIds":["promotion-test-1"],
              "maximumRuntimeMillis":1000,
              "maximumOutputBytes":4096,
              "assumptions":["..."],
              "knownInvalidDomains":[]
            }
          }],
          "rootNodeKeys": ["semantic-node-key"],
          "atomicityReport": [{
            "nodeKey":"semantic-node-key","isAtomic":true,"estimatedEffort":1,"rationale":"..."
          }],
          "ambiguityBranches": [{
            "branchKey":"interpretation-a",
            "interpretation":"...",
            "affectedNodeKeys":["semantic-node-key"]
          }]
        }

        A SIMULATION opportunity uses the same object shape, but its final fields must look like:
        {
          "requiredInputs":[{
            "name":"scenario","type":"OBJECT","description":"Scenario inputs","required":true,
            "validationRules":[],
            "objectFields":[{
              "name":"outdoor_temperatures","type":"ARRAY",
              "description":"Exactly 72 hourly outdoor temperatures","required":true,
              "validationRules":[],"minimumItems":72,"maximumItems":72,
              "items":{
                "type":"NUMBER_ITEMS",
                "physicalDimension":{"exponents":[{"key":"temperature","value":1}]}
              }
            },{
              "name":"shelter_area","type":"NUMBER",
              "description":"Conditioned shelter floor area","required":true,
              "validationRules":[],
              "physicalDimension":{"exponents":[{"key":"length","value":2}]}
            }]
          }],
          "requiredOutputs":[{
            "name":"result_summary","type":"STRING","description":"Bounded result summary",
            "required":true,"enumValues":[],"validationRules":[]
          },{
            "name":"uncertainty","type":"STRING","description":"Explicit model uncertainty",
            "required":true,"enumValues":[],"validationRules":[]
          }],
          "implementation":{"type":"PYTHON"},
          "execution":{
            "type":"SIMULATION",
            "simulationConfiguration":{
              "type":"DETERMINISTIC",
              "resultSummaryOutputField":"result_summary",
              "uncertaintyOutputField":"uncertainty"
            }
          },
          "assumptions":["The bounded scenario contains all required model inputs."]
        }

        A dimensionally coherent aggregate heat-loss reference formula uses this exact shape:
        {
          "requiredInputs":[{
            "name":"coefficients","type":"ARRAY",
            "description":"Power-per-area-per-temperature coefficients","required":true,
            "validationRules":[],"minimumItems":1,"maximumItems":10000,
            "items":{
              "type":"NUMBER_ITEMS",
              "physicalDimension":{
                "exponents":[
                  {"key":"length","value":-2},
                  {"key":"power","value":1},
                  {"key":"temperature","value":-1}
                ]
              }
            }
          },{
            "name":"area","type":"NUMBER","description":"Measured transfer area","required":true,
            "validationRules":[],
            "physicalDimension":{"exponents":[{"key":"length","value":2}]}
          }],
          "requiredOutputs":[{
            "name":"heat_loss_coefficient","type":"NUMBER",
            "description":"Aggregate power-per-temperature coefficient","required":true,
            "validationRules":[],
            "physicalDimension":{"exponents":[
              {"key":"power","value":1},{"key":"temperature","value":-1}
            ]}
          }],
          "assumptions":[
            "Coefficient items are per-area values and area is measured in square length."
          ],
          "implementation":{
            "type":"FORMULA_DSL",
            "formulaDslReferenceSemantics":"DIMENSIONAL_ANALYSIS_V1",
            "formulaDslReferenceProgram":{
              "kind":"FORMULA",
              "outputs":[{
                "field":"heat_loss_coefficient",
                "expression":{
                  "type":"binary","operation":"MULTIPLY",
                  "left":{
                    "type":"aggregate","operation":"MEAN",
                    "values":{"type":"input","field":"coefficients"}
                  },
                  "right":{"type":"input","field":"area"}
                }
              }],
              "randomSeedInputField":null,
              "version":1
            }
          },
          "execution":{"type":"FORMULA","behavior":"DETERMINISTIC"},
          "promotionTestIds":["per-area-times-area-boundary-test"]
        }

        expectedReuseNodeIds contains exact semantic keys from this same nodes array, never hashes
        or invented IDs. Every target must exist, differ from the tool-authoring node, and declare a
        later stageKey. In a two-round run, put the authoring node in the first stage and every
        target in the second stage, keep the authoring node free of grade-gated prerequisites, and
        ensure each target's prerequisite chain remains executable in round 1.
        The compiler deterministically converts those semantic keys to Atlas IDs.
    """.trimIndent()

    private fun incentiveDesignerContract(): String = """
        ROLE CONTRACT: IncentiveDesignerOutput
        Use the Task Anatomist node keys from context. Provide exactly one policy per node. Total
        node credits and first-completion bonuses must stay within the envelope generation-credit
        and cumulative-cost bounds. maximumRewardCreditsPerRound must be at least the sum of every
        node policy's credits plus firstCompletionBonus, and no greater than
        (perGenerationCreditPolicy.maximumCredits - perGenerationCreditPolicy.baseCredits) times
        requiredWorkerGroupCount. Keep rewards instrumental and anti-concentrated.

        {
          "nodePolicies": [{
            "nodeKey": "semantic-node-key",
            "capturePolicy": {
              "mode":"GRADE_AUTHORITY","minimumGrade":0.7,"recaptureMargin":0.05,
              "maximumConsecutiveRounds":null
            },
            "rewardSpec": {
              "rewardClass":"SMALL","credits":2.0,"capabilityYields":[],
              "firstCompletionBonus":0.0,"congestionLevel":0
            }
          }],
          "actionCosts": [{"key":"WRITE_STAGING","value":1.0}],
          "scoringDimensions": [{
            "id":"evidence-quality","name":"Evidence quality","description":"...","weight":1.0,
            "maxScore":100.0
          }],
          "maximumRewardCreditsPerRound": 10.0,
          "maximumSharePerGroup": 0.6,
          "repeatOwnershipDiminishingFactor": 0.8,
          "mandatoryFrontierBonus": 0.15,
          "independentValidationCredit": 0.5,
          "graderRewardPolicy": "..."
        }
    """.trimIndent()

    private fun ecologistContract(): String = """
        ROLE CONTRACT: EcologistOutput
        Worker and grader counts, profiles, topology, and rounds must fit the envelope exactly.
        Group keys are semantic identities and topologyOrder contains each worker key once.

        {
          "workerGroups":[{"groupKey":"worker-a","profile":"Library","modelFamilyPreference":null}],
          "graderGroups":[{"groupKey":"grader-a","profile":"Monastery","modelFamilyPreference":null}],
          "topologyKind":"RING",
          "topologyOrder":["worker-a"],
          "checkinRounds":[1],
          "leakIntervalRounds":1,
          "dispatchLagRounds":1
        }
    """.trimIndent()

    private fun promptArchitectContract(): String = """
        ROLE CONTRACT: PromptArchitectOutput
        Define bounded role outputs and explicit information boundaries. Worker-visible fields may
        not include receiverContract, futureNodePayloads, hiddenTests, graderCriteria, or
        finalDeliverableRelationship. Outlier must receive a novelty mandate, not ordinary work.

        {
          "rolePrompts":[
            {"key":"Researcher","value":"..."},
            {"key":"Builder","value":"..."},
            {"key":"Outlier","value":"..."}
          ],
          "informationBoundaries":[{
            "role":"Researcher",
            "visibleFields":["currentStage","assignedNode"],
            "forbiddenFields":["receiverContract","futureNodePayloads","hiddenTests"]
          }],
          "outlierNoveltyMandate":"...",
          "deathCommitSchemaJson":"{}",
          "legacyProtocolTemplateJson":"{}"
        }
    """.trimIndent()

    private fun kgArchitectContract(): String = """
        ROLE CONTRACT: KgArchitectOutput
        Relation aliases must target declared canonical relation IDs. Every node mapping key must
        be a Task Anatomist node key, and every mapped type/relation ID must exist. When
        dependencyOutputs contains CouncilSeedCurator, every seed fact relation must be either an
        exact declared relation ID or a distinct alias key whose value is an exact declared
        relation ID. Preserve valid canonical relations and add the smallest compatible relation
        or alias; never emit a self-alias or remove authority used by another seed fact.

        {
          "schemaVersion":"node-atlas-v1",
          "entityTypes":[{"id":"Claim","requiredProperties":[]}],
          "relationTypes":[{
            "id":"SUPPORTS","sourceEntityTypeIds":["Claim"],"targetEntityTypeIds":["Claim"]
          }],
          "aliases":[{"key":"backs","value":"SUPPORTS"}],
          "claimLedgerSchemaJson":"{}",
          "nodeMappings":[
            {"key":"semantic-node-key","value":["Claim","SUPPORTS"]}
          ]
        }
    """.trimIndent()

    private fun modelEcologistContract(): String = """
        ROLE CONTRACT: ModelEcologistOutput
        Use only exact model IDs and capabilities listed in the envelope. Assign every active
        worker role plus GraderResearcher, GraderBuilder, and GraderCritic. Fallbacks must also be
        available and must not repeat the primary model. Never assign a Council* role: Council
        roles are transient compiler-front-end roles, not runtime roles. Every operator override
        named model.<Role> is mandatory and its primaryModelId must equal the exact override value.
        dependencyOutputs contains the exact Ecologist and PromptArchitect authorities: assign
        every prompted runtime role and ensure each declared worker/grader family preference has
        a matching assignment in that population.

        {
          "assignments":[{
            "role":"Researcher",
            "primaryModelId":"exact-envelope-model-id",
            "fallbackModelIds":[],
            "requiredCapabilities":["structured-output"]
          }],
          "diversityRationale":"...",
          "degradedModePolicy":"..."
        }
    """.trimIndent()

    private fun seedCuratorContract(): String = """
        ROLE CONTRACT: SeedCuratorOutput
        Keep Stage 0 minimal. Calibration artifacts and dead ends are grader/control material and
        must not reveal future-node payloads to workers. Artifact sha256 values commit exact
        content. dependencyOutputs contains the exact KgArchitect authority. Every
        primordialKgFacts relation must equal an exact declared canonical relation ID or exact
        distinct alias key from that authority; prefer the canonical relation ID and never invent
        a relation from natural-language semantics.

        {
          "primordialKb":[{"id":"seed-1","content":"...","domain":"general"}],
          "primordialKgFacts":[{
            "id":"fact-1","subject":"A","relation":"SUPPORTS","object":"B",
            "confidence":0.9,"domain":"general"
          }],
          "primordialTools":[],
          "calibrationArtifacts":[{
            "artifactKey":"calibration-1","mediaType":"application/json",
            "content":"{}","sha256":"lowercase-sha256"
          }],
          "knownDeadEnds":[],
          "stageZeroContext":["..."],
          "foundingNorms":"..."
        }
    """.trimIndent()

    private fun synthesisContract(): String = """
        ROLE CONTRACT: SynthesisOutput
        Refer only to typed local keys from prior role outputs. Do not emit StartingField, Atlas,
        runtime IDs, or copies of repaired upstream-role outputs. The strict SynthesisOutput schema
        still requires parentAtlasHash and each commitmentHash field. Use only supplied revision
        lineage for parentAtlasHash, with null for an initial field. commitmentHash is a
        compiler-owned required placeholder: preserve its prior value during repair or provide the
        required string during initial synthesis; the compiler recomputes the exact SHA-256 from
        testDefinitionJson before validation. Stage membership must match each node's declared
        stageKey: include every node exactly once in the stage with that exact key. The
        number of stages must not exceed executionEnvelope.maximumRounds. Hidden tests are committed before
        round 0. Every executableTestIds entry and every tool
        promotionTestIds entry from the Task Anatomist output must have exactly one sealedTests
        entry using its exact compiler-owned authorityKey from sealedTestAuthorities. The runtime
        restores testKey and nodeKey from that authority; do not emit or reclassify either field.
        Do not invent additional sealed tests or repeat an authorityKey.
        Every PROMOTION authority entry references one promotionSchemaKey in
        sealedTestAuthorities.promotionSchemas. That catalog contains the exact Task-authored
        executable inputSchema and outputSchema. The provider output schema binds each promotion
        authorityKey to that exact schema pair; it is not advisory repair text. A promotion key
        cannot select another opportunity's schema group.
        A PROMOTION authority can only use the PROMOTION branch, and a GENERAL authority can only
        use the GENERAL branch. Do not attach a validation node's authority to the tool-authoring
        node or vice versa. Emit exactly
        one or more acceptedArtifactTypes copied verbatim from that declaring node's
        deliverableContract.acceptedArtifactTypes; never substitute a plausible artifact type from
        another node. Emit exactly
        executionEnvelope.maximumRounds stages when the Task Anatomist uses that many stage keys,
        and never invent a stage for each node. sealedTests is unique by testKey. If the same test
        ID appears in both executableTestIds and promotionTestIds for one node, emit one sealed test,
        not one copy per reference.
        GENERAL testDefinitionJson is not an escaped JSON string on the strict provider wire. It is
        exactly one GeneralTestDefinitionV1 object with target and assertion fields. The only target
        branches are FINAL_OUTPUT_TEXT, OUTPUT_TEXT_FILE(relativePath), and
        OUTPUT_JSON_FILE(relativePath). The only assertion branches are
        TEXT_CONTAINS(expected, caseSensitive), TEXT_MATCHES_RE2(pattern),
        JSON_POINTER_EXISTS(pointer), JSON_POINTER_EQUALS_STRING(pointer, expected), and
        JSON_POINTER_NUMBER_RANGE(pointer, minimumInclusive, maximumInclusive). Use text
        assertions only with final-output text or text-file targets, and JSON Pointer assertions
        only with JSON-file targets. File paths must exactly match the normalized
        executionEnvelope.outputSpec authority. Patterns use RE2/J semantics. One sealed test
        contains one assertion; emit multiple tests rather than AND/OR trees. Null, scalar, array,
        arbitrary-map, unknown-target, unknown-assertion, missing-field, and extra-field GENERAL
        definitions fail strict decoding before compilation.
        For every PROMOTION authority in every implementation mode, testDefinitionJson is a closed
        provider object with exactly input and expectedOutput fields. Each field contains one
        tagged JSON value; there is no outer generic tagged-value discriminator:
        {"input":{"type":"object","entries":{"required_name":{"type":"string","value":"..."}}},
        "expectedOutput":{"type":"object","entries":{"required_ok":{"type":"boolean","value":true}}}}.
        Unlike GENERAL objects, each PROMOTION object uses an `entries` property map whose keys
        exactly equal the Task-authored schema properties; never emit an entry array there.
        Required properties contain their exact tagged value directly. Every optional property is
        still present in that fixed map and uses exactly one closed wrapper:
        {"state":"ABSENT"} when omitted, or
        {"state":"PRESENT","value":{tagged value}} when supplied. Apply the same fixed-map and
        wrapper rules recursively to nested promotion objects and object-valued array items.
        The input must satisfy every required input field
        and nested object/array contract, including exact collection bounds, and expectedOutput must
        satisfy every required output field and bound. Wrong tagged roots, duplicate object-entry
        keys, omitted required properties, additional properties, wrong primitive kinds, enum
        mismatches, and invalid array cardinalities fail strict decoding before compilation. For
        every implementation mode, encode each expectedOutput field declared as NUMBER with the
        exact finite JVM `Double.toString()` JSON token (for example 500.0, 2.5, 1.0E-7, or -0.0;
        never 500, 2.50, 1e-7, or 1.00E-7). INTEGER fields use integer tokens. This public
        schema-directed representation is mandatory because sealed authority stores NUMBER values
        as finite Doubles and promotion comparison performs no numeric coercion or private-fixture
        repair. For
        FORMULA_DSL, compute expectedOutput by
        executing the Council-authored formulaDslReferenceProgram under the committed production
        semantics. Preserve exact JSON numeric representation: an output emitted as 500.0 must be
        tagged as number 500.0, not integer 500; integer and number are distinct authority. The
        compiler executes every FORMULA_DSL case
        through the production materializer and sandbox after generic schema validation and
        selectively rejects this synthesis output on any exact expected/actual mismatch.
        Non-executable narrative definitions such as type/case labels do not satisfy any promotion
        test.
        During repair, fix every input and expectedOutput error reported for one testDefinitionJson
        in the same complete response. Cardinality diagnostics state actualItems and the exact
        missingItems or excessItems count. Change only the named invalid array by that exact count.
        If a diagnostic says input or expectedOutput already satisfies its sealed schema, copy that
        exact JSON value unchanged from previousOutput.

        {
          "atlasVersion":1,
          "parentAtlasHash":null,
          "stages":[{
            "stageKey":"stage-zero",
            "title":"Foundations",
            "nodeKeys":["semantic-node-key"],
            "mandatoryNodeKeys":["semantic-node-key"],
            "evidenceCoverageThreshold":0.7,
            "integrationReadinessThreshold":0.6
          }],
          "sealedTests":[{
            "type":"PROMOTION",
            "authorityKey":"exact-key-from-sealedTestAuthorities",
            "acceptedArtifactTypes":["EVIDENCE_TABLE"],
            "testDefinitionJson":{
              "input":{"type":"object","entries":{
                "required_name":{"type":"string","value":"example"},
                "optional_limit":{"state":"ABSENT"}
              }},
              "expectedOutput":{"type":"object","entries":{
                "required_ok":{"type":"boolean","value":true}
              }}
            },
            "commitmentHash":"compiler-recomputed-from-testDefinitionJson"
          }],
          "workerFieldNotes":[],
          "graderFieldNotes":[],
          "rubricSchemaVersion":"node-atlas-v1",
          "declaredChange":null
        }
    """.trimIndent()
}
