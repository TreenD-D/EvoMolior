package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.FinalOutputKind
import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.core.contract.GeneratedImageAsset
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EvoMoliorRunServiceFinalOutputImageGateTest {
    @Test
    fun `final html is rejected when generated image assets are omitted`() {
        val rejection = validate(
            output = GeneratedOutputFile(
                relativePath = "final-article.html",
                mediaType = "text/html",
                content = "<!doctype html><html><body><article><h1>Article</h1></article></body></html>"
            ),
            generatedImageAssets = listOf(testImageAsset())
        )

        assertTrue(rejection.orEmpty().contains("assets/images/hero.png"))
    }

    @Test
    fun `final html passes image gate when generated asset path is embedded in image tag`() {
        val rejection = validate(
            output = GeneratedOutputFile(
                relativePath = "final-article.html",
                mediaType = "text/html",
                content = """
                    <!doctype html>
                    <html><body><article><figure><img src="assets/images/hero.png" alt="Generated hero"></figure></article></body></html>
                """.trimIndent()
            ),
            generatedImageAssets = listOf(testImageAsset())
        )

        assertNull(rejection)
    }

    @Test
    fun `final deliverable writer prompt includes winning final round summary`() {
        val service = newService()
        val method = service::class.declaredFunctions.first { it.name == "buildFinalDeliverableWriterPrompt" }
            .apply { isAccessible = true }
        val prompt = method.call(
            service,
            RunConfig(
                runId = "run-final-writer-summary",
                objective = "Build the systematic review pack",
                groupCount = 1,
                groupProfiles = listOf(GroupProfileType.Library),
                rounds = 1,
                scoringDimensions = listOf(
                    ScoringDimension(
                        id = "correctness",
                        name = "Correctness",
                        description = "Correct candidate screening decisions.",
                        weight = 1.0
                    )
                ),
                openRouterApiKey = "test-key",
                finalOutputSpec = FinalOutputSpec(
                    relativePath = "systematic_review_pack.md",
                    mediaType = "text/markdown",
                    description = "Systematic review pack",
                    kind = FinalOutputKind.GENERIC_FILE
                ),
                isTestRun = true
            ),
            "group-1",
            3,
            FinalOutputSpec(
                relativePath = "systematic_review_pack.md",
                mediaType = "text/markdown",
                description = "Systematic review pack",
                kind = FinalOutputKind.GENERIC_FILE
            ),
            1,
            null,
            emptyList<GeneratedImageAsset>(),
            "Late accepted evidence: include corpus_id 7016 and 7017.",
            null
        ) as String

        assertTrue(prompt.contains("=== WINNING GROUP FINAL ROUND SUMMARY ==="))
        assertTrue(prompt.contains("include corpus_id 7016"))
    }

    @Test
    fun `final html is rejected when generated asset path is only mentioned as text`() {
        val rejection = validate(
            output = GeneratedOutputFile(
                relativePath = "final-article.html",
                mediaType = "text/html",
                content = """
                    <!doctype html>
                    <html><body><article><p>Use assets/images/hero.png as the hero image.</p></article></body></html>
                """.trimIndent()
            ),
            generatedImageAssets = listOf(testImageAsset())
        )

        assertTrue(rejection.orEmpty().contains("<img"))
        assertTrue(rejection.orEmpty().contains("assets/images/hero.png"))
    }

    @Test
    fun `final markdown is rejected when fenced corpus csv rows duplicate candidate ids`() {
        val rejection = validate(
            output = GeneratedOutputFile(
                relativePath = "systematic_review_pack.md",
                mediaType = "text/markdown",
                content = """
                    ### excluded_hard_negatives.csv
                    ```csv
                    corpus_id,pmid,title,primary_exclusion_reason,evidence
                    7014,37231074,"Tirzepatide versus insulin",Drug trial,"Abstract"
                    7014,37231074,"Tirzepatide duplicate",Duplicate,"Abstract"
                    ```
                """.trimIndent()
            ),
            generatedImageAssets = emptyList()
        )

        assertTrue(rejection.orEmpty().contains("duplicate corpus_id"))
        assertTrue(rejection.orEmpty().contains("7014"))
    }

    @Test
    fun `final markdown accepts unique fenced corpus csv rows`() {
        val rejection = validate(
            output = GeneratedOutputFile(
                relativePath = "systematic_review_pack.md",
                mediaType = "text/markdown",
                content = """
                    ### included_studies.csv
                    ```csv
                    corpus_id,pmid,title,inclusion_decision,reason
                    7016,37519462,"ENGAGE-2",included,"Meets criteria"
                    7017,34472895,"eHealth program",included,"Meets criteria"
                    ```
                """.trimIndent()
            ),
            generatedImageAssets = emptyList()
        )

        assertNull(rejection)
    }

    @Test
    fun `final markdown accepts same candidate id in separate required csv tables`() {
        val rejection = validate(
            output = GeneratedOutputFile(
                relativePath = "systematic_review_pack.md",
                mediaType = "text/markdown",
                content = """
                    ### included_studies.csv
                    ```csv
                    corpus_id,pmid,title,inclusion_decision,reason
                    7016,37519462,"ENGAGE-2",included,"Meets criteria"
                    ```

                    ### evidence_table.csv
                    ```csv
                    corpus_id,population,intervention,comparator,outcome,design,decision
                    7016,"Adults","Intervention","Control","Weight","RCT",include
                    ```
                """.trimIndent()
            ),
            generatedImageAssets = emptyList()
        )

        assertNull(rejection)
    }

    @Test
    fun `final markdown accepts same candidate id across repeated csv headers in one fence`() {
        val rejection = validate(
            output = GeneratedOutputFile(
                relativePath = "systematic_review_pack.md",
                mediaType = "text/markdown",
                content = """
                    ```csv
                    corpus_id,pmid,title,inclusion_decision,reason
                    7016,37519462,"ENGAGE-2",included,"Meets criteria"
                    corpus_id,population,intervention,comparator,outcome,design,decision
                    7016,"Adults","Intervention","Control","Weight","RCT",include
                    ```
                """.trimIndent()
            ),
            generatedImageAssets = emptyList()
        )

        assertNull(rejection)
    }

    @Test
    fun `final markdown accepts multiple criterion rows for the same excluded candidate`() {
        val rejection = validate(
            output = GeneratedOutputFile(
                relativePath = "systematic_review_pack.md",
                mediaType = "text/markdown",
                content = """
                    ### included_studies.csv
                    ```csv
                    corpus_id,pmid,title,inclusion_decision,reason,effect_direction,mental_health_result,weight_result,evidence
                    4565,22146094,"VLCD",included,"Meets criteria",Null,NA,weight loss,"Candidate evidence"
                    ```

                    ### excluded_hard_negatives.csv
                    ```csv
                    corpus_id,pmid,title,primary_exclusion_reason,evidence
                    4567,123,"Wrong comparator",Comparator,"No direct T2DM vs non-T2DM weight comparison"
                    ```

                    ### exclusion_reasons.csv
                    ```csv
                    corpus_id,criterion_failed,reason
                    4567,comparator,"No direct T2DM vs non-T2DM comparison"
                    4567,outcome,"Outcome cannot answer the review question"
                    ```

                    ### evidence_table.csv
                    ```csv
                    corpus_id,population,intervention,comparator,outcome,design,decision
                    4565,"Adults","VLCD","T2DM vs non-T2DM","Weight","Comparative",include
                    4567,"Adults","VLCD","No eligible comparator","Weight","Non-comparative",exclude
                    ```
                """.trimIndent()
            ),
            generatedImageAssets = emptyList()
        )

        assertNull(rejection)
    }

    @Test
    fun `final markdown rejects systematic review evidence table omissions when candidate universe is inferable`() {
        val rejection = validate(
            output = GeneratedOutputFile(
                relativePath = "systematic_review_pack.md",
                mediaType = "text/markdown",
                content = """
                    ### included_studies.csv
                    ```csv
                    corpus_id,pmid,title,inclusion_decision,reason
                    4565,22146094,"VLCD",included,"Meets criteria"
                    ```

                    ### excluded_hard_negatives.csv
                    ```csv
                    corpus_id,pmid,title,primary_exclusion_reason,evidence
                    4566,123,"Wrong outcome",Outcome,"No relevant outcome"
                    ```

                    ### exclusion_reasons.csv
                    ```csv
                    corpus_id,reason,evidence
                    4566,Wrong outcome,"No relevant outcome"
                    4568,Wrong design,"Observational"
                    4570,Wrong comparator,"No eligible comparator"
                    ```

                    ### evidence_table.csv
                    ```csv
                    corpus_id,population,intervention,comparator,outcome,design,decision
                    4565,"Adults","VLCD","Control","Weight","RCT",include
                    4566,"Adults","Diet","Control","Other","RCT",exclude
                    ```
                """.trimIndent()
            ),
            generatedImageAssets = emptyList()
        )

        assertTrue(rejection.orEmpty().contains("evidence_table.csv omits corpus_id"))
        assertTrue(rejection.orEmpty().contains("4568"))
        assertTrue(rejection.orEmpty().contains("4570"))
    }

    @Test
    fun `final markdown rejects systematic review header-only csv sections`() {
        val rejection = validate(
            output = GeneratedOutputFile(
                relativePath = "systematic_review_pack.md",
                mediaType = "text/markdown",
                content = """
                    ### included_studies.csv
                    ```csv
                    corpus_id,pmid,title,inclusion_decision,reason
                    ```

                    ### excluded_hard_negatives.csv
                    ```csv
                    corpus_id,pmid,title,primary_exclusion_reason,evidence
                    ```

                    ### evidence_table.csv
                    ```csv
                    corpus_id,population,intervention,comparator,outcome,design,decision
                    ```
                """.trimIndent()
            ),
            generatedImageAssets = emptyList()
        )

        assertTrue(rejection.orEmpty().contains("no corpus_id row"))
    }

    @Test
    fun `final markdown rejects systematic review included excluded coverage omissions`() {
        val rejection = validate(
            output = GeneratedOutputFile(
                relativePath = "systematic_review_pack.md",
                mediaType = "text/markdown",
                content = """
                    ### included_studies.csv
                    ```csv
                    corpus_id,pmid,title,inclusion_decision,reason
                    4565,22146094,"VLCD",included,"Meets criteria"
                    ```

                    ### excluded_hard_negatives.csv
                    ```csv
                    corpus_id,pmid,title,primary_exclusion_reason,evidence
                    4566,123,"Wrong outcome",Outcome,"No relevant outcome"
                    ```

                    ### exclusion_reasons.csv
                    ```csv
                    corpus_id,reason,evidence
                    4566,Wrong outcome,"No relevant outcome"
                    4568,Wrong design,"Observational"
                    4570,Wrong comparator,"No eligible comparator"
                    ```
                """.trimIndent()
            ),
            generatedImageAssets = emptyList()
        )

        assertTrue(rejection.orEmpty().contains("included_studies.csv/excluded_hard_negatives.csv coverage omits corpus_id"))
        assertTrue(rejection.orEmpty().contains("4568"))
        assertTrue(rejection.orEmpty().contains("4570"))
    }

    @Test
    fun `final markdown rejects systematic review decision omissions when one decision table is absent`() {
        val rejection = validate(
            output = GeneratedOutputFile(
                relativePath = "systematic_review_pack.md",
                mediaType = "text/markdown",
                content = """
                    ### included_studies.csv
                    ```csv
                    corpus_id,pmid,title,inclusion_decision,reason
                    4565,22146094,"VLCD",included,"Meets criteria"
                    ```

                    ### exclusion_reasons.csv
                    ```csv
                    corpus_id,reason,evidence
                    4566,Wrong outcome,"No relevant outcome"
                    4568,Wrong design,"Observational"
                    4570,Wrong comparator,"No eligible comparator"
                    ```
                """.trimIndent()
            ),
            generatedImageAssets = emptyList()
        )

        assertTrue(rejection.orEmpty().contains("included_studies.csv/excluded_hard_negatives.csv coverage omits corpus_id"))
        assertTrue(rejection.orEmpty().contains("4566"))
        assertTrue(rejection.orEmpty().contains("4568"))
        assertTrue(rejection.orEmpty().contains("4570"))
    }

    @Test
    fun `final markdown accepts complete systematic review corpus coverage`() {
        val rejection = validate(
            output = GeneratedOutputFile(
                relativePath = "systematic_review_pack.md",
                mediaType = "text/markdown",
                content = """
                    ### included_studies.csv
                    ```csv
                    corpus_id,pmid,title,inclusion_decision,reason
                    4565,22146094,"VLCD",included,"Meets criteria"
                    ```

                    ### excluded_hard_negatives.csv
                    ```csv
                    corpus_id,pmid,title,primary_exclusion_reason,evidence
                    4566,123,"Wrong outcome",Outcome,"No relevant outcome"
                    4568,456,"Wrong design",Design,"Observational"
                    4570,789,"Wrong comparator",Comparator,"No eligible comparator"
                    ```

                    ### exclusion_reasons.csv
                    ```csv
                    corpus_id,reason,evidence
                    4566,Wrong outcome,"No relevant outcome"
                    4568,Wrong design,"Observational"
                    4570,Wrong comparator,"No eligible comparator"
                    ```

                    ### evidence_table.csv
                    ```csv
                    corpus_id,population,intervention,comparator,outcome,design,decision
                    4565,"Adults","VLCD","Control","Weight","RCT",include
                    4566,"Adults","Diet","Control","Other","RCT",exclude
                    4568,"Adults","Diet","Control","Weight","Observational",exclude
                    4570,"Adults","Diet","None","Weight","RCT",exclude
                    ```
                """.trimIndent()
            ),
            generatedImageAssets = emptyList()
        )

        assertNull(rejection)
    }

    @Test
    fun `final markdown rejects latest task24 archived incomplete artifact when available`() {
        val artifact = File(
            "results/tier4-real-metasyn-task24-two-complexity-20260704-complex/" +
                "tier4-real-metasyn-task24-complex-complexity-20260704/outputs/systematic_review_pack.md"
        )
        if (!artifact.isFile) return

        val rejection = validate(
            output = GeneratedOutputFile(
                relativePath = "systematic_review_pack.md",
                mediaType = "text/markdown",
                content = artifact.readText()
            ),
            generatedImageAssets = emptyList()
        )

        assertTrue(rejection.orEmpty().contains("omits corpus_id"))
        assertTrue(rejection.orEmpty().contains("4568"))
        assertTrue(rejection.orEmpty().contains("4570"))
    }

    private fun validate(
        output: GeneratedOutputFile,
        generatedImageAssets: List<GeneratedImageAsset>
    ): String? {
        val service = newService()
        val method = service::class.declaredFunctions.first { it.name == "validateFinalOutputAgainstObjective" }
            .apply { isAccessible = true }
        return method.call(
            service,
            output,
            FinalOutputSpec(
                relativePath = output.relativePath,
                mediaType = output.mediaType
            ),
            "Write a concise visual article.",
            generatedImageAssets
        ) as String?
    }

    private fun newService(): EvoMoliorRunService =
        EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in this final-output gate test")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = SubstrateDatabaseFactory(createTempDirectory("svc-final-output-image-gate-").toFile()),
            metaPlannerRunner = MetaPlannerRunner()
        )

    private fun testImageAsset(): GeneratedImageAsset = GeneratedImageAsset(
        relativePath = "assets/images/hero.png",
        mediaType = "image/png",
        width = 512,
        height = 512,
        sizeBytes = 4096,
        prompt = "Hero image",
        model = "qwen-image-2.0-pro",
        reviewStatus = "verified"
    )
}
