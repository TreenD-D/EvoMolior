package com.achulkov.evokammer.api.service

import java.nio.file.Files
import java.nio.file.Path

/**
 * Scans run logs for known failure signatures that should stop a release or prompt a retry.
 *
 * The scanner is path-based so CI can point it at a generated run artifact instead of a
 * checked-in evidence log.
 *
 * @param signatures Signature definitions to search for.
 */
class RunLogSignatureScanner(
    private val signatures: List<LogSignature> = DEFAULT_SIGNATURES
) {
    /**
     * Scans the given log file for known failure signatures.
     *
     * @param logPath Path to a generated or supplied run log artifact.
     * @return All detected signature matches, in file order.
     */
    fun scan(logPath: Path): List<LogSignatureMatch> = Files.readAllLines(logPath)
        .asSequence()
        .flatMapIndexed { index, line ->
            signatures.asSequence()
                .filter { signature -> line.contains(signature.needle) }
                .map { signature ->
                    LogSignatureMatch(
                        signatureId = signature.id,
                        lineNumber = index + 1,
                        line = line.trimEnd()
                    )
                }
        }
        .toList()

    /**
     * True when the supplied log contains at least one tracked failure signature.
     *
     * @param logPath Path to the log artifact to scan.
     */
    fun hasFailures(logPath: Path): Boolean = scan(logPath).isNotEmpty()

    companion object {
        private val DEFAULT_SIGNATURES = listOf(
            LogSignature(
                id = "deprecated-grok-fast",
                needle = "Grok 4 Fast is deprecated"
            ),
            LogSignature(
                id = "agent-stuck-in-node",
                needle = "AIAgentStuckInTheNodeException"
            ),
            LogSignature(
                id = "max-iterations-reached",
                needle = "AIAgentMaxNumberOfIterationsReachedException"
            ),
            LogSignature(
                id = "empty-tool-response",
                needle = "Unexpected response: no tool calls and no content"
            ),
            LogSignature(
                id = "openrouter-provider-502",
                needle = "Provider returned error (code: 502)"
            )
        )
    }
}

/**
 * A tracked log signature.
 *
 * @param id Stable identifier for reporting.
 * @param needle Substring that identifies the failure mode.
 */
data class LogSignature(
    val id: String,
    val needle: String
)

/**
 * A matched failure signature found in a run log.
 *
 * @param signatureId Stable identifier of the matched signature.
 * @param lineNumber 1-based line number where the signature was found.
 * @param line The matching log line.
 */
data class LogSignatureMatch(
    val signatureId: String,
    val lineNumber: Int,
    val line: String
)
