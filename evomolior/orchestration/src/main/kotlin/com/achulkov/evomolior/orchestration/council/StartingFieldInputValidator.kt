package com.achulkov.evomolior.orchestration.council

import com.achulkov.evomolior.core.atlas.AtlasValidationErrorCode
import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.FormulaDslReferenceSemantics
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.ProofMethod
import com.achulkov.evomolior.core.atlas.ToolDslValidator
import com.achulkov.evomolior.core.atlas.ToolImplementationMode
import com.achulkov.evomolior.core.atlas.ToolOpportunity
import com.achulkov.evomolior.core.atlas.ToolOpportunityType
import com.achulkov.evomolior.core.atlas.TypedField
import com.achulkov.evomolior.core.atlas.TypedFieldType
import com.achulkov.evomolior.core.council.ContractPath
import com.achulkov.evomolior.core.council.CompiledSealedTestAuthorityV3
import com.achulkov.evomolior.core.council.CouncilContractHashing
import com.achulkov.evomolior.core.council.CouncilDeterministicIds
import com.achulkov.evomolior.core.council.CouncilRoleOutputBundle
import com.achulkov.evomolior.core.council.ExecutionEnvelope
import com.achulkov.evomolior.core.council.FieldCompileError
import com.achulkov.evomolior.core.council.FieldCompileErrorCode
import com.achulkov.evomolior.core.council.FieldDegradationRecord
import com.achulkov.evomolior.core.council.LocalLimitationNode
import com.achulkov.evomolior.core.council.LocalNodeKey
import com.achulkov.evomolior.core.council.QuantitativeToolFieldLocation
import com.achulkov.evomolior.core.council.QuantitativeToolFieldObligation
import com.achulkov.evomolior.core.council.QuantitativeToolFieldShape
import com.achulkov.evomolior.core.council.QuantitativeToolObligation
import com.achulkov.evomolior.core.council.StartingFieldCompileResult
import com.achulkov.evomolior.core.council.SealedTestV3
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.model.Role
import kotlinx.serialization.json.Json

internal data class InputValidation(
    val errors: List<FieldCompileError>,
    val findings: List<com.achulkov.evomolior.core.council.ConsistencyFinding>,
    val degradationRecords: List<FieldDegradationRecord>
)

internal object StartingFieldInputValidator {
    val CHECKS = listOf(
        "objective-preservation", "envelope-fit", "node-references-and-dag",
        "stage-membership-and-disclosure", "node-atomicity", "policy-completeness-and-bounds",
        "reward-caps", "model-availability-and-capabilities", "typed-kg-aliases-and-mappings",
        "tool-opportunity-semantics", "acceptance-profile", "sealed-test-commitments", "schema-versions",
        "revision-lineage", "field-hashes-and-seal"
    )

    private val json = Json { ignoreUnknownKeys = false }

    fun validate(
        runId: String,
        rawObjective: String,
        envelope: ExecutionEnvelope,
        outputs: CouncilRoleOutputBundle,
        previousValid: StartingFieldCompileResult.Valid?
    ): InputValidation {
        val specialists = CouncilSpecialistBundle.from(outputs)
        val errors = buildList {
            if (runId.isBlank()) add(error(FieldCompileErrorCode.ENVELOPE_VIOLATION, "runId", "Run ID is blank", Role.CouncilSynthesis))
            CouncilSpecialistClosureValidator.validate(runId, envelope, specialists)
                .errors
                .forEach(::add)
            StartingFieldSupplementalValidator.validateEnvelopeAuthority(envelope).forEach(::add)
            validateEnvelopeAuthority(envelope).forEach(::add)
            validateScheduledToolReuse(runId, envelope, outputs).forEach(::add)
            validateStages(envelope, outputs).forEach(::add)
            validateSynthesisAcceptanceProfile(envelope, outputs).forEach(::add)
            validateTests(outputs).forEach(::add)
            validateQuantitativePromotionFixtures(envelope, outputs).forEach(::add)
            validateSynthesisJsonContracts(outputs).forEach(::add)
        }
        val revision = StartingFieldRevisionValidator.validate(
            runId = runId,
            rawObjective = rawObjective,
            envelope = envelope,
            outputs = outputs,
            previousValid = previousValid
        )
        return InputValidation(
            errors = errors + revision.first,
            findings = if (errors.isEmpty() && revision.first.isEmpty()) {
                listOf(
                    com.achulkov.evomolior.core.council.ConsistencyFinding(
                        code = "OBJECTIVE_EXACT",
                        severity = com.achulkov.evomolior.core.council.ConsistencySeverity.INFO,
                        path = path("receiverContract", "rawObjective"),
                        message = "The exact objective is preserved without normalization (${rawObjective.encodeToByteArray().size} UTF-8 bytes)."
                    )
                )
            } else {
                emptyList()
            },
            degradationRecords = revision.second
        )
    }

    internal fun validateSpecialistClosure(
        runId: String,
        envelope: ExecutionEnvelope,
        outputs: CouncilSpecialistBundle
    ): List<FieldCompileError> = buildList {
        StartingFieldSupplementalValidator.validateSpecialists(envelope, outputs).forEach(::add)
        validateSpecialistEnvelope(envelope, outputs).forEach(::add)
        validateSpecialistAcceptanceProfile(runId, envelope, outputs).forEach(::add)
        validateQuantitativeToolObligation(envelope, outputs).forEach(::add)
        validateSpecialistNodes(runId, envelope, outputs).forEach(::add)
        validatePolicies(envelope, outputs).forEach(::add)
        validateModels(envelope, outputs).forEach(::add)
        validateKg(outputs).forEach(::add)
        validateCrossNodeTestIds(outputs.taskAnatomist.nodes).forEach(::add)
        validateSpecialistJsonContracts(outputs).forEach(::add)
    }

    fun validateBuiltAtlas(
        atlas: NodeAtlas,
        outputs: CouncilRoleOutputBundle,
        previousValid: StartingFieldCompileResult.Valid?
    ): List<FieldCompileError> {
        val parent = previousValid?.atlas
        return atlas.validate(parent).errors.map { atlasError ->
            val code = when (atlasError.code) {
                AtlasValidationErrorCode.UNKNOWN_NODE_REFERENCE -> FieldCompileErrorCode.INVALID_NODE_REFERENCE
                AtlasValidationErrorCode.DEPENDENCY_CYCLE, AtlasValidationErrorCode.INVALID_ROOT ->
                    FieldCompileErrorCode.INVALID_DAG
                AtlasValidationErrorCode.INVALID_NODE_ATOMICITY -> FieldCompileErrorCode.NON_ATOMIC_NODE
                AtlasValidationErrorCode.TOOL_PURPOSE_OVERLAP ->
                    FieldCompileErrorCode.INVALID_NODE_REFERENCE
                AtlasValidationErrorCode.INVALID_STAGE,
                AtlasValidationErrorCode.INVALID_DISCLOSURE_PAYLOAD_HASH -> FieldCompileErrorCode.INVALID_STAGE
                AtlasValidationErrorCode.INVALID_PARENT_LINEAGE -> FieldCompileErrorCode.UNDECLARED_MATERIAL_CHANGE
                else -> FieldCompileErrorCode.HASH_MISMATCH
            }
            error(
                code = code,
                path = "atlas.${atlasError.nodeId?.value ?: "root"}",
                message = atlasError.message,
                roles = setOf(Role.CouncilTaskAnatomist, Role.CouncilSynthesis),
                keys = atlasError.nodeId?.let { id ->
                    outputs.taskAnatomist.nodes
                        .firstOrNull { CouncilDeterministicIds.nodeId(atlas.runId, it.key) == id.value }
                        ?.let { setOf(it.key) }
                }.orEmpty()
            )
        }
    }

    fun validateCompiledAcceptanceProfile(
        envelope: ExecutionEnvelope,
        sealedTests: CompiledSealedTestAuthorityV3
    ): List<FieldCompileError> = buildList {
        when (envelope.acceptanceProfile) {
            AcceptanceProfile.NORMAL -> Unit
            AcceptanceProfile.RUNTIME_USABILITY_SMOKE -> {
                if (sealedTests.privateSuite.tests.none { it is SealedTestV3.General }) {
                    add(
                        error(
                            FieldCompileErrorCode.ENVELOPE_VIOLATION,
                            "synthesis.sealedTests",
                            "Runtime usability smoke requires at least one compiled GENERAL test",
                            Role.CouncilSynthesis
                        )
                    )
                }
                if (sealedTests.privateSuite.tests.any { it is SealedTestV3.Promotion }) {
                    add(
                        error(
                            FieldCompileErrorCode.ENVELOPE_VIOLATION,
                            "synthesis.sealedTests",
                            "Runtime usability smoke forbids promotion-test authority",
                            Role.CouncilSynthesis
                        )
                    )
                }
            }
            AcceptanceProfile.APPENDIX_B_FULL -> {
                if (sealedTests.privateSuite.tests.none { it is SealedTestV3.Promotion }) {
                    add(
                        error(
                            FieldCompileErrorCode.ENVELOPE_VIOLATION,
                            "synthesis.sealedTests",
                            "Full Appendix B acceptance requires compiled promotion tests",
                            Role.CouncilSynthesis
                        )
                    )
                }
            }
        }
    }

