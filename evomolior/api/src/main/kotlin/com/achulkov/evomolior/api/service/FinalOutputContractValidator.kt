package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.api.model.ResearchClaimSupportCoverageContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVRecord
import java.io.StringReader
import java.util.Locale

/**
 * Companion publication artifacts available at a validation boundary.
 *
 * @param claimLedgerJsonl Optional research claim ledger in JSON Lines form.
 */
data class FinalOutputValidationContext(
    val claimLedgerJsonl: String? = null
)

/**
 * Validates generated operator-facing files against their declared publication contract.
 */
object FinalOutputContractValidator {
    private val json = Json { ignoreUnknownKeys = true }
    private val publicUrlRegex = Regex("""https?://[^\s"'<>),;\]]+""", RegexOption.IGNORE_CASE)
    private val isoDateRegex = Regex("""\b20\d{2}-\d{2}-\d{2}\b""")
    private val markdownHeadingRegex = Regex("""(?m)^\s{0,3}#{1,6}\s+(.+?)\s*$""")
    private val interactiveHtmlRegex = Regex(
        """(?is)<(?:script|button|input|select|textarea|details)\b|\bdata-[a-z0-9_-]+\s*=|\b(?:onclick|onchange|oninput)\s*="""
    )

    /**
     * Returns all deterministic contract violations for [output].
     *
     * @param output Generated file to inspect.
     * @param outputSpec Declared path, media type, and validation contract.
     * @param context Optional companion artifacts used by cross-artifact grounding gates.
     */
    fun validate(
        output: GeneratedOutputFile,
        outputSpec: FinalOutputSpec,
        context: FinalOutputValidationContext = FinalOutputValidationContext()
    ): List<String> {
        val errors = mutableListOf<String>()
        val content = output.content.trim()
        if (content.isBlank()) return listOf("output is empty")
        if (output.relativePath != outputSpec.relativePath) {
            errors += "path is ${output.relativePath}, expected ${outputSpec.relativePath}"
        }
        if (!output.mediaType.equals(outputSpec.mediaType, ignoreCase = true)) {
            errors += "media type is ${output.mediaType}, expected ${outputSpec.mediaType}"
        }

        val contract = outputSpec.validation
        when {
            outputSpec.mediaType.startsWith("text/html", ignoreCase = true) -> {
                val isHtml = content.startsWith("<!doctype html>", ignoreCase = true) ||
                    content.startsWith("<html", ignoreCase = true)
                if (!isHtml) errors += "content is not a standalone HTML document"
                if (contract?.requireInteractiveHtml == true && !interactiveHtmlRegex.containsMatchIn(content)) {
                    errors += "HTML has no interactive control, data attribute, or script"
                }
            }

            outputSpec.mediaType.equals("text/csv", ignoreCase = true) ||
                outputSpec.relativePath.endsWith(".csv", ignoreCase = true) -> {
                errors += validateCsv(content, outputSpec)
            }

            outputSpec.mediaType.equals("application/json", ignoreCase = true) ||
                outputSpec.mediaType.endsWith("+json", ignoreCase = true) -> {
                errors += validateJson(content, outputSpec)
            }

            outputSpec.mediaType.equals("text/markdown", ignoreCase = true) ||
                outputSpec.relativePath.endsWith(".md", ignoreCase = true) -> {
                errors += validateMarkdown(content, outputSpec)
            }
        }

        val minimumPublicUrlCount = contract?.minimumPublicUrlCount ?: 0
        if (minimumPublicUrlCount > 0) {
            val publicUrlCount = publicUrlRegex.findAll(content).map { it.value }.toSet().size
            if (publicUrlCount < minimumPublicUrlCount) {
                errors += "contains $publicUrlCount distinct public URL(s), expected at least $minimumPublicUrlCount"
            }
        }
        contract?.researchClaimSupportCoverage?.let { coverageContract ->
            context.claimLedgerJsonl?.let { claimLedger ->
                errors += validateClaimSupportCoverage(claimLedger, coverageContract)
            }
        }
        return errors
    }

