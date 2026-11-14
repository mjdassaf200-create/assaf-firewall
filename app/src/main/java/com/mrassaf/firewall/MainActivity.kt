package com.mrassaf.firewall

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.net.Uri
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
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import android.text.InputType
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    private lateinit var listContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statsText: TextView
    private lateinit var searchBox: EditText
    private lateinit var scheduleCheck: CheckBox
    private lateinit var startTimeButton: Button
    private lateinit var endTimeButton: Button
    private lateinit var countText: TextView
    private lateinit var cloudStatusText: TextView
    private val prefs by lazy { getSharedPreferences(FirewallVpnService.PREFS, MODE_PRIVATE) }
    private var pendingStart = false
    private var currentScreen = "home"
    private var appOpenAd: AppOpenAd? = null
    private var interstitialAd: InterstitialAd? = null
    private var activityResumed = false
    private var appOpenShowing = false
    private var interstitialShowing = false
    private var showAllApps = false
    private var appOpenShownThisLaunch = false
    private var accountBanned = false
    private var banReason = ""
    private var banCheckRunning = false
    private var arabic = true
    private var startHour = 22
    private var startMinute = 0
    private var endHour = 7
    private var endMinute = 0
    private val appRows = mutableListOf<Pair<String, View>>()
    private val telemetryHandler = Handler(Looper.getMainLooper())
    private val telemetryPrefs by lazy { getSharedPreferences("assaf_telemetry", MODE_PRIVATE) }
    private val telemetryTask = object : Runnable {
        override fun run() {
            if (telemetryPrefs.getBoolean("enabled", false) && !accountBanned) {
                Thread {
                    runCatching {
                        val installId = telemetryPrefs.getString("install_id", null)
                            ?: UUID.randomUUID().toString().replace("-", "").also {
                                telemetryPrefs.edit().putString("install_id", it).apply()
                            }
                        val version = runCatching {
                            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
                        }.getOrDefault("unknown")
                        AssafApiClient.pingTelemetry(installId, version)
                    }
                }.start()
                telemetryHandler.postDelayed(this, 2 * 60 * 1000L)
            }
        }
    }

    private val banCheckTask = object : Runnable {
        override fun run() {
            refreshBanStatus()
            if (prefs.getString(KEY_API_TOKEN, null) != null) {
                telemetryHandler.postDelayed(this, 60 * 1000L)
            }
        }
    }

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
        MobileAds.initialize(this) {
            runOnUiThread {
                loadAppOpenAd()
                loadInterstitialAd()
            }
        }
        buildUi()
        if (currentScreen == "apps") loadApps()
        if (!telemetryPrefs.getBoolean("choice_made", false)) showTelemetryConsent()
    }

    private fun showTelemetryConsent() {
        AlertDialog.Builder(this)
            .setTitle(tr("Help improve ASSAF", "ساعدنا على تحسين ASSAF"))
            .setMessage(tr(
                "Allow anonymous usage statistics? ASSAF will send a random installation ID, app version, and last-active time so the owner can count active installations. It will not send your app list, blocked apps, messages, or browsing activity. You can decline and still use the app.",
                "هل تسمح بإرسال إحصائيات استخدام مجهولة؟ سيرسل ASSAF معرّف تثبيت عشوائيًا وإصدار التطبيق وآخر وقت نشاط حتى يتمكن المالك من حساب الأجهزة النشطة. لن يرسل قائمة تطبيقاتك أو التطبيقات المحظورة أو رسائلك أو نشاط التصفح. يمكنك الرفض والاستمرار باستخدام التطبيق."
            ))
            .setPositiveButton(tr("Allow", "سماح")) { _, _ ->
                telemetryPrefs.edit().putBoolean("choice_made", true).putBoolean("enabled", true).apply()
                telemetryHandler.removeCallbacks(telemetryTask)
                telemetryHandler.post(telemetryTask)
            }
            .setNegativeButton(tr("Not now", "ليس الآن")) { _, _ ->
                telemetryPrefs.edit().putBoolean("choice_made", true).putBoolean("enabled", false).apply()
            }
            .setCancelable(false)
            .show()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        if (appOpenAd != null && !appOpenShownThisLaunch) showAppOpenAd()
        telemetryHandler.removeCallbacks(telemetryTask)
        if (telemetryPrefs.getBoolean("enabled", false) && !accountBanned) telemetryHandler.post(telemetryTask)
        if (prefs.getString(KEY_API_TOKEN, null) != null) {
            telemetryHandler.removeCallbacks(banCheckTask)
            telemetryHandler.post(banCheckTask)
        }
    }

    private fun refreshBanStatus() {
        val token = prefs.getString(KEY_API_TOKEN, null) ?: return
        if (banCheckRunning) return
        banCheckRunning = true
        Thread {
            val result = runCatching { AssafApiClient.getMe(token) }
            runOnUiThread {
                banCheckRunning = false
                result.onSuccess { profile ->
                    val wasBanned = accountBanned
                    accountBanned = profile.optBoolean("is_banned", false)
                    banReason = profile.optString("ban_reason", "")
                    if (accountBanned) {
                        telemetryPrefs.edit().putBoolean("enabled", false).apply()
                        telemetryHandler.removeCallbacks(telemetryTask)
                        if (!wasBanned || currentScreen !in setOf("banned", "contact", "appeal")) {
                            currentScreen = "banned"
                        }
                        prefs.edit().putBoolean(KEY_SCHEDULE_ENABLED, false).apply()
                        ScheduleReceiver.reschedule(this)
                        startService(Intent(this, FirewallVpnService::class.java).setAction(FirewallVpnService.ACTION_STOP))
                    } else if (wasBanned) {
                        currentScreen = "home"
                    }
                    if (wasBanned != accountBanned) buildUi()
                }
            }
        }.start()
    }

    override fun onPause() {
        activityResumed = false
        telemetryHandler.removeCallbacks(telemetryTask)
        telemetryHandler.removeCallbacks(banCheckTask)
        super.onPause()
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
        if (accountBanned && currentScreen !in setOf("banned", "contact", "appeal")) currentScreen = "banned"
        when (currentScreen) {
            "banned" -> buildBannedScreen()
            "contact" -> buildContactScreen()
            "appeal" -> buildAppealScreen()
            "apps" -> { buildAppsScreen(); loadApps() }
            "tapping" -> buildTappingScreen()
            "stats", "privacy" -> buildStatsScreen()
            "connection" -> buildConnectionScreen()
            "schedule" -> buildScheduleScreen()
            "settings" -> buildSettingsScreen()
            "shield" -> buildShieldScreen()
            "link" -> buildLinkScreen()
            "wifi" -> buildWifiScreen()
            "profile" -> buildProfileScreen()
            "earn" -> buildEarnScreen()
            "plus" -> buildPlusScreen()
            else -> buildHomeScreen()
        }
    }

    private fun goTo(screen: String) {
        val destination = if (accountBanned && screen !in setOf("banned", "contact", "appeal")) "banned" else screen
        val returningHome = currentScreen != "home" && destination == "home" && !accountBanned
        if (returningHome && interstitialAd != null && !interstitialShowing) {
            showInterstitialThenNavigate()
            return
        }
        currentScreen = destination
        buildUi()
    }

    private fun loadAppOpenAd() {
        if (appOpenAd != null || isFinishing) return
        AppOpenAd.load(
            this,
            "ca-app-pub-6000977044744613/8824657940",
            AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    appOpenAd = ad
                    if (activityResumed && !appOpenShownThisLaunch) showAppOpenAd()
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    appOpenAd = null
                }
            }
        )
    }

    private fun showAppOpenAd() {
        val ad = appOpenAd ?: return
        if (appOpenShowing || isFinishing || isDestroyed) return
        appOpenAd = null
        appOpenShownThisLaunch = true
        appOpenShowing = true
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                appOpenShowing = false
                loadAppOpenAd()
            }
            override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) {
                appOpenShowing = false
                loadAppOpenAd()
            }
        }
        ad.show(this)
    }

    private fun loadInterstitialAd() {
        if (interstitialAd != null || isFinishing) return
        InterstitialAd.load(
            this,
            "ca-app-pub-6000977044744613/9981826945",
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialAd = null
                }
            }
        )
    }

    private fun showInterstitialThenNavigate() {
        val ad = interstitialAd ?: return
        interstitialAd = null
        interstitialShowing = true
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                interstitialShowing = false
                goTo("home")
                loadInterstitialAd()
            }
            override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) {
                interstitialShowing = false
                currentScreen = "home"
                buildUi()
                loadInterstitialAd()
            }
        }
        ad.show(this)
    }

    private fun pageRoot(title: String): LinearLayout {
        val scroll = ScrollView(this).apply { setFillViewport(true); setBackgroundColor(navy); isVerticalScrollBarEnabled = false }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(48), dp(18), dp(24))
            setBackgroundResource(R.drawable.assaf_background)
            layoutDirection = if (arabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        scroll.addView(root)
        root.addView(smallButton().apply {
            text = if (accountBanned) tr("← Back", "← رجوع") else tr("← Back to home", "← الرجوع للرئيسية")
            setOnClickListener { goTo(if (accountBanned) "banned" else "home") }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
        root.addView(TextView(this).apply {
            text = title; textSize = 22f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(white); gravity = Gravity.CENTER; setPadding(0, dp(20), 0, dp(20))
        }, wrap())
        addBannerAd(root)
        setContentView(scroll)
        return root
    }

    private fun addBannerAd(parent: LinearLayout) {
        val adView = AdView(this).apply {
            adUnitId = "ca-app-pub-6000977044744613/3433171409"
            val widthDp = ((resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt() - 36).coerceAtLeast(320)
            setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this@MainActivity, widthDp))
            loadAd(AdRequest.Builder().build())
        }
        parent.addView(adView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(4)
            bottomMargin = dp(12)
        })
    }

    private fun infoCard(value: String): TextView = TextView(this).apply {
        text = value; textSize = 15f; setTextColor(white)
        setPadding(dp(16), dp(16), dp(16), dp(16)); background = rounded(panel, 16)
    }

    private fun buildStatsScreen() {
        val root = pageRoot(if (currentScreen == "privacy") tr("Privacy report", "تقرير الخصوصية") else tr("Network statistics", "إحصائيات الشبكة"))
        statsText = infoCard("")
        root.addView(statsText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) })
        root.addView(primaryButton(if (currentScreen == "privacy") tr("Refresh report", "تحديث التقرير") else tr("Refresh statistics", "تحديث الإحصائيات")).apply { setOnClickListener { updateStats() } },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        updateStats()
    }

    private fun buildConnectionScreen() {
        val root = pageRoot(tr("Data connection", "اتصال البيانات"))
        val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val active = cm.activeNetwork
        val caps = if (active != null) cm.getNetworkCapabilities(active) else null
        val connected = caps != null
        val wifi = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        val cellular = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val status = tr(
            "Connection: " + (if (connected) "Connected" else "No active connection") + "\nWi-Fi: " + (if (wifi) "Connected" else "Not active") + "\nMobile data: " + (if (cellular) "Active" else "Not active"),
            "الاتصال: " + (if (connected) "متصل" else "لا يوجد اتصال نشط") + "\nالواي فاي: " + (if (wifi) "متصل" else "غير نشط") + "\nبيانات الهاتف: " + (if (cellular) "نشطة" else "غير نشطة")
        )
        root.addView(infoCard(status), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) })
        root.addView(primaryButton(tr("Open internet settings", "فتح إعدادات الإنترنت")).apply {
            setOnClickListener {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)) }
                    .onFailure { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        root.addView(TextView(this).apply {
            text = tr("Android does not let ordinary apps switch Wi-Fi/mobile data directly. Use system settings to change them.",
                "أندرويد لا يسمح للتطبيقات العادية بتشغيل أو إيقاف الواي فاي/بيانات الهاتف مباشرة. استخدم إعدادات النظام للتحكم.")
            textSize = 13f; setTextColor(muted); setPadding(0, dp(16), 0, 0)
        }, wrap())
    }

    private fun buildScheduleScreen() {
        val root = pageRoot(tr("Protection schedule", "جدولة الحماية"))
        scheduleCheck = CheckBox(this).apply {
            text = tr("Enable scheduled blocking", "تفعيل الحجب حسب الجدول")
            setTextColor(white); isChecked = prefs.getBoolean(KEY_SCHEDULE_ENABLED, false)
            buttonTintList = ColorStateList.valueOf(blue)
        }
        root.addView(scheduleCheck, wrap())
        startTimeButton = primaryButton("").apply { setOnClickListener { chooseTime(true) } }
        endTimeButton = primaryButton("").apply { setOnClickListener { chooseTime(false) } }
        root.addView(startTimeButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(12) })
        root.addView(endTimeButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(10) })
        updateTimeButtons()
        root.addView(primaryButton(tr("Save schedule", "حفظ الجدولة")).apply { setOnClickListener { saveSchedule() } },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(18) })
        root.addView(TextView(this).apply {
            text = tr("Selected apps are blocked between the start and end times.", "سيتم حجب التطبيقات المحددة بين وقت البداية والنهاية.")
            textSize = 13f; setTextColor(muted); setPadding(0, dp(16), 0, 0)
        }, wrap())
    }

    private fun buildSettingsScreen() {
        val root = pageRoot(tr("Settings", "الإعدادات"))
        root.addView(CheckBox(this).apply {
            text = tr("Use Arabic interface", "استخدام الواجهة العربية")
            setTextColor(white); isChecked = arabic; buttonTintList = ColorStateList.valueOf(blue)
            setOnCheckedChangeListener { _, checked ->
                arabic = checked; prefs.edit().putBoolean(KEY_ARABIC, arabic).apply(); goTo("settings")
            }
        }, wrap())
        root.addView(primaryButton(tr("ASSAF cloud account", "حساب ASSAF السحابي")).apply { setOnClickListener { showAccountDialog() } },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(14) })
        root.addView(smallButton().apply {
            text = tr("Protection schedule", "جدولة الحماية")
            setOnClickListener { goTo("schedule") }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(10) })
        root.addView(smallButton().apply {
            text = tr("Connection details", "تفاصيل الاتصال")
            setOnClickListener { goTo("connection") }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(10) })
        val active = prefs.getBoolean(FirewallVpnService.KEY_FIREWALL_ACTIVE, false)
        root.addView(infoCard(tr("Firewall status: " + if (active) "Active" else "Off", "حالة جدار الحماية: " + if (active) "يعمل" else "متوقف")),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
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
                            refreshBanStatus()
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
        if (prefs.getInt(FirewallVpnService.KEY_CONTROL_MODE, FirewallVpnService.MODE_INDIVIDUAL) != FirewallVpnService.MODE_INDIVIDUAL) return
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

    private fun buildShieldScreen() {
        val root = pageRoot(tr("ASSAF Shield", "درع عساف"))
        root.addView(infoCard(tr(
            "Protection status and filtering engine",
            "حالة الحماية ومحرك التصفية"
        )), wrap())
        root.addView(infoCard(tr(
            "App internet blocking is available in the Applications screen. Ad and malicious-domain blocking needs a separate filtering engine and is not active yet.",
            "يمكن التحكم بإنترنت التطبيقات من صفحة التطبيقات. حجب الإعلانات والنطاقات الضارة يحتاج محرك تصفية مستقل، وهو غير مفعّل حالياً."
        )), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        root.addView(primaryButton(tr("Open applications", "فتح التطبيقات")).apply {
            setOnClickListener { goTo("apps") }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(16) })
    }

    private fun buildLinkScreen() {
        val root = pageRoot(tr("Link Check", "فحص الروابط"))
        val intro = infoCard(tr(
            "ASSAF checks URL patterns, domain/DNS signals, and known-threat reputation when configured. A high score is not a guarantee; if threat reputation is unavailable, the result remains preliminary.",
            "يفحص ASSAF أنماط الرابط ومؤشرات النطاق وDNS وسمعة التهديدات المعروفة عند تفعيل المصدر. الدرجة العالية ليست ضماناً؛ وإذا تعذّر فحص السمعة تبقى النتيجة مبدئية."
        ))
        root.addView(intro, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        val input = EditText(this).apply {
            hint = tr("Paste a public website URL", "الصق رابط موقع عام")
            setTextColor(white); setHintTextColor(muted); textSize = 16f
            setSingleLine(true); inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(14), dp(12), dp(14), dp(12)); background = rounded(panel, 14)
        }
        root.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        val check = primaryButton(tr("Analyze link", "افحص الرابط"))
        root.addView(check, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(12) })
        val report = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(report, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        check.setOnClickListener {
            val value = input.text.toString().trim()
            if (value.isBlank()) {
                Toast.makeText(this, tr("Enter a link first", "أدخل الرابط أولاً"), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            check.isEnabled = false
            check.text = tr("Checking…", "جاري الفحص…")
            report.removeAllViews()
            report.addView(infoCard(tr("Checking the link indicators…", "جاري فحص مؤشرات الرابط…")))
            Thread {
                val response = runCatching { AssafApiClient.analyzeLink(value, prefs.getString(KEY_API_TOKEN, null)) }
                runOnUiThread {
                    check.isEnabled = true
                    check.text = tr("Analyze link", "افحص الرابط")
                    report.removeAllViews()
                    if (response.isFailure) {
                        report.addView(infoCard(tr(
                            "The check could not be completed. No safety conclusion can be made. Details: ${response.exceptionOrNull()?.message ?: "Unknown error"}",
                            "تعذّر إكمال الفحص، لذلك لا يمكن إصدار حكم على أمان الرابط. التفاصيل: ${response.exceptionOrNull()?.message ?: "خطأ غير معروف"}"
                        )))
                    } else {
                        val data = response.getOrThrow()
                        val domain = data.optJSONObject("domain") ?: org.json.JSONObject()
                        val registration = data.optJSONObject("registration") ?: org.json.JSONObject()
                        val security = data.optJSONObject("security_review") ?: org.json.JSONObject()
                        val flags = security.optJSONArray("flags") ?: org.json.JSONArray()
                        val urls = data.optJSONArray("query_parameters") ?: org.json.JSONArray()
                        val dns = domain.optJSONArray("public_dns_addresses") ?: org.json.JSONArray()
                        val nameservers = registration.optJSONArray("nameservers") ?: org.json.JSONArray()

                        fun arrValues(array: org.json.JSONArray): List<String> =
                            (0 until array.length()).mapNotNull { i ->
                                array.optString(i).takeIf { it.isNotBlank() && it != "null" }
                            }
                        val flagObjects = (0 until flags.length()).mapNotNull { flags.optJSONObject(it) }
                        val flagCodes = flagObjects.map { it.optString("code") }.toSet()
                        val threatIntel = security.optJSONObject("threat_intelligence") ?: org.json.JSONObject()
                        val threatStatus = threatIntel.optString("status", "not_checked")
                        val knownThreat = threatStatus == "malicious"
                        val score = security.optInt("score", 70).coerceIn(0, 100)
                        val hasWarning = knownThreat || flagObjects.any { it.optString("level") == "warning" || it.optString("level") == "review" }
                        val scoreColor = when {
                            knownThreat || score < 50 -> Color.rgb(239, 68, 68)
                            hasWarning || score < 80 -> Color.rgb(213, 168, 62)
                            else -> green
                        }
                        val verdict = when {
                            knownThreat -> tr("Known threat reported", "تم الإبلاغ عن تهديد معروف")
                            score < 50 -> tr("High risk indicators — avoid this link", "مؤشرات خطورة مرتفعة — تجنّب الرابط")
                            hasWarning || score < 80 -> tr("Caution — review the indicators", "حذر — راجع المؤشرات")
                            threatStatus != "clean" -> tr("Preliminary only — threat reputation not confirmed", "نتيجة مبدئية — لم يتم تأكيد سمعة الرابط")
                            else -> tr("No known threat found by the connected source — not a guarantee", "لم يرصد المصدر المتصل تهديداً معروفاً — وهذا ليس ضماناً")
                        }
                        val scoreCard = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER
                            setPadding(dp(18), dp(18), dp(18), dp(18))
                            background = rounded(panel, 18)
                        }
                        scoreCard.addView(TextView(this).apply {
                            text = tr("SECURITY SCORE", "درجة الأمان")
                            textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(muted); gravity = Gravity.CENTER
                        })
                        scoreCard.addView(TextView(this).apply {
                            text = "$score / 100"
                            textSize = 34f; typeface = Typeface.DEFAULT_BOLD; setTextColor(scoreColor); gravity = Gravity.CENTER
                            setPadding(0, dp(6), 0, dp(4))
                        })
                        scoreCard.addView(TextView(this).apply {
                            text = verdict
                            textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(scoreColor); gravity = Gravity.CENTER
                        })
                        scoreCard.addView(TextView(this).apply {
                            text = when (threatStatus) {
                                "malicious" -> tr("A connected threat-reputation source reported this URL. Treat it as dangerous; avoid opening it.", "مصدر سمعة التهديدات المتصل أبلغ عن هذا الرابط. تعامل معه كخطر وتجنّب فتحه.")
                                "clean" -> tr("A known-threat lookup returned no match. The page content, TLS, and redirects are not fully verified; this is not a safety guarantee.", "لم يعثر فحص التهديدات المعروف على تطابق. لم يتم التحقق الكامل من محتوى الصفحة أو TLS أو التحويلات؛ وهذه ليست ضمانة أمان.")
                                else -> tr("Threat reputation could not be confirmed. This is an indicator score, not proof of safety; do not enter sensitive information on uncertain links.", "تعذّر تأكيد سمعة التهديدات. هذه درجة للمؤشرات وليست إثباتاً للأمان؛ لا تدخل بيانات حساسة في رابط غير موثوق.")
                            }
                            textSize = 12f; setTextColor(muted); gravity = Gravity.CENTER
                            setPadding(0, dp(10), 0, 0)
                        })
                        report.addView(scoreCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })

                        if (hasWarning) {
                            val warningCard = LinearLayout(this).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(dp(14), dp(14), dp(14), dp(14))
                                background = rounded(Color.rgb(105, 28, 35), 16)
                            }
                            warningCard.addView(TextView(this).apply {
                                text = tr("SECURITY WARNING", "تحذير أمني")
                                textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                            })
                            warningCard.addView(TextView(this).apply {
                                text = if (knownThreat) tr("A connected threat-reputation source matched this URL to a known threat. Do not open it or enter any information.", "طابق مصدر سمعة التهديدات المتصل هذا الرابط مع تهديد معروف. لا تفتحه ولا تدخل أي معلومات.")
                                    else tr("Suspicious indicators were found, but these indicators alone do not prove the site is malicious. Avoid passwords, payment details, and downloads until independently verified.", "تم العثور على مؤشرات مريبة، لكنها وحدها لا تثبت أن الموقع خبيث. تجنّب كلمات المرور وبيانات الدفع والتنزيلات حتى يتم التحقق منه بشكل مستقل.")
                                textSize = 14f; setTextColor(Color.WHITE); setPadding(0, dp(7), 0, 0)
                            })
                            report.addView(warningCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
                        }

                        fun addSection(title: String, lines: List<String>) {
                            val card = LinearLayout(this).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(dp(14), dp(12), dp(14), dp(12))
                                background = rounded(panel, 16)
                            }
                            card.addView(TextView(this).apply {
                                text = title; setTextColor(blue); textSize = 16f; typeface = Typeface.DEFAULT_BOLD
                            })
                            lines.filter { it.isNotBlank() }.forEach { line ->
                                card.addView(TextView(this).apply {
                                    text = line; setTextColor(white); textSize = 13f
                                    setPadding(0, dp(7), 0, dp(2))
                                })
                            }
                            report.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
                        }

                        addSection(tr("01 • Link identity", "01 • هوية الرابط"), listOf(
                            "${tr("Submitted", "الرابط المدخل")}: ${data.optString("submitted_url")}",
                            "${tr("Normalized", "الرابط بعد الترتيب")}: ${data.optString("normalized_url")}",
                            "${tr("Protocol", "البروتوكول")}: ${data.optString("scheme").uppercase(Locale.ROOT)}",
                            "${tr("Host", "اسم المضيف")}: ${data.optString("hostname")}",
                            "${tr("Port", "المنفذ")}: ${data.optInt("port")}",
                            "${tr("Path", "المسار")}: ${data.optString("path")}",
                            "${tr("Fragment", "علامة الصفحة")}: ${data.optBoolean("fragment_present")}"
                        ))
                        addSection(tr("02 • Domain & DNS", "02 • النطاق وDNS"), listOf(
                            "${tr("Domain estimate", "النطاق التقريبي")}: ${domain.optString("registrable_domain_estimate")}",
                            "${tr("Address status", "حالة العنوان")}: ${domain.optString("dns_status")}",
                            "${tr("Public IP addresses", "عناوين IP العامة")}: ${arrValues(dns).joinToString().ifBlank { tr("No public address returned", "لم يُرجع عنوان عام") }}",
                            domain.optString("note")
                        ))
                        val regEvents = registration.optJSONArray("events") ?: org.json.JSONArray()
                        val eventLines = (0 until regEvents.length()).map { i ->
                            val e = regEvents.optJSONObject(i) ?: org.json.JSONObject()
                            "${e.optString("event")}: ${e.optString("date")}"
                        }
                        addSection(tr("03 • Domain registration records", "03 • سجلات تسجيل النطاق"), listOf(
                            "${tr("Lookup status", "حالة الاستعلام")}: ${registration.optString("status")}",
                            "${tr("Registered domain", "النطاق المسجل")}: ${registration.optString("domain_name")}",
                            "${tr("Registry handle", "معرّف السجل")}: ${registration.optString("handle")}",
                            "${tr("Name servers", "خوادم الأسماء")}: ${arrValues(nameservers).joinToString().ifBlank { tr("Not available", "غير متاح") }}",
                            *eventLines.toTypedArray(),
                            registration.optString("disclaimer"),
                            registration.optString("reason")
                        ))
                        val flagLines = flagObjects.map { f ->
                            "• [${f.optString("level")}] ${f.optString("code")}: ${f.optString("detail")}"
                        }
                        addSection(tr("04 • Security indicators", "04 • مؤشرات الأمان"), listOf(
                            "${tr("Assessment", "التقييم")}: ${security.optString("status")}",
                            "${tr("Confidence", "مستوى الثقة")}: ${security.optString("confidence")}",
                            *flagLines.toTypedArray(),
                            security.optString("important")
                        ))
                        addSection(tr("05 • URL parameters", "05 • معاملات الرابط"), if (urls.length() == 0)
                            listOf(tr("No query parameters found.", "لا توجد معاملات إضافية في الرابط."))
                        else (0 until urls.length()).map { i ->
                            val p = urls.optJSONObject(i) ?: org.json.JSONObject()
                            "${p.optString("name")} = ${p.optString("value")}"
                        })
                        addSection(tr("06 • Data sources & limitations", "06 • مصادر البيانات والحدود"), listOf(
                            "${tr("Generated at", "وقت إنشاء التقرير")}: ${data.optString("generated_at")}",
                            "${tr("Sources", "المصادر")}: ${arrValues(data.optJSONArray("data_sources") ?: org.json.JSONArray()).joinToString()}",
                            tr("No basic warning is not the same as safe. Live phishing/malware reputation, page content, TLS certificate, and redirect checks are not available yet.", "عدم ظهور تحذير أساسي لا يعني أن الرابط آمن. فحص سمعة التصيّد والبرمجيات الخبيثة المباشر ومحتوى الصفحة وشهادة TLS والتحويلات غير متاح بعد.")
                        ))
                    }
                }
            }.start()
        }
    }

    private fun buildBannedScreen() {
        val root = pageRoot(tr("Account access restricted", "تم تقييد الحساب"))
        root.addView(infoCard(tr(
            "This account is currently banned. All ASSAF services are disabled except Contact Us and submitting an appeal.",
            "هذا الحساب محظور حاليًا. تم إيقاف خدمات ASSAF باستثناء التواصل معنا وتقديم الطعن."
        )), wrap())
        if (banReason.isNotBlank()) {
            root.addView(infoCard(tr("Reason: $banReason", "سبب الحظر: $banReason")),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }
        root.addView(primaryButton(tr("Contact Us", "تواصل معنا")).apply {
            setOnClickListener { goTo("contact") }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(18) })
        root.addView(TextView(this).apply {
            text = tr("You can contact the ASSAF team and submit an appeal for review.", "يمكنك التواصل مع فريق ASSAF وتقديم طعن لمراجعته.")
            textSize = 13f; setTextColor(muted); gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }, wrap())
    }

    private fun getGuestSupportToken(): String {
        return prefs.getString("guest_support_token", null)
            ?: UUID.randomUUID().toString().replace("-", "").also {
                prefs.edit().putString("guest_support_token", it).apply()
            }
    }

    private fun buildContactScreen() {
        val root = pageRoot(tr("Contact Us", "تواصل معنا"))
        root.addView(infoCard(tr("Send a message to the ASSAF administration.", "أرسل رسالة إلى إدارة ASSAF.")), wrap())
        val message = EditText(this).apply {
            hint = tr("Write your message here…", "اكتب رسالتك هنا…")
            setTextColor(white); setHintTextColor(muted); textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4; gravity = Gravity.TOP or Gravity.START
            setPadding(dp(14), dp(12), dp(14), dp(12)); background = rounded(panel, 14)
        }
        root.addView(message, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)).apply { topMargin = dp(14) })
        val send = primaryButton(tr("Send message", "إرسال الرسالة"))
        root.addView(send, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(12) })
        val chatList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun loadConversations() {
            val token = prefs.getString(KEY_API_TOKEN, null)
            val guestToken = if (token.isNullOrBlank()) getGuestSupportToken() else null
            chatList.removeAllViews()
            chatList.addView(TextView(this).apply { text = tr("Loading conversations…", "جارٍ تحميل المحادثات…"); setTextColor(muted) }, wrap())
            Thread {
                val result = runCatching { AssafApiClient.listSupportMessages(token, guestToken) }
                runOnUiThread {
                    chatList.removeAllViews()
                    result.onSuccess { rows ->
                        if (rows.length() == 0) chatList.addView(infoCard(tr("Your messages will appear here.", "رسائلك وردود الإدارة ستظهر هنا.")), wrap())
                        for (i in 0 until rows.length()) {
                            val row = rows.getJSONObject(i)
                            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); background = rounded(panel, 14) }
                            card.addView(TextView(this).apply { text = tr("Your message", "رسالتك"); setTextColor(blue); textSize = 13f; typeface = Typeface.DEFAULT_BOLD }, wrap())
                            card.addView(TextView(this).apply { text = row.optString("message"); setTextColor(white); textSize = 15f; setPadding(0, dp(6), 0, dp(10)) }, wrap())
                            val replies = row.optJSONArray("replies") ?: org.json.JSONArray()
                            for (j in 0 until replies.length()) {
                                val reply = replies.getJSONObject(j)
                                val ownerReply = reply.optString("sender") == "owner"
                                card.addView(TextView(this).apply {
                                    text = (if (ownerReply) tr("ASSAF support", "إدارة ASSAF") else tr("You", "أنت")) + ": " + reply.optString("message")
                                    setTextColor(if (ownerReply) green else muted); textSize = 14f
                                    setPadding(dp(10), dp(9), dp(10), dp(9)); background = rounded(panelLight, 10)
                                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
                            }
                            val replyInput = EditText(this).apply {
                                hint = tr("Reply in this conversation…", "اكتب ردّك هنا…")
                                setTextColor(white); setHintTextColor(muted); textSize = 14f
                                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                                minLines = 2; setPadding(dp(10), dp(8), dp(10), dp(8)); background = rounded(panelLight, 10)
                            }
                            card.addView(replyInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)).apply { topMargin = dp(10) })
                            val replyButton = primaryButton(tr("Send reply", "إرسال الرد"))
                            card.addView(replyButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8) })
                            replyButton.setOnClickListener {
                                val value = replyInput.text.toString().trim()
                                if (value.isBlank()) { replyInput.error = tr("Write a reply", "اكتب ردّك أولاً"); return@setOnClickListener }
                                replyButton.isEnabled = false
                                Thread {
                                    val sent = runCatching { AssafApiClient.replyToSupport(token, row.optString("id"), value, guestToken) }
                                    runOnUiThread {
                                        replyButton.isEnabled = true
                                        sent.onSuccess { loadConversations() }.onFailure { Toast.makeText(this, it.message ?: tr("Could not send reply", "تعذّر إرسال الرد"), Toast.LENGTH_LONG).show() }
                                    }
                                }.start()
                            }
                            chatList.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
                        }
                    }.onFailure { chatList.addView(infoCard(tr("Could not load conversations: ", "تعذّر تحميل المحادثات: ") + (it.message ?: "")), wrap()) }
                }
            }.start()
        }
        send.setOnClickListener {
            val token = prefs.getString(KEY_API_TOKEN, null)
            val guestToken = if (token.isNullOrBlank()) getGuestSupportToken() else null
            val value = message.text.toString().trim()
            if (value.length < 5) { message.error = tr("Write at least 5 characters", "اكتب 5 أحرف على الأقل"); return@setOnClickListener }
            send.isEnabled = false
            Thread {
                val result = runCatching { AssafApiClient.submitContact(value, token, guestToken) }
                runOnUiThread {
                    send.isEnabled = true
                    result.onSuccess {
                        message.text.clear()
                        Toast.makeText(this, tr("Message sent to ASSAF support", "تم إرسال رسالتك إلى دعم ASSAF"), Toast.LENGTH_LONG).show()
                        loadConversations()
                    }.onFailure { Toast.makeText(this, it.message ?: tr("Could not send message", "تعذّر إرسال الرسالة"), Toast.LENGTH_LONG).show() }
                }
            }.start()
        }
        root.addView(TextView(this).apply { text = tr("Your conversations", "محادثاتك مع الإدارة"); textSize = 19f; typeface = Typeface.DEFAULT_BOLD; setTextColor(white); setPadding(0, dp(22), 0, dp(10)) }, wrap())
        root.addView(smallButton().apply { text = tr("Refresh conversations", "تحديث المحادثات"); setOnClickListener { loadConversations() } },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { bottomMargin = dp(10) })
        root.addView(chatList, wrap())
        root.addView(smallButton().apply {
            text = tr("Submit an appeal against the ban", "تقديم طعن على الحظر")
            setOnClickListener { goTo("appeal") }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(10) })
        loadConversations()
    }

    private fun buildAppealScreen() {
        val root = pageRoot(tr("Appeal the ban", "تقديم طعن على الحظر"))
        root.addView(infoCard(tr(
            "Explain why you believe the ban should be reviewed. The administration will review your appeal.",
            "اشرح لماذا تعتقد أن الحظر يحتاج إلى مراجعة. ستقوم الإدارة بمراجعة الطعن."
        )), wrap())
        val message = EditText(this).apply {
            hint = tr("Write the reasons for your appeal…", "اكتب أسباب الطعن هنا…")
            setTextColor(white); setHintTextColor(muted); textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6; gravity = Gravity.TOP or Gravity.START
            setPadding(dp(14), dp(12), dp(14), dp(12)); background = rounded(panel, 14)
        }
        root.addView(message, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(160)).apply { topMargin = dp(14) })
        val send = primaryButton(tr("Send appeal", "إرسال الطعن"))
        root.addView(send, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(12) })
        send.setOnClickListener {
            val value = message.text.toString().trim()
            if (value.length < 5) {
                message.error = tr("Write at least 5 characters", "اكتب 5 أحرف على الأقل")
                return@setOnClickListener
            }
            val token = prefs.getString(KEY_API_TOKEN, null)
            if (token.isNullOrBlank()) {
                Toast.makeText(this, tr("Sign in to submit an appeal", "سجّل الدخول لتقديم الطعن"), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            send.isEnabled = false
            Thread {
                val result = runCatching { AssafApiClient.submitAppeal(token, value) }
                runOnUiThread {
                    send.isEnabled = true
                    result.onSuccess {
                        message.text.clear()
                        Toast.makeText(this, tr("Appeal submitted for review", "تم إرسال الطعن للمراجعة"), Toast.LENGTH_LONG).show()
                    }.onFailure {
                        Toast.makeText(this, it.message ?: tr("Could not submit appeal", "تعذّر إرسال الطعن"), Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
        root.addView(smallButton().apply {
            text = tr("Contact Us", "تواصل معنا")
            setOnClickListener { goTo("contact") }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(10) })
    }

    private fun buildWifiScreen() {
        val root = pageRoot(tr("Wi-Fi Check", "فحص الواي فاي"))
        val connected = try {
            val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val network = cm.activeNetwork
            val caps = network?.let { cm.getNetworkCapabilities(it) }
            caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        } catch (_: Exception) { false }
        root.addView(infoCard(tr(
            if (connected) "Wi-Fi connection detected." else "No active Wi-Fi connection detected.",
            if (connected) "تم اكتشاف اتصال واي فاي." else "لم يتم اكتشاف اتصال واي فاي نشط."
        )), wrap())
        root.addView(infoCard(tr(
            "ASSAF cannot guarantee a network is safe from connection status alone. Check the router password and use trusted networks.",
            "لا يستطيع ASSAF ضمان أمان الشبكة من حالة الاتصال وحدها. تأكد من كلمة مرور الراوتر واستخدم الشبكات الموثوقة."
        )), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
    }

    private fun buildProfileScreen() {
        val root = pageRoot(tr("Personal profile", "الملف الشخصي"))
        root.addView(infoCard(tr("ASSAF User", "مستخدم ASSAF")), wrap())
        root.addView(infoCard(tr(
            "Manage your language and protection settings on this device.",
            "أدر إعدادات اللغة والحماية على هذا الجهاز."
        )), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        root.addView(primaryButton(tr("Settings", "الإعدادات")).apply {
            setOnClickListener { goTo("settings") }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(16) })
    }

    private fun buildEarnScreen() {
        val root = pageRoot(tr("Earn with us", "اكسب معنا"))

        val balanceCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = GradientDrawable().apply {
                setColor(Color.rgb(19, 27, 46))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.rgb(59, 130, 246))
            }
        }

        balanceCard.addView(TextView(this).apply {
            text = tr("Your current balance", "رصيدك الحالي")
            textSize = 14f
            setTextColor(muted)
            gravity = Gravity.CENTER
        }, wrap())

        balanceCard.addView(TextView(this).apply {
            text = tr("Not available yet", "غير متاح حالياً")
            textSize = 25f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(59, 130, 246))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }, wrap())

        balanceCard.addView(TextView(this).apply {
            text = tr(
                "Your real earnings balance will appear here after the rewards wallet is connected.",
                "سيظهر رصيد أرباحك الحقيقي هنا بعد ربط محفظة الأرباح."
            )
            textSize = 12f
            setTextColor(muted)
            gravity = Gravity.CENTER
        }, wrap())

        balanceCard.addView(primaryButton(tr("Request payout", "طلب سحب الأرباح")).apply {
            isEnabled = false
            alpha = 0.55f
            setOnClickListener {
                Toast.makeText(this@MainActivity,
                    tr("Payout service is not connected yet.", "خدمة السحب غير مربوطة حتى الآن."),
                    Toast.LENGTH_LONG).show()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply {
            topMargin = dp(14)
        })

        root.addView(balanceCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(20) })

        val offersCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(18))
            background = GradientDrawable().apply {
                setColor(Color.rgb(19, 27, 46))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.rgb(30, 58, 138))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(tr("Tasks and offers", "المهام والعروض"))
                    .setMessage(tr(
                        "The rewards provider has not been connected yet. Tasks will appear here after the offerwall and reward verification are configured.",
                        "لم يتم ربط مزوّد المهام والعروض بعد. ستظهر المهام هنا بعد إعداد منصة العروض والتحقق من المكافآت."
                    ))
                    .setPositiveButton(tr("OK", "حسناً"), null)
                    .show()
            }
        }

        offersCard.addView(TextView(this).apply {
            text = "▣"
            textSize = 28f
            setTextColor(Color.rgb(59, 130, 246))
            setPadding(0, 0, dp(14), 0)
        }, wrap())

        val offerText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        offerText.addView(TextView(this).apply {
            text = tr("Daily tasks and offers", "المهام والعروض اليومية")
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(white)
        }, wrap())
        offerText.addView(TextView(this).apply {
            text = tr(
                "Offers and rewards will be shown here when the service is connected",
                "ستظهر العروض والمكافآت هنا بعد ربط الخدمة"
            )
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(3), 0, 0)
        }, wrap())

        offersCard.addView(offerText, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        offersCard.addView(TextView(this).apply {
            text = "‹"
            textSize = 25f
            setTextColor(muted)
        }, wrap())

        root.addView(offersCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun buildPlusScreen() {
        val root = pageRoot(tr("ASSAF Plus", "عساف بلس"))
        root.addView(infoCard(tr(
            "Premium features and subscriptions are not active yet.",
            "ميزات الاشتراك والدفع غير مفعّلة حتى الآن."
        )), wrap())
        root.addView(infoCard(tr(
            "No payment will be taken from this screen. Subscription management will be added after the service is implemented.",
            "لن يتم تحصيل أي دفعة من هذه الشاشة. ستتم إضافة إدارة الاشتراك بعد تنفيذ الخدمة."
        )), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
    }

    // Official ASSAF home screen with the four approved tools.
    // ASSAF home screen: native Kotlin UI, connected to the existing VPN service.
    private fun buildHomeScreen() {
        val active = prefs.getBoolean(FirewallVpnService.KEY_FIREWALL_ACTIVE, false)
        val selected = prefs.getStringSet(FirewallVpnService.KEY_BLOCKED_PACKAGES, emptySet()).orEmpty()
        val mode = prefs.getInt(FirewallVpnService.KEY_CONTROL_MODE, FirewallVpnService.MODE_INDIVIDUAL)
        val blockedCount = if (mode == FirewallVpnService.MODE_INDIVIDUAL) selected.size else 0
        setContent {
            AssafTheme {
                MainScreen(
                    isProtectionActive = active,
                    blockedCount = blockedCount,
                    selectedCount = selected.size,
                    onToggleProtection = {
                        if (prefs.getBoolean(FirewallVpnService.KEY_FIREWALL_ACTIVE, false)) {
                            startService(Intent(this@MainActivity, FirewallVpnService::class.java).setAction(FirewallVpnService.ACTION_STOP))
                        } else {
                            requestVpnAndStart()
                        }
                        Handler(Looper.getMainLooper()).postDelayed({ buildUi() }, 700)
                    },
                    onManageApps = { goTo("apps") },
                    onCheckLinks = { goTo("link") },
                    onContact = { goTo("contact") },
                    onEarn = { goTo("earn") }
                )
            }
        }
    }

    private fun buildTappingScreen() {
        val root = pageRoot(tr("ASSAF TikTok Tapper", "مساعد تكبيس تيك توك"))
        root.addView(infoCard(tr(
            "This tool sends real screen taps using Android Accessibility. Enable the ASSAF tap service and overlay permission, open TikTok, drag the floating target onto the exact place you want to tap, then press START. Use only where automated tapping is allowed.",
            "هذه الأداة ترسل نقرات فعلية للشاشة عبر تسهيلات الاستخدام في أندرويد. فعّل خدمة التكبيس العائمة وصلاحية الظهور فوق التطبيقات، ثم افتح تيك توك واسحب مؤشر الهدف إلى المكان المطلوب واضغط ابدأ. استخدمها فقط حيث يُسمح بالتكبيس الآلي."
        )), wrap())

        val serviceStatus = infoCard("")
        fun refreshStatus() {
            val accessibilityOn = AssafTapAccessibilityService.isEnabled(this)
            val overlayOn = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
            serviceStatus.text = tr(
                "Accessibility: " + if (accessibilityOn) "Enabled" else "Not enabled" + "\nOverlay permission: " + if (overlayOn) "Granted" else "Required",
                "تسهيلات الاستخدام: " + if (accessibilityOn) "مفعّلة" else "غير مفعّلة" + "\nصلاحية الظهور فوق التطبيقات: " + if (overlayOn) "ممنوحة" else "مطلوبة"
            )
        }
        refreshStatus()
        root.addView(serviceStatus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })

        root.addView(primaryButton(tr("1. Enable tap accessibility", "1. تفعيل صلاحية التكبيس")).apply {
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this@MainActivity, tr("Find ASSAF Tap Service and turn it on, then return here.", "ابحث عن خدمة تكبيس ASSAF وفعّلها ثم ارجع إلى هنا."), Toast.LENGTH_LONG).show()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { bottomMargin = dp(8) })

        root.addView(primaryButton(tr("2. Allow display over other apps", "2. السماح بالظهور فوق التطبيقات")).apply {
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    Toast.makeText(this@MainActivity, tr("Overlay permission is already allowed.", "صلاحية الظهور فوق التطبيقات مفعّلة بالفعل."), Toast.LENGTH_SHORT).show()
                }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { bottomMargin = dp(8) })

        root.addView(primaryButton(tr("3. Show floating target and controls", "3. إظهار مؤشر الهدف وأزرار التحكم")).apply {
            setOnClickListener {
                refreshStatus()
                if (!AssafTapAccessibilityService.isEnabled(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, tr("Enable ASSAF Tap Service first.", "فعّل خدمة تكبيس ASSAF أولاً."), Toast.LENGTH_LONG).show()
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(this@MainActivity, tr("Allow overlay permission first.", "اسمح بصلاحية الظهور فوق التطبيقات أولاً."), Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    startService(Intent(this@MainActivity, AssafFloatingCounterService::class.java))
                    Toast.makeText(this@MainActivity, tr("Floating target ready. Open TikTok and drag the blue target onto the desired point.", "مؤشر التكبيس جاهز. افتح تيك توك واسحب الهدف الأزرق إلى المكان المطلوب."), Toast.LENGTH_LONG).show()
                }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        root.addView(TextView(this).apply {
            text = tr("Fixed speed: up to 15 taps/second. Actual speed depends on the phone and Android. The counter shows completed tap gestures, not likes or views.",
                "السرعة المحددة: حتى 15 نقرة/ثانية. السرعة الفعلية تعتمد على الهاتف وأندرويد. العداد يحسب النقرات المنفذة، وليس الإعجابات أو المشاهدات.")
            textSize = 13f; setTextColor(muted); setPadding(0, dp(14), 0, 0)
        }, wrap())
    }

    // Applications screen redesigned from the supplied ASSAF layout.
    // It reuses the existing FirewallVpnService to avoid creating a second VPN.
    private fun buildAppsScreen() {
        val scroll = ScrollView(this).apply {
            setFillViewport(true)
            setBackgroundColor(Color.rgb(9, 17, 32))
            isVerticalScrollBarEnabled = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
            setBackgroundColor(Color.rgb(9, 17, 32))
            layoutDirection = if (arabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        scroll.addView(root)

        // ASSAF brand header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = if (arabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        header.addView(smallButton().apply {
            text = "☰"
            textSize = 24f
            background = rounded(Color.TRANSPARENT, 12)
            setOnClickListener { goTo("home") }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        brand.addView(TextView(this).apply {
            text = "🛡  ASSAF"
            textSize = 27f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            letterSpacing = 0.04f
        }, wrap())
        brand.addView(TextView(this).apply {
            text = "YOUR INTERNET. YOUR CONTROL."
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(0, 210, 255))
        }, wrap())
        header.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = "🛡"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(0, 220, 255))
        }, LinearLayout.LayoutParams(dp(44), dp(48)))
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))

        // Large protection status panel
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(3, 20, 50), Color.rgb(0, 42, 91), Color.rgb(2, 18, 47))).apply {
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.rgb(0, 157, 255))
            }
        }
        statusCard.addView(TextView(this).apply {
            text = "🛡️"
            textSize = 43f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(64), dp(76)))
        val statusColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        statusTitle = TextView(this).apply {
            text = tr("PROTECTION ACTIVE", "الحماية مفعلة")
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(0, 238, 145))
        }
        statusText = TextView(this).apply {
            text = tr("Full control over your app connections", "تحكم كامل في اتصالات تطبيقاتك")
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(225, 237, 255))
        }
        statusColumn.addView(statusTitle, wrap())
        statusColumn.addView(statusText, wrap())
        statusCard.addView(statusColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statusCard.addView(TextView(this).apply {
            text = "▂▅▇\n🌐"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(0, 190, 255))
        }, LinearLayout.LayoutParams(dp(54), dp(70)))
        root.addView(statusCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(112)).apply {
            topMargin = dp(12)
            bottomMargin = dp(12)
        })

        root.addView(TextView(this).apply {
            text = tr("Manage applications  ▦", "إدارة التطبيقات  ▦")
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = if (arabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        fun addModeOption(title: String, subtitle: String, modeValue: Int) {
            val selectedMode = prefs.getInt(FirewallVpnService.KEY_CONTROL_MODE, FirewallVpnService.MODE_INDIVIDUAL)
            val chosen = selectedMode == modeValue
            val option = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(7), dp(8), dp(7), dp(8))
                background = GradientDrawable().apply {
                    setColor(if (chosen) Color.rgb(37, 99, 235) else Color.rgb(224, 242, 254))
                    cornerRadius = dp(18).toFloat()
                    setStroke(dp(if (chosen) 2 else 1),
                        if (chosen) Color.rgb(0, 210, 255) else Color.rgb(45, 93, 157))
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (prefs.getInt(FirewallVpnService.KEY_CONTROL_MODE, FirewallVpnService.MODE_INDIVIDUAL) != modeValue) {
                        prefs.edit().putInt(FirewallVpnService.KEY_CONTROL_MODE, modeValue).apply()
                        if (prefs.getBoolean(FirewallVpnService.KEY_FIREWALL_ACTIVE, false)) requestVpnAndStart()
                        buildUi()
                    }
                }
            }
            option.addView(TextView(this).apply {
                text = title
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(if (chosen) Color.WHITE else Color.rgb(210, 225, 248))
            }, wrap())
            option.addView(TextView(this).apply {
                text = subtitle
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(if (chosen) Color.rgb(219, 234, 254) else Color.rgb(30, 58, 138))
            }, wrap())
            modeRow.addView(option, LinearLayout.LayoutParams(0, dp(76), 1f).apply {
                marginStart = dp(5)
                marginEnd = dp(5)
            })
        }
        addModeOption(tr("Exceptions mode", "وضع الاستثناءات"),
            tr("Allow selected apps only", "اسمح للتطبيقات المحددة فقط"), FirewallVpnService.MODE_EXCEPTIONS)
        addModeOption(tr("Individual control", "التحكم الفردي"),
            tr("Disconnect a selected app", "فصل الإنترنت عن تطبيق محدد"), FirewallVpnService.MODE_INDIVIDUAL)
        root.addView(modeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(84)).apply {
            topMargin = dp(6)
            bottomMargin = dp(8)
        })

        // Keep existing search/filter state initialized, but hide it to match the approved reference screen.
        countText = TextView(this)
        searchBox = EditText(this)
        countText.visibility = View.GONE
        searchBox.visibility = View.GONE

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(3), 0, dp(6))
        }
        root.addView(listContainer, wrap())

        root.addView(TextView(this).apply {
            text = tr("TIP: You can switch between the two control modes at any time.", "ملاحظة: يمكنك التبديل بين الوضعين في أي وقت")
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(235, 244, 255))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(4, 38, 86), Color.rgb(0, 61, 125))).apply {
                cornerRadius = dp(17).toFloat()
                setStroke(dp(1), Color.rgb(0, 174, 255))
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
            bottomMargin = dp(10)
        })
        root.addView(TextView(this).apply {
            text = "━━━━━━━━  MR. ASSAF  ━━━━━━━━"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            letterSpacing = 0.04f
            setTextColor(Color.rgb(220, 230, 248))
            setPadding(0, dp(4), 0, dp(8))
        }, wrap())
        setContentView(scroll)
    }

    private fun loadApps() {
        if (!::listContainer.isInitialized || !::statusText.isInitialized || !::countText.isInitialized) return
        listContainer.removeAllViews()
        appRows.clear()
        val selected = prefs.getStringSet(FirewallVpnService.KEY_BLOCKED_PACKAGES, emptySet()).orEmpty()
        val mode = prefs.getInt(FirewallVpnService.KEY_CONTROL_MODE, FirewallVpnService.MODE_INDIVIDUAL)
        val installedApps = packageManager.getInstalledApplications(0)
            .filter { it.packageName != packageName && packageManager.getLaunchIntentForPackage(it.packageName) != null }
            .map { info ->
                val label = runCatching { packageManager.getApplicationLabel(info).toString() }.getOrDefault(info.packageName)
                Triple(info, label, info.packageName)
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.second })

        installedApps.forEachIndexed { index, item ->
            val info = item.first
            val label = item.second
            val pkg = item.third
            val isBlocked = if (mode == FirewallVpnService.MODE_INDIVIDUAL) pkg in selected else pkg !in selected

            val tileRow: LinearLayout = if (index % 2 == 0) {
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                    layoutDirection = if (arabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                }.also { row ->
                    listContainer.addView(row, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) })
                }
            } else {
                listContainer.getChildAt(listContainer.childCount - 1) as LinearLayout
            }

            val tile = layoutInflater.inflate(R.layout.item_app_control, tileRow, false) as LinearLayout
            val icon = tile.findViewById<ImageView>(R.id.imgAppIcon)
            val name = tile.findViewById<TextView>(R.id.tvAppName)
            val appStatus = tile.findViewById<TextView>(R.id.tvAppStatus)
            val appSwitch = tile.findViewById<android.widget.Switch>(R.id.switchAppControl)

            icon.setImageDrawable(runCatching { packageManager.getApplicationIcon(info) }.getOrNull())
            name.text = label
            appStatus.text = if (isBlocked) tr("Disconnected", "مفصول") else tr("Connected", "متصل")
            appStatus.setBackgroundResource(
                if (isBlocked) R.drawable.bg_button_red else R.drawable.bg_status_connected
            )
            if (isBlocked) {
                appStatus.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            } else {
                appStatus.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_check_small, 0, 0, 0)
            }

            appSwitch.setOnCheckedChangeListener(null)
            // Switch ON means internet connected; OFF means internet disconnected.
            appSwitch.isChecked = !isBlocked
            appSwitch.setOnCheckedChangeListener { _, checked ->
                applyAppBlockState(pkg, label, !checked)
            }

            tileRow.addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            })
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
        val selectionDescription = if (mode == FirewallVpnService.MODE_INDIVIDUAL)
            tr("apps selected to block", "تطبيقات محددة للفصل") else tr("allowed exceptions", "تطبيقات مستثناة")
        countText.text = tr(
            "${installedApps.size} apps found • ${selected.size} ${selectionDescription}",
            "التطبيقات: ${installedApps.size} • ${selected.size} ${selectionDescription}"
        )
        val firewallActive = prefs.getBoolean(FirewallVpnService.KEY_FIREWALL_ACTIVE, false)
        if (::statusTitle.isInitialized) {
            statusTitle.text = if (firewallActive) tr("PROTECTION ACTIVE", "الحماية مفعلة")
                else tr("PROTECTION INACTIVE", "الحماية غير مفعلة")
            statusTitle.setTextColor(if (firewallActive) Color.rgb(0, 238, 145) else Color.rgb(255, 95, 105))
        }
        statusText.text = if (firewallActive) {
            tr("Full control over your app connections", "تحكم كامل في اتصالات تطبيقاتك")
        } else {
            tr("Turn on protection to apply app controls", "فعّل الحماية لتطبيق إعدادات التطبيقات")
        }
    }

    private fun applyAppBlockState(packageName: String, appName: String, shouldBlock: Boolean) {
        val mode = prefs.getInt(FirewallVpnService.KEY_CONTROL_MODE, FirewallVpnService.MODE_INDIVIDUAL)
        val selected = prefs.getStringSet(FirewallVpnService.KEY_BLOCKED_PACKAGES, emptySet()).orEmpty()
        val updated = selected.toMutableSet()
        if (mode == FirewallVpnService.MODE_INDIVIDUAL) {
            if (shouldBlock) updated.add(packageName) else updated.remove(packageName)
        } else {
            if (shouldBlock) updated.remove(packageName) else updated.add(packageName)
        }
        prefs.edit().putStringSet(FirewallVpnService.KEY_BLOCKED_PACKAGES, updated).apply()
        if (mode == FirewallVpnService.MODE_INDIVIDUAL) {
            prefs.getString(KEY_API_TOKEN, null)?.let { token ->
                Thread { runCatching { AssafApiClient.saveSetting(token, packageName, shouldBlock) } }.start()
            }
        }
        if (mode == FirewallVpnService.MODE_INDIVIDUAL && updated.isEmpty()) {
            startService(Intent(this, FirewallVpnService::class.java).setAction(FirewallVpnService.ACTION_STOP))
        } else {
            requestVpnAndStart()
        }
        loadApps()
        Toast.makeText(this,
            if (shouldBlock) tr("Internet blocked for $appName", "تم فصل الإنترنت عن $appName")
            else tr("Internet restored for $appName", "تمت إعادة الإنترنت إلى $appName"),
            Toast.LENGTH_SHORT
        ).show()
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
        val mode = prefs.getInt(FirewallVpnService.KEY_CONTROL_MODE, FirewallVpnService.MODE_INDIVIDUAL)
        if (mode == FirewallVpnService.MODE_INDIVIDUAL && selected.isEmpty()) {
            Toast.makeText(this, tr("Select at least one app to block", "اختر تطبيقًا واحدًا على الأقل لحجبه"), Toast.LENGTH_LONG).show()
            return
        }
        val intent = VpnService.prepare(this)
        if (intent != null) { pendingStart = true; @Suppress("DEPRECATION") startActivityForResult(intent, VPN_REQUEST) }
        else startFirewall()
    }

    private fun startFirewall() {
        val intent = Intent(this, FirewallVpnService::class.java).setAction(FirewallVpnService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        if (::statusText.isInitialized) statusText.text = tr("Protection start requested • Check VPN status.", "تم طلب بدء الحماية • تحقق من حالة VPN.")
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
        background = getDrawable(R.drawable.bg_card_rounded)
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
        background = getDrawable(R.drawable.bg_card_rounded)
        isAllCaps = false
    }

    private fun smallButton() = Button(this).apply {
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(white)
        background = getDrawable(R.drawable.bg_card_rounded)
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
        private const val KEY_FAVORITE_APPS = "favorite_apps"
    }
}

