package com.pathfilter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilterTest {

    @Test
    fun `star stays within one path segment`() {
        val filter = Filter("kt", listOf("src/*.kt"))
        assertTrue(filter.matches("src/App.kt"))
        assertFalse(filter.matches("src/main/App.kt"))
    }

    @Test
    fun `globstar crosses segments`() {
        val filter = Filter("kt", listOf("src/**/*.kt"))
        assertTrue(filter.matches("src/main/kotlin/App.kt"))
    }

    @Test
    fun `leading globstar also matches top-level files`() {
        val filter = Filter("kt", listOf("**/*.kt"))
        assertTrue(filter.matches("App.kt"))
        assertTrue(filter.matches("a/b/App.kt"))
        assertFalse(filter.matches("App.java"))
    }

    @Test
    fun `exclusions win over inclusions`() {
        val filter = Filter("src", listOf("**/*.kt", "!**/test/**"))
        assertTrue(filter.matches("app/Main.kt"))
        assertFalse(filter.matches("app/test/MainTest.kt"))
    }

    @Test
    fun `paths are normalized before matching`() {
        val filter = Filter("kt", listOf("app/*.kt"))
        assertTrue(filter.matches("./app/Main.kt"))
        assertTrue(filter.matches("app\\Main.kt"))
    }

    @Test
    fun `evaluateFilters preserves declaration order`() {
        val filters = linkedMapOf(
            "b" to listOf("b/**"),
            "a" to listOf("a/**"),
        )
        val results = evaluateFilters(filters, listOf("a/x.txt"))
        assertEquals(listOf("b", "a"), results.keys.toList())
        assertEquals(mapOf("b" to false, "a" to true), results)
    }
}

class FilterConfigTest {

    @Test
    fun `parses a filters mapping`() {
        val config = parseFilters(
            """
            filters:
              backend:
                - "app/src/**/*.kt"
                - "!app/src/test/**"
              docs: "**/*.md"   # single string is allowed
            """.trimIndent(),
        )
        assertEquals(listOf("app/src/**/*.kt", "!app/src/test/**"), config["backend"])
        assertEquals(listOf("**/*.md"), config["docs"])
    }

    @Test
    fun `rejects a config without filters`() {
        assertFailsWith<ConfigError> { parseFilters("something_else: 1") }
        assertFailsWith<ConfigError> { parseFilters("") }
    }

    @Test
    fun `rejects empty patterns`() {
        assertFailsWith<ConfigError> {
            parseFilters(
                """
                filters:
                  backend:
                    - ""
                """.trimIndent(),
            )
        }
    }
}
