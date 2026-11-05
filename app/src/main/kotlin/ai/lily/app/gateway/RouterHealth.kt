package ai.lily.app.gateway

import ai.lily.app.net.Http

/** Asks a router whether it is up, using the open health address of its kind (no key is sent). */
object RouterHealth {
    /** "Online", or fails with a message the person can read (wrong address, router down). */
    suspend fun check(config: RouterConfig): Result<String> = runCatching {
        val resp = Http.request("GET", config.origin + config.kind.healthPath, timeoutMs = 10_000)
        if (!resp.ok) error("The router answered ${resp.code}")
        "Online"
    }
}
