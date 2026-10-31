package com.mrassaf.firewall

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

class AssafTapAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var tapping = false
    private val tapTask = object : Runnable {
        override fun run() {
            if (!tapping) return
            val activePackage = rootInActiveWindow?.packageName?.toString().orEmpty()
            if (activePackage == "com.zhiliaoapp.musically" || activePackage == "com.ss.android.ugc.trill") {
                stopTapping()
                Toast.makeText(this@AssafTapAccessibilityService, "ASSAF tapper paused inside TikTok", Toast.LENGTH_LONG).show()
                return
            }
            val prefs = getSharedPreferences("assaf_tapper", MODE_PRIVATE)
            val x = prefs.getFloat("tap_x", resources.displayMetrics.widthPixels / 2f)
            val y = prefs.getFloat("tap_y", resources.displayMetrics.heightPixels / 2f)
            val interval = prefs.getLong("tap_interval", 1000L).coerceIn(300L, 10000L)
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                .build()
            dispatchGesture(gesture, null, null)
            handler.postDelayed(this, interval)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (getSharedPreferences("assaf_tapper", MODE_PRIVATE).getBoolean("enabled", false)) startTapping()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val prefs = getSharedPreferences("assaf_tapper", MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false)) {
            stopTapping()
            return
        }
        val pkg = event?.packageName?.toString().orEmpty()
        if (pkg == "com.zhiliaoapp.musically" || pkg == "com.ss.android.ugc.trill") {
            stopTapping()
        } else {
            startTapping()
        }
    }

    override fun onInterrupt() = stopTapping()

    private fun startTapping() {
        if (tapping) return
        tapping = true
        handler.removeCallbacks(tapTask)
        handler.post(tapTask)
    }

    private fun stopTapping() {
        tapping = false
        handler.removeCallbacks(tapTask)
        getSharedPreferences("assaf_tapper", MODE_PRIVATE).edit().putBoolean("enabled", false).apply()
    }

    override fun onDestroy() {
        stopTapping()
        super.onDestroy()
    }
}
