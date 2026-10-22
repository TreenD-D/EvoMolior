package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.atlas.FinalOutputSpec
import com.achulkov.evomolior.core.atlas.PhysicalDimension
import com.achulkov.evomolior.core.atlas.ToolOpportunityType
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.config.CouncilExecutionPurpose
import com.achulkov.evomolior.core.contract.GroupTopologyKind
import com.achulkov.evomolior.core.council.CompiledFieldState
import com.achulkov.evomolior.core.council.ExecutionEnvelope
import com.achulkov.evomolior.core.council.GenerationCreditPolicy
import com.achulkov.evomolior.core.council.InclusiveIntRange
import com.achulkov.evomolior.core.council.ModelCapabilityCard
import com.achulkov.evomolior.core.council.QuantitativeToolFieldLocation
import com.achulkov.evomolior.core.council.QuantitativeToolFieldObligation
import com.achulkov.evomolior.core.council.QuantitativeToolFieldShape
import com.achulkov.evomolior.core.council.QuantitativeToolObligation
import com.achulkov.evomolior.core.council.SandboxCapabilitySummary
import com.achulkov.evomolior.core.council.SourceAccessPolicySummary
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.repair.AggregationSemantics
import com.achulkov.evomolior.core.repair.QuantitySpec
import com.achulkov.evomolior.core.repair.TimeBasis
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Contract coverage for the strict hash-bound compiled Council v3 manifests. */
class CompiledCouncilManifestV3Test {
    @Test
    fun `candidate codec canonically round trips verified authority`() {
        val manifest = candidate()

        val encoded = CompiledCouncilCandidateManifestV3Codec.encode(manifest)
        val decoded = CompiledCouncilCandidateManifestV3Codec.decodeVerified(encoded)

        assertEquals(manifest, decoded)
        assertEquals(encoded, CompiledCouncilCandidateManifestV3Codec.encode(decoded))
        assertTrue(decoded.hasValidHash())
    }

