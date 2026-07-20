package com.achulkov.evomolior.core.grader

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException

/**
 * Compiles and evaluates untrusted grader-authored patterns with linear-time RE2 semantics.
 */
object SafeRegexMatcher {
    /**
     * Returns a bounded RE2/J compile diagnostic, or null when [pattern] is valid.
     *
     * @param pattern Grader-authored pattern.
     */
    fun validationError(pattern: String): String? = try {
        Pattern.compile(pattern)
        null
    } catch (error: PatternSyntaxException) {
        error.message
            ?.lineSequence()
            ?.firstOrNull()
            ?.take(500)
            ?: "unsupported RE2/J syntax"
    }

    /**
     * Returns whether [pattern] is valid under the safe grader regex dialect.
     *
     * @param pattern Grader-authored pattern.
     */
    fun isValid(pattern: String): Boolean = validationError(pattern) == null

    /**
     * Returns whether [pattern] occurs in [input] without backtracking.
     *
     * @param pattern Valid grader-authored pattern.
     * @param input Canon snapshot to inspect.
     */
    fun containsMatch(pattern: String, input: String): Boolean =
        Pattern.compile(pattern).matcher(input).find()
}
