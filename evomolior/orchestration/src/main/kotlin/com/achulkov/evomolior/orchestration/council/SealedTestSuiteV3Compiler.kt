package com.achulkov.evomolior.orchestration.council

import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.ToolOpportunity
import com.achulkov.evomolior.core.council.CompiledSealedTestAuthorityV3
import com.achulkov.evomolior.core.council.ContractPath
import com.achulkov.evomolior.core.council.CouncilDeterministicIds
import com.achulkov.evomolior.core.council.CouncilPromotionFixtureWireSchemaAuthority
import com.achulkov.evomolior.core.council.CouncilRoleOutputBundle
import com.achulkov.evomolior.core.council.ExecutionEnvelope
import com.achulkov.evomolior.core.council.FieldCompileError
import com.achulkov.evomolior.core.council.FieldCompileErrorCode
import com.achulkov.evomolior.core.council.GeneralTestCompileResult
import com.achulkov.evomolior.core.council.GeneralTestCompiler
import com.achulkov.evomolior.core.council.GeneralTestDefinitionV1Codec
import com.achulkov.evomolior.core.council.LocalNodeKey
import com.achulkov.evomolior.core.council.SealedTestIdentityAuthorityV3
import com.achulkov.evomolior.core.council.SealedTestSuiteV3Authority
import com.achulkov.evomolior.core.council.SealedTestV3
import com.achulkov.evomolior.core.council.TypedJsonValueCodec
import com.achulkov.evomolior.core.model.Role

/** Result of compiling the complete Council test bundle into one executable v3 authority. */
internal sealed interface SealedTestSuiteV3CompileResult {
    /** Complete executable authority. */
    data class Success(
        val authority: CompiledSealedTestAuthorityV3
    ) : SealedTestSuiteV3CompileResult

    /** Deterministic field errors; no suite or field may become active. */
    data class Failure(
        val errors: List<FieldCompileError>,
        val compiledTestCount: Int
    ) : SealedTestSuiteV3CompileResult
}

/**
 * Compiles the Task-derived test identities and Synthesis definitions into one common v3 suite.
 *
 * This compiler performs no persistence and reads no filesystem state.
 */
