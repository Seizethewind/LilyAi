package ai.lily.app.lib.editor

/** What a stretch of code is, so the editor can colour it. */
enum class TokenKind { KEYWORD, TYPE, STRING, COMMENT, NUMBER, LITERAL, TAG, ATTRIBUTE, PROPERTY, HEADING }

/** [start] is included, [end] is not. Spans come in text order and never overlap. */
data class Span(val start: Int, val end: Int, val kind: TokenKind)

/** A language the editor can colour. [lineComment] is what "toggle comment" puts in front of a line. */
enum class Lang(val label: String, val lineComment: String?) {
    PLAIN("Plain text", null),
    KOTLIN("Kotlin", "//"),
    JAVA("Java", "//"),
    JAVASCRIPT("JavaScript", "//"),
    TYPESCRIPT("TypeScript", "//"),
    PYTHON("Python", "#"),
    C("C", "//"),
    CPP("C++", "//"),
    CSHARP("C#", "//"),
    GO("Go", "//"),
    RUST("Rust", "//"),
    SWIFT("Swift", "//"),
    DART("Dart", "//"),
    PHP("PHP", "//"),
    RUBY("Ruby", "#"),
    LUA("Lua", "--"),
    SHELL("Shell", "#"),
    SQL("SQL", "--"),
    HTML("HTML", null),
    XML("XML", null),
    CSS("CSS", null),
    JSON("JSON", null),
    YAML("YAML", "#"),
    MARKDOWN("Markdown", null),
}

/** Picks the language from the file name. Unknown files are plain text. */
fun languageFor(path: String): Lang {
    val name = path.substringAfterLast('/').lowercase()
    return when (name.substringAfterLast('.', "")) {
        "kt", "kts" -> Lang.KOTLIN
        "java", "gradle", "groovy" -> Lang.JAVA
        "js", "mjs", "cjs", "jsx" -> Lang.JAVASCRIPT
        "ts", "tsx", "mts", "cts" -> Lang.TYPESCRIPT
        "py", "pyw" -> Lang.PYTHON
        "c", "h" -> Lang.C
        "cpp", "cc", "cxx", "hpp", "hh", "hxx" -> Lang.CPP
        "cs" -> Lang.CSHARP
        "go" -> Lang.GO
        "rs" -> Lang.RUST
        "swift" -> Lang.SWIFT
        "dart" -> Lang.DART
        "php" -> Lang.PHP
        "rb" -> Lang.RUBY
        "lua" -> Lang.LUA
        "sh", "bash", "zsh", "ksh", "toml", "properties", "env", "gitignore", "conf", "cfg" -> Lang.SHELL
        "sql" -> Lang.SQL
        "html", "htm", "xhtml", "vue" -> Lang.HTML
        "xml", "svg", "xsd", "xsl", "plist" -> Lang.XML
        "css", "scss", "less" -> Lang.CSS
        "json", "jsonc", "json5" -> Lang.JSON
        "yml", "yaml" -> Lang.YAML
        "md", "markdown" -> Lang.MARKDOWN
        else -> if (name == "gradlew") Lang.SHELL else Lang.PLAIN
    }
}

/**
 * Colours [text] in one pass. This is a lightweight lexer, not a parser: it finds comments, strings,
 * numbers, keywords and a few language specific things, and is happy to be a little wrong on odd code.
 */
fun highlight(text: String, lang: Lang): List<Span> = when (lang) {
    Lang.PLAIN -> emptyList()
    Lang.HTML -> lexMarkup(text, html = true)
    Lang.XML -> lexMarkup(text, html = false)
    Lang.MARKDOWN -> lexMarkdown(text)
    Lang.YAML -> lexYaml(text)
    Lang.JSON -> lexCode(text, JSON_RULES, jsonKeys = true)
    else -> lexCode(text, rulesFor(lang))
}

private const val MAX_SPANS = 80_000

private fun words(s: String): Set<String> = s.split(' ', '\n', '\t').filter { it.isNotEmpty() }.toSet()

