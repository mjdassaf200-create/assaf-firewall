package com.mrassaf.firewall

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = context.getSharedPreferences(FirewallVpnService.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ENABLED, false)) return
        val now = Calendar.getInstance()
        val start = prefs.getInt(KEY_START_HOUR, 22) * 60 + prefs.getInt(KEY_START_MINUTE, 0)
        val end = prefs.getInt(KEY_END_HOUR, 7) * 60 + prefs.getInt(KEY_END_MINUTE, 0)
        val minuteNow = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val shouldBlock = if (start == end) true else if (start < end) minuteNow >= start && minuteNow < end else minuteNow >= start || minuteNow < end
        val action = if (shouldBlock) FirewallVpnService.ACTION_START else FirewallVpnService.ACTION_STOP
        val serviceIntent = Intent(context, FirewallVpnService::class.java).setAction(action)
        if (shouldBlock && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(serviceIntent)
        else context.startService(serviceIntent)
        scheduleNext(context, start, end, now)
    }

    companion object {
        const val KEY_ENABLED = "schedule_enabled"
        const val KEY_START_HOUR = "schedule_start_hour"
        const val KEY_START_MINUTE = "schedule_start_minute"
        const val KEY_END_HOUR = "schedule_end_hour"
        const val KEY_END_MINUTE = "schedule_end_minute"
        private const val REQUEST_CODE = 8021

        fun reschedule(context: Context) {
            val prefs = context.getSharedPreferences(FirewallVpnService.PREFS, Context.MODE_PRIVATE)
            val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pending = pendingIntent(context)
            manager.cancel(pending)
            if (!prefs.getBoolean(KEY_ENABLED, false)) return
            val now = Calendar.getInstance()
            val start = prefs.getInt(KEY_START_HOUR, 22) * 60 + prefs.getInt(KEY_START_MINUTE, 0)
            val end = prefs.getInt(KEY_END_HOUR, 7) * 60 + prefs.getInt(KEY_END_MINUTE, 0)
            scheduleNext(context, start, end, now)
            // Apply the saved schedule immediately, not only at the next boundary.
            val intent = Intent(context, ScheduleReceiver::class.java).setAction(ACTION_TICK)
            context.sendBroadcast(intent)
        }

        private fun scheduleNext(context: Context, start: Int, end: Int, now: Calendar) {
            val minuteNow = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            val targets = if (start == end) listOf(start) else listOf(start, end)
            var next: Calendar? = null
            for (target in targets) {
                val candidate = (now.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, target / 60)
                    set(Calendar.MINUTE, target % 60)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_YEAR, 1)
                }
                if (next == null || candidate.timeInMillis < next!!.timeInMillis) next = candidate
            }
            val alarm = next ?: return
            val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pending = pendingIntent(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.timeInMillis, pending)
            else manager.set(AlarmManager.RTC_WAKEUP, alarm.timeInMillis, pending)
        }

        private fun pendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(context, REQUEST_CODE, Intent(context, ScheduleReceiver::class.java).setAction(ACTION_TICK),
                PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)

        const val ACTION_TICK = "com.mrassaf.firewall.SCHEDULE_TICK"
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        ScheduleReceiver.reschedule(context)
    }
}
