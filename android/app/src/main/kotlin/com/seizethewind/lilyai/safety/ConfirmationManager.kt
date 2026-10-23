package com.seizethewind.lilyai.safety

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

data class PendingConfirmation(val id: Long, val message: String)

/**
 * Asks the user to approve an action. The UI shows [pending] as a dialog and reports the answer
 * through [answer]. No answer within a minute counts as "no".
 */
class ConfirmationManager {

    private val mutex = Mutex()
    private val _pending = MutableStateFlow<PendingConfirmation?>(null)
    val pending: StateFlow<PendingConfirmation?> = _pending.asStateFlow()

    @Volatile
    private var waiting: CompletableDeferred<Boolean>? = null
    private var counter = 0L

    suspend fun ask(message: String, timeoutMs: Long = TIMEOUT_MS): Boolean =
        mutex.withLock {
            val deferred = CompletableDeferred<Boolean>()
            waiting = deferred
            counter++
            _pending.value = PendingConfirmation(counter, message)
            val answer = withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
            _pending.value = null
            waiting = null
            answer
        }

    fun answer(allow: Boolean) {
        waiting?.complete(allow)
    }

    /** Used by Stop: denies whatever is waiting. */
    fun cancelAll() {
        waiting?.complete(false)
    }

    companion object {
        private const val TIMEOUT_MS = 60_000L
    }
}
