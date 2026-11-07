package ai.lily.app.codeindex

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A short outline of a project: one line per source file naming the classes and functions it declares, so a model can see where
 * things live without reading every file. ([WorkspaceIndex.relatedTo] only finds files that share words with the request.)
 *
 * Symbols come from one regular expression per language, applied to declarations at most [MAX_INDENT] columns deep: top-level
 * declarations and the members of a class are listed, code nested inside a function is not. Known: Kotlin, Java (types only),
 * JavaScript and TypeScript, Python, Go and Rust. Other files, and files that declare nothing, are left out. At most
 * [MAX_SYMBOLS_PER_FILE] symbols are listed per file.
 *
 * A file is read again only when its stamp changes, so asking again for an unchanged project reads nothing.
 *
 * shortcut: regular expressions, not a parser, so a declaration split over several lines or odd formatting can be missed; move to a real parser if outlines prove too thin.
 */
class ProjectMap(private val files: CodeFiles) {
    private class Entry(val stamp: FileStamp, val symbols: List<String>)

    private class Rule(val regex: Regex, val render: (MatchResult) -> String)

    private val mutex = Mutex()
    private val cache = HashMap<String, Entry>()

    /**
     * The outline, files in path order, as lines like `src/Parser.kt: class Parser; fun parseReply`. Files in [exclude] (the ones
     * whose full text is already shown) are skipped. It stops at [maxChars] and then ends with a line saying more files exist.
     * Empty when nothing declares a symbol. May throw if the project cannot be listed: see [WorkspaceIndex.outline].
     */
    suspend fun outline(maxChars: Int = DEFAULT_MAX_CHARS, exclude: Set<String> = emptySet()): String = mutex.withLock {
        val current = files.stamps()
        cache.keys.retainAll(current.files.keys)
        val lines = ArrayList<String>()
        var used = 0
        var cut = false
        for (path in current.files.keys.sorted()) {
            if (path in exclude) continue
            val rules = rulesFor(path) ?: continue
            val stamp = current.files.getValue(path)
            val cached = cache[path]
            val symbols = if (cached != null && cached.stamp == stamp) {
                cached.symbols
            } else {
                val text = files.read(path)
                val found = if (text == null || CodeFilter.looksGenerated(text)) emptyList<String>() else symbolsIn(text, rules)
                cache[path] = Entry(stamp, found)
                found
            }
            if (symbols.isEmpty()) continue
            val line = path + ": " + symbols.joinToString("; ")
            if (used + line.length + 1 > maxChars) {
                cut = true
                break
            }
            lines += line
            used += line.length + 1
        }
        if (cut) lines += MORE
        lines.joinToString("\n")
    }

    private fun rulesFor(path: String): List<Rule>? = RULES[path.substringAfterLast('.', "").lowercase()]

    private fun symbolsIn(text: String, rules: List<Rule>): List<String> {
        val out = ArrayList<String>()
        for (line in text.lineSequence()) {
            if (indentOf(line) > MAX_INDENT) continue
            for (rule in rules) {
                val m = rule.regex.find(line) ?: continue
                out += rule.render(m)
                break
            }
            if (out.size >= MAX_SYMBOLS_PER_FILE) break
        }
        return out
    }

    private fun indentOf(line: String): Int {
        var n = 0
        for (c in line) {
            if (c == ' ') n++ else if (c == '\t') n += 4 else break
        }
        return n
    }

    companion object {
        const val DEFAULT_MAX_CHARS = 6_000
        const val MAX_SYMBOLS_PER_FILE = 15
        const val MAX_INDENT = 4
        private const val MORE = "(more files not shown)"

        private val KOTLIN = listOf(
            Rule(Regex("""^\s*(?:(?:public|private|protected|internal|open|abstract|sealed|data|enum|annotation|inner|value|companion)\s+)*(class|interface|object)\s+([A-Za-z_]\w*)""")) {
                "${it.groupValues[1]} ${it.groupValues[2]}"
            },
            Rule(Regex("""^\s*(?:(?:public|private|protected|internal|open|abstract|override|suspend|inline|operator|infix|tailrec|external|final)\s+)*fun\s+(?:<[^>]*>\s*)?([A-Za-z_][\w.]*)\s*\(""")) {
                "fun ${it.groupValues[1]}"
            },
        )

        private val JAVA = listOf(
            Rule(Regex("""^\s*(?:(?:public|private|protected|static|final|abstract|sealed|strictfp)\s+)*(class|interface|enum|record)\s+([A-Za-z_]\w*)""")) {
                "${it.groupValues[1]} ${it.groupValues[2]}"
            },
        )

        private val SCRIPT = listOf(
            Rule(Regex("""^\s*(?:export\s+)?(?:default\s+)?(?:declare\s+)?(?:abstract\s+)?(?:async\s+)?(function\*?|class|interface|type|enum)\s+([A-Za-z_\$][\w\$]*)""")) {
                "${it.groupValues[1]} ${it.groupValues[2]}"
            },
            Rule(Regex("""^\s*(?:export\s+)?const\s+([A-Za-z_\$][\w\$]*)\s*=\s*(?:async\s*)?(?:\([^)]*\)|[A-Za-z_\$][\w\$]*)\s*=>""")) {
                "fn ${it.groupValues[1]}"
            },
        )

        private val PYTHON = listOf(
            Rule(Regex("""^\s*(?:async\s+)?(def|class)\s+([A-Za-z_]\w*)""")) {
                "${it.groupValues[1]} ${it.groupValues[2]}"
            },
        )

        private val GO = listOf(
            Rule(Regex("""^func\s+(?:\([^)]*\)\s*)?([A-Za-z_]\w*)""")) { "func ${it.groupValues[1]}" },
            Rule(Regex("""^type\s+([A-Za-z_]\w*)\s+(struct|interface)""")) { "${it.groupValues[2]} ${it.groupValues[1]}" },
        )

        private val RUST = listOf(
            Rule(Regex("""^\s*(?:pub(?:\([^)]*\))?\s+)?(?:async\s+)?(?:unsafe\s+)?(fn|struct|enum|trait|mod)\s+([A-Za-z_]\w*)""")) {
                "${it.groupValues[1]} ${it.groupValues[2]}"
            },
        )

        private val RULES: Map<String, List<Rule>> = mapOf(
            "kt" to KOTLIN, "kts" to KOTLIN,
            "java" to JAVA,
            "js" to SCRIPT, "jsx" to SCRIPT, "mjs" to SCRIPT, "cjs" to SCRIPT, "ts" to SCRIPT, "tsx" to SCRIPT,
            "py" to PYTHON,
            "go" to GO,
            "rs" to RUST,
        )
    }
}
