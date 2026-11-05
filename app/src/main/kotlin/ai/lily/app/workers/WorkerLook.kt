package ai.lily.app.workers

import ai.lily.app.core.WorkerType

/**
 * The icons and colours a worker can be given. A worker stores only a key; the shell maps a key to a Material icon or a Tone, so
 * this stays free of Android. A blank or unknown key means the role's own icon and colour, which is how old workers look right.
 */
object WorkerLook {
    /** Material core icons, by the key a worker stores. */
    val icons = listOf("build", "person", "search", "share", "star", "favorite", "home", "settings", "info", "check", "edit")

    /** The app's Tone names. */
    val colors = listOf("petal", "pad", "stamen", "coral", "neutral")

    fun defaultIcon(role: WorkerType): String = when (role) {
        WorkerType.CODING -> "edit"
        WorkerType.RESEARCH -> "search"
        WorkerType.BUILD -> "build"
        WorkerType.GIT -> "share"
        WorkerType.FILE -> "home"
        WorkerType.GENERAL -> "person"
        WorkerType.TEST -> "check"
        WorkerType.TEAM -> "star"
    }

    fun defaultColor(role: WorkerType): String = when (role) {
        WorkerType.CODING, WorkerType.GENERAL, WorkerType.TEAM -> "petal"
        WorkerType.RESEARCH -> "stamen"
        WorkerType.BUILD, WorkerType.TEST -> "pad"
        WorkerType.GIT, WorkerType.FILE -> "neutral"
    }

    /** The icon key to draw: the worker's own when it is a known one, else the role's. */
    fun icon(role: WorkerType, key: String): String = key.takeIf { it in icons } ?: defaultIcon(role)

    fun color(role: WorkerType, key: String): String = key.takeIf { it in colors } ?: defaultColor(role)
}
