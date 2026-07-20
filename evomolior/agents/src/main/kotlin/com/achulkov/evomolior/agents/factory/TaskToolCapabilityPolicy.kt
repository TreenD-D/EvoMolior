package com.achulkov.evomolior.agents.factory

/**
 * Exact task-facing tool capability policy for one run.
 *
 * Internal society protocol tools are intentionally outside this policy. They implement the
 * contribution and publication protocol rather than giving an arm additional task capabilities.
 *
 * @param allowedToolIds Exact task-facing tool identifiers available to agents.
 */
data class TaskToolCapabilityPolicy(
    val allowedToolIds: Set<String>
) {
    init {
        require(allowedToolIds.all(SUPPORTED_TOOL_IDS::contains)) {
            "Unsupported task-facing tool capability"
        }
    }

    /** Returns whether the exact task-facing capability is enabled. */
    fun allows(toolId: String): Boolean = toolId in allowedToolIds

    companion object {
        const val READ_RUN_ARTIFACT = "read_run_artifact"
        const val REQUEST_SOURCE_ACCESS = "request_source_access"
        const val FETCH_SOURCE = "fetch_source"
        const val LIST_PROMOTED_TOOLS = "list_tools"
        const val EXECUTE_PROMOTED_TOOL = "execute_tool"
        const val CODEX_NATIVE_SEARCH = "codex_native_search"

        /** Complete typed set of task-facing capabilities understood by the runtime. */
        val SUPPORTED_TOOL_IDS: Set<String> = setOf(
            READ_RUN_ARTIFACT,
            REQUEST_SOURCE_ACCESS,
            FETCH_SOURCE,
            LIST_PROMOTED_TOOLS,
            EXECUTE_PROMOTED_TOOL,
            CODEX_NATIVE_SEARCH
        )

        /** Normal production policy. */
        val unrestricted = TaskToolCapabilityPolicy(SUPPORTED_TOOL_IDS)
    }
}
