import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.jvm.application.tasks.CreateStartScripts
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.time.Instant
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo
import kotlin.streams.asSequence

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
    alias(libs.plugins.kotlinx.serialization)
}

application {
    mainClass.set("com.achulkov.evomolior.cli.EvoMoliorCliMainKt")
    applicationName = "evomolior"
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(listOf("-Xjsr305=strict"))
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.named<CreateStartScripts>("startScripts").configure {
    doLast {
        val marker = """
set -- \
        -classpath "${'$'}CLASSPATH" \
        com.achulkov.evomolior.cli.EvoMoliorCliMainKt \
        "${'$'}@"
""".trimIndent()
        val snapshotBlock = """
# Snapshot the installed classpath into a private temp directory before launching.
# This keeps concurrent EvoMolior CLI processes isolated from `installDist` rewrites
# and from other CLI invocations that may update jars under APP_HOME/lib.
if [ "${'$'}{EVOMOLIOR_DISABLE_CLASSPATH_SNAPSHOT:-}" != "1" ] && ! "${'$'}cygwin" && ! "${'$'}msys" ; then
    SNAPSHOT_DIR="${'$'}{TMPDIR:-/tmp}/evomolior-cli-classpath-${'$'}${'$'}-$(date +%s)"
    mkdir -p "${'$'}SNAPSHOT_DIR/lib" || die "Could not create classpath snapshot directory: ${'$'}SNAPSHOT_DIR"
    SNAPSHOT_CLASSPATH=""
    OLD_IFS=${'$'}IFS
    IFS=:
    for classpath_entry in ${'$'}CLASSPATH
    do
        case ${'$'}classpath_entry in
            "${'$'}APP_HOME"/lib/*.jar)
                jar_name=${'$'}{classpath_entry##*/}
                cp -p "${'$'}classpath_entry" "${'$'}SNAPSHOT_DIR/lib/${'$'}jar_name" || die "Could not snapshot jar: ${'$'}classpath_entry"
                SNAPSHOT_CLASSPATH="${'$'}{SNAPSHOT_CLASSPATH:+${'$'}SNAPSHOT_CLASSPATH:}${'$'}SNAPSHOT_DIR/lib/${'$'}jar_name"
                ;;
            *)
                SNAPSHOT_CLASSPATH="${'$'}{SNAPSHOT_CLASSPATH:+${'$'}SNAPSHOT_CLASSPATH:}${'$'}classpath_entry"
                ;;
        esac
    done
    IFS=${'$'}OLD_IFS
    CLASSPATH=${'$'}SNAPSHOT_CLASSPATH
    trap 'rm -rf "${'$'}SNAPSHOT_DIR"' EXIT HUP INT TERM
fi

""".trimIndent()
        unixScript.writeText(unixScript.readText().replace(marker, snapshotBlock + marker))
    }
}

tasks.register("verifyStartScriptClasspathSnapshot") {
    notCompatibleWithConfigurationCache(
        "Start-script verification reads the generated launcher selected by the application plugin."
    )
    dependsOn(tasks.named<CreateStartScripts>("startScripts"))
    doLast {
        val script = tasks.named<CreateStartScripts>("startScripts").get().unixScript
        check(script.readText().contains("Snapshot the installed classpath")) {
            "Generated Unix start script is missing the EvoMolior classpath snapshot block."
        }
    }
}

tasks.named("installDist").configure {
    dependsOn("verifyStartScriptClasspathSnapshot")
}

fun evomoliorSystemdService(userLauncher: File, userBinDir: File): String = """
    [Unit]
    Description=EvoMolior local run daemon
    After=network.target

    [Service]
    Type=simple
    ExecStart=${userLauncher.absolutePath} daemon start
    Restart=always
    RestartSec=5
    Environment=PATH=${userBinDir.absolutePath}:/usr/local/bin:/usr/bin:/bin

    [Install]
    WantedBy=default.target
    """.trimIndent() + "\n"

tasks.register("installUserCli") {
    group = "application"
    description = "Installs the evomolior launcher on the user PATH and configures the local run daemon."
    dependsOn(tasks.named("installDist"))
    doLast {
        val home = providers.systemProperty("user.home").get()
        val buildInstallDir = layout.buildDirectory.dir("install/evomolior").get().asFile
        val userInstallDir = file("$home/.local/share/evomolior/evomolior")
        if (userInstallDir.exists()) {
            userInstallDir.deleteRecursively()
        }
        buildInstallDir.copyRecursively(userInstallDir, overwrite = true)
        val installBin = userInstallDir.resolve("bin/evomolior")
        installBin.setExecutable(true)
        userInstallDir.resolve("bin/evomolior.bat").setExecutable(true)
        val userBinDir = file("$home/.local/bin").also { it.mkdirs() }
        val userLauncher = userBinDir.resolve("evomolior")
        if (userLauncher.exists() || Files.isSymbolicLink(userLauncher.toPath())) {
            userLauncher.delete()
        }
        userLauncher.writeText(
            """
            #!/bin/sh
            exec "${installBin.absolutePath}" "${'$'}@"
            """.trimIndent() + "\n"
        )
        userLauncher.setExecutable(true)

        val pathLine = """export PATH="${'$'}HOME/.local/bin:${'$'}PATH""""
        listOf(".zshrc", ".zprofile", ".bashrc", ".profile").forEach { profileName ->
            val profile = file("$home/$profileName")
            if (!profile.exists()) {
                profile.writeText("\n# EvoMolior CLI\n$pathLine\n")
            } else if (!profile.readText().contains("\$HOME/.local/bin")) {
                profile.appendText("\n# EvoMolior CLI\n$pathLine\n")
            }
        }

        val launchAgentsDir = file("$home/Library/LaunchAgents").also { it.mkdirs() }
        val plist = launchAgentsDir.resolve("com.achulkov.evomolior.daemon.plist")
        val logDir = file("$home/.evomolior/daemon").also { it.mkdirs() }
        plist.writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "https://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
              <key>Label</key>
              <string>com.achulkov.evomolior.daemon</string>
              <key>ProgramArguments</key>
              <array>
                <string>${userLauncher.absolutePath}</string>
                <string>daemon</string>
                <string>start</string>
              </array>
              <key>RunAtLoad</key>
              <true/>
              <key>KeepAlive</key>
              <true/>
              <key>StandardOutPath</key>
              <string>${logDir.resolve("daemon.out.log").absolutePath}</string>
              <key>StandardErrorPath</key>
              <string>${logDir.resolve("daemon.err.log").absolutePath}</string>
            </dict>
            </plist>
            """.trimIndent()
        )

        if (System.getProperty("os.name").lowercase().contains("mac")) {
            val uid = "id -u".runShell().trim()
            "launchctl bootout gui/$uid ${plist.absolutePath}".runCatchingShell(quiet = true)
            "launchctl bootstrap gui/$uid ${plist.absolutePath}".runCatchingShell()
            "launchctl kickstart -k gui/$uid/com.achulkov.evomolior.daemon".runCatchingShell()
        } else if (System.getProperty("os.name").lowercase().contains("linux")) {
            val systemdUserDir = file("$home/.config/systemd/user").also { it.mkdirs() }
            val service = systemdUserDir.resolve("evomolior-daemon.service")
            service.writeText(evomoliorSystemdService(userLauncher, userBinDir))
            val systemdConfigured = listOf(
                "systemctl --user daemon-reload",
                "systemctl --user enable evomolior-daemon.service",
                "systemctl --user restart evomolior-daemon.service"
            ).all { it.runCatchingShell() }
            val lingerEnabled = "loginctl enable-linger $(id -un)".runCatchingShell(quiet = true)
            val skipStrictServiceInstall = System.getenv("EVOMOLIOR_SKIP_DAEMON_SERVICE_INSTALL") == "1"
            if ((!systemdConfigured || !lingerEnabled) && !skipStrictServiceInstall) {
                error(
                    "EvoMolior Linux daemon service could not be fully enabled. " +
                        "Run `sudo loginctl enable-linger ${System.getProperty("user.name")}` if lingering failed, " +
                        "then rerun installDist. For CLI-only installs set EVOMOLIOR_SKIP_DAEMON_SERVICE_INSTALL=1."
                )
            }
            println("EvoMolior daemon systemd user service: ${service.absolutePath}")
        }
        println("EvoMolior launcher installed: ${userLauncher.absolutePath}")
        println("EvoMolior distribution installed: ${userInstallDir.absolutePath}")
        println("Open a new terminal or run: export PATH=\"\$HOME/.local/bin:\$PATH\"")
        if (System.getProperty("os.name").lowercase().contains("mac")) {
            println("EvoMolior daemon LaunchAgent: ${plist.absolutePath}")
        } else if (System.getProperty("os.name").lowercase().contains("linux")) {
            println("Check daemon with: systemctl --user status evomolior-daemon.service")
        }
    }
}

tasks.register("verifyUserCliInstall") {
    group = "verification"
    description = "Verifies the user-local evomolior launcher and OS service definition installed by installUserCli."
    doLast {
        val home = providers.systemProperty("user.home").get()
        val userLauncher = file("$home/.local/bin/evomolior")
        val installedLauncher = file("$home/.local/share/evomolior/evomolior/bin/evomolior")
        check(userLauncher.isFile && userLauncher.canExecute()) {
            "User launcher is missing or not executable: ${userLauncher.absolutePath}"
        }
        check(installedLauncher.isFile && installedLauncher.canExecute()) {
            "Installed launcher is missing or not executable: ${installedLauncher.absolutePath}"
        }
        val osName = System.getProperty("os.name").lowercase()
        if (osName.contains("linux")) {
            val service = file("$home/.config/systemd/user/evomolior-daemon.service")
            val serviceText = service.takeIf(File::isFile)?.readText().orEmpty()
            check(serviceText.contains("ExecStart=${userLauncher.absolutePath} daemon start")) {
                "Linux systemd service is missing expected ExecStart: ${service.absolutePath}"
            }
            check(serviceText.contains("WantedBy=default.target")) {
                "Linux systemd service is missing WantedBy=default.target: ${service.absolutePath}"
            }
        }
        if (osName.contains("mac")) {
            val plist = file("$home/Library/LaunchAgents/com.achulkov.evomolior.daemon.plist")
            val plistText = plist.takeIf(File::isFile)?.readText().orEmpty()
            check(plistText.contains(userLauncher.absolutePath)) {
                "macOS LaunchAgent is missing user launcher path: ${plist.absolutePath}"
            }
        }
    }
}

tasks.named("installUserCli").configure {
    finalizedBy("verifyUserCliInstall")
}

private val ACCEPTANCE_CONFIG_HASH_PROPERTY = "acceptanceConfigSha256"
private val ACCEPTANCE_CODEX_CLI_PROPERTY = "acceptanceCodexCliPath"
private val ACCEPTANCE_JVM_TMPDIR_PROPERTY = "acceptanceJvmTmpDir"
private val ACCEPTANCE_CREATED_AT_UTC_PROPERTY = "acceptanceCreatedAtUtc"
private val ACCEPTANCE_MODEL_ASSIGNMENTS_HASH_PROPERTY = "acceptanceProviderModelAssignmentsSha256"
private val ACCEPTANCE_GATE_EVIDENCE_PROPERTY = "acceptanceGateEvidenceFiles"
private val ACCEPTANCE_MANIFEST_NAME = "acceptance-distribution-manifest-v1.json"
private val SHA_256 = Regex("[0-9a-f]{64}")
private val UTC_TIMESTAMP = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""")

private data class AcceptanceEvidence(
    val path: String,
    val sha256: String,
)

private data class AcceptanceInputs(
    val createdAtUtc: String,
    val configSha256: String,
    val codexCliPath: Path,
    val codexCliSha256: String,
    val codexCliVersion: String,
    val evidence: List<AcceptanceEvidence>,
    val javaVersion: String,
    val jvmTmpDir: Path,
    val modelAssignmentsSha256: String,
    val repositoryRoot: String,
    val sourceClean: Boolean,
    val sourceRevision: String,
    val sourceFiles: List<AcceptanceFile>,
)

private fun sha256(file: Path): String =
    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }

private fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

private fun Project.acceptanceProperty(name: String): String =
    providers.gradleProperty(name).orNull?.trim()?.takeIf(String::isNotEmpty)
        ?: error("Missing required acceptance property -P$name.")

private fun Project.acceptanceSha256Property(name: String): String =
    acceptanceProperty(name).lowercase().also {
        check(SHA_256.matches(it)) {
            "Acceptance property -P$name must be a lowercase SHA-256 hexadecimal digest."
        }
    }

private fun Project.gitOutput(vararg command: String): String =
    ProcessBuilder(*command)
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
        .let { process ->
            val output = process.inputStream.bufferedReader().readText().trim()
            check(process.waitFor() == 0) {
                "Acceptance distribution requires git metadata; `${command.joinToString(" ")}` failed: $output"
            }
            output
        }

private fun Project.acceptanceInputs(): AcceptanceInputs {
    check(System.getProperty("java.specification.version") == "17") {
        "Acceptance distribution requires the Gradle process to run on JDK 17; found " +
            "${System.getProperty("java.runtime.version")} at ${System.getProperty("java.home")}."
    }
    val codexCli = file(acceptanceProperty(ACCEPTANCE_CODEX_CLI_PROPERTY)).toPath().toRealPath()
    check(codexCli.isRegularFile() && Files.isExecutable(codexCli)) {
        "Acceptance Codex CLI must be an executable regular file: ${codexCli.absolutePathString()}"
    }
    val codexVersion = ProcessBuilder(codexCli.toString(), "--version")
        .redirectErrorStream(true)
        .start()
        .let { process ->
            val output = process.inputStream.bufferedReader().readText().trim()
            check(process.waitFor() == 0 && output.isNotBlank()) {
                "Acceptance Codex CLI version probe failed for ${codexCli.absolutePathString()}: $output"
            }
            output
        }
    val evidenceProperty = acceptanceProperty(ACCEPTANCE_GATE_EVIDENCE_PROPERTY)
    val evidence = evidenceProperty.split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { rawPath ->
            val path = rootProject.file(rawPath).toPath().toRealPath()
            check(path.isRegularFile()) {
                "Acceptance gate evidence must be a regular file: ${path.absolutePathString()}"
            }
            AcceptanceEvidence(
                path = path.relativeTo(rootDir.toPath().toRealPath()).toString().replace('\\', '/'),
                sha256 = sha256(path),
            )
        }
        .sortedBy(AcceptanceEvidence::path)
    check(evidence.isNotEmpty()) {
        "Acceptance distribution requires at least one file in -P$ACCEPTANCE_GATE_EVIDENCE_PROPERTY."
    }
    check(evidence.map(AcceptanceEvidence::path).distinct().size == evidence.size) {
        "Acceptance gate evidence files must be unique."
    }
    val sourceStatus = gitOutput("git", "status", "--porcelain=v1", "--untracked-files=all")
    val createdAtUtc = acceptanceProperty(ACCEPTANCE_CREATED_AT_UTC_PROPERTY)
    check(
        UTC_TIMESTAMP.matches(createdAtUtc) &&
            runCatching { Instant.parse(createdAtUtc).toString() == createdAtUtc }.getOrDefault(false),
    ) {
        "Acceptance property -P$ACCEPTANCE_CREATED_AT_UTC_PROPERTY must be an RFC 3339 UTC second " +
            "timestamp, for example 2026-07-19T00:00:00Z."
    }
    return AcceptanceInputs(
        createdAtUtc = createdAtUtc,
        configSha256 = acceptanceSha256Property(ACCEPTANCE_CONFIG_HASH_PROPERTY),
        codexCliPath = codexCli,
        codexCliSha256 = sha256(codexCli),
        codexCliVersion = codexVersion,
        evidence = evidence,
        javaVersion = System.getProperty("java.runtime.version"),
        jvmTmpDir = file(acceptanceProperty(ACCEPTANCE_JVM_TMPDIR_PROPERTY)).toPath()
            .toAbsolutePath()
            .normalize()
            .also(Files::createDirectories)
            .toRealPath(),
        modelAssignmentsSha256 = acceptanceModelAssignmentsSha256(),
        repositoryRoot = rootDir.toPath().toRealPath().toString(),
        sourceClean = sourceStatus.isBlank(),
        sourceRevision = gitOutput("git", "rev-parse", "HEAD"),
        sourceFiles = acceptanceSourceFiles(),
    )
}

private fun json(value: String): String = buildString {
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

private fun jsonObject(values: Map<String, String>): String =
    values.toSortedMap().entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
        "${json(key)}:${json(value)}"
    }

private data class AcceptanceFile(
    val path: String,
    val sha256: String,
    val sizeBytes: Long,
)

private fun isAcceptanceDatabaseArtifact(fileName: String): Boolean {
    val normalized = fileName.lowercase()
    return normalized.matches(Regex(""".*\.(db|sqlite|sqlite3)(-(wal|shm|journal))?""")) ||
        normalized.endsWith("-wal") ||
        normalized.endsWith("-shm") ||
        normalized.endsWith("-journal")
}

private fun Project.acceptanceModelAssignmentsSha256(): String {
    val canonicalAssignments = listOf(
        "Archivist",
        "Auditor",
        "Builder",
        "Connector",
        "Critic",
        "Educator",
        "GraderBuilder",
        "GraderCritic",
        "GraderResearcher",
        "Inspector",
        "MetaPlanner",
        "Outlier",
        "PerspectiveTaker",
        "Planner",
        "Researcher",
    ).joinToString(separator = "") { "$it=gpt-5.6-luna\n" }
    val calculated = sha256(canonicalAssignments)
    check(acceptanceSha256Property(ACCEPTANCE_MODEL_ASSIGNMENTS_HASH_PROPERTY) == calculated) {
        "Acceptance property -P$ACCEPTANCE_MODEL_ASSIGNMENTS_HASH_PROPERTY must equal the SHA-256 " +
            "of UTF-8 sorted role=model lines from ModelSelectionConfig.codexChatGptRuntimeAssignments(): $calculated"
    }
    return calculated
}

private fun Project.acceptanceSourceFiles(): List<AcceptanceFile> {
    val repositoryRoot = rootDir.toPath().toRealPath()
    val candidates = linkedSetOf<Path>()
    fun addFile(relativePath: String) {
        val path = repositoryRoot.resolve(relativePath)
        if (path.isRegularFile()) candidates.add(path)
    }
    fun addTree(relativePath: String) {
        val tree = repositoryRoot.resolve(relativePath)
        if (!tree.isDirectory()) return
        Files.walk(tree).use { paths ->
            paths.asSequence()
                .onEach { path ->
                    check(!Files.isSymbolicLink(path)) {
                        "Acceptance protected source trees must not contain symbolic links: $path"
                    }
                }
                .filter { it.isRegularFile() }
                .filter { path ->
                    val normalized = path.relativeTo(repositoryRoot).toString().replace('\\', '/')
                    "/build/" !in "/$normalized" &&
                        "/results/" !in "/$normalized" &&
                        "/.gradle/" !in "/$normalized" &&
                        "/.kotlin/" !in "/$normalized" &&
                        "/.idea/" !in "/$normalized" &&
                        path.fileName.toString() != ".DS_Store" &&
                        !isAcceptanceDatabaseArtifact(path.fileName.toString()) &&
                        path.fileName.toString() !=
                            "EvoMolior_Competitive_Progressive_Disclosure_Action_Plan_v1.1_agentsdiff.html"
                }
                .forEach { candidates.add(it) }
        }
    }

    listOf(
        "build.gradle.kts",
        "settings.gradle.kts",
        "gradle/libs.versions.toml",
        "documentation/EvoMolior_Competitive_Progressive_Disclosure_Action_Plan-1.html",
        "documentation/Remediation_plan_after_v72.md",
        "documentation/EvoMolior_Roadmap_and_Project_State.md",
    ).forEach(::addFile)
    addTree("build-logic")
    addTree("evomolior")

    check(candidates.isNotEmpty()) { "Acceptance source inventory is unexpectedly empty." }
    return candidates.asSequence()
        .map { path ->
            AcceptanceFile(
                path = path.relativeTo(repositoryRoot).toString().replace('\\', '/'),
                sha256 = sha256(path),
                sizeBytes = Files.size(path),
            )
        }
        .sortedBy(AcceptanceFile::path)
        .toList()
}

private fun acceptanceFiles(bundleRoot: Path): List<AcceptanceFile> =
    Files.walk(bundleRoot).use { paths ->
        paths.asSequence()
            .filter { it.isRegularFile() }
            .filter { it != bundleRoot.resolve(ACCEPTANCE_MANIFEST_NAME) }
            .map { path ->
                AcceptanceFile(
                    path = path.relativeTo(bundleRoot).toString().replace('\\', '/'),
                    sha256 = sha256(path),
                    sizeBytes = Files.size(path),
                )
            }
            .sortedBy(AcceptanceFile::path)
            .toList()
    }

private fun acceptanceManifestCanonicalPayload(
    inputs: AcceptanceInputs,
    files: List<AcceptanceFile>,
): String = buildString {
    append('{')
    append("\"schemaVersion\":\"acceptance-distribution-manifest-v1\"")
    append(",\"createdAtUtc\":${json(inputs.createdAtUtc)}")
    append(",\"repositoryRoot\":${json(inputs.repositoryRoot)}")
    append(",\"sourceRevision\":${json(inputs.sourceRevision)}")
    append(",\"sourceTreeClean\":${inputs.sourceClean}")
    append(",\"sourceFiles\":[")
    append(inputs.sourceFiles.joinToString(",") {
        """{"path":${json(it.path)},"sha256":${json(it.sha256)},"sizeBytes":${it.sizeBytes}}"""
    })
    append(']')
    append(",\"javaVersion\":${json(inputs.javaVersion)}")
    append(",\"acceptanceJvmTmpDir\":${json(inputs.jvmTmpDir.absolutePathString())}")
    append(",\"mainClass\":${json(application.mainClass.get())}")
    append(",\"configurationSha256\":${json(inputs.configSha256)}")
    append(",\"modelAssignmentsSha256\":${json(inputs.modelAssignmentsSha256)}")
    append(",\"codexCliPath\":${json(inputs.codexCliPath.absolutePathString())}")
    append(",\"codexCliSha256\":${json(inputs.codexCliSha256)}")
    append(",\"codexCliVersion\":${json(inputs.codexCliVersion)}")
    append(",\"gateEvidence\":[")
    append(inputs.evidence.joinToString(",") { """{"path":${json(it.path)},"sha256":${json(it.sha256)}}""" })
    append(']')
    append(",\"files\":[")
    append(files.joinToString(",") {
        """{"path":${json(it.path)},"sha256":${json(it.sha256)},"sizeBytes":${it.sizeBytes}}"""
    })
    append("]}")
}

private fun acceptanceManifest(
    distributionId: String,
    inputs: AcceptanceInputs,
    bundleRoot: Path,
): String = acceptanceManifestCanonicalPayload(inputs, acceptanceFiles(bundleRoot))
    .replaceFirst(
        "\"schemaVersion\":\"acceptance-distribution-manifest-v1\"",
        "\"schemaVersion\":\"acceptance-distribution-manifest-v1\",\"distributionId\":${json(distributionId)}",
    )

private fun acceptanceDistributionId(inputs: AcceptanceInputs, bundleRoot: Path): String =
    sha256(acceptanceManifestCanonicalPayload(inputs, acceptanceFiles(bundleRoot)))

private fun acceptanceContentId(inputs: AcceptanceInputs, installDirectory: Path): String {
    val sourceLaunchers = Files.list(installDirectory.resolve("bin")).use { paths ->
        paths.asSequence().filter { it.isRegularFile() }.sortedBy { it.name }
            .associate { it.name to sha256(it) }
    }
    val sourceLibraries = Files.list(installDirectory.resolve("lib")).use { paths ->
        paths.asSequence().filter { it.isRegularFile() && it.name.endsWith(".jar") }.sortedBy { it.name }
            .associate { it.name to sha256(it) }
    }
    check(sourceLibraries.isNotEmpty()) { "installDist did not produce library JARs in $installDirectory" }
    return sha256(
        jsonObject(sortedMapOf(
            "codexCliSha256" to inputs.codexCliSha256,
            "codexCliPath" to inputs.codexCliPath.absolutePathString(),
            "codexCliVersion" to inputs.codexCliVersion,
            "configSha256" to inputs.configSha256,
            "createdAtUtc" to inputs.createdAtUtc,
            "gateEvidenceSha256" to sha256(inputs.evidence.joinToString("\n") { "${it.path}:${it.sha256}" }),
            "javaVersion" to inputs.javaVersion,
            "jvmTmpDir" to inputs.jvmTmpDir.absolutePathString(),
            "launcherSha256" to jsonObject(sourceLaunchers),
            "librarySha256" to jsonObject(sourceLibraries),
            "providerModelAssignmentsSha256" to inputs.modelAssignmentsSha256,
            "repositoryRoot" to inputs.repositoryRoot,
            "sourceRevision" to inputs.sourceRevision,
            "sourceFilesSha256" to sha256(
                inputs.sourceFiles.joinToString("\n") { "${it.path}:${it.sha256}:${it.sizeBytes}" },
            ),
        )),
    )
}

private fun makeReadOnlyRecursively(root: Path) {
    Files.walk(root).use { paths ->
        paths.asSequence().sortedByDescending { it.nameCount }.forEach { path ->
            runCatching {
                val permissions = Files.getPosixFilePermissions(path).toMutableSet()
                permissions.removeAll(
                    setOf(
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.GROUP_WRITE,
                        PosixFilePermission.OTHERS_WRITE,
                    ),
                )
                Files.setPosixFilePermissions(path, permissions)
            }
        }
    }
}

private fun verifyReadOnlyRecursively(root: Path) {
    Files.walk(root).use { paths ->
        paths.asSequence().forEach { path ->
            runCatching {
                check(Files.getPosixFilePermissions(path).none {
                    it == PosixFilePermission.OWNER_WRITE ||
                        it == PosixFilePermission.GROUP_WRITE ||
                        it == PosixFilePermission.OTHERS_WRITE
                }) { "Acceptance distribution is writable: $path" }
            }.getOrElse { failure ->
                throw IllegalStateException("Could not verify immutable acceptance distribution path: $path", failure)
            }
        }
    }
}

private fun capturedSourceTreeClean(manifest: Path): Boolean {
    val value = Regex(""""sourceTreeClean":(true|false)""")
        .find(Files.readString(manifest, StandardCharsets.UTF_8))
        ?.groupValues
        ?.get(1)
        ?: error("Acceptance manifest is missing sourceTreeClean: $manifest")
    return value.toBooleanStrict()
}

val prepareAcceptanceDistribution = tasks.register("prepareAcceptanceDistribution") {
    notCompatibleWithConfigurationCache(
        "Acceptance preparation hashes current source, gate evidence, Git, JDK, and Codex metadata."
    )
    group = "distribution"
    description = "Validates Appendix B acceptance inputs and deterministically prepares its assembly key."
    dependsOn(tasks.named("installDist"))
    doLast {
        val installDirectory = layout.buildDirectory.dir("install/evomolior").get().asFile.toPath()
        check(installDirectory.isDirectory()) { "installDist output is missing: $installDirectory" }
        val contentId = acceptanceContentId(acceptanceInputs(), installDirectory)
        val prepared = layout.buildDirectory.file("acceptance/prepared/$contentId").get().asFile.toPath()
        Files.createDirectories(prepared.parent)
        Files.writeString(prepared, "$contentId\n", StandardCharsets.UTF_8)
        println("Prepared acceptance distribution assembly key: $contentId")
    }
}

val assembleAcceptanceDistribution = tasks.register("assembleAcceptanceDistribution") {
    notCompatibleWithConfigurationCache(
        "Acceptance assembly creates and permission-seals a content-addressed filesystem tree."
    )
    group = "distribution"
    description = "Copies installDist once into an immutable content-addressed Appendix B acceptance distribution."
    dependsOn(prepareAcceptanceDistribution)
    doLast {
        val inputs = acceptanceInputs()
        val installDirectory = layout.buildDirectory.dir("install/evomolior").get().asFile.toPath()
        val assemblyKey = acceptanceContentId(inputs, installDirectory)
        val prepared = layout.buildDirectory.file("acceptance/prepared/$assemblyKey").get().asFile.toPath()
        val candidateRoot = layout.buildDirectory.dir("acceptance/candidates/$assemblyKey").get().asFile.toPath()
        check(!candidateRoot.exists()) {
            "Refusing stale or partial acceptance candidate from an interrupted assembly: " +
                "$candidateRoot"
        }
        Files.createDirectories(candidateRoot.parent)
        check(installDirectory.toFile().copyRecursively(candidateRoot.toFile(), overwrite = false)) {
            "Acceptance installDist copy was incomplete: $candidateRoot"
        }
        val standardLauncher = candidateRoot.resolve("bin/evomolior")
        check(standardLauncher.isRegularFile()) { "Acceptance Unix launcher is missing: $standardLauncher" }
        val launcher = candidateRoot.resolve("bin/evomolior-acceptance")
        Files.copy(standardLauncher, launcher)
        val launcherText = Files.readString(launcher, StandardCharsets.UTF_8)
        val launcherBlock = """
                # Appendix B acceptance bundles execute directly from their immutable payload.
                export EVOMOLIOR_ACCEPTANCE_MANIFEST="${'$'}APP_HOME/$ACCEPTANCE_MANIFEST_NAME"
                export EVOMOLIOR_ACCEPTANCE_LAUNCHER="${'$'}APP_HOME/bin/evomolior-acceptance"
                export EVOMOLIOR_DISABLE_DAEMON=1
                export EVOMOLIOR_DISABLE_CLASSPATH_SNAPSHOT=1
                export EVOMOLIOR_ACCEPTANCE_JVM_TMPDIR="${inputs.jvmTmpDir.absolutePathString()}"

        """.trimIndent()
        check(!launcherText.contains("EVOMOLIOR_ACCEPTANCE_MANIFEST")) {
            "Acceptance launcher was unexpectedly pre-mutated: $launcher"
        }
        check(launcherText.contains("\nDEFAULT_JVM_OPTS=")) {
            "Acceptance launcher does not contain the generated JVM options declaration: $launcher"
        }
        check(launcherText.contains("\nCLASSPATH=")) {
            "Acceptance launcher does not contain the generated classpath declaration: $launcher"
        }
        val jvmOption = "\"-Djava.io.tmpdir=${inputs.jvmTmpDir.absolutePathString()}\""
        Files.writeString(
            launcher,
            launcherText
                .replaceFirst("\nDEFAULT_JVM_OPTS=", "\nDEFAULT_JVM_OPTS=$jvmOption\n#")
                .replaceFirst("\nCLASSPATH=", "\n$launcherBlock\nCLASSPATH="),
            StandardCharsets.UTF_8,
        )
        check(launcher.toFile().setExecutable(true, false)) {
            "Could not mark direct acceptance launcher executable: $launcher"
        }
        val standardWindowsLauncher = candidateRoot.resolve("bin/evomolior.bat")
        check(standardWindowsLauncher.isRegularFile()) {
            "Acceptance Windows launcher is missing: $standardWindowsLauncher"
        }
        val windowsLauncher = candidateRoot.resolve("bin/evomolior-acceptance.bat")
        Files.copy(standardWindowsLauncher, windowsLauncher)
        val windowsText = Files.readString(windowsLauncher, StandardCharsets.UTF_8)
        check(!windowsText.contains("EVOMOLIOR_ACCEPTANCE_MANIFEST")) {
            "Acceptance Windows launcher was unexpectedly pre-mutated: $windowsLauncher"
        }
        val windowsBlock = """
                rem Appendix B acceptance bundles execute directly from their immutable payload.
                set "EVOMOLIOR_ACCEPTANCE_MANIFEST=%APP_HOME%\$ACCEPTANCE_MANIFEST_NAME"
                set "EVOMOLIOR_ACCEPTANCE_LAUNCHER=%APP_HOME%\bin\evomolior-acceptance.bat"
                set "EVOMOLIOR_DISABLE_DAEMON=1"
                set "EVOMOLIOR_DISABLE_CLASSPATH_SNAPSHOT=1"
                set "EVOMOLIOR_ACCEPTANCE_JVM_TMPDIR=${inputs.jvmTmpDir.absolutePathString()}"
                set DEFAULT_JVM_OPTS="-Djava.io.tmpdir=${inputs.jvmTmpDir.absolutePathString()}"

        """.trimIndent().replace("\n", "\r\n") + "\r\n"
        Files.writeString(
            windowsLauncher,
            windowsText.replaceFirst("\r\nset CLASSPATH=", "\r\n$windowsBlock" + "set CLASSPATH="),
            StandardCharsets.UTF_8,
        )
        check(Files.list(candidateRoot.resolve("lib")).use { paths ->
            paths.asSequence().any { it.isRegularFile() && it.name.endsWith(".jar") }
        }) { "Acceptance bundle has no library JARs: $candidateRoot" }
        val contentId = acceptanceDistributionId(inputs, candidateRoot)
        val acceptanceRoot = layout.buildDirectory.dir("acceptance/$contentId").get().asFile.toPath()
        val manifest = acceptanceRoot.resolve(ACCEPTANCE_MANIFEST_NAME)
        if (!acceptanceRoot.exists()) {
            check(!Files.exists(acceptanceRoot)) { "Acceptance bundle path already exists: $acceptanceRoot" }
            Files.move(candidateRoot, acceptanceRoot)
            Files.writeString(manifest, acceptanceManifest(contentId, inputs, acceptanceRoot), StandardCharsets.UTF_8)
            makeReadOnlyRecursively(acceptanceRoot)
        }
        check(manifest.isRegularFile()) { "Acceptance manifest is missing: $manifest" }
        val manifestInputs = inputs.copy(sourceClean = capturedSourceTreeClean(manifest))
        check(Files.readString(manifest, StandardCharsets.UTF_8) == acceptanceManifest(contentId, manifestInputs, acceptanceRoot)) {
            "Existing acceptance distribution does not match its canonical manifest: $acceptanceRoot"
        }
        check(acceptanceDistributionId(manifestInputs, acceptanceRoot) == contentId) {
            "Existing acceptance distribution ID does not match its complete final payload inventory: $acceptanceRoot"
        }
        if (candidateRoot.exists()) {
            check(candidateRoot.toFile().deleteRecursively()) {
                "Could not remove verified temporary acceptance candidate: $candidateRoot"
            }
        }
        Files.writeString(prepared, "$contentId\n", StandardCharsets.UTF_8)
        println("Assembled immutable acceptance distribution: $acceptanceRoot")
    }
}

tasks.register("verifyAcceptanceDistribution") {
    notCompatibleWithConfigurationCache(
        "Acceptance verification re-hashes the sealed distribution and current external authorities."
    )
    group = "verification"
    description = "Verifies all immutable Appendix B acceptance distribution bytes and metadata."
    dependsOn(assembleAcceptanceDistribution)
    doLast {
        val inputs = acceptanceInputs()
        val assemblyKey = acceptanceContentId(
            inputs,
            layout.buildDirectory.dir("install/evomolior").get().asFile.toPath(),
        )
        val contentId = Files.readString(
            layout.buildDirectory.file("acceptance/prepared/$assemblyKey").get().asFile.toPath(),
            StandardCharsets.UTF_8,
        ).trim()
        check(SHA_256.matches(contentId)) { "Prepared acceptance distribution ID is invalid: $contentId" }
        val acceptanceRoot = layout.buildDirectory.dir("acceptance/$contentId").get().asFile.toPath()
        val manifest = acceptanceRoot.resolve(ACCEPTANCE_MANIFEST_NAME)
        check(manifest.isRegularFile() && acceptanceRoot.isDirectory()) {
            "Acceptance distribution is incomplete: $acceptanceRoot"
        }
        val expectedManifest = acceptanceManifest(contentId, inputs.copy(sourceClean = capturedSourceTreeClean(manifest)), acceptanceRoot)
        check(Files.readString(manifest, StandardCharsets.UTF_8) == expectedManifest) {
            "Acceptance manifest verification failed: $manifest"
        }
        check(acceptanceDistributionId(inputs.copy(sourceClean = capturedSourceTreeClean(manifest)), acceptanceRoot) == contentId) {
            "Acceptance distribution ID does not match its complete final payload inventory: $acceptanceRoot"
        }
        val launcher = Files.readString(acceptanceRoot.resolve("bin/evomolior"), StandardCharsets.UTF_8)
        val acceptanceLauncher = Files.readString(
            acceptanceRoot.resolve("bin/evomolior-acceptance"),
            StandardCharsets.UTF_8,
        )
        val windowsLauncher = Files.readString(
            acceptanceRoot.resolve("bin/evomolior.bat"),
            StandardCharsets.UTF_8,
        )
        val acceptanceWindowsLauncher = Files.readString(
            acceptanceRoot.resolve("bin/evomolior-acceptance.bat"),
            StandardCharsets.UTF_8,
        )
        check(
            !launcher.contains("EVOMOLIOR_ACCEPTANCE_MANIFEST") &&
                !windowsLauncher.contains("EVOMOLIOR_ACCEPTANCE_MANIFEST") &&
                acceptanceLauncher.contains(
                    "export EVOMOLIOR_ACCEPTANCE_MANIFEST=\"${'$'}APP_HOME/$ACCEPTANCE_MANIFEST_NAME\"",
                ) &&
                acceptanceLauncher.contains(
                    "export EVOMOLIOR_ACCEPTANCE_LAUNCHER=\"${'$'}APP_HOME/bin/evomolior-acceptance\"",
                ) &&
                acceptanceLauncher.contains("export EVOMOLIOR_DISABLE_DAEMON=1") &&
                acceptanceLauncher.contains("export EVOMOLIOR_DISABLE_CLASSPATH_SNAPSHOT=1") &&
                acceptanceLauncher.contains(
                    "export EVOMOLIOR_ACCEPTANCE_JVM_TMPDIR=\"${inputs.jvmTmpDir.absolutePathString()}\"",
                ) &&
                acceptanceLauncher.contains("-Djava.io.tmpdir=${inputs.jvmTmpDir.absolutePathString()}") &&
                acceptanceWindowsLauncher.contains(
                    "set \"EVOMOLIOR_ACCEPTANCE_MANIFEST=%APP_HOME%\\$ACCEPTANCE_MANIFEST_NAME\"",
                ) &&
                acceptanceWindowsLauncher.contains(
                    "set \"EVOMOLIOR_ACCEPTANCE_LAUNCHER=%APP_HOME%\\bin\\evomolior-acceptance.bat\"",
                ) &&
                acceptanceWindowsLauncher.contains("set \"EVOMOLIOR_DISABLE_DAEMON=1\"") &&
                acceptanceWindowsLauncher.contains(
                    "set \"EVOMOLIOR_DISABLE_CLASSPATH_SNAPSHOT=1\"",
                ) &&
                acceptanceWindowsLauncher.contains(
                    "set \"EVOMOLIOR_ACCEPTANCE_JVM_TMPDIR=${inputs.jvmTmpDir.absolutePathString()}\"",
                ) &&
                acceptanceWindowsLauncher.contains(
                    "-Djava.io.tmpdir=${inputs.jvmTmpDir.absolutePathString()}",
                ),
        ) {
            "Direct acceptance launchers must set manifest, launcher, daemon, classpath-snapshot, and JVM temp authorities."
        }
        verifyReadOnlyRecursively(acceptanceRoot)
        println("Verified immutable acceptance distribution: $acceptanceRoot")
    }
}

fun String.runShell(): String =
    ProcessBuilder("sh", "-lc", this).redirectErrorStream(true).start().let { process ->
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) error(output)
        output
    }

fun String.runCatchingShell(quiet: Boolean = false): Boolean =
    runCatching {
        runShell()
        true
    }.getOrElse { error ->
        if (!quiet) {
            println("Warning: command failed: $this")
            error.message?.takeIf(String::isNotBlank)?.let(::println)
        }
        false
    }

dependencies {
    implementation(project(":engine:runtime"))
    implementation(project(":engine:api"))
    implementation(project(":engine:agents"))

    implementation(libs.napier)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
}
