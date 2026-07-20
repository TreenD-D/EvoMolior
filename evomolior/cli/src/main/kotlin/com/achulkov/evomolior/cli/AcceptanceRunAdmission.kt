package com.achulkov.evomolior.cli

import com.achulkov.evomolior.agents.factory.CouncilAcceptanceSchemaAdmissionProbe
import com.achulkov.evomolior.api.service.AcceptanceAdmissionArtifact
import com.achulkov.evomolior.api.service.AcceptanceAdmissionCheck
import com.achulkov.evomolior.api.service.AcceptanceRunBoundary
import com.achulkov.evomolior.api.service.AcceptanceRunBoundaryVerifier
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.invariantSeparatorsPathString

internal const val ACCEPTANCE_MANIFEST_ENV = "EVOMOLIOR_ACCEPTANCE_MANIFEST"
internal const val ACCEPTANCE_LAUNCHER_ENV = "EVOMOLIOR_ACCEPTANCE_LAUNCHER"
internal const val ACCEPTANCE_CODEX_TMPDIR_ENV = "EVOMOLIOR_CODEX_TMPDIR"
internal const val ACCEPTANCE_JVM_TMPDIR_ENV = "EVOMOLIOR_ACCEPTANCE_JVM_TMPDIR"
internal const val ACCEPTANCE_EXTERNAL_VOLUME_ROOTS_ENV =
    "EVOMOLIOR_ACCEPTANCE_EXTERNAL_VOLUME_ROOTS"
internal const val ACCEPTANCE_ESTIMATED_ARCHIVE_BYTES_ENV =
    "EVOMOLIOR_ESTIMATED_MAXIMUM_RUN_ARCHIVE_BYTES"
private const val ACCEPTANCE_DISTRIBUTION_SCHEMA = "acceptance-distribution-manifest-v1"
private const val ACCEPTANCE_LOCK_SCHEMA = "appendix-b-acceptance-lock-v1"
private const val MINIMUM_FREE_BYTES = 10L * 1024L * 1024L * 1024L
private const val MAX_OWNER_DIAGNOSTIC_BYTES = 2_048

@Serializable
internal data class AcceptanceDistributionFileV1(
    val path: String,
    val sha256: String,
    val sizeBytes: Long
)

@Serializable
internal data class AcceptanceGateEvidenceV1(
    val path: String,
    val sha256: String
)

@Serializable
internal data class AcceptanceDistributionManifestV1(
    val schemaVersion: String = ACCEPTANCE_DISTRIBUTION_SCHEMA,
    val distributionId: String,
    val createdAtUtc: String,
    val repositoryRoot: String,
    val sourceRevision: String,
    val sourceTreeClean: Boolean,
    val javaVersion: String,
    val acceptanceJvmTmpDir: String,
    val mainClass: String,
    val configurationSha256: String,
    val modelAssignmentsSha256: String,
    val codexCliPath: String,
    val codexCliSha256: String,
    val codexCliVersion: String,
    val gateEvidence: List<AcceptanceGateEvidenceV1>,
    val sourceFiles: List<AcceptanceDistributionFileV1>,
    val files: List<AcceptanceDistributionFileV1>
) {
    init {
        require(schemaVersion == ACCEPTANCE_DISTRIBUTION_SCHEMA)
        require(distributionId.isSha256())
        require(sourceRevision.isNotBlank())
        require(configurationSha256.isSha256())
        require(modelAssignmentsSha256.isSha256())
        require(codexCliSha256.isSha256())
        require(codexCliVersion.isNotBlank())
        require(acceptanceJvmTmpDir.isNotBlank() && Path.of(acceptanceJvmTmpDir).isAbsolute)
        require(gateEvidence.isNotEmpty())
        require(gateEvidence.map { it.path } == gateEvidence.map { it.path }.sorted())
        require(gateEvidence.map { it.path }.distinct().size == gateEvidence.size)
        require(gateEvidence.all { it.path.isNotBlank() && it.sha256.isSha256() })
        require(sourceFiles.isNotEmpty())
        require(sourceFiles.map { it.path } == sourceFiles.map { it.path }.sorted())
        require(sourceFiles.map { it.path }.distinct().size == sourceFiles.size)
        require(sourceFiles.all { entry ->
            entry.path.isSafeRelativePath() && entry.sha256.isSha256() && entry.sizeBytes >= 0L
        })
        require(files.isNotEmpty())
        require(files.map { it.path } == files.map { it.path }.sorted())
        require(files.map { it.path }.distinct().size == files.size)
        require(files.all { entry ->
            entry.path.isSafeRelativePath() && entry.sha256.isSha256() && entry.sizeBytes >= 0L
        })
    }
}

@Serializable
internal data class AcceptanceLockOwnerV1(
    val schemaVersion: String = ACCEPTANCE_LOCK_SCHEMA,
    val pid: Long,
    val acquiredAtUtc: String,
    val repositoryRoot: String,
    val runId: String,
    val acceptanceProfile: String,
    val launcherPath: String,
    val distributionManifestSha256: String,
    val sourceRevision: String,
    val configurationSha256: String,
    val modelAssignmentsSha256: String,
    val jvmVersion: String,
    val codexCliPath: String,
    val codexCliSha256: String,
    val codexCliVersion: String
)

