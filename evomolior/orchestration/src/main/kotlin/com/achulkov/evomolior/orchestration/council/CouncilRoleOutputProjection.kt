package com.achulkov.evomolior.orchestration.council

import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.council.CouncilContractHashing
import com.achulkov.evomolior.core.council.CouncilDeterministicIds
import com.achulkov.evomolior.core.council.CouncilRoleOutputBundle
import com.achulkov.evomolior.core.council.ExecutionEnvelope
import com.achulkov.evomolior.core.council.RoleModelAssignment
import com.achulkov.evomolior.core.model.Role

/**
 * Projects Council output onto deterministic runtime authority before compilation.
 *
 * Council roles are transient and can never become runtime assignments. Missing assignments are
 * filled from the explicit preset, or otherwise only from a singleton executable model.
 * Explicit per-role operator overrides remain authoritative singleton pools under a preset.
 * Preset pools are validated against the envelope by the compiler, never silently widened or
 * substituted.
 * Cryptographic content commitments are compiler mechanics and are derived from the exact Council
 * content rather than relying on an LLM to calculate SHA-256 text.
 */
internal fun CouncilRoleOutputBundle.projectDeterministicCompilerAuthority(
    runId: String,
    envelope: ExecutionEnvelope
): CouncilRoleOutputBundle {
    val specialists = CouncilSpecialistBundle.from(this)
        .projectDeterministicCompilerAuthority(runId, envelope)
    return copy(
        taskAnatomist = specialists.taskAnatomist,
        modelEcologist = specialists.modelEcologist,
        seedCurator = specialists.seedCurator,
        synthesis = synthesis.copy(
            sealedTests = synthesis.sealedTests.map { test ->
                test.copy(
                    commitmentHash = CouncilContractHashing.sha256(test.testDefinitionJson)
                )
            }
        )
    )
}

/** Shares compiler-owned normalization with pre-synthesis specialist closure. */
internal fun CouncilSpecialistBundle.projectDeterministicCompilerAuthority(
    runId: String,
    envelope: ExecutionEnvelope
): CouncilSpecialistBundle {
    val presetPools = envelope.codexRuntimePreset?.runtimeModelPools().orEmpty()
    val singletonModelId = envelope.availableModels.singleOrNull()?.modelId
    val overridesByRole = envelope.operatorOverrides.mapNotNull { (key, modelId) ->
        val roleName = key.removePrefix("model.").takeIf { key.startsWith("model.") }
            ?: return@mapNotNull null
        val role = Role.entries.firstOrNull { it.name == roleName }
            ?.takeUnless(Role::isCouncilRole)
            ?: return@mapNotNull null
        role to modelId
    }.toMap()
    envelope.codexRuntimePreset?.let { preset ->
        require(overridesByRole.values.all { it in preset.supportedModelIds }) {
            "Operator model override exceeds preset authority"
        }
    }
    val originalByRole = modelEcologist.assignments
        .filterNot { it.role.isCouncilRole }
        .groupBy(RoleModelAssignment::role)
    val requiredRoles = promptArchitect.rolePrompts.keys
        .filterNotTo(linkedSetOf(), Role::isCouncilRole)
        .apply {
            addAll(overridesByRole.keys)
            addAll(Role.entries.filter { it.name in presetPools })
        }
    val projected = (originalByRole.keys + requiredRoles)
        .sortedBy(Role::name)
        .flatMap { role ->
            // Preserve duplicate runtime declarations for semantic validation, including every
            // declaration's capability requirements. Choosing one would erase contradictions.
            (originalByRole[role] ?: listOf(null)).mapNotNull { original ->
                val presetPool = presetPools[role.name]
                val primaryModelId = overridesByRole[role]
                    ?: presetPool?.first()
                    ?: original?.primaryModelId
                    ?: singletonModelId
                    ?: return@mapNotNull null
                RoleModelAssignment(
                    role = role,
                    primaryModelId = primaryModelId,
                    fallbackModelIds = (when {
                        presetPool != null && role in overridesByRole -> emptyList()
                        presetPool != null -> presetPool.drop(1)
                        else -> original?.fallbackModelIds.orEmpty()
                    })
                        .filter { it != primaryModelId }
                        .distinct(),
                    requiredCapabilities = original?.requiredCapabilities.orEmpty()
                )
            }
        }
    return copy(
        taskAnatomist = taskAnatomist.copy(
            nodes = taskAnatomist.nodes.map { node ->
                node.copy(
                    toolOpportunity = node.toolOpportunity?.let { opportunity ->
                        opportunity.copy(
                            expectedReuseNodeIds = opportunity.expectedReuseNodeIds.mapTo(
                                linkedSetOf()
                            ) { reference ->
                                val localKey = taskAnatomist.nodes
                                    .firstOrNull { it.key.value == reference.value }
                                    ?.key
                                localKey?.let {
                                    NodeId(CouncilDeterministicIds.nodeId(runId, it))
                                } ?: reference
                            }
                        )
                    }
                )
            }
        ),
        modelEcologist = modelEcologist.copy(assignments = projected),
        seedCurator = seedCurator.copy(
            calibrationArtifacts = seedCurator.calibrationArtifacts.map { artifact ->
                artifact.copy(sha256 = CouncilContractHashing.sha256(artifact.content))
            }
        )
    )
}
