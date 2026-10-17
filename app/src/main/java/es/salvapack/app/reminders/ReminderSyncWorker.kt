package es.salvapack.app.reminders

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import es.salvapack.app.data.ReminderInventoryReader
import kotlinx.coroutines.CancellationException

/** Refreshes without starting a repository or any Firestore realtime listeners. */
class ReminderSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val scheduler = ReminderScheduler(applicationContext)
        val generation = scheduler.activeGeneration() ?: return Result.success()
        return try {
            val inventory = ReminderInventoryReader.read(applicationContext)
            if (inventory == null) {
                scheduler.clearIfSessionLost(generation)
                return Result.success()
            }
            scheduler.reconcileFromWorker(inventory.scope, generation)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Offline cached reminders remain usable even when the remote refresh fails.
            val cached = ReminderInventoryReader.cached(applicationContext)
            if (cached != null) scheduler.reconcileFromWorker(cached.scope, generation)
            Result.retry()
        }
    }
}
