package com.achulkov.evomolior.agents.factory

import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.council.QuantitativeToolObligation
import com.achulkov.evomolior.core.model.Role

/**
 * Immutable evidence from independently compiling every pre-Council provider schema.
 *
 * @param acceptanceProfile Exact non-normal profile validated.
 * @param schemaSha256ByRole Exact generated-schema hashes keyed by Council role.
 */
data class CouncilAcceptanceSchemaAdmissionEvidence(
    val acceptanceProfile: AcceptanceProfile,
    val schemaSha256ByRole: Map<String, String>
)

/**
 * Independently validates every provider schema available before the first Council call.
 *
 * The contextual Synthesis schema remains bound to the accepted Task Anatomist response and is
 * independently compiled by the structured-output boundary before that later provider call.
 */
object CouncilAcceptanceSchemaAdmissionProbe {
    /**
     * Generates and Draft-2020-12-compiles the exact specialist schemas for [acceptanceProfile]
     * and [quantitativeToolObligation].
     *
     * @param acceptanceProfile Exact smoke or full acceptance profile.
     * @param quantitativeToolObligation Optional operator-owned Task schema authority.
     * @return Stable role-to-schema-hash evidence.
     */
    fun validate(
        acceptanceProfile: AcceptanceProfile,
        quantitativeToolObligation: QuantitativeToolObligation? = null
    ): CouncilAcceptanceSchemaAdmissionEvidence {
        require(acceptanceProfile != AcceptanceProfile.NORMAL) {
            "Acceptance schema admission requires a non-normal profile"
        }
        val schemas = SPECIALIST_ROLES.associate { role ->
            val document = CodexOutputSchemaDocument.from(
                CouncilRoleSchemas.forRole(
                    role = role,
                    acceptanceProfile = acceptanceProfile,
                    quantitativeToolObligation =
                        quantitativeToolObligation.takeIf {
                            role == Role.CouncilTaskAnatomist
                        }
                )
            )
            val failures = CodexStructuredOutputValidator.validateSchema(document)
            require(failures.isEmpty()) {
                "Generated provider schema for ${role.name} failed independent validation: " +
                    failures.joinToString()
            }
            role.name to document.sha256
        }.toSortedMap()
        return CouncilAcceptanceSchemaAdmissionEvidence(
            acceptanceProfile = acceptanceProfile,
            schemaSha256ByRole = schemas
        )
    }

    private val SPECIALIST_ROLES = listOf(
        Role.CouncilTaskAnatomist,
        Role.CouncilIncentiveDesigner,
        Role.CouncilEcologist,
        Role.CouncilPromptArchitect,
        Role.CouncilKGArchitect,
        Role.CouncilModelEcologist,
        Role.CouncilSeedCurator
    )
}