internal class AcceptanceAdmissionException(
    val code: String,
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

internal fun interface AcceptanceAdmissionController {
    fun admit(
        stagePlans: List<CliStagePlan>,
        options: CliOptions,
        pipelineRunOutput: Path
    ): AcceptanceRunLeaseHandle?

    companion object {
        val NONE = AcceptanceAdmissionController { _, _, _ -> null }
        fun production(): AcceptanceAdmissionController = DefaultAcceptanceAdmissionController()
    }
}

internal interface AcceptanceRunLeaseHandle : AutoCloseable, AcceptanceRunBoundaryVerifier {
    val record: AcceptanceAdmissionArtifact

    fun verifyStable(boundary: String)
}

internal class AcceptanceRunLease(
    override val record: AcceptanceAdmissionArtifact,
    private val repositorySnapshot: Map<String, String>,
    private val repositoryRoot: Path,
    private val distributionRoot: Path,
    private val manifest: AcceptanceDistributionManifestV1,
    private val acceptanceLock: AppendixBAcceptanceRunLock
) : AcceptanceRunLeaseHandle {
    override fun verifyStable(boundary: String) {
        val currentSourceRevision = git(repositoryRoot, "rev-parse", "HEAD")
        if (currentSourceRevision != manifest.sourceRevision) {
            throw AcceptanceAdmissionException(
                "source_revision_changed",
                "Acceptance source revision changed at $boundary"
            )
        }
        val current = snapshotProtectedRepositoryFiles(repositoryRoot, distributionRoot)
        if (current != repositorySnapshot) {
            val changed = (current.keys + repositorySnapshot.keys)
                .filter { current[it] != repositorySnapshot[it] }
                .sorted()
                .take(20)
            throw AcceptanceAdmissionException(
                "protected_source_changed",
                "Acceptance protected source/document bytes changed at $boundary: ${changed.joinToString()}"
            )
        }
        verifyDistributionFiles(distributionRoot, manifest)
    }

    override fun verify(runId: String, boundary: AcceptanceRunBoundary) {
        check(runId == record.runId) {
            "Acceptance boundary run ID $runId does not match admitted run ${record.runId}"
        }
        verifyStable(
            when (boundary) {
                is AcceptanceRunBoundary.RoundCompleted ->
                    "service-round-${boundary.roundIndex}-boundary"
                AcceptanceRunBoundary.BeforeFinalPublication ->
                    "service-before-final-publication"
                AcceptanceRunBoundary.BeforeArchivePublication ->
                    "service-before-archive-publication"
            }
        )
    }

    override fun close() {
        acceptanceLock.close()
    }
}

internal class AppendixBAcceptanceRunLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock
) : AutoCloseable {
    fun writeOwner(ownerJson: String) {
        val bytes = ownerJson.toByteArray(Charsets.UTF_8)
        channel.truncate(0L)
        channel.position(0L)
        channel.write(java.nio.ByteBuffer.wrap(bytes))
        channel.force(true)
    }

    override fun close() {
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    companion object {
        fun acquire(lockPath: Path): AppendixBAcceptanceRunLock {
            Files.createDirectories(lockPath.parent)
            runCatching {
                Files.setPosixFilePermissions(
                    lockPath.parent,
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE
                    )
                )
            }
            val channel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE
            )
            runCatching {
                Files.setPosixFilePermissions(
                    lockPath,
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE
                    )
                )
            }
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                channel.close()
                val owner = runCatching {
                    Files.readString(lockPath).take(MAX_OWNER_DIAGNOSTIC_BYTES)
                }.getOrDefault("<owner record unavailable>")
                throw AcceptanceAdmissionException(
                    "acceptance_lock_held",
                    "Another Appendix-B acceptance run holds $lockPath: $owner"
                )
            }
            return AppendixBAcceptanceRunLock(channel, lock)
        }
    }
}

internal data class AcceptanceAdmissionLeafProbes(
    val effectiveClasspath: (Path, AcceptanceDistributionManifestV1) -> Unit =
        ::verifyEffectiveClasspath,
    val rejectCompetingProcesses: () -> Unit = ::rejectCompetingProcesses,
    val localDaemonRunning: () -> Boolean = { LocalRunDaemonClient.status() != null },
    val codexCli: (AcceptanceDistributionManifestV1, CliOptions) -> Unit =
        ::verifyAcceptanceCodexCli,
    val beforeOutputReservation: (Path) -> Unit = {},
    val tempAuthorities: (Map<String, String>, Path, AcceptanceDistributionManifestV1) ->
        AcceptanceTempAuthorities = ::resolveAcceptanceTempAuthorities,
    val writableVolume: (Path, Long, String) -> Unit = ::verifyWritableVolume,
    val shellAndPython: () -> Unit = ::verifyShellAndPython,
    val providerSchema: (AcceptanceProfile) -> String = ::verifyAcceptanceProviderSchema,
    val scoringChild: (Path) -> Unit = ::verifyScoringChild
)