private class Rules(
    val keywords: Set<String>,
    val types: Set<String> = emptySet(),
    val literals: Set<String> = words("true false null"),
    val lineComments: List<String> = listOf("//"),
    val block: Pair<String, String>? = Pair("/*", "*/"),
    val quotes: String = "\"'",
    val triple: Boolean = false,
    val ignoreCase: Boolean = false,
    val capitalizedTypes: Boolean = true,
    val annotations: Boolean = false,
    val dollarVars: Boolean = false,
    val hexColors: Boolean = false,
    val cssProps: Boolean = false,
    val atKeywords: Boolean = false,
    val directives: Boolean = false,
)

private val KOTLIN_RULES = Rules(
    keywords = words(
        "package import class interface object fun val var if else when for while do return break continue throw try " +
            "catch finally is as in out by init constructor this super override open abstract final sealed data enum " +
            "companion private protected public internal inner lateinit const suspend inline noinline crossinline " +
            "reified typealias vararg where get set operator infix tailrec external annotation expect actual",
    ),
    types = words("Int Long Short Byte Float Double Boolean Char String Unit Any Nothing List Map Set Array"),
    annotations = true,
)

private val JAVA_RULES = Rules(
    keywords = words(
        "package import class interface enum extends implements new if else switch case default for while do return " +
            "break continue throw throws try catch finally this super static final abstract public private protected " +
            "synchronized volatile transient native instanceof void int long short byte float double boolean char var " +
            "record sealed permits yield assert",
    ),
    annotations = true,
)

private val JS_KEYWORDS = words(
    "var let const function return if else switch case default for while do break continue throw try catch finally " +
        "new delete typeof instanceof in of class extends super this import export from as async await yield static " +
        "get set void with debugger",
)

private val JS_RULES = Rules(
    keywords = JS_KEYWORDS,
    literals = words("true false null undefined NaN Infinity"),
    quotes = "\"'`",
)

private val TS_RULES = Rules(
    keywords = JS_KEYWORDS + words(
        "interface type enum implements namespace declare abstract readonly private public protected keyof infer is satisfies",
    ),
    types = words("string number boolean any unknown never object symbol bigint"),
    literals = words("true false null undefined NaN Infinity"),
    quotes = "\"'`",
    annotations = true,
)

private val PYTHON_RULES = Rules(
    keywords = words(
        "and as assert async await break class continue def del elif else except finally for from global if import in " +
            "is lambda nonlocal not or pass raise return try while with yield match case self cls",
    ),
    types = words("int str float bool list dict set tuple bytes object"),
    literals = words("True False None"),
    lineComments = listOf("#"),
    block = null,
    triple = true,
    annotations = true,
)

private val CPP_RULES = Rules(
    keywords = words(
        "alignas auto break case catch class const constexpr continue default delete do else enum explicit export extern " +
            "for friend goto if inline namespace new noexcept operator private protected public register return sizeof " +
            "static static_assert struct switch template this throw try typedef typename union using virtual volatile " +
            "while override final",
    ),
    types = words("void int long short char float double bool unsigned signed size_t string vector map"),
    literals = words("true false NULL nullptr"),
    directives = true,
)

private val CSHARP_RULES = Rules(
    keywords = words(
        "abstract as base break case catch checked class const continue default delegate do else enum event explicit " +
            "extern finally fixed for foreach goto if implicit in interface internal is lock namespace new operator out " +
            "override params private protected public readonly ref return sealed sizeof stackalloc static struct switch " +
            "this throw try typeof unchecked unsafe using virtual volatile while var async await get set record init",
    ),
    types = words("bool byte char decimal double float int long object sbyte short string uint ulong ushort void dynamic"),
    directives = true,
)

private val GO_RULES = Rules(
    keywords = words(
        "break case chan const continue default defer else fallthrough for func go goto if import interface map package " +
            "range return select struct switch type var",
    ),
    types = words(
        "int int8 int16 int32 int64 uint uint8 uint16 uint32 uint64 float32 float64 string bool byte rune error any",
    ),
    literals = words("true false nil iota"),
    quotes = "\"'`",
)

