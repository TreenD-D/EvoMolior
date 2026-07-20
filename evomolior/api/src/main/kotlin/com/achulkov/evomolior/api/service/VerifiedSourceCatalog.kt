package com.achulkov.evomolior.api.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

internal data class VerifiedSourceRecord(
    val title: String,
    val url: String?,
    val publicationDate: String?
)

internal object VerifiedSourceCatalog {
    fun resolve(runRoot: File): List<VerifiedSourceRecord> {
        val artifactsRoot = File(runRoot, "artifacts")
        if (!artifactsRoot.exists()) return emptyList()
        val sourceFile = artifactsRoot.walkTopDown()
            .firstOrNull { it.isFile && it.name.equals("current_sources.md", ignoreCase = true) }
            ?: return emptyList()
        return parse(sourceFile.readText(Charsets.UTF_8))
    }

    fun parse(markdown: String): List<VerifiedSourceRecord> {
        val records = mutableListOf<VerifiedSourceRecord>()
        var title: String? = null
        var url: String? = null
        var publicationDate: String? = null

        fun flush() {
            val resolvedTitle = title?.trim().orEmpty()
            if (resolvedTitle.isNotBlank()) {
                records += VerifiedSourceRecord(
                    title = resolvedTitle,
                    url = url?.trim()?.takeIf { it.isNotBlank() },
                    publicationDate = publicationDate?.trim()?.takeIf { it.isNotBlank() }
                )
            }
            title = null
            url = null
            publicationDate = null
        }

        markdown.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.matches(Regex("""^\d+\.\s+.+"""))) {
                if (title != null) flush()
                title = trimmed.replaceFirst(Regex("""^\d+\.\s+"""), "").trim()
                return@forEach
            }
            if (!trimmed.startsWith("- ")) return@forEach
            when {
                trimmed.startsWith("- URL:", ignoreCase = true) ->
                    url = trimmed.substringAfter("URL:", "").trim()
                trimmed.startsWith("- Publication/update date visible on page:", ignoreCase = true) ->
                    publicationDate = trimmed.substringAfter("Publication/update date visible on page:", "").trim()
                trimmed.startsWith("- Publication update date visible on page:", ignoreCase = true) ->
                    publicationDate = trimmed.substringAfter("Publication update date visible on page:", "").trim()
                trimmed.startsWith("- Publication:", ignoreCase = true) ->
                    publicationDate = trimmed.substringAfter("Publication:", "").trim()
            }
        }

        if (title != null) flush()
        return records
    }

    fun validate(output: GeneratedOutputFile, sourceCatalog: List<VerifiedSourceRecord>) {
        if (sourceCatalog.isEmpty()) return
        when {
            output.mediaType.startsWith("text/html", ignoreCase = true) ->
                validateHtml(output, sourceCatalog)
            output.mediaType.equals("application/json", ignoreCase = true) ||
                output.mediaType.endsWith("+json", ignoreCase = true) ->
                validateJson(output, sourceCatalog)
        }
    }

    private fun validateHtml(output: GeneratedOutputFile, sourceCatalog: List<VerifiedSourceRecord>) {
        val referencesSection = Regex("""(?is)<h2[^>]*>\s*References\s*</h2>\s*<ol>(.*?)</ol>""")
            .find(output.content)
            ?.groupValues
            ?.getOrNull(1)
            ?: return

        Regex("(?is)<li[^>]*>(.*?)</li>")
            .findAll(referencesSection)
            .mapNotNull { match -> match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() } }
            .forEach { item ->
                val flattened = item
                    .replace(Regex("(?is)<[^>]+>"), " ")
                    .replace("&nbsp;", " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()

                val matchedSource = sourceCatalog.firstOrNull { source ->
                    normalizedTitleContains(flattened, source.title) ||
                        source.url?.let { flattened.contains(it, ignoreCase = true) } == true
                } ?: throw IllegalStateException(
                    "Published HTML output ${output.relativePath} contains a reference not found in the verified source catalog: $flattened"
                )

                validateReferenceDatePair(flattened, matchedSource, output.relativePath)
            }
    }

    private fun validateJson(output: GeneratedOutputFile, sourceCatalog: List<VerifiedSourceRecord>) {
        val element = Json.parseToJsonElement(output.content.trim())
        val obj = element as? JsonObject ?: return
        val sources = obj["sources"]?.jsonArray ?: return

        sources.forEachIndexed { index, sourceElement ->
            val sourceObj = sourceElement as? JsonObject ?: throw IllegalStateException(
                "Published JSON output ${output.relativePath} contains a non-object source at index $index"
            )
            val title = sourceObj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val url = sourceObj["url"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
            val publicationDate = sourceObj["publicationDate"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }

            val matchedSource = sourceCatalog.firstOrNull { source ->
                normalizedTitleEquals(title, source.title) ||
                    (url != null && source.url.equals(url, ignoreCase = true))
            } ?: throw IllegalStateException(
                "Published JSON output ${output.relativePath} source[$index] does not match the verified source catalog: title=$title url=${url ?: "null"} publicationDate=${publicationDate ?: "null"}"
            )

            publicationDate?.let { date ->
                if (!datesMatch(date, matchedSource.publicationDate)) {
                    throw IllegalStateException(
                        "Published JSON output ${output.relativePath} source[$index] has publicationDate=$date but the verified source catalog expects ${matchedSource.publicationDate ?: "unknown"} for ${matchedSource.title}"
                    )
                }
            }
        }
    }

    private fun validateReferenceDatePair(
        flattenedReference: String,
        source: VerifiedSourceRecord,
        relativePath: String
    ) {
        val referenceDate = extractReferencePublicationDate(flattenedReference)
        if (referenceDate != null && source.publicationDate != null && !datesMatch(referenceDate, source.publicationDate)) {
            throw IllegalStateException(
                "Published HTML output $relativePath references ${source.title} with publication date $referenceDate, but the verified source catalog expects ${source.publicationDate}"
            )
        }
    }

    private fun extractReferencePublicationDate(reference: String): String? {
        Regex("""\b(20\d{2}-\d{2}-\d{2})\b""").find(reference)?.groupValues?.getOrNull(1)?.let { return it }
        Regex("""\b(20\d{2})\b""").find(reference)?.groupValues?.getOrNull(1)?.let { return it }
        return null
    }

    private fun datesMatch(referenceDate: String, catalogDate: String?): Boolean {
        if (catalogDate == null) return true
        return when {
            referenceDate == catalogDate -> true
            referenceDate.length == 4 && catalogDate.startsWith(referenceDate) -> true
            catalogDate.length == 4 && referenceDate.startsWith(catalogDate) -> true
            else -> false
        }
    }

    private fun normalizedTitleEquals(candidate: String, title: String): Boolean =
        normalizeForMatching(candidate) == normalizeForMatching(title)

    private fun normalizedTitleContains(candidate: String, title: String): Boolean {
        val normalizedCandidate = normalizeForMatching(candidate)
        val normalizedTitle = normalizeForMatching(title)
        return normalizedCandidate.contains(normalizedTitle)
    }

    private fun normalizeForMatching(value: String): String =
        value
            .lowercase()
            .replace(Regex("""(?i)\bet al\.?"""), " ")
            .replace(Regex("""["'’`.,:;()\[\]{}<>/_-]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
}
