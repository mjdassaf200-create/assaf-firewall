package com.mrassaf.firewall

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
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
import kotlin.math.abs

class TikTokTapAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var marker: TextView? = null
    private var control: TextView? = null
    private var running = false
    private var startedAt = 0L
    private var downX = 0f
    private var downY = 0f
    private var markerStartX = 0
    private var markerStartY = 0
    private val prefs by lazy { getSharedPreferences("assaf_tapper", MODE_PRIVATE) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        showFloatingControls()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() { stopTapping() }

    private fun showFloatingControls() {
        if (!Settings.canDrawOverlays(this) || marker != null || control != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val mark = TextView(this).apply {
            text = "A"
            textSize = 27f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setTextColor(Color.rgb(57, 157, 255))
            gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 0f, Color.rgb(8, 35, 70))
            contentDescription = "ASSAF tap position. Drag to choose where taps land."
            setOnTouchListener(markerTouchListener)
        }
        val savedX = prefs.getInt("marker_x", -1)
        val savedY = prefs.getInt("marker_y", -1)
        val markerParams = WindowManager.LayoutParams(
            dp(52), dp(52), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (savedX >= 0) savedX else (resources.displayMetrics.widthPixels / 2 - dp(26)).coerceAtLeast(0)
            y = if (savedY >= 0) savedY else (resources.displayMetrics.heightPixels / 2 - dp(26)).coerceAtLeast(0)
        }

        val button = TextView(this).apply {
            text = "▶"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(13), dp(9), dp(13), dp(9))
            background = GradientDrawable().apply {
                setColor(Color.rgb(24, 92, 180))
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.rgb(75, 180, 255))
            }
            contentDescription = "Start or stop ASSAF tapping"
            setOnClickListener { if (running) stopTapping() else startTapping() }
        }
        val controlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(14)
            y = (resources.displayMetrics.heightPixels / 2).coerceAtLeast(0)
        }

        try {
            windowManager?.addView(mark, markerParams)
            marker = mark
            windowManager?.addView(button, controlParams)
            control = button
        } catch (_: Exception) {
            removeFloatingControls()
        }
    }

    private val markerTouchListener = View.OnTouchListener { view, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                val params = view.layoutParams as WindowManager.LayoutParams
                markerStartX = params.x
                markerStartY = params.y
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                val params = view.layoutParams as WindowManager.LayoutParams
                params.x = (markerStartX + dx.toInt()).coerceIn(0, (resources.displayMetrics.widthPixels - view.width).coerceAtLeast(0))
                params.y = (markerStartY + dy.toInt()).coerceIn(0, (resources.displayMetrics.heightPixels - view.height).coerceAtLeast(0))
                runCatching { windowManager?.updateViewLayout(view, params) }
                true
            }
            MotionEvent.ACTION_UP -> {
                val params = view.layoutParams as WindowManager.LayoutParams
                prefs.edit().putInt("marker_x", params.x).putInt("marker_y", params.y).apply()
                true
            }
            MotionEvent.ACTION_CANCEL -> true
            else -> true
        }
    }

    private fun startTapping() {
        if (marker == null) {
            Toast.makeText(this, "ASSAF: floating marker is unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        val duration = prefs.getInt("duration", 30)
        running = true
        startedAt = System.currentTimeMillis()
        updateControl()
        handler.removeCallbacks(tapRunnable)
        handler.post(tapRunnable)
        if (duration > 0) handler.postDelayed({ stopTapping() }, duration * 1000L)
        Toast.makeText(this, "ASSAF: tapping started at A", Toast.LENGTH_SHORT).show()
    }

    private val tapRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val speed = prefs.getInt("speed", 10).coerceIn(1, 30)
            val duration = prefs.getInt("duration", 30)
            if (duration > 0 && System.currentTimeMillis() - startedAt >= duration * 1000L) {
                stopTapping()
                return
            }
            val mark = marker
            if (mark != null) {
                val location = IntArray(2)
                mark.getLocationOnScreen(location)
                val tapX = location[0] + mark.width / 2f
                val tapY = location[1] + mark.height / 2f
                val path = Path().apply { moveTo(tapX, tapY) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 35))
                    .build()
                dispatchGesture(gesture, null, null)
            }
            handler.postDelayed(this, (1000L / speed).coerceAtLeast(35L))
        }
    }

    private fun stopTapping() {
        val wasRunning = running
        running = false
        handler.removeCallbacks(tapRunnable)
        updateControl()
        if (wasRunning) Toast.makeText(this, "ASSAF: tapping stopped", Toast.LENGTH_SHORT).show()
    }

    private fun updateControl() {
        control?.text = if (running) "■" else "▶"
        (control?.background as? GradientDrawable)?.setColor(
            if (running) Color.rgb(190, 48, 58) else Color.rgb(24, 92, 180)
        )
        control?.contentDescription = if (running) "Stop ASSAF tapping" else "Start ASSAF tapping"
    }

    private fun removeFloatingControls() {
        try { marker?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        try { control?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        marker = null
        control = null
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        stopTapping()
        removeFloatingControls()
        super.onDestroy()
    }
}