    private fun validateSpecialistEnvelope(
        envelope: ExecutionEnvelope,
        outputs: CouncilSpecialistBundle
    ): List<FieldCompileError> = buildList {
        val ecology = outputs.ecologist
        if (ecology.workerGroups.size !in envelope.requiredWorkerGroupCount) {
            add(error(FieldCompileErrorCode.ENVELOPE_VIOLATION, "ecologist.workerGroups", "Worker group count is outside the envelope", Role.CouncilEcologist))
        }
        if (ecology.graderGroups.size !in envelope.requiredGraderGroupCount) {
            add(error(FieldCompileErrorCode.ENVELOPE_VIOLATION, "ecologist.graderGroups", "Grader group count is outside the envelope", Role.CouncilEcologist))
        }
        val allGroups = ecology.workerGroups + ecology.graderGroups
        duplicateValues(allGroups.map { it.groupKey }).forEach {
            add(error(FieldCompileErrorCode.ENVELOPE_VIOLATION, "ecologist.groups.$it", "Group key is duplicated", Role.CouncilEcologist))
        }
        allGroups.filter { it.profile !in envelope.permittedProfiles }.forEach {
            add(error(FieldCompileErrorCode.ENVELOPE_VIOLATION, "ecologist.groups.${it.groupKey}.profile", "Profile is not permitted by the envelope", Role.CouncilEcologist))
        }
        if (ecology.topologyKind !in envelope.allowedTopologyKinds) {
            add(error(FieldCompileErrorCode.ENVELOPE_VIOLATION, "ecologist.topologyKind", "Topology is not supported by the envelope", Role.CouncilEcologist))
        }
        val workerKeys = ecology.workerGroups.map { it.groupKey }
        if (ecology.topologyOrder.size != workerKeys.size ||
            ecology.topologyOrder.toSet() != workerKeys.toSet()
        ) {
            add(error(FieldCompileErrorCode.ENVELOPE_VIOLATION, "ecologist.topologyOrder", "Topology order must enumerate every worker group exactly once", Role.CouncilEcologist))
        }
        if (ecology.checkinRounds.isEmpty() || ecology.checkinRounds != ecology.checkinRounds.distinct().sorted() ||
            ecology.checkinRounds.any { it !in 1..envelope.maxRounds }
        ) {
            add(error(FieldCompileErrorCode.ENVELOPE_VIOLATION, "ecologist.checkinRounds", "Check-in rounds must be unique, ordered, and within the envelope", Role.CouncilEcologist))
        }
        if (ecology.leakIntervalRounds !in 1..envelope.maxRounds || ecology.dispatchLagRounds !in 0..envelope.maxRounds) {
            add(error(FieldCompileErrorCode.ENVELOPE_VIOLATION, "ecologist.roundPolicy", "Leak and dispatch rounds exceed the envelope", Role.CouncilEcologist))
        }
    }

    private fun validateSpecialistAcceptanceProfile(
        runId: String,
        envelope: ExecutionEnvelope,
        outputs: CouncilSpecialistBundle
    ): List<FieldCompileError> = buildList {
        val nodes = outputs.taskAnatomist.nodes
        val stageCount = nodes.mapTo(linkedSetOf()) { it.stageKey }.size
        val opportunities = nodes.filter { it.toolOpportunity != null }
        val declaredGeneralTestCount = nodes.sumOf { node ->
            node.proofContract.executableTestIds.count { testId ->
                testId !in node.toolOpportunity?.promotionTestIds.orEmpty()
            }
        }
        val declaredTestCount = nodes.sumOf { node ->
            (
                node.proofContract.executableTestIds +
                    node.toolOpportunity?.promotionTestIds.orEmpty()
                ).size
        }
        if (
            envelope.acceptanceProfile != AcceptanceProfile.NORMAL &&
            (
                envelope.requiredWorkerGroupCount.minimum < 2 ||
                    envelope.requiredGraderGroupCount.minimum < 2
                )
        ) {
            add(
                error(
                    FieldCompileErrorCode.ENVELOPE_VIOLATION,
                    "envelope.acceptanceProfile",
                    "Acceptance profiles require at least two worker groups and two grader groups",
                    Role.CouncilEcologist
                )
            )
        }
        when (envelope.acceptanceProfile) {
            AcceptanceProfile.NORMAL -> {
                if (declaredTestCount < 1) {
                    add(
                        error(
                            FieldCompileErrorCode.ENVELOPE_VIOLATION,
                            "taskAnatomist.nodes.proofContract.executableTestIds",
                            "Genesis Council requires at least one executable or promotion " +
                                "test declaration",
                            Role.CouncilTaskAnatomist
                        )
                    )
                }
            }
            AcceptanceProfile.RUNTIME_USABILITY_SMOKE -> {
                if (envelope.maxRounds != 1) {
                    add(
                        error(
                            FieldCompileErrorCode.ENVELOPE_VIOLATION,
                            "envelope.maxRounds",
                            "Runtime usability smoke requires exactly one round",
                            Role.CouncilTaskAnatomist
                        )
                    )
                }
                if (stageCount != 1) {
                    add(
                        error(
                            FieldCompileErrorCode.ENVELOPE_VIOLATION,
                            "taskAnatomist.nodes.stageKey",
                            "Runtime usability smoke requires exactly one disclosure stage",
                            Role.CouncilTaskAnatomist
                        )
                    )
                }
                if (opportunities.isNotEmpty()) {
                    add(
                        error(
                            FieldCompileErrorCode.ENVELOPE_VIOLATION,
                            "taskAnatomist.nodes.toolOpportunity",
                            "Runtime usability smoke forbids tool opportunities and expected reuse",
                            Role.CouncilTaskAnatomist,
                            opportunities.mapTo(linkedSetOf()) { it.key }
                        )
                    )
                }
                if (declaredGeneralTestCount < 1) {
                    add(
                        error(
                            FieldCompileErrorCode.ENVELOPE_VIOLATION,
                            "taskAnatomist.nodes.proofContract.executableTestIds",
                            "Runtime usability smoke requires at least one GENERAL test declaration",
                            Role.CouncilTaskAnatomist
                        )
                    )
                }
            }
            AcceptanceProfile.APPENDIX_B_FULL -> {
                if (envelope.maxRounds != APPENDIX_B_FULL_ROUNDS) {
                    add(
                        error(
                            FieldCompileErrorCode.ENVELOPE_VIOLATION,
                            "envelope.maxRounds",
                            "Full Appendix B acceptance requires exactly $APPENDIX_B_FULL_ROUNDS rounds",
                            Role.CouncilTaskAnatomist
                        )
                    )
                }
                if (stageCount < 2) {
                    add(
                        error(
                            FieldCompileErrorCode.ENVELOPE_VIOLATION,
                            "taskAnatomist.nodes.stageKey",
                            "Full Appendix B acceptance requires at least two disclosure stages",
                            Role.CouncilTaskAnatomist
                        )
                    )
                }
                if (opportunities.isEmpty()) {
                    add(
                        error(
                            FieldCompileErrorCode.ENVELOPE_VIOLATION,
                            "taskAnatomist.nodes.toolOpportunity",
                            "Full Appendix B acceptance requires at least one tool opportunity",
                            Role.CouncilTaskAnatomist
                        )
                    )
                }
                opportunities.forEach { origin ->
                    validateFullOpportunityTopology(runId, origin, nodes).forEach(::add)
                }
            }
        }
    }

    private fun validateQuantitativeToolObligation(
        envelope: ExecutionEnvelope,
        outputs: CouncilSpecialistBundle
    ): List<FieldCompileError> {
        val obligation = envelope.quantitativeToolObligation ?: return emptyList()
        val allOpportunityNodes = outputs.taskAnatomist.nodes.filter {
            it.toolOpportunity != null
        }
        val opportunityNodes = allOpportunityNodes.filter { node ->
            val opportunity = node.toolOpportunity ?: return@filter false
            obligation.allowedOpportunityTypes.isEmpty() ||
                opportunity.opportunityType in obligation.allowedOpportunityTypes
        }
        val assessed = opportunityNodes.map { node ->
            node to requireNotNull(node.toolOpportunity).quantitativeMismatches(obligation)
        }
        if (assessed.any { (_, mismatches) -> mismatches.isEmpty() }) {
            return emptyList()
        }
        val roleSummary = obligation.requiredFields.joinToString { field ->
            "${field.roleId}=${field.location.name.lowercase()}.${field.fieldName}"
        }
        if (assessed.isEmpty()) {
            val allowedTypes = obligation.allowedOpportunityTypes
                .takeIf(Set<ToolOpportunityType>::isNotEmpty)
                ?.sortedBy(ToolOpportunityType::name)
                ?.joinToString { it.name }
                ?: "any opportunity type"
            return listOf(
                error(
                    FieldCompileErrorCode.ENVELOPE_VIOLATION,
                    "taskAnatomist.nodes.toolOpportunity.quantitativeToolObligation",
                    "One sealed tool opportunity of an allowed type ($allowedTypes) must satisfy " +
                        "every exact operator-owned quantitative field role on the " +
                        "${obligation.horizonIntervalCount}-" +
                        "${obligation.horizonTimeBasis.name.lowercase()} bounded horizon: " +
                        roleSummary,
                    Role.CouncilTaskAnatomist,
                    allOpportunityNodes.mapTo(linkedSetOf()) { it.key }
                )
            )
        }
        val (bestNode, mismatches) = assessed.minWith(
            compareBy<Pair<LocalLimitationNode, List<QuantitativeFieldMismatch>>>(
                { it.second.size },
                { it.first.key.value }
            )
        )
        return buildList {
            add(error(
                FieldCompileErrorCode.ENVELOPE_VIOLATION,
                "taskAnatomist.nodes.toolOpportunity.quantitativeToolObligation",
                "One sealed tool opportunity must satisfy every exact operator-owned " +
                    "quantitative field role on the ${obligation.horizonIntervalCount}-" +
                    "${obligation.horizonTimeBasis.name.lowercase()} bounded horizon: " +
                    roleSummary,
                Role.CouncilTaskAnatomist,
                opportunityNodes.mapTo(linkedSetOf()) { it.key }
            ))
            mismatches.forEach { mismatch ->
                add(
                    error(
                        FieldCompileErrorCode.ENVELOPE_VIOLATION,
                        "taskAnatomist.nodes.${bestNode.key.value}.toolOpportunity." +
                            "quantitativeToolObligation.${mismatch.roleId}",
                        mismatch.message,
                        Role.CouncilTaskAnatomist,
                        setOf(bestNode.key)
                    )
                )
            }
        }
    }

