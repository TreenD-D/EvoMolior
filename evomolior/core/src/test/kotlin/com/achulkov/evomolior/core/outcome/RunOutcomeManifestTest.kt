package com.achulkov.evomolior.core.outcome

import com.achulkov.evomolior.core.deliverable.RunOutcomeClass
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunOutcomeManifestTest {
    @Test
    fun `accepted manifest requires canonical metrics history and mechanism paths`() {
        val sidecarsOnly = acceptedManifest(
            listOf(
                entry(
                    "authority/final-reference.json",
                    RunOutcomeManifestEntryCategory.ACCEPTED_AUTHORITY,
                ),
                entry(
                    "accepted/run-metrics-v1.sha256",
                    RunOutcomeManifestEntryCategory.ACCEPTED_RUN_METRICS,
                ),
                entry(
                    "accepted/publication-history.sha256",
                    RunOutcomeManifestEntryCategory.ACCEPTED_PUBLICATION_HISTORY,
                ),
                entry(
                    "semantic-package.json",
                    RunOutcomeManifestEntryCategory.RECEIVER_DECISION_PACKAGE,
                ),
            )
        ).withComputedHash()

        assertFalse(sidecarsOnly.hasValidHashAndSemantics())

        val complete = acceptedManifest(
            sidecarsOnly.entries + listOf(
                entry(
                    "accepted/run-metrics-v1.json",
                    RunOutcomeManifestEntryCategory.ACCEPTED_RUN_METRICS,
                ),
                entry(
                    "accepted/publication-history.json",
                    RunOutcomeManifestEntryCategory.ACCEPTED_PUBLICATION_HISTORY,
                ),
                entry(
                    "mechanism-activation-report-v1.json",
                    RunOutcomeManifestEntryCategory.MECHANISM_ACTIVATION_REPORT,
                ),
            )
        ).withComputedHash()

        assertTrue(complete.hasValidHashAndSemantics())
    }

    @Test
    fun `provisional manifest rejects accepted authority categories and paths`() {
        val valid = provisionalManifest(
            listOf(
                entry("recovery/source-set.json", RunOutcomeManifestEntryCategory.RECOVERY_SOURCE_SET),
                entry(
                    "recovery/run-metrics-v1.json",
                    RunOutcomeManifestEntryCategory.RECOVERY_RUN_METRICS,
                ),
                entry(
                    "recovery/publication-history.json",
                    RunOutcomeManifestEntryCategory.RECOVERY_PUBLICATION_HISTORY,
                ),
                entry(
                    "provisional-decision-package.json",
                    RunOutcomeManifestEntryCategory.RECEIVER_DECISION_PACKAGE,
                ),
            )
        ).withComputedHash()
        assertTrue(valid.hasValidHashAndSemantics())

        val leaked = provisionalManifest(
            valid.entries + entry(
                "authority/final-reference.json",
                RunOutcomeManifestEntryCategory.FINAL_REFERENCE_AUTHORITY,
            )
        ).withComputedHash()

        assertFalse(leaked.hasValidHashAndSemantics())
        assertFailsWith<IllegalArgumentException> {
            RunOutcomeManifestPolicy.requireValid(leaked)
        }
    }

    private fun provisionalManifest(entries: List<RunOutcomeManifestEntry>) =
        RunOutcomeManifestV1(
            runId = "run-1",
            outcomeClass = RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE,
            admissionDistributionHash = "a".repeat(64),
            deliverableBlueprintHash = "b".repeat(64),
            decisionPackageHash = "c".repeat(64),
            recoverySourceSetHashes = listOf("d".repeat(64)),
            lifecycleReconciliationHash = "e".repeat(64),
            metricsHash = "1".repeat(64),
            publicationHistoryHash = "f".repeat(64),
            entries = entries.sortedBy { it.path },
        )

    private fun acceptedManifest(entries: List<RunOutcomeManifestEntry>) =
        RunOutcomeManifestV1(
            runId = "run-1",
            outcomeClass = RunOutcomeClass.ACCEPTED_DECISION_PACKAGE,
            admissionDistributionHash = "a".repeat(64),
            startingFieldHash = "b".repeat(64),
            workerAtlasHash = "c".repeat(64),
            graderAtlasHash = "d".repeat(64),
            sealedTestSuiteHash = "e".repeat(64),
            deliverableBlueprintHash = "f".repeat(64),
            decisionPackageHash = "1".repeat(64),
            acceptedAuthorityHash = "2".repeat(64),
            lifecycleReconciliationHash = "3".repeat(64),
            metricsHash = "4".repeat(64),
            publicationHistoryHash = "5".repeat(64),
            entries = entries.sortedBy { it.path },
        )

    private fun entry(
        path: String,
        category: RunOutcomeManifestEntryCategory,
    ) = RunOutcomeManifestEntry(
        path = path,
        mediaType = "application/json",
        sizeBytes = 1,
        sha256 = "1".repeat(64),
        category = category,
    )
}
