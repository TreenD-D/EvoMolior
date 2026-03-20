package com.achulkov.evokammer.sandbox.shuru

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
 * @param bootContextJson Serialised [AgentBootContext] passed to the agent via stdin.
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
    val stagingMountHostPath: String
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
        /** Allowlist for Researcher role. */
        val RESEARCHER = NetworkAllowlistConfig(
            allowedDomains = setOf(
                "en.wikipedia.org", "arxiv.org", "news.ycombinator.com",
                "developer.mozilla.org", "docs.python.org", "docs.kotlin.org",
                "kotlinlang.org", "pub.dev", "pypi.org"
            ),
            rateLimit = 20
        )

        /** Allowlist for Builder role — package registries + docs. */
        val BUILDER = NetworkAllowlistConfig(
            allowedDomains = setOf(
                "search.maven.org", "pypi.org", "npmjs.com", "crates.io",
                "docs.python.org", "docs.kotlin.org", "kotlinlang.org",
                "developer.mozilla.org"
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
            allowedDomains = RESEARCHER.allowedDomains + setOf(
                "github.com", "stackoverflow.com", "reddit.com",
                "medium.com", "towardsdatascience.com"
            ),
            rateLimit = 30
        )
    }
}