internal object SealedTestSuiteV3Compiler {
    /**
     * Compiles every GENERAL definition and PROMOTION fixture before StartingField construction.
     */
    fun compile(
        runId: String,
        envelope: ExecutionEnvelope,
        outputs: CouncilRoleOutputBundle,
        atlas: NodeAtlas
    ): SealedTestSuiteV3CompileResult {
        val errors = mutableListOf<FieldCompileError>()
        val nodesByKey = outputs.taskAnatomist.nodes.associateBy { it.key }
        val tests = outputs.synthesis.sealedTests.sortedBy { it.testKey }
        val promotionByTestId = mutableMapOf<String, PromotionAuthority>()

        nodesByKey.values.forEach { node ->
            val opportunity = node.toolOpportunity ?: return@forEach
            val exactTests = tests.filter {
                it.nodeKey == node.key && it.testKey in opportunity.promotionTestIds
            }
            val validation = CouncilPromotionTestContractValidator.validate(
                opportunity = opportunity,
                tests = exactTests
            )
            validation.failures.forEach { failure ->
                errors += fieldError(
                    code = FieldCompileErrorCode.INVALID_TOOL_SEMANTICS,
                    path = "synthesis.sealedTests.${failure.testId}.testDefinitionJson",
                    message =
                        "Tool promotion test does not satisfy its exact sealed contract: " +
                            failure.message,
                    nodeKey = node.key
                )
            }
            if (validation.failures.isEmpty()) {
                val schema = CouncilPromotionFixtureWireSchemaAuthority.from(opportunity)
                validation.fixtures.forEach { fixture ->
                    val previous = promotionByTestId.put(
                        fixture.testId,
                        PromotionAuthority(
                            nodeKey = node.key,
                            opportunity = opportunity,
                            schema = schema,
                            fixture = fixture
                        )
                    )
                    if (previous != null) {
                        errors += fieldError(
                            code = FieldCompileErrorCode.MISSING_TEST_COMMITMENT,
                            path = "synthesis.sealedTests.${fixture.testId}",
                            message = "Promotion test identity is declared by multiple opportunities",
                            nodeKey = node.key
                        )
                    }
                }
            }
        }
        if (errors.isNotEmpty()) return SealedTestSuiteV3CompileResult.Failure(
            errors = errors,
            compiledTestCount = 0
        )

        val records = mutableListOf<SealedTestV3>()
        val identities = mutableListOf<SealedTestIdentityAuthorityV3>()
        tests.forEach { test ->
            val node = nodesByKey[test.nodeKey]
            if (node == null) {
                errors += fieldError(
                    code = FieldCompileErrorCode.INVALID_NODE_REFERENCE,
                    path = "synthesis.sealedTests.${test.testKey}.nodeKey",
                    message = "Sealed test references an unknown Task node",
                    nodeKey = test.nodeKey
                )
                return@forEach
            }
            val nodeId = CouncilDeterministicIds.nodeId(runId, test.nodeKey)
            val promotion = promotionByTestId[test.testKey]
            if (promotion == null) {
                when (
                    val compiled = GeneralTestCompiler.compileEncoded(
                        testId = test.testKey,
                        definitionJson = test.testDefinitionJson,
                        outputSpec = envelope.outputSpec
                    )
                ) {
                    is GeneralTestCompileResult.Failure -> {
                        compiled.errors.forEach { error ->
                            errors += fieldError(
                                code = FieldCompileErrorCode.valueOf(error.code.name),
                                path =
                                    "synthesis.sealedTests.${test.testKey}." +
                                        error.path,
                                message = error.message,
                                nodeKey = test.nodeKey
                            )
                        }
                    }
                    is GeneralTestCompileResult.Success -> {
                        val definition = GeneralTestDefinitionV1Codec.decode(
                            test.testDefinitionJson
                        )
                        records += SealedTestSuiteV3Authority.generalTest(
                            testId = test.testKey,
                            nodeId = nodeId,
                            acceptedArtifactTypes = test.acceptedArtifactTypes,
                            definition = definition,
                            compiledPlan = compiled.plan
                        )
                        identities += SealedTestIdentityAuthorityV3.General(
                            testId = test.testKey,
                            nodeId = nodeId
                        )
                    }
                }
            } else {
                val input = runCatching {
                    TypedJsonValueCodec.fromJsonElement(promotion.fixture.input)
                }.getOrElse { error ->
                    errors += fieldError(
                        code = FieldCompileErrorCode.INVALID_TOOL_SEMANTICS,
                        path =
                            "synthesis.sealedTests.${test.testKey}." +
                                "testDefinitionJson.input",
                        message =
                            "Promotion input cannot enter typed v3 authority: " +
                                error.message.orEmpty(),
                        nodeKey = test.nodeKey
                    )
                    return@forEach
                }
                val expectedOutput = runCatching {
                    TypedJsonValueCodec.fromJsonElement(promotion.fixture.expectedOutput)
                }.getOrElse { error ->
                    errors += fieldError(
                        code = FieldCompileErrorCode.INVALID_TOOL_SEMANTICS,
                        path =
                            "synthesis.sealedTests.${test.testKey}." +
                                "testDefinitionJson.expectedOutput",
                        message =
                            "Promotion expected output cannot enter typed v3 authority: " +
                                error.message.orEmpty(),
                        nodeKey = test.nodeKey
                    )
                    return@forEach
                }
                records += SealedTestSuiteV3Authority.promotionTest(
                    testId = test.testKey,
                    nodeId = nodeId,
                    opportunityId = promotion.opportunity.opportunityId,
                    fixtureSchemaHash = promotion.schema.schemaKey,
                    acceptedArtifactTypes = test.acceptedArtifactTypes,
                    input = input,
                    expectedOutput = expectedOutput
                )
                identities += SealedTestIdentityAuthorityV3.Promotion(
                    testId = test.testKey,
                    nodeId = nodeId,
                    opportunityId = promotion.opportunity.opportunityId,
                    fixtureSchema = promotion.schema
                )
            }
        }
        if (errors.isNotEmpty()) return SealedTestSuiteV3CompileResult.Failure(
            errors = errors,
            compiledTestCount = records.size
        )

        return runCatching {
            val context = com.achulkov.evomolior.core.council
                .SealedTestSuiteV3ValidationContext(
                    finalOutputSpec = envelope.outputSpec,
                    atlasHash = atlas.atlasHash,
                    tests = identities.sortedBy(SealedTestIdentityAuthorityV3::testId)
                )
            val suite = SealedTestSuiteV3Authority.seal(
                rubricSchemaVersion = outputs.synthesis.rubricSchemaVersion,
                tests = records,
                context = context
            )
            val authority = CompiledSealedTestAuthorityV3(
                privateSuite = suite,
                workerCommitmentView =
                    SealedTestSuiteV3Authority.commitmentView(suite, context),
                validationContext = context
            )
            authority.requireValid()
            SealedTestSuiteV3CompileResult.Success(authority)
        }.getOrElse { error ->
            SealedTestSuiteV3CompileResult.Failure(
                errors = listOf(
                    FieldCompileError(
                        code = FieldCompileErrorCode.HASH_MISMATCH,
                        path = ContractPath(listOf("synthesis", "sealedTests")),
                        message =
                            "Complete sealed-tests-v3 authority failed verification: " +
                                error.message.orEmpty().take(500),
                        responsibleCouncilRoles = setOf(Role.CouncilSynthesis)
                    )
                ),
                compiledTestCount = records.size
            )
        }
    }

    private fun fieldError(
        code: FieldCompileErrorCode,
        path: String,
        message: String,
        nodeKey: LocalNodeKey
    ): FieldCompileError =
        FieldCompileError(
            code = code,
            path = ContractPath(path.split('.')),
            message = message,
            responsibleCouncilRoles = setOf(Role.CouncilSynthesis),
            relatedLocalNodeKeys = setOf(nodeKey)
        )

    private data class PromotionAuthority(
        val nodeKey: LocalNodeKey,
        val opportunity: ToolOpportunity,
        val schema: CouncilPromotionFixtureWireSchemaAuthority,
        val fixture: CouncilPromotionTestFixture
    )
}
