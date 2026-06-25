package com.achulkov.evokammer.substrate

import com.achulkov.evokammer.core.contract.DeliverableArtifactType
import com.achulkov.evokammer.core.contract.DeliverableNodeGradeHistoryEntry
import com.achulkov.evokammer.core.contract.DeliverableSkeletonNode
import com.achulkov.evokammer.core.contract.DeliverableSkeletonNodeStatus
import com.achulkov.evokammer.core.contract.DeliverableSkeletonNodeUpdate
import com.achulkov.evokammer.core.contract.DeliverableSkeletonTree
import com.achulkov.evokammer.core.contract.DeliverableSkeletonTreeSummary
import com.achulkov.evokammer.core.contract.DstRubricPolicy
import com.achulkov.evokammer.core.contract.StartingField
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * File-backed store for per-group deliverable skeleton trees.
 *
 * The store lives under the run's control directory to avoid destructive Room migrations and
 * to keep the tree durable across process restarts.
 *
 * @param substrateFactory Factory used to resolve per-run/per-group control directories.
 */
class DeliverableSkeletonTreeStore(
    private val substrateFactory: SubstrateDatabaseFactory,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = false }
) {
    private val fileName = "deliverable-skeleton-tree.json"

    /**
     * Resolves the durable tree file for a given group.
     *
     * @param runId Active run identifier.
     * @param groupId Group identifier.
     * @return Tree file path.
     */
    fun resolveFile(runId: String, groupId: String): File =
        File(substrateFactory.groupControlDir(runId, groupId), fileName)

    /**
     * Loads the tree from disk if it exists.
     *
     * @param runId Active run identifier.
     * @param groupId Group identifier.
     * @return Loaded tree or null when absent/unparseable.
     */
    fun read(runId: String, groupId: String): DeliverableSkeletonTree? {
        val file = resolveFile(runId, groupId)
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(DeliverableSkeletonTree.serializer(), file.readText()) }.getOrNull()
    }

    /**
     * Returns the compact tree summary if available.
     *
     * @param runId Active run identifier.
     * @param groupId Group identifier.
     * @return The tree summary or null.
     */
    fun readSummary(runId: String, groupId: String): DeliverableSkeletonTreeSummary? =
        read(runId, groupId)?.summarize()

    /**
     * Loads the tree or synthesises one from the StartingField seed, then persists it.
     *
     * @param runId Active run identifier.
     * @param groupId Group identifier.
     * @param startingField Current run seed.
     * @return The canonical initial tree.
     */
    fun initialise(runId: String, groupId: String, startingField: StartingField): DeliverableSkeletonTree {
        val existing = read(runId, groupId)
        if (existing != null) return existing

        val tree = try {
            val raw = startingField.deliverableSkeletonTreeJson.trim()
            when {
                raw.isNotBlank() && raw != "{}" -> json.decodeFromString(
                    DeliverableSkeletonTree.serializer(),
                    raw
                )
                else -> DeliverableSkeletonTree.fromNodeGraph(
                    runId = runId,
                    groupId = groupId,
                    nodeGraphJson = startingField.nodeGraph,
                    objective = startingField.objective
                )
            }
        } catch (_: Exception) {
            DeliverableSkeletonTree.fromNodeGraph(
                runId = runId,
                groupId = groupId,
                nodeGraphJson = startingField.nodeGraph,
                objective = startingField.objective
            )
        }

        write(tree)
        return tree
    }

    /**
     * Applies a node update to the persisted tree if the node exists.
     *
     * @param runId Active run identifier.
     * @param groupId Group identifier.
     * @param update Node update to apply.
     * @return Updated tree, or null when the node is unknown.
     */
    fun applyUpdate(runId: String, groupId: String, update: DeliverableSkeletonNodeUpdate): DeliverableSkeletonTree? {
        val tree = read(runId, groupId) ?: return null
        val node = tree.nodes.firstOrNull { it.nodeId == update.dstNodeRef } ?: return null
        val updatedTrust = update.trustScore

        val nextHistory = buildList {
            addAll(node.gradingHistory.takeLast(4))
            if (update.checkinId != null || update.rubricVersion != null || update.dimensionScores.isNotEmpty()) {
                add(
                    DeliverableNodeGradeHistoryEntry(
                        checkinId = update.checkinId ?: "critic-${tree.version}",
                        dimensionScores = update.dimensionScores,
                        graderIds = update.graderIds,
                        rubricVersion = update.rubricVersion ?: "unknown",
                        graderNotes = update.graderNotes,
                        passedThreshold = update.passedThreshold ?: false
                    )
                )
            }
        }

        val nextOpenGaps = (node.openGaps + update.graderNotes).distinct().take(20)
        val nextStatus = when {
            update.passedThreshold == false -> DeliverableSkeletonNodeStatus.CONTESTED
            update.artifactRef != null && updatedTrust != null && updatedTrust >= 0.8 ->
                DeliverableSkeletonNodeStatus.COMPLETE
            update.artifactRef != null -> DeliverableSkeletonNodeStatus.IN_PROGRESS
            else -> node.status
        }

        // Phase 7.5: Dynamically evolve grader complexity level
        val nextComplexity = evolveComplexity(node, update, nextHistory)

        val updatedNode = node.copy(
            status = nextStatus,
            currentArtifactRef = update.artifactRef ?: node.currentArtifactRef,
            artifactVersion = if (update.artifactRef != null) node.artifactVersion + 1 else node.artifactVersion,
            artifactType = update.artifactType ?: node.artifactType,
            owningGeneration = update.owningGeneration ?: node.owningGeneration,
            owningAgentId = update.owningAgentId ?: node.owningAgentId,
            owningRole = update.owningRole ?: node.owningRole,
            trustScore = updatedTrust ?: node.trustScore,
            graderComplexityLevel = nextComplexity,
            gradingHistory = nextHistory,
            openGaps = nextOpenGaps,
            updatedAtGeneration = update.owningGeneration ?: node.updatedAtGeneration,
            citationAgentChain = update.owningAgentId?.let { node.citationAgentChain + it } ?: node.citationAgentChain
        )

        val updatedTree = tree.replaceNode(updatedNode)
        write(updatedTree)
        return updatedTree
    }

    /**
     * Persists the given tree atomically.
     *
     * @param tree Tree to write.
     */
    fun write(tree: DeliverableSkeletonTree) {
        val file = resolveFile(tree.runId, tree.groupId)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(DeliverableSkeletonTree.serializer(), tree))
        Files.move(
            tmp.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        )
    }

    /**
     * Builds a compact, human-readable tree summary for logs and prompts.
     *
     * @param runId Active run identifier.
     * @param groupId Group identifier.
     * @return Compact summary text.
     */
    fun summaryText(runId: String, groupId: String): String {
        val summary = readSummary(runId, groupId) ?: return "deliverable tree unavailable"
        return buildString {
            append("nodes=").append(summary.totalNodes)
            append(", openGaps=").append(summary.openGapCount)
            append(", contested=").append(summary.contestedNodeCount)
            append(", deadEnds=").append(summary.deadEndCount)
            append(", trust=").append(String.format(java.util.Locale.ROOT, "%.2f", summary.averageTrustScore))
            append(", maxComplexity=").append(summary.maxComplexityLevel)
        }
    }

    /**
     * Applies rubric policies to all matching nodes in the tree, bumping complexity
     * levels to at least the policy minimum.
     *
     * @param runId Active run identifier.
     * @param groupId Group identifier.
     * @param policies Policies to apply.
     * @return Updated tree, or null when the tree is absent.
     */
    fun applyPolicies(runId: String, groupId: String, policies: List<DstRubricPolicy>): DeliverableSkeletonTree? {
        if (policies.isEmpty()) return read(runId, groupId)
        var tree = read(runId, groupId) ?: return null
        var changed = false

        val updatedNodes = tree.nodes.map { node ->
            val relevantPolicy = policies.firstOrNull { it.nodeType == node.nodeType }
            if (relevantPolicy != null && node.graderComplexityLevel < relevantPolicy.minComplexityLevel) {
                changed = true
                node.copy(graderComplexityLevel = relevantPolicy.minComplexityLevel)
            } else {
                node
            }
        }

        if (!changed) return tree
        tree = tree.copy(nodes = updatedNodes, version = tree.version + 1, updatedAtMs = System.currentTimeMillis())
        write(tree)
        return tree
    }

    // ── Private helpers ─────────────────────────────────────────────────────────

    /**
     * Phase 7.5: Dynamically evolves grader complexity level based on grading history.
     *
     * Increases when:
     * - A new rubric version is introduced (indicates more nuanced grading).
     * - New rubric dimensions are evaluated (more aspects being checked).
     * - The node has accumulated multiple grading rounds without reaching COMPLETE.
     *
     * Never decreases. Capped at 10.
     */
    private fun evolveComplexity(
        node: DeliverableSkeletonNode,
        update: DeliverableSkeletonNodeUpdate,
        nextHistory: List<DeliverableNodeGradeHistoryEntry>
    ): Int {
        var level = node.graderComplexityLevel

        // Bump when rubric version changes (new grader nuance)
        val prevVersions = node.gradingHistory.map { it.rubricVersion }.distinct()
        val newVersion = update.rubricVersion
        if (newVersion != null && newVersion !in prevVersions && newVersion != "unknown" && newVersion != "critic-promotion") {
            level += 1
        }

        // Bump when new dimension scores are introduced
        val prevDimIds = node.gradingHistory.flatMap { it.dimensionScores.keys }.toSet()
        val newDimIds = update.dimensionScores.keys.filterNot { it in prevDimIds }
        if (newDimIds.isNotEmpty()) {
            level += 1
        }

        // Bump when the node has been through 3+ grading rounds without completing
        if (node.status != DeliverableSkeletonNodeStatus.COMPLETE && nextHistory.size >= 3) {
            level += 1
        }

        // Bump when the node is CONTESTED for multiple rounds
        if (node.status == DeliverableSkeletonNodeStatus.CONTESTED) {
            level += 1
        }

        return level.coerceIn(node.graderComplexityLevel, 10)
    }
}
