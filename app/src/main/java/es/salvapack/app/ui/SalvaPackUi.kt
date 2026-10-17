@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package es.salvapack.app.ui

import android.Manifest
import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import es.salvapack.app.R
import es.salvapack.app.data.*
import es.salvapack.app.reminders.ReminderScheduler
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

private val SpanishDate = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("es"))
private fun datesOf(product: Product): List<LocalDate> = product.allExpiryDates.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.sorted()
private fun dateOf(product: Product) = datesOf(product).firstOrNull()
private fun needsAttention(product: Product, today: LocalDate): Boolean = datesOf(product).any { !today.isBefore(it.minusMonths(1)) }
private fun Category.icon(): ImageVector = when (this) {
    Category.FOOD -> Icons.Outlined.Restaurant
    Category.MEDICINE -> Icons.Outlined.MedicalServices
    Category.EMERGENCY -> Icons.Outlined.HealthAndSafety
}

@Composable
fun SalvaPackRoot(
    repository: InventoryRepository,
    requestedProduct: String? = null,
    onProductRequestConsumed: () -> Unit = {},
) {
    val inventory by repository.inventory.collectAsStateWithLifecycle()
    val session by repository.session.collectAsStateWithLifecycle()
    val loading by repository.loading.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var today by remember { mutableStateOf(LocalDate.now()) }
    var notificationsEnabled by remember { mutableStateOf(ReminderScheduler(context).notificationsEnabled()) }
    var operationBusy by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        notificationsEnabled = ReminderScheduler(context).notificationsEnabled()
        if (notificationsEnabled) ReminderScheduler(context).showDue()
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                today = LocalDate.now()
                notificationsEnabled = ReminderScheduler(context).notificationsEnabled()
                ReminderScheduler(context).showDue()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { while (true) { delay(60_000); today = LocalDate.now() } }
    LaunchedEffect(session.backpackId) { detailId = null; creating = false; editingId = null }
    LaunchedEffect(requestedProduct, inventory.products) {
        if (requestedProduct != null && inventory.products.any { it.id == requestedProduct }) {
            detailId = requestedProduct
            tab = 0
            onProductRequestConsumed()
        }
    }
    val notify: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 33 && !context.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("notification_requested", false)) {
            context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("notification_requested", true).apply()
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
    }
    val feedback: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }
    val action: (suspend () -> Unit) -> Unit = { block ->
        if (!operationBusy) scope.launch {
            operationBusy = true
            try { block() } catch (e: Exception) { feedback(friendlyError(e)) }
            finally { operationBusy = false }
        }
    }

    Surface(Modifier.fillMaxSize()) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            repository.configured && session.backpackId == null -> PairScreen(repository)
            else -> Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    NavigationBar(containerColor = Cream, tonalElevation = 0.dp) {
                        listOf("Mochila" to Icons.Outlined.Backpack, "Avisos" to Icons.Outlined.NotificationsNone, "Juntos" to Icons.Outlined.PeopleOutline).forEachIndexed { index, (name, icon) ->
                            NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(icon, null) }, label = { Text(name) })
                        }
                    }
                },
                floatingActionButton = {
                    if (tab == 0) ExtendedFloatingActionButton(
                        onClick = { creating = true }, containerColor = Forest, contentColor = Color.White,
                        icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Añadir producto") }
                    )
                }
            ) { padding ->
                Column(Modifier.padding(padding).fillMaxSize().widthIn(max = 720.dp)) {
                    BrandHeader()
                    when (tab) {
                        0 -> InventoryScreen(inventory, repository.configured, today, onOpen = { detailId = it }, onAdd = { creating = true })
                        1 -> AlertsScreen(inventory.products, today, notificationsEnabled, notify, onOpen = { detailId = it })
                        2 -> TogetherScreen(session, repository.configured, inventory, notificationsEnabled, notify, feedback)
                    }
                }
            }
        }

        val detail = inventory.products.find { it.id == detailId }
        if (detail != null && !creating && editingId == null) {
            ProductDetail(detail, today, operationBusy, onBack = { detailId = null }, onEdit = { editingId = detail.id }, onDelete = {
                action { repository.delete(detail.id); detailId = null; feedback("Producto eliminado") }
            })
        }
        if (creating || editingId != null) {
            val original = inventory.products.find { it.id == editingId }
            ProductEditor(original, onClose = { creating = false; editingId = null }, onSave = { product ->
                repository.save(product)
                creating = false
                editingId = null
                feedback("Producto guardado")
            })
        }
    }
}

