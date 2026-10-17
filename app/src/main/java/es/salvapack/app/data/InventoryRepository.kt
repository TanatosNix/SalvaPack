package es.salvapack.app.data

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.util.Locale
import java.util.UUID

class InventoryRepository(context: Context) {
    private val appContext = context.applicationContext
    val configured: Boolean = FirebaseApp.getApps(appContext).isNotEmpty()
    private val auth = if (configured) FirebaseAuth.getInstance() else null
    private val firestore = if (configured) FirebaseFirestore.getInstance() else null
    private val snapshot = InventorySnapshot(appContext)
    private val localInventory = LocalInventory(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val localMutex = Mutex()

    private val mutableInventory = MutableStateFlow(InventoryState())
    val inventory: StateFlow<InventoryState> = mutableInventory.asStateFlow()
    private val mutableSession = MutableStateFlow(Session())
    val session: StateFlow<Session> = mutableSession.asStateFlow()
    private val mutableLoading = MutableStateFlow(configured)
    val loading: StateFlow<Boolean> = mutableLoading.asStateFlow()

    private var profileListener: ListenerRegistration? = null
    private var backpackListener: ListenerRegistration? = null
    private var productsListener: ListenerRegistration? = null
    private var bootstrapTimeout: Job? = null
    private var generation = 0
    private var attachedBackpack: String? = null
    private var profileResolved = false
    private var metadataHasPendingWrites = false
    private var anonymousSignInStarted = false
    private var writeSequence = 0
    private val pendingOperations = mutableSetOf<Int>()
    private var writeError: String? = null
    private val authListener = FirebaseAuth.AuthStateListener { current ->
        val user = current.currentUser
        if (user == null) startAnonymousSession() else resolveUser(user)
    }

    init {
        if (configured) auth!!.addAuthStateListener(authListener)
        else {
            snapshot.activate(LOCAL_SCOPE)
            publishLocal(localInventory.read())
        }
    }

    suspend fun createBackpack() {
        val uid = requireUser()
        requireResolvedProfile()
        val database = requireNotNull(firestore)
        val code = UUID.randomUUID().toString().replace("-", "").uppercase(Locale.ROOT)
        val profile = database.collection("users").document(uid)
        val backpack = database.collection("backpacks").document(code)
        onlineOperation {
            database.runTransaction { transaction ->
                val existing = transaction.get(profile).getString("backpackId")
                check(existing.isNullOrBlank()) { "Este móvil ya está vinculado a una mochila." }
                transaction.set(backpack, mapOf("createdAt" to FieldValue.serverTimestamp()))
                transaction.set(profile, mapOf("backpackId" to code))
            }.await()
        }
    }

    suspend fun joinBackpack(code: String) {
        val uid = requireUser()
        requireResolvedProfile()
        val normalizedCode = code.trim().uppercase(Locale.ROOT)
        require(normalizedCode.matches(Regex("[A-F0-9]{32}"))) { "El código de mochila debe tener 32 caracteres. Cópialo completo." }
        val database = requireNotNull(firestore)
        val profile = database.collection("users").document(uid)
        val backpack = database.collection("backpacks").document(normalizedCode)
        onlineOperation {
            database.runTransaction { transaction ->
                val existing = transaction.get(profile).getString("backpackId")
                check(existing.isNullOrBlank() || existing == normalizedCode) { "Este móvil ya está vinculado a otra mochila." }
                if (existing == normalizedCode) return@runTransaction
                val backpackData = transaction.get(backpack)
                check(backpackData.exists()) { "No encontramos ninguna mochila con ese código." }
                transaction.set(profile, mapOf("backpackId" to normalizedCode))
            }.await()
        }
    }

    suspend fun save(product: Product) {
        val validProduct = product.validated()
        if (!configured) {
            localMutex.withLock {
                val products = localInventory.read().filterNot { it.id == validProduct.id } + validProduct
                publishLocal(products)
            }
            return
        }
        val activeScope = requireCloudScope()
        val document = requireNotNull(firestore).collection("backpacks")
            .document(requireNotNull(session.value.backpackId)).collection("products").document(validProduct.id)
        // Firestore acknowledges writes only after reaching the server. Do not await here:
        // its persistent local queue and realtime listener also work without connectivity.
        enqueueCloudWrite(activeScope, "No se pudo sincronizar el producto. Revisa la conexión y tus permisos.") {
            document.set(validProduct.asFields())
        }
    }

    suspend fun delete(productId: String) {
        require(productId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "El producto no es válido." }
        if (!configured) {
            localMutex.withLock { publishLocal(localInventory.read().filterNot { it.id == productId }) }
            return
        }
        val activeScope = requireCloudScope()
        val document = requireNotNull(firestore).collection("backpacks").document(requireNotNull(session.value.backpackId))
            .collection("products").document(productId)
        enqueueCloudWrite(activeScope, "No se pudo sincronizar la eliminación. Revisa la conexión y tus permisos.") { document.delete() }
    }

    suspend fun refresh() {
        if (!configured) {
            publishLocal(localInventory.read())
            return
        }
        val user = auth?.currentUser ?: run { startAnonymousSession(); return }
        val backpackId = session.value.backpackId
        if (backpackId == null) {
            resolveUser(user)
            return
        }
        val activeScope = requireCloudScope()
        val revision = snapshot.revision()
        val result = onlineOperation {
            requireNotNull(firestore).collection("backpacks").document(backpackId)
                .collection("products").get(Source.SERVER).await()
        }
        if (snapshot.scope() == activeScope && snapshot.revision() == revision) {
            publishCloud(activeScope, result.documents.mapNotNull { it.asProduct() }, result.metadata.isFromCache, result.metadata.hasPendingWrites())
        }
    }

    private fun resolveUser(user: FirebaseUser?) {
        stopListeners()
        if (user == null) {
            snapshot.clear()
            mutableInventory.value = InventoryState()
            mutableSession.value = Session()
            mutableLoading.value = false
            return
        }
        val currentGeneration = generation
        val uid = user.uid
        if (snapshot.scope()?.startsWith("$uid:") != true) snapshot.clear()
        mutableInventory.value = InventoryState()
            mutableSession.value = Session()
        mutableLoading.value = true
        bootstrapTimeout = scope.launch {
            delay(12_000)
            if (isActiveUser(uid, currentGeneration) && mutableLoading.value) {
                mutableLoading.value = false
                setError("No se pudo cargar la mochila. Comprueba la conexión y vuelve a intentarlo.")
            }
        }
        profileListener = requireNotNull(firestore).collection("users").document(uid)
            .addSnapshotListener(MetadataChanges.INCLUDE) { profile, error ->
                if (!isActiveUser(uid, currentGeneration)) return@addSnapshotListener
                if (error != null) {
                    mutableLoading.value = false
                    setError("No se pudo preparar este móvil. Comprueba la conexión y la configuración de Firebase.")
                    return@addSnapshotListener
                }
                if (profile == null) return@addSnapshotListener
                // An absent cache entry is not evidence that this account has no backpack.
                if (!profile.exists() && profile.metadata.isFromCache) return@addSnapshotListener
                val backpackId = profile.getString("backpackId")
                mutableLoading.value = false
                bootstrapTimeout?.cancel()
                if (backpackId.isNullOrBlank()) {
                    profileResolved = true
                    snapshot.clear()
                    mutableInventory.value = InventoryState()
                    mutableSession.value = Session()
                } else if (!backpackId.matches(Regex("[A-F0-9]{32}"))) {
                    profileResolved = false
                    setError("El código de la mochila guardada no es válido.")
                } else if (attachedBackpack != backpackId) {
                    profileResolved = true
                    attachBackpack(user, backpackId, currentGeneration)
                }
            }
    }

    private fun attachBackpack(user: FirebaseUser, backpackId: String, currentGeneration: Int) {
        backpackListener?.remove()
        productsListener?.remove()
        attachedBackpack = backpackId
        val activeScope = "${user.uid}:$backpackId"
        val savedProducts = snapshot.activate(activeScope)
        mutableSession.value = Session(backpackId = backpackId)
        metadataHasPendingWrites = snapshot.hasPendingWrites(activeScope)
        mutableInventory.value = InventoryState(savedProducts, isFromCache = true, hasPendingWrites = metadataHasPendingWrites)
        // Includes persisted deletes, which may no longer appear in a query's documents.
        enqueueCloudWrite(activeScope, "No se pudo confirmar la sincronización. Vuelve a iniciar sesión.") {
            requireNotNull(firestore).waitForPendingWrites()
        }
        val backpack = requireNotNull(firestore).collection("backpacks").document(backpackId)
        backpackListener = backpack.addSnapshotListener { document, error ->
            if (!isActiveUser(user.uid, currentGeneration) || snapshot.scope() != activeScope) return@addSnapshotListener
            if (error != null) {
                handleListenerError(error)
            } else if (document != null && document.exists()) mutableSession.value = Session(backpackId = backpackId)
        }
        productsListener = backpack.collection("products").addSnapshotListener(MetadataChanges.INCLUDE) { result, error ->
            if (!isActiveUser(user.uid, currentGeneration) || snapshot.scope() != activeScope) return@addSnapshotListener
            if (error != null) {
                handleListenerError(error)
                return@addSnapshotListener
            }
            if (result != null) {
                metadataHasPendingWrites = result.metadata.hasPendingWrites()
                val products = result.documents.mapNotNull { it.asProduct() }
                if (result.metadata.isFromCache && !result.metadata.hasPendingWrites() && products.isEmpty() && mutableInventory.value.products.isNotEmpty()) {
                    val pending = metadataHasPendingWrites || pendingOperations.isNotEmpty()
                    snapshot.setPendingWrites(activeScope, pending)
                    mutableInventory.value = mutableInventory.value.copy(isFromCache = true, hasPendingWrites = pending)
                } else publishCloud(activeScope, products, result.metadata.isFromCache, result.metadata.hasPendingWrites())
            }
        }
    }

    private fun publishLocal(products: List<Product>) {
        val sorted = products.sortedForDisplay()
        localInventory.write(sorted)
        snapshot.write(LOCAL_SCOPE, sorted)
        mutableInventory.value = InventoryState(sorted)
    }

    private fun publishCloud(activeScope: String, products: List<Product>, isFromCache: Boolean, hasPendingWrites: Boolean) {
        val sorted = products.sortedForDisplay()
        metadataHasPendingWrites = hasPendingWrites
        val pending = metadataHasPendingWrites || pendingOperations.isNotEmpty()
        if (snapshot.write(activeScope, sorted, pending)) {
            mutableInventory.value = InventoryState(sorted, isFromCache, pending, error = writeError)
        }
    }

    private fun enqueueCloudWrite(activeScope: String, failureMessage: String, operation: () -> Task<Void>) {
        val operationId = ++writeSequence
        val writeGeneration = generation
        writeError = null
        pendingOperations.add(operationId)
        snapshot.setPendingWrites(activeScope, true)
        mutableInventory.value = mutableInventory.value.copy(hasPendingWrites = true, error = null)
        val task = try {
            operation()
        } catch (error: Exception) {
            finishCloudWrite(activeScope, operationId, writeGeneration, failureMessage)
            throw error
        }
        task.addOnCompleteListener { completed ->
            finishCloudWrite(activeScope, operationId, writeGeneration, if (completed.isSuccessful) null else failureMessage)
        }
    }

    private fun finishCloudWrite(activeScope: String, operationId: Int, writeGeneration: Int, error: String?) {
        if (generation != writeGeneration) return
        pendingOperations.remove(operationId)
        if (error != null) writeError = error
        val pending = metadataHasPendingWrites || pendingOperations.isNotEmpty()
        snapshot.setPendingWrites(activeScope, pending)
        mutableInventory.value = mutableInventory.value.copy(hasPendingWrites = pending, error = error ?: mutableInventory.value.error)
    }

    private fun handleListenerError(error: FirebaseFirestoreException) {
        if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED || error.code == FirebaseFirestoreException.Code.UNAUTHENTICATED) {
            snapshot.clear()
            metadataHasPendingWrites = false
            mutableInventory.value = InventoryState(hasPendingWrites = pendingOperations.isNotEmpty(), error = "No se puede acceder a esta mochila. Comprueba la conexión y el código compartido.")
        } else setError("No se pudo actualizar la mochila: ${error.message.orEmpty()}")
    }

