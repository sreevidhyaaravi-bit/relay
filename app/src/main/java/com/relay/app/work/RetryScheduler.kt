package com.relay.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.relay.app.RelayApp
import com.relay.app.relay.ProcessTrigger
import java.util.concurrent.TimeUnit

/**
 * Persistent retries through WorkManager. WorkManager does not guarantee timing; Android may
 * delay work (Doze, battery saver, OEM restrictions). Processing also runs on app resume and on
 * every route change, so WorkManager is a safety net, not the main trigger.
 */
object RetryScheduler {
    private const val WORK_TIMED = "relay-retry-timed"
    private const val WORK_INTERNET = "relay-retry-internet"
    private const val WORK_PERIODIC = "relay-retry-periodic"

    /** One-time unique retry at [atUtc] (REPLACE). */
    fun scheduleAt(context: Context, atUtc: Long, nowUtc: Long) {
        val delay = (atUtc - nowUtc).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<RetryWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        runCatching { WorkManager.getInstance(context).enqueueUniqueWork(WORK_TIMED, ExistingWorkPolicy.REPLACE, request) }
    }

    /** One-time unique work constrained to NetworkType.CONNECTED (internet relay retry). */
    fun scheduleWhenInternet(context: Context) {
        val request = OneTimeWorkRequestBuilder<RetryWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        runCatching { WorkManager.getInstance(context).enqueueUniqueWork(WORK_INTERNET, ExistingWorkPolicy.KEEP, request) }
    }

    /** 15-minute periodic safety net (KEEP). */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<RetryWorker>(15, TimeUnit.MINUTES).build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }

    fun cancelAll(context: Context) {
        runCatching {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(WORK_TIMED)
            wm.cancelUniqueWork(WORK_INTERNET)
            wm.cancelUniqueWork(WORK_PERIODIC)
        }
    }
}

/** Runs one TransportManager pass (trigger WORKER) using the app's AppContainer. */
class RetryWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? RelayApp ?: return Result.success()
        return try {
            app.container.transportManager.processNow(ProcessTrigger.WORKER)
            Result.success()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.retry()
        }
    }
}
