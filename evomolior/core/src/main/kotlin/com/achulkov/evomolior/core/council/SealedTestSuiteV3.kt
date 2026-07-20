package com.achulkov.evomolior.core.council

import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.FinalOutputSpec
import com.achulkov.evomolior.core.atlas.TypedToolSchemaValidator
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

private fun isLowercaseSha256(value: String): Boolean =
    value.length == 64 && value.all { character ->
        character in '0'..'9' || character in 'a'..'f'
    }

/** Closed typed source definitions supported by the `sealed-tests-v3` authority. */
@Serializable
sealed interface SealedTestDefinitionV3 {
    /**
     * General deterministic assertion source.
     *
     * @param definition Closed assertion-language definition.
     */
    @Serializable
    @SerialName("GENERAL")
    data class General(
        val definition: GeneralTestDefinitionV1
    ) : SealedTestDefinitionV3

    /**
     * Exact tool-promotion fixture source.
     *
     * @param input Exact typed tool input.
     * @param expectedOutput Exact typed expected tool output.
     */
    @Serializable
    @SerialName("PROMOTION")
    data class Promotion(
        val input: TypedJsonValue,
        val expectedOutput: TypedJsonValue
    ) : SealedTestDefinitionV3
}

/** Stable strict codec for closed [SealedTestDefinitionV3] source definitions. */
object SealedTestDefinitionV3Codec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        classDiscriminator = "type"
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        allowSpecialFloatingPointValues = false
    }

    /**
     * Encodes [definition] as canonical closed definition JSON.
     *
     * @param definition Source definition to encode.
     * @return Canonical definition text.
     */
    fun encode(definition: SealedTestDefinitionV3): String =
        CouncilContractHashing.canonicalJson(
            json.encodeToJsonElement(SealedTestDefinitionV3.serializer(), definition)
        )

    /**
     * Decodes one strict closed v3 definition.
     *
     * @param value Strict definition JSON.
     * @return Decoded definition.
     */
    fun decode(value: String): SealedTestDefinitionV3 =
        json.decodeFromString(SealedTestDefinitionV3.serializer(), value)

    /**
     * Returns the canonical definition commitment.
     *
     * @param definition Definition to commit.
     * @return Lowercase SHA-256 digest.
     */
    fun hash(definition: SealedTestDefinitionV3): String =
        CouncilContractHashing.sha256(encode(definition))
}

/**
 * Exact Task-derived identity and execution authority for one expected v3 test.
 */
@Serializable
sealed interface SealedTestIdentityAuthorityV3 {
    /** Stable expected test identity. */
    val testId: String

    /** Stable expected Atlas node identity. */
    val nodeId: String

    /**
     * Expected general-test identity.
     *
     * @param testId Stable expected test identity.
     * @param nodeId Stable expected Atlas node identity.
     */
    @Serializable
    @SerialName("GENERAL")
    data class General(
        override val testId: String,
        override val nodeId: String
    ) : SealedTestIdentityAuthorityV3

    /**
     * Expected promotion-test identity and exact fixture schemas.
     *
     * @param testId Stable expected test identity.
     * @param nodeId Stable expected Atlas node identity.
     * @param opportunityId Exact Task-derived tool opportunity identity.
     * @param fixtureSchema Exact Task-derived input/output schema-pair authority.
     */
    @Serializable
    @SerialName("PROMOTION")
    data class Promotion(
        override val testId: String,
        override val nodeId: String,
        val opportunityId: String,
        val fixtureSchema: CouncilPromotionFixtureWireSchemaAuthority
    ) : SealedTestIdentityAuthorityV3
}

/**
 * External authority required to construct or activate a v3 suite.
 *
 * This context prevents a self-consistent suite from authorizing its own output path, test
 * identity, tool opportunity, or promotion schemas.
 *
 * @param finalOutputSpec Exact receiver output contract.
 * @param atlasHash Exact Task-derived Atlas commitment.
 * @param tests Complete Task-derived expected test authority, ordered by test ID.
 */
@Serializable
data class SealedTestSuiteV3ValidationContext(
    val finalOutputSpec: FinalOutputSpec,
    val atlasHash: String,
    val tests: List<SealedTestIdentityAuthorityV3>
) {
    init {
        require(isLowercaseSha256(atlasHash)) {
            "v3 validation context requires one lowercase Atlas SHA-256 digest"
        }
        require(tests.isNotEmpty()) { "v3 validation context requires expected tests" }
        require(tests.size <= SealedTestSuiteV3Limits.MAX_TESTS_PER_SUITE) {
            "v3 validation context exceeds the suite test limit"
        }
        require(tests.map(SealedTestIdentityAuthorityV3::testId).distinct().size == tests.size) {
            "v3 validation context test IDs must be unique"
        }
        require(tests == tests.sortedBy(SealedTestIdentityAuthorityV3::testId)) {
            "v3 validation context tests must be ordered by testId"
        }
        require(tests.all { it.testId.isNotBlank() && it.nodeId.isNotBlank() }) {
            "v3 validation context identities must not be blank"
        }
        require(
            tests.filterIsInstance<SealedTestIdentityAuthorityV3.Promotion>()
                .all { it.opportunityId.isNotBlank() }
        ) {
            "v3 promotion authority requires a non-blank opportunity identity"
        }
    }

    /**
     * Resolves exact Task-derived authority for [testId].
     *
     * @param testId Stable test identity.
     * @return Exact expected authority, or null when undeclared.
     */
    fun find(testId: String): SealedTestIdentityAuthorityV3? =
        tests.firstOrNull { it.testId == testId }
}

