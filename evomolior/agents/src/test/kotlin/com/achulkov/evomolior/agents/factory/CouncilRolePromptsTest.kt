package com.achulkov.evomolior.agents.factory

import com.achulkov.evomolior.core.atlas.PhysicalDimension
import com.achulkov.evomolior.core.atlas.ToolOpportunityType
import com.achulkov.evomolior.core.atlas.TypedFieldType
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.council.QuantitativeToolFieldLocation
import com.achulkov.evomolior.core.council.QuantitativeToolFieldObligation
import com.achulkov.evomolior.core.council.QuantitativeToolFieldShape
import com.achulkov.evomolior.core.council.QuantitativeToolObligation
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.repair.AggregationSemantics
import com.achulkov.evomolior.core.repair.QuantitySpec
import com.achulkov.evomolior.core.repair.TimeBasis
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that each Council prompt carries the runtime envelope and its typed role contract.
 */
class CouncilRolePromptsTest {

    @Test
    fun `task prompt renders exact operator quantitative tool contract`() {
        val obligation = QuantitativeToolObligation(
            horizonTimeBasis = TimeBasis.HOUR,
            horizonIntervalCount = 72,
            allowedOpportunityTypes = setOf(ToolOpportunityType.CALCULATION),
            requiredOpportunityId = "bounded_energy_audit",
            exactPromotionTestIds = setOf("runtime_boundary", "unit_boundary"),
            maximumOpportunityRuntimeMillis = 8_000,
            requiredFields = listOf(
                QuantitativeToolFieldObligation(
                    roleId = "shelter_area",
                    fieldName = "shelter_area_m2",
                    location = QuantitativeToolFieldLocation.INPUT,
                    shape = QuantitativeToolFieldShape.SCALAR,
                    quantitySpec = QuantitySpec(
                        dimension = PhysicalDimension(mapOf("length" to 2)),
                        unitId = "m2",
                        scaleToSi = 1.0,
                        aggregation = AggregationSemantics.INSTANTANEOUS
                    )
                ),
                QuantitativeToolFieldObligation(
                    roleId = "hourly_electrical_energy",
                    fieldName = "hourly_electrical_energy_kwh",
                    location = QuantitativeToolFieldLocation.OUTPUT,
                    shape = QuantitativeToolFieldShape.HORIZON_SERIES,
                    numericType = TypedFieldType.NUMBER,
                    quantitySpec = QuantitySpec(
                        dimension = PhysicalDimension(mapOf("energy" to 1)),
                        unitId = "kWh",
                        scaleToSi = 3_600_000.0,
                        aggregation = AggregationSemantics.SUM_OVER_INTERVALS,
                        timeBasis = TimeBasis.HOUR
                    )
                )
            )
        )
        val envelope = councilTestExecutionEnvelope().copy(
            maxRounds = 6,
            acceptanceProfile = AcceptanceProfile.APPENDIX_B_FULL,
            quantitativeToolObligation = obligation
        )

        val taskPrompt = CouncilRolePrompts.forRole(Role.CouncilTaskAnatomist, envelope)
        val synthesisPrompt = CouncilRolePrompts.forRole(Role.CouncilSynthesis, envelope)

        assertTrue(taskPrompt.contains("OPERATOR-OWNED QUANTITATIVE TOOL CONTRACT"))
        assertTrue(taskPrompt.contains("\"shelter_area_m2\""))
        assertTrue(taskPrompt.contains("\"hourly_electrical_energy_kwh\""))
        assertTrue(taskPrompt.contains("\"horizonIntervalCount\": 72"))
        assertTrue(taskPrompt.contains("\"requiredOpportunityId\": \"bounded_energy_audit\""))
        assertTrue(taskPrompt.contains("\"runtime_boundary\""))
        assertTrue(taskPrompt.contains("\"maximumOpportunityRuntimeMillis\": 8000"))
        assertTrue(taskPrompt.contains("complete promotionTestIds set"))
        assertTrue(taskPrompt.contains("inclusive ceiling"))
        assertTrue(taskPrompt.contains("\"unitId\": \"kWh\""))
        assertTrue(taskPrompt.contains("\"aggregation\": \"SUM_OVER_INTERVALS\""))
        assertTrue(taskPrompt.contains("\"timeBasis\": \"HOUR\""))
        assertTrue(taskPrompt.contains("INPUT means `requiredInputs`, OUTPUT means `requiredOutputs`"))
        assertTrue(taskPrompt.contains("one top-level field"))
        assertTrue(taskPrompt.contains("minimumItems and maximumItems"))
        assertTrue(taskPrompt.contains("inside `items`"))
        assertTrue(taskPrompt.contains("copy the complete"))
        assertTrue(taskPrompt.contains("`quantitySpec` exactly"))
        assertTrue(taskPrompt.contains("COPY-READY TASK ANATOMIST TOOL-OPPORTUNITY AUTHORITY"))
        assertTrue(taskPrompt.contains("\"name\": \"shelter_area_m2\""))
        assertTrue(taskPrompt.contains("\"type\": \"NUMBER\""))
        assertTrue(taskPrompt.contains("\"name\": \"hourly_electrical_energy_kwh\""))
        assertTrue(taskPrompt.contains("\"type\": \"NUMBER_ITEMS\""))
        assertTrue(taskPrompt.contains("\"minimumItems\": 72"))
        assertTrue(taskPrompt.contains("\"maximumItems\": 72"))
        assertTrue(taskPrompt.contains("\"timeBasis\": null"))
        assertTrue(taskPrompt.contains("never replace a numeric field"))
        assertTrue(taskPrompt.contains("delete every conflicting prior field object"))
        assertTrue(taskPrompt.contains("Never emit a `nodes`"))
        assertTrue(taskPrompt.contains("ordinal-bearing partition arrays"))
        assertFalse(synthesisPrompt.contains("OPERATOR-OWNED QUANTITATIVE TOOL CONTRACT"))
    }

