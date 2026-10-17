package es.salvapack.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ExpiryAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ReminderScheduler.ALARM_ACTION) ReminderScheduler(context).showDue()
    }
}

class ReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
            )) ReminderScheduler(context).restoreAfterSystemChange()
    }
}