internal class DefaultAcceptanceAdmissionController(
    private val env: Map<String, String> = System.getenv(),
    private val probes: AcceptanceAdmissionLeafProbes = AcceptanceAdmissionLeafProbes()
) : AcceptanceAdmissionController {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = false
    }

    override fun admit(
        stagePlans: List<CliStagePlan>,
        options: CliOptions,
        pipelineRunOutput: Path
    ): AcceptanceRunLeaseHandle? {
        val acceptanceStages = stagePlans.filter { it.acceptanceProfile != AcceptanceProfile.NORMAL }
        if (acceptanceStages.isEmpty()) return null
        if (stagePlans.size != 1 || acceptanceStages.size != 1) {
            throw AcceptanceAdmissionException(
                "acceptance_pipeline_shape",
                "An acceptance pipeline must contain exactly one non-normal stage and no ordinary or follow-up stages"
            )
        }
        val stage = acceptanceStages.single()
        requireAcceptanceProfile(stage, options)

        val manifestPath = env[ACCEPTANCE_MANIFEST_ENV]
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?.toAbsolutePath()
            ?.normalize()
            ?: throw AcceptanceAdmissionException(
                "distribution_manifest_missing",
                "$ACCEPTANCE_MANIFEST_ENV must name the immutable distribution manifest"
            )
        val configuredRepositoryRoot = AcceptanceRepositoryRoot.resolveOrNull(env)?.toRealPath()
            ?: throw AcceptanceAdmissionException(
                "repository_root_missing",
                "Acceptance repository root authority is missing"
            )
        val discoveryManifest = decodeManifest(Files.readAllBytes(manifestPath))
        val discoveryRepositoryRoot = Path.of(discoveryManifest.repositoryRoot).toRealPath()
        if (discoveryRepositoryRoot != configuredRepositoryRoot) {
            throw AcceptanceAdmissionException(
                "repository_root_mismatch",
                "Acceptance repository root does not match the immutable manifest"
            )
        }
        val lockPath = configuredRepositoryRoot.resolve(AppendixBAcceptanceLockProbe.RELATIVE_PATH)
        val acceptanceLock = AppendixBAcceptanceRunLock.acquire(lockPath)

        try {
            val manifestBytes = Files.readAllBytes(manifestPath)
            val manifest = decodeManifest(manifestBytes)
            val manifestSha256 = sha256(manifestBytes)
            val repositoryRoot = Path.of(manifest.repositoryRoot).toRealPath()
            if (repositoryRoot != configuredRepositoryRoot) {
                throw AcceptanceAdmissionException(
                    "repository_root_changed",
                    "Acceptance repository root changed while acquiring the exclusive lock"
                )
            }
            val distributionRoot = manifestPath.parent
                ?: throw AcceptanceAdmissionException(
                    "distribution_root_missing",
                    "Distribution manifest has no parent"
                )
            val owner = AcceptanceLockOwnerV1(
                pid = ProcessHandle.current().pid(),
                acquiredAtUtc = Instant.now().toString(),
                repositoryRoot = repositoryRoot.toString(),
                runId = stage.runId,
                acceptanceProfile = stage.acceptanceProfile.id,
                launcherPath = distributionRoot.resolve("bin/evomolior-acceptance").toString(),
                distributionManifestSha256 = manifestSha256,
                sourceRevision = manifest.sourceRevision,
                configurationSha256 = manifest.configurationSha256,
                modelAssignmentsSha256 = manifest.modelAssignmentsSha256,
                jvmVersion = System.getProperty("java.runtime.version"),
                codexCliPath = manifest.codexCliPath,
                codexCliSha256 = manifest.codexCliSha256,
                codexCliVersion = manifest.codexCliVersion
            )
            acceptanceLock.writeOwner(json.encodeToString(owner))

            verifyDistributionRoot(distributionRoot, manifestPath, manifest, env)
            verifyDistributionFiles(distributionRoot, manifest)
            verifyDistributionIdentity(manifest)
            val authorizedSource = manifest.sourceFiles.associate { it.path to it.sha256 }.toSortedMap()
            val currentSource = snapshotProtectedRepositoryFiles(repositoryRoot, distributionRoot)
            if (currentSource != authorizedSource) {
                throw AcceptanceAdmissionException(
                    "source_manifest_mismatch",
                    "Protected source/document bytes do not match the immutable distribution manifest"
                )
            }
            val configurationPath = options.pipelineConfigPath
                ?: throw AcceptanceAdmissionException(
                    "configuration_authority_missing",
                    "Acceptance requires a hashed -pipeline-config authority"
                )
            if (sha256(configurationPath) != manifest.configurationSha256) {
                throw AcceptanceAdmissionException(
                    "configuration_hash_mismatch",
                    "Acceptance pipeline configuration does not match the immutable manifest"
                )
            }
            val assignmentsSha256 = sha256(
                canonicalModelAssignments(exactAcceptanceModelAssignments(stage))
            )
            if (assignmentsSha256 != manifest.modelAssignmentsSha256) {
                throw AcceptanceAdmissionException(
                    "model_assignment_hash_mismatch",
                    "Acceptance model assignments do not match the immutable manifest"
                )
            }

            probes.beforeOutputReservation(pipelineRunOutput)
            val reservedRunOutput = reserveAcceptanceRunOutput(pipelineRunOutput)
            val checks = performAdmissionChecks(
                repositoryRoot = repositoryRoot,
                distributionRoot = distributionRoot,
                pipelineRunOutput = reservedRunOutput,
                manifest = manifest,
                stage = stage,
                options = options
            )
            val estimate = estimatedMaximumArchiveBytes()
            val requiredFreeBytes = maxOf(MINIMUM_FREE_BYTES, Math.multiplyExact(estimate, 4L))
            val record = AcceptanceAdmissionArtifact(
                runId = stage.runId,
                acceptanceProfile = stage.acceptanceProfile,
                admittedAtUtc = Instant.now().toString(),
                ownerPid = ProcessHandle.current().pid(),
                distributionManifestSha256 = manifestSha256,
                sourceRevision = manifest.sourceRevision,
                configurationSha256 = manifest.configurationSha256,
                modelAssignmentsSha256 = manifest.modelAssignmentsSha256,
                jvmVersion = System.getProperty("java.runtime.version"),
                codexCliPath = manifest.codexCliPath,
                codexCliSha256 = manifest.codexCliSha256,
                codexCliVersion = manifest.codexCliVersion,
                estimatedMaximumRunArchiveBytes = estimate,
                requiredFreeBytes = requiredFreeBytes,
                checks = checks
            )
            val snapshot = snapshotProtectedRepositoryFiles(repositoryRoot, distributionRoot)
            return AcceptanceRunLease(
                record = record,
                repositorySnapshot = snapshot,
                repositoryRoot = repositoryRoot,
                distributionRoot = distributionRoot,
                manifest = manifest,
                acceptanceLock = acceptanceLock
            )
        } catch (error: Throwable) {
            acceptanceLock.close()
            throw error
        }
    }

    private fun performAdmissionChecks(
        repositoryRoot: Path,
        distributionRoot: Path,
        pipelineRunOutput: Path,
        manifest: AcceptanceDistributionManifestV1,
        stage: CliStagePlan,
        options: CliOptions
    ): List<AcceptanceAdmissionCheck> {
        val checks = mutableListOf<AcceptanceAdmissionCheck>()
        if (!Files.isDirectory(pipelineRunOutput) || !Files.isDirectory(pipelineRunOutput.resolve("control"))) {
            throw AcceptanceAdmissionException(
                "run_output_authority_lost",
                "Reserved acceptance output/control authority is unavailable: $pipelineRunOutput"
            )
        }
        checks += AcceptanceAdmissionCheck("output_authority", pipelineRunOutput.toString())
        if (Runtime.version().feature() != 17) {
            throw AcceptanceAdmissionException("jdk_not_17", "Acceptance requires JDK 17")
        }
        if (System.getProperty("java.runtime.version") != manifest.javaVersion) {
            throw AcceptanceAdmissionException(
                "jvm_version_mismatch",
                "Acceptance JVM version differs from the immutable distribution manifest"
            )
        }
        checks += AcceptanceAdmissionCheck("jdk_17", System.getProperty("java.runtime.version"))

        if (git(repositoryRoot, "rev-parse", "HEAD") != manifest.sourceRevision) {
            throw AcceptanceAdmissionException("source_revision_mismatch", "Source HEAD differs from manifest")
        }
        checks += AcceptanceAdmissionCheck(
            "source_revision",
            "${manifest.sourceRevision}:treeClean=${manifest.sourceTreeClean}"
        )

        manifest.gateEvidence.forEach { evidence ->
            val path = repositoryRoot.resolve(evidence.path).normalize()
            if (!path.startsWith(repositoryRoot) || sha256(path) != evidence.sha256) {
                throw AcceptanceAdmissionException(
                    "gate_evidence_mismatch",
                    "Gate evidence changed or is missing: ${evidence.path}"
                )
            }
        }
        checks += AcceptanceAdmissionCheck("gate_evidence", "${manifest.gateEvidence.size} files")

        probes.rejectCompetingProcesses()
        checks += AcceptanceAdmissionCheck("exclusive_processes", "no competing acceptance JVM or Gradle compile/test")

        if (probes.localDaemonRunning()) {
            throw AcceptanceAdmissionException(
                "daemon_running",
                "The local EvoMolior daemon must be stopped before acceptance admission"
            )
        }
        checks += AcceptanceAdmissionCheck("daemon_disabled", "no live local daemon")

        probes.effectiveClasspath(distributionRoot, manifest)
        checks += AcceptanceAdmissionCheck("effective_classpath", "immutable bundle classpath verified")

        probes.codexCli(manifest, options)
        checks += AcceptanceAdmissionCheck("codex_cli", "${manifest.codexCliSha256}:${manifest.codexCliVersion}")

        val tempAuthorities = probes.tempAuthorities(env, repositoryRoot, manifest)

        val estimate = estimatedMaximumArchiveBytes()
        val requiredFreeBytes = maxOf(MINIMUM_FREE_BYTES, Math.multiplyExact(estimate, 4L))
        probes.writableVolume(pipelineRunOutput.parent ?: options.outputDir, requiredFreeBytes, "output")
        probes.writableVolume(tempAuthorities.jvm, requiredFreeBytes, "acceptance_jvm_temp")
        probes.writableVolume(tempAuthorities.codex, requiredFreeBytes, "codex_temp")
        checks += AcceptanceAdmissionCheck("storage", "requiredFreeBytes=$requiredFreeBytes")

        probes.shellAndPython()
        checks += AcceptanceAdmissionCheck("sandbox_capabilities", "bounded shell and Python probes passed")

        val schemaEvidence = probes.providerSchema(stage.acceptanceProfile)
        checks += AcceptanceAdmissionCheck(
            "provider_schema",
            schemaEvidence
        )

        probes.scoringChild(distributionRoot)
        checks += AcceptanceAdmissionCheck("scoring_child", "loopback bind/start/health/stop passed")

        checks += AcceptanceAdmissionCheck(
            "profile_horizon",
            "${stage.acceptanceProfile.id}:${stage.rounds} rounds"
        )
        checks += AcceptanceAdmissionCheck(
            "provider_route",
            "${LlmBackend.CODEX_CHATGPT.id}:${ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL}:fallbacks=[]"
        )
        checks += AcceptanceAdmissionCheck(
            "sealed_suite_compiler",
            "Council output remains subject to deterministic compile/seal/executability verification before activation"
        )
        return checks.sortedBy { it.id }
    }

    private fun decodeManifest(bytes: ByteArray): AcceptanceDistributionManifestV1 = try {
        json.decodeFromString(AcceptanceDistributionManifestV1.serializer(), bytes.toString(Charsets.UTF_8))
    } catch (error: Exception) {
        throw AcceptanceAdmissionException(
            "distribution_manifest_invalid",
            "Acceptance distribution manifest is invalid: ${error.message}",
            error
        )
    }

    private fun estimatedMaximumArchiveBytes(): Long =
        env[ACCEPTANCE_ESTIMATED_ARCHIVE_BYTES_ENV]
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?: throw AcceptanceAdmissionException(
                "archive_estimate_missing",
                "$ACCEPTANCE_ESTIMATED_ARCHIVE_BYTES_ENV must be a positive byte count"
            )
}

