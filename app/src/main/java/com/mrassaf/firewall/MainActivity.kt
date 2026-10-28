package com.mrassaf.firewall

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
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
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.text.InputType
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var listContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var searchBox: EditText
    private lateinit var scheduleCheck: CheckBox
    private lateinit var startTimeButton: Button
    private lateinit var endTimeButton: Button
    private lateinit var countText: TextView
    private lateinit var cloudStatusText: TextView
    private val prefs by lazy { getSharedPreferences(FirewallVpnService.PREFS, MODE_PRIVATE) }
    private var pendingStart = false
    private var showAppsScreen = false
    private var arabic = true
    private var startHour = 22
    private var startMinute = 0
    private var endHour = 7
    private var endMinute = 0
    private val appRows = mutableListOf<Pair<String, View>>()

    private val navy = Color.rgb(9, 17, 32)
    private val panel = Color.rgb(18, 31, 52)
    private val panelLight = Color.rgb(25, 42, 67)
    private val blue = Color.rgb(57, 157, 255)
    private val white = Color.rgb(242, 247, 255)
    private val muted = Color.rgb(151, 170, 197)
    private val green = Color.rgb(67, 211, 155)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arabic = prefs.getBoolean(KEY_ARABIC, true)
        startHour = prefs.getInt(KEY_START_HOUR, 22)
        startMinute = prefs.getInt(KEY_START_MINUTE, 0)
        endHour = prefs.getInt(KEY_END_HOUR, 7)
        endMinute = prefs.getInt(KEY_END_MINUTE, 0)
        buildUi()
        if (showAppsScreen) loadApps()
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
        if (showAppsScreen) buildAppsScreen() else buildHomeScreen()
    }

    // Main screen: keep the approved ASSAF artwork as the first thing the user sees.
    // The Apps control is a real button, not a decorative part of the image.
    private fun buildHomeScreen() {
        // Show the approved 27 October artwork as the actual home screen.
        // The invisible hit area sits over the Apps tile printed in that artwork.
        val root = FrameLayout(this).apply {
            layoutDirection = if (arabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            setBackgroundColor(navy)
        }
        val artwork = ImageView(this).apply {
            setImageResource(R.drawable.assaf_main_interface)
            scaleType = ImageView.ScaleType.FIT_XY
            contentDescription = tr("ASSAF main screen", "الشاشة الرئيسية لتطبيق عساف")
            isClickable = false
            isFocusable = false
        }
        root.addView(artwork, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val appsHotspot = View(this).apply {
            contentDescription = tr("Apps — open application list", "قائمة التطبيقات — فتح التطبيقات")
            isClickable = true
            isFocusable = true
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                showAppsScreen = true
                buildUi()
                loadApps()
            }
        }
        root.addView(appsHotspot, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START))

        // Coordinates follow the Apps card in the approved 1024x1536 artwork.
        root.post {
            val w = root.width
            val h = root.height
            val lp = appsHotspot.layoutParams as FrameLayout.LayoutParams
            lp.width = (w * 0.215f).toInt()
            lp.height = (h * 0.125f).toInt()
            lp.leftMargin = (w * 0.515f).toInt()
            lp.topMargin = (h * 0.465f).toInt()
            appsHotspot.layoutParams = lp
        }

        // Keep language switching accessible without replacing the artwork.
        val languageButton = Button(this).apply {
            text = if (arabic) "English" else "العربية"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            setTextColor(white)
            background = rounded(Color.argb(205, 7, 27, 59), 18)
            setOnClickListener {
                arabic = !arabic
                prefs.edit().putBoolean(KEY_ARABIC, arabic).apply()
                buildUi()
            }
        }
        root.addView(languageButton, FrameLayout.LayoutParams(
            dp(100), dp(40), Gravity.TOP or Gravity.END
        ).apply {
            topMargin = dp(10)
            marginEnd = dp(10)
        })
        setContentView(root)
    }

    // Separate app screen: only the six requested apps appear here.
    private fun buildAppsScreen() {
        val scroll = ScrollView(this).apply {
            setFillViewport(true)
            setBackgroundResource(R.drawable.assaf_main_interface)
            isVerticalScrollBarEnabled = false
            layoutDirection = if (arabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
            setBackgroundResource(R.drawable.assaf_background)
            layoutDirection = if (arabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        scroll.addView(root)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(smallButton().apply {
            text = tr("← Back", "رجوع ←")
            setOnClickListener {
                showAppsScreen = false
                buildUi()
            }
        }, LinearLayout.LayoutParams(dp(100), dp(44)))
        top.addView(TextView(this).apply {
            text = tr("YOUR APPLICATIONS", "التطبيقات")
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(white)
            setPadding(dp(8), 0, dp(8), 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(top, wrap())

        statusText = TextView(this).apply {
            textSize = 13f
            setTextColor(white)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(panel, 14)
        }
        val statusParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        statusParams.topMargin = dp(14)
        root.addView(statusText, statusParams)

        countText = TextView(this).apply {
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(14), 0, dp(4))
        }
        root.addView(countText, wrap())

        searchBox = EditText(this).apply {
            hint = tr("Search installed apps", "ابحث في تطبيقات الهاتف")
            setSingleLine(true)
            textSize = 14f
            setTextColor(white)
            setHintTextColor(muted)
            background = rounded(panel, 13)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addTextChangedListener(SimpleTextWatcher { filterApps(it) })
        }
        val searchParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        searchParams.topMargin = dp(10)
        root.addView(searchBox, searchParams)

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, dp(4))
        }
        root.addView(listContainer, wrap())

        root.addView(primaryButton(tr("Apply & start protection", "تطبيق وبدء الحماية")).apply {
            setOnClickListener { requestVpnAndStart() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        root.addView(smallButton().apply {
            text = tr("Turn firewall off", "إيقاف جدار الحماية")
            setOnClickListener {
                startService(Intent(this@MainActivity, FirewallVpnService::class.java).setAction(FirewallVpnService.ACTION_STOP))
                statusText.text = tr("Firewall stop requested", "تم طلب إيقاف الحجب")
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        setContentView(scroll)
    }

    private fun showAccountDialog() {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val emailInput = EditText(this).apply {
            hint = tr("Email address", "البريد الإلكتروني")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine(true)
        }
        val passwordInput = EditText(this).apply {
            hint = tr("Password (10+ characters for new accounts)", "كلمة المرور (10 أحرف على الأقل للحساب الجديد)")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val registerCheck = CheckBox(this).apply {
            text = tr("Create a new account", "إنشاء حساب جديد")
            setTextColor(white)
        }
        form.addView(emailInput, wrap())
        form.addView(passwordInput, wrap())
        form.addView(registerCheck, wrap())
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(tr("ASSAF Cloud Account", "حساب ASSAF السحابي"))
            .setView(form)
            .setNegativeButton(tr("Cancel", "إلغاء"), null)
            .setPositiveButton(tr("Continue", "متابعة"), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val email = emailInput.text.toString().trim()
                val password = passwordInput.text.toString()
                if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    emailInput.error = tr("Enter a valid email", "أدخل بريدًا إلكترونيًا صحيحًا")
                    return@setOnClickListener
                }
                if (password.length < if (registerCheck.isChecked) 10 else 1) {
                    passwordInput.error = tr("Password is too short", "كلمة المرور قصيرة")
                    return@setOnClickListener
                }
                cloudStatusText.text = tr("Connecting securely…", "جارٍ الاتصال بشكل آمن…")
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
                Thread {
                    val result = runCatching {
                        AssafApiClient.authenticate(email, password, registerCheck.isChecked)
                    }
                    runOnUiThread {
                        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        result.onSuccess { token ->
                            prefs.edit().putString(KEY_API_TOKEN, token).putString(KEY_ACCOUNT_EMAIL, email).apply()
                            cloudStatusText.text = tr("Signed in • Cloud sync ready", "تم تسجيل الدخول • المزامنة السحابية جاهزة")
                            Toast.makeText(this, tr("ASSAF account connected", "تم ربط حساب ASSAF"), Toast.LENGTH_LONG).show()
                            dialog.dismiss()
                            syncCloudSettingChanges()
                        }.onFailure {
                            cloudStatusText.text = tr("Sign-in failed", "فشل تسجيل الدخول")
                            android.app.AlertDialog.Builder(this)
                                .setTitle(tr("Could not connect", "تعذر الاتصال"))
                                .setMessage(it.message ?: tr("Please try again.", "حاول مرة أخرى."))
                                .setPositiveButton(tr("OK", "حسنًا"), null).show()
                        }
                    }
                }.start()
            }
        }
        dialog.show()
    }

    private fun syncCloudSettingChanges() {
        val token = prefs.getString(KEY_API_TOKEN, null) ?: return
        val localSelected = prefs.getStringSet(FirewallVpnService.KEY_BLOCKED_PACKAGES, emptySet()).orEmpty().toSet()
        Thread {
            val result = runCatching {
                val remote = AssafApiClient.listSettings(token)
                val remoteSettings = mutableMapOf<String, Boolean>()
                for (i in 0 until remote.length()) {
                    val item = remote.optJSONObject(i) ?: continue
                    val packageName = item.optString("package_name")
                    if (packageName.isNotBlank()) remoteSettings[packageName] = item.optBoolean("blocked", false)
                }

                val installedLaunchable = packageManager.getInstalledApplications(0)
                    .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null && it.packageName != packageName }
                    .map { it.packageName }
                    .toSet()
                val merged = localSelected.filter { it in installedLaunchable }.toMutableSet()

                // Cloud values restore settings on this device; only apply packages installed here.
                remoteSettings.forEach { (pkg, blocked) ->
                    if (pkg in installedLaunchable) {
                        if (blocked) merged.add(pkg) else merged.remove(pkg)
                    }
                }

                // Upload local selections that have never been saved to the account.
                merged.filter { it in installedLaunchable && it !in remoteSettings }
                    .forEach { AssafApiClient.saveSetting(token, it, true) }

                val savedCount = AssafApiClient.listSettings(token).length()
                Pair(merged, savedCount)
            }
            runOnUiThread {
                result.onSuccess { (merged, count) ->
                    prefs.edit().putStringSet(FirewallVpnService.KEY_BLOCKED_PACKAGES, merged).apply()
                    loadApps()
                    if (prefs.getBoolean(FirewallVpnService.KEY_FIREWALL_ACTIVE, false)) {
                        if (merged.isEmpty()) {
                            startService(Intent(this, FirewallVpnService::class.java).setAction(FirewallVpnService.ACTION_STOP))
                        } else {
                            val serviceIntent = Intent(this, FirewallVpnService::class.java).setAction(FirewallVpnService.ACTION_START)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent) else startService(serviceIntent)
                        }
                    }
                    cloudStatusText.text = tr("Cloud connected • $count settings synced", "السحابة متصلة • تمت مزامنة $count إعدادات")
                }.onFailure {
                    cloudStatusText.text = tr("Signed in • Sync needs retry", "تم تسجيل الدخول • تحتاج المزامنة لإعادة المحاولة")
                }
            }
        }.start()
    }

    private fun loadApps() {
        listContainer.removeAllViews()
        appRows.clear()
        val selected = prefs.getStringSet(FirewallVpnService.KEY_BLOCKED_PACKAGES, emptySet()).orEmpty()

        // Discover every installed app that has a launcher activity. The approved
        // home artwork is untouched; only the contents of the Apps screen are dynamic.
        val installedApps = packageManager.getInstalledApplications(0)
            .filter { it.packageName != packageName && packageManager.getLaunchIntentForPackage(it.packageName) != null }
            .map { info ->
                val label = runCatching { packageManager.getApplicationLabel(info).toString() }
                    .getOrDefault(info.packageName)
                Triple(info, label, info.packageName)
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.second })

        var tileRow: LinearLayout? = null
        installedApps.forEachIndexed { index, item ->
            val info = item.first
            val label = item.second
            val pkg = item.third

            if (index % 3 == 0) {
                tileRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                    layoutDirection = if (arabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                }
                listContainer.addView(tileRow, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(9) })
            }

            val isBlocked = selected.contains(pkg)
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(12), dp(6), dp(10))
                background = rounded(if (isBlocked) Color.rgb(55, 34, 49) else panel, 16)
                isClickable = true
                isFocusable = true
                contentDescription = label
            }

            val iconView = ImageView(this).apply {
                val drawable = runCatching { packageManager.getApplicationIcon(info) }.getOrNull()
                if (drawable != null) setImageDrawable(drawable)
                else setImageDrawable(GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(blue)
                })
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            tile.addView(iconView, LinearLayout.LayoutParams(dp(52), dp(52)))
            tile.addView(TextView(this).apply {
                text = label
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(white)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(7), 0, dp(4))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)))
            tile.addView(TextView(this).apply {
                text = if (isBlocked) tr("BLOCKED", "محجوب") else tr("ALLOWED", "مسموح")
                textSize = 9f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(if (isBlocked) Color.rgb(255, 153, 153) else green)
            }, wrap())

            tile.setOnClickListener { confirmAppConnection(pkg, label) }
            val tileParams = LinearLayout.LayoutParams(0, dp(126), 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
            tileRow?.addView(tile, tileParams)
            appRows.add(pkg to tile)
        }

        if (installedApps.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = tr("No launchable apps were found.", "لم يتم العثور على تطبيقات قابلة للفتح.")
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(muted)
                setPadding(dp(12), dp(24), dp(12), dp(24))
            }, wrap())
        }

        countText.text = tr(
            "${installedApps.size} apps found • ${selected.size} selected",
            "التطبيقات الموجودة: ${installedApps.size} • المحددة للحجب: ${selected.size}"
        )
        val firewallActive = prefs.getBoolean(FirewallVpnService.KEY_FIREWALL_ACTIVE, false)
        statusText.text = if (firewallActive) {
            tr("${selected.size} apps selected • Protection is active", "التطبيقات المحددة: ${selected.size} • الحماية تعمل")
        } else {
            tr("${selected.size} apps selected. Apply to start protection.", "التطبيقات المحددة: ${selected.size}. اضغط تطبيق لبدء الحماية.")
        }
    }

    private fun confirmAppConnection(packageName: String, appName: String) {
        val blocked = prefs.getStringSet(FirewallVpnService.KEY_BLOCKED_PACKAGES, emptySet()).orEmpty().contains(packageName)
        val title = if (blocked) tr("Restore internet connection?", "معاودة الاتصال بالإنترنت؟")
                    else tr("Stop internet connection?", "إيقاف الاتصال بالإنترنت؟")
        val message = if (blocked) tr("Allow $appName to access the internet again?", "هل تريد السماح لتطبيق $appName بالاتصال بالإنترنت من جديد؟")
                      else tr("Block internet access for $appName?", "هل تريد إيقاف اتصال تطبيق $appName بالإنترنت؟")
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(tr("Cancel", "إلغاء"), null)
            .setPositiveButton(if (blocked) tr("Reconnect", "معاودة الاتصال") else tr("Block internet", "إيقاف الاتصال")) { _, _ ->
                val updated = prefs.getStringSet(FirewallVpnService.KEY_BLOCKED_PACKAGES, emptySet()).orEmpty().toMutableSet()
                if (blocked) updated.remove(packageName) else updated.add(packageName)
                prefs.edit().putStringSet(FirewallVpnService.KEY_BLOCKED_PACKAGES, updated).apply()
                prefs.getString(KEY_API_TOKEN, null)?.let { token ->
                    Thread { runCatching { AssafApiClient.saveSetting(token, packageName, !blocked) } }.start()
                }
                loadApps()
                if (updated.isEmpty()) {
                    startService(Intent(this, FirewallVpnService::class.java).setAction(FirewallVpnService.ACTION_STOP))
                } else if (prefs.getBoolean(FirewallVpnService.KEY_FIREWALL_ACTIVE, false)) {
                    val serviceIntent = Intent(this, FirewallVpnService::class.java).setAction(FirewallVpnService.ACTION_START)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent) else startService(serviceIntent)
                }
                val toastText = if (blocked) tr("Internet connection restored for $appName", "تمت معاودة اتصال $appName بالإنترنت")
                                 else tr("Internet blocked for $appName", "تم إيقاف اتصال $appName بالإنترنت")
                Toast.makeText(this, toastText, Toast.LENGTH_LONG).show()
            }
            .show()
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
        startTimeButton.text = tr("START", "البداية") + "  " + "%02d:%02d".format(startHour, startMinute)
        endTimeButton.text = tr("END", "النهاية") + "  " + "%02d:%02d".format(endHour, endMinute)
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
        statusText.text = tr("Protection start requested • Check VPN status.", "تم طلب بدء الحماية • تحقق من حالة VPN.")
    }

    private fun updateStats() {
        if (!::statsText.isInitialized) return
        val blockedBytes = prefs.getLong(FirewallVpnService.KEY_BLOCKED_BYTES, 0L)
        val packets = prefs.getLong(FirewallVpnService.KEY_BLOCKED_PACKETS, 0L)
        val rx = android.net.TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
        val tx = android.net.TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
        statsText.text = tr(
            """BLOCKED TRAFFIC
${formatBytes(blockedBytes)}  •  ${packets} packets

DEVICE NETWORK USAGE
↓ ${formatBytes(rx)} received     ↑ ${formatBytes(tx)} sent""",
            """الحركة المحجوبة
${formatBytes(blockedBytes)}  •  ${packets} حزمة

استخدام شبكة الجهاز
↓ ${formatBytes(rx)} استقبال     ↑ ${formatBytes(tx)} إرسال"""
        )
    }

    private fun sectionTitle(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply {
            text = title
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.07f
            setTextColor(muted)
            setPadding(0, dp(20), 0, dp(9))
        }, wrap())
    }

    private fun card(color: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(color, 18)
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun primaryButton(label: String) = Button(this).apply {
        text = label
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        background = rounded(blue, 14)
        isAllCaps = false
    }

    private fun smallButton() = Button(this).apply {
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(white)
        background = rounded(panelLight, 12)
        isAllCaps = false
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
        private const val KEY_API_TOKEN = "api_access_token"
        private const val KEY_ACCOUNT_EMAIL = "api_account_email"
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
