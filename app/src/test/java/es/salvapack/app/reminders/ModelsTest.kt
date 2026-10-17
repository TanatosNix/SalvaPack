package es.salvapack.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelsTest {
    @Test fun `old product model becomes one unit with its original expiry`() {
        val product = Product(
            id = "old",
            name = "Tomate",
            category = Category.FOOD,
            expiryDate = "2030-05-02",
        ).validated()

        assertEquals(1, product.quantity)
        assertEquals(listOf("2030-05-02"), product.allExpiryDates)
    }

    @Test fun `multiple expiry dates are normalized and preserve repeated dates`() {
        val product = Product(
            id = "cans",
            name = "Atún",
            category = Category.FOOD,
            quantity = 3,
            expiryDates = listOf("2031-02-10", "2030-08-01", "2031-02-10"),
        ).validated()

        assertEquals(3, product.quantity)
        assertEquals(listOf("2030-08-01", "2031-02-10", "2031-02-10"), product.allExpiryDates)
        assertEquals("2030-08-01", product.expiryDate)
    }

    @Test fun `a product cannot contain more dates than units`() {
        assertThrows(IllegalArgumentException::class.java) {
            Product(
                id = "invalid",
                name = "Leche",
                category = Category.FOOD,
                quantity = 1,
                expiryDates = listOf("2030-01-01", "2030-02-01"),
            ).validated()
        }
    }
}