    private fun setError(message: String) {
        mutableInventory.value = mutableInventory.value.copy(error = message)
    }

    private fun stopListeners() {
        generation++
        bootstrapTimeout?.cancel()
        profileListener?.remove()
        backpackListener?.remove()
        productsListener?.remove()
        profileListener = null
        backpackListener = null
        productsListener = null
        attachedBackpack = null
        profileResolved = false
        metadataHasPendingWrites = false
        pendingOperations.clear()
        writeError = null
    }

    private fun isActiveUser(uid: String, expectedGeneration: Int): Boolean =
        generation == expectedGeneration && auth?.currentUser?.uid == uid

    private fun requireAuth(): FirebaseAuth = checkNotNull(auth) { "Primero hay que configurar Firebase para sincronizar los dos móviles." }

    private fun requireUser(): String = checkNotNull(requireAuth().currentUser?.uid) { "Se está preparando el acceso seguro. Vuelve a intentarlo en unos segundos." }

    private fun startAnonymousSession() {
        if (anonymousSignInStarted || !configured) return
        anonymousSignInStarted = true
        mutableLoading.value = true
        requireAuth().signInAnonymously().addOnCompleteListener { task ->
            anonymousSignInStarted = false
            if (!task.isSuccessful) {
                mutableLoading.value = false
                setError(if (task.exception is FirebaseNetworkException)
                    "No hay conexión. Conéctate a Internet y pulsa Reintentar."
                else "No se pudo preparar el acceso. Comprueba que el acceso anónimo esté activado en Firebase.")
            }
        }
    }

