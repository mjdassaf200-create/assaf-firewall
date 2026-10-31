package com.mrassaf.firewall

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class AssafFloatingCounterService : Service() {
    private lateinit var wm: WindowManager
    private var target: TextView? = null
    private var controls: LinearLayout? = null
    private lateinit var targetParams: WindowManager.LayoutParams
    private lateinit var controlParams: WindowManager.LayoutParams
    private var running = false
    private var taps = 0
    private var countView: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (target == null) showOverlay()
        return START_NOT_STICKY
    }

    private fun params(x: Int, y: Int, flags: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
        flags, PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y }

    private fun showOverlay() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        targetParams = params(160, 380, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        target = TextView(this).apply {
            text = "A"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "ASSAF tap target — drag to the exact screen position"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(20, 110, 235))
                setStroke(3, Color.WHITE)
            }
        }
        val targetSize = (54 * resources.displayMetrics.density).toInt()
        wm.addView(target, targetParams.apply { width = targetSize; height = targetSize })
        var downX = 0f; var downY = 0f; var originX = 0; var originY = 0
        target?.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    originX = targetParams.x; originY = targetParams.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    targetParams.x = (originX + event.rawX - downX).toInt().coerceAtLeast(0)
                    targetParams.y = (originY + event.rawY - downY).toInt().coerceAtLeast(0)
                    runCatching { wm.updateViewLayout(view, targetParams) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }

        controlParams = params(12, 110, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(12, 8, 12, 8)
            background = GradientDrawable().apply {
                setColor(Color.rgb(9, 17, 32)); cornerRadius = 18f
                setStroke(2, Color.rgb(57, 157, 255))
            }
        }
        val heading = TextView(this).apply {
            text = "ASSAF • 15/s"; textSize = 12f
            typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        }
        countView = TextView(this).apply {
            text = "0 completed"; textSize = 12f
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(4, 5, 4, 5)
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
        fun addControl(label: String, action: () -> Unit) {
            row.addView(TextView(this).apply {
                text = label; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE); gravity = Gravity.CENTER
                setPadding(10, 8, 10, 8); setOnClickListener { action() }
            })
        }
        addControl("START") { startTapping() }
        addControl("STOP") { stopTapping() }
        addControl("×") { stopSelf() }
        controls?.addView(heading); controls?.addView(countView); controls?.addView(row)
        wm.addView(controls, controlParams)
    }

    private fun startTapping() {
        if (running) return
        val service = AssafTapAccessibilityService.instance
        if (service == null || !AssafTapAccessibilityService.isEnabled(this)) {
            Toast.makeText(this, "Enable ASSAF Tap Service in Accessibility settings first.", Toast.LENGTH_LONG).show()
            return
        }
        running = true
        targetParams.flags = targetParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(target, targetParams) }
        tapNext()
    }

    private fun tapNext() {
        if (!running) return
        val v = target ?: return
        val x = targetParams.x + v.width / 2f
        val y = targetParams.y + v.height / 2f
        val accepted = AssafTapAccessibilityService.instance?.tapAt(x, y) {
            if (running) {
                taps += 1
                countView?.text = "$taps completed"
            }
        } ?: false
        if (!accepted) {
            stopTapping()
            Toast.makeText(this, "Tap gesture was not accepted. Check Accessibility permission.", Toast.LENGTH_LONG).show()
            return
        }
        handler.postDelayed({ tapNext() }, 67L)
    }

    private fun stopTapping() {
        running = false
        handler.removeCallbacksAndMessages(null)
        if (::targetParams.isInitialized) {
            targetParams.flags = targetParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            target?.let { runCatching { wm.updateViewLayout(it, targetParams) } }
        }
    }

    override fun onDestroy() {
        stopTapping()
        target?.let { runCatching { wm.removeView(it) } }
        controls?.let { runCatching { wm.removeView(it) } }
        target = null; controls = null
        super.onDestroy()
    }
}