@Composable
private fun BrandHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.logo), null, Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)))
        Spacer(Modifier.width(10.dp))
        Text("SalvaPack", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.weight(1f))
        Text("PREPARADOS, JUNTOS", style = MaterialTheme.typography.labelSmall, color = Muted, letterSpacing = 1.sp)
    }
}

@Composable
private fun InventoryScreen(state: InventoryState, cloud: Boolean, today: LocalDate, onOpen: (String) -> Unit, onAdd: () -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var categoryName by rememberSaveable { mutableStateOf<String?>(null) }
    val category = Category.entries.find { it.name == categoryName }
    val filtered = state.products.filter { (category == null || category == it.category) && it.name.contains(search.trim(), ignoreCase = true) }
        .sortedWith(compareBy<Product> { it.allExpiryDates.minOrNull() ?: "9999-12-31" }.thenBy { it.name.lowercase() })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 106.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Forest).padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("VUESTRA MOCHILA DE SALVAMENTO", color = Sage, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, modifier = Modifier.weight(1f))
                    Icon(Icons.Outlined.Shield, null, tint = Sage, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.height(17.dp))
                Text("Lo esencial,\nsiempre a mano.", style = MaterialTheme.typography.headlineLarge, color = Color.White)
                Spacer(Modifier.height(12.dp))
                Text("Cuidad de lo que os cuida.", color = Color(0xFFD5DFC9), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (cloud) Icons.Outlined.CloudDone else Icons.Outlined.PhoneAndroid, null, tint = Sage, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(when {
                        !cloud -> "Guardado en este móvil"
                        state.hasPendingWrites -> "Cambios pendientes de sincronizar"
                        state.isFromCache -> "Mostrando datos guardados"
                        else -> "Mochila compartida · al día"
                    }, color = Sage, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric(state.products.size.toString(), "productos", Icons.Outlined.Inventory2, Sage, Modifier.weight(1f))
                Metric(state.products.count { needsAttention(it, today) }.toString(), "por revisar", Icons.Outlined.EventAvailable, PaleAmber, Modifier.weight(1f))
            }
        }
        if (state.error != null) item { InfoCard(state.error, Icons.Outlined.CloudOff, PaleAmber) }
        item {
            Spacer(Modifier.height(4.dp))
            Text("Dentro de la mochila", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), placeholder = { Text("Buscar un producto") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, shape = RoundedCornerShape(18.dp))
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = category == null, onClick = { categoryName = null }, label = { Text("Todo") })
                Category.entries.forEach { cat -> FilterChip(selected = category == cat, onClick = { categoryName = cat.name }, label = { Text(cat.label) }, leadingIcon = { Icon(cat.icon(), null, Modifier.size(17.dp)) }) }
            }
        }
        if (filtered.isEmpty()) item {
            EmptyState(
                if (state.products.isEmpty()) "Todo empieza con lo esencial" else "No hay coincidencias",
                if (state.products.isEmpty()) "Añadid vuestro primer producto y tened su caducidad bajo control." else "Prueba otro nombre o categoría.",
                Icons.Outlined.Backpack,
                if (state.products.isEmpty()) onAdd else null
            )
        }
        items(filtered, key = { it.id }) { product -> ProductCard(product, today, onClick = { onOpen(product.id) }) }
    }
}

