package top.xixiclaire.screentime

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Periodic worker scheduled by MainActivity. Reads usage stats and POSTs them.
 * Returns retry on transient failures (network), success otherwise so the
 * periodic schedule keeps marching even if a single attempt fails to parse.
 */
class ReportWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!MainActivity.hasUsageAccess(applicationContext)) {
            // Permission revoked — bail but don't retry storm
            return Result.success()
        }
        val report = UsageReader.collect(applicationContext)
        val ok = Reporter.send(applicationContext, report)
        return if (ok) Result.success() else Result.retry()
    }
}