/** Bounds owned by the complete sealed-tests-v3 suite authority. */
object SealedTestSuiteV3Limits {
    /** Maximum combined GENERAL and PROMOTION tests in one suite. */
    const val MAX_TESTS_PER_SUITE: Int = 64
}

/**
 * One executable test record in the common sealed v3 authority.
 *
 * The full record is grader-private. Worker-visible commitments are derived by
 * [SealedTestSuiteV3Authority.commitmentView] and retain this record's exact [testHash].
 */
@Serializable
sealed interface SealedTestV3 {
    /** Stable test identity. */
    val testId: String

    /** Stable owning Atlas node identity. */
    val nodeId: String

    /** Canonically ordered accepted artifact kinds. */
    val acceptedArtifactTypes: List<ArtifactType>

    /** Commitment of the closed source definition. */
    val definitionHash: String

    /** Commitment of this complete record excluding this field. */
    val testHash: String

    /**
     * General assertion paired with its compiler-owned executable plan.
     *
     * @param testId Stable test identity.
     * @param nodeId Owning Atlas node.
     * @param acceptedArtifactTypes Canonically ordered accepted artifact kinds.
     * @param definition Closed general assertion source.
     * @param compiledPlan Hash-bound executable plan.
     * @param definitionHash Commitment of the closed GENERAL source definition.
     * @param testHash Commitment of this record excluding this field.
     */
    @Serializable
    @SerialName("GENERAL")
    data class General(
        override val testId: String,
        override val nodeId: String,
        override val acceptedArtifactTypes: List<ArtifactType>,
        val definition: GeneralTestDefinitionV1,
        val compiledPlan: CompiledGeneralTestPlan,
        override val definitionHash: String,
        override val testHash: String = ""
    ) : SealedTestV3

    /**
     * Exact typed promotion fixture paired with its Task-derived tool authority.
     *
     * @param testId Stable test identity.
     * @param nodeId Owning Atlas node.
     * @param opportunityId Exact tool opportunity exercised by the fixture.
     * @param fixtureSchemaHash Exact Task-derived input/output schema-pair commitment.
     * @param acceptedArtifactTypes Canonically ordered accepted artifact kinds.
     * @param input Exact typed tool input.
     * @param expectedOutput Exact typed expected tool output.
     * @param definitionHash Commitment of the closed PROMOTION source definition.
     * @param testHash Commitment of this record excluding this field.
     */
    @Serializable
    @SerialName("PROMOTION")
    data class Promotion(
        override val testId: String,
        override val nodeId: String,
        val opportunityId: String,
        val fixtureSchemaHash: String,
        override val acceptedArtifactTypes: List<ArtifactType>,
        val input: TypedJsonValue,
        val expectedOutput: TypedJsonValue,
        override val definitionHash: String,
        override val testHash: String = ""
    ) : SealedTestV3

    /** Returns the closed source definition committed by [definitionHash]. */
    fun sourceDefinition(): SealedTestDefinitionV3 = when (this) {
        is General -> SealedTestDefinitionV3.General(definition)
        is Promotion -> SealedTestDefinitionV3.Promotion(input, expectedOutput)
    }

    /** Returns a copy carrying its deterministic record commitment. */
    fun withComputedHash(): SealedTestV3 = when (this) {
        is General -> copy(testHash = SealedTestV3Codec.hash(this))
        is Promotion -> copy(testHash = SealedTestV3Codec.hash(this))
    }

    /** Returns whether [testHash] matches the complete canonical record. */
    fun hasValidHash(): Boolean =
        CouncilContractHashing.constantTimeEquals(testHash, SealedTestV3Codec.hash(this))
}

/** Stable canonical codec and self-hash authority for [SealedTestV3]. */
object SealedTestV3Codec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        classDiscriminator = "type"
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        allowSpecialFloatingPointValues = false
    }

    /**
     * Encodes [test] as canonical v3 test JSON.
     *
     * @param test Executable test record.
     * @return Canonical test JSON including its attached hash.
     */
    fun encode(test: SealedTestV3): String =
        CouncilContractHashing.canonicalJson(
            json.encodeToJsonElement(SealedTestV3.serializer(), test)
        )

    /**
     * Decodes one strict v3 test record.
     *
     * @param value Strict test JSON.
     * @return Decoded record; callers should validate it through its owning suite.
     */
    fun decode(value: String): SealedTestV3 =
        json.decodeFromString(SealedTestV3.serializer(), value)

    /**
     * Hashes [test] after excluding its self-referential `testHash` field.
     *
     * @param test Test record to commit.
     * @return Lowercase SHA-256 digest.
     */
    fun hash(test: SealedTestV3): String {
        val element = json.encodeToJsonElement(SealedTestV3.serializer(), test)
        val commitment = JsonObject(element.jsonObject.filterKeys { it != "testHash" })
        return CouncilContractHashing.sha256(
            CouncilContractHashing.canonicalJson(commitment)
        )
    }
}

