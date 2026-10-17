package es.salvapack.app.reminders

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Calendar dates stay as dates until the alarm is placed in the device's current timezone. */
object ReminderPolicy {
    val deliveryTime: LocalTime = LocalTime.of(9, 0)

    data class Entry(val productId: String, val expiryDate: LocalDate) {
        val key: String get() = "${productId.length}:$productId:$expiryDate"
        val reminderDate: LocalDate get() = expiryDate.minusMonths(1)

        fun scheduledInstant(zone: ZoneId): Instant =
            reminderDate.atTime(deliveryTime).atZone(zone).toInstant()

        fun isDue(now: Instant, zone: ZoneId): Boolean = !scheduledInstant(zone).isAfter(now)
    }

    data class Plan(
        val due: List<Entry>,
        val nextAlarm: Instant?,
        val retainedDeliveredKeys: Set<String>,
        val obsoleteKeys: Set<String>,
    )

    fun entry(productId: String, expiryDate: String?): Entry? {
        if (productId.isBlank() || expiryDate.isNullOrBlank()) return null
        return try {
            Entry(productId, LocalDate.parse(expiryDate)).also { it.reminderDate }
        } catch (_: DateTimeException) {
            null
        }
    }

    fun plan(
        entries: List<Entry>,
        deliveredKeys: Set<String>,
        previousKeys: Set<String>,
        now: Instant,
        zone: ZoneId,
    ): Plan {
        val unique = entries.distinctBy { it.key }
        val currentKeys = unique.mapTo(mutableSetOf()) { it.key }
        val pending = unique.filterNot { it.key in deliveredKeys }
        return Plan(
            due = pending.filter { it.isDue(now, zone) },
            nextAlarm = pending.asSequence().map { it.scheduledInstant(zone) }
                .filter { it.isAfter(now) }.minOrNull(),
            retainedDeliveredKeys = deliveredKeys.intersect(currentKeys),
            obsoleteKeys = (previousKeys + deliveredKeys) - currentKeys,
        )
    }

    /** Retry a denied/disabled notification without repeatedly waking the phone in a tight loop. */
    fun nextRetry(now: Instant, zone: ZoneId): Instant {
        val localNow = now.atZone(zone)
        val today = localNow.toLocalDate().atTime(deliveryTime).atZone(zone).toInstant()
        return if (today.isAfter(now)) today
        else localNow.toLocalDate().plusDays(1).atTime(deliveryTime).atZone(zone).toInstant()
    }
}