private val RUST_RULES = Rules(
    keywords = words(
        "as async await break const continue crate dyn else enum extern fn for if impl in let loop match mod move mut " +
            "pub ref return self Self static struct super trait type unsafe use where while",
    ),
    types = words("i8 i16 i32 i64 i128 isize u8 u16 u32 u64 u128 usize f32 f64 bool char str String Vec Option Result Box"),
    literals = words("true false"),
    quotes = "\"",
)

private val SWIFT_RULES = Rules(
    keywords = words(
        "associatedtype class deinit enum extension fileprivate func import init inout internal let open operator " +
            "private protocol public rethrows static struct subscript typealias var break case continue default defer " +
            "do else fallthrough for guard if in repeat return switch where while as Any catch is super self Self " +
            "throw throws try async await actor",
    ),
    literals = words("true false nil"),
    annotations = true,
)

private val DART_RULES = Rules(
    keywords = words(
        "abstract as assert async await break case catch class const continue covariant default deferred do dynamic " +
            "else enum export extends extension external factory final finally for Function get hide if implements " +
            "import in interface is late library mixin new on operator part required rethrow return set show static " +
            "super switch sync this throw try typedef var void while with yield",
    ),
    types = words("int double num String bool List Map Set"),
    annotations = true,
)

private val PHP_RULES = Rules(
    keywords = words(
        "abstract and array as break callable case catch class clone const continue declare default do echo else " +
            "elseif empty extends final finally fn for foreach function global goto if implements include include_once " +
            "instanceof interface isset list match namespace new or print private protected public require " +
            "require_once return static switch throw trait try unset use var while xor yield",
    ),
    lineComments = listOf("//", "#"),
    ignoreCase = true,
    dollarVars = true,
)

private val RUBY_RULES = Rules(
    keywords = words(
        "alias and begin break case class def do else elsif end ensure for if in module next not or redo rescue retry " +
            "return self super then undef unless until when while yield",
    ),
    literals = words("true false nil"),
    lineComments = listOf("#"),
    block = null,
)

private val LUA_RULES = Rules(
    keywords = words("and break do else elseif end for function goto if in local not or repeat return then until while"),
    literals = words("true false nil"),
    lineComments = listOf("--"),
    block = Pair("--[[", "]]"),
    capitalizedTypes = false,
)

private val SHELL_RULES = Rules(
    keywords = words(
        "if then else elif fi case esac for select while until do done in function time return exit break continue " +
            "local export readonly declare unset source alias echo cd set shift trap eval exec test",
    ),
    literals = emptySet(),
    lineComments = listOf("#"),
    block = null,
    capitalizedTypes = false,
    dollarVars = true,
)

private val SQL_RULES = Rules(
    keywords = words(
        "select from where insert into values update set delete create alter drop table index view database schema " +
            "join inner outer left right full cross on as and or not in is null like between exists distinct group by " +
            "order having limit offset union all case when then else end primary key foreign references default " +
            "unique check constraint asc desc with returning begin commit rollback transaction truncate grant revoke",
    ),
    types = words(
        "int integer bigint smallint serial varchar char text boolean bool date time timestamp datetime float double " +
            "decimal numeric json jsonb uuid blob",
    ),
    lineComments = listOf("--"),
    ignoreCase = true,
    capitalizedTypes = false,
)

private val JSON_RULES = Rules(
    keywords = emptySet(),
    lineComments = emptyList(),
    block = null,
    quotes = "\"",
    capitalizedTypes = false,
)

private val CSS_RULES = Rules(
    keywords = emptySet(),
    literals = emptySet(),
    lineComments = emptyList(),
    capitalizedTypes = false,
    hexColors = true,
    cssProps = true,
    atKeywords = true,
)

private val PLAIN_RULES = Rules(keywords = emptySet(), literals = emptySet(), lineComments = emptyList(), block = null)

