package com.achulkov.evomolior.competition.grader

import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeScoreVector
import com.achulkov.evomolior.core.grader.BoundedScore
import com.achulkov.evomolior.core.grader.GraderBundleValidationFailure
import com.achulkov.evomolior.core.grader.GraderScoreBundle
import com.achulkov.evomolior.core.grader.GraderScoreBundleStatus
import com.achulkov.evomolior.core.grader.GradingRequirementId
import com.achulkov.evomolior.core.grader.RequirementResult
import com.achulkov.evomolior.core.grader.RequirementResultStatus
import com.achulkov.evomolior.core.grader.ScoreNormalizationTrace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertTrue

class ExactGraderBundleResolverTest {
    private val resolver = ExactGraderBundleResolver()
    private val graders = setOf("grader-a", "grader-b")
    private val targets = setOf(AtlasGroupId("worker-a"), AtlasGroupId("worker-b"))

    @Test
    fun `complete exact cardinality verifies snapshots and aggregates quorum`() {
        val bundles = graders.flatMap { grader ->
            targets.map { target ->
                bundle(grader, target, if (grader == "grader-a") 0.7 else 0.9)
            }
        }.reversed()

        val verdict = resolver.resolve("run-1", 1, graders, targets, bundles)

        assertEquals(ExactGraderRoundStatus.VALID, verdict.status)
        assertEquals(4, verdict.acceptedBundleHashes.size)
        assertEquals(
            0.8,
            verdict.nodeScores.getValue(AtlasGroupId("worker-a"))
                .getValue(NodeId("node-1")).correctness,
            absoluteTolerance = 1e-9
        )
        assertEquals(0.2, verdict.maximumNodeDisagreement!!, absoluteTolerance = 1e-9)
    }

    @Test
    fun `missing duplicate invalid abstained and snapshot mismatch fail closed`() {
        val complete = graders.flatMap { grader ->
            targets.map { target -> bundle(grader, target, 0.8) }
        }
        assertEquals(
            ExactGraderRoundStatus.INVALID,
            resolver.resolve("run-1", 1, graders, targets, complete.dropLast(1)).status
        )
        assertEquals(
            ExactGraderRoundStatus.INVALID,
            resolver.resolve("run-1", 1, graders, targets, complete + complete.first()).status
        )
        assertEquals(
            ExactGraderRoundStatus.INVALID,
            resolver.resolve(
                "run-1",
                1,
                graders,
                targets,
                complete.mapIndexed { index, bundle ->
                    if (index == 0) bundle.copy(
                        status = GraderScoreBundleStatus.INVALID,
                        payloadHash = ""
                    ).withComputedHash() else bundle
                }
            ).status
        )
        assertEquals(
            ExactGraderRoundStatus.ABSTAINED,
            resolver.resolve(
                "run-1",
                1,
                graders,
                targets,
                complete.mapIndexed { index, bundle ->
                    if (index == 0) bundle.copy(
                        status = GraderScoreBundleStatus.ABSTAINED,
                        payloadHash = ""
                    ).withComputedHash() else bundle
                }
            ).status
        )
        assertEquals(
            ExactGraderRoundStatus.INVALID,
            resolver.resolve(
                "run-1",
                1,
                graders,
                targets,
                complete.mapIndexed { index, bundle ->
                    if (index == 0) bundle.copy(
                        targetSnapshotHash = "different",
                        payloadHash = ""
                    ).withComputedHash() else bundle
                }
            ).status
        )
    }

    @Test
    fun `invalid bundle verdict identifies grader target and failed predicates`() {
        val complete = graders.flatMap { grader ->
            targets.map { target -> bundle(grader, target, 0.8) }
        }
        val invalid = complete.first().copy(
            nodeScores = emptyMap(),
            requirementResults = complete.first().requirementResults.mapValues {
                RequirementResult(RequirementResultStatus.NOT_APPLICABLE)
            },
            status = GraderScoreBundleStatus.INVALID,
            validationFailures = setOf(
                GraderBundleValidationFailure.ASSIGNMENT_RESPONSE_STRUCTURE_INVALID,
                GraderBundleValidationFailure.ALL_REQUIREMENTS_NOT_APPLICABLE
            ),
            payloadHash = ""
        ).withComputedHash()

        val verdict = resolver.resolve(
            "run-1",
            1,
            graders,
            targets,
            listOf(invalid) + complete.drop(1)
        )

        assertEquals(ExactGraderRoundStatus.INVALID, verdict.status)
        assertContains(verdict.reason.orEmpty(), "grader=grader-a,target=worker-a")
        assertContains(verdict.reason.orEmpty(), "assignment_response_structure_invalid")
        assertContains(verdict.reason.orEmpty(), "all_requirements_not_applicable")
    }