@Composable
private fun Metric(value: String, label: String, icon: ImageVector, color: Color, modifier: Modifier) {
    Row(modifier.clip(RoundedCornerShape(22.dp)).background(color).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(value, style = MaterialTheme.typography.headlineMedium)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Muted)
        }
        Icon(icon, null, tint = Forest, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun ProductCard(product: Product, today: LocalDate, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            ProductPhoto(product.photoBase64, product.category.icon(), Modifier.size(68.dp).clip(RoundedCornerShape(16.dp)))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(product.category.label.uppercase(Locale.forLanguageTag("es")), color = Muted, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
                Text(product.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${product.quantity} ${if (product.quantity == 1) "unidad" else "unidades"}", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(5.dp))
                ExpiryBadge(product, today)
            }
            Icon(Icons.Outlined.ChevronRight, "Ver ficha", tint = Muted, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun ExpiryBadge(product: Product, today: LocalDate) {
    val expiry = dateOf(product)
    val expired = expiry?.isBefore(today) == true
    val due = needsAttention(product, today)
    val color = if (expired) Danger else if (due) Amber else Muted
    val text = when {
        expiry == null -> "Sin caducidad"
        expired -> "Caducado · ${expiry.format(SpanishDate)}"
        expiry == today -> "Caduca hoy"
        due -> "Revisar · ${expiry.format(SpanishDate)}"
        else -> "Caduca ${expiry.format(SpanishDate)}"
    }
    val extra = (datesOf(product).size - 1).takeIf { it > 0 }?.let { " · +$it ${if (it == 1) "fecha" else "fechas"}" }.orEmpty()
    Text(text + extra, color = color, fontSize = 12.sp, lineHeight = 17.sp)
}

@Composable
private fun ProductPhoto(base64: String?, fallback: ImageVector, modifier: Modifier = Modifier) {
    val image = remember(base64) {
        base64?.let { runCatching { val bytes = Base64.decode(it, Base64.DEFAULT); BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull() }
    }
    if (image != null) Image(image, "Foto del producto", modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(Sage), contentAlignment = Alignment.Center) { Icon(fallback, null, Modifier.size(32.dp), tint = Forest) }
}

@Composable
private fun AlertsScreen(products: List<Product>, today: LocalDate, enabled: Boolean, onEnable: () -> Unit, onOpen: (String) -> Unit) {
    val alerts = products.filter { needsAttention(it, today) }.sortedBy { dateOf(it) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Un mes de margen.", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Text("Para revisar y renovar con tranquilidad.", color = Muted)
        }
        item { NotificationCard(enabled, onEnable) }
        item { InfoCard("Cada aviso se programa un mes antes de la fecha de caducidad. Si añades un producto dentro de ese plazo, se avisará al guardarlo.", Icons.Outlined.EventAvailable, Sage) }
        item { Text("Necesitan vuestra atención · ${alerts.size}", style = MaterialTheme.typography.titleMedium) }
        if (alerts.isEmpty()) item { EmptyState("Todo bajo control", "Aquí aparecerán los productos caducados o que estén en su último mes.", Icons.Outlined.VerifiedUser) }
        items(alerts, key = { it.id }) { product -> ProductCard(product, today) { onOpen(product.id) } }
    }
}

@Composable
private fun NotificationCard(enabled: Boolean, onEnable: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = if (enabled) Sage else PaleAmber), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (enabled) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsOff, null)
                Spacer(Modifier.width(10.dp))
                Text(if (enabled) "Avisos activados" else "Activad las notificaciones", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(if (enabled) "En este móvil. Revisad también este permiso en el otro teléfono." else "Dale permiso a SalvaPack para avisarte aunque no tengas la app abierta.", style = MaterialTheme.typography.bodyMedium)
            if (!enabled) TextButton(onClick = onEnable, contentPadding = PaddingValues(top = 8.dp, end = 8.dp)) { Text("Activar avisos"); Spacer(Modifier.width(6.dp)); Icon(Icons.Outlined.ArrowOutward, null, Modifier.size(16.dp)) }
        }
    }
}

@Composable
private fun TogetherScreen(session: Session, cloud: Boolean, state: InventoryState, enabled: Boolean, onEnable: () -> Unit, feedback: (String) -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Una mochila.\nLos dos al día.", style = MaterialTheme.typography.headlineLarge)
        Text("El mismo inventario, estéis donde estéis.", color = Muted)
        if (!cloud) {
            InfoCard("Versión local\nAhora los productos se guardan en este móvil. La conexión compartida está pendiente de configurar; esta versión todavía no sincroniza entre teléfonos.", Icons.Outlined.PhoneAndroid, PaleAmber)
        } else {
            Card(colors = CardDefaults.cardColors(containerColor = Sage), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.PeopleOutline, null, Modifier.size(36.dp))
                    Text("Mochila compartida", style = MaterialTheme.typography.titleLarge)
                    Text("Comparte este código solo con tu pareja. En el otro móvil basta con abrir SalvaPack y elegir «Unirme con un código»; no hace falta crear una cuenta.")
                    if (session.backpackId != null) {
                        SelectionCode(session.backpackId)
                        Button(onClick = {
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Código SalvaPack", session.backpackId))
                            feedback("Código copiado")
                        }) { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Copiar código") }
                    }
                }
            }
            if (state.hasPendingWrites || state.isFromCache) InfoCard("Hay datos guardados en este móvil. Mantén la conexión para completar la sincronización.", Icons.Outlined.CloudSync, PaleAmber)
        }
        NotificationCard(enabled, onEnable)
        Text("Los avisos apuntan a las 9:00 del día previsto. Android puede retrasarlos por el ahorro de batería. Abrid la app después de reiniciar o de cambiar permisos para comprobar que todo está al día.", style = MaterialTheme.typography.bodySmall, color = Muted)
        Text("SalvaPack · 0.1.0", color = Muted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SelectionCode(code: String) {
    Text(code.chunked(4).joinToString(" "), modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.7f)).padding(14.dp), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
}

@Composable
private fun InfoCard(text: String, icon: ImageVector, background: Color) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(background).padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(23.dp), tint = Forest)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EmptyState(title: String, body: String, icon: ImageVector, onAdd: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(76.dp).clip(CircleShape).background(Sage), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(35.dp), tint = Forest) }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, color = Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        if (onAdd != null) TextButton(onClick = onAdd) { Text("Añadir el primer producto"); Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)) }
    }
}