    @Test
    fun `every Council role prompt contains the exact execution envelope`() {
        val envelope = councilTestExecutionEnvelope()

        Role.entries.filter(Role::isCouncilRole).forEach { role ->
            val prompt = CouncilRolePrompts.forRole(role, envelope)
            assertTrue(prompt.contains("\"requiredWorkerGroupCount\""), "$role is missing the envelope")
            assertTrue(prompt.contains("\"test/model\""), "$role is missing available model IDs")
            assertTrue(prompt.contains("semantic local"), "$role is missing deterministic-ID guidance")
        }
    }

    @Test
    fun `every Council repair requires a complete strict replacement`() {
        val envelope = councilTestExecutionEnvelope()

        Role.entries.filter(Role::isCouncilRole).forEach { role ->
            val prompt = CouncilRolePrompts.forRole(role, envelope)
            assertTrue(prompt.contains("one complete replacement object"), role.name)
            assertTrue(prompt.contains("preserve every unaffected field"), role.name)
            assertTrue(prompt.contains("relatedNodeContracts and generatedToolSchemas"), role.name)
            assertTrue(prompt.contains("quantitativeToolContractAuthority"), role.name)
            assertTrue(prompt.contains("immutable"), role.name)
            assertTrue(prompt.contains("invalidOutput is diagnostic only"), role.name)
            assertTrue(prompt.contains("one conjunctive repair set"), role.name)
            assertTrue(prompt.contains("never alternate between partial fixes"), role.name)
            assertTrue(prompt.contains("must never be a patch, partial object"), role.name)
        }
    }

