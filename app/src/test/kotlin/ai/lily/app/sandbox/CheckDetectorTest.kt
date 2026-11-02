package ai.lily.app.sandbox

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckDetectorTest {
    private fun detect(files: Map<String, String>, override: List<String> = emptyList()) =
        runBlocking { CheckDetector.detect(files.keys, { files[it] }, override) }

    @Test
    fun npmScriptsBecomeChecksInOrderWithOneInstall() {
        val found = detect(
            mapOf(
                "package.json" to """{"scripts":{"build":"tsc","test":"jest","lint":"eslint ."}}""",
                "package-lock.json" to "{}",
            ),
        )

        assertEquals(CheckSource.DETECTED, found.source)
        assertEquals(listOf("npm run lint", "npm run test", "npm run build"), found.checks.map { it.label })
        assertEquals("CI=true npm run test", found.checks[1].command)
        assertEquals(setOf("npm ci"), found.checks.map { it.install }.toSet())
    }

    @Test
    fun theDefaultNpmInitTestScriptIsNotACheck() {
        val found = detect(mapOf("package.json" to """{"scripts":{"test":"echo \"Error: no test specified\" && exit 1"}}"""))

        assertEquals(CheckSource.NONE, found.source)
        assertTrue(found.checks.isEmpty())
    }

    @Test
    fun yarnAndPlainNpmGetTheirOwnInstall() {
        val yarn = detect(mapOf("package.json" to """{"scripts":{"test":"jest"}}""", "yarn.lock" to ""))
        assertEquals("yarn install --frozen-lockfile", yarn.checks.single().install)
        assertEquals("CI=true yarn run test", yarn.checks.single().command)

        val plain = detect(mapOf("package.json" to """{"scripts":{"test":"jest"}}"""))
        assertEquals("npm install", plain.checks.single().install)
    }

    @Test
    fun aBrokenPackageJsonGivesNothingInsteadOfCrashing() {
        assertTrue(detect(mapOf("package.json" to "{ not json")).checks.isEmpty())
    }

    @Test
    fun gradleNeedsTheWrapperScriptAndJar() {
        val both = detect(mapOf("gradlew" to "", "gradle/wrapper/gradle-wrapper.jar" to ""))
        assertEquals("sh ./gradlew test --console=plain --no-daemon", both.checks.single().command)

        assertTrue(detect(mapOf("gradlew" to "")).checks.isEmpty())
    }

    @Test
    fun pytestIsFoundFromConfigOrFromTestFiles() {
        assertEquals("pytest", detect(mapOf("pytest.ini" to "")).checks.single().label)
        assertEquals("pytest", detect(mapOf("pyproject.toml" to "[tool.pytest.ini_options]")).checks.single().label)
        assertEquals("pytest", detect(mapOf("tests/test_api.py" to "")).checks.single().label)
        assertTrue(detect(mapOf("pyproject.toml" to "[project]\nname='x'")).checks.isEmpty())
    }

    @Test
    fun pytestInstallsRequirementsWhenThereAreSome() {
        val found = detect(mapOf("pytest.ini" to "", "requirements.txt" to "requests"))

        assertEquals(
            "python -m pip install -q pytest && python -m pip install -q -r requirements.txt",
            found.checks.single().install,
        )
    }

    @Test
    fun makeTestNeedsARealTestTarget() {
        assertEquals("make test", detect(mapOf("Makefile" to "build:\n\tcc x.c\n\ntest: build\n\t./a.out\n")).checks.single().command)
        assertTrue(detect(mapOf("Makefile" to ".PHONY: test\ntests:\n\ttrue\n")).checks.isEmpty())
    }

    @Test
    fun goAndCargoAreDetectedFromTheirManifests() {
        assertEquals("go test ./...", detect(mapOf("go.mod" to "module x")).checks.single().command)
        assertEquals("cargo test", detect(mapOf("Cargo.toml" to "[package]")).checks.single().command)
    }

    @Test
    fun onlyTheProjectRootIsLookedAt() {
        val found = detect(mapOf("web/package.json" to """{"scripts":{"test":"jest"}}""", "docs/go.mod" to "module x"))

        assertEquals(CheckSource.NONE, found.source)
    }

    @Test
    fun theUsersOwnCommandsReplaceDetection() {
        val found = detect(
            mapOf("package.json" to """{"scripts":{"test":"jest"}}"""),
            override = listOf("  make verify ", "", "make verify", "./check.sh"),
        )

        assertEquals(CheckSource.OVERRIDE, found.source)
        assertEquals(listOf("make verify", "./check.sh"), found.checks.map { it.command })
        assertTrue(found.checks.all { it.install == null })
    }

    @Test
    fun aProjectWithSeveralKindsGetsEachOfThem() {
        val found = detect(
            mapOf(
                "package.json" to """{"scripts":{"test":"jest"}}""",
                "go.mod" to "module x",
                "Makefile" to "test:\n\ttrue\n",
            ),
        )

        assertEquals(listOf("npm run test", "make test", "go test"), found.checks.map { it.label })
    }
}
