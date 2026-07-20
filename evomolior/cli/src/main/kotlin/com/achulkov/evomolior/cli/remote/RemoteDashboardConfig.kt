package com.achulkov.evomolior.cli.remote

import com.achulkov.evomolior.cli.CliUsageException
import java.net.InetAddress
import java.nio.file.Path

/**
 * Deployment-provided configuration for the remote CLI dashboard.
 *
 * @param host Host interface Ktor binds to.
 * @param port HTTP port Ktor binds to.
 * @param username Operator login username.
 * @param password Operator login password.
 * @param dataDir Persistent dashboard data directory.
 * @param cliExecutable CLI binary used for subprocess launches.
 * @param secureCookie Whether the session cookie is restricted to HTTPS.
 * @param maxConcurrentRuns Maximum number of dashboard subprocesses running concurrently.
 * @param maxSseConnections Maximum number of concurrent dashboard event streams.
 */
internal data class RemoteDashboardConfig(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val dataDir: Path,
    val cliExecutable: String,
    val secureCookie: Boolean = false,
    val maxConcurrentRuns: Int = 2,
    val maxSseConnections: Int = 32
) {
    init {
        require(host.isNotBlank()) { "Dashboard host cannot be blank" }
        require(secureCookie || isLoopbackHost(host)) {
            "Non-loopback dashboard bindings require HTTPS termination and secure cookies"
        }
        require(maxConcurrentRuns in 1..32) { "maxConcurrentRuns must be between 1 and 32" }
        require(maxSseConnections in 1..256) { "maxSseConnections must be between 1 and 256" }
    }

    companion object {
        fun parse(
            args: Array<String>,
            env: Map<String, String> = System.getenv()
        ): RemoteDashboardConfig {
            var host = env["EVOMOLIOR_DASHBOARD_HOST"]?.takeIf(String::isNotBlank) ?: "127.0.0.1"
            var port = env["EVOMOLIOR_DASHBOARD_PORT"]?.toIntOrNull() ?: 8080
            var dataDir = Path.of(env["EVOMOLIOR_DASHBOARD_DATA_DIR"]?.takeIf(String::isNotBlank) ?: "/data")
            var cliExecutable = env["EVOMOLIOR_CLI_BIN"]?.takeIf(String::isNotBlank) ?: "evomolior"
            val secureCookie = env["EVOMOLIOR_DASHBOARD_SECURE_COOKIE"]
                ?.equals("true", ignoreCase = true) == true
            val maxConcurrentRuns = env["EVOMOLIOR_DASHBOARD_MAX_CONCURRENT_RUNS"]
                ?.toIntOrNull()
                ?: 2
            val maxSseConnections = env["EVOMOLIOR_DASHBOARD_MAX_SSE_CONNECTIONS"]
                ?.toIntOrNull()
                ?: 32

            var index = 0
            while (index < args.size) {
                when (val arg = args[index]) {
                    "-h", "--help" -> throw CliUsageException(usage())
                    "--host" -> host = args.getOrNull(++index) ?: throw CliUsageException("Missing value for $arg")
                    "--port" -> {
                        val value = args.getOrNull(++index) ?: throw CliUsageException("Missing value for $arg")
                        port = value.toIntOrNull()?.takeIf { it in 1..65535 }
                            ?: throw CliUsageException("Dashboard port must be between 1 and 65535, got: $value")
                    }
                    "--data-dir" -> dataDir = Path.of(args.getOrNull(++index) ?: throw CliUsageException("Missing value for $arg"))
                    "--cli-bin" -> cliExecutable = args.getOrNull(++index) ?: throw CliUsageException("Missing value for $arg")
                    else -> throw CliUsageException("Unknown serve argument: $arg")
                }
                index++
            }

            val username = env["EVOMOLIOR_DASHBOARD_USERNAME"]?.trim().orEmpty()
            val password = env["EVOMOLIOR_DASHBOARD_PASSWORD"]?.trim().orEmpty()
            if (username.isBlank()) {
                throw CliUsageException("EVOMOLIOR_DASHBOARD_USERNAME is required for the remote dashboard")
            }
            if (password.isBlank()) {
                throw CliUsageException("EVOMOLIOR_DASHBOARD_PASSWORD is required for the remote dashboard")
            }
            if (cliExecutable.isBlank()) {
                throw CliUsageException("CLI executable cannot be blank")
            }
            if (maxConcurrentRuns !in 1..32) {
                throw CliUsageException("EVOMOLIOR_DASHBOARD_MAX_CONCURRENT_RUNS must be between 1 and 32")
            }
            if (maxSseConnections !in 1..256) {
                throw CliUsageException("EVOMOLIOR_DASHBOARD_MAX_SSE_CONNECTIONS must be between 1 and 256")
            }
            if (!secureCookie && !isLoopbackHost(host)) {
                throw CliUsageException(
                    "A non-loopback dashboard host requires EVOMOLIOR_DASHBOARD_SECURE_COOKIE=true " +
                        "and HTTPS termination in front of the dashboard"
                )
            }

            return RemoteDashboardConfig(
                host = host,
                port = port,
                username = username,
                password = password,
                dataDir = dataDir.toAbsolutePath().normalize(),
                cliExecutable = cliExecutable,
                secureCookie = secureCookie,
                maxConcurrentRuns = maxConcurrentRuns,
                maxSseConnections = maxSseConnections
            )
        }

        private fun isLoopbackHost(host: String): Boolean =
            host.equals("localhost", ignoreCase = true) ||
                runCatching { InetAddress.getAllByName(host).all(InetAddress::isLoopbackAddress) }
                    .getOrDefault(false)

        fun usage(): String = """
            EvoMolior remote dashboard

            Environment:
              EVOMOLIOR_DASHBOARD_USERNAME  required
              EVOMOLIOR_DASHBOARD_PASSWORD  required
              EVOMOLIOR_DASHBOARD_HOST      optional, default 127.0.0.1
              EVOMOLIOR_DASHBOARD_PORT      optional, default 8080
              EVOMOLIOR_DASHBOARD_DATA_DIR  optional, default /data
              EVOMOLIOR_CLI_BIN             optional, default evomolior
              EVOMOLIOR_DASHBOARD_SECURE_COOKIE optional; required for non-loopback binds
              EVOMOLIOR_DASHBOARD_MAX_CONCURRENT_RUNS optional, default 2
              EVOMOLIOR_DASHBOARD_MAX_SSE_CONNECTIONS optional, default 32

            Optional arguments:
              --host HOST
              --port PORT
              --data-dir PATH
              --cli-bin PATH
        """.trimIndent()
    }
}
