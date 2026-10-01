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
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.TimeUnit

/** Registers only an explicitly enabled status card with the gateway. */
object StatusPushWork {
    private const val PREFS = "locomate.fcm-target"
    private const val KEY_TARGET = "target"
    private const val KEY_ACTIVE_RUN_ID = "activeRunId"
    private const val KEY_ACTION = "action"
    private const val KEY_RUN_ID = "runId"
    private const val ACTION_SYNC = "sync"
    private const val ACTION_UNREGISTER = "unregister"
    private const val ACTION_EXPIRE = "expire"

    private fun available(context: Context): Boolean = FirebaseApp.getApps(context).isNotEmpty()

    fun enable(context: Context) {
        if (!available(context)) return
        val active = JourneyStatusNotification(context).activeRun() ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ACTIVE_RUN_ID, active.runId).apply()
        FirebaseMessaging.getInstance().isAutoInitEnabled = true
        FirebaseMessaging.getInstance().register()
        enqueue(context, ACTION_SYNC, null)
        scheduleExpiryCheck(context)
    }

    fun registered(context: Context, target: String) {
        if (target.length !in 20..4096 || target.any { it.code !in 0x21..0x7e }) return
        if (JourneyStatusNotification(context).activeRun() == null) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TARGET, target).apply()
        enqueue(context, ACTION_SYNC, null)
    }

    fun unregister(context: Context, runId: String) {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (preferences.getString(KEY_ACTIVE_RUN_ID, null) == runId) {
            preferences.edit().remove(KEY_ACTIVE_RUN_ID).apply()
        }
        enqueue(context, ACTION_UNREGISTER, runId)
        if (!available(context) || JourneyStatusNotification(context).activeRun() != null) return
        FirebaseMessaging.getInstance().isAutoInitEnabled = false
        FirebaseMessaging.getInstance().unregister()
        preferences.edit().remove(KEY_TARGET).apply()
    }

    fun scheduleExpiryCheck(context: Context) {
        if (!available(context)) return
        val request = OneTimeWorkRequestBuilder<StatusPushWorker>()
            .setInputData(Data.Builder().putString(KEY_ACTION, ACTION_EXPIRE).build())
            .setInitialDelay(11, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "status-push-expiry", ExistingWorkPolicy.REPLACE, request)
    }

    private fun enqueue(context: Context, action: String, runId: String?) {
        if (!available(context)) return
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
            val gateway = RailGateway(applicationContext)
            return try {
                when (inputData.getString(KEY_ACTION)) {
                    ACTION_SYNC -> {
                        if (!gateway.configured) return Result.success()
                        val active = JourneyStatusNotification(applicationContext).activeRun()
                            ?: return Result.success()
                        val target = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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
                        val runId = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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
