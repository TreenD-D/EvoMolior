package com.achulkov.evomolior.cli

import com.achulkov.evomolior.api.model.FinalOutputValidationContract
import java.nio.file.Files
import java.nio.file.Path

/**
 * A ready-to-run configuration for one of EvoMolior's best-fit applications.
 *
 * Each template pairs a pipeline config with a prompt scaffold. The output contract is
 * deterministic (sections, word bounds, URL hygiene, access dates, JSON shape), so a run cannot
 * publish a malformed or silently condensed deliverable as if it met the request.
 *
 * @param id Stable name used on the command line.
 * @param summary One-line description.
 * @param pipeline Pipeline config written as `pipeline.json`; its prompt file is `prompt.md`.
 * @param promptScaffold Objective scaffold written as `prompt.md`, with `<<...>>` placeholders.
 */
internal data class ApplicationTemplate(
    val id: String,
    val summary: String,
    val pipeline: CliPipelineConfig,
    val promptScaffold: String,
)

/** Built-in application templates and the `template` subcommand. */
internal object ApplicationTemplates {
    const val PIPELINE_FILE = "pipeline.json"
    const val PROMPT_FILE = "prompt.md"

    private fun stage(
        rounds: Int,
        maxAgentsPerGroup: Int,
        output: CliPipelineFinalOutputSpec,
    ) = CliPipelineConfig(
        primary = CliPipelineStageConfig(
            id = "primary",
            promptFile = PROMPT_FILE,
            fast = false,
            rounds = rounds,
            groupCount = 2,
            maxAgentsPerGroup = maxAgentsPerGroup,
            roundDurationMinutes = 0,
            finalOutput = output,
        )
    )