/**
 * One common, immutable executable sealed-test authority.
 *
 * @param schemaVersion Exact suite ABI.
 * @param rubricSchemaVersion Exact rubric ABI paired with the suite.
 * @param atlasHash Exact Atlas authority paired with every node reference.
 * @param finalOutputSpecHash Exact receiver output-contract commitment.
 * @param assertionLanguageVersion General assertion ABI pinned by the suite.
 * @param evaluatorVersion Deterministic evaluator semantics pinned by the suite.
 * @param tests Canonically ordered complete private test records.
 * @param suiteHash Commitment of this complete suite excluding this field.
 */
@Serializable
data class SealedTestSuiteV3(
    val schemaVersion: String = SealedTestSuiteV3Authority.SCHEMA_VERSION,
    val rubricSchemaVersion: String,
    val atlasHash: String,
    val finalOutputSpecHash: String,
    val assertionLanguageVersion: String =
        GeneralTestDefinitionV1Codec.ASSERTION_LANGUAGE_VERSION,
    val evaluatorVersion: String = GeneralTestCompiler.EVALUATOR_VERSION,
    val tests: List<SealedTestV3>,
    val suiteHash: String = ""
) {
    /** Returns a copy carrying the deterministic suite commitment. */
    fun withComputedHash(): SealedTestSuiteV3 =
        copy(suiteHash = SealedTestSuiteV3Codec.hash(this))

    /** Returns whether [suiteHash] matches the complete canonical suite. */
    fun hasValidHash(): Boolean =
        CouncilContractHashing.constantTimeEquals(
            suiteHash,
            SealedTestSuiteV3Codec.hash(this)
        )
}

/** Closed test kinds exposed by the worker-safe commitment projection. */
@Serializable
enum class SealedTestKindV3 {
    GENERAL,
    PROMOTION
}

/**
 * Worker-safe commitment to one private test record.
 *
 * @param testId Stable test identity.
 * @param nodeId Owning Atlas node.
 * @param kind Closed test kind.
 * @param opportunityId Promotion opportunity identity, or null for a general test.
 * @param acceptedArtifactTypes Canonically ordered accepted artifact kinds.
 * @param definitionHash Exact private source-definition commitment.
 * @param testHash Exact full private test-record commitment.
 */
@Serializable
data class SealedTestCommitmentV3(
    val testId: String,
    val nodeId: String,
    val kind: SealedTestKindV3,
    val opportunityId: String?,
    val acceptedArtifactTypes: List<ArtifactType>,
    val definitionHash: String,
    val testHash: String
)

/**
 * Worker-safe projection derived from exactly one common private suite authority.
 *
 * This projection has no independent semantic suite hash. [sourceSuiteHash] is the identity of the
 * full common authority from which every record commitment was selected.
 *
 * @param schemaVersion Exact projection ABI.
 * @param sourceSuiteSchemaVersion Exact source suite ABI.
 * @param sourceSuiteHash Exact source common-suite commitment.
 * @param rubricSchemaVersion Exact paired rubric ABI.
 * @param atlasHash Exact paired Atlas commitment.
 * @param finalOutputSpecHash Exact receiver output-contract commitment.
 * @param assertionLanguageVersion Exact source assertion ABI.
 * @param evaluatorVersion Exact source evaluator ABI.
 * @param tests Worker-safe exact record commitments.
 * @param viewHash Commitment of this projection excluding this field.
 */
@Serializable
data class SealedTestSuiteV3CommitmentView(
    val schemaVersion: String = SealedTestSuiteV3Authority.COMMITMENT_VIEW_SCHEMA_VERSION,
    val sourceSuiteSchemaVersion: String,
    val sourceSuiteHash: String,
    val rubricSchemaVersion: String,
    val atlasHash: String,
    val finalOutputSpecHash: String,
    val assertionLanguageVersion: String,
    val evaluatorVersion: String,
    val tests: List<SealedTestCommitmentV3>,
    val viewHash: String = ""
) {
    /** Returns a copy carrying the deterministic projection hash. */
    fun withComputedHash(): SealedTestSuiteV3CommitmentView =
        copy(viewHash = SealedTestSuiteV3CommitmentViewCodec.hash(this))

    /** Returns whether [viewHash] matches this complete projection. */
    fun hasValidHash(): Boolean =
        CouncilContractHashing.constantTimeEquals(
            viewHash,
            SealedTestSuiteV3CommitmentViewCodec.hash(this)
        )
}

