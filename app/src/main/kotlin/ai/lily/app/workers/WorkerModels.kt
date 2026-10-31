package ai.lily.app.workers

import ai.lily.app.core.WorkerType

/** Default router selector per worker. Profile names must exist in the router dashboard; otherwise use auto. */
object WorkerModels {
    fun forWorker(type: WorkerType): String = when (type) {
        WorkerType.CODING -> "auto:smart"
        WorkerType.RESEARCH -> "auto"
        WorkerType.BUILD -> "auto:fast"
        WorkerType.GIT -> "auto:fast"
        WorkerType.FILE -> "auto:fast"
        WorkerType.GENERAL -> "auto"
    }
}