    /** All templates, in recommended order of first use. */
    val all: List<ApplicationTemplate> = listOf(
        ApplicationTemplate(
            id = "research-dossier",
            summary = "Evidence-graded landscape of sources, vendors, datasets or tools with per-entry " +
                "verification status, gaps, and recommendations.",
            pipeline = stage(
                rounds = 3,
                maxAgentsPerGroup = 3,
                output = CliPipelineFinalOutputSpec(
                    relativePath = "dossier.md",
                    mediaType = "text/markdown",
                    description = "Evidence-graded research dossier with per-entry verification status.",
                    validation = FinalOutputValidationContract(
                        minimumPublicUrlCount = 15,
                        requiredMarkdownSectionSignals = listOf(
                            "Executive summary", "Scope and method", "Inventory", "Evidence status",
                            "Gaps", "Recommendations", "Limitations", "References"
                        ),
                        requireMarkdownAccessDate = true,
                        maximumWordCount = 9_000,
                        minimumWordCount = 2_000,
                        requiredTextStatements = listOf("Discovery leads are not verified evidence"),
                    ),
                ),
            ),
            promptScaffold = """
                # Objective
                <<Describe the landscape to map: what kinds of sources, vendors, datasets, or tools, for
                which decision, and which regions or segments matter most.>>

                # Scope
                - In scope: <<products, regions, time window>>
                - Out of scope: <<what to ignore>>
                - Decision this dossier supports: <<e.g. shortlist two providers for a pilot>>

                # Evidence rules
                - Label every inventory entry VERIFIED (primary documentation inspected, with an ISO
                  access date), DISCOVERY (unverified lead), or GAP (relevant but not inspectable).
                - Discovery leads are not verified evidence: never count them toward findings.
                - Cite public URLs exactly; never retype a URL with invisible characters.
                - Record licensing, pricing, quotas, and coverage only when a source states them.
                - Put everything unresolved under Limitations instead of guessing.

                # Inputs
                Place seed lists, prior research exports, and requirement notes in the -input folder.
            """.trimIndent(),
        ),
        ApplicationTemplate(
            id = "systematic-screening",
            summary = "Criteria-driven inclusion/exclusion screening of supplied candidates with a " +
                "per-decision audit trail.",
            pipeline = stage(
                rounds = 3,
                maxAgentsPerGroup = 3,
                output = CliPipelineFinalOutputSpec(
                    relativePath = "screening-report.md",
                    mediaType = "text/markdown",
                    description = "Screening report with eligibility criteria and per-candidate decisions.",
                    validation = FinalOutputValidationContract(
                        requiredMarkdownSectionSignals = listOf(
                            "Review question", "Eligibility criteria", "Included", "Excluded",
                            "Evidence table", "Limitations"
                        ),
                        maximumWordCount = 8_000,
                        minimumWordCount = 800,
                    ),
                ),
            ),
            promptScaffold = """
                # Review question
                <<State the question, e.g. in PICO form: population, intervention, comparator, outcome.>>

                # Eligibility criteria
                - Include when: <<criteria>>
                - Exclude when: <<criteria, including hard negatives that look relevant>>

                # Screening rules
                - Screen every supplied candidate exactly once; give the decision, the criterion
                  applied, and the supporting passage cited as filename:Lstart-Lend.
                - Explain each exclusion; flag ambiguous candidates instead of forcing a decision.
                - Use only the supplied candidate records as evidence.

                # Inputs
                Place the candidate list (one record per line or a CSV) and any protocol in -input.
            """.trimIndent(),
        ),
        ApplicationTemplate(
            id = "code-audit",
            summary = "Architecture or change-risk review of an uploaded codebase with source-line " +
                "citations that the offline source checker verifies.",
            pipeline = stage(
                rounds = 2,
                maxAgentsPerGroup = 3,
                output = CliPipelineFinalOutputSpec(
                    relativePath = "audit.md",
                    mediaType = "text/markdown",
                    description = "Code audit with cited findings, proposed changes, and a validation plan.",
                    validation = FinalOutputValidationContract(
                        exactMarkdownHeadings = listOf(
                            "Summary", "Findings", "Evidence", "Proposed changes",
                            "Validation plan", "Risks and limitations"
                        ),
                        maximumWordCount = 6_000,
                        minimumWordCount = 800,
                    ),
                ),
            ),
            promptScaffold = """
                # Objective
                <<What to review: a subsystem, a change set, or a risk area, and why.>>

                # Review rules
                - Cite code as path/to/File.kt:L120-L140 so the source checker can verify each span;
                  quote exact lines in backticks before the citation when quoting.
                - Rank findings by impact and confidence; separate defects from suggestions.
                - Propose concrete changes and the tests that would verify them; say which checks
                  were not executed.

                # Inputs
                Copy the relevant source files (not build outputs) into the -input folder.
            """.trimIndent(),
        ),
        ApplicationTemplate(
            id = "evaluation-rubric",
            summary = "Reusable grading rubric, mechanical checks, and failure modes for a type of " +
                "deliverable, emitted as machine-readable JSON.",
            pipeline = stage(
                rounds = 2,
                maxAgentsPerGroup = 2,
                output = CliPipelineFinalOutputSpec(
                    relativePath = "rubric.json",
                    mediaType = "application/json",
                    description = "Evaluation rubric with dimensions, checks, failure modes, and calibration.",
                    validation = FinalOutputValidationContract(
                        requiredJsonRootKeys = listOf("dimensions", "checks", "failure_modes", "calibration_examples"),
                        minimumJsonArraySizes = mapOf("dimensions" to 3, "checks" to 5, "failure_modes" to 3),
                        requiredJsonArrayItemFields = mapOf(
                            "dimensions" to listOf("id", "description", "weight", "levels"),
                            "checks" to listOf("id", "description", "pass_condition"),
                            "failure_modes" to listOf("id", "description", "detection"),
                        ),
                    ),
                ),
            ),
            promptScaffold = """
                # Deliverable type to evaluate
                <<Describe the artefact (e.g. vendor comparison memo, API design doc) and its readers.>>

                # Rubric requirements
                - Dimensions with weights and LOW/MEDIUM/HIGH/EXCELLENT level descriptions.
                - Checks that can be applied mechanically, each with an explicit pass condition.
                - Failure modes, including deceptive signals that look good but are weak, with how to
                  detect each.
                - Calibration examples showing what distinguishes adjacent levels.

                # Inputs
                Place example deliverables (good and weak) and any existing guidelines in -input.
            """.trimIndent(),
        ),
        ApplicationTemplate(
            id = "decision-memo",
            summary = "Short, evidence-backed decision memo comparing options for one question.",
            pipeline = stage(
                rounds = 2,
                maxAgentsPerGroup = 2,
                output = CliPipelineFinalOutputSpec(
                    relativePath = "memo.md",
                    mediaType = "text/markdown",
                    description = "Decision memo with recommendation, options, evidence, risks, next steps.",
                    validation = FinalOutputValidationContract(
                        exactMarkdownHeadings = listOf(
                            "Recommendation", "Options considered", "Evidence", "Risks", "Next steps"
                        ),
                        maximumWordCount = 1_500,
                        minimumWordCount = 400,
                    ),
                ),
            ),
            promptScaffold = """
                # Decision
                <<The single question to decide, the deadline, and who decides.>>

                # Options
                <<List the options, including doing nothing.>>

                # Constraints and evidence rules
                - Ground every claim in the supplied inputs (cite filename:Lstart-Lend) or label it as
                  an assumption.
                - State what evidence would change the recommendation.
            """.trimIndent(),
        ),
    )

