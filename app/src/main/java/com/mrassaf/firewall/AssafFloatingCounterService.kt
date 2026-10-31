package com.mrassaf.firewall

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

class AssafFloatingCounterService : Service() {
    private lateinit var wm: WindowManager
    private var panel: LinearLayout? = null
    private var countView: TextView? = null
    private var running = false
    private var count = 0
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            count += 1
            countView?.text = "$count"
            handler.postDelayed(this, 1000L / 15L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (panel == null) showOverlay()
        return START_NOT_STICKY
    }

    private fun showOverlay() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(12, 10, 12, 10)
            background = GradientDrawable().apply {
                setColor(Color.rgb(9, 17, 32))
                cornerRadius = 24f
                setStroke(3, Color.rgb(57, 157, 255))
            }
        }
        val title = TextView(this).apply {
            text = "ASSAF • 15/s"
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        countView = TextView(this).apply {
            text = "0"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val start = TextView(this).apply {
            text = "▶"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(14, 6, 14, 6)
            setOnClickListener {
                if (!running) {
                    running = true
                    handler.removeCallbacks(tick)
                    handler.post(tick)
                }
            }
        }
        val stop = TextView(this).apply {
            text = "■"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(14, 6, 14, 6)
            setOnClickListener {
                running = false
                handler.removeCallbacks(tick)
            }
        }
        val close = TextView(this).apply {
            text = "×"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(10, 4, 10, 4)
            setOnClickListener { stopSelf() }
        }
        controls.addView(start)
        controls.addView(stop)
        controls.addView(close)
        root.addView(title)
        root.addView(countView)
        root.addView(controls)

        val type = if (android.os.Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 180
        }
        var downX = 0f
        var downY = 0f
        var origX = 0
        var origY = 0
        title.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    origX = params.x; origY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = origX + (event.rawX - downX).toInt()
                    params.y = origY + (event.rawY - downY).toInt()
                    runCatching { wm.updateViewLayout(root, params) }
                    true
                }
                else -> true
            }
        }
        wm.addView(root, params)
        panel = root
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(tick)
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        super.onDestroy()
    }
}