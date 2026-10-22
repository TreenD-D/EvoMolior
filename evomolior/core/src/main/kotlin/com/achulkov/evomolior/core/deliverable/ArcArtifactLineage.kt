package com.achulkov.evomolior.core.deliverable

import com.achulkov.evomolior.core.atlas.AtlasHashing
import kotlinx.serialization.Serializable

/**
 * Deterministic source module set replicated by every ARC group.
 *
 * @param modules Relative module paths and exact source bytes.
 * @param backboneHash Hash of the canonical module set.
 */
@Serializable
data class ArcArtifactBackbone(
    val modules: Map<String, String>,
    val backboneHash: String = "",
) {
    init {
        require(modules.isNotEmpty()) { "ARC artifact backbone must contain modules" }
        require(modules.keys.all(String::isSafeArtifactPath)) {
            "ARC backbone module paths must be safe relative paths"
        }
        require(modules.values.all(String::isNotBlank)) {
            "ARC backbone module sources must not be blank"
        }
    }

    /** Returns this backbone with its deterministic commitment attached. */
    fun withComputedHash(): ArcArtifactBackbone =
        copy(backboneHash = hashBackbone(modules))

    /** Verifies the canonical backbone commitment. */
    fun hasValidHash(): Boolean =
        backboneHash.isArtifactSha256() && backboneHash == hashBackbone(modules)

    companion object {
        /** Returns the immutable ARC modules shared by every solver branch. */
        fun canonical(): ArcArtifactBackbone = ArcArtifactBackbone(
            modules = linkedMapOf(
                "src/backbone/scaffold.py" to
                    "# host-owned ARC scaffold backbone\n",
                "src/backbone/receiver.py" to
                    "# host-owned receiver contract and row cardinality backbone\n",
                "src/backbone/loo_harness.py" to
                    "# host-owned visible leave-one-pair-out harness backbone\n",
                "src/backbone/final_integration.py" to
                    "# host-owned final integration and publication backbone\n",
            ),
        ).withComputedHash()

        private fun hashBackbone(modules: Map<String, String>): String =
            AtlasHashing.hashText(
                modules.toSortedMap().entries.joinToString("\n") { (path, source) ->
                    "$path=${AtlasHashing.hashText(source)}"
                },
            )
    }
}

/** Deterministic solver-family branches used to create structured group diversity. */
@Serializable
enum class ArcSolverFamily(
    val familyId: String,
    val source: String,
) {
    OBJECT_COMPONENT(
        familyId = "object-component",
        source = """
            # divergent ARC solver family: object/component programs
            def build_task_solvers(demos):
                return object_solver, object_counterfactual
        """.trimIndent() + "\n",
    ),
    GEOMETRIC_PALETTE(
        familyId = "geometric-palette",
        source = """
            # divergent ARC solver family: geometric and palette programs
            def build_task_solvers(demos):
                return geometric_solver, palette_counterfactual
        """.trimIndent() + "\n",
    ),
    RELATIONAL_TOPOLOGY(
        familyId = "relational-topology",
        source = """
            # divergent ARC solver family: relation and topology programs
            def build_task_solvers(demos):
                return relational_solver, topology_counterfactual
        """.trimIndent() + "\n",
    ),
    REFINEMENT_MUTATION(
        familyId = "refinement-mutation",
        source = """
            # divergent ARC solver family: refinement and mutation programs
            def build_task_solvers(demos):
                return refinement_solver, mutation_counterfactual
        """.trimIndent() + "\n",
    );

    /** Stable branch source hash. */
    val sourceHash: String
        get() = AtlasHashing.hashText(source)
}

/**
 * Hash-bound lineage connecting one group branch to the common backbone.
 *
 * @param runId Owning run.
 * @param groupId Owning group.
 * @param round Round that materialized the branch.
 * @param backboneHash Common replicated module hash.
 * @param solverFamily Divergent solver family.
 * @param branchHash Exact divergent module hash.
 * @param lineageHash Self-hash excluding this field.
 */
@Serializable
data class ArcArtifactBranchLineage(
    val runId: String,
    val groupId: String,
    val round: Int,
    val backboneHash: String,
    val solverFamily: ArcSolverFamily,
    val branchHash: String,
    val lineageHash: String = "",
) {
    init {
        require(runId.isNotBlank() && groupId.isNotBlank()) {
            "ARC branch lineage identity must not be blank"
        }
        require(round >= 0) { "ARC branch lineage round must not be negative" }
        require(backboneHash.isArtifactSha256()) { "ARC backbone hash is invalid" }
        require(branchHash.isArtifactSha256()) { "ARC branch hash is invalid" }
    }

    /** Returns this lineage with its deterministic commitment attached. */
    fun withComputedHash(): ArcArtifactBranchLineage =
        copy(
            lineageHash = AtlasHashing.hashText(
                listOf(
                    runId,
                    groupId,
                    round,
                    backboneHash,
                    solverFamily.familyId,
                    branchHash,
                ).joinToString("|"),
            ),
        )

    /** Verifies the exact lineage commitment. */
    fun hasValidHash(): Boolean =
        lineageHash.isArtifactSha256() &&
            lineageHash == withComputedHash().lineageHash
}

private fun String.isSafeArtifactPath(): Boolean =
    isNotBlank() &&
        !startsWith("/") &&
        !contains('\\') &&
        split('/').none { it.isBlank() || it == "." || it == ".." }

private fun String.isArtifactSha256(): Boolean = matches(Regex("[0-9a-f]{64}"))
