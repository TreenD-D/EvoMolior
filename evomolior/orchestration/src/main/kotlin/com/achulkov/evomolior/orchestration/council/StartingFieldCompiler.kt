package com.achulkov.evomolior.orchestration.council

import com.achulkov.evomolior.core.council.CouncilContractHashing
import com.achulkov.evomolior.core.council.CouncilFieldSealing
import com.achulkov.evomolior.core.council.CouncilRoleOutputBundle
import com.achulkov.evomolior.core.council.ExecutionEnvelope
import com.achulkov.evomolior.core.council.FieldConsistencyReport
import com.achulkov.evomolior.core.council.StartingFieldCompileResult
import com.achulkov.evomolior.core.contract.RunInputArtifact
import kotlinx.serialization.json.Json

/**
 * Compiles the eight typed Genesis Council outputs into an immutable competitive starting field.
 *
 * Compilation is deliberately fail-closed: malformed or envelope-incompatible Council material is
 * reported as typed errors and is never replaced with defaults or materially rewritten.
 */
class StartingFieldCompiler {
    /**
     * Compiles and seals one worker/grader field pair.
     *
     * @param runId Stable run identity.
     * @param rawObjective Exact, unnormalised operator objective.
     * @param envelope Runtime capabilities and constraints supplied before Council execution.
     * @param outputs Complete set of eight typed Council outputs.
     * @param previousValid Previous valid field when compiling a revision.
     * @param inputArtifacts Operator-owned immutable run inputs committed into both fields.
     * @return A valid sealed field pair or all deterministic compile errors.
     */
    fun compile(
        runId: String,
        rawObjective: String,
        envelope: ExecutionEnvelope,
        outputs: CouncilRoleOutputBundle,
        previousValid: StartingFieldCompileResult.Valid? = null,
        inputArtifacts: List<RunInputArtifact> = emptyList()
    ): StartingFieldCompileResult {
        val rejectionEvidence = rejectionEvidence(outputs)
        val validation = StartingFieldInputValidator.validate(
            runId = runId,
            rawObjective = rawObjective,
            envelope = envelope,
            outputs = outputs,
            previousValid = previousValid
        )
        if (validation.errors.isNotEmpty()) {
            return invalid(validation.errors, rejectionEvidence)
        }

        val receiver = StartingFieldArtifacts.buildReceiverContract(
            rawObjective = rawObjective,
            envelope = envelope,
            outputs = outputs
        )
        val atlas = StartingFieldArtifacts.buildAtlas(
            runId = runId,
            receiver = receiver,
            outputs = outputs
        )
        val atlasErrors = StartingFieldInputValidator.validateBuiltAtlas(
            atlas = atlas,
            outputs = outputs,
            previousValid = previousValid
        )
        if (atlasErrors.isNotEmpty()) {
            return invalid(atlasErrors, rejectionEvidence)
        }
        val graderAtlas = GraderAtlasCompiler.compile(runId, receiver, atlas)
        val sealedTests = when (
            val result = SealedTestSuiteV3Compiler.compile(
                runId = runId,
                envelope = envelope,
                outputs = outputs,
                atlas = atlas
            )
        ) {
            is SealedTestSuiteV3CompileResult.Failure -> {
                return invalid(
                    errors = result.errors,
                    evidence = rejectionEvidence,
                    compiledTestCount = result.compiledTestCount
                )
            }
            is SealedTestSuiteV3CompileResult.Success -> result.authority
        }
        val acceptanceErrors = StartingFieldInputValidator.validateCompiledAcceptanceProfile(
            envelope = envelope,
            sealedTests = sealedTests
        )
        if (acceptanceErrors.isNotEmpty()) {
            return invalid(
                errors = acceptanceErrors,
                evidence = rejectionEvidence,
                compiledTestCount = sealedTests.privateSuite.tests.size
            )
        }

        val artifacts = StartingFieldArtifacts.build(
            runId = runId,
            rawObjective = rawObjective,
            envelope = envelope,
            outputs = outputs,
            atlas = atlas,
            sealedTests = sealedTests,
            inputArtifacts = inputArtifacts
        )
        val workerWithoutSeal = artifacts.workerField
        val graderWithoutSeal = artifacts.graderField
        val report = FieldConsistencyReport(
            compilerVersion = COMPILER_VERSION,
            checksPerformed = StartingFieldInputValidator.CHECKS,
            findings = validation.findings,
            workerFieldHash = CouncilContractHashing.hashStartingField(workerWithoutSeal),
            graderFieldHash = CouncilContractHashing.hashStartingField(graderWithoutSeal),
            atlasHash = atlas.atlasHash,
            envelopeHash = CouncilContractHashing.hashExecutionEnvelope(envelope),
            roleOutputBundleHash = CouncilContractHashing.hashRoleOutputs(outputs)
        )
        val seal = CouncilFieldSealing.createSeal(
            workerField = workerWithoutSeal,
            graderField = graderWithoutSeal,
            atlas = atlas,
            graderAtlas = graderAtlas,
            envelope = envelope,
            report = report
        )
        return StartingFieldCompileResult.Valid(
            workerField = workerWithoutSeal.copy(seal = seal),
            graderField = graderWithoutSeal.copy(seal = seal),
            receiverContract = receiver,
            atlas = atlas,
            graderAtlas = graderAtlas,
            sealedTests = sealedTests,
            report = report,
            degradationRecords = validation.degradationRecords
        )
    }

    private fun invalid(
        errors: List<com.achulkov.evomolior.core.council.FieldCompileError>,
        evidence: RejectionEvidence,
        compiledTestCount: Int = 0
    ): StartingFieldCompileResult.Invalid =
        StartingFieldCompileResult.Invalid(
            errors = errors,
            responsibleCouncilRoles = errors.flatMapTo(linkedSetOf()) {
                it.responsibleCouncilRoles
            },
            declaredTestCount = evidence.declaredTestCount,
            compiledTestCount = compiledTestCount,
            definitionHashesByTestId = evidence.definitionHashesByTestId
        )

    private fun rejectionEvidence(outputs: CouncilRoleOutputBundle): RejectionEvidence {
        val tests = outputs.synthesis.sealedTests
        return RejectionEvidence(
            declaredTestCount = tests.size,
            definitionHashesByTestId = tests.associate { test ->
                test.testKey to canonicalSourceDefinitionHash(test.testDefinitionJson)
            }
        )
    }

    /**
     * Commits a source definition by canonical JSON value, not provider formatting.
     *
     * Malformed pre-Synthesis/decode source retains a deterministic raw-text hash so rejection
     * evidence remains available even when no canonical JSON value exists.
     */
    private fun canonicalSourceDefinitionHash(source: String): String =
        runCatching {
            CouncilContractHashing.sha256(
                CouncilContractHashing.canonicalJson(
                    sourceDefinitionJson.parseToJsonElement(source)
                )
            )
        }.getOrElse {
            CouncilContractHashing.sha256(source)
        }

    private data class RejectionEvidence(
        val declaredTestCount: Int,
        val definitionHashesByTestId: Map<String, String>
    )

    private companion object {
        const val COMPILER_VERSION = "increment-2-v1"
        val sourceDefinitionJson = Json {
            ignoreUnknownKeys = false
            isLenient = false
            coerceInputValues = false
            allowSpecialFloatingPointValues = false
        }
    }
}