/** Canonical codec for the worker-safe [SealedTestSuiteV3CommitmentView]. */
object SealedTestSuiteV3CommitmentViewCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
    }

    /**
     * Encodes [view] as canonical commitment-view JSON.
     *
     * @param view Worker-safe projection.
     * @return Canonical JSON.
     */
    fun encode(view: SealedTestSuiteV3CommitmentView): String =
        CouncilContractHashing.canonicalJson(
            json.encodeToJsonElement(
                SealedTestSuiteV3CommitmentView.serializer(),
                view.also(SealedTestSuiteV3Authority::requireValidCommitmentView)
            )
        )

    /**
     * Decodes one strict commitment view.
     *
     * @param value Strict projection JSON.
     * @return Decoded projection.
     */
    fun decode(value: String): SealedTestSuiteV3CommitmentView =
        json.decodeFromString(SealedTestSuiteV3CommitmentView.serializer(), value)

    /**
     * Strictly decodes and verifies one commitment projection.
     *
     * @param value Strict projection JSON.
     * @return Self-hash and structure-verified projection.
     */
    fun decodeVerified(value: String): SealedTestSuiteV3CommitmentView =
        decode(value).also(SealedTestSuiteV3Authority::requireValidCommitmentView)

    /**
     * Hashes [view] after excluding its self-referential `viewHash` field.
     *
     * @param view Projection to commit.
     * @return Lowercase SHA-256 digest.
     */
    fun hash(view: SealedTestSuiteV3CommitmentView): String {
        val element = json.encodeToJsonElement(
            SealedTestSuiteV3CommitmentView.serializer(),
            view
        )
        val commitment = JsonObject(element.jsonObject.filterKeys { it != "viewHash" })
        return CouncilContractHashing.sha256(
            CouncilContractHashing.canonicalJson(commitment)
        )
    }
}

/** Stable canonical codec, strict readback gate, and self-hash authority for v3 suites. */
object SealedTestSuiteV3Codec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        classDiscriminator = "type"
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        allowSpecialFloatingPointValues = false
    }

    /**
     * Encodes one fully verified active v3 suite.
     *
     * @param suite Suite to encode.
     * @param context Exact external output, Atlas, and Task-derived test authority.
     * @return Canonical suite JSON.
     */
    fun encode(
        suite: SealedTestSuiteV3,
        context: SealedTestSuiteV3ValidationContext
    ): String {
        SealedTestSuiteV3Authority.requireValid(suite, context)
        return CouncilContractHashing.canonicalJson(
            json.encodeToJsonElement(SealedTestSuiteV3.serializer(), suite)
        )
    }

    /**
     * Strictly decodes one suite without granting active authority.
     *
     * Historical or tampered bytes may decode structurally but remain unusable until verified.
     *
     * @param value Strict suite JSON.
     * @return Structurally decoded suite.
     */
    fun decode(value: String): SealedTestSuiteV3 =
        json.decodeFromString(SealedTestSuiteV3.serializer(), value)

    /**
     * Strictly decodes and verifies one active v3 suite.
     *
     * @param value Strict suite JSON.
     * @param context Exact external output, Atlas, and Task-derived test authority.
     * @return Hash- and semantics-verified suite.
     * @throws IllegalArgumentException when any active-authority invariant fails.
     */
    fun decodeVerified(
        value: String,
        context: SealedTestSuiteV3ValidationContext
    ): SealedTestSuiteV3 =
        decode(value).also { suite ->
            SealedTestSuiteV3Authority.requireValid(suite, context)
        }

    /**
     * Hashes [suite] after excluding its self-referential `suiteHash` field.
     *
     * @param suite Suite to commit.
     * @return Lowercase SHA-256 digest.
     */
    fun hash(suite: SealedTestSuiteV3): String {
        val element = json.encodeToJsonElement(SealedTestSuiteV3.serializer(), suite)
        val commitment = JsonObject(element.jsonObject.filterKeys { it != "suiteHash" })
        return CouncilContractHashing.sha256(
            CouncilContractHashing.canonicalJson(commitment)
        )
    }
}

/**
 * Constructor, validator, and worker projection for the one common v3 suite authority.
 *
 * Legacy `sealed-tests-v2` values are never accepted or converted by this authority.
 */
object SealedTestSuiteV3Authority {
    /** Exact active suite ABI. */
    const val SCHEMA_VERSION: String = "sealed-tests-v3"

    /** Exact worker-safe commitment projection ABI. */
    const val COMMITMENT_VIEW_SCHEMA_VERSION: String = "sealed-tests-v3-commitments"

