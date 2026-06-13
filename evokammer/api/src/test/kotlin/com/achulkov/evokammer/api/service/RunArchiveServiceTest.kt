package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.api.model.FinalOutputSpec
import com.achulkov.evokammer.api.model.FinalOutputKind
import com.achulkov.evokammer.core.contract.RunInputArtifact
import com.achulkov.evokammer.core.contract.RunInputArtifactKind
import com.achulkov.evokammer.core.contract.StartingField
import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.core.model.ScoringDimension
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import com.achulkov.evokammer.substrate.db.entity.MetaArchiveEntryEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RunArchiveServiceTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `buildResultsBundle includes starting field uploaded inputs and meta archive outputs`() = runTest {
        val rootDir = createTempDirectory("run-archive-test-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-archive-test"
        val groupId = "group-0"

        dbFactory.get(runId, groupId)
        val metaDb = dbFactory.get(runId, "__meta_archive__")

        val artifactDir = File(rootDir, "evokammer/runs/$runId/artifacts/docs/specs")
        artifactDir.mkdirs()
        val inputFile = File(artifactDir, "brief.txt")
        inputFile.writeText("hello archive")

        val artifact = RunInputArtifact(
            relativePath = "docs/specs/brief.txt",
            sourceName = "brief.txt",
            mimeType = "text/plain",
            sizeBytes = inputFile.length(),
            sha256 = sha256Hex(inputFile.readBytes()),
            kind = RunInputArtifactKind.TEXT,
            textPreview = "hello archive"
        )

        val startingField = StartingField.default("archive the run", runId = runId).copy(
            inputArtifacts = listOf(artifact)
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField — $runId",
                contentJson = json.encodeToString(StartingField.serializer(), startingField),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                entryType = RunArchiveService.ENTRY_DEAD_ENDS,
                groupId = groupId,
                label = "Dead Ends — $groupId",
                contentJson = """[{"approach":"wrong","reason":"failed","domain":"test"}]""",
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                entryType = RunArchiveService.ENTRY_ROUND_SUMMARY,
                groupId = groupId,
                label = "Round summary — $groupId",
                contentJson = """{"groupId":"$groupId","roundIndex":0,"summary":"hello"}""",
                runObjective = startingField.objective,
                finalScore = 88.0,
                isFromWinner = true,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "run-completed-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run Completed — $runId",
                contentJson = json.encodeToString(
                    RunCompletionRecord.serializer(),
                    RunCompletionRecord(
                        runId = runId,
                        winnerId = groupId,
                        finalAnswerSummary = "Round 1 final answer: winner=$groupId",
                        finalDeliverableHtml = """
                            <!doctype html>
                            <html lang="en">
                            <head><meta charset="utf-8"><title>MindVortex</title></head>
                            <body><article><h1>MindVortex</h1><p>hello archive article</p></article></body>
                            </html>
                        """.trimIndent(),
                        judgeOutputsByGrader = mapOf("grader-0" to "Judge output"),
                        submissionSummariesByGroup = mapOf(groupId to "<article>hello</article>")
                    )
                ),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )

        val bundle = service.buildResultsBundle(runId)
        assertNotNull(bundle)

        val entries = unzipEntries(bundle)
        assertTrue(entries.keys.contains("manifest.json"))
        assertTrue(entries.keys.contains("input-artifacts.json"))
        assertTrue(entries.keys.contains("starting-field.json"))
        assertTrue(entries.keys.contains("inputs/docs/specs/brief.txt"))
        assertTrue(entries.keys.contains("outputs/final-article.html"))
        assertTrue(entries.keys.any { it.startsWith("outputs/dead_ends_export") })
        assertTrue(entries.keys.any { it.startsWith("outputs/round_summary") })
        assertTrue(entries["manifest.json"]!!.contains("\"artifactCount\":1"))
        assertTrue(entries["input-artifacts.json"]!!.contains("\"docs/specs/brief.txt\""))
        assertTrue(entries["starting-field.json"]!!.contains("\"inputArtifacts\""))
        assertTrue(entries["outputs/final-article.html"]!!.contains("<!doctype html>"))
        assertTrue(entries["outputs/final-article.html"]!!.contains("<h1>MindVortex</h1>"))
        assertTrue(entries["outputs/final-article.html"]!!.contains("hello archive article"))
        assertEquals("hello archive", entries["inputs/docs/specs/brief.txt"])
    }

    @Test
    fun `buildResultsBundle extracts embedded final deliverable html from submission summary`() = runTest {
        val rootDir = createTempDirectory("run-archive-summary-html-test-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-archive-summary-html"
        val groupId = "group-0"
        val metaDb = dbFactory.get(runId, "__meta_archive__")

        val startingField = StartingField.default("archive the run", runId = runId)
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField — $runId",
                contentJson = json.encodeToString(StartingField.serializer(), startingField),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "run-completed-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run Completed — $runId",
                contentJson = json.encodeToString(
                    RunCompletionRecord.serializer(),
                    RunCompletionRecord(
                        runId = runId,
                        winnerId = groupId,
                        finalAnswerSummary = "Round 1 final answer: winner=$groupId",
                        submissionSummariesByGroup = mapOf(
                            groupId to """
                                ROUND SUBMISSION SUMMARY
                                - Completed article structure.
                                
                                FINAL DELIVERABLE HTML
                                <!doctype html>
                                <html lang="en">
                                <head><meta charset="utf-8"><title>Embedded summary article</title></head>
                                <body><article><h1>Embedded summary article</h1><p>Publication-ready.</p></article></body>
                                </html>
                            """.trimIndent()
                        )
                    )
                ),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )

        val bundle = service.buildResultsBundle(runId)
        assertNotNull(bundle)

        val entries = unzipEntries(bundle)
        assertTrue(entries["outputs/final-article.html"]!!.contains("Embedded summary article"))
        assertTrue(entries["outputs/final-article.html"]!!.contains("Publication-ready."))
    }

    @Test
    fun `buildResultsBundle falls back from winner summary to another summary with embedded html`() = runTest {
        val rootDir = createTempDirectory("run-archive-summary-fallback-test-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-archive-summary-fallback"
        val winnerId = "group-0"
        val otherGroupId = "group-1"
        val metaDb = dbFactory.get(runId, "__meta_archive__")

        val startingField = StartingField.default("archive the run", runId = runId).copy(
            groupProfiles = mapOf(winnerId to com.achulkov.evokammer.core.model.GroupProfileType.Library, otherGroupId to com.achulkov.evokammer.core.model.GroupProfileType.Library),
            ringTopology = listOf(winnerId, otherGroupId)
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField — $runId",
                contentJson = json.encodeToString(StartingField.serializer(), startingField),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "run-completed-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run Completed — $runId",
                contentJson = json.encodeToString(
                    RunCompletionRecord.serializer(),
                    RunCompletionRecord(
                        runId = runId,
                        winnerId = winnerId,
                        finalAnswerSummary = "Round 1 final answer: winner=$winnerId",
                        submissionSummariesByGroup = mapOf(
                            winnerId to "ROUND SUBMISSION SUMMARY\n- Winner summary without embedded html.",
                            otherGroupId to """
                                ROUND SUBMISSION SUMMARY
                                - Non-winner summary carries the final deliverable.

                                FINAL DELIVERABLE HTML
                                <!doctype html>
                                <html lang="en">
                                <head><meta charset="utf-8"><title>Fallback article</title></head>
                                <body><article><h1>Fallback article</h1><p>Chosen from the first available embedded html block.</p></article></body>
                                </html>
                            """.trimIndent()
                        )
                    )
                ),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )

        val bundle = service.buildResultsBundle(runId)
        assertNotNull(bundle)

        val entries = unzipEntries(bundle)
        assertTrue(entries["outputs/final-article.html"]!!.contains("Fallback article"))
        assertTrue(entries["outputs/final-article.html"]!!.contains("Chosen from the first available embedded html block."))
    }

    @Test
    fun `buildResultsBundle includes generated json output files when present`() = runTest {
        val rootDir = createTempDirectory("run-archive-generated-output-test-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-archive-generated-output"
        val metaDb = dbFactory.get(runId, "__meta_archive__")

        val startingField = StartingField.default("archive the run", runId = runId)
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField — $runId",
                contentJson = json.encodeToString(StartingField.serializer(), startingField),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "run-completed-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run Completed — $runId",
                contentJson = json.encodeToString(
                    RunCompletionRecord.serializer(),
                    RunCompletionRecord(
                        runId = runId,
                        winnerId = "group-0",
                        generatedOutputFiles = listOf(
                            GeneratedOutputFile(
                                relativePath = "briefs/summary.json",
                                mediaType = "application/json",
                                content = """{"headline":"MindVortex","lang":"de"}"""
                            )
                        )
                    )
                ),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )

        val bundle = service.buildResultsBundle(runId)
        assertNotNull(bundle)

        val entries = unzipEntries(bundle)
        assertEquals("""{"headline":"MindVortex","lang":"de"}""", entries["outputs/briefs/summary.json"])
        assertFalse(entries.keys.contains("outputs/final-article.html"))
    }

    @Test
    fun `buildResultsBundle includes patch bundle output with structured change set`() = runTest {
        val rootDir = createTempDirectory("run-archive-patch-bundle-test-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-archive-patch-bundle"
        val metaDb = dbFactory.get(runId, "__meta_archive__")

        val startingField = StartingField.default("archive the run", runId = runId)
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField — $runId",
                contentJson = json.encodeToString(StartingField.serializer(), startingField),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "run-completed-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run Completed — $runId",
                contentJson = json.encodeToString(
                    RunCompletionRecord.serializer(),
                    RunCompletionRecord(
                        runId = runId,
                        winnerId = "group-0",
                        requestedOutputSpec = FinalOutputSpec(
                            relativePath = "self-improvement-patch-bundle.json",
                            mediaType = "application/json",
                            description = "Machine-readable patch bundle with edited files, diffs, and documentation.",
                            kind = FinalOutputKind.PATCH_BUNDLE
                        ),
                        generatedOutputFiles = listOf(
                            GeneratedOutputFile(
                                relativePath = "self-improvement-patch-bundle.json",
                                mediaType = "application/json",
                                content = """
                                    {
                                      "summary": "Update Example.kt",
                                      "changedFiles": [
                                        {
                                          "path": "Example.kt",
                                          "status": "modified",
                                          "diff": "--- a/Example.kt\n+++ b/Example.kt\n@@\n-fun message(): String = \"original\"\n+fun message(): String = \"updated\"",
                                          "rationale": "The message function must return the updated string."
                                        }
                                      ],
                                      "documentation": "Example.kt now returns the updated message.",
                                      "verification": [
                                        "Compile Example.kt",
                                        "Invoke message() and confirm updated output"
                                      ],
                                      "notes": "Rollback restores the original return value."
                                    }
                                """.trimIndent()
                            )
                        )
                    )
                ),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )

        val bundle = service.buildResultsBundle(runId)
        assertNotNull(bundle)

        val entries = unzipEntries(bundle)
        val patchBundle = entries["outputs/self-improvement-patch-bundle.json"]
        assertNotNull(patchBundle)

        val patchJson = json.parseToJsonElement(patchBundle).jsonObject
        assertEquals("Update Example.kt", patchJson["summary"]?.jsonPrimitive?.content)
        val changedFiles = patchJson["changedFiles"]?.jsonArray
        assertNotNull(changedFiles)
        assertEquals("Example.kt", changedFiles.first().jsonObject["path"]?.jsonPrimitive?.content)
        assertEquals("modified", changedFiles.first().jsonObject["status"]?.jsonPrimitive?.content)
        assertTrue(patchJson["documentation"]?.jsonPrimitive?.content.orEmpty().contains("Example.kt"))
        assertTrue(patchJson["verification"]?.jsonArray?.isNotEmpty() == true)
        assertTrue(patchJson["notes"]?.jsonPrimitive?.content.orEmpty().contains("Rollback"))
    }

    @Test
    fun `buildResultsBundle recovers configured json output from archived round summary when final writer failed`() = runTest {
        val rootDir = createTempDirectory("run-archive-summary-json-recovery-test-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-archive-summary-json-recovery"
        val winnerId = "group-0"
        val otherGroupId = "group-1"
        val metaDb = dbFactory.get(runId, "__meta_archive__")

        PersistedRunStateStore.writeForDataRoot(
            rootDir,
            PersistedRunState(
                runId = runId,
                objective = "Produce a strict JSON evidence brief",
                groupCount = 2,
                groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Library),
                rounds = 3,
                roundDurationMinutes = 1,
                maxAgentsPerGroup = 2,
                scoringDimensions = listOf(
                    ScoringDimension(
                        id = "evidence_fidelity",
                        name = "Evidence Fidelity",
                        description = "Reward grounded evidence synthesis.",
                        weight = 1.0
                    )
                ),
                finalOutputSpec = FinalOutputSpec(
                    relativePath = "briefs/evidence-brief.json",
                    mediaType = "application/json",
                    description = "Machine-readable evidence brief"
                ),
                isTestRun = true,
                budgetOverride = 50.0,
                groupIds = listOf(winnerId, otherGroupId),
                graderGroupIds = emptyList(),
                status = PersistedRunStatus.COMPLETED,
                nextRoundIndex = 3
            )
        )

        val startingField = StartingField.default("archive the run", runId = runId).copy(
            groupProfiles = mapOf(
                winnerId to GroupProfileType.Library,
                otherGroupId to GroupProfileType.Library
            ),
            ringTopology = listOf(winnerId, otherGroupId)
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField — $runId",
                contentJson = json.encodeToString(StartingField.serializer(), startingField),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                entryType = RunArchiveService.ENTRY_ROUND_SUMMARY,
                groupId = null,
                label = "Round summary — $runId",
                contentJson = """
                    {
                      "runId":"$runId",
                      "roundIndex":1,
                      "roundWinnerId":"$winnerId",
                      "judgeOutputsByGrader":{},
                      "groupSummaries":[
                        {
                          "groupId":"$winnerId",
                          "criticPromoted":1,
                          "criticRejected":0,
                          "criticArbitrated":0,
                          "fitnessScoredAgents":0,
                          "fitnessTopEligibleAgents":0,
                          "legacyProtocolsExtracted":0,
                          "legacyProtocolsExpired":0,
                          "deadEndsInserted":0,
                          "submissionSummary":"ROUND SUBMISSION SUMMARY\n\nEVIDENCE BRIEF JSON\n```json\n{\"title\":\"Recovered brief\",\"generatedOn\":\"2026-06-05\",\"audience\":\"General public\",\"keyTakeaways\":[\"Recovered from archived round summary\"],\"medicationEvidence\":\"Recovered medication evidence\",\"nonPharmacologicalEvidence\":\"Recovered non-pharmacological evidence\",\"ageGroupNuances\":\"Recovered age nuance\",\"limitations\":[\"Recovered from persisted archive after final writer failure\"],\"sources\":[]}\n```",
                          "deterministicScore":{"overallScore":0.0,"automatedTestsPassed":0,"automatedTestsFailed":0,"mandatoryFailure":false,"gradingSheetApplied":"none","failedTestIds":[],"dimensionScores":{}},
                          "disclosureCheckin":{"overallScore":0.0,"passed":false,"testPassRate":0.0,"failedTestCount":0,"gradingSheetApplied":"none","stageAdvanced":false,"dimensionScores":{}},
                          "blindScore":{"weightedTotal":0.0,"rank":1,"dimensionScores":{}}
                        },
                        {
                          "groupId":"$otherGroupId",
                          "criticPromoted":0,
                          "criticRejected":0,
                          "criticArbitrated":0,
                          "fitnessScoredAgents":0,
                          "fitnessTopEligibleAgents":0,
                          "legacyProtocolsExtracted":0,
                          "legacyProtocolsExpired":0,
                          "deadEndsInserted":0,
                          "submissionSummary":"ROUND SUBMISSION SUMMARY\n- No valid final output",
                          "deterministicScore":{"overallScore":0.0,"automatedTestsPassed":0,"automatedTestsFailed":0,"mandatoryFailure":false,"gradingSheetApplied":"none","failedTestIds":[],"dimensionScores":{}},
                          "disclosureCheckin":{"overallScore":0.0,"passed":false,"testPassRate":0.0,"failedTestCount":0,"gradingSheetApplied":"none","stageAdvanced":false,"dimensionScores":{}},
                          "blindScore":{"weightedTotal":0.0,"rank":2,"dimensionScores":{}}
                        }
                      ],
                      "generatedAtMs":123
                    }
                """.trimIndent(),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = true,
                generation = 2,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "run-completed-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run Completed — $runId",
                contentJson = json.encodeToString(
                    RunCompletionRecord.serializer(),
                    RunCompletionRecord(
                        runId = runId,
                        winnerId = winnerId,
                        finalAnswerSummary = "Round 2 final answer: winner=$winnerId"
                    )
                ),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 2,
                archivedAtMs = System.currentTimeMillis()
            )
        )

        val bundle = service.buildResultsBundle(runId)
        assertNotNull(bundle)

        val entries = unzipEntries(bundle)
        assertEquals(
            """{"title":"Recovered brief","generatedOn":"2026-06-05","audience":"General public","keyTakeaways":["Recovered from archived round summary"],"medicationEvidence":"Recovered medication evidence","nonPharmacologicalEvidence":"Recovered non-pharmacological evidence","ageGroupNuances":"Recovered age nuance","limitations":["Recovered from persisted archive after final writer failure"],"sources":[]}""",
            entries["outputs/briefs/evidence-brief.json"]
        )
        assertFalse(entries.keys.contains("outputs/final-article.html"))

        service.archiveRun(
            runId = runId,
            groupIds = listOf(winnerId, otherGroupId),
            winnerId = winnerId,
            objective = startingField.objective,
            startingFieldJson = json.encodeToString(StartingField.serializer(), startingField),
            finalGeneration = 2,
            completionRecord = RunCompletionRecord(
                runId = runId,
                winnerId = winnerId,
                finalAnswerSummary = "Round 2 final answer: winner=$winnerId"
            )
        )

        val recoveredOutputFile = File(rootDir, "evokammer/runs/$runId/results/briefs/evidence-brief.json")
        assertTrue(recoveredOutputFile.exists())
        assertEquals(
            """{"title":"Recovered brief","generatedOn":"2026-06-05","audience":"General public","keyTakeaways":["Recovered from archived round summary"],"medicationEvidence":"Recovered medication evidence","nonPharmacologicalEvidence":"Recovered non-pharmacological evidence","ageGroupNuances":"Recovered age nuance","limitations":["Recovered from persisted archive after final writer failure"],"sources":[]}""",
            recoveredOutputFile.readText()
        )
        assertFalse(File(rootDir, "evokammer/runs/$runId/results/final-article.html").exists())
    }

    @Test
    fun `buildResultsBundle can recover configured json output without persisted state`() = runTest {
        val rootDir = createTempDirectory("run-archive-explicit-output-spec-test-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-archive-explicit-output-spec"
        val winnerId = "group-0"
        val metaDb = dbFactory.get(runId, "__meta_archive__")

        val startingField = StartingField.default("archive the run", runId = runId)
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField — $runId",
                contentJson = json.encodeToString(StartingField.serializer(), startingField),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "run-completed-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run Completed — $runId",
                contentJson = json.encodeToString(
                    RunCompletionRecord.serializer(),
                    RunCompletionRecord(
                        runId = runId,
                        winnerId = winnerId,
                        finalAnswerSummary = "Round 2 final answer: winner=$winnerId"
                    )
                ),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 2,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                entryType = RunArchiveService.ENTRY_ROUND_SUMMARY,
                groupId = null,
                label = "Round summary — $runId",
                contentJson = """
                    {
                      "runId":"$runId",
                      "roundIndex":1,
                      "roundWinnerId":"$winnerId",
                      "groupSummaries":[
                        {
                          "groupId":"$winnerId",
                          "submissionSummary":"ROUND SUBMISSION SUMMARY\n\nBRIEF JSON\n```json\n{\"headline\":\"Recovered brief\",\"lang\":\"en\"}\n```"
                        }
                      ],
                      "generatedAtMs":123
                    }
                """.trimIndent(),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = true,
                generation = 2,
                archivedAtMs = System.currentTimeMillis()
            )
        )

        val bundle = service.buildResultsBundle(
            runId = runId,
            requestedOutputSpec = FinalOutputSpec(
                relativePath = "briefs/evidence-brief.json",
                mediaType = "application/json",
                description = "Machine-readable evidence brief"
            )
        )
        assertNotNull(bundle)

        val entries = unzipEntries(bundle)
        assertEquals("""{"headline":"Recovered brief","lang":"en"}""", entries["outputs/briefs/evidence-brief.json"])
        assertFalse(entries.keys.contains("outputs/final-article.html"))
    }

    @Test
    fun `validateGeneratedOutputFile rejects placeholder article references without traceable identifiers`() {
        val badHtml = GeneratedOutputFile(
            relativePath = "final-article.html",
            mediaType = "text/html",
            content = """
                <!doctype html>
                <html lang="en">
                <body>
                  <article>
                    <h2>References</h2>
                    <ol>
                      <li>Smith J, et al. Invented ADHD trial results.</li>
                    </ol>
                  </article>
                </body>
                </html>
            """.trimIndent()
        )

        assertFailsWith<IllegalStateException> {
            validateGeneratedOutputFile(badHtml)
        }
    }

    @Test
    fun `validateGeneratedOutputFile rejects invalid json source metadata`() {
        val badJson = GeneratedOutputFile(
            relativePath = "briefs/evidence-brief.json",
            mediaType = "application/json",
            content = """{"sources":[{"title":"Invented source","doi":"not-a-doi","url":"https://example.invalid"}]}"""
        )

        assertFailsWith<IllegalStateException> {
            validateGeneratedOutputFile(badJson)
        }
    }

    @Test
    fun `buildResultsBundle rejects article references that do not match the verified source catalog`() = runTest {
        val rootDir = createTempDirectory("run-archive-source-catalog-test-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-archive-source-catalog"
        val metaDb = dbFactory.get(runId, "__meta_archive__")

        val artifactsDir = File(rootDir, "evokammer/runs/$runId/artifacts")
        artifactsDir.mkdirs()
        artifactsDir.resolve("current_sources.md").writeText(
            """
            Current-source anchor notes for the live ADHD article pipeline run

            1. Pharmacological interventions for ADHD: a systematic review and dose-effect network meta-analysis
            - Source: PubMed
            - URL: https://pubmed.ncbi.nlm.nih.gov/42134365/
            - Publication: 2025
            - Notes: large pharmacology synthesis spanning stimulant and non-stimulant monotherapy across ages 5+.
            """.trimIndent()
        )

        val startingField = StartingField.default("archive the run", runId = runId)
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField — $runId",
                contentJson = json.encodeToString(StartingField.serializer(), startingField),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "run-completed-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run Completed — $runId",
                contentJson = json.encodeToString(
                    RunCompletionRecord.serializer(),
                    RunCompletionRecord(
                        runId = runId,
                        winnerId = "group-0",
                        finalDeliverableHtml = """
                            <!doctype html>
                            <html lang="en">
                            <body>
                              <article>
                                <h2>References</h2>
                                <ol>
                                  <li>Faraone SV, et al. Pharmacological interventions for ADHD: a systematic review and dose-effect network meta-analysis. <em>PubMed</em>. 2024.</li>
                                </ol>
                              </article>
                            </body>
                            </html>
                        """.trimIndent()
                    )
                ),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )

        assertFailsWith<IllegalStateException> {
            service.buildResultsBundle(runId)
        }
    }

    @Test
    fun `buildResultsBundle rejects json evidence brief sources that do not match the verified source catalog`() = runTest {
        val rootDir = createTempDirectory("run-archive-source-catalog-json-test-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-archive-source-catalog-json"
        val metaDb = dbFactory.get(runId, "__meta_archive__")

        val artifactsDir = File(rootDir, "evokammer/runs/$runId/artifacts")
        artifactsDir.mkdirs()
        artifactsDir.resolve("current_sources.md").writeText(
            """
            Current-source anchor notes for the live ADHD article pipeline run

            1. Pharmacological interventions for ADHD: a systematic review and dose-effect network meta-analysis
            - Source: PubMed
            - URL: https://pubmed.ncbi.nlm.nih.gov/42134365/
            - Publication: 2025
            - Notes: large pharmacology synthesis spanning stimulant and non-stimulant monotherapy across ages 5+.
            """.trimIndent()
        )

        val startingField = StartingField.default("archive the run", runId = runId)
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField — $runId",
                contentJson = json.encodeToString(StartingField.serializer(), startingField),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "run-completed-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run Completed — $runId",
                contentJson = json.encodeToString(
                    RunCompletionRecord.serializer(),
                    RunCompletionRecord(
                        runId = runId,
                        winnerId = "group-0",
                        generatedOutputFiles = listOf(
                            GeneratedOutputFile(
                                relativePath = "briefs/evidence-brief.json",
                                mediaType = "application/json",
                                content = """{"title":"Wrong title","publicationDate":"2024","sources":[{"title":"Pharmacological interventions for ADHD: a systematic review and dose-effect network meta-analysis","publicationDate":"2024","url":"https://pubmed.ncbi.nlm.nih.gov/42134365/"}]}"""
                            )
                        )
                    )
                ),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )

        assertFailsWith<IllegalStateException> {
            service.buildResultsBundle(runId)
        }
    }

    @Test
    fun `buildResultsBundle keeps a valid recovered article when an unrelated brief is invalid`() = runTest {
        val rootDir = createTempDirectory("run-archive-source-catalog-mixed-test-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-archive-source-catalog-mixed"
        val metaDb = dbFactory.get(runId, "__meta_archive__")

        val artifactsDir = File(rootDir, "evokammer/runs/$runId/artifacts")
        artifactsDir.mkdirs()
        artifactsDir.resolve("current_sources.md").writeText(
            """
            Current-source anchor notes for the live ADHD article pipeline run

            1. Pharmacological interventions for ADHD: a systematic review and dose-effect network meta-analysis
            - Source: PubMed
            - URL: https://pubmed.ncbi.nlm.nih.gov/42134365/
            - Publication: 2025
            - Notes: large pharmacology synthesis spanning stimulant and non-stimulant monotherapy across ages 5+.
            """.trimIndent()
        )

        val startingField = StartingField.default("archive the run", runId = runId)
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField — $runId",
                contentJson = json.encodeToString(StartingField.serializer(), startingField),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "run-completed-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run Completed — $runId",
                contentJson = json.encodeToString(
                    RunCompletionRecord.serializer(),
                    RunCompletionRecord(
                        runId = runId,
                        winnerId = "group-0",
                        generatedOutputFiles = listOf(
                            GeneratedOutputFile(
                                relativePath = "final-article.html",
                                mediaType = "text/html",
                                content = """
                                    <!doctype html>
                                    <html lang="en">
                                    <body>
                                      <article>
                                        <h2>References</h2>
                                        <ol>
                                          <li>Pharmacological interventions for ADHD: a systematic review and dose-effect network meta-analysis. <a href="https://pubmed.ncbi.nlm.nih.gov/42134365/">PMID 42134365</a>. 2025.</li>
                                        </ol>
                                      </article>
                                    </body>
                                    </html>
                                """.trimIndent()
                            ),
                            GeneratedOutputFile(
                                relativePath = "briefs/evidence-brief.json",
                                mediaType = "application/json",
                                content = """{"title":"Wrong title","publicationDate":"2024","sources":[{"title":"Pharmacological interventions for ADHD: a systematic review and dose-effect network meta-analysis","publicationDate":"2024","url":"https://pubmed.ncbi.nlm.nih.gov/42134365/"}]}"""
                            )
                        )
                    )
                ),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )

        val bundle = service.buildResultsBundle(runId)
        assertNotNull(bundle)
        val entries = unzipEntries(bundle)
        assertTrue(entries.keys.contains("outputs/final-article.html"))
        assertFalse(entries.keys.contains("outputs/briefs/evidence-brief.json"))
    }

    private fun unzipEntries(bytes: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                val content = zip.readBytes().toString(Charsets.UTF_8)
                result[entry.name] = content
                zip.closeEntry()
            }
        }
        return result
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