private fun reserveAcceptanceRunOutput(pipelineRunOutput: Path): Path {
    val normalized = pipelineRunOutput.toAbsolutePath().normalize()
    val parent = normalized.parent
        ?: throw AcceptanceAdmissionException(
            "run_output_parent_missing",
            "Acceptance output directory has no parent: $pipelineRunOutput"
        )
    Files.createDirectories(parent)
    val fileName = normalized.fileName
        ?: throw AcceptanceAdmissionException(
            "run_output_name_missing",
            "Acceptance output directory has no final path component: $pipelineRunOutput"
        )
    val reserved = parent.toRealPath().resolve(fileName)
    try {
        Files.createDirectory(reserved)
        Files.createDirectory(reserved.resolve("control"))
    } catch (_: FileAlreadyExistsException) {
        throw AcceptanceAdmissionException(
            "run_output_exists",
            "Acceptance output/control authority must be new: $pipelineRunOutput"
        )
    }
    return reserved
}

private fun verifyAcceptanceCodexCli(
    manifest: AcceptanceDistributionManifestV1,
    options: CliOptions
) {
    val codexPath = Path.of(manifest.codexCliPath).toRealPath()
    if (!Files.isExecutable(codexPath) || sha256(codexPath) != manifest.codexCliSha256) {
        throw AcceptanceAdmissionException("codex_binary_mismatch", "Codex CLI bytes changed")
    }
    val codexVersion = command(listOf(codexPath.toString(), "--version"), 10_000)
    if (codexVersion != manifest.codexCliVersion) {
        throw AcceptanceAdmissionException("codex_version_mismatch", "Codex CLI version changed")
    }
    if (Path.of(options.openRouterApiKey).toRealPath() != codexPath) {
        throw AcceptanceAdmissionException("codex_route_mismatch", "CLI did not resolve the authorized Codex binary")
    }
    CodexAuthCommand.requireUsableSession(codexPath.toString())
}

