package com.achulkov.evokammer.api.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks down the public runtime surface so scoring remains behind the engine boundary.
 */
class ScoringIsolationSurfaceTest {

    @Test
    fun `engine service exposes no scoring-specific control methods`() {
        val methodNames = EvoKammerEngineService::class.java.methods.map { it.name }

        assertTrue(methodNames.contains("startRun"))
        assertTrue(methodNames.contains("pauseRun"))
        assertTrue(methodNames.contains("resumeRun"))
        assertTrue(methodNames.contains("abortRun"))
        assertTrue(methodNames.contains("getState"))
        assertTrue(methodNames.contains("getMetaArchiveSummary"))

        assertFalse(methodNames.any { name ->
            name.contains("score", ignoreCase = true) ||
                name.contains("grading", ignoreCase = true) ||
                name.contains("gate", ignoreCase = true)
        }, "Engine service must not expose scoring-specific control methods")
    }

    @Test
    fun `ipc server only exposes lifecycle entry points`() {
        val methodNames = EvoKammerIpcServer::class.java.methods.map { it.name }.distinct()

        assertTrue(methodNames.contains("start"))
        assertTrue(methodNames.contains("stop"))

        assertFalse(methodNames.any { name ->
            name.contains("score", ignoreCase = true) ||
                name.contains("grading", ignoreCase = true) ||
                name.contains("gate", ignoreCase = true)
        }, "IPC server must not expose scoring-specific entry points")
    }
}
