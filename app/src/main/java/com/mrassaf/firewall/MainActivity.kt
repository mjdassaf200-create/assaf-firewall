package com.mrassaf.firewall

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var listContainer: LinearLayout
    private lateinit var statusText: TextView
    private val prefs by lazy { getSharedPreferences(FirewallVpnService.PREFS, MODE_PRIVATE) }
    private var pendingStart = false

    private val vpnPermission = registerForActivityResult(android.app.ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK && pendingStart) { pendingStart = false; startFirewall() }
        else { pendingStart = false; Toast.makeText(this, "VPN permission is required.", Toast.LENGTH_LONG).show() }
    }

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); buildUi(); loadApps() }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(14))
            setBackgroundColor(0xFFF4F7FB.toInt())
        }
        root.addView(TextView(this).apply {
            text = "ASSAF"; textSize = 30f; gravity = Gravity.CENTER
            setTextColor(0xFF102A43.toInt()); typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, wrap())
        root.addView(TextView(this).apply {
            text = "YOUR DIGITAL WORLD, SECURED."; textSize = 12f; gravity = Gravity.CENTER
            setTextColor(0xFF52667A.toInt()); setPadding(0, dp(4), 0, dp(16))
        }, wrap())
        statusText = TextView(this).apply {
            text = "Choose apps to block"; textSize = 15f; setTextColor(0xFF102A43.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12)); setBackgroundColor(0xFFE2EAF3.toInt())
        }
        root.addView(statusText, wrap())
        val scroll = ScrollView(this)
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        scroll.addView(listContainer)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(Button(this).apply {
            text = "Apply & block selected apps"
            setOnClickListener { requestVpnAndStart() }
        }, wrap())
        root.addView(Button(this).apply {
            text = "Turn firewall off"
            setOnClickListener {
                startService(Intent(this@MainActivity, FirewallVpnService::class.java).setAction(FirewallVpnService.ACTION_STOP))
                statusText.text = "Firewall turned off"
            }
        }, wrap())
        setContentView(root)
    }

    private fun loadApps() {
        listContainer.removeAllViews()
        val selected = prefs.getStringSet(FirewallVpnService.KEY_BLOCKED_PACKAGES, emptySet()).orEmpty()
        val apps = packageManager.getInstalledApplications(0)
            .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null && it.packageName != packageName }
            .sortedBy { packageManager.getApplicationLabel(it).toString().lowercase() }
        for (app in apps) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(5), dp(8), dp(5)); setBackgroundColor(0xFFFFFFFF.toInt())
            }
            val icon = runCatching { packageManager.getApplicationIcon(app) }.getOrNull()
            if (icon != null) row.addView(android.widget.ImageView(this).apply {
                setImageDrawable(icon); layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(8) }
            })
            row.addView(TextView(this).apply {
                text = packageManager.getApplicationLabel(app); textSize = 15f; setTextColor(0xFF243B53.toInt())
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(CheckBox(this).apply {
                isChecked = selected.contains(app.packageName)
                setOnCheckedChangeListener { _, checked ->
                    val updated = prefs.getStringSet(FirewallVpnService.KEY_BLOCKED_PACKAGES, emptySet()).orEmpty().toMutableSet()
                    if (checked) updated.add(app.packageName) else updated.remove(app.packageName)
                    prefs.edit().putStringSet(FirewallVpnService.KEY_BLOCKED_PACKAGES, updated).apply()
                }
            })
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.bottomMargin = dp(4); listContainer.addView(row, params)
        }
        statusText.text = selected.size.toString() + " app(s) selected. Apply to block their internet."
    }

    private fun requestVpnAndStart() {
        val selected = prefs.getStringSet(FirewallVpnService.KEY_BLOCKED_PACKAGES, emptySet()).orEmpty()
        if (selected.isEmpty()) { Toast.makeText(this, "Select at least one app first.", Toast.LENGTH_LONG).show(); return }
        val intent = VpnService.prepare(this)
        if (intent != null) { pendingStart = true; vpnPermission.launch(intent) } else startFirewall()
    }

    private fun startFirewall() {
        val intent = Intent(this, FirewallVpnService::class.java).setAction(FirewallVpnService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        statusText.text = "Firewall requested. Check the VPN notification."
        Toast.makeText(this, "ASSAF firewall started.", Toast.LENGTH_SHORT).show()
    }

    private fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