private fun verifyAcceptanceProviderSchema(acceptanceProfile: AcceptanceProfile): String =
    CouncilAcceptanceSchemaAdmissionProbe.validate(acceptanceProfile)
        .schemaSha256ByRole
        .entries
        .joinToString(",") { (role, hash) -> "$role:$hash" }

private fun requireAcceptanceProfile(stage: CliStagePlan, options: CliOptions) {
    if (options.llmBackend != LlmBackend.CODEX_CHATGPT) {
        throw AcceptanceAdmissionException("provider_route", "Acceptance requires codex-chatgpt")
    }
    val expectedRounds = when (stage.acceptanceProfile) {
        AcceptanceProfile.NORMAL -> error("Normal stage cannot enter acceptance admission")
        AcceptanceProfile.RUNTIME_USABILITY_SMOKE -> 1
        AcceptanceProfile.APPENDIX_B_FULL -> 6
    }
    if (stage.rounds != expectedRounds) {
        throw AcceptanceAdmissionException(
            "profile_horizon",
            "Acceptance profile ${stage.acceptanceProfile.id} requires exactly $expectedRounds rounds"
        )
    }
    if ((stage.groupCount ?: 2) < 2) {
        throw AcceptanceAdmissionException("worker_count", "Acceptance requires at least two worker groups")
    }
    if (!stage.finalOutputExplicit || stage.finalOutputSpec == null) {
        throw AcceptanceAdmissionException("final_output", "Acceptance requires an explicit final output")
    }
}

private fun exactAcceptanceModelAssignments(stage: CliStagePlan): Map<String, String> {
    val expected = ModelSelectionConfig.codexChatGptRuntimeAssignments().toSortedMap()
    val invalidOverrides = stage.modelOverrides.filterValues {
        it != ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL
    }
    if (invalidOverrides.isNotEmpty()) {
        throw AcceptanceAdmissionException(
            "provider_model_fallback",
            "Acceptance model overrides contain undeclared routes: ${invalidOverrides.keys.sorted()}"
        )
    }
    return expected
}

private fun canonicalModelAssignments(assignments: Map<String, String>): ByteArray =
    assignments.toSortedMap().entries.joinToString("\n") { (role, model) -> "$role=$model" }
        .plus("\n")
        .toByteArray(Charsets.UTF_8)

private fun verifyDistributionRoot(
    distributionRoot: Path,
    manifestPath: Path,
    manifest: AcceptanceDistributionManifestV1,
    env: Map<String, String>
) {
    if (manifestPath.fileName.toString() != "acceptance-distribution-manifest-v1.json") {
        throw AcceptanceAdmissionException(
            "distribution_manifest_name",
            "Acceptance manifest must be named acceptance-distribution-manifest-v1.json"
        )
    }
    val realRoot = distributionRoot.toRealPath()
    if (realRoot.fileName.toString() != manifest.distributionId) {
        throw AcceptanceAdmissionException(
            "distribution_content_address",
            "Acceptance distribution directory name does not equal distributionId"
        )
    }
    val launcher = env[ACCEPTANCE_LAUNCHER_ENV]
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let(Path::of)
        ?.toRealPath()
        ?: throw AcceptanceAdmissionException(
            "acceptance_launcher_missing",
            "$ACCEPTANCE_LAUNCHER_ENV must identify the direct immutable launcher"
        )
    val expectedLauncher = realRoot.resolve("bin/evomolior-acceptance").toRealPath()
    if (launcher != expectedLauncher || !Files.isExecutable(launcher)) {
        throw AcceptanceAdmissionException(
            "acceptance_launcher_mismatch",
            "Acceptance was not launched directly through bin/evomolior-acceptance"
        )
    }
    Files.walk(realRoot).use { paths ->
        paths.forEach { path ->
            if (Files.isSymbolicLink(path)) {
                throw AcceptanceAdmissionException(
                    "distribution_symlink",
                    "Acceptance distribution must not contain symbolic links: $path"
                )
            }
            val permissions = runCatching { Files.getPosixFilePermissions(path) }.getOrNull()
            if (permissions != null && permissions.any { it in WRITE_PERMISSIONS }) {
                throw AcceptanceAdmissionException(
                    "distribution_mutable",
                    "Acceptance distribution retains write permission: $path"
                )
            }
        }
    }
}

