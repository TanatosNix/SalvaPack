package es.salvapack.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import es.salvapack.app.ui.SalvaPackRoot
import es.salvapack.app.ui.SalvaPackTheme

class MainActivity : ComponentActivity() {
    private var requestedProduct by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestedProduct = intent.getStringExtra("product_id")
        val app = application as SalvaPackApplication
        app.observeInventory()
        val repository = app.repository
        setContent {
            SalvaPackTheme {
                SalvaPackRoot(repository, requestedProduct) { requestedProduct = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedProduct = intent.getStringExtra("product_id")
    }
}