    @Test
    fun `complete quorum accepts empty node authority without synthetic scores`() {
        val noEvidenceTarget = AtlasGroupId("worker-b")
        val bundles = graders.flatMap { grader ->
            targets.map { target ->
                bundle(grader, target, 0.8).let { bundle ->
                    if (target == noEvidenceTarget) {
                        bundle.copy(
                            nodeScores = emptyMap(),
                            requirementResults = bundle.requirementResults.mapValues {
                                RequirementResult(RequirementResultStatus.NOT_APPLICABLE)
                            },
                            payloadHash = ""
                        ).withComputedHash()
                    } else {
                        bundle
                    }
                }
            }
        }

        val verdict = resolver.resolve("run-1", 1, graders, targets, bundles)

        assertEquals(ExactGraderRoundStatus.VALID, verdict.status)
        assertTrue(verdict.nodeScores.getValue(noEvidenceTarget).isEmpty())
        assertEquals(setOf(NodeId("node-1")), verdict.nodeScores.getValue(AtlasGroupId("worker-a")).keys)
    }

    @Test
    fun `single critical failure remains contested and cannot force quorum hard failure`() {
        val criticalRequirementId = GradingRequirementId("novel-critical")
        val bundles = graders.flatMap { grader ->
            targets.map { target ->
                bundle(grader, target, 0.8).let { bundle ->
                    if (grader == "grader-a" && target.value == "worker-a") {
                        bundle.copy(
                            nodeScores = bundle.nodeScores.mapValues {
                                it.value.copy(hardFailure = true)
                            },
                            requirementResults = bundle.requirementResults +
                                (criticalRequirementId to RequirementResult(
                                    status = RequirementResultStatus.FAIL,
                                    failureLevel = 3,
                                    criticalFailure = true
                                )),
                            payloadHash = ""
                        ).withComputedHash()
                    } else {
                        bundle
                    }
                }
            }
        }

        val verdict = resolver.resolve("run-1", 1, graders, targets, bundles)

        assertEquals(ExactGraderRoundStatus.VALID, verdict.status)
        assertEquals(
            setOf(criticalRequirementId),
            verdict.contestedCriticalRequirements.getValue(AtlasGroupId("worker-a"))
        )
        assertEquals(
            false,
            verdict.nodeScores.getValue(AtlasGroupId("worker-a"))
                .getValue(NodeId("node-1"))
                .hardFailure
        )
    }

    private fun bundle(
        graderGroupId: String,
        targetGroupId: AtlasGroupId,
        score: Double
    ) = GraderScoreBundle(
        bundleId = "bundle-$graderGroupId-${targetGroupId.value}",
        runId = "run-1",
        round = 1,
        graderGroupId = graderGroupId,
        targetGroupId = targetGroupId,
        graderAtlasHash = "grader-atlas",
        criteriaFreezeHash = "freeze-$graderGroupId",
        targetSnapshotHash = "snapshot-${targetGroupId.value}",
        nodeScores = mapOf(NodeId("node-1") to vector(score)),
        groupScores = mapOf("summary" to BoundedScore(score)),
        requirementResults = mapOf(
            GradingRequirementId("requirement-$graderGroupId") to
                RequirementResult(RequirementResultStatus.PASS)
        ),
        rationaleHashes = mapOf("summary" to "rationale-$graderGroupId"),
        normalizationTrace = listOf(
            ScoreNormalizationTrace("summary", score * 10, score, 1.0, "policy")
        ),
        status = GraderScoreBundleStatus.VALID
    ).withComputedHash()

    private fun vector(score: Double) = NodeScoreVector(
        correctness = score,
        evidenceQuality = score,
        reproducibility = score,
        robustness = score,
        integrationUtility = score,
        receiverRelevance = score,
        novelty = score,
        efficiency = score,
        hardFailure = false,
        confidence = score
    )
}
