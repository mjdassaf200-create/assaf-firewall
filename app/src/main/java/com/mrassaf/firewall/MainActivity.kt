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
    private var arabic = false
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
        val scroll = ScrollView(this).apply {
            setFillViewport(true)
            setBackgroundColor(navy)
            isVerticalScrollBarEnabled = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
            setBackgroundColor(navy)
        }
        scroll.addView(root)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = TextView(this).apply {
            text = "A"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.rgb(4, 20, 48))
                setStroke(dp(2), blue)
                cornerRadius = dp(16).toFloat()
            }
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(54), dp(54)))
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        brand.addView(TextView(this).apply {
            text = "ASSAF"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(white)
            letterSpacing = 0.06f
        }, wrap())
        brand.addView(TextView(this).apply {
            text = "YOUR INTERNET. YOUR CONTROL."
            textSize = 9f
            setTextColor(muted)
            letterSpacing = 0.08f
        }, wrap())
        header.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Button(this).apply {
            text = if (arabic) "EN" else "ع"
            textSize = 14f
            setTextColor(white)
            background = rounded(panelLight, 12)
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener {
                arabic = !arabic
                prefs.edit().putBoolean(KEY_ARABIC, arabic).apply()
                buildUi()
                loadApps()
                updateStats()
            }
        }, LinearLayout.LayoutParams(dp(52), dp(46)))
        root.addView(header, wrap())

        val hero = card(blue).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(22), dp(18), dp(22))
        }
        hero.addView(TextView(this).apply {
            text = "🛡  A  ⟲"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }, wrap())
        hero.addView(TextView(this).apply {
            text = "ASSAF  •  YOUR INTERNET. YOUR CONTROL."
            textSize = 11f
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }, wrap())
        hero.addView(TextView(this).apply {
            text = tr("Take control of your connections", "تحكّم باتصالات تطبيقاتك")
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(4))
        }, wrap())
        hero.addView(TextView(this).apply {
            text = tr("Choose which apps can access the internet.", "اختر التطبيقات المسموح لها بالوصول إلى الإنترنت.")
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(230, 241, 255))
        }, wrap())
        val heroParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        heroParams.topMargin = dp(22)
        root.addView(hero, heroParams)

        val statusCard = card(panel).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
        }
        val statusHeading = TextView(this).apply {
            text = tr("FIREWALL STATUS", "حالة جدار الحماية")
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(muted)
        }
        statusCard.addView(statusHeading, wrap())
        statusText = TextView(this).apply {
            text = tr("Ready to protect your device", "جاهز لحماية جهازك")
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(white)
            setPadding(0, dp(7), 0, 0)
        }
        statusCard.addView(statusText, wrap())
        val statusParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        statusParams.topMargin = dp(12)
        root.addView(statusCard, statusParams)

        sectionTitle(root, tr("NETWORK OVERVIEW", "نظرة عامة على الشبكة"))
        statsText = TextView(this).apply {
            textSize = 13f
            setTextColor(white)
            setLineSpacing(dp(5).toFloat(), 1f)
            setPadding(dp(15), dp(14), dp(15), dp(14))
            background = rounded(panel, 16)
        }
        root.addView(statsText, wrap())

        val appsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        appsHeader.addView(TextView(this).apply {
            text = tr("YOUR APPLICATIONS", "تطبيقاتك")
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.07f
            setTextColor(muted)
            setPadding(0, dp(20), 0, dp(9))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        countText = TextView(this).apply {
            text = "0"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(white)
            background = rounded(panelLight, 10)
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }
        appsHeader.addView(countText)
        val appsParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        appsParams.topMargin = dp(20)
        root.addView(appsHeader, appsParams)

        searchBox = EditText(this).apply {
            hint = tr("Search installed apps", "ابحث عن تطبيق مثبت")
            setSingleLine(true)
            textSize = 15f
            setTextColor(white)
            setHintTextColor(muted)
            background = rounded(panel, 13)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            compoundDrawablePadding = dp(8)
            addTextChangedListener(SimpleTextWatcher { filterApps(it) })
        }
        val searchParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        searchParams.topMargin = dp(8)
        root.addView(searchBox, searchParams)

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(listContainer, wrap())

        sectionTitle(root, tr("SMART SCHEDULE", "الجدولة الذكية"))
        val scheduleCard = card(panel).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(14))
        }
        scheduleCheck = CheckBox(this).apply {
            text = tr("Enable scheduled blocking", "تفعيل الحجب المجدول")
            textSize = 14f
            setTextColor(white)
            buttonTintList = ColorStateList.valueOf(blue)
            isChecked = prefs.getBoolean(KEY_SCHEDULE_ENABLED, false)
        }
        scheduleCard.addView(scheduleCheck, wrap())
        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        startTimeButton = smallButton()
        startTimeButton.setOnClickListener { chooseTime(true) }
        endTimeButton = smallButton()
        endTimeButton.setOnClickListener { chooseTime(false) }
        val timeParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        timeParams.marginEnd = dp(6)
        times.addView(startTimeButton, timeParams)
        val endParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        endParams.marginStart = dp(6)
        times.addView(endTimeButton, endParams)
        scheduleCard.addView(times, wrap())
        updateTimeButtons()
        val save = primaryButton(tr("Save schedule", "حفظ الجدولة"))
        save.setOnClickListener { saveSchedule() }
        val saveParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        saveParams.topMargin = dp(10)
        scheduleCard.addView(save, saveParams)
        val scheduleParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        scheduleParams.topMargin = dp(8)
        root.addView(scheduleCard, scheduleParams)

        sectionTitle(root, tr("ASSAF CLOUD ACCOUNT", "حساب ASSAF السحابي"))
        val cloudCard = card(panel).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }
        cloudStatusText = TextView(this).apply {
            text = if (prefs.getString(KEY_API_TOKEN, null).isNullOrBlank())
                tr("Not signed in • Settings stay on this device", "لم يتم تسجيل الدخول • الإعدادات محفوظة على الجهاز")
            else tr("Signed in • ASSAF cloud connected", "تم تسجيل الدخول • اتصال ASSAF السحابي")
            textSize = 13f
            setTextColor(white)
        }
        cloudCard.addView(cloudStatusText, wrap())
        val accountButton = primaryButton(tr("Sign in / Create account", "تسجيل الدخول / إنشاء حساب"))
        accountButton.setOnClickListener { showAccountDialog() }
        val accountParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        accountParams.topMargin = dp(10)
        cloudCard.addView(accountButton, accountParams)
        val healthButton = smallButton().apply {
            text = tr("Check server connection", "فحص اتصال السيرفر")
            setOnClickListener {
                cloudStatusText.text = tr("Checking server…", "جارٍ فحص السيرفر…")
                Thread {
                    val result = runCatching { AssafApiClient.checkHealth() }
                    runOnUiThread {
                        cloudStatusText.text = result.fold(
                            { tr("Server reachable • API healthy", "السيرفر متصل • واجهة API تعمل") },
                            { tr("Connection failed: " + (it.message ?: "unknown error"), "فشل الاتصال: " + (it.message ?: "خطأ غير معروف")) }
                        )
                    }
                }.start()
            }
        }
        val healthParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
        healthParams.topMargin = dp(7)
        cloudCard.addView(healthButton, healthParams)
        val cloudParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        cloudParams.topMargin = dp(8)
        root.addView(cloudCard, cloudParams)

        sectionTitle(root, tr("FIREWALL CONTROLS", "التحكم بالحماية"))
        val apply = primaryButton(tr("Apply & start protection", "تطبيق وبدء الحماية"))
        apply.setOnClickListener { requestVpnAndStart() }
        root.addView(apply, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        val stop = smallButton().apply {
            text = tr("Turn firewall off", "إيقاف جدار الحماية")
            setTextColor(Color.rgb(255, 145, 145))
            setOnClickListener {
                startService(Intent(this@MainActivity, FirewallVpnService::class.java).setAction(FirewallVpnService.ACTION_STOP))
                statusText.text = tr("Firewall stop requested", "تم طلب إيقاف الحجب")
            }
        }
        val stopParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        stopParams.topMargin = dp(8)
        root.addView(stop, stopParams)

        root.addView(TextView(this).apply {
            text = tr(
                "VPN-based filtering • Private by design
Android does not allow ordinary apps to switch Wi-Fi/mobile data off per app.",
                "تصفية عبر VPN • خصوصية من التصميم
أندرويد لا يسمح للتطبيق العادي بإطفاء الواي فاي أو بيانات الهاتف لكل تطبيق."
            )
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(8), dp(18), dp(8), 0)
        }, wrap())

        root.addView(TextView(this).apply {
            text = "M. Azmi .Assaf"
            textSize = 13f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(white)
            setPadding(dp(8), dp(20), dp(8), dp(4))
        }, wrap())
        root.addView(TextView(this).apply {
            text = "mjdassaf200@gmail.com"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(blue)
            setPadding(dp(8), 0, dp(8), dp(12))
            setOnClickListener {
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = android.net.Uri.parse("mailto:mjdassaf200@gmail.com")
                    putExtra(Intent.EXTRA_SUBJECT, "MR. ASSAF App")
                }
                runCatching { startActivity(intent) }
                    .onFailure { Toast.makeText(this@MainActivity, "No email app found", Toast.LENGTH_SHORT).show() }
            }
        }, wrap())
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
        val selected = prefs.getStringSet(FirewallVpnService.KEY_BLOCKED_PACKAGES, emptySet()).orEmpty().toSet()
        Thread {
            val result = runCatching {
                selected.forEach { AssafApiClient.saveSetting(token, it, true) }
                AssafApiClient.listSettings(token).length
            }
            runOnUiThread {
                result.onSuccess { count ->
                    cloudStatusText.text = tr("Cloud connected • $count saved cloud settings", "السحابة متصلة • $count إعدادات محفوظة")
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
        val apps = packageManager.getInstalledApplications(0)
            .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null && it.packageName != packageName }
            .sortedBy { packageManager.getApplicationLabel(it).toString().lowercase(Locale.ROOT) }
        for (app in apps) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(panel, 14)
            }
            runCatching { packageManager.getApplicationIcon(app) }.getOrNull()?.let { icon ->
                row.addView(android.widget.ImageView(this).apply {
                    setImageDrawable(icon)
                    layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(12) }
                })
            }
            row.addView(TextView(this).apply {
                text = packageManager.getApplicationLabel(app)
                textSize = 16f
                setTextColor(white)
                maxLines = 2
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val stateLabel = TextView(this).apply {
                text = if (selected.contains(app.packageName)) tr("BLOCKED", "محجوب") else tr("ALLOWED", "متصل")
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(if (selected.contains(app.packageName)) Color.rgb(255, 153, 153) else green)
                background = rounded(if (selected.contains(app.packageName)) Color.rgb(75, 35, 48) else Color.rgb(20, 65, 58), 10)
                setPadding(dp(9), dp(7), dp(9), dp(7))
            }
            row.addView(stateLabel)
            row.isClickable = true
            row.isFocusable = true
            row.setOnClickListener { confirmAppConnection(app.packageName, packageManager.getApplicationLabel(app).toString()) }
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.bottomMargin = dp(7)
            appRows.add(app.packageName to row)
            listContainer.addView(row, params)
        }
        countText.text = selected.size.toString()
        statusText.text = tr("${selected.size} apps selected. Apply to start protection.", "التطبيقات المحددة: ${selected.size}. اضغط تطبيق لبدء الحماية.")
        if (apps.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = tr("No launchable apps found.", "لم يتم العثور على تطبيقات قابلة للتشغيل.")
                setTextColor(muted)
                setPadding(dp(12), dp(18), dp(12), dp(18))
            }, wrap())
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
            "BLOCKED TRAFFIC
${formatBytes(blockedBytes)}  •  ${packets} packets

DEVICE NETWORK USAGE
↓ ${formatBytes(rx)} received     ↑ ${formatBytes(tx)} sent",
            "الحركة المحجوبة
${formatBytes(blockedBytes)}  •  ${packets} حزمة

استخدام شبكة الجهاز
↓ ${formatBytes(rx)} استقبال     ↑ ${formatBytes(tx)} إرسال"
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