    @Test
    fun `synthesis prompt requires typed stages tests and revision lineage`() {
        val prompt = CouncilRolePrompts.forRole(
            Role.CouncilSynthesis,
            councilTestExecutionEnvelope()
        )

        assertTrue(prompt.contains("SynthesisOutput"))
        assertTrue(prompt.contains("\"sealedTests\""))
        assertTrue(prompt.contains("\"commitmentHash\""))
        assertTrue(prompt.contains("\"parentAtlasHash\""))
        assertTrue(prompt.contains("\"declaredChange\""))
        assertTrue(prompt.contains("Do not emit StartingField"))
        assertTrue(prompt.contains("number of stages must not exceed"))
        assertTrue(prompt.contains("include every node exactly once"))
        assertTrue(prompt.contains("Every executableTestIds entry"))
        assertTrue(prompt.contains("compiler-owned authorityKey"))
        assertTrue(prompt.contains("promotionSchemaKey"))
        assertTrue(prompt.contains("sealedTestAuthorities.promotionSchemas"))
        assertTrue(prompt.contains("not advisory repair text"))
        assertTrue(prompt.contains("restores testKey and nodeKey"))
        assertTrue(prompt.contains("do not emit or reclassify"))
        assertTrue(prompt.contains("PROMOTION branch"))
        assertTrue(prompt.contains("GENERAL branch"))
        assertTrue(prompt.contains("copied verbatim from that declaring node's"))
        assertTrue(prompt.contains("never invent a stage for each node"))
        assertTrue(prompt.contains("sealedTests is unique by testKey"))
        assertTrue(prompt.contains("not one copy per reference"))
        assertTrue(prompt.contains("still requires parentAtlasHash and each commitmentHash field"))
        assertTrue(prompt.contains("compiler-owned required placeholder"))
        assertTrue(prompt.contains("compiler recomputes the exact SHA-256"))
        assertTrue(prompt.contains("actualItems"))
        assertTrue(prompt.contains("missingItems or excessItems"))
        assertTrue(prompt.contains("exact JSON value unchanged from previousOutput"))
        assertTrue(prompt.contains("not an escaped JSON string"))
        assertTrue(prompt.contains("GENERAL testDefinitionJson"))
        assertTrue(prompt.contains("exactly one GeneralTestDefinitionV1"))
        assertTrue(prompt.contains("FINAL_OUTPUT_TEXT"))
        assertTrue(prompt.contains("OUTPUT_TEXT_FILE(relativePath)"))
        assertTrue(prompt.contains("OUTPUT_JSON_FILE(relativePath)"))
        assertTrue(prompt.contains("TEXT_MATCHES_RE2(pattern)"))
        assertTrue(prompt.contains("JSON_POINTER_NUMBER_RANGE"))
        assertTrue(prompt.contains("emit multiple tests rather than AND/OR trees"))
        assertTrue(prompt.contains("arbitrary-map"))
        assertTrue(prompt.contains("extra-field GENERAL"))
        assertTrue(prompt.contains("there is no outer generic tagged-value discriminator"))
        assertTrue(prompt.contains("exactly input and expectedOutput fields"))
        assertTrue(prompt.contains("PROMOTION object uses an `entries` property map"))
        assertTrue(prompt.contains("never emit an entry array there"))
        assertTrue(prompt.contains("Required properties contain their exact tagged value directly"))
        assertTrue(prompt.contains("{\"state\":\"ABSENT\"}"))
        assertTrue(prompt.contains("{\"state\":\"PRESENT\",\"value\":{tagged value}}"))
        assertTrue(prompt.contains("recursively to nested promotion objects"))
        assertTrue(prompt.contains("Wrong tagged roots"))
        assertTrue(prompt.contains("fail strict decoding before compilation"))
        assertTrue(prompt.contains("integer and number are distinct authority"))
        assertTrue(!prompt.contains("Do not emit StartingField, Atlas,\nhashes"))
    }

