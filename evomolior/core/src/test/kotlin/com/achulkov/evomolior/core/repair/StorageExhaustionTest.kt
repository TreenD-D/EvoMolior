package com.achulkov.evomolior.core.repair

import java.io.IOException
import java.nio.file.FileSystemException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageExhaustionTest {
    @Test
    fun `full volume is recognised directly and through wrapping causes`() {
        val enospc = FileSystemException(
            "/var/folders/rh/T/evomolior-codex-7494918433412715868.txt: No space left on device"
        )
        assertTrue(StorageExhaustion.isCause(enospc))
        assertTrue(StorageExhaustion.isCause(IllegalStateException("node failed", RuntimeException(enospc))))
        assertTrue(StorageExhaustion.isCause(IOException("write failed: ENOSPC")))
    }

    @Test
    fun `other failures and non io messages are not storage exhaustion`() {
        assertFalse(StorageExhaustion.isCause(IOException("Connection reset")))
        assertFalse(StorageExhaustion.isCause(IllegalStateException("No space left on device")))
        assertFalse(StorageExhaustion.isCause(RuntimeException("model capacity unavailable")))
    }
}