    private fun requireResolvedProfile() {
        check(profileResolved) { "Primero hay que preparar este móvil. Comprueba la conexión y pulsa Reintentar." }
    }

    private fun requireCloudScope(): String {
        val uid = requireUser()
        val backpackId = checkNotNull(session.value.backpackId) { "Crea una mochila o únete a la de tu pareja." }
        val activeScope = "$uid:$backpackId"
        check(snapshot.scope() == activeScope) { "La mochila aún se está cargando. Vuelve a intentarlo." }
        return activeScope
    }

    private suspend fun <T> onlineOperation(operation: suspend () -> T): T = try {
        withTimeout(20_000) { operation() }
    } catch (error: TimeoutCancellationException) {
        throw IllegalStateException("La conexión está tardando demasiado. Comprueba Internet y vuelve a intentarlo.", error)
    } catch (error: FirebaseAuthException) {
        val message = when (error.errorCode) {
            "ERROR_OPERATION_NOT_ALLOWED" -> "Falta activar el acceso anónimo en Firebase."
            else -> "No se pudo preparar el acceso seguro. Vuelve a intentarlo."
        }
        throw IllegalStateException(message, error)
    } catch (error: FirebaseNetworkException) {
        throw IllegalStateException("No hay conexión. Comprueba Internet y vuelve a intentarlo.", error)
    } catch (error: FirebaseTooManyRequestsException) {
        throw IllegalStateException("Ha habido demasiados intentos. Espera unos minutos y vuelve a intentarlo.", error)
    } catch (error: FirebaseFirestoreException) {
        val message = when (error.code) {
            FirebaseFirestoreException.Code.PERMISSION_DENIED -> "No se puede acceder a esa mochila. Revisa el código y la configuración de Firebase."
            FirebaseFirestoreException.Code.UNAUTHENTICATED -> "El acceso del dispositivo ha caducado. Vuelve a abrir la app con conexión."
            FirebaseFirestoreException.Code.UNAVAILABLE, FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> "No se pudo conectar. Comprueba Internet y vuelve a intentarlo."
            else -> "No se pudo completar la operación. Vuelve a intentarlo."
        }
        throw IllegalStateException(message, error)
    }

    private companion object {
        const val LOCAL_SCOPE = "local"
    }
}
