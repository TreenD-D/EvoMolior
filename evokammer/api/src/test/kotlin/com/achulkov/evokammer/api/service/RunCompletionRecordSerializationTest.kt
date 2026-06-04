package com.achulkov.evokammer.api.service

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
            judgeOutputsByGrader = mapOf("grader-0" to "judge output"),
            submissionSummariesByGroup = mapOf("group-0" to "submission summary"),
            completedAtMs = 42L
        )

        val encoded = json.encodeToString(RunCompletionRecord.serializer(), payload)
        val decoded = json.decodeFromString(RunCompletionRecord.serializer(), encoded)

        assertEquals(payload, decoded)
    }
}
