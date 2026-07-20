package com.achulkov.evomolior.api.service

/**
 * Synchronous acceptance authority boundaries at which immutable execution inputs must be
 * re-verified before the run may advance.
 */
sealed interface AcceptanceRunBoundary {
    /**
     * A completed competitive round, before control advances beyond its authoritative result.
     *
     * @param roundIndex Zero-based completed round index.
     */
    data class RoundCompleted(val roundIndex: Int) : AcceptanceRunBoundary

    /** The boundary immediately before receiver-facing final publication begins. */
    data object BeforeFinalPublication : AcceptanceRunBoundary

    /** The boundary immediately before completion and archive authorities are persisted. */
    data object BeforeArchivePublication : AcceptanceRunBoundary
}

/**
 * Verifies immutable acceptance inputs synchronously inside the run authority path.
 *
 * Implementations fail closed by throwing when the source or distribution authority has drifted.
 */
fun interface AcceptanceRunBoundaryVerifier {
    /**
     * Verifies one acceptance boundary.
     *
     * @param runId Run whose authority is about to advance.
     * @param boundary Exact synchronous boundary being verified.
     */
    fun verify(runId: String, boundary: AcceptanceRunBoundary)

    companion object {
        /** No-op verifier used by normal runs and non-acceptance callers. */
        val NONE: AcceptanceRunBoundaryVerifier = AcceptanceRunBoundaryVerifier { _, _ -> }
    }
}
