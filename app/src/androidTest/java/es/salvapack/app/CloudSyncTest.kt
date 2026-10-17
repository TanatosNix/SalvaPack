package es.salvapack.app

import android.graphics.Bitmap
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import es.salvapack.app.data.Category
import es.salvapack.app.data.InventoryRepository
import es.salvapack.app.data.Product
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Integration test against the disposable local Firebase emulators. */
@RunWith(AndroidJUnit4::class)
class CloudSyncTest {
    @Test
    fun twoAnonymousPhonesSharePhotosEditsAndDeletion() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        assertTrue(FirebaseApp.getApps(context).none { it.name == FirebaseApp.DEFAULT_APP_NAME })
        val unique = UUID.randomUUID().toString().replace("-", "")
        val options = FirebaseOptions.Builder()
            .setProjectId("demo-salvapack")
            .setApplicationId("1:1234567890:android:0123456789abcdef")
            .setApiKey("AIzaSy" + "0".repeat(33)).build()
        val firstApp = FirebaseApp.initializeApp(context, options)
        val secondApp = FirebaseApp.initializeApp(context, options, "phone-b-$unique")
        val firstAuth = FirebaseAuth.getInstance(firstApp).apply { useEmulator(HOST, AUTH_PORT) }
        val secondAuth = FirebaseAuth.getInstance(secondApp).apply { useEmulator(HOST, AUTH_PORT) }
        val firstDb = FirebaseFirestore.getInstance(firstApp).apply { useEmulator(HOST, FIRESTORE_PORT) }
        val secondDb = FirebaseFirestore.getInstance(secondApp).apply { useEmulator(HOST, FIRESTORE_PORT) }

        try {
            val firstPhone = withContext(Dispatchers.Main) { InventoryRepository(context) }
            firstPhone.loading.awaitValue("El acceso anónimo del primer móvil no terminó") { !it }
            withContext(Dispatchers.Main) { firstPhone.createBackpack() }
            val backpackId = requireNotNull(firstPhone.session.awaitValue("No se creó la mochila") { it.backpackId != null }.backpackId)
            val photo = tinyJpeg()
            val item = Product(name = "Agua de reserva", category = Category.FOOD, expiryDate = "2030-12-15", photoBase64 = photo)
            withContext(Dispatchers.Main) { firstPhone.save(item) }
            firstPhone.inventory.awaitValue("No se guardó el producto") { state ->
                state.products.any { it.id == item.id && it.photoBase64 == photo } && !state.hasPendingWrites
            }

            val secondUid = requireNotNull(withTimeout(20_000) { secondAuth.signInAnonymously().await().user?.uid })
            val secondProfile = secondDb.collection("users").document(secondUid)
            val secondBag = secondDb.collection("backpacks").document(backpackId)
            withTimeout(20_000) {
                secondDb.runTransaction { transaction ->
                    check(transaction.get(secondBag).exists())
                    transaction.set(secondProfile, mapOf("backpackId" to backpackId))
                }.await()
            }
            val shared = secondBag.collection("products").document(item.id)
            assertEquals(photo, withTimeout(20_000) { shared.get(Source.SERVER).await() }.getString("photoBase64"))

            withTimeout(20_000) { shared.update("name", "Agua revisada").await() }
            firstPhone.inventory.awaitValue("El primer móvil no recibió la edición") { state ->
                state.products.any { it.id == item.id && it.name == "Agua revisada" }
            }
            withTimeout(20_000) { shared.delete().await() }
            firstPhone.inventory.awaitValue("El primer móvil no recibió la eliminación") { state -> state.products.none { it.id == item.id } }
        } finally {
            listOf(firstDb, secondDb).forEach { db -> runCatching { withTimeout(5_000) { db.terminate().await() } } }
            secondApp.delete(); firstApp.delete()
        }
    }

    private suspend fun <T> StateFlow<T>.awaitValue(message: String, predicate: (T) -> Boolean): T = try {
        withTimeout(25_000) { first(predicate) }
    } catch (error: TimeoutCancellationException) {
        throw AssertionError("$message. Último estado: $value", error)
    }

    private fun tinyJpeg(): String {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF497A50.toInt()) }
        return try {
            val bytes = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, bytes)
            Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
        } finally { bitmap.recycle() }
    }

    private companion object {
        const val HOST = "10.0.2.2"
        const val AUTH_PORT = 9099
        const val FIRESTORE_PORT = 8080
    }
}
