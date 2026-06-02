package com.achulkov.evokammer.api.service

import kotlin.system.exitProcess

/**
 * Command-line entrypoint for scanning a run log artifact for known failure signatures.
 *
 * Usage:
 * `./gradlew :evokammer:api:scanRunLogSignatures -PrunLogPath=/path/to/log`
 */
fun main(args: Array<String>) {
    if (args.size != 1) {
        System.err.println("Usage: RunLogSignatureScannerMain <logPath>")
        exitProcess(2)
    }

    val logPath = runCatching { java.nio.file.Path.of(args[0]) }
        .getOrElse {
            System.err.println("Invalid log path: ${args[0]}")
            exitProcess(2)
        }

    val scanner = RunLogSignatureScanner()
    val matches = scanner.scan(logPath)

    if (matches.isEmpty()) {
        println("No tracked failure signatures found in ${logPath.toAbsolutePath()}")
        return
    }

    System.err.println("Tracked failure signatures found in ${logPath.toAbsolutePath()}:")
    matches.forEach { match ->
        System.err.println("  [${match.signatureId}] line ${match.lineNumber}: ${match.line}")
    }
    exitProcess(1)
}