    @Test
    fun `model prompt limits assignments to envelope models`() {
        val prompt = CouncilRolePrompts.forRole(
            Role.CouncilModelEcologist,
            councilTestExecutionEnvelope()
        )

        assertTrue(prompt.contains("\"primaryModelId\""))
        assertTrue(prompt.contains("\"fallbackModelIds\""))
        assertTrue(prompt.contains("Use only exact model IDs"))
        assertTrue(prompt.contains("test/model"))
        assertTrue(prompt.contains("Never assign a Council* role"))
        assertTrue(prompt.contains("model.<Role>"))
        assertTrue(prompt.contains("exact Ecologist and PromptArchitect authorities"))
        assertTrue(prompt.contains("every prompted runtime role"))
        assertTrue(prompt.contains("family preference"))
    }

    @Test
    fun `KG and seed prompts require exact directional relation authority`() {
        val envelope = councilTestExecutionEnvelope()
        val kgPrompt = CouncilRolePrompts.forRole(Role.CouncilKGArchitect, envelope)
        val seedPrompt = CouncilRolePrompts.forRole(Role.CouncilSeedCurator, envelope)

        assertTrue(kgPrompt.contains("dependencyOutputs as immutable cross-role authority"))
        assertTrue(kgPrompt.contains("dependencyOutputs contains CouncilSeedCurator"))
        assertTrue(kgPrompt.contains("exact declared relation ID"))
        assertTrue(kgPrompt.contains("never emit a self-alias"))
        assertTrue(seedPrompt.contains("dependencyOutputs as immutable cross-role authority"))
        assertTrue(seedPrompt.contains("dependencyOutputs contains the exact KgArchitect authority"))
        assertTrue(seedPrompt.contains("prefer the canonical relation ID"))
        assertTrue(seedPrompt.contains("never invent"))
    }

