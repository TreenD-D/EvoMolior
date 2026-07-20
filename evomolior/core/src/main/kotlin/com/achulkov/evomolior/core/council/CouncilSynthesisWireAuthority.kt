package com.achulkov.evomolior.core.council

import com.achulkov.evomolior.core.atlas.ToolOpportunity
import com.achulkov.evomolior.core.atlas.TypedToolSchemaCompiler
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Exact executable schemas governing one or more promotion fixtures on the provider wire.
 *
 * The schema catalog is provider-only authority. Persisted Synthesis output and commitment
 * hashing continue to use the established domain serializers.
 *
 * @param schemaKey Deterministic identity for the exact input/output schema pair.
 * @param inputSchema Exact Task-authored executable input schema.
 * @param outputSchema Exact Task-authored executable output schema.
 */
@Serializable
data class CouncilPromotionFixtureWireSchemaAuthority(
    val schemaKey: String,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject
) {
    init {
        require(schemaKey == schemaKey(inputSchema, outputSchema)) {
            "Council promotion fixture schema key must match its exact schema pair"
        }
        require(inputSchema["type"]?.jsonPrimitive?.content == "object") {
            "Council promotion fixture input schema must have an object root"
        }
        require(outputSchema["type"]?.jsonPrimitive?.content == "object") {
            "Council promotion fixture output schema must have an object root"
        }
    }

    companion object {
        /**
         * Builds exact promotion-fixture authority from one Task-authored opportunity.
         *
         * @param opportunity Tool opportunity declaring the promotion fixtures.
         * @return Deterministic provider-wire schema authority.
         */
        fun from(opportunity: ToolOpportunity): CouncilPromotionFixtureWireSchemaAuthority =
            fromSchemas(
                inputSchema = TypedToolSchemaCompiler.compileSchema(
                    opportunity.requiredInputs
                ),
                outputSchema = TypedToolSchemaCompiler.compileSchema(
                    opportunity.requiredOutputs
                )
            )

        /**
         * Builds exact promotion-fixture authority from compiled schemas.
         *
         * @param inputSchema Exact executable input schema.
         * @param outputSchema Exact executable output schema.
         * @return Deterministic provider-wire schema authority.
         */
        fun fromSchemas(
            inputSchema: JsonObject,
            outputSchema: JsonObject
        ): CouncilPromotionFixtureWireSchemaAuthority =
            CouncilPromotionFixtureWireSchemaAuthority(
                schemaKey = schemaKey(inputSchema, outputSchema),
                inputSchema = inputSchema,
                outputSchema = outputSchema
            )

        private fun schemaKey(inputSchema: JsonObject, outputSchema: JsonObject): String =
            CouncilContractHashing.sha256(
                CouncilContractHashing.canonicalJson(inputSchema) +
                    "\u0000" +
                    CouncilContractHashing.canonicalJson(outputSchema)
            )
    }
}

/**
 * Provider-wire shape required for one Task-declared sealed test.
 */
@Serializable
enum class CouncilSealedTestWireKind {
    /** Tool promotion fixture with exactly `input` and `expectedOutput`. */
    PROMOTION,

    /** Closed typed deterministic general assertion definition. */
    GENERAL
}

/**
 * One exact Task-derived sealed-test identity available to Council Synthesis.
 *
 * The provider emits [authorityKey] rather than independently repeating [testKey] and [nodeKey].
 * This prevents a response from reclassifying or rebinding a known promotion test.
 *
 * @param authorityKey Deterministic provider-only identity.
 * @param testKey Task-declared test identity restored into the domain output.
 * @param nodeKey Task-declared node identity restored into the domain output.
 * @param kind Required provider-wire definition shape.
 * @param promotionSchemaKey Exact promotion-fixture schema authority, or null for general tests.
 */
@Serializable
data class CouncilSealedTestWireAuthority(
    val authorityKey: String,
    val testKey: String,
    val nodeKey: LocalNodeKey,
    val kind: CouncilSealedTestWireKind,
    val promotionSchemaKey: String? = null
) {
    init {
        require(
            (kind == CouncilSealedTestWireKind.PROMOTION) ==
                (promotionSchemaKey != null)
        ) {
            "Council promotion fixture schema authority must exist exactly for promotion tests"
        }
    }
}

/**
 * Complete provider-wire sealed-test authority derived from the current Task Anatomist output.
 *
 * @param entries Deterministically ordered exact test identities.
 * @param promotionSchemas Deduplicated exact executable schema pairs used by promotion entries.
 */