    private fun ToolOpportunity.quantitativeMismatches(
        obligation: QuantitativeToolObligation
    ): List<QuantitativeFieldMismatch> = buildList {
        obligation.requiredOpportunityId?.let { requiredId ->
            if (opportunityId != requiredId) {
                add(
                    QuantitativeFieldMismatch(
                        roleId = "opportunity_id",
                        message = "Opportunity ID must be '$requiredId', actual='$opportunityId'"
                    )
                )
            }
        }
        if (
            obligation.exactPromotionTestIds.isNotEmpty() &&
            promotionTestIds != obligation.exactPromotionTestIds
        ) {
            add(
                QuantitativeFieldMismatch(
                    roleId = "promotion_test_ids",
                    message = "Promotion test IDs must equal " +
                        obligation.exactPromotionTestIds.sorted().joinToString(
                            prefix = "[",
                            postfix = "]"
                        ) +
                        ", actual=" +
                        promotionTestIds.sorted().joinToString(prefix = "[", postfix = "]")
                )
            )
        }
        obligation.maximumOpportunityRuntimeMillis?.let { maximumRuntime ->
            if (maximumRuntimeMillis > maximumRuntime) {
                add(
                    QuantitativeFieldMismatch(
                        roleId = "maximum_runtime_millis",
                        message = "maximumRuntimeMillis must be at most $maximumRuntime, " +
                            "actual=$maximumRuntimeMillis"
                    )
                )
            }
        }
        obligation.requiredFields.forEach { requiredField ->
            val fields = when (requiredField.location) {
                QuantitativeToolFieldLocation.INPUT -> requiredInputs
                QuantitativeToolFieldLocation.OUTPUT -> requiredOutputs
            }
            val field = fields.singleOrNull { it.name == requiredField.fieldName }
            if (field == null) {
                add(
                    QuantitativeFieldMismatch(
                        roleId = requiredField.roleId,
                        message = "Missing required top-level " +
                            "${requiredField.location.name.lowercase()} field " +
                            "'${requiredField.fieldName}' for quantitative role " +
                            "'${requiredField.roleId}'"
                    )
                )
                return@forEach
            }
            val differences = buildList {
                if (!field.required) add("required must be true")
                when (requiredField.shape) {
                    QuantitativeToolFieldShape.SCALAR -> {
                        if (field.type != requiredField.numericType) {
                            add(
                                "type must be ${requiredField.numericType}, actual=${field.type}"
                            )
                        }
                    }
                    QuantitativeToolFieldShape.HORIZON_SERIES -> {
                        if (field.type != TypedFieldType.ARRAY) {
                            add("type must be ARRAY, actual=${field.type}")
                        }
                        if (field.itemType != requiredField.numericType) {
                            add(
                                "array item type must be ${requiredField.numericType}, " +
                                    "actual=${field.itemType}"
                            )
                        }
                        if (
                            field.minimumItems != obligation.horizonIntervalCount ||
                            field.maximumItems != obligation.horizonIntervalCount
                        ) {
                            add(
                                "array bounds must both equal " +
                                    "${obligation.horizonIntervalCount}, actual=" +
                                    "${field.minimumItems}..${field.maximumItems}"
                            )
                        }
                    }
                }
                if (field.physicalDimension != requiredField.quantitySpec.dimension) {
                    add(
                        "physicalDimension must be " +
                            "${requiredField.quantitySpec.dimension}, actual=" +
                            "${field.physicalDimension}"
                    )
                }
                if (field.quantitySpec != requiredField.quantitySpec) {
                    add(
                        "quantitySpec must be ${requiredField.quantitySpec}, actual=" +
                            "${field.quantitySpec}"
                    )
                }
            }
            if (differences.isNotEmpty()) {
                add(
                    QuantitativeFieldMismatch(
                        roleId = requiredField.roleId,
                        message = "Quantitative role '${requiredField.roleId}' at " +
                            "${requiredField.location.name.lowercase()}." +
                            "${requiredField.fieldName} is incompatible: " +
                            differences.joinToString("; ")
                    )
                )
            }
        }
    }

    private data class QuantitativeFieldMismatch(
        val roleId: String,
        val message: String
    )

    private fun validateQuantitativePromotionFixtures(
        envelope: ExecutionEnvelope,
        outputs: CouncilRoleOutputBundle
    ): List<FieldCompileError> = buildList {
        val fixtures = envelope.quantitativeToolObligation
            ?.exactPromotionFixtures
            .orEmpty()
        if (fixtures.isEmpty()) return@buildList
        val testsById = outputs.synthesis.sealedTests.groupBy { it.testKey }
        fixtures.forEach { fixture ->
            val path = "synthesis.sealedTests.${fixture.testId}.testDefinitionJson"
            val test = testsById[fixture.testId]?.singleOrNull()
            if (test == null) {
                add(
                    error(
                        FieldCompileErrorCode.ENVELOPE_VIOLATION,
                        path,
                        "Operator-owned promotion fixture '${fixture.testId}' is missing or " +
                            "duplicated",
                        Role.CouncilSynthesis
                    )
                )
                return@forEach
            }
            val definition = runCatching {
                json.parseToJsonElement(test.testDefinitionJson) as? kotlinx.serialization.json.JsonObject
            }.getOrNull()
            val actualInput = definition?.get("input")
            val actualOutput = definition?.get("expectedOutput")
            val unexpectedFields = definition?.keys.orEmpty() - setOf("input", "expectedOutput")
            if (
                definition == null ||
                unexpectedFields.isNotEmpty() ||
                actualInput != fixture.input ||
                actualOutput != fixture.expectedOutput
            ) {
                add(
                    error(
                        FieldCompileErrorCode.ENVELOPE_VIOLATION,
                        path,
                        "Promotion fixture '${fixture.testId}' must exactly equal the " +
                            "operator-owned input and expectedOutput authority",
                        Role.CouncilSynthesis
                    )
                )
            }
        }
    }

    private fun validateFullOpportunityTopology(
        runId: String,
        origin: LocalLimitationNode,
        nodes: List<LocalLimitationNode>
    ): List<FieldCompileError> = buildList {
        val opportunity = requireNotNull(origin.toolOpportunity)
        val nodesByReuseReference = buildMap<NodeId, LocalLimitationNode> {
            nodes.forEach { node ->
                put(NodeId(node.key.value), node)
                put(NodeId(CouncilDeterministicIds.nodeId(runId, node.key)), node)
            }
        }
        val validationNodes = nodes.filter { candidate ->
            candidate.kind == NodeKind.VALIDATION &&
                origin.key in candidate.parentKeys &&
                origin.key in candidate.prerequisiteKeys &&
                candidate.proofContract.method == ProofMethod.REPRODUCTION &&
                candidate.proofContract.executableTestIds.isNotEmpty() &&
                candidate.toolOpportunity == null
        }
        val reuseNodes = opportunity.expectedReuseNodeIds.mapNotNull(nodesByReuseReference::get)
        val path = "taskAnatomist.nodes.${origin.key.value}.toolOpportunity"
        if (validationNodes.isEmpty()) {
            add(
                error(
                    FieldCompileErrorCode.ENVELOPE_VIOLATION,
                    "$path.validation",
                    "Full Appendix B acceptance requires a distinct validation node with " +
                        "kind=VALIDATION, the authoring node key in both parentKeys and " +
                        "prerequisiteKeys, proofContract.method=REPRODUCTION, at least one " +
                        "distinct executableTestId, and toolOpportunity=null",
                    Role.CouncilTaskAnatomist,
                    setOf(origin.key)
                )
            )
        }
        val validationKeys = validationNodes.mapTo(linkedSetOf()) { it.key }
        val reuseKeys = reuseNodes.mapTo(linkedSetOf()) { it.key }
        val overlap = validationKeys.intersect(reuseKeys)
        if (overlap.isNotEmpty()) {
            add(
                error(
                    FieldCompileErrorCode.INVALID_NODE_REFERENCE,
                    "$path.expectedReuseNodeIds",
                    "Full Appendix B validation and independent AGENT_USE nodes must be distinct",
                    Role.CouncilTaskAnatomist,
                    setOf(origin.key) + overlap
                )
            )
        }
        if (!hasDownstreamInfluencePath(reuseKeys, nodes)) {
            add(
                error(
                    FieldCompileErrorCode.ENVELOPE_VIOLATION,
                    "$path.expectedReuseNodeIds",
                    "Full Appendix B expected reuse must reach later evidence, integration, or final-output authority",
                    Role.CouncilTaskAnatomist,
                    setOf(origin.key) + reuseKeys
                )
            )
        }
    }

    private fun hasDownstreamInfluencePath(
        startingKeys: Set<LocalNodeKey>,
        nodes: List<LocalLimitationNode>
    ): Boolean {
        if (startingKeys.isEmpty()) return false
        val nodesByKey = nodes.associateBy { it.key }
        val dependents = buildMap<LocalNodeKey, MutableSet<LocalNodeKey>> {
            nodes.forEach { node ->
                (node.parentKeys + node.prerequisiteKeys).forEach { source ->
                    getOrPut(source, ::linkedSetOf).add(node.key)
                }
            }
        }
        val queue = ArrayDeque(startingKeys)
        val visited = mutableSetOf<LocalNodeKey>()
        while (queue.isNotEmpty()) {
            val key = queue.removeFirst()
            if (!visited.add(key)) continue
            val node = nodesByKey[key] ?: continue
            if (node.canInfluenceReceiverOutput()) return true
            dependents[key].orEmpty().sortedBy(LocalNodeKey::value).forEach(queue::addLast)
        }
        return false
    }