    @Test
    fun `candidate codec rejects unknown and v2 schema fields`() {
        val encoded = CompiledCouncilCandidateManifestV3Codec.encode(candidate())

        assertFailsWith<Exception> {
            CompiledCouncilCandidateManifestV3Codec.decodeVerified(
                encoded.dropLast(1) + ",\"unknown\":true}"
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CompiledCouncilCandidateManifestV3Codec.decodeVerified(
                encoded.replace(
                    CompiledCouncilCandidateManifestV3.SCHEMA_VERSION,
                    "compiled-council-manifest-v2"
                )
            )
        }
        assertFailsWith<Exception> {
            CompiledCouncilCandidateManifestV3Codec.decodeVerified(
                JsonObject(
                    Json.parseToJsonElement(encoded).jsonObject -
                        "validationContext"
                ).toString()
            )
        }
    }

    @Test
    fun `candidate rejects tampered sealed authority fields Atlases and self hash`() {
        val manifest = candidate()

        assertInvalid(manifest.copy(privateSuite = manifest.privateSuite.copy(suiteHash = "0".repeat(64))))
        assertInvalid(
            manifest.copy(
                validationContext = manifest.validationContext.copy(atlasHash = "1".repeat(64))
            )
        )
        assertInvalid(
            manifest.copy(
                workerCommitmentView = manifest.workerCommitmentView.copy(
                    sourceSuiteHash = "2".repeat(64)
                )
            )
        )
        assertInvalid(
            manifest.copy(
                workerStartingField = manifest.workerStartingField.copy(
                    norms = manifest.workerStartingField.norms + " tampered"
                )
            )
        )
        assertInvalid(manifest.copy(workerAtlas = manifest.workerAtlas.copy(atlasHash = "3".repeat(64))))
        assertInvalid(manifest.copy(graderAtlas = manifest.graderAtlas.copy(atlasHash = "4".repeat(64))))
        assertInvalid(
            manifest.copy(
                graderAtlas = manifest.graderAtlas.copy(workerAtlasHash = "5".repeat(64))
            )
        )
        val uncommittedBlueprintSeal = requireNotNull(manifest.workerStartingField.seal)
            .copy(deliverableBlueprintHash = "f".repeat(64))
        assertInvalid(
            manifest.copy(
                workerStartingField =
                    manifest.workerStartingField.copy(seal = uncommittedBlueprintSeal),
                graderStartingField =
                    manifest.graderStartingField.copy(seal = uncommittedBlueprintSeal),
            )
        )
        assertFalse(manifest.copy(manifestHash = "6".repeat(64)).hasValidHash())
    }

    @Test
    fun `active pointer rejects tampering and non-active state`() {
        val active = ActiveCompiledCouncilManifestV3(
            runId = "manifest-run",
            candidateArchiveEntryId = "archive-entry",
            candidateManifestHash = candidate().manifestHash,
            activatedAtEpochMs = 1L
        ).withComputedHash()

        val encoded = ActiveCompiledCouncilManifestV3Codec.encode(active)
        assertEquals(active, ActiveCompiledCouncilManifestV3Codec.decodeVerified(encoded))
        assertFalse(active.copy(candidateArchiveEntryId = "other").hasValidHash())
        assertFalse(
            active.copy(state = CompiledFieldState.EXECUTABILITY_VERIFIED).hasValidHash()
        )
        assertFailsWith<Exception> {
            ActiveCompiledCouncilManifestV3Codec.decodeVerified(
                encoded.dropLast(1) + ",\"unknown\":true}"
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ActiveCompiledCouncilManifestV3Codec.decodeVerified(
                encoded.replace(
                    ActiveCompiledCouncilManifestV3.SCHEMA_VERSION,
                    "compiled-council-active-v2"
                )
            )
        }
    }

    @Test
    fun `resume intent rejects quantitative obligation absent from sealed manifest`() {
        val candidate = candidate()
        val obligation = QuantitativeToolObligation(
            horizonTimeBasis = TimeBasis.HOUR,
            horizonIntervalCount = 72,
            allowedOpportunityTypes = setOf(ToolOpportunityType.CALCULATION),
            requiredFields = listOf(
                QuantitativeToolFieldObligation(
                    roleId = "hourly_power",
                    fieldName = "hourly_power_kw",
                    location = QuantitativeToolFieldLocation.OUTPUT,
                    shape = QuantitativeToolFieldShape.HORIZON_SERIES,
                    quantitySpec = QuantitySpec(
                        dimension = PhysicalDimension(mapOf("power" to 1)),
                        unitId = "kW",
                        scaleToSi = 1_000.0,
                        aggregation = AggregationSemantics.AVERAGE_OVER_INTERVALS,
                        timeBasis = TimeBasis.HOUR
                    )
                )
            )
        )

        requireCompiledCouncilRecoveryIntent(
            candidate = candidate,
            expectedCouncilExecutionPurpose = CouncilExecutionPurpose.STANDARD,
            expectedAcceptanceProfile = AcceptanceProfile.NORMAL,
            expectedQuantitativeToolObligation = null
        )
        val failure = assertFailsWith<IllegalArgumentException> {
            requireCompiledCouncilRecoveryIntent(
                candidate = candidate,
                expectedCouncilExecutionPurpose = CouncilExecutionPurpose.STANDARD,
                expectedAcceptanceProfile = AcceptanceProfile.NORMAL,
                expectedQuantitativeToolObligation = obligation
            )
        }

        assertTrue(
            failure.message.orEmpty().contains("quantitative-tool obligation")
        )
    }

    private fun assertInvalid(value: CompiledCouncilCandidateManifestV3) {
        assertFalse(value.withComputedHash().hasValidHash())
    }

    private fun candidate(): CompiledCouncilCandidateManifestV3 {
        val runId = "manifest-run"
        val compiled = compiledCouncilTestFixture(
            runId = runId,
            objective = "Verify the compiled Council manifest.",
            envelope = envelope()
        )
        return CompiledCouncilCandidateManifestV3(
            runId = runId,
            workerStartingField = compiled.workerField,
            graderStartingField = compiled.graderField,
            workerAtlas = compiled.atlas,
            graderAtlas = compiled.graderAtlas,
            receiverContract = compiled.receiverContract,
            executionEnvelope = envelope(),
            consistencyReport = compiled.report,
            privateSuite = compiled.sealedTests.privateSuite,
            workerCommitmentView = compiled.sealedTests.workerCommitmentView,
            validationContext = compiled.sealedTests.validationContext
        ).withComputedHash()
    }

    private fun envelope(): ExecutionEnvelope = ExecutionEnvelope(
        requiredWorkerGroupCount = InclusiveIntRange(2, 2),
        requiredGraderGroupCount = InclusiveIntRange(3, 3),
        permittedProfiles = setOf(GroupProfileType.Library, GroupProfileType.Factory),
        availableModels = listOf(
            ModelCapabilityCard(
                modelId = "manifest-model",
                providerId = "test",
                familyId = "manifest",
                structuredOutputReliability = 1.0,
                toolCallReliability = 1.0,
                codeBuildReliability = 1.0,
                longContextSynthesisReliability = 1.0
            )
        ),
        maxAgentsPerGroupPerRound = 2,
        maxRounds = 2,
        perGenerationCreditPolicy = GenerationCreditPolicy(1.0, 2.0),
        allowedTopologyKinds = setOf(GroupTopologyKind.RING),
        sourceAccessPolicy = SourceAccessPolicySummary(networkAccessEnabled = false),
        sandboxCapabilities = SandboxCapabilitySummary(
            maximumToolRuntimeSeconds = 1,
            maximumWorkspaceBytes = 1L
        ),
        outputSpec = FinalOutputSpec(
            relativePath = "answer.md",
            mediaType = "text/markdown",
            requiredSections = setOf("answer")
        ),
        atomicNodeEffortBound = 2
    )
}
