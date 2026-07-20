package com.achulkov.evomolior.substrate

import com.achulkov.evomolior.core.contract.OverseerKernelState
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Durable file-backed store for the deterministic Overseer kernel state.
 *
 * The kernel state is global, not run-scoped, so it lives outside the per-run database
 * directories and avoids destructive schema migrations.
 *
 * @param substrateFactory Factory used to resolve the global Overseer state directory.
 * @param json JSON encoder/decoder for the kernel state snapshot.
 */
class OverseerKernelStateStore(
    private val substrateFactory: SubstrateDatabaseFactory,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = false }
) {
    /**
     * Resolves the durable state file.
     *
     * @return File path for the Overseer kernel snapshot.
     */
    fun resolveFile(): File = substrateFactory.overseerStateFile()

    /**
     * Loads the current Overseer kernel state if present.
     *
     * @return Stored state, or null if absent/unparseable.
     */
    fun read(): OverseerKernelState? {
        val file = resolveFile()
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(OverseerKernelState.serializer(), file.readText()) }.getOrNull()
    }

    /**
     * Persists the given state atomically.
     *
     * @param state Kernel state to write.
     */
    fun write(state: OverseerKernelState) {
        val file = resolveFile()
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(OverseerKernelState.serializer(), state))
        Files.move(
            tmp.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        )
    }

    /**
     * Reads, transforms, and writes the Overseer kernel state atomically at the logical level.
     *
     * @param transform Pure transform applied to the current snapshot.
     * @return The updated snapshot.
     */
    fun update(transform: (OverseerKernelState) -> OverseerKernelState): OverseerKernelState {
        val next = transform(read() ?: OverseerKernelState())
        write(next)
        return next
    }
}
