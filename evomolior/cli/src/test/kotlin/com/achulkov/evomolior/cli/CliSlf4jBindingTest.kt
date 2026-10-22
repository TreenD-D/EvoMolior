package com.achulkov.evomolior.cli

import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals

class CliSlf4jBindingTest {
    @Test
    fun `CLI runtime binds SLF4J to JUL`() {
        assertEquals("org.slf4j.jul.JDK14LoggerFactory", LoggerFactory.getILoggerFactory().javaClass.name)
    }
}
