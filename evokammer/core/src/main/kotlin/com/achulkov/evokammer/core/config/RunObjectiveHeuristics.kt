package com.achulkov.evokammer.core.config

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
        return BROWSER_DELIVERABLE_KEYWORDS.any(normalized::contains)
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

    private val BROWSER_DELIVERABLE_KEYWORDS = listOf(
        "html",
        "html5",
        "browser",
        "web page",
        "webpage",
        "landing page",
        "microsite",
        "article"
    )

    private val LONG_FORM_ARTICLE_KEYWORDS = listOf(
        "article",
        "guide",
        "blog",
        "long-form",
        "long form",
        "editorial"
    )
}