    private fun LocalLimitationNode.canInfluenceReceiverOutput(): Boolean =
        kind in setOf(NodeKind.EVIDENCE, NodeKind.INTEGRATION, NodeKind.DECISION, NodeKind.DELIVERY) ||
            deliverableContract.acceptedArtifactTypes.any {
                it in setOf(
                    ArtifactType.EVIDENCE_TABLE,
                    ArtifactType.DECISION_RECORD,
                    ArtifactType.DELIVERABLE_FRAGMENT,
                    ArtifactType.FINAL_DELIVERABLE
                )
            } ||
            deliverableContract.finalDeliverableRelationship.isNotBlank()

    private fun validateSynthesisAcceptanceProfile(
        envelope: ExecutionEnvelope,
        outputs: CouncilRoleOutputBundle
    ): List<FieldCompileError> = buildList {
        val stageCount = outputs.synthesis.stages.size
        when (envelope.acceptanceProfile) {
            AcceptanceProfile.NORMAL -> Unit
            AcceptanceProfile.RUNTIME_USABILITY_SMOKE -> if (stageCount != 1) {
                add(
                    error(
                        FieldCompileErrorCode.ENVELOPE_VIOLATION,
                        "synthesis.stages",
                        "Runtime usability smoke requires exactly one compiled disclosure stage",
                        Role.CouncilSynthesis
                    )
                )
            }
            AcceptanceProfile.APPENDIX_B_FULL -> if (stageCount < 2) {
                add(
                    error(
                        FieldCompileErrorCode.ENVELOPE_VIOLATION,
                        "synthesis.stages",
                        "Full Appendix B acceptance requires at least two compiled disclosure stages",
                        Role.CouncilSynthesis
                    )
                )
            }
        }
    }

    private fun validateEnvelopeAuthority(
        envelope: ExecutionEnvelope
    ): List<FieldCompileError> = buildList {
        if (envelope.maxAgentsPerGroupPerRound < 1 || envelope.maxRounds < 1 ||
            envelope.atomicNodeEffortBound < 1 ||
            envelope.perGenerationCreditPolicy.baseCredits < 0.0 ||
            envelope.perGenerationCreditPolicy.maximumCredits <
            envelope.perGenerationCreditPolicy.baseCredits ||
            envelope.perGenerationCreditPolicy.carryoverFraction !in 0.0..1.0 ||
            envelope.cumulativeCostCap?.let { it <= 0.0 } == true
        ) {
            add(error(FieldCompileErrorCode.ENVELOPE_VIOLATION, "envelope", "Envelope numeric bounds are internally invalid", Role.CouncilSynthesis))
        }
    }

    private fun validateSpecialistNodes(
        runId: String,
        envelope: ExecutionEnvelope,
        outputs: CouncilSpecialistBundle
    ): List<FieldCompileError> = buildList {
        val anatomy = outputs.taskAnatomist
        val keys = anatomy.nodes.map { it.key }
        duplicateValues(keys).forEach {
            add(error(FieldCompileErrorCode.INVALID_NODE_REFERENCE, "taskAnatomist.nodes.${it.value}", "Node key is duplicated", Role.CouncilTaskAnatomist, setOf(it)))
        }
        val keySet = keys.toSet()
        if (anatomy.nodes.isEmpty() || anatomy.rootNodeKeys.isEmpty() || !keySet.containsAll(anatomy.rootNodeKeys)) {
            add(error(FieldCompileErrorCode.INVALID_DAG, "taskAnatomist.rootNodeKeys", "At least one valid root is required", Role.CouncilTaskAnatomist))
        }
        val distinctStageKeys = anatomy.nodes.map { it.stageKey }.toSet()
        if (distinctStageKeys.any(String::isBlank)) {
            add(error(FieldCompileErrorCode.INVALID_STAGE, "taskAnatomist.nodes.stageKey", "Every node requires a non-blank disclosure stage key", Role.CouncilTaskAnatomist))
        }
        if (distinctStageKeys.size > envelope.maxRounds) {
            add(error(FieldCompileErrorCode.ENVELOPE_VIOLATION, "taskAnatomist.nodes.stageKey", "Distinct disclosure stage count exceeds maximum rounds", Role.CouncilTaskAnatomist))
        }
        val nodesByReuseReference = buildMap<NodeId, LocalLimitationNode> {
            anatomy.nodes.forEach { node ->
                put(NodeId(node.key.value), node)
                put(NodeId(CouncilDeterministicIds.nodeId(runId, node.key)), node)
            }
        }
        anatomy.nodes.forEach { node ->
            val references = node.parentKeys + node.prerequisiteKeys + node.alternativeToKeys
            val unknown = references - keySet
            if (unknown.isNotEmpty()) {
                add(error(FieldCompileErrorCode.INVALID_NODE_REFERENCE, "taskAnatomist.nodes.${node.key.value}", "Node references unknown local keys", Role.CouncilTaskAnatomist, unknown + node.key))
            }
            if (node.key in references || (node.key in anatomy.rootNodeKeys && node.parentKeys.isNotEmpty())) {
                add(error(FieldCompileErrorCode.INVALID_DAG, "taskAnatomist.nodes.${node.key.value}", "Node self-reference or root parent is invalid", Role.CouncilTaskAnatomist, setOf(node.key)))
            }
            if (node.receiverObligationKey !in anatomy.receiverObligations ||
                anatomy.receiverObligations[node.receiverObligationKey].isNullOrBlank()
            ) {
                add(error(FieldCompileErrorCode.INVALID_NODE_REFERENCE, "taskAnatomist.nodes.${node.key.value}.receiverObligationKey", "Receiver obligation is missing", Role.CouncilTaskAnatomist, setOf(node.key)))
            }
            if (node.estimatedEffort !in 1..envelope.atomicNodeEffortBound ||
                node.layer < 0 || node.limitationStatement.isBlank() || node.capabilityTarget.isBlank() ||
                node.atomicQuestion.isBlank()
            ) {
                add(error(FieldCompileErrorCode.NON_ATOMIC_NODE, "taskAnatomist.nodes.${node.key.value}", "Node exceeds the atomic effort bound or lacks explicit atomic content", Role.CouncilTaskAnatomist, setOf(node.key)))
            }
        }
        val assessments = anatomy.atomicityReport.associateBy { it.nodeKey }
        if (assessments.size != anatomy.atomicityReport.size || assessments.keys != keySet) {
            add(error(FieldCompileErrorCode.NON_ATOMIC_NODE, "taskAnatomist.atomicityReport", "Atomicity assessments must cover every node exactly once", Role.CouncilTaskAnatomist))
        }
        anatomy.nodes.forEach { node ->
            val assessment = assessments[node.key]
            if (assessment == null || !assessment.isAtomic || assessment.estimatedEffort != node.estimatedEffort ||
                assessment.estimatedEffort > envelope.atomicNodeEffortBound
            ) {
                add(error(FieldCompileErrorCode.NON_ATOMIC_NODE, "taskAnatomist.atomicityReport.${node.key.value}", "Atomicity assessment must affirm the exact bounded effort", Role.CouncilTaskAnatomist, setOf(node.key)))
            }
            val opportunity = node.toolOpportunity
            if (opportunity != null) {
                val path = "taskAnatomist.nodes.${node.key.value}.toolOpportunity"
                if (!envelope.sandboxCapabilities.capabilityIds.containsAll(
                        opportunity.requiredCapabilities
                    )
                ) {
                    add(error(FieldCompileErrorCode.ENVELOPE_VIOLATION, path, "Tool opportunity requires unavailable sandbox capabilities", Role.CouncilTaskAnatomist, setOf(node.key)))
                }
                val unknownReuseIds =
                    opportunity.expectedReuseNodeIds - nodesByReuseReference.keys
                if (unknownReuseIds.isNotEmpty()) {
                    add(error(FieldCompileErrorCode.INVALID_NODE_REFERENCE, "$path.expectedReuseNodeIds", "Tool opportunity references unknown downstream Atlas nodes", Role.CouncilTaskAnatomist, setOf(node.key)))
                }
                val sourceReferences = setOf(
                    NodeId(node.key.value),
                    NodeId(CouncilDeterministicIds.nodeId(runId, node.key))
                )
                if (opportunity.expectedReuseNodeIds.any(sourceReferences::contains)) {
                    add(error(FieldCompileErrorCode.INVALID_NODE_REFERENCE, "$path.expectedReuseNodeIds", "Tool opportunity cannot name its authoring node as downstream reuse", Role.CouncilTaskAnatomist, setOf(node.key)))
                }
                if (opportunity.opportunityType == ToolOpportunityType.SIMULATION &&
                    opportunity.preferredImplementationMode !in setOf(
                        ToolImplementationMode.SIMULATION_DSL,
                        ToolImplementationMode.PYTHON
                    )
                ) {
                    add(error(FieldCompileErrorCode.ENVELOPE_VIOLATION, "$path.preferredImplementationMode", "Simulation opportunities require the constrained simulation DSL or bounded Python", Role.CouncilTaskAnatomist, setOf(node.key)))
                }
                opportunity.requiredInputs.forEach { field ->
                    validateExecutableToolField(
                        path = "$path.requiredInputs.${field.name}",
                        field = field,
                        nodeKey = node.key
                    ).forEach(::add)
                }
                opportunity.requiredOutputs.forEach { field ->
                    validateExecutableToolField(
                        path = "$path.requiredOutputs.${field.name}",
                        field = field,
                        nodeKey = node.key
                    ).forEach(::add)
                }
                if (opportunity.preferredImplementationMode == ToolImplementationMode.FORMULA_DSL) {
                    validateFormulaDslSemantics(path, node.key, opportunity).forEach(::add)
                }
            }
        }
        if (hasCycle(anatomy.nodes.associate { it.key to (it.parentKeys + it.prerequisiteKeys) })) {
            add(error(FieldCompileErrorCode.INVALID_DAG, "taskAnatomist.nodes", "Parent/prerequisite graph contains a cycle", Role.CouncilTaskAnatomist))
        }
        val reachable = generateSequence(anatomy.rootNodeKeys) { current ->
            val next = anatomy.nodes.filter { node -> node.parentKeys.any(current::contains) }.mapTo(mutableSetOf()) { it.key }
            (current + next).takeIf { it.size > current.size }
        }.last()
        if (reachable != keySet) {
            add(error(FieldCompileErrorCode.INVALID_DAG, "taskAnatomist.rootNodeKeys", "Every node must be reachable from a declared root through parent edges", Role.CouncilTaskAnatomist, keySet - reachable))
        }
        anatomy.ambiguityBranches.forEach { branch ->
            if (branch.branchKey.isBlank() || branch.interpretation.isBlank() || branch.affectedNodeKeys.isEmpty() ||
                !keySet.containsAll(branch.affectedNodeKeys)
            ) {
                add(error(FieldCompileErrorCode.INVALID_NODE_REFERENCE, "taskAnatomist.ambiguityBranches.${branch.branchKey}", "Ambiguity branch is incomplete or references unknown nodes", Role.CouncilTaskAnatomist, branch.affectedNodeKeys))
            }
        }
    }

