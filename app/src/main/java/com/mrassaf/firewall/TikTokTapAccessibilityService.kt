package com.mrassaf.firewall

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast

class TikTokTapAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var bubble: TextView? = null
    private var running = false
    private var startedAt = 0L
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private val prefs by lazy { getSharedPreferences("assaf_tapper", MODE_PRIVATE) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        showBubble()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() { stopTapping() }

    private fun showBubble() {
        if (!Settings.canDrawOverlays(this) || bubble != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = TextView(this).apply {
            text = "▶ ASSAF"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(18, 12, 18, 12)
            background = GradientDrawable().apply {
                setColor(Color.rgb(24, 92, 180))
                cornerRadius = 48f
                setStroke(2, Color.rgb(75, 180, 255))
            }
            setOnTouchListener(object : View.OnTouchListener {
                private var dragged = false
                override fun onTouch(v: View, e: MotionEvent): Boolean {
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = e.rawX; downY = e.rawY
                            dragged = false
                            val p = v.layoutParams as WindowManager.LayoutParams
                            startX = p.x; startY = p.y
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = e.rawX - downX
                            val dy = e.rawY - downY
                            if (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12) dragged = true
                            if (dragged) {
                                val p = v.layoutParams as WindowManager.LayoutParams
                                p.x = startX + dx.toInt()
                                p.y = startY + dy.toInt()
                                windowManager?.updateViewLayout(v, p)
                            }
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!dragged) {
                                if (running) stopTapping() else startTapping()
                            }
                            return true
                        }
                        MotionEvent.ACTION_CANCEL -> return true
                    }
                    return true
                }
            })
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 24; y = 260 }
        try { windowManager?.addView(view, params); bubble = view } catch (_: Exception) {}
    }

    private fun startTapping() {
        val duration = prefs.getInt("duration", 30)
        running = true
        startedAt = System.currentTimeMillis()
        updateBubble()
        handler.removeCallbacks(tapRunnable)
        handler.post(tapRunnable)
        if (duration > 0) handler.postDelayed({ stopTapping() }, duration * 1000L)
        Toast.makeText(this, "ASSAF: tapping started", Toast.LENGTH_SHORT).show()
    }

    private val tapRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val speed = prefs.getInt("speed", 10).coerceIn(5, 30)
            val duration = prefs.getInt("duration", 30)
            if (duration > 0 && System.currentTimeMillis() - startedAt >= duration * 1000L) {
                stopTapping(); return
            }
            val dm = resources.displayMetrics
            val path = Path().apply { moveTo(dm.widthPixels / 2f, dm.heightPixels / 2f) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 35))
                .build()
            dispatchGesture(gesture, null, null)
            handler.postDelayed(this, (1000L / speed).coerceAtLeast(35L))
        }
    }

    private fun stopTapping() {
        running = false
        handler.removeCallbacks(tapRunnable)
        handler.removeCallbacksAndMessages(null)
        updateBubble()
        Toast.makeText(this, "ASSAF: tapping stopped", Toast.LENGTH_SHORT).show()
    }

    private fun updateBubble() {
        bubble?.text = if (running) "■ STOP" else "▶ ASSAF"
        (bubble?.background as? GradientDrawable)?.setColor(
            if (running) Color.rgb(190, 48, 58) else Color.rgb(24, 92, 180)
        )
    }

    override fun onDestroy() {
        stopTapping()
        try { bubble?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        bubble = null
        super.onDestroy()
    }
}