private fun rulesFor(lang: Lang): Rules = when (lang) {
    Lang.KOTLIN -> KOTLIN_RULES
    Lang.JAVA -> JAVA_RULES
    Lang.JAVASCRIPT -> JS_RULES
    Lang.TYPESCRIPT -> TS_RULES
    Lang.PYTHON -> PYTHON_RULES
    Lang.C, Lang.CPP -> CPP_RULES
    Lang.CSHARP -> CSHARP_RULES
    Lang.GO -> GO_RULES
    Lang.RUST -> RUST_RULES
    Lang.SWIFT -> SWIFT_RULES
    Lang.DART -> DART_RULES
    Lang.PHP -> PHP_RULES
    Lang.RUBY -> RUBY_RULES
    Lang.LUA -> LUA_RULES
    Lang.SHELL -> SHELL_RULES
    Lang.SQL -> SQL_RULES
    Lang.CSS -> CSS_RULES
    else -> PLAIN_RULES
}

/** The next character that is not a space, tab or carriage return, or NUL at the end. */
private fun nextNonSpace(text: String, from: Int): Char {
    var i = from
    while (i < text.length && (text[i] == ' ' || text[i] == '\t' || text[i] == '\r')) i++
    return if (i < text.length) text[i] else '\u0000'
}

/** The end of a string that starts at [start]. A string stops at the end of its line unless it is a backtick or triple quoted one. */
private fun scanString(t: String, start: Int, q: Char, triple: Boolean): Int {
    val n = t.length
    if (triple && t.startsWith("$q$q$q", start)) {
        val close = t.indexOf("$q$q$q", start + 3)
        return if (close < 0) n else close + 3
    }
    val multiline = q == '`'
    var i = start + 1
    while (i < n) {
        val ch = t[i]
        if (ch == '\\') {
            i += 2
            continue
        }
        if (ch == q) return i + 1
        if (ch == '\n' && !multiline) return i
        i++
    }
    return n
}

private fun lexCode(text: String, r: Rules, jsonKeys: Boolean = false): List<Span> {
    val out = ArrayList<Span>()
    val n = text.length
    var i = 0
    var depth = 0
    while (i < n && out.size < MAX_SPANS) {
        val c = text[i]
        if (c <= ' ') {
            i++
            continue
        }
        val block = r.block
        if (block != null && text.startsWith(block.first, i)) {
            val close = text.indexOf(block.second, i + block.first.length)
            val e = if (close < 0) n else close + block.second.length
            out += Span(i, e, TokenKind.COMMENT)
            i = e
            continue
        }
        val lineComment = r.lineComments.firstOrNull { text.startsWith(it, i) }
        if (lineComment != null) {
            var e = text.indexOf('\n', i)
            if (e < 0) e = n
            out += Span(i, e, TokenKind.COMMENT)
            i = e
            continue
        }
        if (r.quotes.indexOf(c) >= 0) {
            val e = scanString(text, i, c, r.triple)
            val kind = if (jsonKeys && nextNonSpace(text, e) == ':') TokenKind.PROPERTY else TokenKind.STRING
            out += Span(i, e, kind)
            i = e
            continue
        }
        if (r.directives && c == '#') {
            var e = i + 1
            while (e < n && (text[e] == ' ' || text[e] == '\t')) e++
            while (e < n && text[e].isLetter()) e++
            out += Span(i, e, TokenKind.KEYWORD)
            i = e
            continue
        }
        if (r.hexColors && c == '#' && i + 1 < n && text[i + 1].isLetterOrDigit()) {
            var e = i + 1
            while (e < n && text[e].isLetterOrDigit()) e++
            out += Span(i, e, TokenKind.NUMBER)
            i = e
            continue
        }
        if (c == '@' && (r.annotations || r.atKeywords) && i + 1 < n && text[i + 1].isLetter()) {
            var e = i + 1
            while (e < n && (text[e].isLetterOrDigit() || text[e] == '_')) e++
            out += Span(i, e, if (r.atKeywords) TokenKind.KEYWORD else TokenKind.TYPE)
            i = e
            continue
        }
        if (c.isDigit()) {
            var e = i + 1
            while (e < n && (text[e].isLetterOrDigit() || text[e] == '_' ||
                    (text[e] == '.' && e + 1 < n && text[e + 1].isDigit()))
            ) e++
            out += Span(i, e, TokenKind.NUMBER)
            i = e
            continue
        }
        if (c.isLetter() || c == '_' || c == '$') {
            var e = i + 1
            while (e < n) {
                val ch = text[e]
                val part = ch.isLetterOrDigit() || ch == '_' || (ch == '$' && !r.dollarVars) || (r.cssProps && ch == '-')
                if (!part) break
                e++
            }
            val word = text.substring(i, e)
            val key = if (r.ignoreCase) word.lowercase() else word
            val kind = when {
                c == '$' && r.dollarVars -> TokenKind.PROPERTY
                key in r.keywords -> TokenKind.KEYWORD
                key in r.literals -> TokenKind.LITERAL
                key in r.types -> TokenKind.TYPE
                r.cssProps && depth > 0 && nextNonSpace(text, e) == ':' -> TokenKind.PROPERTY
                r.capitalizedTypes && c.isUpperCase() && word.length > 1 && word.any { it.isLowerCase() } -> TokenKind.TYPE
                else -> null
            }
            if (kind != null) out += Span(i, e, kind)
            i = e
            continue
        }
        if (r.cssProps) {
            if (c == '{') depth++ else if (c == '}' && depth > 0) depth--
        }
        i++
    }
    return out
}