@Composable
private fun ProductDetail(product: Product, today: LocalDate, busy: Boolean, onBack: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    BackHandler { onBack() }
    var confirm by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize()) {
        Scaffold(topBar = { TopAppBar(title = { Text("Ficha del producto") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Volver") } }, actions = { IconButton(onClick = onEdit, enabled = !busy) { Icon(Icons.Outlined.Edit, "Editar producto") } }) }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                ProductPhoto(product.photoBase64, product.category.icon(), Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(28.dp)))
                Text(product.category.label.uppercase(Locale.forLanguageTag("es")), color = Muted, style = MaterialTheme.typography.labelLarge, letterSpacing = 1.sp)
                Text(product.name, style = MaterialTheme.typography.headlineLarge)
                InfoCard("Cantidad: ${product.quantity} ${if (product.quantity == 1) "unidad" else "unidades"}", Icons.Outlined.Inventory2, Sage)
                ExpiryBadge(product, today)
                val dates = datesOf(product)
                if (dates.isNotEmpty()) {
                    dates.groupingBy { it }.eachCount().forEach { (expiry, count) ->
                        val units = if (count == 1) "1 unidad" else "$count unidades"
                        InfoCard("$units · Caducidad: ${expiry.format(SpanishDate)}\nAviso desde: ${expiry.minusMonths(1).format(SpanishDate)}", Icons.Outlined.CalendarMonth, if (!today.isBefore(expiry.minusMonths(1))) PaleAmber else Sage)
                    }
                    if (dates.size < product.quantity) Text("${product.quantity - dates.size} ${if (product.quantity - dates.size == 1) "unidad no tiene" else "unidades no tienen"} fecha indicada.", color = Muted, style = MaterialTheme.typography.bodyMedium)
                } else InfoCard("Este producto no tiene fechas de caducidad y no generará avisos.", Icons.Outlined.EventAvailable, Sage)
                Button(onClick = onEdit, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Editar ficha") }
                TextButton(onClick = { confirm = true }, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally), colors = ButtonDefaults.textButtonColors(contentColor = Danger)) { Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Eliminar producto") }
            }
        }
        if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("¿Eliminar este producto?") }, text = { Text("«${product.name}» se quitará de la mochila y sus avisos se cancelarán.") }, confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text("Eliminar", color = Danger) } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } })
    }
}

