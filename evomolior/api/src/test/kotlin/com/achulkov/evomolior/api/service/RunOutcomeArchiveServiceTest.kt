@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.contract.RecoveryPublicationState
import com.achulkov.evomolior.core.contract.RecoveryPublicationStateEvent
import com.achulkov.evomolior.core.contract.StartupDiagnosticState
import com.achulkov.evomolior.core.contract.StartupDiagnosticStateEvent
import com.achulkov.evomolior.core.contract.CostLedgerEntry
import com.achulkov.evomolior.core.contract.CostLedgerEntryKind
import com.achulkov.evomolior.core.contract.CostSubjectType
import com.achulkov.evomolior.core.contract.PublicationState
import com.achulkov.evomolior.core.contract.PublicationStateEvent
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.config.CouncilExecutionPurpose
import com.achulkov.evomolior.core.deliverable.AcceptedDeliverableAuthority
import com.achulkov.evomolior.core.deliverable.AcceptanceStatus
import com.achulkov.evomolior.core.deliverable.ComponentAuthorityClass
import com.achulkov.evomolior.core.deliverable.DeliverableAssemblyRequest
import com.achulkov.evomolior.core.deliverable.DeliverableBlueprint
import com.achulkov.evomolior.core.deliverable.DeliverableComponentKind
import com.achulkov.evomolior.core.deliverable.DeliverableComponentProvenance
import com.achulkov.evomolior.core.deliverable.DeliverableComponentSpec
import com.achulkov.evomolior.core.deliverable.DeliverableContentOrigin
import com.achulkov.evomolior.core.deliverable.DeliverableEvidenceDisclosure
import com.achulkov.evomolior.core.deliverable.DeliverableEvidenceLink
import com.achulkov.evomolior.core.deliverable.DeliverableFormat
import com.achulkov.evomolior.core.deliverable.DeliverableHashing
import com.achulkov.evomolior.core.deliverable.ExecutionCompletionStatus
import com.achulkov.evomolior.core.deliverable.PrimaryArtifactValidationStatus
import com.achulkov.evomolior.core.deliverable.DeliverableSemanticBlock
import com.achulkov.evomolior.core.deliverable.DeterministicDeliverableAssembler
import com.achulkov.evomolior.core.deliverable.FailedAcceptanceGate
import com.achulkov.evomolior.core.deliverable.RecoverySourceCandidate
import com.achulkov.evomolior.core.deliverable.RecoverySourceSet
import com.achulkov.evomolior.core.deliverable.RunOutcomeClass
import com.achulkov.evomolior.core.deliverable.SuppliedDeliverableComponent
import com.achulkov.evomolior.core.outcome.StartupDiagnosticIssue
import com.achulkov.evomolior.core.outcome.StartupDiagnosticPackageV1
import com.achulkov.evomolior.core.outcome.StartupFailurePhase
import com.achulkov.evomolior.core.outcome.RunOutcomeManifestV1
import com.achulkov.evomolior.core.metrics.MetricAvailability
import com.achulkov.evomolior.core.metrics.RunMetricCatalog
import com.achulkov.evomolior.core.metrics.RunMetricObservation
import com.achulkov.evomolior.core.metrics.RunMetricsV1
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.cost.CostLedgerRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class RunOutcomeArchiveServiceTest {
    private val service = RunOutcomeArchiveService()

    @Test
    fun `historical v4 verification does not require outcome-v5 authority`() {
        val manifest = RunManifest(
            schemaVersion = 4,
            runId = "run-v4",
            objective = "historical",
            groupCount = 1,
            generationCeiling = 1,
            startingFieldArchiveId = "starting",
            startingFieldSha256 = hash("starting"),
            artifactCount = 0,
            archiveEntryCount = 0,
            inputArtifacts = emptyList(),
            entries = emptyList(),
        )
        val bytes = zip(
            mapOf(
                "manifest.json" to Json.encodeToString(
                    RunManifest.serializer(),
                    manifest,
                ).encodeToByteArray()
            )
        )

        val verification = RunManifestVerifier.verify(bytes)

        assertFalse(verification.isValid)
        assertFalse(verification.errors.any { it.contains("Unsupported manifest schema") })
        assertFalse(verification.errors.any { it.contains("outcome", ignoreCase = true) })
    }

    @Test
    fun `accepted package emits and verifies outcome-aware RunManifest v5`() {
        val fixture = fixture(RunOutcomeClass.ACCEPTED_DECISION_PACKAGE)
        val archive = service.archiveAccepted(
            acceptedRequest(fixture)
        )

        val verification = RunManifestVerifier.verify(archive.bytes, expectedRunId = RUN_ID)

        assertTrue(verification.isValid, verification.errors.joinToString())
        assertEquals(5, verification.manifest?.schemaVersion)
        assertSourceSidecars(archive)
        assertEquals(
            sha256(unzip(archive.bytes).getValue(RunManifestVerifier.OUTCOME_MANIFEST_PATH)),
            verification.manifest?.outcomeManifestSha256,
        )
    }

    @Test
    fun `non-normal accepted and provisional archives commit exact admission bytes`() {
        val distributionHash = hash("admission")
        val admissionBytes = acceptanceAdmissionBytes(
            profile = AcceptanceProfile.RUNTIME_USABILITY_SMOKE,
            distributionHash = distributionHash,
        )
        val acceptedFixture = fixture(RunOutcomeClass.ACCEPTED_DECISION_PACKAGE)
        val accepted = service.archiveAccepted(
            acceptedRequest(acceptedFixture).copy(
                acceptanceAdmissionBytes = admissionBytes,
                councilExecutionPurpose = CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE,
                acceptanceProfile = AcceptanceProfile.RUNTIME_USABILITY_SMOKE,
            )
        )
        val acceptedFiles = unzip(accepted.bytes)
        val acceptedVerification = RunManifestVerifier.verify(
            accepted.bytes,
            expectedRunId = RUN_ID,
            expectedCouncilExecutionPurpose = CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE,
            expectedAcceptanceProfile = AcceptanceProfile.RUNTIME_USABILITY_SMOKE,
        )
        assertTrue(acceptedVerification.isValid, acceptedVerification.errors.joinToString())
        assertTrue(
            acceptedFiles.getValue(AcceptanceAdmissionArtifact.FILE_NAME)
                .contentEquals(admissionBytes)
        )
        assertEquals(
            sha256(admissionBytes),
            acceptedVerification.manifest?.acceptanceAdmissionSha256,
        )
        assertEquals(
            sha256(admissionBytes),
            accepted.outcomeManifest.acceptanceAdmissionHash,
        )

        val provisionalFixture = fixture(RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE)
        val sourceSet = RecoverySourceSet.seal(
            RUN_ID,
            listOf(RecoverySourceCandidate("group-0", hash("snapshot"), setOf("evidence"))),
        )
        val provisional = service.archiveProvisional(
            provisionalRequest(provisionalFixture, sourceSet).copy(
                acceptanceProfile = AcceptanceProfile.RUNTIME_USABILITY_SMOKE,
                acceptanceAdmissionBytes = admissionBytes,
            )
        )
        val provisionalAuthority = service.verifyProvisional(provisional.bytes)
        assertEquals(
            AcceptanceProfile.RUNTIME_USABILITY_SMOKE,
            provisionalAuthority.acceptanceProfile,
        )
        assertEquals(
            sha256(admissionBytes),
            provisionalAuthority.outcomeManifest.acceptanceAdmissionHash,
        )

        assertFailsWith<IllegalArgumentException> {
            service.archiveAccepted(
                acceptedRequest(acceptedFixture).copy(
                    acceptanceProfile = AcceptanceProfile.RUNTIME_USABILITY_SMOKE,
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.archiveProvisional(
                provisionalRequest(provisionalFixture, sourceSet).copy(
                    acceptanceProfile = AcceptanceProfile.RUNTIME_USABILITY_SMOKE,
                    acceptanceAdmissionBytes = acceptanceAdmissionBytes(
                        profile = AcceptanceProfile.APPENDIX_B_FULL,
                        distributionHash = distributionHash,
                    ),
                )
            )
        }
    }

    @Test
    fun `v5 rejects self-consistent forged final-reference authority`() {
        val fixture = fixture(RunOutcomeClass.ACCEPTED_DECISION_PACKAGE)
        val archive = service.archiveAccepted(
            acceptedRequest(fixture)
        )
        val files = unzip(archive.bytes).toMutableMap()
        val forgedAuthority = AcceptedDeliverableAuthority(
            winnerAuthorityId = "forged-winner",
            winnerAuthorityHash = hash("forged-winner"),
            sourceAuthorityIds = setOf("forged-source"),
            writerAuthorityId = "forged-writer",
            writerOutputHash = hash("forged-output"),
        )
        val authorityPath = "authority/final-reference.json"
        val authorityBytes = strictJson.encodeToString(
            AcceptedDeliverableAuthority.serializer(),
            forgedAuthority,
        ).encodeToByteArray()
        files[authorityPath] = authorityBytes

        val outcome = strictJson.decodeFromString(
            RunOutcomeManifestV1.serializer(),
            files.getValue(RunManifestVerifier.OUTCOME_MANIFEST_PATH).decodeToString(),
        )
        val forgedOutcome = outcome.copy(
            acceptedAuthorityHash = acceptedDeliverableAuthorityHash(forgedAuthority),
            entries = outcome.entries.map { entry ->
                if (entry.path == authorityPath) {
                    entry.copy(
                        sizeBytes = authorityBytes.size.toLong(),
                        sha256 = sha256(authorityBytes),
                    )
                } else {
                    entry
                }
            },
            manifestHash = "",
        ).withComputedHash()
        val outcomeBytes = strictJson.encodeToString(
            RunOutcomeManifestV1.serializer(),
            forgedOutcome,
        ).encodeToByteArray()
        files[RunManifestVerifier.OUTCOME_MANIFEST_PATH] = outcomeBytes

        val runManifest = strictJson.decodeFromString(
            RunManifest.serializer(),
            files.getValue("manifest.json").decodeToString(),
        )
        val forgedRunManifest = runManifest.copy(
            outcomeManifestSha256 = sha256(outcomeBytes),
            entries = runManifest.entries.map { entry ->
                when (entry.path) {
                    authorityPath -> entry.copy(
                        sizeBytes = authorityBytes.size.toLong(),
                        sha256 = sha256(authorityBytes),
                    )
                    RunManifestVerifier.OUTCOME_MANIFEST_PATH -> entry.copy(
                        sizeBytes = outcomeBytes.size.toLong(),
                        sha256 = sha256(outcomeBytes),
                    )
                    else -> entry
                }
            },
        )
        files["manifest.json"] = strictJson.encodeToString(
            RunManifest.serializer(),
            forgedRunManifest,
        ).encodeToByteArray()

        val verification = RunManifestVerifier.verify(zip(files), expectedRunId = RUN_ID)

        assertFalse(verification.isValid)
        assertTrue(
            verification.errors.any {
                it.contains("differs from the receiver decision package")
            },
            verification.errors.joinToString(),
        )
    }

    @Test
    fun `accepted v5 rejects restamped omission of canonical support authority`() {
        val archive = service.archiveAccepted(
            acceptedRequest(fixture(RunOutcomeClass.ACCEPTED_DECISION_PACKAGE))
        )

        listOf(
            "accepted/run-metrics-v1.json",
            "accepted/publication-history.json",
            MechanismActivationReportV1.ARCHIVE_PATH,
        ).forEach { removedPath ->
            val verification = RunManifestVerifier.verify(
                removeAcceptedPayloadAndRestamp(archive.bytes, removedPath),
                expectedRunId = RUN_ID,
            )

            assertFalse(verification.isValid, "Missing $removedPath was accepted")
            assertTrue(
                verification.errors.any {
                    it.contains("class separation") ||
                        it.contains("lacks $removedPath") ||
                        it.contains("requires exactly one canonical $removedPath")
                },
                "$removedPath: ${verification.errors.joinToString()}",
            )
        }
    }

    @Test
    fun `accepted archive rejects invalid mandatory support authority`() {
        val request = acceptedRequest(fixture(RunOutcomeClass.ACCEPTED_DECISION_PACKAGE))

        assertFailsWith<IllegalArgumentException> {
            service.archiveAccepted(request.copy(publicationHistory = emptyList()))
        }
        assertFailsWith<IllegalArgumentException> {
            service.archiveAccepted(
                request.copy(metricsBytes = request.metricsBytes + byteArrayOf(0))
            )
        }
        assertFailsWith<IllegalArgumentException> {
            service.archiveAccepted(
                request.copy(
                    mechanismActivationReport =
                        mechanismReport(RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE)
                )
            )
        }
    }

    @Test
    fun `no-candidate recovery publishes every eligible group without ranking`() {
        val fixture = fixture(RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE)
        val sourceSet = RecoverySourceSet.seal(
            RUN_ID,
            listOf(
                RecoverySourceCandidate("group-1", hash("snapshot-1"), setOf("evidence-1")),
                RecoverySourceCandidate("group-0", hash("snapshot-0"), setOf("evidence-0")),
            ),
        )
        val history = recoveryHistory(RUN_ID)
        val metricsBytes = strictJson.encodeToString(
            RunMetricsV1.serializer(),
            acceptedMetrics(RUN_ID),
        ).encodeToByteArray()

        val archive = service.archiveProvisional(
            ProvisionalOutcomeArchiveRequest(
                runId = RUN_ID,
                admissionDistributionHash = hash("admission"),
                startingFieldHash = hash("starting"),
                workerAtlasHash = hash("worker"),
                graderAtlasHash = hash("grader"),
                sealedTestSuiteHash = hash("tests"),
                lifecycleReconciliationHash = hash("recovery-lifecycle"),
                metricsHash = sha256(metricsBytes),
                metricsBytes = metricsBytes,
                blueprint = fixture.blueprint,
                decisionPackage = fixture.packageValue,
                recoverySourceSets = listOf(sourceSet),
                eligibleGroupIds = setOf("group-0", "group-1"),
                recoveryPublicationHistory = history,
                archivedAtMs = 1_000L,
            )
        )

        val authority = service.verifyProvisional(archive.bytes)
        val presentation = RunOutcomeBundleInspector.inspect(archive.bytes)
        assertSourceSidecars(archive)

        assertEquals(
            mapOf(
                "group-0" to "NON_ACCEPTED_SOURCE_A",
                "group-1" to "NON_ACCEPTED_SOURCE_B",
            ),
            authority.recoverySourceSets.single().sourceLabels,
        )
        assertEquals(null, authority.decisionPackage.acceptedAuthority)
        assertEquals(RUN_ID, assertNotNull(presentation.metrics).runId)
        assertFalse(unzip(archive.bytes).keys.any { it.contains("final-reference") })
        assertTrue("recovery/run-metrics-v1.json" in unzip(archive.bytes))
    }

    @Test
    fun `provider-produced recovered notebook survives provisional archive with source authority`() {
        val fixture = fixture(RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE)
        val sourceSet = RecoverySourceSet.seal(
            RUN_ID,
            listOf(
                RecoverySourceCandidate("group-0", hash("snapshot"), setOf("evidence"))
            ),
        )
        val notebook = GeneratedOutputFile(
            relativePath = "submission.ipynb",
            mediaType = "application/x-ipynb+json",
            content = """
                {
                  "cells": [],
                  "metadata": {},
                  "nbformat": 4,
                  "nbformat_minor": 5
                }
            """.trimIndent(),
            sourceGroupId = "group-0",
            sourceAgentId = "recovery-writer",
            sourceSubmissionId = "submission-recovery-001",
        )

        val archive = service.archiveProvisional(
            provisionalRequest(fixture, sourceSet).copy(
                recoveredOutput = notebook,
                recoveredOutputSource = RecoveredDeliverableSource.RECOVERY_WRITER_OUTPUT,
                recoveredOutputRoundIndex = 5,
            )
        )

        val authority = service.verifyProvisional(archive.bytes)
        val recovered = assertNotNull(authority.recoveredDeliverables.singleOrNull())
        val files = unzip(archive.bytes)
        val recoveredPath = "outputs/recovered/submission.ipynb"

        assertEquals(recoveredPath, recoveredPathFor(recovered))
        assertEquals("submission.ipynb", recovered.relativePath)
        assertEquals(RecoveredDeliverableSource.RECOVERY_WRITER_OUTPUT, recovered.source)
        assertEquals("group-0", recovered.sourceGroupId)
        assertEquals(5, recovered.sourceRoundIndex)
        assertEquals("submission-recovery-001", recovered.sourceSubmissionId)
        assertEquals(notebook.contentHash, recovered.contentHash)
        assertEquals(notebook.content, files.getValue(recoveredPath).decodeToString())
        assertEquals(notebook.contentHash, sha256(files.getValue(recoveredPath)))
        assertEquals(
            "application/x-ipynb+json",
            authority.outcomeManifest.entries.single { it.path == recoveredPath }.mediaType,
        )
        val terminal = assertNotNull(authority.terminalOutcome)
        assertEquals(
            ExecutionCompletionStatus.COMPLETED,
            terminal.executionStatus,
        )
        assertEquals(AcceptanceStatus.PROVISIONAL, terminal.acceptanceStatus)
        assertEquals(recoveredPath, terminal.primaryArtifactRef?.relativePath)
        assertEquals(
            PrimaryArtifactValidationStatus.PASS,
            terminal.primaryArtifactValidationStatus,
        )
        val presentation = RunOutcomeBundleInspector.inspect(archive.bytes)
        assertEquals(ExecutionCompletionStatus.COMPLETED, presentation.executionStatus)
        assertEquals(AcceptanceStatus.PROVISIONAL, presentation.acceptanceStatus)
        assertEquals(recoveredPath, presentation.primaryArtifactPath)
    }

    @Test
    fun `active cost reservation blocks provisional archive closure`() =
        kotlinx.coroutines.test.runTest {
            val fixture = fixture(RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE)
            val sourceSet = RecoverySourceSet.seal(
                RUN_ID,
                listOf(
                    RecoverySourceCandidate(
                        "group-0",
                        hash("snapshot"),
                        setOf("evidence")
                    )
                ),
            )
            val archive = service.archiveProvisional(provisionalRequest(fixture, sourceSet))
            val root = createTempDirectory("provisional-active-cost-").toFile()
            val factory = SubstrateDatabaseFactory(root)
            CostLedgerRepository(factory.get(RUN_ID, "__cost_ledger__")).reserve(
                CostLedgerEntry(
                    entryId = "cost-reserved-provisional",
                    reservationId = "reservation-provisional",
                    runId = RUN_ID,
                    groupId = null,
                    agentId = "recovery-writer",
                    subjectType = CostSubjectType.SIMULATION,
                    subjectId = "recovery-publication-race",
                    kind = CostLedgerEntryKind.RESERVED,
                    reservedMaximumUsd = 0.5,
                    occurredAt = NOW,
                ).withComputedHash()
            )

            val failure = assertFailsWith<IllegalStateException> {
                RunArchiveService(factory).writeOutcomeArchive(RUN_ID, archive)
            }

            assertTrue(failure.message.orEmpty().contains("active cost reservation"))
            assertFalse(
                java.io.File(
                    factory.dbFilePath(RUN_ID, "group-0").parentFile,
                    "results/recovery-results.zip"
                ).exists()
            )
        }

    @Test
    fun `startup failure emits diagnostic-only archive authority`() {
        val fixture = fixture(RunOutcomeClass.STARTUP_DIAGNOSTIC_PACKAGE)
        val diagnostic = StartupDiagnosticPackageV1(
            runId = RUN_ID,
            failurePhase = StartupFailurePhase.COMPILATION,
            admissionDistributionHash = hash("admission"),
            startingFieldHash = hash("starting"),
            candidateAuthorityHashes = listOf(hash("candidate")),
            declaredCardinality = 4,
            compiledCardinality = 2,
            preflightCardinality = 0,
            issues = listOf(
                StartupDiagnosticIssue(
                    code = "COMPILE_FIELD_REJECTED",
                    publicMessage = "Compiled field did not satisfy the public contract.",
                    remediation = "Correct the declared field and retry admission.",
                    authorityHash = hash("issue"),
                )
            ),
            generatedAt = NOW,
        ).withComputedHash()

        val archive = service.archiveStartupDiagnostic(
            StartupDiagnosticArchiveRequest(
                runId = RUN_ID,
                admissionDistributionHash = hash("admission"),
                startingFieldHash = hash("starting"),
                lifecycleReconciliationHash = hash("no-runtime-lifecycle"),
                blueprint = fixture.blueprint,
                decisionPackage = fixture.packageValue,
                diagnosticPackage = diagnostic,
                publicationHistory = startupHistory(RUN_ID),
                mechanismActivationReport =
                    mechanismReport(RunOutcomeClass.STARTUP_DIAGNOSTIC_PACKAGE),
                archivedAtMs = 2_000L,
            )
        )

        val authority = service.verifyStartup(archive.bytes)
        val paths = unzip(archive.bytes).keys
        assertSourceSidecars(archive)
        val presentation = RunOutcomeBundleInspector.inspect(archive.bytes)

        assertTrue(authority.hasValidHash())
        assertTrue("diagnostic/startup-diagnostic-package-v1.json" in paths)
        assertTrue(MechanismActivationReportV1.ARCHIVE_PATH in paths)
        assertEquals(
            MechanismActivationState.ACTIVATED_WITH_DOWNSTREAM_USE,
            presentation.mechanismActivationReport
                ?.mechanisms
                ?.single { it.mechanismId == "publication-barrier" }
                ?.state,
        )
        assertFalse(paths.any { it.contains("final-reference") || it.endsWith("run-metrics-v1.json") })
    }

    @Test
    fun `startup verification rejects a restamped detached diagnostic package`() {
        val fixture = fixture(RunOutcomeClass.STARTUP_DIAGNOSTIC_PACKAGE)
        val diagnostic = StartupDiagnosticPackageV1(
            runId = RUN_ID,
            failurePhase = StartupFailurePhase.ADMISSION,
            admissionDistributionHash = hash("admission"),
            startingFieldHash = null,
            candidateAuthorityHashes = emptyList(),
            declaredCardinality = 0,
            compiledCardinality = 0,
            preflightCardinality = 0,
            issues = listOf(
                StartupDiagnosticIssue(
                    code = "ADMISSION_REJECTED",
                    publicMessage = "Startup admission was rejected.",
                    remediation = "Correct the public run request.",
                    authorityHash = hash("startup-issue"),
                )
            ),
            generatedAt = NOW,
        ).withComputedHash()
        val archive = service.archiveStartupDiagnostic(
            StartupDiagnosticArchiveRequest(
                runId = RUN_ID,
                admissionDistributionHash = hash("admission"),
                startingFieldHash = null,
                lifecycleReconciliationHash = hash("no-runtime-lifecycle"),
                blueprint = fixture.blueprint,
                decisionPackage = fixture.packageValue,
                diagnosticPackage = diagnostic,
                publicationHistory = startupHistory(RUN_ID),
                mechanismActivationReport =
                    mechanismReport(RunOutcomeClass.STARTUP_DIAGNOSTIC_PACKAGE),
                archivedAtMs = 2_000L,
            )
        )
        val files = unzip(archive.bytes).toMutableMap()
        val diagnosticPath = "diagnostic/startup-diagnostic-package-v1.json"
        val forgedDiagnostic = diagnostic.copy(
            runId = "foreign-run",
            packageHash = "",
        ).withComputedHash()
        val forgedDiagnosticBytes = strictJson.encodeToString(
            StartupDiagnosticPackageV1.serializer(),
            forgedDiagnostic,
        ).encodeToByteArray()
        files[diagnosticPath] = forgedDiagnosticBytes

        val outcome = strictJson.decodeFromString(
            RunOutcomeManifestV1.serializer(),
            files.getValue(RunManifestVerifier.OUTCOME_MANIFEST_PATH).decodeToString(),
        )
        val forgedOutcome = outcome.copy(
            entries = outcome.entries.map { entry ->
                if (entry.path == diagnosticPath) {
                    entry.copy(
                        sizeBytes = forgedDiagnosticBytes.size.toLong(),
                        sha256 = sha256(forgedDiagnosticBytes),
                    )
                } else {
                    entry
                }
            },
            manifestHash = "",
        ).withComputedHash()
        files[RunManifestVerifier.OUTCOME_MANIFEST_PATH] = strictJson.encodeToString(
            RunOutcomeManifestV1.serializer(),
            forgedOutcome,
        ).encodeToByteArray()
        val authority = strictJson.decodeFromString(
            StartupDiagnosticArchiveAuthorityV1.serializer(),
            files.getValue(StartupDiagnosticArchiveAuthorityV1.PATH).decodeToString(),
        )
        files[StartupDiagnosticArchiveAuthorityV1.PATH] = strictJson.encodeToString(
            StartupDiagnosticArchiveAuthorityV1.serializer(),
            authority.copy(
                outcomeManifestHash = forgedOutcome.manifestHash,
                authorityHash = "",
            ).withComputedHash(),
        ).encodeToByteArray()

        val failure = assertFailsWith<IllegalArgumentException> {
            service.verifyStartup(zip(files))
        }

        assertTrue(
            failure.message.orEmpty().contains("detached from manifest authority")
        )
    }

    @Test
    fun `recovery rejects private content accepted authority leaks and tampering`() {
        val provisional = fixture(
            RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE,
            narrative = "Public summary SECRET_PRIVATE_FIXTURE",
        )
        val sourceSet = RecoverySourceSet.seal(
            RUN_ID,
            listOf(RecoverySourceCandidate("group-0", hash("snapshot"), setOf("evidence"))),
        )
        assertFailsWith<IllegalArgumentException> {
            service.archiveProvisional(
                provisionalRequest(
                    provisional,
                    sourceSet,
                    forbiddenPrivateFragments = setOf("SECRET_PRIVATE_FIXTURE"),
                )
            )
        }

        val accepted = fixture(RunOutcomeClass.ACCEPTED_DECISION_PACKAGE)
        assertFailsWith<IllegalArgumentException> {
            service.archiveProvisional(provisionalRequest(accepted, sourceSet))
        }

        val clean = fixture(RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE)
        assertFailsWith<IllegalArgumentException> {
            service.archiveProvisional(
                provisionalRequest(clean, sourceSet).copy(
                    mechanismActivationReport =
                        mechanismReport(RunOutcomeClass.ACCEPTED_DECISION_PACKAGE)
                )
            )
        }
        val archive = service.archiveProvisional(provisionalRequest(clean, sourceSet))
        val entries = unzip(archive.bytes).toMutableMap()
        val packagePath = entries.keys.first { it == "provisional-decision-package.json" }
        entries[packagePath] = entries.getValue(packagePath) + "tampered".encodeToByteArray()

        assertFailsWith<IllegalArgumentException> {
            service.verifyProvisional(zip(entries))
        }

        val archiveWithReport = service.archiveProvisional(
            provisionalRequest(clean, sourceSet).copy(
                mechanismActivationReport =
                    mechanismReport(RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE)
            )
        )
        val reportTamper = unzip(archiveWithReport.bytes).toMutableMap()
        reportTamper[MechanismActivationReportV1.ARCHIVE_PATH] =
            reportTamper.getValue(MechanismActivationReportV1.ARCHIVE_PATH) +
                "tampered".encodeToByteArray()
        assertFailsWith<IllegalArgumentException> {
            service.verifyProvisional(zip(reportTamper))
        }
    }

    private fun assertSourceSidecars(archive: OutcomeArchive) {
        val files = unzip(archive.bytes)
        val sidecars = archive.outcomeManifest.entries.filter { it.path.startsWith("source-checks/") }
        assertTrue(sidecars.isNotEmpty())
        sidecars.forEach { entry ->
            val bytes = files.getValue(entry.path)
            assertEquals(entry.sha256, sha256(bytes))
            assertEquals(entry.sizeBytes, bytes.size.toLong())
            assertTrue(bytes.decodeToString().contains("Not semantic proof"))
        }
        archive.outcomeManifest.entries.filter {
            it.category in setOf(
                com.achulkov.evomolior.core.outcome.RunOutcomeManifestEntryCategory.RENDERED_DELIVERABLE,
                com.achulkov.evomolior.core.outcome.RunOutcomeManifestEntryCategory.RECEIVER_DECISION_PACKAGE,
            ) && ReportSourceSidecars.isReport(it.path, it.mediaType)
        }.forEach { report ->
            val sidecar = files.getValue(ReportSourceSidecars.pathFor(RUN_ID, report.path)).decodeToString()
            assertTrue(sidecar.contains(report.sha256))
        }
    }

    private fun provisionalRequest(
        fixture: Fixture,
        sourceSet: RecoverySourceSet,
        forbiddenPrivateFragments: Set<String> = emptySet(),
    ): ProvisionalOutcomeArchiveRequest {
        val metricsBytes = strictJson.encodeToString(
            RunMetricsV1.serializer(),
            acceptedMetrics(RUN_ID),
        ).encodeToByteArray()
        return ProvisionalOutcomeArchiveRequest(
            runId = RUN_ID,
            admissionDistributionHash = hash("admission"),
            startingFieldHash = hash("starting"),
            workerAtlasHash = hash("worker"),
            graderAtlasHash = hash("grader"),
            sealedTestSuiteHash = hash("tests"),
            lifecycleReconciliationHash = hash("recovery-lifecycle"),
            metricsHash = sha256(metricsBytes),
            metricsBytes = metricsBytes,
            blueprint = fixture.blueprint,
            decisionPackage = fixture.packageValue,
            recoverySourceSets = listOf(sourceSet),
            eligibleGroupIds = setOf("group-0"),
            recoveryPublicationHistory = recoveryHistory(RUN_ID),
            archivedAtMs = 3_000L,
            forbiddenPrivateFragments = forbiddenPrivateFragments,
        )
    }

    private fun recoveredPathFor(
        recovered: com.achulkov.evomolior.api.service.RecoveredDeliverableRecord,
    ): String = "outputs/recovered/${recovered.relativePath}"

    private fun acceptedRequest(fixture: Fixture): AcceptedOutcomeArchiveRequest {
        val metricsBytes = strictJson.encodeToString(
            RunMetricsV1.serializer(),
            acceptedMetrics(RUN_ID),
        ).encodeToByteArray()
        val publicationHistory = acceptedHistory(RUN_ID)
        val publicationHistoryBytes = strictJson.encodeToString(
            ListSerializer(PublicationStateEvent.serializer()),
            publicationHistory,
        ).encodeToByteArray()
        return AcceptedOutcomeArchiveRequest(
            runId = RUN_ID,
            objective = "Choose an implementation",
            groupCount = 2,
            generationCeiling = 4,
            admissionDistributionHash = hash("admission"),
            startingFieldHash = hash("starting"),
            workerAtlasHash = hash("worker"),
            graderAtlasHash = hash("grader"),
            sealedTestSuiteHash = hash("tests"),
            lifecycleReconciliationHash = hash("lifecycle"),
            metricsHash = sha256(metricsBytes),
            metricsBytes = metricsBytes,
            publicationHistoryHash = sha256(publicationHistoryBytes),
            publicationHistory = publicationHistory,
            blueprint = fixture.blueprint,
            decisionPackage = fixture.packageValue,
            mechanismActivationReport =
                mechanismReport(RunOutcomeClass.ACCEPTED_DECISION_PACKAGE),
        )
    }

    private fun acceptanceAdmissionBytes(
        profile: AcceptanceProfile,
        distributionHash: String,
    ): ByteArray = strictJson.encodeToString(
        AcceptanceAdmissionArtifact.serializer(),
        AcceptanceAdmissionArtifact(
            runId = RUN_ID,
            acceptanceProfile = profile,
            admittedAtUtc = "2026-07-26T00:00:00Z",
            ownerPid = 1L,
            distributionManifestSha256 = distributionHash,
            sourceRevision = "test-revision",
            configurationSha256 = hash("configuration"),
            modelAssignmentsSha256 = hash("models"),
            jvmVersion = "17",
            javaHome = "/test/jdk",
            javaExecutableSha256 = hash("java"),
            javaReleaseSha256 = hash("java-release"),
            javaModulesSha256 = hash("java-modules"),
            codexCliPath = "/test/codex",
            codexCliSha256 = hash("codex"),
            codexCliVersion = "test",
            estimatedMaximumRunArchiveBytes = 1L,
            requiredFreeBytes = 1L,
            checks = listOf(AcceptanceAdmissionCheck("fixture", "verified")),
        )
    ).encodeToByteArray()

    private fun acceptedMetrics(runId: String) = RunMetricsV1(
        runId = runId,
        observations = RunMetricCatalog.orderedMetricIds.map { metricId ->
            RunMetricObservation(
                metricId = metricId,
                status = MetricAvailability.UNAVAILABLE,
                unit = null,
                evidenceRefs = listOf("authority/$metricId"),
                unavailableReason = "Fixture evidence is unavailable.",
            )
        },
    )

    private fun acceptedHistory(runId: String): List<PublicationStateEvent> {
        var previous: PublicationStateEvent? = null
        return PublicationState.entries.mapIndexed { index, state ->
            PublicationStateEvent(
                eventId = "accepted-$index",
                runId = runId,
                state = state,
                sequence = index,
                previousEventHash = previous?.eventHash,
                authorityReferenceIds = setOf("authority-$index"),
                occurredAt = NOW,
            ).withComputedHash().also { previous = it }
        }
    }

    private fun removeAcceptedPayloadAndRestamp(
        archiveBytes: ByteArray,
        removedPath: String,
    ): ByteArray {
        val files = unzip(archiveBytes).toMutableMap()
        files.remove(removedPath)
        val outcome = strictJson.decodeFromString(
            RunOutcomeManifestV1.serializer(),
            files.getValue(RunManifestVerifier.OUTCOME_MANIFEST_PATH).decodeToString(),
        )
        val restampedOutcome = outcome.copy(
            entries = outcome.entries.filterNot { it.path == removedPath },
            manifestHash = "",
        ).withComputedHash()
        val outcomeBytes = strictJson.encodeToString(
            RunOutcomeManifestV1.serializer(),
            restampedOutcome,
        ).encodeToByteArray()
        files[RunManifestVerifier.OUTCOME_MANIFEST_PATH] = outcomeBytes

        val runManifest = strictJson.decodeFromString(
            RunManifest.serializer(),
            files.getValue("manifest.json").decodeToString(),
        )
        files["manifest.json"] = strictJson.encodeToString(
            RunManifest.serializer(),
            runManifest.copy(
                outcomeManifestSha256 = sha256(outcomeBytes),
                entries = runManifest.entries
                    .filterNot { it.path == removedPath }
                    .map { entry ->
                        if (entry.path == RunManifestVerifier.OUTCOME_MANIFEST_PATH) {
                            entry.copy(
                                sizeBytes = outcomeBytes.size.toLong(),
                                sha256 = sha256(outcomeBytes),
                            )
                        } else {
                            entry
                        }
                    },
            ),
        ).encodeToByteArray()
        return zip(files)
    }

    private fun fixture(
        outcomeClass: RunOutcomeClass,
        narrative: String = "Public supplied summary.",
    ): Fixture {
        val blueprint = DeliverableBlueprint(
            blueprintId = "blueprint-1",
            receiverContractHash = hash("receiver"),
            audience = "Operator",
            decisionOrUse = "Choose a safe next action",
            primaryFormat = DeliverableFormat.MARKDOWN,
            companionFormats = setOf(DeliverableFormat.JSON),
            componentSpecs = listOf(
                DeliverableComponentSpec(
                    componentId = "summary",
                    kind = DeliverableComponentKind.EXECUTIVE_SUMMARY,
                    required = true,
                    minimumAuthority = ComponentAuthorityClass.ACCEPTED,
                ),
                DeliverableComponentSpec(
                    componentId = "risks",
                    kind = DeliverableComponentKind.RISK_REGISTER,
                    required = true,
                    minimumAuthority = ComponentAuthorityClass.ACCEPTED,
                ),
            ),
        ).withComputedHash()
        val evidence = listOf(
            evidence("summary-evidence"),
            evidence("risks-evidence"),
        )
        val supplied = when (outcomeClass) {
            RunOutcomeClass.ACCEPTED_DECISION_PACKAGE -> listOf(
                component(
                    "summary",
                    DeliverableComponentKind.EXECUTIVE_SUMMARY,
                    ComponentAuthorityClass.ACCEPTED,
                    "summary-evidence",
                    narrative,
                ),
                component(
                    "risks",
                    DeliverableComponentKind.RISK_REGISTER,
                    ComponentAuthorityClass.ACCEPTED,
                    "risks-evidence",
                    "Accepted public risk register.",
                ),
            )
            RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE -> listOf(
                component(
                    "summary",
                    DeliverableComponentKind.EXECUTIVE_SUMMARY,
                    ComponentAuthorityClass.PROVISIONAL,
                    "summary-evidence",
                    narrative,
                )
            )
            RunOutcomeClass.STARTUP_DIAGNOSTIC_PACKAGE -> emptyList()
        }
        val packageValue = DeterministicDeliverableAssembler.assemble(
            DeliverableAssemblyRequest(
                blueprint = blueprint,
                packageId = "package-${outcomeClass.name.lowercase()}",
                runId = RUN_ID,
                outcomeClass = outcomeClass,
                suppliedComponents = supplied,
                evidenceLedger = evidence,
                knownFailures = if (
                    outcomeClass == RunOutcomeClass.ACCEPTED_DECISION_PACKAGE
                ) {
                    emptyList()
                } else {
                    listOf(
                        FailedAcceptanceGate(
                            gateId = "mandatory-gate",
                            code = "NOT_ACCEPTED",
                            publicMessage = "The mandatory public gate did not pass.",
                            authorityHash = hash("gate"),
                        )
                    )
                },
                acceptedAuthority = if (
                    outcomeClass == RunOutcomeClass.ACCEPTED_DECISION_PACKAGE
                ) {
                    AcceptedDeliverableAuthority(
                        winnerAuthorityId = "winner-1",
                        winnerAuthorityHash = hash("winner"),
                        sourceAuthorityIds = setOf("source-1"),
                        writerAuthorityId = "writer-1",
                        writerOutputHash = hash("writer-output"),
                    )
                } else {
                    null
                },
                generatedAt = NOW,
            )
        )
        return Fixture(blueprint, packageValue)
    }

    private fun component(
        id: String,
        kind: DeliverableComponentKind,
        authority: ComponentAuthorityClass,
        evidenceId: String,
        narrative: String,
    ) = SuppliedDeliverableComponent(
        componentId = id,
        kind = kind,
        title = id.replaceFirstChar { it.uppercase() },
        authorityClass = authority,
        blocks = listOf(
            DeliverableSemanticBlock.Narrative(
                narrative,
                DeliverableContentOrigin.DETERMINISTIC_SUPPLIED,
            )
        ),
        provenance = DeliverableComponentProvenance(evidenceIds = setOf(evidenceId)),
    )

    private fun evidence(id: String) = DeliverableEvidenceLink(
        evidenceId = id,
        evidenceHash = hash(id),
        authorityClass = ComponentAuthorityClass.ACCEPTED,
        disclosure = DeliverableEvidenceDisclosure.PUBLIC_COMMITTED,
        publicSummary = "Public evidence for $id.",
    )

    private fun recoveryHistory(runId: String): List<RecoveryPublicationStateEvent> {
        var previous: RecoveryPublicationStateEvent? = null
        return RecoveryPublicationState.entries.mapIndexed { index, state ->
            RecoveryPublicationStateEvent(
                eventId = "recovery-$index",
                runId = runId,
                state = state,
                sequence = index,
                previousEventHash = previous?.eventHash,
                authorityReferenceIds = setOf("authority-$index"),
                occurredAt = NOW,
            ).withComputedHash().also { previous = it }
        }
    }

    private fun startupHistory(runId: String): List<StartupDiagnosticStateEvent> {
        var previous: StartupDiagnosticStateEvent? = null
        return StartupDiagnosticState.entries.mapIndexed { index, state ->
            StartupDiagnosticStateEvent(
                eventId = "startup-$index",
                runId = runId,
                state = state,
                sequence = index,
                previousEventHash = previous?.eventHash,
                authorityReferenceIds = setOf("authority-$index"),
                occurredAt = NOW,
            ).withComputedHash().also { previous = it }
        }
    }

    private fun mechanismReport(
        outcomeClass: RunOutcomeClass,
    ): MechanismActivationReportV1 = MechanismActivationReportV1(
        runId = RUN_ID,
        outcomeClass = outcomeClass,
        mechanisms = MechanismActivationCatalog.orderedMechanismIds.map { mechanismId ->
            MechanismActivation(
                mechanismId = mechanismId,
                state = if (mechanismId == "publication-barrier") {
                    MechanismActivationState.ACTIVATED_WITH_DOWNSTREAM_USE
                } else {
                    MechanismActivationState.NOT_CONFIGURED
                },
                authorityIds = listOf("authority/$mechanismId"),
            )
        },
        generatedAt = NOW,
    ).withComputedHash()

    private fun hash(value: String): String = DeliverableHashing.hashText(value)

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return entries
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                entries.toSortedMap().forEach { (path, bytes) ->
                    zip.putNextEntry(ZipEntry(path).apply { time = 315_532_800_000L })
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }

    private data class Fixture(
        val blueprint: DeliverableBlueprint,
        val packageValue: com.achulkov.evomolior.core.deliverable.ReceiverDecisionPackage,
    )

    companion object {
        private const val RUN_ID = "run-outcome"
        private val NOW = Instant.parse("2026-07-24T12:00:00Z")
        private val strictJson = Json {
            encodeDefaults = true
            explicitNulls = true
        }
    }
}
