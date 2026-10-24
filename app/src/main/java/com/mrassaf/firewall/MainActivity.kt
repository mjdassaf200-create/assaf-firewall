package com.mrassaf.firewall

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var listContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var searchBox: EditText
    private lateinit var scheduleCheck: CheckBox
    private lateinit var startTimeButton: Button
    private lateinit var endTimeButton: Button
    private val prefs by lazy { getSharedPreferences(FirewallVpnService.PREFS, MODE_PRIVATE) }
    private var pendingStart = false
    private var arabic = false
    private var startHour = 22
    private var startMinute = 0
    private var endHour = 7
    private var endMinute = 0
    private val appRows = mutableListOf<Pair<String, View>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arabic = prefs.getBoolean(KEY_ARABIC, false)
        startHour = prefs.getInt(KEY_START_HOUR, 22)
        startMinute = prefs.getInt(KEY_START_MINUTE, 0)
        endHour = prefs.getInt(KEY_END_HOUR, 7)
        endMinute = prefs.getInt(KEY_END_MINUTE, 0)
        buildUi()
        loadApps()
        updateStats()
    }

    @Deprecated("Use platform callback; kept compatible without AndroidX dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST) {
            if (resultCode == RESULT_OK && pendingStart) startFirewall()
            else Toast.makeText(this, tr("VPN permission is required", "يلزم السماح باتصال VPN"), Toast.LENGTH_LONG).show()
            pendingStart = false
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(12))
            setBackgroundColor(0xFFF4F7FB.toInt())
        }
        root.addView(TextView(this).apply {
            text = "ASSAF FIREWALL"; textSize = 26f; gravity = Gravity.CENTER
            setTextColor(0xFF102A43.toInt()); typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, wrap())
        root.addView(Button(this).apply {
            text = tr("العربية / English", "English / العربية")
            setOnClickListener {
                arabic = !arabic
                prefs.edit().putBoolean(KEY_ARABIC, arabic).apply()
                buildUi(); loadApps(); updateStats()
            }
        }, wrap())
        statusText = TextView(this).apply {
            text = tr("Select apps to block", "اختر التطبيقات لحجب الإنترنت عنها")
            textSize = 14f; setTextColor(0xFF102A43.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12)); setBackgroundColor(0xFFE2EAF3.toInt())
        }
        root.addView(statusText, wrap())
        statsText = TextView(this).apply {
            textSize = 13f; setTextColor(0xFF243B53.toInt())
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        root.addView(statsText, wrap())
        searchBox = EditText(this).apply {
            hint = tr("Search apps…", "ابحث عن تطبيق…")
            singleLine = true
            addTextChangedListener(SimpleTextWatcher { filterApps(it) })
        }
        root.addView(searchBox, wrap())
        val scroll = ScrollView(this)
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listContainer)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(TextView(this).apply {
            text = tr("Daily schedule (uses device local time)", "جدولة يومية (حسب وقت الهاتف)")
            textSize = 15f; setTextColor(0xFF102A43.toInt()); setPadding(0, dp(8), 0, dp(4))
        }, wrap())
        scheduleCheck = CheckBox(this).apply {
            text = tr("Enable scheduled blocking", "تفعيل الحجب المجدول")
            isChecked = prefs.getBoolean(KEY_SCHEDULE_ENABLED, false)
        }
        root.addView(scheduleCheck, wrap())
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startTimeButton = Button(this).apply { setOnClickListener { chooseTime(true) } }
        endTimeButton = Button(this).apply { setOnClickListener { chooseTime(false) } }
        times.addView(startTimeButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        times.addView(endTimeButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(times, wrap())
        updateTimeButtons()
        root.addView(Button(this).apply {
            text = tr("Save schedule", "حفظ الجدولة")
            setOnClickListener { saveSchedule() }
        }, wrap())
        root.addView(Button(this).apply {
            text = tr("Apply & block selected apps", "تطبيق وحجب التطبيقات المحددة")
            setOnClickListener { requestVpnAndStart() }
        }, wrap())
        root.addView(Button(this).apply {
            text = tr("Turn firewall off", "إيقاف الجدار الناري")
            setOnClickListener {
                startService(Intent(this@MainActivity, FirewallVpnService::class.java).setAction(FirewallVpnService.ACTION_STOP))
                statusText.text = tr("Firewall stop requested", "تم طلب إيقاف الحجب")
            }
        }, wrap())
        root.addView(TextView(this).apply {
            text = tr("Network mode: blocking applies to selected apps on the active network. Android does not allow ordinary apps to switch Wi-Fi/mobile data off per app.", "وضع الشبكة: الحجب للتطبيقات المحددة عبر الشبكة النشطة. أندرويد لا يسمح للتطبيق العادي بإطفاء الواي فاي/بيانات الهاتف لكل تطبيق.")
            textSize = 11f; setTextColor(0xFF52667A.toInt()); setPadding(0, dp(8), 0, 0)
        }, wrap())
        setContentView(root)
    }

    private fun loadApps() {
        listContainer.removeAllViews()
        appRows.clear()
        val selected = prefs.getStringSet(FirewallVpnService.KEY_BLOCKED_PACKAGES, emptySet()).orEmpty()
        val apps = packageManager.getInstalledApplications(0)
            .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null && it.packageName != packageName }
            .sortedBy { packageManager.getApplicationLabel(it).toString().lowercase(Locale.ROOT) }
        for (app in apps) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(5), dp(8), dp(5)); setBackgroundColor(0xFFFFFFFF.toInt())
            }
            runCatching { packageManager.getApplicationIcon(app) }.getOrNull()?.let { icon ->
                row.addView(android.widget.ImageView(this).apply {
                    setImageDrawable(icon); layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(8) }
                })
            }
            row.addView(TextView(this).apply {
                text = packageManager.getApplicationLabel(app); textSize = 14f; setTextColor(0xFF243B53.toInt())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(CheckBox(this).apply {
                isChecked = selected.contains(app.packageName)
                setOnCheckedChangeListener { _, checked ->
                    val updated = prefs.getStringSet(FirewallVpnService.KEY_BLOCKED_PACKAGES, emptySet()).orEmpty().toMutableSet()
                    if (checked) updated.add(app.packageName) else updated.remove(app.packageName)
                    prefs.edit().putStringSet(FirewallVpnService.KEY_BLOCKED_PACKAGES, updated).apply()
                    statusText.text = tr("${updated.size} apps selected", "التطبيقات المحددة: ${updated.size}")
                }
            })
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.bottomMargin = dp(4)
            appRows.add(app.packageName to row)
            listContainer.addView(row, params)
        }
        statusText.text = tr("${selected.size} apps selected. Tap Apply to block.", "التطبيقات المحددة: ${selected.size}. اضغط تطبيق لبدء الحجب.")
    }

    private fun filterApps(query: String) {
        val q = query.trim().lowercase(Locale.ROOT)
        appRows.forEach { (pkg, row) ->
            val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            row.visibility = if (q.isEmpty() || label.lowercase(Locale.ROOT).contains(q) || pkg.lowercase(Locale.ROOT).contains(q)) View.VISIBLE else View.GONE
        }
    }

    private fun chooseTime(start: Boolean) {
        val h = if (start) startHour else endHour
        val m = if (start) startMinute else endMinute
        TimePickerDialog(this, { _, hour, minute ->
            if (start) { startHour = hour; startMinute = minute } else { endHour = hour; endMinute = minute }
            updateTimeButtons()
        }, h, m, true).show()
    }

    private fun updateTimeButtons() {
        if (!::startTimeButton.isInitialized || !::endTimeButton.isInitialized) return
        startTimeButton.text = tr("Start", "البداية") + ": " + "%02d:%02d".format(startHour, startMinute)
        endTimeButton.text = tr("End", "النهاية") + ": " + "%02d:%02d".format(endHour, endMinute)
    }

    private fun saveSchedule() {
        prefs.edit().putBoolean(KEY_SCHEDULE_ENABLED, scheduleCheck.isChecked)
            .putInt(KEY_START_HOUR, startHour).putInt(KEY_START_MINUTE, startMinute)
            .putInt(KEY_END_HOUR, endHour).putInt(KEY_END_MINUTE, endMinute).apply()
        ScheduleReceiver.reschedule(this)
        Toast.makeText(this, tr("Schedule saved", "تم حفظ الجدولة"), Toast.LENGTH_SHORT).show()
    }

    private fun requestVpnAndStart() {
        val selected = prefs.getStringSet(FirewallVpnService.KEY_BLOCKED_PACKAGES, emptySet()).orEmpty()
        if (selected.isEmpty()) {
            Toast.makeText(this, tr("Select at least one app first", "اختر تطبيقًا واحدًا على الأقل"), Toast.LENGTH_LONG).show()
            return
        }
        val intent = VpnService.prepare(this)
        if (intent != null) { pendingStart = true; @Suppress("DEPRECATION") startActivityForResult(intent, VPN_REQUEST) }
        else startFirewall()
    }

    private fun startFirewall() {
        val intent = Intent(this, FirewallVpnService::class.java).setAction(FirewallVpnService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        statusText.text = tr("Firewall start requested; verify the VPN icon/notification.", "تم طلب التشغيل؛ تحقق من إشعار VPN.")
    }

    private fun updateStats() {
        if (!::statsText.isInitialized) return
        val blockedBytes = prefs.getLong(FirewallVpnService.KEY_BLOCKED_BYTES, 0L)
        val packets = prefs.getLong(FirewallVpnService.KEY_BLOCKED_PACKETS, 0L)
        val rx = android.net.TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
        val tx = android.net.TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
        statsText.text = tr(
            "Blocked tunnel: ${formatBytes(blockedBytes)} • ${packets} packets\nDevice total (not per-app): ↓ ${formatBytes(rx)}  ↑ ${formatBytes(tx)}",
            "حركة النفق المحجوبة: ${formatBytes(blockedBytes)} • ${packets} حزمة\nإجمالي الجهاز (ليس لكل تطبيق): ↓ ${formatBytes(rx)}  ↑ ${formatBytes(tx)}"
        )
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "${bytes} B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }

    private fun tr(en: String, ar: String) = if (arabic) ar else en
    private fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val VPN_REQUEST = 901
        private const val KEY_ARABIC = "ui_arabic"
        private const val KEY_SCHEDULE_ENABLED = "schedule_enabled"
        private const val KEY_START_HOUR = "schedule_start_hour"
        private const val KEY_START_MINUTE = "schedule_start_minute"
        private const val KEY_END_HOUR = "schedule_end_hour"
        private const val KEY_END_MINUTE = "schedule_end_minute"
    }
}

private class SimpleTextWatcher(private val changed: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed(s?.toString().orEmpty()) }
    override fun afterTextChanged(s: android.text.Editable?) = Unit
}