@Composable
private fun ProductEditor(original: Product?, onClose: () -> Unit, onSave: suspend (Product) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var id by rememberSaveable { mutableStateOf(original?.id ?: UUID.randomUUID().toString()) }
    var name by rememberSaveable { mutableStateOf(original?.name.orEmpty()) }
    var categoryName by rememberSaveable { mutableStateOf(original?.category?.name ?: Category.FOOD.name) }
    var quantityText by rememberSaveable { mutableStateOf((original?.quantity ?: 1).toString()) }
    var expiryDates by rememberSaveable { mutableStateOf(ArrayList(original?.allExpiryDates.orEmpty())) }
    // Save only the draft file path across rotation; a large photo must not enter the Binder bundle.
    var photoPath by rememberSaveable { mutableStateOf<String?>(null) }
    var photoRemoved by rememberSaveable { mutableStateOf(false) }
    val photo = remember(photoPath, photoRemoved, original?.photoBase64) {
        if (photoRemoved) null else photoPath?.let { runCatching { File(it).readText() }.getOrNull() } ?: original?.photoBase64
    }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    val category = Category.valueOf(categoryName)
    val dirty = name != original?.name.orEmpty() || category != (original?.category ?: Category.FOOD) || quantityText != (original?.quantity ?: 1).toString() || expiryDates != original?.allExpiryDates.orEmpty() || photo != original?.photoBase64
    val close = { if (!busy) { if (dirty) discard = true else onClose() } }
    BackHandler { close() }
    val readPhoto: (Uri) -> Unit = { uri -> scope.launch {
        busy = true; error = null
        try {
            val encoded = readProductPhoto(context, uri)
            val draft = withContext(Dispatchers.IO) {
                File.createTempFile("photo-draft-", ".txt", context.cacheDir).apply { writeText(encoded) }
            }
            photoPath = draft.absolutePath
            photoRemoved = false
        } catch (e: Exception) { error = "No se pudo cargar la foto. Prueba otra imagen." }
        finally { busy = false }
    } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) readPhoto(uri) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) cameraUri?.let { readPhoto(Uri.parse(it)) } }
    Surface(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = { TopAppBar(title = { Text(if (original == null) "Nuevo producto" else "Editar producto") }, navigationIcon = { IconButton(onClick = close, enabled = !busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Volver") } }) },
            bottomBar = {
                Surface(shadowElevation = 8.dp) {
                    Button(onClick = {
                        val quantity = quantityText.toIntOrNull()
                        if (name.isBlank()) error = "Escribe el nombre del producto."
                        else if (quantity == null || quantity !in 1..999) error = "La cantidad debe estar entre 1 y 999."
                        else if (expiryDates.size > quantity) error = "Quita fechas o aumenta la cantidad: solo puede haber una fecha por unidad."
                        else scope.launch {
                            busy = true; error = null
                            try { onSave(Product(id = id, name = name.trim(), category = category, expiryDate = expiryDates.minOrNull(), photoBase64 = photo, quantity = quantity, expiryDates = expiryDates)) }
                            catch (e: Exception) { error = friendlyError(e) }
                            finally { busy = false }
                        }
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(20.dp).height(54.dp)) {
                        if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp) else { Icon(Icons.Outlined.Check, null); Spacer(Modifier.width(8.dp)); Text("Guardar producto") }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                ProductPhoto(photo, Icons.Outlined.AddPhotoAlternate, Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(24.dp)))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !busy, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.PhotoLibrary, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Galería") }
                    OutlinedButton(onClick = {
                        try {
                            val directory = File(context.cacheDir, "camera").apply { mkdirs() }
                            val file = File.createTempFile("product-", ".jpg", directory)
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                            cameraUri = uri.toString()
                            camera.launch(uri)
                        } catch (e: Exception) { error = "No hay una cámara disponible. Puedes elegir una foto de la galería." }
                    }, enabled = !busy, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.PhotoCamera, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Cámara") }
                }
                if (photo != null) TextButton(onClick = { photoRemoved = true }, enabled = !busy) { Text("Quitar foto") }
                OutlinedTextField(name, { if (it.length <= 120) name = it }, label = { Text("Nombre del producto") }, placeholder = { Text("Por ejemplo, barritas energéticas") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
                Text("Categoría", style = MaterialTheme.typography.titleMedium)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Category.entries.forEach { cat ->
                        Surface(onClick = { if (!busy) categoryName = cat.name }, color = if (category == cat) Sage else Color.White, shape = RoundedCornerShape(16.dp)) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(cat.icon(), null, tint = Forest)
                                Spacer(Modifier.width(12.dp))
                                Text(cat.label, Modifier.weight(1f))
                                RadioButton(selected = category == cat, onClick = { categoryName = cat.name }, enabled = !busy)
                            }
                        }
                    }
                }
                OutlinedTextField(quantityText, { value -> if (value.length <= 3 && value.all(Char::isDigit)) quantityText = value }, label = { Text("Cantidad de unidades") }, supportingText = { Text("Por ejemplo, 4 latas del mismo producto") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done))
                Text("Fechas de caducidad", style = MaterialTheme.typography.titleMedium)
                Text("Puedes añadir una fecha por unidad. Si varias unidades caducan el mismo día, repite esa fecha.", color = Muted, style = MaterialTheme.typography.bodyMedium)
                expiryDates.forEachIndexed { index, raw ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val initial = LocalDate.parse(raw)
                            DatePickerDialog(context, { _, year, month, day ->
                                expiryDates = ArrayList(expiryDates.toMutableList().also { it[index] = LocalDate.of(year, month + 1, day).toString() })
                            }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
                        }, modifier = Modifier.weight(1f).height(54.dp), enabled = !busy, shape = RoundedCornerShape(16.dp)) {
                            Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text("Unidad ${index + 1} · ${LocalDate.parse(raw).format(SpanishDate)}")
                        }
                        IconButton(onClick = { expiryDates = ArrayList(expiryDates.toMutableList().also { it.removeAt(index) }) }, enabled = !busy) { Icon(Icons.Outlined.DeleteOutline, "Quitar fecha", tint = Danger) }
                    }
                }
                val quantity = quantityText.toIntOrNull() ?: 0
                OutlinedButton(onClick = {
                    val initial = expiryDates.lastOrNull()?.let(LocalDate::parse) ?: LocalDate.now().plusMonths(6)
                    DatePickerDialog(context, { _, year, month, day -> expiryDates = ArrayList(expiryDates + LocalDate.of(year, month + 1, day).toString()) }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
                }, modifier = Modifier.fillMaxWidth().height(54.dp), enabled = !busy && expiryDates.size < quantity, shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text("Añadir fecha de caducidad")
                }
                if (expiryDates.isEmpty()) Text("Sin fechas de caducidad. Este producto no generará avisos.", color = Muted, style = MaterialTheme.typography.bodyMedium)
                expiryDates.minOrNull()?.let { raw ->
                    val alert = LocalDate.parse(raw).minusMonths(1)
                    InfoCard(if (!LocalDate.now().isBefore(alert)) "Está dentro del plazo de aviso. Se notificará al guardarlo si has activado las notificaciones." else "Te avisaremos desde el ${alert.format(SpanishDate)}, un mes antes.", Icons.Outlined.NotificationsNone, Sage)
                }
                if (error != null) Text(error.orEmpty(), color = Danger, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("¿Salir sin guardar?") }, text = { Text("Los cambios de esta ficha se perderán.") }, confirmButton = { TextButton(onClick = onClose) { Text("Salir") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("Seguir editando") } })
    }
}

@Composable
private fun PairScreen(repository: InventoryRepository) {
    val inventory by repository.inventory.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var code by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val action: (suspend () -> Unit) -> Unit = { block -> scope.launch { busy = true; error = null; try { block() } catch (e: Exception) { error = friendlyError(e) } finally { busy = false } } }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Spacer(Modifier.height(24.dp))
        Image(painterResource(R.drawable.logo), null, Modifier.size(100.dp).clip(RoundedCornerShape(26.dp)))
        Text("Vuestra preparación\nempieza aquí.", style = MaterialTheme.typography.headlineLarge)
        Text("Una persona crea la mochila y comparte el código. En el otro móvil solo hay que introducirlo: sin correo y sin contraseña.", color = Muted)
        inventory.error?.let { message ->
            InfoCard(message, Icons.Outlined.CloudOff, PaleAmber)
            OutlinedButton(onClick = { action { repository.refresh() } }, enabled = !busy) { Text("Reintentar conexión") }
        }
        Button(onClick = { action { repository.createBackpack() } }, enabled = !busy && inventory.error == null, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Crear nuestra mochila") }
        HorizontalDivider()
        Text("¿Tu pareja ya la ha creado?", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(code, { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(32) }, label = { Text("Código de la mochila") }, supportingText = { Text("Pega el código de 32 caracteres") }, modifier = Modifier.fillMaxWidth(), enabled = !busy, shape = RoundedCornerShape(16.dp), minLines = 2)
        OutlinedButton(onClick = { action { repository.joinBackpack(code.trim()) } }, enabled = !busy && code.length == 32 && inventory.error == null, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Unirme con un código") }
        if (busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        if (error != null) Text(error.orEmpty(), color = Danger)
    }
}

private fun friendlyError(error: Exception): String = when (error) {
    is com.google.firebase.FirebaseNetworkException -> "No hay conexión. Comprueba Internet e inténtalo de nuevo."
    is com.google.firebase.FirebaseTooManyRequestsException -> "Ha habido demasiados intentos. Espera un momento y vuelve a probar."
    is com.google.firebase.firestore.FirebaseFirestoreException -> when (error.code) {
        com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED -> "No se puede acceder a esta mochila. Comprueba el código o la configuración de Firebase."
        com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAVAILABLE -> "No hay conexión con la mochila. Vuelve a intentarlo con Internet."
        else -> "No se ha podido completar el cambio. Vuelve a intentarlo."
    }
    is kotlinx.coroutines.TimeoutCancellationException -> "La conexión está tardando demasiado. Comprueba Internet y vuelve a intentarlo."
    is IllegalArgumentException, is IllegalStateException -> error.message ?: "Revisa los datos e inténtalo de nuevo."
    else -> "No se ha podido completar la operación. Vuelve a intentarlo."
}
