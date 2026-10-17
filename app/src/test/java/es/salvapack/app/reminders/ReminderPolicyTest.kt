package es.salvapack.app.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ReminderPolicyTest {
    private val madrid = ZoneId.of("Europe/Madrid")

    @Test fun `March 31 clamps to February 28 in a common year`() {
        assertEquals(LocalDate.of(2027, 2, 28), entry("food", "2027-03-31").reminderDate)
    }

    @Test fun `March 31 clamps to February 29 in a leap year`() {
        assertEquals(LocalDate.of(2028, 2, 29), entry("food", "2028-03-31").reminderDate)
    }

    @Test fun `January expiration crosses into the previous year`() {
        assertEquals(LocalDate.of(2026, 12, 10), entry("food", "2027-01-10").reminderDate)
    }

    @Test fun `a calendar month is not thirty days`() {
        assertEquals(LocalDate.of(2027, 3, 15), entry("food", "2027-04-15").reminderDate)
        assertEquals(LocalDate.of(2027, 2, 15), entry("food", "2027-03-15").reminderDate)
    }

    @Test fun `missing and malformed dates do not generate reminders`() {
        assertNull(ReminderPolicy.entry("food", null))
        assertNull(ReminderPolicy.entry("food", ""))
        assertNull(ReminderPolicy.entry("food", "31/03/2027"))
        assertNull(ReminderPolicy.entry("food", "2027-02-29"))
        assertNull(ReminderPolicy.entry("", "2027-02-28"))
    }

    @Test fun `delivery starts at nine local time and not at the UTC day boundary`() {
        val item = entry("food", "2027-02-15")
        assertFalse(item.isDue(Instant.parse("2027-01-14T23:00:00Z"), madrid))
        assertFalse(item.isDue(Instant.parse("2027-01-15T07:59:59Z"), madrid))
        assertTrue(item.isDue(Instant.parse("2027-01-15T08:00:00Z"), madrid))
    }

    @Test fun `summer delivery uses daylight savings offset`() {
        assertEquals(Instant.parse("2027-07-15T07:00:00Z"), entry("food", "2027-08-15").scheduledInstant(madrid))
    }

    @Test fun `timezone change preserves calendar date and local nine oclock`() {
        val item = entry("food", "2027-02-15")
        val losAngeles = ZoneId.of("America/Los_Angeles")
        assertEquals(Instant.parse("2027-01-15T17:00:00Z"), item.scheduledInstant(losAngeles))
        assertEquals(LocalDate.of(2027, 1, 15), item.scheduledInstant(losAngeles).atZone(losAngeles).toLocalDate())
    }

    @Test fun `new product within its final month catches up immediately`() {
        val item = entry("medicine", "2027-02-20")
        val plan = plan(listOf(item), "2027-02-05T06:00:00Z")
        assertEquals(listOf(item), plan.due)
        assertNull(plan.nextAlarm)
    }

    @Test fun `expired products missed while phone was off also catch up`() {
        val item = entry("medicine", "2027-01-20")
        assertEquals(listOf(item), plan(listOf(item), "2027-02-05T12:00:00Z").due)
    }

    @Test fun `delivered product is not notified a second time`() {
        val item = entry("food", "2027-02-20")
        val plan = ReminderPolicy.plan(listOf(item, item), setOf(item.key), setOf(item.key),
            Instant.parse("2027-02-05T12:00:00Z"), madrid)
        assertTrue(plan.due.isEmpty())
        assertEquals(setOf(item.key), plan.retainedDeliveredKeys)
        assertNull(plan.nextAlarm)
    }

    @Test fun `duplicate product in one snapshot yields just one reminder`() {
        val item = entry("food", "2027-02-20")
        assertEquals(listOf(item), plan(listOf(item, item), "2027-02-05T12:00:00Z").due)
    }

    @Test fun `changed expiration cancels the old key and schedules a new reminder`() {
        val original = entry("food", "2027-02-20")
        val updated = entry("food", "2027-05-20")
        val plan = ReminderPolicy.plan(listOf(updated), setOf(original.key), setOf(original.key),
            Instant.parse("2027-02-05T12:00:00Z"), madrid)
        assertEquals(setOf(original.key), plan.obsoleteKeys)
        assertTrue(plan.retainedDeliveredKeys.isEmpty())
        assertTrue(plan.due.isEmpty())
        assertEquals(Instant.parse("2027-04-20T07:00:00Z"), plan.nextAlarm)
    }

    @Test fun `changed expiration already due is eligible even if previous date was delivered`() {
        val original = entry("food", "2027-02-20")
        val updated = entry("food", "2027-02-22")
        val plan = ReminderPolicy.plan(listOf(updated), setOf(original.key), setOf(original.key),
            Instant.parse("2027-02-05T12:00:00Z"), madrid)
        assertEquals(listOf(updated), plan.due)
    }

    @Test fun `deleting product or removing date clears its delivered state and notification`() {
        val original = entry("food", "2027-02-20")
        val plan = ReminderPolicy.plan(emptyList(), setOf(original.key), setOf(original.key),
            Instant.parse("2027-02-05T12:00:00Z"), madrid)
        assertEquals(setOf(original.key), plan.obsoleteKeys)
        assertTrue(plan.retainedDeliveredKeys.isEmpty())
        assertNull(plan.nextAlarm)
    }

    @Test fun `plan selects earliest future date independently of input order`() {
        val later = entry("food", "2027-05-20")
        val next = entry("medicine", "2027-03-20")
        val past = entry("water", "2027-02-20")
        val plan = plan(listOf(later, next, past), "2027-02-05T12:00:00Z")
        assertEquals(listOf(past), plan.due)
        assertEquals(Instant.parse("2027-02-20T08:00:00Z"), plan.nextAlarm)
    }

    @Test fun `delivered notification is canceled even if process stopped before storing known keys`() {
        val original = entry("food", "2027-02-20")
        val plan = ReminderPolicy.plan(emptyList(), setOf(original.key), emptySet(),
            Instant.parse("2027-02-05T12:00:00Z"), madrid)
        assertEquals(setOf(original.key), plan.obsoleteKeys)
    }

    @Test fun `denied notification can be retried until delivery is recorded`() {
        val item = entry("food", "2027-02-20")
        val first = plan(listOf(item), "2027-02-05T12:00:00Z")
        val retry = plan(listOf(item), "2027-02-06T12:00:00Z")
        assertEquals(first.due, retry.due)
        assertEquals(Instant.parse("2027-02-06T08:00:00Z"),
            ReminderPolicy.nextRetry(Instant.parse("2027-02-05T12:00:00Z"), madrid))
    }

    @Test fun `retry at nine advances to next day and follows DST`() {
        assertEquals(Instant.parse("2027-03-28T07:00:00Z"),
            ReminderPolicy.nextRetry(Instant.parse("2027-03-27T08:00:00Z"), madrid))
    }

    private fun entry(id: String, expiry: String) = requireNotNull(ReminderPolicy.entry(id, expiry))

    private fun plan(entries: List<ReminderPolicy.Entry>, now: String): ReminderPolicy.Plan =
        ReminderPolicy.plan(entries, emptySet(), emptySet(), Instant.parse(now), madrid)
}
