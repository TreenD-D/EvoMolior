package com.achulkov.evomolior.core.config

/**
 * Lightweight heuristics for inferring output expectations from a run objective.
 *
 * These helpers intentionally stay conservative and are only used to tune runtime defaults
 * such as final-publication handling and fast-run scoring rubrics.
 */
object RunObjectiveHeuristics {

    /**
     * Returns true when the objective clearly asks for a browser-openable deliverable.
     *
     * @param objective Human-authored run objective text.
     */
    fun requestsBrowserDeliverable(objective: String): Boolean {
        val normalized = objective.lowercase()
        return BROWSER_DELIVERABLE_PATTERNS.any { pattern -> pattern.containsMatchIn(normalized) }
    }

    /**
     * Returns true when the objective appears to target a long-form publication artifact.
     *
     * @param objective Human-authored run objective text.
     */
    fun requestsLongFormArticle(objective: String): Boolean {
        if (!requestsBrowserDeliverable(objective)) {
            return false
        }
        val normalized = objective.lowercase()
        return LONG_FORM_ARTICLE_KEYWORDS.any(normalized::contains)
    }

    /**
     * Returns true when the objective requires time-sensitive or externally verifiable evidence.
     *
     * This intentionally targets clear research signals rather than generic words such as
     * "research", which may describe work over a supplied offline corpus.
     *
     * @param objective Human-authored run objective text.
     */
    fun requiresExternalEvidence(objective: String): Boolean {
        val normalized = objective.lowercase()
        return EXTERNAL_EVIDENCE_PATTERNS.any { pattern -> pattern.containsMatchIn(normalized) }
    }

    private val BROWSER_DELIVERABLE_PATTERNS = listOf(
        Regex("""\bhtml5?\b"""),
        Regex("""\bbrowser(?:-readable)?\b"""),
        Regex("""\bweb\s?page\b"""),
        Regex("""\blanding page\b"""),
        Regex("""\bmicrosite\b"""),
        Regex("""\barticle\b"""),
        Regex("""\bpublication-ready\b"""),
        Regex("""\bfinal artifacts?\b"""),
        Regex("""\bfinal deliverable\b"""),
        Regex("""\bfinal (?:operator-facing )?report\b"""),
        Regex("""\boperator-facing report\b"""),
        Regex("""\bresearch report\b"""),
        Regex("""\bsubmission brief\b"""),
        Regex("""\bstrategy document\b""")
    )

    private val LONG_FORM_ARTICLE_KEYWORDS = listOf(
        "article",
        "guide",
        "blog",
        "long-form",
        "long form",
        "editorial"
    )

    private val EXTERNAL_EVIDENCE_PATTERNS = listOf(
        Regex("""\bcurrent(?:ly)?\b"""),
        Regex("""\blatest\b"""),
        Regex("""\btoday(?:'s)?\b"""),
        Regex("""\brecent(?:ly)?\b"""),
        Regex("""\bmarket intelligence\b"""),
        Regex("""\bmarket map\b"""),
        Regex("""\bcompetitors?\b"""),
        Regex("""\bpricing\b"""),
        Regex("""\bapp stores?\b"""),
        Regex("""\bdistribution trends?\b"""),
        Regex("""\bfunding\b"""),
        Regex("""\bpatents?\b"""),
        Regex("""\bpublic sources?\b"""),
        Regex("""\bweb sources?\b"""),
        Regex("""\blive retrieval\b"""),
        Regex("""\b20(?:2[5-9]|[3-9]\d)\b""")
    )
}
