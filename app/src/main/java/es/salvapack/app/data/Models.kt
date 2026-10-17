package es.salvapack.app.data

import com.google.firebase.firestore.DocumentSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

enum class Category(val label: String) {
    FOOD("Comida"), MEDICINE("Medicamentos"), EMERGENCY("Emergencia")
}

data class Product(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val category: Category,
    val expiryDate: String? = null,
    val photoBase64: String? = null,
    val quantity: Int = 1,
    val expiryDates: List<String> = expiryDate?.let(::listOf) ?: emptyList(),
)

/** All current expiry dates, while still accepting products saved by older app versions. */
val Product.allExpiryDates: List<String>
    get() = expiryDates.ifEmpty { listOfNotNull(expiryDate) }

data class InventoryState(
    val products: List<Product> = emptyList(),
    val isFromCache: Boolean = false,
    val hasPendingWrites: Boolean = false,
    val error: String? = null,
)

data class Session(
    val backpackId: String? = null,
)

internal fun Product.validated(): Product {
    require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "El identificador del producto no es válido." }
    val cleanName = name.trim()
    require(cleanName.length in 1..120) { "Escribe un nombre de entre 1 y 120 caracteres." }
    require(quantity in 1..999) { "La cantidad debe estar entre 1 y 999." }
    require(allExpiryDates.size <= quantity) { "No puede haber más fechas de caducidad que unidades." }
    allExpiryDates.forEach {
        require(it.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "La fecha de caducidad no es válida." }
        require(runCatching { LocalDate.parse(it) }.isSuccess) { "La fecha de caducidad no es válida." }
    }
    require(photoBase64 == null || photoBase64.length <= 350_000) { "La foto es demasiado grande. Elige otra imagen." }
    val dates = allExpiryDates.sorted()
    return copy(name = cleanName, expiryDate = dates.firstOrNull(), expiryDates = dates)
}

internal fun Product.asFields(): Map<String, Any?> = mapOf(
    "id" to id,
    "name" to name,
    "category" to category.name,
    "expiryDate" to expiryDate,
    "expiryDates" to allExpiryDates,
    "quantity" to quantity,
    "photoBase64" to photoBase64,
)

internal fun DocumentSnapshot.asProduct(): Product? = runCatching {
    Product(
        id = id,
        name = getString("name") ?: return null,
        category = Category.valueOf(getString("category") ?: return null),
        expiryDate = getString("expiryDate"),
        photoBase64 = getString("photoBase64"),
        quantity = (getLong("quantity") ?: 1L).toInt(),
        expiryDates = (get("expiryDates") as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
    ).validated()
}.getOrNull()

internal fun List<Product>.sortedForDisplay(): List<Product> = sortedWith(
    compareBy<Product> { it.allExpiryDates.minOrNull() ?: "9999-12-31" }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
)

internal object ProductJson {
    fun encode(products: List<Product>): String = JSONArray().apply {
        products.forEach { product ->
            put(JSONObject().apply {
                put("id", product.id)
                put("name", product.name)
                put("category", product.category.name)
                put("expiryDate", product.expiryDate ?: JSONObject.NULL)
                put("expiryDates", JSONArray(product.allExpiryDates))
                put("quantity", product.quantity)
                put("photoBase64", product.photoBase64 ?: JSONObject.NULL)
            })
        }
    }.toString()

    fun decode(json: String?): List<Product> = runCatching {
        val array = JSONArray(json ?: "[]")
        (0 until array.length()).mapNotNull { index ->
            runCatching {
                val entry = array.getJSONObject(index)
                Product(
                    id = entry.getString("id"),
                    name = entry.getString("name"),
                    category = Category.valueOf(entry.getString("category")),
                    expiryDate = entry.optionalString("expiryDate"),
                    photoBase64 = entry.optionalString("photoBase64"),
                    quantity = entry.optInt("quantity", 1),
                    expiryDates = entry.optionalStringList("expiryDates"),
                ).validated()
            }.getOrNull()
        }.sortedForDisplay()
    }.getOrDefault(emptyList())

    private fun JSONObject.optionalString(key: String): String? =
        if (isNull(key) || !has(key)) null else getString(key)

    private fun JSONObject.optionalStringList(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf(String::isNotBlank) }
    }
}
