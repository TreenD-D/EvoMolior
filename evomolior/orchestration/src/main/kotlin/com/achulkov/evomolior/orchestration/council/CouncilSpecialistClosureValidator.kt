package com.achulkov.evomolior.orchestration.council

import com.achulkov.evomolior.core.council.ContractPath
import com.achulkov.evomolior.core.council.CouncilContractHashing
import com.achulkov.evomolior.core.council.CouncilRoleOutputBundle
import com.achulkov.evomolior.core.council.EcologistOutput
import com.achulkov.evomolior.core.council.ExecutionEnvelope
import com.achulkov.evomolior.core.council.FieldCompileError
import com.achulkov.evomolior.core.council.FieldCompileErrorCode
import com.achulkov.evomolior.core.council.IncentiveDesignerOutput
import com.achulkov.evomolior.core.council.KgArchitectOutput
import com.achulkov.evomolior.core.council.ModelEcologistOutput
import com.achulkov.evomolior.core.council.PromptArchitectOutput
import com.achulkov.evomolior.core.council.SeedCuratorOutput
import com.achulkov.evomolior.core.council.TaskAnatomistOutput
import com.achulkov.evomolior.core.model.Role
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

internal data class CouncilSpecialistBundle(
    val taskAnatomist: TaskAnatomistOutput,
    val incentiveDesigner: IncentiveDesignerOutput,
    val ecologist: EcologistOutput,
    val promptArchitect: PromptArchitectOutput,
    val kgArchitect: KgArchitectOutput,
    val modelEcologist: ModelEcologistOutput,
    val seedCurator: SeedCuratorOutput
) {
    companion object {
        fun from(outputs: CouncilRoleOutputBundle): CouncilSpecialistBundle =
            CouncilSpecialistBundle(
                taskAnatomist = outputs.taskAnatomist,
                incentiveDesigner = outputs.incentiveDesigner,
                ecologist = outputs.ecologist,
                promptArchitect = outputs.promptArchitect,
                kgArchitect = outputs.kgArchitect,
                modelEcologist = outputs.modelEcologist,
                seedCurator = outputs.seedCurator
            )
    }
}

internal data class CouncilClosureReport(
    val errorsByResponsibleRole: Map<Role, List<FieldCompileError>>,
    val frozenValidRoleHashes: Map<Role, String>
) {
    val isClosed: Boolean
        get() = errorsByResponsibleRole.isEmpty()

    val errors: List<FieldCompileError>
        get() = errorsByResponsibleRole.values.flatten().distinct()

    fun errorsFor(role: Role): List<FieldCompileError> =
        errorsByResponsibleRole[role].orEmpty()
}

internal object CouncilSpecialistClosureValidator {
    fun validate(
        runId: String,
        envelope: ExecutionEnvelope,
        bundle: CouncilSpecialistBundle
    ): CouncilClosureReport {
        val errors = StartingFieldInputValidator.validateSpecialistClosure(
            runId = runId,
            envelope = envelope,
            outputs = bundle
        )
        val errorsByRole = specialistRoles.mapNotNull { role ->
            errors.filter { role in it.responsibleCouncilRoles }
                .takeIf(List<FieldCompileError>::isNotEmpty)
                ?.let { role to it }
        }.toMap(linkedMapOf())
        val invalidRoles = errorsByRole.keys
        return CouncilClosureReport(
            errorsByResponsibleRole = errorsByRole,
            frozenValidRoleHashes = roleHashes(
                bundle = bundle,
                roles = specialistRoles.filterNot(invalidRoles::contains)
            )
        )
    }

    fun verifyFrozenRoleHashes(
        bundle: CouncilSpecialistBundle,
        expectedHashes: Map<Role, String>
    ): List<FieldCompileError> {
        val actualHashes = roleHashes(bundle, expectedHashes.keys)
        return expectedHashes.mapNotNull { (role, expectedHash) ->
            val actualHash = actualHashes[role]
            if (
                actualHash != null &&
                CouncilContractHashing.constantTimeEquals(expectedHash, actualHash)
            ) {
                null
            } else {
                FieldCompileError(
                    code = FieldCompileErrorCode.HASH_MISMATCH,
                    path = ContractPath(
                        listOf("councilClosure", "frozenValidRoleHashes", role.name)
                    ),
                    message = "Unrepaired specialist output changed across selective closure repair",
                    responsibleCouncilRoles = setOf(role)
                )
            }
        }
    }

    private fun roleHashes(
        bundle: CouncilSpecialistBundle,
        roles: Iterable<Role>
    ): Map<Role, String> = roles.associateWithTo(linkedMapOf()) { role ->
        when (role) {
            Role.CouncilTaskAnatomist -> hash(
                TaskAnatomistOutput.serializer(),
                bundle.taskAnatomist
            )
            Role.CouncilIncentiveDesigner -> hash(
                IncentiveDesignerOutput.serializer(),
                bundle.incentiveDesigner
            )
            Role.CouncilEcologist -> hash(EcologistOutput.serializer(), bundle.ecologist)
            Role.CouncilPromptArchitect -> hash(
                PromptArchitectOutput.serializer(),
                bundle.promptArchitect
            )
            Role.CouncilKGArchitect -> hash(KgArchitectOutput.serializer(), bundle.kgArchitect)
            Role.CouncilModelEcologist -> hash(
                ModelEcologistOutput.serializer(),
                bundle.modelEcologist
            )
            Role.CouncilSeedCurator -> hash(
                SeedCuratorOutput.serializer(),
                bundle.seedCurator
            )
            else -> error("$role is not a specialist Council role")
        }
    }

    private fun <T> hash(serializer: KSerializer<T>, value: T): String =
        CouncilContractHashing.sha256(
            CouncilContractHashing.canonicalJson(
                json.encodeToJsonElement(serializer, value)
            )
        )

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        classDiscriminator = "resultType"
    }
}

internal val specialistRoles = listOf(
    Role.CouncilTaskAnatomist,
    Role.CouncilIncentiveDesigner,
    Role.CouncilEcologist,
    Role.CouncilPromptArchitect,
    Role.CouncilKGArchitect,
    Role.CouncilModelEcologist,
    Role.CouncilSeedCurator
)