    /**
     * Finds a template by [id].
     *
     * @throws CliUsageException when no template has that id.
     */
    fun require(id: String): ApplicationTemplate = all.firstOrNull { it.id == id }
        ?: throw CliUsageException("Unknown template '$id'. Available: ${all.joinToString { it.id }}")

    /** Renders [template]'s pipeline config exactly as `init` writes it. */
    fun pipelineJson(template: ApplicationTemplate): String =
        cliJson.encodeToString(CliPipelineConfig.serializer(), template.pipeline)

    /**
     * Writes [template] into [directory], refusing to overwrite existing files.
     *
     * @return Paths written.
     * @throws CliUsageException when a target file already exists.
     */
    fun init(template: ApplicationTemplate, directory: Path): List<Path> {
        Files.createDirectories(directory)
        val files = listOf(
            directory.resolve(PIPELINE_FILE) to pipelineJson(template) + "\n",
            directory.resolve(PROMPT_FILE) to template.promptScaffold + "\n",
        )
        files.firstOrNull { Files.exists(it.first) }?.let { (path, _) ->
            throw CliUsageException("Refusing to overwrite existing $path")
        }
        files.forEach { (path, content) -> Files.writeString(path, content) }
        return files.map { it.first }
    }

    /**
     * Runs `template list | show <id> | init <id> <directory>`.
     *
     * @param args Arguments after `template`.
     * @param out Destination for normal output.
     * @return Process exit code.
     */
    fun run(args: List<String>, out: (String) -> Unit = ::println): Int {
        when (args.firstOrNull()) {
            null, "list" -> {
                out("EvoMolior application templates:")
                all.forEach { out("  %-22s %s".format(it.id, it.summary)) }
                out("")
                out("Use: evomolior template init <id> <directory>, edit prompt.md, then run with")
                out("     -pipeline-config <directory>/pipeline.json -input <inputs> -output <outputs>")
            }
            "show" -> {
                val template = require(args.getOrNull(1) ?: throw CliUsageException(usage()))
                out("# $PIPELINE_FILE")
                out(pipelineJson(template))
                out("")
                out("# $PROMPT_FILE")
                out(template.promptScaffold)
            }
            "init" -> {
                val template = require(args.getOrNull(1) ?: throw CliUsageException(usage()))
                val directory = Path.of(args.getOrNull(2) ?: throw CliUsageException(usage()))
                val written = init(template, directory)
                written.forEach { out("Wrote $it") }
                out("")
                out("Next: edit ${directory.resolve(PROMPT_FILE)}, then run")
                out(
                    "  evomolior run -pipeline-config ${directory.resolve(PIPELINE_FILE)} " +
                        "-input <inputs> -output <outputs> [backend flags]"
                )
            }
            "-h", "--help", "help" -> out(usage())
            else -> throw CliUsageException(usage())
        }
        return 0
    }

    fun usage(): String = """
        evomolior template list
        evomolior template show <id>
        evomolior template init <id> <directory>
    """.trimIndent()
}
