package com.achulkov.evomolior.core.contract

import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProvisionalNovelHypothesisTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    @Test
    fun `hypothesis hash is stable and detects mutation`() {
        val hypothesis = validHypothesis().withComputedHash()

        assertTrue(hypothesis.hasValidHash())
        assertEquals(
            hypothesis,
            json.decodeFromString(
                ProvisionalNovelHypothesis.serializer(),
                json.encodeToString(hypothesis)
            )
        )
        assertNotEquals(
            hypothesis.hypothesisHash,
            AtlasHashing.hashProvisionalNovelHypothesis(hypothesis.copy(idea = "mutated"))
        )
        assertTrue(!hypothesis.copy(idea = "mutated").hasValidHash())
    }

    @Test
    fun `hypothesis rejects unsupported or non canonical fields`() {
        assertFailsWith<IllegalArgumentException> {
            validHypothesis(confidence = Double.NaN)
        }
        assertFailsWith<IllegalArgumentException> {
            validHypothesis(provenanceRefs = listOf("b", "a"))
        }
        assertFailsWith<IllegalArgumentException> {
            validHypothesis(status = HypothesisStatus.ACCEPTED)
        }
    }

    private fun validHypothesis(
        confidence: Double = 0.42,
        provenanceRefs: List<String> = listOf("artifact:read-1", "observation:round-2"),
        status: HypothesisStatus = HypothesisStatus.PROPOSED
    ) = ProvisionalNovelHypothesis(
        hypothesisId = "hypothesis-1",
        runId = "run-1",
        groupId = AtlasGroupId("group-1"),
        round = 2,
        agentId = AtlasAgentId("agent-1"),
        title = "Latent pairing",
        idea = "Candidate rows may be governed by a hidden pairing invariant.",
        inferredFrom = "Two repeated near-miss examples share the same positional symmetry.",
        assumptions = listOf("The examples are drawn from the same task family."),
        uncertainty = "The apparent symmetry may be an artifact of the sampled examples.",
        expectedImpact = "A pairing-aware search could reduce false candidate matches.",
        falsifiablePrediction = "The rule fails on a held-out example with the same surface symmetry.",
        minimalTestOrArtifact = "Run the pairing check on the next three held-out examples.",
        confidence = confidence,
        provenanceRefs = provenanceRefs,
        status = status
    )
}
