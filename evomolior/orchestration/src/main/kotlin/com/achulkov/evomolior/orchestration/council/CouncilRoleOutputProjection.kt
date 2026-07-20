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
 * filled only when the envelope exposes exactly one executable model, so the projection introduces
 * no model-selection discretion. Explicit per-role operator overrides remain authoritative.
 * Cryptographic content commitments are compiler mechanics and are derived from the exact Council
 * content rather than relying on an LLM to calculate SHA-256 text.
 */
internal fun CouncilRoleOutputBundle.projectDeterministicCompilerAuthority(
    runId: String,
    envelope: ExecutionEnvelope
): CouncilRoleOutputBundle {
    val singletonModelId = envelope.availableModels.singleOrNull()?.modelId
    val overridesByRole = envelope.operatorOverrides.mapNotNull { (key, modelId) ->
        val roleName = key.removePrefix("model.").takeIf { key.startsWith("model.") }
            ?: return@mapNotNull null
        val role = Role.entries.firstOrNull { it.name == roleName }
            ?.takeUnless(Role::isCouncilRole)
            ?: return@mapNotNull null
        role to modelId
    }.toMap()
    val originalByRole = modelEcologist.assignments
        .filterNot { it.role.isCouncilRole }
        .associateBy(RoleModelAssignment::role)
    val requiredRoles = promptArchitect.rolePrompts.keys
        .filterNotTo(linkedSetOf(), Role::isCouncilRole)
        .apply { addAll(overridesByRole.keys) }
    val projected = (originalByRole.keys + requiredRoles)
        .sortedBy(Role::name)
        .mapNotNull { role ->
            val original = originalByRole[role]
            val primaryModelId = overridesByRole[role]
                ?: original?.primaryModelId
                ?: singletonModelId
                ?: return@mapNotNull null
            RoleModelAssignment(
                role = role,
                primaryModelId = primaryModelId,
                fallbackModelIds = original?.fallbackModelIds
                    .orEmpty()
                    .filter { it != primaryModelId }
                    .distinct(),
                requiredCapabilities = original?.requiredCapabilities.orEmpty()
            )
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
        ),
        synthesis = synthesis.copy(
            sealedTests = synthesis.sealedTests.map { test ->
                test.copy(
                    commitmentHash = CouncilContractHashing.sha256(test.testDefinitionJson)
                )
            }
        )
    )
}
