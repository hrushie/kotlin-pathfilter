package com.pathfilter

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.system.exitProcess

const val VERSION = "1.0.0"

private const val PROG = "pathfilter"

private const val USAGE =
    "usage: pathfilter -c FILE [-o FILE] [--stdin] [--files-from FILE] [--version] [-h] [PATH ...]"

private val HELP = """
$USAGE

Decide which CI/CD filters match a set of changed files, based on named glob
filters defined in a YAML file. Emits results in env-file (key=value) format.

positional arguments:
  PATH                 changed file paths (may be combined with --stdin/--files-from)

options:
  -c, --config FILE    path to the filters YAML configuration file (required)
  -o, --output FILE    write the env file here (default: stdout)
      --files-from FILE read newline-delimited changed paths from this file ('-' for stdin)
      --stdin          read newline-delimited changed paths from stdin
      --version        show program's version number and exit
  -h, --help           show this help message and exit

examples:
  pathfilter -c filters.yaml app/src/App.kt README.md
  git diff --name-only origin/main | pathfilter -c filters.yaml --stdin -o changes.env
""".trimIndent()

fun main(args: Array<String>) {
    exitProcess(runCli(args))
}

private class ExitRequest(val code: Int) : Exception()

private class UsageError(message: String) : Exception(message)

private class Options {
    var config: String? = null
    var output: String? = null
    var filesFrom: String? = null
    var readStdin = false
    val files = mutableListOf<String>()
}

private fun parseArgs(argv: Array<String>): Options {
    val opts = Options()
    var i = 0

    fun optionValue(name: String, inlineValue: String?): String {
        if (inlineValue != null) return inlineValue
        if (i + 1 >= argv.size) throw UsageError("argument $name: expected one argument")
        return argv[++i]
    }

    while (i < argv.size) {
        val arg = argv[i]
        val eq = if (arg.startsWith("--")) arg.indexOf('=') else -1
        val name = if (eq >= 0) arg.substring(0, eq) else arg
        val inline = if (eq >= 0) arg.substring(eq + 1) else null

        when {
            name == "-c" || name == "--config" -> opts.config = optionValue("-c/--config", inline)
            name == "-o" || name == "--output" -> opts.output = optionValue("-o/--output", inline)
            name == "--files-from" -> opts.filesFrom = optionValue("--files-from", inline)
            name == "--stdin" -> opts.readStdin = true
            name == "--version" -> {
                println("$PROG $VERSION")
                throw ExitRequest(0)
            }
            name == "-h" || name == "--help" -> {
                println(HELP)
                throw ExitRequest(0)
            }
            arg == "--" -> {
                opts.files.addAll(argv.drop(i + 1))
                return opts
            }
            arg.startsWith("-") && arg != "-" -> throw UsageError("unrecognized arguments: $arg")
            else -> opts.files.add(arg)
        }
        i++
    }

    if (opts.config == null) {
        throw UsageError("the following arguments are required: -c/--config")
    }
    return opts
}

private fun nonBlankLines(text: String): List<String> =
    text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

private fun collectChangedFiles(opts: Options): List<String> {
    val files = ArrayList(opts.files)

    opts.filesFrom?.let { source ->
        if (source == "-") {
            files.addAll(nonBlankLines(System.`in`.readBytes().decodeToString()))
        } else {
            val text = try {
                Files.readString(Paths.get(source))
            } catch (exc: IOException) {
                throw ConfigError("could not read --files-from $source: ${exc.message}", exc)
            }
            files.addAll(nonBlankLines(text))
        }
    }

    if (opts.readStdin) {
        files.addAll(nonBlankLines(System.`in`.readBytes().decodeToString()))
    }

    return files.distinct()
}

fun renderEnv(results: Map<String, Boolean>): String {
    if (results.isEmpty()) return ""
    return results.entries.joinToString("\n") { (name, matched) -> "$name=$matched" } + "\n"
}

fun runCli(argv: Array<String>): Int {
    val opts = try {
        parseArgs(argv)
    } catch (exit: ExitRequest) {
        return exit.code
    } catch (exc: UsageError) {
        System.err.println(USAGE)
        System.err.println("$PROG: error: ${exc.message}")
        return 2
    }

    val filters: Map<String, List<String>>
    val changedFiles: List<String>
    try {
        filters = loadFilters(Paths.get(opts.config!!))
        changedFiles = collectChangedFiles(opts)
    } catch (exc: ConfigError) {
        System.err.println("$PROG: error: ${exc.message}")
        return 2
    }

    val results = evaluateFilters(filters, changedFiles)
    val output = renderEnv(results)

    val outputPath = opts.output
    if (outputPath == null) {
        print(output)
        return 0
    }

    try {
        Files.writeString(Paths.get(outputPath), output)
    } catch (exc: IOException) {
        System.err.println("$PROG: error: could not write $outputPath: ${exc.message}")
        return 2
    }
    // Keep a summary on stderr so CI logs still show the result.
    val matched = results.filterValues { it }.keys.sorted()
    System.err.println(
        "$PROG: evaluated ${filters.size} filter(s) against ${changedFiles.size} " +
            "file(s); matched: ${if (matched.isEmpty()) "(none)" else matched.joinToString(", ")}",
    )
    return 0
}
