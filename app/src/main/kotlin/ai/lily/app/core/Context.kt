package ai.lily.app.core

/** Current task/project context. */
class Context(
    var projectPath: String? = null,
    val notes: MutableList<String> = mutableListOf(),
)