    private val contractJson = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
    }

    /**
     * Builds one canonical GENERAL record from a compiler-produced plan.
     *
     * @param testId Stable test identity.
     * @param nodeId Owning Atlas node.
     * @param acceptedArtifactTypes Accepted artifact kinds.
     * @param definition Closed general source.
     * @param compiledPlan Exact compiler-produced plan.
     * @return Hash-bound test record.
     */
    fun generalTest(
        testId: String,
        nodeId: String,
        acceptedArtifactTypes: Set<ArtifactType>,
        definition: GeneralTestDefinitionV1,
        compiledPlan: CompiledGeneralTestPlan
    ): SealedTestV3.General {
        val source = SealedTestDefinitionV3.General(definition)
        val candidate = SealedTestV3.General(
            testId = testId,
            nodeId = nodeId,
            acceptedArtifactTypes = acceptedArtifactTypes.sortedBy(ArtifactType::name),
            definition = definition,
            compiledPlan = compiledPlan,
            definitionHash = SealedTestDefinitionV3Codec.hash(source)
        )
        return candidate.copy(testHash = SealedTestV3Codec.hash(candidate))
    }

    /**
     * Builds one canonical PROMOTION record from exact typed fixture values.
     *
     * @param testId Stable test identity.
     * @param nodeId Owning Atlas node.
     * @param opportunityId Exact tool opportunity identity.
     * @param fixtureSchemaHash Exact Task-derived schema-pair commitment.
     * @param acceptedArtifactTypes Accepted artifact kinds.
     * @param input Exact typed tool input.
     * @param expectedOutput Exact typed expected tool output.
     * @return Hash-bound test record.
     */
    fun promotionTest(
        testId: String,
        nodeId: String,
        opportunityId: String,
        fixtureSchemaHash: String,
        acceptedArtifactTypes: Set<ArtifactType>,
        input: TypedJsonValue,
        expectedOutput: TypedJsonValue
    ): SealedTestV3.Promotion {
        val source = SealedTestDefinitionV3.Promotion(input, expectedOutput)
        val candidate = SealedTestV3.Promotion(
            testId = testId,
            nodeId = nodeId,
            opportunityId = opportunityId,
            fixtureSchemaHash = fixtureSchemaHash,
            acceptedArtifactTypes = acceptedArtifactTypes.sortedBy(ArtifactType::name),
            input = input,
            expectedOutput = expectedOutput,
            definitionHash = SealedTestDefinitionV3Codec.hash(source)
        )
        return candidate.copy(testHash = SealedTestV3Codec.hash(candidate))
    }

    /**
     * Seals one canonical suite from complete private test records.
     *
     * @param rubricSchemaVersion Exact paired rubric ABI.
     * @param tests Complete private test records.
     * @param context Exact external output and Task-derived test authority.
     * @return Canonically ordered, hash-bound common suite authority.
     */
    fun seal(
        rubricSchemaVersion: String,
        tests: List<SealedTestV3>,
        context: SealedTestSuiteV3ValidationContext
    ): SealedTestSuiteV3 {
        require(tests.isNotEmpty()) { "sealed-tests-v3 requires at least one test" }
        require(tests.size <= SealedTestSuiteV3Limits.MAX_TESTS_PER_SUITE) {
            "sealed-tests-v3 exceeds the " +
                "${SealedTestSuiteV3Limits.MAX_TESTS_PER_SUITE}-test limit"
        }
        require(tests.map(SealedTestV3::testId).distinct().size == tests.size) {
            "sealed-tests-v3 test IDs must be unique"
        }
        val canonicalTests = tests.map(::canonicalizeTest)
            .sortedBy(SealedTestV3::testId)
        val suite = SealedTestSuiteV3(
            rubricSchemaVersion = rubricSchemaVersion,
            atlasHash = context.atlasHash,
            finalOutputSpecHash = hashFinalOutputSpec(context.finalOutputSpec),
            tests = canonicalTests
        ).withComputedHash()
        requireValid(suite, context)
        return suite
    }

    /**
     * Returns every deterministic active-authority violation.
     *
     * @param suite Candidate suite.
     * @param context Exact external output and Task-derived test authority.
     * @return Ordered validation errors; empty means valid.
     */
    fun validationErrors(
        suite: SealedTestSuiteV3,
        context: SealedTestSuiteV3ValidationContext
    ): List<String> = buildList {
        if (suite.schemaVersion != SCHEMA_VERSION) {
            add("schemaVersion must be $SCHEMA_VERSION")
        }
        if (suite.rubricSchemaVersion.isBlank()) {
            add("rubricSchemaVersion must not be blank")
        }
        if (!isLowercaseSha256(suite.atlasHash)) {
            add("atlasHash must be one lowercase SHA-256 digest")
        } else if (
            !CouncilContractHashing.constantTimeEquals(
                suite.atlasHash,
                context.atlasHash
            )
        ) {
            add("atlasHash does not match external Task-derived Atlas authority")
        }
        if (!isLowercaseSha256(suite.finalOutputSpecHash)) {
            add("finalOutputSpecHash must be one lowercase SHA-256 digest")
        } else if (
            !CouncilContractHashing.constantTimeEquals(
                suite.finalOutputSpecHash,
                hashFinalOutputSpec(context.finalOutputSpec)
            )
        ) {
            add("finalOutputSpecHash does not match external receiver output authority")
        }
        if (
            suite.assertionLanguageVersion !=
            GeneralTestDefinitionV1Codec.ASSERTION_LANGUAGE_VERSION
        ) {
            add("assertionLanguageVersion is unsupported")
        }
        if (suite.evaluatorVersion != GeneralTestCompiler.EVALUATOR_VERSION) {
            add("evaluatorVersion is unsupported")
        }
        if (suite.tests.isEmpty()) {
            add("tests must not be empty")
        }
        if (suite.tests.size > SealedTestSuiteV3Limits.MAX_TESTS_PER_SUITE) {
            add("tests exceed the ${SealedTestSuiteV3Limits.MAX_TESTS_PER_SUITE}-test limit")
        }
        if (suite.tests.map(SealedTestV3::testId).distinct().size != suite.tests.size) {
            add("test IDs must be unique")
        }
        if (suite.tests != suite.tests.sortedBy(SealedTestV3::testId)) {
            add("tests must be ordered by testId")
        }
        if (
            suite.tests.map(SealedTestV3::testId).toSet() !=
            context.tests.map(SealedTestIdentityAuthorityV3::testId).toSet()
        ) {
            add("tests do not exactly match Task-derived validation authority")
        }
        suite.tests.forEachIndexed { index, test ->
            validateTest(suite, test, context).forEach { error ->
                add("tests[$index].$error")
            }
        }
        if (!suite.hasValidHash()) {
            add("suiteHash does not match canonical suite content")
        }
    }

    /**
     * Returns whether [suite] is one valid active v3 authority.
     *
     * @param suite Candidate suite.
     * @param context Exact external output and Task-derived test authority.
     * @return Whether every structural, semantic, and hash invariant passes.
     */
    fun isValid(
        suite: SealedTestSuiteV3,
        context: SealedTestSuiteV3ValidationContext
    ): Boolean =
        validationErrors(suite, context).isEmpty()

    /**
     * Requires [suite] to satisfy every active v3 authority invariant.
     *
     * @param suite Candidate suite.
     * @param context Exact external output and Task-derived test authority.
     * @throws IllegalArgumentException when validation fails.
     */
    fun requireValid(
        suite: SealedTestSuiteV3,
        context: SealedTestSuiteV3ValidationContext
    ) {
        val errors = validationErrors(suite, context)
        require(errors.isEmpty()) {
            "Invalid sealed-tests-v3 authority: ${errors.joinToString("; ")}"
        }
    }

    /**
     * Derives the worker-safe commitments from one verified common suite.
     *
     * @param suite Verified common suite.
     * @param context Exact external output and Task-derived test authority.
     * @return Projection retaining the exact source suite and test commitments.
     */
    fun commitmentView(
        suite: SealedTestSuiteV3,
        context: SealedTestSuiteV3ValidationContext
    ): SealedTestSuiteV3CommitmentView {
        requireValid(suite, context)
        return SealedTestSuiteV3CommitmentView(
            sourceSuiteSchemaVersion = suite.schemaVersion,
            sourceSuiteHash = suite.suiteHash,
            rubricSchemaVersion = suite.rubricSchemaVersion,
            atlasHash = suite.atlasHash,
            finalOutputSpecHash = suite.finalOutputSpecHash,
            assertionLanguageVersion = suite.assertionLanguageVersion,
            evaluatorVersion = suite.evaluatorVersion,
            tests = suite.tests.map { test ->
                SealedTestCommitmentV3(
                    testId = test.testId,
                    nodeId = test.nodeId,
                    kind = when (test) {
                        is SealedTestV3.General -> SealedTestKindV3.GENERAL
                        is SealedTestV3.Promotion -> SealedTestKindV3.PROMOTION
                    },
                    opportunityId = (test as? SealedTestV3.Promotion)?.opportunityId,
                    acceptedArtifactTypes = test.acceptedArtifactTypes,
                    definitionHash = test.definitionHash,
                    testHash = test.testHash
                )
            }
        ).withComputedHash()
    }

    /**
     * Hashes the exact receiver output contract paired with a suite.
     *
     * @param outputSpec Exact receiver output contract.
     * @return Lowercase canonical SHA-256 digest.
     */
    fun hashFinalOutputSpec(outputSpec: FinalOutputSpec): String =
        CouncilContractHashing.sha256(
            CouncilContractHashing.canonicalJson(
                contractJson.encodeToJsonElement(FinalOutputSpec.serializer(), outputSpec)
            )
        )

    /**
     * Returns every deterministic worker-projection integrity violation.
     *
     * @param view Candidate worker-safe projection.
     * @return Ordered validation errors; empty means internally valid.
     */
    fun commitmentViewValidationErrors(
        view: SealedTestSuiteV3CommitmentView
    ): List<String> = buildList {
        if (view.schemaVersion != COMMITMENT_VIEW_SCHEMA_VERSION) {
            add("schemaVersion must be $COMMITMENT_VIEW_SCHEMA_VERSION")
        }
        if (view.sourceSuiteSchemaVersion != SCHEMA_VERSION) {
            add("sourceSuiteSchemaVersion must be $SCHEMA_VERSION")
        }
        if (!isLowercaseSha256(view.sourceSuiteHash)) {
            add("sourceSuiteHash must be one lowercase SHA-256 digest")
        }
        if (view.rubricSchemaVersion.isBlank()) {
            add("rubricSchemaVersion must not be blank")
        }
        if (!isLowercaseSha256(view.atlasHash)) {
            add("atlasHash must be one lowercase SHA-256 digest")
        }
        if (!isLowercaseSha256(view.finalOutputSpecHash)) {
            add("finalOutputSpecHash must be one lowercase SHA-256 digest")
        }
        if (
            view.assertionLanguageVersion !=
            GeneralTestDefinitionV1Codec.ASSERTION_LANGUAGE_VERSION
        ) {
            add("assertionLanguageVersion is unsupported")
        }
        if (view.evaluatorVersion != GeneralTestCompiler.EVALUATOR_VERSION) {
            add("evaluatorVersion is unsupported")
        }
        if (view.tests.isEmpty()) {
            add("tests must not be empty")
        }
        if (view.tests.size > SealedTestSuiteV3Limits.MAX_TESTS_PER_SUITE) {
            add("tests exceed the ${SealedTestSuiteV3Limits.MAX_TESTS_PER_SUITE}-test limit")
        }
        if (view.tests.map(SealedTestCommitmentV3::testId).distinct().size != view.tests.size) {
            add("test IDs must be unique")
        }
        if (view.tests != view.tests.sortedBy(SealedTestCommitmentV3::testId)) {
            add("tests must be ordered by testId")
        }
        view.tests.forEachIndexed { index, test ->
            if (test.testId.isBlank()) add("tests[$index].testId must not be blank")
            if (test.nodeId.isBlank()) add("tests[$index].nodeId must not be blank")
            if (test.acceptedArtifactTypes.isEmpty()) {
                add("tests[$index].acceptedArtifactTypes must not be empty")
            }
            if (
                test.acceptedArtifactTypes.distinct().size !=
                test.acceptedArtifactTypes.size
            ) {
                add("tests[$index].acceptedArtifactTypes must be unique")
            }
            if (
                test.acceptedArtifactTypes !=
                test.acceptedArtifactTypes.sortedBy(ArtifactType::name)
            ) {
                add("tests[$index].acceptedArtifactTypes must be ordered by name")
            }
            if (!isLowercaseSha256(test.definitionHash)) {
                add("tests[$index].definitionHash must be one lowercase SHA-256 digest")
            }
            if (!isLowercaseSha256(test.testHash)) {
                add("tests[$index].testHash must be one lowercase SHA-256 digest")
            }
            when (test.kind) {
                SealedTestKindV3.GENERAL -> if (test.opportunityId != null) {
                    add("tests[$index].GENERAL opportunityId must be null")
                }
                SealedTestKindV3.PROMOTION -> if (test.opportunityId.isNullOrBlank()) {
                    add("tests[$index].PROMOTION opportunityId must not be blank")
                }
            }
        }
        if (!view.hasValidHash()) {
            add("viewHash does not match canonical commitment-view content")
        }
    }

    /**
     * Requires [view] to satisfy its complete self-contained integrity contract.
     *
     * @param view Candidate worker-safe projection.
     * @throws IllegalArgumentException when validation fails.
     */
    fun requireValidCommitmentView(view: SealedTestSuiteV3CommitmentView) {
        val errors = commitmentViewValidationErrors(view)
        require(errors.isEmpty()) {
            "Invalid sealed-tests-v3 commitment view: ${errors.joinToString("; ")}"
        }
    }

    /**
     * Verifies that [view] is exactly the projection of [suite].
     *
     * @param view Candidate worker-safe projection.
     * @param suite Exact common private authority.
     * @param context Exact external output and Task-derived test authority.
     * @return Whether the projection is internally valid and byte-for-byte derivable.
     */
    fun verifyCommitmentView(
        view: SealedTestSuiteV3CommitmentView,
        suite: SealedTestSuiteV3,
        context: SealedTestSuiteV3ValidationContext
    ): Boolean {
        if (!isValid(suite, context)) return false
        if (commitmentViewValidationErrors(view).isNotEmpty()) return false
        return view == commitmentView(suite, context)
    }

    private fun canonicalizeTest(test: SealedTestV3): SealedTestV3 {
        require(test.acceptedArtifactTypes.isNotEmpty()) {
            "Sealed test '${test.testId}' requires accepted artifact types"
        }
        require(
            test.acceptedArtifactTypes.distinct().size == test.acceptedArtifactTypes.size
        ) {
            "Sealed test '${test.testId}' repeats an accepted artifact type"
        }
        val types = test.acceptedArtifactTypes.sortedBy(ArtifactType::name)
        return when (test) {
            is SealedTestV3.General ->
                test.copy(acceptedArtifactTypes = types, testHash = "").withComputedHash()
            is SealedTestV3.Promotion ->
                test.copy(acceptedArtifactTypes = types, testHash = "").withComputedHash()
        }
    }

    private fun validateTest(
        suite: SealedTestSuiteV3,
        test: SealedTestV3,
        context: SealedTestSuiteV3ValidationContext
    ): List<String> = buildList {
        if (test.testId.isBlank()) add("testId must not be blank")
        if (test.nodeId.isBlank()) add("nodeId must not be blank")
        if (test.acceptedArtifactTypes.isEmpty()) {
            add("acceptedArtifactTypes must not be empty")
        }
        if (test.acceptedArtifactTypes.distinct().size != test.acceptedArtifactTypes.size) {
            add("acceptedArtifactTypes must be unique")
        }
        if (
            test.acceptedArtifactTypes !=
            test.acceptedArtifactTypes.sortedBy(ArtifactType::name)
        ) {
            add("acceptedArtifactTypes must be ordered by name")
        }
        val expectedDefinitionHash =
            SealedTestDefinitionV3Codec.hash(test.sourceDefinition())
        if (
            !CouncilContractHashing.constantTimeEquals(
                test.definitionHash,
                expectedDefinitionHash
            )
        ) {
            add("definitionHash does not match the closed source definition")
        }
        if (!test.hasValidHash()) {
            add("testHash does not match canonical test content")
        }
        val expectedAuthority = context.find(test.testId)
        if (expectedAuthority == null) {
            add("testId is absent from Task-derived validation authority")
        } else if (expectedAuthority.nodeId != test.nodeId) {
            add("nodeId differs from Task-derived validation authority")
        }
        when (test) {
            is SealedTestV3.General -> {
                if (
                    expectedAuthority != null &&
                    expectedAuthority !is SealedTestIdentityAuthorityV3.General
                ) {
                    add("test kind differs from Task-derived validation authority")
                }
                if (test.compiledPlan.testId != test.testId) {
                    add("compiledPlan.testId does not match testId")
                }
                if (!test.compiledPlan.hasValidHash()) {
                    add("compiledPlan.planHash is invalid")
                }
                if (
                    test.compiledPlan.assertionLanguageVersion !=
                    suite.assertionLanguageVersion
                ) {
                    add("compiledPlan assertion language differs from suite authority")
                }
                if (test.compiledPlan.evaluatorVersion != suite.evaluatorVersion) {
                    add("compiledPlan evaluator differs from suite authority")
                }
                if (
                    !CouncilContractHashing.constantTimeEquals(
                        test.compiledPlan.definitionHash,
                        GeneralTestDefinitionV1Codec.hash(test.definition)
                    )
                ) {
                    add("compiledPlan definitionHash does not match GENERAL definition")
                }
                if (
                    !GeneralTestCompiler.verify(
                        plan = test.compiledPlan,
                        definition = test.definition,
                        outputSpec = context.finalOutputSpec
                    )
                ) {
                    add(
                        "compiledPlan does not deterministically recompile against " +
                            "external receiver output authority"
                    )
                }
            }
            is SealedTestV3.Promotion -> {
                val promotionAuthority =
                    expectedAuthority as? SealedTestIdentityAuthorityV3.Promotion
                if (expectedAuthority != null && promotionAuthority == null) {
                    add("test kind differs from Task-derived validation authority")
                }
                if (test.opportunityId.isBlank()) {
                    add("opportunityId must not be blank")
                }
                if (!isLowercaseSha256(test.fixtureSchemaHash)) {
                    add("fixtureSchemaHash must be one lowercase SHA-256 digest")
                }
                if (promotionAuthority != null) {
                    if (test.opportunityId != promotionAuthority.opportunityId) {
                        add("opportunityId differs from Task-derived validation authority")
                    }
                    if (
                        !CouncilContractHashing.constantTimeEquals(
                            test.fixtureSchemaHash,
                            promotionAuthority.fixtureSchema.schemaKey
                        )
                    ) {
                        add(
                            "fixtureSchemaHash differs from Task-derived validation authority"
                        )
                    }
                    TypedToolSchemaValidator.validationFailure(
                        TypedJsonValueCodec.toJsonElement(test.input),
                        promotionAuthority.fixtureSchema.inputSchema
                    )?.let { failure ->
                        add(
                            "input violates Task-derived fixture schema: " +
                                failure.stableCode
                        )
                    }
                    TypedToolSchemaValidator.validationFailure(
                        TypedJsonValueCodec.toJsonElement(test.expectedOutput),
                        promotionAuthority.fixtureSchema.outputSchema
                    )?.let { failure ->
                        add(
                            "expectedOutput violates Task-derived fixture schema: " +
                                failure.stableCode
                        )
                    }
                }
            }
        }
    }

}
