package app.locomate.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CommunityUploadRetryTest {
    @Test fun retriesAfterConnectivityReturnsWithoutAnotherLocation() = runTest {
        var online = false
        var queued = 1
        var attempts = 0
        val retry = CommunityUploadRetry(backgroundScope, { true }, {
            attempts++
            if (!online) throw IOException("Offline")
            queued = 0
        }, {})
        retry.start()
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(1, queued)

        online = true
        advanceTimeBy(CommunityUploadRetry.RETRY_INTERVAL_MILLIS - 1)
        runCurrent()
        assertEquals(1, attempts)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, attempts)
        assertEquals(0, queued)
    }

    @Test fun requestsDuringUploadAreCoalescedAndNeverOverlap() = runTest {
        val firstUpload = CompletableDeferred<Unit>()
        var attempts = 0
        var simultaneous = 0
        var maximum = 0
        val retry = CommunityUploadRetry(backgroundScope, { true }, {
            simultaneous++
            maximum = maxOf(maximum, simultaneous)
            attempts++
            if (attempts == 1) firstUpload.await()
            simultaneous--
        }, {})
        retry.start()
        runCurrent()
        repeat(100) { retry.request() }
        retry.start()
        runCurrent()
        assertEquals(1, attempts)

        firstUpload.complete(Unit)
        runCurrent()
        assertEquals(2, attempts)
        assertEquals(1, maximum)
        advanceTimeBy(CommunityUploadRetry.RETRY_INTERVAL_MILLIS - 1)
        runCurrent()
        assertEquals(2, attempts)
    }

    @Test fun runExpiryStopsBeforeTheNextUpload() = runTest {
        val expiresAt = CommunityUploadRetry.RETRY_INTERVAL_MILLIS
        var attempts = 0
        var stopped = 0
        val retry = CommunityUploadRetry(backgroundScope,
            { testScheduler.currentTime < expiresAt }, { attempts++ }, { stopped++ })
        retry.start()
        runCurrent()
        advanceTimeBy(expiresAt)
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(1, stopped)
    }

    @Test fun withdrawalPreventsALocationTriggeredRetry() = runTest {
        var consent = true
        var attempts = 0
        var stopped = 0
        val retry = CommunityUploadRetry(backgroundScope, { consent }, { attempts++ }, { stopped++ })
        retry.start()
        runCurrent()
        consent = false
        retry.request()
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(1, stopped)
    }

    @Test fun stoppingTheServiceCancelsAnUploadAndAllRetries() = runTest {
        val upload = CompletableDeferred<Unit>()
        var attempts = 0
        var cancellations = 0
        val retry = CommunityUploadRetry(backgroundScope, { true }, {
            attempts++
            try { upload.await() } finally { cancellations++ }
        }, {})
        retry.start()
        runCurrent()
        backgroundScope.cancel()
        runCurrent()
        retry.request()
        advanceTimeBy(CommunityUploadRetry.RETRY_INTERVAL_MILLIS * 2)
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(1, cancellations)
    }
}