/** HTML and XML: tags, attributes, strings and comments. In HTML the code inside script and style tags is coloured too. */
private fun lexMarkup(text: String, html: Boolean): List<Span> {
    val out = ArrayList<Span>()
    val n = text.length
    var i = 0
    while (i < n && out.size < MAX_SPANS) {
        val lt = text.indexOf('<', i)
        if (lt < 0) break
        i = lt
        if (text.startsWith("<!--", i)) {
            val close = text.indexOf("-->", i + 4)
            val e = if (close < 0) n else close + 3
            out += Span(i, e, TokenKind.COMMENT)
            i = e
            continue
        }
        if (text.startsWith("<![CDATA[", i)) {
            val close = text.indexOf("]]>", i + 9)
            val e = if (close < 0) n else close + 3
            out += Span(i, e, TokenKind.STRING)
            i = e
            continue
        }
        if (text.startsWith("<?", i)) {
            val close = text.indexOf("?>", i + 2)
            val e = if (close < 0) n else close + 2
            out += Span(i, e, TokenKind.COMMENT)
            i = e
            continue
        }
        if (text.startsWith("<!", i)) {
            val close = text.indexOf('>', i)
            val e = if (close < 0) n else close + 1
            out += Span(i, e, TokenKind.KEYWORD)
            i = e
            continue
        }
        var p = i + 1
        val closing = p < n && text[p] == '/'
        if (closing) p++
        val nameStart = p
        while (p < n && (text[p].isLetterOrDigit() || text[p] == ':' || text[p] == '-' || text[p] == '_' || text[p] == '.')) p++
        if (p == nameStart) {
            i++
            continue
        }
        out += Span(i, p, TokenKind.TAG)
        val tagName = text.substring(nameStart, p).lowercase()
        while (p < n) {
            val ch = text[p]
            if (ch == '>') {
                out += Span(p, p + 1, TokenKind.TAG)
                p++
                break
            }
            if (ch == '/' && p + 1 < n && text[p + 1] == '>') {
                out += Span(p, p + 2, TokenKind.TAG)
                p += 2
                break
            }
            if (ch == '"' || ch == '\'') {
                val close = text.indexOf(ch, p + 1)
                val e = if (close < 0) n else close + 1
                out += Span(p, e, TokenKind.STRING)
                p = e
                continue
            }
            if (ch.isLetter() || ch == '_' || ch == ':' || ch == '@') {
                var e = p + 1
                while (e < n && !text[e].isWhitespace() && "=>/\"'".indexOf(text[e]) < 0) e++
                out += Span(p, e, TokenKind.ATTRIBUTE)
                p = e
                continue
            }
            p++
        }
        i = p
        if (html && !closing && (tagName == "script" || tagName == "style")) {
            val close = text.indexOf("</$tagName", i, ignoreCase = true)
            val e = if (close < 0) n else close
            if (e > i) {
                val inner = lexCode(text.substring(i, e), if (tagName == "script") JS_RULES else CSS_RULES)
                for (s in inner) out += Span(s.start + i, s.end + i, s.kind)
            }
            i = e
        }
    }
    return out
}

