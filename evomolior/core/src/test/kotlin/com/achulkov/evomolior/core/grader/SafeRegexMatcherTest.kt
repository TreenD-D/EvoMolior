package com.achulkov.evomolior.core.grader

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SafeRegexMatcherTest {
    @Test
    fun `grader patterns use linear-time matching`() {
        val adversarialPattern = "(a+)+$"
        val adversarialInput = "a".repeat(100_000) + "!"

        assertTrue(SafeRegexMatcher.isValid(adversarialPattern))
        assertFalse(SafeRegexMatcher.containsMatch(adversarialPattern, adversarialInput))
    }

    @Test
    fun `unsupported backtracking constructs fail validation`() {
        assertFalse(SafeRegexMatcher.isValid("value(?=unsafe)"))
        assertNotNull(SafeRegexMatcher.validationError("value(?=unsafe)"))
    }

    @Test
    fun `RE2 repetition bounds stop at one thousand`() {
        assertTrue(SafeRegexMatcher.isValid("value[\\s\\S]{0,1000}result"))
        assertFalse(SafeRegexMatcher.isValid("value[\\s\\S]{0,1001}result"))
        assertFalse(SafeRegexMatcher.isValid("value[\\s\\S]{0,2000}result"))
    }
}
