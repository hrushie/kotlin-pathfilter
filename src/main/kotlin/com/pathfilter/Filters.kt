package com.pathfilter

/**
 * A named filter: a list of glob patterns where a leading `!` marks an
 * exclusion. A path matches the filter when it matches at least one include
 * pattern and none of the excludes.
 *
 * Glob semantics follow GitHub's paths-filter: `*` and `?` stay within a
 * single path segment, `**` may cross segments, and a leading globstar
 * ("**" + "/") also matches files at the top level.
 */
class Filter(val name: String, patterns: List<String>) {
    private val includes = mutableListOf<Regex>()
    private val excludes = mutableListOf<Regex>()

    init {
        for (pattern in patterns) {
            if (pattern.startsWith("!")) {
                excludes.add(compileGlob(pattern.substring(1)))
            } else {
                includes.add(compileGlob(pattern))
            }
        }
    }

    fun matches(path: String): Boolean {
        val normalized = normalizePath(path)
        return includes.any { it.matches(normalized) } && excludes.none { it.matches(normalized) }
    }

    fun matchesAny(paths: List<String>): Boolean = paths.any { matches(it) }
}

/** Evaluate every filter, preserving the declaration order from the config. */
fun evaluateFilters(
    filters: Map<String, List<String>>,
    changedFiles: List<String>,
): LinkedHashMap<String, Boolean> {
    val results = LinkedHashMap<String, Boolean>()
    for ((name, patterns) in filters) {
        results[name] = Filter(name, patterns).matchesAny(changedFiles)
    }
    return results
}

// "./app/App.kt", "app\App.kt" and "app/App.kt" should all match the same.
private fun normalizePath(path: String): String {
    var p = path.trim().replace('\\', '/')
    while (p.startsWith("./")) p = p.substring(2)
    return p
}

private val regexCache = HashMap<String, Regex>()

internal fun compileGlob(pattern: String): Regex =
    regexCache.getOrPut(pattern) { Regex(globToRegex(pattern)) }

internal fun globToRegex(pattern: String): String {
    val out = StringBuilder("^")
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        when {
            pattern.startsWith("**/", i) -> {
                // Zero or more leading segments, so "**/*.kt" also matches "App.kt".
                out.append("(?:.*/)?")
                i += 3
            }
            pattern.startsWith("**", i) -> {
                out.append(".*")
                i += 2
            }
            c == '*' -> {
                out.append("[^/]*")
                i++
            }
            c == '?' -> {
                out.append("[^/]")
                i++
            }
            else -> {
                if (c.isLetterOrDigit() || c == '_' || c == '/') out.append(c) else out.append('\\').append(c)
                i++
            }
        }
    }
    return out.append('$').toString()
}