private fun lexMarkdown(text: String): List<Span> {
    val out = ArrayList<Span>()
    val n = text.length
    var i = 0
    var inFence = false
    while (i < n && out.size < MAX_SPANS) {
        var e = text.indexOf('\n', i)
        if (e < 0) e = n
        val line = text.substring(i, e)
        val t = line.trimStart()
        val hashes = t.takeWhile { it == '#' }.length
        when {
            t.startsWith("```") || t.startsWith("~~~") -> {
                out += Span(i, e, TokenKind.STRING)
                inFence = !inFence
            }
            inFence -> if (e > i) out += Span(i, e, TokenKind.STRING)
            hashes in 1..6 && (t.length == hashes || t[hashes] == ' ') -> out += Span(i, e, TokenKind.HEADING)
            t.startsWith(">") -> out += Span(i, e, TokenKind.COMMENT)
            else -> {
                var k = line.indexOf('`')
                while (k >= 0) {
                    val close = line.indexOf('`', k + 1)
                    if (close < 0) break
                    out += Span(i + k, i + close + 1, TokenKind.STRING)
                    k = line.indexOf('`', close + 1)
                }
            }
        }
        i = e + 1
    }
    return out
}

private val YAML_KEY = Regex("""^(\s*(?:-\s+)*)("[^"]*"|'[^']*'|[^\s:#'"\-\[\]{},&*!|>%@`][^:#]*?)\s*:(?=\s|$)""")
private val YAML_NUMBER = Regex("-?\\d+(\\.\\d+)?")
private val YAML_LITERALS = setOf("true", "false", "null", "yes", "no", "on", "off", "~")

private fun lexYaml(text: String): List<Span> {
    val out = ArrayList<Span>()
    val n = text.length
    var i = 0
    while (i < n && out.size < MAX_SPANS) {
        var e = text.indexOf('\n', i)
        if (e < 0) e = n
        val line = text.substring(i, e)
        // Where a comment starts, ignoring a # inside quotes.
        var cut = line.length
        var quote = '\u0000'
        var k = 0
        while (k < line.length) {
            val ch = line[k]
            if (quote != '\u0000') {
                if (ch == '\\' && quote == '"') k++ else if (ch == quote) quote = '\u0000'
            } else if (ch == '"' || ch == '\'') {
                quote = ch
            } else if (ch == '#' && (k == 0 || line[k - 1].isWhitespace())) {
                cut = k
                break
            }
            k++
        }
        val code = line.substring(0, cut)
        var from = 0
        if (code.startsWith("---") || code.startsWith("...")) {
            out += Span(i, i + 3, TokenKind.KEYWORD)
            from = 3
        } else {
            val m = YAML_KEY.find(code)
            val key = m?.groups?.get(2)
            if (m != null && key != null) {
                out += Span(i + key.range.first, i + key.range.last + 1, TokenKind.PROPERTY)
                from = m.range.last + 1
            }
        }
        scanYamlValue(code, from, i, out)
        if (cut < line.length) out += Span(i + cut, e, TokenKind.COMMENT)
        i = e + 1
    }
    return out
}

private fun scanYamlValue(code: String, from: Int, base: Int, out: MutableList<Span>) {
    val n = code.length
    var k = from
    while (k < n) {
        val ch = code[k]
        if (ch.isWhitespace() || ",[]{}".indexOf(ch) >= 0) {
            k++
            continue
        }
        if (ch == '"' || ch == '\'') {
            val e = scanString(code, k, ch, false)
            out += Span(base + k, base + e, TokenKind.STRING)
            k = e
            continue
        }
        var e = k
        while (e < n && !code[e].isWhitespace() && ",[]{}".indexOf(code[e]) < 0) e++
        val w = code.substring(k, e)
        val kind = when {
            w.lowercase() in YAML_LITERALS -> TokenKind.LITERAL
            YAML_NUMBER.matches(w) -> TokenKind.NUMBER
            w.length > 1 && (w[0] == '&' || w[0] == '*') -> TokenKind.TYPE
            else -> null
        }
        if (kind != null) out += Span(base + k, base + e, kind)
        k = e
    }
}
