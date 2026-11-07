package ai.lily.app.shell

/**
 * What the current page did while it loaded: console messages, failed requests and how long it took. Lily's site checks read it.
 * Plain Kotlin so it can be tested without a device. WebView calls in on the main thread and the checks read from another one, hence the locks.
 * Only the newest [cap] entries are kept, each cut to [MAX_TEXT] characters, so a noisy page cannot fill memory.
 * A new page start clears it. shortcut: a redirect also restarts the log and the clock, when redirects matter keep the first start.
 */
class PageLog(private val cap: Int = 200) {
    enum class Kind { Console, Error, Http }

    data class Entry(val kind: Kind, val text: String)

    private val items = ArrayDeque<Entry>()
    private var startedAt = 0L
    private var finishedAt = 0L

    @Synchronized
    fun add(kind: Kind, text: String) {
        items.addLast(Entry(kind, text.take(MAX_TEXT)))
        while (items.size > cap) items.removeFirst()
    }

    @Synchronized
    fun entries(): List<Entry> = items.toList()

    /** A page started loading at [now] (a monotonic clock in milliseconds). */
    @Synchronized
    fun started(now: Long) {
        items.clear()
        startedAt = now
        finishedAt = 0L
    }

    @Synchronized
    fun finished(now: Long) {
        finishedAt = now
    }

    /** Milliseconds from start to finish, or null while the page is still loading or nothing has loaded. */
    @Synchronized
    fun loadMillis(): Long? = if (startedAt > 0L && finishedAt >= startedAt) finishedAt - startedAt else null

    companion object {
        const val MAX_TEXT = 500
    }
}