private fun verifyDistributionFiles(
    distributionRoot: Path,
    manifest: AcceptanceDistributionManifestV1
) {
    val realRoot = distributionRoot.toRealPath()
    manifest.files.forEach { entry ->
        val path = realRoot.resolve(entry.path).normalize()
        if (!path.startsWith(realRoot) || !Files.isRegularFile(path)) {
            throw AcceptanceAdmissionException("distribution_file_missing", "Missing bundle file ${entry.path}")
        }
        if (Files.size(path) != entry.sizeBytes || sha256(path) != entry.sha256) {
            throw AcceptanceAdmissionException("distribution_file_changed", "Changed bundle file ${entry.path}")
        }
        val permissions = runCatching { Files.getPosixFilePermissions(path) }.getOrNull()
        if (permissions != null && permissions.any { it in WRITE_PERMISSIONS }) {
            throw AcceptanceAdmissionException(
                "distribution_file_mutable",
                "Bundle file retains write permission: ${entry.path}"
            )
        }
    }
    val actual = Files.walk(realRoot).use { paths ->
        paths.filter(Files::isRegularFile)
            .map { realRoot.relativize(it).invariantSeparatorsPathString }
            .filter { it != "acceptance-distribution-manifest-v1.json" }
            .sorted()
            .toList()
    }
    if (actual != manifest.files.map { it.path }) {
        throw AcceptanceAdmissionException(
            "distribution_inventory_mismatch",
            "Bundle inventory contains missing or uncommitted files"
        )
    }
}

internal fun verifyDistributionIdentity(manifest: AcceptanceDistributionManifestV1) {
    if (canonicalDistributionId(manifest) != manifest.distributionId) {
        throw AcceptanceAdmissionException(
            "distribution_id_mismatch",
            "Acceptance distributionId does not match its complete canonical payload inventory"
        )
    }
}

internal fun canonicalDistributionId(manifest: AcceptanceDistributionManifestV1): String {
    val canonical = buildString {
        append('{')
        append("\"schemaVersion\":\"acceptance-distribution-manifest-v1\"")
        append(",\"createdAtUtc\":${canonicalJson(manifest.createdAtUtc)}")
        append(",\"repositoryRoot\":${canonicalJson(manifest.repositoryRoot)}")
        append(",\"sourceRevision\":${canonicalJson(manifest.sourceRevision)}")
        append(",\"sourceTreeClean\":${manifest.sourceTreeClean}")
        append(",\"sourceFiles\":[")
        append(manifest.sourceFiles.joinToString(",") {
            """{"path":${canonicalJson(it.path)},"sha256":${canonicalJson(it.sha256)},"sizeBytes":${it.sizeBytes}}"""
        })
        append(']')
        append(",\"javaVersion\":${canonicalJson(manifest.javaVersion)}")
        append(",\"acceptanceJvmTmpDir\":${canonicalJson(manifest.acceptanceJvmTmpDir)}")
        append(",\"mainClass\":${canonicalJson(manifest.mainClass)}")
        append(",\"configurationSha256\":${canonicalJson(manifest.configurationSha256)}")
        append(",\"modelAssignmentsSha256\":${canonicalJson(manifest.modelAssignmentsSha256)}")
        append(",\"codexCliPath\":${canonicalJson(manifest.codexCliPath)}")
        append(",\"codexCliSha256\":${canonicalJson(manifest.codexCliSha256)}")
        append(",\"codexCliVersion\":${canonicalJson(manifest.codexCliVersion)}")
        append(",\"gateEvidence\":[")
        append(manifest.gateEvidence.joinToString(",") {
            """{"path":${canonicalJson(it.path)},"sha256":${canonicalJson(it.sha256)}}"""
        })
        append(']')
        append(",\"files\":[")
        append(manifest.files.joinToString(",") {
            """{"path":${canonicalJson(it.path)},"sha256":${canonicalJson(it.sha256)},"sizeBytes":${it.sizeBytes}}"""
        })
        append("]}")
    }
    return sha256(canonical.toByteArray(Charsets.UTF_8))
}

private fun canonicalJson(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
        }
    }
    append('"')
}

private fun verifyEffectiveClasspath(
    distributionRoot: Path,
    manifest: AcceptanceDistributionManifestV1
) {
    val libRoot = distributionRoot.resolve("lib").toRealPath()
    val expectedJars = manifest.files.map { it.path }
        .filter { it.startsWith("lib/") && it.endsWith(".jar") }
        .toSet()
    val classpath = System.getProperty("java.class.path")
        .split(System.getProperty("path.separator"))
        .filter(String::isNotBlank)
        .map { Path.of(it).toRealPath() }
    if (classpath.isEmpty() || classpath.any { !it.startsWith(libRoot) }) {
        throw AcceptanceAdmissionException(
            "effective_classpath_outside_bundle",
            "Acceptance JVM classpath contains entries outside the immutable bundle"
        )
    }
    val actualJars = classpath.map { distributionRoot.toRealPath().relativize(it).invariantSeparatorsPathString }
        .toSet()
    if (actualJars != expectedJars) {
        throw AcceptanceAdmissionException(
            "effective_classpath_inventory",
            "Acceptance JVM classpath does not equal the committed JAR inventory"
        )
    }
    val codeSource = EvoMoliorCliMainMarker::class.java.protectionDomain.codeSource.location
        .toURI()
        .let(Path::of)
        .toRealPath()
    if (!codeSource.startsWith(libRoot)) {
        throw AcceptanceAdmissionException(
            "cli_code_source",
            "CLI code source is outside the immutable distribution: $codeSource"
        )
    }
}

private object EvoMoliorCliMainMarker

internal fun isProtectedGradleCompileOrTestCommand(command: String): Boolean =
    Regex(
        """(?:^|\s):?(?:(?:[A-Za-z0-9_.-]+):)*(?:compile[\w-]*|test|check|build)(?=\s|$)"""
    ).containsMatchIn(command)

