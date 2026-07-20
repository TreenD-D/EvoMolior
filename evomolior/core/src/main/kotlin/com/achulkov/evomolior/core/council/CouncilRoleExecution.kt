package com.achulkov.evomolior.core.council

import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.model.Role

/**
 * One typed Genesis Council provider invocation.
 *
 * [synthesisWireAuthority] is present only for Synthesis and binds its provider schema to the
 * current Task Anatomist output without parsing rendered prompt context.
 *
 * @param role Council role to execute.
 * @param contextJson Complete strict task context.
 * @param acceptanceProfile Explicit provider-schema and compiler acceptance profile.
 * @param synthesisWireAuthority Current Task-derived sealed-test authority for Synthesis.
 */
data class CouncilRoleExecutionRequest(
    val role: Role,
    val contextJson: String,
    val acceptanceProfile: AcceptanceProfile = AcceptanceProfile.NORMAL,
    val synthesisWireAuthority: CouncilSynthesisWireAuthority? = null
) {
    init {
        require(role.isCouncilRole) { "Role $role is not a Council role" }
        require(
            (role == Role.CouncilSynthesis) == (synthesisWireAuthority != null)
        ) {
            if (role == Role.CouncilSynthesis) {
                "Council Synthesis requires Task-derived sealed-test wire authority"
            } else {
                "Only Council Synthesis may receive sealed-test wire authority"
            }
        }
    }
}

/**
 * Executes one typed Genesis Council request.
 */
fun interface CouncilAgentRunner {
    /**
     * Executes [request] and returns one complete provider response.
     *
     * @param request Typed role, context, and provider-wire authority.
     * @return One complete JSON response.
     */
    suspend fun run(request: CouncilRoleExecutionRequest): String
}
