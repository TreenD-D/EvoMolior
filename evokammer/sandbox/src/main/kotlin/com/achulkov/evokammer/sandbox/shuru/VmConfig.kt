package com.achulkov.evokammer.sandbox.shuru

import com.achulkov.evokammer.sandbox.network.PUBLIC_WEB_HOST_ALLOWLIST

/**
 * Configuration for a Shuru microVM agent instance.
 *
 * @param agentId Unique agent identifier.
 * @param groupId Group this agent belongs to.
 * @param runId Run this agent belongs to.
 * @param cpuCount Number of vCPUs to allocate.
 * @param memoryMb Memory in megabytes.
 * @param diskGb Disk size in gigabytes.
 * @param mounts Filesystem mount configuration.
 * @param network Network allowlist configuration.
 * @param agentJarPath Path to the AgentRunner JAR on the host filesystem.
 * @param bootContextJson Serialised [AgentBootContext] passed to the agent via the
 *   `EVOKAMMER_BOOT_CONTEXT` environment variable.
 * @param openRouterApiKey OpenRouter API key injected as `OPENROUTER_API_KEY` env var.
 * @param groupProfile Group profile name injected as `EVOKAMMER_GROUP_PROFILE` env var.
 * @param ipcPort Port for IPC between the agent VM and the LifecycleManager.
 */
data class VmConfig(
    val agentId: String,
    val groupId: String,
    val runId: String,
    val cpuCount: Int = 1,
    val memoryMb: Int = 512,
    val diskGb: Int = 2,
    val mounts: FilesystemMountConfig,
    val network: NetworkAllowlistConfig,
    val agentJarPath: String,
    val bootContextJson: String,
    val openRouterApiKey: String,
    val groupProfile: String,
    val ipcPort: Int
)

/**
 * Filesystem mount configuration for a Shuru microVM.
 *
 * @param kbMountHostPath Host path to the group's KB directory (read-only in VM as /kb).
 * @param toolsMountHostPath Host path to the group's tools directory (read-only as /tools).
 * @param stagingMountHostPath Host path to the agent's staging directory (write-only as /staging).
 */
data class FilesystemMountConfig(
    val kbMountHostPath: String,
    val toolsMountHostPath: String,
    val stagingMountHostPath: String,
    val artifactsMountHostPath: String? = null
)

/**
 * Network allowlist configuration for a Shuru microVM.
 *
 * @param allowedDomains Set of domains the agent may access via HTTP/HTTPS.
 * @param rateLimit Maximum outbound HTTP requests per minute.
 * @param ipcTargetHost Host address the agent can reach for IPC (LifecycleManager endpoint).
 */
data class NetworkAllowlistConfig(
    val allowedDomains: Set<String>,
    val rateLimit: Int = 10,
    val ipcTargetHost: String = "10.0.0.1"
) {
    companion object {
        /**
         * Broad baseline public-web allowlist.
         *
         * This keeps the sandbox useful for general-purpose runs while still limiting egress
         * to obvious public web, code hosting, documentation, and model-provider domains.
         */
        val GENERAL_WEB = NetworkAllowlistConfig(
            allowedDomains = PUBLIC_WEB_HOST_ALLOWLIST,
            rateLimit = 30
        )

        /** Allowlist for Researcher role. */
        val RESEARCHER = NetworkAllowlistConfig(
            allowedDomains = GENERAL_WEB.allowedDomains + setOf(
                "en.wikipedia.org",
                "docs.kotlin.org",
                "pub.dev"
            ),
            rateLimit = 20
        )

        /** Allowlist for Builder role — package registries + docs. */
        val BUILDER = NetworkAllowlistConfig(
            allowedDomains = GENERAL_WEB.allowedDomains + setOf(
                "search.maven.org",
                "pypi.org",
                "npmjs.com",
                "crates.io",
                "docs.python.org",
                "docs.kotlin.org",
                "kotlinlang.org"
            ),
            rateLimit = 15
        )

        /** Roles that cannot access the internet. */
        val NO_INTERNET = NetworkAllowlistConfig(
            allowedDomains = emptySet(),
            rateLimit = 0
        )

        /** Broader allowlist for Outlier and Council roles. */
        val BROAD = NetworkAllowlistConfig(
            allowedDomains = GENERAL_WEB.allowedDomains + setOf(
                "reddit.com",
                "substack.com",
                "substackcdn.com",
                "medium.com",
                "towardsdatascience.com",
                "dev.to"
            ),
            rateLimit = 30
        )
    }
}