    private fun validateScheduledToolReuse(
        runId: String,
        envelope: ExecutionEnvelope,
        outputs: CouncilRoleOutputBundle
    ): List<FieldCompileError> = buildList {
        val anatomy = outputs.taskAnatomist
        val stageIndex = outputs.synthesis.stages
            .mapIndexed { index, stage -> stage.stageKey to index }
            .toMap()
        val earliestExecutableRounds = earliestExecutableRounds(
            prerequisitesByNode = anatomy.nodes.associate {
                it.key to it.prerequisiteKeys
            },
            stageRoundByNode = anatomy.nodes.associate {
                it.key to stageIndex[it.stageKey]
            }
        )
        val nodesByReuseReference = buildMap<NodeId, LocalLimitationNode> {
            anatomy.nodes.forEach { node ->
                put(NodeId(node.key.value), node)
                put(NodeId(CouncilDeterministicIds.nodeId(runId, node.key)), node)
            }
        }
        anatomy.nodes.forEach { node ->
            val opportunity = node.toolOpportunity ?: return@forEach
            val path = "taskAnatomist.nodes.${node.key.value}.toolOpportunity"
            val sourceStageIndex = stageIndex[node.stageKey] ?: return@forEach
            val nonLaterReuse = opportunity.expectedReuseNodeIds.filter { reuseId ->
                val target = nodesByReuseReference[reuseId] ?: return@filter false
                val targetStageIndex = stageIndex[target.stageKey] ?: return@filter false
                targetStageIndex <= sourceStageIndex
            }
            if (nonLaterReuse.isNotEmpty()) {
                add(error(FieldCompileErrorCode.FUTURE_STAGE_DISCLOSURE, "$path.expectedReuseNodeIds", "Expected reuse must target nodes in a later disclosure stage", Role.CouncilTaskAnatomist, setOf(node.key)))
            }
            val sourceEarliestRound = earliestExecutableRounds[node.key]
            if (sourceEarliestRound != null && sourceEarliestRound >= envelope.maxRounds) {
                add(
                    error(
                        FieldCompileErrorCode.ENVELOPE_VIOLATION,
                        "$path.schedule",
                        "Tool authoring node is first executable in round " +
                            "$sourceEarliestRound, outside the envelope's rounds " +
                            "0..${envelope.maxRounds - 1}",
                        Role.CouncilTaskAnatomist,
                        setOf(node.key)
                    )
                )
            }
            if (sourceEarliestRound != null) {
                opportunity.expectedReuseNodeIds.forEach { reuseId ->
                    val target = nodesByReuseReference[reuseId] ?: return@forEach
                    val targetEarliestRound =
                        earliestExecutableRounds[target.key] ?: return@forEach
                    val reuseEarliestRound = maxOf(
                        targetEarliestRound,
                        sourceEarliestRound + 1
                    )
                    if (reuseEarliestRound >= envelope.maxRounds) {
                        add(
                            error(
                                FieldCompileErrorCode.ENVELOPE_VIOLATION,
                                "$path.expectedReuseNodeIds",
                                "Expected later-agent reuse on ${target.key.value} is first " +
                                    "executable in round $reuseEarliestRound, outside the " +
                                    "envelope's rounds 0..${envelope.maxRounds - 1}",
                                Role.CouncilTaskAnatomist,
                                setOf(node.key, target.key)
                            )
                        )
                    }
                }
            }
        }
    }

    private fun validateExecutableToolField(
        path: String,
        field: TypedField,
        nodeKey: LocalNodeKey
    ): List<FieldCompileError> = buildList {
        if (
            field.type == TypedFieldType.JSON ||
            (field.type == TypedFieldType.ARRAY && field.itemType == TypedFieldType.JSON)
        ) {
            add(
                invalidToolSemantics(
                    path,
                    "Tool contracts require explicit typed structure; opaque JSON is not " +
                        "executable schema authority",
                    nodeKey
                )
            )
        }
        field.objectFields.forEach { nested ->
            validateExecutableToolField(
                path = "$path.objectFields.${nested.name}",
                field = nested,
                nodeKey = nodeKey
            ).forEach(::add)
        }
    }

    private fun validateFormulaDslSemantics(
        path: String,
        nodeKey: LocalNodeKey,
        opportunity: ToolOpportunity
    ): List<FieldCompileError> = buildList {
        val hasRequiredSemantics =
            opportunity.formulaDslReferenceSemantics ==
                FormulaDslReferenceSemantics.DIMENSIONAL_ANALYSIS_V1
        if (!hasRequiredSemantics) {
            add(
                invalidToolSemantics(
                    "$path.formulaDslReferenceSemantics",
                    "FORMULA_DSL requires DIMENSIONAL_ANALYSIS_V1 reference semantics",
                    nodeKey
                )
            )
        }
        val dimensionErrors = (
            opportunity.requiredInputs.flatMap { field ->
                validateFormulaNumericDimension(path, "requiredInputs", field, nodeKey)
            } +
                opportunity.requiredOutputs.flatMap { field ->
                    validateFormulaNumericDimension(path, "requiredOutputs", field, nodeKey)
                }
            )
        addAll(dimensionErrors)
        if (opportunity.formulaDslReferenceProgram == null) {
            add(
                invalidToolSemantics(
                    "$path.formulaDslReferenceProgram",
                    "FORMULA_DSL requires a Council-authored reference program",
                    nodeKey
                )
            )
        } else {
            ToolDslValidator.referenceProgramValidationError(opportunity)?.let { reason ->
                add(
                    invalidToolSemantics(
                        "$path.formulaDslReferenceProgram",
                        "FORMULA_DSL reference program is invalid: $reason",
                        nodeKey
                    )
                )
            }
        }
    }

    private fun validateFormulaNumericDimension(
        path: String,
        collection: String,
        field: TypedField,
        nodeKey: LocalNodeKey
    ): List<FieldCompileError> {
        if (
            field.type == TypedFieldType.OBJECT ||
            (field.type == TypedFieldType.ARRAY && field.itemType == TypedFieldType.OBJECT)
        ) {
            return listOf(
                invalidToolSemantics(
                    "$path.$collection.${field.name}.type",
                    "FORMULA_DSL accepts only flat primitive or primitive-array fields; " +
                        "structured object access is not part of the sealed DSL",
                    nodeKey
                )
            )
        }
        val isNumeric = field.type == TypedFieldType.INTEGER ||
            field.type == TypedFieldType.NUMBER ||
            (
                field.type == TypedFieldType.ARRAY &&
                    field.itemType in setOf(TypedFieldType.INTEGER, TypedFieldType.NUMBER)
                )
        return if (isNumeric && field.physicalDimension == null) {
            listOf(
                invalidToolSemantics(
                    "$path.$collection.${field.name}.physicalDimension",
                    "FORMULA_DSL numeric fields require an explicit physical dimension; " +
                        "use an empty exponent map for dimensionless values",
                    nodeKey
                )
            )
        } else {
            emptyList()
        }
    }

    private fun invalidToolSemantics(
        path: String,
        message: String,
        nodeKey: LocalNodeKey
    ): FieldCompileError = error(
        FieldCompileErrorCode.INVALID_TOOL_SEMANTICS,
        path,
        message,
        Role.CouncilTaskAnatomist,
        setOf(nodeKey)
    )

