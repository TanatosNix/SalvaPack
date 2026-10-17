package es.salvapack.app

import android.app.Application
import es.salvapack.app.data.InventoryRepository
import es.salvapack.app.data.ReminderInventoryReader
import es.salvapack.app.reminders.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class SalvaPackApplication : Application() {
    val repository by lazy { InventoryRepository(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var inventoryJob: Job? = null

    fun observeInventory() {
        if (inventoryJob != null) return
        inventoryJob = scope.launch {
            combine(repository.inventory, repository.session, repository.loading) { inventory, session, loading ->
                Triple(inventory, session, loading)
            }.collect { (_, _, loading) ->
                if (!loading) {
                    val scheduler = ReminderScheduler(this@SalvaPackApplication)
                    if (ReminderInventoryReader.currentScope(this@SalvaPackApplication) != null) {
                        scheduler.startBackgroundSync()
                        scheduler.showDue()
                    } else scheduler.clear()
                }
            }
        }
    }
}
