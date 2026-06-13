package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.api.model.FinalOutputSpec
import com.achulkov.evokammer.api.model.FinalOutputKind
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class RunCompletionRecordSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `run completion record round-trips final answer payload`() {
        val payload = RunCompletionRecord(
            runId = "run-1",
            winnerId = "group-0",
            finalAnswerSummary = "winner=group-0",
            finalDeliverableHtml = "<!doctype html><html><body><article>done</article></body></html>",
            requestedOutputSpec = FinalOutputSpec(
                relativePath = "briefs/evidence-brief.json",
                mediaType = "application/json",
                description = "Machine-readable evidence brief",
                kind = FinalOutputKind.PATCH_BUNDLE
            ),
            judgeOutputsByGrader = mapOf("grader-0" to "judge output"),
            submissionSummariesByGroup = mapOf("group-0" to "submission summary"),
            completedAtMs = 42L
        )

        val encoded = json.encodeToString(RunCompletionRecord.serializer(), payload)
        val decoded = json.decodeFromString(RunCompletionRecord.serializer(), encoded)

        assertEquals(payload, decoded)
    }

    @Test
    fun `final output spec defaults missing kind to generic file`() {
        val payload = """
            {
              "relativePath": "briefs/evidence-brief.json",
              "mediaType": "application/json",
              "description": "Machine-readable evidence brief"
            }
        """.trimIndent()

        val decoded = json.decodeFromString(FinalOutputSpec.serializer(), payload)

        assertEquals("briefs/evidence-brief.json", decoded.relativePath)
        assertEquals("application/json", decoded.mediaType)
        assertEquals("Machine-readable evidence brief", decoded.description)
        assertEquals(FinalOutputKind.GENERIC_FILE, decoded.kind)
    }
}