    /**
     * Throws when [output] violates [outputSpec].
     *
     * @param output Generated file to inspect.
     * @param outputSpec Declared publication contract.
     * @param context Optional companion artifacts used by cross-artifact grounding gates.
     */
    fun requireValid(
        output: GeneratedOutputFile,
        outputSpec: FinalOutputSpec,
        context: FinalOutputValidationContext = FinalOutputValidationContext()
    ) {
        val errors = validate(output, outputSpec, context)
        require(errors.isEmpty()) {
            "Published output ${outputSpec.relativePath} violates its contract: ${errors.joinToString("; ")}"
        }
    }

    /**
     * Renders the contract as concise retry instructions for the final writer.
     *
     * @param outputSpec Declared publication contract.
     */
    fun promptRequirements(outputSpec: FinalOutputSpec): String? {
        val contract = outputSpec.validation ?: return null
        val requirements = buildList {
            if (contract.minimumPublicUrlCount > 0) {
                add("include at least ${contract.minimumPublicUrlCount} distinct public HTTP(S) source URL(s)")
            }
            if (contract.requireInteractiveHtml) {
                add("include a functional interactive HTML surface such as controls, details, filtering, or script")
            }
            if (contract.requiredCsvColumns.isNotEmpty()) {
                add("use exactly parseable CSV records with required columns: ${contract.requiredCsvColumns.joinToString(", ")}")
            }
            contract.minimumCsvDataRows?.let { add("include at least $it CSV data rows") }
            contract.exactCsvDataRows?.let { add("include exactly $it CSV data rows") }
            contract.evidenceUrlColumn?.let {
                add("put at least one public HTTP(S) URL in `$it` for every CSV data row")
            }
            contract.rankColumn?.let {
                add("make `$it` the consecutive integer sequence 1 through the final CSV row")
            }
            contract.csvRankedEvidence?.let { ranked ->
                add(
                    "for the top ${ranked.topRows} rows ranked by `${ranked.rankColumn}`, put at least " +
                        "${ranked.minimumUrlsPerRow} distinct public URL(s) per row in " +
                        "`${ranked.evidenceUrlColumn}` and at least ${ranked.minimumDistinctUrls} " +
                        "distinct URL(s) across those rows"
                )
            }
            contract.csvSourceMetadata?.sourceMetadataColumn?.let {
                add("include non-blank source metadata in `$it` for every CSV data row")
            }
            contract.csvSourceMetadata?.accessDateColumn?.let {
                add("include an ISO YYYY-MM-DD source access date in `$it` for every CSV data row")
            }
            contract.researchClaimSupportCoverage?.let {
                val scope = if (it.usedInFinalAnswerOnly) "final-answer claims" else "claims"
                add(
                    "when a research claim ledger is available, source-support at least " +
                        "${formatPercentage(it.minimumCoverage)} of $scope"
                )
            }
            if (contract.requiredJsonRootKeys.isNotEmpty()) {
                add("include JSON root keys: ${contract.requiredJsonRootKeys.joinToString(", ")}")
            }
            contract.minimumJsonArraySizes.forEach { (key, size) ->
                add("include at least $size item(s) in JSON array `$key`")
            }
            contract.requiredJsonArrayItemFields.forEach { (key, fields) ->
                add("make every `$key` JSON item an object with fields: ${fields.joinToString(", ")}")
            }
            if (contract.requiredMarkdownSectionSignals.isNotEmpty()) {
                add(
                    "include Markdown headings containing: " +
                        contract.requiredMarkdownSectionSignals.joinToString(", ")
                )
            }
            if (contract.requireMarkdownAccessDate) {
                add("include source access dates in ISO YYYY-MM-DD form")
            }
        }
        return requirements.takeIf { it.isNotEmpty() }
            ?.joinToString(separator = "\n") { "- $it" }
    }