@Serializable
data class CouncilSynthesisWireAuthority(
    val entries: List<CouncilSealedTestWireAuthority>,
    val promotionSchemas: List<CouncilPromotionFixtureWireSchemaAuthority> = emptyList()
) {
    @Transient
    private val byAuthorityKey = entries.associateBy(CouncilSealedTestWireAuthority::authorityKey)

    @Transient
    private val byDomainIdentity = entries.associateBy { it.testKey to it.nodeKey }

    @Transient
    private val promotionSchemasByKey =
        promotionSchemas.associateBy(CouncilPromotionFixtureWireSchemaAuthority::schemaKey)

    init {
        require(byAuthorityKey.size == entries.size) {
            "Council Synthesis authority keys must be unique"
        }
        require(byDomainIdentity.size == entries.size) {
            "Council Synthesis test/node identities must be unique"
        }
        require(entries == entries.sortedBy(CouncilSealedTestWireAuthority::authorityKey)) {
            "Council Synthesis wire authority must be deterministically ordered"
        }
        require(promotionSchemasByKey.size == promotionSchemas.size) {
            "Council promotion fixture schema keys must be unique"
        }
        require(
            promotionSchemas ==
                promotionSchemas.sortedBy(CouncilPromotionFixtureWireSchemaAuthority::schemaKey)
        ) {
            "Council promotion fixture schemas must be deterministically ordered"
        }
        val referencedPromotionSchemas = entries.mapNotNull(
            CouncilSealedTestWireAuthority::promotionSchemaKey
        ).toSet()
        require(referencedPromotionSchemas == promotionSchemasByKey.keys) {
            "Council promotion fixture schema catalog must exactly match promotion entries"
        }
    }

    /**
     * Resolves one provider-only authority key.
     *
     * @param authorityKey Provider-emitted identity.
     * @return Exact Task authority, or null when unknown.
     */
    fun findByAuthorityKey(authorityKey: String): CouncilSealedTestWireAuthority? =
        byAuthorityKey[authorityKey]

    /**
     * Resolves one persisted domain test identity.
     *
     * @param testKey Test identity.
     * @param nodeKey Declaring node identity.
     * @return Exact Task authority, or null when undeclared.
     */
    fun findByDomainIdentity(
        testKey: String,
        nodeKey: LocalNodeKey
    ): CouncilSealedTestWireAuthority? = byDomainIdentity[testKey to nodeKey]

    /**
     * Resolves the exact executable schema pair for one promotion entry.
     *
     * @param entry Task-derived promotion authority.
     * @return Exact provider-wire fixture schema authority.
     */
    fun promotionSchemaFor(
        entry: CouncilSealedTestWireAuthority
    ): CouncilPromotionFixtureWireSchemaAuthority {
        require(entry.kind == CouncilSealedTestWireKind.PROMOTION) {
            "General sealed tests do not have promotion fixture schemas"
        }
        return promotionSchemasByKey.getValue(requireNotNull(entry.promotionSchemaKey))
    }

    companion object {
        /**
         * Derives the complete exact test authority from [taskAnatomist].
         *
         * A test declared as both executable and promotional on the same node becomes one
         * promotion authority. Cross-node duplicate IDs remain distinct here so the deterministic
         * field compiler can report the pre-existing Task defect at its authoritative path.
         *
         * @param taskAnatomist Current strictly decoded Task Anatomist output.
         * @return Deterministically ordered provider-wire authority.
         */
        fun from(taskAnatomist: TaskAnatomistOutput): CouncilSynthesisWireAuthority {
            val promotionSchemas = linkedMapOf<String, CouncilPromotionFixtureWireSchemaAuthority>()
            val entries = taskAnatomist.nodes
                .flatMap { node ->
                    val promotionIds = node.toolOpportunity?.promotionTestIds.orEmpty()
                    val promotionSchema = node.toolOpportunity
                        ?.takeIf { promotionIds.isNotEmpty() }
                        ?.let(CouncilPromotionFixtureWireSchemaAuthority::from)
                    promotionSchema?.let { schema ->
                        val existing = promotionSchemas.putIfAbsent(schema.schemaKey, schema)
                        require(existing == null || existing == schema) {
                            "Council promotion fixture schema hash collision"
                        }
                    }
                    (node.proofContract.executableTestIds + promotionIds)
                        .sorted()
                        .map { testKey ->
                            val kind = if (testKey in promotionIds) {
                                CouncilSealedTestWireKind.PROMOTION
                            } else {
                                CouncilSealedTestWireKind.GENERAL
                            }
                            CouncilSealedTestWireAuthority(
                                authorityKey = CouncilContractHashing.sha256(
                                    "${kind.name}\u0000${node.key.value}\u0000$testKey"
                                ),
                                testKey = testKey,
                                nodeKey = node.key,
                                kind = kind,
                                promotionSchemaKey = promotionSchema
                                    ?.schemaKey
                                    ?.takeIf {
                                        kind == CouncilSealedTestWireKind.PROMOTION
                                    }
                            )
                        }
                }
                .sortedBy(CouncilSealedTestWireAuthority::authorityKey)
            return CouncilSynthesisWireAuthority(
                entries = entries,
                promotionSchemas = promotionSchemas.values.sortedBy(
                    CouncilPromotionFixtureWireSchemaAuthority::schemaKey
                )
            )
        }
    }
}
