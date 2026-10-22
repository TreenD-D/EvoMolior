package com.achulkov.evomolior.orchestration.council

import com.achulkov.evomolior.core.config.CodexRuntimePreset
import com.achulkov.evomolior.core.council.CouncilFieldSealing
import com.achulkov.evomolior.core.council.FieldCompileErrorCode
import com.achulkov.evomolior.core.council.RoleModelAssignment
import com.achulkov.evomolior.core.council.StartingFieldCompileResult
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.orchestration.lifecycle.LifecycleManager
import com.achulkov.evomolior.sandbox.shuru.ShuruVmManager
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CodexPresetProjectionTest {
    private val fixtures = StartingFieldCompilerTest()

    @Test
    fun `presets replace Council model proposals for every runtime role before sealing`() {
        CodexRuntimePreset.entries.forEach { preset ->
            val envelope = envelope(preset)
            val source = presetOutputs()
            val projected = source.projectDeterministicCompilerAuthority("preset-run", envelope)
            assertEquals(
                preset.runtimeModelPools(),
                projected.modelEcologist.assignments.associate {
                    it.role.name to (listOf(it.primaryModelId) + it.fallbackModelIds)
                }
            )
            assertEquals(source.ecologist, projected.ecologist)
            assertTrue(projected.modelEcologist.assignments.none { it.role.isCouncilRole })
            val result = assertIs<StartingFieldCompileResult.Valid>(
                StartingFieldCompiler().compile("preset-run", "Objective", envelope, projected)
            )
            assertEquals(
                preset.runtimeModelPools(),
                result.workerField.modelPools + result.graderField.modelPools
            )
            assertTrue(
                CouncilFieldSealing.verifySeal(
                    result.workerField.seal!!,
                    result.workerField,
                    result.graderField,
                    result.atlas,
                    result.graderAtlas,
                    result.receiverContract,
                    envelope,
                    result.report
                )
            )
        }
    }

    @Test
    fun `operator role override is an exact singleton without Council or preset fallbacks`() {
        val preset = CodexRuntimePreset.MIXED_FAMILIES
        val envelope = envelope(preset).copy(
            operatorOverrides = mapOf("model.Planner" to CodexRuntimePreset.LUNA)
        )
        val source = presetOutputs()
        val outputs = source.copy(
            modelEcologist = source.modelEcologist.copy(
                assignments = source.modelEcologist.assignments + RoleModelAssignment(
                    Role.Planner,
                    CodexRuntimePreset.ASTRA,
                    fallbackModelIds = listOf("provider/worker", CodexRuntimePreset.TERRA)
                )
            )
        )
        val projected = outputs.projectDeterministicCompilerAuthority("override-run", envelope)
        val planner = projected.modelEcologist.assignments.single { it.role == Role.Planner }
        assertEquals(CodexRuntimePreset.LUNA, planner.primaryModelId)
        assertEquals(emptyList(), planner.fallbackModelIds)
        val result = assertIs<StartingFieldCompileResult.Valid>(
            StartingFieldCompiler().compile("override-run", "Objective", envelope, projected)
        )
        assertEquals(listOf(CodexRuntimePreset.LUNA), result.workerField.modelPools[Role.Planner.name])
    }

    @Test
    fun `unavailable preset model fails existing compiler checks without substitution`() {
        val envelope = envelope(CodexRuntimePreset.FULL_LUNA).copy(
            availableModels = fixtures.envelope().availableModels
        )
        val projected = fixtures.outputs()
            .projectDeterministicCompilerAuthority("unavailable", envelope)
        val result = assertIs<StartingFieldCompileResult.Invalid>(
            StartingFieldCompiler().compile("unavailable", "Objective", envelope, projected)
        )
        assertTrue(result.errors.any { it.code == FieldCompileErrorCode.UNKNOWN_MODEL })
    }

    @Test
    fun `preset does not erase required model capabilities`() {
        val envelope = envelope(CodexRuntimePreset.FULL_LUNA).let { envelope ->
            envelope.copy(availableModels = envelope.availableModels.map { it.copy(capabilities = emptySet()) })
        }
        val projected = fixtures.outputs()
            .projectDeterministicCompilerAuthority("capabilities", envelope)
        val result = assertIs<StartingFieldCompileResult.Invalid>(
            StartingFieldCompiler().compile("capabilities", "Objective", envelope, projected)
        )
        assertTrue(result.errors.any { it.code == FieldCompileErrorCode.UNKNOWN_MODEL })
    }

    @Test
    fun `null preset preserves Council assignments and fallback pools`() {
        val source = fixtures.outputs().let { source ->
            source.copy(modelEcologist = source.modelEcologist.copy(
                assignments = source.modelEcologist.assignments.map {
                    it.copy(fallbackModelIds = listOf("provider/legacy-fallback"))
                }
            ))
        }
        val projected = source.projectDeterministicCompilerAuthority("legacy", fixtures.envelope())
        assertEquals(
            source.modelEcologist.assignments.sortedBy { it.role.name },
            projected.modelEcologist.assignments
        )
    }

    @Test
    fun `full Luna operator override removes stale provider fallback`() {
        val envelope = envelope(CodexRuntimePreset.FULL_LUNA).copy(
            operatorOverrides = mapOf("model.Researcher" to CodexRuntimePreset.LUNA)
        )
        val source = presetOutputs().let { source ->
            source.copy(modelEcologist = source.modelEcologist.copy(
                assignments = source.modelEcologist.assignments.map {
                    it.copy(fallbackModelIds = listOf("provider/legacy-fallback"))
                }
            ))
        }
        val projected = source.projectDeterministicCompilerAuthority("baseline", envelope)
        assertTrue(projected.modelEcologist.assignments.all {
            it.primaryModelId == CodexRuntimePreset.LUNA && it.fallbackModelIds.isEmpty()
        })
        assertIs<StartingFieldCompileResult.Valid>(
            StartingFieldCompiler().compile("baseline", "Objective", envelope, projected)
        )
    }

    @Test
    fun `sealed Astra operator override executes under mixed families without widening other pools`() {
        val preset = CodexRuntimePreset.MIXED_FAMILIES
        val envelope = envelope(CodexRuntimePreset.MIXED_FAMILIES).copy(
            codexRuntimePreset = preset,
            operatorOverrides = mapOf("model.Planner" to CodexRuntimePreset.ASTRA)
        )
        val projected = presetOutputs().projectDeterministicCompilerAuthority("override", envelope)
        val result = assertIs<StartingFieldCompileResult.Valid>(
            StartingFieldCompiler().compile("override", "Objective", envelope, projected)
        )
        assertEquals(
            listOf(CodexRuntimePreset.ASTRA),
            result.workerField.modelPools[Role.Planner.name]
        )
        assertEquals(
            preset.runtimeModelPools() + (Role.Planner.name to listOf(CodexRuntimePreset.ASTRA)),
            result.workerField.modelPools + result.graderField.modelPools
        )
        val manager = LifecycleManager(
            runId = "override",
            groupId = "group-0",
            shuruVmManager = ShuruVmManager(),
            substrateDatabaseFactory = SubstrateDatabaseFactory(
                createTempDirectory("preset-override-test-").toFile()
            ),
            agentJarPath = "/unused/agent.jar",
            openRouterApiKey = "",
            codexRuntimePreset = preset,
            modelSelectionRandom = object : Random() {
                override fun nextBits(bitCount: Int): Int = error("Singleton must not sample")
            }
        )
        assertEquals(
            CodexRuntimePreset.ASTRA,
            manager.resolveModelForRole(
                Role.Planner, result.workerField.modelAssignment, result.workerField.modelPools
            )
        )
        assertEquals(
            CodexRuntimePreset.LUNA,
            manager.resolveModelForRole(
                Role.Researcher, result.workerField.modelAssignment, result.workerField.modelPools
            )
        )
    }

    @Test
    fun `full Luna rejects Astra override even when manually included in the envelope`() {
        val envelope = envelope(CodexRuntimePreset.MIXED_FAMILIES).copy(
            codexRuntimePreset = CodexRuntimePreset.FULL_LUNA,
            operatorOverrides = mapOf("model.Planner" to CodexRuntimePreset.ASTRA)
        )
        assertFailsWith<IllegalArgumentException> {
            presetOutputs().projectDeterministicCompilerAuthority("invalid-override", envelope)
        }
    }

    private fun envelope(preset: CodexRuntimePreset) = fixtures.envelope().let { source ->
        source.copy(
            codexRuntimePreset = preset,
            availableModels = preset.supportedModelIds.map { model ->
                source.availableModels.first().copy(
                    modelId = model,
                    familyId = model,
                    capabilities = source.availableModels.flatMap { it.capabilities }.toSet()
                )
            }
        )
    }

    private fun presetOutputs() = fixtures.outputs().let { source ->
        source.copy(
            ecologist = source.ecologist.copy(
                workerGroups = source.ecologist.workerGroups.map {
                    it.copy(modelFamilyPreference = null)
                },
                graderGroups = source.ecologist.graderGroups.map {
                    it.copy(modelFamilyPreference = null)
                }
            )
        )
    }
}
