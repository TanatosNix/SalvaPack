package es.salvapack.app.data

import android.content.Context

/** Private, session-scoped copy used by reminders without a running UI or listener. */
class InventorySnapshot(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("inventory_snapshot", Context.MODE_PRIVATE)

    fun read(): List<Product> = synchronized(lock) {
        if (preferences.getString(SCOPE, null) == null) emptyList()
        else ProductJson.decode(preferences.getString(PRODUCTS, null))
    }

    fun write(products: List<Product>) = synchronized(lock) {
        if (preferences.getString(SCOPE, null) != null) {
            preferences.edit().putString(PRODUCTS, ProductJson.encode(products)).putLong(REVISION, nextRevision()).apply()
        }
    }

    fun clear() = synchronized(lock) {
        preferences.edit().clear().putLong(REVISION, nextRevision()).apply()
    }

    internal fun clear(scope: String) = synchronized(lock) {
        if (preferences.getString(SCOPE, null) == scope) clear()
    }

    internal fun revision(): Long = synchronized(lock) { preferences.getLong(REVISION, 0) }

    internal fun hasPendingWrites(scope: String): Boolean = synchronized(lock) {
        preferences.getString(SCOPE, null) == scope && preferences.getBoolean(PENDING, false)
    }

    internal fun setPendingWrites(scope: String, pending: Boolean) = synchronized(lock) {
        if (preferences.getString(SCOPE, null) == scope) {
            preferences.edit().putBoolean(PENDING, pending).putLong(REVISION, nextRevision()).apply()
        }
    }

    internal fun scope(): String? = synchronized(lock) { preferences.getString(SCOPE, null) }

    internal fun activate(scope: String): List<Product> = synchronized(lock) {
        if (preferences.getString(SCOPE, null) != scope) {
            preferences.edit().clear().putString(SCOPE, scope).putLong(REVISION, nextRevision()).apply()
        }
        ProductJson.decode(preferences.getString(PRODUCTS, null))
    }

    internal fun read(scope: String): List<Product>? = synchronized(lock) {
        if (preferences.getString(SCOPE, null) != scope) null
        else ProductJson.decode(preferences.getString(PRODUCTS, null))
    }

    internal fun write(scope: String, products: List<Product>, hasPendingWrites: Boolean? = null): Boolean = synchronized(lock) {
        if (preferences.getString(SCOPE, null) != scope) false
        else {
            preferences.edit().putString(PRODUCTS, ProductJson.encode(products)).putLong(REVISION, nextRevision())
                .apply { if (hasPendingWrites != null) putBoolean(PENDING, hasPendingWrites) }.apply()
            true
        }
    }

    internal fun writeIfUnchanged(scope: String, expectedRevision: Long, products: List<Product>): Boolean = synchronized(lock) {
        if (preferences.getLong(REVISION, 0) != expectedRevision) false else write(scope, products)
    }

    private fun nextRevision(): Long = preferences.getLong(REVISION, 0) + 1

    private companion object {
        val lock = Any()
        const val SCOPE = "scope"
        const val PRODUCTS = "products"
        const val REVISION = "revision"
        const val PENDING = "pending_writes"
    }
}

internal class LocalInventory(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("local_inventory", Context.MODE_PRIVATE)

    fun read(): List<Product> = ProductJson.decode(preferences.getString("products", null))

    fun write(products: List<Product>) {
        preferences.edit().putString("products", ProductJson.encode(products)).apply()
    }
}
