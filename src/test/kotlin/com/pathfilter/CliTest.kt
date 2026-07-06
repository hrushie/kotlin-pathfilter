package com.pathfilter

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private data class CliResult(val exitCode: Int, val stdout: String, val stderr: String)

/** Run [runCli], capturing stdout/stderr and optionally feeding [stdin]. */
private fun runCliCaptured(args: List<String>, stdin: String? = null): CliResult {
    val originalOut = System.out
    val originalErr = System.err
    val originalIn = System.`in`
    val outBuf = ByteArrayOutputStream()
    val errBuf = ByteArrayOutputStream()
    System.setOut(PrintStream(outBuf))
    System.setErr(PrintStream(errBuf))
    if (stdin != null) System.setIn(ByteArrayInputStream(stdin.toByteArray()))
    try {
        val exitCode = runCli(args.toTypedArray())
        return CliResult(exitCode, outBuf.toString(), errBuf.toString())
    } finally {
        System.setOut(originalOut)
        System.setErr(originalErr)
        System.setIn(originalIn)
    }
}

private fun tempFile(content: String): Path =
    Files.createTempFile("pathfilter-test", ".tmp").also { it.writeText(content) }

private const val ONE_FILTER_CONFIG = """
filters:
  all:
    - "**"
"""

class CliTest {

    @Test
    fun `missing config is a usage error`() {
        val result = runCliCaptured(listOf("App.kt"))
        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("-c/--config"))
    }

    @Test
    fun `unknown option is a usage error`() {
        val result = runCliCaptured(listOf("--nope"))
        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("unrecognized arguments"))
    }

    @Test
    fun `version flag prints version and exits cleanly`() {
        val result = runCliCaptured(listOf("--version"))
        assertEquals(0, result.exitCode)
        assertEquals("pathfilter $VERSION\n", result.stdout)
    }

    @Test
    fun `help flag exits cleanly`() {
        val result = runCliCaptured(listOf("--help"))
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.startsWith("usage:"))
    }

    @Test
    fun `missing config file is reported as a config error`() {
        val result = runCliCaptured(listOf("-c", "/no/such/file.yaml", "App.kt"))
        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("configuration file not found"))
    }

    @Test
    fun `positional paths render the env file to stdout`() {
        val config = tempFile(ONE_FILTER_CONFIG)
        val result = runCliCaptured(listOf("-c", config.toString(), "App.kt"))
        assertEquals(0, result.exitCode)
        assertEquals("all=true\n", result.stdout)
    }

    @Test
    fun `output file is written and a summary goes to stderr`() {
        val config = tempFile(ONE_FILTER_CONFIG)
        val output = Files.createTempFile("pathfilter-test-out", ".env")
        val result = runCliCaptured(listOf("-c", config.toString(), "-o", output.toString(), "App.kt"))
        assertEquals(0, result.exitCode)
        assertEquals("", result.stdout)
        assertEquals("all=true\n", Files.readString(output))
        assertTrue(result.stderr.contains("matched: all"))
    }

    @Test
    fun `files-from is merged with positional args and de-duplicated`() {
        val config = tempFile(ONE_FILTER_CONFIG)
        val changedFiles = tempFile("App.kt\nREADME.md\n")
        val result = runCliCaptured(
            listOf("-c", config.toString(), "--files-from", changedFiles.toString(), "App.kt"),
        )
        assertEquals(0, result.exitCode)
        assertEquals("all=true\n", result.stdout)
    }

    @Test
    fun `stdin paths are read when --stdin is passed`() {
        val config = tempFile(ONE_FILTER_CONFIG)
        val result = runCliCaptured(
            listOf("-c", config.toString(), "--stdin"),
            stdin = "App.kt\n",
        )
        assertEquals(0, result.exitCode)
        assertEquals("all=true\n", result.stdout)
    }
}

class RenderEnvTest {

    @Test
    fun `empty results render as an empty string`() {
        assertEquals("", renderEnv(emptyMap()))
    }

    @Test
    fun `results render one line per filter`() {
        assertEquals("a=true\nb=false\n", renderEnv(linkedMapOf("a" to true, "b" to false)))
    }
}
