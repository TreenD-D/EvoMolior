package com.achulkov.evomolior.cli

import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.api.service.FinalOutputContractValidator
import com.achulkov.evomolior.api.service.GeneratedOutputFile
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApplicationTemplatesTest {
    @Test
    fun `templates are unique and their pipeline json round-trips`() {
        val ids = ApplicationTemplates.all.map { it.id }
        assertEquals(ids.toSet().size, ids.size)
        ApplicationTemplates.all.forEach { template ->
            val decoded = cliJson.decodeFromString(
                CliPipelineConfig.serializer(),
                ApplicationTemplates.pipelineJson(template)
            )
            assertEquals(template.pipeline, decoded, template.id)
            val primary = requireNotNull(decoded.primary)
            assertEquals(ApplicationTemplates.PROMPT_FILE, primary.promptFile)
            assertTrue(!primary.fast && (primary.groupCount ?: 0) >= 2, template.id)
            assertTrue(requireNotNull(primary.finalOutput).validation != null, template.id)
        }
    }

    @Test
    fun `conforming deliverables pass each template contract and condensed ones fail`() {
        ApplicationTemplates.all.forEach { template ->
            val output = requireNotNull(template.pipeline.primary?.finalOutput)
            val spec = FinalOutputSpec(
                relativePath = output.relativePath,
                mediaType = output.mediaType,
                validation = output.validation
            )
            // The memo's 1,500-word ceiling needs shorter sections than the longer reports.
            val wordsPerSection = if (template.id == "decision-memo") 200 else 450
            val conforming = conformingDeliverable(template.id, wordsPerSection)
            val errors = FinalOutputContractValidator.validate(
                GeneratedOutputFile(output.relativePath, output.mediaType, conforming),
                spec
            )
            assertTrue(errors.isEmpty(), "${template.id}: $errors")

            if (output.validation?.minimumWordCount != null) {
                val condensed = conformingDeliverable(template.id, wordsPerSection = 5)
                val condensedErrors = FinalOutputContractValidator.validate(
                    GeneratedOutputFile(output.relativePath, output.mediaType, condensed),
                    spec
                )
                assertTrue(condensedErrors.any { it.contains("minimum") }, "${template.id}: $condensedErrors")
            }
        }
    }

    @Test
    fun `init writes pipeline and prompt and refuses to overwrite`() {
        val directory = createTempDirectory("application-template-").resolve("dossier")
        val template = ApplicationTemplates.require("research-dossier")

        val written = ApplicationTemplates.init(template, directory)

        assertEquals(
            listOf(ApplicationTemplates.PIPELINE_FILE, ApplicationTemplates.PROMPT_FILE),
            written.map { it.fileName.toString() }
        )
        assertTrue(Files.readString(directory.resolve(ApplicationTemplates.PROMPT_FILE)).contains("# Evidence rules"))
        assertFailsWith<CliUsageException> { ApplicationTemplates.init(template, directory) }
        assertFailsWith<CliUsageException> { ApplicationTemplates.require("unknown-template") }
    }

    @Test
    fun `template subcommand lists shows and initialises`() {
        val lines = mutableListOf<String>()
        assertEquals(0, ApplicationTemplates.run(listOf("list"), lines::add))
        ApplicationTemplates.all.forEach { template -> assertTrue(lines.any { it.contains(template.id) }) }

        lines.clear()
        assertEquals(0, ApplicationTemplates.run(listOf("show", "decision-memo"), lines::add))
        assertTrue(lines.any { it.contains("\"relativePath\": \"memo.md\"") }, lines.joinToString("\n"))

        val directory = createTempDirectory("application-template-cli-").resolve("memo")
        assertEquals(0, ApplicationTemplates.run(listOf("init", "decision-memo", directory.toString()), lines::add))
        assertTrue(Files.exists(directory.resolve(ApplicationTemplates.PIPELINE_FILE)))
        assertFailsWith<CliUsageException> { ApplicationTemplates.run(listOf("init", "decision-memo")) }
    }

    @Test
    fun `every initialised template loads through the run path into a valid run config`() {
        ApplicationTemplates.all.forEach { template ->
            val directory = createTempDirectory("template-run-${template.id}-")
            ApplicationTemplates.init(template, directory.resolve("config"))
            val input = Files.createDirectories(directory.resolve("input"))
            Files.writeString(input.resolve("notes.txt"), "Seed notes for ${template.id}.")
            var runtimeRequested = false

            val result = runCli(
                options = CliOptions(
                    prompt = "ignored in favour of the template prompt file",
                    inputDir = input,
                    outputDir = directory.resolve("output"),
                    openRouterApiKey = "sk-test",
                    modelOverrides = emptyMap(),
                    debug = false,
                    fast = false,
                    rounds = 1,
                    runId = "template-${template.id}",
                    pipelineConfigPath = directory.resolve("config/${ApplicationTemplates.PIPELINE_FILE}")
                ),
                // RunConfig (including the output contract) is built before the runtime; reaching
                // the factory proves the template is a valid run request. Stop before any provider.
                runtimeFactory = { _, _ ->
                    runtimeRequested = true
                    error("template smoke test stops before provider runtime")
                },
                storagePreflight = {}
            )

            assertTrue(runtimeRequested, template.id)
            assertTrue(!result.completedSuccessfully, template.id)
            // The stage prompt comes from the template's prompt.md, not the -prompt argument.
            val stagePrompt = Files.readString(directory.resolve("output/template-${template.id}/prompt.txt"))
            assertTrue(stagePrompt.startsWith(template.promptScaffold.lineSequence().first()), template.id)
        }
    }

    /** Builds a deliverable satisfying [templateId]'s contract with [wordsPerSection] filler words. */
    private fun conformingDeliverable(templateId: String, wordsPerSection: Int): String {
        val filler = (1..wordsPerSection).joinToString(" ") { "evidence$it" }
        fun markdown(headings: List<String>, extra: String = "") = buildString {
            headings.forEach { heading ->
                appendLine("## $heading")
                appendLine()
                appendLine(filler)
                appendLine()
            }
            append(extra)
        }
        return when (templateId) {
            "research-dossier" -> markdown(
                listOf(
                    "Executive summary", "Scope and method", "Inventory", "Evidence status",
                    "Gaps", "Recommendations", "Limitations", "References"
                ),
                extra = buildString {
                    appendLine("Discovery leads are not verified evidence.")
                    (1..15).forEach { appendLine("- https://source$it.example.org/docs (accessed 2026-10-01)") }
                }
            )
            "systematic-screening" -> markdown(
                listOf("Review question", "Eligibility criteria", "Included", "Excluded", "Evidence table", "Limitations")
            )
            "code-audit" -> markdown(
                listOf("Summary", "Findings", "Evidence", "Proposed changes", "Validation plan", "Risks and limitations")
            )
            "decision-memo" -> markdown(
                listOf("Recommendation", "Options considered", "Evidence", "Risks", "Next steps")
            )
            "evaluation-rubric" -> """
                {"dimensions":[
                  {"id":"d1","description":"$filler","weight":0.4,"levels":["low","high"]},
                  {"id":"d2","description":"Coverage","weight":0.3,"levels":["low","high"]},
                  {"id":"d3","description":"Traceability","weight":0.3,"levels":["low","high"]}],
                 "checks":[
                  {"id":"c1","description":"Has dates","pass_condition":"ISO date present"},
                  {"id":"c2","description":"Has URLs","pass_condition":"at least one URL"},
                  {"id":"c3","description":"Has owner","pass_condition":"owner named"},
                  {"id":"c4","description":"Has risks","pass_condition":"risks section"},
                  {"id":"c5","description":"Has scope","pass_condition":"scope stated"}],
                 "failure_modes":[
                  {"id":"f1","description":"Discovery-only sources","detection":"no access date"},
                  {"id":"f2","description":"Invisible URL characters","detection":"Cf code points"},
                  {"id":"f3","description":"Silent condensation","detection":"word floor"}],
                 "calibration_examples":[]}
            """.trimIndent()
            else -> error("No conforming fixture for $templateId")
        }
    }
}
