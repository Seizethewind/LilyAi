package ai.lily.app.workspace

import java.io.File

/** What [WorkspaceSetup.create] did. Paths in [created] and [kept] are relative to [root]. */
data class SetupResult(val root: File, val project: File, val created: List<String>, val kept: List<String>)

/**
 * Sets up the workspace folder the person chose: `<parent>/lilyai/AGENT.md`, and for one project
 * `<parent>/lilyai/<project>/README.md` and `<parent>/lilyai/<project>/AGENT.md`.
 *
 * Nothing that already exists is touched. A file that is there is reported in [SetupResult.kept] and left as it is, so running the
 * setup again, or on a folder the person already filled, never loses their words.
 */
object WorkspaceSetup {
    const val ROOT_NAME = "lilyai"
    const val DEFAULT_PROJECT = "my-project"

    /** A folder name made from what the person typed: lowercase letters, digits, hyphen and underscore. Empty becomes [DEFAULT_PROJECT]. */
    fun projectName(raw: String): String {
        val cleaned = raw.trim().lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-')
        return cleaned.ifEmpty { DEFAULT_PROJECT }
    }

    /** Throws IllegalStateException when a folder cannot be made (for example, no file access yet). */
    fun create(parent: File, project: String): SetupResult {
        val root = File(parent, ROOT_NAME)
        val name = projectName(project)
        val dir = File(root, name)
        check(dir.isDirectory || dir.mkdirs()) { "Could not create ${dir.path}" }

        val created = mutableListOf<String>()
        val kept = mutableListOf<String>()
        fun write(file: File, text: String) {
            val rel = file.relativeTo(root).path
            // AGENTS.md is the other common spelling; a folder that has one already has its instructions.
            val there = file.exists() || (file.name == "AGENT.md" && File(file.parentFile, "AGENTS.md").exists())
            if (there) {
                kept += rel
            } else {
                file.writeText(text)
                created += rel
            }
        }
        write(File(root, "AGENT.md"), WorkspaceTemplates.rootAgent())
        write(File(dir, "README.md"), WorkspaceTemplates.readme(name))
        write(File(dir, "AGENT.md"), WorkspaceTemplates.projectAgent(name))
        return SetupResult(root, dir, created, kept)
    }
}

/** The starting text of the files the setup writes. The person edits them afterwards; Lily and her workers read them as they are. */
object WorkspaceTemplates {
    fun rootAgent(): String = """
        # AGENT.md - instructions for Lily and her workers

        ## Folder layout
        - lilyai/                 this workspace root
          - AGENT.md              rules for all projects (this file)
          - <project>/            one folder per project
            - README.md           what the project is
            - AGENT.md            rules for this project (wins where it differs from this file)

        ## For everyone
        - Work only inside this folder. Never read or write outside it.
        - Read the project's README.md before changing anything.
        - Make small, reviewable changes. Never delete or overwrite a file without asking.
        - Say what you changed and what you did not test. Do not claim a build or test passed unless it actually ran.
        - If a request is unclear, ask one question before starting.

        ## Lily (manager)
        - Break the request into tasks, pick the worker, and keep the project context current.
        - Record decisions and results in memory. Promote a fact only after a check passes.

        ## CodingWorker
        - Follow the project's existing style. Edit existing files before creating new ones.
        - Propose fixes as diffs the user can approve.

        ## BuildWorker
        - Run the build or tests, read the real error output, and report the first cause, not a guess.
        - Stop after 3 failed fix attempts and ask.

        ## FileWorker
        - Create files only under the project folder. Keep names lowercase with hyphens.

        ## GitWorker
        - Work on a branch, not main. Write clear commit messages. Never force-push.

        ## ResearchWorker
        - Cite sources. Mark anything unverified as unverified.
    """.trimIndent() + "\n"

    fun readme(name: String): String = """
        # $name

        What this project is: (one or two lines)

        ## Layout
        - README.md   this file
        - AGENT.md    rules for Lily and her workers in this project

        ## How Lily works here
        Lily and her workers read AGENT.md in this folder, and the one in the lilyai folder above it, before they act.
        Add the stack, the commands to build and test, and the files not to touch to the Project notes in AGENT.md.
    """.trimIndent() + "\n"

    fun projectAgent(name: String): String = """
        # AGENT.md - $name

        Rules for this project. They add to lilyai/AGENT.md and win where they differ from it.

        ## Project notes
        - Stack:
        - Build and test commands:
        - Do not touch:
    """.trimIndent() + "\n"
}
