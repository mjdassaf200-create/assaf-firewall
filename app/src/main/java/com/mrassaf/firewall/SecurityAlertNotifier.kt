package com.mrassaf.firewall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * Creates a quiet, WhatsApp-like heads-up security notification.
 * The heads-up presentation is controlled by Android; the notification itself
 * remains in the notification shade until the user opens or dismisses it.
 *
 * Call showSuspiciousApp only after a real detection engine has produced a
 * supported finding. Permission-review results alone are not malware verdicts.
 */
object SecurityAlertNotifier {
    private const val CHANNEL_ID = "assaf_security_alerts"
    private const val NOTIFICATION_ID = 7301

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "أمان",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "تنبيهات التهديدات الأمنية من أمان"
                    setShowBadge(true)
                    enableVibration(false)
                    setSound(null, null)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    fun showSuspiciousApp(
        context: Context,
        appName: String,
        packageName: String,
        source: String,
        reason: String
    ) {
        createChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val detailsIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_security_alert", true)
            putExtra("security_alert_app", appName)
            putExtra("security_alert_package", packageName)
            putExtra("security_alert_source", source)
            putExtra("security_alert_reason", reason)
        }
        val detailsPendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            detailsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // This only opens Android's own uninstall confirmation; it never silently removes an app.
        val uninstallIntent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
        val uninstallPendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID + 1,
            uninstallIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = "تم رصد تطبيق يحتاج إلى مراجعة: $appName"
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context).setPriority(Notification.PRIORITY_HIGH)
        }

        val notification = builder
            .setSmallIcon(R.drawable.ic_assaf_security)
            .setContentTitle("أمان")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(
                "$text\nالمصدر: $source\nسبب التنبيه: $reason\nافتح التفاصيل لتقرر الإزالة أو الإبقاء على التطبيق."
            ))
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(detailsPendingIntent)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .addAction(0, "التفاصيل", detailsPendingIntent)
            .addAction(0, "إزالة التطبيق", uninstallPendingIntent)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }
}
