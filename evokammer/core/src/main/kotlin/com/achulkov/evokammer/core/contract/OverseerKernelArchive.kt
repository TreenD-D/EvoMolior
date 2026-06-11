package com.achulkov.evokammer.core.contract

/**
 * Canonical archive identifiers for the deterministic Overseer control plane.
 */
object OverseerKernelArchive {
    /**
     * Synthetic archive run ID used to store Overseer evidence in the meta-archive.
     */
    const val RUN_ID: String = "__overseer_kernel__"

    /**
     * Meta-archive entry type used for mirrored Overseer audit entries.
     */
    const val ENTRY_TYPE: String = "OVERSEER_AUDIT"
}