    private fun validateStages(
        envelope: ExecutionEnvelope,
        outputs: CouncilRoleOutputBundle
    ): List<FieldCompileError> = buildList {
        val stages = outputs.synthesis.stages
        val nodeKeys = outputs.taskAnatomist.nodes.mapTo(mutableSetOf()) { it.key }
        duplicateValues(stages.map { it.stageKey }).forEach {
            add(error(FieldCompileErrorCode.INVALID_STAGE, "synthesis.stages.$it", "Stage key is duplicated", Role.CouncilSynthesis))
        }
        val memberships = stages.flatMap { stage -> stage.nodeKeys.map { it to stage.stageKey } }.groupBy({ it.first }, { it.second })
        nodeKeys.forEach { key ->
            val node = outputs.taskAnatomist.nodes.first { it.key == key }
            if (memberships[key] != listOf(node.stageKey)) {
                add(error(FieldCompileErrorCode.INVALID_STAGE, "synthesis.stages.${node.stageKey}", "Node must occur exactly once in its declared stage", setOf(Role.CouncilTaskAnatomist, Role.CouncilSynthesis), setOf(key)))
            }
        }
        stages.forEach { stage ->
            if (stage.stageKey.isBlank() || stage.title.isBlank() || stage.nodeKeys.isEmpty() ||
                !nodeKeys.containsAll(stage.nodeKeys) ||
                !stage.nodeKeys.containsAll(stage.mandatoryNodeKeys) ||
                stage.evidenceCoverageThreshold !in 0.0..1.0 ||
                stage.integrationReadinessThreshold !in 0.0..1.0
            ) {
                add(error(FieldCompileErrorCode.INVALID_STAGE, "synthesis.stages.${stage.stageKey}", "Stage membership, title, or thresholds are invalid", Role.CouncilSynthesis, stage.nodeKeys))
            }
        }
        val index = stages.mapIndexed { i, stage -> stage.stageKey to i }.toMap()
        outputs.taskAnatomist.nodes.forEach { node ->
            val sourceIndex = index[node.stageKey] ?: return@forEach
            val future = (node.parentKeys + node.prerequisiteKeys + node.alternativeToKeys)
                .filter { ref ->
                    val target = outputs.taskAnatomist.nodes.firstOrNull { it.key == ref }
                    target != null && (index[target.stageKey] ?: Int.MAX_VALUE) > sourceIndex
                }.toSet()
            if (future.isNotEmpty()) {
                add(error(FieldCompileErrorCode.FUTURE_STAGE_DISCLOSURE, "taskAnatomist.nodes.${node.key.value}.references", "Node references material assigned to a future disclosure stage", setOf(Role.CouncilTaskAnatomist, Role.CouncilSynthesis), future + node.key))
            }
        }
        val initialStages = outputs.taskAnatomist.nodes.filter { it.revealMode == com.achulkov.evomolior.core.atlas.RevealMode.INITIAL }.map { index[it.stageKey] }
        if (initialStages.any { it != 0 }) {
            add(error(FieldCompileErrorCode.FUTURE_STAGE_DISCLOSURE, "taskAnatomist.nodes.revealMode", "INITIAL nodes may only belong to stage zero", Role.CouncilTaskAnatomist))
        }
        if (stages.size > envelope.maxRounds) {
            add(error(FieldCompileErrorCode.ENVELOPE_VIOLATION, "synthesis.stages", "Disclosure stage count exceeds maximum rounds", Role.CouncilSynthesis))
        }
    }

    private fun earliestExecutableRounds(
        prerequisitesByNode: Map<LocalNodeKey, Set<LocalNodeKey>>,
        stageRoundByNode: Map<LocalNodeKey, Int?>
    ): Map<LocalNodeKey, Int> {
        val memo = mutableMapOf<LocalNodeKey, Int>()
        val visiting = mutableSetOf<LocalNodeKey>()

        fun visit(nodeKey: LocalNodeKey): Int? {
            memo[nodeKey]?.let { return it }
            val stageRound = stageRoundByNode[nodeKey] ?: return null
            if (!visiting.add(nodeKey)) return null
            var earliestRound = stageRound
            for (prerequisite in prerequisitesByNode[nodeKey].orEmpty()) {
                val prerequisiteRound = visit(prerequisite)
                if (prerequisiteRound == null) {
                    visiting.remove(nodeKey)
                    return null
                }
                earliestRound = maxOf(earliestRound, prerequisiteRound + 1)
            }
            visiting.remove(nodeKey)
            memo[nodeKey] = earliestRound
            return earliestRound
        }

        prerequisitesByNode.keys
            .sortedBy(LocalNodeKey::value)
            .forEach(::visit)
        return memo
    }

    private fun validatePolicies(
        envelope: ExecutionEnvelope,
        outputs: CouncilSpecialistBundle
    ): List<FieldCompileError> = buildList {
        val nodes = outputs.taskAnatomist.nodes.associateBy { it.key }
        val policies = outputs.incentiveDesigner.nodePolicies
        val policyMap = policies.associateBy { it.nodeKey }
        if (policyMap.size != policies.size || policyMap.keys != nodes.keys) {
            add(error(FieldCompileErrorCode.INCOMPLETE_POLICY, "incentiveDesigner.nodePolicies", "Capture and reward policies must cover every node exactly once", Role.CouncilIncentiveDesigner))
        }
        nodes.forEach { (key, node) ->
            val policy = policyMap[key]
            if (policy != null && (
                    policy.capturePolicy.minimumGrade !in 0.0..1.0 ||
                        policy.capturePolicy.recaptureMargin !in 0.0..1.0 ||
                        policy.capturePolicy.nearTieMargin?.let { it !in 0.0..1.0 } == true ||
                        policy.capturePolicy.maximumConsecutiveRounds?.let { it < 1 } == true ||
                        !policy.rewardSpec.credits.isFinite() ||
                        policy.rewardSpec.credits < 0.0 ||
                        !policy.rewardSpec.firstCompletionBonus.isFinite() ||
                        policy.rewardSpec.firstCompletionBonus < 0.0 ||
                        policy.rewardSpec.congestionLevel < 0
                    )
            ) {
                add(error(FieldCompileErrorCode.INCOMPLETE_POLICY, "incentiveDesigner.nodePolicies.${key.value}", "Node capture and reward policy bounds are invalid", Role.CouncilIncentiveDesigner, setOf(key)))
            }
            if (
                node.replicationPolicy.requiredCount < 1 ||
                node.replicationPolicy.requiredCount > envelope.maxAgentsPerGroupPerRound
            ) {
                add(error(FieldCompileErrorCode.INCOMPLETE_POLICY, "taskAnatomist.nodes.${key.value}.replicationPolicy", "Node replication policy exceeds the available per-group agents", Role.CouncilTaskAnatomist, setOf(key)))
            }
        }
        val totalRewards = policies.sumOf { it.rewardSpec.credits + it.rewardSpec.firstCompletionBonus }
        val rewardHeadroom = (
            envelope.perGenerationCreditPolicy.maximumCredits -
                envelope.perGenerationCreditPolicy.baseCredits
            ) * outputs.ecologist.workerGroups.size
        if (!outputs.incentiveDesigner.maximumRewardCreditsPerRound.isFinite() ||
            outputs.incentiveDesigner.maximumRewardCreditsPerRound < 0.0 ||
            totalRewards > outputs.incentiveDesigner.maximumRewardCreditsPerRound ||
            outputs.incentiveDesigner.maximumRewardCreditsPerRound > rewardHeadroom
        ) {
            add(error(FieldCompileErrorCode.REWARD_CAP_EXCEEDED, "incentiveDesigner.maximumRewardCreditsPerRound", "Node rewards exceed the declared or envelope generation cap", Role.CouncilIncentiveDesigner))
        }
        val diminishing = outputs.incentiveDesigner.repeatOwnershipDiminishingFactor
        val frontierBonus = outputs.incentiveDesigner.mandatoryFrontierBonus
        val validationCredit = outputs.incentiveDesigner.independentValidationCredit
        if (outputs.incentiveDesigner.maximumSharePerGroup <= 0.0 ||
            outputs.incentiveDesigner.maximumSharePerGroup > 1.0 ||
            diminishing == null || !diminishing.isFinite() || diminishing <= 0.0 || diminishing > 1.0 ||
            frontierBonus == null || !frontierBonus.isFinite() || frontierBonus < 0.0 ||
            validationCredit == null || !validationCredit.isFinite() || validationCredit < 0.0 ||
            outputs.incentiveDesigner.graderRewardPolicy.isBlank() ||
            outputs.incentiveDesigner.actionCosts.any { it.key.isBlank() || !it.value.isFinite() || it.value < 0.0 } ||
            outputs.incentiveDesigner.scoringDimensions.isEmpty() ||
            duplicateValues(outputs.incentiveDesigner.scoringDimensions.map { it.id }).isNotEmpty()
        ) {
            add(error(FieldCompileErrorCode.INCOMPLETE_POLICY, "incentiveDesigner", "Economic and scoring policies are incomplete or invalid", Role.CouncilIncentiveDesigner))
        }
    }

