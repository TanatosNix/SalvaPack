package es.salvapack.app.data

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

data class ReminderInventory(val scope: String, val products: List<Product>)

/** Short-lived reads for background workers; no listeners or repository instances. */
object ReminderInventoryReader {
    fun currentScope(context: Context): String? {
        val activeScope = InventorySnapshot(context).scope() ?: return null
        val configured = FirebaseApp.getApps(context.applicationContext).isNotEmpty()
        if (!configured) return activeScope.takeIf { it == "local" }
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return null
        return activeScope.takeIf { it.startsWith("$uid:") }
    }

    fun isCurrent(context: Context, scope: String): Boolean = currentScope(context) == scope

    fun cached(context: Context): ReminderInventory? {
        val activeScope = currentScope(context) ?: return null
        val products = InventorySnapshot(context).read(activeScope) ?: return null
        return if (isCurrent(context, activeScope)) ReminderInventory(activeScope, products) else null
    }

    suspend fun read(context: Context): ReminderInventory? {
        val saved = cached(context) ?: return null
        if (saved.scope == "local") return saved
        val snapshot = InventorySnapshot(context)
        val revision = snapshot.revision()
        val backpackId = saved.scope.substringAfter(':')
        return try {
            val result = withTimeout(10_000) {
                FirebaseFirestore.getInstance().collection("backpacks").document(backpackId)
                    .collection("products").get(Source.SERVER).await()
            }
            if (!isCurrent(context, saved.scope)) return null
            val products = result.documents.mapNotNull { it.asProduct() }.sortedForDisplay()
            // A foreground edit may have arrived while this request was in flight.
            if (!snapshot.writeIfUnchanged(saved.scope, revision, products)) return cached(context)
            if (isCurrent(context, saved.scope)) ReminderInventory(saved.scope, products) else null
        } catch (error: Exception) {
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            if (!isCurrent(context, saved.scope)) return null
            if (error is FirebaseFirestoreException && error.code in listOf(
                    FirebaseFirestoreException.Code.PERMISSION_DENIED,
                    FirebaseFirestoreException.Code.UNAUTHENTICATED,
                )) {
                snapshot.clear(saved.scope)
                null
            } else cached(context)
        }
    }
}
