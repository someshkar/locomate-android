package app.locomate.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.locomate.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/** Durable desired state is authoritative; late responses never replace a newer user mutation. */
object JourneyAlertsWork {
    private val mutationLock = Mutex()
    private val scopeName get() = railStorageScope(BuildConfig.RAIL_API_URL)

    fun recover(context: Context) {
        if (StatusPushWork.deleting(context)) return
        val store = JourneyAlertStore(context)
        store.expire()
        reconcilePermissions(store, JourneyAlertsNotification(context).available())
        JourneyAlertsNotification(context).cancelDisabled()
        StatusPushWork.refreshRegistration(context)
        enqueue(context)
        scheduleExpiry(context)
    }

    fun enqueue(context: Context) {
        if (StatusPushWork.deleting(context) || !RailGateway(context).configured) return
        val request = OneTimeWorkRequestBuilder<JourneyAlertsWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "journey-alerts-sync-$scopeName", ExistingWorkPolicy.REPLACE, request)
    }

    internal fun reconcilePermissions(store: JourneyAlertStore, notificationsAllowed: Boolean) {
        if (!notificationsAllowed) {
            store.all().filter { it.enabled }.forEach { store.disable(it.runId) }
        }
    }

    fun scheduleExpiry(context: Context) {
        if (StatusPushWork.deleting(context)) return
        val expiry = JourneyAlertStore(context).all().filter { it.active() }.minOfOrNull { it.expiresAt }
        val name = "journey-alerts-expiry-$scopeName"
        if (expiry == null) {
            WorkManager.getInstance(context).cancelUniqueWork(name)
            return
        }
        val request = OneTimeWorkRequestBuilder<JourneyAlertsExpiryWorker>()
            .setInitialDelay((expiry - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
    }

    class JourneyAlertsExpiryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            if (!StatusPushWork.deleting(applicationContext)) recover(applicationContext)
            return Result.success()
        }
    }

    class JourneyAlertsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result = mutationLock.withLock {
            val context = applicationContext
            if (StatusPushWork.deleting(context)) return@withLock Result.success()
            val gateway = RailGateway(context)
            if (!gateway.configured) return@withLock Result.success()
            val store = JourneyAlertStore(context)
            var retry = false
            try {
                store.expire()
                reconcilePermissions(store, JourneyAlertsNotification(context).available())
                JourneyAlertsNotification(context).cancelDisabled()
                StatusPushWork.target(context)?.let(store::updateTarget)
                for (subscription in store.all().filter { it.pending }) {
                    currentCoroutineContext().ensureActive()
                    if (StatusPushWork.deleting(context)) return@withLock Result.success()
                    if (store.get(subscription.runId)?.revision != subscription.revision) continue
                    try {
                        if (subscription.enabled) {
                            if (!subscription.active() || subscription.target == null) continue
                            val ack = gateway.registerJourneyAlerts(subscription)
                            store.acknowledge(subscription.runId, ack.revision, ack.expiresAt)
                        } else {
                            gateway.unregisterJourneyAlerts(subscription.runId, subscription.revision)
                            store.acknowledge(subscription.runId, subscription.revision)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: GatewayError) {
                        if (error.status == 409 && error.currentRevision != null) {
                            store.resolveConflict(subscription.runId, subscription.revision, error.currentRevision)
                            retry = true
                        } else {
                            store.failed(subscription.runId, subscription.revision,
                                if (error.status == 0 || error.status >= 500 || error.status == 429)
                                    "Waiting for the rail gateway · will retry" else error.message ?: "Alerts were not accepted")
                            if (error.status == 0 || error.status >= 500 || error.status == 429) retry = true
                        }
                    } catch (_: Exception) {
                        store.failed(subscription.runId, subscription.revision, "Offline · will retry")
                        retry = true
                    }
                }
                scheduleExpiry(context)
                StatusPushWork.releaseIfUnused(context)
                if (retry) Result.retry() else Result.success()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Result.retry()
            }
        }
    }
}