    private fun validateCsv(content: String, outputSpec: FinalOutputSpec): List<String> {
        val contract = outputSpec.validation
        val parser = runCatching {
            CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .get()
                .parse(StringReader(content))
        }.getOrElse { return listOf("CSV is not parseable: ${it.message}") }

        return parser.use {
            val errors = mutableListOf<String>()
            val headers = parser.headerNames
            val records = runCatching { parser.records }
                .getOrElse { return@use listOf("CSV is not parseable: ${it.message}") }

            records.filter { it.size() != headers.size }.forEach { record ->
                errors += "CSV record ${record.recordNumber} has ${record.size()} fields, expected ${headers.size}"
            }
            contract?.requiredCsvColumns.orEmpty().filterNot(headers::contains).forEach { column ->
                errors += "CSV is missing required column `$column`"
            }
            contract?.minimumCsvDataRows?.let { minimum ->
                if (records.size < minimum) errors += "CSV has ${records.size} data rows, expected at least $minimum"
            }
            contract?.exactCsvDataRows?.let { exact ->
                if (records.size != exact) errors += "CSV has ${records.size} data rows, expected exactly $exact"
            }
            contract?.evidenceUrlColumn?.let { column ->
                if (column in headers) {
                    records.filterNot { record ->
                        runCatching { publicUrlRegex.containsMatchIn(record.get(column)) }.getOrDefault(false)
                    }.forEach { record ->
                        errors += "CSV record ${record.recordNumber} has no public URL in `$column`"
                    }
                }
            }
            contract?.rankColumn?.let { column ->
                if (column in headers) {
                    records.forEachIndexed { index, record ->
                        val actual = runCatching { record.get(column).trim().toInt() }.getOrNull()
                        val expected = index + 1
                        if (actual != expected) {
                            errors += "CSV record ${record.recordNumber} has rank ${actual ?: "non-integer"}, expected $expected"
                        }
                    }
                }
            }
            contract?.csvRankedEvidence?.let { ranked ->
                val requiredColumns = buildList {
                    add(ranked.rankColumn)
                    add(ranked.evidenceUrlColumn)
                    ranked.conceptColumn?.let(::add)
                }
                requiredColumns.filterNot(headers::contains).forEach { column ->
                    errors += "CSV ranked evidence is missing column `$column`"
                }
                if (requiredColumns.all(headers::contains)) {
                    val rankedRecords = records.mapNotNull { record ->
                        val rank = csvValue(record, ranked.rankColumn)?.trim()?.toIntOrNull()
                        rank?.let { it to record }
                    }.filter { (rank) -> rank in 1..ranked.topRows }
                        .sortedBy { (rank) -> rank }
                    rankedRecords.groupingBy { (rank) -> rank }.eachCount()
                        .filterValues { count -> count > 1 }
                        .forEach { (rank, count) ->
                            errors += "CSV ranked evidence has $count rows with rank $rank"
                        }
                    val presentRanks = rankedRecords.map { (rank) -> rank }.toSet()
                    (1..ranked.topRows).filterNot(presentRanks::contains).forEach { rank ->
                        errors += "CSV ranked evidence has no row with rank $rank"
                    }
                    val urlsAcrossTopRows = mutableSetOf<String>()
                    rankedRecords.forEach { (rank, record) ->
                        val urls = publicUrls(csvValue(record, ranked.evidenceUrlColumn).orEmpty())
                        urlsAcrossTopRows += urls
                        if (urls.size < ranked.minimumUrlsPerRow) {
                            val concept = ranked.conceptColumn
                                ?.let { column -> csvValue(record, column)?.trim()?.takeIf(String::isNotEmpty) }
                                ?.let { " (`$it`)" }
                                .orEmpty()
                            errors += "CSV ranked row $rank$concept has ${urls.size} distinct public URL(s) in " +
                                "`${ranked.evidenceUrlColumn}`, expected at least ${ranked.minimumUrlsPerRow}"
                        }
                    }
                    if (urlsAcrossTopRows.size < ranked.minimumDistinctUrls) {
                        errors += "CSV top ${ranked.topRows} ranked rows contain ${urlsAcrossTopRows.size} " +
                            "distinct public URL(s), expected at least ${ranked.minimumDistinctUrls}"
                    }
                }
            }
            contract?.csvSourceMetadata?.sourceMetadataColumn?.let { column ->
                if (column !in headers) {
                    errors += "CSV is missing source metadata column `$column`"
                } else {
                    records.filter { csvValue(it, column).isNullOrBlank() }.forEach { record ->
                        errors += "CSV record ${record.recordNumber} has blank source metadata in `$column`"
                    }
                }
            }
            contract?.csvSourceMetadata?.accessDateColumn?.let { column ->
                if (column !in headers) {
                    errors += "CSV is missing access-date column `$column`"
                } else {
                    records.filterNot {
                        isoDateRegex.containsMatchIn(csvValue(it, column).orEmpty())
                    }.forEach { record ->
                        errors += "CSV record ${record.recordNumber} has no ISO YYYY-MM-DD access date in `$column`"
                    }
                }
            }
            errors
        }
    }

