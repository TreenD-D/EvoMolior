package com.achulkov.evomolior.api.service

import kotlinx.serialization.json.Json
import java.util.zip.ZipInputStream

/** Exact legacy recovery authority verification, separate from accepted and outcome-v2 manifests. */
object LegacyRecoveryBundleVerifier {
    /**
     * Verifies every committed entry and the non-accepted recovery record.
     * @param bundleBytes Exact immutable archive bytes.
     * @param expectedRunId Run identity supplied independently by the stage or archive owner.
     * @param expectedRecord Optional substrate-persisted authority, retaining the archive owner's stronger check.
     * @param json Archive owner's serialization format when an external record is supplied.
     */
    fun verify(
        bundleBytes: ByteArray,
        expectedRunId: String,
        expectedRecord: RecoveredRunRecord? = null,
        json: Json = Json { ignoreUnknownKeys = true; prettyPrint = false },
    ): RecoveredRunRecord {
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(bundleBytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && entry.name !in files) {
                    "Recovery bundle contains a directory or duplicate entry"
                }
                files[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        val manifestBytes = requireNotNull(files[RecoveryBundleManifest.MANIFEST_PATH]) {
            "Recovery bundle manifest is missing"
        }
        val manifest = json.decodeFromString(RecoveryBundleManifest.serializer(), manifestBytes.decodeToString())
        val recordBytes = requireNotNull(files[RecoveryBundleManifest.RECOVERY_RECORD_PATH]) {
            "Recovery bundle record is missing"
        }
        val bundledRecord = json.decodeFromString(RecoveredRunRecord.serializer(), recordBytes.decodeToString())
        val record = expectedRecord ?: bundledRecord
        require(record.runId == expectedRunId && manifest.runId == expectedRunId) {
            "Recovery bundle belongs to another run"
        }
        require(bundledRecord == record) { "Recovery bundle record differs from recovered-run authority" }
        val expectedRecordBytes = expectedRecord?.let {
            json.encodeToString(RecoveredRunRecord.serializer(), it).encodeToByteArray()
        } ?: recordBytes
        require(recordBytes.contentEquals(expectedRecordBytes)) {
            "Recovery bundle record bytes differ from recovered-run authority"
        }
        require(manifest == RecoveryBundleManifest(
            runId = expectedRunId,
            entries = record.committedBundleEntries,
        )) { "Recovery bundle manifest differs from recovered-run authority" }
        val authorities = record.committedBundleEntries + record.manifestAuthority + RecoveryBundleEntry(
            path = RecoveryBundleManifest.RECOVERY_RECORD_PATH,
            sha256 = sha256Hex(expectedRecordBytes),
            sizeBytes = expectedRecordBytes.size.toLong(),
            category = RecoveryBundleEntryCategory.RECOVERY_AUTHORITY,
        )
        val declared = authorities.associateBy(RecoveryBundleEntry::path)
        require(declared.keys == files.keys) {
            "Recovery bundle entry cardinality differs from recovered-run authority"
        }
        declared.forEach { (path, authority) ->
            val bytes = files.getValue(path)
            require(bytes.size.toLong() == authority.sizeBytes && sha256Hex(bytes) == authority.sha256) {
                "Recovery bundle hash or size mismatch for $path"
            }
        }
        return record
    }
}