private fun rejectCompetingProcesses() {
    val currentPid = ProcessHandle.current().pid()
    ProcessHandle.allProcesses().use { processes ->
        processes.filter { it.pid() != currentPid }.forEach { process ->
            val info = process.info()
            val command = buildString {
                append(info.command().orElse(""))
                append(' ')
                append(info.arguments().orElse(emptyArray()).joinToString(" "))
                append(' ')
                append(info.commandLine().orElse(""))
            }.lowercase()
            val competingAcceptance =
                command.contains("evomolior") &&
                    (command.contains("appendix-b-full") || command.contains(ACCEPTANCE_MANIFEST_ENV.lowercase()))
            val gradleCompileOrTest =
                (command.contains("gradle") || command.contains("gradledaemon")) &&
                    isProtectedGradleCompileOrTestCommand(command)
            if (competingAcceptance || gradleCompileOrTest) {
                throw AcceptanceAdmissionException(
                    if (competingAcceptance) "acceptance_jvm_running" else "gradle_compile_test_running",
                    "Conflicting process is already running (pid=${process.pid()})"
                )
            }
        }
    }
}

private fun verifyWritableVolume(path: Path, requiredFreeBytes: Long, label: String) {
    Files.createDirectories(path)
    val probe = Files.createTempFile(path, ".evomolior-$label-probe-", ".tmp")
    try {
        Files.writeString(probe, "ok")
    } finally {
        Files.deleteIfExists(probe)
    }
    val store: FileStore = Files.getFileStore(path)
    if (store.usableSpace < requiredFreeBytes) {
        throw AcceptanceAdmissionException(
            "${label}_free_space",
            "$label volume has ${store.usableSpace} usable bytes; $requiredFreeBytes required"
        )
    }
}

internal data class AcceptanceTempAuthorities(
    val jvm: Path,
    val codex: Path
)

internal fun resolveAcceptanceTempAuthorities(
    env: Map<String, String>,
    repositoryRoot: Path,
    manifest: AcceptanceDistributionManifestV1,
    systemTemp: Path = Path.of(System.getProperty("java.io.tmpdir"))
): AcceptanceTempAuthorities {
    val codexTemp = env[ACCEPTANCE_CODEX_TMPDIR_ENV]
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let(Path::of)
        ?.toAbsolutePath()
        ?.normalize()
        ?: throw AcceptanceAdmissionException(
            "codex_temp_missing",
            "$ACCEPTANCE_CODEX_TMPDIR_ENV must name a repository or external-volume directory"
        )
    if (!Files.exists(codexTemp)) {
        Files.createDirectories(codexTemp)
    }
    val realRepositoryRoot = repositoryRoot.toRealPath()
    val normalizedSystemTemp = systemTemp.toRealPath()
    val realCodexTemp = codexTemp.toRealPath()
    val repositoryStorage = realCodexTemp.startsWith(realRepositoryRoot) &&
        sameFileStore(realCodexTemp, realRepositoryRoot)
    val approvedExternalStorage = env[ACCEPTANCE_EXTERNAL_VOLUME_ROOTS_ENV]
        ?.split(java.io.File.pathSeparatorChar)
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        .orEmpty()
        .map { Path.of(it).toRealPath() }
        .any { root ->
            !root.startsWith(normalizedSystemTemp) &&
                !normalizedSystemTemp.startsWith(root) &&
                !sameFileStore(root, normalizedSystemTemp) &&
                realCodexTemp.startsWith(root) &&
                sameFileStore(realCodexTemp, root)
        }
    if (!repositoryStorage && !approvedExternalStorage) {
        throw AcceptanceAdmissionException(
            "codex_temp_volume_unauthorized",
            "Codex temp must resolve onto repository storage or an explicitly approved external volume"
        )
    }

    val acceptanceJvmTemp = Path.of(manifest.acceptanceJvmTmpDir).toRealPath()
    val launcherJvmTemp = env[ACCEPTANCE_JVM_TMPDIR_ENV]
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let(Path::of)
        ?.toRealPath()
        ?: throw AcceptanceAdmissionException(
            "acceptance_jvm_temp_missing",
            "$ACCEPTANCE_JVM_TMPDIR_ENV must identify the immutable acceptance JVM temp authority"
        )
    if (launcherJvmTemp != acceptanceJvmTemp) {
        throw AcceptanceAdmissionException(
            "acceptance_jvm_temp_authority_mismatch",
            "Acceptance launcher JVM temp authority differs from the immutable distribution manifest"
        )
    }
    if (normalizedSystemTemp != acceptanceJvmTemp) {
        throw AcceptanceAdmissionException(
            "acceptance_jvm_temp_mismatch",
            "Acceptance JVM temp authority differs from the immutable distribution manifest"
        )
    }
    if (acceptanceJvmTemp == realCodexTemp) {
        throw AcceptanceAdmissionException(
            "acceptance_temp_not_dedicated",
            "Acceptance JVM temp and Codex temp must use distinct authorities"
        )
    }
    return AcceptanceTempAuthorities(jvm = acceptanceJvmTemp, codex = realCodexTemp)
}

private fun sameFileStore(first: Path, second: Path): Boolean {
    val firstStore = Files.getFileStore(first)
    val secondStore = Files.getFileStore(second)
    return firstStore == secondStore ||
        (firstStore.name() == secondStore.name() && firstStore.type() == secondStore.type())
}

private fun verifyShellAndPython() {
    val shell = System.getenv("EVOMOLIOR_ACCEPTANCE_SHELL")?.takeIf(String::isNotBlank) ?: "/bin/sh"
    command(listOf(shell, "-c", "test \"$(printf evomolior)\" = evomolior"), 5_000)
    val python = System.getenv("EVOMOLIOR_ACCEPTANCE_PYTHON")?.takeIf(String::isNotBlank) ?: "python3"
    command(listOf(python, "-c", "import json,sys; sys.exit(0)"), 5_000)
}

internal fun scoringChildCommand(
    javaHome: String = System.getProperty("java.home"),
    classpath: String = System.getProperty("java.class.path"),
    jvmTemp: String = System.getProperty("java.io.tmpdir")
): List<String> = listOf(
    Path.of(javaHome, "bin", "java").toString(),
    "-Djava.io.tmpdir=$jvmTemp",
    "-cp",
    classpath,
    "com.achulkov.evomolior.api.service.ScoringWorkerServerKt"
)

