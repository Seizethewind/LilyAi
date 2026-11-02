package ai.lily.app.sandbox

import org.json.JSONObject

/** One command to run in the sandbox. [install] runs once before it (shared by checks with the same text). */
data class ProjectCheck(val label: String, val command: String, val install: String? = null)

enum class CheckSource { OVERRIDE, DETECTED, NONE }

data class DetectedChecks(val checks: List<ProjectCheck>, val source: CheckSource)

/**
 * Works out which checks a project has, from its files. Only the workspace root is looked at. A list of commands the
 * person wrote always replaces detection. Detection is a guess from file names and scripts, never from running anything.
 */
object CheckDetector {
    private val NPM_SCRIPTS = listOf("lint", "typecheck", "test", "build")
    private val MAKEFILES = listOf("Makefile", "makefile", "GNUmakefile")
    private val MAKE_TEST = Regex("(?m)^test\\s*:")

    suspend fun detect(
        paths: Collection<String>,
        read: suspend (String) -> String?,
        override: List<String> = emptyList(),
    ): DetectedChecks {
        val custom = override.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (custom.isNotEmpty()) return DetectedChecks(custom.map { ProjectCheck(it, it) }, CheckSource.OVERRIDE)

        val all = paths.toSet()
        val root = all.filter { '/' !in it }.toSet()
        val found = ArrayList<ProjectCheck>()

        if ("package.json" in root) found += npmChecks(root, read)
        if ("gradlew" in root && "gradle/wrapper/gradle-wrapper.jar" in all) {
            found += ProjectCheck("gradle test", "sh ./gradlew test --console=plain --no-daemon")
        }
        if (usesPytest(root, all, read)) found += pytestCheck(root)
        for (name in MAKEFILES) {
            if (name in root && read(name)?.let { MAKE_TEST.containsMatchIn(it) } == true) {
                found += ProjectCheck("make test", "make test")
                break
            }
        }
        if ("go.mod" in root) found += ProjectCheck("go test", "go test ./...")
        if ("Cargo.toml" in root) found += ProjectCheck("cargo test", "cargo test")

        val checks = found.distinctBy { it.command }
        return DetectedChecks(checks, if (checks.isEmpty()) CheckSource.NONE else CheckSource.DETECTED)
    }

    private suspend fun npmChecks(root: Set<String>, read: suspend (String) -> String?): List<ProjectCheck> {
        val text = read("package.json") ?: return emptyList()
        val scripts = runCatching { JSONObject(text).optJSONObject("scripts") }.getOrNull() ?: return emptyList()
        val yarn = "yarn.lock" in root && "package-lock.json" !in root
        val install = when {
            "package-lock.json" in root -> "npm ci"
            yarn -> "yarn install --frozen-lockfile"
            else -> "npm install"
        }
        val runner = if (yarn) "yarn run" else "npm run"
        val out = ArrayList<ProjectCheck>()
        for (name in NPM_SCRIPTS) {
            val body = scripts.optString(name, "")
            // `npm init` writes a test script that only prints an error; running it would always fail.
            if (body.isBlank() || body.contains("no test specified")) continue
            out += ProjectCheck("$runner $name", "CI=true $runner $name", install)
        }
        return out
    }

    private suspend fun usesPytest(root: Set<String>, all: Set<String>, read: suspend (String) -> String?): Boolean {
        if ("pytest.ini" in root || "conftest.py" in root) return true
        for (name in listOf("pyproject.toml", "setup.cfg", "tox.ini")) {
            if (name in root && read(name)?.contains("pytest") == true) return true
        }
        return all.any { p ->
            val file = p.substringAfterLast('/')
            file.endsWith(".py") && (file.startsWith("test_") || file.endsWith("_test.py"))
        }
    }

    private fun pytestCheck(root: Set<String>): ProjectCheck {
        val install = buildString {
            append("python -m pip install -q pytest")
            if ("requirements.txt" in root) append(" && python -m pip install -q -r requirements.txt")
        }
        return ProjectCheck("pytest", "python -m pytest -q", install)
    }
}
