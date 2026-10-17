package es.salvapack.app.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import es.salvapack.app.MainActivity
import es.salvapack.app.R
import es.salvapack.app.data.Product
import es.salvapack.app.data.allExpiryDates
import es.salvapack.app.data.ReminderInventoryReader
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * One calendar month before expiry, aiming for 09:00 local time. Android may defer inexact
 * alarms and background sync. Already-due items are checked again when the app is opened.
 *
 * Start only for an active inventory. Call [clear] BEFORE signing out or switching accounts.
 * All mutations share a process-wide lock; a generation invalidates in-flight remote reads.
 */
class ReminderScheduler(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val alarms = app.getSystemService(AlarmManager::class.java)
    private val notifications = app.getSystemService(NotificationManager::class.java)

    fun startBackgroundSync() = synchronized(lock) {
        if (ReminderInventoryReader.currentScope(app) == null) return@synchronized
        if (!prefs.getBoolean(ENABLED, false)) {
            prefs.edit().putBoolean(ENABLED, true)
                .putLong(GENERATION, generation() + 1).commit()
        }
        createChannel()
        enqueuePeriodicSync()
    }

    fun reconcile(products: List<Product>) = synchronized(lock) {
        if (!isEnabled()) return@synchronized
        val scope = ReminderInventoryReader.currentScope(app) ?: return@synchronized
        reconcileLocked(products, scope)
    }

    /** App-level permission alone is insufficient when the expiry channel has been disabled. */
    fun notificationsEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                app, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED) return false
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < 26 ||
            notifications.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** No network operation: the alarm always checks the latest cache for the active session. */
    fun showDue() = synchronized(lock) {
        if (!isEnabled()) return@synchronized
        val current = ReminderInventoryReader.cached(app)
        if (current == null) {
            if (ReminderInventoryReader.currentScope(app) == null) clear()
            return@synchronized
        }
        reconcileLocked(current.products, current.scope)
    }

    fun clear() = synchronized(lock) {
        // Persist invalidation first, so a canceled worker cannot recreate reminders afterward.
        prefs.edit().putBoolean(ENABLED, false).putLong(GENERATION, generation() + 1)
            .remove(DELIVERED).remove(KNOWN).remove(SCOPE).commit()
        alarms.cancel(alarmIntent())
        notifications.cancelAll()
        WorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)
    }

    internal fun activeGeneration(): Long? = synchronized(lock) {
        if (isEnabled()) generation() else null
    }

    internal fun reconcileFromWorker(scope: String, expectedGeneration: Long) =
        synchronized(lock) {
            if (!isEnabled() || generation() != expectedGeneration ||
                !ReminderInventoryReader.isCurrent(app, scope)) return@synchronized
            // A foreground edit may have replaced the remote result before this lock was acquired.
            val latest = ReminderInventoryReader.cached(app) ?: return@synchronized
            if (latest.scope == scope) reconcileLocked(latest.products, scope)
        }

    internal fun clearIfSessionLost(expectedGeneration: Long) = synchronized(lock) {
        if (isEnabled() && generation() == expectedGeneration &&
            ReminderInventoryReader.currentScope(app) == null) clear()
    }

    internal fun restoreAfterSystemChange() = synchronized(lock) {
        if (!isEnabled()) return@synchronized
        showDue()
        if (isEnabled()) enqueuePeriodicSync()
    }

    private fun reconcileLocked(products: List<Product>, scope: String) {
        if (!ReminderInventoryReader.isCurrent(app, scope)) return
        createChannel()
        if (prefs.getString(SCOPE, null) != scope) {
            alarms.cancel(alarmIntent())
            notifications.cancelAll()
            prefs.edit().putString(SCOPE, scope).remove(DELIVERED).remove(KNOWN).commit()
        }

        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val entries = products.flatMap { product ->
            product.allExpiryDates.mapNotNull { date -> ReminderPolicy.entry(product.id, date) }
        }
        val plan = ReminderPolicy.plan(entries, storedKeys(DELIVERED), storedKeys(KNOWN), now, zone)
        plan.obsoleteKeys.forEach { notifications.cancel(notificationTag(it), NOTIFICATION_ID) }
        val delivered = plan.retainedDeliveredKeys.toMutableSet()
        val productsById = products.associateBy { it.id }
        var needsRetry = false
        for (entry in plan.due) {
            val product = productsById[entry.productId] ?: continue
            // Session can also change via Firebase, outside this scheduler's lock.
            if (!ReminderInventoryReader.isCurrent(app, scope)) return
            if (publish(product, entry, now.atZone(zone).toLocalDate())) {
                delivered.add(entry.key)
                prefs.edit().putStringSet(DELIVERED, delivered.toSet()).commit()
            } else {
                needsRetry = true
            }
        }
        prefs.edit().putStringSet(DELIVERED, delivered.toSet())
            .putStringSet(KNOWN, entries.mapTo(mutableSetOf()) { it.key }).commit()

        alarms.cancel(alarmIntent())
        val retry = if (needsRetry) ReminderPolicy.nextRetry(now, zone) else null
        val next = listOfNotNull(plan.nextAlarm, retry).minOrNull()
        if (next != null && ReminderInventoryReader.isCurrent(app, scope)) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toEpochMilli(), alarmIntent())
        }
    }

    private fun publish(product: Product, entry: ReminderPolicy.Entry, today: LocalDate): Boolean {
        if (!notificationsEnabled()) return false

        val days = ChronoUnit.DAYS.between(today, entry.expiryDate)
        val formattedDate = entry.expiryDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        val message = when {
            days < 0 -> "Caducó el $formattedDate. Revisa tu mochila."
            days == 0L -> "Caduca hoy. Revisa tu mochila."
            else -> "Caduca el $formattedDate. Es el momento de revisarlo."
        }
        val open = Intent(app, MainActivity::class.java)
            .setData(Uri.parse("salvapack://product/${Uri.encode(product.id)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("product_id", product.id)
        val pendingOpen = PendingIntent.getActivity(
            app, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(product.name)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pendingOpen)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        return try {
            notifications.notify(notificationTag(entry.key), NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            // Permission can be revoked between the permission check and notify().
            false
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Caducidad de productos", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Avisos un mes antes de la fecha de caducidad" },
            )
        }
    }

    private fun enqueuePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<ReminderSyncWorker>(12, TimeUnit.HOURS).build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        app, 0, Intent(app, ExpiryAlarmReceiver::class.java).setAction(ALARM_ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun storedKeys(key: String): Set<String> = prefs.getStringSet(key, emptySet()).orEmpty().toSet()
    private fun isEnabled(): Boolean = prefs.getBoolean(ENABLED, false)
    private fun generation(): Long = prefs.getLong(GENERATION, 0)
    private fun notificationTag(key: String): String = "salvapack.expiry:$key"

    companion object {
        private val lock = Any()
        private const val PREFERENCES = "salvapack_reminders"
        private const val ENABLED = "enabled"
        private const val GENERATION = "generation"
        private const val DELIVERED = "delivered"
        private const val KNOWN = "known"
        private const val SCOPE = "scope"
        private const val CHANNEL_ID = "expiry_reminders"
        private const val NOTIFICATION_ID = 1
        private const val WORK_NAME = "salvapack_inventory_reminders"
        internal const val ALARM_ACTION = "es.salvapack.app.EXPIRY_ALARM"
    }
}
