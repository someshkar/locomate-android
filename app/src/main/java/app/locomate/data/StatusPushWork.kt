package app.locomate.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.locomate.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.TimeUnit

/** FCM registration is shared by the explicitly enabled status card and journey alerts. */
object StatusPushWork {
    val preferenceName: String get() = "locomate.fcm-target.${railStorageScope(BuildConfig.RAIL_API_URL)}"
    private const val KEY_TARGET = "target"
    private const val KEY_ACTIVE_RUN_ID = "activeRunId"
    private const val KEY_DELETING = "deletingInstallation"
    private const val KEY_ACTION = "action"
    private const val KEY_RUN_ID = "runId"
    private const val ACTION_SYNC = "sync"
    private const val ACTION_UNREGISTER = "unregister"
    private const val ACTION_EXPIRE = "expire"

    fun available(context: Context): Boolean = FirebaseApp.getApps(context).isNotEmpty()
    private fun preferences(context: Context) = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    fun deleting(context: Context): Boolean = PrivacyDeletionState.pending(context) || preferences(context)
        .getBoolean(KEY_DELETING, false)
    fun target(context: Context): String? = preferences(context).getString(KEY_TARGET, null)
    fun hasConsumers(context: Context): Boolean = JourneyStatusNotification(context).activeRun() != null ||
        runCatching { JourneyAlertStore(context).hasActive() }.getOrDefault(false)

    fun enable(context: Context) {
        if (!available(context) || deleting(context)) return
        val active = JourneyStatusNotification(context).activeRun() ?: return
        preferences(context).edit()
            .putString(KEY_ACTIVE_RUN_ID, active.runId).apply()
        refreshRegistration(context)
        scheduleExpiryCheck(context)
    }

    fun refreshRegistration(context: Context) {
        if (!available(context) || deleting(context)) return
        if (!hasConsumers(context)) {
            releaseIfUnused(context)
            return
        }
        FirebaseMessaging.getInstance().isAutoInitEnabled = true
        FirebaseMessaging.getInstance().register()
        enqueue(context, ACTION_SYNC, null)
        JourneyAlertsWork.enqueue(context)
    }

    fun registered(context: Context, target: String) {
        if (deleting(context)) return
        if (target.length !in 20..4096 || target.any { it.code !in 0x21..0x7e }) return
        if (!hasConsumers(context)) return
        if (!preferences(context).edit().putString(KEY_TARGET, target).commit()) return
        enqueue(context, ACTION_SYNC, null)
        JourneyAlertsWork.enqueue(context)
    }

    fun unregister(context: Context, runId: String) {
        if (deleting(context)) return
        val preferences = preferences(context)
        if (preferences.getString(KEY_ACTIVE_RUN_ID, null) == runId) {
            preferences.edit().remove(KEY_ACTIVE_RUN_ID).apply()
        }
        enqueue(context, ACTION_UNREGISTER, runId)
        releaseIfUnused(context)
    }

    fun releaseIfUnused(context: Context) {
        if (!available(context) || deleting(context) || hasConsumers(context)) return
        FirebaseMessaging.getInstance().isAutoInitEnabled = false
        FirebaseMessaging.getInstance().unregister()
        preferences(context).edit().remove(KEY_TARGET).apply()
    }

    fun scheduleExpiryCheck(context: Context) {
        if (!available(context) || deleting(context)) return
        val request = OneTimeWorkRequestBuilder<StatusPushWorker>()
            .setInputData(Data.Builder().putString(KEY_ACTION, ACTION_EXPIRE).build())
            .setInitialDelay(11, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "status-push-expiry", ExistingWorkPolicy.REPLACE, request)
    }

    /** Prevent late token callbacks and workers from recreating a deleted gateway session. */
    fun beginPrivacyDeletion(context: Context) {
        PrivacyDeletionState.begin(context)
        check(preferences(context).edit()
            .putBoolean(KEY_DELETING, true).commit()) { "Could not pause status delivery" }
        // Both native push consumers share this deletion gate.
        WorkManager.getInstance(context).cancelAllWork().result.get(10, TimeUnit.SECONDS)
        if (available(context)) {
            FirebaseMessaging.getInstance().isAutoInitEnabled = false
            FirebaseMessaging.getInstance().unregister()
        }
    }

    fun finishPrivacyDeletion(context: Context): Boolean {
        if (!preferences(context).edit().clear().commit()) return false
        return PrivacyDeletionState.finish(context)
    }

    private fun enqueue(context: Context, action: String, runId: String?) {
        if (!available(context) || deleting(context)) return
        val input = Data.Builder().putString(KEY_ACTION, action).apply {
            if (runId != null) putString(KEY_RUN_ID, runId)
        }.build()
        val request = OneTimeWorkRequestBuilder<StatusPushWorker>()
            .setInputData(input)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            if (action == ACTION_SYNC) "status-push-sync" else "status-push-unregister-$runId",
            ExistingWorkPolicy.REPLACE, request)
    }

    class StatusPushWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
        override suspend fun doWork(): Result {
            if (deleting(applicationContext)) return Result.success()
            val gateway = RailGateway(applicationContext)
            return try {
                when (inputData.getString(KEY_ACTION)) {
                    ACTION_SYNC -> {
                        if (!gateway.configured) return Result.success()
                        val active = JourneyStatusNotification(applicationContext).activeRun()
                            ?: return Result.success()
                        val target = preferences(applicationContext)
                            .getString(KEY_TARGET, null) ?: return Result.success()
                        gateway.registerAndroidStatus(active.runId, target)
                    }
                    ACTION_UNREGISTER -> {
                        if (!gateway.configured) return Result.success()
                        val runId = inputData.getString(KEY_RUN_ID) ?: return Result.success()
                        if (JourneyStatusNotification(applicationContext).activeRun()?.runId == runId) {
                            return Result.success()
                        }
                        gateway.unregisterAndroidStatus(runId)
                    }
                    ACTION_EXPIRE -> {
                        if (JourneyStatusNotification(applicationContext).activeRun() != null) {
                            return Result.success()
                        }
                        val runId = preferences(applicationContext)
                            .getString(KEY_ACTIVE_RUN_ID, null) ?: return Result.success()
                        unregister(applicationContext, runId)
                    }
                    else -> return Result.failure()
                }
                Result.success()
            } catch (error: GatewayError) {
                if (error.status == 503 || error.status in 400..499) Result.success()
                else if (runAttemptCount < 4) Result.retry() else Result.failure()
            } catch (_: Exception) {
                if (runAttemptCount < 4) Result.retry() else Result.failure()
            }
        }
    }
}
