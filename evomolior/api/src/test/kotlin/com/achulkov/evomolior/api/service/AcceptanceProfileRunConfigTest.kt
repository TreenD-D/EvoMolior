package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.api.model.FinalOutputValidationContract
import com.achulkov.evomolior.api.model.JupyterNotebookSourceContract
import com.achulkov.evomolior.api.model.AcceptanceDiagnosticMode
import com.achulkov.evomolior.api.model.RunAutoCompletionPolicy
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.config.CouncilExecutionPurpose
import com.achulkov.evomolior.core.config.CodexReasoningEffort
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.atlas.PhysicalDimension
import com.achulkov.evomolior.core.atlas.ToolOpportunityType
import com.achulkov.evomolior.core.council.QuantitativeToolFieldLocation
import com.achulkov.evomolior.core.council.QuantitativeToolFieldObligation
import com.achulkov.evomolior.core.council.QuantitativeToolFieldShape
import com.achulkov.evomolior.core.council.QuantitativeToolObligation
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.core.repair.AggregationSemantics
import com.achulkov.evomolior.core.repair.QuantitySpec
import com.achulkov.evomolior.core.repair.TimeBasis
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcceptanceProfileRunConfigTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    @Test
    fun `normal config JSON remains profile-free while acceptance profiles propagate`() {
        val normal = config()
        val encoded = json.encodeToString(RunConfig.serializer(), normal)
        assertFalse(encoded.contains("acceptanceProfile"))
        assertFalse(encoded.contains("acceptanceDiagnosticMode"))
        assertTrue(encoded.contains("\"codexReasoningEffort\":\"high\""))
        assertEquals(normal, json.decodeFromString(RunConfig.serializer(), encoded))

        val smoke = acceptanceConfig(AcceptanceProfile.RUNTIME_USABILITY_SMOKE)
        val envelope = CouncilExecutionEnvelopeFactory.create(smoke)
        assertEquals(AcceptanceProfile.RUNTIME_USABILITY_SMOKE, envelope.acceptanceProfile)
        assertEquals(CodexReasoningEffort.HIGH, envelope.codexReasoningEffort)

        val persisted = PersistedRunState.fromRunConfig(
            config = smoke,
            groupIds = listOf("worker-0", "worker-1"),
            graderGroupIds = listOf("grader-0", "grader-1"),
            allowedHosts = emptySet()
        )
        assertEquals(AcceptanceProfile.RUNTIME_USABILITY_SMOKE, persisted.acceptanceProfile)
        assertEquals(
            AcceptanceProfile.RUNTIME_USABILITY_SMOKE,
            persisted.toRunConfig("/tmp/codex").acceptanceProfile
        )
        assertFailsWith<IllegalArgumentException> {
            persisted.requireCompatibleRecoveryIntent(
                smoke.copy(acceptanceProfile = AcceptanceProfile.NORMAL)
            )
        }

        val persistedJson = json.encodeToString(PersistedRunState.serializer(), persisted)
        assertTrue(persistedJson.contains("\"acceptanceProfile\":\"runtime-usability-smoke\""))

        val diagnostic = smoke.copy(
            acceptanceDiagnosticMode =
                AcceptanceDiagnosticMode.FULL_SWEEP_NON_ACCEPTING
        )
        val diagnosticState = PersistedRunState.fromRunConfig(
            config = diagnostic,
            groupIds = listOf("worker-0", "worker-1"),
            graderGroupIds = listOf("grader-0", "grader-1"),
            allowedHosts = emptySet()
        )
        assertEquals(
            AcceptanceDiagnosticMode.FULL_SWEEP_NON_ACCEPTING,
            diagnosticState.toRunConfig("/tmp/codex").acceptanceDiagnosticMode
        )
        assertFailsWith<IllegalArgumentException> {
            diagnosticState.requireCompatibleRecoveryIntent(
                diagnostic.copy(
                    acceptanceDiagnosticMode = AcceptanceDiagnosticMode.STRICT
                )
            )
        }

        val highEffort = diagnostic.copy(codexReasoningEffort = CodexReasoningEffort.HIGH)
        val highEffortState = PersistedRunState.fromRunConfig(
            config = highEffort,
            groupIds = listOf("worker-0", "worker-1"),
            graderGroupIds = listOf("grader-0", "grader-1"),
            allowedHosts = emptySet()
        )
        assertEquals(
            CodexReasoningEffort.HIGH,
            highEffortState.toRunConfig("/tmp/codex").codexReasoningEffort
        )
        assertTrue(
            json.encodeToString(PersistedRunState.serializer(), highEffortState)
                .contains("\"codexReasoningEffort\":\"high\"")
        )
        assertFailsWith<IllegalArgumentException> {
            highEffortState.requireCompatibleRecoveryIntent(
                highEffort.copy(codexReasoningEffort = CodexReasoningEffort.MEDIUM)
            )
        }
    }

    @Test
    fun `acceptance config rejects incompatible purpose horizon completion and output`() {
        assertFailsWith<IllegalArgumentException> {
            config(rounds = 1).copy(
                acceptanceProfile = AcceptanceProfile.RUNTIME_USABILITY_SMOKE
            )
        }
        assertFailsWith<IllegalArgumentException> {
            config().copy(
                acceptanceDiagnosticMode =
                    AcceptanceDiagnosticMode.FULL_SWEEP_NON_ACCEPTING
            )
        }
        assertFailsWith<IllegalArgumentException> {
            acceptanceConfig(AcceptanceProfile.RUNTIME_USABILITY_SMOKE).copy(rounds = 2)
        }
        assertFailsWith<IllegalArgumentException> {
            acceptanceConfig(AcceptanceProfile.APPENDIX_B_FULL).copy(rounds = 5)
        }
        assertFailsWith<IllegalArgumentException> {
            acceptanceConfig(AcceptanceProfile.APPENDIX_B_FULL).copy(
                autoCompletionPolicy = RunAutoCompletionPolicy(2)
            )
        }
        assertFailsWith<IllegalArgumentException> {
            acceptanceConfig(AcceptanceProfile.APPENDIX_B_FULL).copy(
                finalOutputSpec = null
            )
        }
        assertFailsWith<IllegalArgumentException> {
            acceptanceConfig(AcceptanceProfile.APPENDIX_B_FULL).copy(
                finalOutputSpec = FinalOutputSpec(
                    relativePath = "result.bin",
                    mediaType = "application/octet-stream"
                )
            )
        }
    }

    @Test
    fun `acceptance config admits an explicit Jupyter notebook source output`() {
        val output = FinalOutputSpec(
            relativePath = "submission.ipynb",
            mediaType = "application/x-ipynb+json",
            description = "Offline Kaggle notebook source",
            validation = FinalOutputValidationContract(
                requiredJsonRootKeys =
                    listOf("cells", "metadata", "nbformat", "nbformat_minor"),
                minimumJsonArraySizes = mapOf("cells" to 1),
                requiredJsonArrayItemFields = mapOf(
                    "cells" to listOf("cell_type", "metadata", "source")
                ),
                jupyterNotebookSource = JupyterNotebookSourceContract(
                    minimumCodeCellCount = 3,
                    requiredCodeSignals = listOf("def validate_submission"),
                    requiredMarkdownSignals = listOf("offline"),
                    forbiddenCodeSignals = listOf("requests.")
                )
            )
        )

        val config = acceptanceConfig(AcceptanceProfile.APPENDIX_B_FULL).copy(
            finalOutputSpec = output
        )

        assertEquals(output, config.finalOutputSpec)
        assertEquals(
            output,
            json.decodeFromString(
                RunConfig.serializer(),
                json.encodeToString(RunConfig.serializer(), config)
            ).finalOutputSpec
        )
    }

    @Test
    fun `quantitative tool authority round trips through envelope persistence and recovery`() {
        val obligation = quantitativeToolObligation()
        val config = acceptanceConfig(AcceptanceProfile.APPENDIX_B_FULL).copy(
            quantitativeToolObligation = obligation
        )

        val encodedConfig = json.encodeToString(RunConfig.serializer(), config)
        assertTrue(encodedConfig.contains("\"quantitativeToolObligation\""))
        assertEquals(
            obligation,
            json.decodeFromString(RunConfig.serializer(), encodedConfig)
                .quantitativeToolObligation
        )
        assertEquals(
            obligation,
            CouncilExecutionEnvelopeFactory.create(config).quantitativeToolObligation
        )

        val persisted = PersistedRunState.fromRunConfig(
            config = config,
            groupIds = listOf("worker-0", "worker-1"),
            graderGroupIds = listOf("grader-0", "grader-1"),
            allowedHosts = emptySet()
        )
        assertEquals(obligation, persisted.quantitativeToolObligation)
        assertEquals(
            obligation,
            persisted.toRunConfig("/tmp/codex").quantitativeToolObligation
        )
        assertTrue(
            json.encodeToString(PersistedRunState.serializer(), persisted)
                .contains("\"quantitativeToolObligation\"")
        )
        persisted.requireCompatibleRecoveryIntent(config.copy())
        assertFailsWith<IllegalArgumentException> {
            persisted.requireCompatibleRecoveryIntent(
                config.copy(quantitativeToolObligation = null)
            )
        }
    }

    private fun acceptanceConfig(profile: AcceptanceProfile): RunConfig = config(
        rounds = when (profile) {
            AcceptanceProfile.NORMAL -> 2
            AcceptanceProfile.RUNTIME_USABILITY_SMOKE -> 1
            AcceptanceProfile.APPENDIX_B_FULL -> 6
        }
    ).copy(
        llmBackend = LlmBackend.CODEX_CHATGPT,
        councilExecutionPurpose = CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE,
        acceptanceProfile = profile,
        finalOutputSpec = FinalOutputSpec(
            relativePath = "receiver/result.md",
            mediaType = "text/markdown"
        )
    )

    private fun config(rounds: Int = 2): RunConfig = RunConfig(
        runId = "acceptance-profile-config",
        objective = "Produce a receiver-usable result.",
        groupCount = 2,
        groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
        rounds = rounds,
        scoringDimensions = listOf(
            ScoringDimension(
                id = "quality",
                name = "Quality",
                description = "Receiver quality",
                weight = 1.0
            )
        ),
        openRouterApiKey = "test-key"
    )

    private fun quantitativeToolObligation() = QuantitativeToolObligation(
        horizonTimeBasis = TimeBasis.HOUR,
        horizonIntervalCount = 72,
        allowedOpportunityTypes = setOf(ToolOpportunityType.CALCULATION),
        requiredOpportunityId = "hourly_power_audit",
        exactPromotionTestIds = setOf("runtime_boundary", "unit_boundary"),
        maximumOpportunityRuntimeMillis = 8_000,
        requiredFields = listOf(
            QuantitativeToolFieldObligation(
                roleId = "hourly_electrical_power",
                fieldName = "hourly_electrical_power_kw",
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
}
