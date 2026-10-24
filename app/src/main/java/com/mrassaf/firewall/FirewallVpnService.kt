package com.mrassaf.firewall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log

class FirewallVpnService : VpnService() {
    private var tunnel: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopFirewall() else startFirewall()
        return START_NOT_STICKY
    }

    private fun startFirewall() {
        val selected = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getStringSet(KEY_BLOCKED_PACKAGES, emptySet()).orEmpty()
            .filter { it != packageName }.toSet()
        if (selected.isEmpty()) { stopSelf(); return }
        startForeground(NOTIFICATION_ID, buildNotification())
        try {
            tunnel?.close()
            val builder = Builder().setSession("ASSAF Firewall")
                .setMtu(1500).addAddress("10.111.0.1", 32)
                .addRoute("0.0.0.0", 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.addRoute("::", 0)
            selected.forEach { builder.addAllowedApplication(it) }
            tunnel = builder.establish()
            if (tunnel == null) { Log.e(TAG, "VPN tunnel could not be established"); stopSelf() }
            // Packets from selected apps are intentionally not forwarded: their traffic is blocked.
            // Unselected apps are outside this per-app VPN and retain normal network access.
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start firewall", e)
            stopSelf()
        }
    }

    private fun stopFirewall() {
        try { tunnel?.close() } catch (_: Exception) {}
        tunnel = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val channelId = "assaf_firewall_status"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(channelId, "ASSAF Firewall", NotificationManager.IMPORTANCE_LOW))
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, channelId) else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder.setContentTitle("ASSAF Firewall is active")
            .setContentText("Blocking internet for selected apps")
            .setSmallIcon(android.R.drawable.ic_lock_lock).setOngoing(true).build()
    }

    override fun onRevoke() { stopFirewall(); super.onRevoke() }
    override fun onDestroy() {
        try { tunnel?.close() } catch (_: Exception) {}
        tunnel = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.mrassaf.firewall.START"
        const val ACTION_STOP = "com.mrassaf.firewall.STOP"
        const val PREFS = "assaf_settings"
        const val KEY_BLOCKED_PACKAGES = "blocked_packages"
        private const val NOTIFICATION_ID = 7001
        private const val TAG = "ASSAF"
    }
}