    private fun validateClaimSupportCoverage(
        claimLedgerJsonl: String,
        contract: ResearchClaimSupportCoverageContract
    ): List<String> {
        val parsedClaims = mutableListOf<JsonObject>()
        claimLedgerJsonl.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEachIndexed { index, line ->
                val claim = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull()
                    ?: return listOf("claim ledger line ${index + 1} is not a JSON object")
                parsedClaims += claim
            }
        if (parsedClaims.isEmpty()) return listOf("claim ledger contains no claims")

        val eligibleClaims = if (contract.usedInFinalAnswerOnly) {
            parsedClaims.filter { claim ->
                (claim["used_in_final_answer"] as? JsonPrimitive)?.booleanOrNull == true
            }
        } else {
            parsedClaims
        }
        if (eligibleClaims.isEmpty()) {
            return listOf("claim ledger contains no final-answer claims for claim-support coverage")
        }

        val supportedCount = eligibleClaims.count { claim ->
            (claim["supporting_sources"] as? JsonArray)
                ?.any { source -> source.isNonBlankSourceReference() } == true
        }
        val coverage = supportedCount.toDouble() / eligibleClaims.size
        return if (coverage + COVERAGE_EPSILON < contract.minimumCoverage) {
            listOf(
                "claim ledger source-support coverage is ${formatPercentage(coverage)} " +
                    "($supportedCount/${eligibleClaims.size}), expected at least " +
                    formatPercentage(contract.minimumCoverage)
            )
        } else {
            emptyList()
        }
    }

    private fun publicUrls(value: String): Set<String> =
        publicUrlRegex.findAll(value).map { it.value }.toSet()

    private fun csvValue(record: CSVRecord, column: String): String? =
        runCatching { record.get(column) }.getOrNull()

    private fun JsonElement.isNonBlankSourceReference(): Boolean =
        (this as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true

    private fun formatPercentage(value: Double): String =
        String.format(Locale.ROOT, "%.1f%%", value * 100.0)

    private fun validateJson(content: String, outputSpec: FinalOutputSpec): List<String> {
        val root = runCatching { json.parseToJsonElement(content) }
            .getOrElse { return listOf("JSON is not parseable: ${it.message}") }
        val rootObject = root as? JsonObject ?: return listOf("JSON root must be an object")
        val contract = outputSpec.validation ?: return emptyList()
        val errors = mutableListOf<String>()

        contract.requiredJsonRootKeys.filterNot(rootObject::containsKey).forEach { key ->
            errors += "JSON root is missing key `$key`"
        }
        contract.minimumJsonArraySizes.forEach { (key, minimum) ->
            val array = rootObject[key] as? JsonArray
            if (array == null) {
                errors += "JSON root key `$key` must be an array"
            } else if (array.size < minimum) {
                errors += "JSON array `$key` has ${array.size} item(s), expected at least $minimum"
            }
        }
        contract.requiredJsonArrayItemFields.forEach { (key, fields) ->
            val array = rootObject[key] as? JsonArray
            if (array == null) {
                errors += "JSON root key `$key` must be an array"
            } else {
                array.forEachIndexed { index, item ->
                    val itemObject = item as? JsonObject
                    if (itemObject == null) {
                        errors += "JSON array `$key` item $index must be an object"
                    } else {
                        fields.filterNot(itemObject::containsKey).forEach { field ->
                            errors += "JSON array `$key` item $index is missing field `$field`"
                        }
                    }
                }
            }
        }
        return errors
    }

    private fun validateMarkdown(content: String, outputSpec: FinalOutputSpec): List<String> {
        val contract = outputSpec.validation ?: return emptyList()
        val errors = mutableListOf<String>()
        val headings = markdownHeadingRegex.findAll(content)
            .map { it.groupValues[1].lowercase() }
            .toList()
        contract.requiredMarkdownSectionSignals.forEach { signal ->
            if (headings.none { it.contains(signal, ignoreCase = true) }) {
                errors += "Markdown is missing a heading containing `$signal`"
            }
        }
        if (contract.requireMarkdownAccessDate && !isoDateRegex.containsMatchIn(content)) {
            errors += "Markdown has no ISO YYYY-MM-DD access date"
        }
        return errors
    }

    private const val COVERAGE_EPSILON = 1e-12
}
