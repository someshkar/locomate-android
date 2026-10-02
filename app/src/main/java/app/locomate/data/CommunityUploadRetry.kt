package app.locomate.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One upload at a time, with a bounded retry even when no new location arrives. */
internal class CommunityUploadRetry(
    private val scope: CoroutineScope,
    private val canUpload: () -> Boolean,
    private val upload: suspend () -> Unit,
    private val onInactive: () -> Unit,
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null

    /** Called on the service thread. Repeated starts keep the same upload worker. */
    fun start() {
        if (worker?.isActive == true) {
            request()
            return
        }
        worker = scope.launch {
            while (isActive) {
                if (!canUpload()) {
                    onInactive()
                    return@launch
                }
                try {
                    upload()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Leave unacknowledged observations queued for the next bounded attempt.
                }
                // New fixes can prompt an earlier flush. Conflation prevents a backlog of uploads.
                withTimeoutOrNull(RETRY_INTERVAL_MILLIS) { requests.receive() }
            }
        }
    }

    fun request() { requests.trySend(Unit) }

    companion object {
        const val RETRY_INTERVAL_MILLIS = 30_000L
    }
}
