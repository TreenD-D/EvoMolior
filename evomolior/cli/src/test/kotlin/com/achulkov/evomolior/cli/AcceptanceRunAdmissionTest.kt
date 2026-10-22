package com.achulkov.evomolior.cli

import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.api.service.StartupRejectionFileStore
import com.achulkov.evomolior.api.service.StartupRejectionPhase
import com.achulkov.evomolior.api.service.ArcAgi2NotebookQualificationAuthority
import com.achulkov.evomolior.core.repair.AggregationSemantics
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcceptanceRunAdmissionTest {
    private val javaHome: Path = Path.of(System.getProperty("java.home")).toRealPath()
    private val javaExecutableSha256: String by lazy { testSha256(javaHome.resolve("bin/java")) }
    private val javaReleaseSha256: String by lazy { testSha256(javaHome.resolve("release")) }
    private val javaModulesSha256: String by lazy { testSha256(javaHome.resolve("lib/modules")) }

    @Test
    fun `protected Gradle task detection recognizes qualified task paths`() {
        assertTrue(isProtectedGradleCompileOrTestCommand("./gradlew :engine:api:test"))
        assertTrue(isProtectedGradleCompileOrTestCommand("gradle :evomolior:cli:compileKotlin"))
        assertFalse(isProtectedGradleCompileOrTestCommand("gradle :evomolior:cli:tasks"))
    }

    @Test
    fun `current invocation ancestors are ignored during competing process admission`() {
        val parentPids = mapOf(10L to 20L, 20L to 30L, 30L to 40L)
        val invocationIds = collectInvocationProcessIds(10L, parentPids::get)
        assertEquals(setOf(10L, 20L, 30L, 40L), invocationIds)

        rejectCompetingProcesses(
            processes = invocationIds.asSequence().map { pid ->
                AcceptanceProcessSnapshot(
                    pid = pid,
                    command = "/repo/scripts/run_acceptance_provider.sh provider.log exit -- " +
                        "/bundle/bin/evomolior-acceptance run " +
                        "-acceptance-profile appendix-b-full"
                )
            },
            ignoredProcessIds = invocationIds
        )
    }

    @Test
    fun `process inventory failures become closed admission rejection`() {
        listOf<() -> Unit>(
            {
                rejectCompetingProcessesSafely(
                    invocationProcessIds = { throw SecurityException("private detail") },
                    processInventory = { emptyList() }
                )
            },
            {
                rejectCompetingProcessesSafely(
                    invocationProcessIds = { setOf(10L) },
                    processInventory = { throw IllegalStateException("private detail") }
                )
            },
            {
                rejectCompetingProcesses(
                    processes = sequenceOf(
                        AcceptanceProcessSnapshot(
                            pid = 91_000L,
                            command = "",
                            user = "acceptance-owner"
                        )
                    ),
                    ignoredProcessIds = emptySet(),
                    currentUser = "acceptance-owner"
                )
            }
        ).forEach { attempt ->
            assertEquals(
                "process_inventory_unavailable",
                assertFailsWith<AcceptanceAdmissionException> { attempt() }.code
            )
        }
    }

    @Test
    fun `admission owner diagnostics accept only catalogued codes`() {
        assertFailsWith<IllegalArgumentException> {
            AcceptanceAdmissionException("arbitrary_detail", "private detail")
        }
    }

    @Test
    fun `ARC acceptance authority rejects production configuration drift`() {
        val canonical = ArcAgi2NotebookQualificationAuthority.quantitativeToolObligation
        val targetStageId = ArcAgi2NotebookQualificationAuthority.PIPELINE_STAGE_ID
        val canonicalOutputSpec = FinalOutputSpec(
            relativePath = ArcAgi2NotebookQualificationAuthority.NOTEBOOK_RELATIVE_PATH,
            mediaType = ArcAgi2NotebookQualificationAuthority.NOTEBOOK_MEDIA_TYPE,
            validation = ArcAgi2NotebookQualificationAuthority.notebookValidationContract,
        )
        verifyAcceptanceTargetAuthority(targetStageId, canonical, canonicalOutputSpec)
        verifyAcceptanceTargetAuthority("unrelated-stage", null)
        val first = canonical.requiredFields.first()
        val drifted = canonical.copy(
            requiredFields = listOf(
                first.copy(
                    quantitySpec = first.quantitySpec.copy(
                        aggregation = AggregationSemantics.MAXIMUM
                    )
                )
            ) + canonical.requiredFields.drop(1)
        )

        assertEquals(
            "arc_qualification_authority_mismatch",
            assertFailsWith<AcceptanceAdmissionException> {
                verifyAcceptanceTargetAuthority(targetStageId, drifted, canonicalOutputSpec)
            }.code
        )
        assertEquals(
            "arc_qualification_authority_mismatch",
            assertFailsWith<AcceptanceAdmissionException> {
                verifyAcceptanceTargetAuthority(
                    targetStageId,
                    canonical.copy(requiredOpportunityId = "drifted-opportunity"),
                    canonicalOutputSpec,
                )
            }.code
        )
        assertEquals(
            "arc_qualification_authority_mismatch",
            assertFailsWith<AcceptanceAdmissionException> {
                verifyAcceptanceTargetAuthority(
                    targetStageId,
                    canonical,
                    canonicalOutputSpec.copy(
                        validation = requireNotNull(canonicalOutputSpec.validation).copy(
                            requiredJsonRootKeys = listOf("cells"),
                        ),
                    ),
                )
            }.code
        )
        assertEquals(
            "arc_qualification_authority_mismatch",
            assertFailsWith<AcceptanceAdmissionException> {
                verifyAcceptanceTargetAuthority(targetStageId, null, canonicalOutputSpec)
            }.code
        )
    }

    @Test
    fun `unrelated acceptance command remains rejected`() {
        val rejected = assertFailsWith<AcceptanceAdmissionException> {
            rejectCompetingProcesses(
                processes = sequenceOf(
                    AcceptanceProcessSnapshot(
                        pid = 91_001L,
                        command = "/bundle/evomolior-acceptance -acceptance-profile appendix-b-full"
                    )
                ),
                ignoredProcessIds = setOf(ProcessHandle.current().pid())
            )
        }

        assertEquals("acceptance_jvm_running", rejected.code)
    }

    @Test
    fun `independent acceptance roots may coexist while Gradle compile remains rejected`() {
        rejectCompetingProcessesSafely(
            invocationProcessIds = { emptySet() },
            processInventory = {
                listOf(
                    AcceptanceProcessSnapshot(
                        pid = 91_002L,
                        command = "/other-root/bin/evomolior-acceptance " +
                            "-acceptance-profile appendix-b-full"
                    )
                )
            },
            allowConcurrentAcceptance = true
        )

        assertEquals(
            "gradle_compile_test_running",
            assertFailsWith<AcceptanceAdmissionException> {
                rejectCompetingProcessesSafely(
                    invocationProcessIds = { emptySet() },
                    processInventory = {
                        listOf(
                            AcceptanceProcessSnapshot(
                                pid = 91_003L,
                                command = "GradleDaemon :engine:api:test"
                            )
                        )
                    },
                    allowConcurrentAcceptance = true
                )
            }.code
        )
    }

    @Test
    fun `unrelated protected Gradle compile or test command remains rejected`() {
        listOf(
            "./gradlew :evomolior:cli:compileKotlin",
            "GradleDaemon :evomolior:api:test"
        ).forEachIndexed { index, command ->
            val rejected = assertFailsWith<AcceptanceAdmissionException> {
                rejectCompetingProcesses(
                    processes = sequenceOf(
                        AcceptanceProcessSnapshot(pid = 92_000L + index, command = command)
                    ),
                    ignoredProcessIds = emptySet()
                )
            }
            assertEquals("gradle_compile_test_running", rejected.code)
        }
    }

    @Test
    fun `hermetic admission holds lease records owner detects drift and releases lock`() {
        val repository = createTempDirectory("acceptance-admission-repository-")
        val admissionJvmTemp = createTempDirectory("acceptance-admission-jvm-temp-")
        Files.writeString(repository.resolve("settings.gradle.kts"), "rootProject.name = \"test\"\n")
        val source = Files.createDirectories(repository.resolve("evomolior")).resolve("Source.kt")
        Files.writeString(source, "package test\n")
        val configuration = repository.resolve("pipeline.json")
        Files.writeString(configuration, "{}")
        Files.writeString(repository.resolve("gate.txt"), "gate")
        Files.writeString(repository.resolve(".gitignore"), ".evomolior-locks/\n")
        testCommand(listOf("git", "-C", repository.toString(), "init"))
        testCommand(listOf("git", "-C", repository.toString(), "add", "."))
        testCommand(
            listOf(
                "git", "-C", repository.toString(), "-c", "user.name=test",
                "-c", "user.email=test@example.invalid", "commit", "-m", "fixture"
            )
        )
        val sourceRevision = testCommand(listOf("git", "-C", repository.toString(), "rev-parse", "HEAD"))
        val stage = CliStagePlan(
            id = "appendix",
            prompt = "Hermetic admission",
            inputDir = repository,
            outputDir = repository.resolve("out"),
            runId = "admission-contract",
            fast = false,
            rounds = 6,
            groupCount = 2,
            maxAgentsPerGroup = null,
            roundDurationMinutes = null,
            budgetOverride = null,
            inheritsPreviousOutputs = false,
            modelOverrides = emptyMap(),
            finalOutputSpec = CliPipelineFinalOutputSpec("deliverable.md", "text/markdown"),
            finalOutputExplicit = true,
            enforceProviderMonetaryCostCap = true,
            acceptanceProfile = AcceptanceProfile.APPENDIX_B_FULL
        )
        val distributionStaging = createTempDirectory("acceptance-admission-distribution-")
        val launcherStaging = Files.createDirectories(distributionStaging.resolve("bin"))
            .resolve("evomolior-acceptance")
        Files.writeString(launcherStaging, "#!/bin/sh\nexit 0\n")
        val sourceFiles = listOf(
            AcceptanceDistributionFileV1(
                "evomolior/Source.kt",
                testSha256(source),
                Files.size(source)
            ),
            AcceptanceDistributionFileV1(
                "settings.gradle.kts",
                testSha256(repository.resolve("settings.gradle.kts")),
                Files.size(repository.resolve("settings.gradle.kts"))
            )
        ).sortedBy { it.path }
        val unsigned = AcceptanceDistributionManifestV1(
            distributionId = "a".repeat(64),
            createdAtUtc = "2026-07-20T00:00:00Z",
            repositoryRoot = repository.toString(),
            sourceRevision = sourceRevision,
            sourceTreeClean = true,
            javaVersion = System.getProperty("java.runtime.version"),
            javaHome = javaHome.toString(),
            javaExecutableSha256 = javaExecutableSha256,
            javaReleaseSha256 = javaReleaseSha256,
            javaModulesSha256 = javaModulesSha256,
            acceptanceJvmTmpDir = admissionJvmTemp.toString(),
            mainClass = "com.achulkov.evomolior.cli.EvoMoliorCliMainKt",
            configurationSha256 = testSha256(configuration),
            modelAssignmentsSha256 = testSha256(
                ModelSelectionConfig.codexChatGptRuntimeAssignments()
                    .toSortedMap()
                    .entries
                    .joinToString("\n") { (role, model) -> "$role=$model" }
                    .plus("\n")
                    .toByteArray()
            ),
            codexCliPath = "/unused/codex",
            codexCliSha256 = "b".repeat(64),
            codexCliVersion = "unused",
            gateEvidence = listOf(AcceptanceGateEvidenceV1("gate.txt", testSha256("gate".toByteArray()))),
            sourceFiles = sourceFiles,
            files = listOf(
                AcceptanceDistributionFileV1(
                    "bin/evomolior-acceptance",
                    testSha256(launcherStaging),
                    Files.size(launcherStaging)
                )
            )
        )
        val manifest = unsigned.copy(distributionId = canonicalDistributionId(unsigned))
        val distribution = createTempDirectory("acceptance-admission-bundle-parent-")
            .resolve(manifest.distributionId)
        Files.createDirectories(distribution.resolve("bin"))
        val launcher = distribution.resolve("bin/evomolior-acceptance")
        Files.copy(launcherStaging, launcher)
        Files.writeString(
            distribution.resolve("acceptance-distribution-manifest-v1.json"),
            kotlinx.serialization.json.Json { encodeDefaults = true; explicitNulls = false }
                .encodeToString(AcceptanceDistributionManifestV1.serializer(), manifest)
        )
        Files.walk(distribution).use { paths ->
            paths.forEach { path ->
                Files.setPosixFilePermissions(
                    path,
                    if (Files.isDirectory(path)) setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE)
                    else setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE)
                )
            }
        }
        val invokedProbes = mutableSetOf<String>()
        val controller = DefaultAcceptanceAdmissionController(
            env = mapOf(
                ACCEPTANCE_MANIFEST_ENV to distribution.resolve("acceptance-distribution-manifest-v1.json").toString(),
                ACCEPTANCE_LAUNCHER_ENV to launcher.toString(),
                "EVOMOLIOR_REPOSITORY_ROOT" to repository.toString(),
                ACCEPTANCE_ESTIMATED_ARCHIVE_BYTES_ENV to "1024"
            ),
            probes = AcceptanceAdmissionLeafProbes(
                effectiveClasspath = { _, _ -> invokedProbes += "effective_classpath" },
                rejectCompetingProcesses = { invokedProbes += "exclusive_processes" },
                localDaemonRunning = {
                    invokedProbes += "daemon_disabled"
                    false
                },
                codexCli = { _, _ -> invokedProbes += "codex_cli" },
                beforeOutputReservation = { output ->
                    if (output.fileName.toString() == "raced-output") {
                        assertEquals(
                            "acceptance_lock_held",
                            assertFailsWith<AcceptanceAdmissionException> {
                                AppendixBAcceptanceRunLock.acquire(
                                    repository.resolve(AppendixBAcceptanceLockProbe.RELATIVE_PATH)
                                )
                            }.code
                        )
                        Files.createDirectory(output)
                        Files.writeString(output.resolve("intruder.txt"), "raced")
                    }
                },
                tempAuthorities = { _, _, _ ->
                    AcceptanceTempAuthorities(admissionJvmTemp, repository.resolve("codex-temp"))
                },
                writableVolume = { _, _, _ -> invokedProbes += "storage" },
                shellAndPython = { invokedProbes += "sandbox_capabilities" },
                providerSchema = { _, _ ->
                    invokedProbes += "provider_schema"
                    "hermetic-schema"
                },
                scoringChild = { invokedProbes += "scoring_child" }
            )
        )
        val options = CliOptions(
            prompt = stage.prompt,
            inputDir = repository,
            outputDir = repository.resolve("out"),
            openRouterApiKey = "/unused/codex",
            llmBackend = LlmBackend.CODEX_CHATGPT,
            modelOverrides = emptyMap(),
            debug = false,
            fast = false,
            rounds = 6,
            pipelineConfigPath = configuration
        )

        val reservedOutput = createTempDirectory("acceptance-admission-output-parent-")
            .resolve("new-output")
        val lease = controller.admit(listOf(stage), options, reservedOutput)
        assertEquals(stage.runId, lease?.record?.runId)
        assertEquals(ProcessHandle.current().pid(), lease?.record?.ownerPid)
        assertEquals(
            setOf(
                "jdk_17",
                "source_revision",
                "gate_evidence",
                "exclusive_processes",
                "daemon_disabled",
                "effective_classpath",
                "codex_cli",
                "output_authority",
                "storage",
                "sandbox_capabilities",
                "provider_schema",
                "scoring_child",
                "profile_horizon",
                "provider_route",
                "sealed_suite_compiler"
            ),
            lease?.record?.checks?.map { it.id }?.toSet()
        )
        assertTrue("effective_classpath" in invokedProbes)
        assertTrue(Files.isDirectory(reservedOutput.resolve("control")))
        assertTrue(Files.readString(repository.resolve(AppendixBAcceptanceLockProbe.RELATIVE_PATH)).contains(stage.runId))
        assertEquals(
            "acceptance_lock_held",
            assertFailsWith<AcceptanceAdmissionException> {
                AppendixBAcceptanceRunLock.acquire(repository.resolve(AppendixBAcceptanceLockProbe.RELATIVE_PATH))
            }.code
        )
        Files.writeString(source, "package test\n// drift\n")
        assertEquals(
            "protected_source_changed",
            assertFailsWith<AcceptanceAdmissionException> { lease!!.verifyStable("contract-drift") }.code
        )
        lease!!.close()
        Files.writeString(source, "package test\n")

        val racedOutput = repository.resolve("raced-output")
        val raced = assertFailsWith<AcceptanceAdmissionException> {
            controller.admit(listOf(stage), options, racedOutput)
        }
        assertEquals("run_output_exists", raced.code)
        assertEquals("raced", Files.readString(racedOutput.resolve("intruder.txt")))
        AppendixBAcceptanceRunLock.acquire(repository.resolve(AppendixBAcceptanceLockProbe.RELATIVE_PATH)).close()
    }

    private fun testCommand(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        check(process.waitFor() == 0) { process.inputStream.bufferedReader().readText() }
        return process.inputStream.bufferedReader().readText().trim()
    }

    private fun testSha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun testSha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `scoring child inherits the admitted JVM temp authority`() {
        val command = scoringChildCommand(
            javaHome = "/jdk",
            classpath = "/bundle/lib/all.jar",
            jvmTemp = "/external/acceptance-jvm"
        )

        assertEquals("/jdk/bin/java", command[0])
        assertEquals("-Djava.io.tmpdir=/external/acceptance-jvm", command[1])
        assertEquals("-cp", command[2])
        assertEquals("/bundle/lib/all.jar", command[3])
    }

    @Test
    fun `acceptance invocation never delegates for direct or pipeline profile`() {
        assertTrue(
            AcceptanceInvocationDetector.requestsAcceptance(
                arrayOf(
                    "run",
                    "-acceptance-profile",
                    "appendix-b-full"
                )
            )
        )
        assertFalse(
            LocalRunDaemonClient.shouldDelegate(
                arrayOf(
                    "run",
                    "-acceptance-profile",
                    "appendix-b-full"
                ),
                env = emptyMap()
            )
        )

        val pipeline = createTempDirectory("acceptance-detector-").resolve("pipeline.json")
        Files.writeString(
            pipeline,
            """
            {
              "primary": {
                "id": "appendix",
                "acceptanceProfile": "runtime-usability-smoke"
              }
            }
            """.trimIndent()
        )
        assertTrue(
            AcceptanceInvocationDetector.requestsAcceptance(
                arrayOf("run", "-pipeline-config", pipeline.toString())
            )
        )
        assertFalse(
            LocalRunDaemonClient.shouldDelegate(
                arrayOf("run", "-pipeline-config", pipeline.toString()),
                env = emptyMap()
            )
        )
    }

    @Test
    fun `OS lock is exclusive and stale owner content is diagnostic only`() {
        val repositoryRoot = createTempDirectory("acceptance-lock-")
        val lockPath = repositoryRoot.resolve(AppendixBAcceptanceLockProbe.RELATIVE_PATH)
        val first = AppendixBAcceptanceRunLock.acquire(lockPath)
        first.writeOwner("""{"schemaVersion":"appendix-b-acceptance-lock-v1","pid":101}""")

        val blocked = assertFailsWith<AcceptanceAdmissionException> {
            AppendixBAcceptanceRunLock.acquire(lockPath)
        }
        assertEquals("acceptance_lock_held", blocked.code)
        assertTrue(blocked.message.orEmpty().contains("\"pid\":101"))
        assertFailsWith<CliUsageException> {
            AppendixBAcceptanceLockProbe.requireAvailableForDaemonStart(repositoryRoot)
        }

        first.close()

        AppendixBAcceptanceRunLock.acquire(lockPath).use { second ->
            second.writeOwner("""{"schemaVersion":"appendix-b-acceptance-lock-v1","pid":202}""")
        }
        AppendixBAcceptanceLockProbe.requireAvailableForDaemonStart(repositoryRoot)
        assertTrue(Files.readString(lockPath).contains("\"pid\":202"))
    }

    @Test
    fun `distribution manifest accepts exact dirty-tree inventory but rejects unsafe paths`() {
        val hash = "a".repeat(64)
        val valid = AcceptanceDistributionManifestV1(
            distributionId = hash,
            createdAtUtc = "2026-07-19T12:00:00Z",
            repositoryRoot = "/repo",
            sourceRevision = "revision",
            sourceTreeClean = false,
            javaVersion = "17",
            javaHome = "/java/home",
            javaExecutableSha256 = hash,
            javaReleaseSha256 = hash,
            javaModulesSha256 = hash,
            acceptanceJvmTmpDir = "/acceptance-jvm-tmp",
            mainClass = "com.achulkov.evomolior.cli.EvoMoliorCliMainKt",
            configurationSha256 = hash,
            modelAssignmentsSha256 = hash,
            codexCliPath = "/opt/codex",
            codexCliSha256 = hash,
            codexCliVersion = "codex 1",
            gateEvidence = listOf(AcceptanceGateEvidenceV1("build/gate.log", hash)),
            sourceFiles = listOf(AcceptanceDistributionFileV1("evomolior/core/build.gradle.kts", hash, 1)),
            files = listOf(AcceptanceDistributionFileV1("lib/cli.jar", hash, 1))
        )
        assertFalse(valid.sourceTreeClean)

        assertFailsWith<IllegalArgumentException> {
            valid.copy(
                files = listOf(AcceptanceDistributionFileV1("../mutable.jar", hash, 1))
            )
        }
    }

    @Test
    fun `distribution ID commits the final launcher inventory and external authorities`() {
        val hash = "a".repeat(64)
        val unsigned = AcceptanceDistributionManifestV1(
            distributionId = hash,
            createdAtUtc = "2026-07-19T12:00:00Z",
            repositoryRoot = "/repo",
            sourceRevision = "revision",
            sourceTreeClean = false,
            javaVersion = "17",
            javaHome = "/java/home",
            javaExecutableSha256 = hash,
            javaReleaseSha256 = hash,
            javaModulesSha256 = hash,
            acceptanceJvmTmpDir = "/acceptance-jvm-tmp",
            mainClass = "com.achulkov.evomolior.cli.EvoMoliorCliMainKt",
            configurationSha256 = hash,
            modelAssignmentsSha256 = hash,
            codexCliPath = "/opt/codex",
            codexCliSha256 = hash,
            codexCliVersion = "codex 1",
            gateEvidence = listOf(AcceptanceGateEvidenceV1("build/gate.log", hash)),
            sourceFiles = listOf(AcceptanceDistributionFileV1("evomolior/core/build.gradle.kts", hash, 1)),
            files = listOf(
                AcceptanceDistributionFileV1("bin/evomolior-acceptance", hash, 1),
                AcceptanceDistributionFileV1("lib/cli.jar", hash, 1)
            )
        )
        val sealed = unsigned.copy(distributionId = canonicalDistributionId(unsigned))

        verifyDistributionIdentity(sealed)

        val changedLauncher = sealed.copy(
            files = sealed.files.map {
                if (it.path == "bin/evomolior-acceptance") it.copy(sha256 = "b".repeat(64)) else it
            }
        )
        val rejected = assertFailsWith<AcceptanceAdmissionException> {
            verifyDistributionIdentity(changedLauncher)
        }
        assertEquals("distribution_id_mismatch", rejected.code)

        val changedTempAuthority = sealed.copy(acceptanceJvmTmpDir = "/different-acceptance-jvm-tmp")
        assertEquals(
            "distribution_id_mismatch",
            assertFailsWith<AcceptanceAdmissionException> {
                verifyDistributionIdentity(changedTempAuthority)
            }.code
        )
        assertEquals(
            "distribution_id_mismatch",
            assertFailsWith<AcceptanceAdmissionException> {
                verifyDistributionIdentity(sealed.copy(configurationSha256 = "c".repeat(64)))
            }.code
        )
        assertEquals(
            "distribution_id_mismatch",
            assertFailsWith<AcceptanceAdmissionException> {
                verifyDistributionIdentity(sealed.copy(javaModulesSha256 = "d".repeat(64)))
            }.code
        )
    }

    @Test
    fun `JDK admission binds home executable release and runtime modules hashes`() {
        val hash = "a".repeat(64)
        val manifest = AcceptanceDistributionManifestV1(
            distributionId = hash,
            createdAtUtc = "2026-07-26T00:00:00Z",
            repositoryRoot = "/repo",
            sourceRevision = "revision",
            sourceTreeClean = true,
            javaVersion = System.getProperty("java.runtime.version"),
            javaHome = javaHome.toString(),
            javaExecutableSha256 = javaExecutableSha256,
            javaReleaseSha256 = javaReleaseSha256,
            javaModulesSha256 = javaModulesSha256,
            acceptanceJvmTmpDir = "/acceptance-jvm-tmp",
            mainClass = "com.achulkov.evomolior.cli.EvoMoliorCliMainKt",
            configurationSha256 = hash,
            modelAssignmentsSha256 = hash,
            codexCliPath = "/opt/codex",
            codexCliSha256 = hash,
            codexCliVersion = "codex 1",
            gateEvidence = listOf(AcceptanceGateEvidenceV1("build/gate.log", hash)),
            sourceFiles = listOf(AcceptanceDistributionFileV1("evomolior/core/build.gradle.kts", hash, 1)),
            files = listOf(AcceptanceDistributionFileV1("lib/cli.jar", hash, 1)),
        )

        assertEquals("jdk_17", verifyAcceptanceJavaRuntime(manifest).id)
        assertEquals(
            "jdk_home_mismatch",
            assertFailsWith<AcceptanceAdmissionException> {
                verifyAcceptanceJavaRuntime(manifest.copy(javaHome = "/different/jdk"))
            }.code,
        )
        assertEquals(
            "jdk_java_hash_mismatch",
            assertFailsWith<AcceptanceAdmissionException> {
                verifyAcceptanceJavaRuntime(manifest.copy(javaExecutableSha256 = "b".repeat(64)))
            }.code,
        )
        assertEquals(
            "jdk_release_hash_mismatch",
            assertFailsWith<AcceptanceAdmissionException> {
                verifyAcceptanceJavaRuntime(manifest.copy(javaReleaseSha256 = "b".repeat(64)))
            }.code,
        )
        assertEquals(
            "jdk_modules_hash_mismatch",
            assertFailsWith<AcceptanceAdmissionException> {
                verifyAcceptanceJavaRuntime(manifest.copy(javaModulesSha256 = "b".repeat(64)))
            }.code,
        )
    }

    @Test
    fun `JVM temp authority must be launcher-bound, JVM-bound, and distinct from Codex temp`() {
        val root = createTempDirectory("acceptance-temp-authority-")
        val jvmTemp = Files.createDirectories(root.resolve("jvm"))
        val codexTemp = Files.createDirectories(root.resolve("codex"))
        val alternateTemp = Files.createDirectories(root.resolve("alternate"))
        val unrelatedCodexTemp = createTempDirectory("acceptance-unrelated-codex-")
        val systemTemp = createTempDirectory("acceptance-system-temp-")
        val systemCodexTemp = Files.createDirectories(systemTemp.resolve("codex"))
        val symlinkedCodexTemp = root.resolve("codex-system-link")
        Files.createSymbolicLink(symlinkedCodexTemp, systemCodexTemp)
        val hash = "a".repeat(64)
        val manifest = AcceptanceDistributionManifestV1(
            distributionId = hash,
            createdAtUtc = "2026-07-19T12:00:00Z",
            repositoryRoot = root.toString(),
            sourceRevision = "revision",
            sourceTreeClean = false,
            javaVersion = "17",
            javaHome = "/java/home",
            javaExecutableSha256 = hash,
            javaReleaseSha256 = hash,
            javaModulesSha256 = hash,
            acceptanceJvmTmpDir = jvmTemp.toString(),
            mainClass = "com.achulkov.evomolior.cli.EvoMoliorCliMainKt",
            configurationSha256 = hash,
            modelAssignmentsSha256 = hash,
            codexCliPath = "/opt/codex",
            codexCliSha256 = hash,
            codexCliVersion = "codex 1",
            gateEvidence = listOf(AcceptanceGateEvidenceV1("build/gate.log", hash)),
            sourceFiles = listOf(AcceptanceDistributionFileV1("evomolior/core/build.gradle.kts", hash, 1)),
            files = listOf(AcceptanceDistributionFileV1("bin/evomolior-acceptance", hash, 1))
        )
        val validEnv = mapOf(
            ACCEPTANCE_JVM_TMPDIR_ENV to jvmTemp.toString(),
            ACCEPTANCE_CODEX_TMPDIR_ENV to codexTemp.toString()
        )

        assertEquals(
            "acceptance_jvm_temp_missing",
            assertFailsWith<AcceptanceAdmissionException> {
                resolveAcceptanceTempAuthorities(
                    validEnv - ACCEPTANCE_JVM_TMPDIR_ENV,
                    root,
                    manifest,
                    jvmTemp
                )
            }.code
        )
        assertEquals(
            "acceptance_jvm_temp_authority_mismatch",
            assertFailsWith<AcceptanceAdmissionException> {
                resolveAcceptanceTempAuthorities(
                    validEnv + (ACCEPTANCE_JVM_TMPDIR_ENV to alternateTemp.toString()),
                    root,
                    manifest,
                    jvmTemp
                )
            }.code
        )
        assertEquals(
            "acceptance_jvm_temp_mismatch",
            assertFailsWith<AcceptanceAdmissionException> {
                resolveAcceptanceTempAuthorities(validEnv, root, manifest, alternateTemp)
            }.code
        )
        assertEquals(
            "acceptance_temp_not_dedicated",
            assertFailsWith<AcceptanceAdmissionException> {
                resolveAcceptanceTempAuthorities(
                    validEnv + (ACCEPTANCE_CODEX_TMPDIR_ENV to jvmTemp.toString()),
                    root,
                    manifest,
                    jvmTemp
                )
            }.code
        )
        assertEquals(
            "codex_temp_volume_unauthorized",
            assertFailsWith<AcceptanceAdmissionException> {
                resolveAcceptanceTempAuthorities(
                    validEnv + (ACCEPTANCE_CODEX_TMPDIR_ENV to unrelatedCodexTemp.toString()),
                    root,
                    manifest,
                    jvmTemp
                )
            }.code
        )
        assertEquals(
            "codex_temp_volume_unauthorized",
            assertFailsWith<AcceptanceAdmissionException> {
                resolveAcceptanceTempAuthorities(
                    validEnv + (ACCEPTANCE_CODEX_TMPDIR_ENV to symlinkedCodexTemp.toString()),
                    root,
                    manifest,
                    systemTemp
                )
            }.code
        )
    }

    @Test
    fun `protected source snapshot excludes SQLite families sidecars and generated metadata`() {
        val repository = createTempDirectory("acceptance-source-filter-")
        val sourceRoot = Files.createDirectories(repository.resolve("evomolior"))
        Files.writeString(sourceRoot.resolve("Source.kt"), "package test\n")
        listOf(
            "authority.db",
            "authority.db-wal",
            "authority.db-shm",
            "authority.sqlite",
            "authority.sqlite-wal",
            "authority.sqlite3-journal",
            "orphan-wal",
            "orphan-shm",
            "orphan-journal"
        ).forEach { name ->
            Files.writeString(sourceRoot.resolve(name), "excluded")
            assertTrue(isAcceptanceDatabaseArtifact(name))
        }
        Files.writeString(sourceRoot.resolve(".DS_Store"), "excluded")
        Files.writeString(
            Files.createDirectories(sourceRoot.resolve(".kotlin")).resolve("diagnostics.bin"),
            "excluded"
        )
        val distribution = createTempDirectory("acceptance-source-filter-distribution-")

        val snapshot = snapshotProtectedRepositoryFiles(repository, distribution)

        assertEquals(setOf("evomolior/Source.kt"), snapshot.keys)
    }

    @Test
    fun `protected source snapshot excludes the admitted acceptance JVM temp authority`() {
        val repository = createTempDirectory("acceptance-runtime-filter-")
        val sourceRoot = Files.createDirectories(repository.resolve("evomolior"))
        Files.writeString(sourceRoot.resolve("Source.kt"), "package test\n")
        val runtimeRoot = Files.createDirectories(
            repository.resolve("evomolior/cli/.evomolior-tmp/acceptance-run")
        )
        listOf("ready.port", "stdout.log", "stderr.log", "androidx_sqlite.tmp").forEach { name ->
            Files.writeString(runtimeRoot.resolve(name), "runtime")
        }
        val distribution = createTempDirectory("acceptance-runtime-filter-distribution-")

        val snapshot = snapshotProtectedRepositoryFiles(
            repositoryRoot = repository,
            distributionRoot = distribution,
            excludedRoots = listOf(runtimeRoot)
        )

        assertEquals(setOf("evomolior/Source.kt"), snapshot.keys)
    }

    @Test
    fun `admission rejection writes negative evidence and never publishes fallback or results`() {
        val input = createTempDirectory("acceptance-rejection-input-")
        val output = createTempDirectory("acceptance-rejection-output-")
        val pipeline = createTempDirectory("acceptance-rejection-config-").resolve("pipeline.json")
        Files.writeString(
            pipeline,
            """
            {
              "primary": {
                "id": "appendix",
                "prompt": "Produce a validated final report.",
                "fast": true,
                "rounds": 6,
                "groupCount": 2,
                "councilExecutionPurpose": "APPENDIX_B_ACCEPTANCE",
                "acceptanceProfile": "appendix-b-full",
                "finalOutput": {
                  "relativePath": "deliverable.md",
                  "mediaType": "text/markdown"
                }
              }
            }
            """.trimIndent()
        )
        var runtimeCreations = 0
        val ownerDiagnostics = mutableListOf<String>()
        val result = runCli(
            options = CliOptions(
                prompt = "ignored in favor of configured primary",
                inputDir = input,
                outputDir = output,
                openRouterApiKey = "/tmp/codex",
                llmBackend = LlmBackend.CODEX_CHATGPT,
                modelOverrides = emptyMap(),
                debug = false,
                fast = false,
                rounds = 1,
                runId = "acceptance-admission-rejected",
                pipelineConfigPath = pipeline
            ),
            runtimeFactory = { _, _ ->
                runtimeCreations += 1
                error("runtime must not be created")
            },
            acceptanceAdmissionController = AcceptanceAdmissionController { _, _, _ ->
                throw AcceptanceAdmissionException(
                    "distribution_file_changed",
                    "Immutable bundle changed before provider construction"
                )
            },
            acceptanceAdmissionDiagnosticSink = ownerDiagnostics::add
        )

        val runRoot = output.resolve("acceptance-admission-rejected")
        assertFalse(result.completedSuccessfully)
        assertEquals(0, runtimeCreations)
        assertTrue(Files.isRegularFile(runRoot.resolve("prompt.txt")))
        assertTrue(Files.readString(runRoot.resolve("summary.json")).contains("\"status\": \"REJECTED\""))
        assertTrue(Files.isRegularFile(runRoot.resolve("startup-rejection.json")))
        assertFalse(Files.exists(runRoot.resolve("results.zip")))
        assertFalse(Files.exists(runRoot.resolve("pipeline-results.zip")))
        assertFalse(Files.exists(runRoot.resolve("outputs")))
        val rejection = StartupRejectionFileStore { runId ->
            runRoot.resolve("control").toFile()
        }.read("acceptance-admission-rejected")
        assertEquals(StartupRejectionPhase.ADMISSION, rejection?.phase)
        assertEquals(0, rejection?.providerExecutionCount)
        assertEquals(
            listOf("EvoMolior acceptance admission rejected: code=distribution_file_changed"),
            ownerDiagnostics
        )
        assertFalse(Files.readString(runRoot.resolve("startup-rejection.json")).contains("distribution_file_changed"))
    }
}
