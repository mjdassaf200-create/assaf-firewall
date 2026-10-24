package com.mrassaf.firewall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class FirewallVpnService : VpnService() {
    private var tunnel: ParcelFileDescriptor? = null
    private var readerThread: Thread? = null
    private val reading = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopFirewall() else startFirewall()
        return START_NOT_STICKY
    }

    private fun startFirewall() {
        val selected = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getStringSet(KEY_BLOCKED_PACKAGES, emptySet()).orEmpty()
            .filter { it != packageName }.toSet()
        if (selected.isEmpty()) { stopFirewall(); return }
        startForeground(NOTIFICATION_ID, buildNotification())
        try {
            stopTunnelOnly()
            val builder = Builder().setSession("ASSAF Firewall")
                .setMtu(1500)
                .addAddress("10.111.0.1", 32)
                .addRoute("0.0.0.0", 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.addAddress("fd00:111::1", 128)
                builder.addRoute("::", 0)
            }
            selected.forEach { builder.addAllowedApplication(it) }
            tunnel = builder.establish()
            val established = tunnel
            if (established == null) {
                Log.e(TAG, "VPN tunnel could not be established")
                stopFirewall()
                return
            }
            // Read and intentionally discard packets, so selected apps cannot reach the network.
            reading.set(true)
            readerThread = thread(name = "ASSAF-VPN-dropper", isDaemon = true) {
                val buffer = ByteArray(32767)
                try {
                    FileInputStream(established.fileDescriptor).use { input ->
                        while (reading.get()) {
                            val count = input.read(buffer)
                            if (count > 0) {
                                val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
                                prefs.edit()
                                    .putLong(KEY_BLOCKED_BYTES, prefs.getLong(KEY_BLOCKED_BYTES, 0L) + count)
                                    .putLong(KEY_BLOCKED_PACKETS, prefs.getLong(KEY_BLOCKED_PACKETS, 0L) + 1L)
                                    .apply()
                            } else if (count < 0) break
                        }
                    }
                } catch (e: Exception) {
                    if (reading.get()) Log.w(TAG, "VPN packet reader stopped", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start firewall", e)
            stopFirewall()
        }
    }

    private fun stopTunnelOnly() {
        reading.set(false)
        try { tunnel?.close() } catch (_: Exception) {}
        tunnel = null
        readerThread = null
    }

    private fun stopFirewall() {
        stopTunnelOnly()
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
    override fun onDestroy() { stopTunnelOnly(); super.onDestroy() }

    companion object {
        const val ACTION_START = "com.mrassaf.firewall.START"
        const val ACTION_STOP = "com.mrassaf.firewall.STOP"
        const val PREFS = "assaf_settings"
        const val KEY_BLOCKED_PACKAGES = "blocked_packages"
        const val KEY_BLOCKED_BYTES = "blocked_bytes"
        const val KEY_BLOCKED_PACKETS = "blocked_packets"
        private const val NOTIFICATION_ID = 7001
        private const val TAG = "ASSAF"
    }
}