private fun verifyScoringChild(distributionRoot: Path) = runBlocking {
    val diagnostics = Files.createTempDirectory(distributionRoot.parent, ".scoring-admission-")
    val ready = diagnostics.resolve("ready.port")
    val stdout = diagnostics.resolve("stdout.log")
    val stderr = diagnostics.resolve("stderr.log")
    val token = UUID.randomUUID().toString()
    val process = ProcessBuilder(
        scoringChildCommand() + listOf(
        "0",
        ready.toString(),
        "admission-worker-a",
        "admission-worker-b"
        )
    ).apply {
        environment()["EVOMOLIOR_SCORING_WORKER_TOKEN"] = token
        redirectOutput(stdout.toFile())
        redirectError(stderr.toFile())
    }.start()
    try {
        val client = HttpClient.newBuilder().build()
        withTimeout(15_000) {
            while (true) {
                check(process.isAlive) {
                    "Scoring child exited during admission: ${Files.readString(stderr).take(1_024)}"
                }
                val port = ready.takeIf(Files::isRegularFile)
                    ?.let(Files::readString)
                    ?.trim()
                    ?.toIntOrNull()
                if (port == null) {
                    delay(50)
                    continue
                }
                val response = withContext(Dispatchers.IO) {
                    client.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/health"))
                            .header("X-EvoMolior-Scoring-Token", token)
                            .GET()
                            .build(),
                        HttpResponse.BodyHandlers.ofString()
                    )
                }
                if (response.statusCode() == 200 && response.body().contains("\"ready\":true")) break
                delay(50)
            }
        }
    } catch (error: Exception) {
        throw AcceptanceAdmissionException("scoring_child_health", "Scoring child admission probe failed", error)
    } finally {
        process.destroy()
        if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
        diagnostics.toFile().deleteRecursively()
    }
}

internal fun snapshotProtectedRepositoryFiles(
    repositoryRoot: Path,
    distributionRoot: Path
): Map<String, String> {
    val protectedRoots = listOf(
        repositoryRoot.resolve("evomolior"),
        repositoryRoot.resolve("build-logic")
    )
    val explicit = listOf(
        repositoryRoot.resolve("build.gradle.kts"),
        repositoryRoot.resolve("settings.gradle.kts"),
        repositoryRoot.resolve("gradle/libs.versions.toml"),
        repositoryRoot.resolve(
            "documentation/EvoMolior_Competitive_Progressive_Disclosure_Action_Plan-1.html"
        ),
        repositoryRoot.resolve("documentation/Remediation_plan_after_v72.md"),
        repositoryRoot.resolve("documentation/EvoMolior_Roadmap_and_Project_State.md")
    )
    val candidates = buildList {
        explicit.filterTo(this, Files::isRegularFile)
        protectedRoots.filter(Files::isDirectory).forEach { root ->
            Files.walk(root).use { paths ->
                paths.peek { path ->
                    if (Files.isSymbolicLink(path)) {
                        throw AcceptanceAdmissionException(
                            "source_manifest_symlink",
                            "Protected source/document authority must not contain symbolic links: $path"
                        )
                    }
                }
                    .filter(Files::isRegularFile)
                    .filter { path ->
                        val value = repositoryRoot.relativize(path).invariantSeparatorsPathString
                        !value.contains("/build/") &&
                            !value.startsWith("build/") &&
                            !value.contains("/.gradle/") &&
                            !value.startsWith(".gradle/") &&
                            !value.contains("/.kotlin/") &&
                            !value.startsWith(".kotlin/") &&
                            !value.contains("/.idea/") &&
                            !value.startsWith(".idea/") &&
                            !value.contains("/results/") &&
                            !value.startsWith("results/") &&
                            path.fileName.toString() != ".DS_Store" &&
                            !isAcceptanceDatabaseArtifact(path.fileName.toString()) &&
                            path.toAbsolutePath().normalize() != distributionRoot.toAbsolutePath().normalize()
                    }
                    .forEach(::add)
            }
        }
    }
    return candidates.distinct()
        .associate { repositoryRoot.relativize(it).invariantSeparatorsPathString to sha256(it) }
        .toSortedMap()
}

internal fun isAcceptanceDatabaseArtifact(fileName: String): Boolean {
    val normalized = fileName.lowercase()
    return normalized.matches(Regex(""".*\.(db|sqlite|sqlite3)(-(wal|shm|journal))?""")) ||
        normalized.endsWith("-wal") ||
        normalized.endsWith("-shm") ||
        normalized.endsWith("-journal")
}

private fun command(command: List<String>, timeoutMs: Long): String {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
        process.destroyForcibly()
        throw AcceptanceAdmissionException("subprocess_timeout", "Command timed out: ${command.first()}")
    }
    val output = process.inputStream.bufferedReader().readText().trim()
    if (process.exitValue() != 0) {
        throw AcceptanceAdmissionException(
            "subprocess_failed",
            "Command failed (${process.exitValue()}): ${command.first()}: ${output.take(1_024)}"
        )
    }
    return output
}

private fun git(repositoryRoot: Path, vararg args: String): String =
    command(listOf("git", "-C", repositoryRoot.toString(), *args), 15_000)

private fun sha256(path: Path): String {
    if (!Files.isRegularFile(path)) {
        throw AcceptanceAdmissionException("required_file_missing", "Required file is missing: $path")
    }
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHex()
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun String.isSha256(): Boolean = matches(Regex("[0-9a-f]{64}"))

private fun String.isSafeRelativePath(): Boolean {
    if (isBlank()) return false
    val path = runCatching { Path.of(this) }.getOrNull() ?: return false
    return !path.isAbsolute && path.normalize().toString() == path.toString() && !startsWith("..")
}

private val WRITE_PERMISSIONS = setOf(
    PosixFilePermission.OWNER_WRITE,
    PosixFilePermission.GROUP_WRITE,
    PosixFilePermission.OTHERS_WRITE
)
