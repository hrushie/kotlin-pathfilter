package com.pathfilter

import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

class ConfigError(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Loads the filters config: a top-level `filters:` mapping of filter name to
 * a list of glob patterns (or a single pattern string).
 *
 * The YAML parsing below only covers the subset this format needs — nested
 * mappings, sequences of scalars, quotes and comments. Not pulling in a YAML
 * library keeps the jar self-contained.
 */
fun loadFilters(path: Path): LinkedHashMap<String, List<String>> {
    val text = try {
        Files.readString(path)
    } catch (exc: NoSuchFileException) {
        throw ConfigError("configuration file not found: $path", exc)
    } catch (exc: IOException) {
        throw ConfigError("could not read configuration file $path: ${exc.message}", exc)
    }
    return parseFilters(text)
}

fun parseFilters(text: String): LinkedHashMap<String, List<String>> {
    val data = parseYaml(text) ?: throw ConfigError("configuration is empty")
    if (data !is Map<*, *>) {
        throw ConfigError("top-level configuration must be a mapping with a 'filters' key")
    }

    val filters = data["filters"]
        ?: throw ConfigError("configuration must contain a top-level 'filters' key")
    if (filters !is Map<*, *>) {
        throw ConfigError("'filters' must be a mapping of name -> list of patterns")
    }
    if (filters.isEmpty()) {
        throw ConfigError("'filters' must define at least one filter")
    }

    val result = LinkedHashMap<String, List<String>>()
    for ((rawName, rawPatterns) in filters) {
        val name = rawName.toString()
        val patterns = if (rawPatterns is String) listOf(rawPatterns) else rawPatterns
        if (patterns !is List<*>) {
            throw ConfigError("filter '$name' must be a list of glob patterns")
        }
        val cleaned = patterns.map { pattern ->
            if (pattern == null || (pattern is String && pattern.isBlank())) {
                throw ConfigError("filter '$name' contains an empty pattern")
            }
            pattern as? String ?: throw ConfigError("filter '$name' contains a non-string pattern: $pattern")
        }
        if (cleaned.isEmpty()) {
            throw ConfigError("filter '$name' must define at least one pattern")
        }
        result[name] = cleaned
    }
    return result
}

private data class Line(val indent: Int, val content: String, val lineno: Int)

private fun parseYaml(text: String): Any? {
    val lines = ArrayList<Line>()
    text.split("\n").forEachIndexed { i, raw ->
        val lineno = i + 1
        val content = stripComment(raw.removeSuffix("\r"))
        if (content.isBlank()) return@forEachIndexed
        val stripped = content.trimStart(' ', '\t')
        val leading = content.substring(0, content.length - stripped.length)
        if ('\t' in leading) {
            throw ConfigError("line $lineno: tab characters are not allowed in indentation")
        }
        lines.add(Line(leading.length, stripped.trimEnd(), lineno))
    }

    if (lines.isEmpty()) return null

    val (value, pos) = parseNode(lines, 0)
    if (pos != lines.size) {
        throw ConfigError("line ${lines[pos].lineno}: unexpected indentation")
    }
    return value
}

private fun parseNode(lines: List<Line>, pos: Int): Pair<Any?, Int> =
    if (lines[pos].content.startsWith("-")) {
        parseSequence(lines, pos, lines[pos].indent)
    } else {
        parseMapping(lines, pos, lines[pos].indent)
    }

private fun parseSequence(lines: List<Line>, startPos: Int, indent: Int): Pair<List<Any?>, Int> {
    val items = ArrayList<Any?>()
    var pos = startPos
    while (pos < lines.size) {
        val (curIndent, content, _) = lines[pos]
        if (curIndent != indent || !content.startsWith("-")) break
        val item = content.substring(1).trim()
        if (item.isEmpty()) {
            // A bare '-' introduces a nested block if the next line is indented
            // deeper; otherwise it's an empty (null) item.
            if (pos + 1 < lines.size && lines[pos + 1].indent > indent) {
                val (child, newPos) = parseNode(lines, pos + 1)
                items.add(child)
                pos = newPos
            } else {
                items.add(null)
                pos++
            }
        } else {
            items.add(unquote(item))
            pos++
        }
    }
    return items to pos
}

private fun parseMapping(lines: List<Line>, startPos: Int, indent: Int): Pair<LinkedHashMap<String, Any?>, Int> {
    val mapping = LinkedHashMap<String, Any?>()
    var pos = startPos
    while (pos < lines.size) {
        val (curIndent, content, lineno) = lines[pos]
        if (curIndent != indent || content.startsWith("-")) break
        val colon = content.indexOf(':')
        if (colon < 0) {
            throw ConfigError("line $lineno: expected 'key:' but found '$content'")
        }
        val key = unquote(content.substring(0, colon).trim())
        val rest = content.substring(colon + 1).trim()
        when {
            rest.isNotEmpty() -> {
                mapping[key] = unquote(rest)
                pos++
            }
            pos + 1 < lines.size && lines[pos + 1].indent > indent -> {
                val (value, newPos) = parseNode(lines, pos + 1)
                mapping[key] = value
                pos = newPos
            }
            else -> {
                mapping[key] = null
                pos++
            }
        }
    }
    return mapping to pos
}

// Drop a trailing '#' comment, but not a '#' inside a quoted scalar.
private fun stripComment(line: String): String {
    val out = StringBuilder()
    var quote: Char? = null
    for (i in line.indices) {
        val c = line[i]
        when {
            quote != null -> {
                out.append(c)
                if (c == quote) quote = null
            }
            c == '"' || c == '\'' -> {
                quote = c
                out.append(c)
            }
            c == '#' && (i == 0 || line[i - 1] == ' ' || line[i - 1] == '\t') -> return out.toString()
            else -> out.append(c)
        }
    }
    return out.toString()
}

private fun unquote(value: String): String {
    val trimmed = value.trim()
    if (trimmed.length >= 2 && trimmed.first() == trimmed.last() &&
        (trimmed.first() == '"' || trimmed.first() == '\'')
    ) {
        return trimmed.substring(1, trimmed.length - 1)
    }
    return trimmed
}