    @Test
    fun `task and incentive contracts expose exact cross-field constraints`() {
        val envelope = councilTestExecutionEnvelope()
        val taskPrompt = CouncilRolePrompts.forRole(Role.CouncilTaskAnatomist, envelope)
        val incentivePrompt = CouncilRolePrompts.forRole(Role.CouncilIncentiveDesigner, envelope)

        assertTrue(taskPrompt.contains("\"affectedNodeKeys\""))
        assertTrue(taskPrompt.contains("EvidenceKind="))
        assertTrue(taskPrompt.contains("maximumRounds distinct stageKey"))
        assertTrue(taskPrompt.contains("at least one executable or promotion test ID"))
        assertTrue(taskPrompt.contains("empty test authority cannot activate a run"))
        assertTrue(taskPrompt.contains("globally unique across nodes"))
        assertTrue(taskPrompt.contains("separate validation node needs distinct IDs"))
        assertTrue(taskPrompt.contains("toolOpportunity is non-null only on the node that authors"))
        assertTrue(taskPrompt.contains("kind=VALIDATION"))
        assertTrue(taskPrompt.contains("both parentKeys and prerequisiteKeys"))
        assertTrue(taskPrompt.contains("proofContract.method=REPRODUCTION"))
        assertTrue(taskPrompt.contains("Never target a node in the same stage or an earlier stage"))
        assertTrue(taskPrompt.contains("present in executionEnvelope.sandboxCapabilities"))
        assertTrue(taskPrompt.contains("using only branchKey, interpretation, and affectedNodeKeys"))
        assertTrue(taskPrompt.contains("Typed fields use a closed type-discriminated shape"))
        assertTrue(taskPrompt.contains("STRING also includes enumValues and omits"))
        assertTrue(taskPrompt.contains("ARRAY requires minimumItems, maximumItems"))
        assertTrue(taskPrompt.contains("Set equal minimumItems and maximumItems"))
        assertTrue(taskPrompt.contains("OBJECT requires a non-empty objectFields"))
        assertTrue(taskPrompt.contains("Opaque JSON and"))
        assertTrue(taskPrompt.contains("JSON_ITEMS are absent"))
        assertTrue(taskPrompt.contains("Never emit"))
        assertTrue(taskPrompt.contains("\"opportunityId\""))
        assertTrue(taskPrompt.contains("\"expectedReuseNodeIds\""))
        assertTrue(taskPrompt.contains("two nested closed discriminator objects"))
        assertTrue(taskPrompt.contains("contains type with one ToolImplementationMode value"))
        assertTrue(
            taskPrompt.contains(
                "`execution` contains type with one ToolOpportunityType value"
            )
        )
        assertTrue(taskPrompt.contains("\"simulationConfiguration\""))
        assertTrue(taskPrompt.contains("\"resultSummaryOutputField\":\"result_summary\""))
        assertTrue(taskPrompt.contains("\"uncertaintyOutputField\":\"uncertainty\""))
        assertTrue(taskPrompt.contains("\"randomSeedInputField\":null"))
        assertTrue(taskPrompt.contains("omits randomSeedInputField"))
        assertTrue(taskPrompt.contains("Every other execution branch"))
        assertTrue(taskPrompt.contains("omits simulationConfiguration"))
        assertTrue(taskPrompt.contains("must omit both formula"))
        assertTrue(taskPrompt.contains("Do not emit the old"))
        assertTrue(taskPrompt.contains("top-level opportunityType"))
        assertTrue(!taskPrompt.contains("\"simulationRecordBindings\":null"))
        assertTrue(!taskPrompt.contains("\"formulaDslReferenceSemantics\":null"))
        assertTrue(!taskPrompt.contains("\"formulaDslReferenceProgram\":null"))
        assertTrue(taskPrompt.contains("DIMENSIONAL_ANALYSIS_V1"))
        assertTrue(taskPrompt.contains("\"formulaDslReferenceProgram\""))
        assertTrue(taskPrompt.contains("\"physicalDimension\":{\"exponents\":[]}"))
        assertTrue(taskPrompt.contains("\"name\":\"coefficients\",\"type\":\"ARRAY\""))
        assertTrue(taskPrompt.contains("\"minimumItems\":1,\"maximumItems\":10000"))
        assertTrue(taskPrompt.contains("\"items\":{"))
        assertTrue(taskPrompt.contains("\"type\":\"NUMBER_ITEMS\""))
        assertTrue(!taskPrompt.contains("\"itemType\""))
        assertTrue(
            taskPrompt.contains(
                "{\"exponents\":[{\"key\":\"power\",\"value\":1}," +
                    "{\"key\":\"temperature\",\"value\":-1}]}"
            )
        )
        assertTrue(
            taskPrompt.contains(
                "{\"exponents\":[{\"key\":\"length\",\"value\":-2}," +
                    "{\"key\":\"power\",\"value\":1},{\"key\":\"temperature\",\"value\":-1}]}"
            )
        )
        assertTrue(
            taskPrompt.contains(
                "{\"type\":\"literal\",\"value\":{\"type\":\"integer\",\"value\":1}}"
            )
        )
        assertTrue(taskPrompt.contains("Never multiply an aggregate"))
        assertTrue(taskPrompt.contains("worker tool-authoring"))
        assertTrue(taskPrompt.contains("must still author the promoted formula implementation"))
        assertTrue(
            taskPrompt.contains(
                "Field descriptions,\nassumptions, the reference program, validationStrategy, " +
                    "and promotion tests must describe"
            )
        )
        assertTrue(taskPrompt.contains("\"type\":\"binary\",\"operation\":\"MULTIPLY\""))
        assertTrue(taskPrompt.contains("\"promotionTestIds\":[\"per-area-times-area-boundary-test\"]"))
        assertTrue(taskPrompt.contains("compiler deterministically converts those semantic keys"))
        assertTrue(incentivePrompt.contains("maximumRewardCreditsPerRound must be at least the sum"))
        assertTrue(incentivePrompt.contains("requiredWorkerGroupCount"))
    }
}
