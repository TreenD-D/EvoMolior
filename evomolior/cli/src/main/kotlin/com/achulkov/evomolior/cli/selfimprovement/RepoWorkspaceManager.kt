package com.achulkov.evomolior.cli.selfimprovement

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Prepares the mutable repository copy used by overseer cycles.
 *
 * The source checkout stays untouched; the loop mutates the isolated copy under the workspace
 * root so later cycles can read the rewritten tree.
 */
internal object RepoWorkspaceManager {
    private const val WORKSPACE_DIR_NAME = ".evomolior-self-improve"
    private const val WORKSPACE_REPO_DIR_NAME = "repo"

    /**
     * Returns the mutable repo copy for the current workspace, creating it once if needed.
     *
     * @param sourceRepoRoot Read-only source checkout.
     * @param workspaceRoot Isolated workspace root for the overseer run.
     */
    fun prepareMutableRepoCopy(sourceRepoRoot: Path, workspaceRoot: Path): Path {
        val repoCopyRoot = workspaceRoot
            .resolve(WORKSPACE_DIR_NAME)
            .resolve(WORKSPACE_REPO_DIR_NAME)
            .toAbsolutePath()
            .normalize()

        if (Files.exists(repoCopyRoot)) {
            return repoCopyRoot
        }

        Files.createDirectories(repoCopyRoot)
        copyRepository(sourceRepoRoot.toAbsolutePath().normalize(), repoCopyRoot)
        return repoCopyRoot
    }

    private fun copyRepository(sourceRepoRoot: Path, targetRoot: Path) {
        Files.walkFileTree(
            sourceRepoRoot,
            object : java.nio.file.SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes
                ): java.nio.file.FileVisitResult {
                    if (dir != sourceRepoRoot && shouldSkip(sourceRepoRoot.relativize(dir))) {
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE
                    }

                    val relative = sourceRepoRoot.relativize(dir)
                    val target = targetRoot.resolve(relative.toString())
                    Files.createDirectories(target)
                    return java.nio.file.FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes
                ): java.nio.file.FileVisitResult {
                    val relative = sourceRepoRoot.relativize(file)
                    if (shouldSkip(relative)) {
                        return java.nio.file.FileVisitResult.CONTINUE
                    }

                    val target = targetRoot.resolve(relative.toString())
                    Files.createDirectories(target.parent)
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            }
        )
    }

    private fun shouldSkip(relativePath: Path): Boolean {
        val normalized = relativePath.toString().replace(File.separatorChar, '/')
        return normalized == ".git" ||
            normalized.startsWith(".git/") ||
            normalized == ".gradle" ||
            normalized.startsWith(".gradle/") ||
            normalized == ".evomolior-data" ||
            normalized.startsWith(".evomolior-data/") ||
            normalized == ".evomolior-self-improve" ||
            normalized.startsWith(".evomolior-self-improve/") ||
            normalized == ".pipeline-inputs" ||
            normalized.startsWith(".pipeline-inputs/") ||
            normalized == "outputs" ||
            normalized.startsWith("outputs/") ||
            normalized == "build" ||
            normalized.startsWith("build/") ||
            normalized.contains("/build/") ||
            normalized == "out" ||
            normalized.startsWith("out/") ||
            normalized.contains("/out/") ||
            normalized == ".idea" ||
            normalized.startsWith(".idea/") ||
            normalized == "documentation/tmp" ||
            normalized.startsWith("documentation/tmp/") ||
            normalized.contains("/documentation/tmp/") ||
            normalized.endsWith(".iml")
    }
}
