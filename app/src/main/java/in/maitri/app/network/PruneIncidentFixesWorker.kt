package `in`.maitri.app.network

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class PruneIncidentFixesWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = IncidentFixQueue(applicationContext).use { queue ->
        queue.prune()
        if (queue.hasPending()) Result.retry() else Result.success()
    }
}
