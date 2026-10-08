package app.locomate.data

import android.content.Context
import androidx.work.*
import app.locomate.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.TimeUnit

object PhysicalReportSyncWork {
    private const val NAME = "physical-report-sync"
    fun enqueue(context: Context) {
        if (StatusPushWork.deleting(context) || BuildConfig.RAIL_API_URL.isBlank()) return
        val request = OneTimeWorkRequestBuilder<PhysicalReportWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
    }
    fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(NAME) }
    class PhysicalReportWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
        override suspend fun doWork(): Result {
            if (StatusPushWork.deleting(applicationContext)) return Result.success()
            val gateway = RailGateway(applicationContext)
            if (!gateway.configured) return Result.success()
            val queue = PhysicalReportQueue(applicationContext, gateway.sourceUrl)
            return try {
                CommunitySync(CommunityQueue(applicationContext, gateway.sourceUrl), gateway,
                    CommunityPreferences(applicationContext, gateway.sourceUrl)).flushWithdrawals()
                for (report in queue.pending()) {
                    currentCoroutineContext().ensureActive()
                    if (!report.hasCurrentConsent() || !queue.contains(report)) continue
                    gateway.submitQueuedPhysicalReport(report)
                    currentCoroutineContext().ensureActive()
                    queue.remove(report.key, report.installation)
                }
                Result.success()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: GatewayError) {
                if (error.status in 400..499 && error.status != 429 && error.code != "request_in_progress") Result.success()
                else if (runAttemptCount < 5) Result.retry() else Result.failure()
            } catch (_: Exception) { if (runAttemptCount < 5) Result.retry() else Result.failure() }
        }
    }
}