    private fun validateModels(
        envelope: ExecutionEnvelope,
        outputs: CouncilSpecialistBundle
    ): List<FieldCompileError> = buildList {
        val cards = envelope.availableModels.associateBy { it.modelId }
        if (cards.size != envelope.availableModels.size) {
            add(error(FieldCompileErrorCode.ENVELOPE_VIOLATION, "envelope.availableModels", "Model IDs must be unique", Role.CouncilModelEcologist))
        }
        envelope.availableModels.forEach { card ->
            if (card.modelId.isBlank() || card.familyId.isBlank() ||
                card.structuredOutputReliability !in 0.0..1.0 ||
                card.toolCallReliability !in 0.0..1.0 ||
                card.codeBuildReliability !in 0.0..1.0 ||
                card.longContextSynthesisReliability !in 0.0..1.0 ||
                card.judgeCalibrationScore?.let { it !in 0.0..1.0 } == true ||
                card.observedCostPerThousandTokens?.let { it < 0.0 || !it.isFinite() } == true ||
                card.evidenceSampleCount < 0
            ) {
                add(error(FieldCompileErrorCode.ENVELOPE_VIOLATION, "envelope.availableModels.${card.modelId}", "Model capability card contains invalid IDs or metrics", Role.CouncilModelEcologist))
            }
        }
        duplicateValues(outputs.modelEcologist.assignments.map { it.role }).forEach {
            add(error(FieldCompileErrorCode.UNKNOWN_MODEL, "modelEcologist.assignments.$it", "Role assignment is duplicated", Role.CouncilModelEcologist))
        }
        outputs.modelEcologist.assignments.forEach { assignment ->
            if (
                envelope.acceptanceProfile != AcceptanceProfile.NORMAL &&
                assignment.fallbackModelIds.isNotEmpty()
            ) {
                add(
                    error(
                        FieldCompileErrorCode.ENVELOPE_VIOLATION,
                        "modelEcologist.assignments.${assignment.role}.fallbackModelIds",
                        "Acceptance model assignments require an empty fallback route set",
                        Role.CouncilModelEcologist
                    )
                )
            }
            if (assignment.primaryModelId.isBlank() ||
                assignment.fallbackModelIds.distinct().size != assignment.fallbackModelIds.size ||
                assignment.primaryModelId in assignment.fallbackModelIds
            ) {
                add(error(FieldCompileErrorCode.UNKNOWN_MODEL, "modelEcologist.assignments.${assignment.role}", "Model assignment IDs must be non-blank and non-duplicated", Role.CouncilModelEcologist))
            }
            (listOf(assignment.primaryModelId) + assignment.fallbackModelIds).forEach { modelId ->
                val card = cards[modelId]
                if (card == null) {
                    add(error(FieldCompileErrorCode.UNKNOWN_MODEL, "modelEcologist.assignments.${assignment.role}.$modelId", "Model is unavailable in the envelope", Role.CouncilModelEcologist))
                } else if (!card.capabilities.containsAll(assignment.requiredCapabilities)) {
                    add(error(FieldCompileErrorCode.UNKNOWN_MODEL, "modelEcologist.assignments.${assignment.role}.requiredCapabilities", "Assigned model lacks required capabilities", Role.CouncilModelEcologist))
                }
            }
            if (assignment.role.isCouncilRole) {
                add(error(FieldCompileErrorCode.UNKNOWN_MODEL, "modelEcologist.assignments.${assignment.role}", "Runtime assignment cannot target a transient Council role", Role.CouncilModelEcologist))
            }
        }
        envelope.operatorOverrides
            .filterKeys { it.startsWith(MODEL_OVERRIDE_PREFIX) }
            .forEach { (key, requiredModelId) ->
                val roleName = key.removePrefix(MODEL_OVERRIDE_PREFIX)
                val assignment = outputs.modelEcologist.assignments
                    .firstOrNull { it.role.name == roleName }
                if (assignment?.primaryModelId != requiredModelId) {
                    add(
                        error(
                            FieldCompileErrorCode.UNKNOWN_MODEL,
                            "modelEcologist.assignments.$roleName",
                            "Primary model must equal the operator-owned override",
                            Role.CouncilModelEcologist
                        )
                    )
                }
            }
        val assignedRoles = outputs.modelEcologist.assignments.mapTo(mutableSetOf()) { it.role }
        val promptedRoles = outputs.promptArchitect.rolePrompts.keys
        if (!assignedRoles.containsAll(promptedRoles)) {
            add(error(FieldCompileErrorCode.UNKNOWN_MODEL, "modelEcologist.assignments", "Every prompted runtime role requires a model assignment", setOf(Role.CouncilPromptArchitect, Role.CouncilModelEcologist)))
        }
        val boundaries = outputs.promptArchitect.informationBoundaries
        if (duplicateValues(boundaries.map { it.role }).isNotEmpty() ||
            boundaries.any { it.visibleFields.intersect(it.forbiddenFields).isNotEmpty() }
        ) {
            add(error(FieldCompileErrorCode.FUTURE_STAGE_DISCLOSURE, "promptArchitect.informationBoundaries", "Role information boundaries must be unique and may not expose forbidden fields", Role.CouncilPromptArchitect))
        }
        val workerFamilies = outputs.modelEcologist.assignments
            .filterNot { it.role.isGraderRole }
            .mapNotNullTo(mutableSetOf()) { cards[it.primaryModelId]?.familyId }
        val graderFamilies = outputs.modelEcologist.assignments
            .filter { it.role.isGraderRole }
            .mapNotNullTo(mutableSetOf()) { cards[it.primaryModelId]?.familyId }
        (outputs.ecologist.workerGroups + outputs.ecologist.graderGroups).forEach { group ->
            val family = group.modelFamilyPreference
            if (family != null && envelope.availableModels.none { it.familyId == family }) {
                add(error(FieldCompileErrorCode.UNKNOWN_MODEL, "ecologist.groups.${group.groupKey}.modelFamilyPreference", "Preferred model family is unavailable", setOf(Role.CouncilEcologist, Role.CouncilModelEcologist)))
            }
        }
        outputs.ecologist.workerGroups.filter {
            it.modelFamilyPreference != null && it.modelFamilyPreference !in workerFamilies
        }.forEach { group ->
            add(error(FieldCompileErrorCode.UNKNOWN_MODEL, "ecologist.groups.${group.groupKey}.modelFamilyPreference", "Worker group preference has no worker-role assignment in that family", setOf(Role.CouncilEcologist, Role.CouncilModelEcologist)))
        }
        outputs.ecologist.graderGroups.filter {
            it.modelFamilyPreference != null && it.modelFamilyPreference !in graderFamilies
        }.forEach { group ->
            add(error(FieldCompileErrorCode.UNKNOWN_MODEL, "ecologist.groups.${group.groupKey}.modelFamilyPreference", "Grader group preference has no grader-role assignment in that family", setOf(Role.CouncilEcologist, Role.CouncilModelEcologist)))
        }
    }

    private fun validateKg(outputs: CouncilSpecialistBundle): List<FieldCompileError> = buildList {
        val kg = outputs.kgArchitect
        val entityIds = kg.entityTypes.map { it.id }
        val relationIds = kg.relationTypes.map { it.id }
        if (kg.schemaVersion.isBlank() || duplicateValues(entityIds).isNotEmpty() ||
            duplicateValues(relationIds).isNotEmpty() || entityIds.any(String::isBlank) || relationIds.any(String::isBlank)
        ) {
            add(error(FieldCompileErrorCode.INVALID_KG_ALIAS, "kgArchitect", "KG type IDs and schema version must be unique and non-blank", Role.CouncilKGArchitect))
        }
        val entitySet = entityIds.toSet()
        kg.relationTypes.forEach { relation ->
            if (relation.sourceEntityTypeIds.isEmpty() || relation.targetEntityTypeIds.isEmpty() ||
                !entitySet.containsAll(relation.sourceEntityTypeIds + relation.targetEntityTypeIds)
            ) {
                add(error(FieldCompileErrorCode.INVALID_KG_ALIAS, "kgArchitect.relationTypes.${relation.id}", "Relation endpoint types are invalid", Role.CouncilKGArchitect))
            }
        }
        kg.aliases.forEach { (alias, canonical) ->
            if (alias.isBlank() || alias in relationIds || canonical !in relationIds || alias == canonical) {
                add(error(FieldCompileErrorCode.INVALID_KG_ALIAS, "kgArchitect.aliases.$alias", "Alias must be distinct and resolve to a canonical relation", Role.CouncilKGArchitect))
            }
        }
        val nodeKeys = outputs.taskAnatomist.nodes.mapTo(mutableSetOf()) { it.key }
        val knownTypes = entitySet + relationIds
        if (kg.nodeMappings.keys != nodeKeys || kg.nodeMappings.values.any { it.isEmpty() || !knownTypes.containsAll(it) }) {
            add(error(FieldCompileErrorCode.INVALID_KG_ALIAS, "kgArchitect.nodeMappings", "Every node requires a non-empty mapping to known KG types", setOf(Role.CouncilTaskAnatomist, Role.CouncilKGArchitect)))
        }
        val knownRelations = relationIds.toSet() + kg.aliases.keys
        outputs.seedCurator.primordialKgFacts.forEach { fact ->
            if (fact.relation !in knownRelations || fact.confidence !in 0.0..1.0) {
                add(error(FieldCompileErrorCode.INVALID_KG_ALIAS, "seedCurator.primordialKgFacts.${fact.id}", "Seed fact uses an unknown relation or invalid confidence", setOf(Role.CouncilKGArchitect, Role.CouncilSeedCurator)))
            }
        }
    }

    private fun validateCrossNodeTestIds(
        nodes: List<LocalLimitationNode>
    ): List<FieldCompileError> = crossNodeTestDeclarations(nodes).map { (testId, nodeKeys) ->
        val declaringNodes = nodeKeys
            .sortedBy(LocalNodeKey::value)
            .joinToString { it.value }
        error(
            FieldCompileErrorCode.MISSING_TEST_COMMITMENT,
            "taskAnatomist.nodes.executableTestIds.$testId",
            "Test ID '$testId' is declared by multiple nodes: $declaringNodes",
            Role.CouncilTaskAnatomist
        )
    }

    private fun crossNodeTestDeclarations(
        nodes: List<LocalLimitationNode>
    ): Map<String, Set<LocalNodeKey>> = buildMap<String, MutableSet<LocalNodeKey>> {
        nodes.forEach { node ->
            (
                node.proofContract.executableTestIds +
                    node.toolOpportunity?.promotionTestIds.orEmpty()
                ).forEach { testId ->
                getOrPut(testId, ::linkedSetOf).add(node.key)
            }
        }
    }.filterValues { it.size > 1 }