private class SimpleTextWatcher(private val changed: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed(s?.toString().orEmpty()) }
    override fun afterTextChanged(s: android.text.Editable?) = Unit
}


@Composable
private fun AssafTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = ComposeColor(0xFF050B14),
            surface = ComposeColor(0xFF0B162C)
        ),
        content = content
    )
}

@Composable
private fun MainScreen(
    isProtectionActive: Boolean,
    blockedCount: Int,
    selectedCount: Int,
    onToggleProtection: () -> Unit,
    onManageApps: () -> Unit,
    onCheckLinks: () -> Unit,
    onContact: () -> Unit,
    onEarn: () -> Unit
) {
    val muted = ComposeColor(0xFF7B9CC6)
    val blue = ComposeColor(0xFF007AFF)
    val green = ComposeColor(0xFF00E676)
    val panel = ComposeColor(0xFF08142A)
    val line = ComposeColor(0xFF16325C)

    Box(
        modifier = Modifier.fillMaxSize().background(
            androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(ComposeColor(0xFF0A1D3A), ComposeColor(0xFF040A14), ComposeColor(0xFF010408))
            )
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(8.dp))
            Image(
                painter = painterResource(id = R.drawable.ic_assaf_logo),
                contentDescription = "Assaf Logo",
                modifier = Modifier.size(150.dp).padding(top = 4.dp)
            )
            Text("ASSAF", color = ComposeColor.White, fontSize = 24.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 3.sp,
                modifier = Modifier.padding(top = 4.dp))
            Text("YOUR INTERNET. YOUR CONTROL.", color = muted,
                fontSize = 9.sp, letterSpacing = 1.5.sp,
                modifier = Modifier.padding(top = 2.dp))
            Text("حمايتك تحت سيطرتك", color = ComposeColor.White,
                fontSize = 19.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 22.dp))
            Text("تحكّم باتصال تطبيقاتك بالإنترنت بسهولة", color = muted,
                fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = panel),
                border = androidx.compose.foundation.BorderStroke(1.dp, line)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(46.dp).background(ComposeColor(0xFF0F254A), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                modifier = Modifier.size(18.dp),
                                color = if (isProtectionActive) green else blue,
                                shape = CircleShape
                            ) {}
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("حالة الحماية", color = ComposeColor.White,
                                fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 2.dp)) {
                                Box(Modifier.size(6.dp).background(
                                    if (isProtectionActive) green else ComposeColor(0xFFB0BEC5), CircleShape))
                                Spacer(Modifier.width(6.dp))
                                Text(if (isProtectionActive) "مفعلة" else "غير مفعلة",
                                    color = if (isProtectionActive) green else ComposeColor(0xFFB0BEC5),
                                    fontSize = 12.sp)
                            }
                        }
                    }
                    Button(
                        onClick = onToggleProtection,
                        modifier = Modifier.height(44.dp),
                        shape = RoundedCornerShape(22.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = blue)
                    ) {
                        Text(if (isProtectionActive) "إيقاف الحماية" else "تفعيل الحماية",
                            color = ComposeColor.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = panel),
                border = androidx.compose.foundation.BorderStroke(1.dp, line)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(blockedCount.toString(), color = ComposeColor.White,
                            fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("تطبيقات محظورة", color = muted, fontSize = 11.sp,
                            modifier = Modifier.padding(top = 2.dp))
                    }
                    Box(Modifier.width(1.dp).height(30.dp).background(line))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(selectedCount.toString(), color = ComposeColor.White,
                            fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("تطبيقات محددة", color = muted, fontSize = 11.sp,
                            modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            OptionCard("إدارة التطبيقات",
                "اختر التطبيقات التي تريد التحكم باتصالها بالإنترنت",
                Icons.Default.GridOn, onManageApps)
            Spacer(Modifier.height(10.dp))
            OptionCard("فحص الروابط",
                "تحقق من أمان أي رابط قبل فتحه",
                Icons.Default.Link, onCheckLinks)
            Spacer(Modifier.height(10.dp))
            OptionCard("تواصل معنا",
                "أرسل رسالة مباشرة إلى مالك التطبيق",
                Icons.Default.SupportAgent, onContact)
            Spacer(Modifier.height(10.dp))
            OptionCard("اكسب معنا",
                "أنجز المهام واجمع رصيدك",
                Icons.Default.MonetizationOn, onEarn)

            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(Modifier.width(50.dp).height(1.dp).background(line))
                Text("M R .   A S S A F", color = ComposeColor(0xFF567095),
                    fontSize = 11.sp, letterSpacing = 2.sp,
                    modifier = Modifier.padding(horizontal = 12.dp))
                Box(Modifier.width(50.dp).height(1.dp).background(line))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun OptionCard(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ComposeColor(0xFF0B162C))) {
        Row(Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = ComposeColor(0xFF0084FF), modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = ComposeColor.White, fontSize = 14.sp,
                    fontWeight = FontWeight.Bold)
                Text(subtitle, color = ComposeColor(0xFF8AA2C0), fontSize = 11.sp)
            }
            Icon(Icons.Default.ChevronRight, null,
                tint = ComposeColor(0xFF8AA2C0), modifier = Modifier.size(16.dp))
        }
    }
}
