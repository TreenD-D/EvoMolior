package com.achulkov.evomolior.orchestration.council

import com.achulkov.evomolior.core.atlas.LimitationNode
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.contract.StartingField
import com.achulkov.evomolior.core.council.ContractPath
import com.achulkov.evomolior.core.council.CouncilContractHashing
import com.achulkov.evomolior.core.council.CouncilDeterministicIds
import com.achulkov.evomolior.core.council.CouncilRoleOutputBundle
import com.achulkov.evomolior.core.council.ExecutionEnvelope
import com.achulkov.evomolior.core.council.FieldCompileError
import com.achulkov.evomolior.core.council.FieldCompileErrorCode
import com.achulkov.evomolior.core.council.FieldDegradationRecord
import com.achulkov.evomolior.core.council.MaterialFieldChangeKind
import com.achulkov.evomolior.core.council.StartingFieldCompileResult
import com.achulkov.evomolior.core.model.Role
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal object StartingFieldRevisionValidator {
    private val json = Json { ignoreUnknownKeys = false }

    fun validate(
        runId: String,
        rawObjective: String,
        envelope: ExecutionEnvelope,
        outputs: CouncilRoleOutputBundle,
        previousValid: StartingFieldCompileResult.Valid?
    ): Pair<List<FieldCompileError>, List<FieldDegradationRecord>> {
        val synthesis = outputs.synthesis
        if (previousValid == null) {
            val invalid = synthesis.atlasVersion != 1 ||
                synthesis.parentAtlasHash != null ||
                synthesis.declaredChange != null
            return if (invalid) {
                listOf(error("synthesis.atlasVersion", "Initial compilation must be version 1 without parent or change declaration")) to emptyList()
            } else {
                emptyList<FieldCompileError>() to emptyList()
            }
        }
        val errors = mutableListOf<FieldCompileError>()
        if (previousValid.atlas.runId != runId) {
            errors += error("runId", "Revision run ID must equal the previous Atlas run ID")
        }
        if (previousValid.workerField.objective != rawObjective ||
            previousValid.graderField.objective != rawObjective
        ) {
            errors += FieldCompileError(
                code = FieldCompileErrorCode.OBJECTIVE_CHANGED,
                path = ContractPath(listOf("receiverContract", "rawObjective")),
                message = "A revision cannot change any objective byte",
                responsibleCouncilRoles = setOf(Role.CouncilSynthesis)
            )
        }
        if (synthesis.atlasVersion != previousValid.atlas.version + 1 ||
            synthesis.parentAtlasHash != previousValid.atlas.atlasHash
        ) {
            errors += error("synthesis.parentAtlasHash", "Revision must name the exact parent hash and increment version exactly once")
        }
        val changes = materialChanges(runId, envelope, outputs, previousValid)
        val declaration = synthesis.declaredChange
        if (changes.isNotEmpty()) {
            val expectedId = declaration?.let {
                CouncilDeterministicIds.changeDeclarationId(it.parentAtlasHash, it.rationale)
            }
            if (declaration == null ||
                declaration.parentAtlasHash != previousValid.atlas.atlasHash ||
                declaration.changeKinds != changes ||
                declaration.declarationId != expectedId ||
                declaration.rationale.isBlank() ||
                declaration.requestedBy.isBlank()
            ) {
                errors += error("synthesis.declaredChange", "Every material revision requires an exact deterministic declaration")
            }
        } else if (declaration != null) {
            errors += error("synthesis.declaredChange", "A change declaration may not claim absent material changes")
        }
        val records = if (errors.isEmpty() && declaration != null) {
            listOf(
                FieldDegradationRecord(
                    recordId = CouncilDeterministicIds.create(
                        "field-degradation",
                        declaration.declarationId,
                        changes.map { it.name }.sorted().joinToString(",")
                    ),
                    declarationId = declaration.declarationId,
                    affectedCapabilities = changes.mapTo(sortedSetOf()) { it.name },
                    reason = declaration.rationale,
                    mitigations = listOf(outputs.modelEcologist.degradedModePolicy)
                        .filter(String::isNotBlank)
                )
            )
        } else {
            emptyList()
        }
        return errors to records
    }

    private fun materialChanges(
        runId: String,
        envelope: ExecutionEnvelope,
        outputs: CouncilRoleOutputBundle,
        previous: StartingFieldCompileResult.Valid
    ): Set<MaterialFieldChangeKind> = buildSet {
        val worker = previous.workerField
        if (outputs.ecologist.workerGroups.size != worker.groupCount ||
            outputs.ecologist.graderGroups.size != worker.graderGroupCount
        ) add(MaterialFieldChangeKind.GROUP_COUNT)
        val requestedTopology = outputs.ecologist.topologyOrder.map { groupKey ->
            CouncilDeterministicIds.groupId(runId, "worker", groupKey)
        }
        if (outputs.ecologist.topologyKind != worker.topologyKind ||
            requestedTopology != worker.ringTopology
        ) add(MaterialFieldChangeKind.TOPOLOGY)
        val families = envelope.availableModels.associate { it.modelId to it.familyId }
        val previousFamilies = (previous.workerField.modelAssignment + previous.graderField.modelAssignment)
        val previousPools = previous.workerField.modelPools + previous.graderField.modelPools
        val previousFamilyPools = previousFamilies.keys.associateWith { role ->
            previousPools[role].orEmpty().map { families[it] ?: "unknown:$it" }
        }
        val requestedFamilyPools = outputs.modelEcologist.assignments.associate {
            it.role.name to (listOf(it.primaryModelId) + it.fallbackModelIds)
                .map { modelId -> families[modelId] ?: "unknown:$modelId" }
        }
        if (requestedFamilyPools != previousFamilyPools) add(MaterialFieldChangeKind.MODEL_FAMILY)
        val stageCountChanged = outputs.synthesis.stages.size != previous.atlas.stageDefinitions.size
        if (stageCountChanged) {
            add(MaterialFieldChangeKind.STAGE_COUNT)
        }
        val oldLineage = previous.atlas.nodes.values.associate { node ->
            node.id.value to (
                node.parentIds + node.prerequisiteIds + node.alternativeToIds
                ).map { it.value }.sorted()
        }
        val newLineage = outputs.taskAnatomist.nodes.associate { node ->
            CouncilDeterministicIds.nodeId(runId, node.key) to
                (node.parentKeys + node.prerequisiteKeys + node.alternativeToKeys)
                    .map { CouncilDeterministicIds.nodeId(runId, it) }
                    .sorted()
        }
        val lineageChanged = oldLineage != newLineage ||
            previous.atlas.rootNodeIds.map { it.value }.toSet() !=
            outputs.taskAnatomist.rootNodeKeys
                .map { CouncilDeterministicIds.nodeId(runId, it) }
                .toSet()
        if (lineageChanged) add(MaterialFieldChangeKind.NODE_LINEAGE)

        val candidate = runCatching {
            val receiver = StartingFieldArtifacts.buildReceiverContract(
                rawObjective = previous.workerField.objective,
                envelope = envelope,
                outputs = outputs
            )
            val atlas = StartingFieldArtifacts.buildAtlas(runId, receiver, outputs)
            val sealedTests = when (
                val result = SealedTestSuiteV3Compiler.compile(
                    runId = runId,
                    envelope = envelope,
                    outputs = outputs,
                    atlas = atlas
                )
            ) {
                is SealedTestSuiteV3CompileResult.Failure ->
                    error("Revision sealed tests are not executable")
                is SealedTestSuiteV3CompileResult.Success -> result.authority
            }
            atlas to StartingFieldArtifacts.build(
                runId = runId,
                rawObjective = previous.workerField.objective,
                envelope = envelope,
                outputs = outputs,
                atlas = atlas,
                sealedTests = sealedTests,
                inputArtifacts = previous.workerField.inputArtifacts
            )
        }.getOrNull()
        if (candidate != null) {
            val (atlas, fields) = candidate
            if (atlasOtherChanged(
                    previous = previous.atlas,
                    current = atlas,
                    lineageChanged = lineageChanged,
                    stageCountChanged = stageCountChanged
                ) ||
                fieldOtherChanged(previous.workerField, fields.workerField, this) ||
                fieldOtherChanged(previous.graderField, fields.graderField, this)
            ) {
                add(MaterialFieldChangeKind.OTHER)
            }
        }
    }

    private fun atlasOtherChanged(
        previous: NodeAtlas,
        current: NodeAtlas,
        lineageChanged: Boolean,
        stageCountChanged: Boolean
    ): Boolean {
        if (previous.receiverContractHash != current.receiverContractHash) return true
        val commonNodeIds = previous.nodes.keys intersect current.nodes.keys
        if (commonNodeIds.any { nodeId ->
                comparableNode(previous.nodes.getValue(nodeId), stageCountChanged) !=
                    comparableNode(current.nodes.getValue(nodeId), stageCountChanged)
            }
        ) return true
        if (!lineageChanged && previous.nodes.keys != current.nodes.keys) return true
        if (!stageCountChanged) {
            val previousStages = previous.stageDefinitions.map {
                it.copy(revealPayloadHash = "")
            }
            val currentStages = current.stageDefinitions.map {
                it.copy(revealPayloadHash = "")
            }
            if (previousStages != currentStages) return true
        }
        return false
    }

    private fun comparableNode(node: LimitationNode, stageCountChanged: Boolean): LimitationNode =
        node.copy(
            parentIds = emptySet(),
            prerequisiteIds = emptySet(),
            alternativeToIds = emptySet(),
            revealPolicy = if (stageCountChanged) {
                node.revealPolicy.copy(
                    stageId = "",
                    mode = com.achulkov.evomolior.core.atlas.RevealMode.STAGE_PASS,
                    prerequisiteNodeIds = emptySet(),
                    minimumRound = 0
                )
            } else {
                node.revealPolicy.copy(prerequisiteNodeIds = emptySet())
            },
            payloadHash = ""
        )

    private fun fieldOtherChanged(
        previous: StartingField,
        current: StartingField,
        knownChanges: Set<MaterialFieldChangeKind>
    ): Boolean {
        val groupCountChanged = MaterialFieldChangeKind.GROUP_COUNT in knownChanges
        val topologyChanged = MaterialFieldChangeKind.TOPOLOGY in knownChanges
        if (!groupCountChanged && (
                previous.groupCount != current.groupCount ||
                    previous.graderGroupCount != current.graderGroupCount ||
                    previous.groupProfiles != current.groupProfiles
                )
        ) return true
        if (!topologyChanged && (
                previous.topologyKind != current.topologyKind ||
                    previous.ringTopology != current.ringTopology
                )
        ) return true
        if (!groupCountChanged && previous.globalBudgetCeiling != current.globalBudgetCeiling) return true
        return previous.generationCeiling != current.generationCeiling ||
            previous.checkinMilestones != current.checkinMilestones ||
            previous.leakIntervalGenerations != current.leakIntervalGenerations ||
            previous.dispatchLagGenerations != current.dispatchLagGenerations ||
            previous.rolePrompts != current.rolePrompts ||
            previous.kgSchema != current.kgSchema ||
            previous.actionCosts != current.actionCosts ||
            previous.scoreNormalization != current.scoreNormalization ||
            previous.primordialKb != current.primordialKb ||
            previous.primordialKgFacts != current.primordialKgFacts ||
            previous.primordialTools != current.primordialTools ||
            previous.deathNoteSchema != current.deathNoteSchema ||
            previous.legacyProtocolTemplate != current.legacyProtocolTemplate ||
            normalizeSuite(previous.sealedTestSuiteJson) != normalizeSuite(current.sealedTestSuiteJson) ||
            previous.norms != current.norms ||
            previous.consistencyReport != current.consistencyReport
    }

    private fun normalizeSuite(value: String): String = runCatching {
        val withoutAtlasDerivedCommitments = JsonObject(
            json.parseToJsonElement(value).jsonObject.filterKeys {
                it !in setOf("atlasHash", "suiteHash", "sourceSuiteHash", "viewHash")
            }
        )
        CouncilContractHashing.canonicalJson(withoutAtlasDerivedCommitments)
    }.getOrDefault(value)

    private fun error(path: String, message: String) = FieldCompileError(
        code = FieldCompileErrorCode.UNDECLARED_MATERIAL_CHANGE,
        path = ContractPath(path.split('.')),
        message = message,
        responsibleCouncilRoles = setOf(Role.CouncilSynthesis)
    )
}