    private fun validateTests(outputs: CouncilRoleOutputBundle): List<FieldCompileError> = buildList {
        val tests = outputs.synthesis.sealedTests
        val nodes = outputs.taskAnatomist.nodes.associateBy { it.key }
        val crossNodeTestIds = crossNodeTestDeclarations(outputs.taskAnatomist.nodes).keys
        duplicateValues(tests.map { it.testKey }).forEach {
            add(error(FieldCompileErrorCode.MISSING_TEST_COMMITMENT, "synthesis.sealedTests.$it", "Test key is duplicated", Role.CouncilSynthesis))
        }
        tests.forEach { test ->
            val node = nodes[test.nodeKey]
            val invalidPredicates = buildList {
                if (node == null) add("unknown nodeKey '${test.nodeKey.value}'")
                if (test.testKey.isBlank()) add("blank testKey")
                if (test.acceptedArtifactTypes.isEmpty()) {
                    add("acceptedArtifactTypes is empty")
                } else if (
                    node != null &&
                    node.deliverableContract.acceptedArtifactTypes
                        .intersect(test.acceptedArtifactTypes)
                        .isEmpty()
                ) {
                    add(
                        "acceptedArtifactTypes=${test.acceptedArtifactTypes.sortedBy { it.name }} " +
                            "do not intersect node contract=" +
                            node.deliverableContract.acceptedArtifactTypes.sortedBy { it.name }
                    )
                }
                if (!isJson(test.testDefinitionJson)) add("testDefinitionJson is not valid JSON")
                if (test.commitmentHash != CouncilContractHashing.sha256(test.testDefinitionJson)) {
                    add("commitmentHash does not match exact testDefinitionJson")
                }
            }
            if (invalidPredicates.isNotEmpty()) {
                add(
                    error(
                        FieldCompileErrorCode.MISSING_TEST_COMMITMENT,
                        "synthesis.sealedTests.${test.testKey}",
                        "Sealed test is invalid: ${invalidPredicates.joinToString("; ")}",
                        Role.CouncilSynthesis,
                        setOf(test.nodeKey)
                    )
                )
            }
        }
        nodes.values.forEach { node ->
            node.proofContract.executableTestIds
                .filterNot(crossNodeTestIds::contains)
                .forEach { testId ->
                if (tests.none { it.testKey == testId && it.nodeKey == node.key }) {
                    add(error(FieldCompileErrorCode.MISSING_TEST_COMMITMENT, "taskAnatomist.nodes.${node.key.value}.proofContract.executableTestIds.$testId", "Executable test '$testId' lacks a matching precommitment for node '${node.key.value}'", Role.CouncilSynthesis, setOf(node.key)))
                }
            }
            node.toolOpportunity?.promotionTestIds.orEmpty()
                .filterNot(crossNodeTestIds::contains)
                .forEach { testId ->
                if (tests.none { it.testKey == testId && it.nodeKey == node.key }) {
                    add(error(FieldCompileErrorCode.MISSING_TEST_COMMITMENT, "taskAnatomist.nodes.${node.key.value}.toolOpportunity.promotionTestIds.$testId", "Tool promotion test '$testId' lacks a matching precommitment for node '${node.key.value}'", Role.CouncilSynthesis, setOf(node.key)))
                }
            }
            val opportunity = node.toolOpportunity
            if (opportunity != null) {
                val exactTests = tests.filter {
                    it.nodeKey == node.key &&
                        it.testKey in opportunity.promotionTestIds
                }
                if (
                    exactTests.size == opportunity.promotionTestIds.size &&
                    exactTests.mapTo(linkedSetOf()) { it.testKey } ==
                    opportunity.promotionTestIds
                ) {
                    val fixtureValidation = CouncilPromotionTestContractValidator.validate(
                        opportunity = opportunity,
                        tests = exactTests
                    )
                    fixtureValidation.failures.forEach { failure ->
                        add(
                            error(
                                FieldCompileErrorCode.INVALID_TOOL_SEMANTICS,
                                "synthesis.sealedTests.${failure.testId}.testDefinitionJson",
                                "Tool promotion test does not satisfy its exact sealed contract: " +
                                    failure.message,
                                Role.CouncilSynthesis,
                                setOf(node.key)
                            )
                        )
                    }
                    if (
                        fixtureValidation.failures.isEmpty() &&
                        opportunity.preferredImplementationMode ==
                            ToolImplementationMode.FORMULA_DSL &&
                        opportunity.formulaDslReferenceProgram != null &&
                        ToolDslValidator.referenceProgramValidationError(opportunity) == null
                    ) {
                        FormulaDslReferencePromotionValidator.validate(
                            opportunity = opportunity,
                            fixtures = fixtureValidation.fixtures
                        ).forEach { failure ->
                        add(
                            error(
                                FieldCompileErrorCode.INVALID_TOOL_SEMANTICS,
                                "synthesis.sealedTests.${failure.testId}.testDefinitionJson",
                                "FORMULA_DSL promotion test is not executable through the " +
                                    "production reference runtime: ${failure.message}",
                                Role.CouncilSynthesis,
                                setOf(node.key)
                            )
                        )
                    }
                    }
                }
            }
        }
    }

    private fun validateSpecialistJsonContracts(
        outputs: CouncilSpecialistBundle
    ): List<FieldCompileError> = buildList {
        listOf(
            "promptArchitect.deathCommitSchemaJson" to outputs.promptArchitect.deathCommitSchemaJson,
            "promptArchitect.legacyProtocolTemplateJson" to outputs.promptArchitect.legacyProtocolTemplateJson,
            "kgArchitect.claimLedgerSchemaJson" to outputs.kgArchitect.claimLedgerSchemaJson
        ).forEach { (contractPath, value) ->
            if (!isJson(value)) add(error(FieldCompileErrorCode.SCHEMA_VERSION_MISMATCH, contractPath, "Contract is not valid JSON", contractPath.substringBefore('.').toRole()))
        }
        val seeds = outputs.seedCurator
        if (duplicateValues(seeds.primordialKb.map { it.id }).isNotEmpty() ||
            duplicateValues(seeds.primordialKgFacts.map { it.id }).isNotEmpty() ||
            duplicateValues(seeds.primordialTools.map { it.id }).isNotEmpty() ||
            duplicateValues(seeds.calibrationArtifacts.map { it.artifactKey }).isNotEmpty()
        ) {
            add(error(FieldCompileErrorCode.SCHEMA_VERSION_MISMATCH, "seedCurator", "Seed and calibration IDs must be unique within each artifact class", Role.CouncilSeedCurator))
        }
        seeds.primordialTools.forEach { tool ->
            if (tool.id.isBlank() || tool.name.isBlank() || tool.code.isBlank() ||
                !isJson(tool.inputSchemaJson) || !isJson(tool.outputSchemaJson)
            ) {
                add(error(FieldCompileErrorCode.SCHEMA_VERSION_MISMATCH, "seedCurator.primordialTools.${tool.id}", "Seed tool and its typed schemas must be complete", Role.CouncilSeedCurator))
            }
        }
        seeds.calibrationArtifacts.forEach { artifact ->
            if (artifact.artifactKey.isBlank() || artifact.mediaType.isBlank() ||
                artifact.sha256 != CouncilContractHashing.sha256(artifact.content)
            ) {
                add(error(FieldCompileErrorCode.HASH_MISMATCH, "seedCurator.calibrationArtifacts.${artifact.artifactKey}", "Calibration artifact commitment does not match exact content", Role.CouncilSeedCurator))
            }
        }
    }

    private fun validateSynthesisJsonContracts(
        outputs: CouncilRoleOutputBundle
    ): List<FieldCompileError> = buildList {
        if (outputs.synthesis.rubricSchemaVersion.isBlank() ||
            outputs.kgArchitect.schemaVersion.isBlank() ||
            outputs.synthesis.rubricSchemaVersion != outputs.kgArchitect.schemaVersion
        ) {
            add(error(FieldCompileErrorCode.SCHEMA_VERSION_MISMATCH, "synthesis.rubricSchemaVersion", "KG, node, test, and rubric schema versions must be explicit and equal", setOf(Role.CouncilKGArchitect, Role.CouncilSynthesis)))
        }
    }

    private fun hasCycle(edges: Map<LocalNodeKey, Set<LocalNodeKey>>): Boolean {
        val visiting = mutableSetOf<LocalNodeKey>()
        val visited = mutableSetOf<LocalNodeKey>()
        fun visit(key: LocalNodeKey): Boolean {
            if (key in visiting) return true
            if (!visited.add(key)) return false
            visiting += key
            val cycle = edges[key].orEmpty().any(::visit)
            visiting -= key
            return cycle
        }
        return edges.keys.any(::visit)
    }

    private fun isJson(value: String): Boolean = runCatching { json.parseToJsonElement(value) }.isSuccess

    private fun String.toRole(): Role = when (this) {
        "promptArchitect" -> Role.CouncilPromptArchitect
        "kgArchitect" -> Role.CouncilKGArchitect
        else -> Role.CouncilSynthesis
    }

    private fun <T> duplicateValues(values: List<T>): Set<T> =
        values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys

    private fun path(vararg segments: String) = ContractPath(segments.toList())

    private fun error(
        code: FieldCompileErrorCode,
        path: String,
        message: String,
        role: Role,
        keys: Set<LocalNodeKey> = emptySet()
    ) = error(code, path, message, setOf(role), keys)

    private fun error(
        code: FieldCompileErrorCode,
        path: String,
        message: String,
        roles: Set<Role>,
        keys: Set<LocalNodeKey> = emptySet()
    ) = FieldCompileError(code, ContractPath(path.split('.')), message, roles, keys)

    private const val MODEL_OVERRIDE_PREFIX = "model."
    private const val APPENDIX_B_FULL_ROUNDS = 6
}
